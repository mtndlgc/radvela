// Basit günlükleme: Android'de logcat (etiket "OtoRadyo"), bilgisayarda OTO_LOG ortam değişkeni varsa stderr.
#pragma once

#include <cstdarg>
#include <cstdio>
#include <cstdlib>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace oto {

inline void logf(const char* fmt, ...) __attribute__((format(printf, 1, 2)));

inline void logf(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
#ifdef __ANDROID__
    __android_log_vprint(ANDROID_LOG_INFO, "OtoRadyo", fmt, ap);
#else
    static const bool on = std::getenv("OTO_LOG") != nullptr;
    if (on) {
        vfprintf(stderr, fmt, ap);
        fputc('\n', stderr);
    }
#endif
    va_end(ap);
}

}  // namespace oto
