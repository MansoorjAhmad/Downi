"""DOWNI on-device download engine — Built on the proven v1.3.6 core.

100% on-device local execution via Chaquopy + yt-dlp.
No cloud relays, no external servers, no account login required.
"""

import json
import os
import concurrent.futures
import socket
import urllib.error
import ssl
import re
import time
import urllib.parse
import urllib.request
import certifi
from yt_dlp import YoutubeDL


# ---------------------------------------------------------------------------
# THE TRUTH LAYER - one classifier, one raised shape.
#
# The rules live in truth.py (no yt-dlp imports there, so the classifier can
# be unit-tested on a plain Python - see tools/test_truth.py). Every failure
# this engine produces is tagged with its cause, and the raw reason survives
# to every surface: nothing here guesses, and nothing here blames the user's
# video for a reason the engine never verified.
# ---------------------------------------------------------------------------

from truth import classify as _classify, fail as _fail  # noqa: E402


def _ydl_opts(**extra):
    """The options every yt-dlp call in this engine uses.

    js_runtimes is EMPTY on purpose. The newer yt-dlp solves YouTube's JS
    challenge by spawning deno (or node) as a subprocess, and Chaquopy cannot
    spawn one on Android - the app died with SIGSEGV inside
    _posixsubprocess the first time the nightly touched a YouTube link. The
    formats resolve perfectly well without a runtime (the probe found 48 of
    them on a machine WITH deno installed and the same 48 with it switched
    off), so we never ask for a runtime we cannot have.
    """
    opts = {
        'quiet': True,
        'no_warnings': True,
        'ca_certs': certifi.where(),
        'js_runtimes': {},
        # There is no ffmpeg on this device, and yt-dlp must be told so in
        # the one way it understands: a location that does not exist. It then
        # reports "Continuing without ffmpeg" and NEVER probes for the binary.
        #
        # Two things this avoids, both found on the phone:
        #   - the default (None) makes yt-dlp RUN ffmpeg to read its version,
        #     and Chaquopy cannot spawn a process on Android: SIGSEGV inside
        #     _posixsubprocess, the app dies.
        #   - False is not handled by yt-dlp at all: its ffmpeg postprocessor
        #     calls os.path.exists(False) and raises
        #     "expected str, bytes or os.PathLike object, not bool", which
        #     killed every 1080p grab AFTER the media had downloaded.
        #
        # DOWNI does not need ffmpeg: the 1080p merge is Android's own
        # MediaMuxer, in Java.
        'ffmpeg_location': '/nonexistent/downi-no-ffmpeg',
    }
    opts.update(extra)
    return opts



def _safe_name(value, uploader=None):
    """Sanitize title/filename: strips hashtags, platform suffixes, and illegal filesystem chars.

    Preserves full multi-language Unicode (Urdu, Arabic, Hindi, Cyrillic, Chinese, emoji).
    """
    if not value:
        return (uploader.strip() + ' - Video') if (uploader and str(uploader).strip()) else 'Video'

    import unicodedata

    # T1: Unicode normalization (NFC)
    text = unicodedata.normalize('NFC', str(value))

    # T2: Strip BiDi control characters, directional overrides, and zero-width markers
    text = re.sub(r'[\u200e\u200f\u202a-\u202e\u2066-\u2069\ufeff\u200b]+', '', text)

    # 1. Strip platform suffixes (e.g. " - YouTube", " | TikTok", " • Instagram")
    text = re.sub(r'\s*[-|•–—]\s*(YouTube|TikTok|Instagram|Facebook|Reels|Shorts).*$', '', text, flags=re.IGNORECASE)

    # 2. Strip trailing hashtags if real text precedes them
    cleaned = re.sub(r'(\s*#\w+)+\s*$', '', text).strip()
    if cleaned:
        text = cleaned
    else:
        # Title only had hashtags (e.g. "#nature #travel"): keep words without "#"
        text = text.replace('#', '')

    # Remove any remaining "#" in middle
    text = text.replace('#', '')

    # T4: Replace illegal filesystem characters (\ / : * ? " < > |) and control chars
    text = re.sub(r'[\\/:*?"<>|\r\n\t\x00-\x1f\x7f-\x9f]+', ' ', text)

    # T5: Collapse whitespace and trim leading/trailing dots/spaces/dashes
    text = re.sub(r'\s+', ' ', text).strip(' .-_')

    # T8: Fallback if empty after stripping
    if not text:
        text = (uploader.strip() + ' - Video') if (uploader and str(uploader).strip()) else 'Video'

    # T5: Disambiguate Windows reserved device names
    if text.upper() in {'CON', 'PRN', 'AUX', 'NUL',
                        'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
                        'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'}:
        text = f"Video_{text}"

    # T3/T7: Length truncation (max 80 chars, max 180 UTF-8 bytes)
    if len(text) > 80:
        cut = text.rfind(' ', 0, 80)
        text = text[:cut].strip(' .-_') if cut > 40 else text[:80].strip(' .-_')

    # Bound by 180 UTF-8 bytes
    encoded = text.encode('utf-8', errors='ignore')
    if len(encoded) > 180:
        text = encoded[:180].decode('utf-8', errors='ignore').strip(' .-_')

    return text.strip(' .-_') or "Video"


def _clean_url(url):
    """Extract and unwrap URL without altering critical query tokens."""
    if not url:
        return ""
    match = re.search(r'https?://[^\s<>"\'\)]+', url)
    clean = match.group(0).rstrip('.,;:!?)]}') if match else url.strip()

    # Expand short share links if needed
    if any(short in clean for short in ('vt.tiktok.com', 'vm.tiktok.com', 'tiktok.com/t/', 'fb.watch', 'facebook.com/share')):
        try:
            req = urllib.request.Request(
                clean,
                headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'}
            )
            with urllib.request.urlopen(req, timeout=10) as response:
                clean = response.geturl()
        except Exception:
            pass

    return clean


def _detect_platform(url):
    u = (url or '').lower()
    if 'youtube' in u or 'youtu.be' in u:
        return 'youtube'
    if 'tiktok' in u:
        return 'tiktok'
    if 'instagram' in u:
        return 'instagram'
    if 'facebook' in u or 'fb.watch' in u or 'fb.gg' in u:
        return 'facebook'
    if 'twitter' in u or 'x.com' in u:
        return 'twitter'
    if 'reddit' in u:
        return 'reddit'
    if 'pinterest' in u:
        return 'pinterest'
    return 'other'


# Height cap used by the split+merge fallback for each quality lane.
# 'best' caps at 1080 on purpose: YouTube's H.264 (avc1) video-only streams only
# exist up to 1080p, and H.264 + M4A is the one pair MediaMuxer can mux on every
# supported device (VP9 needs API 29+, AV1 needs API 34+).
_LANE_MAX_HEIGHTS = {
    'best': 1080,
    '1080': 1080,
    '720': 720,
    '480': 480,
    '360': 360,
}


def _lane_height(format_id):
    """Height cap for the merge fallback of a given quality lane."""
    return _LANE_MAX_HEIGHTS.get(str(format_id).lower(), 1080)


# ---------------------------------------------------------------------------
# Split download (DASH video + audio) for on-device muxing — no ffmpeg needed
# ---------------------------------------------------------------------------

def _scaled_hook(progress_listener, lo, hi):
    """Progress hook that maps one stream's download into a [lo, hi] % window."""
    last = [0.0]

    def hook(d):
        if progress_listener is None:
            return
        status = d.get('status')
        if status == 'downloading':
            if hasattr(progress_listener, 'isCancelled') and progress_listener.isCancelled():
                raise RuntimeError("Download cancelled.")
            now = time.time()
            if now - last[0] < 0.25:
                return
            last[0] = now
            downloaded = d.get('downloaded_bytes') or 0
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            speed = d.get('speed') or 0.0
            eta = d.get('eta') or 0
            frac = (float(downloaded) / float(total)) if total > 0 else 0.0
            # lo..hi are fractions of the whole job (0.0-0.7 for the video
            # stream, 0.7-0.95 for the audio stream) but the Java listener wants
            # a percentage, exactly like the combined-path hook below. Without
            # the *100 the bar sat below 1% for the entire merge and then jumped
            # straight to the Java 96/98/100 "merging"/"saving" steps.
            pct = (lo + (hi - lo) * min(1.0, frac)) * 100.0
            try:
                progress_listener.onProgress(float(pct), int(downloaded), int(total), float(speed or 0.0), int(eta or 0))
            except Exception:
                pass
        elif status == 'finished':
            try:
                progress_listener.onProgress(float(hi) * 100.0, 0, 0, 0.0, 0)
            except Exception:
                pass

    return hook


def _download_split(url, target_dir, max_height=1080, progress_listener=None):
    """Download best H.264 video-only + M4A audio-only streams for muxing.

    Returns a dict the Java layer muxes with MediaMuxer, or None when this video
    has no separate streams (the caller then falls back / reports honestly).
    """
    os.makedirs(target_dir, exist_ok=True)
    base = {
        'quiet': True,
        'no_warnings': True,
        'js_runtimes': {},
        'ffmpeg_location': '/nonexistent/downi-no-ffmpeg',
        'noplaylist': True,
        'ca_certs': certifi.where(),
        'outtmpl': os.path.join(target_dir, '%(title).120B-%(id)s.%(ext)s'),
        'windowsfilenames': True,
        'overwrites': True,
        # DOWNI has no pause/resume: download straight to the final name, never a .part file.
        'nopart': True,
        'continuedl': False,
        'socket_timeout': 30,
        'retries': 3,
        'concurrent_fragment_downloads': 3,
    }
    video_path = None
    audio_path = None
    try:
        video_opts = dict(base)
        video_opts['format'] = (
            'bestvideo[ext=mp4][vcodec^=avc1][height<=%d]/'
            'bestvideo[ext=mp4][vcodec^=avc1]/'
            'bestvideo[height<=%d]' % (max_height, max_height)
        )
        video_opts['progress_hooks'] = [_scaled_hook(progress_listener, 0.0, 0.7)] if progress_listener else []
        with YoutubeDL(video_opts) as ydl:
            v_info = ydl.extract_info(url, download=True)
            video_path = ydl.prepare_filename(v_info)
        if not video_path or not os.path.exists(video_path):
            return None

        audio_opts = dict(base)
        audio_opts['format'] = 'bestaudio[ext=m4a]/bestaudio'
        audio_opts['progress_hooks'] = [_scaled_hook(progress_listener, 0.7, 0.95)] if progress_listener else []
        with YoutubeDL(audio_opts) as ydl:
            a_info = ydl.extract_info(url, download=True)
            audio_path = ydl.prepare_filename(a_info)
        if not audio_path or not os.path.exists(audio_path):
            try:
                os.remove(video_path)
            except OSError:
                pass
            return None
    except Exception as exc:
        for leftover in (video_path, audio_path):
            if leftover:
                try:
                    os.remove(leftover)
                except OSError:
                    pass
        # THE TRUTH LAYER: returning None here would report "this video has no
        # separate 1080p stream" for whatever actually went wrong (a 403, a
        # timeout, a refused request). The reason is the reason - raise it.
        _fail(exc)

    return {
        'merge': True,
        'video_path': video_path,
        'audio_path': audio_path,
        'title': _safe_name((v_info.get('title') if v_info else '') or 'video'),
        'ext': 'mp4',
        'platform': 'youtube',
    }


# ---------------------------------------------------------------------------
# TikTok Direct Handler (Proven fast & watermark-free)
# ---------------------------------------------------------------------------

def _fetch_tiktok_data(url):
    api = "https://www.tikwm.com/api/"
    data = urllib.parse.urlencode({
        'url': url,
        'count': 12,
        'cursor': 0,
        'web': 1,
        'hd': 1
    }).encode('utf-8')
    req = urllib.request.Request(
        api,
        data=data,
        headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            'Accept': 'application/json, text/javascript, */*; q=0.01',
            'Referer': 'https://www.tikwm.com/'
        }
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode('utf-8', errors='ignore'))


def _download_stream_direct(stream_url, target_path, referer='', progress_listener=None):
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': '*/*',
    }
    if referer:
        headers['Referer'] = referer
    req = urllib.request.Request(stream_url, headers=headers)
    with urllib.request.urlopen(req, timeout=35) as resp:
        total = int(resp.headers.get('Content-Length') or 0)
        downloaded = 0
        last_cb = 0.0
        start = time.time()
        with open(target_path, 'wb') as f:
            while True:
                if progress_listener and hasattr(progress_listener, 'isCancelled') and progress_listener.isCancelled():
                    raise RuntimeError("Download cancelled.")
                chunk = resp.read(128 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                now = time.time()
                if progress_listener and (now - last_cb > 0.25):
                    last_cb = now
                    elapsed = max(0.001, now - start)
                    speed = downloaded / elapsed if downloaded > 0 else 0.0
                    eta = int((total - downloaded) / speed) if (total > downloaded and speed > 0) else 0
                    pct = (downloaded / total * 100.0) if total > 0 else 50.0
                    try:
                        progress_listener.onProgress(float(min(99.0, pct)), int(downloaded), int(total), float(speed), int(eta))
                    except Exception:
                        pass
    if progress_listener:
        try:
            progress_listener.onProgress(100.0, downloaded, total, 0.0, 0)
        except Exception:
            pass


def _download_tiktok_direct(url, target_dir, is_audio=False, progress_listener=None):
    data = _fetch_tiktok_data(url)
    if data.get('code') != 0:
        _fail(data.get('msg') or 'TikTok video not available')
    item = data.get('data') or {}
    stream_url = item.get('music') if is_audio else (item.get('hdplay') or item.get('play') or item.get('wmplay'))
    if not stream_url:
        _fail('No downloadable stream found in TikTok response')
    if stream_url.startswith('/'):
        stream_url = urllib.parse.urljoin('https://www.tikwm.com', stream_url)
    raw_title = item.get('title') or ('tiktok_' + str(item.get('id', int(time.time()))))
    title = _safe_name(raw_title)
    ext = 'mp3' if is_audio else 'mp4'
    filename = f"{title[:50]}-{item.get('id', 'video')}.{ext}"
    out_path = os.path.join(target_dir, filename)
    _download_stream_direct(stream_url, out_path, 'https://www.tiktok.com/', progress_listener)
    filesize = os.path.getsize(out_path) if os.path.exists(out_path) else 0
    return {
        'path': out_path,
        'title': title,
        'ext': ext,
        'filesize': filesize,
        'platform': 'tiktok'
    }


# ---------------------------------------------------------------------------
# Inspect (v1.3.6 core with TikTok fast-path)
# ---------------------------------------------------------------------------

def inspect(url):
    clean = _clean_url(url)
    platform = _detect_platform(clean)

    # 1) TikTok fast direct metadata
    if platform == 'tiktok':
        try:
            data = _fetch_tiktok_data(clean)
            if data.get('code') == 0:
                d = data.get('data', {})
                cover = d.get('cover') or d.get('origin_cover') or ''
                if cover.startswith('/'):
                    cover = urllib.parse.urljoin('https://www.tikwm.com', cover)
                return json.dumps({
                    'title': _safe_name(d.get('title') or 'TikTok Video'),
                    'uploader': (d.get('author') or {}).get('nickname') or (d.get('author') or {}).get('unique_id') or 'TikTok Creator',
                    'duration': int(d.get('duration') or 0),
                    'thumbnail': cover,
                    'webpage_url': clean,
                    'platform': 'tiktok',
                    'formats': [
                        {'id': 'best', 'label': 'HD Video (No Watermark)', 'ext': 'mp4', 'badge': 'HD'},
                        {'id': 'audio', 'label': 'Audio Track (MP3)', 'ext': 'mp3', 'badge': 'MP3'}
                    ]
                })
        except Exception:
            pass

    # 2) Standard yt-dlp extraction with certifi (identical to v1.3.6)
    options = {
        'quiet': True,
        'no_warnings': True,
        'js_runtimes': {},
        'ffmpeg_location': '/nonexistent/downi-no-ffmpeg',
        'skip_download': True,
        'noplaylist': True,
        'ca_certs': certifi.where(),
    }
    info = None
    inspect_error = None
    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(clean, download=False)
    except Exception as exc:
        # THE TRUTH LAYER: fabricating a card here made a link we could not
        # read look like a video with no title, and the layer above then told
        # the user "That link isn't a video" - a cause we never verified. Keep
        # the reason and pass it up.
        inspect_error = exc
        info = None

    if info:
        title = _safe_name(info.get('title') or 'video')
        uploader = info.get('uploader') or info.get('channel') or platform.capitalize()
        duration = int(info.get('duration') or 0)
        thumbnail = info.get('thumbnail') or ''
        webpage_url = info.get('webpage_url') or clean
    else:
        if inspect_error is not None:
            # Nothing could be read from this link - and we know why.
            _fail(inspect_error)
        title = _safe_name(platform.capitalize() + ' Video')
        uploader = platform.capitalize()
        duration = 0
        thumbnail = ''
        webpage_url = clean

    # Honest, platform-aware quality choices. No ffmpeg is bundled on-device.
    # YouTube's 1080p is a separate video+audio pair merged by Android's
    # MediaMuxer; other sites' 1080p is often video-only DASH, which this engine
    # cannot merge, so their only honest lane is the best single file.
    if platform == 'youtube':
        formats = [
            {'id': 'best', 'label': 'Best Available Quality (HD)', 'ext': 'mp4', 'badge': 'HD'},
            {'id': '1080', 'label': '1080p Full HD (merged on device)', 'ext': 'mp4', 'badge': '1080p'},
            {'id': '720', 'label': '720p HD Quality', 'ext': 'mp4', 'badge': '720p'},
            {'id': '480', 'label': '480p Standard Quality', 'ext': 'mp4', 'badge': '480p'},
            {'id': '360', 'label': '360p Data Saver', 'ext': 'mp4', 'badge': '360p'},
            {'id': 'audio', 'label': 'Audio Track (MP3 / M4A)', 'ext': 'mp3', 'badge': 'MP3'},
        ]
        note = ('YouTube serves 1080p as separate video and audio — DOWNI merges them on your '
                'device. Pick "Best" for the fastest grab, or 1080p Full HD for maximum quality.')
    else:
        # Instagram (and any other non-YouTube site) does not expose a
        # trustworthy set of single-file resolutions to this engine. Its 1080p
        # stream is usually video-only DASH, and DOWNI has no ffmpeg to merge
        # non-YouTube streams. Offering 1080p/720p/480p rows here promised
        # resolutions the delivered file did not have (v3.6.2 matrix: the
        # "Up to 1080p" lane delivered 720x1280). Offer only the honest best
        # single-file lane; do not invent tiers.
        formats = [
            {'id': 'best', 'label': 'Best Available Quality', 'ext': 'mp4', 'badge': 'Best'},
        ]
        note = ('Best single file available. Higher resolutions may exist only as '
                'separate video and audio streams, which DOWNI does not merge for this site.')

    return json.dumps({
        'title': title,
        'uploader': uploader,
        'duration': duration,
        'thumbnail': thumbnail,
        'webpage_url': webpage_url,
        'platform': platform,
        'formats': formats,
        'note': note,
    })


# ---------------------------------------------------------------------------
# Download (Exact rock-solid v1.3.6 core + progress listener)
# ---------------------------------------------------------------------------

def _download_lanes(url, target_dir, format_id='best', progress_listener=None):
    os.makedirs(target_dir, exist_ok=True)
    clean = _clean_url(url)
    platform = _detect_platform(clean)
    is_audio = str(format_id).lower() in ('audio', 'mp3', 'm4a')

    if progress_listener and hasattr(progress_listener, 'isCancelled') and progress_listener.isCancelled():
        raise RuntimeError("Download cancelled.")

    # 1) TikTok direct fast download
    if platform == 'tiktok':
        try:
            res = _download_tiktok_direct(clean, target_dir, is_audio, progress_listener)
            return json.dumps(res)
        except RuntimeError:
            raise                                  # the reason is classified - keep it
        except Exception:
            pass

    # 2) Split + on-device merge — true 1080p where only DASH streams exist
    #    (video and audio arrive separately and are muxed natively in Java).
    if platform == 'youtube' and str(format_id) == '1080':
        split = _download_split(clean, target_dir, 1080, progress_listener)
        if split:
            return json.dumps(split)
        # Only reached when the split SUCCEEDED but produced no separate streams
        # - a genuine "there is no 1080p here". A split that failed raises its
        # own reason and never reaches this line.
        _fail('This video has no separate 1080p stream - pick 720p HD or Best Available.')

    # 3) Universal yt-dlp download with proven v1.3.6 format cascading
    video_formats = {
        '1080': 'best[height<=1080][ext=mp4][vcodec!=none]/best[height<=1080][vcodec!=none]/best',
        '720': 'best[height<=720][ext=mp4][vcodec!=none]/best[height<=720][vcodec!=none]/best',
        '480': 'best[height<=480][ext=mp4][vcodec!=none]/best[height<=480][vcodec!=none]/best',
        '360': 'best[height<=360][ext=mp4][vcodec!=none]/best[height<=360][vcodec!=none]/best',
        'best': 'best[ext=mp4][vcodec!=none]/best[vcodec!=none]/best',
    }
    fmt = 'bestaudio[ext=m4a]/bestaudio/best' if is_audio else video_formats.get(str(format_id), 'best[ext=mp4][vcodec!=none]/best[vcodec!=none]/best')

    last_callback_time = [0.0]

    def _progress_hook(d):
        if progress_listener is None:
            return
        status = d.get('status')
        if status == 'downloading':
            if hasattr(progress_listener, 'isCancelled') and progress_listener.isCancelled():
                raise RuntimeError("Download cancelled.")
            now = time.time()
            if now - last_callback_time[0] < 0.25:
                return
            last_callback_time[0] = now
            downloaded = d.get('downloaded_bytes') or 0
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            speed = d.get('speed') or 0.0
            eta = d.get('eta') or 0
            pct = (float(downloaded) / float(total) * 100.0) if total > 0 else 0.0
            try:
                progress_listener.onProgress(float(min(99.0, max(0.0, pct))), int(downloaded), int(total), float(speed or 0.0), int(eta or 0))
            except Exception:
                pass
        elif status == 'finished':
            try:
                progress_listener.onProgress(100.0, 0, 0, 0.0, 0)
            except Exception:
                pass

    options = {
        'quiet': True,
        'no_warnings': True,
        'js_runtimes': {},
        'ffmpeg_location': '/nonexistent/downi-no-ffmpeg',
        'noplaylist': True,
        'ca_certs': certifi.where(),
        'format': fmt,
        'outtmpl': os.path.join(target_dir, '%(title).120B-%(id)s.%(ext)s'),
        'restrictfilenames': False,
        'windowsfilenames': True,
        'overwrites': True,
        # DOWNI has no pause/resume: download straight to the final name, never a .part file.
        'nopart': True,
        'continuedl': False,
        'socket_timeout': 30,
        'retries': 3,
        'concurrent_fragment_downloads': 3,
        'progress_hooks': [_progress_hook] if progress_listener else [],
    }

    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(clean, download=True)
        try:
            path = ydl.prepare_filename(info)
        except Exception:
            path = None

        if not path or not os.path.exists(path):
            candidates = [
                os.path.join(target_dir, name)
                for name in os.listdir(target_dir)
                if os.path.isfile(os.path.join(target_dir, name)) and not name.endswith('.part')
            ]
            if candidates:
                path = max(candidates, key=os.path.getmtime)
            else:
                _fail('The site did not provide a downloadable public media file.')

    title = _safe_name((info.get('title') if info else '') or 'video')
    ext = os.path.splitext(path)[1].lower().lstrip('.') or ('m4a' if is_audio else 'mp4')
    filesize = os.path.getsize(path) if os.path.exists(path) else 0

    return json.dumps({
        'path': path,
        'title': title,
        'ext': ext,
        'filesize': filesize,
        'platform': platform,
    })


def download(url, target_dir, format_id='best', progress_listener=None):
    """Public entry point for a single download.

    YouTube (and increasingly other sites) now serve adaptive-only streams: there
    is no combined video+audio format any more, so every "best"/height selector
    that asks for one misses and yt-dlp raises "Requested format is not
    available". Those lanes now fall back to the SAME proven split+merge path
    that true 1080p uses (H.264 video-only + M4A audio-only, muxed on-device by
    Mp4Merger). No client spoofing, no forced UA, no new selectors.

    Cancel semantics are preserved: a cancelled listener makes _download_split
    return None, the original error is re-raised, and the Java layer stays
    silent because job.cancelled is set.
    """
    try:
        return _download_lanes(url, target_dir, format_id, progress_listener)
    except Exception as exc:
        if str(format_id).lower() in ('audio', 'mp3', 'm4a'):
            _fail(exc)
        if 'requested format is not available' not in str(exc).lower():
            _fail(exc)
        clean = _clean_url(url)
        if _detect_platform(clean) != 'youtube':
            _fail(exc)
        try:
            split = _download_split(clean, target_dir, _lane_height(format_id), progress_listener)
        except Exception as split_exc:
            # The split failed for its OWN reason (a refused request, a
            # timeout). That reason is the truth - not the stale "format"
            # error that sent us here.
            _fail(split_exc)
        if split:
            return json.dumps(split)
        _fail(exc)


def engine_info():
    """Diagnostic info for the Settings screen."""
    import yt_dlp
    import platform
    return json.dumps({
        'engine': 'DOWNI Pure Core (Chaquopy 3.11 + yt-dlp)',
        'yt_dlp_version': yt_dlp.version.__version__,
        'python': platform.python_version(),
    })


def playlist_inspect(url, max_items=25):
    """Flat-extract a playlist's entries for the batch downloader.

    Uses extract_flat so it is fast (no per-video network work). Returns None
    when the URL is not a playlist or nothing was found.
    """
    opts = {
        'quiet': True,
        'no_warnings': True,
        'js_runtimes': {},
        'ffmpeg_location': '/nonexistent/downi-no-ffmpeg',
        'skip_download': True,
        'extract_flat': 'in_playlist',
        'playlist_items': '1:%d' % max_items,
        'ca_certs': certifi.where(),
        'socket_timeout': 30,
        'retries': 2,
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception:
        return None
    if not info:
        return None
    entries = info.get('entries') or []
    items = []
    for entry in entries[:max_items]:
        if not entry:
            continue
        raw = entry.get('url') or entry.get('webpage_url') or ''
        if raw and not raw.startswith('http'):
            ie = (entry.get('ie_key') or info.get('extractor_key') or '').lower()
            if 'youtube' in ie or 'youtu' in (info.get('webpage_url') or '').lower():
                raw = 'https://www.youtube.com/watch?v=' + raw
        if raw.startswith('http'):
            items.append({
                'url': raw,
                'title': entry.get('title') or 'Video',
                'duration': int(entry.get('duration') or 0),
            })
    if not items:
        return None
    return {'count': len(items), 'items': items}


def _diag_dns(host, timeout=4.0):
    t0 = time.time()
    try:
        res = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
        ms = int((time.time() - t0) * 1000)
        ip = res[0][4][0] if res else host
        return True, ms, '', f"Resolved {ip}"
    except Exception as e:
        ms = int((time.time() - t0) * 1000)
        return False, ms, f"DNS failed: {e}", ''


def _diag_tls(host, timeout=5.0):
    t0 = time.time()
    try:
        ctx = ssl.create_default_context(cafile=certifi.where())
        with socket.create_connection((host, 443), timeout=timeout) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as ssock:
                ver = ssock.version()
                ms = int((time.time() - t0) * 1000)
                return True, ms, '', f"TLS OK ({ver})"
    except Exception as e:
        ms = int((time.time() - t0) * 1000)
        return False, ms, f"TLS failed: {e}", ''


def _diag_http(url, timeout=6.0, method='GET', headers=None):
    t0 = time.time()
    try:
        h = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'}
        if headers:
            h.update(headers)
        req = urllib.request.Request(url, headers=h, method=method)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            code = resp.getcode()
            ms = int((time.time() - t0) * 1000)
            if 300 <= code < 400:
                return True, ms, '', f"Reachable but restricted (HTTP {code})"
            return True, ms, '', f"HTTP {code}"
    except urllib.error.HTTPError as e:
        ms = int((time.time() - t0) * 1000)
        # 403 (Forbidden), 429 (Rate-limited), 3xx (Redirect) prove DNS, TLS, and TCP are healthy
        if e.code in (403, 429) or (300 <= e.code < 400):
            return True, ms, '', f"Reachable but restricted (HTTP {e.code})"
        return False, ms, f"HTTP Error {e.code}", f"HTTP {e.code}"
    except Exception as e:
        ms = int((time.time() - t0) * 1000)
        return False, ms, f"Connection failed: {e}", ''


def _run_with_hard_timeout(fn, hard_timeout_seconds=7.0):
    """Execute check in an isolated worker thread with a hard wall-clock timeout."""
    t0 = time.time()
    executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    future = executor.submit(fn)
    try:
        return future.result(timeout=hard_timeout_seconds)
    except concurrent.futures.TimeoutError:
        ms = int((time.time() - t0) * 1000)
        return False, ms, f"Timed out after {hard_timeout_seconds:.1f}s", ''
    except Exception as exc:
        ms = int((time.time() - t0) * 1000)
        return False, ms, str(exc), ''
    finally:
        executor.shutdown(wait=False, cancel_futures=True)


def diagnose():
    """Real and robust per-platform network diagnostics (DNS, TLS, reachability)."""
    import yt_dlp
    import platform
    results = []

    def check(name, fn, hard_timeout=7.0):
        ok, ms, err, detail = _run_with_hard_timeout(fn, hard_timeout_seconds=hard_timeout)
        results.append({
            'check': name,
            'ok': ok,
            'ms': ms,
            'error': err,
            'detail': detail
        })

    # 1. Internet reachability (general connectivity test)
    check('Internet reachability', lambda: _diag_http('https://connectivitycheck.gstatic.com/generate_204', timeout=5.0), hard_timeout=6.0)

    # 2. YouTube
    check('YouTube DNS', lambda: _diag_dns('www.youtube.com', timeout=4.0), hard_timeout=5.0)
    check('YouTube TLS', lambda: _diag_tls('www.youtube.com', timeout=4.0), hard_timeout=5.0)
    check('YouTube reachability', lambda: _diag_http('https://www.youtube.com/generate_204', timeout=5.0), hard_timeout=6.0)

    # 3. TikTok (official platform & direct domain)
    check('TikTok (tiktok.com) DNS', lambda: _diag_dns('www.tiktok.com', timeout=4.0), hard_timeout=5.0)
    check('TikTok (tiktok.com) TLS', lambda: _diag_tls('www.tiktok.com', timeout=4.0), hard_timeout=5.0)
    check('TikTok reachability (tiktok.com)', lambda: _diag_http('https://www.tiktok.com/', timeout=5.0, method='HEAD'), hard_timeout=6.0)

    # 4. TikTok helper service (tikwm.com API used for fast downloads)
    check('TikTok helper service DNS (tikwm.com)', lambda: _diag_dns('www.tikwm.com', timeout=4.0), hard_timeout=5.0)
    check('TikTok helper service TLS (tikwm.com)', lambda: _diag_tls('www.tikwm.com', timeout=4.0), hard_timeout=5.0)
    check('TikTok helper service (tikwm.com)', lambda: _diag_http('https://www.tikwm.com/api/?url=https://www.tiktok.com/@tiktok/video/7106594312292453675', timeout=6.0), hard_timeout=7.0)

    # 5. Instagram
    check('Instagram DNS', lambda: _diag_dns('www.instagram.com', timeout=4.0), hard_timeout=5.0)
    check('Instagram TLS', lambda: _diag_tls('www.instagram.com', timeout=4.0), hard_timeout=5.0)
    check('Instagram reachability', lambda: _diag_http('https://www.instagram.com/', timeout=5.0, method='HEAD'), hard_timeout=6.0)

    return json.dumps({
        'results': results,
        'yt_dlp': yt_dlp.version.__version__,
        'python': platform.python_version(),
    })
