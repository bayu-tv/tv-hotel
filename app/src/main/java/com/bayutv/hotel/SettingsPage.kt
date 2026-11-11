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
button{margin-top:14px;padding:12px 22px;border-radius:99px;border:1px solid #b8975a;background:none;color:#b8975a;font-size:16px;font-family:system-ui,sans-serif}
.r{padding:11px 12px;border:1px solid #34302a;border-radius:10px;margin-top:6px;font-family:system-ui,sans-serif}
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
<h2>Kota (cuaca dan jadwal sholat)</h2>
<label>Kota terpilih</label><input id="city" readonly>
<label>Cari kota lain</label><input id="q" placeholder="mis. Pontianak, Jakarta, Medan">
<button onclick="findCity()">Cari</button>
<div id="res"></div>
<h2>Panduan Rumah</h2>
<label>Nama Wi-Fi</label><input id="wifi">
<label>Sandi Wi-Fi</label><input id="wifipass">
<label>Aturan rumah</label><textarea id="rules" rows="3"></textarea>
<label>Catatan</label><textarea id="note" rows="3"></textarea>
<h2>Latar Beranda</h2>
<label>Pilihan latar</label>
<select id="bg"><option value="none">Polos (bawaan)</option><option value="senja">Senja</option><option value="photo">Foto saya</option></select>
<label>Pilih foto dari HP (otomatis dikecilkan)</label>
<input id="file" type="file" accept="image/*" onchange="photo()">
<h2>Sumber channel</h2>
<label>Kode negara iptv-org (2 huruf, mis. id, my, sg)</label><input id="country" maxlength="2">
<label>Alamat playlist M3U sendiri (opsional, menggantikan kode negara)</label><input id="playlist" placeholder="https://...">
<div class="s">Gunakan hanya sumber yang Anda berhak menontonnya.</div>
<button onclick="save()">Simpan ke TV</button>
<div id="msg"></div>
</div>
<script>
var PIN='',LAT='',LON='',TZ='';
var F=['family','city','wifi','wifipass','rules','note','country','playlist','bg'];
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
  LAT=d.lat||'';LON=d.lon||'';TZ=d.tz||''
 }).catch(function(){g('err').textContent='Tidak bisa terhubung ke TV. Pastikan HP satu Wi-Fi dengan TV.'})
}
function findCity(){
 var q=g('q').value.trim();if(!q)return;
 fetch('https://geocoding-api.open-meteo.com/v1/search?count=5&language=id&name='+encodeURIComponent(q))
 .then(function(r){return r.json()}).then(function(d){
  window.RES=d.results||[];var h='';
  window.RES.forEach(function(x,i){h+='<div class="r" onclick="pick('+i+')">'+x.name+(x.admin1?', '+x.admin1:'')+', '+x.country+'</div>'});
  g('res').innerHTML=h||'<div class="r">Tidak ditemukan</div>'
 }).catch(function(){g('res').innerHTML='<div class="r">Pencarian butuh internet di HP</div>'})
}
function pick(i){var x=window.RES[i];g('city').value=x.name;LAT=String(x.latitude);LON=String(x.longitude);TZ=x.timezone;g('res').innerHTML='<div class="r">Dipilih: '+x.name+'</div>'}
function save(){
 var o={lat:LAT,lon:LON,tz:TZ};F.forEach(function(k){o[k]=g(k).value});
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
