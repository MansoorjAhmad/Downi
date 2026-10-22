"""Unit test for the engine's truth layer (tools/test_truth.py).

READ_THIS_BEFORE_UPGRADE.md §8.7: a change that touches a core routine ships
WITH its test. This one covers the classifier - the piece that decides what
the user is told when a grab fails.

Run it with a plain Python:

    tools\\python311\\python.exe tools\\test_truth.py
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.join(os.path.dirname(HERE), 'android', 'app', 'src', 'main', 'python')
sys.path.insert(0, ENGINE)

import truth  # noqa: E402

FAILURES = []


def check(label, got, want):
    if got != want:
        FAILURES.append('%s\n     got:  %r\n     want: %r' % (label, got, want))


# --- every cause code, from a raw reason that actually says so -----------
CASES = [
    ('ERROR: [youtube] abc: Unable to download webpage: https://x: HTTP Error 403: Forbidden',
     'blocked'),
    ('ERROR: Sign in to confirm you\'re not a bot. Use --cookies-from-browser',
     'blocked'),
    ('ERROR: [youtube] abc: This video requires a PO token',
     'blocked'),
    ('HTTP Error 429: Too Many Requests', 'rate_limited'),
    ('ERROR: [youtube] abc: Private video. Sign in if you\'ve been granted access',
     'private'),
    ('ERROR: [youtube] abc: Sign in to confirm your age', 'private'),
    ('ERROR: [youtube] abc: The uploader has not made this video available in your country',
     'geo'),
    ('ERROR: [youtube] abc: Requested format is not available', 'format'),
    ('This video has no separate 1080p stream - pick 720p HD or Best Available.',
     'format'),
    ('ERROR: Unsupported URL: https://example.com/page', 'link'),
    ('ERROR: [youtube] abc: No video formats found!', 'link'),
    ('ERROR: Unable to download webpage: <urlopen error timed out>', 'network'),
    ('HTTP Error 404: Not Found', 'link'),
    ('[Errno 7] Failed to establish a new connection', 'network'),
    ('Download cancelled.', 'cancelled'),
    # Nothing in this text says anything we can stand behind.
    ('something nobody can interpret', 'unknown'),
    ('', 'unknown'),
]


def main():
    for raw, want in CASES:
        check('classify(%r)' % raw[:48], truth.classify(raw), want)

    # --- the tag survives the bridge and comes back clean -----------------
    try:
        truth.fail('ERROR: HTTP Error 403: Forbidden')
    except RuntimeError as exc:
        message = str(exc)
        check('fail() tags the cause', message.startswith('[cause:blocked]'), True)
        cause, raw = truth.split_cause(message)
        check('split_cause() reads the cause', cause, 'blocked')
        check('split_cause() keeps the raw reason', raw, 'ERROR: HTTP Error 403: Forbidden')
    else:
        FAILURES.append('fail() did not raise')

    # --- an untagged message still gets classified (nothing arrives blind) -
    cause, raw = truth.split_cause('ERROR: Requested format is not available')
    check('split_cause() classifies an untagged message', cause, 'format')
    check('split_cause() keeps an untagged message', raw, 'ERROR: Requested format is not available')

    # --- a reason that passes through two layers is tagged ONCE -----------
    # (the 1080p split tags it, then download() re-raises it - a double tag
    #  would leave a stray "[cause:x]" in the text the user reads)
    try:
        try:
            truth.fail('ERROR: HTTP Error 403: Forbidden')
        except RuntimeError as exc:
            truth.fail(exc)
    except RuntimeError as exc:
        message = str(exc)
        check('fail() does not tag twice', message.count('[cause:'), 1)
        cause, raw = truth.split_cause(message)
        check('the single tag still classifies', cause, 'blocked')
        check('the raw reason is untouched', raw, 'ERROR: HTTP Error 403: Forbidden')
    else:
        FAILURES.append('fail() did not raise twice')

    # --- the rule that matters most: 'unknown' never blames the video ----
    check('unknown stays unknown',
          truth.classify('the media stream could not be downloaded'), 'unknown')

    if FAILURES:
        print('FAIL (%d)' % len(FAILURES))
        for f in FAILURES:
            print('  - ' + f)
        return 1
    print('ok    the truth layer: %d classifications, the tag round-trips, '
          'nothing arrives unclassified' % len(CASES))
    return 0


if __name__ == '__main__':
    sys.exit(main())
