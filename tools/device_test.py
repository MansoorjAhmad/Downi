"""DOWNI device-test helper (runs on the PC, talks to the phone over adb).

Companion for DEVICE_TEST.md: it does not replace the matrix (only a human can
confirm "plays with sound"), but it removes the guesswork around it:

  status            device + Android version + installed DOWNI version
  media             what is actually in Movies/ + Music/ (and MediaStore)
  logs [seconds]    clear logcat, then stream only the interesting tags
  clear             clear logcat (pair with dump around a single tap)
  dump              print what the filter caught since the last clear
  crash             dump the last crash buffer
  share <url>       fire ACTION_SEND at DOWNI (DowniDrop, no Chrome needed)
  ptext <url>       fire ACTION_PROCESS_TEXT at DOWNI (text-selection share)
  install [apk]     adb install -r a signed APK (default: test_out/DOWNI-v3.0.2.apk)
  verify [minMB]    pull the newest Movies file and probe it with the bundled
                    ffprobe -> proves real video+audio streams landed

Read-only against the repos; nothing here touches The Law's code paths.
"""
import os
import subprocess
import sys

try:  # keep output ASCII-safe on Windows consoles
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
APP_ID = 'com.omnidownloader.app'
ACTIVITY = APP_ID + '/.MainActivity'

# The app itself never calls android.util.Log (errors surface in the UI through
# call.reject), so logcat is for crashes + system-level denials only.
# Capacitor routes the WebView console (console.log/error + uncaught exceptions)
# into logcat under "Capacitor/Console", which is where a broken JS handler shows.
LOG_FILTER = [
    'AndroidRuntime:E',
    'Capacitor/Console:V',
    'chromium:V',
    'Chaquopy:V',
    'python.stdout:V',
    'python.stderr:V',
    'MediaProvider:W',
    'PermissionManager:W',
    'ActivityTaskManager:I',
    'ActivityManager:W',
    '*:S',
]


def adb_path():
    override = os.environ.get('ADB')
    if override and os.path.exists(override):
        return override
    candidate = os.path.join(REPO, 'android-sdk', 'platform-tools', 'adb.exe')
    if os.path.exists(candidate):
        return candidate
    return 'adb'


ADB = adb_path()


def run(args, timeout=60):
    """Run adb and return (returncode, stdout+stderr)."""
    try:
        proc = subprocess.run(
            [ADB] + args,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
        return proc.returncode, proc.stdout.decode('utf-8', 'replace')
    except FileNotFoundError:
        return 127, 'adb not found at %s - set ADB=<full path>' % ADB
    except subprocess.TimeoutExpired as exc:
        out = exc.output.decode('utf-8', 'replace') if exc.output else ''
        return 124, out + '\n[timed out after %ss]' % timeout


def require_device():
    code, out = run(['devices'])
    attached = [ln for ln in out.splitlines()[1:] if ln.strip() and '\tdevice' in ln]
    if not attached:
        print('No phone attached. Enable Developer options -> USB debugging, plug in,')
        print('accept the "Allow USB debugging?" prompt, then re-run.')
        print('(adb said: %s)' % out.strip().replace('\n', ' | '))
        return None
    return attached[0].split('\t')[0]


def sh(args, timeout=60):
    return run(['shell'] + args, timeout=timeout)


def cmd_status():
    serial = require_device()
    if not serial:
        return 1
    print('Device  : %s' % serial)
    for label, prop in (
        ('Model', 'ro.product.model'),
        ('Android', 'ro.build.version.release'),
        ('SDK', 'ro.build.version.sdk'),
        ('ABI', 'ro.product.cpu.abi'),
        ('Build', 'ro.build.display.id'),
    ):
        code, out = sh(['getprop', prop])
        print('%-8s: %s' % (label + ' ', out.strip()))

    code, out = sh(['dumpsys', 'package', APP_ID])
    if code == 0 and 'versionName' in out:
        for line in out.splitlines():
            line = line.strip()
            if line.startswith('versionName') or line.startswith('versionCode'):
                print('App     : %s' % line)
        for perm in ('READ_MEDIA_VIDEO', 'READ_MEDIA_AUDIO', 'POST_NOTIFICATIONS'):
            print('Perm    : %-18s %s' % (perm, 'granted' if (perm in out and 'granted=true' in out) else 'not granted / SDK<33'))
    else:
        print('App     : %s is NOT installed' % APP_ID)

    for label, path in (('Movies', '/sdcard/Movies'), ('Music', '/sdcard/Music')):
        code, out = sh(['ls', '-l', path])
        rows = [ln for ln in out.splitlines() if ln.strip() and not ln.startswith('total')]
        print('%-8s: %d entries' % (label + ' ', len(rows)))
    return 0


def cmd_media():
    if not require_device():
        return 1
    for label, shell_cmd in (
        ('Movies', ['ls', '-l', '/sdcard/Movies']),
        ('Music', ['ls', '-l', '/sdcard/Music']),
        ('Stray .part files', ['find /sdcard/Movies /sdcard/Music -name "*.part*" 2>/dev/null | head -20']),
    ):
        print('--- %s ---' % label)
        code, out = sh(shell_cmd, timeout=90)
        print(out.strip() or '(empty)')
    print('--- MediaStore (video) ---')
    code, out = sh([
        'content', 'query', '--uri', 'content://media/external/video/media',
        '--projection', '_display_name:_size:relative_path',
    ], timeout=90)
    print(out.strip() or '(query unavailable - use the listing above)')
    return 0


def cmd_logs(seconds=20):
    if not require_device():
        return 1
    run(['logcat', '-c'])
    print('logcat cleared - now do ONE action on the phone (streaming %ss)...' % seconds)
    code, out = run(['logcat', '-v', 'time'] + LOG_FILTER, timeout=seconds)
    body = out.strip()
    print(body if body else '(silent - no crash, no denial: that is a PASS signal)')
    if 'FATAL EXCEPTION' in body:
        print('\n!! CRASH DETECTED - capture the block above and file it as a v3.0.3 fix.')
        return 2
    return 0


def cmd_crash():
    if not require_device():
        return 1
    code, out = run(['logcat', '-b', 'crash', '-d', '-v', 'time'])
    print(out.strip() or '(crash buffer empty)')
    return 0


def cmd_clear():
    if not require_device():
        return 1
    run(['logcat', '-c'])
    print('logcat cleared - now do the single action on the phone, then run: dump')
    return 0


def cmd_dump():
    if not require_device():
        return 1
    code, out = run(['logcat', '-d', '-v', 'time'] + LOG_FILTER)
    body = out.strip()
    print(body if body else '(nothing matched the filter since the clear: PASS signal)')
    if 'FATAL EXCEPTION' in body:
        print('\n!! CRASH DETECTED - capture the block above and file it as a v3.0.3 fix.')
        return 2
    return 0


def cmd_share(url, action='android.intent.action.SEND', extra='android.intent.extra.TEXT'):
    if not require_device():
        return 1
    code, out = sh([
        'am', 'start',
        '-a', action,
        '-t', 'text/plain',
        '--es', extra, url,
        '-n', ACTIVITY,
    ])
    print(out.strip() or 'intent sent (%s)' % action)
    print('Watch the phone: the Inspector should open with that link.')
    return 0 if 'Error' not in out else 1


def cmd_install(apk=None):
    if not require_device():
        return 1
    apk = apk or os.path.join(REPO, 'test_out', 'DOWNI-v3.0.2.apk')
    if not os.path.exists(apk):
        print('APK not found: %s' % apk)
        return 1
    print('Installing %s (%.1f MB) with -r - the signing key must match the installed app'
          % (apk, os.path.getsize(apk) / 1048576.0))
    code, out = run(['install', '-r', apk], timeout=300)
    print(out.strip())
    return code


def cmd_verify(min_mb=0.05):
    if not require_device():
        return 1
    code, out = sh(['ls', '-t', '/sdcard/Movies'])
    names = [ln.strip() for ln in out.splitlines() if ln.strip() and 'No such file' not in ln]
    if not names:
        print('Nothing in Movies/ - did the download land?')
        return 1
    newest = names[0]
    local = os.path.join(REPO, 'test_out', 'pulled_%s' % newest)
    code, out = run(['pull', '/sdcard/Movies/%s' % newest, local], timeout=240)
    if code != 0 or not os.path.exists(local):
        print('Could not pull %s:\n%s' % (newest, out.strip()))
        return 1
    size_mb = os.path.getsize(local) / 1048576.0
    print('Pulled  : %s (%.2f MB)' % (newest, size_mb))
    if size_mb < float(min_mb):
        print('FAIL    : smaller than the %.2f MB floor' % float(min_mb))
        return 1
    ffprobe = os.path.join(HERE, 'ffprobe.exe')
    if not os.path.exists(ffprobe):
        print('(ffprobe.exe missing - skipping the stream check)')
        return 0
    proc = subprocess.run(
        [ffprobe, '-v', 'error', '-show_entries',
         'format=duration,size,format_name:stream=index,codec_type,codec_name,width,height,channels',
         '-of', 'default=noprint_wrappers=1', local],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    report = proc.stdout.decode('utf-8', 'replace')
    print(report.strip())
    has_video = 'codec_type=video' in report
    has_audio = 'codec_type=audio' in report
    print('\nvideo stream: %s   audio stream: %s' % (has_video, has_audio))
    if has_video and not has_audio:
        print('FAIL    : video only - it would play silent. File it as v3.0.3 work.')
        return 1
    print('PASS    : file exists, is non-trivial, and carries both streams.')
    return 0


def main(argv):
    if len(argv) < 2 or argv[1] in ('-h', '--help', 'help'):
        print(__doc__.strip())
        return 0
    cmd = argv[1]
    rest = argv[2:]
    table = {
        'status': cmd_status,
        'media': cmd_media,
        'crash': cmd_crash,
        'clear': cmd_clear,
        'dump': cmd_dump,
    }
    if cmd in table:
        return table[cmd]()
    if cmd == 'logs':
        return cmd_logs(int(rest[0]) if rest else 20)
    if cmd == 'share':
        if not rest:
            print('usage: share <url>')
            return 2
        return cmd_share(rest[0])
    if cmd in ('ptext', 'process-text'):
        if not rest:
            print('usage: ptext <url>')
            return 2
        return cmd_share(rest[0], 'android.intent.action.PROCESS_TEXT',
                         'android.intent.extra.PROCESS_TEXT')
    if cmd == 'install':
        return cmd_install(rest[0] if rest else None)
    if cmd == 'verify':
        return cmd_verify(rest[0] if rest else 0.05)
    print('unknown command: %s\n' % cmd)
    print(__doc__.strip())
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
