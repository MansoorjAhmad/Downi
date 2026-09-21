"""CI smoke test for the DOWNI extraction engine.

Approximates the mandatory READ_THIS_BEFORE_UPGRADE.md test matrix without an
Android device: it runs downloader.py's diagnose() (internet, YouTube,
Instagram, TikTok API) plus a real TikTok fast-path metadata fetch (tikwm).

Hard-fails on: engine import, diagnose() crash, internet unreachable.
Everything platform-specific (YouTube/Instagram/TikTok) is reported as a
warning: datacenter CI IPs are bot-checked or blocked by all three, so only
the device test matrix (READ_THIS_BEFORE_UPGRADE.md section 5) can truly
verify them.
"""
import json
import os
import sys

try:  # keep output ASCII-safe on Windows consoles
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'android', 'app', 'src', 'main', 'python'))

import downloader  # noqa: E402

TT_URL = 'https://www.tiktok.com/@tiktok/video/7106594312292453675'


def main() -> int:
    report = json.loads(downloader.diagnose())
    results = report.get('results') or []
    hard_failures = []
    warnings = []

    # 1) The authoritative TikTok check: the exact tikwm API the engine
    #    downloads through. Network-level blocks (CI IPs) are warnings.
    try:
        data = downloader._fetch_tiktok_data(TT_URL)
        item = data.get('data') or {}
        if data.get('code') == 0 and (item.get('hdplay') or item.get('play')):
            print(f"[OK] TikTok fast-path (tikwm) -> stream resolved: {str(item.get('title'))[:50]!r}")
        else:
            warnings.append(f"TikTok fast-path: API responded but no stream (code={data.get('code')}, msg={data.get('msg')})")
            print('[WARN] TikTok fast-path: API responded but no stream (check on a real device)')
    except Exception as exc:
        warnings.append(f'TikTok fast-path unreachable from this network: {exc}')
        print(f'[WARN] TikTok fast-path unreachable from this network: {exc}')

    # 2) The rest of the diagnose() report.
    for check in results:
        status = 'OK' if check['ok'] else 'WARN'
        print(f"[{status}] {check['check']} ({check['ms']} ms) {check['error']}")
        if check['ok']:
            continue
        if check['check'] == 'Internet reachability':
            hard_failures.append('Internet reachability failed - CI runner has no usable network')
        else:
            warnings.append(f"{check['check']}: {check['error']} (datacenter-IP sensitive; verify on a real device)")

    print(f"\nyt-dlp {report.get('yt_dlp')} - Python {report.get('python')}")
    for w in warnings:
        print(f'WARNING: {w}')

    if hard_failures:
        print('\nSMOKE TEST FAILED:')
        for f in hard_failures:
            print(f'  - {f}')
        return 1
    print('\nSMOKE TEST PASSED' + (f' ({len(warnings)} platform warnings - device matrix is the real gate)' if warnings else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main())
