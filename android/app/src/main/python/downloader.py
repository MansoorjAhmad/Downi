"""On-device public-video downloader used by OmniDownloader Android bridge.

This uses yt-dlp locally via Chaquopy, augmented with direct high-speed stream extractors
for platforms like TikTok (HD without watermark), universal single-stream muxed formats,
multi-tier download fallbacks, and bulletproof SSL error protection.
No ffmpeg executable is required on device.
"""

import json
import os
import re
import ssl
import time
import urllib.parse
import urllib.request

# Ensure CA bundle and unverified SSL context are active globally
try:
    import certifi
    os.environ['SSL_CERT_FILE'] = certifi.where()
    os.environ['REQUESTS_CA_BUNDLE'] = certifi.where()
except Exception:
    pass

try:
    ssl._create_default_https_context = ssl._create_unverified_context
except Exception:
    pass

from yt_dlp import YoutubeDL


def _get_ssl_context():
    try:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    except Exception:
        return None


def _safe_name(value):
    if not value:
        return 'video'
    value = re.sub(r'[\\/:*?"<>|\r\n\t]+', ' ', value)
    value = re.sub(r'[^\x20-\x7E]', '', value)
    value = re.sub(r'\s+', ' ', value).strip(' .')
    return (value or 'video')[:80]


def _clean_url(url):
    if not url:
        return ""
    match = re.search(r'https?://[^\s<>"\']+', url)
    if match:
        clean = match.group(0).rstrip('.,;:!?)]}')
    else:
        clean = url.strip()

    if any(short in clean for short in ['vt.tiktok.com', 'vm.tiktok.com', 'tiktok.com/t/']):
        try:
            req = urllib.request.Request(
                clean,
                headers={'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1'}
            )
            with urllib.request.urlopen(req, timeout=8, context=_get_ssl_context()) as response:
                clean = response.geturl()
        except Exception:
            pass

    clean = re.sub(r'([?&])(igsh|si|utm_[^&=]+|feature)=[^&]+', '', clean)
    clean = re.sub(r'[?&]$', '', clean)
    return clean


def _detect_platform(url):
    u = (url or '').lower()
    if 'youtube' in u or 'youtu.be' in u:
        return 'youtube'
    if 'tiktok' in u:
        return 'tiktok'
    if 'instagram' in u:
        return 'instagram'
    if 'facebook' in u or 'fb.watch' in u:
        return 'facebook'
    if 'twitter' in u or 'x.com' in u:
        return 'twitter'
    if 'reddit' in u:
        return 'reddit'
    if 'pinterest' in u:
        return 'pinterest'
    return 'other'


def _download_stream_with_progress(stream_url, target_path, referer='', progress_listener=None):
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': '*/*',
    }
    if referer:
        headers['Referer'] = referer

    req = urllib.request.Request(stream_url, headers=headers)
    with urllib.request.urlopen(req, timeout=35, context=_get_ssl_context()) as resp:
        total_size = int(resp.headers.get('Content-Length') or 0)
        downloaded = 0
        last_cb = 0.0
        start_time = time.time()
        chunk_size = 64 * 1024

        with open(target_path, 'wb') as f:
            while True:
                chunk = resp.read(chunk_size)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                now = time.time()
                if progress_listener and (now - last_cb > 0.25):
                    last_cb = now
                    elapsed = max(0.001, now - start_time)
                    speed = downloaded / elapsed
                    eta = int((total_size - downloaded) / speed) if (total_size > downloaded and speed > 0) else 0
                    pct = (downloaded / total_size * 100.0) if total_size > 0 else 50.0
                    try:
                        progress_listener.onProgress(float(min(99.0, pct)), int(downloaded), int(total_size), float(speed), int(eta))
                    except Exception:
                        pass

    if progress_listener:
        try:
            progress_listener.onProgress(100.0, downloaded, total_size, 0.0, 0)
        except Exception:
            pass


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
    with urllib.request.urlopen(req, timeout=15, context=_get_ssl_context()) as resp:
        return json.loads(resp.read().decode('utf-8', errors='ignore'))


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

    _download_stream_with_progress(stream_url, out_path, 'https://www.tiktok.com/', progress_listener)

    filesize = os.path.getsize(out_path) if os.path.exists(out_path) else 0
    return {
        'path': out_path,
        'title': title,
        'ext': ext,
        'filesize': filesize,
        'platform': 'tiktok'
    }


def inspect(url):
    clean = _clean_url(url)
    platform = _detect_platform(clean)

    # TikTok fast direct inspection
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

    options = {
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'noplaylist': True,
        'nocheckcertificate': True,
        'http_headers': {
            'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36',
            'Accept-Language': 'en-US,en;q=0.9',
        },
    }

    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(clean, download=False)
            title = _safe_name(info.get('title'))
            uploader = info.get('uploader') or info.get('channel') or platform.capitalize()
            duration = int(info.get('duration') or 0)
            thumbnail = info.get('thumbnail') or ''
    except Exception:
        # Resilient fallback: don't block user if metadata extraction is throttled
        title = _safe_name(platform.capitalize() + ' Media')
        uploader = platform.capitalize()
        duration = 0
        thumbnail = ''

    formats = [
        {'id': 'best', 'label': 'Best Available Quality', 'ext': 'mp4', 'badge': 'Best'},
        {'id': '1080', 'label': '1080p Full HD', 'ext': 'mp4', 'badge': '1080p'},
        {'id': '720', 'label': '720p HD Quality', 'ext': 'mp4', 'badge': '720p'},
        {'id': '480', 'label': '480p Standard Quality', 'ext': 'mp4', 'badge': '480p'},
        {'id': '360', 'label': '360p Data Saver', 'ext': 'mp4', 'badge': '360p'},
        {'id': 'audio', 'label': 'Audio Only (MP3)', 'ext': 'mp3', 'badge': 'Audio'}
    ]

    return json.dumps({
        'title': title,
        'uploader': uploader,
        'duration': duration,
        'thumbnail': thumbnail,
        'webpage_url': clean,
        'platform': platform,
        'formats': formats
    })


def download(url, target_dir, format_id="best", progress_listener=None):
    os.makedirs(target_dir, exist_ok=True)
    clean = _clean_url(url)
    platform = _detect_platform(clean)
    is_audio = str(format_id).lower() in ("audio", "mp3", "m4a")

    # If TikTok, attempt direct fast extraction first
    if platform == 'tiktok':
        try:
            result = _download_tiktok_direct(clean, target_dir, is_audio, progress_listener)
            return json.dumps(result)
        except Exception:
            pass

    # Universal muxed single-stream format selector (guaranteed to never require ffmpeg)
    if is_audio:
        selected_format = "ba[ext=m4a]/ba/b/best"
    elif str(format_id).lower() in ("1080", "1080p"):
        selected_format = "b[height<=1080][ext=mp4]/b[height<=1080]/b/best"
    elif str(format_id).lower() in ("720", "720p"):
        selected_format = "b[height<=720][ext=mp4]/b[height<=720]/b/best"
    elif str(format_id).lower() in ("480", "480p"):
        selected_format = "b[height<=480][ext=mp4]/b[height<=480]/b/best"
    elif str(format_id).lower() in ("360", "360p"):
        selected_format = "b[height<=360][ext=mp4]/b[height<=360]/b/best"
    else:
        selected_format = "b[ext=mp4]/b/best"

    last_callback_time = [0.0]

    def _progress_hook(d):
        if progress_listener is None:
            return
        status = d.get('status')
        if status == 'downloading':
            now = time.time()
            if now - last_callback_time[0] < 0.25:
                return
            last_callback_time[0] = now

            downloaded = d.get('downloaded_bytes') or 0
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            speed = d.get('speed') or 0.0
            eta = d.get('eta') or 0
            percent = (float(downloaded) / float(total) * 100.0) if total > 0 else 0.0

            try:
                progress_listener.onProgress(
                    float(min(99.0, max(0.0, percent))),
                    int(downloaded),
                    int(total),
                    float(speed or 0.0),
                    int(eta or 0)
                )
            except Exception:
                pass
        elif status == 'finished':
            try:
                progress_listener.onProgress(100.0, 0, 0, 0.0, 0)
            except Exception:
                pass

    # Safe outtmpl using %(id)s to eliminate any Python format character errors
    safe_outtmpl = os.path.join(target_dir, '%(id)s.%(ext)s')

    options = {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'nocheckcertificate': True,
        'format': selected_format,
        'outtmpl': safe_outtmpl,
        'restrictfilenames': True,
        'windowsfilenames': True,
        'overwrites': True,
        'nopart': False,
        'progress_hooks': [_progress_hook],
        'http_headers': {
            'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36',
            'Accept-Language': 'en-US,en;q=0.9',
        },
    }

    info = None
    # Tier 1: Try selected format
    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(clean, download=True)
    except Exception as e1:
        # Tier 2: Universal pre-muxed single stream
        try:
            options['format'] = 'b/best'
            with YoutubeDL(options) as ydl:
                info = ydl.extract_info(clean, download=True)
        except Exception as e2:
            # Tier 3: Universal best format
            try:
                options['format'] = 'best'
                with YoutubeDL(options) as ydl:
                    info = ydl.extract_info(clean, download=True)
            except Exception as e3:
                err_msg = str(e3) or str(e2) or str(e1)
                raise RuntimeError(f"Could not download stream: {err_msg}")

    # Determine downloaded file path
    path = None
    try:
        with YoutubeDL(options) as ydl:
            path = ydl.prepare_filename(info)
    except Exception:
        pass

    if not path or not os.path.exists(path):
        candidates = [
            os.path.join(target_dir, name) for name in os.listdir(target_dir)
            if os.path.isfile(os.path.join(target_dir, name)) and not name.endswith('.part')
        ]
        if candidates:
            path = max(candidates, key=os.path.getmtime)

    if not path or not os.path.exists(path):
        raise RuntimeError('Media file was not created on storage. Check permissions or internet connection.')

    filesize = os.path.getsize(path) if os.path.exists(path) else 0
    raw_title = info.get('title') if info else 'Omni Video'

    return json.dumps({
        'path': path,
        'title': _safe_name(raw_title),
        'ext': os.path.splitext(path)[1].lower().lstrip('.') or ('m4a' if is_audio else 'mp4'),
        'filesize': filesize,
        'platform': platform,
    })
