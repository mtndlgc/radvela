// FM ön uç: 2.048 MS/s IQ -> 256 kS/s kompozit (MPX).
//
// Donanım, istasyonun +256 kHz üzerine ayarlanır (RTL-SDR DC çıkıntısı kanal dışında kalır);
// yazılımla -256 kHz kaydırılır, kanal süzgecinden geçirilir, 8'e azaltılır ve
// açı farkı (quadrature) ayrımlayıcıyla demodüle edilir.
#pragma once

#include <complex>
#include <cstddef>
#include <vector>

#include "dsp_util.h"

namespace dr {

class FmFrontEnd {
public:
    static constexpr int kInputRate = 2048000;
    static constexpr int kDecim = 8;
    static constexpr int kLoOffsetHz = 256000;  // = kInputRate / 8

    FmFrontEnd();
    void reset();

    // iq: normalize edilmiş IQ (-1..1). Üretilen kompozit örnekleri mpx'e eklenir.
    void process(const std::complex<float>* iq, size_t n, std::vector<float>& mpx);

    // Kanal süzgeci çıkışındaki ortalama güç (dBFS)
    float levelDb() const;

private:
    FirDecimator fi_, fq_;
    std::vector<float> inI_, inQ_, outI_, outQ_;
    unsigned phase_ = 0;
    std::complex<float> prev_{1.0f, 0.0f};
    float power_ = 1e-6f;
};

}  // namespace dr
