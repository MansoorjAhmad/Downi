"""Name what the app's own WebView keeps animating, and attribute the foreground cost to it.

The M8 pass measured the thing nobody had measured: with MainActivity in the front, the process
draws **~61 fps and burns ~25 s of CPU per 20 s of wall clock** (~1.25 cores) with no input at all
(`tools/core_idle_cost.ps1 -Offscreen` is the Core's own control for exactly this). The frames
counter says *that* it happens; it cannot say *what* is animating, and the answer is not visible in
`dumpsys gfxinfo` - the drawing happens inside the WebView's renderer.

This rig asks the renderer directly over the WebView's own DevTools socket (the debug build has
`setWebContentsDebuggingEnabled`, so `@webview_devtools_remote_<pid>` is listening). It is the
WebView twin of `core_state_audit.py`: same idea, read the instrument's own account instead of
guessing from pixels.

    list      every running animation: `animationName`, the element it is on (tag.class#id, plus
              `::before` / `::after` when it is a pseudo - the ones that matter here are pseudos),
              `iterations` (forever = Infinity), duration and playState
    raf       how many frames the renderer itself produces over N seconds (the WebView's own fps,
              to compare against `dumpsys gfxinfo`'s process-wide count)
    pause     stop every animation whose name or selector matches, so the Android-side frames
              counter can be read again with that animation gone - the attribution step
    resume    play everything again (a reload does the same; `pause` changes nothing on disk)
    metrics   the renderer's own Performance counters (TaskDuration / ScriptDuration /
              LayoutDuration / RecalcStyleDuration) - says *what* the CPU went into

Usage (the app must be in the FRONT: a backgrounded WebView throttles to nothing):

    python tools/core_web_anim.py list
    python tools/core_web_anim.py pause --match vortex
    python tools/core_web_anim.py pause --match spin
    python tools/core_web_anim.py raf --seconds 3
    python tools/core_web_anim.py metrics
    python tools/core_web_anim.py resume
    python tools/core_web_anim.py rect --match .vortex   the box in device px, to aim a real `input tap`
    python tools/core_web_anim.py waking                 the `.vortex` class and its own animations
    python tools/core_web_anim.py wake --dry             what a tap on the Vortex would grab right now
    python tools/core_web_anim.py wake                   the wake, sampled in time (class + animations)

It finds and forwards the socket itself (`adb forward tcp:9222 ...`), so the only setup is a
connected phone with the app in the front
(`android-sdk\\platform-tools\\adb.exe shell am start -n com.omnidownloader.app/.MainActivity`).

What it found first (vivo V2058, 2026-09-28, build installed from `app-spike-signed.apk`). The
app's foreground cost is **two CSS animations, and nothing else** - both on the home screen's hero
button, both on pseudo-elements, both `iterations: Infinite`:

    spin     div.vortex::before   transform: rotate(360deg) over a conic-gradient + -webkit-mask
    breathe  div.vortex::after    opacity .55->1 + transform: scale(.96->1.05) on a radial gradient

`dumpsys gfxinfo` (whole process) against `pause`/`resume` of those two, app in the front every
time (focus checked, never backgrounded - a backgrounded WebView throttles to 0 and reads like a
fix):

    both running            606 / 10 s  607 / 10 s  495 / 8 s  426 / 7 s  429 / 7 s  = ~61 fps
    only `spin` paused                       426 / 7 s                             = ~61 fps
    only `breathe` paused                    429 / 7 s                             = ~61 fps
    BOTH paused                    0 / 9 s    0 / 8 s                             =   0 fps
    app backgrounded (HOME)        0 / 10 s                                        =   0 fps

So each animation alone is enough to hold the process at 60 fps; only both together park it. The
app process burns **+9 s of CPU per 8 s of wall clock** while animating (00:07:50 -> 00:07:59) and
**+0 s** while parked (00:08:23 -> 00:08:23) - the ~1.25 cores `core_idle_cost.ps1` measured, now
attributed to a named element.

Two honest limits, both worth keeping in mind before blaming anything else: this WebView's own
Performance counters (`metrics`) report 0.000 s for TaskDuration/ScriptDuration/LayoutDuration and
0 LayoutCount/RecalcStyleCount even at 61 fps, so they attribute nothing here - the attribution
above comes from pause/measure, not from them; and only `com.omnidownloader.app` shows up in
`ps -A` for the package, so the +9 s is that process's own CPU as `ps` reports it.
"""
import argparse
import json
import re
import subprocess
import sys
import time
import urllib.request

APP = "com.omnidownloader.app"
SOCKET_RE = re.compile(r"@(webview_devtools_remote_\d+)")
PORT = 9222

# Runs in the page: every animation the document is currently running, with where it lives. The
# pseudo-element matters - `.vortex::before` / `::after` are the app's own forever-animations.
JS_ANIMS = r"""
(() => {
  const out = [];
  for (const a of document.getAnimations()) {
    const ef = a.effect || {};
    const t = ef.target;
    let sel = t ? (t.tagName ? t.tagName.toLowerCase() : '?') : '?';
    if (t && t.id) sel += '#' + t.id;
    if (t && t.classList && t.classList.length) sel += '.' + Array.from(t.classList).join('.');
    if (ef.pseudoElement) sel += ef.pseudoElement;
    const ct = ef.getComputedTiming ? ef.getComputedTiming() : {};
    out.push({
      name: a.animationName || '(transition)',
      sel: sel,
      state: a.playState,
      iter: ct.iterations === Infinity ? 'forever' : ct.iterations,
      dur: ct.duration,
      pseudo: ef.pseudoElement || ''
    });
  }
  return out;
})()
"""

JS_RAF = r"""
(() => new Promise(res => {
  let n = 0; const t0 = performance.now();
  const step = () => {
    n++;
    if (performance.now() - t0 < %d) requestAnimationFrame(step);
    else res({frames: n, ms: Math.round(performance.now() - t0)});
  };
  requestAnimationFrame(step);
}))()
"""


JS_RECT = r"""
(() => {
  const el = document.querySelector(%s);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return {
    sel: %s,
    css: {x: r.x, y: r.y, w: r.width, h: r.height},
    dpr: window.devicePixelRatio || 1,
    view: {w: window.innerWidth, h: window.innerHeight},
    screen: {w: screen.width, h: screen.height},
    doc: {w: document.documentElement.scrollWidth, h: document.documentElement.scrollHeight}
  };
})()
"""

# The wake is a 1.5 s class, so it has to be caught on a live connection: `waking` is the read that
# says the tap landed, and it separates `iter=1` (the one-shot the fix introduced) from `forever`.
JS_WAKING = r"""
(() => {
  const v = document.querySelector('.vortex');
  const anims = document.getAnimations().filter(a => {
    const ef = a.effect || {}, t = ef.target;
    let sel = t && t.tagName ? t.tagName.toLowerCase() : '?';
    if (t && t.id) sel += '#' + t.id;
    if (t && t.classList && t.classList.length) sel += '.' + Array.from(t.classList).join('.');
    if (ef.pseudoElement) sel += ef.pseudoElement;
    return sel.indexOf('vortex') >= 0;
  }).map(a => {
    const ef = a.effect || {};
    const ct = ef.getComputedTiming ? ef.getComputedTiming() : {};
    let sel = ef.target && ef.target.tagName ? ef.target.tagName.toLowerCase() : '?';
    if (ef.pseudoElement) sel += ef.pseudoElement;
    return {
      name: a.animationName || '(transition)',
      sel: sel,
      iter: ct.iterations === Infinity ? 'forever' : ct.iterations,
      state: a.playState,
      cur: Math.round(a.currentTime || 0)
    };
  });
  return {cls: v ? v.className : '(no .vortex)', anims: anims};
})()
"""


# A tap on `.vortex` calls handleVortexClick(), which grabs the clipboard *after* the wake. `--dry`
# asks the page's own grabber what the tap would download, without tapping - so the wake can be
# measured without spending the owner's data on a stale link.
JS_CLIP = r"""
(async () => {
  try { return await grabClipboardUrl(); } catch (e) { return 'ERR ' + e; }
})()
"""

JS_WAKE_TAP = r"""
(() => { const v = document.querySelector(%s); if (!v) return false; v.click(); return true; })()
"""

# One sample: the class, the vortex animations with their own clock, all off the live connection.
JS_WAKE_SAMPLE = r"""
(() => {
  const v = document.querySelector('.vortex');
  const out = [];
  for (const a of document.getAnimations()) {
    const ef = a.effect || {}, t = ef.target;
    let sel = t && t.tagName ? t.tagName.toLowerCase() : '?';
    if (t && t.id) sel += '#' + t.id;
    if (t && t.classList && t.classList.length) sel += '.' + Array.from(t.classList).join('.');
    if (ef.pseudoElement) sel += ef.pseudoElement;
    if (sel.indexOf('vortex') < 0) continue;
    const ct = ef.getComputedTiming ? ef.getComputedTiming() : {};
    out.push({
      name: a.animationName || '(transition)', sel: sel,
      iter: ct.iterations === Infinity ? 'forever' : ct.iterations,
      state: a.playState, cur: Math.round(a.currentTime || 0)
    });
  }
  return {t: Math.round(performance.now()), cls: v ? v.className : '?', anims: out};
})()
"""


def adb(*args, timeout=25):
    out = subprocess.run(["android-sdk\\platform-tools\\adb.exe"] + list(args),
                         capture_output=True, text=True, timeout=timeout)
    return out.stdout.strip()


def find_socket():
    unix = adb("shell", "cat /proc/net/unix")
    hits = [m.group(1) for m in SOCKET_RE.finditer(unix)]
    if not hits:
        raise SystemExit("no @webview_devtools_remote socket - is the debug build in the front?")
    return hits[0]


def page_target():
    for _ in range(10):
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/json/list" % PORT, timeout=5) as r:
                for t in json.load(r):
                    if t.get("type") == "page":
                        return t["webSocketDebuggerUrl"]
        except Exception:
            pass
        time.sleep(0.5)
    raise SystemExit("the DevTools port did not answer - check `adb forward` and the app's window")


class Cdp(object):
    """Minimal DevTools client: one request in, one result out (events are skipped)."""

    def __init__(self, url):
        import websocket  # websocket-client; imported here so `--help` works without it
        # Chromium rejects a WebSocket handshake that carries an Origin it did not opt into
        # ("403 ... Use the command line flag --remote-allow-origins=*"), so send none.
        self.ws = websocket.create_connection(url, timeout=30, suppress_origin=True)
        self.next_id = 1

    def send(self, method, params=None, timeout=45):
        mid = self.next_id
        self.next_id += 1
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        deadline = time.time() + timeout
        while time.time() < deadline:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise SystemExit("%s -> %s" % (method, msg["error"]))
                return msg.get("result", {})
        raise SystemExit("%s: no reply" % method)

    def js(self, expr, await_promise=False):
        res = self.send("Runtime.evaluate", {
            "expression": expr, "returnByValue": True, "awaitPromise": await_promise})
        if res.get("exceptionDetails"):
            raise SystemExit("page error: %s" % res["exceptionDetails"].get("text"))
        return res.get("result", {}).get("value")


def connect():
    sock = find_socket()
    adb("forward", "tcp:%d" % PORT, "localabstract:%s" % sock)
    cdp = Cdp(page_target())
    cdp.send("Runtime.enable")
    return cdp, sock


def cmd_list(cdp):
    anims = cdp.js(JS_ANIMS) or []
    running = [a for a in anims if a["state"] == "running"]
    print("RUNNING ANIMATIONS  %d running / %d in the document" % (len(running), len(anims)))
    for a in anims:
        ms = "%.1fs" % (a["dur"] / 1000.0) if isinstance(a["dur"], (int, float)) else str(a["dur"])
        print("  %-10s %-46s iter=%-8s dur=%-7s %s" %
              (a["name"], a["sel"], a["iter"], ms, a["state"]))
    forever = [a for a in running if a["iter"] == "forever"]
    print("")
    print("VERDICT  %d animation(s) run forever while the app is in the front:" % len(forever))
    for a in forever:
        print("           %s on %s" % (a["name"], a["sel"]))
    if not forever:
        print("           none - a foreground cost would then be compositing or poll, not animation")
    return 0


def cmd_raf(cdp, seconds):
    res = cdp.js(JS_RAF % int(seconds * 1000), await_promise=True)
    fps = res["frames"] / (res["ms"] / 1000.0)
    print("RAF  %d frames in %d ms -> %.1f fps inside the renderer" % (res["frames"], res["ms"], fps))
    print("     (compare with `dumpsys gfxinfo %s`; the process cannot be faster than this)" % APP)
    return 0


def cmd_pause(cdp, match, resume):
    if resume:
        n = cdp.js("document.getAnimations().forEach(a => a.play());"
                   "document.getAnimations().length")
        print("RESUME  %s animation(s) playing again" % n)
        return 0
    expr = r"""
    (() => {
      const m = %s;
      const hit = [], miss = [];
      for (const a of document.getAnimations()) {
        const ef = a.effect || {}, t = ef.target;
        let sel = t && t.tagName ? t.tagName.toLowerCase() : '?';
        if (t && t.id) sel += '#' + t.id;
        if (t && t.classList && t.classList.length) sel += '.' + Array.from(t.classList).join('.');
        if (ef.pseudoElement) sel += ef.pseudoElement;
        const s = (a.animationName || '') + ' ' + sel;
        (s.indexOf(m) >= 0 ? (a.pause(), hit) : miss).push(s);
      }
      return {hit: hit, miss: miss};
    })()
    """ % json.dumps(match)
    res = cdp.js(expr) or {"hit": [], "miss": []}
    print("PAUSED  %d animation(s) matching %r:" % (len(res["hit"]), match))
    for h in res["hit"]:
        print("          %s" % h)
    if not res["hit"]:
        print("        none - still running: %s" % ", ".join(res["miss"]))
        return 1
    print("        now re-read the Android side, e.g.")
    print('          adb shell dumpsys gfxinfo %s reset; sleep 10; adb shell dumpsys gfxinfo %s'
          ' | findstr "Total frames"' % (APP, APP))
    return 0


def cmd_metrics(cdp):
    cdp.send("Performance.enable")
    m = {x["name"]: x["value"] for x in cdp.send("Performance.getMetrics")["metrics"]}
    for k in ("TaskDuration", "ScriptDuration", "LayoutDuration", "RecalcStyleDuration",
              "LayoutCount", "RecalcStyleCount", "DevToolsCommandDuration", "JSHeapUsedSize"):
        if k in m:
            v = m[k]
            print("  %-22s %s" % (k, ("%.3f s" % v) if k.endswith("Duration") else v))
    print("")
    print("Cumulative since the page loaded: subtract two readings to attribute a window.")
    return 0


def cmd_rect(cdp, sel):
    r = cdp.js(JS_RECT % (json.dumps(sel), json.dumps(sel)))
    if not r:
        print("RECT  nothing matches %r - is the app on the right screen?" % sel)
        return 1
    css, dpr = r["css"], r["dpr"]
    print("RECT  %s" % sel)
    print("      viewport  x=%.1f y=%.1f  %.1f x %.1f CSS px" % (css["x"], css["y"], css["w"], css["h"]))
    print("      dpr=%.3f  innerViewport %sx%s  screen %sx%s  document %sx%s" %
          (dpr, r["view"]["w"], r["view"]["h"], r["screen"]["w"], r["screen"]["h"],
           r["doc"]["w"], r["doc"]["h"]))
    # The WebView fills the activity's window, so device px = CSS px * dpr with the window's own
    # origin at 0,0 (the status bar is drawn *over* the page - the page's own header clears it).
    cx = (css["x"] + css["w"] / 2.0) * dpr
    cy = (css["y"] + css["h"] / 2.0) * dpr
    print("      centre    %.0f,%.0f device px" % (cx, cy))
    print("      aim it:   adb shell input tap %.0f %.0f" % (cx, cy))
    return 0


def cmd_waking(cdp, match):
    r = cdp.js(JS_WAKING) or {}
    anims = [a for a in (r.get("anims") or []) if match in (a.get("name") or "") + a.get("sel", "")]
    running = [a for a in anims if a["state"] == "running"]
    forever = [a for a in anims if a["iter"] == "forever"]
    print("WAKING  class of .vortex = %r" % r.get("cls"))
    print("        %d vortex animation(s): %d running, %d forever" % (len(anims), len(running), len(forever)))
    for a in anims:
        print("        %-10s %-22s iter=%-8s %-8s t=%s ms" %
              (a["name"], a["sel"], a["iter"], a["state"], a["cur"]))
    if not anims:
        print("        none - the ring and the glow are resting still (that is the v3.3.1 fix)")
    return 0


def cmd_wake(cdp, sel, ms, interval, dry):
    if dry:
        print("CLIPBOARD  the tap would grab %r (nothing was tapped)" % (cdp.js(JS_CLIP, await_promise=True),))
        return 0
    before = cdp.js(JS_WAKING) or {}
    print("BEFORE  class=%r  %d vortex animation(s)" % (before.get("cls"), len(before.get("anims") or [])))
    if not cdp.js(JS_WAKE_TAP % json.dumps(sel)):
        print("WAKE    nothing matches %r" % sel)
        return 1
    print("WAKE    clicked %s - sampling every %d ms for %d ms (the click is the tap's own handler)" %
          (sel, interval, ms))
    t0, steps, names, peak, saw_forever = None, int(ms / interval), set(), 0, False
    for i in range(steps + 1):
        s = cdp.js(JS_WAKE_SAMPLE) or {}
        if t0 is None:
            t0 = s.get("t")
        rel = (s.get("t") or 0) - (t0 or 0)
        anims = s.get("anims") or []
        for a in anims:
            names.add(a["name"])
            if a["iter"] == "forever":
                saw_forever = True
        peak = max(peak, len(anims))
        desc = ", ".join("%s(%s,%s,t=%sms)" % (a["name"], a["iter"], a["state"], a["cur"])
                         for a in anims) or "none"
        print("  t=%+5d ms  class=%-16r  %s" % (rel, s.get("cls"), desc))
        if i < steps:
            time.sleep(interval / 1000.0)
    print("")
    if not names:
        print("VERDICT  wake seen on %s: NO - nothing animated, the tap changed nothing" % sel)
        return 1
    print("VERDICT  wake seen on %s: yes - %d name(s) %s, at most %d at once" %
          (sel, len(names), sorted(names), peak))
    print("         forever animations among them: %s" %
          ("YES - REGRESSION, something loops" if saw_forever
           else "none - one pass each, then still (the v3.3.1 fix holds)"))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="every animation and where it lives")
    p = sub.add_parser("raf", help="the renderer's own fps")
    p.add_argument("--seconds", type=float, default=3.0)
    p = sub.add_parser("pause", help="stop animations matching a name/selector")
    p.add_argument("--match", default="")
    sub.add_parser("resume", help="play everything again")
    sub.add_parser("metrics", help="the renderer's Performance counters")
    p = sub.add_parser("rect", help="an element's box in CSS px and device px, to aim a real tap")
    p.add_argument("--match", default=".vortex")
    p = sub.add_parser("waking", help="the .vortex class and the animations on it (after a tap)")
    p.add_argument("--match", default="")
    p = sub.add_parser("wake", help="click .vortex from the page (its own handler) and sample the wake in time")
    p.add_argument("--match", default=".vortex")
    p.add_argument("--ms", type=int, default=2200)
    p.add_argument("--interval", type=int, default=150)
    p.add_argument("--dry", action="store_true", help="only report what the tap would grab - no tap")
    args = ap.parse_args()

    cdp, sock = connect()
    print("device socket @%s  (forwarded to 127.0.0.1:%d)" % (sock, PORT))
    if args.cmd == "list":
        rc = cmd_list(cdp)
    elif args.cmd == "raf":
        rc = cmd_raf(cdp, args.seconds)
    elif args.cmd == "pause":
        rc = cmd_pause(cdp, args.match, resume=False)
    elif args.cmd == "resume":
        rc = cmd_pause(cdp, None, resume=True)
    elif args.cmd == "rect":
        rc = cmd_rect(cdp, args.match)
    elif args.cmd == "waking":
        rc = cmd_waking(cdp, args.match)
    elif args.cmd == "wake":
        rc = cmd_wake(cdp, args.match, args.ms, args.interval, args.dry)
    else:
        rc = cmd_metrics(cdp)
    sys.exit(rc)


if __name__ == "__main__":
    main()

