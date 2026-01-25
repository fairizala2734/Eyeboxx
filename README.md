# Eyeboxx — MicroSleep Detector (Android)

> Android app untuk deteksi indikasi **microsleep** berbasis kamera (real-time) menggunakan **CameraX + MediaPipe Tasks Vision + TensorFlow Lite**.  
> Cocok buat eksperimen/riset kecil, demo, atau pengembangan lanjutan.

---

## ✨ Highlights
- 📷 **Real-time camera pipeline** pakai **CameraX**
- 🧠 Inference **on-device** (TFLite) — low-latency, no server required
- 🧩 Integrasi **MediaPipe Tasks Vision**
- 📦 Build output **3 APK**: `universal`, `arm64-v8a`, `armeabi-v7a`
- 🧼 Release build: `minifyEnabled` + `shrinkResources` aktif

---

## 🧰 Tech Stack
- **Kotlin**
- **Android SDK**: minSdk **24**, target/compile **36**
- **CameraX**
- **MediaPipe Tasks Vision**
- **TensorFlow Lite (minimal)**  
  (tanpa Flex / GPU; dependency transitif Flex/GPU di-exclude)

---

## 📥 Download (APK)
Kamu bisa ambil dari **GitHub Releases**.

### Pilih APK yang mana?
- **Universal** → paling aman buat mayoritas user (ukuran lebih besar)
- **arm64-v8a** → *recommended*, mayoritas HP Android modern
- **armeabi-v7a** → untuk device ARM 32-bit yang lebih lama

> Kalau bingung, ambil **Universal** dulu.

---

## ✅ Requirements
- Android Studio (disarankan versi terbaru)
- JDK 11 (project kamu set JVM target 11)
- Android SDK + platform tools
- Device Android **7.0+** (minSdk 24)

---

## 🚀 Quick Start (Run di Android Studio)
1. Clone repo:
   ```bash
   git clone https://github.com/fairizala2734/Eyeboxx.git
