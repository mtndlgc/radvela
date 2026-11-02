// DAB tabloları: EBU Latin -> Unicode, karakter kümesi dönüşümü ve Band III kanal listesi.
// Veriler ETSI TS 101 756 (Tablo 1/19) ve EN 300 401'e göredir; AbracaDABra (MIT, © Petr Kopecký)
// projesindeki doğrulanmış tablolarla karşılaştırılmıştır.
#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace oto {

inline const uint16_t* ebuLatinTable() {
    static const uint16_t kTable[256] = {
    0x0000, 0x0118, 0x012E, 0x0172, 0x0102, 0x0116, 0x010E, 0x0218,
    0x021A, 0x010A, 0x000A, 0x000B, 0x0120, 0x0139, 0x017B, 0x0143,
    0x0105, 0x0119, 0x012F, 0x0173, 0x0103, 0x0117, 0x010F, 0x0219,
    0x021B, 0x010B, 0x0147, 0x011A, 0x0121, 0x013A, 0x017C, 0x001F,
    0x0020, 0x0021, 0x0022, 0x0023, 0x0142, 0x0025, 0x0026, 0x0027,
    0x0028, 0x0029, 0x002A, 0x002B, 0x002C, 0x002D, 0x002E, 0x002F,
    0x0030, 0x0031, 0x0032, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037,
    0x0038, 0x0039, 0x003A, 0x003B, 0x003C, 0x003D, 0x003E, 0x003F,
    0x0040, 0x0041, 0x0042, 0x0043, 0x0044, 0x0045, 0x0046, 0x0047,
    0x0048, 0x0049, 0x004A, 0x004B, 0x004C, 0x004D, 0x004E, 0x004F,
    0x0050, 0x0051, 0x0052, 0x0053, 0x0054, 0x0055, 0x0056, 0x0057,
    0x0058, 0x0059, 0x005A, 0x005B, 0x016E, 0x005D, 0x0141, 0x005F,
    0x0104, 0x0061, 0x0062, 0x0063, 0x0064, 0x0065, 0x0066, 0x0067,
    0x0068, 0x0069, 0x006A, 0x006B, 0x006C, 0x006D, 0x006E, 0x006F,
    0x0070, 0x0071, 0x0072, 0x0073, 0x0074, 0x0075, 0x0076, 0x0077,
    0x0078, 0x0079, 0x007A, 0x00AB, 0x016F, 0x00BB, 0x013D, 0x0126,
    0x00E1, 0x00E0, 0x00E9, 0x00E8, 0x00ED, 0x00EC, 0x00F3, 0x00F2,
    0x00FA, 0x00F9, 0x00D1, 0x00C7, 0x015E, 0x00DF, 0x00A1, 0x0178,
    0x00E2, 0x00E4, 0x00EA, 0x00EB, 0x00EE, 0x00EF, 0x00F4, 0x00F6,
    0x00FB, 0x00FC, 0x00F1, 0x00E7, 0x015F, 0x011F, 0x0131, 0x00FF,
    0x0136, 0x0145, 0x00A9, 0x0122, 0x011E, 0x011B, 0x0148, 0x0151,
    0x0150, 0x20AC, 0x00A3, 0x0024, 0x0100, 0x0112, 0x012A, 0x016A,
    0x0137, 0x0146, 0x013B, 0x0123, 0x013C, 0x0130, 0x0144, 0x0171,
    0x0170, 0x00BF, 0x013E, 0x00B0, 0x0101, 0x0113, 0x012B, 0x016B,
    0x00C1, 0x00C0, 0x00C9, 0x00C8, 0x00CD, 0x00CC, 0x00D3, 0x00D2,
    0x00DA, 0x00D9, 0x0158, 0x010C, 0x0160, 0x017D, 0x00D0, 0x013F,
    0x00C2, 0x00C4, 0x00CA, 0x00CB, 0x00CE, 0x00CF, 0x00D4, 0x00D6,
    0x00DB, 0x00DC, 0x0159, 0x010D, 0x0161, 0x017E, 0x0111, 0x0140,
    0x00C3, 0x00C5, 0x00C6, 0x0152, 0x0177, 0x00DD, 0x00D5, 0x00D8,
    0x00DE, 0x014A, 0x0154, 0x0106, 0x015A, 0x0179, 0x0164, 0x00F0,
    0x00E3, 0x00E5, 0x00E6, 0x0153, 0x0175, 0x00FD, 0x00F5, 0x00F8,
    0x00FE, 0x014B, 0x0155, 0x0107, 0x015B, 0x017A, 0x0165, 0x0127,
    };
    return kTable;
}

inline void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out += static_cast<char>(cp);
    } else if (cp < 0x800) {
        out += static_cast<char>(0xC0 | (cp >> 6));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
        out += static_cast<char>(0xE0 | (cp >> 12));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
        out += static_cast<char>(0xF0 | (cp >> 18));
        out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    }
}

// charset: 0 = EBU Latin, 4 = ISO 8859-1, 6 = UCS-2 (BE), 15 = UTF-8
inline std::string dabTextToUtf8(const char* data, size_t len, uint8_t charset) {
    std::string out;
    switch (charset) {
        case 0x0:
            for (size_t i = 0; i < len && data[i]; ++i) appendUtf8(out, ebuLatinTable()[static_cast<uint8_t>(data[i])]);
            break;
        case 0x4:
            for (size_t i = 0; i < len; ++i) appendUtf8(out, static_cast<uint8_t>(data[i]));
            break;
        case 0x6:
            for (size_t i = 0; i + 1 < len; i += 2) {
                const uint32_t u = (static_cast<uint8_t>(data[i]) << 8) | static_cast<uint8_t>(data[i + 1]);
                if (u == 0) break;
                appendUtf8(out, u);
            }
            break;
        case 0xF:
            out.assign(data, strnlen(data, len));
            break;
        default:
            break;
    }
    return out;
}

inline std::string trimRight(std::string s) {
    while (!s.empty() && (s.back() == ' ' || s.back() == '\0')) s.pop_back();
    return s;
}

struct DabChannel {
    uint32_t freqKHz;
    const char* name;
};

inline const std::vector<DabChannel>& dabChannels() {
    static const std::vector<DabChannel> kList = {
        {174928, "5A"},
        {176640, "5B"},
        {178352, "5C"},
        {180064, "5D"},
        {181936, "6A"},
        {183648, "6B"},
        {185360, "6C"},
        {187072, "6D"},
        {188928, "7A"},
        {190640, "7B"},
        {192352, "7C"},
        {194064, "7D"},
        {195936, "8A"},
        {197648, "8B"},
        {199360, "8C"},
        {201072, "8D"},
        {202928, "9A"},
        {204640, "9B"},
        {206352, "9C"},
        {208064, "9D"},
        {209936, "10A"},
        {211648, "10B"},
        {213360, "10C"},
        {215072, "10D"},
        {216928, "11A"},
        {218640, "11B"},
        {220352, "11C"},
        {222064, "11D"},
        {223936, "12A"},
        {225648, "12B"},
        {227360, "12C"},
        {229072, "12D"},
        {230784, "13A"},
        {232496, "13B"},
        {234208, "13C"},
        {235776, "13D"},
        {237488, "13E"},
        {239200, "13F"},
        {251936, "16A"},
        {253648, "16B"},
        {255360, "16C"},
        {257072, "16D"},
        {258928, "17A"},
        {260640, "17B"},
        {262352, "17C"},
        {264064, "17D"},
    };
    return kList;
}

inline const char* dabChannelName(uint32_t freqKHz) {
    for (const auto& c : dabChannels())
        if (c.freqKHz == freqKHz) return c.name;
    return "";
}

inline uint32_t dabChannelFreqKHz(const std::string& name) {
    for (const auto& c : dabChannels())
        if (name == c.name) return c.freqKHz;
    return 0;
}

}  // namespace oto
