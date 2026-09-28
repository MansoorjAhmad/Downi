"""Instrument the split+merge progress hook (PC side).

_download_split maps the video stream into [0.0, 0.7] and the audio stream into
[0.7, 0.95]. This prints the first/last events it actually emits, so a stalled or
mis-scaled progress bar can be told apart from a display bug.

  python tools/hook_probe.py [url] [height]
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'android', 'app', 'src', 'main', 'python'))
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

import downloader  # noqa: E402

url = sys.argv[1] if len(sys.argv) > 1 else 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'
height = int(sys.argv[2]) if len(sys.argv) > 2 else 240
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'test_out', 'hook_check')
os.makedirs(out, exist_ok=True)

events = []


class Listener:
    def isCancelled(self):
        return False

    def onProgress(self, pct, downloaded, total, speed, eta):
        events.append((round(float(pct), 2), int(downloaded), int(total)))


result = downloader._download_split(url, out, height, Listener())
print('height cap : %s' % height)
print('merge      : %s' % bool(result))
print('events     : %d' % len(events))
for label, chunk in (('first', events[:5]), ('last', events[-5:])):
    for pct, downloaded, total in chunk:
        print('  %-5s pct=%6.2f%%  downloaded=%9d  total=%9d' % (label, pct, downloaded, total))
if events:
    print('max pct    : %.2f%%' % max(e[0] for e in events))
