# NadaKuy

Pemutar musik Android berbasis Kotlin dan Jetpack Compose. NadaKuy menyediakan pencarian YouTube Music, YouTube, dan Audius tanpa akun aplikasi, serta koneksi opsional ke Spotify Premium.

## Unduh APK

**[Unduh APK NadaKuy terbaru untuk Android](https://github.com/kamo-bit/NadaKuy/releases/latest)**

Mendukung Android 8.0/API 26 atau lebih baru. Unduh APK melalui browser HP, buka berkasnya, lalu ikuti proses pemasangan Android. Jika diminta, izinkan pemasangan dari browser atau pengelola berkas yang digunakan.

APK ini merupakan build debug untuk pengujian, dengan sertifikat lokal yang sama seperti APK NadaKuy sebelumnya. [Halaman rilis](https://github.com/kamo-bit/NadaKuy/releases/latest) menyediakan catatan versi dan checksum SHA-256.

## Fitur

- Pencarian lagu dan artis, dengan peringkat yang mengutamakan kecocokan judul, artis, dan rekaman asli.
- Rekomendasi lagu terkait lintas artis dari favorit dan minat dengar terkini, serta bagian **Sering kamu putar** di Beranda.
- Radio Lagu dan autoplay, dengan kontrol lokal **Kurang suka** serta **Jangan rekomendasikan artis ini** yang bisa dipulihkan.
- Pemutaran di latar belakang, kontrol notifikasi, antrean, shuffle, repeat, dan sleep timer.
- Normalisasi volume lokal yang aktif secara default dan bisa dimatikan, termasuk untuk musik offline.
- Antrean tersimpan otomatis di perangkat, pengaturan urutan, dan penyimpanan antrean menjadi playlist.
- Lagu disukai, playlist, riwayat pemutaran, dan riwayat pencarian yang disimpan di perangkat.
- Unduhan audio untuk diputar offline di NadaKuy, dengan progres, jeda/lanjutkan, coba lagi, dan penghapusan salinan lokal.
- Unduh seluruh playlist atau lagu disukai sekaligus; lagu yang sudah offline dilewati. Pilihan jaringan tanpa kuota berlaku untuk semua unduhan.
- Backup dan pemulihan playlist, favorit, riwayat putar, serta pencarian melalui pemilih file Android.
- Pembaruan di dalam aplikasi: cek rilis resmi, unduh dengan progres, periksa paket, lalu pasang melalui konfirmasi Android.
- Lirik berjalan dari LRCLIB, sorotan baris aktif, dan ketuk baris untuk seek. Romaji Jepang dibuat lokal di perangkat, dengan teks asli tetap tersedia.
- Pemutar layar penuh dengan warna yang mengikuti sampul album.
- Spotify App Remote untuk mengendalikan pemutaran melalui aplikasi Spotify resmi.

YouTube Music menjadi sumber awal. YouTube dan Audius tidak memerlukan API key pengguna. Integrasi YouTube menggunakan NewPipe Extractor; perubahan layanan upstream dapat memerlukan pembaruan dependensi atau resolver. Peringkat pencarian memakai heuristik dan tidak menjamin semua hasil merupakan rekaman resmi.

## Rekomendasi dan pencarian

**Pilihan untukmu** memakai maksimal tiga rekaman sebagai titik awal, dengan mengutamakan dua artis favorit berdasarkan hitungan dengar dan satu minat terkini. Saat belum ada hitungan pemutaran, riwayat yang sudah tersimpan menjadi titik awal. YouTube dan Audius mengambil kandidat terkait yang dapat berasal dari artis lain; Spotify opsional tetap mencari lagu dari artis titik awal. Lagu yang ada dalam riwayat atau statistik tidak disarankan kembali, termasuk unggahan lain dengan judul/artis yang sama. Bagian **Sering kamu putar** muncul setelah sebuah lagu memiliki setidaknya dua pemutaran yang dihitung.

Satu pemutaran dihitung setelah 30 detik audio benar-benar berjalan, atau setengah durasi untuk lagu yang lebih pendek. Jeda, buffering dan lompatan posisi tidak menambah waktu dengar. Penghitungan YouTube/Audius dimiliki service pemutar sehingga tetap bekerja di latar belakang; Spotify dihitung selama App Remote terhubung dan pemutarannya teramati. Statistik dibatasi 500 rekaman serta ukuran data 1 MiB, disimpan di perangkat dan tidak masuk backup koleksi atau backup Android. Permintaan rekomendasi memakai metadata lagu atau artis yang diperlukan oleh katalog yang dipilih, tanpa mengirim seluruh statistik.

Pencarian YouTube/YouTube Music mendahulukan kecocokan permintaan, lalu versi yang diminta dan rekaman dengan petunjuk studio/resmi. Cover, live, karaoke, remix, versi dipercepat/diperlambat dan kompilasi diturunkan ketika tidak diminta. Jika pengguna menulis **live**, **cover**, atau jenis versi lain, permintaan itu tetap dihormati. Petunjuk kanal Topic, VEVO, unggahan terverifikasi dan judul resmi membantu pengurutan, tetapi tidak membuktikan siapa pemilik rekaman asli. Versi alternatif tetap tersedia.

## Radio Lagu dan autoplay

Buka menu tiga titik pada lagu YouTube Music, YouTube, atau Audius, lalu pilih **Mulai Radio Lagu**. Lagu tersebut mulai diputar, menggantikan antrean sebelumnya, dan autoplay diaktifkan untuk menambahkan lagu berikutnya secara otomatis. Lagu tambahan ditandai **Ditambahkan radio** pada antrean.

Autoplay awalnya mati. Ubah melalui **Pengaturan → Radio dan rekomendasi** atau sakelar **Autoplay** pada antrean. Autoplay menambahkan lagu setelah antrean pilihanmu; repeat menunda pengisian otomatis sampai repeat dimatikan. Mematikan autoplay membuang lagu otomatis yang belum diputar, sambil mempertahankan lagu saat ini dan lagu yang kamu tambahkan sendiri.

Setiap pengisian menambahkan maksimal enam lagu, tanpa batas total enam atau sepuluh lagu. Saat tersisa paling banyak tiga lagu berikutnya, autoplay mulai mengisi antrean lagi. Radio YouTube melanjutkan halaman mix dan menyimpan kandidat yang belum dipilih; ketika halaman habis, pencarian dilanjutkan dari rekaman terkait dalam stasiun awal. Jumlah tambahan tetap bergantung pada kandidat yang tersedia dan sesuai. Pemuatan yang gagal dicoba ulang otomatis dengan jeda bertahap selama pemutaran masih diaktifkan; kamu juga bisa menekan **Coba lagi**. Radio yang dijeda tetap dijeda.

Pada menu lagu, pilih **Kurang suka** untuk menyembunyikan rekaman tersebut atau **Jangan rekomendasikan artis ini** untuk menyembunyikan artis. Pilihan ini berlaku pada **Pilihan untukmu**, Radio Lagu, dan autoplay; lagu tetap tersedia di pencarian dan koleksi, serta tidak menghapus pilihan manual dari antrean atau menghentikan lagu saat ini. Buka **Pengaturan → Radio dan rekomendasi → Kelola kontrol rekomendasi**, lalu tekan **Pulihkan** pada lagu atau artis untuk membatalkan satu pilihan. Kontrol disimpan lokal tanpa akun aplikasi atau API key pengguna.

Radio YouTube mengambil mix musik publik tanpa akun berdasarkan lagu awal, lalu mencocokkan kandidat kembali di katalog lagu YouTube Music untuk mengenali artis rekamannya; petunjuk lagu terkait menjadi cadangan jika mix tidak tersedia. Audius menggabungkan pencarian artis dan tren genre. Pengisian berikutnya menelusuri beberapa artis dari mix awal, dengan mengutamakan artis yang belum banyak muncul belakangan. Lagu awal tetap menjadi acuan, termasuk ketika memutar artis lain. Riwayat dengar memberi bobot tambahan pada kandidat terkait, tanpa memasukkan favorit di luar kandidat tersebut.

Urutan radio dan rekomendasi mengutamakan variasi artis serta berusaha menghindari artis yang sama berturut-turut. Jika kandidat sedikit, artis dapat berulang agar pemutaran tetap berlanjut. Hasil katalog disimpan dalam cache terbatas; kontrol lokal dan riwayat diterapkan saat memilih lagu. Kemiripan lagu, pencocokan rekaman/artis, dan peringkat versi asli memakai heuristik serta metadata sumber; alias musisi belum selalu dikenali sebagai artis yang sama, dan hasil tidak menjamin rekaman resmi atau kemiripan yang selalu tepat. Radio memerlukan koneksi dan bergantung pada ketersediaan katalog.

## Normalisasi volume

Buka **Pengaturan → Pemutaran → Normalisasi volume** untuk mengaktifkan atau mematikannya. Fitur ini aktif secara default dan menyesuaikan penguatan audio secara bertahap untuk mengurangi perbedaan level antar lagu YouTube Music, YouTube, dan Audius, termasuk salinan offline. Pilihan disimpan di perangkat; tidak perlu login atau izin tambahan, dan berkas unduhan tidak diubah.

Penyesuaian mengikuti audio yang sedang diputar, sehingga membutuhkan waktu untuk beradaptasi dan tidak menjamin semua lagu terdengar sama keras. Volume perangkat tetap kamu atur sendiri. Saat sumber Spotify dipilih, sakelar dinonaktifkan karena audio ditangani aplikasi Spotify resmi; atur normalisasi di Spotify. Pilihan NadaKuy tetap disimpan untuk YouTube dan Audius.

## Antrean tersimpan

Untuk YouTube Music, YouTube, dan Audius, antrean serta posisi lagu disimpan otomatis di perangkat. Saat aplikasi dibuka kembali, lagu saat ini, urutan termasuk shuffle, repeat, dan posisi terakhir yang tersimpan dipulihkan dalam keadaan dijeda. Tekan **Putar** untuk melanjutkan; pemutar menyiapkan sumber audio kembali saat itu.

Buka **Antrean** pada pemutar untuk melihat **Sedang diputar** dan **Berikutnya**. Geser pegangan di kanan lagu berikutnya untuk mengatur urutan, atau ketuk pegangan lalu pilih **Pindahkan ke atas/bawah**. Lagu saat ini tetap di tempatnya. Urutan yang terlihat mengikuti shuffle bila aktif; lagu yang sama dapat muncul beberapa kali.

Tekan **Simpan**, beri nama, lalu simpan ke playlist baru. Playlist berisi lagu saat ini dan lagu berikutnya sesuai urutan saat dialog dibuka; lagu yang sama disimpan sekali. Perubahan antrean setelah dialog dibuka tidak mengubah pilihan tersebut.

Antrean tersimpan hanya berlaku pada perangkat ini dan tidak masuk backup koleksi atau transfer perangkat. Jika penyimpanan atau pemulihan gagal, keterangannya muncul di antrean. Antrean Spotify tetap dikelola aplikasi Spotify resmi.

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

Saat aplikasi dibuka, NadaKuy memeriksa rilis stabil terbaru dan menampilkan pemberitahuan jika versi lebih baru beserta paketnya tersedia. **Lihat pembaruan** membuka bagian pembaruan di Pengaturan; **Nanti**, tombol Kembali, atau menutup dialog menunda pengingat versi tersebut selama 24 jam. Pemeriksaan otomatis dibatasi enam jam selama proses aplikasi yang sama (30 menit setelah kegagalan), sedangkan pemeriksaan manual tetap tersedia. Gangguan internet tidak menampilkan dialog error saat membuka aplikasi. Tidak ada unduhan atau pemasangan otomatis.

Paket diambil dari rilis resmi dan disimpan di ruang privat aplikasi; ukuran, SHA-256, nama paket, versi dan sertifikat penandatanganan diperiksa sebelum pemasangan. Unduhan yang terputus dicoba ulang secara terbatas. Jika paket gagal diperiksa, NadaKuy menyediakan tindakan unduh ulang. Paket pembaruan tidak disertakan dalam backup koleksi. Riwayat rilis GitHub tetap tersedia sebagai tautan tambahan.

Versi lama yang masih membuka browser perlu memasang APK dengan fitur ini satu kali. Setelah itu, unduhan pembaruan berikutnya dilakukan dari Pengaturan. Android tetap memerlukan persetujuan pemasangan; pembaruan mengambil paket APK baru dan mengganti instalasi yang ada.

## Lirik dan romaji

Buka pemutar layar penuh lalu tekan **Lirik** di kanan atas. Lirik dengan timestamp mengikuti posisi pemutar, termasuk setelah seek; ketuk satu baris untuk melompat ke bagian tersebut. Gulir manual untuk membaca bagian lain, lalu tekan **Ikuti lagu** untuk kembali mengikuti pemutaran.

Untuk lirik Jepang, pilihan **Romaji** menampilkan bacaan Latin di bawah tulisan asli. Konversi memakai kamus Jepang lokal, bukan penerjemahan bahasa Mandarin. Bacaan otomatis dapat berbeda untuk nama, ejaan khusus, atau permainan kata. Pilihan romaji hanya muncul ketika ada hasil konversi.

Lirik diperoleh dari LRCLIB berdasarkan judul, artis, dan durasi. Timestamp hanya dipakai jika durasi rekaman cocok dalam toleransi empat detik; versi lain dapat memakai lirik teks dengan keterangan waktu belum tersedia. Ketersediaan dan ketepatan lirik bergantung pada sumber. Lirik yang berhasil dibuka disimpan lokal untuk dibaca lagi tanpa internet; men-download audio tidak otomatis men-download lirik. Cache lirik dibatasi 50 rekaman dan 8 MB, dan tidak disertakan dalam backup koleksi.

Jika pencarian otomatis tidak menemukan lirik atau memilih versi yang salah, tekan **Cari lirik** pada layar lirik atau ikon cari di kanan atas. Pencarian bebas di LRCLIB menampilkan judul, artis, album, durasi, dan status sinkron setiap hasil; ketuk hasil untuk melihat pratinjau, lalu pilih **Gunakan lirik ini**. Pilihan tetap memakai pemeriksaan durasi: jika selisihnya lebih dari empat detik, lirik tampil sebagai teks tanpa waktu. Pilihan disimpan untuk lagu tersebut bersama cache lirik, dapat diganti kapan saja, atau dikembalikan lewat **Kembali ke pencocokan otomatis**.

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
