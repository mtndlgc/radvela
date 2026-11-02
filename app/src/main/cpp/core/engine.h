// RadioEngine: tek RTL-SDR üzerinde FM ve DAB/DAB+ modlarını yöneten üst katman.
// Olayları dış dinleyiciye (JNI) iletir; tarama sırasında sesi susturur.
#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "dab_control.h"
#include "engine_events.h"
#include "fm_receiver.h"
#include "iq_source.h"
#include "rtl_tcp_source.h"
#include "sample_fifo.h"
#ifdef OTO_WITH_USB
#include "rtlsdr_usb_source.h"
#endif

namespace oto {

class RadioEngine : public EngineListener {
public:
    enum class Mode { Idle, Fm, Dab };

    explicit RadioEngine(EngineListener* out);
    ~RadioEngine() override;

    // Kaynak
#ifdef OTO_WITH_USB
    bool openUsb(int fd, std::string* error = nullptr);
#endif
    bool openTcp(const std::string& host, int port, int timeoutMs, std::string* error = nullptr);
    void closeSource();
    bool sourceOpen() const;

    bool startFm(int freqHz);
    bool startDab(const std::string& channel, uint32_t sid, int scids);  // sid=0: yalnız kanalı ayarla
    void playDabService(uint32_t sid, int scids);
    void stop();  // sesi durdur, kaynağı açık tut

    void scanFm(bool resolveNames);
    void scanDab();
    void cancelScan();
    bool scanning() const { return scanning_.load(); }

    void setAgc(bool on);
    void setGainIndex(int idx);
    void setPpm(int ppm);
    float signalLevel() const { return src_ ? src_->signalLevel() : 0.0f; }
    uint64_t droppedSamples() const { return fifo_.dropped(); }  // IQ tamponu taşmaları (işleme yetişemedi)
    int gainCount() const;
    float gainDb() const;
    void setForceMono(bool m);
    void setDeemphasisMicros(float us);

    Mode mode() const { return mode_.load(); }

    // EngineListener (ileri ilet)
    void onAudio(const int16_t* pcm, size_t frames, int rate) override;
    void onFmStatus(float r, bool s, bool y) override { if (out_) out_->onFmStatus(r, s, y); }
    void onFmPi(uint16_t pi) override { if (out_) out_->onFmPi(pi); }
    void onFmPs(const std::string& ps) override;
    void onFmRt(const std::string& rt) override { if (out_ && !muted_) out_->onFmRt(rt); }
    void onFmPty(int pty) override { if (out_ && !muted_) out_->onFmPty(pty); }
    void onFmStationFound(int f, float s) override { if (out_) out_->onFmStationFound(f, s); }
    void onFmStationName(int f, const std::string& ps) override { if (out_) out_->onFmStationName(f, ps); }
    void onDabEnsemble(const std::string& c, const std::string& l, uint32_t e) override { if (out_) out_->onDabEnsemble(c, l, e); }
    void onDabService(const std::string& c, uint32_t s, int sc, const std::string& l, bool aac) override {
        if (out_) out_->onDabService(c, s, sc, l, aac);
    }
    void onDabServicesComplete(const std::string& c) override { if (out_) out_->onDabServicesComplete(c); }
    void onDabSelected(uint32_t s, int sc, const std::string& l) override { if (out_) out_->onDabSelected(s, sc, l); }
    void onDabStatus(float snr, int sync) override { if (out_) out_->onDabStatus(snr, sync); }
    void onDabQuality(int f, int ok, int err, int rs) override { if (out_) out_->onDabQuality(f, ok, err, rs); }
    void onDabLabel(const std::string& d) override { if (out_ && !muted_) out_->onDabLabel(d); }
    void onDabAudioParams(const std::string& c, int r, bool st) override { if (out_) out_->onDabAudioParams(c, r, st); }
    void onDabSlide(const uint8_t* d, size_t n, int t) override { if (out_ && !muted_) out_->onDabSlide(d, n, t); }
    void onScanProgress(int p, const std::string& w) override { if (out_) out_->onScanProgress(p, w); }
    void onScanFreq(int f) override { if (out_) out_->onScanFreq(f); }
    void onScanDone(bool c) override { if (out_) out_->onScanDone(c); }
    void onConnection(bool c, const std::string& d) override { if (out_) out_->onConnection(c, d); }
    void onGain(float db, bool a) override { if (out_) out_->onGain(db, a); }
    void onMessage(bool e, const std::string& t) override { if (out_) out_->onMessage(e, t); }

private:
    void stopLocked();
    void joinScan();
    void attachSource(std::unique_ptr<IqSource> s);

    EngineListener* out_;
    SampleFifo& fifo_;
    std::unique_ptr<IqSource> src_;
    std::unique_ptr<FmReceiver> fm_;
    std::unique_ptr<DabControl> dab_;
    std::mutex ctl_;
    std::atomic<Mode> mode_{Mode::Idle};

    std::thread scanThread_;
    std::atomic<bool> scanning_{false};
    std::atomic<bool> cancel_{false};
    std::atomic<bool> muted_{false};
    std::atomic<bool> agc_{true};
    std::atomic<int> ppm_{0};

    std::mutex psMutex_;
    std::string lastPs_;
    bool forceMono_ = false;
    float deemph_ = 50.0f;
};

}  // namespace oto
