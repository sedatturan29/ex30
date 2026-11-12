# EX30 orta ekranına kurulum (Android Automotive OS)

Amaç: uygulamayı EX30'un orta ekranında, Play Store'dan inen diğer uygulamalar gibi
çalıştırmak. Orada hem OBD adaptörüne bağlanır hem de aracın Android Automotive
üzerinden uygulamalara açtığı verileri (hız, lastik basıncı, batarya sıcaklığı vb.)
adaptörsüz okur. Uygulama yalnızca okur; araca hiçbir şey yazmaz.

Her güncellemede GitHub üç dosya üretir (Releases sayfasında):

| Dosya | Ne için |
| --- | --- |
| `EX30-Batarya.apk` | Telefon (+ Android Auto) |
| `EX30-Batarya-Arac.aab` | Orta ekran: Google Play'e yüklenecek paket |
| `EX30-Batarya-Arac.apk` | Orta ekran: USB bellek / ADB ile doğrudan kurulum |

## Yol A – Google Play iç test (önerilen)

Uygulama Play Store'da yalnızca sizin hesabınıza görünür; herkese açık yayınlanmaz.

1. **Geliştirici hesabı:** https://play.google.com/console adresinden, arabada
   kullandığınız Google hesabıyla (ya da başka bir hesapla) kayıt olun. Tek seferlik
   25 dolar ve kimlik doğrulaması gerekir; onay birkaç gün sürebilir.
2. **Uygulama oluşturun:** Create app → ad "EX30 Batarya", tür App, ücretsiz.
   Paket adı ilk yüklemede dosyadan alınır: `com.ex30.batarya`.
3. **Araç form faktörünü ekleyin:** Test and release → Advanced settings →
   Form factors → Add form factor → **Android Automotive OS** (şartları kabul edin).
4. **İç test sürümü:** Test and release → Testing → Internal testing. Sayfanın
   üstündeki form faktörü seçicisinden **Android Automotive OS only** kanalını seçin →
   Create new release → `EX30-Batarya-Arac.aab` dosyasını yükleyin → Save → Start rollout.
   İlk yüklemede Play App Signing'i kabul edin.
5. **Test kullanıcısı:** Internal testing → Testers → bir e-posta listesi oluşturup
   **arabada oturum açık olan Google hesabını** ekleyin.
6. **Daveti kabul edin:** Testers sekmesindeki "Copy link" bağlantısını o hesapla
   (telefonda ya da bilgisayarda) açıp "Become a tester" deyin.
7. **Arabada:** Play Store → "EX30 Batarya" aratın (görünmesi birkaç saat sürebilir;
   görünmezse hesabınızın uygulama listesine bakın) → Yükle.

Güncelleme: yeni `.aab` dosyasını aynı kanala yeni sürüm olarak yüklersiniz; araba
Play Store'dan kendisi günceller. Sürüm numarası her derlemede kendiliğinden artar.

Notlar:
- İç test kanalında Google'ın araç uygulaması incelemesi yapılmaz. Kapalı/açık test
  ya da herkese açık yayın istenirse inceleme devreye girer ve bu tür bir uygulama
  büyük ihtimalle kabul edilmez; bu yüzden iç testte kalın.
- Play Console içerik beyanlarını (gizlilik politikası, veri güvenliği vb.) isteyebilir.
  Uygulama veri toplamaz ve internete veri göndermez.

## Yol B – Doğrudan kurulum (hesap gerekmez, çalışırsa en hızlısı)

1. Arabanın Play Store'undan bir dosya yöneticisi kurun (ör. AnExplorer).
2. `EX30-Batarya-Arac.apk` dosyasını USB belleğe koyup arabaya takın, dosya
   yöneticisinden açıp kurun.
3. Ya da: Ayarlar → Sistem → Hakkında → yazılım numarasına 7 kez dokunarak geliştirici
   modunu açmayı deneyin; açılırsa `adb install EX30-Batarya-Arac.apk`.

Volvo bu yolları kapatmış olabilir. Ayrıca bu yolla kurulan uygulamayı sistem sürüş
sırasında gizleyebilir. Play'den (Yol A) gelen uygulamada bu kısıt büyük ihtimalle
uygulanmaz, ama garanti değildir.

## Arabada ilk açılış

1. Uygulama izin ister: araç enerji bilgisi, hız, lastikler, kilometre, konum,
   yakındaki cihazlar (Bluetooth). Hepsine izin verin.
2. **Canlı sekmesi → Canlı panel → Aç.** Aracın verdiği değerler OBD olmadan hemen
   görünür: hız kutusunda "Araç", lastiklerde "Araç" etiketi.
3. **Ayarlar → Araç verileri (Android Automotive):** Volvo'nun açtığı tüm özelliklerin
   canlı listesi. "Listeyi kopyala" ile geliştirmeye gönderin.
4. **OBD:** Bağlan'a basın. Listede IOS-Vlink (Bluetooth LE) görünmeli. Android-Vlink
   (klasik) için adaptörün arabanın Bluetooth ayarlarında eşleşmiş olması gerekir.

## Bilinmeyenler (araçta denenince netleşecek)

- Volvo hangi araç özelliklerini üçüncü parti uygulamalara açıyor?
- Orta ekranın Bluetooth'u OBD adaptörüne bağlanmaya izin veriyor mu?
- Sürüş sırasında ekran açık kalıyor mu?
- Aracın yazılımı hangi Android sürümünde? (Uygulama Android 8 ve üstünü destekler.)
