#include "fm_frontend.h"

#include <cmath>

namespace dr {

namespace {
// exp(+j 2π k / 8): -256 kHz'deki istasyonu 0'a getirir
const float kMixRe[8] = {1.0f, 0.70710678f, 0.0f, -0.70710678f, -1.0f, -0.70710678f, 0.0f, 0.70710678f};
const float kMixIm[8] = {0.0f, 0.70710678f, 1.0f, 0.70710678f, 0.0f, -0.70710678f, -1.0f, -0.70710678f};
// 1.0 = 75 kHz sapma
const float kDemodScale = static_cast<float>(FmFrontEnd::kInputRate / FmFrontEnd::kDecim / (2.0 * kPi * 75000.0));
}  // namespace

FmFrontEnd::FmFrontEnd() {
    // Geçiş bandı 100 kHz'e kadar, 156 kHz'den sonra >60 dB bastırma (bitişik kanallar)
    auto h = designLowpass(129, 128000.0 / kInputRate, 5.65);
    fi_.init(h, kDecim);
    fq_.init(h, kDecim);
}

void FmFrontEnd::reset() {
    fi_.reset();
    fq_.reset();
    phase_ = 0;
    prev_ = {1.0f, 0.0f};
    power_ = 1e-6f;
}

void FmFrontEnd::process(const std::complex<float>* iq, size_t n, std::vector<float>& mpx) {
    inI_.resize(n);
    inQ_.resize(n);
    unsigned ph = phase_;
    for (size_t i = 0; i < n; ++i) {
        const float re = iq[i].real(), im = iq[i].imag();
        inI_[i] = re * kMixRe[ph] - im * kMixIm[ph];
        inQ_[i] = re * kMixIm[ph] + im * kMixRe[ph];
        ph = (ph + 1) & 7u;
    }
    phase_ = ph;

    outI_.clear();
    outQ_.clear();
    fi_.process(inI_.data(), n, outI_);
    fq_.process(inQ_.data(), n, outQ_);

    const size_t m = outI_.size();
    mpx.reserve(mpx.size() + m);
    for (size_t k = 0; k < m; ++k) {
        const std::complex<float> c(outI_[k], outQ_[k]);
        const std::complex<float> d = c * std::conj(prev_);
        prev_ = c;
        mpx.push_back(std::atan2(d.imag(), d.real()) * kDemodScale);
        power_ += 0.0005f * (std::norm(c) - power_);
    }
}

float FmFrontEnd::levelDb() const { return 10.0f * std::log10(power_ + 1e-12f); }

}  // namespace dr
