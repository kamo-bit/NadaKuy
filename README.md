# NadaKuy

Pemutar musik Android berbasis Kotlin dan Jetpack Compose. NadaKuy menyediakan pencarian YouTube Music, YouTube, dan Audius tanpa akun aplikasi, serta koneksi opsional ke Spotify Premium.

## Unduh APK

**[Unduh NadaKuy 1.3.4 untuk Android](https://github.com/kamo-bit/NadaKuy/releases/download/v1.3.4/NadaKuy-1.3.4.apk)**

Mendukung Android 8.0/API 26 atau lebih baru. Unduh APK melalui browser HP, buka berkasnya, lalu ikuti proses pemasangan Android. Jika diminta, izinkan pemasangan dari browser atau pengelola berkas yang digunakan.

APK ini merupakan build debug untuk pengujian, dengan sertifikat lokal yang sama seperti APK NadaKuy sebelumnya. [Halaman rilis](https://github.com/kamo-bit/NadaKuy/releases/latest) menyediakan catatan versi dan checksum SHA-256.

## Fitur

- Pencarian lagu dan artis, dengan peringkat yang mengutamakan kecocokan judul, artis, dan rekaman asli.
- Pemutaran di latar belakang, kontrol notifikasi, antrean, shuffle, repeat, dan sleep timer.
- Lagu disukai, playlist, riwayat pemutaran, dan riwayat pencarian yang disimpan di perangkat.
- Pemutar layar penuh dengan warna yang mengikuti sampul album.
- Spotify App Remote untuk mengendalikan pemutaran melalui aplikasi Spotify resmi.

YouTube Music menjadi sumber awal. YouTube dan Audius tidak memerlukan API key pengguna. Integrasi YouTube menggunakan NewPipe Extractor; perubahan layanan upstream dapat memerlukan pembaruan dependensi atau resolver. Peringkat pencarian memakai heuristik dan tidak menjamin semua hasil merupakan rekaman resmi.

## Mulai mengembangkan

Kebutuhan:

- JDK 17 dan Android Studio atau Android SDK command-line tools.
- Android SDK Platform 36 dan Build Tools 36.0.0.
- Python 3 untuk mengambil SDK Spotify yang dipin.
- Koneksi internet untuk mengunduh dependensi Gradle dan SDK.

```sh
git clone https://github.com/kamo-bit/NadaKuy.git
cd NadaKuy
python scripts/fetch_spotify_sdk.py
```

Buka folder ini di Android Studio dan tunggu Gradle sync. Untuk build dari terminal, atur `ANDROID_HOME` atau buat `local.properties` berisi `sdk.dir` yang menunjuk ke instalasi Android SDK. Berkas tersebut hanya untuk mesin lokal dan diabaikan Git.

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Linux/macOS:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK debug tersedia di `app/build/outputs/apk/debug/app-debug.apk`. Aplikasi mendukung Android 8.0/API 26 atau lebih baru. Build debug memakai kunci lokal pengembang; pembaruan APK pada perangkat yang sama memerlukan sertifikat penandatanganan yang sama.

## Mencoba di perangkat

Pilih emulator atau HP di Android Studio lalu tekan **Run**, atau jalankan:

```sh
./gradlew installDebug
```

Pada PowerShell gunakan `.\gradlew.bat installDebug`. Setelah aplikasi dibuka, pilih **Sumber musik** untuk beralih katalog. Koleksi disimpan lokal dan belum memiliki sinkronisasi antarpiranti.

## Spotify opsional

YouTube dan Audius dapat dipakai tanpa konfigurasi Spotify. Untuk mencoba Spotify, siapkan akun Premium, aplikasi Spotify resmi, dan aplikasi di Spotify Developer Dashboard. Masukkan Client ID melalui layar koneksi NadaKuy. Alur autentikasi menggunakan PKCE; Client Secret tidak diperlukan.

- Package Android: `app.nada.player`
- Redirect URI: `http://127.0.0.1:43821/callback`
- Daftarkan fingerprint SHA-1 sertifikat build yang dipakai; lihat `./gradlew signingReport`.

Ikuti pengaturan dan akses pengguna yang berlaku di [Spotify Developer Dashboard](https://developer.spotify.com/dashboard). Kredensial dan sesi pengguna tidak disertakan dalam source.

## Struktur proyek

| Lokasi | Tanggung jawab |
| --- | --- |
| `app/src/main/java/app/nada/player/ui` | Layar dan komponen Compose |
| `MusicViewModel.kt` | State aplikasi dan koordinasi katalog/pemutar |
| `data` | Model lagu serta penyimpanan koleksi lokal dan katalog Audius |
| `youtube` | Pencarian, peringkat hasil, pemilihan stream, dan resolver YouTube |
| `playback` | Media3, service latar belakang, antrean, timer, dan transisi audio |
| `spotify` | PKCE, penyimpanan sesi terenkripsi, katalog, dan App Remote |
| `app/src/test` | Tes unit JVM |
| `app/src/androidTest` | Tes Android dan perjalanan pemutaran opsional |

Lihat [panduan kontribusi](CONTRIBUTING.md) untuk alur kerja dan pengujian, serta [catatan arsitektur](docs/ARCHITECTURE.md) untuk batas antarkomponen.

## Dependensi dan atribusi

Versi dependensi ditentukan di `gradle/libs.versions.toml` dan `app/build.gradle.kts`. SDK Spotify diambil dari rilis resmi dengan verifikasi SHA-256 dan tidak disimpan dalam Git. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) mencatat sumber dan lisensi komponen utama.

NadaKuy tidak berafiliasi dengan Spotify, YouTube, atau Audius; nama dan logo layanan tetap milik pemiliknya.
