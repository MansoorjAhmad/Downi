"""Mocked unit tests for DOWNI's Python extraction engine (tools/test_engine.py).

Runs offline in plain Python (no live network required) and verifies:
- inspect() real YouTube format inspection, filesize estimates, honest M4A/WebM audio labels, TikTok & non-YT formats
- Silent 360p regression guard (720/best on YT routes to _download_split instead of progressive itag 18)
- _clean_url() URL extraction and short-link expansion
- _download_split() height cap preservation, single-extraction reuse, and cancellation [cause:cancelled]
- _run_ydl_with_fragment_fallback() 3 -> 1 fragment fallback on 403 (and never on a rate limit)
- extract-cache reuse across the inspect -> grab gap (one anonymous request per grab)
- playlist_inspect() error propagation via truth layer
- truth.py Tikwm rate-limit classification and non-loose cancel handling
- diagnose() concurrency, 403/429 failure verdicts, and _diag_yt_range() probe

Run:
    tools\\python311\\python.exe tools\\test_engine.py
"""

import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.join(os.path.dirname(HERE), 'android', 'app', 'src', 'main', 'python')
sys.path.insert(0, ENGINE)

import downloader  # noqa: E402
import truth  # noqa: E402


class _FakeCancelListener:
    def __init__(self, cancelled=False):
        self._cancelled = cancelled
        self.events = []

    def isCancelled(self):
        return self._cancelled

    def onProgress(self, pct, downloaded, total, speed, eta):
        self.events.append((pct, downloaded, total, speed, eta))


class TestCleanUrlAndPlatform(unittest.TestCase):
    def test_extracts_url_and_strips_trailing_punctuation(self):
        raw = 'Check this out: https://www.youtube.com/watch?v=dQw4w9WgXcQ!!)'
        self.assertEqual(downloader._clean_url(raw), 'https://www.youtube.com/watch?v=dQw4w9WgXcQ')

    def test_expands_short_tiktok_url_with_ssl_context(self):
        class _Resp:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def geturl(self):
                return 'https://www.tiktok.com/@user/video/123456789'

        seen_kwargs = {}

        def fake_urlopen(req, timeout=10, context=None):
            seen_kwargs['timeout'] = timeout
            seen_kwargs['has_context'] = context is not None
            return _Resp()

        with patch('urllib.request.urlopen', side_effect=fake_urlopen):
            res = downloader._clean_url('https://vt.tiktok.com/ZSNabcdef/')

        self.assertEqual(res, 'https://www.tiktok.com/@user/video/123456789')
        self.assertTrue(seen_kwargs.get('has_context'))

    def test_detect_platform(self):
        self.assertEqual(downloader._detect_platform('https://youtu.be/abc'), 'youtube')
        self.assertEqual(downloader._detect_platform('https://www.tiktok.com/@a/video/1'), 'tiktok')
        self.assertEqual(downloader._detect_platform('https://www.instagram.com/reel/xyz/'), 'instagram')
        self.assertEqual(downloader._detect_platform('https://vimeo.com/123'), 'other')


class TestInspect(unittest.TestCase):
    def test_youtube_inspect_reads_real_formats_and_honest_audio_label(self):
        fake_info = {
            'id': 'yt720',
            'title': 'Sample Clip - YouTube',
            'uploader': 'ChannelX',
            'duration': 95,
            'thumbnail': 'https://i.ytimg.com/vi/yt720/hqdefault.jpg',
            'webpage_url': 'https://www.youtube.com/watch?v=yt720',
            'formats': [
                {'format_id': '18', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 360, 'filesize': 2_000_000},
                {'format_id': '135', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'none', 'height': 480, 'filesize': 4_000_000},
                {'format_id': '136', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'none', 'height': 720, 'filesize': 9_000_000},
                {'format_id': '140', 'ext': 'm4a', 'vcodec': 'none', 'acodec': 'mp4a.40.2', 'abr': 128, 'filesize': 1_500_000},
            ],
        }

        class _FakeYdl:
            def __init__(self, opts):
                self.opts = opts

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                return fake_info

        with patch.object(downloader, 'YoutubeDL', _FakeYdl):
            out = json.loads(downloader.inspect('https://www.youtube.com/watch?v=yt720'))

        ids = [f['id'] for f in out['formats']]
        self.assertNotIn('1080', ids)
        # Top tier 720p matches best verbatim, so redundant HD duplicate is omitted.
        self.assertEqual(ids, ['720', '480', '360', 'audio'])
        by_id = {f['id']: f for f in out['formats']}
        self.assertEqual(by_id['720']['filesize'], 9_000_000 + 1_500_000)
        self.assertEqual(by_id['480']['filesize'], 4_000_000 + 1_500_000)
        self.assertEqual(by_id['360']['filesize'], 2_000_000)
        self.assertEqual(by_id['audio']['ext'], 'm4a')
        self.assertEqual(by_id['audio']['badge'], 'M4A')
        self.assertNotIn('MP3', by_id['audio']['label'])
        self.assertEqual(by_id['audio']['filesize'], 1_500_000)

    def test_youtube_inspect_webm_audio_label_when_no_m4a(self):
        fake_info = {
            'id': 'ytwebm',
            'title': 'WebM Only Audio',
            'duration': 30,
            'formats': [
                {'format_id': '18', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 360, 'filesize': 1_000_000},
                {'format_id': '251', 'ext': 'webm', 'vcodec': 'none', 'acodec': 'opus', 'abr': 160, 'filesize': 600_000},
            ],
        }

        class _FakeYdl:
            def __init__(self, opts):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                return fake_info

        with patch.object(downloader, 'YoutubeDL', _FakeYdl):
            out = json.loads(downloader.inspect('https://www.youtube.com/watch?v=ytwebm'))

        audio = next(f for f in out['formats'] if f['id'] == 'audio')
        self.assertEqual(audio['ext'], 'webm')
        self.assertEqual(audio['badge'], 'WEBM')
        self.assertIn('WEBM', audio['label'])
        self.assertEqual(audio['filesize'], 600_000)

    def test_tiktok_inspect_returns_filesize_and_mp3_audio(self):
        fake_tikwm = {
            'code': 0,
            'data': {
                'title': 'Cool clip #fyp',
                'duration': 15,
                'cover': '/cover.jpg',
                'hd_size': 3_360_445,
                'author': {'nickname': 'Creator'},
            },
        }
        with patch.object(downloader, '_fetch_tiktok_data', return_value=fake_tikwm):
            out = json.loads(downloader.inspect('https://www.tiktok.com/@a/video/1'))

        self.assertEqual(out['platform'], 'tiktok')
        self.assertEqual(out['formats'][0]['filesize'], 3_360_445)
        self.assertEqual(out['formats'][1]['ext'], 'mp3')
        self.assertEqual(out['formats'][1]['badge'], 'MP3')

    def test_instagram_inspect_single_best_with_filesize(self):
        fake_info = {
            'id': 'ig1',
            'title': 'IG Reel',
            'uploader': 'creator',
            'duration': 19,
            'thumbnail': 'https://cdn.example.com/t.jpg',
            'formats': [
                {'format_id': 'v1080', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'none', 'height': 1080, 'filesize': 8_000_000},
                {'format_id': 'c720', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 720, 'filesize': 4_603_266},
            ],
        }

        class _FakeYdl:
            def __init__(self, opts):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                return fake_info

        with patch.object(downloader, 'YoutubeDL', _FakeYdl):
            out = json.loads(downloader.inspect('https://www.instagram.com/reel/DcZTAe4jKBp/'))

        self.assertEqual(len(out['formats']), 1)
        self.assertEqual(out['formats'][0]['id'], 'best')
        self.assertEqual(out['formats'][0]['filesize'], 4_603_266)


class TestDownloadPipelineAndSplit(unittest.TestCase):
    def test_youtube_720_and_best_route_to_split_avoiding_360p_downgrade(self):
        split_calls = []

        def fake_split(url, target_dir, max_height=1080, progress_listener=None, **kwargs):
            split_calls.append(max_height)
            return {
                'merge': True,
                'video_path': os.path.join(target_dir, 'v.mp4'),
                'audio_path': os.path.join(target_dir, 'a.m4a'),
                'title': 'Test',
                'ext': 'mp4',
                'platform': kwargs.get('platform', 'youtube'),
            }

        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(downloader, '_download_split', side_effect=fake_split):
                res_720 = json.loads(downloader.download('https://www.youtube.com/watch?v=abc', tmp, '720'))
                res_best_34 = json.loads(downloader.download('https://www.youtube.com/watch?v=abc', tmp, 'best', sdk_int=34))
                res_best_28 = json.loads(downloader.download('https://www.youtube.com/watch?v=abc', tmp, 'best', sdk_int=28))

        self.assertEqual(split_calls, [720, 2160, 1080])
        self.assertTrue(res_720['merge'])
        self.assertTrue(res_best_34['merge'])
        self.assertTrue(res_best_28['merge'])

    def test_download_split_height_caps_and_single_extract(self):
        seen_formats = []
        extract_calls = [0]
        process_calls = [0]

        fake_info = {
            'id': 'yt1',
            'title': 'Test Split',
            'extractor': 'youtube',
            '_type': 'video',
            'formats': [
                {'format_id': '135', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'none', 'height': 480, 'url': 'https://ex/v.mp4'},
                {'format_id': '140', 'ext': 'm4a', 'vcodec': 'none', 'acodec': 'mp4a', 'url': 'https://ex/a.m4a'},
            ],
        }

        with tempfile.TemporaryDirectory() as tmp:
            v_file = os.path.join(tmp, 'Test Split-yt1.mp4')
            a_file = os.path.join(tmp, 'Test Split-yt1.m4a')

            class _FakeYdl:
                def __init__(self, opts):
                    self.opts = opts
                    if not opts.get('skip_download'):
                        seen_formats.append(opts['format'])

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return False

                def extract_info(self, url, download=False):
                    extract_calls[0] += 1
                    return fake_info

                def process_video_result(self, info_dict, download=True):
                    process_calls[0] += 1
                    fmt = self.opts.get('format', '')
                    if 'bestaudio' in fmt:
                        with open(a_file, 'wb') as f:
                            f.write(b'audio')
                        return {'_type': 'video', 'id': 'yt1', 'title': 'Test Split', 'ext': 'm4a'}
                    with open(v_file, 'wb') as f:
                        f.write(b'video')
                    return {'_type': 'video', 'id': 'yt1', 'title': 'Test Split', 'ext': 'mp4'}

                def prepare_filename(self, info_dict):
                    return a_file if info_dict.get('ext') == 'm4a' else v_file

            with patch.object(downloader, 'YoutubeDL', _FakeYdl):
                res = downloader._download_split('https://www.youtube.com/watch?v=yt1', tmp, max_height=480)

        self.assertIsNotNone(res)
        self.assertTrue(res['merge'])
        self.assertEqual(extract_calls[0], 1)
        self.assertEqual(process_calls[0], 2)
        video_selector = seen_formats[0]
        # Every fallback branch in the video selector must honor height<=480
        for branch in video_selector.split('/'):
            self.assertIn('[height<=480]', branch)

    def test_download_cancel_raises_explicit_cancelled_cause(self):
        listener = _FakeCancelListener(cancelled=True)
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(RuntimeError) as ctx:
                downloader.download('https://www.youtube.com/watch?v=abc', tmp, '1080', listener)
        cause, raw = truth.split_cause(str(ctx.exception))
        self.assertEqual(cause, 'cancelled')
        self.assertIn('cancel', raw.lower())

    def test_fragment_fallback_3_to_1_on_403(self):
        attempts = []

        class _FakeYdl:
            def __init__(self, opts):
                attempts.append(opts['concurrent_fragment_downloads'])

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=True):
                if len(attempts) == 1:
                    raise RuntimeError('HTTP Error 403: Forbidden')
                return {'title': 'Recovered', 'ext': 'mp4'}

            def prepare_filename(self, res):
                return '/tmp/recovered.mp4'

        with patch.object(downloader, 'YoutubeDL', _FakeYdl), patch('time.sleep'):
            res, path = downloader._run_ydl_with_fragment_fallback(
                {'quiet': True, 'concurrent_fragment_downloads': 3},
                'https://example.com/v',
            )

        self.assertEqual(attempts, [3, 1])
        self.assertEqual(path, '/tmp/recovered.mp4')

    def test_rate_limit_is_never_retried(self):
        attempts = []

        class _RateLimitedYdl:
            def __init__(self, opts):
                attempts.append(opts['concurrent_fragment_downloads'])

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=True):
                raise RuntimeError(
                    'ERROR: [Instagram] Df7j0cXs5J3: The webpage request was redirected '
                    'to the login page. You have exceeded the rate-limit for accessing '
                    'posts anonymously')

        with patch.object(downloader, 'YoutubeDL', _RateLimitedYdl), patch('time.sleep'):
            with self.assertRaises(RuntimeError):
                downloader._run_ydl_with_fragment_fallback(
                    {'quiet': True, 'concurrent_fragment_downloads': 3},
                    'https://www.instagram.com/reel/Df7j0cXs5J3/',
                )

        # One request. The old code fired a second one 1.5 s later, which is
        # what deepened the limit it was trying to escape.
        self.assertEqual(attempts, [3])

    def test_inspect_metadata_survives_the_grab_gap(self):
        url = 'https://www.instagram.com/reel/Df7j0cXs5J3/'
        info = {'title': 'Video by someone', 'formats': [{'format_id': 'best', 'url': 'https://cdn.example/v.mp4'}]}
        downloader.clear_caches()
        with patch.object(downloader.time, 'monotonic', return_value=1000.0):
            downloader._put_cached_info([url], info)
        # Five minutes later - the old 150 s TTL had expired, so the grab made
        # a second anonymous extraction for the same tap.
        with patch.object(downloader.time, 'monotonic', return_value=1300.0):
            cached = downloader._get_cached_info(url)
        self.assertIsNotNone(cached)
        self.assertEqual(cached.get('title'), 'Video by someone')
        downloader.clear_caches()

    def test_instagram_grab_never_reextracts_after_a_failure(self):
        """One anonymous page request per grab: a failed grab reports the truth
        instead of evicting the cache and asking Instagram a second time."""
        url = 'https://www.instagram.com/reel/nolimit/'
        info = {'title': 'Video by someone', 'formats': [
            {'format_id': 'best', 'ext': 'mp4', 'url': 'https://cdn.example/v.mp4'},
        ]}
        downloader.clear_caches()
        with patch.object(downloader.time, 'monotonic', return_value=1000.0):
            downloader._put_cached_info([url], info)
        calls = []

        def _boom(opts, u, info=None, progress_listener=None):
            calls.append(info is not None)
            raise RuntimeError(
                '[cause:rate_limited] The webpage request was redirected to the login page')

        with patch.object(downloader, '_run_ydl_with_fragment_fallback', side_effect=_boom), \
                patch.object(downloader, '_evict_cached_info') as evict, \
                patch.object(downloader.time, 'monotonic', return_value=1000.0):
            with tempfile.TemporaryDirectory() as tmp:
                with self.assertRaises(RuntimeError):
                    downloader._download_lanes(url, tmp, 'best', sdk_int=34)

        self.assertEqual(calls, [True])
        evict.assert_not_called()
        # The inspector's cache entry survives the failed grab.
        with patch.object(downloader.time, 'monotonic', return_value=1010.0):
            self.assertIsNotNone(downloader._get_cached_info(url))
        downloader.clear_caches()

    def test_playlist_inspect_propagates_truth_error(self):
        class _FailYdl:
            def __init__(self, opts):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                raise RuntimeError('HTTP Error 429: Too Many Requests')

        with patch.object(downloader, 'YoutubeDL', _FailYdl):
            with self.assertRaises(RuntimeError) as ctx:
                downloader.playlist_inspect('https://www.youtube.com/playlist?list=PL123')

        cause, _ = truth.split_cause(str(ctx.exception))
        self.assertEqual(cause, 'rate_limited')


class TestTruthAndDiagnose(unittest.TestCase):
    def test_truth_tikwm_limit_and_no_loose_cancel_false_positive(self):
        self.assertEqual(truth.classify('Free Api Limit: 1 request/second'), 'rate_limited')
        self.assertEqual(truth.classify('ERROR: Cancel Culture: Video unavailable'), 'private')
        self.assertEqual(truth.classify('ERROR: How to Cancel: unexpected error'), 'unknown')
        self.assertEqual(truth.classify('[cause:cancelled] stopped'), 'cancelled')

    def test_diag_http_treats_403_and_429_as_failures(self):
        for code in (403, 429):
            err = urllib.error.HTTPError('https://example.com', code, 'err', {}, io.BytesIO(b''))
            with patch('urllib.request.urlopen', side_effect=err):
                ok, _, msg, _ = downloader._diag_http('https://example.com')
            self.assertFalse(ok)
            self.assertIn(str(code), msg)

    def test_diag_yt_range_succeeds_with_range_get(self):
        class _FakeYdl:
            def __init__(self, opts):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                return {
                    'formats': [
                        {'ext': 'mp4', 'protocol': 'https', 'url': 'https://cdn.example.com/vid.mp4'}
                    ]
                }

        class _FakeResp:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def getcode(self):
                return 206

        seen_headers = {}

        def fake_urlopen(req, timeout=6.0, context=None):
            seen_headers.update({k.lower(): v for k, v in req.header_items()})
            return _FakeResp()

        with patch.object(downloader, 'YoutubeDL', _FakeYdl), patch('urllib.request.urlopen', side_effect=fake_urlopen):
            ok, _, err, detail = downloader._diag_yt_range(timeout=5.0)

        self.assertTrue(ok)
        self.assertEqual(err, '')
        self.assertEqual(seen_headers.get('range'), 'bytes=0-65535')
        self.assertIn('206', detail)

    def test_diagnose_runs_concurrently_and_preserves_order(self):
        with patch.object(downloader, '_diag_http', return_value=(True, 10, '', 'HTTP 200')), \
             patch.object(downloader, '_diag_dns', return_value=(True, 5, '', 'Resolved 1.1.1.1')), \
             patch.object(downloader, '_diag_tls', return_value=(True, 8, '', 'TLS OK')), \
             patch.object(downloader, '_diag_tikwm', return_value=(True, 12, '', 'API OK')), \
             patch.object(downloader, '_diag_yt_range', return_value=(True, 15, '', 'Extractor + Range OK')):
            report = json.loads(downloader.diagnose())

        self.assertEqual(len(report['results']), 14)
        self.assertEqual(report['results'][0]['check'], 'Internet reachability')
        self.assertEqual(report['results'][4]['check'], 'YouTube extractor + Range')
        self.assertTrue(all(r['ok'] for r in report['results']))


class TestStage3Features(unittest.TestCase):
    def test_youtube_1440p_4k_api_gates_and_opus_audio(self):
        fake_info = {
            'id': 'yt4k',
            'title': '4K Sample',
            'duration': 120,
            'formats': [
                {'format_id': '18', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 360, 'filesize': 2_000_000},
                {'format_id': '137', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'none', 'height': 1080, 'filesize': 10_000_000},
                {'format_id': '271', 'ext': 'webm', 'vcodec': 'vp9', 'acodec': 'none', 'height': 1440, 'filesize': 25_000_000},
                {'format_id': '401', 'ext': 'mp4', 'vcodec': 'av01.0.12M.08', 'acodec': 'none', 'height': 2160, 'filesize': 55_000_000},
                {'format_id': '140', 'ext': 'm4a', 'vcodec': 'none', 'acodec': 'mp4a.40.2', 'abr': 128, 'filesize': 2_000_000},
                {'format_id': '251', 'ext': 'webm', 'vcodec': 'none', 'acodec': 'opus', 'abr': 160, 'filesize': 2_500_000},
            ],
        }

        class _FakeYdl:
            def __init__(self, opts):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                return fake_info

        with patch.object(downloader, 'YoutubeDL', _FakeYdl):
            ids_28 = [f['id'] for f in json.loads(downloader.inspect('https://youtu.be/yt4k', sdk_int=28))['formats']]
            ids_30 = [f['id'] for f in json.loads(downloader.inspect('https://youtu.be/yt4k', sdk_int=30))['formats']]
            ids_34 = [f['id'] for f in json.loads(downloader.inspect('https://youtu.be/yt4k', sdk_int=34))['formats']]

        self.assertEqual(ids_28, ['1080', '360', 'audio', 'audio_opus'])
        self.assertEqual(ids_30, ['1440', '1080', '360', 'audio', 'audio_opus'])
        self.assertEqual(ids_34, ['2160', '1440', '1080', '360', 'audio', 'audio_opus'])

    def test_download_split_4k_vp9_av1_selector_by_sdk(self):
        sel_34 = downloader._build_split_video_selector(2160, 34)
        sel_30 = downloader._build_split_video_selector(2160, 30)
        self.assertIn('av01', sel_34)
        self.assertIn('vp9', sel_34)
        self.assertNotIn('av01', sel_30)
        self.assertIn('vp9', sel_30)
        for branch in sel_34.split('/'):
            self.assertIn('[height<=2160]', branch)

    def test_instagram_1080p_dash_inspect_and_split_merge(self):
        fake_ig = {
            'id': 'ig1080',
            'title': 'IG 1080p Reel',
            'uploader': 'creator',
            'duration': 20,
            'thumbnail': 'https://cdn.example.com/ig.jpg',
            'formats': [
                {'format_id': 'c720', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 720, 'filesize': 4_000_000},
                {'format_id': 'v1080', 'ext': 'mp4', 'vcodec': 'avc1.64002a', 'acodec': 'none', 'height': 1080, 'filesize': 9_000_000},
                {'format_id': 'a140', 'ext': 'm4a', 'vcodec': 'none', 'acodec': 'mp4a.40.2', 'abr': 128, 'filesize': 1_000_000},
            ],
        }

        class _FakeYdl:
            def __init__(self, opts):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                return fake_ig

        with patch.object(downloader, 'YoutubeDL', _FakeYdl):
            insp = json.loads(downloader.inspect('https://www.instagram.com/reel/ig1080/'))
        ids = [f['id'] for f in insp['formats']]
        self.assertIn('1080', ids)
        by_id = {f['id']: f for f in insp['formats']}
        self.assertEqual(by_id['1080']['filesize'], 10_000_000)
        # v3.8.0 parity: the default "best" row is the single progressive file;
        # DASH merge is an explicit opt-in, so it never triggers a second
        # anonymous page request during a normal grab.
        self.assertEqual(by_id['best']['filesize'], 4_000_000)
        self.assertNotIn('HD', by_id['best']['label'])

        with tempfile.TemporaryDirectory() as tmp:
            v_file = os.path.join(tmp, 'IG 1080p Reel-ig1080.mp4')
            a_file = os.path.join(tmp, 'IG 1080p Reel-ig1080.m4a')

            class _FakeDlYdl:
                def __init__(self, opts):
                    self.opts = opts

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return False

                def extract_info(self, url, download=False):
                    return fake_ig

                def process_video_result(self, info_dict, download=True):
                    fmt = self.opts.get('format', '')
                    if 'bestaudio' in fmt:
                        with open(a_file, 'wb') as f:
                            f.write(b'aac')
                        return {'_type': 'video', 'id': 'ig1080', 'title': 'IG 1080p Reel', 'ext': 'm4a'}
                    with open(v_file, 'wb') as f:
                        f.write(b'h264')
                    return {'_type': 'video', 'id': 'ig1080', 'title': 'IG 1080p Reel', 'ext': 'mp4'}

                def prepare_filename(self, info_dict):
                    return a_file if info_dict.get('ext') == 'm4a' else v_file

            with patch.object(downloader, 'YoutubeDL', _FakeDlYdl):
                # "best" is the single progressive file (no merge).
                dl = json.loads(downloader.download('https://www.instagram.com/reel/ig1080/', tmp, 'best'))
                # The explicit HD lane still merges on device.
                hd = json.loads(downloader.download('https://www.instagram.com/reel/ig1080/', tmp, '1080'))

        self.assertFalse(dl.get('merge', False))
        self.assertEqual(dl['platform'], 'instagram')
        self.assertTrue(hd['merge'])
        self.assertEqual(hd['platform'], 'instagram')

    def test_subtitles_inspect_and_download_sidecar(self):
        fake_info = {
            'id': 'sub1',
            'title': 'Sub Video',
            'duration': 60,
            'language': 'ur',
            'subtitles': {'ur': [{'ext': 'vtt'}], 'live_chat': [{}]},
            'automatic_captions': {'en': [{'ext': 'srt'}], 'es': [{'ext': 'srt'}]},
            'formats': [
                {'format_id': '18', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 360, 'filesize': 1_000_000},
            ],
        }

        class _FakeInspectYdl:
            def __init__(self, opts):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                return fake_info

        with patch.object(downloader, 'YoutubeDL', _FakeInspectYdl):
            insp = json.loads(downloader.inspect('https://vimeo.com/123'))
        self.assertTrue(insp['has_subtitles'])
        self.assertEqual(insp['subtitle_langs'][:2], ['ur', 'en'])
        self.assertNotIn('live_chat', insp['subtitle_langs'])

        seen_opts = {}
        with tempfile.TemporaryDirectory() as tmp:
            vid_path = os.path.join(tmp, 'Sub Video-sub1.mp4')
            sub_path = os.path.join(tmp, 'Sub Video-sub1.en.srt')

            class _FakeDlYdl:
                def __init__(self, opts):
                    seen_opts.update(opts)

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return False

                def extract_info(self, url, download=True):
                    with open(vid_path, 'wb') as f:
                        f.write(b'mp4')
                    with open(sub_path, 'w', encoding='utf-8') as f:
                        f.write('1\n00:00:00,000 --> 00:00:01,000\nHello\n')
                    return {'title': 'Sub Video', 'ext': 'mp4'}

                def prepare_filename(self, res):
                    return vid_path

            with patch.object(downloader, 'YoutubeDL', _FakeDlYdl):
                res = json.loads(downloader.download(
                    'https://vimeo.com/123', tmp, 'best',
                    opts_json=json.dumps({'subtitles': True, 'subtitle_langs': ['en']})
                ))

        self.assertTrue(seen_opts.get('writesubtitles'))
        self.assertTrue(seen_opts.get('writeautomaticsub'))
        self.assertEqual(seen_opts.get('subtitleslangs'), ['en'])
        self.assertEqual(seen_opts.get('ffmpeg_location'), '/nonexistent/downi-no-ffmpeg')
        self.assertEqual(res.get('subtitle_paths'), [sub_path])

    def test_playlist_inspect_pagination(self):
        seen_opts = {}

        class _FakePlaylistYdl:
            def __init__(self, opts):
                seen_opts.update(opts)

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                return {
                    'title': 'Big Playlist',
                    'playlist_count': 120,
                    'entries': [
                        {
                            'url': f'vid000000{i}' if i != 51 else 'dQw4w9WgXcQ',
                            'title': f'Video {i}',
                            'duration': 60,
                            'ie_key': 'Youtube',
                            **({'thumbnails': [{'url': 'https://cdn.example/t52.jpg'}]} if i == 52 else {}),
                        }
                        for i in range(51, 101)
                    ],
                }

        with patch.object(downloader, 'YoutubeDL', _FakePlaylistYdl):
            res = downloader.playlist_inspect('https://www.youtube.com/playlist?list=PL123', offset=50, limit=50)

        self.assertEqual(seen_opts.get('playliststart'), 51)
        self.assertEqual(seen_opts.get('playlistend'), 100)
        self.assertEqual(res['title'], 'Big Playlist')
        self.assertEqual(res['total_count'], 120)
        self.assertEqual(len(res['items']), 50)
        self.assertEqual(res['items'][0]['thumbnail'], 'https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg')
        self.assertEqual(res['items'][1]['thumbnail'], 'https://cdn.example/t52.jpg')
        self.assertTrue(res['has_more'])
        self.assertEqual(res['next_offset'], 100)

    def test_audio_download_calls_tagger_and_reports_tagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            m4a_path = os.path.join(tmp, 'Track-1.m4a')
            webm_path = os.path.join(tmp, 'Track-1.webm')

            class _FakeYdl:
                def __init__(self, opts):
                    self.fmt = opts.get('format', '')

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return False

                def extract_info(self, url, download=True):
                    p = webm_path if 'webm' in self.fmt else m4a_path
                    with open(p, 'wb') as f:
                        f.write(b'audio')
                    return {'title': 'Track', 'uploader': 'Artist', 'thumbnail': 'https://ex/t.jpg', 'ext': 'webm' if 'webm' in self.fmt else 'm4a'}

                def prepare_filename(self, res):
                    return webm_path if res.get('ext') == 'webm' else m4a_path

            with patch.object(downloader, 'YoutubeDL', _FakeYdl), \
                 patch.object(downloader.audio_tagger, 'tag_audio_file', return_value=True) as mock_tag:
                res_m4a = json.loads(downloader.download('https://www.youtube.com/watch?v=1', tmp, 'audio'))
                res_opus = json.loads(downloader.download('https://www.youtube.com/watch?v=1', tmp, 'audio_opus'))

        self.assertTrue(res_m4a['tagged'])
        self.assertFalse(res_opus['tagged'])
        self.assertEqual(mock_tag.call_count, 1)

    def test_extract_subtitle_langs_prioritizes_clean_english_over_hashed_codes(self):
        has_subs, langs = downloader._extract_subtitle_langs({
            'language': 'zh-HK-2epwjfpmbia',
            'subtitles': {
                'zh-HK-2epwjfpmbia': [{'ext': 'vtt'}],
                'fr-FR': [{'ext': 'vtt'}],
                'en-orig': [{'ext': 'vtt'}],
            },
            'automatic_captions': {'en': [{'ext': 'vtt'}]},
        })
        self.assertTrue(has_subs)
        self.assertEqual(langs[0], 'en')
        self.assertIn('en-orig', langs[:2])

    def test_download_split_webm_uses_distinct_outtmpl(self):
        fake_yt = {
            'id': 'uhd1',
            'title': 'UHD Clip',
            'formats': [
                {'format_id': '271', 'ext': 'webm', 'vcodec': 'vp9', 'acodec': 'none', 'height': 1440, 'filesize': 10_000_000},
                {'format_id': '251', 'ext': 'webm', 'vcodec': 'none', 'acodec': 'opus', 'abr': 160, 'filesize': 200_000},
            ],
        }
        seen_outtmpls = []

        with tempfile.TemporaryDirectory() as tmp:
            class _FakeUhdYdl:
                def __init__(self, opts):
                    self.opts = opts
                    if opts.get('outtmpl') and not opts.get('skip_download'):
                        seen_outtmpls.append(opts['outtmpl'])

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return False

                def extract_info(self, url, download=False):
                    return fake_yt

                def _path_for(self, info_dict):
                    tmpl = self.opts.get('outtmpl', '')
                    suffix = '.v.' if '.v.' in tmpl else ('.a.' if '.a.' in tmpl else '.')
                    return os.path.join(tmp, f"UHD Clip-uhd1{suffix}{info_dict.get('ext', 'webm')}")

                def process_video_result(self, info_dict, download=True):
                    is_aud = 'bestaudio' in (self.opts.get('format') or '')
                    ext = 'webm'
                    p = self._path_for({'ext': ext})
                    with open(p, 'wb') as f:
                        f.write(b'opus' if is_aud else b'vp9video')
                    return {'_type': 'video', 'id': 'uhd1', 'title': 'UHD Clip', 'ext': ext}

                def prepare_filename(self, info_dict):
                    return self._path_for(info_dict)

            with patch.object(downloader, 'YoutubeDL', _FakeUhdYdl):
                res = json.loads(downloader.download('https://youtu.be/uhd1', tmp, '1440', sdk_int=33))
            self.assertTrue(os.path.exists(res['video_path']))
            self.assertTrue(os.path.exists(res['audio_path']))
            self.assertNotEqual(res['video_path'], res['audio_path'])
            self.assertEqual(res['ext'], 'webm')
            with open(res['video_path'], 'rb') as fv, open(res['audio_path'], 'rb') as fa:
                self.assertEqual(fv.read(), b'vp9video')
                self.assertEqual(fa.read(), b'opus')
            self.assertEqual(len(seen_outtmpls), 2)
            self.assertNotEqual(seen_outtmpls[0], seen_outtmpls[1])

    def test_instagram_vp9_aac_skips_unmuxable_split_and_uses_progressive(self):
        fake_ig = {
            'id': 'igvp9',
            'title': 'IG VP9 Reel',
            'formats': [
                {'format_id': '1', 'ext': 'mp4', 'vcodec': None, 'acodec': None, 'height': None},
                {'format_id': 'dash-v', 'ext': 'mp4', 'vcodec': 'vp09.00.30.08', 'acodec': 'none', 'height': 854},
                {'format_id': 'dash-a', 'ext': 'm4a', 'vcodec': 'none', 'acodec': 'mp4a.40.5'},
            ],
        }
        with tempfile.TemporaryDirectory() as tmp:
            prog_file = os.path.join(tmp, 'IG VP9 Reel-igvp9.mp4')

            class _FakeYdl:
                def __init__(self, opts):
                    self.opts = opts

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return False

                def extract_info(self, url, download=False):
                    if not download:
                        return fake_ig
                    with open(prog_file, 'wb') as f:
                        f.write(b'progressive-mp4')
                    return {'_type': 'video', 'id': 'igvp9', 'title': 'IG VP9 Reel', 'ext': 'mp4'}

                def prepare_filename(self, info_dict):
                    return prog_file

            with patch.object(downloader, 'YoutubeDL', _FakeYdl):
                dl = json.loads(downloader.download('https://www.instagram.com/reel/igvp9/', tmp, 'best', sdk_int=33))
            self.assertFalse(dl.get('merge', False))
            self.assertEqual(dl['path'], prog_file)

    def test_inspect_to_download_reuses_extract_cache_and_evicts_on_stale_error(self):
        downloader.clear_caches()
        fake_yt = {
            'id': 'cache1',
            'title': 'Cached Clip',
            'webpage_url': 'https://www.youtube.com/watch?v=cache1',
            'formats': [
                {'format_id': '137', 'ext': 'mp4', 'vcodec': 'avc1.640028', 'acodec': 'none', 'height': 1080, 'filesize': 5_000_000},
                {'format_id': '140', 'ext': 'm4a', 'vcodec': 'none', 'acodec': 'mp4a.40.2', 'abr': 128, 'filesize': 500_000},
            ],
        }
        calls = {'extract': 0, 'fail_stale': True}

        with tempfile.TemporaryDirectory() as tmp:
            v_file = os.path.join(tmp, 'Cached Clip-cache1.v.mp4')
            a_file = os.path.join(tmp, 'Cached Clip-cache1.a.m4a')

            class _FakeCacheYdl:
                def __init__(self, opts):
                    self.opts = opts

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return False

                def extract_info(self, url, download=False):
                    calls['extract'] += 1
                    if calls['extract'] >= 2:
                        calls['fail_stale'] = False
                    return fake_yt

                def process_video_result(self, info_dict, download=True):
                    if calls['fail_stale']:
                        raise RuntimeError('HTTP Error 403: Forbidden')
                    is_aud = 'bestaudio' in (self.opts.get('format') or '')
                    p = a_file if is_aud else v_file
                    with open(p, 'wb') as f:
                        f.write(b'aac' if is_aud else b'h264')
                    return {'_type': 'video', 'id': 'cache1', 'title': 'Cached Clip', 'ext': 'm4a' if is_aud else 'mp4'}

                def prepare_filename(self, info_dict):
                    return a_file if info_dict.get('ext') == 'm4a' else v_file

            with patch.object(downloader, 'YoutubeDL', _FakeCacheYdl):
                # Warm cache via inspect (1 extract call)
                downloader.inspect('https://youtu.be/cache1', 33)
                self.assertEqual(calls['extract'], 1)
                # Stale cached URLs 403 -> evicts cache -> 2nd extract succeeds!
                res = json.loads(downloader.download('https://youtu.be/cache1', tmp, '1080', sdk_int=33))
            self.assertTrue(res['merge'])
            self.assertEqual(calls['extract'], 2)

            # Now fresh inspect -> clean download reuses cache with ZERO extra extract_info calls!
            calls['extract'] = 0
            calls['fail_stale'] = False
            downloader.clear_caches()
            with patch.object(downloader, 'YoutubeDL', _FakeCacheYdl):
                downloader.inspect('https://youtu.be/cache1', 33)
                self.assertEqual(calls['extract'], 1)
                json.loads(downloader.download('https://youtu.be/cache1', tmp, '1080', sdk_int=33))
            self.assertEqual(calls['extract'], 1)

    def test_youtube_extract_falls_back_when_skip_manifests_returns_empty_formats(self):
        downloader.clear_caches()
        seen_skip = []

        class _FakeFallbackYdl:
            def __init__(self, opts):
                yt_args = (opts.get('extractor_args') or {}).get('youtube') or {}
                seen_skip.append(list(yt_args.get('skip') or []))

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                if seen_skip[-1] == ['hls', 'dash']:
                    return {'id': 'live1', 'title': 'Live', 'formats': []}
                return {
                    'id': 'live1',
                    'title': 'Live',
                    'formats': [{'format_id': '95', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 720}],
                }

        with patch.object(downloader, 'YoutubeDL', _FakeFallbackYdl):
            insp = json.loads(downloader.inspect('https://youtu.be/live1', 33))
        self.assertEqual(seen_skip, [['hls', 'dash'], []])
        self.assertTrue(any(f['id'] == '720' for f in insp['formats']))

    def test_tiktok_inspect_to_download_reuses_tikwm_cache_and_cover_list(self):
        downloader.clear_caches()
        fetch_calls = []
        tag_calls = []

        class _FakeTikResp:
            def __init__(self, body):
                self._body = body
                self.headers = {'Content-Length': str(len(body))}

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self, n=-1):
                chunk = self._body
                self._body = b''
                return chunk

        def _fake_urlopen(req, **kwargs):
            u = req.full_url
            if 'tikwm.com/api' in u:
                fetch_calls.append(u)
                return _FakeTikResp(json.dumps({
                    'code': 0,
                    'data': {
                        'id': '999',
                        'title': 'Frogs',
                        'duration': 10,
                        'cover': 'https://cdn.example/c1.jpg',
                        'origin_cover': 'https://cdn.example/c2.jpg',
                        'hdplay': 'https://cdn.example/v.mp4',
                        'music_info': {'play': 'https://cdn.example/a.mp3', 'cover': 'https://cdn.example/mc.jpg'},
                    }
                }).encode())
            return _FakeTikResp(b'ID3-audio-payload')

        with tempfile.TemporaryDirectory() as tmp:
            with patch('urllib.request.urlopen', side_effect=_fake_urlopen), \
                 patch.object(downloader.audio_tagger, 'tag_audio_file', side_effect=lambda p, t, a, cov, ctx: tag_calls.append(cov) or True):
                downloader.inspect('https://www.tiktok.com/@u/video/999', 33)
                dl = json.loads(downloader.download('https://www.tiktok.com/@u/video/999', tmp, 'audio', sdk_int=33))
        self.assertEqual(len(fetch_calls), 1)
        self.assertTrue(dl['tagged'])
        self.assertEqual(tag_calls[0], ['https://cdn.example/c1.jpg', 'https://cdn.example/c2.jpg', 'https://cdn.example/mc.jpg'])

    def test_youtube_formats_keep_best_when_no_tier_matches(self):
        formats_empty = downloader._build_youtube_formats({'formats': []}, sdk_int=33)
        self.assertEqual(formats_empty[0]['id'], 'best')
        self.assertEqual(formats_empty[0]['filesize'], 0)

        formats_legacy = downloader._build_youtube_formats(
            {'formats': [{'format_id': '271', 'ext': 'webm', 'vcodec': 'vp9', 'acodec': 'none', 'height': 1440, 'filesize': 9_000_000}]},
            sdk_int=28,
        )
        self.assertEqual(formats_legacy[0]['id'], 'best')

    def test_subtitle_langs_ignore_hashed_language_tag_for_english_default(self):
        has_subs, langs = downloader._extract_subtitle_langs({
            'language': 'zh-HK-2epwjfpmbia',
            'subtitles': {
                'zh-HK-2epwjfpmbia': [{'ext': 'vtt'}],
                'en': [{'ext': 'vtt'}],
            },
        })
        self.assertTrue(has_subs)
        self.assertEqual(langs[0], 'en')


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
