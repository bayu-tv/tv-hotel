package com.bayutv.hotel

object SettingsPage {
    const val HTML = """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>Pengaturan BayuTV</title>
<style>
  :root {
    --bg: #0b0a09;
    --card: #161412;
    --card-hover: #1e1b18;
    --border: rgba(184, 151, 90, 0.28);
    --brass: #b8975a;
    --brass-light: #e6c88f;
    --ivory: #f5f0e6;
    --mute: #a8a092;
    --ok: #38a169;
    --danger: #c53030;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--ivory);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    line-height: 1.45;
    padding-bottom: 84px;
  }
  .wrap { max-width: 640px; margin: 0 auto; padding: 16px; }
  .topbar {
    display: flex; align-items: center; justify-content: space-between;
    padding: 12px 16px; background: #12100e;
    border-bottom: 1px solid var(--border);
    position: sticky; top: 0; z-index: 20;
  }
  .brand { font-weight: 800; letter-spacing: 0.1em; color: var(--brass-light); font-size: 15px; }
  .sub-brand { font-size: 11px; color: var(--mute); }

  /* Login PIN */
  .pin-box {
    max-width: 380px; margin: 48px auto; padding: 24px;
    background: var(--card); border: 1px solid var(--border); border-radius: 16px;
    text-align: center;
  }
  .pin-box h2 { margin: 0 0 8px; color: var(--brass-light); font-size: 20px; }
  .pin-box p { margin: 0 0 18px; color: var(--mute); font-size: 13px; }
  .pin-input {
    width: 100%; padding: 14px; font-size: 22px; text-align: center; letter-spacing: 8px;
    background: #0e0d0b; color: var(--ivory); border: 1px solid var(--brass);
    border-radius: 12px; outline: none; margin-bottom: 14px;
  }

  /* Tombol */
  .btn {
    display: inline-flex; align-items: center; justify-content: center; gap: 8px;
    width: 100%; padding: 13px 16px; border-radius: 12px; border: none;
    font-size: 14px; font-weight: 700; cursor: pointer; text-decoration: none;
    transition: transform 0.08s ease, opacity 0.15s ease;
  }
  .btn:active { transform: scale(0.98); }
  .btn-primary { background: linear-gradient(135deg, var(--brass-light), var(--brass)); color: #0b0a09; }
  .btn-secondary { background: #23201c; color: var(--ivory); border: 1px solid var(--border); }
  .btn-danger { background: rgba(197, 48, 48, 0.18); color: #feb2b2; border: 1px solid rgba(197, 48, 48, 0.45); }
  .btn-sm { width: auto; padding: 8px 12px; font-size: 12px; border-radius: 9px; }

  /* Pencarian & Panduan */
  .search-bar {
    width: 100%; padding: 12px 14px; border-radius: 12px;
    background: var(--card); border: 1px solid var(--border);
    color: var(--ivory); font-size: 14px; outline: none; margin-bottom: 14px;
  }
  .search-bar:focus { border-color: var(--brass-light); }

  /* Grid Kotak Kategori */
  .grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }
  @media (max-width: 360px) { .grid { grid-template-columns: 1fr; } }

  .tile {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 16px 14px;
    display: flex; flex-direction: column; justify-content: space-between;
    cursor: pointer; text-align: left; position: relative;
    transition: border-color 0.15s, background 0.15s;
  }
  .tile:active { background: var(--card-hover); border-color: var(--brass-light); }
  .tile-icon {
    width: 38px; height: 38px; border-radius: 10px;
    background: rgba(184, 151, 90, 0.15); border: 1px solid rgba(184, 151, 90, 0.35);
    display: flex; align-items: center; justify-content: center;
    font-size: 19px; margin-bottom: 10px;
  }
  .tile-title { font-size: 14px; font-weight: 700; color: var(--ivory); margin-bottom: 4px; }
  .tile-desc { font-size: 11.5px; color: var(--mute); line-height: 1.35; margin-bottom: 10px; }
  .tile-badge {
    align-self: flex-start; font-size: 10.5px; font-weight: 700;
    padding: 3px 8px; border-radius: 20px;
    background: rgba(230, 200, 143, 0.14); color: var(--brass-light);
  }

  /* Halaman Detail Kategori */
  .section-head {
    display: flex; align-items: center; gap: 10px; margin-bottom: 14px;
  }
  .back-btn {
    background: #221f1b; color: var(--brass-light); border: 1px solid var(--border);
    padding: 8px 12px; border-radius: 10px; font-size: 13px; font-weight: 700; cursor: pointer;
  }
  .section-title { font-size: 18px; font-weight: 800; color: var(--brass-light); margin: 0; }

  .guide-box {
    background: rgba(184, 151, 90, 0.1);
    border-left: 3px solid var(--brass-light);
    border-radius: 10px;
    padding: 12px 14px;
    margin-bottom: 16px;
    font-size: 12.5px;
    color: #e8e0d2;
  }
  .guide-box strong { color: var(--brass-light); display: block; margin-bottom: 4px; }

  .field-card {
    background: var(--card); border: 1px solid var(--border);
    border-radius: 14px; padding: 14px; margin-bottom: 12px;
  }
  .field-label { display: block; font-size: 13.5px; font-weight: 700; color: var(--ivory); margin-bottom: 6px; }
  .field-input, .field-select, .field-textarea {
    width: 100%; padding: 11px 12px; border-radius: 10px;
    background: #0e0d0b; border: 1px solid #332e27;
    color: var(--ivory); font-size: 14px; outline: none;
  }
  .field-input:focus, .field-select:focus, .field-textarea:focus { border-color: var(--brass-light); }
  .field-textarea { min-height: 76px; resize: vertical; }
  .hint { font-size: 11.5px; color: var(--mute); margin-top: 6px; line-height: 1.4; }
  .row-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }

  /* Bilah Simpan Bawah */
  .save-bar {
    position: fixed; bottom: 0; left: 0; right: 0;
    background: rgba(18, 16, 14, 0.96); backdrop-filter: blur(8px);
    border-top: 1px solid var(--border);
    padding: 12px 16px; z-index: 30;
  }
  .save-inner { max-width: 640px; margin: 0 auto; display: flex; gap: 10px; }

  /* Toast Notifikasi */
  .toast {
    position: fixed; top: 64px; left: 50%; transform: translateX(-50%);
    background: #1f1c18; color: var(--brass-light); border: 1px solid var(--brass-light);
    padding: 10px 16px; border-radius: 99px; font-size: 13px; font-weight: 700;
    box-shadow: 0 8px 24px rgba(0,0,0,0.6); z-index: 50; display: none;
    max-width: 90%; text-align: center;
  }
  .hidden { display: none !important; }
</style>
</head>
<body>

<div class="topbar">
  <div>
    <div class="brand">BAYUTV CONTROL</div>
    <div class="sub-brand" id="topSub">Pengaturan &amp; Kontrol TV via HP</div>
  </div>
  <button class="btn btn-secondary btn-sm hidden" id="btnLogout" onclick="logoutPin()">Keluar PIN</button>
</div>

<div class="toast" id="toast"></div>

<!-- LAYAR MASUK PIN -->
<div class="wrap" id="viewPin">
  <div class="pin-box">
    <h2>Masukkan PIN TV</h2>
    <p>Lihat 4 digit PIN yang tampil di layar Pengaturan TV Anda untuk membuka menu.</p>
    <input type="password" inputmode="numeric" maxlength="6" class="pin-input" id="pinInput" placeholder="••••" onkeydown="if(event.key==='Enter')loginPin()">
    <button class="btn btn-primary" onclick="loginPin()">Buka Pengaturan</button>
  </div>
</div>

<!-- LAYAR UTAMA: KOTAK-KOTAK KATEGORI -->
<div class="wrap hidden" id="viewHome">
  <input type="search" class="search-bar" id="searchBox" placeholder="Cari pengaturan (misal: remote, adzan, tmdb, pin)..." oninput="filterTiles(this.value)">

  <div class="guide-box" id="homeGuide">
    <strong>Cara Menggunakan Pengaturan HP</strong>
    Pilih salah satu kotak kategori di bawah sesuai kebutuhan. Setiap bagian sudah dilengkapi petunjuk pengisian. Setelah mengubah pengaturan, tekan tombol <b>Simpan Pengaturan</b> di bagian bawah.
  </div>

  <div class="grid" id="categoryGrid">
    <div class="tile" data-keywords="remote kontrol sentuh hp autostart mulai otomatis tema background latar foto" onclick="openCat('catRemote')">
      <div>
        <div class="tile-icon">📱</div>
        <div class="tile-title">Remote HP &amp; Tampilan</div>
        <div class="tile-desc">Buka remote sentuh HP, atur buka otomatis saat TV nyala, tema &amp; foto latar.</div>
      </div>
      <span class="tile-badge" id="badgeRemote">Remote &amp; Tema</span>
    </div>

    <div class="tile" data-keywords="siaran tv channel m3u playlist negara refresh segarkan" onclick="openCat('catTv')">
      <div>
        <div class="tile-icon">📡</div>
        <div class="tile-title">Siaran TV &amp; Playlist</div>
        <div class="tile-desc">Pilih negara siaran bawaan, pasang link playlist M3U, atau segarkan channel macet.</div>
      </div>
      <span class="tile-badge" id="badgeTv">Siaran Langsung</span>
    </div>

    <div class="tile" data-keywords="film anime drama tmdb api poster jellyfin server koleksi riwayat" onclick="openCat('catFilm')">
      <div>
        <div class="tile-icon">🎬</div>
        <div class="tile-title">Film, Anime &amp; Koleksi</div>
        <div class="tile-desc">Aktifkan poster &amp; sinopsis TMDB, sambungkan server Jellyfin, dan kelola riwayat.</div>
      </div>
      <span class="tile-badge" id="badgeFilm">TMDB &amp; Koleksi</span>
    </div>

    <div class="tile" data-keywords="adzan sholat jadwal lokasi kota koordinat latitude longitude zona waktu pemancar antena" onclick="openCat('catAdzan')">
      <div>
        <div class="tile-icon">🕌</div>
        <div class="tile-title">Adzan, Lokasi &amp; Antena</div>
        <div class="tile-desc">Atur pengingat waktu sholat otomatis, GPS kota, dan arah pemancar antena digital.</div>
      </div>
      <span class="tile-badge" id="badgeAdzan">Jadwal &amp; Lokasi</span>
    </div>

    <div class="tile" data-keywords="anak pin kunci batasan waktu durasi internasional dewasa film" onclick="openCat('catAnak')">
      <div>
        <div class="tile-icon">🔒</div>
        <div class="tile-title">Kontrol Anak &amp; Kunci</div>
        <div class="tile-desc">Pasang PIN khusus anak, kunci menu tertentu, dan batasi lama menonton per hari.</div>
      </div>
      <span class="tile-badge" id="badgeAnak">PIN Anak</span>
    </div>

    <div class="tile" data-keywords="beranda rumah keluarga wifi sandi password aturan catatan informasi" onclick="openCat('catInfo')">
      <div>
        <div class="tile-icon">🏠</div>
        <div class="tile-title">Info Beranda &amp; Wi-Fi</div>
        <div class="tile-desc">Ubah nama keluarga di TV, tampilkan info Wi-Fi tamu, dan catatan pengumuman.</div>
      </div>
      <span class="tile-badge" id="badgeInfo">Profil TV</span>
    </div>
  </div>
</div>

<!-- KATEGORI 1: REMOTE HP & TAMPILAN -->
<div class="wrap hidden cat-page" id="catRemote">
  <div class="section-head">
    <button class="back-btn" onclick="goHome()">← Kotak Menu</button>
    <h2 class="section-title">Remote HP &amp; Tampilan</h2>
  </div>

  <div class="guide-box">
    <strong>Di Mana Pengaturan &amp; Fitur Remote HP?</strong>
    1. Fitur <b>Remote Sentuh HP</b> otomatis aktif saat layar TV sedang membuka <b>TV Live</b> (berjalan di port <b>8091</b>).<br>
    2. Saat layar Pengaturan ini terbuka di TV, server berjalan di port <b>8080</b>.<br>
    3. Buka <b>TV Live</b> di TV terlebih dahulu, lalu tekan tombol di bawah untuk langsung mengendalikan channel &amp; volume dari HP:
    <div style="margin-top:10px">
      <button type="button" class="btn btn-primary" onclick="openRemotePage()">Buka Remote HP Sekarang (Port 8091)</button>
    </div>
  </div>

  <div class="field-card">
    <label class="field-label" for="autostart">Mulai Otomatis Saat TV Dinyalakan</label>
    <select class="field-select" id="autostart">
      <option value="Mati">Mati (Ikuti layar utama Android TV)</option>
      <option value="Beranda">Buka Beranda BayuTV</option>
      <option value="TV Live">Langsung Putar TV Live</option>
    </select>
    <div class="hint">Pilih <b>Langsung Putar TV Live</b> jika TV ingin langsung menayangkan siaran begitu dinyalakan tanpa perlu menekan tombol apa pun.</div>
  </div>

  <div class="field-card">
    <label class="field-label" for="theme">Tema Nuansa Beranda</label>
    <select class="field-select" id="theme">
      <option value="auto">Otomatis (Menyesuaikan momen/kalender)</option>
      <option value="default">Klasik Elegan (Emas &amp; Gelap)</option>
      <option value="ramadan">Nuansa Ramadan</option>
      <option value="newyear">Nuansa Tahun Baru</option>
    </select>
    <div class="hint">Mengubah aksen warna tampilan layar utama BayuTV.</div>
  </div>

  <div class="field-card">
    <label class="field-label" for="bg">Latar Belakang Beranda</label>
    <select class="field-select" id="bg">
      <option value="none">Bawaan Gelap</option>
      <option value="plain">Hitam Polos Minimalis</option>
      <option value="senja">Gradasi Senja Hangat</option>
      <option value="photo">Foto Keluarga / Kustom (Unggah di bawah)</option>
    </select>
    <div class="hint">Jika memilih <b>Foto Keluarga / Kustom</b>, unggah fotonya melalui tombol di bawah ini.</div>

    <div style="margin-top:12px; padding-top:12px; border-top:1px solid #2a251f;">
      <label class="field-label" for="bgFile">Unggah Foto Latar Belakang TV</label>
      <input type="file" id="bgFile" accept="image/*" class="field-input" style="padding:8px">
      <div style="margin-top:8px">
        <button type="button" class="btn btn-secondary" onclick="uploadBg()">Kirim Foto ke TV</button>
      </div>
      <div class="hint">Foto dari galeri HP akan otomatis disesuaikan ukurannya dan dikirim ke TV sebagai latar beranda.</div>
    </div>
  </div>
</div>

<!-- KATEGORI 2: SIARAN TV & PLAYLIST -->
<div class="wrap hidden cat-page" id="catTv">
  <div class="section-head">
    <button class="back-btn" onclick="goHome()">← Kotak Menu</button>
    <h2 class="section-title">Siaran TV &amp; Playlist</h2>
  </div>

  <div class="guide-box">
    <strong>Cara Mengatur Siaran TV</strong>
    Secara bawaan BayuTV memuat daftar siaran TV sesuai kode negara. Anda juga bisa menambahkan tautan playlist <b>.m3u</b> sendiri. Jika ada channel yang gagal diputar, tekan tombol <b>Segarkan Daftar Channel</b>.
  </div>

  <div class="field-card">
    <label class="field-label" for="country">Kode Negara Siaran Bawaan</label>
    <input type="text" maxlength="2" class="field-input" id="country" placeholder="id">
    <div class="hint">Isi 2 huruf kode negara, misalnya <b>id</b> untuk Indonesia, <b>my</b> untuk Malaysia, atau <b>sg</b> untuk Singapura.</div>
  </div>

  <div class="field-card">
    <label class="field-label" for="playlist">Link Playlist Tambahan (M3U / M3U8)</label>
    <input type="url" class="field-input" id="playlist" placeholder="https://contoh.com/daftar-tv.m3u">
    <div class="hint">Harus diawali <b>http://</b> atau <b>https://</b>. Kosongkan kolom ini jika hanya ingin memakai siaran bawaan.</div>
  </div>

  <div class="field-card">
    <label class="field-label">Perawatan Daftar Channel</label>
    <button type="button" class="btn btn-secondary" onclick="refreshChannels()">Segarkan &amp; Reset Channel Macet</button>
    <div class="hint">Menghapus catatan channel yang sempat error dan memaksa TV mengunduh ulang daftar siaran terbaru.</div>
  </div>
</div>

<!-- KATEGORI 3: FILM, ANIME & KOLEKSI -->
<div class="wrap hidden cat-page" id="catFilm">
  <div class="section-head">
    <button class="back-btn" onclick="goHome()">← Kotak Menu</button>
    <h2 class="section-title">Film, Anime &amp; Koleksi</h2>
  </div>

  <div class="guide-box">
    <strong>Cara Mengaktifkan Poster Film &amp; Anime</strong>
    1. Daftar gratis di <b>themoviedb.org</b> &rarr; buka Pengaturan Akun &rarr; <b>API</b> &rarr; salin <b>API Key (v3 auth)</b> ke kolom TMDB di bawah.<br>
    2. Jika Anda menyimpan koleksi film di laptop/PC memakai aplikasi <b>Jellyfin</b>, isi alamat servernya agar muncul di tab <i>Koleksi Saya</i>.
  </div>

  <div class="field-card">
    <label class="field-label" for="tmdb">Kunci API TMDB (Wajib untuk Poster &amp; Sinopsis)</label>
    <input type="text" class="field-input" id="tmdb" placeholder="Tempel API Key TMDB di sini">
    <div class="hint">Tanpa kunci ini, menu Film hanya menampilkan katalog YouTube Resmi. Setelah diisi dan disimpan, seluruh katalog film &amp; anime akan tampil lengkap.</div>
  </div>

  <div class="field-card">
    <label class="field-label" for="jf_url">Alamat Server Jellyfin (Opsional)</label>
    <input type="url" class="field-input" id="jf_url" placeholder="http://192.168.1.10:8096">
    <div class="row-2" style="margin-top:10px">
      <div>
        <label class="field-label" for="jf_user">Nama Pengguna</label>
        <input type="text" class="field-input" id="jf_user" placeholder="admin">
      </div>
      <div>
        <label class="field-label" for="jf_pass">Kata Sandi</label>
        <input type="password" class="field-input" id="jf_pass" placeholder="(Biarkan kosong jika tetap)">
      </div>
    </div>
    <div class="hint">Pastikan PC server Jellyfin terhubung di jaringan Wi-Fi yang sama dengan TV.</div>
  </div>

  <div class="field-card">
    <label class="field-label">Riwayat Tontonan</label>
    <button type="button" class="btn btn-danger" onclick="clearHistory()">Hapus Semua Riwayat Lanjutkan Menonton</button>
    <div class="hint">Membersihkan seluruh daftar film/serial di baris <i>Lanjutkan Menonton</i> pada layar TV.</div>
  </div>
</div>

<!-- KATEGORI 4: ADZAN, LOKASI & ANTENA -->
<div class="wrap hidden cat-page" id="catAdzan">
  <div class="section-head">
    <button class="back-btn" onclick="goHome()">← Kotak Menu</button>
    <h2 class="section-title">Adzan, Lokasi &amp; Antena</h2>
  </div>

  <div class="guide-box">
    <strong>Cara Mengatur Jadwal Adzan &amp; Arah Antena</strong>
    Tekan tombol <b>Gunakan Lokasi GPS HP Ini</b> agar koordinat Latitude, Longitude, dan Zona Waktu terisi otomatis secara akurat tanpa perlu mengetik manual.
  </div>

  <div class="field-card">
    <button type="button" class="btn btn-secondary" onclick="detectGps()">📍 Gunakan Lokasi GPS HP Ini</button>
    <div class="field-label" for="city" style="margin-top:12px">Nama Kota / Kabupaten</div>
    <input type="text" class="field-input" id="city" placeholder="Contoh: Tanah Grogot">
    <div class="row-2" style="margin-top:10px">
      <div>
        <label class="field-label" for="lat">Latitude (Lintang)</label>
        <input type="text" class="field-input" id="lat" placeholder="-1.9025">
      </div>
      <div>
        <label class="field-label" for="lon">Longitude (Bujur)</label>
        <input type="text" class="field-input" id="lon" placeholder="116.1950">
      </div>
    </div>
    <div style="margin-top:10px">
      <label class="field-label" for="tz">Zona Waktu (Timezone)</label>
      <select class="field-select" id="tz">
        <option value="Asia/Jakarta">WIB — Asia/Jakarta</option>
        <option value="Asia/Makassar">WITA — Asia/Makassar</option>
        <option value="Asia/Jayapura">WIT — Asia/Jayapura</option>
      </select>
    </div>
  </div>

  <div class="field-card">
    <label class="field-label" for="adzan">Pengingat Masuk Waktu Sholat / Adzan</label>
    <select class="field-select" id="adzan">
      <option value="Ya">Aktif (Tampilkan pengingat di layar TV)</option>
      <option value="Tidak">Nonaktif</option>
    </select>
    <div class="row-2" style="margin-top:10px">
      <div>
        <label class="field-label" for="adzan_offset">Koreksi Menit (-15 s/d +15)</label>
        <input type="number" min="-15" max="15" class="field-input" id="adzan_offset" placeholder="0">
      </div>
      <div>
        <label class="field-label" for="adzan_min">Lama Tampil (Menit)</label>
        <input type="number" min="1" max="30" class="field-input" id="adzan_min" placeholder="4">
      </div>
    </div>
    <div style="margin-top:10px">
      <label class="field-label" for="adzan_url">Link Audio Adzan Kustom (Opsional)</label>
      <input type="url" class="field-input" id="adzan_url" placeholder="https://contoh.com/adzan.mp3">
    </div>
    <div class="hint">Koreksi menit berguna jika jadwal setempat selisih 1–2 menit dari perhitungan standar.</div>
  </div>

  <div class="field-card">
    <label class="field-label" for="tower_name">Kompas Arah Pemancar TV Digital (Opsional)</label>
    <input type="text" class="field-input" id="tower_name" placeholder="Nama Pemancar MUX Terdekat">
    <div class="row-2" style="margin-top:10px">
      <div>
        <label class="field-label" for="tower_lat">Latitude Pemancar</label>
        <input type="text" class="field-input" id="tower_lat" placeholder="-1.2345">
      </div>
      <div>
        <label class="field-label" for="tower_lon">Longitude Pemancar</label>
        <input type="text" class="field-input" id="tower_lon" placeholder="116.8765">
      </div>
    </div>
    <div class="hint">Jika diisi, TV dapat membantu menunjukkan arah derajat kompas untuk memutar antena digital UHF.</div>
  </div>
</div>

<!-- KATEGORI 5: KONTROL ANAK & KUNCI -->
<div class="wrap hidden cat-page" id="catAnak">
  <div class="section-head">
    <button class="back-btn" onclick="goHome()">← Kotak Menu</button>
    <h2 class="section-title">Kontrol Anak &amp; Kunci</h2>
  </div>

  <div class="guide-box">
    <strong>Cara Mengamankan Tontonan Anak</strong>
    Atur <b>PIN Anak</b> (4–6 angka) terlebih dahulu, lalu pilih kategori siaran atau menu film yang ingin dikunci. Saat menu yang dikunci dibuka di TV, aplikasi akan meminta PIN Anak tersebut.
  </div>

  <div class="field-card">
    <label class="field-label" for="pin_anak">PIN Anak (4–6 Angka) <span id="pinAnakStatus" style="color:var(--brass-light); font-size:12px;"></span></label>
    <input type="password" inputmode="numeric" maxlength="6" class="field-input" id="pin_anak" placeholder="Isi angka baru atau biarkan kosong">
    <div style="margin-top:8px">
      <button type="button" class="btn btn-danger btn-sm" onclick="removePinAnak()">Hapus PIN Anak</button>
    </div>
    <div class="hint">Biarkan kosong jika tidak ingin mengganti PIN Anak yang sudah tersimpan.</div>
  </div>

  <div class="field-card">
    <label class="field-label" for="lock_intl">Kunci Siaran Internasional</label>
    <select class="field-select" id="lock_intl">
      <option value="Tidak">Tidak Dikunci</option>
      <option value="Ya">Kunci dengan PIN Anak</option>
    </select>

    <label class="field-label" for="lock_custom" style="margin-top:10px">Kunci Playlist Tambahan (M3U)</label>
    <select class="field-select" id="lock_custom">
      <option value="Tidak">Tidak Dikunci</option>
      <option value="Ya">Kunci dengan PIN Anak</option>
    </select>

    <label class="field-label" for="lock_film" style="margin-top:10px">Kunci Menu Film &amp; Serial</label>
    <select class="field-select" id="lock_film">
      <option value="Tidak">Tidak Dikunci</option>
      <option value="Ya">Kunci dengan PIN Anak</option>
    </select>
  </div>

  <div class="field-card">
    <label class="field-label" for="limit_min">Batas Durasi Menonton per Hari (Menit)</label>
    <input type="number" min="0" max="1440" class="field-input" id="limit_min" placeholder="0 (Tanpa batas)">
    <div class="hint">Contoh: isi <b>120</b> untuk membatasi pemakaian TV maksimal 2 jam per hari. Isi <b>0</b> untuk menonaktifkan batas waktu.</div>
  </div>
</div>

<!-- KATEGORI 6: INFO BERANDA & WI-FI -->
<div class="wrap hidden cat-page" id="catInfo">
  <div class="section-head">
    <button class="back-btn" onclick="goHome()">← Kotak Menu</button>
    <h2 class="section-title">Info Beranda &amp; Wi-Fi</h2>
  </div>

  <div class="guide-box">
    <strong>Personalisasi Layar Utama TV</strong>
    Informasi di bawah ini ditampilkan pada beranda TV agar anggota keluarga atau tamu mudah melihat nama jaringan Wi-Fi dan catatan rumah.
  </div>

  <div class="field-card">
    <label class="field-label" for="family">Nama Keluarga / Judul Beranda</label>
    <input type="text" class="field-input" id="family" placeholder="Contoh: Keluarga Bayu">
    <div class="hint">Tampil sebagai sapaan utama di layar beranda TV.</div>
  </div>

  <div class="field-card">
    <div class="row-2">
      <div>
        <label class="field-label" for="wifi">Nama Wi-Fi (SSID)</label>
        <input type="text" class="field-input" id="wifi" placeholder="WiFi Rumah">
      </div>
      <div>
        <label class="field-label" for="wifipass">Sandi Wi-Fi</label>
        <input type="text" class="field-input" id="wifipass" placeholder="sandiwifi123">
      </div>
    </div>
    <div class="hint">Memudahkan keluarga atau tamu menyambungkan HP ke Wi-Fi tanpa perlu bertanya berulang kali.</div>
  </div>

  <div class="field-card">
    <label class="field-label" for="rules">Aturan / Pesan Singkat</label>
    <textarea class="field-textarea" id="rules" placeholder="Contoh: Matikan TV sebelum tidur."></textarea>

    <label class="field-label" for="note" style="margin-top:10px">Catatan Tambahan</label>
    <textarea class="field-textarea" id="note" placeholder="Catatan jadwal atau pengumuman keluarga..."></textarea>
  </div>
</div>

<!-- BILAH SIMPAN PERMANEN -->
<div class="save-bar hidden" id="saveBar">
  <div class="save-inner">
    <button type="button" class="btn btn-primary" onclick="saveAll()">Simpan Pengaturan</button>
  </div>
</div>

<script>
  var currentPin = localStorage.getItem('bayutv_pin') || '';
  var allKeys = [
    'family', 'city', 'lat', 'lon', 'tz', 'wifi', 'wifipass', 'rules', 'note', 'country', 'playlist',
    'bg', 'theme', 'tmdb', 'jf_url', 'jf_user', 'jf_pass', 'tower_name', 'tower_lat', 'tower_lon',
    'adzan', 'adzan_offset', 'adzan_url', 'adzan_min', 'pin_anak', 'lock_intl', 'lock_custom', 'lock_film',
    'limit_min', 'autostart'
  ];

  function el(id) { return document.getElementById(id); }

  function toast(msg) {
    var t = el('toast');
    t.textContent = msg;
    t.style.display = 'block';
    clearTimeout(window._toastTimer);
    window._toastTimer = setTimeout(function() { t.style.display = 'none'; }, 3200);
  }

  function loginPin() {
    var val = el('pinInput').value.trim();
    if (!val) { toast('Masukkan PIN dari layar TV'); return; }
    currentPin = val;
    loadData(true);
  }

  function logoutPin() {
    currentPin = '';
    localStorage.removeItem('bayutv_pin');
    el('pinInput').value = '';
    el('viewHome').classList.add('hidden');
    el('saveBar').classList.add('hidden');
    el('btnLogout').classList.add('hidden');
    var pages = document.querySelectorAll('.cat-page');
    for (var i = 0; i < pages.length; i++) pages[i].classList.add('hidden');
    el('viewPin').classList.remove('hidden');
  }

  function loadData(fromLogin) {
    fetch('/api/get', { headers: { 'X-Pin': currentPin } })
      .then(function(r) {
        if (r.status === 403) throw new Error('PIN salah');
        if (r.status === 429) throw new Error('Terlalu banyak percobaan PIN. Tutup & buka ulang Pengaturan di TV.');
        if (!r.ok) throw new Error('Gagal terhubung ke TV');
        return r.json();
      })
      .then(function(data) {
        localStorage.setItem('bayutv_pin', currentPin);
        for (var i = 0; i < allKeys.length; i++) {
          var k = allKeys[i];
          if (el(k) && data[k] !== undefined) {
            el(k).value = data[k];
          }
        }
        el('pinAnakStatus').textContent = (data.pin_anak_set === 'Ya') ? '(Sudah Aktif)' : '(Belum Diatur)';
        updateBadges(data);
        el('viewPin').classList.add('hidden');
        el('btnLogout').classList.remove('hidden');
        goHome();
        if (fromLogin) toast('Terhubung ke BayuTV');
      })
      .catch(function(err) {
        if (fromLogin) toast(err.message);
        else logoutPin();
      });
  }

  function updateBadges(d) {
    el('badgeRemote').textContent = 'Mulai: ' + (d.autostart || 'Mati');
    el('badgeTv').textContent = 'Negara: ' + (d.country || 'id').toUpperCase();
    el('badgeFilm').textContent = d.tmdb ? 'TMDB Aktif' : 'TMDB Belum Diisi';
    el('badgeAdzan').textContent = (d.adzan === 'Ya') ? ('Adzan: ' + (d.city || 'Aktif')) : 'Adzan Nonaktif';
    el('badgeAnak').textContent = (d.pin_anak_set === 'Ya') ? 'PIN Aktif' : 'Belum Dikunci';
    el('badgeInfo').textContent = d.family || 'Profil TV';
  }

  function goHome() {
    var pages = document.querySelectorAll('.cat-page');
    for (var i = 0; i < pages.length; i++) pages[i].classList.add('hidden');
    el('viewHome').classList.remove('hidden');
    el('saveBar').classList.remove('hidden');
    el('topSub').textContent = 'Pilih kategori pengaturan di bawah';
    window.scrollTo(0, 0);
  }

  function openCat(id) {
    el('viewHome').classList.add('hidden');
    var pages = document.querySelectorAll('.cat-page');
    for (var i = 0; i < pages.length; i++) pages[i].classList.add('hidden');
    el(id).classList.remove('hidden');
    el('saveBar').classList.remove('hidden');
    el('topSub').textContent = 'Tekan Simpan Pengaturan setelah selesai';
    window.scrollTo(0, 0);
  }

  function filterTiles(q) {
    var query = (q || '').toLowerCase().trim();
    var tiles = document.querySelectorAll('.tile');
    for (var i = 0; i < tiles.length; i++) {
      var kw = (tiles[i].getAttribute('data-keywords') || '') + ' ' + tiles[i].innerText.toLowerCase();
      tiles[i].style.display = (!query || kw.indexOf(query) !== -1) ? 'flex' : 'none';
    }
  }

  function saveAll() {
    var payload = {};
    for (var i = 0; i < allKeys.length; i++) {
      var k = allKeys[i];
      if (el(k)) payload[k] = String(el(k).value || '');
    }
    fetch('/api/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Pin': currentPin },
      body: JSON.stringify(payload)
    })
    .then(function(r) {
      if (!r.ok) throw new Error('Gagal menyimpan pengaturan');
      return r.text();
    })
    .then(function() {
      toast('Pengaturan berhasil disimpan ke TV');
      loadData(false);
    })
    .catch(function(e) { toast(e.message); });
  }

  function removePinAnak() {
    if (!confirm('Hapus PIN Anak dan buka semua kunci?')) return;
    fetch('/api/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Pin': currentPin },
      body: JSON.stringify({ pin_anak: 'hapus', lock_intl: 'Tidak', lock_custom: 'Tidak', lock_film: 'Tidak' })
    })
    .then(function(r) { if (r.ok) { toast('PIN Anak telah dihapus'); loadData(false); } });
  }

  function refreshChannels() {
    fetch('/api/refresh', { method: 'POST', headers: { 'X-Pin': currentPin } })
      .then(function(r) {
        if (!r.ok) throw new Error('Gagal menyegarkan channel');
        toast('Daftar channel sedang dimuat ulang di TV');
      })
      .catch(function(e) { toast(e.message); });
  }

  function clearHistory() {
    if (!confirm('Hapus seluruh riwayat Lanjutkan Menonton di TV?')) return;
    fetch('/api/clear-history', { method: 'POST', headers: { 'X-Pin': currentPin } })
      .then(function(r) {
        if (!r.ok) throw new Error('Gagal menghapus riwayat');
        toast('Semua riwayat tontonan telah dihapus');
      })
      .catch(function(e) { toast(e.message); });
  }

  function detectGps() {
    if (!navigator.geolocation) { toast('Browser HP tidak mendukung GPS'); return; }
    toast('Mengambil koordinat GPS HP...');
    navigator.geolocation.getCurrentPosition(function(pos) {
      el('lat').value = pos.coords.latitude.toFixed(5);
      el('lon').value = pos.coords.longitude.toFixed(5);
      try {
        var z = Intl.DateTimeFormat().resolvedOptions().timeZone;
        if (z) el('tz').value = z;
      } catch (e) {}
      toast('Koordinat GPS terisi! Jangan lupa tekan Simpan Pengaturan.');
    }, function() {
      toast('Izin lokasi ditolak. Aktifkan GPS di HP lalu coba lagi.');
    });
  }

  function openRemotePage() {
    var remoteUrl = 'http://' + window.location.hostname + ':8091';
    window.location.href = remoteUrl;
  }

  function uploadBg() {
    var input = el('bgFile');
    if (!input.files || !input.files[0]) { toast('Pilih file foto dari galeri HP terlebih dahulu'); return; }
    var file = input.files[0];
    var reader = new FileReader();
    reader.onload = function(ev) {
      var img = new Image();
      img.onload = function() {
        var maxW = 1920, maxH = 1080;
        var w = img.width, h = img.height;
        if (w > maxW || h > maxH) {
          var ratio = Math.min(maxW / w, maxH / h);
          w = Math.round(w * ratio);
          h = Math.round(h * ratio);
        }
        var canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        canvas.getContext('2d').drawImage(img, 0, 0, w, h);
        canvas.toBlob(function(blob) {
          fetch('/api/bg', {
            method: 'POST',
            headers: { 'X-Pin': currentPin },
            body: blob
          })
          .then(function(r) {
            if (!r.ok) throw new Error('Gagal mengunggah foto ke TV');
            el('bg').value = 'photo';
            toast('Foto berhasil dipasang sebagai latar beranda TV!');
          })
          .catch(function(err) { toast(err.message); });
        }, 'image/jpeg', 0.85);
      };
      img.src = ev.target.result;
    };
    reader.readAsDataURL(file);
  }

  if (currentPin) {
    loadData(false);
  }
</script>
</body>
</html>"""
}
