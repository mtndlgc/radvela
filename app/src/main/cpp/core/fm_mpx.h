// FM kompozit (MPX) sinyal işleyici.
//
// Girdi: 256 kS/s gerçek kompozit sinyal (1.0 = 75 kHz sapma).
// Çıktı: 32 kS/s stereo PCM (19 kHz pilot PLL'li stereo çözme, de-emphasis)
//        ve RDS bit akışı (diferansiyel çözülmüş).
//
// RDS taşıyıcısı ve bit saati pilotun 3. harmoniğine / pilot çevrimlerine kilitlenir
// (RDS standardı gereği 57 kHz = 3 x 19 kHz, bit hızı = 19 kHz / 16).
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

#include "dsp_util.h"

namespace dr {

class FmMpx {
public:
    static constexpr int kFs = 256000;
    static constexpr int kAudioDecim = 8;
    static constexpr int kAudioRate = kFs / kAudioDecim;  // 32000

    FmMpx();

    void setForceMono(bool mono) { forceMono_ = mono; }
    void setDeemphasisMicros(float us);  // 50 (Avrupa/Türkiye/Azerbaycan) veya 75 (Amerika)

    void process(const float* mpx, size_t n);

    // Her process() sonunda, üretilen PCM (L R L R ... int16) ile çağrılır.
    std::function<void(const int16_t*, size_t /*frames*/)> onPcm;
    // Diferansiyel çözülmüş RDS biti (0/1)
    std::function<void(int)> onRdsBit;

    bool pilotLocked() const { return locked_; }
    float pilotAmplitude() const { return amp_; }
    float stereoBlend() const { return blend_; }
    float phaseError() const { return errEst_; }

private:
    void pllAndMix(const float* mpx, size_t n);
    void audioStage();
    void rdsStage();
    void closeBin(long cycle);

    // Giriş
    float dcX_ = 0.0f, dcY_ = 0.0f;

    // PLL
    Biquad pilotBpf1_, pilotBpf2_;
    double theta_ = 0.0;
    double cycles_ = 0.0;  // açılmamış (unwrapped) pilot çevrim sayısı
    float integ_ = 0.0f;
    float qAvg_ = 0.0f, pdAvg_ = 0.0f, amp_ = 0.0f, errEst_ = 0.0f;
    bool locked_ = false;
    int lockCnt_ = 0, unlockCnt_ = 0;
    float kp_, ki_;
    double w0_;

    // Blok arabellekleri
    std::vector<float> sumIn_, diffIn_, zr_, zi_;
    std::vector<double> cycBuf_;

    // Ses yolu
    FirDecimator sumFir_, diffFir_;
    std::vector<float> sumOut_, diffOut_;
    float deL_ = 0.0f, deR_ = 0.0f, deAlpha_;
    float blend_ = 0.0f;
    bool forceMono_ = false;
    std::vector<int16_t> pcm_;

    // RDS yolu
    FirDecimator zrFir_, ziFir_;
    std::vector<float> zrOut_, ziOut_;
    std::vector<size_t> zrIdx_, ziIdx_;
    double sRe_ = 0.0, sIm_ = 0.0;  // z^2 ortalaması (BPSK ekseni)
    double phi_ = 0.0;
    bool phiInit_ = false;
    double cycPrev_ = 0.0;
    bool cycPrevValid_ = false;
    double acc_ = 0.0;
    double sums_[16] = {0};
    double energy_[16] = {0};
    int bestK_ = 0;
    int prevY_ = 0;
    long binsClosed_ = 0;
};

}  // namespace dr
