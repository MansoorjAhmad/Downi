"""core_web_audit.py -- Part 3's measurements, taken from the page itself.

Part 3 is the UI-polish bucket ("flat icons, spacing, typography" -- the owner's list) and this repo's
rule for it is the same as everywhere else: measure first, choose, then touch. This asks the app's own
renderer -- over the same DevTools socket `core_web_anim.py` uses -- for the one account a screenshot
cannot give: every interactive element's geometry and type, in CSS px (on this device CSS px IS dp: the
WebView's devicePixelRatio equals the screen density, 2.75).

What each measurement is for:

  tap targets   every control smaller than 48 px in either direction. 48 is the platform's own minimum
                for a touch target, so a small one is a defect for a thumb, not a matter of taste.
  below fold    interactive elements whose rect starts below window.innerHeight -- a primary action the
                owner must scroll to reach (first seen by hand in DEVICE_TEST.md 0k).
  type scale    every distinct font-size/weight in use, with counts and one example selector each.
  spacing       the distinct paddings in use, with counts.

The app must be in the front (debug build, so `setWebContentsDebuggingEnabled` is on). The tool does not
navigate: it audits the screen in front, and prints the controls' own coordinates so the caller can tap
them -- which is how it walked all five screens on 2026-09-28.

    python tools\\core_web_audit.py
    python tools\\core_web_audit.py --json test_out\\_web_audit_grab.json
"""
import argparse
import json
import sys
import time

sys.path.insert(0, __file__.rsplit("\\", 1)[0])

import core_web_anim as W                                       # noqa: E402

JS_AUDIT = r"""
(() => {
  const px = v => Math.round(v * 10) / 10;
  const sel = el => {
    let s = el.tagName.toLowerCase();
    if (el.id) s += '#' + el.id;
    if (el.className && typeof el.className === 'string') {
      const c = el.className.trim().split(/\s+/).filter(Boolean).slice(0, 2);
      if (c.length) s += '.' + c.join('.');
    }
    return s;
  };
  const vis = el => {
    const r = el.getBoundingClientRect();
    if (r.width <= 2 || r.height <= 2) return false;
    const cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity || '1') > 0.05;
  };
  const IH = window.innerHeight;
  const nodes = [...document.querySelectorAll(
    'button,a,input,select,textarea,[role=button],[role=tab],[onclick],[class*=btn],[class*=chip],[class*=tab],[class*=dock]')]
    .filter(vis);
  const targets = nodes.map(el => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return { sel: sel(el), label: (el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 34),
             x: px(r.x), y: px(r.y), w: px(r.width), h: px(r.height),
             fs: px(parseFloat(cs.fontSize) || 0), below: px(r.bottom - IH), top_below: px(r.top - IH) };
  });
  const sizes = {}, pads = {};
  [...document.querySelectorAll('body *')].forEach(el => {
    if (!el.textContent || !el.textContent.trim()) return;
    const cs = getComputedStyle(el);
    const k = cs.fontSize + ' / ' + cs.fontWeight;
    if (!sizes[k]) sizes[k] = { n: 0, eg: sel(el) };
    sizes[k].n++;
    const p = [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].join(' ');
    if (p !== '0px 0px 0px 0px') pads[p] = (pads[p] || 0) + 1;
  });
  return { view: { w: window.innerWidth, h: IH },
           dpr: window.devicePixelRatio,
           doc: { w: document.documentElement.scrollWidth, h: document.documentElement.scrollHeight },
           title: document.title, n_targets: nodes.length, targets: targets,
           active: (document.querySelector('.nav-item.on') || {}).id || '',
           marks: ['#inputManualUrl', '#btnInspectLink', '#lastGrabChip', '#vaultSearch', '#vaultChipAll',
                   '#setAccent', '#queueActiveContainer', '#inspectorCard', '#dropSettingsCard']
                  .filter(s => document.querySelector(s)),
           sizes: sizes, pads: pads };
})()
"""


def main():
    try:                       # labels carry the app's own emoji; keep the console ASCII-safe
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default="", help="also write the raw measurement here")
    ap.add_argument("--tab", default="", help="click a dock tab first: grab|queue|vault|settings")
    ap.add_argument("--click", default="", help="click this selector first (e.g. '#lastGrabChip')")
    args = ap.parse_args()

    cdp, sock = W.connect()
    if args.click:
        sel = args.click
        if sel[:1] not in ("#", ".", "["):
            sel = "#" + sel                      # a bare word is an id: cmd eats a literal '#' otherwise
        got = cdp.js("(() => { const el = document.querySelector(%s);"
                     " if (!el) return 'missing'; el.click(); return el.tagName + '#' + el.id; })()"
                     % json.dumps(sel))
        print("nav   clicked %s -> %s" % (sel, got))
        time.sleep(1.2)
    if args.tab:
        # The DOM's own click, not an injected tap: the page is a single document and this is exactly
        # what a finger does to the same button (injected taps at the dock's coordinates stopped
        # landing on this build, 2026-09-28 -- the audit tool had to stop depending on them).
        tid = "#nav" + args.tab.strip().capitalize()
        got = cdp.js("(() => { const el = document.querySelector('%s');"
                     " if (!el) return 'missing'; el.click(); return el.id; })()" % tid)
        print("nav   clicked %s -> %s" % (tid, got))
        time.sleep(1.0)
    data = cdp.js(JS_AUDIT)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=1)

    view = data["view"]
    print("SCREEN  %s   viewport %dx%d css   dpr %.2f   document %dx%d   %d interactive element(s)"
          % (data["title"] or "(untitled)", view["w"], view["h"], data["dpr"],
             data["doc"]["w"], data["doc"]["h"], data["n_targets"]))
    print("        active tab: %s   markers: %s"
          % (data.get("active") or "(none)", ", ".join(data.get("marks") or []) or "(none)"))

    small = [t for t in data["targets"] if t["w"] < 48 or t["h"] < 48]
    print("")
    print("TAP TARGETS under 48 px (the platform's own minimum for a thumb): %d of %d"
          % (len(small), data["n_targets"]))
    for t in sorted(small, key=lambda t: min(t["w"], t["h"]))[:14]:
        print("  %-34s %5.1f x %5.1f  at %6.1f,%6.1f  %s"
              % (t["sel"][:34], t["w"], t["h"], t["x"], t["y"],
                 ("'" + t["label"] + "'") if t["label"] else ""))

    below = [t for t in data["targets"] if t["top_below"] > 0]
    print("")
    print("BELOW THE FOLD (top past window.innerHeight %d): %d" % (view["h"], len(below)))
    for t in sorted(below, key=lambda t: t["top_below"])[:10]:
        print("  %-34s top %+7.1f px past the fold   %s"
              % (t["sel"][:34], t["top_below"], ("'" + t["label"] + "'") if t["label"] else ""))

    print("")
    print("TYPE SCALE  %d distinct size/weight combination(s)" % len(data["sizes"]))
    for k, v in sorted(data["sizes"].items(), key=lambda kv: -kv[1]["n"])[:14]:
        print("  %-22s x%-4d  e.g. %s" % (k, v["n"], v["eg"]))

    print("")
    print("SPACING  %d distinct padding value(s), most used first" % len(data["pads"]))
    for k, n in sorted(data["pads"].items(), key=lambda kv: -kv[1])[:12]:
        print("  x%-4d  %s" % (n, k))

    print("")
    verdict = []
    if small:
        verdict.append("%d control(s) below 48 px" % len(small))
    if below:
        verdict.append("%d control(s) below the fold" % len(below))
    print("VERDICT  " + (", ".join(verdict) if verdict else "nothing small, nothing hidden on this screen"))
    print("         socket %s" % sock)
    return 0


if __name__ == "__main__":
    sys.exit(main())
