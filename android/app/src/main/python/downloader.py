"""DOWNI on-device download engine — Built on the proven v1.3.6 core.

100% on-device local execution via Chaquopy + yt-dlp.
No cloud relays, no external servers, no account login required.
"""

import json
import os
import re
import time
import urllib.parse
import urllib.request
import certifi
from yt_dlp import YoutubeDL


class PausedError(Exception):
    """Raised when the Java layer asks the transfer to pause.

    Distinct from cancellation: a pause KEEPS the partial file (and its .part for yt-dlp
    lanes) so a later resume call continues from bytes on disk. Java detects this class
    name in the raised exception and records the job as paused instead of failed.
    """
    pass


def _safe_name(value):
    """Sanitize filename to valid characters within 120 bytes."""
    if not value:
        return 'video'
    value = re.sub(r'[\\/:*?"<>|\r\n\t]+', ' ', value)
    value = re.sub(r'\s+', ' ', value).strip(' .')
    return (value or 'video')[:120]


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
            if hasattr(progress_listener, 'isPaused') and progress_listener.isPaused():
                raise PausedError("Download paused.")
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
    supports_pause = bool(progress_listener) and hasattr(progress_listener, 'isPaused')
    base = {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'ca_certs': certifi.where(),
        'outtmpl': os.path.join(target_dir, '%(title).120B-%(id)s.%(ext)s'),
        'windowsfilenames': True,
        'overwrites': True,
        # Pause/resume (D3): headless jobs (Fetcher/Drop) download into .part files so a
        # pause keeps the bytes and a later call continues them. In-app lanes keep the
        # original behaviour — the engine change is gated, never global.
        'nopart': not supports_pause,
        'continuedl': supports_pause,
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
    except PausedError:
        # A pause must keep BOTH partials (video + audio) for the resume pass — fall through
        # to nothing only for genuine failures.
        raise
    except Exception:
        for leftover in (video_path, audio_path):
            if leftover:
                try:
                    os.remove(leftover)
                except OSError:
                    pass
        return None

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
    # D3: byte-continuation. A partial file left by a pause becomes the resume offset — the
    # request carries a Range header, the file opens in append mode, and progress reflects
    # offset+transfer. A server that ignores Range (plain 200) restarts clean, transparently.
    offset = 0
    if os.path.exists(target_path):
        try:
            offset = os.path.getsize(target_path)
        except OSError:
            offset = 0
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': '*/*',
    }
    if offset > 0:
        headers['Range'] = 'bytes=%d-' % offset
    if referer:
        headers['Referer'] = referer
    req = urllib.request.Request(stream_url, headers=headers)
    with urllib.request.urlopen(req, timeout=35) as resp:
        status = getattr(resp, 'status', 200) or 200
        if offset > 0 and status != 206:
            offset = 0                       # Range unsupported - restart clean, honestly
        total = (int(resp.headers.get('Content-Length') or 0) + offset) if offset > 0 else int(resp.headers.get('Content-Length') or 0)
        downloaded = offset
        last_cb = 0.0
        start = time.time()
        with open(target_path, 'ab' if offset > 0 else 'wb') as f:
            while True:
                if progress_listener and hasattr(progress_listener, 'isCancelled') and progress_listener.isCancelled():
                    raise RuntimeError("Download cancelled.")
                if progress_listener and hasattr(progress_listener, 'isPaused') and progress_listener.isPaused():
                    raise PausedError("Download paused.")
                chunk = resp.read(128 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                now = time.time()
                if progress_listener and (now - last_cb > 0.25):
                    last_cb = now
                    elapsed = max(0.001, now - start)
                    speed = (downloaded - offset) / elapsed if downloaded > offset else 0.0
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
        raise RuntimeError(data.get('msg') or 'TikTok video not available')
    item = data.get('data') or {}
    stream_url = item.get('music') if is_audio else (item.get('hdplay') or item.get('play') or item.get('wmplay'))
    if not stream_url:
        raise RuntimeError('No downloadable stream found in TikTok response')
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
        'skip_download': True,
        'noplaylist': True,
        'ca_certs': certifi.where(),
    }
    info = None
    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(clean, download=False)
    except Exception:
        info = None

    if info:
        title = _safe_name(info.get('title') or 'video')
        uploader = info.get('uploader') or info.get('channel') or platform.capitalize()
        duration = int(info.get('duration') or 0)
        thumbnail = info.get('thumbnail') or ''
        webpage_url = info.get('webpage_url') or clean
    else:
        title = _safe_name(platform.capitalize() + ' Video')
        uploader = platform.capitalize()
        duration = 0
        thumbnail = ''
        webpage_url = clean

    # Honest, platform-aware quality choices. No ffmpeg is bundled on-device, so
    # every download is a single muxed (video+audio) stream. YouTube only serves
    # muxed streams up to 720p, so advertising 1080p there would be a lie.
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
        formats = [
            {'id': 'best', 'label': 'Best Available Quality (HD)', 'ext': 'mp4', 'badge': 'HD'},
            {'id': '1080', 'label': 'Up to 1080p Full HD', 'ext': 'mp4', 'badge': '1080p'},
            {'id': '720', 'label': '720p HD Quality', 'ext': 'mp4', 'badge': '720p'},
            {'id': '480', 'label': '480p Standard Quality', 'ext': 'mp4', 'badge': '480p'},
            {'id': 'audio', 'label': 'Audio Track (MP3 / M4A)', 'ext': 'mp3', 'badge': 'MP3'},
        ]
        note = ''

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
            raise
        except Exception:
            pass

    # 2) Split + on-device merge — true 1080p where only DASH streams exist
    #    (video and audio arrive separately and are muxed natively in Java).
    if platform == 'youtube' and str(format_id) == '1080':
        split = _download_split(clean, target_dir, 1080, progress_listener)
        if split:
            return json.dumps(split)
        raise RuntimeError('This video has no separate 1080p stream — pick 720p HD or Best Available.')

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
            if hasattr(progress_listener, 'isPaused') and progress_listener.isPaused():
                raise PausedError("Download paused.")
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

    supports_pause = bool(progress_listener) and hasattr(progress_listener, 'isPaused')
    options = {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'ca_certs': certifi.where(),
        'format': fmt,
        'outtmpl': os.path.join(target_dir, '%(title).120B-%(id)s.%(ext)s'),
        'restrictfilenames': False,
        'windowsfilenames': True,
        'overwrites': True,
        # D3: headless jobs download into .part and resume from them; in-app lanes unchanged.
        'nopart': not supports_pause,
        'continuedl': supports_pause,
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
                raise RuntimeError('The site did not provide a downloadable public media file.')

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
    except PausedError:
        raise                                  # a pause must never trigger a lane fallback
    except Exception as exc:
        if str(format_id).lower() in ('audio', 'mp3', 'm4a'):
            raise
        if 'requested format is not available' not in str(exc).lower():
            raise
        clean = _clean_url(url)
        if _detect_platform(clean) != 'youtube':
            raise
        split = _download_split(clean, target_dir, _lane_height(format_id), progress_listener)
        if split:
            return json.dumps(split)
        raise


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


def diagnose():
    """Per-platform network diagnostics to check device connectivity."""
    import yt_dlp
    import platform
    results = []

    def check(name, fn):
        t0 = time.time()
        try:
            fn()
            results.append({'check': name, 'ok': True, 'ms': int((time.time() - t0) * 1000), 'error': ''})
        except Exception as e:
            results.append({'check': name, 'ok': False, 'ms': int((time.time() - t0) * 1000), 'error': str(e)[:220]})

    def _head(u):
        req = urllib.request.Request(u, method='HEAD', headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'})
        urllib.request.urlopen(req, timeout=10).read(0)

    def _extract(u):
        opts = {'quiet': True, 'no_warnings': True, 'skip_download': True, 'ca_certs': certifi.where()}
        with YoutubeDL(opts) as ydl:
            ydl.extract_info(u, download=False)

    def _check_instagram():
        # The probe reel can be deleted or made private at any time — a dead
        # probe must not report a working network as broken. If extraction
        # alone fails, fall back to plain reachability before declaring defeat.
        try:
            _extract('https://www.instagram.com/reel/DcZTAe4jKBp')
        except Exception:
            _head('https://www.instagram.com/')

    def _youtube_downloadable():
        """Probe what a download actually needs, not just metadata.

        Metadata extraction alone cannot tell a working network from a broken
        download path: YouTube now serves adaptive-only streams, so every
        combined selector misses and the split+merge lane has to carry it
        (v3.0.3). Engine health has to fail when neither can resolve, or it
        reports green while every video lane is dead.
        """
        u = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'
        combined = 'best[ext=mp4][vcodec!=none]/best[vcodec!=none]/best'
        try:
            with YoutubeDL({'quiet': True, 'no_warnings': True, 'skip_download': True,
                            'ca_certs': certifi.where(), 'format': combined}) as ydl:
                ydl.extract_info(u, download=False)
            return
        except Exception:
            pass
        for selector in ('bestvideo[ext=mp4][vcodec^=avc1][height<=1080]/bestvideo[height<=1080]',
                         'bestaudio[ext=m4a]/bestaudio'):
            opts = {'quiet': True, 'no_warnings': True, 'skip_download': True,
                    'ca_certs': certifi.where(), 'format': selector}
            with YoutubeDL(opts) as ydl:
                ydl.extract_info(u, download=False)

    check('Internet reachability', lambda: _head('https://www.google.com/generate_204'))
    check('YouTube extraction', _youtube_downloadable)
    check('Instagram extraction', _check_instagram)
    # Exercise the REAL TikTok path (the tikwm API the engine downloads through),
    # not a bare HEAD to the site root — tikwm 403s plain HEAD requests, which
    # made working downloads show up as a failed diagnostic.
    check('TikTok API reachability', lambda: _fetch_tiktok_data('https://www.tiktok.com/@tiktok/video/7106594312292453675'))

    return json.dumps({
        'results': results,
        'yt_dlp': yt_dlp.version.__version__,
        'python': platform.python_version(),
    })
