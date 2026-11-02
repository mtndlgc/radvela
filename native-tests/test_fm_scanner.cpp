// FmScanner testi: sentetik çok istasyonlu bant
#include <cstdio>
#include <string>
#include <random>
#include <cmath>
#include "../app/src/main/cpp/core/fm_scanner.h"
struct St { double mhz; double amp; double dev; double afreq; };
int main(int argc, char** argv) {
    const double fs = 2048000.0;
    const bool dense = argc > 1 && std::string(argv[1]) == "dense";
    std::vector<St> st = {{87.5, 0.10, 50e3, 700}, {89.3, 0.30, 60e3, 1000}, {95.7, 0.05, 60e3, 1300}, {95.9, 0.02, 60e3, 1500},
                          {99.9, 0.012, 50e3, 900}, {101.1, 0.15, 70e3, 400}, {107.9, 0.08, 50e3, 800}, {108.0, 0.06, 50e3, 650}};
    if (dense) {  // büyük şehir: ~30 istasyon, 0.2 MHz ve üstü aralıklı, geniş güç aralığı
        st.clear();
        std::mt19937 g(11);
        std::uniform_real_distribution<double> u(0.0, 1.0);
        for (int ch = 0; ch <= 205; ch += 6 + static_cast<int>(u(g) * 4)) {
            const double amp = 0.004 * std::pow(10.0, u(g) * 2.2);  // 0.004 .. 0.63
            st.push_back({87.5 + ch * 0.1, amp, 40e3 + u(g) * 30e3, 300 + u(g) * 1500});
        }
    }
    std::mt19937 rng(7); std::normal_distribution<float> nd(0.f, 0.008f);
    long calls = 0;
    auto reader = [&](int center, size_t count, std::vector<std::complex<float>>& out) {
        ++calls; out.assign(count, {0, 0});
        for (const auto& s : st) {
            const double off = s.mhz * 1e6 - center;
            if (std::fabs(off) > 1.0e6) continue;   // RTL kenar sönümü sonrası pratik sınır
            // R820T FI süzgeci (1.53 MHz): ±765 kHz'de -3 dB, dışarıda dik düşüş
            const double resp = 1.0 / std::sqrt(1.0 + std::pow(std::fabs(off) / 765e3, 8.0));
            double ph = 0;
            for (size_t n = 0; n < count; ++n) {
                const double t = n / fs;
                const double inst = off + s.dev * std::sin(2 * M_PI * s.afreq * t);
                ph += 2 * M_PI * inst / fs;
                out[n] += std::complex<float>(static_cast<float>(s.amp * resp * std::cos(ph)), static_cast<float>(s.amp * resp * std::sin(ph)));
            }
        }
        for (auto& v : out) v += std::complex<float>(nd(rng), nd(rng));
        return true;
    };
    std::atomic<bool> cancel{false};
    int lastp = 0;
    auto res = dr::FmScanner::scan(reader, cancel, [&](int p) { lastp = p; });
    printf("tuning calls=%ld, progress=%d, found=%zu (gercek %zu)\n", calls, lastp, res.size(), st.size());
    if (dense) {
        int hit = 0, miss = 0, falsep = 0;
        for (const auto& t : st) { bool f = false; for (auto& r : res) if (std::abs(r.freqHz - static_cast<int>(std::lround(t.mhz * 1e6))) < 1000) f = true; (f ? hit : miss)++; if (!f) printf("  KACTI %.1f MHz amp=%.4f\n", t.mhz, t.amp); }
        for (auto& r : res) { bool f = false; for (const auto& t : st) if (std::abs(r.freqHz - static_cast<int>(std::lround(t.mhz * 1e6))) < 1000) f = true; if (!f) { ++falsep; printf("  SAHTE %.1f MHz %.1f dB\n", r.freqHz / 1e6, r.strengthDb); } }
        printf("bulunan=%d kacan=%d sahte=%d\n", hit, miss, falsep);
    }
    for (auto& s : res) printf("  %.1f MHz  %.1f dB\n", s.freqHz / 1e6, s.strengthDb);
    return 0;
}
