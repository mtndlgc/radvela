// FM alıcı: IqSource -> SampleFifo -> FM ön uç + MPX işleyici + RDS çözücü.
#pragma once

#include <atomic>
#include <complex>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "engine_events.h"
#include "fm_frontend.h"
#include "fm_mpx.h"
#include "iq_source.h"
#include "rds_decoder.h"
#include "sample_fifo.h"

namespace oto {

class FmReceiver : private dr::RdsEvents {
public:
    FmReceiver(IqSource& src, SampleFifo& fifo, EngineListener& listener);
    ~FmReceiver() override;

    // freqHz: istasyon frekansı. İş parçacığı çalışmıyorsa başlatır, çalışıyorsa yeniden ayarlar.
    void start(int freqHz);
    void stop();
    bool running() const { return running_.load(); }
    int frequency() const { return currentHz_.load(); }

    // Alıcı çalışmıyorken: merkezi ayarla ve `count` karmaşık örnek (±1 ölçekli) oku.
    bool capture(int centerHz, size_t count, std::vector<std::complex<float>>& out);

    void setForceMono(bool mono) { forceMono_ = mono; }
    void setDeemphasisMicros(float us) { deemphUs_ = us; }

private:
    void loop();
    bool tuneAndWait(int centerHz, int timeoutMs);
    void attach();
    void detach();

    // RdsEvents
    void onRdsPi(uint16_t pi) override { listener_.onFmPi(pi); }
    void onRdsPs(const std::string& ps) override { listener_.onFmPs(ps); }
    void onRdsRt(const std::string& rt) override { listener_.onFmRt(rt); }
    void onRdsPty(int pty) override { listener_.onFmPty(pty); }

    IqSource& src_;
    SampleFifo& fifo_;
    EngineListener& listener_;

    std::thread thread_;
    std::mutex startMutex_;
    std::atomic<bool> running_{false};
    std::atomic<int> pendingHz_{0};
    std::atomic<int> currentHz_{0};
    std::atomic<bool> forceMono_{false};
    std::atomic<float> deemphUs_{50.0f};

    // Ayar bekleme
    std::mutex tm_;
    std::condition_variable tcv_;
    uint32_t tunedHz_ = 0;

    // Yalnız çalışan iş parçacığında kullanılır
    dr::FmFrontEnd fe_;
    std::unique_ptr<dr::FmMpx> mpx_;
    dr::RdsDecoder rds_;
};

}  // namespace oto
