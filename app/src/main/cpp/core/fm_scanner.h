// FM bandı tarayıcı: 2.048 MHz'lik pencereleri FFT ile tarayıp 100 kHz'lik kanal ızgarasındaki
// istasyonları bulur (87.5 - 108.0 MHz). Donanım erişimi `Reader` ile soyutlanmıştır.
#pragma once

#include <atomic>
#include <complex>
#include <functional>
#include <vector>

namespace dr {

struct FmStation {
    int freqHz = 0;
    float strengthDb = 0.0f;  // bu pencerenin gürültü tabanına göre güç (dB)
};

class FmScanner {
public:
    // centerHz'e ayarlanıp en az `count` örnek okunur (settle süresi okuyucunun işi). Başarısızsa false.
    using Reader = std::function<bool(int centerHz, size_t count, std::vector<std::complex<float>>& out)>;

    static constexpr int kBandStartHz = 87500000;
    static constexpr int kBandEndHz = 108000000;
    static constexpr int kStepHz = 100000;

    // progress: 0..100. Bulunan istasyonlar frekansa göre sıralı döner.
    static std::vector<FmStation> scan(const Reader& reader, const std::atomic<bool>& cancel,
                                       const std::function<void(int)>& progress = nullptr,
                                       float thresholdDb = 8.0f);
};

}  // namespace dr
