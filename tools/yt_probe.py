"""Read-only diagnostic: what does a platform serve to THIS network right now?

Mirrors downloader.py's proven option set (certifi, no client spoofing, no forced
UA) but stops at metadata, so it never writes media. Run it on the PC when a
device cell fails: same yt-dlp pin, comparable network path.

  python tools/yt_probe.py [url]
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'android', 'app', 'src', 'main', 'python'))
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

import certifi
from yt_dlp import YoutubeDL

import downloader  # the app's own module: same cleanup, same platform detection

DEFAULT = 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'


def main(url):
    clean = downloader._clean_url(url)
    print('url      : %s' % clean)
    print('platform : %s' % downloader._detect_platform(clean))
    opts = {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'ca_certs': certifi.where(),
        'socket_timeout': 30,
        'retries': 3,
        'skip_download': True,
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(clean, download=False)
    except Exception as exc:
        print('RESULT   : EXTRACTION FAILED')
        print('error    : %s: %s' % (type(exc).__name__, exc))
        return 1

    formats = info.get('formats') or []
    combined = [f for f in formats
                if f.get('vcodec') not in (None, 'none') and f.get('acodec') not in (None, 'none')]
    print('RESULT   : OK - %s' % info.get('title'))
    print('formats  : %d total, %d combined (video+audio)' % (len(formats), len(combined)))
    print('--- combined mp4 (the Vortex "best" path prefers these) ---')
    for f in combined:
        if f.get('ext') == 'mp4':
            print('  id=%-8s %sx%s  %s / %s' % (f.get('format_id'), f.get('width'),
                                                f.get('height'), f.get('vcodec'), f.get('acodec')))
    print('--- top adaptive video-only (what the 1080p merge path needs) ---')
    adaptive = [f for f in formats
                if f.get('vcodec') not in (None, 'none') and f.get('acodec') in (None, 'none')]
    for f in adaptive[-4:]:
        print('  id=%-8s %sx%s  %s' % (f.get('format_id'), f.get('width'),
                                       f.get('height'), f.get('vcodec')))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT))
