"""Local check of the YouTube adaptive-only merge fallback (PC side).

Runs the app's own downloader.download() at a small lane and reports whether the
split+merge contract came back (video_path + audio_path + merge:true), which is
what the Java layer hands to Mp4Merger. MediaMuxer is Android-only, so this does
NOT mux - pair it with tools/ffmpeg.exe to prove the pair is muxable.

  python tools/lane_check.py [url] [lane]
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'android', 'app', 'src', 'main', 'python'))
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

import downloader  # noqa: E402

url = sys.argv[1] if len(sys.argv) > 1 else 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'
lane = sys.argv[2] if len(sys.argv) > 2 else '360'
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'test_out', 'lane_check')
os.makedirs(out, exist_ok=True)


class Listener:
    def __init__(self):
        self.events = 0
        self.max_pct = 0.0

    def isCancelled(self):
        return False

    def onProgress(self, pct, downloaded, total, speed, eta):
        self.events += 1
        if pct > self.max_pct:
            self.max_pct = pct


def main():
    print('lane            : %s' % lane)
    print('lane height cap : %s' % downloader._lane_height(lane))
    listener = Listener()
    try:
        data = json.loads(downloader.download(url, out, lane, listener))
    except Exception as exc:
        print('RESULT          : FAILED')
        print('error           : %s: %s' % (type(exc).__name__, exc))
        return 1

    print('result keys     : %s' % ', '.join(sorted(data.keys())))
    print('merge           : %s' % data.get('merge'))
    print('title           : %s' % data.get('title'))
    print('ext             : %s' % data.get('ext'))
    print('progress events : %d (max %.1f%%)' % (listener.events, listener.max_pct))
    ok = True
    for key in ('video_path', 'audio_path', 'path'):
        if data.get(key):
            path = data[key]
            exists = os.path.exists(path)
            size = os.path.getsize(path) if exists else 0
            print('%-15s : %s (%d bytes)' % (key, os.path.basename(path), size))
            ok = ok and exists and size > 0
    if data.get('merge') and not (data.get('video_path') and data.get('audio_path')):
        ok = False
    print('RESULT          : %s' % ('PASS - merge contract returned' if ok else 'FAIL'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
