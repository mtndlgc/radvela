#include "fm_scanner.h"

#include <algorithm>
#include <cmath>

#include "dsp_util.h"
#include "log.h"

namespace dr {

namespace {
constexpr int kFs = 2048000;
constexpr size_t kFftSize = 8192;
constexpr int kAverages = 8;
constexpr int kWindowHz = 1000000;            // kullanılan merkez genişliği: tuner süzgeci (1.53 MHz) düz bölgesinde kalır
constexpr int kCenterStepHz = kWindowHz;      // pencere kaydırma adımı
constexpr int kFirstCenterHz = 87950000;      // ızgaradan 50 kHz kayık: DC çıkıntısı iki kanalın arasına düşer
constexpr double kBinHz = static_cast<double>(kFs) / kFftSize;

float percentile(std::vector<float> v, float p) {
    if (v.empty()) return 0.0f;
    std::sort(v.begin(), v.end());
    const size_t idx = static_cast<size_t>(p * static_cast<float>(v.size() - 1));
    return v[idx];
}
}  // namespace

std::vector<FmStation> FmScanner::scan(const Reader& reader, const std::atomic<bool>& cancel,
                                       const std::function<void(int)>& progress, float thresholdDb) {
    const int numChannels = (kBandEndHz - kBandStartHz) / kStepHz + 1;  // 206
    std::vector<float> power(static_cast<size_t>(numChannels), -200.0f);  // dB, pencerenin tabanına göre
    std::vector<float> dist(static_cast<size_t>(numChannels), 1e12f);
    std::vector<bool> have(static_cast<size_t>(numChannels), false);

    Fft fft(kFftSize);
    std::vector<double> win(kFftSize);
    for (size_t i = 0; i < kFftSize; ++i) win[i] = 0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(i) / (kFftSize - 1));

    int numCenters = 0;
    for (int c = kFirstCenterHz; c - kWindowHz / 2 < kBandEndHz; c += kCenterStepHz) ++numCenters;

    int doneCenters = 0;
    for (int center = kFirstCenterHz; center - kWindowHz / 2 < kBandEndHz; center += kCenterStepHz) {
        if (cancel) return {};
        std::vector<std::complex<float>> iq;
        if (!reader(center, kFftSize * kAverages, iq) || iq.size() < kFftSize) {
            ++doneCenters;
            continue;
        }

        std::vector<double> psd(kFftSize, 0.0);
        const size_t blocks = std::min<size_t>(kAverages, iq.size() / kFftSize);
        std::vector<std::complex<float>> buf(kFftSize);
        for (size_t b = 0; b < blocks; ++b) {
            for (size_t i = 0; i < kFftSize; ++i) buf[i] = iq[b * kFftSize + i] * static_cast<float>(win[i]);
            fft.transform(buf);
            for (size_t i = 0; i < kFftSize; ++i) psd[i] += std::norm(buf[i]);
        }

        // DC çıkıntısını komşu bin'lerle yumuşat (±3 kHz)
        const int dcBins = static_cast<int>(3000.0 / kBinHz) + 1;
        const double edgeL = psd[kFftSize - dcBins - 1], edgeR = psd[dcBins + 1];
        for (int k = -dcBins; k <= dcBins; ++k) {
            const size_t idx = static_cast<size_t>((k + static_cast<int>(kFftSize)) % static_cast<int>(kFftSize));
            psd[idx] = 0.5 * (edgeL + edgeR);
        }

        // Bu pencerenin kanallarının gücü: ±35 kHz üzerinden toplam (komşu kanal taşmasını azaltmak için dar tutuldu)
        std::vector<int> idxs;
        std::vector<float> chp;
        for (int ch = 0; ch < numChannels; ++ch) {
            const int f = kBandStartHz + ch * kStepHz;
            const int off = f - center;
            if (std::abs(off) > kWindowHz / 2) continue;
            double sum = 0.0;
            const int lo = static_cast<int>(std::lround((off - 35000) / kBinHz));
            const int hi = static_cast<int>(std::lround((off + 35000) / kBinHz));
            for (int k = lo; k < hi; ++k) {
                const size_t idx = static_cast<size_t>(((k % static_cast<int>(kFftSize)) + static_cast<int>(kFftSize)) % static_cast<int>(kFftSize));
                sum += psd[idx];
            }
            idxs.push_back(ch);
            chp.push_back(10.0f * std::log10(static_cast<float>(sum) + 1e-20f));
        }
        if (chp.empty()) continue;
        const float floorDb = percentile(chp, 0.2f);  // gürültü tabanı: alt %20
        {
            size_t best = 0;
            for (size_t i = 1; i < chp.size(); ++i)
                if (chp[i] > chp[best]) best = i;
            oto::logf("FM pencere %.2f MHz: taban=%.1f dB, en guclu %.1f MHz (+%.1f dB)", center / 1e6, floorDb,
                      (kBandStartHz + idxs[best] * kStepHz) / 1e6, chp[best] - floorDb);
        }
        for (size_t i = 0; i < idxs.size(); ++i) {
            const size_t ch = static_cast<size_t>(idxs[i]);
            const float d = static_cast<float>(std::abs(kBandStartHz + idxs[i] * kStepHz - center));
            if (d < dist[ch]) {  // bir kanal birden fazla pencerede görünüyorsa merkeze yakın olanı kullan
                dist[ch] = d;
                power[ch] = chp[i] - floorDb;
                have[ch] = true;
            }
        }

        ++doneCenters;
        if (progress) progress(100 * doneCenters / std::max(1, numCenters));
    }

    // Yerel maksimum ve eşik
    std::vector<FmStation> out;
    for (int ch = 0; ch < numChannels; ++ch) {
        const size_t c = static_cast<size_t>(ch);
        if (!have[c] || power[c] < thresholdDb) continue;
        const float left = (ch > 0 && have[c - 1]) ? power[c - 1] : -200.0f;
        const float right = (ch + 1 < numChannels && have[c + 1]) ? power[c + 1] : -200.0f;
        // Bitişik kanalın taşması sahte tepe üretmesin: komşudan en az 3 dB güçlü ya da eşit istasyon
        if (power[c] + 0.01f < left || power[c] < right) continue;
        oto::logf("FM istasyon %.1f MHz (+%.1f dB)", (kBandStartHz + ch * kStepHz) / 1e6, power[c]);
        FmStation s;
        s.freqHz = kBandStartHz + ch * kStepHz;
        s.strengthDb = power[c];
        out.push_back(s);
    }
    if (progress) progress(100);
    return out;
}

}  // namespace dr
