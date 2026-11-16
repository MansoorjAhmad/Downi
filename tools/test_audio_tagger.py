"""Unit tests for pure-Python audio_tagger.py (ID3v2.3 MP3 & M4A moov/stco/co64).

Run:
    tools\\python311\\python.exe -B tools\\test_audio_tagger.py
"""

import os
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.join(os.path.dirname(HERE), 'android', 'app', 'src', 'main', 'python')
sys.path.insert(0, ENGINE)

import audio_tagger  # noqa: E402


def _box(btype, payload):
    return struct.pack('>I4s', 8 + len(payload), btype) + payload


def _find_child(buf, start, end, target_type):
    for pos, hdr, size, btype in audio_tagger._iter_boxes(buf, start, end):
        if btype == target_type:
            return pos, hdr, size
    return None


def _read_stco_offsets(moov_bytes):
    moov_hdr = 8
    trak = _find_child(moov_bytes, moov_hdr, len(moov_bytes), b'trak')
    mdia = _find_child(moov_bytes, trak[0] + trak[1], trak[0] + trak[2], b'mdia')
    minf = _find_child(moov_bytes, mdia[0] + mdia[1], mdia[0] + mdia[2], b'minf')
    stbl = _find_child(moov_bytes, minf[0] + minf[1], minf[0] + minf[2], b'stbl')
    stco = _find_child(moov_bytes, stbl[0] + stbl[1], stbl[0] + stbl[2], b'stco')
    p = stco[0] + stco[1]
    count = struct.unpack_from('>I', moov_bytes, p + 4)[0]
    return [struct.unpack_from('>I', moov_bytes, p + 8 + i * 4)[0] for i in range(count)]


def _read_co64_offsets(moov_bytes):
    moov_hdr = 8
    trak = _find_child(moov_bytes, moov_hdr, len(moov_bytes), b'trak')
    mdia = _find_child(moov_bytes, trak[0] + trak[1], trak[0] + trak[2], b'mdia')
    minf = _find_child(moov_bytes, mdia[0] + mdia[1], mdia[0] + mdia[2], b'minf')
    stbl = _find_child(moov_bytes, minf[0] + minf[1], minf[0] + minf[2], b'stbl')
    co64 = _find_child(moov_bytes, stbl[0] + stbl[1], stbl[0] + stbl[2], b'co64')
    p = co64[0] + co64[1]
    count = struct.unpack_from('>I', moov_bytes, p + 4)[0]
    return [struct.unpack_from('>Q', moov_bytes, p + 8 + i * 8)[0] for i in range(count)]


def _build_minimal_m4a(moov_first=True, use_co64=False):
    ftyp = _box(b'ftyp', b'M4A \x00\x00\x00\x00M4A mp42isom')
    chunk1 = b'AUDIO_CHUNK_1'
    chunk2 = b'AUDIO_CHUNK_2'
    mdat = _box(b'mdat', chunk1 + chunk2)
    mvhd = _box(b'mvhd', b'\x00' * 96)

    def make_moov(off1, off2):
        if use_co64:
            tbl = _box(b'co64', struct.pack('>IIQQ', 0, 2, off1, off2))
        else:
            tbl = _box(b'stco', struct.pack('>IIII', 0, 2, off1, off2))
        stbl = _box(b'stbl', tbl)
        minf = _box(b'minf', stbl)
        mdia = _box(b'mdia', minf)
        trak = _box(b'trak', mdia)
        return _box(b'moov', mvhd + trak)

    if moov_first:
        dummy_moov = make_moov(0, 0)
        mdat_start = len(ftyp) + len(dummy_moov)
        moov = make_moov(mdat_start + 8, mdat_start + 8 + len(chunk1))
        return ftyp + moov + mdat
    else:
        mdat_start = len(ftyp)
        moov = make_moov(mdat_start + 8, mdat_start + 8 + len(chunk1))
        return ftyp + mdat + moov


class TestAudioTagger(unittest.TestCase):
    def test_mp3_id3v23_tagging_and_retagging_preserves_audio(self):
        fake_jpeg = b'\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xd9'
        audio_payload = b'\xff\xfb\x90\x00' + b'\xaa' * 128

        class _FakeResp:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self, n=-1):
                return fake_jpeg

        with tempfile.TemporaryDirectory() as tmp:
            mp3_path = os.path.join(tmp, 'song.mp3')
            with open(mp3_path, 'wb') as f:
                f.write(audio_payload)

            with patch('urllib.request.urlopen', return_value=_FakeResp()):
                ok = audio_tagger.tag_audio_file(
                    mp3_path, 'My Song 🎵', 'Artist Name', 'https://cdn.example.com/cover.jpg'
                )
            self.assertTrue(ok)

            with open(mp3_path, 'rb') as f:
                tagged_bytes = f.read()

            self.assertEqual(tagged_bytes[:6], b'ID3\x03\x00\x00')
            tag_size = audio_tagger._syncsafe_decode(tagged_bytes[6:10])
            self.assertEqual(tagged_bytes[10 + tag_size:], audio_payload)
            tag_body = tagged_bytes[10:10 + tag_size]
            self.assertIn(b'TIT2', tag_body)
            self.assertIn(b'TPE1', tag_body)
            self.assertIn(b'APIC', tag_body)
            self.assertIn(b'image/jpeg\x00', tag_body)
            self.assertIn(fake_jpeg, tag_body)

            # Re-tag with new title and verify no duplicate ID3 headers
            with patch('urllib.request.urlopen', return_value=_FakeResp()):
                ok2 = audio_tagger.tag_audio_file(
                    mp3_path, 'Updated Title', 'Artist 2', 'https://cdn.example.com/cover.jpg'
                )
            self.assertTrue(ok2)
            with open(mp3_path, 'rb') as f:
                retagged = f.read()
            tag_size2 = audio_tagger._syncsafe_decode(retagged[6:10])
            self.assertEqual(retagged[10 + tag_size2:], audio_payload)

    def test_m4a_moov_before_mdat_updates_stco_offsets(self):
        fake_png = b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00'

        class _FakeResp:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self, n=-1):
                return fake_png

        with tempfile.TemporaryDirectory() as tmp:
            m4a_path = os.path.join(tmp, 'track.m4a')
            with open(m4a_path, 'wb') as f:
                f.write(_build_minimal_m4a(moov_first=True))

            with patch('urllib.request.urlopen', return_value=_FakeResp()):
                ok = audio_tagger.tag_audio_file(
                    m4a_path, 'M4A Title', 'M4A Artist', 'https://cdn.example.com/cover.png'
                )
            self.assertTrue(ok)

            with open(m4a_path, 'rb') as f:
                raw = f.read()

            moov_pos, _, moov_size = _find_child(raw, 0, len(raw), b'moov')
            moov_bytes = raw[moov_pos:moov_pos + moov_size]
            offsets = _read_stco_offsets(moov_bytes)
            self.assertEqual(raw[offsets[0]:offsets[0] + 13], b'AUDIO_CHUNK_1')
            self.assertEqual(raw[offsets[1]:offsets[1] + 13], b'AUDIO_CHUNK_2')

            self.assertIn(b'\xa9nam', moov_bytes)
            self.assertIn(b'M4A Title', moov_bytes)
            self.assertIn(b'\xa9ART', moov_bytes)
            self.assertIn(b'M4A Artist', moov_bytes)
            self.assertIn(b'covr', moov_bytes)
            self.assertIn(fake_png, moov_bytes)

    def test_m4a_moov_after_mdat_keeps_stco_offsets_intact(self):
        with tempfile.TemporaryDirectory() as tmp:
            m4a_path = os.path.join(tmp, 'track_after.m4a')
            with open(m4a_path, 'wb') as f:
                f.write(_build_minimal_m4a(moov_first=False))

            with patch('urllib.request.urlopen', side_effect=OSError('offline')):
                ok = audio_tagger.tag_audio_file(
                    m4a_path, 'After Title', 'After Artist', 'https://cdn.example.com/404.jpg'
                )
            self.assertTrue(ok)

            with open(m4a_path, 'rb') as f:
                raw = f.read()

            moov_pos, _, moov_size = _find_child(raw, 0, len(raw), b'moov')
            moov_bytes = raw[moov_pos:moov_pos + moov_size]
            offsets = _read_stco_offsets(moov_bytes)
            self.assertEqual(raw[offsets[0]:offsets[0] + 13], b'AUDIO_CHUNK_1')
            self.assertEqual(raw[offsets[1]:offsets[1] + 13], b'AUDIO_CHUNK_2')
            self.assertIn(b'After Title', moov_bytes)

    def test_m4a_moov_before_mdat_updates_co64_offsets(self):
        with tempfile.TemporaryDirectory() as tmp:
            m4a_path = os.path.join(tmp, 'track64.m4a')
            with open(m4a_path, 'wb') as f:
                f.write(_build_minimal_m4a(moov_first=True, use_co64=True))

            ok = audio_tagger.tag_audio_file(m4a_path, '64 Title', '64 Artist', '')
            self.assertTrue(ok)

            with open(m4a_path, 'rb') as f:
                raw = f.read()

            moov_pos, _, moov_size = _find_child(raw, 0, len(raw), b'moov')
            moov_bytes = raw[moov_pos:moov_pos + moov_size]
            offsets = _read_co64_offsets(moov_bytes)
            self.assertEqual(raw[offsets[0]:offsets[0] + 13], b'AUDIO_CHUNK_1')
            self.assertEqual(raw[offsets[1]:offsets[1] + 13], b'AUDIO_CHUNK_2')

    def test_non_fatal_preserves_corrupt_or_unsupported_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad_m4a = os.path.join(tmp, 'corrupt.m4a')
            orig = b'not-a-valid-mp4-container'
            with open(bad_m4a, 'wb') as f:
                f.write(orig)
            self.assertFalse(audio_tagger.tag_audio_file(bad_m4a, 'T', 'A', ''))
            with open(bad_m4a, 'rb') as f:
                self.assertEqual(f.read(), orig)

            webm = os.path.join(tmp, 'opus.webm')
            with open(webm, 'wb') as f:
                f.write(b'webmdata')
            self.assertFalse(audio_tagger.tag_audio_file(webm, 'T', 'A', ''))

    def test_fetch_thumbnail_falls_back_through_candidates_and_skips_html(self):
        fake_png = b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR'
        seen = []

        class _FakeResp:
            def __init__(self, body, ctype='image/png'):
                self._body = body
                self.headers = {'Content-Type': ctype}

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self, n=-1):
                return self._body

        def _fake_urlopen(req, **kwargs):
            u = req.full_url
            seen.append((u, req.headers.get('Referer')))
            if 'blocked' in u:
                return _FakeResp(b'<html>403 Forbidden</html>', 'text/html')
            return _FakeResp(fake_png, 'image/png')

        with patch('urllib.request.urlopen', side_effect=_fake_urlopen):
            img, mime, mp4_t = audio_tagger._fetch_thumbnail([
                'https://tiktokcdn.example.com/blocked.webp',
                'https://p16-tiktokcdn.example.com/cover.png',
            ])
        self.assertEqual(img, fake_png)
        self.assertEqual(mime, 'image/png')
        self.assertEqual(mp4_t, 14)
        self.assertEqual(len(seen), 2)
        self.assertEqual(seen[1][1], 'https://www.tiktok.com/')


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
