"""Sheet vs device: the Core's mark at the same disc size (cell K-A2 evidence).

The owner's judgement at K-A2 is "same mark, same size, no redesign" - and that is easiest to
make when the design sheet and the phone's own screenshot sit in one image with their discs the
same size. This script crops one Core per cell, rescales so every disc measures the same number
of pixels, and tiles three rows:

    row 1   the sheets themselves (3: idle / detected, 4: paused)
    row 2   on-device 64 dp at each markScale of the sweep  -> pick the matching one
    row 3   the shipping size row, 48 / 56 / 64 dp, at the bake scale

Device geometry is taken from the Core's code (window px = size_dp x density, r = px/2 - 6dp),
never guessed: the cells keep their real relative size once normalised by the disc.

Usage:
    python tools/core_mark_compare.py
    python tools/core_mark_compare.py --bake 0.63 --density 2.75 --out test_out/core_visual/_review_mark_ondevice.jpg
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHOTS = os.path.join(ROOT, "test_out", "core_visual")
DENSITY_DEFAULT = 2.75        # the gate phone (vivo V2058): 440 dpi / 160
R_INSET_DP = 6.0              # CoreHost: r = min(w,h)/2 - 6dp
AT_DEFAULT = (500, 1000)      # where tools/core_mark_gate.ps1 parks the Core

# (sheet file, glyph centre x, y, disc diameter px, label) - measured with
# tools/core_mark_measure.py, so the reference row is verifiable, not eyeballed.
SHEET_CELLS = [
    ("3", 138, 378, 144, "sheet 3  idle"),
    ("3", 346, 376, 147, "sheet 3  detected"),
    ("4", 196, 680, 150, "sheet 4  paused"),
]
SCALE_SWEEP = ["s042", "s053", "s056", "s063", "s070"]
SIZE_ROW = [48, 56, 64]

CANVAS = 220                  # px per cell
TARGET_DISC = 130             # every disc is normalised to this many px


def font(size):
    for name in ("arial.ttf", "segoeui.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except Exception:
            pass
    return ImageFont.load_default()


def cell(img, cx, cy, disc, label, target_disc=TARGET_DISC):
    """One Core, scaled so its disc measures `target_disc` px, on a CANVAS x CANVAS tile."""
    scale = target_disc / float(disc)
    side = CANVAS / scale
    box = (cx - side / 2.0, cy - side / 2.0, cx + side / 2.0, cy + side / 2.0)
    crop = img.crop(tuple(int(round(v)) for v in box))
    crop = crop.resize((CANVAS, CANVAS), Image.Resampling.LANCZOS)
    return crop, label


def device_cell(path, size_dp, density, at, label):
    """Geometry straight from CoreHost: only thus is the normalised size honest."""
    img = Image.open(path).convert("RGB")
    px = int(round(size_dp * density))
    cx = at[0] + px / 2.0
    cy = at[1] + px / 2.0
    disc = 2 * (px / 2.0 - R_INSET_DP * density)
    return cell(img, cx, cy, disc, label)


def sheet_cell(sheet_dir, name, cx, cy, disc, label):
    img = Image.open(os.path.join(sheet_dir, name)).convert("RGB")
    return cell(img, cx, cy, disc, label)


def main():
    ap = argparse.ArgumentParser(description="Sheet vs device, discs normalised (K-A2).")
    ap.add_argument("--sheets", default=r"C:\Users\Manso\Desktop", help="folder holding the sheets 1..8")
    ap.add_argument("--bake", type=float, default=0.56, help="the markScale the size row was shot at")
    ap.add_argument("--default", action="store_true",
                    help="use the `-Default` shots (core_mark_def_*): no `mark` command was sent, "
                         "so they show the baked DEFAULT_MARK_SCALE")
    ap.add_argument("--density", type=float, default=DENSITY_DEFAULT)
    ap.add_argument("--at", default="%d,%d" % AT_DEFAULT)
    ap.add_argument("--out", default=os.path.join(SHOTS, "_review_mark_ondevice.jpg"))
    args = ap.parse_args()

    at = tuple(int(v) for v in args.at.split(","))
    f, ftitle = font(13), font(16)
    pad, gap, label_h = 20, 16, 26

    rows = []
    ref = []
    for name, cx, cy, disc, label in SHEET_CELLS:
        p = os.path.join(args.sheets, name)
        if not os.path.isfile(p):
            print("sheet missing, cell skipped: " + p)
            continue
        ref.append(sheet_cell(args.sheets, name, cx, cy, disc, label))
    rows.append(("the design sheets (reference)", ref))

    sweep = []
    for tag in SCALE_SWEEP:
        p = os.path.join(SHOTS, "core_mark_%s_64dp.png" % tag)
        if not os.path.isfile(p):
            continue
        scale = int(tag[1:]) / 100.0
        sweep.append(device_cell(p, 64, args.density, at, "device %.2f" % scale))
    if sweep:
        rows.append(("on device, 64 dp, markScale sweep", sweep))

    bake_tag = "def" if args.default else "s%03d" % int(round(args.bake * 100))
    sizes = []
    for dp in SIZE_ROW:
        p = os.path.join(SHOTS, "core_mark_%s_%ddp.png" % (bake_tag, dp))
        if not os.path.isfile(p):
            continue
        sizes.append(device_cell(p, dp, args.density, at, "device %d dp" % dp))
    if sizes:
        rows.append(("on device at markScale %.2f - the shipping size row" % args.bake, sizes))
    else:
        print("no size-row shots for markScale %.2f yet: " % args.bake
              + "run tools\\core_mark_gate.ps1 -Bake %.2f" % args.bake)

    if not ref and not sweep and not sizes:
        print("nothing to compare - no sheets and no shots found")
        return 1

    cell_w = CANVAS + gap
    w = pad + max(len(r) for _, r in rows) * cell_w - gap + pad
    row_h = CANVAS + label_h
    h = pad + len(rows) * (row_h + 18 + 6)

    out = Image.new("RGB", (w, h), (8, 12, 18))
    d = ImageDraw.Draw(out)
    y = pad
    for title, cells in rows:
        d.text((pad, y), title, fill=(125, 249, 255), font=ftitle)
        y += 22
        x = pad
        for img, label in cells:
            out.paste(img, (x, y))
            tw = d.textlength(label, font=f)
            d.text((x + (CANVAS - tw) / 2.0, y + CANVAS + 4), label, fill=(200, 214, 226), font=f)
            x += cell_w
        y += row_h + 18 + 6

    if not os.path.isdir(os.path.dirname(args.out)):
        os.makedirs(os.path.dirname(args.out))
    out.save(args.out, "JPEG", quality=94)
    print("wrote %s  (%dx%d)" % (os.path.relpath(args.out, ROOT), out.width, out.height))
    print("every disc is normalised to %d px, so any size difference is real, not framing." % TARGET_DISC)
    return 0


if __name__ == "__main__":
    sys.exit(main())
