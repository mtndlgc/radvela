#include "dab_control.h"

#include <algorithm>
#include <cstring>

#include "dab_tables.h"
#include "log.h"

#ifdef RADVELA_SYSTEM_AAC_ONLY
extern std::atomic<bool> gNoAacDecoder;  // core/aac_system.cpp
#endif

namespace oto {

namespace {
constexpr int kTickMs = 500;
constexpr int kServicesSettleMs = 2500;  // bu süre yeni servis gelmezse liste tamam sayılır
constexpr float kInvScale = 1.0f / 128.0f;

std::string codingOf(const std::string& mode) {
    if (mode.find("HE-AAC v2") != std::string::npos || mode.find("HE-AACv2") != std::string::npos) return "HE-AACv2";
    if (mode.find("HE-AAC") != std::string::npos) return "HE-AAC";
    if (mode.find("AAC") != std::string::npos) return "AAC-LC";
    return "MP2";
}
}  // namespace

int32_t FifoInput::getSamples(DSPCOMPLEX* buffer, int32_t size) {
    int32_t done = 0;
    while (done < size) {
        if (pos_ >= len_) {
            pos_ = 0;
            len_ = fifo_.readSome(cache_.data(), cache_.size()) & ~static_cast<size_t>(1);
            if (len_ == 0) break;
        }
        const int32_t n = std::min<int32_t>(size - done, static_cast<int32_t>((len_ - pos_) / 2));
        for (int32_t i = 0; i < n; ++i, pos_ += 2)
            buffer[done + i] = DSPCOMPLEX(cache_[pos_] * kInvScale, cache_[pos_ + 1] * kInvScale);
        done += n;
    }
    return done;
}

DabControl::DabControl(IqSource& src, SampleFifo& fifo, EngineListener& listener)
    : src_(src), fifo_(fifo), listener_(listener), input_(src, fifo) {}

DabControl::~DabControl() { shutdown(); }

bool DabControl::init() {
    if (running_) return true;
    {
        std::lock_guard<std::mutex> lk(qm_);
        quit_ = false;
        q_.clear();
    }
    running_ = true;
    src_.onTuned = [this](uint32_t hz) {
        Event e;
        e.type = Event::InputTuned;
        e.u32 = hz / 1000;
        post(e);
    };
    nextTick_ = std::chrono::steady_clock::now();
    worker_ = std::thread(&DabControl::workerLoop, this);
    return true;
}

void DabControl::shutdown() {
    if (!running_) return;
    src_.onTuned = nullptr;
    {
        std::lock_guard<std::mutex> lk(qm_);
        quit_ = true;
    }
    qcv_.notify_all();
    if (worker_.joinable()) worker_.join();
    rx_.reset();
    {
        std::lock_guard<std::mutex> lk(qm_);
        q_.clear();
    }
    fifo_.reset();
    freqKHz_ = 0;
    running_ = false;
}

// ----------------------------------------------------------------- olay kuyruğu

void DabControl::post(Event ev) {
    {
        std::lock_guard<std::mutex> lk(qm_);
        q_.push_back(ev);
    }
    qcv_.notify_one();
}

void DabControl::workerLoop() {
    for (;;) {
        Event ev;
        bool have = false;
        {
            std::unique_lock<std::mutex> lk(qm_);
            qcv_.wait_until(lk, nextTick_, [&] { return quit_ || !q_.empty(); });
            if (quit_) return;
            if (!q_.empty()) {
                ev = q_.front();
                q_.pop_front();
                have = true;
            }
        }
        if (have) handle(ev);
        const auto now = std::chrono::steady_clock::now();
        if (now >= nextTick_) {
            nextTick_ = now + std::chrono::milliseconds(kTickMs);
            tick();
        }
    }
}

void DabControl::handle(const Event& ev) {
    switch (ev.type) {
        case Event::ReqTune: onReqTune(ev.u32, ev.sid, ev.scids); break;
        case Event::ReqSelect:
            pending_.sid = ev.sid;
            pending_.scids = ev.scids;
            tryPlayPending();
            break;
        case Event::ReqIdle:
            stopReceiver();
            freqKHz_ = 0;
            pending_ = {};
            current_ = {};
            src_.stopStreaming();
            break;
        case Event::InputTuned: onInputTuned(ev.u32); break;
        case Event::Restart:
            // Topluluk yeniden yapılandırıldı: alt kanal adresleri değişmiş olabilir, servisi yeniden kur
            if (current_.sid) {
                pending_ = current_;
                tryPlayPending();
            }
            break;
    }
}

void DabControl::tick() {
    if (!rx_) return;
#ifdef RADVELA_SYSTEM_AAC_ONLY
    if (gNoAacDecoder.exchange(false)) listener_.onMessage(true, "@nat_no_aac");
#endif

    const int fibOk = fibOk_.exchange(0), fibErr = fibErr_.exchange(0);
    const int level = synced_ ? (fibOk > 0 ? 2 : 1) : 0;
    syncLevel_ = level;
    listener_.onDabStatus(snr_.load(), level);
    const int mscOk = mscOk_.exchange(0), mscErr = mscErr_.exchange(0), rsErr = rsErr_.exchange(0);
    listener_.onDabQuality(fibErr, mscOk, mscErr, rsErr);
    sumFibErr_ += fibErr;
    sumMscErr_ += mscErr;
    sumRsErr_ += rsErr;
    if (fibErr || mscErr || rsErr) logf("DAB hata: FIC=%d ses=%d RS=%d snr=%.1f kazanc=%.1f", fibErr, mscErr, rsErr, snr_.load(), src_.gainDb());
    if (++tickCount_ % 20 == 0)
        logf("DAB kalite toplam: FIC=%ld ses=%ld RS=%ld snr=%.1f tasma=%llu", sumFibErr_, sumMscErr_, sumRsErr_, snr_.load(),
             static_cast<unsigned long long>(fifo_.dropped()));
    {
        std::lock_guard<std::mutex> lk(sm_);
        if (level > scSync_) {
            scSync_ = level;
            scv_.notify_all();
        }
    }

    const std::string label = rx_->getEnsembleLabel().utf8_label();
    if (!label.empty() && label != ensLabel_) {
        ensLabel_ = label;
        listener_.onDabEnsemble(channel_, trimRight(label), rx_->getEnsembleId());
        std::lock_guard<std::mutex> lk(sm_);
        scEnsemble_ = true;
        scv_.notify_all();
    }

    const auto now = std::chrono::steady_clock::now();
    if (!ensLabel_.empty()) {
        for (const Service& s : rx_->getServiceList()) {
            const std::string name = s.serviceLabel.utf8_label();
            if (name.empty() || seen_.count(s.serviceId) || !rx_->serviceHasAudioComponent(s)) continue;
            bool aac = false;
            for (const auto& c : rx_->getComponents(s))
                if (c.transportMode() == TransportMode::Audio && c.audioType() == AudioServiceComponentType::DABPlus) aac = true;
            seen_.insert(s.serviceId);
            lastNewService_ = now;
            listener_.onDabService(channel_, s.serviceId, 0, trimRight(name), aac);
        }
    }
    if (!servicesReported_ && !seen_.empty() && now - lastNewService_ > std::chrono::milliseconds(kServicesSettleMs)) {
        servicesReported_ = true;
        listener_.onDabServicesComplete(channel_);
        std::lock_guard<std::mutex> lk(sm_);
        scServicesComplete_ = true;
        scv_.notify_all();
    }
    tryPlayPending();
}

// ----------------------------------------------------------------- istekler

void DabControl::tune(uint32_t freqKHz, uint32_t sid, uint8_t scids) {
    Event e;
    e.type = Event::ReqTune;
    e.u32 = freqKHz;
    e.sid = sid;
    e.scids = scids;
    post(e);
}

void DabControl::selectService(uint32_t sid, uint8_t scids) {
    Event e;
    e.type = Event::ReqSelect;
    e.sid = sid;
    e.scids = scids;
    post(e);
}

void DabControl::idle() {
    Event e;
    e.type = Event::ReqIdle;
    post(e);
}

void DabControl::stopReceiver() {
    rx_.reset();
    ensLabel_.clear();
    seen_.clear();
    servicesReported_ = false;
    synced_ = false;
    syncLevel_ = 0;
    fibOk_ = fibErr_ = mscOk_ = mscErr_ = rsErr_ = 0;
    snr_ = 0.0f;
    lastMode_.clear();
    lastRate_ = 0;
}

void DabControl::onReqTune(uint32_t freqKHz, uint32_t sid, uint8_t scids) {
    logf("DAB ayar istegi %u kHz sid=%08X (mevcut %u, alici=%d)", freqKHz, sid, freqKHz_, rx_ ? 1 : 0);
    if (freqKHz == freqKHz_ && rx_) {
        if (sid) {
            pending_.sid = sid;
            pending_.scids = scids;
            tryPlayPending();
        }
        return;
    }
    stopReceiver();
    freqKHz_ = freqKHz;
    current_ = {};
    pending_.sid = sid;
    pending_.scids = scids;
    channel_ = dabChannelName(freqKHz);
    {
        std::lock_guard<std::mutex> lk(sm_);
        scTunedKHz_ = 0;
        scEnsemble_ = false;
        scServicesComplete_ = false;
        scNoSignal_ = false;
        scSync_ = 0;
    }
    src_.tuneHz(freqKHz * 1000u);  // akış oturunca onTuned -> InputTuned
}

void DabControl::onInputTuned(uint32_t freqKHz) {
    logf("DAB giris ayarlandi %u kHz (istenen %u)", freqKHz, freqKHz_);
    if (freqKHz == 0 || freqKHz != freqKHz_) return;  // eski ayar
    stopReceiver();
    input_.clear();
    RadioReceiverOptions opts;
    rx_ = std::make_unique<RadioReceiver>(*this, input_, opts, 1);
    lastNewService_ = std::chrono::steady_clock::now();
    rx_->restart(scanMode_.load());
    listener_.onDabStatus(0.0f, 0);
    std::lock_guard<std::mutex> lk(sm_);
    scTunedKHz_ = freqKHz;
    scv_.notify_all();
}

void DabControl::tryPlayPending() {
    if (!rx_ || !pending_.sid) return;
    // Eski kayıtlarda SId'nin üst baytlarında ECC bulunabilir: 16 bitlik program SId'si üzerinden eşleştir
    for (const Service& s : rx_->getServiceList()) {
        if ((s.serviceId & 0xFFFFu) != (pending_.sid & 0xFFFFu)) continue;
        if (!rx_->serviceHasAudioComponent(s)) return;
        lastMode_.clear();
        lastRate_ = 0;
        if (!rx_->playSingleProgramme(*this, "", s)) return;  // alt kanal bilgisi henüz gelmedi: sonraki turda yeniden dene
        logf("DAB servis secildi SId=%08X", s.serviceId);
        current_ = pending_;
        pending_ = {};
        listener_.onDabLabel("");
        listener_.onDabSelected(current_.sid, current_.scids, trimRight(s.serviceLabel.utf8_label()));
        return;
    }
}

// ----------------------------------------------------------------- arka uç geri çağrıları

void DabControl::onSyncChange(char isSync) {
    synced_ = isSync != 0;
    if (!isSync) return;
    std::lock_guard<std::mutex> lk(sm_);
    scSync_ = std::max(scSync_, 1);
    scv_.notify_all();
}

void DabControl::onSignalPresence(bool isSignal) {
    if (isSignal) return;
    std::lock_guard<std::mutex> lk(sm_);
    scNoSignal_ = true;
    scv_.notify_all();
}

void DabControl::onFIBDecodeSuccess(bool crcCheckOk, const uint8_t*) {
    if (crcCheckOk)
        ++fibOk_;
    else
        ++fibErr_;
}

void DabControl::onRestartService() {
    Event e;
    e.type = Event::Restart;
    post(e);
}

void DabControl::onFrameErrors(int frameErrors) {
    if (frameErrors > 0)
        mscErr_ += frameErrors;
    else
        ++mscOk_;
}

void DabControl::onNewAudio(std::vector<int16_t>&& audioData, int sampleRate, const std::string& mode) {
    if (sampleRate <= 0 || audioData.empty()) return;
    if (mode != lastMode_ || sampleRate != lastRate_) {
        lastMode_ = mode;
        lastRate_ = sampleRate;
        listener_.onDabAudioParams(codingOf(mode), sampleRate, mode.find("Mono") == std::string::npos);
    }
    listener_.onAudio(audioData.data(), audioData.size() / 2, sampleRate);
}

// ----------------------------------------------------------------- tarama

void DabControl::scan(const std::atomic<bool>& cancel) {
    const auto& chans = dabChannels();
    const int total = static_cast<int>(chans.size());
    // Hızlı tur boş kanalları çabuk geçer ama zayıf sinyalde kilitlenme 20 sn'yi bulabilir: hiçbir şey bulunmazsa
    // ikinci tur, kanal başına uzun bekleyerek ve erken "sinyal yok" kararını yok sayarak yeniden tarar.
    bool found = false;
    for (int pass = 0; pass < 2 && !found && !cancel; ++pass) {
      const bool slow = pass == 1;
      scanMode_ = !slow;
      if (slow) logf("DAB tarama: hizli turda yayin bulunamadi, uzun tur basliyor");
      for (int i = 0; i < total && !cancel; ++i) {
        const auto& ch = chans[static_cast<size_t>(i)];
        listener_.onScanProgress(100 * i / total, ch.name);

        tune(ch.freqKHz);

        auto waitFor = [&](const std::function<bool()>& pred, int ms) {
            std::unique_lock<std::mutex> lk(sm_);
            const auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
            while (!pred() && !cancel) {
                if (scv_.wait_until(lk, std::min(end, std::chrono::steady_clock::now() + std::chrono::milliseconds(100))) ==
                        std::cv_status::timeout &&
                    std::chrono::steady_clock::now() >= end)
                    break;
            }
            return pred();
        };

        if (!waitFor([&] { return scTunedKHz_ == ch.freqKHz; }, 6000)) continue;  // ayar tamamlanmadı
        const bool synced = waitFor([&] { return scSync_ >= 1 || (!slow && scNoSignal_); }, slow ? 8000 : 4500);
        if (!synced || (!slow && scNoSignal_)) {  // sinyal yok
            logf("DAB tarama %s: senkron yok (AGC seviye=%.0f, kazanc %.1f dB)", ch.name, src_.signalLevel(), src_.gainDb());
            continue;
        }
        if (!waitFor([&] { return scEnsemble_; }, slow ? 25000 : 6000)) continue;  // topluluk bilgisi yok
        found = true;
        waitFor([&] { return scServicesComplete_; }, slow ? 15000 : 8000);
      }
    }
    scanMode_ = false;
    idle();
    listener_.onScanProgress(100, "");
}

}  // namespace oto
