// RTL-SDR için ortak yazılım: uint8 IQ -> float dönüşümü, DC giderme (DOC) ve yazılım AGC.
// Sabitler ve mantık AbracaDABra'nın (MIT, © Petr Kopecký) rtlsdrinput bileşeninden uyarlanmıştır.
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <vector>

namespace oto {

class RtlFrontEnd {
public:
    static constexpr float kDocC = 0.05f;
    static constexpr float kAgcAttack = 0.1f;
    static constexpr float kAgcRelease = 0.00005f;
    static constexpr float kAgcLevelMaxDefault = 105.0f;
    static constexpr int kAgcMaxJump = 8;  // tek kararda en çok bu kadar tablo adımı

    void setGains(const std::vector<int>& tenthDb) {
        gains_ = tenthDb;
        minFactor_.clear();
        for (size_t i = 0; i + 1 < gains_.size(); ++i)
            minFactor_.push_back(std::pow(10.0f, static_cast<float>(gains_[i] - gains_[i + 1] - 5) / 200.0f));
        minFactor_.push_back(std::pow(10.0f, -5.0f / 20.0f));
        gainIdx_ = static_cast<int>(gains_.size() / 2);
    }

    int gainCount() const { return static_cast<int>(gains_.size()); }
    int gainIdx() const { return gainIdx_; }
    int gainTenthDb(int idx) const { return gains_.empty() ? 0 : gains_[static_cast<size_t>(clampIdx(idx))]; }
    float gainDb() const { return gains_.empty() ? 0.0f : gains_[static_cast<size_t>(clampIdx(gainIdx_))] * 0.1f; }
    int clampIdx(int idx) const { return gains_.empty() ? 0 : std::max(0, std::min(idx, static_cast<int>(gains_.size()) - 1)); }

    void setSoftwareAgc(bool on) { swAgc_ = on; }
    bool softwareAgc() const { return swAgc_; }

    // Kazanç indeksini kaydet (donanıma yazmayı çağıran yapar)
    void storeGainIdx(int idx) { gainIdx_ = clampIdx(idx); }

    void resetDoc() { dcI_ = dcQ_ = 0.0f; }

    // len: bayt sayısı (I,Q,I,Q...). out: len adet float (±128 ölçekli).
    void convert(const uint8_t* in, size_t len, float* out) {
        float agc = agcLevel_.load(std::memory_order_relaxed);
        int32_t sumI = 0, sumQ = 0;
        const float dcI = dcI_, dcQ = dcQ_;
        for (size_t k = 0; k < len; ++k) {
            const int tmp = static_cast<int>(in[k]) - 128;
            const float a = static_cast<float>(std::abs(tmp));
            const float c = (a > agc) ? kAgcAttack : kAgcRelease;
            agc = c * a + agc - c * agc;
            if (k & 1u) {
                sumQ += tmp;
                out[k] = static_cast<float>(tmp) - dcQ;
            } else {
                sumI += tmp;
                out[k] = static_cast<float>(tmp) - dcI;
            }
        }
        const float n = static_cast<float>(len >> 1);
        dcI_ = static_cast<float>(sumI) * kDocC / n + dcI - kDocC * dcI;
        dcQ_ = static_cast<float>(sumQ) * kDocC / n + dcQ - kDocC * dcQ;
        agcLevel_.store(agc, std::memory_order_relaxed);
    }

    float agcLevel() const { return agcLevel_.load(std::memory_order_relaxed); }

    // AGC kararı: yeni kazanç indeksi (aynıysa -1 döner).
    // Seviye [minLev, kAgcLevelMax] bandında tutulur (AbracaDABra ile aynı bant). Bant dışındaysa tek adım yerine
    // gereken kadar atlanır (zayıf antende tabloyu adım adım çıkmak saniyeler sürüyordu), ama atlama hedefi
    // "kazanç değişince tahmini seviye bandın üstüne çıkmasın" kuralıyla seçilir; böylece iki indeks arasında
    // gidip gelme (salınım) olmaz.
    int agcDecision() const {
        if (!swAgc_ || gains_.empty()) return -1;
        const float lev = agcLevel_.load(std::memory_order_relaxed);
        const int cur = clampIdx(gainIdx_);
        const int last = static_cast<int>(gains_.size()) - 1;
        const float minLev = minFactor_[static_cast<size_t>(cur)] * kAgcLevelMaxDefault;
        auto predicted = [&](float level, int idx) {
            return level * std::pow(10.0f, static_cast<float>(gains_[static_cast<size_t>(idx)] - gains_[static_cast<size_t>(cur)]) / 200.0f);
        };
        int idx = cur;
        if (lev > kAgcLevelMaxDefault) {
            // Kırpma varsa ölçülen seviye gerçeği gizler: en az ~4 dB fazla say
            const float eff = lev > 118.0f ? lev * 1.6f : lev;
            idx = cur - 1;
            while (idx > 0 && idx > cur - kAgcMaxJump && predicted(eff, idx) > kAgcLevelMaxDefault) --idx;
        } else if (lev < minLev) {
            const float l = std::max(lev, 1.0f);
            idx = cur + 1;
            while (idx < last && idx < cur + kAgcMaxJump && predicted(l, idx + 1) <= kAgcLevelMaxDefault) ++idx;
        }
        idx = clampIdx(idx);
        return idx == cur ? -1 : idx;
    }

    void resetAgcLevel() { agcLevel_.store(0.0f, std::memory_order_relaxed); }
    int resetGainIdx() { return static_cast<int>(gains_.size() >> 1); }

private:
    std::vector<int> gains_;
    std::vector<float> minFactor_;
    int gainIdx_ = 0;
    bool swAgc_ = true;
    float dcI_ = 0.0f, dcQ_ = 0.0f;
    std::atomic<float> agcLevel_{0.0f};
};

}  // namespace oto
