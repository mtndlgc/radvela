// IQ kaynağı soyutlaması: USB RTL-SDR (Android) veya rtl_tcp (test/uzak).
#pragma once

#include <cstdint>
#include <functional>
#include <string>

namespace oto {

class IqSource {
public:
    virtual ~IqSource() = default;

    virtual bool isOpen() const = 0;
    virtual std::string description() const = 0;

    // Merkez frekansını ayarla (Hz). Eski veriyi atar; akış oturunca onTuned(hz) çağrılır.
    // onTuned, okuyucu iş parçacığından çağrılır: içinden kaynağa senkron çağrı yapmayın.
    virtual void tuneHz(uint32_t hz) = 0;
    virtual uint32_t frequencyHz() const = 0;

    // Kazanç
    virtual void setSoftwareAgc(bool on) = 0;
    virtual void setGainIndex(int index) = 0;  // elle kazanç (AGC kapanır)
    virtual void setPpm(int /*ppm*/) {}         // kristal frekans düzeltmesi (ppm); desteklemeyen kaynaklar yok sayar
    virtual int gainCount() const = 0;
    virtual float gainDb() const = 0;
    virtual int gainIndex() const = 0;
    virtual float signalLevel() const { return 0.0f; }  // AGC tepe seviyesi (0..128), yoksa 0

    // Akışı durdur (donanım boşta)
    virtual void stopStreaming() = 0;

    std::function<void(uint32_t /*hz*/)> onTuned;
    std::function<void(const std::string& /*neden*/)> onError;
};

}  // namespace oto
