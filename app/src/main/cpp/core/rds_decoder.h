// RDS bit akışı çözücüsü: 26 bitlik blok senkronu (IEC 62106 Ek B), grup ayrıştırma,
// program servis adı (PS), RadioText (RT), PI ve PTY.
#pragma once

#include <cstdint>
#include <string>

namespace dr {

class RdsEvents {
public:
    virtual ~RdsEvents() = default;
    virtual void onRdsPi(uint16_t /*pi*/) {}
    virtual void onRdsPs(const std::string& /*utf8*/) {}
    virtual void onRdsRt(const std::string& /*utf8*/) {}
    virtual void onRdsPty(int /*pty*/) {}
    virtual void onRdsSync(bool /*synced*/) {}
};

class RdsDecoder {
public:
    explicit RdsDecoder(RdsEvents* events = nullptr) : ev_(events) { reset(); }

    void setEvents(RdsEvents* events) { ev_ = events; }
    void reset();

    // Diferansiyel çözülmüş bit akışı (ilk iletilen bit önce). bit: 0 veya 1.
    void pushBit(int bit);

    bool synced() const { return synced_; }
    uint16_t pi() const { return pi_; }

    // Test erişimi
    static uint32_t syndrome(uint32_t word26);
    static uint32_t offsetSyndrome(int offsetType);  // 0=A 1=B 2=C 3=C' 4=D

private:
    void handleBlock(int blockNo, int offsetType);
    void processGroup();
    void setSync(bool s);

    RdsEvents* ev_ = nullptr;

    uint32_t reg_ = 0;
    uint64_t bitCount_ = 0;
    bool synced_ = false;
    int expectedBlock_ = 0;
    int bitsToBlock_ = 0;
    int lastBlock_ = -1;
    uint64_t lastBlockBit_ = 0;
    uint32_t history_ = 0;  // son 32 blok: 1 = hatalı

    // Grup birleştirme
    uint16_t blk_[4] = {0, 0, 0, 0};
    bool cPrime_ = false;
    unsigned have_ = 0;

    uint16_t pi_ = 0;
    bool piValid_ = false;
    int pty_ = -1;

    // PS
    char ps_[8];
    unsigned psMask_ = 0;
    std::string lastPs_;

    // RT
    unsigned char rt_[64];
    uint32_t rtMask_ = 0;  // 2 karakterlik birimler
    int rtAb_ = -1;
    std::string lastRt_;
};

}  // namespace dr
