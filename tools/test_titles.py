"""Unit test for Title and Filename Sanitization (tools/test_titles.py).
Tests T1-T8 risk vectors against _safe_name in downloader.py.
"""

import os
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.join(os.path.dirname(HERE), 'android', 'app', 'src', 'main', 'python')
sys.path.insert(0, ENGINE)

import downloader  # noqa: E402

FAILURES = []


def check(label, got, condition, detail=""):
    if not condition:
        FAILURES.append(f"FAIL: {label}\n      got: {got!r}\n      expected: {detail}")


def run_tests():
    # T1: Unicode Normalization (NFC)
    # Urdu with combining marks (NFD)
    urdu_nfd = unicodedata.normalize('NFD', 'اُردُو ویڈیو')
    res_urdu = downloader._safe_name(urdu_nfd)
    check("T1 Urdu normalization", res_urdu, unicodedata.is_normalized('NFC', res_urdu), "NFC normalized string")

    # Hindi with combining matras
    hindi_nfd = unicodedata.normalize('NFD', 'नमस्ते दुनिया')
    res_hindi = downloader._safe_name(hindi_nfd)
    check("T1 Hindi normalization", res_hindi, unicodedata.is_normalized('NFC', res_hindi), "NFC normalized string")

    # Chinese characters
    chinese = "精彩短视频 2026"
    res_chinese = downloader._safe_name(chinese)
    check("T1 Chinese preservation", res_chinese, "精彩短视频 2026" in res_chinese, "preserved Chinese text")

    # T2: RTL / BiDi Overrides & Invisible Formatting
    # Injected RLO (\u202e) and LRM (\u200e)
    bidi_in = "My \u202eReversed\u202c \u200eTitle\u200f \ufeff\u200b"
    res_bidi = downloader._safe_name(bidi_in)
    bidi_chars = {'\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
                  '\u2066', '\u2067', '\u2068', '\u2069', '\ufeff', '\u200b'}
    check("T2 BiDi stripping", res_bidi, not any(c in res_bidi for c in bidi_chars), "no BiDi/invisible chars")

    # T3: Emoji / ZWJ Sequences
    emoji_in = "🔥 Amazing Video 🎉 🚀"
    res_emoji = downloader._safe_name(emoji_in)
    check("T3 Emoji preservation", res_emoji, "🔥" in res_emoji and "🎉" in res_emoji, "emojis preserved")

    # T4: Forbidden Characters & Control Characters
    forbidden_in = "Video: with / illegal * chars ? <like> \"this\" | and \x00\x07\x1b control"
    res_forbidden = downloader._safe_name(forbidden_in)
    illegal_chars = set('\\/:*?"<>|\r\n\t\x00\x07\x1b')
    check("T4 Forbidden chars replacement", res_forbidden, not any(c in res_forbidden for c in illegal_chars), "no illegal chars")

    # T5: Windows Reserved Device Names & Trailing Dots/Spaces
    for reserved in ['CON', 'con', 'PRN', 'prn', 'AUX', 'aux', 'NUL', 'nul', 'COM1', 'com1', 'LPT1', 'lpt1']:
        res_res = downloader._safe_name(reserved)
        check(f"T5 Reserved name {reserved}", res_res, res_res.startswith("Video_"), "prefixed with Video_")

    trailing_in = "My Title....    "
    res_trailing = downloader._safe_name(trailing_in)
    check("T5 Trailing dots/spaces", res_trailing, res_trailing == "My Title", "no trailing dots or spaces")

    # T7: Very Long Titles
    long_in = "This is an extremely long title that exceeds the normal eighty character boundary and contains many words to test smart word boundary truncation cleanly"
    res_long = downloader._safe_name(long_in)
    check("T7 Character length limit", res_long, len(res_long) <= 80, "<= 80 chars")
    check("T7 UTF-8 byte limit", res_long, len(res_long.encode('utf-8')) <= 180, "<= 180 UTF-8 bytes")
    check("T7 Word boundary cut", res_long, not res_long.endswith(' '), "cleanly trimmed without trailing space")

    # T7 Long multi-byte string (Chinese/Urdu)
    long_multibyte = "اُردُو " * 50
    res_mb = downloader._safe_name(long_multibyte)
    check("T7 Multi-byte byte limit", res_mb, len(res_mb.encode('utf-8')) <= 180, "<= 180 UTF-8 bytes")

    # T8: Empty or Metadata-Only Title Fallback
    check("T8 Empty string", downloader._safe_name(""), "Video" in downloader._safe_name(""), "Video fallback")
    check("T8 Spaces only", downloader._safe_name("   "), "Video" in downloader._safe_name("   "), "Video fallback")
    check("T8 Punctuation only", downloader._safe_name("***:::???///"), "Video" in downloader._safe_name("***:::???///"), "Video fallback")
    check("T8 None input", downloader._safe_name(None), "Video" in downloader._safe_name(None), "Video fallback")
    check("T8 None with uploader", downloader._safe_name(None, uploader="Creator"), downloader._safe_name(None, uploader="Creator") == "Creator - Video", "Creator - Video fallback")

    if FAILURES:
        print(f"FAILED: {len(FAILURES)} test(s) failed:")
        for f in FAILURES:
            print(" ", f)
        sys.exit(1)
    else:
        print("ok    title & filename audit (T1-T8): all test cases passed")


if __name__ == '__main__':
    run_tests()
