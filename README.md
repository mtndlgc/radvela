# Radvela

**EN:** FM (stereo + RDS) and DAB / DAB+ radio for Android, driven directly by an RTL-SDR USB dongle (no separate "driver" app).
Built for car head units (BYD DiLink 3 and similar): large touch targets, preset buttons, rotation and split-screen support,
steering-wheel media keys. Free software, GPL-2.0-or-later.

**TR:** FM (stereo + RDS) ve DAB / DAB+ yayınlarını RTL-SDR USB dongle ile alan Android radyo uygulaması.
Araç multimedya ekranları (BYD DiLink 3 vb.) için tasarlandı: büyük dokunma hedefleri, hazır istasyon düğmeleri,
ekran dönmesi ve bölünmüş ekran, direksiyon medya tuşları. Özgür yazılımdır, GPL-2.0 veya sonrası.

## Özellikler
- **DAB/DAB+**: welle.io arka ucu (GPL) + kendi denetleyicimiz (`core/dab_control.cpp`). DAB+ (AAC-LC, HE-AAC, HE-AACv2) ve DAB (MP2) ses, MOT slayt gösterisi.
- **FM**: kendi DSP zincirimiz (kanal süzgeci, ayrımlayıcı, 19 kHz pilot PLL, stereo, RDS PS/RT/PTY), otomatik band taraması.
- **USB**: Android'in verdiği USB tanıtıcısı doğrudan `librtlsdr`'ye verilir; ek sürücü uygulaması gerekmez.
- **Arayüz**: beş tema (MENÜ > TEMA GALERİSİ): OEM Premium, Modern Digital (canlı ses spektrumu), Classic Analog (analog kadran; koyu metal veya ahşap zemin), Minimal Driving (sürüş için dev ad ve düğmeler), Station Wall (istasyon kartları). Hepsi `RadioUi` üzerinden `RadioState` okur; radyo motoruna bağımlı değildir. 5 renk şeması, büyük dokunma hedefleri, yatay/dikey/bölünmüş ekran.
- **Kalite tanılaması**: DAB'da canlı SNR, FIC/ses çerçevesi hata sayaçları, sessizliğin nedeni (zayıf sinyal mi, çözücü mü); "KAZANÇ ARA" ile SNR'ye göre kazanç optimizasyonu.
- ppm düzeltmesi, yazılım ses seviyesi, mono/vurgu ayarı, dongle takılınca otomatik başlama.
- Ses kaydı (WAV), uyku zamanlayıcısı, ekolayzer, istasyon yedekleme. Uygulama ücretsizdir; tüm özellikler ve temalar açıktır, reklam ve uygulama içi satın alma yoktur.

## Derleme
AGP 9.2, Gradle 9.4, NDK 27 ve CMake 3.22.1. Tüm yerel kod kaynaktan derlenir, hazır kitaplık yoktur; 16 KB sayfa boyutu desteklenir. Yalnız `arm64-v8a` üretilir.

İki yapı vardır:
- `play`: `targetSdk 36`, AAC cihaz çözücüsüyle, FAAD2 yok. `gradlew bundlePlayRelease`
- `sideload`: BYD DiLink 3 için `targetSdk 33`, FAAD2 yedek çözücü, açılışta başlatma var. `gradlew assembleSideloadRelease`

Yayın adımları: `docs/PLAY_RELEASE.md`.

## Bilgisayarda çekirdek testleri
```
cmake -S app/src/main/cpp -B build-host -DOTO_HOST_TESTS=ON
cmake --build build-host -j
python3 native-tests/gen_mpx.py /tmp/mpx.raw && python3 native-tests/gen_iq.py /tmp/mpx.raw /tmp/st.u8 25
python3 native-tests/fake_rtl_tcp.py 12345 98356000=/tmp/st.u8 &
build-host/native-tests/test_engine 12345 98100000
build-host/native-tests/test_dab 12345
```

## Bilinen sınırlar
- DAB alımı sinyal kalitesine çok duyarlıdır; zayıf sinyalde (SNR ≲ 8 dB) DAB+ ses çerçeveleri çözülemez. İyi anten ve gerekirse DAB filtresi gerekir.
- DL+ ve duyurular yok.
- Araç içi (BYD DiLink) testi henüz yapılmadı; USB ana makine desteği ve Android sürümü araca göre değişebilir.
- Yalnız Band III ve DAB mod 1 desteklenir.

## BYD DiLink (Song Plus vb.) kurulumu

Araç ekranı **DiLink 3.0** (Android 10, ARM64) ise uygulama şu koşulları sağlar: `minSdk 26` (≤ 29), `targetSdk 33` (≤ 33), yalnız `arm64-v8a`.
Ekran yatay/dikey döner, bölünmüş ekranda çalışır; direksiyon medya tuşları (sonraki/önceki, oynat/durdur) desteklenir.

USB bellekle kurulum (root gerekmez):

1. Belleği FAT32 veya exFAT biçimlendirin.
2. Kök dizinde `Third Party Apps <ülke kodu>` adlı klasör açın (ör. Brezilya için `Third Party Apps 55`).
   Ülke kodu aracın `sys.byd.countrycode` değeridir; klasör adı boşluklar dahil birebir aynı olmalıdır.
   Avrupa/Avustralya/Japonya yazılımlarında klasör adı `Application Installation <ülke kodu>` olur (parola yok, bulut doğrulaması var).
3. APK dosyasını klasöre koyun, belleği aracın USB girişine takın.
4. Parola istenirse girin, açılan dosya listesinde APK'ya dokunup kurun.
5. RTL-SDR dongle'ı USB girişine takın; çıkan izin penceresinde "Her zaman" seçeneğini işaretleyin.

Not: Bazı yazılım sürümleri yan yüklemeyi hiç kabul etmez ya da ülkeye göre kısıtlar.

## Lisans ve kaynak kodu
Radvela, **GNU GPL sürüm 2 veya (isteğinize bağlı) sonraki sürümü** ile dağıtılır (bkz. `LICENSE`). Bunun nedeni GPL lisanslı
welle.io, `librtlsdr` ve FAAD2 ile birlikte derlenmesidir. Uygulamanın tam kaynak kodu bu depodur; APK alan herkes bu kodu
isteyebilir, değiştirebilir ve aynı lisansla dağıtabilir. Üçüncü taraf bileşenler ve sürümleri için `NOTICE.md` dosyasına bakın.

## Sorumluluk reddi
- Radvela; BYD, WorldDAB, Google, AbracaDABra veya Wavesink ile bağlantılı, onlarca onaylanmış ya da desteklenen bir ürün değildir.
  "BYD", "DiLink", "DAB+" ve diğer adlar sahiplerinin markalarıdır ve yalnızca uyumluluğu belirtmek için anılır.
- Yayın kaydı özelliği yalnız kişisel kullanım içindir; kayıtların hukuki durumu ülkeye göre değişir, sorumluluk kullanıcıya aittir.
- Yazılım "olduğu gibi" sunulur, hiçbir garanti verilmez (GPL, bölüm 11–12).

## Teşekkür
welle.io katkıcıları (DAB çekirdeği), Petr Kopecký ve AbracaDABra katkıcıları, osmocom / old-dab (RTL-SDR), FAAD2, mpg123 ve libusb projeleri.
