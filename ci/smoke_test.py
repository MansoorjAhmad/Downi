"""CI smoke test for the DOWNI extraction engine.

Approximates the mandatory READ_THIS_BEFORE_UPGRADE.md test matrix without an
Android device: it runs downloader.py's diagnose() (internet, YouTube,
Instagram, TikTok API) plus a real TikTok fast-path metadata fetch (tikwm).

Hard-fails on: engine import, internet reachability, TikTok fast-path.
Warns only on: YouTube/Instagram extraction — GitHub's datacenter IPs are
bot-checked by both platforms, which says nothing about on-device behavior.
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'android', 'app', 'src', 'main', 'python'))

import downloader  # noqa: E402

TT_URL = 'https://www.tiktok.com/@tiktok/video/7106594312292453675'


def main() -> int:
    report = json.loads(downloader.diagnose())
    hard_failures = []
    warnings = []

    for check in report['results']:
        status = 'OK' if check['ok'] else 'FAIL'
        print(f"[{status}] {check['check']} ({check['ms']} ms) {check['error']}")
        if check['ok']:
            continue
        if check['check'] in ('Internet reachability', 'TikTok API reachability'):
            hard_failures.append(check['check'])
        else:
            warnings.append(f"{check['check']}: {check['error']}")

    # TikTok fast-path: the exact API the app downloads through.
    try:
        data = downloader._fetch_tiktok_data(TT_URL)
        if data.get('code') != 0:
            hard_failures.append(f"tikwm API: {data.get('msg')}")
            print(f"[FAIL] TikTok fast-path → {data.get('msg')}")
        else:
            item = data.get('data') or {}
            if not (item.get('hdplay') or item.get('play')):
                hard_failures.append('tikwm returned no playable stream')
                print('[FAIL] TikTok fast-path → no playable stream')
            else:
                print(f"[OK] TikTok fast-path → {str(item.get('title'))[:60]!r}")
    except Exception as exc:
        hard_failures.append(f'TikTok fast-path: {exc}')
        print(f"[FAIL] TikTok fast-path: {exc}")

    print(f"\nyt-dlp {report.get('yt_dlp')} · Python {report.get('python')}")
    for w in warnings:
        print(f"WARNING (datacenter-IP sensitive, must pass on a real device): {w}")

    if hard_failures:
        print('\nSMOKE TEST FAILED:')
        for f in hard_failures:
            print(f'  - {f}')
        return 1
    print('\nSMOKE TEST PASSED' + (f' ({len(warnings)} datacenter-only warnings)' if warnings else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main())
