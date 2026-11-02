// Küçük DSP yardımcıları: Kaiser pencereli sinc FIR tasarımı, azaltan (decimating)
// FIR süzgeç ve basit radix-2 FFT. Dış bağımlılık yok.
#pragma once

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace dr {

constexpr double kPi = 3.14159265358979323846;

inline double besselI0(double x) {
    double sum = 1.0, term = 1.0;
    for (int k = 1; k < 60; ++k) {
        const double h = x / (2.0 * k);
        term *= h * h;
        sum += term;
        if (term < 1e-14 * sum) break;
    }
    return sum;
}

// Kaiser pencereli sinc alçak geçiren süzgeç. cutoff: çevrim/örnek (0..0.5).
// DC kazancı `gain` olacak şekilde normalize edilir.
inline std::vector<float> designLowpass(int taps, double cutoff, double beta, double gain = 1.0) {
    std::vector<double> d(static_cast<size_t>(taps));
    const double m = (taps - 1) / 2.0;
    double sum = 0.0;
    const double i0b = besselI0(beta);
    for (int n = 0; n < taps; ++n) {
        const double x = n - m;
        const double s = (std::fabs(x) < 1e-9) ? 2.0 * cutoff : std::sin(2.0 * kPi * cutoff * x) / (kPi * x);
        const double r = (m > 0.0) ? x / m : 0.0;
        const double w = besselI0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0b;
        d[static_cast<size_t>(n)] = s * w;
        sum += d[static_cast<size_t>(n)];
    }
    std::vector<float> h(static_cast<size_t>(taps));
    for (int n = 0; n < taps; ++n) h[static_cast<size_t>(n)] = static_cast<float>(d[static_cast<size_t>(n)] / sum * gain);
    return h;
}

// Nokta çarpımı (FIR iç döngüsü). Toplama sırası sabit olduğundan sonuç tekrarlanabilirdir; 4'lü SIMD
// (ARM NEON / x86 SSE, GCC/Clang vektör uzantısı) ve 2 bağımsız toplayıcı kullanır. Skaler döngü, her toplamın
// bir öncekini beklemesi yüzünden ~4 kat yavaştır: gerçek zamanlı FM için kritik.
typedef float v4f __attribute__((vector_size(16), aligned(4)));

inline float dotProduct(const float* x, const float* h, size_t n) {
    v4f a0 = {0, 0, 0, 0}, a1 = {0, 0, 0, 0};
    size_t k = 0;
    for (; k + 8 <= n; k += 8) {
        v4f x0, x1, h0, h1;
        __builtin_memcpy(&x0, x + k, 16);
        __builtin_memcpy(&x1, x + k + 4, 16);
        __builtin_memcpy(&h0, h + k, 16);
        __builtin_memcpy(&h1, h + k + 4, 16);
        a0 += x0 * h0;
        a1 += x1 * h1;
    }
    a0 += a1;
    float acc = a0[0] + a0[1] + a0[2] + a0[3];
    for (; k < n; ++k) acc += x[k] * h[k];
    return acc;
}

// Gerçek girişli, gerçek katsayılı azaltan FIR. Akış (streaming) çalışır:
// ardışık çağrılar arasında durumu korur.
class FirDecimator {
public:
    FirDecimator() = default;
    FirDecimator(std::vector<float> taps, int decim) { init(std::move(taps), decim); }

    void init(std::vector<float> taps, int decim) {
        h_ = std::move(taps);
        decim_ = std::max(1, decim);
        state_.assign(h_.size() - 1, 0.0f);
        next_ = 0;
    }

    size_t taps() const { return h_.size(); }

    void reset() {
        std::fill(state_.begin(), state_.end(), 0.0f);
        next_ = 0;
    }

    // in[0..n) işlenir, çıkışlar out'a eklenir. endIdx verilirse her çıkış için,
    // pencerenin son örneğinin bu bloktaki indeksi eklenir.
    void process(const float* in, size_t n, std::vector<float>& out, std::vector<size_t>* endIdx = nullptr) {
        const size_t nt = h_.size();
        tmp_.resize(state_.size() + n);
        std::copy(state_.begin(), state_.end(), tmp_.begin());
        std::copy(in, in + n, tmp_.begin() + static_cast<std::ptrdiff_t>(state_.size()));
        const size_t total = tmp_.size();
        size_t j = next_;
        const float* h = h_.data();
        while (j + nt <= total) {
            out.push_back(dotProduct(tmp_.data() + j, h, nt));
            if (endIdx) endIdx->push_back(j);
            j += static_cast<size_t>(decim_);
        }
        const size_t keepFrom = total - (nt - 1);
        state_.assign(tmp_.begin() + static_cast<std::ptrdiff_t>(keepFrom), tmp_.end());
        next_ = j - keepFrom;
    }

private:
    std::vector<float> h_;
    int decim_ = 1;
    std::vector<float> state_;
    std::vector<float> tmp_;
    size_t next_ = 0;
};

// RBJ band-geçiren (sabit 0 dB tepe) biquad, doğrudan form I.
class Biquad {
public:
    void designBandpass(double fs, double f0, double q) {
        const double w0 = 2.0 * kPi * f0 / fs;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        b0_ = static_cast<float>(alpha / a0);
        b2_ = static_cast<float>(-alpha / a0);
        a1_ = static_cast<float>(-2.0 * std::cos(w0) / a0);
        a2_ = static_cast<float>((1.0 - alpha) / a0);
        x1_ = x2_ = y1_ = y2_ = 0.0f;
    }
    float process(float x) {
        const float y = b0_ * x + b2_ * x2_ - a1_ * y1_ - a2_ * y2_;
        x2_ = x1_;
        x1_ = x;
        y2_ = y1_;
        y1_ = y;
        return y;
    }

private:
    float b0_ = 0, b2_ = 0, a1_ = 0, a2_ = 0;
    float x1_ = 0, x2_ = 0, y1_ = 0, y2_ = 0;
};

// Basit yinelemesiz radix-2 FFT (n 2'nin kuvveti). Bant taraması için yeterli.
class Fft {
public:
    explicit Fft(size_t n) : n_(n), tw_(n / 2), rev_(n) {
        for (size_t i = 0; i < n / 2; ++i) {
            const double a = -2.0 * kPi * static_cast<double>(i) / static_cast<double>(n);
            tw_[i] = std::complex<float>(static_cast<float>(std::cos(a)), static_cast<float>(std::sin(a)));
        }
        size_t bits = 0;
        while ((static_cast<size_t>(1) << bits) < n) ++bits;
        for (size_t i = 0; i < n; ++i) {
            size_t r = 0;
            for (size_t b = 0; b < bits; ++b)
                if (i & (static_cast<size_t>(1) << b)) r |= static_cast<size_t>(1) << (bits - 1 - b);
            rev_[i] = r;
        }
    }

    size_t size() const { return n_; }

    void transform(std::vector<std::complex<float>>& x) const {
        for (size_t i = 0; i < n_; ++i)
            if (i < rev_[i]) std::swap(x[i], x[rev_[i]]);
        for (size_t len = 2; len <= n_; len <<= 1) {
            const size_t half = len / 2, step = n_ / len;
            for (size_t i = 0; i < n_; i += len) {
                for (size_t k = 0; k < half; ++k) {
                    const std::complex<float> t = x[i + k + half] * tw_[k * step];
                    x[i + k + half] = x[i + k] - t;
                    x[i + k] += t;
                }
            }
        }
    }

private:
    size_t n_;
    std::vector<std::complex<float>> tw_;
    std::vector<size_t> rev_;
};

}  // namespace dr
