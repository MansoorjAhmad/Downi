#!/usr/bin/env python3
"""core_lottie_build.py -- author the Fetcher 2.0 "Core" state animations as Lottie JSON.

No After Effects, no hand-drawn in-betweens: every state is a *transform of the owner's
approved sheet art* (res/drawable-nodpi/core_orb*.png, lifted from sheet C1 by
tools/core_sheet_extract.py). The material is his; the motion is generated here, so the
ten files in V3.3_PLAN.md section 3 can exist without waiting on an AE export.

Usage
-----
    python tools/core_lottie_build.py            # build every state into app/src/main/assets/core
    python tools/core_lottie_build.py --check    # build, validate the JSON, write QA previews

Requirements: pillow, python-lottie.  Install the latter without touching the repo:

    python -m pip install --target <somewhere>/_lotlib lottie
    set PYTHONPATH=<somewhere>/_lotlib

Why generated JSON is safe to ship
----------------------------------
Lottie JSON fails *silently* at runtime (a bad keyframe just renders nothing), so this file
never trusts itself: --check re-opens each file and asserts the invariants that matter
(version/frame rate/canvas, every layer's refId resolves, no absolute paths, keyframe times
monotonic and inside the composition, image assets carry real w/h, the animation actually
contains keyframes). A JVM test (CoreLottieSpecTest) re-asserts the same contract on every
build so the assets cannot rot.
"""

import argparse
import json
import math
import os
import re
import shutil
import sys

from PIL import Image as PILImage
from PIL import ImageDraw as PILImageDraw

try:
    from lottie import objects
    from lottie.exporters import export_lottie
    from lottie.objects import easing, shapes
except ImportError as exc:  # pragma: no cover - dependency guidance only
    raise SystemExit(
        "python-lottie is not importable (%s).\n"
        "  python -m pip install --target <dir> lottie  and  set PYTHONPATH=<dir>" % exc
    )

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)                       # mobile-app/
ART_DIR = os.path.join(APP, "android", "app", "src", "main", "res", "drawable-nodpi")
OUT_DIR = os.path.join(APP, "android", "app", "src", "main", "assets", "core")
IMG_DIR = os.path.join(OUT_DIR, "images")
QA_DIR = os.path.join(APP, "test_out", "lottie_qa")      # scratch previews, not shipped

CANVAS = 512          # the art tile is 512x512; the disc inside it is 0.8606 of the side
FPS = 60
ART_TILE_RATIO = 0.8606
ART_TILE_RATIO_PAUSED = 0.7799

# ---------------------------------------------------------------------------
# easing: the sheet's motion is never linear -- it enters fast and settles.
# Bezier(out_point, in_point), both in the 0..1 unit square (see OffsetKeyframe docs).
# ---------------------------------------------------------------------------
EASE_OUT = easing.Bezier(objects.NVector(0.16, 0.84), objects.NVector(0.30, 1.0))
EASE_IN_OUT = easing.Bezier(objects.NVector(0.42, 0.0), objects.NVector(0.58, 1.0))
EASE_IN = easing.Bezier(objects.NVector(0.55, 0.06), objects.NVector(0.68, 0.19))
LINEAR = easing.Linear()

def add_to(container, item):
    """python-lottie mixes helpers and plain lists (dd_shape / dd vs ppend)."""
    for meth in ("add_shape", "add", "append"):
        if hasattr(container, meth):
            getattr(container, meth)(item)
            return item
    raise SystemExit("cannot add to %r" % type(container))


def art(name):
    """A Lottie image asset pointing at the shared images/ folder (never an absolute path)."""
    src = os.path.join(ART_DIR, name)
    if not os.path.isfile(src):
        raise SystemExit("missing art: %s (run tools/core_sheet_extract.py first)" % src)
    asset = objects.Image.linked(src)
    asset.id = name
    asset.path = "images/"          # Image.linked() would bake the absolute dir in here
    asset.file_name = name
    return asset


def image_layer(asset, name):
    """An image layer showing the whole tile, 1:1, centred."""
    lay = objects.ImageLayer(asset.id)
    lay.name = name
    tr = objects.Transform()
    tr.position.value = objects.NVector(CANVAS / 2, CANVAS / 2)
    tr.anchor_point.value = objects.NVector(CANVAS / 2, CANVAS / 2)
    tr.scale.value = objects.NVector(100, 100)
    lay.transform = tr
    return lay


def key(prop, frames, interp=EASE_IN_OUT):
    """(frame, value) pairs -> keyframes. The easing passed describes the segment *leaving*
    that keyframe, so the last one gets none."""
    for i, (t, v) in enumerate(frames):
        if i == len(frames) - 1:
            prop.add_keyframe(t, v)
        else:
            prop.add_keyframe(t, v, interp)
    return prop


def add_scaled_keyframes(prop, frames, interp=EASE_IN_OUT):
    """Same as key() but values are (sx, sy) pairs in percent -> NVector(x, y)."""
    return key(prop, [(t, objects.NVector(x, y)) for t, x, y in frames], interp)


def _trim(sweep, interp):
    """The trim that turns the ring into a progress meter. `sweep` is [(frame, percent 0..100)]
    -- bodymovin trims are percentages and their offset is degrees."""
    trim = shapes.Trim()
    for attr in ("start", "end", "offset"):
        if not hasattr(trim, attr):
            raise SystemExit("python-lottie Trim has no .%s -- cannot animate the ring" % attr)
    trim.start.value = 0.0
    trim.offset.value = -90.0              # 12 o'clock, like the sheet
    key(trim.end, list(sweep), interp)
    return trim


def arc_layer(radius, colour, width, end_sweep, name="ring", start_sweep=None, interp=EASE_IN_OUT):
    """The energy perimeter (sheet C5) as real vector geometry: an ellipse stroke trimmed to
    the progress. `start_sweep` is only used for the comet head -- a short arc travelling with
    the ring instead of a separate sprite."""
    group = shapes.Group()
    add_to(group, shapes.Ellipse(objects.NVector(CANVAS / 2, CANVAS / 2),
                                   objects.NVector(radius * 2, radius * 2)))
    # `Color` takes components positionally: `Color(r, g, b[, a])`. Passing the tuple as ONE
    # argument yields `[[r,g,b], 0, 0, 1]`, which lottie-android refuses with a JsonDataException
    # at `c.k[0]` -- the whole composition is rejected on the device (found 2026-09-27, M2 gate).
    stroke = shapes.Stroke(objects.Color(*colour), width)
    stroke.line_cap = shapes.LineCap.Round
    add_to(group, stroke)
    if end_sweep:
        if len(end_sweep) == 1 and float(end_sweep[0][1]) >= 99.999:
            pass                     # a full circle: a trim would only add a 1-key non-animation
        elif len(end_sweep) == 1:
            trim = shapes.Trim()
            trim.offset.value = -90.0
            trim.end.value = float(end_sweep[0][1])
            add_to(group, trim)
        else:
            trim = _trim(end_sweep, interp)
            if start_sweep:
                key(trim.start, list(start_sweep), interp)
            add_to(group, trim)

    lay = objects.ShapeLayer()
    lay.name = name
    add_to(lay.shapes, group)
    tr = objects.Transform()
    tr.position.value = objects.NVector(0, 0)
    tr.anchor_point.value = objects.NVector(0, 0)
    lay.transform = tr
    return lay


def fill_layer(name, diameter, colour, opacity_frames):
    """A soft disc of light -- used for the completing merge (C5: energy pulls inward)."""
    group = shapes.Group()
    add_to(group, shapes.Ellipse(objects.NVector(CANVAS / 2, CANVAS / 2),
                                   objects.NVector(diameter, diameter)))
    add_to(group, shapes.Fill(objects.Color(*colour)))
    lay = objects.ShapeLayer()
    lay.name = name
    add_to(lay.shapes, group)
    tr = objects.Transform()
    tr.position.value = objects.NVector(0, 0)
    tr.anchor_point.value = objects.NVector(0, 0)
    key(tr.opacity, opacity_frames, EASE_OUT)
    lay.transform = tr
    return lay


def new_anim(name, frames):
    an = objects.Animation(frames, FPS)
    an.width, an.height = CANVAS, CANVAS
    an.name = name
    return an


def place(an, layer):
    """Layers are written overlays-first: Lottie draws index 1 on top, and python-lottie
    numbers them in insertion order, so the body of the orb goes in last.

    Every layer also carries the composition's own ip/op. python-lottie only copies those in
    when a layer goes through its own add-layer path, and this generator appends straight to
    `an.layers` -- so they used to be missing, and lottie-android fell back to the
    composition's *end frame* for each layer's out point. That lands the layer's in/out
    keyframe exactly on progress 1.0, which is the frame a non-looping animation holds:
    BaseLayer.setVisible(false) then hides every layer the moment the state settles, which is
    when all of our gate shots are taken (measured on device 2026-09-27, `tree=[...visible=false]`
    with the state held empty while the mid-flight frames were fine).
    """
    if hasattr(layer, "index"):
        layer.index = len(an.layers) + 1
    if hasattr(layer, "in_point"):
        layer.in_point = 0
    if hasattr(layer, "out_point"):
        layer.out_point = an.out_point
    an.layers.append(layer)
    return layer


def add_asset(an, asset):
    """An asset for one of the image layers. `assets` is a plain list in python-lottie."""
    if hasattr(an.assets, "add"):
        an.assets.add(asset)
    else:
        an.assets.append(asset)
    return asset


CYAN = (0x22 / 255.0, 0xD3 / 255.0, 0xEE / 255.0)
PALE = (0xBD / 255.0, 0xFB / 255.0, 0xFF / 255.0)
RING_R = 230.0          # just outside the disc (the art's disc is 0.8606 * 512 = 440.6 px)
TRACK_W = 4.0

# ---------------------------------------------------------------------------
# the ten states (V3.3_PLAN.md section 3) as declarations, not code: art + keyframes,
# so retuning a state is a number change and the whole set stays readable side by side.
#
#   art / art2   the sheet art; art2 = the layer that fades IN over art (cross-fade states)
#   scale        [(frame, percent)]  uniform, on every art layer
#   opacity      [(frame, percent)]  the single art layer (no art2)
#   fade_in/out  [(frame, percent)]  art2 / art in a cross-fade
#   rotate       [(frame, degrees)]  the gel settling into place (C2)
#   shift        [(frame, px)]       a nudge along x, for the unsupported read
#   track        [(frame, percent)]  the static full ring's opacity
#   ring         sweep=[(frame, percent)], optional comet/opacity/width/interp
#   merge        the C5 completing flash: diameter + opacity keyframes
# ---------------------------------------------------------------------------
STATES = {
    "core_dormant": dict(          # C2's lowest energy: asleep, no ring at all
        frames=90, art="core_orb_paused.png", note="C2 dormant",
        scale=[(0, 97), (45, 100), (90, 97)],
        opacity=[(0, 62), (45, 78), (90, 62)],
    ),
    "core_wake": dict(             # C2: dormant -> aware -> ready in ~600 ms
        frames=36, art="core_orb.png", note="C2 wake, 600 ms, plays once",
        scale=[(0, 88), (26, 101.5), (36, 100)],
        opacity=[(0, 0), (18, 100), (36, 100)],
        rotate=[(0, -6.0), (26, 1.0), (36, 0.0)],
        track=[(0, 0), (36, 38)],
    ),
    "core_idle_ready": dict(       # the loop while a video is detected and waiting for a tap
        frames=120, art="core_orb.png", note="C1/C2 ready, loops",
        scale=[(0, 100), (30, 103), (60, 100), (90, 97.5), (120, 100)],
        opacity=[(0, 88), (30, 96), (60, 88), (120, 88)],
        track=[(0, 22), (60, 30), (120, 22)],
    ),
    "core_press": dict(            # C3: ~10% compression then overshoot, plays on tap
        frames=24, art="core_orb.png", note="C3 press + rebound, plays once",
        scale=[(0, 100), (6, 90), (14, 103.5), (24, 100)],
    ),
    "core_progress": dict(         # C5: the ring IS the progress -- the view scrubs this file
        frames=120, art="core_orb.png", note="C5 ring, driven by setProgress(pct)",
        scale=[(0, 100), (60, 101.5), (120, 100)],
        ring=dict(sweep=[(0, 0.0), (120, 100.0)], width=5.0,
                  comet=[(0, 0.0), (120, 96.5)], interp="linear"),
    ),
    "core_pause": dict(            # C5 pause: the ring freezes, the energy recedes
        frames=18, art="core_orb_paused.png", note="C5 pause, plays once",
        scale=[(0, 100), (10, 98.5), (18, 99.5)],
        track=[(0, 55), (18, 34)],
    ),
    "core_complete": dict(         # C5: ring reaches 100%, energy pulls in, bright merge
        frames=45, art="core_orb.png", note="C5 complete, plays once",
        scale=[(0, 100), (18, 108), (30, 96), (45, 100)],
        ring=dict(sweep=[(0, 86.0), (18, 100.0)], width=5.0,
                  opacity=[(0, 100), (20, 100), (34, 0)]),
        merge=dict(diameter=300, opacity=[(0, 0), (14, 55), (30, 0)]),
    ),
    "core_failure": dict(          # C6: teal -> muted rose. Quiet, not broken.
        frames=30, art="core_orb.png", art2="core_orb_rose.png", note="C6 failure, plays once",
        fade_out=[(0, 100), (14, 0), (30, 0)],
        fade_in=[(0, 0), (14, 100), (30, 100)],
        # C6.1's two cells: the energy SHIFTS to muted rose by frame 14, and then the stable failure
        # exhales - a small settle, no pulse, nothing that reads as an alarm. (Before 2026-09-28 the
        # row held one slow contraction to 98.5 and no exhale at all.)
        scale=[(0, 100), (14, 98.9), (21, 97.5), (30, 98.5)],
    ),
    "core_retry": dict(            # C6: rose -> teal with the C3 press/rebound
        frames=36, art="core_orb_rose.png", art2="core_orb.png", note="C6 retry, plays once",
        fade_out=[(0, 100), (12, 0), (36, 0)],
        fade_in=[(0, 0), (12, 100), (36, 100)],
        scale=[(0, 100), (4, 92), (18, 104), (36, 100)],
    ),
    "core_unsupported": dict(      # C6: muted blue/grey, a small acknowledgement, no red
        frames=30, art="core_orb.png", art2="core_orb_neutral.png",
        note="C6 unsupported, plays once",
        fade_out=[(0, 100), (12, 0), (30, 0)],
        fade_in=[(0, 0), (12, 100), (30, 100)],
        shift=[(0, 0), (8, 3), (30, 0)],
    ),
}


def build(name, spec):
    """A spec -> Animation. Layer order is overlays-first (see place())."""
    an = new_anim(name, spec["frames"])
    asset = art(spec["art"])
    add_asset(an, asset)
    body = image_layer(asset, "core")
    body_layers = [body]

    if "art2" in spec:                       # cross-fade: the incoming art sits on top
        asset2 = art(spec["art2"])
        add_asset(an, asset2)
        incoming = image_layer(asset2, "core_in")
        key(incoming.transform.opacity, spec["fade_in"], EASE_OUT)
        key(body.transform.opacity, spec["fade_out"], EASE_OUT)
        body_layers.insert(0, incoming)
    elif "opacity" in spec:
        key(body.transform.opacity, spec["opacity"])

    if "rotate" in spec:
        key(body.transform.rotation, spec["rotate"], EASE_OUT)
    if "scale" in spec:
        for lay in body_layers:
            add_scaled_keyframes(lay.transform.scale, [(t, v, v) for t, v in spec["scale"]])
    if "shift" in spec:
        for lay in body_layers:
            key(lay.transform.position,
                [(t, objects.NVector(CANVAS / 2 + dx, CANVAS / 2)) for t, dx in spec["shift"]],
                EASE_OUT)

    if "merge" in spec:
        place(an, fill_layer("merge", spec["merge"]["diameter"], PALE, spec["merge"]["opacity"]))
    if "ring" in spec:
        r = spec["ring"]
        interp = LINEAR if r.get("interp") == "linear" else EASE_IN_OUT
        if r.get("comet"):
            place(an, arc_layer(RING_R, PALE, r.get("width", 5.0) + 1.0, r["sweep"], "comet",
                                start_sweep=r["comet"], interp=interp))
        ring = arc_layer(RING_R, CYAN, r.get("width", 5.0), r["sweep"], "ring", interp=interp)
        if "opacity" in r:
            key(ring.transform.opacity, r["opacity"], EASE_OUT)
        place(an, ring)
    if "track" in spec:
        track = arc_layer(RING_R, CYAN, TRACK_W, [(0, 100.0)])
        key(track.transform.opacity, spec["track"])
        place(an, track)
    for lay in body_layers:
        place(an, lay)
    return an


# ---------------------------------------------------------------------------
# The two looks the sheets never exported as separate art: C6's muted rose and the muted
# blue/grey. Baked here with the SAME maths CoreTint.java uses (hue rotation + saturation
# loss, Rec.709, no offsets -- the obsidian body must stay obsidian), so the bitmap a Lottie
# cross-fade lands on is what the Java static path would have drawn.
# ---------------------------------------------------------------------------
ROSE_HUE_DEG, ROSE_SATURATION = 163.0, 0.82        # must match CoreTint.ROSE_*
NEUTRAL_HUE_DEG, NEUTRAL_SATURATION = 18.0, 0.22   # must match CoreTint.NEUTRAL_*
TINTS = {
    "core_orb_rose.png": (ROSE_HUE_DEG, ROSE_SATURATION),
    "core_orb_neutral.png": (NEUTRAL_HUE_DEG, NEUTRAL_SATURATION),
}
CORE_TINT_JAVA = os.path.join(APP, "android", "app", "src", "main", "java", "com", "omnidownloader",
                              "app", "downicore", "CoreTint.java")


def check_core_tint_agreement():
    """Baked art is only correct if it was baked with CoreTint's own numbers, so read them from
    the Java source rather than trusting the copies above. CoreLottieSpecTest holds the other half
    of this contract (it pins the art and re-states the constants on the JVM side)."""
    try:
        src = open(CORE_TINT_JAVA, encoding="utf-8").read()
    except OSError as exc:
        return ["cannot read CoreTint.java (%s)" % exc]
    problems = []
    for name, ours in (("ROSE_HUE_DEG", ROSE_HUE_DEG), ("ROSE_SATURATION", ROSE_SATURATION),
                       ("NEUTRAL_HUE_DEG", NEUTRAL_HUE_DEG),
                       ("NEUTRAL_SATURATION", NEUTRAL_SATURATION)):
        m = re.search(r"\b%s\s*=\s*([0-9.]+)f" % name, src)
        if not m:
            problems.append("CoreTint.java declares no %s" % name)
        elif abs(float(m.group(1)) - ours) > 1e-6:
            problems.append("CoreTint.%s = %s, but the generator bakes with %s"
                            % (name, m.group(1), ours))
    return problems


def tint_matrix(degrees, saturation):
    rad = math.radians(degrees)
    c, s = math.cos(rad), math.sin(rad)
    lr, lg, lb = 0.213, 0.715, 0.072
    r = (lr + c * (1 - lr) - s * lr, lg - c * lg - s * lg, lb - c * lb + s * (1 - lb))
    g = (lr - c * lr + s * 0.143, lg + c * (1 - lg) + s * 0.140, lb - c * lb - s * 0.283)
    b = (lr - c * lr - s * (1 - lr), lg - c * lg + s * lg, lb + c * (1 - lb) + s * lb)
    sr, sg, sb = (1 - saturation) * lr, (1 - saturation) * lg, (1 - saturation) * lb
    return (
        tuple(sr + saturation * v for v in r),
        tuple(sg + saturation * v for v in g),
        tuple(sb + saturation * v for v in b),
    )


def write_tinted_art(verbose=True):
    """core_orb.png -> the rose and neutral variants, in the repo's drawable-nodpi."""
    src = os.path.join(ART_DIR, "core_orb.png")
    made = []
    for name, (deg, sat) in TINTS.items():
        dst = os.path.join(ART_DIR, name)
        im = PILImage.open(src).convert("RGBA")
        m = tint_matrix(deg, sat)
        px = im.load()
        for y in range(im.size[1]):
            for x in range(im.size[0]):
                r, g, b, a = px[x, y]
                px[x, y] = (
                    min(255, max(0, int(round(m[0][0] * r + m[0][1] * g + m[0][2] * b)))),
                    min(255, max(0, int(round(m[1][0] * r + m[1][1] * g + m[1][2] * b)))),
                    min(255, max(0, int(round(m[2][0] * r + m[2][1] * g + m[2][2] * b)))),
                    a,
                )
        im.save(dst)
        made.append((name, os.path.getsize(dst)))
        if verbose:
            print("  tinted %-24s %6d bytes  (hue %+5.1f deg, sat %.2f)" % (name, os.path.getsize(dst), deg, sat))
    return made


def write_images(verbose=True):
    """Every PNG the JSONs reference, into the shared images/ folder next to them."""
    os.makedirs(IMG_DIR, exist_ok=True)
    names = ["core_orb.png", "core_orb_paused.png"] + list(TINTS)
    for name in names:
        src = os.path.join(ART_DIR, name)
        if not os.path.isfile(src):
            raise SystemExit("missing art: %s" % src)
        shutil.copyfile(src, os.path.join(IMG_DIR, name))
    if verbose:
        total = sum(os.path.getsize(os.path.join(IMG_DIR, n)) for n in names)
        print("  images/  %d files, %d bytes" % (len(names), total))


# ---------------------------------------------------------------------------
# export + the self-check. Lottie JSON fails *silently* at runtime, so nothing here is
# trusted: --check re-opens every file and asserts the contract Android relies on.
# ---------------------------------------------------------------------------
def animated_props(node, path=""):
    """Every animatable property dict in a layer tree (lod: "k" plus "a")."""
    if isinstance(node, dict):
        if "k" in node and ("a" in node or isinstance(node["k"], list)):
            yield path, node
        for k, v in node.items():
            for hit in animated_props(v, path + "/" + str(k)):
                yield hit
    elif isinstance(node, list):
        for i, v in enumerate(node):
            for hit in animated_props(v, path + "[%d]" % i):
                yield hit


def validate(path, spec):
    """Returns (problems, animated_property_count) for one exported state."""
    raw = open(path, encoding="utf-8").read()
    d = json.loads(raw)
    bad = []

    def want(cond, msg):
        if not cond:
            bad.append(msg)

    want(str(d.get("v", "")).startswith("5."), "version not 5.x: %r" % d.get("v"))
    want(d.get("w") == CANVAS and d.get("h") == CANVAS, "canvas %sx%s" % (d.get("w"), d.get("h")))
    want(d.get("fr") == FPS, "fr %s != %d" % (d.get("fr"), FPS))
    want(d.get("ip") == 0, "ip %s != 0" % d.get("ip"))
    want(d.get("op") == spec["frames"], "op %s != %d" % (d.get("op"), spec["frames"]))
    for leak in ("C:\\", "C:/", "/Users/", "\\\\"):
        want(leak not in raw, "absolute path leaked (%r)" % leak)

    ids = set()
    for asm in d.get("assets", []):
        ids.add(asm.get("id"))
        if asm.get("w") is not None:
            want(asm.get("w") == CANVAS and asm.get("h") == CANVAS,
                 "asset %r is %sx%s" % (asm.get("id"), asm.get("w"), asm.get("h")))
        if asm.get("p"):
            want(":" not in str(asm["p"]), "asset %r path looks absolute" % asm.get("id"))

    want(bool(d.get("layers")), "no layers")
    inds = [lay.get("ind") for lay in d["layers"]]
    want(len(set(inds)) == len(inds), "duplicate layer indices %s" % inds)
    for lay in d["layers"]:
        want(isinstance(lay.get("ks"), dict), "layer %r has no transform" % lay.get("name"))
        if lay.get("ty") == 2:
            want(lay.get("refId") in ids,
                 "layer %r refId %r unresolved" % (lay.get("name"), lay.get("refId")))
        # Every layer needs the composition's ip/op. Without them lottie-android substitutes the
        # composition's end frame for the layer's out point, the layer's in/out keyframe then sits
        # exactly on progress 1.0 -- the frame a non-looping animation holds -- and the layer is
        # hidden (BaseLayer.setVisible(false)) in every settled state, which is when our device
        # gates photograph it. The mid-flight frames look perfect, which is what made this so
        # quiet: measured on device 2026-09-27 (2026-09-27 M2 gate, `tree=[...visible=false]`).
        want(lay.get("ip") == 0, "layer %r ip %r != 0" % (lay.get("name"), lay.get("ip")))
        want(lay.get("op") == spec["frames"],
             "layer %r op %r != %d" % (lay.get("name"), lay.get("op"), spec["frames"]))

    # Every stroke/fill colour must be FOUR FLAT components. python-lottie's Color takes its
    # components positionally; one tuple argument emits `[[r,g,b], 0, 0, 1]`, which lottie-android
    # rejects outright (`JsonDataException at $.layers[i].shapes[..].it[j].c.k[0]`) and the state
    # renders nothing -- it shipped in all ten files until the M2 device gate's `err=` caught it
    # (2026-09-27). This check is the author-time half of that lesson; CoreLottieSpecTest is the
    # build-time half. Colours here are constants (never animated), hence the flat-list form.
    # (Five of the ten states have no ring at all -- art, scale and opacity only -- so an empty
    # colour list is fine; a colour that IS present must be the shape lottie reads.)
    def walk_colours(node, out):
        if isinstance(node, dict):
            if node.get("ty") in ("st", "fl") and isinstance(node.get("c"), dict):
                out.append((node.get("ty"), node["c"].get("k")))
            for v in node.values():
                walk_colours(v, out)
        elif isinstance(node, list):
            for v in node:
                walk_colours(v, out)

    colours = []
    walk_colours(d.get("layers", []), colours)
    for kind, k in colours:
        want(isinstance(k, list) and len(k) == 4
             and all(isinstance(x, (int, float)) for x in k),
             "%s colour is not four flat components (lottie refuses this): %r" % (kind, k))

    animated = 0
    for lay in d["layers"]:
        for where, prop in animated_props(lay):
            if prop.get("a") != 1 or not isinstance(prop.get("k"), list):
                continue
            animated += 1
            want(len(prop["k"]) >= 2,
                 "%s: an 'animated' property has fewer than 2 keyframes -- bodymovin will not "
                 "interpolate it" % where)
            times = [kf.get("t") for kf in prop["k"]]
            want(all(t is not None for t in times), "%s: keyframe without a time" % where)
            want(times == sorted(times), "%s: times not increasing %s" % (where, times))
            want(all(0 <= t <= spec["frames"] for t in times),
                 "%s: keyframe outside 0..%d: %s" % (where, spec["frames"], times))
            for kf in prop["k"]:
                for val in (kf.get("s"), kf.get("e")):
                    for v in (val if isinstance(val, list) else [val]):
                        if isinstance(v, (int, float)):
                            want(abs(v) < 100000, "%s: implausible value %r" % (where, v))
    want(animated > 0, "nothing is animated -- the state would freeze")
    return bad, animated


def export_state(name, an):
    path = os.path.join(OUT_DIR, name + ".json")
    with open(path, "w", encoding="utf-8") as f:
        export_lottie(an, f, pretty=True)
    return path


# QA preview: our own compositor over the real art (an intent preview; the device renders Lottie).
def at(pairs, t, default=None):
    """Smoothstepped lookup into [(frame, value)] -- close enough for a preview."""
    if not pairs:
        return default
    if t <= pairs[0][0]:
        return pairs[0][1]
    if t >= pairs[-1][0]:
        return pairs[-1][1]
    for i in range(len(pairs) - 1):
        (t0, v0), (t1, v1) = pairs[i], pairs[i + 1]
        if t0 <= t <= t1:
            f = 0.0 if t1 == t0 else (t - t0) / float(t1 - t0)
            f = f * f * (3 - 2 * f)
            return v0 + (v1 - v0) * f
    return pairs[-1][1]


def draw_arc(dst, radius_px, colour, width_px, start_pct, end_pct, alpha=1.0):
    """A trimmed ellipse stroke the way bodymovin draws it: clockwise from 12 o'clock."""
    if end_pct <= start_pct or alpha <= 0.01:
        return
    cd = dst.size[0] / 2.0
    a0 = -90.0 + 360.0 * start_pct / 100.0
    a1 = -90.0 + 360.0 * end_pct / 100.0
    steps = max(6, int((a1 - a0) / 4.0))
    pts = []
    for i in range(steps + 1):
        ang = math.radians(a0 + (a1 - a0) * i / steps)
        pts.append((cd + radius_px * math.cos(ang), cd + radius_px * math.sin(ang)))
    layer = PILImage.new("RGBA", dst.size, (0, 0, 0, 0))
    PILImageDraw.Draw(layer).line(pts, fill=tuple(int(c * 255) for c in colour) + (255,),
                                 width=max(1, int(round(width_px))), joint="curve")
    layer.putalpha(layer.getchannel("A").point(lambda v: int(v * alpha)))
    dst.alpha_composite(layer)


def preview(name, spec, size=150, tiles=5):
    sheet = PILImage.new("RGBA", (size * tiles, size), (14, 18, 22, 255))
    frames = [int(round(spec["frames"] * i / float(tiles - 1))) for i in range(tiles)]
    k = size / float(CANVAS)
    for i, fr in enumerate(frames):
        tile = PILImage.new("RGBA", (size, size), (14, 18, 22, 255))
        art_names = [spec["art"]] + ([spec["art2"]] if spec.get("art2") else [])
        ops = [at(spec.get("fade_out") or spec.get("opacity"), fr, 100)]
        if spec.get("art2"):
            ops.append(at(spec["fade_in"], fr, 0))
        for art_name, op in zip(art_names, ops):
            if op <= 0.5:
                continue
            im = PILImage.open(os.path.join(IMG_DIR, art_name)).convert("RGBA")
            side = max(1, int(round(size * at(spec.get("scale"), fr, 100) / 100.0)))
            im = im.resize((side, side), PILImage.LANCZOS)
            im.putalpha(im.getchannel("A").point(lambda v, o=op: int(v * o / 100.0)))
            dx = int(round(size * at(spec.get("shift"), fr, 0) / float(CANVAS)))
            tile.alpha_composite(im, ((size - side) // 2 + dx, (size - side) // 2))
        if spec.get("track"):
            draw_arc(tile, RING_R * k, CYAN, TRACK_W * k, 0.0, 100.0,
                     at(spec["track"], fr, 0) / 100.0)
        ring = spec.get("ring")
        if ring:
            sweep = at(ring["sweep"], fr, 0.0)
            draw_arc(tile, RING_R * k, CYAN, ring.get("width", 5.0) * k, 0.0, sweep,
                     at(ring.get("opacity"), fr, 100) / 100.0)
            if ring.get("comet"):
                draw_arc(tile, RING_R * k, PALE, (ring.get("width", 5.0) + 1) * k,
                         at(ring["comet"], fr, 0.0), sweep)
        if spec.get("merge"):
            op = at(spec["merge"]["opacity"], fr, 0)
            if op > 0.5:
                d = spec["merge"]["diameter"] * k * 0.62
                layer = PILImage.new("RGBA", (size, size), (0, 0, 0, 0))
                PILImageDraw.Draw(layer).ellipse(
                    ((size - d) / 2, (size - d) / 2, (size + d) / 2, (size + d) / 2),
                    fill=tuple(int(c * 255) for c in PALE) + (255,))
                layer.putalpha(layer.getchannel("A").point(lambda v, o=op: int(v * o / 100.0)))
                tile.alpha_composite(layer)
        sheet.alpha_composite(tile, (size * i, 0))
    os.makedirs(QA_DIR, exist_ok=True)
    out = os.path.join(QA_DIR, name + "_sheet.png")
    sheet.save(out)
    return out, frames


def main():
    ap = argparse.ArgumentParser(description="build the Fetcher 2.0 Core Lottie states")
    ap.add_argument("--check", action="store_true",
                    help="validate every JSON and write five-frame QA contact sheets")
    args = ap.parse_args()

    print("Core Lottie build -> %s" % OUT_DIR)
    drifted = check_core_tint_agreement()
    if drifted:
        print("\nCoreTint drift -- the baked art would not match the Java tint:")
        for d in drifted:
            print("  - %s" % d)
        return 1
    print("  CoreTint.java agrees with the baked tints")
    os.makedirs(OUT_DIR, exist_ok=True)
    write_tinted_art()
    write_images()

    failures = []
    print("\n%-18s %6s %7s %5s %8s %s" % ("state", "frames", "layers", "keys", "bytes", "note"))
    for name, spec in STATES.items():
        path = export_state(name, build(name, spec))
        bad, animated = validate(path, spec)
        d = json.loads(open(path, encoding="utf-8").read())
        print("%-18s %6d %7d %5d %8d %s" % (name, spec["frames"], len(d["layers"]), animated,
                                            os.path.getsize(path), spec.get("note", "")))
        failures += ["%s: %s" % (name, m) for m in bad]
        if args.check:
            sheet, frames = preview(name, spec)
            print("        preview %s  frames %s" % (os.path.basename(sheet), frames))

    if failures:
        print("\nVALIDATION FAILED (%d)" % len(failures))
        for f in failures:
            print("  - %s" % f)
        return 1
    print("\n%d states built and validated: canvas %d, %d fps, op == frames, refIds resolve, "
          "times monotonic and in range, no absolute paths, and every state animates."
          % (len(STATES), CANVAS, FPS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
