# NadaKuy

Pemutar musik Android berbasis Kotlin dan Jetpack Compose. NadaKuy menyediakan pencarian YouTube Music, YouTube, dan Audius tanpa akun aplikasi, serta koneksi opsional ke Spotify Premium.

## Unduh APK

**[Unduh APK NadaKuy terbaru untuk Android](https://github.com/kamo-bit/NadaKuy/releases/latest)**

Mendukung Android 8.0/API 26 atau lebih baru. Unduh APK melalui browser HP, buka berkasnya, lalu ikuti proses pemasangan Android. Jika diminta, izinkan pemasangan dari browser atau pengelola berkas yang digunakan.

APK ini merupakan build debug untuk pengujian, dengan sertifikat lokal yang sama seperti APK NadaKuy sebelumnya. [Halaman rilis](https://github.com/kamo-bit/NadaKuy/releases/latest) menyediakan catatan versi dan checksum SHA-256.

## Fitur

- Pencarian lagu dan artis, dengan peringkat yang mengutamakan kecocokan judul, artis, dan rekaman asli.
- Pemutaran di latar belakang, kontrol notifikasi, antrean, shuffle, repeat, dan sleep timer.
- Lagu disukai, playlist, riwayat pemutaran, dan riwayat pencarian yang disimpan di perangkat.
- Unduhan audio untuk diputar offline di NadaKuy, dengan progres, jeda/lanjutkan, coba lagi, dan penghapusan salinan lokal.
- Unduh seluruh playlist atau lagu disukai sekaligus; lagu yang sudah offline dilewati. Pilihan jaringan tanpa kuota berlaku untuk semua unduhan.
- Backup dan pemulihan playlist, favorit, riwayat putar, serta pencarian melalui pemilih file Android.
- Pembaruan di dalam aplikasi: cek rilis resmi, unduh dengan progres, periksa paket, lalu pasang melalui konfirmasi Android.
- Lirik berjalan dari LRCLIB, sorotan baris aktif, dan ketuk baris untuk seek. Romaji Jepang dibuat lokal di perangkat, dengan teks asli tetap tersedia.
- Pemutar layar penuh dengan warna yang mengikuti sampul album.
- Spotify App Remote untuk mengendalikan pemutaran melalui aplikasi Spotify resmi.

YouTube Music menjadi sumber awal. YouTube dan Audius tidak memerlukan API key pengguna. Integrasi YouTube menggunakan NewPipe Extractor; perubahan layanan upstream dapat memerlukan pembaruan dependensi atau resolver. Peringkat pencarian memakai heuristik dan tidak menjamin semua hasil merupakan rekaman resmi.

## Mendengarkan offline

1. Cari lagu YouTube Music, YouTube, atau Audius, lalu buka menu tiga titik pada lagu.
2. Pilih **Unduh untuk offline**. Izinkan notifikasi jika ingin melihat progres di luar aplikasi.
3. Buka **Koleksi → Unduhan**. Status **Siap offline** menandakan audio sudah tersimpan sepenuhnya.
4. Ketuk lagu atau **Putar semua unduhan** untuk mendengarkan salinan lokal, termasuk saat internet dimatikan.

Tombol unduh juga tersedia pada pemutar layar penuh. Unduhan dapat dijeda, dilanjutkan, dicoba kembali, dan dihapus melalui halaman Unduhan. Unduhan yang belum selesai menunggu koneksi jika jaringan terputus. Audio disimpan di ruang aplikasi pada perangkat ini, bukan diekspor sebagai MP3 ke folder Download. Data unduhan tidak ikut backup atau transfer perangkat; koleksi dan playlist tetap memakai penyimpanan yang sudah ada.

Unduhan Spotify tidak tersedia melalui App Remote; pemutaran Spotify tetap ditangani aplikasi Spotify resmi. Menghapus NadaKuy atau membersihkan seluruh data aplikasi akan menghapus salinan offline.

Untuk mengunduh seluruh playlist, buka playlist atau **Lagu yang disukai**, tekan **Unduh semua**, lalu konfirmasi jumlah lagunya. Lagu yang sudah tersimpan atau sedang diproses tidak diunduh ulang. Lagu yang gagal atau dijeda dapat dimasukkan kembali ke antrean. Batas dua unduhan bersamaan tetap berlaku; progres setiap lagu tersedia di **Koleksi → Unduhan**.

## Backup dan pembaruan

Buka tab **Pengaturan** di ujung kanan navbar, setelah **Koleksi**. Pada layar lebar, urutan yang sama tersedia di navigation rail. Pengaturan juga dapat dibuka lewat menu **Tentang NadaKuy** di Beranda.

- **Simpan backup** membuat file JSON melalui pemilih lokasi Android. File berisi metadata koleksi, tanpa audio offline, token, atau koneksi Spotify.
- **Pulihkan dari file** memeriksa isi backup dan menampilkan ringkasan sebelum menggabungkan. Koleksi yang ada tetap disimpan; identitas yang sama digabungkan tanpa duplikat, dan nama playlist lokal dipertahankan. Batas file 8 MB, 1.000 playlist, dan 20.000 entri lagu; riwayat tetap dibatasi 40 lagu serta 10 pencarian.
- **Hanya jaringan tanpa kuota** mengikuti penanda koneksi Android, biasanya Wi-Fi. Berlaku untuk musik, playlist, dan paket pembaruan; unduhan menunggu jika syarat koneksi belum terpenuhi.
- **Cek pembaruan** meminta rilis stabil terbaru dari GitHub tanpa login. Pilih **Unduh pembaruan** untuk mengambil paket langsung di NadaKuy, dengan progres dan tombol batal. Unduhan tetap dikelola ketika pengguna pindah halaman atau aplikasi berada di latar belakang.
- Setelah pemeriksaan paket selesai, pilih **Pasang pembaruan**. Android dapat meminta izin pemasangan dari NadaKuy, kemudian menampilkan konfirmasi **Perbarui**. Koleksi dan audio offline tetap tersimpan. Jika pemasangan dibatalkan, paket tetap siap dipasang tanpa diunduh ulang.

Pemeriksaan versi dilakukan atas permintaan. Paket diambil dari rilis resmi dan disimpan di ruang privat aplikasi; ukuran, SHA-256, nama paket, versi dan sertifikat penandatanganan diperiksa sebelum pemasangan. Unduhan yang terputus dicoba ulang secara terbatas. Jika paket gagal diperiksa, NadaKuy menyediakan tindakan unduh ulang. Paket pembaruan tidak disertakan dalam backup koleksi. Riwayat rilis GitHub tetap tersedia sebagai tautan tambahan.

Versi lama yang masih membuka browser perlu memasang APK dengan fitur ini satu kali. Setelah itu, unduhan pembaruan berikutnya dilakukan dari Pengaturan. Android tetap memerlukan persetujuan pemasangan; pembaruan mengambil paket APK baru dan mengganti instalasi yang ada.

## Lirik dan romaji

Buka pemutar layar penuh lalu tekan **Lirik** di kanan atas. Lirik dengan timestamp mengikuti posisi pemutar, termasuk setelah seek; ketuk satu baris untuk melompat ke bagian tersebut. Gulir manual untuk membaca bagian lain, lalu tekan **Ikuti lagu** untuk kembali mengikuti pemutaran.

Untuk lirik Jepang, pilihan **Romaji** menampilkan bacaan Latin di bawah tulisan asli. Konversi memakai kamus Jepang lokal, bukan penerjemahan bahasa Mandarin. Bacaan otomatis dapat berbeda untuk nama, ejaan khusus, atau permainan kata. Pilihan romaji hanya muncul ketika ada hasil konversi.

Lirik diperoleh dari LRCLIB berdasarkan judul, artis, dan durasi. Timestamp hanya dipakai jika durasi rekaman cocok dalam toleransi empat detik; versi lain dapat memakai lirik teks dengan keterangan waktu belum tersedia. Ketersediaan dan ketepatan lirik bergantung pada sumber. Lirik yang berhasil dibuka disimpan lokal untuk dibaca lagi tanpa internet; men-download audio tidak otomatis men-download lirik. Cache lirik dibatasi 50 rekaman dan 8 MB, dan tidak disertakan dalam backup koleksi.

Beberapa rekaman memiliki pemilihan sumber dan koreksi waktu khusus yang telah dibandingkan dengan vokalnya. Koreksi dibatasi pada identitas rekaman, durasi, ID sumber, serta fingerprint isi dan timestamp lirik. Jika data sumber berubah, koreksi lama tidak diterapkan. Data cache tetap menyimpan versi asli dari penyedia agar pergeseran waktu tidak bertambah saat lirik dibuka ulang.

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
| `downloads` | Indeks unduhan, cache audio persisten, dan service unduhan latar belakang |
| `spotify` | PKCE, penyimpanan sesi terenkripsi, katalog, dan App Remote |
| `app/src/test` | Tes unit JVM |
| `app/src/androidTest` | Tes Android dan perjalanan pemutaran opsional |

Lihat [panduan kontribusi](CONTRIBUTING.md) untuk alur kerja dan pengujian, serta [catatan arsitektur](docs/ARCHITECTURE.md) untuk batas antarkomponen.

## Dependensi dan atribusi

Versi dependensi ditentukan di `gradle/libs.versions.toml` dan `app/build.gradle.kts`. SDK Spotify diambil dari rilis resmi dengan verifikasi SHA-256 dan tidak disimpan dalam Git. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) mencatat sumber dan lisensi komponen utama.

NadaKuy tidak berafiliasi dengan Spotify, YouTube, atau Audius; nama dan logo layanan tetap milik pemiliknya.
