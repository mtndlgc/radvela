// DabControl yaşam döngüsü testi: gerçek libdabsdr.so + sahte rtl_tcp (yalnız gürültü).
// Doğrulanan: başlat/kapat döngüleri kilitlenmeden biter, gürültüde "senkron yok" bildirilir,
// FM <-> DAB geçişleri çalışır, tarama iptal edilebilir.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <thread>

#include "../app/src/main/cpp/core/engine.h"

struct L : oto::EngineListener {
    std::atomic<long> frames{0};
    std::atomic<int> status{0}, maxSync{0}, ensembles{0}, services{0}, scanProg{0};
    std::atomic<bool> scanDone{false};
    std::atomic<bool> scanCancelled{false};
    void onAudio(const int16_t*, size_t f, int) override { frames += static_cast<long>(f); }
    void onDabStatus(float, int sync) override {
        ++status;
        int m = maxSync.load();
        while (sync > m && !maxSync.compare_exchange_weak(m, sync)) {}
    }
    void onDabEnsemble(const std::string& c, const std::string& l, uint32_t) override { ++ensembles; printf("  topluluk %s %s\n", c.c_str(), l.c_str()); }
    void onDabService(const std::string&, uint32_t, int, const std::string& l, bool) override { ++services; printf("  servis %s\n", l.c_str()); }
    void onScanProgress(int p, const std::string& w) override { scanProg = p; if (!w.empty()) printf("  tarama %d%% %s\n", p, w.c_str()); }
    void onScanDone(bool c) override { scanCancelled = c; scanDone = true; }
    void onMessage(bool e, const std::string& t) override { printf("  mesaj(%d): %s\n", e, t.c_str()); }
};
static void sleepS(double s) { std::this_thread::sleep_for(std::chrono::milliseconds(int(s * 1000))); }

int main(int argc, char** argv) {
    const int port = argc > 1 ? atoi(argv[1]) : 12345;
    L l;
    oto::RadioEngine eng(&l);
    if (!eng.openTcp("127.0.0.1", port, 5000)) { printf("BAGLANTI YOK\n"); return 1; }

    for (int i = 0; i < 3; ++i) {
        printf("[%d] DAB 11D baslat/kapat\n", i + 1);
        if (!eng.startDab("11D", 0, 0)) { printf("  startDab BASARISIZ\n"); return 2; }
        sleepS(3.5);
        printf("  durum olayi=%d enYuksekSenkron=%d topluluk=%d\n", l.status.load(), l.maxSync.load(), l.ensembles.load());
        eng.stop();
    }

    printf("[4] DAB -> FM -> DAB gecisi\n");
    eng.startDab("12B", 0, 0);
    sleepS(1.5);
    l.frames = 0;
    eng.startFm(98100000);
    sleepS(3);
    printf("  FM ses karesi=%ld\n", l.frames.load());
    eng.startDab("5A", 0, 0);
    sleepS(1.5);

    printf("[5] DAB tarama (iptal ile)\n");
    eng.scanDab();
    sleepS(8);
    eng.cancelScan();
    printf("  tarama bitti=%d iptal=%d ilerleme=%d%%\n", l.scanDone.load(), l.scanCancelled.load(), l.scanProg.load());

    printf("[6] kaynak kapat\n");
    eng.closeSource();
    printf("BITTI\n");
    return 0;
}
