// rtl_tcp istemcisi: test ve uzaktan kullanım içindir (Android'de varsayılan yol doğrudan USB'dir).
#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

#include "iq_source.h"
#include "rtl_common.h"
#include "sample_fifo.h"

namespace oto {

class RtlTcpSource : public IqSource {
public:
    explicit RtlTcpSource(SampleFifo& fifo);
    ~RtlTcpSource() override;

    bool open(const std::string& host, int port, int timeoutMs, std::string* error = nullptr);
    void close();

    bool isOpen() const override { return running_.load(); }
    std::string description() const override { return "rtl_tcp (" + host_ + ":" + std::to_string(port_) + ")"; }
    void tuneHz(uint32_t hz) override;
    uint32_t frequencyHz() const override { return freq_.load(); }
    void setSoftwareAgc(bool on) override { fe_.setSoftwareAgc(on); }
    void setGainIndex(int index) override;
    int gainCount() const override { return fe_.gainCount(); }
    float gainDb() const override { return fe_.gainDb(); }
    int gainIndex() const override { return fe_.gainIdx(); }
    void stopStreaming() override {}

private:
    bool send(uint8_t cmd, uint32_t param);
    void rxLoop();
    void agcLoop();
    void applyGain(int idx);

    SampleFifo& fifo_;
    std::string host_;
    int port_ = 1234;
    int fd_ = -1;
    std::mutex sendMutex_;
    RtlFrontEnd fe_;
    std::vector<float> conv_;
    std::thread rx_, agc_;
    std::atomic<bool> running_{false};
    std::atomic<uint32_t> freq_{0};
    std::atomic<int64_t> discardBytes_{0};
    std::atomic<bool> pendingTuned_{false};
    int tunerType_ = 0;
};

}  // namespace oto
