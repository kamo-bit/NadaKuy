# Arsitektur

NadaKuy merupakan aplikasi Android tanpa backend sendiri. Permintaan katalog dan media dilakukan dari perangkat ke layanan masing-masing.

## Katalog dan identitas

`MusicViewModel` mengoordinasikan state UI, sumber aktif, koleksi, dan pemutar. `MusicRepository` mengelola model `Track`, preferensi koleksi, serta akses Audius. `YouTubeCatalog` menyediakan katalog YouTube melalui NewPipe Extractor; `YouTubeSearchRanking` mengurutkan hasil dan `YouTubeAudioSelection` memilih stream.

Identitas sumber dipertahankan pada lagu. Untuk YouTube, URL media sementara diselesaikan kembali pada jalur pemuatan Media3 melalui `YouTubeDataSource`, termasuk setelah restart dan saat lagu berikutnya dimuat. Playlist menyimpan identitas dan metadata, bukan URL stream yang dapat kedaluwarsa.

## Pemutaran

`PlaybackService` memiliki pemutar Media3 dan MediaSession untuk YouTube/Audius. UI menggunakan MediaController. `PlaybackQueue` mempertahankan urutan efektif antrean; `SleepTimer` menangani batas waktu dan akhir lagu. `TransitionPlayer` menerapkan envelope volume untuk transisi tanpa mengganti pengaturan volume pengguna.

Spotify menggunakan App Remote dan pemutaran berlangsung melalui aplikasi Spotify resmi. Resolver YouTube/Audius tidak menerima identitas lagu Spotify.

## Data lokal

Playlist, lagu disukai, riwayat, dan pilihan sumber disimpan dalam preferensi perangkat. Sesi Spotify disimpan terenkripsi dengan kunci Android Keystore di direktori yang dikecualikan dari backup. Tidak ada sinkronisasi koleksi antarpiranti.

## Pengujian

Tes JVM melindungi logika yang tidak memerlukan Android. Tes instrumentasi memeriksa batas Android, penyimpanan, MediaSession, dan lifecycle pemutar. Perjalanan yang membutuhkan katalog langsung atau nada uji memakai argumen opt-in agar tes biasa tetap terukur dan tidak memutar audio tanpa sengaja.
