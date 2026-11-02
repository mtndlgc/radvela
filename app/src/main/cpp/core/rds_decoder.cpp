#include "rds_decoder.h"

#include <cstring>

#include "rds_charset.h"

namespace dr {

namespace {

// IEC 62106 Ek B: 26x10 eşlik denetim matrisi H (satır 0 = ilk iletilen bit).
const uint16_t kH[26] = {
    0b1000000000, 0b0100000000, 0b0010000000, 0b0001000000, 0b0000100000, 0b0000010000, 0b0000001000,
    0b0000000100, 0b0000000010, 0b0000000001, 0b1011011100, 0b0101101110, 0b0010110111, 0b1010000111,
    0b1110011111, 0b1100010011, 0b1101010101, 0b1101110110, 0b0110111011, 0b1000000001, 0b1111011100,
    0b0111101110, 0b0011110111, 0b1010100111, 0b1110001111, 0b1100011011};

// Ofset kelimelerine karşılık gelen sendrom değerleri (Tablo B.1)
const uint16_t kOffsetSyndrome[5] = {0b1111011000, 0b1111010100, 0b1001011100, 0b1111001100, 0b1001011000};
const int kOffsetBlock[5] = {0, 1, 2, 2, 3};

int offsetTypeForSyndrome(uint32_t s) {
    for (int i = 0; i < 5; ++i)
        if (kOffsetSyndrome[i] == s) return i;
    return -1;
}

std::string rtrim(std::string s) {
    while (!s.empty() && s.back() == ' ') s.pop_back();
    return s;
}

std::string toUtf8(const unsigned char* chars, size_t n) {
    std::string out;
    for (size_t i = 0; i < n; ++i) out += rdsCharToUtf8(chars[i]);
    return out;
}

}  // namespace

uint32_t RdsDecoder::syndrome(uint32_t w) {
    uint32_t r = 0;
    for (int k = 0; k < 26; ++k)
        if ((w >> k) & 1u) r ^= kH[25 - k];
    return r;
}

uint32_t RdsDecoder::offsetSyndrome(int t) { return kOffsetSyndrome[t]; }

void RdsDecoder::reset() {
    reg_ = 0;
    bitCount_ = 0;
    synced_ = false;
    expectedBlock_ = 0;
    bitsToBlock_ = 0;
    lastBlock_ = -1;
    lastBlockBit_ = 0;
    history_ = 0;
    have_ = 0;
    cPrime_ = false;
    pi_ = 0;
    piValid_ = false;
    pty_ = -1;
    std::memset(ps_, ' ', sizeof(ps_));
    psMask_ = 0;
    lastPs_.clear();
    std::memset(rt_, ' ', sizeof(rt_));
    rtMask_ = 0;
    rtAb_ = -1;
    lastRt_.clear();
}

void RdsDecoder::setSync(bool s) {
    if (s == synced_) return;
    synced_ = s;
    if (ev_) ev_->onRdsSync(s);
}

void RdsDecoder::pushBit(int bit) {
    reg_ = ((reg_ << 1) | static_cast<uint32_t>(bit & 1)) & 0x3FFFFFFu;
    ++bitCount_;

    if (!synced_) {
        if (bitCount_ < 26) return;
        const int t = offsetTypeForSyndrome(syndrome(reg_));
        if (t < 0) return;
        const int blk = kOffsetBlock[t];
        if (lastBlock_ >= 0 && bitCount_ - lastBlockBit_ == 26 && blk == (lastBlock_ + 1) % 4) {
            // Art arda iki geçerli blok: senkron kuruldu
            setSync(true);
            history_ = 0;
            bitsToBlock_ = 26;
            expectedBlock_ = (blk + 1) % 4;
            lastBlock_ = -1;
            handleBlock(blk, t);
        } else {
            lastBlock_ = blk;
            lastBlockBit_ = bitCount_;
        }
        return;
    }

    if (--bitsToBlock_ > 0) return;
    bitsToBlock_ = 26;

    const int t = offsetTypeForSyndrome(syndrome(reg_));
    const bool ok = (t >= 0 && kOffsetBlock[t] == expectedBlock_);
    history_ = (history_ << 1) | (ok ? 0u : 1u);
    const int blockNo = expectedBlock_;
    expectedBlock_ = (expectedBlock_ + 1) % 4;

    if (ok) {
        handleBlock(blockNo, t);
    } else if (blockNo == 0) {
        have_ = 0;
    }

    // Son 32 bloktan 20'si hatalıysa senkronu bırak
    if (__builtin_popcount(history_) >= 20) {
        setSync(false);
        lastBlock_ = -1;
        have_ = 0;
    }
}

void RdsDecoder::handleBlock(int blockNo, int offsetType) {
    const uint16_t data = static_cast<uint16_t>((reg_ >> 10) & 0xFFFFu);
    switch (blockNo) {
        case 0:
            have_ = 1;
            blk_[0] = data;
            if (!piValid_ || pi_ != data) {
                pi_ = data;
                piValid_ = true;
                if (ev_) ev_->onRdsPi(pi_);
            }
            break;
        case 1:
            if (have_ & 1) have_ |= 2;
            else have_ = 2;
            blk_[1] = data;
            break;
        case 2:
            have_ |= 4;
            blk_[2] = data;
            cPrime_ = (offsetType == 3);
            break;
        case 3:
            have_ |= 8;
            blk_[3] = data;
            processGroup();
            have_ = 0;
            break;
        default:
            break;
    }
}

void RdsDecoder::processGroup() {
    if (!(have_ & 2) || !(have_ & 8)) return;  // B ve D yoksa kullanışlı veri yok
    const uint16_t b = blk_[1];
    const int type = (b >> 12) & 0xF;
    const bool versionB = (b >> 11) & 1;
    const int pty = (b >> 5) & 0x1F;

    if (pty != pty_) {
        pty_ = pty;
        if (ev_) ev_->onRdsPty(pty);
    }

    if (type == 0) {
        // 0A/0B: PS, iki karakter / grup
        const int addr = b & 3;
        const uint16_t d = blk_[3];
        ps_[2 * addr] = static_cast<char>(d >> 8);
        ps_[2 * addr + 1] = static_cast<char>(d & 0xFF);
        psMask_ |= 1u << addr;
        if (psMask_ == 0xF) {
            psMask_ = 0;
            const std::string ps = rtrim(toUtf8(reinterpret_cast<const unsigned char*>(ps_), 8));
            if (ps != lastPs_ && !ps.empty()) {
                lastPs_ = ps;
                if (ev_) ev_->onRdsPs(ps);
            }
        }
    } else if (type == 2) {
        // 2A/2B: RadioText
        const int addr = b & 0xF;
        const int ab = (b >> 4) & 1;
        if (ab != rtAb_) {
            rtAb_ = ab;
            std::memset(rt_, ' ', sizeof(rt_));
            rtMask_ = 0;
        }
        if (!versionB) {
            if (!(have_ & 4)) return;
            const uint16_t c = blk_[2], d = blk_[3];
            rt_[4 * addr + 0] = static_cast<unsigned char>(c >> 8);
            rt_[4 * addr + 1] = static_cast<unsigned char>(c & 0xFF);
            rt_[4 * addr + 2] = static_cast<unsigned char>(d >> 8);
            rt_[4 * addr + 3] = static_cast<unsigned char>(d & 0xFF);
            rtMask_ |= 3u << (2 * addr);
        } else {
            const uint16_t d = blk_[3];
            rt_[2 * addr + 0] = static_cast<unsigned char>(d >> 8);
            rt_[2 * addr + 1] = static_cast<unsigned char>(d & 0xFF);
            rtMask_ |= 1u << addr;
        }

        // Metin tamam mı? 0x0D sonlandırıcıya kadar tüm segmentler gelmiş olmalı
        const int totalChars = versionB ? 32 : 64;
        int end = -1;
        for (int i = 0; i < totalChars; ++i) {
            if (!(rtMask_ & (1u << (i / 2)))) break;
            if (rt_[i] == 0x0D) {
                end = i;
                break;
            }
        }
        const uint32_t fullMask = versionB ? 0xFFFFu : 0xFFFFFFFFu;
        size_t len = 0;
        bool complete = false;
        if (end >= 0) {
            len = static_cast<size_t>(end);
            complete = true;
        } else if ((rtMask_ & fullMask) == fullMask) {
            len = static_cast<size_t>(totalChars);
            complete = true;
        }
        if (complete) {
            const std::string rt = rtrim(toUtf8(rt_, len));
            if (rt != lastRt_) {
                lastRt_ = rt;
                if (ev_) ev_->onRdsRt(rt);
            }
        }
    }
}

}  // namespace dr
