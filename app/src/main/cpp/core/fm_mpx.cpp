#include "fm_mpx.h"

#include <algorithm>
#include <cmath>

namespace dr {

namespace {

constexpr double kTwoPi = 2.0 * kPi;
constexpr double kPilotHz = 19000.0;

// Pilot PLL: ~20 Hz gürültü bant genişliği, sönüm 0.707
constexpr double kLoopBwHz = 20.0;

}  // namespace

FmMpx::FmMpx() {
    const double wn = kTwoPi * kLoopBwHz;
    kp_ = static_cast<float>(2.0 * 0.707 * wn / kFs);
    ki_ = static_cast<float>(wn * wn / (static_cast<double>(kFs) * kFs));
    w0_ = kTwoPi * kPilotHz / kFs;
    pilotBpf1_.designBandpass(kFs, kPilotHz, 30.0);
    pilotBpf2_.designBandpass(kFs, kPilotHz, 30.0);

    // Ses süzgeci: 15 kHz geçiş, 17 kHz'den itibaren >=70 dB bastırma (19 kHz pilot 13 kHz'e katlanmasın)
    // geçiş merkezi 16 kHz, Kaiser beta 6.76 ~ 70 dB
    {
        auto h = designLowpass(513, 16000.0 / kFs, 6.76);
        sumFir_.init(h, kAudioDecim);
        diffFir_.init(h, kAudioDecim);
    }
    // RDS süzgeci: ~2.4 kHz'e kadar geçiş, 4.8 kHz'den sonra durdurma
    {
        auto h = designLowpass(385, 3600.0 / kFs, 5.65);
        zrFir_.init(h, kAudioDecim);
        ziFir_.init(h, kAudioDecim);
    }
    setDeemphasisMicros(50.0f);
}

void FmMpx::setDeemphasisMicros(float us) {
    deAlpha_ = 1.0f - std::exp(-1.0f / (us * 1e-6f * static_cast<float>(kAudioRate)));
}

void FmMpx::process(const float* mpx, size_t n) {
    if (n == 0) return;
    pllAndMix(mpx, n);
    audioStage();
    rdsStage();
    if (onPcm && !pcm_.empty()) onPcm(pcm_.data(), pcm_.size() / 2);
    pcm_.clear();
}

void FmMpx::pllAndMix(const float* mpx, size_t n) {
    sumIn_.resize(n);
    diffIn_.resize(n);
    zr_.resize(n);
    zi_.resize(n);
    cycBuf_.resize(n);

    const float ampAlpha = 0.0015f;
    for (size_t i = 0; i < n; ++i) {
        // DC engelleyici (~8 Hz)
        const float x = mpx[i];
        const float y = x - dcX_ + 0.9998f * dcY_;
        dcX_ = x;
        dcY_ = y;

        const float s = static_cast<float>(std::sin(theta_));
        const float c = static_cast<float>(std::cos(theta_));

        // Pilotu dar bant süzgeçle ayır (program sesi faz dedektörünü kirletmesin)
        const float pilot = pilotBpf2_.process(pilotBpf1_.process(y));

        // Faz dedektörü ve genlik kestirimi (pilot = A sin(theta))
        const float pd = pilot * c;
        const float q = pilot * s;
        qAvg_ += ampAlpha * (q - qAvg_);
        pdAvg_ += ampAlpha * (pd - pdAvg_);
        amp_ = 2.0f * qAvg_;

        float e = 0.0f;
        const float ampMag = std::fabs(amp_);
        if (ampMag > 0.012f) {
            e = pd / (0.5f * ampMag);
            // amp < 0: PLL 180 derece ters (kararsız) dengede; döngüyü oradan çıkar
            if (amp_ < 0.0f) e += 0.5f;
            if (e > 3.0f) e = 3.0f;
            if (e < -3.0f) e = -3.0f;
        } else {
            integ_ *= 0.9995f;  // pilot yok: nominal frekansa dön
        }
        integ_ += ki_ * e;
        const double dTheta = w0_ + static_cast<double>(kp_ * e + integ_);
        theta_ += dTheta;
        if (theta_ >= kTwoPi) theta_ -= kTwoPi;
        cycles_ += dTheta / kTwoPi;

        // Kilit tespiti (histerezisli)
        const float errEst = (amp_ > 1e-6f) ? pdAvg_ / (0.5f * amp_) : 1.0f;
        errEst_ = errEst;
        if (!locked_) {
            if (amp_ > 0.02f && std::fabs(errEst) < 0.25f) {
                if (++lockCnt_ > 5000) {
                    locked_ = true;
                    unlockCnt_ = 0;
                }
            } else {
                lockCnt_ = 0;
            }
        } else {
            if (amp_ < 0.01f || std::fabs(errEst) > 0.6f) {
                if (++unlockCnt_ > 2000) {
                    locked_ = false;
                    lockCnt_ = 0;
                }
            } else {
                unlockCnt_ = 0;
            }
        }

        // Stereo fark taşıyıcısı 2*sin(2θ) ve RDS taşıyıcısı e^{-j3θ}
        const float sc = s * c;
        sumIn_[i] = y;
        diffIn_[i] = y * 4.0f * sc;
        const float cos3 = 4.0f * c * c * c - 3.0f * c;
        const float sin3 = 3.0f * s - 4.0f * s * s * s;
        zr_[i] = y * cos3;
        zi_[i] = -y * sin3;
        cycBuf_[i] = cycles_;
    }
}

void FmMpx::audioStage() {
    sumOut_.clear();
    diffOut_.clear();
    sumFir_.process(sumIn_.data(), sumIn_.size(), sumOut_);
    diffFir_.process(diffIn_.data(), diffIn_.size(), diffOut_);
    const size_t m = std::min(sumOut_.size(), diffOut_.size());
    pcm_.reserve(m * 2);
    const float target = (locked_ && !forceMono_) ? 1.0f : 0.0f;
    for (size_t k = 0; k < m; ++k) {
        blend_ += 0.003f * (target - blend_);
        const float sum = sumOut_[k];
        const float diff = diffOut_[k] * blend_;
        const float l = sum + diff;
        const float r = sum - diff;
        deL_ += deAlpha_ * (l - deL_);
        deR_ += deAlpha_ * (r - deR_);
        auto toPcm = [](float v) -> int16_t {
            float x = v * 32767.0f;
            if (x > 32767.0f) x = 32767.0f;
            if (x < -32768.0f) x = -32768.0f;
            return static_cast<int16_t>(x);
        };
        pcm_.push_back(toPcm(deL_));
        pcm_.push_back(toPcm(deR_));
    }
}

void FmMpx::rdsStage() {
    zrOut_.clear();
    ziOut_.clear();
    zrIdx_.clear();
    ziIdx_.clear();
    zrFir_.process(zr_.data(), zr_.size(), zrOut_, &zrIdx_);
    ziFir_.process(zi_.data(), zi_.size(), ziOut_, &ziIdx_);
    const size_t m = std::min(zrOut_.size(), ziOut_.size());

    for (size_t k = 0; k < m; ++k) {
        const double zr = zrOut_[k], zi = ziOut_[k];

        // BPSK eksenini kestir: z^2 ortalamasının açısının yarısı
        sRe_ = sRe_ * (1.0 - 1.0 / 4000.0) + (zr * zr - zi * zi);
        sIm_ = sIm_ * (1.0 - 1.0 / 4000.0) + 2.0 * zr * zi;
        double phiNew = 0.5 * std::atan2(sIm_, sRe_);
        if (!phiInit_) {
            phi_ = phiNew;
            phiInit_ = true;
        } else {
            // π belirsizliğinde sıçramayı önle (bir önceki değere en yakın olanı seç)
            while (phiNew - phi_ > kPi / 2) phiNew -= kPi;
            while (phiNew - phi_ < -kPi / 2) phiNew += kPi;
            phi_ = phiNew;
        }
        const double r = zr * std::cos(phi_) + zi * std::sin(phi_);

        // Pilot çevrimlerine göre bin'leme (zaman yerine faz üzerinden integral)
        const double cycNow = cycBuf_[zrIdx_[k]];
        if (cycPrevValid_) {
            double a = cycPrev_;
            const double b = cycNow;
            if (b > a && b - a < 2.0) {
                for (;;) {
                    const long ia = static_cast<long>(std::floor(a));
                    const double edge = static_cast<double>(ia + 1);
                    if (b <= edge) {
                        acc_ += r * (b - a);
                        break;
                    }
                    acc_ += r * (edge - a);
                    closeBin(ia);
                    a = edge;
                }
            }
        }
        cycPrev_ = cycNow;
        cycPrevValid_ = true;
    }
}

void FmMpx::closeBin(long cycle) {
    sums_[static_cast<unsigned long>(cycle) & 15u] = acc_;
    acc_ = 0.0;
    ++binsClosed_;

    // Son 16 pilot çevrimi = 1 bit penceresi; bifaz: ilk yarı - ikinci yarı
    double first = 0.0, second = 0.0;
    for (int i = 0; i < 8; ++i) first += sums_[static_cast<unsigned long>(cycle - 15 + i) & 15u];
    for (int i = 8; i < 16; ++i) second += sums_[static_cast<unsigned long>(cycle - 15 + i) & 15u];
    const double b = first - second;

    const int slot = static_cast<int>(static_cast<unsigned long>(cycle) & 15u);
    energy_[slot] = 0.995 * energy_[slot] + b * b;

    if (slot == bestK_) {
        const int y = (b > 0.0) ? 1 : 0;
        const int bit = y ^ prevY_;
        prevY_ = y;
        if (onRdsBit) onRdsBit(bit);
    }

    if (slot == 15) {
        int k = 0;
        for (int i = 1; i < 16; ++i)
            if (energy_[i] > energy_[k]) k = i;
        if (k != bestK_ && energy_[k] > 1.3 * energy_[bestK_]) bestK_ = k;
    }
}

}  // namespace dr
