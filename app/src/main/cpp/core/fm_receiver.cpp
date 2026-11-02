#include "fm_receiver.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <thread>

#include "log.h"

namespace oto {

namespace {
constexpr float kScale = 1.0f / 128.0f;  // ±128 -> ±1
}

FmReceiver::FmReceiver(IqSource& src, SampleFifo& fifo, EngineListener& listener)
    : src_(src), fifo_(fifo), listener_(listener) {
    rds_.setEvents(this);
}

FmReceiver::~FmReceiver() { stop(); }

void FmReceiver::attach() {
    src_.onTuned = [this](uint32_t hz) {
        std::lock_guard<std::mutex> lk(tm_);
        tunedHz_ = hz;
        tcv_.notify_all();
    };
}

void FmReceiver::detach() { src_.onTuned = nullptr; }

bool FmReceiver::tuneAndWait(int centerHz, int timeoutMs) {
    const uint32_t hz = static_cast<uint32_t>(centerHz);
    {
        std::lock_guard<std::mutex> lk(tm_);
        tunedHz_ = 0;
    }
    src_.tuneHz(hz);
    std::unique_lock<std::mutex> lk(tm_);
    return tcv_.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] { return tunedHz_ == hz || !running_.load(); }) && tunedHz_ == hz;
}

void FmReceiver::start(int freqHz) {
    std::lock_guard<std::mutex> lk(startMutex_);
    pendingHz_ = freqHz;
    if (running_.load()) {  // çalışıyor: döngü yeni frekansı alır
        return;
    }
    if (thread_.joinable()) thread_.join();
    attach();
    running_ = true;
    thread_ = std::thread(&FmReceiver::loop, this);
}

void FmReceiver::stop() {
    std::lock_guard<std::mutex> lk(startMutex_);
    running_ = false;
    tcv_.notify_all();
    if (thread_.joinable()) thread_.join();
    detach();
}

bool FmReceiver::capture(int centerHz, size_t count, std::vector<std::complex<float>>& out) {
    if (running_.load()) return false;
    attach();
    running_ = true;  // tuneAndWait iptal denetimi için
    const bool ok = tuneAndWait(centerHz, 2500);
    running_ = false;
    if (!ok) return false;
    const size_t need = count * 2;
    std::vector<float> raw(need);
    // Tarama sırasında kazanç elle tutulur (yazılım AGC kapalı). Yakalanan örneklerin rms ve kırpma oranına
    // göre kazanç pencere içinde birkaç kez ayarlanır: zayıf antende düşük kazanç sinyali ADC gürültüsüne
    // gömer, güçlü istasyonlu pencerede yüksek kazanç alıcıyı doyurur.
    const int gains = src_.gainCount();
    for (int attempt = 0; attempt < 4; ++attempt) {
        // Ayar/kazanç sonrası kısa yerleşme: ~60 ms'lik veriyi at
        fifo_.waitAvailable(2 * 2048000 / 16, 500);
        fifo_.reset();
        if (!fifo_.waitAvailable(need, 3000)) return false;
        fifo_.readSome(raw.data(), need);

        double sumsq = 0.0;
        size_t clipped = 0;
        for (size_t i = 0; i < need; ++i) {
            const float v = raw[i];
            sumsq += static_cast<double>(v) * v;
            if (v > 124.0f || v < -124.0f) ++clipped;
        }
        const float rms = static_cast<float>(std::sqrt(sumsq / static_cast<double>(need)));
        const float clipFrac = static_cast<float>(clipped) / static_cast<float>(need);
        const int idx = src_.gainIndex();
        int next = idx;
        if (gains > 1) {
            if (clipFrac > 0.0005f) {
                next = idx - (clipFrac > 0.01f ? 5 : 2);
            } else if (rms < 12.0f) {
                const float db = 20.0f * std::log10(24.0f / std::max(rms, 0.5f));
                next = idx + std::max(1, static_cast<int>(std::lround(db / 1.7f)));
            } else if (rms > 48.0f) {
                next = idx - 2;
            }
            next = std::max(0, std::min(next, gains - 1));
        }
        logf("FM tarama %d Hz: rms=%.1f klip=%.4f kazanc idx %d (%.1f dB)%s", centerHz, rms, clipFrac, idx, src_.gainDb(),
             next != idx && attempt < 3 ? " -> ayarla" : "");
        if (next == idx || attempt == 3) break;
        src_.setGainIndex(next);
        std::this_thread::sleep_for(std::chrono::milliseconds(60));
    }
    out.resize(count);
    for (size_t i = 0; i < count; ++i) out[i] = {raw[2 * i] * kScale, raw[2 * i + 1] * kScale};
    return true;
}

void FmReceiver::loop() {
    constexpr size_t kBlock = 16384;  // karmaşık örnek
    std::vector<float> raw(kBlock * 2);
    std::vector<std::complex<float>> iq(kBlock);
    std::vector<float> mpxBuf;
    auto lastStatus = std::chrono::steady_clock::now();
    bool tuned = false;

    while (running_) {
        const int req = pendingHz_.exchange(0);
        if (req != 0) {
            tuned = false;
            currentHz_ = req;
            mpx_.reset();
            if (tuneAndWait(req + dr::FmFrontEnd::kLoOffsetHz, 2500)) {
                fe_.reset();
                mpx_ = std::make_unique<dr::FmMpx>();
                mpx_->setForceMono(forceMono_);
                mpx_->setDeemphasisMicros(deemphUs_);
                mpx_->onPcm = [this](const int16_t* pcm, size_t frames) {
                    listener_.onAudio(pcm, frames, dr::FmMpx::kAudioRate);
                };
                mpx_->onRdsBit = [this](int bit) { rds_.pushBit(bit); };
                rds_.reset();
                listener_.onFmPs("");
                listener_.onFmRt("");
                listener_.onFmPty(-1);
                tuned = true;
            } else {
                listener_.onMessage(true, "@nat_fm_tune");
                std::this_thread::sleep_for(std::chrono::milliseconds(200));
            }
            continue;
        }
        if (!tuned || !mpx_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        if (!fifo_.waitAvailable(8192 * 2, 100)) continue;
        const size_t floats = fifo_.readSome(raw.data(), kBlock * 2);
        const size_t n = floats / 2;
        if (n == 0) continue;
        for (size_t i = 0; i < n; ++i) iq[i] = {raw[2 * i] * kScale, raw[2 * i + 1] * kScale};

        mpxBuf.clear();
        fe_.process(iq.data(), n, mpxBuf);
        mpx_->setForceMono(forceMono_);
        mpx_->process(mpxBuf.data(), mpxBuf.size());

        const auto now = std::chrono::steady_clock::now();
        if (now - lastStatus > std::chrono::milliseconds(250)) {
            lastStatus = now;
            listener_.onFmStatus(fe_.levelDb() - src_.gainDb(), mpx_->pilotLocked(), rds_.synced());
        }
    }
}

}  // namespace oto
