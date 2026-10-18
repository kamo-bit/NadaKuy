# Berkontribusi ke NadaKuy

## Persiapan

Ikuti langkah build di [README](README.md), kemudian pastikan aplikasi bisa dibuka pada emulator atau HP. Gunakan Gradle Wrapper yang disertakan agar versi Gradle tetap sama pada semua mesin.

Buat branch untuk satu perubahan yang jelas, misalnya `feature/playlist-sort` atau `fix/playback-transition`. Gunakan commit singkat yang menjelaskan perilaku yang berubah.

## Menulis perubahan

- Pertahankan identitas paket `app.nada.player` dan kompatibilitas koleksi yang sudah tersimpan.
- Tempatkan kode katalog pada modul sumbernya; hindari permintaan jaringan langsung dari composable.
- Simpan identitas lagu yang stabil. URL media bertanda tangan bersifat sementara dan tidak boleh masuk playlist atau riwayat permanen.
- Pisahkan volume pengguna dari peredaman transisi. Operasi pemutar dan MediaController harus mengikuti thread dan lifecycle Android.
- Jangan masukkan token, cookie, data akun, signing key, konfigurasi mesin, APK referensi, atau hasil build ke Git.
- Pertahankan lisensi dan atribusi dependensi ketika memperbarui pustaka atau aset.

## Pemeriksaan sebelum pull request

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Untuk tes di Android, sambungkan satu perangkat/emulator lalu jalankan:

```sh
./gradlew connectedDebugAndroidTest
```

Pada PowerShell gunakan `.\gradlew.bat`. Tes unit mencakup peringkat pencarian, identitas YouTube, pilihan stream audio, callback Spotify, dan envelope volume. Tes Android mencakup penyimpanan koleksi, sesi Spotify, kontrol pemutar, serta identitas lagu. Beberapa tes memakai preferensi dan antrean aplikasi; gunakan emulator atau profil pengujian.

Tes jaringan dan nada uji tidak berjalan secara default. Aktifkan hanya perjalanan yang diperlukan:

| Argumen instrumentasi | Cakupan |
| --- | --- |
| `liveYouTube=true` | Pencarian dan resolusi media dari layanan langsung |
| `liveQuality=true` | Peringkat hasil, decoder, dan AudioTrack |
| `liveSession=true` | Antrean, Next/Previous, akhir lagu, dan latar belakang |
| `transitionTone=true` | Nada deterministik untuk memeriksa transisi audio |

Contoh:

```sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveYouTube=true
```

Untuk `liveSession`, pilih sumber YouTube di aplikasi sebelum pengujian. Tes layanan langsung bergantung pada jaringan dan ketersediaan katalog. Tes otomatis memeriksa state serta error pemutar; kualitas suara tetap perlu didengarkan pada perangkat sasaran, termasuk saat pergantian lagu dan aplikasi di latar belakang.

## Pull request

Jelaskan masalah, hasil perubahan, langkah mencoba, dan pemeriksaan yang benar-benar dijalankan. Untuk perubahan antarmuka, sertakan tangkapan layar. Untuk audio, sebutkan perangkat, versi Android, sumber musik, serta hasil saat Next/Previous dan pemutaran latar belakang.

CI membangun APK debug dan APK uji instrumentasi, menjalankan tes unit, dan memeriksa lint. CI tidak memutar lagu atau menjalankan emulator. Peringatan lint yang sudah ada tidak sama dengan kegagalan build; hindari menambah peringatan baru pada kode yang disentuh.
