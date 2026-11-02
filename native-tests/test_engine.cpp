// RadioEngine entegrasyon testi (FM yolu): sahte rtl_tcp sunucusuna (fake_rtl_tcp.py) bağlanır.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <thread>

#include "../app/src/main/cpp/core/engine.h"

struct L : oto::EngineListener {
    std::atomic<long> frames{0};
    std::atomic<int> rate{0};
    std::mutex m;
    std::string ps, rt;
    int pi = -1, pty = -1;
    float rssi = 0;
    bool stereo = false, rds = false;
    std::atomic<int> found{0};
    std::atomic<bool> scanDone{false};
    void onAudio(const int16_t*, size_t f, int r) override { frames += static_cast<long>(f); rate = r; }
    void onFmStatus(float r, bool s, bool y) override { std::lock_guard<std::mutex> l(m); rssi = r; stereo = s; rds = y; }
    void onFmPi(uint16_t p) override { std::lock_guard<std::mutex> l(m); pi = p; }
    void onFmPs(const std::string& s) override { std::lock_guard<std::mutex> l(m); ps = s; }
    void onFmRt(const std::string& s) override { std::lock_guard<std::mutex> l(m); rt = s; }
    void onFmPty(int p) override { std::lock_guard<std::mutex> l(m); pty = p; }
    void onFmStationFound(int f, float s) override { ++found; printf("  bulundu %.1f MHz %.1f dB\n", f / 1e6, s); }
    void onScanDone(bool) override { scanDone = true; }
    void onConnection(bool c, const std::string& d) override { printf("  baglanti=%d %s\n", c, d.c_str()); }
    void onMessage(bool e, const std::string& t) override { printf("  mesaj(%d): %s\n", e, t.c_str()); }
};
static void sleepS(double s) { std::this_thread::sleep_for(std::chrono::milliseconds(int(s * 1000))); }

int main(int argc, char** argv) {
    const int port = argc > 1 ? atoi(argv[1]) : 12345;
    const int fm = argc > 2 ? atoi(argv[2]) : 98100000;
    L l;
    oto::RadioEngine eng(&l);
    if (!eng.openTcp("127.0.0.1", port, 5000)) { printf("BAGLANTI YOK\n"); return 1; }
    printf("[1] FM %.1f MHz\n", fm / 1e6);
    eng.startFm(fm);
    sleepS(9);
    {
        std::lock_guard<std::mutex> g(l.m);
        printf("  PI=%04X PTY=%d PS=[%s] RT=[%s] stereo=%d rds=%d rssi=%.1f dB  ses: %ld kare @%d Hz\n", l.pi < 0 ? 0 : l.pi,
               l.pty, l.ps.c_str(), l.rt.c_str(), l.stereo, l.rds, l.rssi, l.frames.load(), l.rate.load());
    }
    printf("[2] FM tarama (yalniz gurultu: 0 istasyon beklenir)\n");
    eng.scanFm(false);
    for (int i = 0; i < 600 && !l.scanDone; ++i) sleepS(0.1);
    printf("  tamam=%d bulunan=%d\n", l.scanDone.load(), l.found.load());
    printf("[3] tekrar FM (tarama sonrasi)\n");
    l.frames = 0;
    eng.startFm(fm);
    sleepS(4);
    printf("  ses karesi=%ld\n", l.frames.load());
    eng.closeSource();
    printf("BITTI\n");
    return 0;
}
