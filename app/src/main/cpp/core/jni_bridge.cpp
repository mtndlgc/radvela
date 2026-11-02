// JNI köprüsü: app.otoradyo.NativeRadio <-> RadioEngine
#include <jni.h>
#include <pthread.h>

#include <memory>
#include <string>
#include <vector>

#include "dab_tables.h"
#include "engine.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OtoRadyo", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

namespace {

JavaVM* g_vm = nullptr;
pthread_key_t g_detachKey;
pthread_once_t g_keyOnce = PTHREAD_ONCE_INIT;

void detachThread(void*) {
    if (g_vm) g_vm->DetachCurrentThread();
}
void makeKey() { pthread_key_create(&g_detachKey, detachThread); }

// Çağıran iş parçacığını JVM'e bağla (yerel iş parçacıkları için), çıkışta ayır.
JNIEnv* getEnv() {
    JNIEnv* env = nullptr;
    if (!g_vm) return nullptr;
    const jint r = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (r == JNI_OK) return env;
    if (r == JNI_EDETACHED) {
        pthread_once(&g_keyOnce, makeKey);
#ifdef __ANDROID__
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
#else
        if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return nullptr;
#endif
        pthread_setspecific(g_detachKey, reinterpret_cast<void*>(1));
        return env;
    }
    return nullptr;
}

// Olay türleri (NativeRadio.kt ile aynı olmalı)
enum Ev {
    EV_CONNECTION = 1,
    EV_FM_STATUS = 2,
    EV_FM_PI = 3,
    EV_FM_PS = 4,
    EV_FM_RT = 5,
    EV_FM_PTY = 6,
    EV_FM_FOUND = 7,
    EV_FM_NAME = 8,
    EV_DAB_ENSEMBLE = 9,
    EV_DAB_SERVICE = 10,
    EV_DAB_SERVICES_COMPLETE = 11,
    EV_DAB_SELECTED = 12,
    EV_DAB_STATUS = 13,
    EV_DAB_LABEL = 14,
    EV_DAB_AUDIO = 15,
    EV_SCAN_PROGRESS = 16,
    EV_SCAN_DONE = 17,
    EV_MESSAGE = 18,
    EV_GAIN = 19,
    EV_DAB_QUALITY = 20,
    EV_SCAN_FREQ = 21,
};

class JniListener : public oto::EngineListener {
public:
    JniListener(JNIEnv* env, jobject self) {
        self_ = env->NewGlobalRef(self);
        jclass cls = env->GetObjectClass(self);
        midAudio_ = env->GetMethodID(cls, "onNativeAudio", "([SI)V");
        midEvent_ = env->GetMethodID(cls, "onNativeEvent", "(ILjava/lang/String;DD)V");
        midSlide_ = env->GetMethodID(cls, "onNativeSlide", "([BI)V");
        env->DeleteLocalRef(cls);
    }
    ~JniListener() override {
        if (JNIEnv* env = getEnv()) env->DeleteGlobalRef(self_);
    }

    // PCM: her zaman stereo (L R L R ...); `frames` çerçeve sayısıdır.
    void onAudio(const int16_t* pcm, size_t frames, int rate) override {
        JNIEnv* env = getEnv();
        if (!env || !midAudio_) return;
        const jsize n = static_cast<jsize>(frames * 2);
        jshortArray arr = env->NewShortArray(n);
        if (!arr) return;
        env->SetShortArrayRegion(arr, 0, n, reinterpret_cast<const jshort*>(pcm));
        env->CallVoidMethod(self_, midAudio_, arr, rate);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    void onDabSlide(const uint8_t* data, size_t len, int subtype) override {
        JNIEnv* env = getEnv();
        if (!env || !midSlide_ || len == 0) return;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
        if (!arr) return;
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(data));
        env->CallVoidMethod(self_, midSlide_, arr, subtype);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    void onConnection(bool c, const std::string& d) override { ev(EV_CONNECTION, d, c ? 1 : 0, 0); }
    void onFmStatus(float rssi, bool stereo, bool rds) override { ev(EV_FM_STATUS, "", rssi, (stereo ? 1 : 0) | (rds ? 2 : 0)); }
    void onFmPi(uint16_t pi) override { ev(EV_FM_PI, "", pi, 0); }
    void onFmPs(const std::string& s) override { ev(EV_FM_PS, s, 0, 0); }
    void onFmRt(const std::string& s) override { ev(EV_FM_RT, s, 0, 0); }
    void onFmPty(int p) override { ev(EV_FM_PTY, "", p, 0); }
    void onFmStationFound(int f, float s) override { ev(EV_FM_FOUND, "", f, s); }
    void onFmStationName(int f, const std::string& ps) override { ev(EV_FM_NAME, ps, f, 0); }
    void onDabEnsemble(const std::string& c, const std::string& l, uint32_t eid) override {
        ev(EV_DAB_ENSEMBLE, c + "\x01" + l, eid, 0);
    }
    void onDabService(const std::string& c, uint32_t sid, int scids, const std::string& l, bool aac) override {
        ev(EV_DAB_SERVICE, c + "\x01" + l, static_cast<double>(sid), scids | (aac ? 0x100 : 0));
    }
    void onDabServicesComplete(const std::string& c) override { ev(EV_DAB_SERVICES_COMPLETE, c, 0, 0); }
    void onDabSelected(uint32_t sid, int scids, const std::string& l) override { ev(EV_DAB_SELECTED, l, static_cast<double>(sid), scids); }
    void onDabStatus(float snr, int sync) override { ev(EV_DAB_STATUS, "", snr, sync); }
    void onDabQuality(int f, int ok, int err, int rs) override { ev(EV_DAB_QUALITY, std::to_string(f) + "," + std::to_string(ok) + "," + std::to_string(err) + "," + std::to_string(rs), 0, 0); }
    void onDabLabel(const std::string& d) override { ev(EV_DAB_LABEL, d, 0, 0); }
    void onDabAudioParams(const std::string& coding, int rate, bool stereo) override { ev(EV_DAB_AUDIO, coding, rate, stereo ? 1 : 0); }
    void onScanProgress(int p, const std::string& w) override { ev(EV_SCAN_PROGRESS, w, p, 0); }
    void onScanFreq(int f) override { ev(EV_SCAN_FREQ, "", f, 0); }
    void onScanDone(bool c) override { ev(EV_SCAN_DONE, "", c ? 1 : 0, 0); }
    void onGain(float db, bool a) override { ev(EV_GAIN, "", db, a ? 1 : 0); }
    void onMessage(bool e, const std::string& t) override { ev(EV_MESSAGE, t, e ? 1 : 0, 0); }

private:
    // Modified UTF-8 sorunlarından kaçınmak için dizgiyi UTF-16'ya çevirerek geçir
    static std::u16string toU16(const std::string& s) {
        std::u16string out;
        for (size_t i = 0; i < s.size();) {
            const unsigned char c = static_cast<unsigned char>(s[i]);
            uint32_t cp;
            int len;
            if (c < 0x80) { cp = c; len = 1; }
            else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
            else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
            else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
            else { cp = 0xFFFD; len = 1; }
            if (i + static_cast<size_t>(len) > s.size()) { out.push_back(0xFFFD); break; }
            for (int k = 1; k < len; ++k) cp = (cp << 6) | (static_cast<unsigned char>(s[i + static_cast<size_t>(k)]) & 0x3F);
            i += static_cast<size_t>(len);
            if (cp >= 0x10000) {
                cp -= 0x10000;
                out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
                out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
            } else {
                out.push_back(static_cast<char16_t>(cp));
            }
        }
        return out;
    }

    void ev(int type, const std::string& text, double a, double b) {
        JNIEnv* env = getEnv();
        if (!env || !midEvent_) return;
        const std::u16string u = toU16(text);
        jstring js = env->NewString(reinterpret_cast<const jchar*>(u.data()), static_cast<jsize>(u.size()));
        env->CallVoidMethod(self_, midEvent_, type, js, a, b);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    jobject self_ = nullptr;
    jmethodID midAudio_ = nullptr, midEvent_ = nullptr, midSlide_ = nullptr;
};

struct Handle {
    std::unique_ptr<JniListener> listener;
    std::unique_ptr<oto::RadioEngine> engine;
};

Handle* H(jlong h) { return reinterpret_cast<Handle*>(h); }

std::string toStd(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

#define FN(name) Java_app_otoradyo_NativeRadio_##name

JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv* env, jobject self) {
    auto* h = new Handle();
    h->listener = std::make_unique<JniListener>(env, self);
    h->engine = std::make_unique<oto::RadioEngine>(h->listener.get());
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL FN(nativeDestroy)(JNIEnv*, jobject, jlong handle) {
    if (!handle) return;
    delete H(handle);  // önce engine (alıcı iş parçacıkları), sonra listener
}

#ifdef OTO_WITH_USB
// fd: UsbDeviceConnection.getFileDescriptor(); bağlantıyı Java tarafı açık tutmalıdır.
JNIEXPORT jstring JNICALL FN(nativeOpenUsb)(JNIEnv* env, jobject, jlong h, jint fd) {
    std::string err;
    if (H(h)->engine->openUsb(fd, &err)) return nullptr;
    return env->NewStringUTF(err.empty() ? "@nat_open_fail" : err.c_str());
}
#endif

// Test/uzak kullanım için rtl_tcp. Başarılıysa null, değilse hata iletisi döner.
JNIEXPORT jstring JNICALL FN(nativeOpenTcp)(JNIEnv* env, jobject, jlong h, jstring host, jint port, jint timeoutMs) {
    std::string err;
    if (H(h)->engine->openTcp(toStd(env, host), port, timeoutMs, &err)) return nullptr;
    return env->NewStringUTF(err.empty() ? "@nat_tcp_fail" : err.c_str());
}

JNIEXPORT void JNICALL FN(nativeCloseSource)(JNIEnv*, jobject, jlong h) { H(h)->engine->closeSource(); }

JNIEXPORT jboolean JNICALL FN(nativeStartFm)(JNIEnv*, jobject, jlong h, jint freqHz) {
    return H(h)->engine->startFm(freqHz) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN(nativeStartDab)(JNIEnv* env, jobject, jlong h, jstring channel, jlong sid, jint scids) {
    return H(h)->engine->startDab(toStd(env, channel), static_cast<uint32_t>(sid), scids) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(nativePlayDab)(JNIEnv*, jobject, jlong h, jlong sid, jint scids) {
    H(h)->engine->playDabService(static_cast<uint32_t>(sid), scids);
}

JNIEXPORT void JNICALL FN(nativeStop)(JNIEnv*, jobject, jlong h) { H(h)->engine->stop(); }

JNIEXPORT void JNICALL FN(nativeScanFm)(JNIEnv*, jobject, jlong h, jboolean resolveNames) {
    H(h)->engine->scanFm(resolveNames == JNI_TRUE);
}

JNIEXPORT void JNICALL FN(nativeScanDab)(JNIEnv*, jobject, jlong h) { H(h)->engine->scanDab(); }

JNIEXPORT void JNICALL FN(nativeCancelScan)(JNIEnv*, jobject, jlong h) { H(h)->engine->cancelScan(); }

JNIEXPORT void JNICALL FN(nativeSetAgc)(JNIEnv*, jobject, jlong h, jboolean on) { H(h)->engine->setAgc(on == JNI_TRUE); }

JNIEXPORT void JNICALL FN(nativeSetGainIndex)(JNIEnv*, jobject, jlong h, jint idx) { H(h)->engine->setGainIndex(idx); }

JNIEXPORT jlong JNICALL FN(nativeDropped)(JNIEnv*, jobject, jlong h) { return static_cast<jlong>(H(h)->engine->droppedSamples()); }

JNIEXPORT void JNICALL FN(nativeSetPpm)(JNIEnv*, jobject, jlong h, jint ppm) { H(h)->engine->setPpm(ppm); }

JNIEXPORT jint JNICALL FN(nativeGainCount)(JNIEnv*, jobject, jlong h) { return H(h)->engine->gainCount(); }

JNIEXPORT jfloat JNICALL FN(nativeLevel)(JNIEnv*, jobject, jlong h) { return H(h)->engine->signalLevel(); }
JNIEXPORT jfloat JNICALL FN(nativeGainDb)(JNIEnv*, jobject, jlong h) { return H(h)->engine->gainDb(); }

JNIEXPORT void JNICALL FN(nativeSetForceMono)(JNIEnv*, jobject, jlong h, jboolean on) { H(h)->engine->setForceMono(on == JNI_TRUE); }

JNIEXPORT void JNICALL FN(nativeSetDeemphasis)(JNIEnv*, jobject, jlong h, jfloat us) { H(h)->engine->setDeemphasisMicros(us); }

// 0 boşta, 1 FM, 2 DAB
JNIEXPORT jint JNICALL FN(nativeMode)(JNIEnv*, jobject, jlong h) { return static_cast<jint>(H(h)->engine->mode()); }

JNIEXPORT jobjectArray JNICALL FN(nativeDabChannels)(JNIEnv* env, jobject) {
    const auto& ch = oto::dabChannels();
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(ch.size()), strCls, nullptr);
    for (size_t i = 0; i < ch.size(); ++i) {
        jstring s = env->NewStringUTF(ch[i].name);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

}  // extern "C"
