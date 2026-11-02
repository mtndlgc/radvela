# Üçüncü taraf bileşenler

Radvela, GPL-2.0-veya-sonrası (bkz. `LICENSE`) ile dağıtılır. Uygulamadaki tüm yerel kod bu depodan kaynaktan derlenir;
hazır (kaynaksız) kitaplık içermez. Tam kaynak kodu bu depodadır.

## welle.io (DAB/DAB+ çekirdeği) — GPL-2.0-veya-sonrası
- Kaynak: https://github.com/AlbrechtL/welle.io (işleme `512558d`)
- `app/src/main/cpp/third_party/welle/`: `src/backend`, `src/various` ve `src/input/virtual_input.h` dosyaları kopyalanmıştır.
- **Değişiklik:** `backend/dabplus_decoder.cpp`, Android'de önce cihazın AAC çözücüsünü (`core/aac_system.cpp`, MediaCodec) kullanır; kullanılamazsa FAAD2'ye döner.
- Lisans ve yazarlar: `third_party/welle/COPYING`, `third_party/welle/AUTHORS`.
- Birlikte gelen `third_party/fec` (Phil Karn, LGPL-2.1) ve `third_party/kiss_fft` (BSD-3-Clause) de welle.io deposundandır.

## librtlsdr — GPL-2.0-veya-sonrası
- Kaynak: https://github.com/old-dab/rtlsdr (işleme `ebb000f`)
- **Değişiklik:** `src/librtlsdr.c` ve `include/rtl-sdr.h` dosyalarına Android USB dosya tanıtıcısıyla açan `rtlsdr_open_fd()` eklenmiştir.

## libusb — LGPL-2.1-veya-sonrası
- Kaynak: https://github.com/libusb/libusb (işleme `987c117`). `third_party/libusb/libusb/version_describe.h`, derleme sırasında üretilen dosyanın yerine yazılmıştır.

## FAAD2 (DAB+ AAC yedek çözücüsü) — GPL-2.0-veya-sonrası
`app/src/main/cpp/third_party/faad2` — welle.io deposundan alınan kopya (lisans: `COPYING`).
Yalnız `sideload` (araç) yapısında derlenir. `play` yapısı AAC'yi cihazın çözücüsüyle (MediaCodec) çözer ve FAAD2 içermez.
Lisansın istediği telif notu (`sideload` yapısında Hakkında bölümünde de gösterilir):
Code from FAAD2 is copyright (c) Nero AG, www.nero.com

## mpg123 (DAB MP2 çözümü) — LGPL-2.1
`app/src/main/cpp/third_party/mpg123` — welle.io deposundan alınan kopya (lisans: `COPYING`).

## AbracaDABra — MIT, © Petr Kopecký
- Kaynak: https://github.com/KejPi/AbracaDABra
- `core/rtl_common.h` (DC giderme + yazılım AGC) ve `core/rtlsdr_usb_source.*` bu projenin `rtlsdrinput` bileşeninden uyarlanmıştır.
- MIT lisans metni: `app/src/main/cpp/third_party/LICENSE-AbracaDABra-MIT`.
- 1.0.0 öncesi sürümlerde kullanılan kaynak kodsuz `libdabsdr.so` kaldırılmıştır.

## RDS
RDS karakter tablosu ve blok denetim matrisi/ofset sendromları IEC 62106 (EN 50067) standardındandır.
FM kodlayıcıları ve RDS çözücü bu projeye özgüdür.

## Görseller
`app/src/main/res/drawable-nodpi/wood_dark.jpg` (ahşap doku) bu proje için prosedürel olarak üretilmiştir (gürültü işlevleri); üçüncü taraf görsel içermez. Hazır istasyon düğmelerindeki baş harf rozetleri çizimdir; marka logosu kullanılmaz.

## Station logos / İstasyon logoları

Station logos are looked up by name in the community database [radio-browser.info](https://www.radio-browser.info/)
(optional; can be turned off in Settings) and cached on the device. Station names are the only data sent.
Logos are trademarks of their respective owners and are shown for identification only.
İstasyon logoları isteğe bağlı olarak (Ayarlar'dan kapatılabilir) radio-browser.info topluluk veri tabanından adla aranır ve cihazda önbelleğe alınır; gönderilen tek veri istasyon adıdır.
