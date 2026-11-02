// Doğrudan USB RTL-SDR kaynağı (Android): Java tarafında açılan USB dosya tanıtıcısını
// librtlsdr (rtlsdr_open_fd) ile kullanır. Sürücü uygulaması gerekmez.
#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

#include "iq_source.h"
#include "rtl_common.h"
#include "sample_fifo.h"

struct rtlsdr_dev;

namespace oto {

class RtlsdrUsbSource : public IqSource {
public:
    explicit RtlsdrUsbSource(SampleFifo& fifo);
    ~RtlsdrUsbSource() override;

    // fd: UsbDeviceConnection.getFileDescriptor(). Bağlantıyı Java tarafı canlı tutmalıdır.
    bool open(int fd, std::string* error = nullptr);
    void close();

    bool isOpen() const override { return dev_ != nullptr; }
    std::string description() const override { return desc_; }
    void tuneHz(uint32_t hz) override;
    uint32_t frequencyHz() const override { return freq_.load(); }
    void setSoftwareAgc(bool on) override;
    void setGainIndex(int index) override;
    void setPpm(int ppm) override;
    int gainCount() const override { return fe_.gainCount(); }
    float gainDb() const override { return fe_.gainDb(); }
    int gainIndex() const override { return fe_.gainIdx(); }
    float signalLevel() const override { return fe_.agcLevel(); }
    void stopStreaming() override;

private:
    static void asyncCallback(unsigned char* buf, uint32_t len, void* ctx);
    void process(const unsigned char* buf, uint32_t len);
    void readLoop();
    void agcLoop();
    void applyGain(int idx);
    void startStreamingLocked();

    SampleFifo& fifo_;
    rtlsdr_dev* dev_ = nullptr;
    std::string desc_;
    RtlFrontEnd fe_;
    std::vector<float> conv_;

    std::mutex mtx_;  // dev_ erişimini ve start/stop'u serileştirir
    std::thread readThread_;
    std::thread agcThread_;
    std::atomic<bool> streaming_{false};
    std::atomic<bool> agcRun_{false};
    std::atomic<uint32_t> freq_{0};
    std::atomic<int> restartCounter_{0};
    std::atomic<int> agcFastTicks_{0};
    std::atomic<int> ppm_{0};
};

}  // namespace oto
