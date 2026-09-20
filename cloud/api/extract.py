"""DOWNI Cloud Boost — server-side extraction relay (Vercel Python function).

POST /api/extract  { "url": "...", "format": "best" }
  -> { "ok": true, "title": "...", "ext": "mp4", "url": "<direct CDN link>" }

GET /api/extract?health=1
  -> { "ok": true, "service": "downi-cloud-boost" }
"""

import json
import os
from http.server import BaseHTTPRequestHandler

from yt_dlp import YoutubeDL

_FORMATS = {
    "audio": "ba[ext=m4a]/ba/b",
    "1080": "b[height<=1080][ext=mp4]/b[height<=1080]/b/best",
    "720": "b[height<=720][ext=mp4]/b[height<=720]/b/best",
    "480": "b[height<=480][ext=mp4]/b[height<=480]/b/best",
    "360": "b[height<=360][ext=mp4]/b[height<=360]/b/best",
    "best": "b[ext=mp4]/b/best",
}


def _resolve(link, fmt):
    options = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "socket_timeout": 20,
        "retries": 2,
        "format": _FORMATS.get((fmt or "best").lower(), _FORMATS["best"]),
        "http_headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept-Language": "en-US,en;q=0.9",
        },
    }
    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(link, download=False)

    chosen = None
    fmts = info.get("formats") or []
    if fmts:
        playable = [f for f in fmts if f.get("url") and f.get("vcodec") != "none"]
        candidates = playable or [f for f in fmts if f.get("url")]
        if candidates:
            chosen = max(candidates, key=lambda f: (f.get("height") or 0, f.get("tbr") or 0))
    url = (chosen or {}).get("url") or info.get("url")
    if not url:
        raise RuntimeError("No downloadable stream found")
    ext = (chosen or {}).get("ext") or info.get("ext") or "mp4"
    return {
        "ok": True,
        "title": info.get("title") or "DOWNI Video",
        "uploader": info.get("uploader") or "",
        "ext": ext,
        "filesize": (chosen or {}).get("filesize") or 0,
        "url": url,
    }


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self._send(200, {"ok": True})

    def do_GET(self):
        self._send(200, {"ok": True, "service": "downi-cloud-boost"})

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            data = json.loads(raw.decode("utf-8") or "{}")
            link = (data.get("url") or data.get("link") or "").strip()
            fmt = (data.get("format") or "best").strip()
            if not link.startswith("http"):
                self._send(400, {"ok": False, "error": "Invalid link"})
                return
            result = _resolve(link, fmt)
            self._send(200, result)
        except Exception as error:
            self._send(502, {"ok": False, "error": str(error)[:400]})
