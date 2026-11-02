#include "rtlsdr_usb_source.h"

#include <chrono>
#include <cstring>

#include "log.h"
#include "rtl-sdr.h"

namespace oto {

namespace {
constexpr uint32_t kSampleRate = 2048000;
constexpr uint32_t kChunkIq = 2048 * 32;          // 32 ms
constexpr uint32_t kBandwidthHz = 1530000;        // DAB için tuner bant genişliği
constexpr int kRestartBuffers = 3;                // ayar sonrası atılan/yok sayılan tampon sayısı (3 x 32 ms)
constexpr int kAgcFastTicks = 24;                 // ayardan sonra hızlı AGC turu sayısı (24 x 96 ms)
}  // namespace

RtlsdrUsbSource::RtlsdrUsbSource(SampleFifo& fifo) : fifo_(fifo) {}

RtlsdrUsbSource::~RtlsdrUsbSource() { close(); }

bool RtlsdrUsbSource::open(int fd, std::string* error) {
    close();
    auto fail = [&](const char* msg) {
        if (error) *error = msg;
        if (dev_) {
            rtlsdr_close(dev_);
            dev_ = nullptr;
        }
        return false;
    };

    rtlsdr_dev_t* d = nullptr;
    const int orc = rtlsdr_open_fd(&d, fd);
    logf("rtlsdr_open_fd(fd=%d) -> %d", fd, orc);
    if (orc < 0 || !d) return fail("@nat_usb_open");
    dev_ = d;

    logf("rtlsdr ornekleme hizi ayarlaniyor");
    if (rtlsdr_set_sample_rate(dev_, kSampleRate) < 0) return fail("@nat_sample_rate");

    const int tuner = rtlsdr_get_tuner_type(dev_);
    const char* name = "bilinmeyen";
    switch (tuner) {
        case RTLSDR_TUNER_E4000: name = "E4000"; break;
        case RTLSDR_TUNER_FC0012: name = "FC0012"; break;
        case RTLSDR_TUNER_FC0013: name = "FC0013"; break;
        case RTLSDR_TUNER_FC2580: name = "FC2580"; break;
        case RTLSDR_TUNER_R820T: name = "R820T"; break;
        case RTLSDR_TUNER_R828D: name = "R828D"; break;
        default: break;
    }
    desc_ = std::string("RTL-SDR (") + name + ")";
    logf("tuner: %s", name);

    // Bant genişliği ayarı bir kez tuner'ın ayarlanmış olmasını gerektirir
    rtlsdr_set_center_freq(dev_, 200000000);
    uint32_t applied = 0;
    rtlsdr_set_and_get_tuner_bandwidth(dev_, kBandwidthHz, &applied, 1);

    const int count = rtlsdr_get_tuner_gains(dev_, nullptr);
    std::vector<int> gains(static_cast<size_t>(count > 0 ? count : 0));
    if (count > 0) rtlsdr_get_tuner_gains(dev_, gains.data());
    fe_.setGains(gains);
    logf("tuner kazanc sayisi=%d bant=%u Hz", count, applied);

    // Elle kazanç modu; AGC yazılımda (AbracaDABra ile aynı)
    rtlsdr_set_tuner_gain_mode(dev_, 1);
    fe_.setSoftwareAgc(true);
    applyGain(fe_.resetGainIdx());

    conv_.assign(static_cast<size_t>(kChunkIq) * 2, 0.0f);
    agcRun_ = true;
    agcThread_ = std::thread(&RtlsdrUsbSource::agcLoop, this);
    return true;
}

void RtlsdrUsbSource::close() {
    stopStreaming();
    agcRun_ = false;
    if (agcThread_.joinable()) agcThread_.join();
    std::lock_guard<std::mutex> lk(mtx_);
    if (dev_) {
        rtlsdr_close(dev_);
        dev_ = nullptr;
    }
}

void RtlsdrUsbSource::applyGain(int idx) {
    idx = fe_.clampIdx(idx);
    fe_.storeGainIdx(idx);
    if (dev_) rtlsdr_set_tuner_gain(dev_, fe_.gainTenthDb(idx));
}

void RtlsdrUsbSource::setSoftwareAgc(bool on) { fe_.setSoftwareAgc(on); }

void RtlsdrUsbSource::setGainIndex(int index) {
    fe_.setSoftwareAgc(false);
    std::lock_guard<std::mutex> lk(mtx_);
    applyGain(index);
}

void RtlsdrUsbSource::setPpm(int ppm) {
    ppm_ = ppm;
    std::lock_guard<std::mutex> lk(mtx_);
    if (dev_) rtlsdr_set_freq_correction(dev_, ppm);  // aynı değerde -2 döner; zararsız
}

void RtlsdrUsbSource::tuneHz(uint32_t hz) {
    std::lock_guard<std::mutex> lk(mtx_);
    if (!dev_) return;
    logf("tuneHz %u (akis=%d)", hz, streaming_.load() ? 1 : 0);
    freq_ = hz;
    rtlsdr_set_center_freq(dev_, hz);
    if (fe_.softwareAgc()) applyGain(fe_.resetGainIdx());
    fe_.resetAgcLevel();
    agcFastTicks_ = kAgcFastTicks;
    restartCounter_ = kRestartBuffers;
    if (!streaming_) startStreamingLocked();
}

void RtlsdrUsbSource::startStreamingLocked() {
    if (streaming_ || !dev_) return;
    if (readThread_.joinable()) readThread_.join();  // önceki okuyucu hata ile bittiyse
    rtlsdr_reset_buffer(dev_);
    streaming_ = true;
    readThread_ = std::thread(&RtlsdrUsbSource::readLoop, this);
}

void RtlsdrUsbSource::stopStreaming() {
    {
        std::lock_guard<std::mutex> lk(mtx_);
        if (streaming_ && dev_) rtlsdr_cancel_async(dev_);
    }
    if (readThread_.joinable()) readThread_.join();
    streaming_ = false;
}

void RtlsdrUsbSource::readLoop() {
    rtlsdr_dev_t* d = dev_;
    const int r = rtlsdr_read_async(d, &RtlsdrUsbSource::asyncCallback, this, 16, kChunkIq * 2);
    streaming_ = false;
    logf("rtlsdr_read_async bitti: %d", r);
    if (r < 0 && onError) onError("@nat_usb_read");
}

void RtlsdrUsbSource::asyncCallback(unsigned char* buf, uint32_t len, void* ctx) {
    static_cast<RtlsdrUsbSource*>(ctx)->process(buf, len);
}

void RtlsdrUsbSource::process(const unsigned char* buf, uint32_t len) {
    int rc = restartCounter_.load();
    if (rc > 0) {
        restartCounter_ = rc - 1;
        if (rc - 1 > 0) return;  // ilk tampon(lar) atılır
        // Kanal karışmasın: tamponu temizle, DC kestirimini sıfırla
        fifo_.reset();
        fe_.resetDoc();
        logf("akis oturdu: %u Hz, fifo=%zu", freq_.load(), fifo_.available());
        if (onTuned) onTuned(freq_.load());
    }
    if (conv_.size() < len) conv_.resize(len);
    fe_.convert(buf, len, conv_.data());
    fifo_.write(conv_.data(), len);
}

void RtlsdrUsbSource::agcLoop() {
    int tick = 0;
    while (agcRun_) {
        // Ayardan hemen sonra hızlı (2 tampondan uzun: kazanç değişimi bir sonraki ölçüme yansısın), sonra yavaş
        const bool fast = agcFastTicks_ > 0;
        std::this_thread::sleep_for(std::chrono::milliseconds(fast ? 96 : 256));
        if (fast) --agcFastTicks_;
        if (!streaming_ || restartCounter_ > 0) continue;
        const int before = fe_.gainIdx();
        const int idx = fe_.agcDecision();
        if (idx >= 0) {
            std::lock_guard<std::mutex> lk(mtx_);
            if (dev_ && fe_.softwareAgc()) applyGain(idx);
            logf("AGC seviye=%.0f kazanc idx %d -> %d (%.1f dB)", fe_.agcLevel(), before, fe_.gainIdx(), fe_.gainDb());
            tick = 0;
        } else if (++tick >= (fast ? 10 : 8)) {
            tick = 0;
            logf("AGC seviye=%.0f kazanc idx %d (%.1f dB) sabit, tasma=%llu", fe_.agcLevel(), fe_.gainIdx(), fe_.gainDb(),
                 static_cast<unsigned long long>(fifo_.dropped()));
        }
    }
}

}  // namespace oto
