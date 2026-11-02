// FmMpx birim testi: ham float32 MPX dosyası (256 kS/s) -> PCM (int16 stereo, 32 kS/s) + RDS olayları
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <string>
#include "../app/src/main/cpp/core/fm_mpx.h"
#include "../app/src/main/cpp/core/rds_decoder.h"

struct Ev : dr::RdsEvents {
    std::string ps, rt; int pi = -1, pty = -1; bool synced = false;
    void onRdsPi(uint16_t p) override { pi = p; }
    void onRdsPs(const std::string& s) override { ps = s; }
    void onRdsRt(const std::string& s) override { rt = s; }
    void onRdsPty(int p) override { pty = p; }
    void onRdsSync(bool s) override { synced = s; }
};

int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "kullanim: %s in.raw out.pcm [mono]\n", argv[0]); return 2; }
    FILE* f = fopen(argv[1], "rb");
    if (!f) { perror("open"); return 2; }
    std::vector<float> buf(4096);
    dr::FmMpx mpx;
    if (argc > 3 && std::string(argv[3]) == "mono") mpx.setForceMono(true);
    Ev ev; dr::RdsDecoder rds(&ev);
    FILE* o = fopen(argv[2], "wb");
    long bits = 0, ones = 0;
    mpx.onPcm = [&](const int16_t* p, size_t frames) { fwrite(p, 2, frames * 2, o); };
    mpx.onRdsBit = [&](int b) { rds.pushBit(b); ++bits; ones += b; };
    size_t got;
    size_t total = 0;
    while ((got = fread(buf.data(), 4, buf.size(), f)) > 0) {
        mpx.process(buf.data(), got);
        size_t prev = total; total += got;
        if (getenv("DBG") && (prev / 64000) != (total / 64000)) printf("t=%.2fs lock=%d amp=%.3f err=%.3f blend=%.2f\n", total / 256000.0, mpx.pilotLocked(), mpx.pilotAmplitude(), mpx.phaseError(), mpx.stereoBlend());
    }
    fclose(f); fclose(o);
    printf("pilot_locked=%d amp=%.3f blend=%.2f rds_bits=%ld ones=%.2f\n", mpx.pilotLocked(), mpx.pilotAmplitude(), mpx.stereoBlend(), bits, bits ? double(ones)/bits : 0.0);
    printf("rds_synced=%d PI=%04X PTY=%d\nPS=[%s]\nRT=[%s]\n", ev.synced, ev.pi < 0 ? 0 : ev.pi, ev.pty, ev.ps.c_str(), ev.rt.c_str());
    return 0;
}
