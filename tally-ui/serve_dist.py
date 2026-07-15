#!/usr/bin/env python3
import http.client
import mimetypes
import os
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


HOST = os.getenv("HOST", "0.0.0.0")
PORT = int(os.getenv("PORT", "4200"))
BACKEND_HOST = os.getenv("BACKEND_HOST", "127.0.0.1")
BACKEND_PORT = int(os.getenv("BACKEND_PORT", "9090"))
DIST_DIR = Path(__file__).resolve().parent / "dist" / "tally-ui"


class TallyUiHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(DIST_DIR), **kwargs)

    def do_GET(self):
        if self.path.startswith("/api/"):
            self._proxy()
            return
        self._serve_spa()

    def do_POST(self):
        self._proxy()

    def do_PUT(self):
        self._proxy()

    def do_PATCH(self):
        self._proxy()

    def do_DELETE(self):
        self._proxy()

    def do_OPTIONS(self):
        self._proxy()

    def _serve_spa(self):
        path = urlsplit(self.path).path
        if path == "/":
            self.path = "/index.html"
            return super().do_GET()

        requested = DIST_DIR / path.lstrip("/")
        if requested.is_file():
            return super().do_GET()

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write((DIST_DIR / "index.html").read_bytes())

    def _proxy(self):
        body = None
        content_length = self.headers.get("Content-Length")
        if content_length:
            body = self.rfile.read(int(content_length))

        conn = http.client.HTTPConnection(BACKEND_HOST, BACKEND_PORT, timeout=60)
        try:
            headers = {
                key: value
                for key, value in self.headers.items()
                if key.lower() not in {"host", "connection", "content-length"}
            }
            headers["Host"] = f"{BACKEND_HOST}:{BACKEND_PORT}"
            conn.request(self.command, self.path, body=body, headers=headers)
            response = conn.getresponse()
            payload = response.read()

            self.send_response(response.status, response.reason)
            for key, value in response.getheaders():
                if key.lower() in {"connection", "transfer-encoding", "content-length"}:
                    continue
                self.send_header(key, value)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        finally:
            conn.close()

    def guess_type(self, path):
        content_type, _ = mimetypes.guess_type(path)
        return content_type or "application/octet-stream"


def main():
    if not DIST_DIR.is_dir():
        raise SystemExit(f"Built UI not found: {DIST_DIR}")
    server = ThreadingHTTPServer((HOST, PORT), TallyUiHandler)
    print(f"Serving UI from {DIST_DIR} on http://127.0.0.1:{PORT}")
    print(f"Proxying /api to http://{BACKEND_HOST}:{BACKEND_PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
