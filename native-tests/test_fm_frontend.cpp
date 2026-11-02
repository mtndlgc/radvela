// IQ (uint8, 2.048 MS/s) -> FmFrontEnd -> FmMpx -> PCM + RDS
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <string>
#include <complex>
#include "../app/src/main/cpp/core/fm_frontend.h"
#include "../app/src/main/cpp/core/fm_mpx.h"
#include "../app/src/main/cpp/core/rds_decoder.h"
struct Ev : dr::RdsEvents {
    std::string ps, rt; int pi = -1; bool sync = false;
    void onRdsPi(uint16_t p) override { pi = p; }
    void onRdsPs(const std::string& s) override { ps = s; }
    void onRdsRt(const std::string& s) override { rt = s; }
    void onRdsSync(bool s) override { sync = s; }
};
int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "kullanim: %s in.u8 out.pcm\n", argv[0]); return 2; }
    FILE* f = fopen(argv[1], "rb"); FILE* o = fopen(argv[2], "wb");
    if (!f || !o) { fprintf(stderr, "dosya acilamadi\n"); return 1; }
    dr::FmFrontEnd fe; dr::FmMpx mpx; Ev ev; dr::RdsDecoder rds(&ev);
    mpx.onPcm = [&](const int16_t* p, size_t n) { fwrite(p, 2, n * 2, o); };
    mpx.onRdsBit = [&](int b) { rds.pushBit(b); };
    std::vector<uint8_t> raw(2 * 16384); std::vector<std::complex<float>> iq(16384); std::vector<float> m;
    size_t n; double lvl = 0; long blocks = 0;
    while ((n = fread(raw.data(), 2, 16384, f)) > 0) {
        for (size_t i = 0; i < n; ++i) iq[i] = {(raw[2*i] - 127.5f) / 127.5f, (raw[2*i+1] - 127.5f) / 127.5f};
        m.clear(); fe.process(iq.data(), n, m); mpx.process(m.data(), m.size());
        lvl += fe.levelDb(); ++blocks;
    }
    printf("level=%.1f dBFS pilot=%d amp=%.3f rds_sync=%d PI=%04X PS=[%s] RT=[%s]\n", lvl / blocks, mpx.pilotLocked(), mpx.pilotAmplitude(), ev.sync, ev.pi < 0 ? 0 : ev.pi, ev.ps.c_str(), ev.rt.c_str());
    fclose(f); fclose(o); return 0;
}
