import http.server, socketserver, os, glob, hashlib

PORT = 7778
BASE = os.path.dirname(os.path.abspath(__file__))

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()[:16] + "..."

class StoreHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        super().end_headers()

    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            apks = glob.glob(os.path.join(BASE, "*.apk")) + glob.glob(os.path.join(BASE, "app/build/outputs/apk/**/*.apk"), recursive=True)
            rows = ""
            for apk in sorted(apks, key=os.path.getmtime, reverse=True):
                rel = os.path.relpath(apk, BASE)
                mb = os.path.getsize(apk) / (1024 * 1024)
                h = sha256_file(apk)
                rows += f'<div class="apk"><b>{os.path.basename(apk)}</b><br><small>{mb:.2f} MB | SHA256: {h}</small><br><a href="/{rel}">Download APK</a></div>'
            if not rows:
                rows = "<p style='color:#94A3B8'>No APK found yet. Place downloaded APK in ~/PuyalSovereignOS</p>"
            html = f"""<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>YUNA Mini App Store</title>
            <style>body{{background:#03070E;color:#fff;font-family:sans-serif;text-align:center;padding:30px}}
            .apk{{background:#0E1726;border:1px solid #00FFFF44;padding:18px;border-radius:16px;margin:12px auto;max-width:340px}}
            a{{display:inline-block;margin-top:10px;padding:10px 20px;border-radius:24px;background:linear-gradient(135deg,#00FFFF,#9C27B0);color:#000;font-weight:700;text-decoration:none}}</style>
            </head><body><h2>Puyal Sovereign OS — Mini App Store</h2>{rows}</body></html>"""
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(html.encode("utf-8"))
        else:
            super().do_GET()

os.chdir(BASE)
socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("0.0.0.0", PORT), StoreHandler) as httpd:
    print(f"[✔] YUNA :7778 Dynamic App Store LIVE -> http://127.0.0.1:{PORT}")
    httpd.serve_forever()
