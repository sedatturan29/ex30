# EX30 Reader

Volvo EX30 için OBD ve araç verisi okuyucu: batarya, motor, soğutma, şarj eğrisi,
kapasite tahmini, yolculuklar (Trip A/B, otomatik), park kaybı, ısıtma testi,
ünite bulucu ve kod tarayıcı. Yalnızca okur; araca hiçbir şey yazmaz.

| | Link |
| --- | --- |
| Web (Android'de Chrome) | https://sedatturan29.github.io/ex30/ex30-reader.html |
| Uygulama (telefon, Android Auto, orta ekran) | https://github.com/sedatturan29/ex30/releases/latest/download/EX30-Reader.apk |
| Google Play iç test paketi (yalnızca orta ekran) | https://github.com/sedatturan29/ex30/releases/latest/download/EX30-Reader-Play.aab |
| Orta ekran kurulum rehberi | [AAOS-KURULUM.md](AAOS-KURULUM.md) |

OBD adaptörü: IOS-Vlink (Bluetooth LE; web ve uygulama) ya da Android-Vlink
(klasik Bluetooth; yalnızca uygulama).

## Yapı

- `src/app.html` – ana uygulama (sekmeler)
- `src/panel.html` – Panel sekmesinin içeriği
- `tools/build.py` – ikisini tek dosyada birleştirir → `ex30-reader.html`
- `android-app/` – Android uygulaması (aynı HTML'yi gösterir, Bluetooth ve araç verisi köprüleri)

`src/` değişince `python3 tools/build.py` çalıştırılır. GitHub her güncellemede
APK'yı yeniden derleyip Releases'e koyar.

## Arşiv

İlk sürüm (EX30 Batarya v1, ayrı ana sayfa + panel, v1.1–v1.13):
- Dosyalar: [`arsiv/v1/`](arsiv/v1/)
- Dal: [`arsiv/v1`](https://github.com/sedatturan29/ex30/tree/arsiv/v1)
- APK'lar: [Releases](https://github.com/sedatturan29/ex30/releases) içinde v1.x sürümleri

Eski linkler (`ex30-batarya.html`, `panel.html`) yeni sayfaya yönlenir; kayıtlar ve
ayarlar korunur.
