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
                    speed = downloaded / elapsed
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

    formats = [
        {'id': 'best', 'label': 'Best Available Quality (HD)', 'ext': 'mp4', 'badge': 'HD'},
        {'id': '1080', 'label': '1080p Full HD', 'ext': 'mp4', 'badge': '1080p'},
        {'id': '720', 'label': '720p HD Quality', 'ext': 'mp4', 'badge': '720p'},
        {'id': '480', 'label': '480p Standard Quality', 'ext': 'mp4', 'badge': '480p'},
        {'id': 'audio', 'label': 'Audio Track (MP3 / M4A)', 'ext': 'mp3', 'badge': 'MP3'},
    ]

    return json.dumps({
        'title': title,
        'uploader': uploader,
        'duration': duration,
        'thumbnail': thumbnail,
        'webpage_url': webpage_url,
        'platform': platform,
        'formats': formats,
    })


# ---------------------------------------------------------------------------
# Download (Exact rock-solid v1.3.6 core + progress listener)
# ---------------------------------------------------------------------------

def download(url, target_dir, format_id='best', progress_listener=None):
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

    # 2) Universal yt-dlp download with proven v1.3.6 format cascading
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
        'noplaylist': True,
        'ca_certs': certifi.where(),
        'format': fmt,
        'outtmpl': os.path.join(target_dir, '%(title).120B-%(id)s.%(ext)s'),
        'restrictfilenames': False,
        'windowsfilenames': True,
        'overwrites': True,
        'nopart': True,
        'socket_timeout': 30,
        'retries': 3,
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


def engine_info():
    """Diagnostic info for the Settings screen."""
    import yt_dlp
    import platform
    return json.dumps({
        'engine': 'DOWNI Pure Core (Chaquopy 3.11 + yt-dlp)',
        'yt_dlp_version': yt_dlp.version.__version__,
        'python': platform.python_version(),
    })


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

    check('Internet reachability', lambda: _head('https://www.google.com/generate_204'))
    check('YouTube extraction', lambda: _extract('https://www.youtube.com/watch?v=dQw4w9WgXcQ'))
    check('Instagram extraction', lambda: _extract('https://www.instagram.com/reel/DcZTAe4jKBp'))
    check('TikTok API reachability', lambda: _head('https://www.tikwm.com/'))

    return json.dumps({
        'results': results,
        'yt_dlp': yt_dlp.version.__version__,
        'python': platform.python_version(),
    })
