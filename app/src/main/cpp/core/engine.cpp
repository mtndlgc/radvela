#include "engine.h"

#include <chrono>
#include <complex>
#include <vector>

#include "dab_tables.h"
#include "fm_scanner.h"
#include "log.h"

namespace oto {

RadioEngine::RadioEngine(EngineListener* out) : out_(out), fifo_(inputFifo()) {}

RadioEngine::~RadioEngine() {
    cancelScan();
    std::lock_guard<std::mutex> lk(ctl_);
    stopLocked();
    fm_.reset();
    dab_.reset();
    src_.reset();
}

void RadioEngine::attachSource(std::unique_ptr<IqSource> s) {
    s->onError = [this](const std::string& why) {
        if (out_) out_->onMessage(true, why);
        if (out_) out_->onConnection(false, why);
    };
    src_ = std::move(s);
    fm_ = std::make_unique<FmReceiver>(*src_, fifo_, *this);
    dab_ = std::make_unique<DabControl>(*src_, fifo_, *this);
    fm_->setForceMono(forceMono_);
    fm_->setDeemphasisMicros(deemph_);
    src_->setSoftwareAgc(agc_);
    src_->setPpm(ppm_);
    if (out_) out_->onConnection(true, src_->description());
}

#ifdef OTO_WITH_USB
bool RadioEngine::openUsb(int fd, std::string* error) {
    closeSource();
    std::lock_guard<std::mutex> lk(ctl_);
    auto usb = std::make_unique<RtlsdrUsbSource>(fifo_);
    if (!usb->open(fd, error)) return false;
    attachSource(std::move(usb));
    return true;
}
#endif

bool RadioEngine::openTcp(const std::string& host, int port, int timeoutMs, std::string* error) {
    closeSource();
    std::lock_guard<std::mutex> lk(ctl_);
    auto tcp = std::make_unique<RtlTcpSource>(fifo_);
    if (!tcp->open(host, port, timeoutMs, error)) return false;
    attachSource(std::move(tcp));
    return true;
}

void RadioEngine::closeSource() {
    cancelScan();
    std::lock_guard<std::mutex> lk(ctl_);
    if (!src_) return;
    stopLocked();
    fm_.reset();
    dab_.reset();
    src_.reset();
    fifo_.reset();
    if (out_) out_->onConnection(false, "");
}

bool RadioEngine::sourceOpen() const { return src_ && src_->isOpen(); }

void RadioEngine::stopLocked() {
    if (fm_) fm_->stop();
    if (dab_) dab_->shutdown();
    if (src_) src_->stopStreaming();
    mode_ = Mode::Idle;
}

void RadioEngine::stop() {
    cancelScan();
    std::lock_guard<std::mutex> lk(ctl_);
    stopLocked();
}

bool RadioEngine::startFm(int freqHz) {
    logf("startFm %d Hz", freqHz);
    cancelScan();
    std::lock_guard<std::mutex> lk(ctl_);
    if (!sourceOpen()) return false;
    if (dab_ && dab_->running()) dab_->shutdown();
    muted_ = false;
    mode_ = Mode::Fm;
    fm_->setForceMono(forceMono_);
    fm_->setDeemphasisMicros(deemph_);
    fm_->start(freqHz);
    return true;
}

bool RadioEngine::startDab(const std::string& channel, uint32_t sid, int scids) {
    logf("startDab %s sid=%08X scids=%d", channel.c_str(), sid, scids);
    cancelScan();
    std::lock_guard<std::mutex> lk(ctl_);
    if (!sourceOpen()) return false;
    const uint32_t kHz = dabChannelFreqKHz(channel);
    if (kHz == 0) return false;
    if (fm_ && fm_->running()) fm_->stop();
    if (!dab_->running() && !dab_->init()) {
        if (out_) out_->onMessage(true, "@nat_dab_core");
        return false;
    }
    muted_ = false;
    mode_ = Mode::Dab;
    dab_->tune(kHz, sid, static_cast<uint8_t>(scids));
    return true;
}

void RadioEngine::playDabService(uint32_t sid, int scids) {
    std::lock_guard<std::mutex> lk(ctl_);
    if (mode_ == Mode::Dab && dab_ && dab_->running()) dab_->selectService(sid, static_cast<uint8_t>(scids));
}

void RadioEngine::setAgc(bool on) {
    agc_ = on;
    if (src_) src_->setSoftwareAgc(on);
}
void RadioEngine::setGainIndex(int idx) {
    agc_ = false;
    if (src_) src_->setGainIndex(idx);
}
void RadioEngine::setPpm(int ppm) {
    ppm_ = ppm;
    if (src_) src_->setPpm(ppm);
}
int RadioEngine::gainCount() const { return src_ ? src_->gainCount() : 0; }
float RadioEngine::gainDb() const { return src_ ? src_->gainDb() : 0.0f; }

void RadioEngine::setForceMono(bool m) {
    forceMono_ = m;
    if (fm_) fm_->setForceMono(m);
}

void RadioEngine::setDeemphasisMicros(float us) {
    deemph_ = us;
    if (fm_) fm_->setDeemphasisMicros(us);
}

void RadioEngine::joinScan() {
    if (scanThread_.joinable()) scanThread_.join();
}

void RadioEngine::cancelScan() {
    cancel_ = true;
    joinScan();
    cancel_ = false;
    scanning_ = false;
    muted_ = false;
}

void RadioEngine::scanFm(bool resolveNames) {
    logf("scanFm");
    cancelScan();
    {
        std::lock_guard<std::mutex> lk(ctl_);
        if (!sourceOpen()) {
            if (out_) out_->onMessage(true, "@nat_no_dongle");
            if (out_) out_->onScanDone(true);
            return;
        }
        stopLocked();
        scanning_ = true;
        muted_ = true;
    }
    scanThread_ = std::thread([this, resolveNames] {
        auto reader = [this](int center, size_t count, std::vector<std::complex<float>>& out) {
            onScanFreq(center);
            return fm_->capture(center, count, out);
        };
        // Tarama boyunca elle kazanç (pencere başına örnek istatistiğine göre ayarlanır), sonra AGC geri açılır
        if (src_ && src_->gainCount() > 1) src_->setGainIndex(src_->gainCount() * 3 / 5);
        const auto stations = dr::FmScanner::scan(
            reader, cancel_, [this, resolveNames](int p) { if (out_) out_->onScanProgress(resolveNames ? p / 2 : p, "FM"); });
        if (src_) src_->setSoftwareAgc(agc_);
        logf("scanFm bitti: %zu istasyon%s", stations.size(), cancel_.load() ? " (iptal)" : "");

        if (!cancel_) {
            for (const auto& s : stations)
                if (out_) out_->onFmStationFound(s.freqHz, s.strengthDb);
        }

        if (resolveNames && !cancel_ && !stations.empty()) {
            int idx = 0;
            for (const auto& s : stations) {
                if (cancel_) break;
                {
                    std::lock_guard<std::mutex> lk(psMutex_);
                    lastPs_.clear();
                }
                onScanFreq(s.freqHz);
                fm_->start(s.freqHz);
                // PS gelene kadar en çok 6 sn bekle
                const auto t0 = std::chrono::steady_clock::now();
                while (!cancel_ && std::chrono::steady_clock::now() - t0 < std::chrono::seconds(6)) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(150));
                    std::lock_guard<std::mutex> lk(psMutex_);
                    if (!lastPs_.empty()) break;
                }
                std::string ps;
                {
                    std::lock_guard<std::mutex> lk(psMutex_);
                    ps = lastPs_;
                }
                if (!ps.empty() && out_) out_->onFmStationName(s.freqHz, ps);
                ++idx;
                if (out_) out_->onScanProgress(50 + 50 * idx / static_cast<int>(stations.size()), "FM");
            }
            fm_->stop();
        }
        if (src_) src_->stopStreaming();
        muted_ = false;
        scanning_ = false;
        mode_ = Mode::Idle;
        if (out_) out_->onScanDone(cancel_.load());
    });
}

void RadioEngine::scanDab() {
    logf("scanDab");
    cancelScan();
    {
        std::lock_guard<std::mutex> lk(ctl_);
        if (!sourceOpen()) {
            if (out_) out_->onMessage(true, "@nat_no_dongle");
            if (out_) out_->onScanDone(true);
            return;
        }
        if (fm_) fm_->stop();
        if (!dab_->running() && !dab_->init()) {
            if (out_) out_->onMessage(true, "@nat_dab_core");
            if (out_) out_->onScanDone(true);
            return;
        }
        scanning_ = true;
        muted_ = true;
        mode_ = Mode::Dab;
    }
    scanThread_ = std::thread([this] {
        dab_->scan(cancel_);
        muted_ = false;
        scanning_ = false;
        mode_ = Mode::Idle;
        if (out_) out_->onScanDone(cancel_.load());
    });
}

void RadioEngine::onAudio(const int16_t* pcm, size_t frames, int rate) {
    if (out_ && !muted_) out_->onAudio(pcm, frames, rate);
}

void RadioEngine::onFmPs(const std::string& ps) {
    {
        std::lock_guard<std::mutex> lk(psMutex_);
        lastPs_ = ps;
    }
    if (out_ && !muted_) out_->onFmPs(ps);
}

}  // namespace oto
