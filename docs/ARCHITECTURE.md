# Arsitektur

NadaKuy merupakan aplikasi Android tanpa backend sendiri. Permintaan katalog dan media dilakukan dari perangkat ke layanan masing-masing.

## Katalog dan identitas

`MusicViewModel` mengoordinasikan state UI, sumber aktif, koleksi, dan pemutar. `MusicRepository` mengelola model `Track`, preferensi koleksi, serta akses Audius. `YouTubeCatalog` menyediakan katalog YouTube melalui NewPipe Extractor; `YouTubeSearchRanking` mengurutkan hasil dan `YouTubeAudioSelection` memilih stream.

Identitas sumber dipertahankan pada lagu. Untuk YouTube, URL media sementara diselesaikan kembali pada jalur pemuatan Media3 melalui `YouTubeDataSource`, termasuk setelah restart dan saat lagu berikutnya dimuat. Playlist menyimpan identitas dan metadata, bukan URL stream yang dapat kedaluwarsa.

## Pemutaran

`PlaybackService` memiliki pemutar Media3 dan MediaSession untuk YouTube/Audius. UI menggunakan MediaController. `PlaybackQueue` mempertahankan urutan efektif antrean; `SleepTimer` menangani batas waktu dan akhir lagu. `TransitionPlayer` menerapkan envelope volume untuk transisi tanpa mengganti pengaturan volume pengguna.

Spotify menggunakan App Remote dan pemutaran berlangsung melalui aplikasi Spotify resmi. Resolver YouTube/Audius tidak menerima identitas lagu Spotify.

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

`AppUpdates` mengambil rilis stabil terbaru dari endpoint publik GitHub untuk `kamo-bit/NadaKuy`. Versi dibandingkan sebagai tiga komponen bilangan. Tautan rilis dan APK harus tepat berasal dari repository tersebut; draft, prerelease, dan versi tak dikenal tidak ditawarkan. UI mengambil versi terpasang dari PackageManager dan melakukan pemeriksaan hanya atas permintaan pengguna. Pengunduhan dibuka melalui browser, sementara Android tetap menangani persetujuan pemasangan dan pemeriksaan sertifikat APK.

## Pengujian

Tes JVM melindungi logika yang tidak memerlukan Android. Tes instrumentasi memeriksa batas Android, penyimpanan, MediaSession, dan lifecycle pemutar. Perjalanan yang membutuhkan katalog langsung atau nada uji memakai argumen opt-in agar tes biasa tetap terukur dan tidak memutar audio tanpa sengaja.
