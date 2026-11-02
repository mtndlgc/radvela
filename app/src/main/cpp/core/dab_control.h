// welle.io DAB arka ucu (GPL-2.0-veya-sonrası) için denetleyici: kanal ayarı, servis listesi, servis seçimi, tarama.
// Tüm RadioReceiver çağrıları tek çalışan iş parçacığında yapılır; arka uç geri çağrıları yalnız olay kuyruğuna yazar.
#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "engine_events.h"
#include "iq_source.h"
#include "radio-receiver.h"
#include "sample_fifo.h"

namespace oto {

// SampleFifo'daki ±128 ölçekli IQ örneklerini welle.io'nun beklediği ±1 ölçekli karmaşık örneklere çevirir.
class FifoInput final : public InputInterface {
public:
    FifoInput(IqSource& src, SampleFifo& fifo) : src_(src), fifo_(fifo), cache_(8192) {}

    void clear() { pos_ = len_ = 0; }

    void setFrequency(int) override {}
    int getFrequency() const override { return static_cast<int>(src_.frequencyHz()); }
    bool is_ok() override { return true; }
    bool restart() override { return true; }
    void stop() override {}
    void reset() override { clear(); }
    int32_t getSamples(DSPCOMPLEX* buffer, int32_t size) override;
    std::vector<DSPCOMPLEX> getSpectrumSamples(int) override { return {}; }
    int32_t getSamplesToRead() override { return static_cast<int32_t>((len_ - pos_ + fifo_.available()) / 2); }
    float setGain(int gain) override {
        src_.setGainIndex(gain);
        return src_.gainDb();
    }
    float getGain() const override { return src_.gainDb(); }
    int getGainCount() override { return src_.gainCount(); }
    void setAgc(bool agc) override { src_.setSoftwareAgc(agc); }
    std::string getDescription() override { return src_.description(); }

private:
    IqSource& src_;
    SampleFifo& fifo_;
    std::vector<float> cache_;
    size_t pos_ = 0, len_ = 0;
};

class DabControl final : public RadioControllerInterface, public ProgrammeHandlerInterface {
public:
    DabControl(IqSource& src, SampleFifo& fifo, EngineListener& listener);
    ~DabControl() override;

    bool init();
    void shutdown();
    bool running() const { return running_.load(); }

    // Kanala ayarla (kHz). sid != 0 ise ayar tamamlanınca o servisi çal.
    void tune(uint32_t freqKHz, uint32_t sid = 0, uint8_t scids = 0);
    void selectService(uint32_t sid, uint8_t scids);
    void idle();

    // Tüm kanalları tara. Çağıran iş parçacığında bloklar; iptal bayrağını dinler.
    void scan(const std::atomic<bool>& cancel);

    int syncLevel() const { return syncLevel_.load(); }

private:
    struct Event {
        enum Type { ReqTune, ReqSelect, ReqIdle, InputTuned, Restart } type = ReqIdle;
        uint32_t u32 = 0;
        uint32_t sid = 0;
        uint8_t scids = 0;
    };

    void post(Event ev);
    void workerLoop();
    void handle(const Event& ev);
    void tick();

    void onReqTune(uint32_t freqKHz, uint32_t sid, uint8_t scids);
    void onInputTuned(uint32_t freqKHz);
    void stopReceiver();
    void tryPlayPending();

    // RadioControllerInterface (arka uç iş parçacıkları)
    void onSNR(float snr) override { snr_ = snr; }
    void onFrequencyCorrectorChange(int, int) override {}
    void onSyncChange(char isSync) override;
    void onSignalPresence(bool isSignal) override;
    void onServiceDetected(uint32_t) override {}
    void onNewEnsemble(uint16_t) override {}
    void onSetEnsembleLabel(DabLabel&) override {}
    void onDateTimeUpdate(const dab_date_time_t&) override {}
    void onFIBDecodeSuccess(bool crcCheckOk, const uint8_t*) override;
    void onNewImpulseResponse(std::vector<float>&&) override {}
    void onConstellationPoints(std::vector<DSPCOMPLEX>&&) override {}
    void onNewNullSymbol(std::vector<DSPCOMPLEX>&&) override {}
    void onTIIMeasurement(tii_measurement_t&&) override {}
    void onMessage(message_level_t, const std::string&, const std::string&) override {}
    void onRestartService() override;

    // ProgrammeHandlerInterface (ses çözücü iş parçacığı)
    void onFrameErrors(int frameErrors) override;
    void onNewAudio(std::vector<int16_t>&& audioData, int sampleRate, const std::string& mode) override;
    void onRsErrors(bool uncorrectedErrors, int) override { if (uncorrectedErrors) ++rsErr_; }
    void onAacErrors(int aacErrors) override { if (aacErrors) ++mscErr_; }
    void onNewDynamicLabel(const std::string& label) override { listener_.onDabLabel(label); }
    void onMOT(const mot_file_t& mot) override { listener_.onDabSlide(mot.data.data(), mot.data.size(), mot.content_sub_type); }
    void onPADLengthError(size_t, size_t) override {}

    IqSource& src_;
    SampleFifo& fifo_;
    EngineListener& listener_;
    FifoInput input_;
    std::unique_ptr<RadioReceiver> rx_;  // yalnız çalışan iş parçacığı dokunur

    // Olay kuyruğu
    std::mutex qm_;
    std::condition_variable qcv_;
    std::deque<Event> q_;
    std::thread worker_;
    bool quit_ = false;
    std::atomic<bool> running_{false};

    // Durum (yalnız çalışan iş parçacığı)
    uint32_t freqKHz_ = 0;
    std::string channel_;
    std::string ensLabel_;
    std::set<uint32_t> seen_;
    std::chrono::steady_clock::time_point lastNewService_{};
    std::chrono::steady_clock::time_point nextTick_{};
    bool servicesReported_ = false;
    struct { uint32_t sid = 0; uint8_t scids = 0; } pending_, current_;

    // Arka uçtan gelen sayaçlar
    std::atomic<float> snr_{0.0f};
    std::atomic<bool> synced_{false};
    std::atomic<int> fibOk_{0}, fibErr_{0}, mscOk_{0}, mscErr_{0}, rsErr_{0};
    std::atomic<int> syncLevel_{0};
    std::atomic<bool> scanMode_{false};
    long sumFibErr_ = 0, sumMscErr_ = 0, sumRsErr_ = 0, tickCount_ = 0;  // tanılama günlüğü

    // Son ses biçimi (ses iş parçacığı)
    std::string lastMode_;
    int lastRate_ = 0;

    // Tarama senkronizasyonu
    std::mutex sm_;
    std::condition_variable scv_;
    uint32_t scTunedKHz_ = 0;
    bool scEnsemble_ = false;
    bool scServicesComplete_ = false;
    int scSync_ = 0;
    bool scNoSignal_ = false;
};

}  // namespace oto
