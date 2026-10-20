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

Untuk radio dan rekomendasi, `RadioSelectionTest` menguji penyaringan umpan balik, identitas rekaman/artis, duplikat, sumber, dan batas kelompok. `RecommendationRankingTest` mencakup variasi artis, riwayat yang hanya membobot kandidat terkait, fallback kandidat sedikit, prefix pagination yang stabil, seed minat terkini, serta pencegahan pergeseran dari stasiun awal. `RelatedRecordingMatchTest` membedakan artis rekaman dari nama artis dalam judul cover, mengenali kredit artis pada unggahan label, serta menolak versi tribute/backing dan durasi yang tidak cocok.

`RecommendationSettingsTest` memeriksa default autoplay mati, penyimpanan ulang, dan pemulihan pilihan individual. `RadioControlsUiTest` menguji aksi menu, sakelar berlabel aksesibel, serta pemulihan lagu/artis pada komponen Compose. `RadioAutoplayTest` memakai pemutar asli dengan WAV lokal dan kandidat sintetis untuk menguji transisi decoder, hasil terlambat setelah antrean diganti, pembatalan autoplay, repeat, penyaringan lagu otomatis tanpa menghapus pilihan manual, batas riwayat radio, dan acuan lagu awal saat pengisian berikutnya mencapai artis lain. Tes lokal ini tidak membuktikan ketersediaan katalog langsung. Variasi artis merupakan preferensi dengan fallback ketika kandidat sedikit, bukan kuota mutlak.

Tes jaringan dan nada uji tidak berjalan secara default. Aktifkan hanya perjalanan yang diperlukan:

| Argumen instrumentasi | Cakupan |
| --- | --- |
| `liveYouTube=true` | Pencarian dan resolusi media dari layanan langsung |
| `liveQuality=true` | Peringkat hasil, decoder, dan AudioTrack |
| `liveSession=true` | Antrean, Next/Previous, akhir lagu, dan latar belakang |
| `transitionTone=true` | Nada deterministik untuk memeriksa transisi audio |
| `liveLyrics=true` | Lirik nyata Inggris/Jepang, pembacaan kanji, serta pembukaan ulang cache; juga memakai pencarian YouTube Music |
| `liveLyricsCatalog=true` | Enam rekaman Jepang/Indonesia; menyimpan metadata dan hasil lirik lokal untuk audit opsional |
| `liveLyricsPlayback=true` | Seek maju/mundur, ketuk baris, Next/Previous dan pergantian otomatis pada service dan layar lirik asli; pilih sampel dengan `lyricsSong` |
| `liveLyricsTiming=true` | Enam identitas rekaman yang tersimpan; memeriksa pemilihan sumber, waktu terkoreksi, serta kesamaan hasil jaringan dan cache |
| `acousticExport=true` | Menyimpan byte audio keenam rekaman melalui resolver/cache pemutar ke ruang privat aplikasi, beserta ID, ukuran dan SHA-256 untuk pemeriksaan akustik lokal |
| `liveAppUpdateFixtures=true` | Pemeriksaan APK nyata, checksum/sertifikat/versi salah dan pembatalan transfer; membutuhkan fixture APK baru yang ditandatangani serta fixture bersertifikat berbeda di external files aplikasi |
| `liveRecommendations=true` | Pencarian katalog nyata dan pemilihan rekomendasi lintas artis dari kandidat terkait dengan seed riwayat; tidak membuktikan hak kepemilikan rekaman |
| `liveRecommendationCache=true` | Refresh katalog nyata, buka ulang cache rekomendasi, pemuatan kelompok berikutnya, serta cache disk/memori gambar album; membutuhkan riwayat dengar pada emulator |
| `liveRecommendationPlayback=true` | Dua pemutaran katalog nyata melewati ambang 30 detik; sengaja menambah riwayat dan hitungan lokal pada profil uji |
| `liveRadio=true` | `RadioLiveTest`: katalog YouTube Music nyata, service/MediaController dan decoder, Next/Previous, penyaringan/pemulihan lagu serta artis, sleep timer, transisi otomatis di latar belakang, dan penghapusan lagu otomatis saat autoplay dimatikan |
| `liveRadioDiversity=true` | Mix YouTube anonim untuk Kangen, Dan, dan The Lazy Song; variasi artis pada kandidat mentah dan lagu yang sudah dicocokkan, urutan pilihan, serta refresh/cache pool katalog |
| `liveRadioContinuation=true` | `RadioContinuationLiveTest`: 12 lagu melalui service/decoder asli, pengisian otomatis melewati kelompok pertama, variasi artis, acuan stasiun awal, serta paruh kedua di latar belakang; `radioQuery` opsional memilih lagu awal |

Contoh:

```sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveYouTube=true
```

Untuk `liveSession`, pilih sumber YouTube di aplikasi sebelum pengujian. Tes layanan langsung bergantung pada jaringan dan ketersediaan katalog. Tes otomatis memeriksa state serta error pemutar; kualitas suara tetap perlu didengarkan pada perangkat sasaran, termasuk saat pergantian lagu dan aplikasi di latar belakang.

Jalankan `RadioLiveTest` dengan `-Pandroid.testInstrumentationRunnerArguments.liveRadio=true` pada profil uji: tes mengganti antrean dan benar-benar memutar audio. Bukti metadata lagu dan transisi disimpan di `noBackupFilesDir/radio-live.txt` pada perangkat. Transisi diperiksa saat Activity berada pada `CREATED`, sementara service tetap memutar. Cakupan ini tidak membuktikan ketersediaan Audius, kemiripan semua rekomendasi, atau kualitas suara secara akustik. Untuk perubahan radio, periksa juga menu lagu, antrean, dan pemulihan pilihan melalui Pengaturan pada HP, tablet, serta ukuran font diperbesar.

`RadioDiversityLiveTest` hanya berjalan dengan `liveRadioDiversity=true` dan menyimpan metadata kandidat/pilihan serta waktu refresh/cache di `noBackupFilesDir/radio-diversity-live.txt`. Periksa artis hasil pencocokan, bukan hanya nama kanal atau nama artis dalam judul. Angka variasi dari tiga seed ini tidak menjamin semua stasiun memiliki kandidat sebanyak itu atau semua alias musisi dikenali. `RecommendationCacheLiveTest` memakai ViewModel asli untuk refresh, buka ulang dan pagination, lalu memeriksa cache disk/memori Coil; waktu refresh dan buka ulang adalah pengukuran berbeda, bukan bukti percepatan keseluruhan.

`RadioContinuationLiveTest` menyimpan setiap pengisian antrean dan lagu yang diputar di `noBackupFilesDir/radio-continuation-live.txt`, termasuk saat gagal. Tes memutar awal setiap lagu selama sedikitnya tiga detik, lalu melakukan seek ke 1,2 detik terakhir agar pergantian akhir lagu tetap dilakukan pemutar. Tes tidak menambahkan kandidat atau menekan Next, dan memeriksa bahwa lagu setelah kelompok pertama benar-benar diputar. Ini merupakan uji transisi yang dipercepat, bukan pengujian mendengarkan 12 lagu penuh atau penilaian akustik. Gunakan profil uji karena antrean diganti dan audio benar-benar diputar.

Untuk audit lirik, jalankan `LyricsCatalogLiveTest` dengan `liveLyricsCatalog=true` dahulu, lalu `LyricsPlaybackLiveTest` dengan `liveLyricsPlayback=true` dan `lyricsSong=yoru`, `lemon`, `refrain`, `kangen`, `dan`, atau `yangterdalam`. Jalankan setiap sampel dalam proses instrumentasi terpisah. Hasil rinci disimpan lokal, bukan ditulis ke log atau repository. Sorotan diuji terhadap timestamp lirik dan posisi Media3; tes ini tidak menilai keselarasan vokal secara akustik atau menjamin bacaan khusus semua lagu.

`LyricsTimingLiveTest` dan `LyricsAcousticExportTest` memakai metadata rekaman yang disimpan audit katalog, sehingga tidak mengganti hasil pencarian di tengah pengujian. Ekspor akustik mengunduh audio dan hanya berjalan dengan argumennya sendiri. Pisahkan validasi cursor dari pembandingan onset vokal terhadap audio. Pengukuran otomatis pada nyanyian dapat keliru; periksa beberapa bagian lagu dan abaikan pencocokan yang ambigu sebelum mengubah profil waktu. Simpan rekaman, transkripsi dan hasil pengukuran lengkap secara privat. Profil produksi hanya berisi identitas, fingerprint, dan koreksi waktu; perubahan lirik atau rekaman harus membatalkan koreksi lama.

## Pull request

`OfflineDownloadsTest` menguji service, kecocokan byte cache, pemutar asli tanpa upstream, pemulihan media yang hilang, dan penghapusan metadata rusak dengan fixture lokal. Untuk perubahan unduhan, uji juga lagu dari katalog langsung: unduh, matikan internet, buka ulang aplikasi, putar dan seek, lalu hapus salinan offline. Pastikan jeda/lanjutkan dan kondisi menunggu jaringan tetap jelas. Fixture lokal tidak membuktikan ketersediaan katalog layanan langsung.

Jelaskan masalah, hasil perubahan, langkah mencoba, dan pemeriksaan yang benar-benar dijalankan. Untuk perubahan antarmuka, sertakan tangkapan layar. Untuk audio, sebutkan perangkat, versi Android, sumber musik, serta hasil saat Next/Previous dan pemutaran latar belakang.

CI membangun APK debug dan APK uji instrumentasi, menjalankan tes unit, dan memeriksa lint. CI tidak memutar lagu atau menjalankan emulator. Peringatan lint yang sudah ada tidak sama dengan kegagalan build; hindari menambah peringatan baru pada kode yang disentuh.

Untuk backup, uji ekspor/pilih file melalui Storage Access Framework, batal pada pratinjau, penggabungan dua kali, dan file tidak valid tanpa perubahan koleksi. Audio serta sesi Spotify tidak boleh masuk format portabel. Untuk unduhan playlist, pastikan lagu yang sudah selesai/aktif dilewati dan jumlah pada konfirmasi memakai seluruh playlist, termasuk saat pencarian lokal memfilter daftar. Pemeriksaan versi harus membandingkan angka dengan benar, tidak menawarkan downgrade, menolak rilis di luar repository resmi, dan menampilkan pemulihan saat jaringan gagal.

Untuk lirik, pertahankan pasangan judul/artis dan pemeriksaan durasi sebelum memakai timestamp. Jangan memasang waktu buatan pada lirik teks. Uji cursor sebelum baris pertama, seek dua arah, pergantian lagu, gulir manual/Ikuti lagu, dan tampilan asli saat romaji dimatikan. Tes kamus harus berjalan juga di Android agar packaging resource Kuromoji benar-benar terverifikasi. Lirik nyata tidak dimasukkan sebagai fixture di repository; gunakan teks uji sendiri dan log hanya metadata serta jumlah baris.

Untuk pembaruan aplikasi, gunakan APK dengan paket dan sertifikat yang sama serta versionCode lebih tinggi. Pada emulator pengujian, build debug bernomor lama dapat dibuat dengan `assembleDebug '-PqaVersionCode=12' '-PqaVersionName=1.6.1'`. Jangan menerbitkan build pengujian ini. Simpan APK final sebelum membangun versi uji. `UpdateDownloadTest` memakai `update-final.apk` dan `update-wrong-signer.apk` pada `getExternalFilesDir(null)`; fixture tidak dimasukkan ke Git. Tes UI memakai state transfer sintetis dan tidak membuktikan unduhan jaringan.

Uji juga alur rilis nyata: cek versi, unduh, pindah ke Home lalu kembali, batalkan izin/dialog pemasangan dan pastikan paket tidak diunduh ulang. Terakhir, pasang pembaruan melalui pemasang Android dan periksa versi, koleksi serta indeks audio offline. APK disimpan privat dan dibagikan ke pemasang melalui FileProvider dengan izin baca untuk berkas tersebut. WorkManager memiliki lifecycle transfer; jangan mengganti progres dengan timer atau memasang paket tanpa pemeriksaan ulang sebelum intent pemasangan.
