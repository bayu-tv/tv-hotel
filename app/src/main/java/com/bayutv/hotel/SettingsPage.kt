package com.bayutv.hotel

// Halaman web yang dibuka di HP. Tidak memakai tanda dolar agar aman di Kotlin.
object SettingsPage {
    val HTML = """<!doctype html>
<html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pengaturan Bayu TV</title>
<style>
body{margin:0 auto;background:#121110;color:#efe8da;font-family:Georgia,serif;padding:20px 20px 60px;max-width:520px}
h1{font-weight:400;color:#b8975a;font-size:25px;margin:6px 0 4px}
h2{font-weight:400;color:#b8975a;font-size:18px;margin:26px 0 0;border-top:1px solid #34302a;padding-top:16px}
label{display:block;margin:14px 0 4px;color:#a79f90;font-size:13px;font-family:system-ui,sans-serif}
input,select,textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #34302a;background:#1a1816;color:#efe8da;font-size:16px;font-family:inherit}
button{margin-top:14px;padding:12px 22px;border-radius:99px;border:1px solid #b8975a;background:none;color:#b8975a;font-size:16px;font-family:system-ui,sans-serif;cursor:pointer}
.r{padding:11px 12px;border:1px solid #34302a;border-radius:10px;margin-top:6px;font-family:system-ui,sans-serif;background:#1a1816;cursor:pointer}
.r:active{border-color:#e6c88f}
.s{color:#a79f90;font-size:13px;font-family:system-ui,sans-serif;margin-top:6px}
#msg{margin-top:16px;color:#7fb08a;font-family:system-ui,sans-serif}
</style></head><body>
<h1>Pengaturan Bayu TV</h1>
<div id="lock">
<label>Masukkan PIN yang tampil di layar TV</label>
<input id="pin" inputmode="numeric" maxlength="4" placeholder="4 angka">
<button onclick="login()">Buka</button>
<div id="err" style="color:#c9706a;margin-top:12px;font-family:system-ui,sans-serif"></div>
</div>
<div id="main" style="display:none">
<h2>Keluarga</h2>
<label>Nama keluarga di sapaan Beranda</label><input id="family">
<h2>Lokasi (cuaca dan jadwal sholat)</h2>
<label>Desa / Kelurahan / Kota terpilih</label><input id="city" placeholder="bisa diedit nama tampilannya">
<label>Cari desa, kelurahan, kecamatan, atau kota</label><input id="q" placeholder="mis. Tanah Grogot, Senaken, Tepian Batang">
<button type="button" onclick="findCity()">Cari Lokasi</button>
<div id="res"></div>
<label>Garis lintang (bisa disesuaikan manual)</label><input id="lat" inputmode="decimal">
<label>Garis bujur</label><input id="lon" inputmode="decimal">
<input id="tz" type="hidden">
<div class="s">Mendukung pencarian hingga tingkat desa/kelurahan di seluruh Indonesia (hasil bandara otomatis dilewati).</div>
<h2>Panduan Rumah</h2>
<label>Nama Wi-Fi</label><input id="wifi">
<label>Sandi Wi-Fi</label><input id="wifipass">
<label>Aturan rumah</label><textarea id="rules" rows="3"></textarea>
<label>Catatan</label><textarea id="note" rows="3"></textarea>
<h2>Waktu Sholat di TV Live</h2>
<label>Tampilkan pemberitahuan dan kecilkan volume saat masuk waktu sholat</label>
<select id="adzan"><option>Ya</option><option>Tidak</option></select>
<label>Koreksi waktu sholat (menit, mis. 2 atau -1, 0 = standar)</label><input id="adzan_offset" inputmode="numeric" placeholder="0">
<label>Lama volume dikecilkan (menit)</label><input id="adzan_min" inputmode="numeric" placeholder="4">
<label>Alamat file suara adzan (opsional, .mp3)</label><input id="adzan_url" placeholder="https://.../adzan.mp3">
<div class="s">Bila diisi, suara adzan diputar lalu siaran kembali normal setelah selesai. Tekan OK di remote untuk menghentikan lebih cepat.</div>
<h2>Kunci Anak</h2>
<label>PIN kunci anak (4-6 angka). Ketik <b>hapus</b> untuk mematikan kunci.</label>
<input id="pin_anak" inputmode="numeric" maxlength="6" placeholder="kosongkan bila tidak diubah" autocomplete="off">
<div id="pinset" class="s"></div>
<label>Kunci channel Internasional</label><select id="lock_intl"><option>Tidak</option><option>Ya</option></select>
<label>Kunci Playlist Saya</label><select id="lock_custom"><option>Tidak</option><option>Ya</option></select>
<label>Kunci menu Film</label><select id="lock_film"><option>Tidak</option><option>Ya</option></select>
<label>Batas menonton TV Live per hari (menit, 0 = tanpa batas)</label><input id="limit_min" inputmode="numeric" placeholder="0">
<div class="s">Saat batas tercapai, TV Live berhenti sampai PIN dimasukkan (berlaku sampai besok).</div>
<h2>Saat TV Menyala</h2>
<label>Buka Bayu TV otomatis</label>
<select id="autostart"><option>Mati</option><option>Beranda</option><option>TV Live</option></select>
<div class="s">Berlaku saat TV dinyalakan dari kondisi mati total. Di TV, izinkan "Tampilkan di atas aplikasi lain" bila diminta.</div>
<h2>Arah Antena</h2>
<div class="s">Cari tower di <b>Google Maps</b>: ketik "pemancar TVRI" atau "tower TV" di sekitar kota Anda, lalu <b>tekan lama</b> titik tower sampai koordinatnya muncul di kotak pencarian, salin. Bisa tempel langsung format "lintang, bujur" (contoh bentuknya: -0.12345, 117.12345) ke kolom Lintang.</div>
<label>Nama tower (opsional)</label><input id="tower_name" placeholder="mis. Tower Trans / TVRI">
<label>Lintang tower (latitude)</label><input id="tower_lat" placeholder="contoh: -0.12345" oninput="splitCoord()">
<label>Bujur tower (longitude)</label><input id="tower_lon" placeholder="contoh: 117.12345">
<button type="button" onclick="openCompass()">Buka kompas di HP</button>
<div class="s">Kompas memakai sensor HP. Simpan dulu ke TV agar layar "Arah &amp; Sinyal Antena" di TV ikut menampilkan arahnya.</div>
<div id="cmsg" class="s"></div>
<h2>Tema</h2>
<label>Tema tampilan</label>
<select id="theme"><option value="auto">Otomatis (Ramadan dan Tahun Baru)</option><option value="default">Standar</option><option value="ramadan">Ramadan</option><option value="newyear">Tahun Baru</option></select>
<div class="s">Otomatis mengikuti kalender Hijriah di TV, yang bisa berbeda satu hari dari ketetapan pemerintah. Pilih manual bila perlu.</div>
<h2>Latar Beranda</h2>
<label>Pilihan latar</label>
<select id="bg"><option value="none">Cahaya lembut bergerak (bawaan)</option><option value="plain">Polos</option><option value="senja">Senja</option><option value="photo">Foto saya</option></select>
<label>Pilih foto dari HP (otomatis dikecilkan)</label>
<input id="file" type="file" accept="image/*" onchange="photo()">
<h2>Film (TMDB)</h2>
<label>Kunci API TMDB (gratis)</label><input id="tmdb" placeholder="tempel kunci di sini">
<div class="s">Daftar gratis di themoviedb.org, lalu buka Pengaturan, API, dan salin kunci (API Key) atau Read Access Token. Dipakai untuk katalog film, sinopsis Indonesia, trailer, dan daftar layanan resmi.</div>
<h2>Koleksi Saya (Jellyfin)</h2>
<label>Alamat server Jellyfin</label><input id="jf_url" placeholder="http://192.168.1.10:8096">
<label>Nama pengguna</label><input id="jf_user" autocomplete="off">
<label>Sandi (kosongkan bila tidak diubah)</label><input id="jf_pass" type="password" autocomplete="off">
<div class="s">Jellyfin gratis dipasang di PC/laptop/NAS. Film dan anime milik Anda diputar tanpa iklan; letakkan subtitle Indonesia (.srt, mis. Judul.id.srt) di samping file video.</div>
<h2>Sumber channel</h2>
<div class="s">Daftar utama diperbarui otomatis oleh GitHub Actions setiap 6 jam (Indonesia + internasional, sudah diperiksa).</div>
<label>Kode negara cadangan bila daftar utama belum siap (2 huruf, mis. id)</label><input id="country" maxlength="2">
<label>Playlist M3U tambahan (opsional, tampil sebagai "Playlist Saya")</label><input id="playlist" placeholder="https://...">
<button type="button" onclick="refreshList()">Perbarui Daftar Channel Sekarang</button>
<div id="rmsg" class="s"></div>
<br>
<button onclick="save()">Simpan ke TV</button>
<div id="msg"></div>
</div>
<script>
var PIN='';
var F=['family','city','lat','lon','tz','wifi','wifipass','rules','note','country','playlist','bg','theme','tmdb','jf_url','jf_user','jf_pass','tower_name','tower_lat','tower_lon','adzan','adzan_offset','adzan_url','adzan_min','pin_anak','lock_intl','lock_custom','lock_film','limit_min','autostart'];
var REPO='';
function splitCoord(){var v=g('tower_lat').value;if(v.indexOf(',')>0&&v.split(',').length==2){var p=v.split(',');g('tower_lat').value=p[0].trim();g('tower_lon').value=p[1].trim()}}
function openCompass(){
 var la=g('lat').value,lo=g('lon').value,tl=g('tower_lat').value,tn=g('tower_lon').value;
 if(!la||!lo){g('cmsg').textContent='Isi dulu lokasi rumah di bagian Lokasi.';return}
 if(!tl||!tn){g('cmsg').textContent='Isi dulu lintang dan bujur tower.';return}
 if(!REPO){g('cmsg').textContent='Alamat repo belum diketahui.';return}
 var p=REPO.split('/');
 var u='https://'+p[0].toLowerCase()+'.github.io/'+p[1]+'/kompas.html?lat='+encodeURIComponent(la)+'&lon='+encodeURIComponent(lo)+'&tlat='+encodeURIComponent(tl)+'&tlon='+encodeURIComponent(tn)+'&name='+encodeURIComponent(g('tower_name').value);
 window.open(u,'_blank');
}
function g(i){return document.getElementById(i)}
function api(p,o){o=o||{};o.headers=o.headers||{};o.headers['X-Pin']=PIN;return fetch(p,o)}
function login(){
 PIN=g('pin').value;
 api('/api/get').then(function(r){
  if(!r.ok){g('err').textContent=(r.status==429)?'Terlalu banyak percobaan. Tutup lalu buka lagi layar Pengaturan di TV.':'PIN salah.';return null}
  return r.json()
 }).then(function(d){
  if(!d)return;
  g('lock').style.display='none';g('main').style.display='block';
  F.forEach(function(k){if(d[k]!==undefined)g(k).value=d[k]});
  if(d.repo)REPO=d.repo;
  g('pinset').textContent=d.pin_anak_set?'PIN kunci anak sudah diatur.':'PIN kunci anak belum diatur (kunci tidak aktif).';
 }).catch(function(){g('err').textContent='Tidak bisa terhubung ke TV. Pastikan HP satu Wi-Fi dengan TV.'})
}
function guessTz(lon){return lon<115?'Asia/Jakarta':(lon<126?'Asia/Makassar':'Asia/Jayapura')}
function tzLabel(tz){if(tz=='Asia/Jakarta')return'WIB';if(tz=='Asia/Makassar')return'WITA';if(tz=='Asia/Jayapura')return'WIT';return tz||''}
function isValidPlace(x){
 var fc=(x.feature_code||'').toUpperCase();
 var nm=(x.name||'').toLowerCase();
 if(fc.indexOf('AIR')===0||fc.indexOf('RSTN')===0||fc.indexOf('PORT')===0)return false;
 if(nm.indexOf('airport')>=0||nm.indexOf('bandara')>=0||nm.indexOf('pelabuhan')>=0)return false;
 return true;
}
function renderPlaces(list){
 var seen={};var cleanList=[];
 list.forEach(function(x){
  var key=x.latitude.toFixed(3)+','+x.longitude.toFixed(3);
  if(seen[key])return;seen[key]=1;cleanList.push(x);
 });
 window.RES=cleanList;
 var h='';
 cleanList.forEach(function(x,i){
  var sub=[x.admin2,x.admin1].filter(Boolean).join(', ');
  var label=x.name+(sub?' — '+sub:'');
  h+='<div class="r" onclick="pick('+i+')"><b>'+label+'</b><div class="s">'+tzLabel(x.timezone)+' · '+x.latitude.toFixed(4)+', '+x.longitude.toFixed(4)+'</div></div>';
 });
 g('res').innerHTML=h||'<div class="r">Tidak ditemukan. Coba tulis nama kecamatan atau kabupatennya.</div>';
}
function findCity(){
 var raw=g('q').value.trim();if(!raw)return;
 g('res').innerHTML='<div class="r">Mencari lokasi...</div>';
 if(raw.indexOf(',')>0&&raw.split(',').length==2){
  var p=raw.split(',');var la=parseFloat(p[0]),lo=parseFloat(p[1]);
  if(!isNaN(la)&&!isNaN(lo)){
   renderPlaces([{name:g('city').value||'Lokasi Terpilih',admin2:'',admin1:'Koordinat Manual',latitude:la,longitude:lo,timezone:guessTz(lo)}]);
   return;
  }
 }
 var clean=raw.replace(/^(desa|kelurahan|kel\.?|kecamatan|kec\.?|kabupaten|kab\.?|kota)\s+/i,'').trim();
 var queries=[clean];
 var noSpace=clean.replace(/\s+/g,'');
 if(noSpace.toLowerCase()!=clean.toLowerCase())queries.push(noSpace);
 Promise.all(queries.map(function(q){
  return fetch('https://geocoding-api.open-meteo.com/v1/search?count=15&language=id&countryCode=ID&name='+encodeURIComponent(q))
   .then(function(r){return r.json()}).then(function(d){return d.results||[]}).catch(function(){return[]});
 })).then(function(arrs){
  var merged=[];arrs.forEach(function(a){merged=merged.concat(a)});
  var valid=merged.filter(isValidPlace);
  if(valid.length>0){renderPlaces(valid);return}
  return fetch('https://nominatim.openstreetmap.org/search?format=json&countrycodes=id&addressdetails=1&limit=10&q='+encodeURIComponent(raw))
   .then(function(r){return r.json()}).then(function(items){
    var mapped=(items||[]).map(function(it){
     var a=it.address||{};
     var nm=a.village||a.suburb||a.hamlet||a.town||a.city_district||a.city||it.name||clean;
     var kab=a.county||a.city||a.municipality||'';
     var prov=a.state||'';
     var la=parseFloat(it.lat),lo=parseFloat(it.lon);
     return {name:nm,admin2:kab,admin1:prov,latitude:la,longitude:lo,timezone:guessTz(lo)};
    }).filter(function(x){return !isNaN(x.latitude)&&isValidPlace(x)});
    renderPlaces(mapped);
   });
 }).catch(function(){g('res').innerHTML='<div class="r">Pencarian butuh internet di HP</div>'});
}
function pick(i){
 var x=window.RES[i];
 var disp=x.name+(x.admin2&&x.admin2.toLowerCase()!=x.name.toLowerCase()?', '+x.admin2:'');
 g('city').value=disp;g('lat').value=String(x.latitude);g('lon').value=String(x.longitude);g('tz').value=x.timezone||guessTz(x.longitude);
 g('res').innerHTML='<div class="r">Dipilih: <b>'+disp+'</b> ('+tzLabel(g('tz').value)+') — klik Simpan ke TV</div>';
}
function refreshList(){
 api('/api/refresh',{method:'POST'}).then(function(r){g('rmsg').textContent=r.ok?'Jadwal pembaruan dikirim. Buka menu TV Live untuk mengunduh channel terbaru.':'Gagal mengirim perintah.'})
}
function save(){
 var o={};F.forEach(function(k){o[k]=g(k).value});
 api('/api/save',{method:'POST',body:JSON.stringify(o)}).then(function(r){g('msg').textContent=r.ok?'Tersimpan. Lihat layar TV.':'Gagal menyimpan.'})
}
function photo(){
 var f=g('file').files[0];if(!f)return;
 var im=new Image();var u=URL.createObjectURL(f);
 im.onload=function(){
  var k=Math.min(1,1280/im.width);var c=document.createElement('canvas');
  c.width=im.width*k;c.height=im.height*k;c.getContext('2d').drawImage(im,0,0,c.width,c.height);
  c.toBlob(function(b){api('/api/bg',{method:'POST',body:b}).then(function(r){
   g('msg').textContent=r.ok?'Foto terkirim ke TV.':'Foto gagal dikirim.';if(r.ok)g('bg').value='photo'})},'image/jpeg',0.75)
 };
 im.src=u
}
</script></body></html>"""
}
