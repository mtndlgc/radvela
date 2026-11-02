// DAB+ AAC çözümü için cihazın kendi çözücüsü (Android MediaCodec). Uygulama içinde AAC çözücü taşımamak içindir.
// Sistem çözücüsü DAB+ yapılandırmasını kabul etmezse nullptr dönülür; FAAD2'li yapıda çağıran ona düşer.
// Test: "adb shell setprop debug.radvela.faad 1" sistem çözücüsünü kapatır.
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <sys/system_properties.h>

#include <atomic>
#include <chrono>
#include <cstring>

#include "dabplus_decoder.h"
#include "log.h"

namespace {

constexpr const char* kMime = "audio/mp4a-latm";

class AACDecoderSystem : public AACDecoder {
public:
    AACDecoderSystem(SubchannelSinkObserver* obs, SuperframeFormat sf) : AACDecoder("MediaCodec", obs, sf) {
        const int rate = sf.dac_rate ? 48000 : 32000;
        channels_ = (sf.aac_channel_mode || sf.ps_flag) ? 2 : 1;

        AMediaFormat* fmt = AMediaFormat_new();
        AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, kMime);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, rate);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, channels_);
        AMediaFormat_setInt32(fmt, "is-adts", 0);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 8192);
        AMediaFormat_setBuffer(fmt, "csd-0", asc, asc_len);

        // Yazılım çözücüleri önce: donanım çözücüsü 960 örnekli çerçeveyi desteklemeyebilir
        for (const char* name : {"c2.android.aac.decoder", "OMX.google.aac.decoder"}) {
            if (tryOpen(AMediaCodec_createCodecByName(name), fmt, name)) break;
        }
        if (!codec_) tryOpen(AMediaCodec_createDecoderByType(kMime), fmt, "varsayilan");
        AMediaFormat_delete(fmt);
        if (codec_) {
            rate_ = rate;
            observer->StartAudio(rate_, channels_, false);
            last_ = std::chrono::steady_clock::now();
        }
    }

    ~AACDecoderSystem() override {
        if (codec_) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
        }
    }

    bool ok() const { return codec_ != nullptr; }

    void DecodeFrame(uint8_t* data, size_t len) override {
        const ssize_t idx = AMediaCodec_dequeueInputBuffer(codec_, 20000);
        if (idx < 0) {
            ++dropped_;
            drain(0);
            return;
        }
        size_t cap = 0;
        uint8_t* in = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(idx), &cap);
        if (!in || cap < len) {
            AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(idx), 0, 0, 0, 0);
            ++dropped_;
            return;
        }
        std::memcpy(in, data, len);
        AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(idx), 0, len, pts_, 0);
        pts_ += 20000;
        drain(1000);
    }

private:
    bool tryOpen(AMediaCodec* c, AMediaFormat* fmt, const char* name) {
        if (!c) return false;
        media_status_t st = AMediaCodec_configure(c, fmt, nullptr, nullptr, 0);
        if (st == AMEDIA_OK) st = AMediaCodec_start(c);
        if (st != AMEDIA_OK) {
            oto::logf("AAC sistem: %s acilamadi (%d)", name, static_cast<int>(st));
            AMediaCodec_delete(c);
            return false;
        }
        codec_ = c;
        oto::logf("AAC sistem: cozucu %s, ASC %zu bayt", name, asc_len);
        return true;
    }

    void drain(int64_t timeoutUs) {
        for (;;) {
            AMediaCodecBufferInfo info;
            const ssize_t idx = AMediaCodec_dequeueOutputBuffer(codec_, &info, timeoutUs);
            if (idx >= 0) {
                size_t cap = 0;
                uint8_t* out = AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(idx), &cap);
                if (out && info.size > 0) {
                    observer->PutAudio(out + info.offset, static_cast<size_t>(info.size));
                    stats(info.size);
                }
                AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(idx), false);
                timeoutUs = 0;
            } else if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* f = AMediaCodec_getOutputFormat(codec_);
                int32_t r = 0, c = 0;
                AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &r);
                AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &c);
                AMediaFormat_delete(f);
                oto::logf("AAC sistem: cikis bicimi %d Hz, %d kanal", r, c);
                if (r > 0 && c > 0) {
                    rate_ = r;
                    channels_ = c;
                    observer->StartAudio(rate_, channels_, false);
                }
            } else if (idx != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                return;
            }
        }
    }

    // Doğrulama: DAB+ çerçevesi 960 tabanlıdır; yanlış çözülürse çerçeve başına örnek ve saniyedeki örnek sayısı kayar
    void stats(int32_t bytes) {
        const long frames = bytes / (2 * channels_);
        sumFrames_ += frames;
        lastFrame_ = frames;
        const auto now = std::chrono::steady_clock::now();
        const double sec = std::chrono::duration<double>(now - last_).count();
        if (sec >= 10.0) {
            oto::logf("AAC sistem: %.0f ornek/sn (beklenen %d), cerceve %ld ornek, atilan %ld", sumFrames_ / sec, rate_, lastFrame_, dropped_);
            sumFrames_ = 0;
            last_ = now;
        }
    }

    AMediaCodec* codec_ = nullptr;
    int rate_ = 0;
    int channels_ = 2;
    int64_t pts_ = 0;
    long dropped_ = 0;
    long sumFrames_ = 0;
    long lastFrame_ = 0;
    std::chrono::steady_clock::time_point last_{};
};

}  // namespace

// Yedek çözücüsü olmayan (Play) yapıda ses çözülemiyorsa DabControl kullanıcıya bildirir
std::atomic<bool> gNoAacDecoder{false};

AACDecoder* CreateSystemAacDecoder(SubchannelSinkObserver* observer, SuperframeFormat sf) {
    char v[PROP_VALUE_MAX] = {0};
    AACDecoderSystem* d = nullptr;
    if (__system_property_get("debug.radvela.faad", v) > 0 && v[0] == '1') {
        oto::logf("AAC: sistem cozucusu test ayariyla kapali");
    } else {
        d = new AACDecoderSystem(observer, sf);
        if (d->ok()) return d;
        delete d;
        oto::logf("AAC: sistem cozucusu acilamadi");
    }
#ifdef RADVELA_SYSTEM_AAC_ONLY
    gNoAacDecoder = true;
#endif
    return nullptr;
}
