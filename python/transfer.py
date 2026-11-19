#!/usr/bin/env python3
"""
Transfer - comparte archivos en tu red Wi-Fi.

Ejecuta este script en el telefono (con Termux) o en una PC.
Luego, desde cualquier otro dispositivo con navegador (TV, PC, tablet),
abre  http://transfer.local  (o la direccion IP que aparece en pantalla).

Solo usa la libreria estandar de Python 3. No necesita instalar nada mas.

Uso:
    python transfer.py                 # pregunta el puerto (Enter = 4747)
    python transfer.py --port 8080     # usa ese puerto sin preguntar
    python transfer.py --name transfer --dir ~/Descargas/Transfer
"""
import argparse
import html
import json
import mimetypes
import os
import socket
import struct
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CHUNK = 1024 * 1024  # 1 MB


# --------------------------------------------------------------------------
# Utilidades
# --------------------------------------------------------------------------
def local_ip():
    """IP de este dispositivo en la red Wi-Fi."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no envia nada, solo elige la interfaz
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def default_dir():
    termux_dl = os.path.expanduser("~/storage/downloads")
    if os.path.isdir(termux_dl):  # Android con Termux y termux-setup-storage
        return os.path.join(termux_dl, "Transfer")
    return os.path.join(os.path.expanduser("~"), "Transfer")


def safe_name(name):
    name = os.path.basename(name.replace("\\", "/")).strip()
    name = "".join(c for c in name if c not in '<>:"|?*\x00').strip(". ")
    return name or "archivo"


def unique_path(folder, name):
    base, ext = os.path.splitext(name)
    path, i = os.path.join(folder, name), 1
    while os.path.exists(path):
        path = os.path.join(folder, f"{base} ({i}){ext}")
        i += 1
    return path


# --------------------------------------------------------------------------
# mDNS: responde a "transfer.local" con la IP de este dispositivo
# --------------------------------------------------------------------------
MDNS_ADDR, MDNS_PORT = "224.0.0.251", 5353


def _read_name(data, off):
    labels, jumped, end = [], False, off
    for _ in range(64):
        n = data[off]
        if n == 0:
            off += 1
            break
        if n & 0xC0 == 0xC0:
            ptr = ((n & 0x3F) << 8) | data[off + 1]
            if not jumped:
                end = off + 2
            jumped, off = True, ptr
            continue
        labels.append(data[off + 1:off + 1 + n].decode("utf-8", "ignore"))
        off += 1 + n
    return ".".join(labels).lower(), (end if jumped else off)


def _encode_name(name):
    out = b""
    for part in name.split("."):
        b = part.encode()
        out += bytes([len(b)]) + b
    return out + b"\x00"


def mdns_responder(hostname, ip_getter):
    fqdn = hostname.lower() + ".local"
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        except OSError:
            pass
    try:
        sock.bind(("", MDNS_PORT))
        ip = ip_getter()
        mreq = struct.pack("4s4s", socket.inet_aton(MDNS_ADDR), socket.inet_aton(ip))
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
    except OSError as e:
        print(f"  ! No se pudo anunciar {fqdn} ({e}). Usa la direccion IP.")
        return False

    def loop():
        while True:
            try:
                data, addr = sock.recvfrom(9000)
                if len(data) < 12:
                    continue
                tid, flags, qd = struct.unpack("!HHH", data[:6])
                if flags & 0x8000:  # es una respuesta, no una pregunta
                    continue
                off, ask, unicast = 12, False, False
                for _ in range(qd):
                    name, off = _read_name(data, off)
                    qtype, qclass = struct.unpack("!HH", data[off:off + 4])
                    off += 4
                    if name == fqdn and qtype in (1, 255):
                        ask, unicast = True, unicast or bool(qclass & 0x8000)
                if not ask:
                    continue
                ip = ip_getter()
                answer = (_encode_name(fqdn) + struct.pack("!HHIH", 1, 0x8001, 120, 4)
                          + socket.inet_aton(ip))
                legacy = addr[1] != MDNS_PORT
                header = struct.pack("!HHHHHH", tid if legacy else 0, 0x8400, 0, 1, 0, 0)
                pkt = header + answer
                if legacy or unicast:
                    sock.sendto(pkt, addr)
                if not legacy:
                    sock.sendto(pkt, (MDNS_ADDR, MDNS_PORT))
            except Exception:
                continue

    threading.Thread(target=loop, daemon=True).start()
    return True


# --------------------------------------------------------------------------
# Servidor web
# --------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = "Transfer/1.0"
    folder = "."
    page = b""

    def log_message(self, fmt, *args):
        pass  # silencioso

    def _send(self, code, body=b"", ctype="text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        if url.path in ("/", "/index.html"):
            return self._send(200, self.page, "text/html; charset=utf-8")
        if url.path == "/api/files":
            files = []
            for n in os.listdir(self.folder):
                p = os.path.join(self.folder, n)
                if os.path.isfile(p) and not n.endswith(".part"):
                    st = os.stat(p)
                    files.append({"name": n, "size": st.st_size, "mtime": st.st_mtime})
            files.sort(key=lambda f: f["mtime"], reverse=True)
            return self._send(200, json.dumps(files).encode(), "application/json")
        if url.path.startswith("/f/"):
            name = safe_name(urllib.parse.unquote(url.path[3:]))
            path = os.path.join(self.folder, name)
            if not os.path.isfile(path):
                return self._send(404, b"No encontrado")
            size = os.path.getsize(path)
            self.send_response(200)
            view = "view" in urllib.parse.parse_qs(url.query, keep_blank_values=True)
            ctype = (mimetypes.guess_type(name)[0] if view else None) or "application/octet-stream"
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(size))
            q = urllib.parse.quote(name)
            inline = "inline" if view else "attachment"
            self.send_header("Content-Disposition", f"{inline}; filename*=UTF-8''{q}")
            self.end_headers()
            if self.command == "HEAD":
                return
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(CHUNK)
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        return
            return
        self._send(404, b"No encontrado")

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        if url.path != "/api/upload":
            return self._send(404, b"No encontrado")
        q = urllib.parse.parse_qs(url.query)
        name = safe_name(q.get("name", ["archivo"])[0])
        try:
            remaining = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self._send(411, b"Falta Content-Length")
        final = unique_path(self.folder, name)
        tmp = final + ".part"
        try:
            with open(tmp, "wb") as f:
                while remaining > 0:
                    chunk = self.rfile.read(min(CHUNK, remaining))
                    if not chunk:
                        raise ConnectionError("subida interrumpida")
                    f.write(chunk)
                    remaining -= len(chunk)
            os.replace(tmp, final)
        except Exception as e:
            try:
                os.remove(tmp)
            except OSError:
                pass
            return self._send(500, str(e).encode())
        print(f"  + Recibido: {os.path.basename(final)}")
        self._send(200, json.dumps({"name": os.path.basename(final)}).encode(),
                   "application/json")

    def do_DELETE(self):
        url = urllib.parse.urlparse(self.path)
        if not url.path.startswith("/f/"):
            return self._send(404, b"No encontrado")
        path = os.path.join(self.folder, safe_name(urllib.parse.unquote(url.path[3:])))
        if os.path.isfile(path):
            os.remove(path)
            return self._send(200, b"ok")
        self._send(404, b"No encontrado")


# --------------------------------------------------------------------------
# Interfaz (HTML + CSS + JS en un solo bloque)
# --------------------------------------------------------------------------
PAGE = r"""<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Transfer</title>
<style>
:root{--bg:#0f1115;--card:#181b22;--line:#2a2f3a;--txt:#e9ecf1;--mut:#9aa3b2;--acc:#4f8cff;--ok:#2ecc71;--bad:#ff5d5d}
@media (prefers-color-scheme: light){:root{--bg:#f4f6fa;--card:#fff;--line:#dfe3ea;--txt:#151820;--mut:#5d6675}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:860px;margin:0 auto;padding:20px 16px 60px}
header{display:flex;align-items:center;gap:12px;margin-bottom:18px}
header h1{font-size:24px;margin:0}
.addr{margin-left:auto;color:var(--mut);font-size:14px;text-align:right}
.addr b{color:var(--txt)}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:18px;margin-bottom:16px}
#drop{border:2px dashed var(--line);border-radius:12px;padding:34px 16px;text-align:center;cursor:pointer;transition:.15s}
#drop.over,#drop:focus{border-color:var(--acc);background:rgba(79,140,255,.08);outline:none}
#drop .big{font-size:20px;font-weight:600}
#drop .sm{color:var(--mut);font-size:14px;margin-top:6px}
button,.btn{font:inherit;border:0;border-radius:10px;padding:10px 16px;background:var(--acc);color:#fff;cursor:pointer;text-decoration:none;display:inline-block}
button:focus,.btn:focus{outline:3px solid #fff;outline-offset:2px}
.ghost{background:transparent;border:1px solid var(--line);color:var(--txt)}
.up{display:flex;align-items:center;gap:10px;margin-top:10px;font-size:14px}
.up .n{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bar{flex:1.3;height:8px;background:var(--line);border-radius:6px;overflow:hidden}
.bar i{display:block;height:100%;width:0;background:var(--acc);transition:width .1s}
.up.done .bar i{background:var(--ok)} .up.err .bar i{background:var(--bad)}
.head{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}
.head h2{margin:0;font-size:18px}
ul{list-style:none;margin:0;padding:0}
li{display:flex;align-items:center;gap:10px;padding:12px 4px;border-top:1px solid var(--line)}
li:first-child{border-top:0}
li .ic{font-size:22px;width:30px;text-align:center}
li .meta{flex:1;min-width:0}
li .nm{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
li .sz{color:var(--mut);font-size:13px}
li .del{background:transparent;color:var(--mut);padding:8px 10px}
.empty{color:var(--mut);text-align:center;padding:24px 0}
#qr{display:none;text-align:center} #qr img,#qr canvas{background:#fff;padding:8px;border-radius:8px}
@media (min-width:1200px){body{font-size:20px}main{max-width:1100px}} /* TV */
</style>
</head>
<body>
<main>
  <header>
    <h1>📁 Transfer</h1>
    <div class="addr">Abre en otro dispositivo:<br><b>__URL__</b><br><span>__IPURL__</span></div>
  </header>

  <section class="card">
    <div id="drop" tabindex="0" role="button">
      <div class="big">Toca aquí o arrastra archivos</div>
      <div class="sm">Se enviarán a este servidor y aparecerán en todos los dispositivos</div>
    </div>
    <input id="pick" type="file" multiple hidden>
    <div id="ups"></div>
  </section>

  <section class="card">
    <div class="head"><h2>Archivos</h2><button class="ghost" id="reload">Actualizar</button></div>
    <ul id="list"><li class="empty">Cargando…</li></ul>
  </section>

  <section class="card" id="qr"><div id="qrbox"></div><div class="sm">Escanea para abrir en el teléfono</div></section>
</main>
<script>
const $ = s => document.querySelector(s);
const fmt = b => { const u=['B','KB','MB','GB','TB']; let i=0; while(b>=1024&&i<4){b/=1024;i++} return (i?b.toFixed(1):b)+' '+u[i]; };
const icon = n => { const e=(n.split('.').pop()||'').toLowerCase();
  if(/^(jpe?g|png|gif|webp|heic|bmp|svg)$/.test(e)) return '🖼️';
  if(/^(mp4|mkv|mov|avi|webm|m4v)$/.test(e)) return '🎬';
  if(/^(mp3|wav|flac|m4a|ogg|aac)$/.test(e)) return '🎵';
  if(/^(zip|rar|7z|tar|gz)$/.test(e)) return '🗜️';
  if(/^(apk)$/.test(e)) return '📦';
  if(/^(pdf|docx?|xlsx?|pptx?|txt)$/.test(e)) return '📄';
  return '📎'; };
const esc = s => s.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

async function load(){
  try{
    const r = await fetch('/api/files'); const files = await r.json();
    const ul = $('#list');
    if(!files.length){ ul.innerHTML='<li class="empty">Todavía no hay archivos</li>'; return; }
    ul.innerHTML = files.map(f => {
      const u = '/f/'+encodeURIComponent(f.name);
      return `<li><span class="ic">${icon(f.name)}</span>
        <div class="meta"><div class="nm">${esc(f.name)}</div><div class="sz">${fmt(f.size)}</div></div>
        <a class="btn ghost" href="${u}?view" target="_blank">Ver</a>
        <a class="btn" href="${u}" download>Descargar</a>
        <button class="del" data-n="${esc(f.name)}" title="Borrar">✕</button></li>`;
    }).join('');
  }catch(e){ $('#list').innerHTML='<li class="empty">Sin conexión con el servidor</li>'; }
}

function upload(file){
  return new Promise(done => {
    const row = document.createElement('div'); row.className='up';
    row.innerHTML = `<span class="n">${esc(file.name)}</span><span class="bar"><i></i></span><span class="p">0%</span>`;
    $('#ups').prepend(row);
    const xhr = new XMLHttpRequest();
    xhr.open('POST','/api/upload?name='+encodeURIComponent(file.name));
    xhr.upload.onprogress = e => { if(e.lengthComputable){ const p=Math.round(e.loaded/e.total*100);
      row.querySelector('i').style.width=p+'%'; row.querySelector('.p').textContent=p+'%'; } };
    xhr.onload = () => { const ok = xhr.status===200; row.classList.add(ok?'done':'err');
      row.querySelector('i').style.width='100%'; row.querySelector('.p').textContent = ok?'✓':'Error'; load(); done(); };
    xhr.onerror = () => { row.classList.add('err'); row.querySelector('.p').textContent='Error'; done(); };
    xhr.send(file);
  });
}
async function send(files){ for(const f of files) await upload(f); }

const drop = $('#drop'), pick = $('#pick');
drop.onclick = () => pick.click();
drop.onkeydown = e => { if(e.key==='Enter'||e.key===' ') pick.click(); };
pick.onchange = () => { send([...pick.files]); pick.value=''; };
['dragenter','dragover'].forEach(t => drop.addEventListener(t, e => { e.preventDefault(); drop.classList.add('over'); }));
['dragleave','drop'].forEach(t => drop.addEventListener(t, e => { e.preventDefault(); drop.classList.remove('over'); }));
drop.addEventListener('drop', e => send([...e.dataTransfer.files]));
$('#reload').onclick = load;
$('#list').addEventListener('click', async e => {
  const b = e.target.closest('.del'); if(!b) return;
  if(!confirm('¿Borrar "'+b.dataset.n+'"?')) return;
  await fetch('/f/'+encodeURIComponent(b.dataset.n), {method:'DELETE'}); load();
});
load(); setInterval(load, 5000);

// Código QR (solo si hay internet para cargar la librería)
const s = document.createElement('script');
s.src = 'https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js';
s.onload = () => { $('#qr').style.display='block'; new QRCode($('#qrbox'), {text:'__IPURL__', width:180, height:180}); };
document.head.appendChild(s);
</script>
</body>
</html>
"""


def main():
    ap = argparse.ArgumentParser(description="Comparte archivos en tu red Wi-Fi")
    ap.add_argument("--port", type=int, default=None, help="puerto (si no se indica, lo pregunta; Enter = 4747)")
    ap.add_argument("--name", default="transfer", help="nombre en la red (por defecto: transfer)")
    ap.add_argument("--dir", default=default_dir(), help="carpeta donde se guardan los archivos")
    a = ap.parse_args()

    folder = os.path.abspath(os.path.expanduser(a.dir))
    os.makedirs(folder, exist_ok=True)

    if a.port is None:
        a.port = 4747
        if sys.stdin and sys.stdin.isatty():
            while True:
                r = input("  Puerto a usar [Enter = 4747]: ").strip()
                if not r:
                    break
                if r.isdigit() and 1 <= int(r) <= 65535:
                    a.port = int(r)
                    break
                print("  Escribe un número entre 1 y 65535.")

    server = None
    for port in [a.port]:
        try:
            server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
            break
        except OSError:
            continue
    if not server:
        sys.exit(f"No se pudo abrir el puerto {a.port}. ¿Ya está abierto Transfer? Prueba con otro: --port 8080")
    port = server.server_address[1]

    ip = local_ip()
    suffix = "" if port == 80 else f":{port}"
    url = f"http://{a.name}.local{suffix}"
    ipurl = f"http://{ip}{suffix}"

    Handler.folder = folder
    Handler.page = (PAGE.replace("__URL__", html.escape(url))
                        .replace("__IPURL__", html.escape(ipurl))).encode("utf-8")

    print("\n  Transfer está funcionando")
    print("  ------------------------------------------")
    mdns_ok = mdns_responder(a.name, local_ip)
    if mdns_ok:
        print(f"  Abre en otro dispositivo:  {url}")
    print(f"  Si no abre, usa la IP:     {ipurl}")
    print(f"  Carpeta de archivos:       {folder}")
    print("  Para detener: Ctrl + C\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n  Detenido.")


if __name__ == "__main__":
    main()
