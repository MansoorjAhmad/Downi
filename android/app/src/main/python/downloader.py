"""DOWNI on-device download engine — Built on the proven v1.3.6 core.

100% on-device local execution via Chaquopy + yt-dlp.
No cloud relays, no external servers, no account login required.
"""

import copy
import json
import os
import concurrent.futures
import socket
import threading
import urllib.error
import ssl
import re
import time
import urllib.parse
import urllib.request
import certifi
from yt_dlp import YoutubeDL

import audio_tagger

_CACHE_LOCK = threading.Lock()
_SSL_CTX = None
_CLEAN_URL_CACHE = {}   # short_url -> (monotonic_ts, expanded_url)
_TIKWM_CACHE = {}       # clean_url -> (monotonic_ts, parsed_json)
_EXTRACT_CACHE = {}     # clean_url -> (monotonic_ts, sanitized_info_dict)
_CACHE_MAX = 24
_CLEAN_TTL_SEC = 300.0
# 15 minutes, not 2.5. Every extraction is an anonymous request, and Instagram
# limits those per network. The inspector extracts a link, then the grab used
# to extract it AGAIN once this cache had expired - two requests for one tap,
# which is exactly what turns a short limit into "you have exceeded the
# rate-limit".
_INFO_TTL_SEC = 900.0
# A grab may only reuse the inspector's extraction while its signed media URLs
# are certainly still fresh. Past this, the grab extracts once - exactly like
# v3.8.0 did - instead of retrying a dead URL and then re-requesting the page.
_INFO_REUSE_SEC = 120.0


def clear_caches():
    """Clear in-memory extraction and URL expansion caches."""
    with _CACHE_LOCK:
        _CLEAN_URL_CACHE.clear()
        _TIKWM_CACHE.clear()
        _EXTRACT_CACHE.clear()


def _prune_cache_locked(cache_dict, ttl_sec):
    now = time.monotonic()
    expired = [k for k, (ts, _) in cache_dict.items() if (now - ts) > ttl_sec]
    for k in expired:
        cache_dict.pop(k, None)
    while len(cache_dict) > _CACHE_MAX:
        oldest_key = min(cache_dict.items(), key=lambda kv: kv[1][0])[0]
        cache_dict.pop(oldest_key, None)


def _ssl_context():
    """Return a cached SSL context backed by certifi's CA bundle."""
    global _SSL_CTX
    if _SSL_CTX is None:
        with _CACHE_LOCK:
            if _SSL_CTX is None:
                _SSL_CTX = ssl.create_default_context(cafile=certifi.where())
    return _SSL_CTX


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

    # Expand short share links if needed (with short TTL memoization)
    if any(short in clean for short in ('vt.tiktok.com', 'vm.tiktok.com', 'tiktok.com/t/', 'fb.watch', 'facebook.com/share')):
        now = time.monotonic()
        with _CACHE_LOCK:
            cached = _CLEAN_URL_CACHE.get(clean)
            if cached and (now - cached[0]) <= _CLEAN_TTL_SEC:
                return cached[1]
        try:
            req = urllib.request.Request(
                clean,
                headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'}
            )
            with urllib.request.urlopen(req, timeout=10, context=_ssl_context()) as response:
                expanded = response.geturl()
                if expanded:
                    with _CACHE_LOCK:
                        _CLEAN_URL_CACHE[clean] = (time.monotonic(), expanded)
                        _prune_cache_locked(_CLEAN_URL_CACHE, _CLEAN_TTL_SEC)
                    clean = expanded
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
# 'best' caps at 2160 when the device supports VP9 (API >= 29) or AV1 (API >= 34),
# and caps at 1080 (H.264 + M4A) on older devices (API < 29).
_LANE_MAX_HEIGHTS = {
    '2160': 2160,
    '4k': 2160,
    '1440': 1440,
    '1080': 1080,
    '720': 720,
    '480': 480,
    '360': 360,
}


def _coerce_sdk_int(sdk_int):
    try:
        return int(sdk_int) if sdk_int is not None else 34
    except (TypeError, ValueError):
        return 34


def _lane_height(format_id, sdk_int=34):
    """Height cap for the merge fallback of a given quality lane."""
    key = str(format_id).lower()
    if key == 'best':
        return 2160 if _coerce_sdk_int(sdk_int) >= 29 else 1080
    return _LANE_MAX_HEIGHTS.get(key, 1080)


def _is_supported_vcodec(vcodec, sdk_int, allow_legacy=True):
    """Check whether a video codec is supported on the given Android API level."""
    vc = str(vcodec or '').lower()
    if not vc or vc == 'none':
        return False
    if vc.startswith(('avc1', 'h264')):
        return True
    if vc.startswith(('vp9', 'vp09')):
        return sdk_int >= 29
    if vc.startswith('av01'):
        return sdk_int >= 34
    return allow_legacy


def _is_aac_audio(f):
    ac = str(f.get('acodec') or '').lower()
    ext = str(f.get('ext') or '').lower()
    return f.get('vcodec') in (None, 'none') and (ac.startswith('mp4a') or ext == 'm4a')


def _is_mp4_muxable_vcodec(vcodec):
    """Return True if vcodec can be muxed alongside AAC audio in Android MediaMuxer MP4."""
    vc = str(vcodec or '').lower()
    return vc.startswith(('avc1', 'h264', 'hvc1', 'hev1', 'mp4v'))


def _extract_subtitle_langs(info):
    """Return (has_subtitles, top_langs) from yt-dlp info dict."""
    if not info:
        return False, []
    manual = info.get('subtitles') or {}
    auto = info.get('automatic_captions') or {}
    orig_lang = str(info.get('language') or '').strip()

    all_keys = list(manual.keys()) + list(auto.keys())
    clean_tag_re = re.compile(r'^[a-z]{2,3}(?:-[A-Za-z0-9]{2,4})?$')

    ordered = []
    seen = set()

    def _add(code):
        c = str(code or '').strip()
        if not c or c.lower() == 'live_chat' or c in seen:
            return
        seen.add(c)
        ordered.append(c)

    if orig_lang and clean_tag_re.match(orig_lang) and (orig_lang in manual or orig_lang in auto):
        _add(orig_lang)

    # Prefer bare English and clean English variants next so multi-track videos never default to obscure tags.
    for target in ('en', 'en-orig', 'en-US', 'en-us', 'en-GB', 'en-gb'):
        if target in manual or target in auto:
            _add(target)
    for k in all_keys:
        ks = str(k or '').strip()
        if ks.lower().startswith('en-') and clean_tag_re.match(ks):
            _add(ks)
    for k in manual.keys():
        if clean_tag_re.match(str(k or '').strip()):
            _add(k)
    for k in auto.keys():
        if clean_tag_re.match(str(k or '').strip()):
            _add(k)
    for k in all_keys:
        _add(k)

    langs = ordered[:8]
    return bool(langs), langs


def _parse_opts_json(opts_json):
    if not opts_json:
        return {}
    if isinstance(opts_json, dict):
        return dict(opts_json)
    try:
        parsed = json.loads(str(opts_json))
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}


def _subtitle_langs_from_opts(opts):
    langs = opts.get('subtitle_langs') or opts.get('subtitleslangs') or ['en']
    if isinstance(langs, str):
        langs = [x.strip() for x in langs.split(',') if x.strip()] or ['en']
    return list(langs)


def _collect_subtitle_paths(target_dir, before_files):
    if not target_dir or not os.path.isdir(target_dir):
        return []
    found = []
    for name in sorted(os.listdir(target_dir)):
        full = os.path.join(target_dir, name)
        if full not in before_files and os.path.isfile(full) and name.lower().endswith(('.srt', '.vtt')):
            found.append(full)
    return found


# ---------------------------------------------------------------------------
# Split download (DASH video + audio) for on-device muxing — no ffmpeg needed
# ---------------------------------------------------------------------------

def _is_cancelled(progress_listener):
    return bool(progress_listener and hasattr(progress_listener, 'isCancelled') and progress_listener.isCancelled())


def _sanitize_info_for_cache(info):
    """Copy an extracted info dict stripping stale per-selection fields so it can be reused safely."""
    if not isinstance(info, dict):
        return None
    cloned = copy.deepcopy(info)
    for k in ('url', 'requested_formats', 'requested_downloads', 'format', 'format_id', 'ext'):
        cloned.pop(k, None)
    return cloned


def _put_cached_info(urls, info):
    sanitized = _sanitize_info_for_cache(info)
    if not sanitized or not sanitized.get('formats'):
        return
    now = time.monotonic()
    with _CACHE_LOCK:
        for u in urls:
            if u:
                _EXTRACT_CACHE[u] = (now, sanitized)
        _prune_cache_locked(_EXTRACT_CACHE, _INFO_TTL_SEC)


def _get_cached_info(url, max_age=None):
    if not url:
        return None
    limit = _INFO_TTL_SEC if max_age is None else min(float(max_age), _INFO_TTL_SEC)
    now = time.monotonic()
    with _CACHE_LOCK:
        entry = _EXTRACT_CACHE.get(url)
        if not entry:
            return None
        ts, info = entry
        if (now - ts) > limit:
            _EXTRACT_CACHE.pop(url, None)
            return None
        return copy.deepcopy(info)


def _evict_cached_info(url):
    if not url:
        return
    with _CACHE_LOCK:
        _EXTRACT_CACHE.pop(url, None)


def _extract_instagram_embed(url):
    """Best-effort public Instagram /embed/captioned/ fallback when primary API is rate-limited."""
    m = re.search(r'instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)', str(url or ''))
    if not m:
        return None
    shortcode = m.group(1)
    embed_url = f'https://www.instagram.com/reel/{shortcode}/embed/captioned/'
    req = urllib.request.Request(
        embed_url,
        headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
            'Accept-Language': 'en-US,en;q=0.9',
        },
    )
    try:
        with urllib.request.urlopen(req, context=_ssl_context(), timeout=15) as resp:
            html = resp.read().decode('utf-8', errors='replace')
    except Exception:
        return None

    def _unescape(s):
        return (s or '').replace('\\/', '/').replace('\\u0026', '&').replace('&amp;', '&')

    vid_m = re.search(r'"video_url"\s*:\s*"([^"]+)"', html) or re.search(r'<video[^>]+src="([^"]+)"', html)
    thumb_m = re.search(r'"display_url"\s*:\s*"([^"]+)"', html) or re.search(r'<img[^>]+class="EmbeddedMediaImage"[^>]+src="([^"]+)"', html)
    thumb = _unescape(thumb_m.group(1)) if thumb_m else ''
    title_m = re.search(r'"caption"\s*:\s*"([^"]{1,140})"', html)
    title = _safe_name(_unescape(title_m.group(1))) if title_m else f'Instagram {shortcode}'

    if vid_m:
        video_url = _unescape(vid_m.group(1))
        if video_url.startswith('http'):
            return {
                'id': shortcode,
                'title': title,
                'uploader': 'Instagram',
                'webpage_url': url,
                'url': video_url,
                'ext': 'mp4',
                'thumbnail': thumb,
                'duration': 0,
                'formats': [{
                    'format_id': 'best',
                    'url': video_url,
                    'ext': 'mp4',
                    'vcodec': 'avc1',
                    'acodec': 'mp4a',
                }],
            }

    if thumb.startswith('http'):
        return {
            'id': shortcode,
            'title': title,
            'uploader': 'Instagram',
            'webpage_url': url,
            'url': thumb,
            'ext': 'jpg',
            'thumbnail': thumb,
            'duration': 0,
            'formats': [{
                'format_id': 'best',
                'url': thumb,
                'ext': 'jpg',
                'vcodec': 'none',
                'acodec': 'none',
            }],
        }
    return None


def _extract_info_fast(base_opts, url, platform=None, download=False):
    """Extract info, skipping unused HLS/DASH YouTube manifests first with full fallback."""
    plat = platform or _detect_platform(url)
    if plat == 'youtube' and not download and 'extractor_args' not in base_opts:
        fast_opts = dict(base_opts)
        fast_opts['extractor_args'] = {'youtube': {'skip': ['hls', 'dash']}}
        try:
            with YoutubeDL(fast_opts) as ydl:
                info = ydl.extract_info(url, download=False)
            if info and (info.get('formats') or info.get('_type') == 'playlist'):
                return info
        except Exception:
            pass
    try:
        with YoutubeDL(base_opts) as ydl:
            return ydl.extract_info(url, download=download)
    except Exception as exc:
        msg = str(exc).lower()
        # Surgical YouTube fallback on 403 / bot-check only (never forced globally)
        if plat == 'youtube' and any(tok in msg for tok in ('403', 'sign in to confirm', 'not a bot')):
            retry_opts = dict(base_opts)
            retry_opts['extractor_args'] = {'youtube': {'player_client': ['tv_embedded', 'web_safari']}}
            with YoutubeDL(retry_opts) as ydl:
                return ydl.extract_info(url, download=download)
        # Instagram public /embed/captioned/ fallback on anonymous rate-limit/login wall/photo-only post
        if plat == 'instagram' and not download and (
            _is_rate_limit_message(msg) or any(tok in msg for tok in ('login', 'empty media', 'no video in this post', '401', '403'))
        ):
            embed_info = _extract_instagram_embed(url)
            if embed_info:
                return embed_info
        raise


_RATE_LIMIT_TOKENS = (
    '429', 'too many requests', 'rate limit', 'rate-limit',
    'free api limit', '1 request/second', '1 request per second',
)


def _is_rate_limit_message(message):
    """True when the site said "too many requests", in any of its dialects.

    A rate limit is the one failure a retry cannot fix: the retry is another
    request, and it extends the wait instead of shortening it. Kept next to
    the fragment fallback so the retry decision can never drift from it.
    """
    text = str(message or '').lower()
    return any(token in text for token in _RATE_LIMIT_TOKENS)


def _run_ydl_with_fragment_fallback(opts, url, info=None, progress_listener=None):
    """Run YoutubeDL download, falling back from 3 -> 1 concurrent fragments on 403.

    A 429/rate limit is raised immediately: a retry is another request, and the
    site has already said no.
    """
    for frag_count in (3, 1):
        if _is_cancelled(progress_listener):
            _fail("Download cancelled.", cause='cancelled')
        attempt_opts = dict(opts)
        attempt_opts['concurrent_fragment_downloads'] = frag_count
        try:
            with YoutubeDL(attempt_opts) as ydl:
                if info is not None and info.get('_type', 'video') == 'video' and info.get('formats') and hasattr(ydl, 'process_video_result'):
                    res = ydl.process_video_result(_sanitize_info_for_cache(info), download=True)
                else:
                    res = ydl.extract_info(url, download=True)
                path = ydl.prepare_filename(res) if res else None
                return res, path
        except Exception as exc:
            if _is_cancelled(progress_listener):
                _fail("Download cancelled.", cause='cancelled')
            msg = str(exc).lower()
            # NEVER retry a rate limit. The old code treated 429 like a
            # fragment-count problem and fired a second request 1.5 s later,
            # which is the opposite of what the site asked for.
            if frag_count == 3 and _is_rate_limit_message(msg):
                raise
            is_fragment_throttle = any(tok in msg for tok in ('403', 'forbidden'))
            if frag_count == 3 and is_fragment_throttle:
                time.sleep(1.5)
                continue
            raise


def _scaled_hook(progress_listener, lo, hi):
    """Progress hook that maps one stream's download into a [lo, hi] % window."""
    last = [0.0]

    def hook(d):
        if progress_listener is None:
            return
        status = d.get('status')
        if status == 'downloading':
            if _is_cancelled(progress_listener):
                _fail("Download cancelled.", cause='cancelled')
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


def _build_split_video_selector(max_height, sdk, prefer_uhd=None, mp4_only=False):
    if mp4_only:
        return '/'.join([
            f'bestvideo[ext=mp4][vcodec^=avc1][height<={max_height}]',
            f'bestvideo[height<={max_height}][vcodec^=avc1]',
            f'bestvideo[ext=mp4][vcodec^=hev1][height<={max_height}]',
            f'bestvideo[ext=mp4][vcodec^=hvc1][height<={max_height}]',
        ])
    if prefer_uhd is None:
        prefer_uhd = max_height > 1080
    branches = []
    if prefer_uhd and max_height > 1080:
        if sdk >= 34:
            branches.append(f'bestvideo[height<={max_height}][vcodec^=av01]')
        if sdk >= 29:
            branches.append(f'bestvideo[height<={max_height}][vcodec^=vp9]')
            branches.append(f'bestvideo[height<={max_height}][vcodec^=vp09]')
        branches.append(f'bestvideo[ext=mp4][vcodec^=avc1][height<={max_height}]')
        branches.append(f'bestvideo[height<={max_height}][vcodec^=avc1]')
    else:
        branches.append(f'bestvideo[ext=mp4][vcodec^=avc1][height<={max_height}]')
        branches.append(f'bestvideo[height<={max_height}][vcodec^=avc1]')
        if sdk >= 29:
            branches.append(f'bestvideo[height<={max_height}][vcodec^=vp9]')
            branches.append(f'bestvideo[height<={max_height}][vcodec^=vp09]')
        if sdk >= 34:
            branches.append(f'bestvideo[height<={max_height}][vcodec^=av01]')
    branches.append(f'bestvideo[ext=mp4][height<={max_height}]')
    branches.append(f'bestvideo[height<={max_height}]')
    return '/'.join(branches)


class _ParallelSplitProgress:
    """Thread-safe progress aggregator across concurrent video + audio transfers."""

    def __init__(self, listener):
        self.listener = listener
        self.lock = threading.Lock()
        self.last_cb = 0.0
        self.streams = {
            'v': [0, 0, 0.0, 0, False],
            'a': [0, 0, 0.0, 0, False],
        }

    def hook(self, key):
        def _on_event(d):
            if self.listener is None:
                return
            status = d.get('status')
            if status == 'downloading':
                if _is_cancelled(self.listener):
                    _fail("Download cancelled.", cause='cancelled')
                now = time.time()
                dl = int(d.get('downloaded_bytes') or 0)
                tot = int(d.get('total_bytes') or d.get('total_bytes_estimate') or 0)
                spd = float(d.get('speed') or 0.0)
                eta = int(d.get('eta') or 0)
                with self.lock:
                    self.streams[key][0] = max(self.streams[key][0], dl)
                    if tot > 0:
                        self.streams[key][1] = tot
                    self.streams[key][2] = spd
                    self.streams[key][3] = eta
                    if now - self.last_cb < 0.25:
                        return
                    self.last_cb = now
                    sum_dl = self.streams['v'][0] + self.streams['a'][0]
                    sum_tot = self.streams['v'][1] + self.streams['a'][1]
                    sum_spd = self.streams['v'][2] + self.streams['a'][2]
                    max_eta = max(self.streams['v'][3], self.streams['a'][3])
                frac = (float(sum_dl) / float(sum_tot)) if sum_tot > 0 else 0.0
                pct = min(95.0, max(0.0, frac * 95.0))
                try:
                    self.listener.onProgress(float(pct), int(sum_dl), int(sum_tot), float(sum_spd), int(max_eta))
                except Exception:
                    pass
            elif status == 'finished':
                with self.lock:
                    self.streams[key][4] = True
                    self.streams[key][2] = 0.0
                    both_done = self.streams['v'][4] and self.streams['a'][4]
                    sum_dl = self.streams['v'][0] + self.streams['a'][0]
                    sum_tot = self.streams['v'][1] + self.streams['a'][1]
                if both_done:
                    try:
                        self.listener.onProgress(95.0, int(sum_dl), int(sum_tot), 0.0, 0)
                    except Exception:
                        pass

        return _on_event


def _predict_split_webm_audio(formats, max_height, sdk, prefer_uhd, platform):
    if not (prefer_uhd and platform == 'youtube'):
        return False
    uhd_v = [
        f for f in (formats or [])
        if f.get('vcodec') not in (None, 'none')
        and f.get('acodec') in (None, 'none')
        and 1080 < int(f.get('height') or 0) <= max_height
    ]
    if sdk >= 34 and any(str(f.get('vcodec') or '').lower().startswith('av01') for f in uhd_v):
        av01_exts = {(f.get('ext') or '').lower() for f in uhd_v if str(f.get('vcodec') or '').lower().startswith('av01')}
        return 'webm' in av01_exts and 'mp4' not in av01_exts
    if sdk >= 29:
        for f in uhd_v:
            vc = str(f.get('vcodec') or '').lower()
            if vc.startswith(('vp9', 'vp09')) and (f.get('ext') or '').lower() == 'webm':
                return True
    return False


def _download_split(url, target_dir, max_height=1080, progress_listener=None,
                    sdk_int=34, platform='youtube', sub_opts=None):
    """Download best supported video-only + audio-only streams concurrently for muxing.

    Reuses cached metadata from inspect()/prefetch when fresh, runs video & audio
    transfers in parallel, and automatically retries once on stale cache errors.
    """
    os.makedirs(target_dir, exist_ok=True)
    sdk = _coerce_sdk_int(sdk_int)
    before_files = {
        os.path.join(target_dir, name)
        for name in os.listdir(target_dir)
    }
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
    v_info = None
    a_info = None
    v_ext = 'mp4'

    # One extraction per grab on every non-YouTube site. A stale signed URL is
    # re-extracted once, and only for YouTube: on Instagram a second anonymous
    # page request is what escalates a soft limit into a hard login wall.
    attempts = (True, False) if platform == 'youtube' else (True,)
    for use_cache in attempts:
        video_path = None
        audio_path = None
        used_cached_info = False
        try:
            if _is_cancelled(progress_listener):
                _fail("Download cancelled.", cause='cancelled')

            info = _get_cached_info(url, _INFO_REUSE_SEC) if use_cache else None
            if info is not None:
                used_cached_info = True
            else:
                extract_opts = dict(base)
                extract_opts['skip_download'] = True
                extract_opts['ignore_no_formats_error'] = True
                extract_opts['format'] = 'best/bestvideo/bestaudio'
                info = _extract_info_fast(extract_opts, url, platform=platform, download=False)
                _put_cached_info([url, (info or {}).get('webpage_url')], info)

            formats = (info or {}).get('formats') or []
            v_only_supported = [
                f for f in formats
                if f.get('vcodec') not in (None, 'none')
                and f.get('acodec') in (None, 'none')
                and (
                    _is_supported_vcodec(f.get('vcodec'), sdk, allow_legacy=True)
                    if platform == 'youtube'
                    else _is_mp4_muxable_vcodec(f.get('vcodec'))
                )
            ]
            if platform == 'youtube':
                has_video_only = any(
                    f.get('vcodec') not in (None, 'none') and f.get('acodec') in (None, 'none')
                    for f in formats
                )
                has_audio_only = any(
                    f.get('acodec') not in (None, 'none') and f.get('vcodec') in (None, 'none')
                    for f in formats
                )
            else:
                has_video_only = bool(v_only_supported)
                has_audio_only = any(_is_aac_audio(f) for f in formats)
                if has_video_only and has_audio_only:
                    top_dash_h = max(
                        (int(f.get('height') or 0) for f in v_only_supported if int(f.get('height') or 0) <= max_height),
                        default=0,
                    )
                    combined_h = max(
                        (int(f.get('height') or 0) for f in formats
                         if f.get('vcodec') not in (None, 'none') and f.get('acodec') not in (None, 'none')
                         and int(f.get('height') or 0) <= max_height),
                        default=0,
                    )
                    if top_dash_h <= combined_h or top_dash_h == 0:
                        return None

            if not info or not (has_video_only and has_audio_only):
                return None

            top_supported_h = max(
                (int(f.get('height') or 0) for f in v_only_supported if int(f.get('height') or 0) <= max_height),
                default=min(max_height, 1080),
            )
            prefer_uhd = (max_height > 1080 and top_supported_h > 1080)
            want_webm_audio = _predict_split_webm_audio(formats, max_height, sdk, prefer_uhd, platform)

            agg = _ParallelSplitProgress(progress_listener) if progress_listener else None

            video_opts = dict(base)
            video_opts['outtmpl'] = os.path.join(target_dir, '%(title).120B-%(id)s.v.%(ext)s')
            video_opts['format'] = _build_split_video_selector(
                max_height, sdk, prefer_uhd=prefer_uhd, mp4_only=(platform != 'youtube')
            )
            video_opts['progress_hooks'] = [agg.hook('v')] if agg else []

            audio_opts = dict(base)
            audio_opts['outtmpl'] = os.path.join(target_dir, '%(title).120B-%(id)s.a.%(ext)s')
            if want_webm_audio:
                audio_opts['format'] = 'bestaudio[ext=webm][acodec^=opus]/bestaudio[ext=webm]/bestaudio[ext=m4a][acodec^=mp4a]/bestaudio'
            else:
                audio_opts['format'] = 'bestaudio[ext=m4a][acodec^=mp4a]/bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio'
            audio_opts['progress_hooks'] = [agg.hook('a')] if agg else []
            if sub_opts and sub_opts.get('subtitles'):
                audio_opts['writesubtitles'] = True
                audio_opts['writeautomaticsub'] = True
                audio_opts['subtitleslangs'] = _subtitle_langs_from_opts(sub_opts)
                audio_opts['subtitlesformat'] = 'srt/vtt/best'

            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                v_fut = pool.submit(_run_ydl_with_fragment_fallback, video_opts, url, info, progress_listener)
                a_fut = pool.submit(_run_ydl_with_fragment_fallback, audio_opts, url, info, progress_listener)
                v_info, video_path = v_fut.result()
                a_info, audio_path = a_fut.result()

            if not video_path or not os.path.exists(video_path) or not audio_path or not os.path.exists(audio_path):
                for leftover in (video_path, audio_path):
                    if leftover:
                        try:
                            os.remove(leftover)
                        except OSError:
                            pass
                return None

            v_ext = str((v_info or {}).get('ext') or os.path.splitext(video_path)[1].lstrip('.')).lower()
            break
        except Exception as exc:
            for leftover in (video_path, audio_path):
                if leftover:
                    try:
                        os.remove(leftover)
                    except OSError:
                        pass
            if _is_cancelled(progress_listener):
                _fail("Download cancelled.", cause='cancelled')
            # Only YouTube gets the second, re-extracted attempt for a stale
            # signed URL. On Instagram that second anonymous page request is
            # exactly what escalates a soft limit into a hard login wall.
            if used_cached_info and platform == 'youtube':
                _evict_cached_info(url)
                continue
            # THE TRUTH LAYER: returning None here would report "this video has no
            # separate 1080p stream" for whatever actually went wrong (a 403, a
            # timeout, a refused request). The reason is the reason - raise it.
            _fail(exc)

    a_ext = str((a_info or {}).get('ext') or os.path.splitext(audio_path)[1].lstrip('.')).lower()
    out_ext = 'webm' if (v_ext == 'webm' and a_ext == 'webm') else 'mp4'
    res = {
        'merge': True,
        'video_path': video_path,
        'audio_path': audio_path,
        'title': _safe_name((v_info.get('title') if v_info else '') or 'video'),
        'ext': out_ext,
        'platform': platform,
    }
    if sub_opts and sub_opts.get('subtitles'):
        res['subtitle_paths'] = _collect_subtitle_paths(target_dir, before_files)
    return res


# ---------------------------------------------------------------------------
# TikTok Direct Handler (Proven fast & watermark-free)
# ---------------------------------------------------------------------------

def _fetch_tiktok_data(url, use_cache=True):
    if use_cache:
        now = time.monotonic()
        with _CACHE_LOCK:
            cached = _TIKWM_CACHE.get(url)
            if cached and (now - cached[0]) <= _INFO_TTL_SEC:
                return copy.deepcopy(cached[1])
    api = "https://www.tikwm.com/api/"
    data = urllib.parse.urlencode({
        'url': url,
        'count': 12,
        'cursor': 0,
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
    with urllib.request.urlopen(req, timeout=15, context=_ssl_context()) as resp:
        parsed = json.loads(resp.read().decode('utf-8', errors='ignore'))
    if isinstance(parsed, dict) and parsed.get('code') == 0:
        with _CACHE_LOCK:
            _TIKWM_CACHE[url] = (time.monotonic(), copy.deepcopy(parsed))
            _prune_cache_locked(_TIKWM_CACHE, _INFO_TTL_SEC)
    return parsed


def _download_stream_direct(stream_url, target_path, referer='', progress_listener=None):
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': '*/*',
    }
    if referer:
        headers['Referer'] = referer
    req = urllib.request.Request(stream_url, headers=headers)
    with urllib.request.urlopen(req, timeout=15, context=_ssl_context()) as resp:
        total = int(resp.headers.get('Content-Length') or 0)
        downloaded = 0
        last_cb = 0.0
        start = time.time()
        with open(target_path, 'wb') as f:
            while True:
                if _is_cancelled(progress_listener):
                    _fail("Download cancelled.", cause='cancelled')
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
    for use_cache in (True, False):
        data = _fetch_tiktok_data(url, use_cache=use_cache)
        if data.get('code') != 0:
            if use_cache:
                continue
            _fail(data.get('msg') or 'TikTok video not available')
        item = data.get('data') or {}
        music_info = item.get('music_info') or {}
        images = item.get('images') if isinstance(item.get('images'), list) else []
        if images and not is_audio:
            raw_title = item.get('title') or ('tiktok_' + str(item.get('id', int(time.time()))))
            title = _safe_name(raw_title)
            slide_paths = []
            for idx, img_url in enumerate(images, start=1):
                if not img_url:
                    continue
                full_img = urllib.parse.urljoin('https://www.tikwm.com', img_url) if str(img_url).startswith('/') else str(img_url)
                slide_path = os.path.join(target_dir, f"{title[:40]}-{item.get('id', 'slide')}-{idx}.jpg")
                try:
                    _download_stream_direct(full_img, slide_path, 'https://www.tikwm.com/', progress_listener)
                    if os.path.exists(slide_path) and os.path.getsize(slide_path) > 0:
                        slide_paths.append(slide_path)
                except Exception:
                    if _is_cancelled(progress_listener):
                        _fail("Download cancelled.", cause='cancelled')
            if slide_paths:
                total_sz = sum(os.path.getsize(p) for p in slide_paths if os.path.exists(p))
                return {
                    'path': slide_paths[0],
                    'extra_paths': slide_paths[1:],
                    'title': title,
                    'ext': 'jpg',
                    'filesize': total_sz,
                    'platform': 'tiktok',
                    'thumbnail': urllib.parse.urljoin('https://www.tikwm.com', str(item.get('cover') or images[0])),
                }
        raw_candidates = (
            (music_info.get('play'), item.get('music'))
            if is_audio
            else (item.get('hdplay'), item.get('play'), item.get('wmplay'))
        )
        candidates = []
        for raw_url in raw_candidates:
            if not raw_url:
                continue
            full_url = urllib.parse.urljoin('https://www.tikwm.com', raw_url) if str(raw_url).startswith('/') else str(raw_url)
            if full_url not in candidates:
                candidates.append(full_url)
        if not candidates:
            if use_cache:
                continue
            _fail('No downloadable stream found in TikTok response')
        raw_title = item.get('title') or ('tiktok_' + str(item.get('id', int(time.time()))))
        title = _safe_name(raw_title)
        ext = 'mp3' if is_audio else 'mp4'
        filename = f"{title[:50]}-{item.get('id', 'video')}.{ext}"
        out_path = os.path.join(target_dir, filename)
        last_exc = None
        for cand in candidates:
            referer = 'https://www.tikwm.com/' if 'tikwm.com' in cand.lower() else 'https://www.tiktok.com/'
            try:
                _download_stream_direct(cand, out_path, referer, progress_listener)
                last_exc = None
                break
            except Exception as exc:
                last_exc = exc
                if _is_cancelled(progress_listener):
                    _fail(exc)
        if last_exc is None:
            break
        if use_cache:
            with _CACHE_LOCK:
                _TIKWM_CACHE.pop(url, None)
            continue
        _fail(last_exc)
    tagged = False
    if is_audio:
        author = item.get('author') or {}
        artist = author.get('nickname') or author.get('unique_id') or 'TikTok Creator'
        cover_candidates = []
        for raw_cov in (item.get('cover'), item.get('origin_cover'), music_info.get('cover')):
            if not raw_cov:
                continue
            cov_url = urllib.parse.urljoin('https://www.tikwm.com', raw_cov) if str(raw_cov).startswith('/') else str(raw_cov)
            if cov_url not in cover_candidates:
                cover_candidates.append(cov_url)
        tagged = bool(audio_tagger.tag_audio_file(out_path, title, artist, cover_candidates, _ssl_context()))
    filesize = os.path.getsize(out_path) if os.path.exists(out_path) else 0
    res = {
        'path': out_path,
        'title': title,
        'ext': ext,
        'filesize': filesize,
        'platform': 'tiktok'
    }
    if is_audio:
        res['tagged'] = tagged
    return res


# ---------------------------------------------------------------------------
# Inspect (v1.3.6 core with TikTok fast-path + real YouTube format probing)
# ---------------------------------------------------------------------------

def _fmt_size(f):
    if not f:
        return 0
    return int(f.get('filesize') or f.get('filesize_approx') or 0)


def _best_audio_format(raw_formats):
    audios = [
        f for f in (raw_formats or [])
        if f.get('acodec') not in (None, 'none') and f.get('vcodec') in (None, 'none')
    ]
    if not audios:
        return None
    m4as = [f for f in audios if (f.get('ext') or '').lower() == 'm4a']
    pool = m4as or audios
    return max(pool, key=lambda f: (float(f.get('abr') or f.get('tbr') or 0), _fmt_size(f)))


def _best_opus_audio_format(raw_formats):
    audios = [
        f for f in (raw_formats or [])
        if f.get('acodec') not in (None, 'none')
        and f.get('vcodec') in (None, 'none')
        and (
            (f.get('ext') or '').lower() == 'webm'
            or str(f.get('acodec') or '').lower().startswith('opus')
        )
    ]
    if not audios:
        return None
    return max(audios, key=lambda f: (float(f.get('abr') or f.get('tbr') or 0), _fmt_size(f)))


def _build_youtube_formats(info, sdk_int=34):
    sdk = _coerce_sdk_int(sdk_int)
    raw_formats = (info or {}).get('formats') or []
    best_audio = _best_audio_format(raw_formats)
    opus_audio = _best_opus_audio_format(raw_formats)
    audio_size = _fmt_size(best_audio)

    if not raw_formats:
        return [
            {'id': 'best', 'label': 'Best Available Quality (HD)', 'ext': 'mp4', 'badge': 'HD', 'filesize': 0},
            {'id': 'audio', 'label': 'Audio Track (M4A)', 'ext': 'm4a', 'badge': 'M4A', 'filesize': 0},
        ]

    tiers = [
        (2160, 1440, '4K Ultra HD', '4K'),
        (1440, 1080, '1440p QHD', '1440p'),
        (1080, 720, '1080p Full HD (merged on device)', '1080p'),
        (720, 480, '720p HD Quality', '720p'),
        (480, 360, '480p Standard Quality', '480p'),
        (360, 0, '360p Data Saver', '360p'),
    ]

    tier_rows = []
    for tier, prev_tier, label, badge in tiers:
        in_tier = [
            f for f in raw_formats
            if f.get('vcodec') not in (None, 'none')
            and prev_tier < int(f.get('height') or 0) <= tier
        ]
        if tier > 1080:
            in_tier = [
                f for f in in_tier
                if _is_supported_vcodec(f.get('vcodec'), sdk, allow_legacy=False)
            ]
        if not in_tier:
            continue
        v_only = [f for f in in_tier if f.get('acodec') in (None, 'none')]
        combined = [f for f in in_tier if f.get('acodec') not in (None, 'none')]

        size = 0
        row_ext = 'mp4'
        if tier >= 480 and v_only:
            prefer_avc = (tier <= 1080)
            pick = max(
                v_only,
                key=lambda f: (
                    1 if _fmt_size(f) > 0 else 0,
                    1 if (prefer_avc and (f.get('ext') or '').lower() == 'mp4'
                          and str(f.get('vcodec') or '').lower().startswith('avc1')) else 0,
                    1 if (f.get('ext') or '').lower() == 'mp4' else 0,
                    _fmt_size(f),
                    float(f.get('tbr') or 0),
                ),
            )
            v_sz = _fmt_size(pick)
            row_ext = 'webm' if ((pick.get('ext') or '').lower() == 'webm' and tier > 1080) else 'mp4'
            a_sz = int(_fmt_size(opus_audio)) if (row_ext == 'webm' and opus_audio) else audio_size
            size = (v_sz + a_sz) if v_sz > 0 else 0
        elif combined:
            mp4s = [f for f in combined if (f.get('ext') or '').lower() == 'mp4']
            pick = max(mp4s or combined, key=lambda f: (_fmt_size(f), float(f.get('tbr') or 0)))
            size = _fmt_size(pick)
        elif v_only:
            pick = max(v_only, key=lambda f: (_fmt_size(f), float(f.get('tbr') or 0)))
            v_sz = _fmt_size(pick)
            size = (v_sz + audio_size) if v_sz > 0 else 0

        tier_rows.append({
            'id': str(tier),
            'label': label,
            'ext': row_ext,
            'badge': badge,
            'filesize': int(size),
        })

    best_cap = 2160 if sdk >= 29 else 1080
    best_size = tier_rows[0]['filesize'] if tier_rows else 0
    best_ext = tier_rows[0]['ext'] if tier_rows else 'mp4'
    if best_size == 0:
        capped = [
            f for f in raw_formats
            if f.get('vcodec') not in (None, 'none')
            and 0 < int(f.get('height') or 0) <= best_cap
            and (int(f.get('height') or 0) <= 1080 or _is_supported_vcodec(f.get('vcodec'), sdk, allow_legacy=False))
        ]
        if capped:
            top = max(capped, key=lambda f: (1 if _fmt_size(f) > 0 else 0, int(f.get('height') or 0), _fmt_size(f)))
            v_sz = _fmt_size(top)
            if v_sz > 0:
                best_ext = 'webm' if ((top.get('ext') or '').lower() == 'webm' and int(top.get('height') or 0) > 1080) else 'mp4'
                a_sz = int(_fmt_size(opus_audio)) if (best_ext == 'webm' and opus_audio) else audio_size
                best_size = v_sz + (a_sz if top.get('acodec') in (None, 'none') else 0)

    top_tier = tier_rows[0] if tier_rows else None
    need_best_row = (
        top_tier is None
        or (int(best_size) > 0 and (top_tier['filesize'] != int(best_size) or top_tier['ext'] != best_ext))
    )
    formats = []
    if need_best_row:
        formats.append({'id': 'best', 'label': 'Best Available Quality (HD)', 'ext': best_ext, 'badge': 'HD', 'filesize': int(best_size)})
    formats.extend(tier_rows)

    if best_audio:
        a_ext = (best_audio.get('ext') or 'm4a').lower()
        a_label = 'Audio Track (M4A)' if a_ext == 'm4a' else f'Audio Track ({a_ext.upper()})'
        formats.append({
            'id': 'audio',
            'label': a_label,
            'ext': a_ext,
            'badge': a_ext.upper(),
            'filesize': int(audio_size),
        })
        if opus_audio and a_ext == 'm4a':
            formats.append({
                'id': 'audio_opus',
                'label': 'Audio Track (Opus/WebM)',
                'ext': 'webm',
                'badge': 'OPUS',
                'filesize': int(_fmt_size(opus_audio)),
            })
    return formats


def _build_other_formats(info, platform='other', sdk_int=34):
    sdk = _coerce_sdk_int(sdk_int)
    raw_formats = (info or {}).get('formats') or []
    combined = [
        f for f in raw_formats
        if f.get('vcodec') not in (None, 'none') and f.get('acodec') not in (None, 'none')
    ]
    mp4s = [f for f in combined if (f.get('ext') or '').lower() == 'mp4']
    pool = mp4s or combined
    best_combined_h = 0
    best_size = 0
    if pool:
        top = max(pool, key=lambda f: (int(f.get('height') or 0), _fmt_size(f), float(f.get('tbr') or 0)))
        best_combined_h = int(top.get('height') or 0)
        best_size = _fmt_size(top)
    if best_size == 0 and info:
        best_size = _fmt_size(info)

    # Unlock DASH split+merge for Instagram / non-YT when MP4-muxable video-only (>720p) + AAC audio-only exist
    v_only = [
        f for f in raw_formats
        if f.get('vcodec') not in (None, 'none')
        and f.get('acodec') in (None, 'none')
        and _is_mp4_muxable_vcodec(f.get('vcodec'))
    ]
    aac_audios = [f for f in raw_formats if _is_aac_audio(f)]
    if v_only and aac_audios:
        top_dash = max(v_only, key=lambda f: (int(f.get('height') or 0), _fmt_size(f), float(f.get('tbr') or 0)))
        top_aac = max(aac_audios, key=lambda f: (float(f.get('abr') or f.get('tbr') or 0), _fmt_size(f)))
        top_dash_h = int(top_dash.get('height') or 0)
        if top_dash_h > 720 and top_dash_h > best_combined_h:
            v_sz = _fmt_size(top_dash)
            a_sz = _fmt_size(top_aac)
            dash_size = (v_sz + a_sz) if v_sz > 0 else 0
            # DASH split+merge is an explicit choice on non-YouTube sites. The
            # default "best" lane stays the single progressive file, exactly as
            # v3.8.0 did: one anonymous metadata request, one media request, no
            # second fetch. The merged HD rows remain available as opt-ins.
            rows = []
            if pool:
                rows.append({
                    'id': 'best',
                    'label': ('Best Available Quality' if not best_combined_h
                              else f'Best Available Quality ({best_combined_h}p)'),
                    'ext': 'mp4',
                    'badge': f'{best_combined_h}p' if best_combined_h else 'Best',
                    'filesize': int(best_size),
                })
            else:
                # No progressive stream at all: best must fall back to merged.
                rows.append({
                    'id': 'best',
                    'label': f'Best Available Quality ({top_dash_h}p HD)',
                    'ext': 'mp4',
                    'badge': f'{top_dash_h}p' if top_dash_h in (1080, 1440, 2160) else 'HD',
                    'filesize': int(dash_size),
                })
            merged_lanes = {
                2160: ('4k', '4K Ultra HD (merged on device)', '4K'),
                1440: ('1440', '1440p QHD (merged on device)', '1440p'),
                1080: ('1080', '1080p Full HD (merged on device)', '1080p'),
            }
            lane_h = next((h for h in (2160, 1440, 1080) if top_dash_h >= h), None)
            if lane_h:
                lane_id, lane_label, lane_badge = merged_lanes[lane_h]
                rows.append({
                    'id': lane_id,
                    'label': lane_label,
                    'ext': 'mp4',
                    'badge': lane_badge,
                    'filesize': int(dash_size),
                })
            if pool and 0 < best_combined_h <= 720:
                rows.append({
                    'id': '720',
                    'label': f'{best_combined_h}p Standard Quality',
                    'ext': 'mp4',
                    'badge': f'{best_combined_h}p',
                    'filesize': int(best_size),
                })
            return rows

    return [
        {'id': 'best', 'label': 'Best Available Quality', 'ext': 'mp4', 'badge': 'Best', 'filesize': int(best_size)},
    ]


def inspect(url, sdk_int=34):
    sdk = _coerce_sdk_int(sdk_int)
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
                vid_size = int(d.get('hd_size') or d.get('size') or 0)
                music_info = d.get('music_info') if isinstance(d.get('music_info'), dict) else {}
                aud_size = int(music_info.get('size') or 0)
                return json.dumps({
                    'title': _safe_name(d.get('title') or 'TikTok Video'),
                    'uploader': (d.get('author') or {}).get('nickname') or (d.get('author') or {}).get('unique_id') or 'TikTok Creator',
                    'duration': int(d.get('duration') or 0),
                    'thumbnail': cover,
                    'webpage_url': clean,
                    'platform': 'tiktok',
                    'has_subtitles': False,
                    'subtitle_langs': [],
                    'formats': [
                        {'id': 'best', 'label': 'HD Video (No Watermark)', 'ext': 'mp4', 'badge': 'HD', 'filesize': vid_size},
                        {'id': 'audio', 'label': 'Audio Track (MP3)', 'ext': 'mp3', 'badge': 'MP3', 'filesize': aud_size}
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
        'playlist_items': '1',
        'ca_certs': certifi.where(),
        'socket_timeout': 30,
        'retries': 3,
    }
    info = None
    inspect_error = None
    try:
        info = _extract_info_fast(options, clean, platform=platform, download=False)
    except Exception as exc:
        # THE TRUTH LAYER: fabricating a card here made a link we could not
        # read look like a video with no title, and the layer above then told
        # the user "That link isn't a video" - a cause we never verified. Keep
        # the reason and pass it up.
        inspect_error = exc
        info = None

    if info and info.get('_type') == 'playlist':
        pl_entries = [e for e in (info.get('entries') or []) if isinstance(e, dict)]
        if pl_entries:
            info = pl_entries[0]

    if info:
        _put_cached_info([clean, info.get('webpage_url')], info)
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

    has_subtitles, subtitle_langs = _extract_subtitle_langs(info)

    if platform == 'youtube':
        formats = _build_youtube_formats(info, sdk)
        note = ('YouTube serves HD streams as separate video and audio — DOWNI merges them on your '
                'device. Audio downloads directly as M4A/WebM without transcoding.')
    else:
        formats = _build_other_formats(info, platform, sdk)
        has_dash = any(f.get('badge') in ('1080p', '1440p', '4K') or 'merged' in f.get('label', '') for f in formats)
        if has_dash:
            note = 'HD streams are served as separate video and audio — DOWNI merges them on your device.'
        else:
            note = ('Best single file available. Higher resolutions may exist only as '
                    'separate video and audio streams, which DOWNI does not merge for this site.')

    return json.dumps({
        'title': title,
        'uploader': uploader,
        'duration': duration,
        'thumbnail': thumbnail,
        'webpage_url': webpage_url,
        'platform': platform,
        'has_subtitles': has_subtitles,
        'subtitle_langs': subtitle_langs,
        'formats': formats,
        'note': note,
    })


# ---------------------------------------------------------------------------
# Download (Exact rock-solid v1.3.6 core + progress listener)
# ---------------------------------------------------------------------------

def _download_lanes(url, target_dir, format_id='best', progress_listener=None, sdk_int=34, opts=None):
    os.makedirs(target_dir, exist_ok=True)
    sdk = _coerce_sdk_int(sdk_int)
    opts = opts or {}
    clean = _clean_url(url)
    platform = _detect_platform(clean)
    fmt_key = str(format_id).lower()
    is_audio = fmt_key in ('audio', 'audio_opus', 'mp3', 'm4a', 'opus', 'webm')
    before_files = {
        os.path.join(target_dir, name)
        for name in os.listdir(target_dir)
    }

    if _is_cancelled(progress_listener):
        _fail("Download cancelled.", cause='cancelled')

    # 1) TikTok direct fast download
    if platform == 'tiktok':
        try:
            res = _download_tiktok_direct(clean, target_dir, is_audio, progress_listener)
            return json.dumps(res)
        except RuntimeError:
            raise                                  # the reason is classified - keep it
        except Exception:
            pass

    # 2) Split + on-device merge for YouTube adaptive DASH streams (best, 2160, 1440, 1080, 720, 480)
    #    and Instagram/non-YT DASH streams when supported video-only (>720p) + AAC audio-only exist.
    if platform == 'youtube' and not is_audio and fmt_key in ('best', '2160', '4k', '1440', '1080', '720', '480'):
        split = _download_split(
            clean, target_dir, _lane_height(fmt_key, sdk), progress_listener,
            sdk_int=sdk, platform='youtube', sub_opts=opts,
        )
        if split:
            return json.dumps(split)
        if fmt_key == '1080':
            _fail('This video has no separate 1080p stream - pick 720p HD or Best Available.')
    # On Instagram/non-YT, 'best' is the single progressive file (v3.8.0
    # behaviour): one metadata request, one media request. DASH merge stays
    # available only as an explicit higher-resolution lane.
    elif platform not in ('youtube', 'tiktok') and not is_audio and fmt_key in ('2160', '4k', '1440', '1080'):
        split = _download_split(
            clean, target_dir, _lane_height(fmt_key, sdk), progress_listener,
            sdk_int=sdk, platform=platform, sub_opts=opts,
        )
        if split:
            return json.dumps(split)

    # 3) Universal yt-dlp download with proven v1.3.6 format cascading
    video_formats = {
        '2160': 'best[height<=2160][ext=mp4][vcodec!=none]/best[height<=2160][vcodec!=none]/best[height<=2160]',
        '1440': 'best[height<=1440][ext=mp4][vcodec!=none]/best[height<=1440][vcodec!=none]/best[height<=1440]',
        '1080': 'best[height<=1080][ext=mp4][vcodec!=none]/best[height<=1080][vcodec!=none]/best[height<=1080]',
        '720': 'best[height<=720][ext=mp4][vcodec!=none]/best[height<=720][vcodec!=none]/best[height<=720]',
        '480': 'best[height<=480][ext=mp4][vcodec!=none]/best[height<=480][vcodec!=none]/best[height<=480]',
        '360': 'best[height<=360][ext=mp4][vcodec!=none]/best[height<=360][vcodec!=none]/best[height<=360]',
        'best': 'best[ext=mp4][vcodec!=none]/best[vcodec!=none]/best',
    }
    if fmt_key in ('audio_opus', 'opus', 'webm'):
        fmt = 'bestaudio[ext=webm]/bestaudio[acodec=opus]/bestaudio/best'
    elif is_audio:
        fmt = 'bestaudio[ext=m4a]/bestaudio/best'
    else:
        fmt = video_formats.get(str(format_id), 'best[ext=mp4][vcodec!=none]/best[vcodec!=none]/best')

    last_callback_time = [0.0]

    def _progress_hook(d):
        if progress_listener is None:
            return
        status = d.get('status')
        if status == 'downloading':
            if _is_cancelled(progress_listener):
                _fail("Download cancelled.", cause='cancelled')
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
    if opts.get('subtitles') and not is_audio:
        options['writesubtitles'] = True
        options['writeautomaticsub'] = True
        options['subtitleslangs'] = _subtitle_langs_from_opts(opts)
        options['subtitlesformat'] = 'srt/vtt/best'

    cached_info = _get_cached_info(clean, _INFO_REUSE_SEC)
    try:
        info, path = _run_ydl_with_fragment_fallback(
            options, clean, info=cached_info, progress_listener=progress_listener
        )
    except Exception as dl_exc:
        if platform == 'instagram' and not is_audio and not _is_cancelled(progress_listener):
            dl_msg = str(dl_exc).lower()
            if _is_rate_limit_message(dl_msg) or any(tok in dl_msg for tok in ('login', 'empty media', 'no video in this post', '401', '403')):
                embed_info = _extract_instagram_embed(clean)
                if embed_info and str(embed_info.get('url') or '').startswith('http'):
                    ext_fb = embed_info.get('ext') or 'mp4'
                    title_fb = embed_info.get('title') or 'Instagram'
                    out_fb = os.path.join(target_dir, f"{title_fb[:50]}-{embed_info.get('id', 'post')}.{ext_fb}")
                    _download_stream_direct(embed_info['url'], out_fb, 'https://www.instagram.com/', progress_listener)
                    return json.dumps({
                        'path': out_fb,
                        'title': title_fb,
                        'ext': ext_fb,
                        'filesize': os.path.getsize(out_fb) if os.path.exists(out_fb) else 0,
                        'platform': 'instagram',
                        'thumbnail': embed_info.get('thumbnail') or '',
                    })
        # A second anonymous page request is what turns a soft Instagram
        # throttle into a hard login wall. Only YouTube may re-extract here
        # (its CDN URLs actually expire); everywhere else report the reason.
        if cached_info is None or _is_cancelled(progress_listener) or platform != 'youtube':
            raise
        _evict_cached_info(clean)
        info, path = _run_ydl_with_fragment_fallback(
            options, clean, info=None, progress_listener=progress_listener
        )

    new_media_files = sorted(
        [
            os.path.join(target_dir, name)
            for name in os.listdir(target_dir)
            if os.path.join(target_dir, name) not in before_files
            and os.path.isfile(os.path.join(target_dir, name))
            and not name.endswith(('.part', '.srt', '.vtt', '.ass', '.ssa', '.ttml'))
            and os.path.getsize(os.path.join(target_dir, name)) > 0
        ],
        key=os.path.getmtime,
    )

    if not path or not os.path.exists(path):
        if new_media_files:
            path = new_media_files[-1]
        else:
            _fail('The site did not provide a downloadable public media file.')

    title = _safe_name((info.get('title') if info else '') or 'video')
    ext = os.path.splitext(path)[1].lower().lstrip('.') or ('m4a' if is_audio else 'mp4')
    tagged = False
    if is_audio and ext in ('m4a', 'mp3'):
        artist = (info.get('uploader') or info.get('channel') or info.get('artist') or '') if info else ''
        thumb_url = (info.get('thumbnail') or '') if info else ''
        tagged = bool(audio_tagger.tag_audio_file(path, title, artist, thumb_url, _ssl_context()))

    extra_paths = [p for p in new_media_files if p != path] if (not is_audio and len(new_media_files) > 1) else []
    filesize = (os.path.getsize(path) if os.path.exists(path) else 0) + sum(
        os.path.getsize(p) for p in extra_paths if os.path.exists(p)
    )
    result = {
        'path': path,
        'title': title,
        'ext': ext,
        'filesize': filesize,
        'platform': platform,
        'thumbnail': (info.get('thumbnail') or '') if info else '',
    }
    if extra_paths:
        result['extra_paths'] = extra_paths
    if is_audio:
        result['tagged'] = tagged
    if opts.get('subtitles') and not is_audio:
        result['subtitle_paths'] = _collect_subtitle_paths(target_dir, before_files)
    return json.dumps(result)


def download(url, target_dir, format_id='best', progress_listener=None, sdk_int=34, opts_json=None):
    """Public entry point for a single download.

    Supports adaptive DASH split+merge up to 2160p (VP9 API>=29, AV1 API>=34)
    on YouTube and Instagram/non-YT DASH, pure-Python audio tagging (.m4a/.mp3),
    and optional subtitle sidecar downloads via opts_json.
    """
    sdk = _coerce_sdk_int(sdk_int)
    opts = _parse_opts_json(opts_json)
    try:
        return _download_lanes(url, target_dir, format_id, progress_listener, sdk_int=sdk, opts=opts)
    except Exception as exc:
        if str(format_id).lower() in ('audio', 'audio_opus', 'mp3', 'm4a', 'opus', 'webm'):
            _fail(exc)
        if 'requested format is not available' not in str(exc).lower():
            _fail(exc)
        clean = _clean_url(url)
        platform = _detect_platform(clean)
        if platform == 'tiktok':
            _fail(exc)
        try:
            split = _download_split(
                clean, target_dir, _lane_height(format_id, sdk), progress_listener,
                sdk_int=sdk, platform=platform, sub_opts=opts,
            )
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


def playlist_inspect(url, offset=0, limit=50, max_items=None):
    """Flat-extract a paginated slice of a playlist's entries for batch downloading.

    Uses extract_flat with playliststart/playlistend so it is fast (no per-video
    network work). Returns None when the URL is not a playlist or nothing was
    found; raises tagged truth errors on extraction failure.
    """
    off = max(0, int(offset or 0))
    lim = max(1, int(max_items if max_items is not None else (limit or 50)))
    start_idx = off + 1
    end_idx = off + lim
    opts = {
        'quiet': True,
        'no_warnings': True,
        'js_runtimes': {},
        'ffmpeg_location': '/nonexistent/downi-no-ffmpeg',
        'skip_download': True,
        'extract_flat': 'in_playlist',
        'playliststart': start_idx,
        'playlistend': end_idx,
        'ca_certs': certifi.where(),
        'socket_timeout': 30,
        'retries': 2,
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as exc:
        _fail(exc)
    if not info:
        return None
    raw_entries = list(info.get('entries') or [])
    page_entries = raw_entries[:lim]
    items = []
    for entry in page_entries:
        if not entry:
            continue
        raw = entry.get('url') or entry.get('webpage_url') or ''
        ie = (entry.get('ie_key') or info.get('extractor_key') or '').lower()
        is_yt = ('youtube' in ie or 'youtu' in (info.get('webpage_url') or '').lower())
        raw_id = str(entry.get('id') or '').strip()
        if raw and not raw.startswith('http'):
            if is_yt:
                raw_id = raw_id or raw
                raw = 'https://www.youtube.com/watch?v=' + raw
        thumb = entry.get('thumbnail') or ''
        if not thumb:
            thumbs = entry.get('thumbnails') or []
            if isinstance(thumbs, list):
                for t in reversed(thumbs):
                    tu = t.get('url') if isinstance(t, dict) else ''
                    if isinstance(tu, str) and tu.startswith('http'):
                        thumb = tu
                        break
        if not thumb and is_yt:
            if not raw_id and 'v=' in raw:
                raw_id = raw.split('v=', 1)[1].split('&', 1)[0]
            if re.fullmatch(r'[A-Za-z0-9_-]{6,20}', raw_id or ''):
                thumb = f'https://i.ytimg.com/vi/{raw_id}/mqdefault.jpg'
        if raw.startswith('http'):
            items.append({
                'url': raw,
                'title': entry.get('title') or 'Video',
                'duration': int(entry.get('duration') or 0),
                'thumbnail': thumb if isinstance(thumb, str) and thumb.startswith('http') else '',
            })
    if not items and off == 0:
        return None
    raw_total = info.get('playlist_count') or info.get('n_entries')
    total_count = int(raw_total) if raw_total is not None else (off + len(raw_entries))
    next_offset = off + len(items)
    has_more = bool(total_count > next_offset) if raw_total is not None else (len(raw_entries) >= lim)
    return {
        'title': info.get('title') or 'Playlist',
        'items': items,
        'count': len(items),
        'total_count': total_count,
        'has_more': has_more,
        'next_offset': next_offset,
    }


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
        ctx = _ssl_context()
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
        with urllib.request.urlopen(req, timeout=timeout, context=_ssl_context()) as resp:
            code = resp.getcode()
            ms = int((time.time() - t0) * 1000)
            if 300 <= code < 400:
                return True, ms, '', f"Redirect (HTTP {code})"
            return True, ms, '', f"HTTP {code}"
    except urllib.error.HTTPError as e:
        ms = int((time.time() - t0) * 1000)
        if e.code == 403:
            return False, ms, "HTTP 403 (Blocked)", "Blocked (HTTP 403)"
        if e.code == 429:
            return False, ms, "HTTP 429 (Throttled)", "Rate-limited (HTTP 429)"
        if 300 <= e.code < 400:
            return True, ms, '', f"Redirect (HTTP {e.code})"
        return False, ms, f"HTTP Error {e.code}", f"HTTP {e.code}"
    except Exception as e:
        ms = int((time.time() - t0) * 1000)
        return False, ms, f"Connection failed: {e}", ''


def _diag_tikwm(timeout=6.0):
    t0 = time.time()
    probe_url = 'https://www.tikwm.com/api/?url=https://www.tiktok.com/@tiktok/video/7106594312292453675'
    try:
        req = urllib.request.Request(
            probe_url,
            headers={
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
                'Accept': 'application/json',
                'Referer': 'https://www.tikwm.com/',
            },
        )
        with urllib.request.urlopen(req, timeout=timeout, context=_ssl_context()) as resp:
            code = resp.getcode()
            payload = json.loads(resp.read().decode('utf-8', errors='ignore'))
            ms = int((time.time() - t0) * 1000)
            if payload.get('code') != 0:
                msg = payload.get('msg') or f"API code {payload.get('code')}"
                return False, ms, msg, f"HTTP {code}"
            item = payload.get('data') or {}
            if not (item.get('hdplay') or item.get('play')):
                return False, ms, "API responded without stream URL", f"HTTP {code}"
            return True, ms, '', f"API OK (HTTP {code})"
    except urllib.error.HTTPError as e:
        ms = int((time.time() - t0) * 1000)
        if e.code == 403:
            return False, ms, "HTTP 403 (Blocked)", "Blocked (HTTP 403)"
        if e.code == 429:
            return False, ms, "HTTP 429 (Throttled)", "Rate-limited (HTTP 429)"
        return False, ms, f"HTTP Error {e.code}", f"HTTP {e.code}"
    except Exception as e:
        ms = int((time.time() - t0) * 1000)
        return False, ms, f"Connection failed: {e}", ''


def _diag_yt_range(timeout=6.0):
    """Lightweight YouTube extractor + 64 KB media Range GET probe (no ffmpeg)."""
    t0 = time.time()
    test_url = 'https://www.youtube.com/watch?v=jNQXAC9IVRw'
    opts = {
        'quiet': True,
        'no_warnings': True,
        'js_runtimes': {},
        'ffmpeg_location': '/nonexistent/downi-no-ffmpeg',
        'skip_download': True,
        'noplaylist': True,
        'ca_certs': certifi.where(),
        'socket_timeout': timeout,
        'retries': 0,
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(test_url, download=False)
        formats = (info or {}).get('formats') or []
        media_url = ''
        for f in formats:
            u = f.get('url') or ''
            proto = (f.get('protocol') or '').lower()
            if u.startswith('http') and proto in ('https', 'http', '') and f.get('ext') in ('mp4', 'm4a'):
                media_url = u
                break
        if not media_url and formats:
            for f in formats:
                u = f.get('url') or ''
                if u.startswith('http'):
                    media_url = u
                    break
        if not media_url:
            ms = int((time.time() - t0) * 1000)
            return False, ms, "Extractor returned no stream URL", ''
        ok, _, err, detail = _diag_http(media_url, timeout=timeout, method='GET', headers={'Range': 'bytes=0-65535'})
        ms = int((time.time() - t0) * 1000)
        if ok:
            return True, ms, '', f"Extractor + Range OK ({detail})"
        return False, ms, err, detail
    except Exception as e:
        ms = int((time.time() - t0) * 1000)
        return False, ms, f"Extractor failed: {e}", ''


def diagnose():
    """Real concurrent per-platform network diagnostics (DNS, TLS, reachability, Range probe)."""
    import yt_dlp
    import platform

    specs = [
        ('Internet reachability', lambda: _diag_http('https://connectivitycheck.gstatic.com/generate_204', timeout=5.0), 6.0),
        ('YouTube DNS', lambda: _diag_dns('www.youtube.com', timeout=4.0), 5.0),
        ('YouTube TLS', lambda: _diag_tls('www.youtube.com', timeout=4.0), 5.0),
        ('YouTube reachability', lambda: _diag_http('https://www.youtube.com/generate_204', timeout=5.0), 6.0),
        ('YouTube extractor + Range', lambda: _diag_yt_range(timeout=10.0), 14.0),
        ('TikTok (tiktok.com) DNS', lambda: _diag_dns('www.tiktok.com', timeout=4.0), 5.0),
        ('TikTok (tiktok.com) TLS', lambda: _diag_tls('www.tiktok.com', timeout=4.0), 5.0),
        ('TikTok reachability (tiktok.com)', lambda: _diag_http('https://www.tiktok.com/', timeout=5.0, method='HEAD'), 6.0),
        ('TikTok helper service DNS (tikwm.com)', lambda: _diag_dns('www.tikwm.com', timeout=4.0), 5.0),
        ('TikTok helper service TLS (tikwm.com)', lambda: _diag_tls('www.tikwm.com', timeout=4.0), 5.0),
        ('TikTok helper service (tikwm.com)', lambda: _diag_tikwm(timeout=5.0), 6.0),
        ('Instagram DNS', lambda: _diag_dns('www.instagram.com', timeout=4.0), 5.0),
        ('Instagram TLS', lambda: _diag_tls('www.instagram.com', timeout=4.0), 5.0),
        ('Instagram reachability', lambda: _diag_http('https://www.instagram.com/', timeout=5.0, method='HEAD'), 6.0),
    ]

    executor = concurrent.futures.ThreadPoolExecutor(max_workers=8)
    try:
        submitted = [(name, timeout_s, time.time(), executor.submit(fn)) for name, fn, timeout_s in specs]
        results = []
        for name, timeout_s, t0, future in submitted:
            remaining = max(0.2, timeout_s - (time.time() - t0))
            try:
                ok, ms, err, detail = future.result(timeout=remaining)
            except concurrent.futures.TimeoutError:
                ms = int((time.time() - t0) * 1000)
                ok, err, detail = False, f"Timed out after {timeout_s:.1f}s", ''
            except Exception as exc:
                ms = int((time.time() - t0) * 1000)
                ok, err, detail = False, str(exc), ''
            results.append({
                'check': name,
                'ok': ok,
                'ms': ms,
                'error': err,
                'detail': detail,
            })
    finally:
        executor.shutdown(wait=False, cancel_futures=True)

    return json.dumps({
        'results': results,
        'yt_dlp': yt_dlp.version.__version__,
        'python': platform.python_version(),
    })
