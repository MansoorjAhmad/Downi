"""DOWNI engine - the truth layer.

One classifier and one raised shape for every failure the engine can produce.

Why this module exists: the reason a grab failed used to die in three places
(the 1080p split swallowed every exception and reported "no separate 1080p
stream" instead, the inspector swallowed every exception and fabricated a
card, and the Java side rewrote the raw text that the UI's keyword buckets
matched on). What reached the user was a catch-all that blamed their video
for what was usually a platform-side block.

The rules here are deliberately blunt: a cause code is only used when the raw
text actually says so, and 'unknown' means the reason said nothing we can
stand behind. 'unknown' must never blame the user's video.

Kept free of yt-dlp imports on purpose, so the classifier can be unit-tested
on a plain Python (see tools/test_truth.py).
"""

# The order matters: it is the order of how much the raw text actually tells
# us. 'sign in to confirm' is a block (it carries 'sign in', which alone would
# read as private), so the block rule comes first.
CAUSE_RULES = (
    ('cancelled', ('cancel',)),
    ('blocked', ('403', 'forbidden', 'not a bot',
                 'confirm you are not a bot', 'po token', 'potoken',
                 'unusual traffic', 'captcha')),
    ('rate_limited', ('429', 'too many requests', 'rate limit', 'rate-limit')),
    ('private', ('private video', 'login required', 'members-only', 'members only',
                 'age-restricted', 'age restricted', 'confirm your age', 'age gate',
                 'requires an account', 'without being logged-in', 'logged-in', 'logged in',
                 'empty media response',
                 'video unavailable', 'has been removed', 'removed by the uploader',
                 'account', 'login', 'sign in')),
    ('geo', ('available in your country', 'available in this country',
             'geo restricted', 'geo-restricted', 'geo blocked', 'geo-restricted')),
    ('format', ('requested format is not available', 'format is not available',
                'no separate')),
    ('link', ('http error 404', 'unsupported url', 'url parsing', 'is not a valid url',
              'no video formats', 'did not provide a downloadable',
              'no downloadable stream')),
    ('network', ('getaddrinfo', 'name or service not known', 'name resolution',
                 'temporary failure in name resolution', 'no address associated',
                 'connection refused', 'connection reset', 'connectionreset',
                 'network is unreachable', 'network is down', 'timed out', 'timeout',
                 'unable to download webpage', 'connection aborted', 'errno 7',
                 'errno 101', 'errno 111', 'ssl', 'certificate', 'socket timeout')),
)

CAUSE_PREFIX = '[cause:'


def classify(raw):
    """The ONE place a raw engine reason becomes a cause code.

    First match wins. 'unknown' means the reason said nothing we can stand
    behind.
    """
    text = (raw or '').lower()
    for code, needles in CAUSE_RULES:
        for needle in needles:
            if needle in text:
                return code
    return 'unknown'


def split_cause(message):
    """Split a raised message into (cause, raw_reason).

    The tag travels as plain text across the Chaquopy bridge, so Java and the
    web layer need no new plumbing: they read the tag, strip it, and keep the
    raw reason exactly as the engine wrote it. A message with no tag is
    classified here, so nothing can arrive unclassified.
    """
    text = str(message or '')
    if text.startswith(CAUSE_PREFIX):
        end = text.find(']')
        if end > 0:
            code = text[len(CAUSE_PREFIX):end]
            return code, text[end + 1:].strip()
    return classify(text), text


def fail(raw, cause=None):
    """Raise the reason, tagged with its cause, so no layer has to guess later.

    A reason that is already tagged is raised as it is - tagging it twice would
    leave a stray "[cause:x]" in the text the user finally reads.
    """
    text = str(raw)
    if text.startswith(CAUSE_PREFIX):
        raise RuntimeError(text)
    code = cause or classify(text)
    raise RuntimeError('%s%s] %s' % (CAUSE_PREFIX, code, text))
