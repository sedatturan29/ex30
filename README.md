# EX30 Batarya

Volvo EX30 batarya sıcaklığı, şarj eğrisi, kapasite tahmini, yolculuk tüketimi,
park kaybı ve ısıtma testi. OBD adaptörüyle (ör. Vgate iCar Pro) yalnızca okuma yapar.

- **IOS-Vlink** (Bluetooth LE): web sayfası ve Android uygulaması
- **Android-Vlink** (klasik Bluetooth): yalnızca Android uygulaması; adaptör telefonda eşleşmiş olmalı

- **Web:** https://sedatturan29.github.io/ex30/ex30-batarya.html (Android'de Chrome)
- **Android uygulaması:** https://github.com/sedatturan29/ex30/releases/latest/download/EX30-Batarya.apk

`ex30-batarya.html` her değiştiğinde GitHub Actions APK'yı yeniden derleyip
Releases'e koyar. Uygulama aynı HTML'yi gösterir; Bluetooth'u Android'in kendi
BLE arayüzüyle sağlar (`android-app/`).
