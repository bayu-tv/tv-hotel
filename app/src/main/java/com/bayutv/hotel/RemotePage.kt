package com.bayutv.hotel

// Remote HP untuk TV Live. Tidak memakai tanda dolar agar aman di string Kotlin.
object RemotePage {
    val HTML = """<!doctype html>
<html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Remote Bayu TV</title>
<style>
body{margin:0 auto;background:#121110;color:#efe8da;font-family:system-ui,sans-serif;padding:16px 16px 60px;max-width:520px}
h1{font-family:Georgia,serif;font-weight:400;color:#b8975a;font-size:24px;margin:4px 0 10px}
input,select{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #34302a;background:#1a1816;color:#efe8da;font-size:15px}
button{padding:14px 0;border-radius:12px;border:1px solid #b8975a;background:#1a1816;color:#e6c88f;font-size:20px;width:100%}
.row{display:flex;gap:10px;margin:10px 0}
.row>*{flex:1}
#cur{font-size:18px;color:#e6c88f;margin:6px 0 2px}
#now{color:#a79f90;font-size:13px;min-height:16px}
.c{padding:12px;border-bottom:1px solid #2a2622;display:flex;gap:10px}
.c b{color:#a79f90;font-weight:400;width:38px;text-align:right}
.c small{color:#7d7568;display:block}
#err{color:#c9706a;margin-top:10px}
</style></head><body>
<h1>Remote Bayu TV</h1>
<div id="lock">
<div>Masukkan PIN remote yang tampil di TV (tekan tombol kanan di TV Live).</div>
<input id="pin" inputmode="numeric" maxlength="4" placeholder="4 angka" style="margin-top:10px">
<div class="row"><button onclick="login()">Sambungkan</button></div>
<div id="err"></div>
</div>
<div id="main" style="display:none">
<div id="cur">-</div><div id="now"></div>
<div class="row"><button onclick="cmd('zap','-1')">&#9650; Naik</button><button onclick="cmd('zap','1')">&#9660; Turun</button></div>
<div class="row"><button onclick="cmd('panel','')">Daftar di TV</button><button onclick="cmd('ok','')">OK</button></div>
<div class="row">
<select id="sleep" onchange="cmd('sleep',this.value)">
<option value="">Timer tidur...</option><option value="0">Matikan timer</option><option value="30">30 menit</option>
<option value="60">60 menit</option><option value="90">90 menit</option><option value="120">120 menit</option>
</select>
<select id="qual" onchange="cmd('quality',this.value)">
<option value="0">Kualitas: Otomatis</option>
<option value="720">Kualitas: 720p (Hemat)</option>
<option value="480">Kualitas: 480p (Ekstra Hemat)</option>
</select>
</div>
<input id="q" placeholder="Cari channel (nama atau nomor)" oninput="draw()">
<div id="list"></div>
</div>
<script>
var PIN='',CH=[];
function g(i){return document.getElementById(i)}
function api(m,p,b){return fetch(p,{method:m,headers:{'X-Pin':PIN,'Content-Type':'application/json'},body:b?JSON.stringify(b):undefined}).then(function(r){if(r.status==403)throw new Error('PIN salah');if(r.status==429)throw new Error('Terlalu banyak salah. Tutup lalu buka lagi TV Live.');if(!r.ok)throw new Error('Gagal');return r.text()})}
function login(){PIN=g('pin').value.trim();load().then(function(){g('lock').style.display='none';g('main').style.display='block';setInterval(load,5000)}).catch(function(e){g('err').textContent=e.message})}
function load(){return api('GET','/api/channels').then(function(t){var d=JSON.parse(t);CH=d.list;g('cur').textContent=d.cur||'-';g('now').textContent=d.now||'';if(d.q!==undefined&&g('qual'))g('qual').value=String(d.q);if(!g('q').value||!g('list').children.length)draw()})}
function cmd(c,a){api('POST','/api/cmd',{cmd:c,arg:a}).then(function(){setTimeout(load,800)}).catch(function(e){alert(e.message)});if(c=='sleep')g('sleep').value=''}
function draw(){var q=g('q').value.toLowerCase().trim(),h='';var n=0;
for(var i=0;i<CH.length&&n<150;i++){var c=CH[i];if(q&&c.name.toLowerCase().indexOf(q)<0&&String(c.n)!==q)continue;n++;
h+='<div class="c" onclick="pick('+i+')"><b>'+c.n+'</b><div>'+esc(c.name)+'<small>'+esc(c.cat)+'</small></div></div>'}
g('list').innerHTML=h||'<div class="c">Tidak ditemukan</div>'}
function pick(i){cmd('play',CH[i].k)}
function esc(s){return String(s).replace(/[&<>"]/g,function(x){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[x]})}
</script>
</body></html>"""
}
