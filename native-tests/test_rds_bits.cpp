// RDS bit düzeyi test: gerçek bir yayından alınmış (Vikerraadio, PI 0x22E1) bit akışı.
// Test verisi windytan/redsea test paketindeki örnekten alınmıştır (MIT).
#include <cstdio>
#include <string>
#include <vector>
#include "../app/src/main/cpp/core/rds_decoder.h"

struct Ev : dr::RdsEvents {
    uint16_t pi = 0; std::string ps, rt; int pty = -1; int syncChanges = 0;
    void onRdsPi(uint16_t p) override { pi = p; printf("PI  %04X\n", p); }
    void onRdsPs(const std::string& s) override { ps = s; printf("PS  [%s]\n", s.c_str()); }
    void onRdsRt(const std::string& s) override { rt = s; printf("RT  [%s]\n", s.c_str()); }
    void onRdsPty(int p) override { pty = p; printf("PTY %d\n", p); }
    void onRdsSync(bool s) override { ++syncChanges; printf("SYNC %d\n", s); }
};

int main() {
    const char* bits =
        "001"
        "1110110110111010011100010101001000010100001110000010"
        "0010001011100001011100110000100101100000111100111110"
        "0010000001100101101101001101101001001000000110111110"
        "0010001011100001011100110000000101100010010011100000"
        "1010011010110011111010010101010011010011000101010101"
        "0010001011100001011100110000100101100001001010101000"
        "0111001101100001010000011001100001000011010111000111"
        "001000";
    Ev ev; dr::RdsDecoder dec(&ev);
    // Çözücüye iki kez ver (senkron için)
    for (int rep = 0; rep < 3; ++rep)
        for (const char* p = bits; *p; ++p) dec.pushBit(*p - '0');
    printf("final: synced=%d pi=%04X\n", dec.synced(), ev.pi);

    // Sendrom tablosu: ofset kelimelerinin standart sendromları
    printf("syn(A offset only)=%03X\n", dec.syndrome(0x0FC));
    return (ev.pi == 0x22E1) ? 0 : 1;
}
