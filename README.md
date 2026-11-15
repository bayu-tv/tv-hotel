# Bayu TV

Aplikasi Android TV keluarga: TV Live (internet + antena dalam satu daftar), Film, Panduan Rumah, jadwal sholat, dan cuaca.

## Cara kerja pembaruan

| Workflow | Kapan jalan | Hasil |
|---|---|---|
| `build.yml` (Build APK) | setiap push ke `main` | Release `vN` berisi APK. TV menawarkan pembaruan saat Beranda dibuka. |
| `channels.yml` (Perbarui daftar channel) | setiap 6 jam, saat `tools/` berubah, atau manual | Branch `data` berisi `channels.m3u` yang sudah diperiksa + `README.md` laporan. TV mengunduhnya sendiri, **tanpa** perlu APK baru. |

### Sekali saja setelah push pertama

1. Repo harus **public** (TV mengunduh rilis dan daftar channel tanpa login).
2. Settings, Actions, General, Workflow permissions: pilih **Read and write permissions**.
3. Tab Actions, pilih **Perbarui daftar channel**, lalu **Run workflow**. Tunggu sampai branch `data` muncul.
4. Secrets tanda tangan APK (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) tetap sama seperti sebelumnya. **Jangan ganti kunci**, karena TV menolak pembaruan yang ditandatangani kunci berbeda.

## Daftar channel

- Sumber: [iptv-org](https://github.com/iptv-org/iptv) (Indonesia + negara pilihan di `tools/sources.json`).
- Setiap alamat diperiksa dari server GitHub. Yang mati dibuang. Yang ditolak server luar negeri (sering karena khusus IP Indonesia) tetap disimpan dengan tanda "belum dicek" lalu diperiksa ulang oleh TV.
- Kategori: Nasional, Berita, Daerah, Religi, Anak & Kartun, Olahraga, Film & Serial, Musik, Dokumenter, dan lainnya, untuk Indonesia dan Internasional.
- Tambah channel sendiri di `tools/custom.m3u` atau alamat playlist di `extra_playlists` (`tools/sources.json`).

Di TV, channel yang gagal diputar disembunyikan otomatis. **Pindai Sinyal** mengunduh alamat terbaru, memeriksa ulang, dan memunculkan lagi channel yang pulih.

## TV antena (hybrid)

TV Live membaca channel antena DVB-T2 yang sudah dipindai oleh pengaturan TV (Android TV Input Framework) dan memutarnya lewat `TvView`, sedangkan channel internet diputar lewat ExoPlayer. Keduanya ada dalam satu daftar (kategori **Antena · TV Digital**).

Beberapa TV (termasuk sebagian Xiaomi) tidak mengizinkan aplikasi pihak ketiga membaca daftar channel antena. Bila begitu, kategori Antena berisi pintasan **Buka aplikasi TV Antena**. Cek kemampuan TV Anda lewat Beranda, TV Antena, **Uji tuner antena**.

### Satu channel, beberapa sumber

Channel dengan nama sama digabung menjadi satu entri dan dicoba berurutan: **antena → alamat internet → aplikasi resmi**.
RCTI, MNCTV, GTV, iNews (RCTI+) serta SCTV dan Indosiar (Vidio) selalu ada di daftar. Bila antena/internet tidak tersedia,
memilih channel itu membuka aplikasi resminya (langsung ke siaran bila aplikasinya mendukung). Saat berpindah dengan atas/bawah,
aplikasi tidak langsung dibuka: tekan **OK** untuk membukanya. Tekan **Back** di aplikasi tersebut untuk kembali; daftar channel langsung terbuka.
Cek dukungan di TV lewat Beranda, TV Antena, **Uji pintasan RCTI+ / Vidio**.

## Film

- Katalog TMDB: Populer, Sedang tayang, Film Indonesia, Anime, Akan tayang, Daftar saya (isi kunci TMDB gratis di Pengaturan lewat HP).
- **Koleksi Saya**: memutar film/anime milik Anda dari server [Jellyfin](https://jellyfin.org) (gratis) di PC/laptop/NAS, tanpa iklan. Subtitle Indonesia dipilih otomatis bila ada (contoh nama file: `Judul.id.srt` di samping videonya). Posisi tonton dilanjutkan otomatis.
- Dari detail film katalog, pilih **Pilihan, Putar dari Koleksi Saya** untuk mencari judul itu di Jellyfin.
