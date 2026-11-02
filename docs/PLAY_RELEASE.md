# Google Play yayın kontrol listesi

Kod tarafı hazır olan her şey bu listenin dışındadır. Aşağıdakiler hesap, hukuk veya gerçek cihaz gerektirir.

## A. Yayını engelleyenler

1. **DAB+ gerçek yayın testi.** Android 15 tablette, Bakü 12A kanalındaki gerçek yayında kanal tarama (15 servis), ses ve servis değiştirme doğrulandı; `play` yapısı (FAAD2'siz) cihazın çözücüsüyle çaldı, çözücü yokken kullanıcıya hata mesajı gösterdiği de denendi. Kalan kontroller: slayt gösterisi, zayıf sinyalde yavaş tarama geçişi, 30 dakikalık kesintisiz dinleme (IQ taşması ve ısınma), Android 10 (DiLink).
2. **AAC patent lisansı.** Via LA, son kullanıcı AAC çözücü ürünü geliştirenlerin lisans almasını istiyor; yayımlanan koşullar 15.000 ABD doları başlangıç ücreti ve ilk 500.000 birim için birim başına 0,98 ABD dolarıdır. DAB+ HE-AAC kullandığı için bu uygulamayı ilgilendirir. FAAD2'nin GPL oluşu patent lisansı vermez. `play` yapısı artık AAC'yi cihazın çözücüsüyle (MediaCodec) çözer ve FAAD2 içermez; bu Android 15 tablette gerçek DAB+ yayınıyla denendi, başka cihazlarda denenmedi. Bu yöntemin patent açısından yeterli olduğunu Via LA'ye veya bir fikri mülkiyet avukatına yazılı olarak doğrulatın. `sideload` yapısı FAAD2'yi yedek olarak içerir, Play dışında dağıtılırsa aynı soru onun için de geçerlidir.
3. **GPL kaynak kodu.** Dağıtılan her sürümün tam kaynağı herkese açık olmalı: https://github.com/mtndlgc/radvela. Her sürümü etiketleyin. Bu depoya `keystore.properties` ve anahtar dosyası girmemelidir.
4. **Ad kontrolü.** Uygulama adı "Radvela" oldu; eski ad "Aethera", radyo alıcılarını kapsayan bir AB markasıyla (IR 1727926) çakıştığı için bırakıldı. Radvela için EUIPO ve USPTO ön aramasında kayıt, Play'de birebir aynı ad bulunmadı. TÜRKPATENT henüz aranmadı ve benzer yazılışlar taranmadı. Uygulama kimliği `app.otoradyo` ilk yayından sonra değiştirilemez; değişecekse şimdi değişmelidir.

## B. Yükleme anahtarı

Anahtar üretildi: proje kökünde `radvela-upload.jks` ve `keystore.properties` (ikisi de git'e girmez). Yeniden üretmek gerekirse `keytool`, Android Studio'nun `jbr\bin` klasöründedir:

```bash
keytool -genkeypair -v -keystore radvela-upload.jks -alias radvela -keyalg RSA -keysize 4096 -validity 10000
```

Proje köküne `keystore.properties` oluşturun (git'e girmez):

```properties
storeFile=radvela-upload.jks
storePassword=...
keyAlias=radvela
keyPassword=...
```

Anahtarı proje dışında da yedekleyin. Sonra:

```bash
gradlew bundlePlayRelease
```

Çıktı: `app/build/outputs/bundle/playRelease/app-play-release.aab`. Araç yapısı için `gradlew assembleSideloadRelease`.

## C. Play Console

1. Geliştirici hesabı açın. Yeni kişisel hesaplarda üretime çıkmadan önce kapalı test zorunluluğu olabilir; güncel tester sayısını ve süreyi Console'da doğrulayın.
2. Uygulamayı **ücretsiz** olarak oluşturun. Play'de ücretsiz yayımlanan bir kayıt sonradan ücretliye çevrilemez.
3. Gizlilik politikası: `docs/PRIVACY.md` dosyasını herkese açık bir adreste yayımlayın.
4. Veri güvenliği formu önerisi: reklam, analiz ve satın alma yok. Logo aramasında istasyon adları üçüncü tarafa gider; formu bu bilgiyle doldurun.
5. Ön plan servisi beyanı: `mediaPlayback`, kullanıcı başlattığı canlı radyo sesi; kısa bir video isteyebilir.
6. Müzik çalar `READ_MEDIA_AUDIO` izni ister; izin beyanında "cihazdaki müzik dosyalarını listeleyip çalmak" diye açıklayın. Veri güvenliği formunda dosyalar cihazdan çıkmaz. SD kart ve USB bellekle gerçek deneme yapın.
7. İçerik derecelendirmesi anketi ve hedef kitle.
8. Mağaza görselleri: uygulama simgesi 512×512, öne çıkan görsel 1024×500, telefon ve tablet ekran görüntüleri. Metin: `docs/STORE_LISTING.md`.
9. Mağaza açıklamasında ve görsellerde dongle gerektiğini açıkça belirtin; BYD ve DAB+ logolarını kullanmayın.

## D. Bilinen sınırlar

- Yalnız `arm64-v8a`; Play 32 bit cihazlara göstermez.
- Android Auto desteği, DL+ ve DAB duyuruları yok.
- Uygulama ücretsizdir. Kapalı kaynak kütüphane içeren reklam veya ödeme bileşeni eklemek GPL sorunu doğurur; eklemeden önce lisansı kontrol edin.
