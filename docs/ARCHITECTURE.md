# Arsitektur

NadaKuy merupakan aplikasi Android tanpa backend sendiri. Permintaan katalog dan media dilakukan dari perangkat ke layanan masing-masing.

## Katalog dan identitas

`MusicViewModel` mengoordinasikan state UI, sumber aktif, koleksi, dan pemutar. `MusicRepository` mengelola model `Track`, preferensi koleksi, serta akses Audius. `YouTubeCatalog` menyediakan katalog YouTube melalui NewPipe Extractor; `YouTubeSearchRanking` mengurutkan hasil dan `YouTubeAudioSelection` memilih stream.

Identitas sumber dipertahankan pada lagu. Untuk YouTube, URL media sementara diselesaikan kembali pada jalur pemuatan Media3 melalui `YouTubeDataSource`, termasuk setelah restart dan saat lagu berikutnya dimuat. Playlist menyimpan identitas dan metadata, bukan URL stream yang dapat kedaluwarsa.

## Pemutaran

`PlaybackService` memiliki pemutar Media3 dan MediaSession untuk YouTube/Audius. UI menggunakan MediaController. `PlaybackQueue` mempertahankan urutan efektif antrean; `SleepTimer` menangani batas waktu dan akhir lagu. `TransitionPlayer` menerapkan envelope volume untuk transisi tanpa mengganti pengaturan volume pengguna.

Spotify menggunakan App Remote dan pemutaran berlangsung melalui aplikasi Spotify resmi. Resolver YouTube/Audius tidak menerima identitas lagu Spotify.

`NadaApplication` memiliki satu `PlaybackSettings` untuk preferensi normalisasi lokal yang aktif secara default. `MusicViewModel` mengamati nilainya untuk Pengaturan; thread audio membaca state yang sama tanpa menunggu penyimpanan. Sakelar Spotify dinonaktifkan tanpa mengubah pilihan tersimpan.

`MusicPlayer` memasang `NormalizationAudioProcessor` pada jalur PCM 16-bit Media3 sebelum penguatan transisi, audio focus, dan volume pengguna. Jalur ini dipakai streaming YouTube/Audius dan cache offline; byte unduhan tetap utuh. `VolumeNormalization` mengukur RMS streaming dalam blok 100 ms dengan integrasi tiga detik, target −18 dBFS, gate −50 dBFS, serta penguatan terbatas −12 hingga +6 dB. Penguatan turun dengan konstanta waktu 0,5 detik dan naik tiga detik. Limiter dengan lookahead 10 ms memakai penguatan bersama antarkanal dan headroom puncak sampel −1 dBFS; ini bukan pengukuran true peak.

`NormalizationAudioSink` menolak passthrough audio terenkode dan offload agar jalur keluaran tetap melalui decoder dan pemroses PCM, termasuk pada rute audio eksternal. Sink ini memiliki handoff offset stream untuk mengulang pengukuran sambil mempertahankan penguatan saat itu. `NormalizationAudioProcessor` menguras sampel lookahead melalui `getOutput` setelah output sebelumnya dikonsumsi; akhir stream belum selesai selama masih ada sampel tertunda. Input kosong tidak mengganti output yang belum diambil.

Seek membuang audio tertunda sambil mempertahankan penguatan. Saat dinonaktifkan, penguatan kembali ke unity dengan konstanta waktu 50 ms; setelah bypass selesai, sampel diteruskan persis dengan latensi lookahead yang tetap. Ini normalisasi RMS adaptif, bukan R128/LUFS terintegrasi seluruh lagu atau ReplayGain; level persepsi yang sama dan ketiadaan noise tidak dijamin.

`QueuePersistence` mengikuti engine service untuk menyimpan identitas setiap kemunculan lagu, urutan shuffle efektif, repeat, indeks dan posisi saat ini, serta acuan stasiun radio. Pemulihan memasang kembali antrean dalam keadaan dijeda dan belum disiapkan; tindakan Putar menyiapkan sumber audio kembali. Perintah pengguna yang mengganti atau mengosongkan antrean menang atas pemuatan simpanan yang terlambat. Sumber Spotify tidak memulihkan antrean lokal.

`QueueStore` memiliki satu penulis selama proses aplikasi, dengan penggantian file atomik di `noBackupFilesDir/playback`. Struktur antrean dan checkpoint posisi terpisah agar pembaruan posisi tidak menulis ulang seluruh antrean. Revisi struktur mengikat checkpoint ke antrean yang sesuai. Posisi dicatat setiap dua detik selama pemutaran dan saat state pemutar berubah; simpanan dibatasi 20.000 entri dan 8 MiB. Error penyimpanan diteruskan ke UI melalui session extras. Data ini tidak masuk backup koleksi atau transfer perangkat.

`QueueSheet` memakai identitas kemunculan untuk memindahkan lagu berikutnya, termasuk duplikat dan shuffle, sambil mempertahankan lagu saat ini. Service memiliki penerapan urutan. Aksi Simpan membekukan lagu saat ini dan berikutnya saat dialog dibuka, lalu membuat playlist melalui `MusicRepository` dengan deduplikasi identitas lagu yang sama seperti koleksi.

## Radio dan rekomendasi

`ListeningHistory` memiliki hitungan pemutaran yang memenuhi ambang dengar. `ListeningRecommendations` memilih hingga tiga rekaman dari favorit dan minat terkini. `RadioCatalog` menyediakan pool kandidat terkait yang disimpan pada cache katalog; YouTube memakai mix anonim dan pencocokan kembali pada metadata katalog lagu, sedangkan Audius memakai pencarian artis serta tren genre. `RecommendationRanking` mengurutkan kandidat tersebut dengan bobot riwayat terbatas dan variasi artis. Statistik tidak menambahkan lagu di luar kandidat terkait. Cabang Spotify tetap mencari lagu dari artis seed.

`RadioAutoplay` dimiliki service dan mengisi antrean ketika tersisa paling banyak tiga lagu berikutnya. Setiap pengisian menambahkan hingga enam lagu; ini batas kelompok, bukan total stasiun. Ledger maksimal 200 rekaman yang sudah diputar tetap mencegah pengulangan setelah riwayat antrean otomatis dipangkas. Ranking memakai sepuluh entri antrean terakhir, terpisah dari identitas yang dikecualikan. Kegagalan, timeout 45 detik, atau hasil tanpa kandidat dicoba ulang dengan jeda 1/3/10/30 detik melalui pemeriksaan service setiap detik; retry otomatis memerlukan `playWhenReady` dan tidak menyalakan radio yang dijeda.

Untuk radio YouTube aktif, `RadioContinuation` memiliki kandidat mentah, kandidat cocok yang belum dipilih, seed asli, serta kemajuan stasiun berdasarkan identitas antrean. `YouTubeCatalog.radioPages` memiliki cursor `Page` NewPipe dan mengambil halaman berikutnya; halaman kosong berbeda dari akhir sumber, cursor berulang dihentikan, dan kegagalan tidak menghabiskan cursor. Kandidat yang belum dikonsumsi tetap tersedia setelah kegagalan/cancel. Setiap pengisian dibatasi dua halaman dan 16 pencocokan, dengan pool siap delapan, seed asli 128 dan ledger hint 1.000; batas ini mengendalikan pekerjaan/memori, bukan jumlah lagu yang diputar. Saat sumber habis, seed asli yang belum ditelusuri membuka mix lanjutan; artis hasil fallback harus sudah dikenal dari halaman stasiun asli agar tidak bergeser bebas. Cursor dan pool hanya berada di memori, tidak disimpan dalam antrean. Setelah pemulihan proses, stasiun dibuka kembali dari lagu awal. Cache pool katalog tetap dipakai untuk rekomendasi beranda; jalur Audius tetap memakai pencarian artis dan tren genre.

`RecommendationSettings` memiliki autoplay, rekaman kurang disukai, serta artis yang disembunyikan. Pilihan diterapkan pada rekomendasi dan lagu otomatis, sementara antrean manual dan lagu yang sedang diputar dipertahankan. Identitas generasi/antrean mencegah hasil pemuatan yang terlambat masuk ke antrean pengganti. Repeat menunda pengisian; pembatalan dan penghentian service membatalkan pekerjaan yang dimiliki radio.

## Unduhan offline

`OfflineLibrary` memiliki satu `DownloadManager`, `StandaloneDatabaseProvider`, dan `SimpleCache` dengan `NoOpCacheEvictor` selama proses aplikasi berjalan. `OfflineDownloadService` menjalankan transfer di latar belakang sebagai service `dataSync`, dengan dua unduhan paralel dan scheduler Android untuk persyaratan jaringan.

Request unduhan menyimpan identitas serta metadata lagu dan URI sumber yang stabil. Resolver YouTube dipakai kembali ketika transfer dibuka atau dilanjutkan; URL media sementara tidak menjadi identitas unduhan. Cache key pemutaran dan unduhan sama-sama berasal dari `Track.id` melalui `Track.toMediaItem`.

Pemutar memakai cache baca terlebih dahulu, dengan penulisan cache dimatikan untuk streaming biasa. Hanya unduhan eksplisit yang menambah media offline. Status selesai diperiksa terhadap cakupan byte cache sebelum ditampilkan sebagai siap offline. Metadata yang rusak tetap mempertahankan identitas request agar unduhan dapat dihapus.

Audio berada di `noBackupFilesDir`; indeks Media3 dikecualikan dari backup dan transfer agar status selesai tidak dipulihkan tanpa media. Playlist dan lagu disukai terpisah dari indeks ini.

`planPlaylistDownloads` mengklasifikasikan satu snapshot playlist berdasarkan identitas lagu. `OfflineLibrary.addAll` menunggu indeks siap, membaca status terbaru, dan mengirim request untuk lagu baru, dijeda, atau gagal. Lagu selesai, aktif, dan dalam penghapusan dilewati. Syarat jaringan tanpa kuota disimpan oleh pemilik unduhan dan diterapkan kembali ketika proses dibuat.

## Data lokal

Playlist, lagu disukai, riwayat, dan pilihan sumber disimpan dalam preferensi perangkat. Sesi Spotify disimpan terenkripsi dengan kunci Android Keystore di direktori yang dikecualikan dari backup. Tidak ada sinkronisasi koleksi antarpiranti.

`LibraryBackup` memiliki format JSON portabel berversi, validasi input terbatas, dan aturan penggabungan koleksi. Snapshot hanya mencakup playlist, favorit, riwayat, dan pencarian. `MusicRepository.restore` menyimpan keempat bagian dalam satu editor sebelum memublikasikan state baru. Pemilih dokumen Android memberi akses hanya ke file yang dipilih pengguna; aplikasi tidak meminta akses seluruh penyimpanan.

## Pemeriksaan pembaruan

`AppUpdates` mengambil rilis stabil terbaru dari endpoint publik GitHub untuk `kamo-bit/NadaKuy`. Versi dibandingkan sebagai tiga komponen bilangan. Tautan rilis dan APK harus tepat berasal dari repository tersebut; draft, prerelease, dan versi tak dikenal tidak ditawarkan. UI mengambil versi terpasang dari PackageManager. Lifecycle `ON_START` memicu pemeriksaan otomatis melalui jalur yang sama dengan pemeriksaan manual; kegagalan otomatis tidak mengganggu pembukaan aplikasi. `UpdateNoticePolicy` milik proses membatasi pemeriksaan dengan waktu monoton (enam jam setelah berhasil, 30 menit setelah gagal). `UpdateNoticePreferences` menyimpan penundaan 24 jam per versi, dan versi baru tidak ikut ditunda. Pemberitahuan hanya menawarkan rilis lebih baru dengan metadata APK valid; tindakannya membuka bagian pembaruan, bukan mengunduh otomatis.

`AppUpdater` serta worker mengelola unduhan di ruang privat aplikasi. `UpdateDownloader` memeriksa ukuran, SHA-256 dari metadata aset GitHub, paket, versi, serta sertifikat sebelum paket dapat dibagikan melalui `UpdateFileProvider`. Android menangani izin dan konfirmasi pemasangan. Paket yang sudah diperiksa dapat dipasang kembali setelah dialog pemasangan dibatalkan tanpa diunduh ulang.

## Lirik

`LyricsRepository` memiliki permintaan HTTP yang dapat dibatalkan dan cache LRCLIB di `noBackupFilesDir/lyrics`. Kunci cache mencakup identitas, judul, artis, durasi, dan versi format. Pencarian memakai lookup metadata lalu search, dengan fallback bacaan Latin judul Jepang; seluruh rangkaian dibatasi 25 detik. Cache berisi rekaman sumber, divalidasi kembali saat dibaca, dan dibatasi 50 file/8 MB.

`LyricsMatching` memiliki pencocokan metadata dan pemeriksaan toleransi durasi empat detik. `LrcParser` mengubah timestamp, offset, dan timestamp berulang menjadi baris terurut. `activeLyricIndex` menghitung baris aktif langsung dari posisi, sehingga seek tidak bergantung pada cursor lama. Lirik tanpa timestamp yang cocok ditampilkan sebagai teks.

`JapaneseRomaji` membaca kana/kanji melalui kamus Kuromoji IPADIC lokal, lalu menerapkan konversi kana ke romaji. Han tanpa penanda kana tidak otomatis dianggap sebagai lirik Jepang. Teks asli dan timestamp tetap dipertahankan. `LyricsScreen` membaca posisi pemutar setiap 200 ms ketika terbuka, tanpa mempercepat polling state global aplikasi, dan menghentikan auto-scroll saat pengguna menggulir manual.

## Pengujian

Tes JVM melindungi logika yang tidak memerlukan Android. Tes instrumentasi memeriksa batas Android, penyimpanan, MediaSession, dan lifecycle pemutar. Perjalanan yang membutuhkan katalog langsung atau nada uji memakai argumen opt-in agar tes biasa tetap terukur dan tidak memutar audio tanpa sengaja.
