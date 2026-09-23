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
from concurrent.futures import ThreadPoolExecutor
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
        'noplaylist': True,
        'ca_certs': certifi.where(),
        'outtmpl': os.path.join(target_dir, '%(title).120B-%(id)s.%(ext)s'),
        'windowsfilenames': True,
        'overwrites': True,
        'nopart': True,
        'socket_timeout': 30,
        'retries': 3,
        'concurrent_fragment_downloads': 3,
        # Chunked HTTP: yt-dlp opens a new ranged connection per 10 MB instead
        # of riding one throttled socket for the whole file (v3.1.2 speed).
        'http_chunk_size': 10 * 1024 * 1024,
    }
    video_path = None
    audio_path = None
    try:
        video_opts = dict(base)
        video_opts['format'] = (
            'bestvideo[ext=mp4][vcodec^=avc1][height<=%d]/'
            'bestvideo[ext=mp4][vcodec^=avc1][height<=%d]/'
            'bestvideo[height<=%d]' % (max_height, max_height, max_height)
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
        # Only "Requested format is not available" means this video genuinely has
        # no separate streams — that stays a soft None so callers can say so.
        # Everything else (cancel, network drops, throttling) must propagate with
        # its REAL message; swallowing it here used to misreport timeouts as
        # "no separate 1080p stream".
        if 'requested format is not available' in str(exc).lower():
            return None
        raise

    return {
        'merge': True,
        'video_path': video_path,
        'audio_path': audio_path,
        'title': _safe_name((v_info.get('title') if v_info else '') or 'video'),
        'ext': 'mp4',
        'platform': _detect_platform(url),
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

    # tikwm rate-limits burst traffic (several rapid shares in a row) with a
    # non-zero code or a dropped connection. Retry up to 3x with a short
    # backoff before letting the caller fail — this alone fixes most of the
    # "first grab works, second grab dies" reports.
    payload = None
    last_error = None
    for attempt in range(3):
        req = urllib.request.Request(
            api,
            data=data,
            headers={
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
                'Accept': 'application/json, text/javascript, */*; q=0.01',
                'Referer': 'https://www.tikwm.com/'
            }
        )
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                payload = json.loads(resp.read().decode('utf-8', errors='ignore'))
            if payload.get('code') == 0:
                return payload
            last_error = payload.get('msg') or 'TikTok API busy'
        except Exception as exc:
            last_error = str(exc)
        if attempt < 2:
            time.sleep(1.0 + attempt)

    if payload is not None:
        return payload
    raise RuntimeError('TikTok throttled that grab. Wait a few seconds, then retry. (%s)' % last_error)


def _download_segmented(stream_url, target_path, headers, total, progress_listener=None, segments=4):
    """Download one file over parallel HTTP range connections.

    When a CDN throttles PER CONNECTION, parallel segments multiply real
    speed. Each worker resumes its own byte range on a dropped connection
    (up to 2 retries) and a user cancel stops every worker at the next chunk.
    `stream_url` should already be the final post-redirect URL so workers
    don't each pay a redirect round trip.
    """
    SEGMENTS = max(1, int(segments))
    span = total // SEGMENTS
    bounds = []
    for i in range(SEGMENTS):
        seg_start = i * span
        seg_end = total - 1 if i == SEGMENTS - 1 else (seg_start + span - 1)
        bounds.append((seg_start, seg_end))

    downloaded = [0] * SEGMENTS
    started = time.time()

    # Pre-allocate so workers can seek+write disjoint ranges concurrently.
    with open(target_path, 'wb') as f:
        f.truncate(total)

    def _worker(idx, seg_start, seg_end):
        expected = seg_end - seg_start + 1
        got = 0
        attempt = 0
        while True:
            try:
                h = dict(headers)
                h['Range'] = 'bytes=%d-%d' % (seg_start + got, seg_end)
                req = urllib.request.Request(stream_url, headers=h)
                with urllib.request.urlopen(req, timeout=30) as resp:
                    with open(target_path, 'r+b') as f:
                        f.seek(seg_start + got)
                        while True:
                            if progress_listener and hasattr(progress_listener, 'isCancelled') and progress_listener.isCancelled():
                                raise RuntimeError("Download cancelled.")
                            chunk = resp.read(256 * 1024)
                            if not chunk:
                                break
                            f.write(chunk)
                            got += len(chunk)
                            downloaded[idx] = got
                if got < expected:
                    raise IOError('Segment ended early (%d/%d bytes)' % (got, expected))
                return
            except RuntimeError:
                raise
            except Exception:
                attempt += 1
                if attempt > 2:
                    raise
                time.sleep(1.0)

    with ThreadPoolExecutor(max_workers=SEGMENTS) as pool:
        futures = [pool.submit(_worker, i, s, e) for i, (s, e) in enumerate(bounds)]
        last_cb = 0.0
        while not all(f.done() for f in futures):
            now = time.time()
            if progress_listener and now - last_cb > 0.25:
                last_cb = now
                done_bytes = sum(downloaded)
                elapsed = max(0.001, now - started)
                speed = done_bytes / elapsed
                eta = int((total - done_bytes) / speed) if speed > 0 else 0
                pct = done_bytes / total * 100.0
                try:
                    progress_listener.onProgress(float(min(99.0, pct)), int(done_bytes), int(total), float(speed), int(eta))
                except Exception:
                    pass
            time.sleep(0.2)
        # Re-raise the first worker failure (cancel included).
        for f in futures:
            f.result()

    if progress_listener:
        try:
            progress_listener.onProgress(100.0, total, total, 0.0, 0)
        except Exception:
            pass


def _download_stream_direct(stream_url, target_path, referer='', progress_listener=None):
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': '*/*',
    }
    if referer:
        headers['Referer'] = referer

    # Probe ONCE: a 1-byte range request that (a) follows tikwm's 302 to the
    # real TikTok CDN URL and (b) reveals the true file size. Every segment
    # below then talks straight to the CDN — v3.1.2 made each of the 4
    # segments re-pay the redirect + TLS handshake, which burned ~0.5-1s per
    # segment before any bytes moved.
    total = 0
    final_url = stream_url
    try:
        probe_headers = dict(headers)
        probe_headers['Range'] = 'bytes=0-0'
        probe = urllib.request.Request(stream_url, headers=probe_headers)
        with urllib.request.urlopen(probe, timeout=20) as resp:
            final_url = resp.geturl() or stream_url
            if getattr(resp, 'status', 200) == 206:
                content_range = resp.headers.get('Content-Range') or ''
                if '/' in content_range:
                    total = int(content_range.split('/')[-1])
    except Exception:
        total = 0

    if total > 512 * 1024:
        # Size-adaptive parallelism: tiny clips barely benefit from many
        # connections (handshake cost dominates), big files want more pipes
        # to outrun per-connection CDN throttling.
        if total < 8 * 1024 * 1024:
            segments = 2
        elif total < 40 * 1024 * 1024:
            segments = 4
        else:
            segments = 6
        try:
            _download_segmented(final_url, target_path, headers, total, progress_listener, segments)
            return
        except RuntimeError:
            raise  # user cancel — never fall back or retry
        except Exception:
            try:
                if os.path.exists(target_path):
                    os.remove(target_path)
            except OSError:
                pass
            total = 0  # fall through to single-stream

    req = urllib.request.Request(final_url, headers=headers)
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
    # The tikwm CDN occasionally drops a stream mid-read; retry once with a
    # fresh connection (deleting the partial file) before giving up. A user
    # cancel ("Download cancelled.") is a RuntimeError and passes straight
    # through — retries must never resurrect a cancelled job.
    for attempt in range(2):
        try:
            _download_stream_direct(stream_url, out_path, 'https://www.tiktok.com/', progress_listener)
            break
        except RuntimeError:
            raise
        except Exception:
            try:
                if os.path.exists(out_path):
                    os.remove(out_path)
            except OSError:
                pass
            if attempt == 1:
                raise
            time.sleep(1.5)
    filesize = os.path.getsize(out_path) if os.path.exists(out_path) else 0
    if filesize <= 0:
        try:
            if os.path.exists(out_path):
                os.remove(out_path)
        except OSError:
            pass
        raise RuntimeError('TikTok returned an empty media stream. Wait a few seconds, then retry.')
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
            # tikwm answered but refused the lookup (throttled / unknown post) —
            # same fast-fail, never the slow yt-dlp crawl.
            return json.dumps({
                'error': 'TikTok is throttling lookups right now. Wait a few seconds, then retry.'
            })
        except Exception as exc:
            # No silent fall-through to the yt-dlp lane for TikTok inspects
            # either: it crawls against the bot wall for minutes and the user
            # just stares at "Scanning qualities…". Fail fast and honestly (B9).
            return json.dumps({
                'error': 'TikTok is throttling lookups right now. Wait a few seconds, then retry.'
            })

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
        note = ('YouTube serves HD as separate video and audio — DOWNI merges them on your '
                'device, so HD grabs take a little longer. 360p Data Saver is the quick single-file grab.')
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
        except Exception as exc:
            # No silent fall-through to the yt-dlp lane for TikTok: that path
            # crawls for minutes against TikTok's bot wall and usually dies
            # with boilerplate anyway. After the tikwm retries above, fail
            # fast with an honest, actionable message instead.
            raise RuntimeError(
                'TikTok throttled that grab. Wait a few seconds, then retry. (%s)' % exc
            )

    # 2) YouTube HD lanes: split + on-device merge. YouTube only ever serves ONE
    #    combined stream with audio — format 18 at 360p — so a combined selector
    #    for best/720/480 resolves it and silently hands the user 360p no matter
    #    what they picked (verified live against yt-dlp 2026.08.19 — A1). Every
    #    lane above 360p therefore goes straight to the same split+merge path
    #    the 1080 lane has used since v3.0.3, capped at the lane's height. The
    #    360 lane keeps the single-file combined grab: it IS the cheap one.
    if platform == 'youtube' and not is_audio and str(format_id) != '360':
        split = _download_split(clean, target_dir, _lane_height(format_id), progress_listener)
        if split:
            return json.dumps(split)
        raise RuntimeError('This video has no separate streams to merge — pick 360p Data Saver or try again.')

    # 3) Universal yt-dlp download — every lane demands a COMBINED stream
    #    (video+audio in one file). The old trailing '/best' could silently match
    #    a video-only format, which is how YouTube 720p/480p/best shipped muted
    #    360p-or-worse files and Reddit/X delivered silent videos (A1/B10). With
    #    no bare fallback, adaptive-only sites raise "Requested format is not
    #    available" here and download() routes them to split+merge instead.
    video_formats = {
        '1080': 'best[height<=1080][ext=mp4][vcodec!=none][acodec!=none]/best[height<=1080][vcodec!=none][acodec!=none]',
        '720': 'best[height<=720][ext=mp4][vcodec!=none][acodec!=none]/best[height<=720][vcodec!=none][acodec!=none]',
        '480': 'best[height<=480][ext=mp4][vcodec!=none][acodec!=none]/best[height<=480][vcodec!=none][acodec!=none]',
        '360': 'best[height<=360][ext=mp4][vcodec!=none][acodec!=none]/best[height<=360][vcodec!=none][acodec!=none]',
        'best': 'best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]',
    }
    fmt = 'bestaudio[ext=m4a]/bestaudio/best' if is_audio else video_formats.get(str(format_id), video_formats['best'])

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
        'concurrent_fragment_downloads': 3,
        'http_chunk_size': 10 * 1024 * 1024,  # v3.1.2 speed: ranged 10 MB chunks
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

    Every video lane selector demands a combined video+audio stream. YouTube
    (and increasingly other sites — Reddit, X) serve adaptive-only streams, so
    those selectors miss and yt-dlp raises "Requested format is not available".
    Any lane on ANY platform then falls back to the SAME proven split+merge
    path that true 1080p uses (H.264 video-only + M4A audio-only, muxed
    on-device by Mp4Merger), capped at the lane's height. No client spoofing,
    no forced UA, no new selectors.

    Cancel semantics are preserved: a cancelled listener makes _download_split
    return None, an error is raised, and the Java layer stays silent because
    job.cancelled is set.
    """
    try:
        return _download_lanes(url, target_dir, format_id, progress_listener)
    except Exception as exc:
        if str(format_id).lower() in ('audio', 'mp3', 'm4a'):
            raise
        if 'requested format is not available' not in str(exc).lower():
            raise
        clean = _clean_url(url)
        split = _download_split(clean, target_dir, _lane_height(format_id), progress_listener)
        if split:
            return json.dumps(split)
        raise RuntimeError(
            'Could not fetch this video with audio in that quality — pick another quality.'
        )


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
        # Mirror the real lane selectors (combined video+audio only) so the
        # diagnostic exercises exactly what a download attempts.
        combined = 'best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]'
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
