"""Audit the Core's state spectrum from the phone's own screenshots (cells K-A3 / K-A4).

`CoreLook.of(state, t, progress)` says what every state should paint; `core_review_sheets.ps1`
lays the shots out in that order. This script turns that strip into numbers, because one thing
in it is easy to miss by eye: `L.error` is set by FAILED **alone** (`CoreLook`), and only
`error > 0.25` selects the rose rim in `CoreHost.onDraw` - so a rose rim on any other state
(PAUSED is the one to watch) is a real defect, not a mood.

Per shot it reports:
    rim      the colour of the rim ring, and which family it is (teal / rose / neutral)
    mark     the teal glyph's size and share of the disc
    bars     the near-white PAUSED bars (must be ~0 in every other state)
    ring     how much of the energy perimeter is lit = the progress (K-A4; never text)

Geometry is the Core's known geometry, the same formulas `core_mark_measure.py` uses:
window px = size_dp x density, r = px/2 - 6dp, rIn = r - 1.2dp.

Usage:
    python tools/core_state_audit.py dir test_out/core_visual --at 500,1000 --glob "core_state_*.png"
    python tools/core_state_audit.py shot test_out/core_visual/core_state_paused.png --at 500,1000
    python tools/core_state_audit.py dir test_out/core_visual --at 500,1000 --glob "core_progress_*.png" --want-ring

`--want-ring` expects the shots' *names* to carry the progress they were taken at
(`core_progress_050.png` = 50%) and checks the painted arc against it - that is K-A4's
"the perimeter IS the progress" claim, measured.

When a number is questioned, two modes print the raw measurement behind it:
    profile <shot> --at 500,1000 --rays 0,90   the radial luminance profile, with each band labelled
    span    <shot> --at 500,1000               the lit arc's angular runs, in degrees

Run by `tools/core_review_sheets.ps1` right after it writes the strips, so a strip and its numbers
are always produced together (logs `_gate_kA3_audit.log`, `_gate_kA4_audit.log`).
"""
import argparse
import colorsys
import glob
import math
import os
import re
import sys

from PIL import Image, ImageDraw

from core_mark_measure import (DENSITY_DEFAULT, RIN_DP, R_INSET_DP, rel)

# CoreHost.onDraw: the rose rim is chosen by `L.error > 0.25`, and CoreLook.of() sets L.error
# in exactly one branch - FAILED. Everything else must read teal.
ROSE_STATE = "failed"

RIM_EDGE_DP = 1.7         # rim stroke: centre r-0.9dp, width 1.6dp -> its INNER edge is r-1.7dp
RIM_OUTER_DP = 0.3        # ... and its outer edge is r-0.3dp
RING_IN_DP = 1.45         # arc band, measured inward from rIn (see scan_ring)
RING_OUT_DP = 0.95        # ... and stopped >=0.6dp short of the rim's antialiased inner edge
BAR_LUMA = 200            # the PAUSED bars are 0xFFEAF9FF over a dark disc
BAR_MIN_PX = 150          # 623 px when PAUSED; a few specular pixels on the glass arc are not bars
ARC_LUMA = 95             # mean channel: lit arc 112..210, dim track ~40, glass layer ~86
ARC_SPREAD = 70
ARC_MERGE_DEG = 2.0       # the sweep's blue end at low progress flickers across the floor
EDGE_LUMA = 100           # the rim itself: ~109 at its darkest (blue end), 133 idle, ~210 lit
EDGE_STEP = 70            # disc (~20) -> rim is a step of 80+; the glass layer only adds ~60
SEED_MIN, SEED_MAX = 40, 95     # radii, in px, the edge walk starts and stops at

# --- V3.3 baked/staged art: bands MEASURED, not derived ----------------------------------------
# The staged Core (M2, 2026-09-27) draws the sheet's orb with the Lottie energy ring INSIDE its own
# glowing membrane, and smaller than the procedural Core every band above is calibrated to - so on it
# those bands read "no rim at all" and "lit 0%": they scan radius 64.2..65.6 px (arc) and 66.8..70.7
# (rim) at 64 dp, while the built Core's content ends at ~57 px and the app behind starts at ~60.
#
# Measured instead, by fitting a circle to the closed ring of `core progress 100%` at three Core sizes
# (`state progress`, CORE_ATTACH 452,1080, so the Core is centred in the window - offset <=0.8 px):
#      48 dp: ring mid r = 36.5 px      56 dp: 45.0 px      64 dp: 53.6 px   (max error 0.03 px)
# which is   mid = ART_RING_FRAC * (size_dp * density / 2) - ART_RING_INSET_DP * density.
#
# The stroke runs mid-3.6 .. mid+1.5 px, and the arc reads truest at its INNER edge, because the orb's
# own membrane light reaches ~137 luma in the same band while the arc is 110..232. Measured over the
# five named progress values at 64 dp (`core_progress_0/25/50/75/100`), painted degrees as a share of
# the circle:
#      r=49 px:   2  12  39  53  49      (cuts the stroke: reads low)
#      r=50 px:   9  24  49  74  99      <- the band below
#      r=51 px:  19  28  51  77 100      (reaches the membrane: floor 19)
#      whole annulus 49..56: 239 254 248 306 360   (unusable - the membrane lights most angles)
# and the art's own gloss fragments into many SHORT runs where a real arc is one long one - at 64 dp
# the angular runs are 186..196 202..205 214 228..230 236 292..316 338 340..342 344..351 at progress
# 0 (9 runs, longest 24 deg), 20 deg the longest on idle and 18 deg on detected, versus 0..81 plus
# 268..360 (i.e. one 173 deg arc across the 12 o'clock wrap) at 50%. So runs shorter than
# ART_MIN_RUN_DEG are the membrane's speculars, not the arc: gating on it reads 0/82/173/263/360 for
# the five named values = 0/23/48/73/100 % of the circle, i.e. every one inside the 6 pt K-A4
# tolerance that the procedural band has always used. Without the gate the same band reads
# 13/25/52/75/100 and the 0 % case alone is 13 pts out.
ART_RING_FRAC = 0.7773          # ring mid radius as a fraction of the window's half side
ART_RING_INSET_DP = 5.388       # ... minus this much, in dp
ART_ARC_IN_DP = 1.45            # arc band: inward from the ring's mid radius (1.45dp -> 49.6 px @64dp)
ART_ARC_OUT_DP = 1.15           # ... to 1.15dp -> 50.4 px: the outer limit stays off the membrane
ART_MEMBRANE_DP = 1.5           # the membrane either side of mid, read for the state's colour family
ART_MIN_RUN_DEG = 25.0          # shorter angular runs than this are the art's speculars, not the arc


def art_ring_mid(size_dp, density):
    """The V3.3 baked art's energy-ring radius in px, from the measured 48/56/64 dp fit."""
    return ART_RING_FRAC * (size_dp * density / 2.0) - ART_RING_INSET_DP * density
FIT_RESIDUAL_MAX = 2.5    # px; above this the "circle" is the app's icons, not the Core


def family(rgb):
    h, s, v = colorsys.rgb_to_hsv(rgb[0] / 255.0, rgb[1] / 255.0, rgb[2] / 255.0)
    deg = h * 360.0
    if s < 0.12:
        return "neutral", deg, s
    if deg <= 25.0 or deg >= 320.0:
        return "rose", deg, s
    if 150.0 <= deg <= 220.0:
        return "teal", deg, s
    return "other", deg, s


def median(vals):
    s = sorted(vals)
    return s[len(s) // 2]


def sample(img, cx, cy, rad, ang):
    x = int(round(cx + rad * math.cos(ang)))
    y = int(round(cy + rad * math.sin(ang)))
    if 0 <= x < img.width and 0 <= y < img.height:
        return img.getpixel((x, y))
    return None


def lum_of(rgb):
    return (rgb[0] + rgb[1] + rgb[2]) / 3.0


def fit_disc(img, cx0, cy0):
    """Locate the Core in the shot instead of trusting the position that was asked for.

    The rim's inner edge is a hard luminance step (dark disc ~20 -> rim 109..210), so it is found on
    360 spokes and then recovered with two 2-parameter least squares fits - x = cx + A*cos(a) and
    y = cy + B*sin(a), which is linear because the angles are known. `--at` is only the seed for the
    walk: WindowManager (or the ROM) can place the window where it likes, and a 2 px error moves this
    script's 1.9 px arc band right off the arc.

    Returns (cx, cy, edge_radius, residual, n) or (None, reason).
    """
    pts = []
    for i in range(360):
        a = math.radians(i)
        ca, sa = math.cos(a), math.sin(a)
        for rad in range(SEED_MIN, SEED_MAX):
            x = int(round(cx0 + rad * ca))
            y = int(round(cy0 + rad * sa))
            if not (0 <= x < img.width and 0 <= y < img.height):
                break
            lum = lum_of(img.getpixel((x, y)))
            if lum >= EDGE_LUMA:
                x3 = int(round(cx0 + (rad - 3) * ca))
                y3 = int(round(cy0 + (rad - 3) * sa))
                if (0 <= x3 < img.width and 0 <= y3 < img.height
                        and lum - lum_of(img.getpixel((x3, y3))) >= EDGE_STEP):
                    pts.append((ca, sa, x, y))
                break
    if len(pts) < 180:
        return None, "only %d/%d spokes hit a rim edge" % (len(pts), 360)

    for attempt in range(2):                                  # one robust refit
        n = float(len(pts))
        sc = sum(p[0] for p in pts)
        ss = sum(p[1] for p in pts)
        scc = sum(p[0] * p[0] for p in pts)
        sss = sum(p[1] * p[1] for p in pts)
        sx = sum(p[2] for p in pts)
        sy = sum(p[3] for p in pts)
        sxc = sum(p[2] * p[0] for p in pts)
        sys_ = sum(p[3] * p[1] for p in pts)
        # [n sc; sc scc] [cx A] = [sx; sxc]   and   [n ss; ss sss] [cy B] = [sy; sys_]
        detx = n * scc - sc * sc
        dety = n * sss - ss * ss
        if detx == 0 or dety == 0:
            return None, "degenerate fit"
        cx = (sx * scc - sc * sxc) / detx
        a_rad = (n * sxc - sc * sx) / detx
        cy = (sy * sss - ss * sys_) / dety
        b_rad = (n * sys_ - ss * sy) / dety
        r_edge = (a_rad + b_rad) / 2.0
        resid = [abs(math.hypot(p[2] - cx, p[3] - cy) - r_edge) for p in pts]
        rms = math.sqrt(sum(r * r for r in resid) / n)
        if attempt == 1 or rms <= FIT_RESIDUAL_MAX:
            return cx, cy, r_edge, rms, len(pts), "ok"
        keep = [p for p, r in zip(pts, resid) if r <= 2.0 * FIT_RESIDUAL_MAX]
        if len(keep) < 180:
            return None, "fit residual %.1f px on %d spokes" % (rms, len(pts))
        pts = keep
    return None, "no fit"


def scan_rim(img, cx, cy, r, dp, mid=None):
    """The most saturated pixel across the rim stroke, per angle.

    `mid` (the baked art's ring radius, in px) switches the band from the procedural rim stroke to the
    art's own membrane - see the ART_* note at the top of this file.
    """
    best_per_angle = []
    steps = 14
    if mid is None:
        rad_lo = r - RIM_EDGE_DP * dp
        rad_hi = r - RIM_OUTER_DP * dp
    else:
        rad_lo = mid - ART_MEMBRANE_DP * dp
        rad_hi = mid + ART_MEMBRANE_DP * dp
    for i in range(720):
        a = math.radians(i * 0.5)
        best = None
        for k in range(steps + 1):
            rad = rad_lo + k * ((rad_hi - rad_lo) / steps)
            rgb = sample(img, cx, cy, rad, a)
            if rgb is None:
                continue
            spread = max(rgb) - min(rgb)
            if best is None or spread > best[1]:
                best = (rgb, spread)
        if best and best[1] >= 25:
            best_per_angle.append(best[0])
    if not best_per_angle:
        return None, 0, 720
    med = (median([c[0] for c in best_per_angle]),
           median([c[1] for c in best_per_angle]),
           median([c[2] for c in best_per_angle]))
    return med, len(best_per_angle), 720


def is_arc(rgb):
    """A lit perimeter pixel.

    Luminance, not `min(rgb)`: the arc is saturated cyan (34,211,238), whose *red* channel is tiny,
    so a min-channel floor silently passes only the near-white ends of the rim gradient. The first
    cut of this script did that and reported every progress shot as ~14%.

    The radial profile that settled it (`... profile <shot> --at x,y`, core_progress_100 at 90deg, r in px):
        63:13 64:74 65:173 66:173 67:173 68:173 69:173 70:173 71:146 72:18
    - the arc+rim merge from r=65 to r=71, and r>=72 is the app behind the Core (dark here, the
    blue wallpaper elsewhere), which is why the band below stops where it does.
    """
    return ((rgb[0] + rgb[1] + rgb[2]) / 3.0) >= ARC_LUMA and (max(rgb) - min(rgb)) >= ARC_SPREAD


def scan_ring(img, cx, cy, r_in, dp, mid=None):
    """Read the energy perimeter: (lit fraction, painted extent in degrees, runs).

    `mid` (the baked art's ring radius, in px) replaces this band with the art's own, measured one -
    the ART_* note at the top of the file records the fit and the five progress readings behind it.

    How much of the energy perimeter is lit: 0..1 of 720 angular samples.

    The arc (2.6dp wide on rIn) and the rim (1.6dp wide on r-0.9dp) overlap almost completely, so
    the arc is read in the only gap between them: rIn-1.45dp .. rIn-0.95dp, which at 64dp is radius
    64.2..65.6 - inside the arc's stroke (its inner edge is rIn-1.3dp = 64.6) and a clear 0.6dp below
    the rim's inner edge (r-1.7dp = 66.8, antialiased from about 66.3).

    That clearance is not cosmetic. Measured on `core_progress_025`, which the phone drew as a 92deg
    arc (25% plus round caps):
        centre (587.7,1087.7), band 64.2..65.6  -> lit 26.9%   (the caps' ~1.3 pts)
        centre (587.4,1088.2), band 64.6..66.0  -> lit 33.0%   (0.58px of fit error reached the rim)

    Three earlier cuts of this function got it wrong, and the failure mode is invisible in the
    output, which is why each one is recorded here:
      * band rIn-1.8dp..rIn+1.8dp read the always-on rim, so *every* state reported "lit" (idle 46%);
      * `min(rgb) >= 110` rejected saturated cyan, whose red channel is 34 (every progress shot: 14%);
      * a 110 luminance floor fragmented the arc where the sweep's blue end composites to ~112 at low
        progress (18 runs instead of one 89deg arc at 25%), so the floor is 95 - still 9 clear of the
        glass layer at ~86, which is the next brightest thing inside the disc;
      * anchoring the geometry on the fitted rim edge moved the band 0.6px outward onto the rim's
        inner edge, whose blue end has spread 134, so idle read 69% and progress_000 92%.

    A radial profile is the ground truth for the band - `python tools/core_state_audit.py profile
    <shot> --at x,y` prints exactly this. core_progress_100 at 90deg, r in px =
    63:13 64:74 65:173 66:173 67:173 68:173 69:173 70:173 71:146 72:18, versus
    core_state_idle at 90deg = 64:15 65:27 66:46 67:123 68:123 69:123 70:124 71:77. The arc's
    angular extent is `... span <shot> --at x,y`: 92 deg at 25% and 184 deg at 50% (i.e. 360p plus
    the round caps), which is why the K-A4 tolerance is not tighter than a few points.
    """
    lo = r_in - RING_IN_DP * dp
    hi = r_in - RING_OUT_DP * dp
    if mid is not None:                       # baked art: the measured band (ART_* note at the top)
        lo = mid - ART_ARC_IN_DP * dp
        hi = mid - ART_ARC_OUT_DP * dp
    hot = []
    for i in range(720):
        a = math.radians(i * 0.5)
        for k in range(5):
            rad = lo + k * ((hi - lo) / 4.0)
            rgb = sample(img, cx, cy, rad, a)
            if rgb is not None and is_arc(rgb):
                hot.append(i * 0.5)
                break

    runs = []
    for ang in hot:
        if runs and ang - runs[-1][1] <= ARC_MERGE_DEG:
            runs[-1][1] = ang
        else:
            runs.append([ang, ang])
    if mid is not None:
        # Baked art only: drop the membrane's specular fragments (ART_MIN_RUN_DEG note above). A
        # wrapped run is measured the same way `extent` below measures it, so 268..360 counts as 92 deg.
        kept = []
        for a, b in runs:
            d = (b - a) % 360.0
            if d == 0.0 and b != a:
                d = 360.0
            if d >= ART_MIN_RUN_DEG:
                kept.append([a, b])
        runs = kept

    extent = 0.0
    for a, b in runs:
        d = (b - a) % 360.0
        if d == 0.0 and b != a:
            d = 360.0
        extent += d

    # Two answers, on purpose. The *fraction* (samples above the floor) understates the arc where
    # the sweep's blue end composites near the floor; the *extent* is what a viewer sees. At a
    # named 25% the phone drew a 89deg arc and the fraction read 23%.
    return len(hot) / 720.0, extent, runs


def count_where(img, cx, cy, radius, pred):
    """Pixels satisfying pred inside a circle, with a bounding box for the report."""
    x0, y0 = max(0, int(cx - radius)), max(0, int(cy - radius))
    x1, y1 = min(img.width - 1, int(cx + radius)), min(img.height - 1, int(cy + radius))
    n = 0
    bx0 = by0 = 10 ** 9
    bx1 = by1 = -1
    for y in range(y0, y1 + 1):
        dy = y - cy
        for x in range(x0, x1 + 1):
            dx = x - cx
            if dx * dx + dy * dy > radius * radius:
                continue
            rgb = img.getpixel((x, y))
            if pred(rgb):
                n += 1
                bx0, by0 = min(bx0, x), min(by0, y)
                bx1, by1 = max(bx1, x), max(by1, y)
    box = None if n == 0 else (bx1 - bx0 + 1, by1 - by0 + 1)
    return n, box


def is_white(rgb):
    return min(rgb) >= BAR_LUMA and (max(rgb) - min(rgb)) <= 30


def is_teal(rgb):
    """The same rule core_mark_measure.py measures the mark with."""
    r, g, b = rgb
    return g >= 90 and b >= 80 and (g - r) >= 20 and g >= b - 30


def state_of(path):
    """`core_state_paused.png` -> paused; `core_progress_050.png` -> the progress 0.50.

    The gate writes the last few shots of a run as `core_zz_*` (hidden, idle-after), so that prefix
    is stripped here too - without it `core_zz_hidden` was not recognised as "hidden" and the script
    reported a MISMATCH with no explanation on screen.
    """
    stem = os.path.splitext(os.path.basename(path))[0]
    if stem.startswith("core_zz_"):
        return stem[len("core_zz_"):].replace("_after", ""), None
    if stem.startswith("core_state_"):
        return stem[len("core_state_"):].replace("_mid", "").replace("_end", ""), None
    if stem.startswith("core_progress_"):
        tail = stem[len("core_progress_"):]
        try:
            return "progress", int(tail) / 100.0
        except ValueError:
            return "progress", None
    return stem, None


def audit_one(path, at, size_dp, density, want_ring, save, fixed=False):
    img = Image.open(path).convert("RGB")
    px = int(round(size_dp * density))
    ax, ay = at
    seed_x, seed_y = ax + px / 2.0, ay + px / 2.0
    name, want = state_of(path)

    print("%s" % os.path.basename(path))
    if fixed:
        # V3.3: the Core is baked art, so trust the window (CORE_ATTACH logs the rect) instead of
        # fitting a rim stroke that no longer exists. See geometry()'s note.
        cx, cy, r_edge, resid, spokes = seed_x, seed_y, None, 0.0, 0
    else:
        fit = fit_disc(img, seed_x, seed_y)
        if fit[0] is None:
            print("  geom  window %d px (%d dp @ %.2f), seeded %d,%d   %s"
                  % (px, size_dp, density, ax, ay, fit[1]))
            if name in ("hidden", "idle"):
                print("  state OK: nothing is meant to be on screen here")
                return "OK"
            print("  state MISMATCH: no Core to measure (%s)" % fit[1])
            return "MISMATCH: no Core in the frame (%s)" % fit[1]
        cx, cy, r_edge, resid, spokes = fit[0], fit[1], fit[2], fit[3], fit[4]
    r = px / 2.0 - R_INSET_DP * density          # r comes from the window; the fit only moves the centre
    r_in = r - RIN_DP * density
    mid = art_ring_mid(size_dp, density) if fixed else None
    rim_edge_want = r - RIM_EDGE_DP * density    # 66.8 at 64dp: where an unlit Core's rim starts
    arc_edge_want = r_in - 1.3 * density         # 64.6: where the lit arc's inner edge starts
    print("  geom  window %d px (%d dp @ %.2f)  r=%.1f  rIn=%.1f  seeded %d,%d"
          % (px, size_dp, density, r, r_in, ax, ay))
    if fixed:
        print("  geom  --fixed: centre (%.1f,%.1f) taken from the window, no disc fit (V3.3 baked art)"
              % (cx, cy))
        print("  geom  --fixed: art ring mid %.1f px | arc band %.1f..%.1f | membrane %.1f..%.1f"
              % (mid, mid - ART_ARC_IN_DP * density, mid - ART_ARC_OUT_DP * density,
                 mid - ART_MEMBRANE_DP * density, mid + ART_MEMBRANE_DP * density))
    else:
        where = ("the rim (nothing lit)" if abs(r_edge - rim_edge_want) <= abs(r_edge - arc_edge_want)
                 else "the arc's inner edge, so the perimeter is lit")
        print("  fit   centre (%.1f,%.1f) = seed + (%+.1f,%+.1f) px"
              "   residual %.2f px over %d spokes"
              % (cx, cy, cx - seed_x, cy - seed_y, resid, spokes))
        print("  fit   edge r=%.1f px lands on %s (rim edge %.1f, arc edge %.1f)"
              % (r_edge, where, rim_edge_want, arc_edge_want))

    rim_rgb, rim_hits, rim_total = scan_rim(img, cx, cy, r, density, mid)
    if rim_rgb is None:
        rim_fam = "none"
        print("  rim   none found on the ring (a hidden Core draws no rim)")
    else:
        rim_fam, deg, sat = family(rim_rgb)
        print("  rim   rgb(%3d,%3d,%3d)  hue=%3.0f  sat=%.2f  -> %-7s (found on %d/%d angles)"
              % (rim_rgb[0], rim_rgb[1], rim_rgb[2], deg, sat, rim_fam, rim_hits, rim_total))

    mk_n, mk_box = count_where(img, cx, cy, 0.80 * r_in, is_teal)
    area = math.pi * (0.80 * r_in) ** 2
    print("  mark  %d px teal = %.1f%% of the inner disc%s"
          % (mk_n, 100.0 * mk_n / area,
             "" if not mk_box else "   bbox %dx%d px" % mk_box))

    bar_n, bar_box = count_where(img, cx, cy, 0.55 * r_in, is_white)
    has_bars = bar_n >= BAR_MIN_PX
    print("  bars  %d px near-white inside 0.55 rIn -> %s%s"
          % (bar_n, "PAUSED bars" if has_bars else "none",
             "" if not bar_box else "   bbox %dx%d px" % bar_box))

    lit, ext, runs = scan_ring(img, cx, cy, r_in, density, mid)
    print("  ring  lit %.0f%% of the perimeter, painted %.0f deg = %.0f%% (%d run(s)%s)"
          % (lit * 100.0, ext, 100.0 * ext / 360.0, len(runs),
             ", short runs dropped as the art's speculars" if mid is not None else ""))

    if name == ROSE_STATE:
        verdict = "OK" if rim_fam == "rose" else "MISMATCH: expected rose, got " + rim_fam
    elif name in ("hidden", "idle_after"):
        verdict = "no rim expected" if rim_fam == "none" else "MISMATCH: no Core should be here"
    elif rim_fam == "none":
        verdict = "MISMATCH: no rim at all"
    else:
        verdict = ("OK" if rim_fam != "rose"
                   else "MISMATCH: rose rim, but CoreLook sets error only in failed - not " + name)
        if name not in ("paused", "resuming") and has_bars:
            verdict += " (and it drew PAUSED bars)"
    if name == "paused" and not has_bars:
        if mid is not None:
            # The two-bar look is baked into core_orb_paused.png since M1 (the view no longer draws
            # bars), so the procedural assertion cannot apply to the baked art. Its art-era substitute
            # is the mark measurement printed above - 599 px teal at 64 dp against idle's 1153 px, the
            # same discriminator DEVICE_TEST.md section 0 M1-2 records (857 px in the M1 pass).
            print("  note  PAUSED's bars are baked into the art (M1) - the mark px above is the check")
        else:
            verdict += "  MISMATCH: PAUSED must draw its bars"

    if want is not None:
        gap = abs(ext / 360.0 - want)
        print("  K-A4  painted %.0f deg vs named %d%%   %s"
              % (ext, round(want * 100),
                 "OK" if gap <= 0.06 else "MISMATCH: gap %.0f pts" % (gap * 100.0)))
        if gap > 0.06:
            verdict += "  ring-vs-name mismatch"
    print("  state %s" % verdict)

    if save:
        draw = ImageDraw.Draw(img)
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(255, 0, 90))
        draw.ellipse([cx - r_in, cy - r_in, cx + r_in, cy + r_in], outline=(255, 255, 0))
        half = r * 1.6
        img.crop((max(0, int(cx - half)), max(0, int(cy - half)),
                  min(img.width, int(cx + half)), min(img.height, int(cy + half)))).save(
                      save, quality=92)
        print("  review " + rel(save))
    return verdict


def geometry(img, at, size_dp, density, fixed=False):
    """Fit the Core's centre, take the radii from the window - the one anchor every mode shares.

    `fixed=True` trusts the window: the centre is the seed (`at` + half the window) and no disc fit
    runs. Needed from V3.3 on: the Core is now the owner's baked art (sheet C1), whose rim is painted
    into the PNG instead of being a stroked circle, so `fit_disc`'s edge walk finds nothing to lock
    onto in most states and the audit used to report "no Core to measure" for a Core that is plainly
    on screen (2026-09-27: 14 of 17 V3.3 state shots). The geometry was never in doubt - CORE_ATTACH
    logs the window rect and no fit can be more exact than the window itself.
    """
    px = int(round(size_dp * density))
    seed_x, seed_y = at[0] + px / 2.0, at[1] + px / 2.0
    if fixed:
        r = px / 2.0 - R_INSET_DP * density
        return seed_x, seed_y, r, r - RIN_DP * density, None, 0.0
    fit = fit_disc(img, seed_x, seed_y)
    if fit[0] is None:
        print("  no Core found: %s" % fit[1])
        return None
    r = px / 2.0 - R_INSET_DP * density
    return fit[0], fit[1], r, r - RIN_DP * density, fit[2], fit[3]


def do_profile(args):
    """The radial luminance profile: the measurement every band in this script is derived from.

    Print it whenever a band's placement is questioned - `scan_ring`'s docstring quotes exactly
    this output, because a wrong band is invisible in the audit's own numbers (it read 0% or 46%
    for the same state before the band was right).
    """
    img = Image.open(args.path).convert("RGB")
    at = tuple(int(v) for v in args.at.split(","))
    g = geometry(img, at, args.size_dp, args.density, args.fixed)
    if g is None:
        return 1
    cx, cy, r, r_in, r_edge, resid = g
    band_lo = r_in - RING_IN_DP * args.density
    band_hi = r_in - RING_OUT_DP * args.density
    print("%s   centre (%.1f,%.1f)  residual %.2f px" % (os.path.basename(args.path), cx, cy, resid))
    print("  disc (dark) < %.1f | arc band %.1f..%.1f | arc stroke from %.1f"
          " | rim stroke %.1f..%.1f | past %.1f = the app behind"
          % (band_lo, band_lo, band_hi, r_in - 1.3 * args.density,
             r - RIM_EDGE_DP * args.density, r - RIM_OUTER_DP * args.density, r))
    if r_edge is None:
        # --fixed skips the disc fit, so there is no fitted edge to print. This used to raise
        # TypeError here ("must be real number, not NoneType"), which made `profile --fixed` - the
        # command this file calls the ground truth for every band - crash on the V3.3 baked art.
        print("  rim edge  no fit (--fixed): read the baked art's edge off the profile itself")
    for deg in [int(v) for v in args.rays.split(",")]:
        a = math.radians(deg)
        cells = []
        for rad in range(args.r_from, args.r_to + 1):
            x = int(round(cx + rad * math.cos(a)))
            y = int(round(cy + rad * math.sin(a)))
            cells.append("%d:%3.0f" % (rad, lum_of(img.getpixel((x, y)))))
        print("  %4d deg  %s" % (deg, " ".join(cells)))
    return 0


def do_span(args):
    """The lit perimeter's angular runs - how the caps' overshoot is measured, not assumed."""
    img = Image.open(args.path).convert("RGB")
    at = tuple(int(v) for v in args.at.split(","))
    g = geometry(img, at, args.size_dp, args.density, args.fixed)
    if g is None:
        return 1
    cx, cy, r, r_in, r_edge, resid = g
    mid = art_ring_mid(args.size_dp, args.density) if args.fixed else None
    lit, ext, runs = scan_ring(img, cx, cy, r_in, args.density, mid)
    print("%s   lit %.1f%%   painted %.0f deg = %.0f%%   %d run(s): %s"
          % (os.path.basename(args.path), 100.0 * lit, ext, 100.0 * ext / 360.0, len(runs),
             "  ".join("%.0f..%.0f deg" % (a, b) for a, b in runs)))
    return 0


# --- M4 (the C5 motion contract): measure a motion pass frame by frame --------------------------
# C5 is a statement about TIME - "pause = frozen, no motion", "resume picks up where it paused",
# "complete = the ring holds its full circle before the merge" - so it cannot be checked with the
# one-shot modes above. tools\core_motion.ps1 records the pass, extracts every frame with ffmpeg and
# writes segments.csv; these two modes turn that into numbers.
FROZEN_MOVE = 1.0         # mean |delta| per pixel per frame (0..255): nothing moved at all
RING_MOVE_DP = 1.6        # +- dp around the art ring's mid: the annulus the arc stroke occupies
#                           A 2.6 dp stroke is ~2 % of the disc's area, so the disc-wide mean above is
#                           nearly blind to the ring: a 62 -> 64 % progress step lifts it ~0.2 while
#                           the annulus mean lifts several units. Both are reported and the LARGER of
#                           the two decides "did anything move", for the frozen runs and for finding
#                           the frame a step actually took effect in (see `ring_move`).
ALIVE_MOVE = 5.0          # ... and the floor that says something visibly did (the control)
FULL_SPAN = 350.0         # deg: the ring is a closed circle (short of 360 only by the caps)
FROZEN_MIN_FRAMES = 10    # a "freeze" shorter than this is a stutter, not a held state
DETECT_MOVE = 3.0         # a state change moved at least this much in its own step
ARC_SWEEP_DP = 1.6        # +- dp around the art ring's mid: the radii the painted stroke occupies
SPAN_TOL = 20.0           # deg: two steps agree about "how much of the ring is lit" within this
#                           The band sees the arc's INNER edge (mid - 1.45dp .. mid - 1.15dp), and the
#                           baked art has its own membrane in it: the same 62% download reads 176 deg
#                           in the staged PAUSED look and 189 deg while the ring is live (measured, M4
#                           pass 2026-09-27). 8 deg was tuned on the pre-stage art; a blank ring is
#                           0 deg, so the claim this guards ("the ring did not leave / the ring is back")
#                           is still caught - see `span` in do_frames.


def core_move(pa, pb, cx, cy, radius, step=2):
    """Mean |delta| per sampled pixel between two frames, over the Core's own window (0 = frozen)."""
    tot = 0
    n = 0
    r2 = radius * radius
    y = int(cy - radius)
    while y <= cy + radius:
        x = int(cx - radius)
        while x <= cx + radius:
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy <= r2:
                aa, bb = pa[x, y], pb[x, y]
                tot += abs(aa[0] - bb[0]) + abs(aa[1] - bb[1]) + abs(aa[2] - bb[2])
                n += 1
            x += step
        y += step
    return tot / (3.0 * n) if n else 0.0


def ring_move(pa, pb, cx, cy, mid, density=2.75):
    """Mean |delta| per sampled pixel between two frames, over the RING annulus alone.

    The disc-wide mean says "something moved"; this says "the RING moved", and for the C5 claims the
    second is the question. Measured on the M4 pass of 2026-09-27: when `progress 62` reached the
    service the arc appeared on the next frame and the annulus mean read ~66 while the disc mean read
    ~22 (both visible that time - the change simply landed 138 ms after its own step's window had
    closed, so the window never held it). A ring-only change is the case this guards: a 62 -> 64 %
    step moves ~2 % of the disc's pixels, which the disc mean cannot see and this can.
    """
    lo = max(0.0, mid - RING_MOVE_DP * density)
    hi = mid + RING_MOVE_DP * density
    tot = 0
    n = 0
    y = int(cy - hi)
    while y <= cy + hi:
        x = int(cx - hi)
        while x <= cx + hi:
            dx, dy = x - cx, y - cy
            d2 = dx * dx + dy * dy
            if lo * lo <= d2 <= hi * hi:
                aa, bb = pa[x, y], pb[x, y]
                tot += abs(aa[0] - bb[0]) + abs(aa[1] - bb[1]) + abs(aa[2] - bb[2])
                n += 1
            x += 1
        y += 1
    return tot / (3.0 * n) if n else 0.0


def moved(row):
    """The one number that decides "did anything move between this frame and the last".

    Frame rows are (t_ms, disc_move, span, centre, swept, ring_move); the larger of the two motion
    means wins, so neither a whole-orb motion nor a ring-only one can hide. The sentinel row the
    frozen-run scan appends is (10**9, 99.0, 0, 0, 0, 0) - 99.0 either way, so it still breaks a run.
    """
    return max(row[1], row[5])


def centre_luma(img, cx, cy, radius):
    """Mean luma inside a small disc at the Core's centre: where COMPLETING's merge flash blooms."""
    tot = 0.0
    n = 0
    r2 = radius * radius
    for y in range(int(cy - radius), int(cy + radius) + 1):
        for x in range(int(cx - radius), int(cx + radius) + 1):
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy <= r2:
                px = img.getpixel((x, y))
                tot += (px[0] + px[1] + px[2]) / 3.0
                n += 1
    return tot / n if n else 0.0


def median_of(vals):
    s = sorted(vals)
    return s[len(s) // 2] if s else 0.0


def read_segments(path):
    """segments.csv (from core_motion.ps1) -> [(name, start_ms, end_ms, effect_ms)]."""
    if not path or not os.path.isfile(path):
        return []
    out = []
    with open(path, "r") as fh:
        fh.readline()                                        # the header
        for line in fh:
            parts = line.strip().split(",", 4)
            if len(parts) < 5:
                continue
            try:
                out.append((parts[0], int(parts[2]), int(parts[3]), int(parts[4])))
            except ValueError:
                continue
    return out


def read_service_moves(frames_dir):
    """The service's own account of the releases, if the rig wrote one (tools/core_touch.ps1).

    `CORE_MOVED x= y=` is logged when a drag is released and carries the window's final rect, so line 1
    is drag_far's answer and line 2 drag_edge's - the magnet and its own control in one pair. This is
    the exact instrument for those two claims, and the pixel tracker below is not: its pairing needs
    BOTH ring crossings on one row inside a window around the centre it last held, and on the M8 pass
    (2026-09-28) it lost a Core that had travelled 237 px over the app's own teal UI ("x 539 -> 357"
    while the service's line for the same gesture read `x=215` = centre 303). Kept as a separate
    reading so the report can show both and say which one decided.
    """
    path = os.path.join(os.path.dirname(os.path.abspath(frames_dir)), "touch.log")
    if not os.path.isfile(path):
        return []
    out = []
    with open(path, "r", errors="ignore") as fh:
        for line in fh:
            m = re.search(r"CORE_MOVED x=(\d+) y=(\d+)", line)
            if m:
                out.append((int(m.group(1)), int(m.group(2))))
    return out


def arc_span_swept(img, cx, cy, mid, density, step_deg=1.0):
    """The painted arc's longest lit run, swept over the ring's own radii.

    `scan_ring` samples ONE narrow band at the art ring's inner edge (tuned in M2-5 so the baked
    orb's own membrane does not count as the arc), and that band under-reads the staged look: the
    same COMPLETE frame reads 288 deg there and 360 deg at the ring's own radius (r = 52..54 px at
    64 dp, M4 pass 2026-09-27 - see `_m4_runs.py`, which is this same predicate). The C5 claims are
    about the ring being closed and about the ring coming back to the same arc, so they are measured
    where the ring actually is; `span` stays as the narrow-band reading for the record.
    """
    lo = int(round(mid - ARC_SWEEP_DP * density))
    hi = int(round(mid + ARC_SWEEP_DP * density))
    best = 0.0
    n = int(round(360.0 / step_deg))
    for rad in range(max(1, lo), hi + 1):
        hot = []
        for i in range(n):
            ang = i * step_deg
            rgb = sample(img, cx, cy, rad, math.radians(ang))
            if rgb is not None and is_arc(rgb):
                hot.append(ang)
        runs = []
        for ang in hot:
            if runs and ang - runs[-1][1] <= 2.0:
                runs[-1][1] = ang
            else:
                runs.append([ang, ang])
        if len(runs) > 1 and runs[0][0] <= 1.0 and runs[-1][1] >= 360.0 - step_deg:
            runs[0][0] = runs[-1][0] - 360.0          # the 0/360 wrap is one run on a circle
            runs.pop()
        for a, b in runs:
            best = max(best, min(360.0, b - a))
    return best


def do_frames(args):
    """Every frame of a motion pass: how much moved, how much of the ring is lit, how bright the
    centre is - then the same numbers grouped by the step that produced them, and the C5 verdicts.

    The pass (`tools\\core_motion.ps1`) drives DETECTED (the motion control: idle_ready LOOPS),
    progress 62%, PAUSED (held), RESUMING, progress 62% again, COMPLETING, COMPLETE, idle, hide.
    What each claim looks like in these three numbers:

        frozen     move stays at 0.0 for the whole hold, in one unbroken run
        resume     the span after RESUME equals the span before PAUSE (never 0)
        complete   the span reaches a closed circle and the centre (the merge) blooms inside it
    """
    files = sorted(glob.glob(os.path.join(args.path, "f_*.png")))
    if not files:
        files = sorted(glob.glob(os.path.join(args.path, "*.png")))
    if not files:
        print("no frames in " + args.path)
        return 1
    at = tuple(int(v) for v in args.at.split(","))
    # M7 re-run (2026-09-28): the geometry used to be fitted from `files[0]` alone, and a pass whose
    # recording starts before the Core is drawn (core_motion.ps1 `show`s it ~100 ms after the grab
    # begins) fed the fit a frame with a half-faded ring: "only 54/360 spokes hit a rim edge", exit 1,
    # 864 perfectly good frames refused. Walk the first frames until one actually carries a Core.
    first, g = None, None
    for probe in files[:30]:
        first = Image.open(probe).convert("RGB")
        g = geometry(first, at, args.size_dp, args.density, args.fixed)
        if g is not None:
            break
    if g is None:
        return 1
    cx, cy, r, r_in, r_edge, resid = g
    mid = art_ring_mid(args.size_dp, args.density) if args.fixed else None
    window = args.size_dp * args.density / 2.0
    window_mid = mid if mid is not None else r_in          # where the progress arc lives
    fps = args.fps
    segs = read_segments(args.segments)
    if args.segments and not segs:
        print("note  no segment table read from " + args.segments + " - frames only")

    print("%s frames (%d) at %d fps   centre %.1f,%.1f  window %.0f px"
          % (args.path, len(files), fps, cx, cy, window))
    if mid is not None:
        print("band  art ring mid %.1f px | arc band %.1f..%.1f | centre disc %.1f px"
              % (mid, mid - ART_ARC_IN_DP * args.density, mid - ART_ARC_OUT_DP * args.density,
                 0.30 * r_in))
    print("  t_ms   move  ring   span  swept  centre")
    rows = []
    prev = None
    for i, path in enumerate(files):
        img = Image.open(path).convert("RGB")
        move = 0.0 if prev is None else core_move(prev.load(), img.load(), cx, cy, window)
        rmove = 0.0 if prev is None else ring_move(prev.load(), img.load(), cx, cy, window_mid, args.density)
        lit, ext, runs = scan_ring(img, cx, cy, r_in, args.density, mid)
        swept = arc_span_swept(img, cx, cy, mid, args.density) if mid is not None else ext
        cen = centre_luma(img, cx, cy, 0.30 * r_in)
        t = int(round(i * 1000.0 / fps))
        rows.append((t, move, ext, cen, swept, rmove))
        if i % args.every == 0:
            print("%6d %6.2f %5.2f %6.0f %6.0f %7.1f" % (t, move, rmove, ext, swept, cen))
        prev = img

    # ---- the frozen runs: one continuous stretch in which nothing moved ------------------------
    # "Nothing" is both metrics: the disc-wide mean AND the ring annulus (see `moved`). A hold that
    # keeps the orb still while the ring's sheen keeps turning is not a freeze, and the disc mean
    # alone cannot tell the difference.
    print("")
    print("frozen runs (move and ring <= %.1f for >= %d frames):" % (FROZEN_MOVE, FROZEN_MIN_FRAMES))
    runs = []
    start = None
    for i, row in enumerate(rows + [(10 ** 9, 99.0, 0.0, 0.0, 0.0, 99.0)]):
        if moved(row) <= FROZEN_MOVE and start is None:
            start = i
        elif moved(row) > FROZEN_MOVE and start is not None:
            if i - start >= FROZEN_MIN_FRAMES:
                runs.append((start, i - 1))
            start = None
    for a, b in runs:
        print("  t=%d..%d ms  %d frames (%.0f ms)  span %.0f  centre %.1f"
              % (rows[a][0], rows[b][0], b - a + 1, (b - a) * 1000.0 / fps,
                 median_of([rows[k][2] for k in range(a, b + 1)]),
                 median_of([rows[k][3] for k in range(a, b + 1)])))
    if not runs:
        print("  none - something moved in every %.0f ms window" % (1000.0 / fps))

    # ---- the same numbers per step, and the C5 claims -----------------------------------------
    #
    # A step's window OPENS where the pass itself says the state changed, not where the rig guessed it
    # would. `segments.csv` stamps each step `push + 450 ms`, and the command poller is not that
    # punctual - the M4 pass of 2026-09-27 was out by 1.4-5 s, which put every step's window on the
    # PREVIOUS state's frames and made the C5 verdicts meaningless (they failed on frames that were
    # still the old look). A state change is the largest move inside its own step, so that frame is
    # found here instead; `effect_ms` stays as the fallback when nothing moved enough to be one.
    print("")
    print("segments (settled = the last 40% of the step, from the frame where the state actually changed):")
    summary = {}
    for si, (name, t0, t1, eff) in enumerate(segs):
        # A step's window ends where the NEXT command is pushed: a push cannot be served before it
        # exists, so whatever this step asked for must land inside it. The rig's own end_ms is only
        # `push + the step's hold`, which cut the change off completely on the DETECTED step (its
        # command took 1.76 s to be served, 60 ms after that window closed).
        # Measured on the pass of 2026-09-27: the device log's `CORE_CMD` instant lines up with the
        # frames' own change instant exactly (device 33.105 s = frame t 3605 ms -> the video's t=0 is
        # device-29.5 s), so a step's change *can* be found this way - but only if it is inside the
        # window. `progress 62`'s ring appeared 138 ms AFTER the progress62 window closed, which is
        # what made the freeze/resume comparison read 220 vs 24 deg. The rig now holds the two
        # ring-bearing steps 4.4 s so the change lands with ~2 s of settled frames behind it, and the
        # detector watches the ring annulus as well as the whole disc (see `moved`).
        end = segs[si + 1][1] if si + 1 < len(segs) else t1
        cand = [k for k, row in enumerate(rows) if t0 <= row[0] <= end]
        if not cand:
            print("  %-12s t=%6d..%-6d  no frames" % (name, t0, end))
            continue
        top = max(moved(rows[k]) for k in cand)
        changed = next((k for k in cand if moved(rows[k]) >= max(DETECT_MOVE, 0.5 * top)), None) \
            if top >= ALIVE_MOVE else None
        k0 = changed if changed is not None else next((k for k in cand if rows[k][0] >= eff), cand[0])
        detected = rows[k0][0] if changed is not None else None
        in_win = [k for k in cand if k >= k0]
        settled = in_win[int(len(in_win) * 0.6):] or in_win
        s = dict(
            move_max=max(moved(rows[k]) for k in in_win),
            span=median_of([rows[k][2] for k in settled]),
            span_max=max(rows[k][2] for k in in_win),
            swept=median_of([rows[k][4] for k in settled]),
            swept_max=max(rows[k][4] for k in in_win),
            cen_med=median_of([rows[k][3] for k in in_win]),
            cen_max=max(rows[k][3] for k in in_win),
            t_cen_peak=rows[max(in_win, key=lambda k: rows[k][3])][0],
            win=(rows[k0][0], end), n=len(in_win), detected=detected)
        summary[name] = s
        print("  %-12s t=%6d..%-6d %3d frames  move_max %5.2f  ring_max %5.2f  swept %3.0f (max %3.0f)"
              "  centre %5.1f (peak %5.1f @%dms)  %s"
              % (name, s["win"][0], end, s["n"], s["move_max"],
                 max(rows[k][5] for k in in_win), s["swept"], s["swept_max"],
                 s["cen_med"], s["cen_max"], s["t_cen_peak"],
                 ("changed at %d ms" % detected) if detected is not None
                 else "no change seen - window from the rig's %d ms" % eff))

    def span_of(name):
        return summary[name]["span"] if name in summary else None

    print("")
    print("the C5 motion contract (M4):")
    bad = 0
    ctl = summary.get("detected")
    if ctl is None:
        print("  control    no DETECTED segment in the table")
        bad += 1
    elif ctl["move_max"] >= ALIVE_MOVE:
        print("  control    DETECTED move_max %.2f >= %.1f: idle_ready loops, so a frozen PAUSED"
              % (ctl["move_max"], ALIVE_MOVE))
        print("             reading is the ring stopping, not a dead rig                PASS")
    else:
        print("  control    DETECTED move_max %.2f < %.1f: the LOOPING state did not move, so the"
              % (ctl["move_max"], ALIVE_MOVE))
        print("             whole pass is suspect (still screen, or no frames)          FAIL")
        bad += 1

    prg = summary.get("progress62") or summary.get("progress62b")
    paused = summary.get("paused")
    if paused is None:
        print("  freeze     no PAUSED segment")
        bad += 1
    else:
        hold = [r for r in runs if paused["win"][0] <= rows[r[0]][0] <= paused["win"][1]]
        longest = max(((b - a) * 1000.0 / fps for a, b in hold), default=0.0)
        n_frozen = sum(b - a + 1 for a, b in hold)
        ok_arc = prg is None or abs(paused["swept"] - prg["swept"]) <= SPAN_TOL
        print("  freeze     PAUSED: %d frame(s) in %d frozen run(s), longest %.0f ms, arc %.0f deg"
              % (n_frozen, len(hold), longest, paused["swept"]))
        if longest >= 1000.0 and ok_arc:
            print("             no motion for %.0f ms and the ring still reads %.0f%%"
                  % (longest, 100.0 * paused["swept"] / 360.0))
            print("             (%.0f%% before the pause)                                PASS"
                  % (100.0 * prg["swept"] / 360.0 if prg else 0.0))
        else:
            print("             %s                                FAIL"
                  % ("moved during the hold" if longest < 1000.0
                     else "the ring left %.0f%%" % (100.0 * (prg["swept"] if prg else 0.0) / 360.0)))
            bad += 1

    res = summary.get("resuming")
    if res is None or prg is None:
        print("  resume     need both a progress and a resuming segment")
        bad += 1
    elif abs(res["swept"] - prg["swept"]) <= SPAN_TOL:
        print("  resume     RESUMING arc %.0f deg = the arc before the pause %.0f deg (%.0f%%), not 0"
              % (res["swept"], prg["swept"], 100.0 * res["swept"] / 360.0))
        print("             the ring picked up where it stopped                        PASS")
    else:
        print("  resume     RESUMING arc %.0f vs %.0f before the pause: the ring did not pick up"
              % (res["swept"], prg["swept"]))
        print("             where it stopped                                         FAIL")
        bad += 1

    comp = summary.get("completing")
    if comp is None:
        print("  complete   no COMPLETING segment")
        bad += 1
    else:
        # The completion is ONE event that spans two steps: COMPLETING has no file of its own (the
        # host's arc pulls inwards), and the promotion to COMPLETE 400 ms later is what plays
        # core_complete - the ring closing, the merge, the fade. Both steps are searched for it.
        end = (summary.get("complete") or comp)["win"][1]
        win = (comp["win"][0], end)
        seen = [r for r in rows if win[0] <= r[0] <= win[1]]
        full = [r for r in seen if r[4] >= FULL_SPAN]
        t_full = full[0][0] if full else None
        peak = max(seen, key=lambda r: r[3]) if seen else (0, 0.0, 0.0, 0.0, 0.0)
        print("  complete   COMPLETING+COMPLETE arc max %.0f deg; a closed circle from %s, held %.0f ms;"
              % (comp["swept_max"], ("%d ms" % t_full) if t_full is not None else "never",
                 len(full) * 1000.0 / fps))
        print("             the centre (the merge) peaks at %d ms, %.1f vs %.1f at rest"
              % (peak[0], peak[3], prg["cen_med"] if prg else 0.0))
        if t_full is None:
            print("             the ring never closed                                        FAIL")
            bad += 1
        elif t_full <= peak[0]:
            print("             full circle at %d ms, merge at %d ms: ring first" % (t_full, peak[0]))
        else:
            print("             the merge blooms at %d ms, before the ring closes at %d ms"
                  % (peak[0], t_full))
        cmpl = summary.get("complete")
        if cmpl is not None:
            print("  settled    COMPLETE arc %.0f deg - the full circle is held after the merge"
                  % cmpl["swept"])
    print("")
    print("VERDICT %d of the C5 motion claims failed" % bad)
    return 1 if bad else 0


# --- M5 (the C3 touch contract): press / drag / edge snap, frame by frame -----------------------
# The Core is the owner's baked art, so it has no stroked rim for `fit_disc` to lock onto - but its
# energy ring is a closed cyan circle whose rest radius the analyzer already knows (`art_ring_mid`).
# A row through the Core's centre crosses that circle exactly twice, as does a column, so the two
# crossings give the centre, the radius AND the axis ratio without any fitting:
#
#   centre   (c0 + c1) / 2      a drag must move it, a tap must not
#   radius   (c1 - c0) / 2      the press compresses it (~10 %), the snap flattens it on one axis
#   aspect   rx / ry            C3's edge contact: flatten along the contact axis, bulge the other
#
# The passes are HORIZONTAL on purpose (tools\core_touch.ps1 holds y), so each frame is tracked from
# the previous frame's answer and the scan never has to search the whole screen.
TOUCH_CLUSTER_GAP = 6      # px: profile samples closer than this are one crossing, not two
TOUCH_PEAK_FLOOR = 0.6     # a crossing must reach this fraction of the strongest one on the axis
TOUCH_CHORD_DP = 7.0       # the second row, this far off the centre, that solves the vertical extent
MAGNET_DP = 12.0           # CoreMotion.EDGE_MAGNET_DP: a release inside this of an edge snaps flush
MAGNET_TOL_PX = 4.0        # the snap is placed by its own animator, so a few px of slack are fair


def axis_crossings(img, axis, fixed, lo, hi):
    """The ring's two crossings along one row (`axis` 'row') or column, as pixel centres.

    Measured on the art's own CYAN, not on the M2 ring band's brightness: the touch pass photographs
    the resting/detected/pressed looks, whose membrane is far dimmer than the download ring `is_arc`
    is tuned for (it needed luma >= 95, and only the brightest arc of the idle membrane reaches it -
    which is why the first run measured the row but never the column). Channel SPREAD separates them
    cleanly: the membrane and the ring are cyan (spread high), while the gel's gloss and the chevron
    are white-ish (spread near zero), so the two outermost spread peaks on an axis ARE the ring.
    """
    W, H = img.size
    px = img.load()
    n = W if axis == "row" else H
    lo = max(0, int(lo))
    hi = min(n - 1, int(hi))
    prof = []
    for i in range(lo, hi + 1):
        rgb = px[i, int(round(fixed))] if axis == "row" else px[int(round(fixed)), i]
        # CYAN only: the Core's ring and membrane are cyan (b >= g > r); the gel's gloss and chevron
        # are white-ish; whatever the app behind shows is arbitrary. Spread alone accepted magenta
        # and orange content in the backdrop as "a crossing", which is how the tracker lost the Core.
        cyan = (rgb[2] >= rgb[0] + 15) and (rgb[1] >= rgb[0])
        prof.append((max(rgb) - min(rgb)) if cyan else 0)
    if len(prof) < 5:
        return []
    top = max(prof)
    if top < 24:                 # 40 until 2026-09-28. The membrane's spread DROPS as the snap
        return []                # compresses it, so the old absolute floor erased exactly the frames
                                 # the claim was meant to measure: the dip hid its own signal. Safe to
                                 # lower now because the diameter pairing below is what rejects
                                 # non-rings; this floor only rejects what is not a crossing at all.
    # NO smoothing: the ring's crossings are only ~3 px wide, and a 5-tap average pulls them under
    # any floor high enough to exclude the art's own cyan chevron (which is WIDER and brighter than
    # the membrane - measured: chevron spread 176-182, crossings 116 and 153).
    floor = max(18.0, TOUCH_PEAK_FLOOR * top)    # 30.0 until 2026-09-28, same reason as the gate above
    peaks = []
    for k in range(1, len(prof) - 1):
        if prof[k] >= floor and prof[k] >= prof[k - 1] and prof[k] >= prof[k + 1]:
            if peaks and k - peaks[-1] <= TOUCH_CLUSTER_GAP:
                continue                              # one crossing, not two
            peaks.append(k)
    return [lo + k for k in peaks]


def axis_centre_radius(img, axis, fixed, expect, r_rest):
    """One axis through the Core: (centre, radius) from the pair of ring crossings it should have.

    The pair is the **ring's own promise**: two crossings about a diameter apart. The expected centre
    is only a tie-breaker between candidate pairs. It used to be the rule - "the two peaks nearest
    `expect +/- r_rest`" - and a fast drag defeats it: measured 2026-09-28, a frame mid-drag read
    peaks [252, 304, 311, 318, 358], where the ring is (252, 358) and 304/311/318 is the chevron's
    own (wider, brighter) cyan cluster, so nothing paired and the tracker froze 135 px behind the
    Core. Pairing by separation survives it, because the chevron never sits a diameter away from
    itself. The diameter gate is generous on purpose - the press shrinks it to 0.90 and the snap's
    squash stays inside 0.10 - and the cyan gate plus the three widening windows are what still
    reject the app behind. None when no pair exists; the caller carries the last value forward.

    The window opens at expect +/- 3 r and WIDENS twice: the edge snap glides the Core 216 px in
    300 ms (24 px a frame), so a single missed frame can put the ring outside a tight window, and
    carrying the old centre forward would then hide the very gesture being measured.
    """
    for mult in (3.0, 5.0, 8.0):
        peaks = axis_crossings(img, axis, fixed, expect - mult * r_rest, expect + mult * r_rest)
        if len(peaks) < 2:
            continue
        best = None
        for i in range(len(peaks)):
            for j in range(i + 1, len(peaks)):
                sep = peaks[j] - peaks[i]
                err = abs(sep - 2.0 * r_rest)
                if err > 0.30 * 2.0 * r_rest:
                    continue                         # not a diameter: not this ring
                mid = (peaks[i] + peaks[j]) / 2.0
                score = err + 0.5 * abs(mid - expect)   # the nearest of the ring-shaped pairs
                if best is None or score < best[0]:
                    best = (score, peaks[i], peaks[j], sep / 2.0)
        if best is None:
            continue
        radius = best[3]
        if radius < 0.55 * r_rest or radius > 1.45 * r_rest:
            continue                             # not our ring
        return (best[1] + best[2]) / 2.0, radius
    return None


def profile_radius(img, cx, cy, r_lo, r_hi, angles=72):
    """The Core's edge radius from the angle-averaged radial profile: the steepest falloff.

    Robust where the two-crossing tracker is not. The press scales the whole art (C3: to 0.90), but
    the ring's own crossings are thin, partly unlit (the membrane's bottom is dark) and sit next to a
    fixed-radius host track - three ways to lose them. Averaging 72 rays turns the disc's edge into the
    largest step in the profile, and using the *steepest falloff* rather than a brightness threshold
    keeps it independent of the look's own luminance (a press brightens the Core as it compresses).
    """
    prof = []
    for r in range(int(r_lo), int(r_hi) + 1):
        tot = n = 0
        for i in range(angles):
            t = 2.0 * math.pi * i / angles
            rgb = sample(img, cx, cy, r, t)
            if rgb is None:
                continue
            tot += 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
            n += 1
        prof.append(tot / n if n else 0.0)
    if len(prof) < 4:
        return None
    grad = [prof[k] - prof[k + 1] for k in range(len(prof) - 1)]
    k = max(range(len(grad)), key=lambda j: grad[j])
    if grad[k] <= 0:
        return None
    return float(int(r_lo) + k)


def do_touch(args):
    """The C3 pass: where the Core is, how big it is and how round it is, per frame + the verdicts."""
    files = sorted(glob.glob(os.path.join(args.path, "f_*.png")))
    if not files:
        print("no frames in " + args.path)
        return 1
    at = tuple(int(v) for v in args.at.split(","))
    half = args.size_dp * args.density / 2.0
    cx = at[0] + half                              # the window's centre, where the pass placed it
    cy = at[1] + half                              # ... and y is a RIG CONSTANT: every gesture in
    r_rest = art_ring_mid(args.size_dp, args.density)   # core_touch.ps1 is horizontal, so the pass
    fps = args.fps                                      # never moves the Core in y
    segs = read_segments(args.segments)
    by = {name: (t0, end) for name, t0, end, eff in segs}
    t_press = by.get("press", (0, 0))
    off = int(round(TOUCH_CHORD_DP * args.density))     # the second row, for the vertical extent
    moves = read_service_moves(args.path)               # the service's own release positions, if kept

    print("%s frames (%d) at %d fps   centre %.1f,%.1f  rest ring %.1f px  chord row +%d px"
          % (args.path, len(files), fps, cx, cy, r_rest, off))
    print("  service account: %d CORE_MOVED line(s)%s"
          % (len(moves), "" if moves else " - no touch.log next to the frames (older pass)"))
    print("  t_ms     cx      rx     ry   aspect   edge")
    rows = []
    cx0 = cx
    for i, path in enumerate(files):
        img = Image.open(path).convert("RGB")
        t = int(round(i * 1000.0 / fps))
        in_press = (t_press[0] <= t <= t_press[1])
        row = axis_centre_radius(img, "row", cy, cx, r_rest)
        rx = row[1] if row is not None else None
        if row is not None:
            # During the in-place press, avoid latching specular peak drift off resting centre
            if not in_press:
                cx = row[0]
            # ry from the chord at +off px: a row off the centre crosses a circle of radius rx only
            # at rx*sqrt(1-(off/ry)^2) - and an ellipse squashed by the snap crosses it at
            # rx*sqrt(1-(off/ry)^2) too, so one more row solves for ry without needing the art's
            # (genuinely dark) bottom edge, which the column scan had to fail on.
            ch = axis_centre_radius(img, "row", cy + off, cx, r_rest)
            c20 = ch[1] if ch is not None else None
            if c20 and rx and c20 < rx:
                ry = off / math.sqrt(max(1e-6, 1.0 - (c20 / rx) ** 2))
            else:
                ry = rx
        else:
            ry = None
        aspect = (rx / ry) if (rx and ry) else None
        eval_cx = cx0 if in_press else cx
        redge = profile_radius(img, eval_cx, cy, 0.55 * r_rest, 1.45 * r_rest)
        rows.append((t, eval_cx, cy, rx, ry, aspect, redge))
        if i % args.every == 0:
            print("%6d %7.1f %6.1f %6.1f %7.3f %6.1f"
                  % (t, eval_cx, rx if rx else 0, ry if ry else 0, aspect if aspect else 0,
                     redge if redge else 0))
    return touch_verdicts(rows, segs, r_rest, moves, half, args.density)


def touch_verdicts(rows, segs, r_rest, moves=None, half=None, density=DENSITY_DEFAULT):
    """Sheet C3's claims, from the frame trace: press, drag follow, edge magnet, clean settle.

    `moves` are the service's own CORE_MOVED positions (see read_service_moves) - the exact instrument
    for the two claims about WHERE the Core ended up. The pixel tracker still supplies the press and
    the settle (shape, not position) and is printed next to the service's numbers so a lost Core reads
    as a lost Core instead of as a Core that stopped following.
    """
    moves = moves or []
    def med(vals, fallback):
        v = [x for x in vals if x]
        return median_of(v) if v else fallback

    def win(t0, t1):
        return [r for r in rows if t0 <= r[0] <= t1]

    print("")
    print("the C3 touch contract (M5):")
    bad = 0
    pending = 0
    by = {name: (t0, end) for name, t0, end, eff in segs}
    if not by:
        print("  no segments read - verdicts need the pass's own table")
        print("")
        print("VERDICT the pass has no segment table")
        return 1

    rest = win(*by.get("rest", (0, 0)))
    r0 = med([r[3] for r in rest], r_rest)
    x0 = med([r[1] for r in rest], 0.0)
    e0 = med([r[6] for r in rest], r_rest)
    print("  rest       ring %.1f px, edge %.1f px, centre x %.1f - the pass's own baseline"
          % (r0, e0, x0))

    pr = win(*by["press"]) if "press" in by else []
    if not pr:
        print("  press      no PRESS segment in the table")
        bad += 1
    else:
        emin = min([r[6] for r in pr if r[6]] or [e0])
        emax = max([r[6] for r in pr if r[6]] or [e0])
        pct = 100.0 * (1.0 - emin / e0)
        tmin = [r for r in pr if r[6] == emin][0][0]
        peak = max([r[6] for r in pr if r[6] and r[0] >= tmin] or [e0])
        print("  press      the art's edge fell to %.1f px = %.1f %% of rest (sheet C3: ~10 %%) at"
              % (emin, pct))
        print("             %d ms, then swelled to %.1f px = %.1f %% (the rebound)"
              % (tmin, peak, 100.0 * peak / e0))
        if 4.0 <= pct <= 16.0:
            print("             the press compressed the gel and it came back             PASS")
        else:
            print("             the press did NOT compress the gel as sheet C3 says      FAIL")
            bad += 1

    df = win(*by["drag_far"]) if "drag_far" in by else []
    if not df and not moves:
        print("  drag       no DRAG segment in the table and no service account to read")
        bad += 1
    else:
        xs = [r[1] for r in df] or [0.0]
        travel = max(xs) - min(xs)
        print("  drag       the Core travelled %.0f px (x %.0f -> %.0f); the finger went 540 -> 300"
              % (travel, xs[0], xs[-1]))
        if moves:
            centre = moves[0][0] + (half or 0.0)
            print("             the service's own account: CORE_MOVED x=%d -> centre %.1f px"
                  % (moves[0][0], centre))
            if abs(centre - 300.0) <= 30.0:
                print("             it followed the finger                                  PASS")
            else:
                print("             it did not follow the finger                             FAIL")
                bad += 1
            short = abs(xs[-1] - centre)
            print("             the pixel tracker read x %.1f%s"
                  % (xs[-1], " (it agrees)" if short <= 30 else
                     " - %.0f px short: it lost the Core, and CORE_MOVED is the exact one" % short))
        else:
            if abs(xs[-1] - 300) <= 30 and travel >= 150:
                print("             it followed the finger                                  PASS")
            else:
                print("             it did not follow the finger                             FAIL")
                bad += 1
            print("             (no touch.log next to the frames: the pixel tracker decided alone)")

    de = win(*by["drag_edge"]) if "drag_edge" in by else []
    if not de:
        print("  magnet     no EDGE segment in the table")
        bad += 1
    else:
        tail = de[-max(1, int(len(de) * 0.3)):]
        xend = med([r[1] for r in tail], 0.0)
        magnet_px = MAGNET_DP * density
        if len(moves) >= 2:
            edge_x, ctrl_x = moves[1][0], moves[0][0]
            print("  magnet     the service's own account: CORE_MOVED x=%d (centre %.1f px) after the"
                  % (edge_x, edge_x + (half or 0.0)))
            print("             release inside the %.0f dp magnet, against its own control - the far"
                  % MAGNET_DP)
            print("             release before it, which read x=%d and did not magnetise" % ctrl_x)
            if edge_x <= magnet_px + MAGNET_TOL_PX and ctrl_x > magnet_px + MAGNET_TOL_PX:
                print("             it snapped flush to the edge, and only near it      PASS")
            else:
                print("             the magnet did not fire as sheet C3 says            FAIL")
                bad += 1
            print("             (the pixel tracker read x %.1f; it loses the Core through the" % xend)
            print("              snap's glide - its pairing needs the ring in a small window.)")
        else:
            pending += 1
            print("  magnet     the pixel tracker last held the Core at x %.1f" % xend)
            print("             NOT DECIDED HERE - touch.log carries %d CORE_MOVED line(s), and this"
                  % len(moves))
            print("             claim needs both releases. Re-run tools/core_touch.ps1, which writes")
            print("             touch.log next to segments.csv, to have the exact instrument decide: a")
            print("             release 20 px off the edge must read x=0, one 218 px off it x=218 (the")
            print("             far drag is the magnet's own control).")
        # --- the snap's contact-axis flattening (W1, 2026-09-28) ----------------------------------
        # Sheet C3: "compress on the contact axis, bulge the other". This pass's gesture is a
        # LEFT-edge snap, so the contact axis is the ROW - `rx`, the pair of ring crossings on the
        # centre row, the same number the press claim above is built on. The cell used to read only
        # the ASPECT rx/ry, and ry's chord solve is ill-conditioned on this art (the membrane is
        # genuinely dark along its bottom), so the claim dead-ended on a number it never needed.
        # The perpendicular bulge is SNAP_BULGE_FRACTION (0.5) x SNAP_SQUASH (0.10) = 5 % of the
        # radius = ~1.3 px of edge travel at 64 dp / 2.75x: below what a 1080-p screenrecord can
        # resolve, so it stays code-pinned while the squash does not.
        rx_de = [r[3] for r in de if r[3]]
        cover = (len(rx_de) / float(len(de))) if de else 0.0
        if len(rx_de) < 3 or cover < 0.5:
            pending += 1
            print("  squash     the tracker held rx on only %d of %d frames of the edge step"
                  % (len(rx_de), len(de)))
            print("             NOT DECIDED HERE - the contact axis needs the ring in view; re-run")
            print("             tools/core_touch.ps1 to have it re-read on a fresh pass.")
        elif not r0:
            print("  squash     no rest baseline to compare the contact axis against")
        else:
            rmin = min(rx_de)
            dip = 100.0 * (1.0 - rmin / r0)
            tmin = [r for r in de if r[3] == rmin][0][0]
            imin = [i for i, r in enumerate(de) if r[3] == rmin][0]
            seq = ["%.0f" % r[3] if r[3] else "-" for r in de[max(0, imin - 7):imin + 8]]
            print("  squash     the contact axis fell to %.1f px = a %.1f %% flattening of the rest"
                  % (rmin, dip))
            print("             ring (%.1f px) at %d ms, on %d of %d frames of the edge step"
                  % (r0, tmin, len(rx_de), len(de)))
            print("             rx through the snap: %s" % " ".join(seq))
            if 4.0 <= dip <= 16.0:
                print("             the snap compressed the gel on its contact axis        PASS")
            else:
                print("             the snap did NOT deform on its contact axis             FAIL")
                bad += 1
            print("             the perpendicular bulge (~1.3 px here) is below this capture's")
            print("             resolution: code-pinned in CoreMotion, not pixel-claimed.")

    st = win(*by["settle"]) if "settle" in by else []
    st_rx = [r[3] for r in st if r[3]]
    cover = (len(st_rx) / float(len(st))) if st else 0.0
    if not st:
        print("  settle     no SETTLE segment in the table")
        bad += 1
    elif len(st_rx) < 3 or cover < 0.5:
        # The old form fell back to the rest values when the tracker had no data, so "no trace left"
        # could be printed having measured ZERO frames - a PASS from a fallback. Seen on the M8 pass
        # of 2026-09-28: the tracker lost the Core during the drag, and the settle claim still read
        # PASS. Same guard as the squash claim: no data, no verdict.
        pending += 1
        print("  settle     the tracker held rx on only %d of %d frames after the drag"
              % (len(st_rx), len(st)))
        print("             NOT DECIDED HERE - re-run tools/core_touch.ps1 to have it re-read")
    else:
        rend = med(st_rx, r0)
        aend = med([r[5] for r in st if r[5]], 1.0)
        print("  settle     back to ring %.1f px (rest %.1f), aspect %.3f" % (rend, r0, aend))
        if abs(rend / r0 - 1.0) <= 0.05 and abs(aend - 1.0) <= 0.05:
            print("             no trace left after the snap                             PASS")
        else:
            print("             the gel did not come fully back to rest                   FAIL")
            bad += 1
    print("")
    if pending:
        print("VERDICT %d of the C3 touch claims failed, %d not decided here" % (bad, pending))
    else:
        print("VERDICT %d of the C3 touch claims failed" % bad)
    return 1 if bad else 0


# --- M6 (the C6 recovery gate): the rose -> teal retry, frame by frame --------------------------
# C6's recovery is 600 ms of composition (core_retry: 36 frames at 60 fps), so it needs the same
# instrument as C5: record, extract every frame, measure. Two numbers per frame:
#
#   edge    the art's own edge radius (the same `profile_radius` the press gate uses) - the retry
#           carries C3's press/rebound, so the art compresses and overshoots inside its 600 ms
#   family  the Core's chroma at that radius, classified rose / teal / neutral by `family()`
#
# What the sheet asks for: FAILED reads rose; RETRY starts rose, presses/rebounds and is teal by the
# end; and the resting look after it carries no trace of the rose.
def ring_family(img, cx, cy, rad, angles=24, min_sat=0.15):
    """The Core's colour family at one radius: the majority vote of the saturated samples there."""
    votes = {}
    hues = {}
    for i in range(angles):
        t = 2.0 * math.pi * i / angles
        rgb = sample(img, cx, cy, rad, t)
        if rgb is None:
            continue
        fam, deg, sat = family(rgb)
        if sat < min_sat or fam == "other":
            continue
        votes[fam] = votes.get(fam, 0) + 1
        hues.setdefault(fam, []).append(deg)
    if not votes:
        return "neutral", 0.0, 0
    fam = max(votes, key=lambda k: votes[k])
    return fam, median(hues[fam]), votes[fam]


def do_hues(args):
    """A colour pass frame by frame: the art's edge radius and the Core's family at each frame, then
    C6's claims (rose at the failure, rose -> teal through the retry, no trace afterwards)."""
    files = sorted(glob.glob(os.path.join(args.path, "f_*.png")))
    if not files:
        print("no frames in " + args.path)
        return 1
    at = tuple(int(v) for v in args.at.split(","))
    half = args.size_dp * args.density / 2.0
    cx = at[0] + half
    cy = at[1] + half
    r_rest = art_ring_mid(args.size_dp, args.density)
    fps = args.fps
    segs = read_segments(args.segments)

    print("%s frames (%d) at %d fps   centre %.1f,%.1f  rest ring %.1f px"
          % (args.path, len(files), fps, cx, cy, r_rest))
    print("  t_ms   edge  family   hue  n")
    rows = []
    for i, path in enumerate(files):
        img = Image.open(path).convert("RGB")
        redge = profile_radius(img, cx, cy, 0.55 * r_rest, 1.45 * r_rest)
        rad = 0.88 * (redge if redge else r_rest)
        fam, hue, n = ring_family(img, cx, cy, rad)
        t = int(round(i * 1000.0 / fps))
        rows.append((t, redge, fam, hue, n))
        if i % args.every == 0:
            print("%6d %6.1f  %-8s %5.0f %2d" % (t, redge if redge else 0, fam, hue, n))
    return hue_verdicts(rows, segs)


def hue_verdicts(rows, segs):
    """C6's claims, read off the colour trace ITSELF rather than off the rig's push windows.

    The pass's own table stamps each step `push + 450 ms`, but the command poller serves a push 0.4-2 s
    later (measured: the `state failed` of the first M6 pass landed 1.9 s after its push), so a window
    anchored on the push reads the PREVIOUS phase — which is exactly how the first run reported "the
    failure does not read rose" while the trace plainly showed 1.3 s of rose. So the phases are found
    where they are: the longest rose run IS the failure, what follows it is the retry, and what follows
    that is the resting look. The rig's table is only used to say whether the phases are in order.
    """
    if not rows:
        print("no frames")
        return 1

    def dominant(rs):
        votes = {}
        for r in rs:
            votes[r[2]] = votes.get(r[2], 0) + 1
        return max(votes, key=lambda k: votes[k]) if votes else "none"

    def runs_of(fam):
        """Every maximal run of frames whose majority family is `fam`, as [start_idx, end_idx]."""
        out = []
        cur = None
        for i, r in enumerate(rows):
            if r[2] == fam:
                cur = [i, i] if cur is None else [cur[0], i]
            elif cur is not None:
                out.append(cur)
                cur = None
        if cur is not None:
            out.append(cur)
        return out

    print("")
    print("the C6 recovery contract (M6):")
    bad = 0
    rose_runs = [r for r in runs_of("rose") if r[1] > r[0]]
    if not rose_runs:
        print("  failure    no run of rose frames at all in the pass")
        return 1
    a, b = max(rose_runs, key=lambda r: r[1] - r[0])
    dur = (rows[b][0] - rows[a][0] + 1000.0 / 30.0) / 1000.0
    print("  failure    %d frames of rose, %.2f s (t=%d..%d ms), hue %.0f"
          % (b - a + 1, dur, rows[a][0], rows[b][0], median([rows[k][3] for k in range(a, b + 1)])))
    if dur >= 0.30:
        print("             the failure reads rose, as sheet C6 says                PASS")
    else:
        print("             the rose does not hold long enough to be the failure    FAIL")
        bad += 1

    # The retry: what happens after the failure's rose run ends. Its own composition is 600 ms, so the
    # transition (edge dip + family change) has to complete inside a generous 1.2 s from the last rose
    # frame - generous because the frame that ENDS the rose run is already one poll-latency into it.
    t_rose_end = rows[b][0]
    after = [r for r in rows if t_rose_end < r[0] <= t_rose_end + 1200]
    if not after:
        print("  retry      no frames after the failure")
        bad += 1
    else:
        tail = [r for r in after if r[0] > t_rose_end + 600]
        ft = dominant(tail if tail else after)
        print("  retry      %.0f ms after the rose ends the Core is %s (hue %.0f)"
              % (median([r[0] for r in after]) - t_rose_end, ft,
                 median([r[3] for r in tail if tail] or [r[3] for r in after])))
        if ft == "teal":
            print("             the rose reads back to teal, as C6 asks                PASS")
        else:
            print("             the transition did not run rose -> teal                 FAIL")
            bad += 1
        edges = [r[1] for r in after if r[1]]
        if len(edges) >= 4:
            e0, emin, emax = edges[0], min(edges), max(edges)
            dip = 100.0 * (1.0 - emin / e0)
            over = 100.0 * (emax / e0 - 1.0)
            print("  press      the retry's own C3 press/rebound: edge %.1f -> %.1f px (%.1f %% down),"
                  % (e0, emin, dip))
            print("             then %.1f px (%.1f %% over) inside its 600 ms" % (emax, over))
            if dip >= 3.0:
                print("             the retry carries the press/rebound                   PASS")
            else:
                print("             no measurable press inside the retry                 FAIL")
                bad += 1

    # The resting look, once the recovery is over (the pass's last step commands IDLE).
    rest = [r for r in rows if r[0] > t_rose_end + 1500]
    if rest:
        rose = sum(1 for r in rest if r[2] == "rose")
        print("  rest       after the recovery: %d of %d frames still rose (hue %.0f)"
              % (rose, len(rest), median([r[3] for r in rest])))
        if rose == 0:
            print("             no trace of the rose left behind                       PASS")
        else:
            print("             the rose is still on the resting Core                  FAIL")
            bad += 1
    print("")
    print("VERDICT %d of the C6 recovery claims failed" % bad)
    return 1 if bad else 0


def do_diff(args):
    """Two stills, pixel for pixel: the freeze check that needs no video.

    The `shot` mode says what one frame LOOKS like; a freeze claim needs the stronger statement that
    nothing moved between two of them, which is what this prints - the mean |delta| over the Core's
    window, how many pixels changed by more than 20, and the worst one.
    """
    a = Image.open(args.a).convert("RGB")
    b = Image.open(args.b).convert("RGB")
    at = tuple(int(v) for v in args.at.split(","))
    g = geometry(a, at, args.size_dp, args.density, args.fixed)
    if g is None:
        return 1
    cx, cy, r, r_in, r_edge, resid = g
    window = args.size_dp * args.density / 2.0
    pa, pb = a.load(), b.load()
    tot = 0.0
    n = 0
    changed = 0
    worst = (0, None)
    for y in range(int(cy - window), int(cy + window) + 1):
        for x in range(int(cx - window), int(cx + window) + 1):
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy > window * window:
                continue
            aa, bb = pa[x, y], pb[x, y]
            d = max(abs(aa[i] - bb[i]) for i in range(3))
            tot += d
            n += 1
            if d > 20:
                changed += 1
            if d > worst[0]:
                worst = (d, (x, y))
    print("%s  vs  %s" % (os.path.basename(args.a), os.path.basename(args.b)))
    print("  window  %d px at %.1f,%.1f (Core %.0f dp @ %.2f)"
          % (int(2 * window), cx, cy, args.size_dp, args.density))
    print("  mean|delta| %.2f   pixels changed >20: %d (%.2f%% of the window)"
          % (tot / n if n else 0.0, changed, 100.0 * changed / n if n else 0.0))
    if worst[1]:
        print("  worst   %d at x,y %d,%d" % (worst[0], worst[1][0], worst[1][1]))
    if worst[0] <= args.tol:
        print("  frozen  PASS: nothing in the window changed by more than %d (tol %d)"
              % (worst[0], args.tol))
        return 0
    print("  frozen  FAIL: something changed by %d (tol %d)" % (worst[0], args.tol))
    return 1


def main():
    ap = argparse.ArgumentParser(description="Audit the Core's rim/mark/bars/perimeter per state.")
    sub = ap.add_subparsers(dest="mode", required=True)

    for mode in ("dir", "shot"):
        p = sub.add_parser(mode, help="a folder of shots" if mode == "dir" else "one shot")
        p.add_argument("path")
        if mode == "dir":
            p.add_argument("--glob", default="core_state_*.png")
        p.add_argument("--at", required=True, help="x,y the Core was placed at")
        p.add_argument("--size-dp", type=int, default=64, dest="size_dp")
        p.add_argument("--density", type=float, default=DENSITY_DEFAULT)
        p.add_argument("--want-ring", action="store_true", dest="want_ring",
                       help="check the lit perimeter against the progress in the file name (K-A4)")
        p.add_argument("--fixed", action="store_true",
                       help="trust --at as the window's top-left and skip the disc fit - required for "
                            "the V3.3 baked art, whose painted rim fit_disc cannot lock onto")
        p.add_argument("--save", default="", help="write one annotated crop (dir mode: first shot)")

    for mode in ("profile", "span"):
        p = sub.add_parser(mode, help="radial profile (ground truth for the bands)"
                           if mode == "profile" else "the lit arc's angular runs")
        p.add_argument("path")
        p.add_argument("--at", required=True, help="x,y the Core was placed at")
        p.add_argument("--size-dp", type=int, default=64, dest="size_dp")
        p.add_argument("--density", type=float, default=DENSITY_DEFAULT)
        p.add_argument("--fixed", action="store_true",
                       help="trust --at as the window's top-left and skip the disc fit (V3.3 baked art)")
        if mode == "profile":
            p.add_argument("--rays", default="0,90,180,270", help="angles in degrees, screen convention")
            p.add_argument("--from", type=int, default=56, dest="r_from")
            p.add_argument("--to", type=int, default=84, dest="r_to")

    p = sub.add_parser("frames", help="a folder of motion frames + its segments.csv (M4, C5 in time)")
    p.add_argument("path", help="the frames folder core_motion.ps1 wrote (f_0001.png ...)")
    p.add_argument("--at", required=True, help="x,y the Core was placed at (the window's top-left)")
    p.add_argument("--size-dp", type=int, default=64, dest="size_dp")
    p.add_argument("--density", type=float, default=DENSITY_DEFAULT)
    p.add_argument("--fps", type=int, default=30, help="must match -Fps of core_motion.ps1")
    p.add_argument("--segments", default="", help="segments.csv; default: the frames' parent folder")
    p.add_argument("--every", type=int, default=1, help="print every Nth frame's row (all are measured)")
    p.add_argument("--fixed", action="store_true",
                   help="trust --at as the window's top-left - required for the V3.3 baked art")

    p = sub.add_parser("touch", help="a folder of touch frames + its segments.csv (M5, C3 in time)")
    p.add_argument("path", help="the frames folder core_touch.ps1 wrote (f_0001.png ...)")
    p.add_argument("--at", required=True, help="x,y the Core was placed at (the window's top-left)")
    p.add_argument("--size-dp", type=int, default=64, dest="size_dp")
    p.add_argument("--density", type=float, default=DENSITY_DEFAULT)
    p.add_argument("--fps", type=int, default=30, help="must match -Fps of core_touch.ps1")
    p.add_argument("--segments", default="", help="segments.csv; default: the frames' parent folder")
    p.add_argument("--every", type=int, default=1, help="print every Nth frame's row")
    p.add_argument("--fixed", action="store_true", help="trust --at as the window's top-left")

    p = sub.add_parser("hues", help="a folder of colour frames + segments.csv (M6, C6 recovery)")
    p.add_argument("path", help="the frames folder core_c6.ps1 wrote (f_0001.png ...)")
    p.add_argument("--at", required=True, help="x,y the Core was placed at (the window's top-left)")
    p.add_argument("--size-dp", type=int, default=64, dest="size_dp")
    p.add_argument("--density", type=float, default=DENSITY_DEFAULT)
    p.add_argument("--fps", type=int, default=30, help="must match -Fps of core_c6.ps1")
    p.add_argument("--segments", default="", help="segments.csv; default: the frames' parent folder")
    p.add_argument("--every", type=int, default=1, help="print every Nth frame's row")
    p.add_argument("--fixed", action="store_true", help="trust --at as the window's top-left")

    p = sub.add_parser("diff", help="two stills, pixel for pixel: did anything move? (M4 freeze)")
    p.add_argument("a")
    p.add_argument("b")
    p.add_argument("--at", required=True, help="x,y the Core was placed at (the window's top-left)")
    p.add_argument("--size-dp", type=int, default=64, dest="size_dp")
    p.add_argument("--density", type=float, default=DENSITY_DEFAULT)
    p.add_argument("--tol", type=int, default=6, help="max channel delta that still counts as frozen")
    p.add_argument("--fixed", action="store_true",
                   help="trust --at as the window's top-left - required for the V3.3 baked art")

    args = ap.parse_args()
    at = tuple(int(v) for v in args.at.split(","))

    if args.mode == "frames":
        if not args.segments:
            args.segments = os.path.join(os.path.dirname(os.path.abspath(args.path)), "segments.csv")
        return do_frames(args)
    if args.mode == "touch":
        if not args.segments:
            args.segments = os.path.join(os.path.dirname(os.path.abspath(args.path)), "segments.csv")
        return do_touch(args)
    if args.mode == "hues":
        if not args.segments:
            args.segments = os.path.join(os.path.dirname(os.path.abspath(args.path)), "segments.csv")
        return do_hues(args)
    if args.mode == "diff":
        return do_diff(args)

    if args.mode == "profile":
        return do_profile(args)
    if args.mode == "span":
        return do_span(args)
    if args.mode == "shot":
        paths = [args.path]
    else:
        if not os.path.isdir(args.path):
            print("not a folder: " + args.path)
            return 1
        paths = sorted(glob.glob(os.path.join(args.path, args.glob)))
        if not paths:
            print("no files match %s in %s" % (args.glob, args.path))
            return 1

    bad = 0
    for i, path in enumerate(paths):
        if not os.path.isfile(path):
            print("not found: " + path)
            bad += 1
            continue
        save = args.save if (args.save and i == 0) else ""
        v = audit_one(path, at, args.size_dp, args.density, args.want_ring, save, args.fixed)
        if "MISMATCH" in v:
            bad += 1

    print("")
    print("VERDICT  %d shot(s), %d mismatch(es)" % (len(paths), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
