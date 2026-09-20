"""DOWNI Cloud Boost — server-side extraction relay (Vercel Python function).

POST /api/extract  { "url": "...", "format": "best" }
  -> { "ok": true, "title": "...", "ext": "mp4", "url": "<direct CDN link>" }

GET /api/extract?health=1
  -> { "ok": true, "service": "downi-cloud-boost" }
"""

import json
from http.server import BaseHTTPRequestHandler

from yt_dlp import YoutubeDL

_FORMATS = {
    "audio": "ba/b",
    "1080": "b[height<=1080]/b/best",
    "720": "b[height<=720]/b/best",
    "480": "b[height<=480]/b/best",
    "360": "b[height<=360]/b/best",
    "best": "b/best",
}

_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept-Language": "en-US,en;q=0.9",
}


def _pick_muxed(info):
    """Pick the best format that has BOTH video and audio (no ffmpeg on device)."""
    # 1) yt-dlp already selected a single format -> info['url'] is its direct link
    url = info.get("url")
    if url and (info.get("acodec") != "none" or not info.get("formats")):
        return url, info.get("ext") or "mp4", info.get("filesize") or 0

    # 2) scan for muxed formats only (vcodec AND acodec present)
    muxed = [
        f for f in (info.get("formats") or [])
        if f.get("url")
        and f.get("vcodec") not in (None, "none")
        and f.get("acodec") not in (None, "none")
    ]
    if muxed:
        best = max(muxed, key=lambda f: (f.get("height") or 0, f.get("tbr") or 0))
        return best["url"], best.get("ext") or "mp4", best.get("filesize") or 0
    return None, None, 0


def _resolve(link, fmt):
    selector = _FORMATS.get((fmt or "best").lower(), _FORMATS["best"])
    is_audio = (fmt or "").lower() in ("audio", "mp3", "m4a")

    # YouTube needs client rotation to expose muxed (video+audio) formats
    attempts = [
        (selector, None),
        (selector, {"youtube": {"player_client": ["ios"]}}),
        (selector, {"youtube": {"player_client": ["tv", "web_embedded"]}}),
        ("b/best", None),
    ]

    last_error = None
    for sel, extractor_args in attempts:
        options = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "socket_timeout": 20,
            "retries": 2,
            "format": sel,
            "http_headers": dict(_HEADERS),
        }
        if extractor_args:
            options["extractor_args"] = extractor_args
        try:
            with YoutubeDL(options) as ydl:
                info = ydl.extract_info(link, download=False)
        except Exception as error:
            last_error = error
            continue

        if is_audio:
            url = info.get("url")
            if not url:
                audio = [
                    f for f in (info.get("formats") or [])
                    if f.get("url") and f.get("acodec") not in (None, "none")
                ]
                if audio:
                    best = max(audio, key=lambda f: f.get("tbr") or 0)
                    url = best["url"]
            if url:
                return {
                    "ok": True,
                    "title": info.get("title") or "DOWNI Video",
                    "uploader": info.get("uploader") or "",
                    "ext": info.get("ext") or "m4a",
                    "filesize": 0,
                    "url": url,
                }
            last_error = RuntimeError("no audio stream in response")
            continue

        url, ext, size = _pick_muxed(info)
        if url:
            return {
                "ok": True,
                "title": info.get("title") or "DOWNI Video",
                "uploader": info.get("uploader") or "",
                "ext": ext,
                "filesize": size,
                "url": url,
            }
        last_error = RuntimeError("no muxed video+audio stream in response")

    raise RuntimeError(str(last_error) or "Could not resolve this link")


def _probe(link):
    """Debug: show what each YouTube client exposes from this server's IP."""
    clients = [None, ["ios"], ["tv", "web_embedded"], ["android_vr"], ["web_safari"]]
    out = []
    for client in clients:
        options = {
            "quiet": True, "no_warnings": True, "noplaylist": True,
            "socket_timeout": 20, "retries": 1,
            "skip_download": True,
            "http_headers": dict(_HEADERS),
        }
        if client:
            options["extractor_args"] = {"youtube": {"player_client": client}}
        label = "+".join(client) if client else "default"
        try:
            with YoutubeDL(options) as ydl:
                info = ydl.extract_info(link, download=False)
            fmts = []
            for f in (info.get("formats") or [])[:40]:
                fmts.append({
                    "id": f.get("id"), "ext": f.get("ext"),
                    "v": f.get("vcodec"), "a": f.get("acodec"),
                    "h": f.get("height"), "proto": f.get("protocol"),
                })
            out.append({"client": label, "ok": True, "count": len(info.get("formats") or []), "formats": fmts})
        except Exception as e:
            out.append({"client": label, "ok": False, "error": str(e)[:150]})
    return out


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
        self._send(200, {"ok": True, "service": "downi-cloud-boost", "build": 2})

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
            if data.get("debug"):
                self._send(200, {"ok": True, "probe": _probe(link)})
                return
            result = _resolve(link, fmt)
            self._send(200, result)
        except Exception as error:
            self._send(502, {"ok": False, "error": str(error)[:400]})
