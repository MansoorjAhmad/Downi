"""Measure the Core mark: the sheets' own proportions vs what the phone actually draws.

Owner ruling 2026-09-25: the Core's mark is *lifted* from design sheet 2, never redrawn
(`tools/core_mark_from_sheet.py`) - but how much of the disc it fills is a judgement the owner
makes by eye, so this script prints the numbers that judgement is about (cell K-A2):

    sheet mode   the black glossy disc and the teal glyph inside it, as the sheet drew them
                 -> "the mark stands ~NN% of the disc tall/wide".
    shot  mode   the same measurement on an on-device screenshot, using the Core's known
                 geometry (window px = size_dp x density, r = px/2 - 6dp, rIn = r - 1.2dp),
                 so the drawn ratio can be compared with the sheet's ratio directly.

Usage:
    python tools/core_mark_measure.py sheet "C:\\Users\\Manso\\Desktop\\3" --box 60,880,1420,1250
    python tools/core_mark_measure.py shot test_out/core_visual/core_mark_s056_64dp.png --at 500,1000
    python tools/core_mark_measure.py shot <shot.png> --at 500,1000 --save test_out/core_visual/_measure.jpg

Both modes accept --save, which writes the crop with the two boxes drawn on it, so the numbers
can be eyeballed against the pixels.
"""
import argparse
import os
import sys

from PIL import Image, ImageChops, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

DENSITY_DEFAULT = 2.75        # the gate phone (vivo V2058): 440 dpi / 160
R_INSET_DP = 6.0              # CoreHost: r = min(w,h)/2 - 6dp   (room for the glow)
RIN_DP = 1.2                  # CoreHost: rIn = r - 1.2dp
DEFAULT_ASSET = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable-nodpi",
                             "downi_core_mark.png")


def teal_mask(img):
    """The mark's teal - saturated cyan-green, the same family as the rim, so callers must
    restrict the region (the glyph lives in the middle, the rim at the edge)."""
    out = Image.new("L", img.size, 0)
    src, dst = img.load(), out.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b = src[x, y][:3]
            if g >= 90 and b >= 80 and (g - r) >= 20 and g >= b - 30:
                dst[x, y] = 255
    return out


def dark_mask(img, ceiling=72, spread=26):
    """The black glossy disc: dark AND near-neutral (so a coloured app behind it cannot pass)."""
    out = Image.new("L", img.size, 0)
    src, dst = img.load(), out.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b = src[x, y][:3]
            mx = max(r, g, b)
            if mx <= ceiling and (mx - min(r, g, b)) <= spread:
                dst[x, y] = 255
    return out


def circle_mask(size, cx, cy, radius):
    out = Image.new("L", size, 0)
    ImageDraw.Draw(out).ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=255)
    return out


def box_of(bb):
    return None if not bb else (bb[0], bb[1], bb[2] - 1, bb[3] - 1)


def report(name, box):
    if not box:
        print("  %-8s none" % name)
        return 0, 0
    w, h = box[2] - box[0] + 1, box[3] - box[1] + 1
    print("  %-8s x=%d y=%d  %dx%d px" % (name, box[0], box[1], w, h))
    return w, h


def save_review(img, box, path):
    ImageDraw.Draw(img).rectangle(box, outline=(255, 0, 90))
    img.save(path, quality=92)
    print("  review   " + path)


def rel(path):
    return os.path.relpath(path, ROOT) if os.path.abspath(path).startswith(ROOT) else path


def do_sheet(args):
    img = Image.open(args.path).convert("RGB")
    if args.box:
        x0, y0, x1, y1 = [int(v) for v in args.box.split(",")]
    else:
        x0, y0, x1, y1 = 0, 0, img.width - 1, img.height - 1
    crop = img.crop((x0, y0, x1 + 1, y1 + 1))
    print("sheet   %s  %dx%d  crop=%d,%d,%d,%d" %
          (os.path.basename(args.path), img.width, img.height, x0, y0, x1, y1))

    print("disc    (the black glossy body: dark and near-neutral)")
    dw, dh = report("disc", box_of(dark_mask(crop, ceiling=args.disc_ceiling).getbbox()))

    # glyph only: inside the disc, and away from the glowing teal rim at its edge
    region = circle_mask(crop.size, crop.width / 2.0, crop.height / 2.0,
                         max(dw, dh) / 2.0 * args.inner)
    print("glyph   (the teal mark, inside %.0f%% of the disc radius)" % (args.inner * 100))
    gw, gh = report("glyph", box_of(ImageChops.multiply(teal_mask(crop), region).getbbox()))

    if dw and dh and gw and gh:
        print("")
        print("sheet ratios   mark height / disc height = %.3f   mark width / disc width = %.3f" %
              (gh / float(dh), gw / float(dw)))
    if args.save:
        save_review(crop, (0, 0, crop.width - 1, crop.height - 1), args.save)
    return 0


def do_shot(args):
    img = Image.open(args.path).convert("RGB")
    print("shot    %s  %dx%d" % (os.path.basename(args.path), img.width, img.height))

    px = int(round(args.size_dp * args.density))
    ax, ay = [int(v) for v in args.at.split(",")]
    cx, cy = ax + px / 2.0, ay + px / 2.0            # the Core is centred in its window
    r = px / 2.0 - R_INSET_DP * args.density
    r_in = r - RIN_DP * args.density
    print("geom    window %d px (%d dp @ %.2f)   r=%.1f   rIn=%.1f" %
          (px, args.size_dp, args.density, r, r_in))

    x0, y0 = max(0, ax - 2), max(0, ay - 2)
    x1, y1 = min(img.width - 1, ax + px + 2), min(img.height - 1, ay + px + 2)
    crop = img.crop((x0, y0, x1 + 1, y1 + 1))
    ccx, ccy = cx - x0, cy - y0

    glyph_region = circle_mask(crop.size, ccx, ccy, r_in * args.inner)
    print("glyph   (teal mark, inside %.0f%% of rIn)" % (args.inner * 100))
    gw, gh = report("glyph", box_of(ImageChops.multiply(teal_mask(crop), glyph_region).getbbox()))

    disc_region = circle_mask(crop.size, ccx, ccy, r_in * 1.02)
    print("disc    (window-bounded dark body)")
    dw, dh = report("disc", box_of(ImageChops.multiply(
        dark_mask(crop, ceiling=args.disc_ceiling), disc_region).getbbox()))

    # Intent: tile side = 2*rIn*scale, and the glyph fills 89% of the tile height / 69% of its
    # width (measured off the 512 px master by tools/core_mark_from_sheet.py).
    tile = 2 * r_in * args.scale
    exp_h = tile * 0.889
    print("expect  scale %.2f -> tile %.1f px, glyph %.1f x %.1f px" %
          (args.scale, tile, tile * 0.689, exp_h))
    if gh:
        print("draw    mark height / disc height = %.3f   (disc %.1f px, mark %d px)" %
              (gh / (2 * r), 2 * r, gh))
        print("delta   measured %d px vs expected %.1f px  (%+.1f%%)" %
              (gh, exp_h, 100.0 * (gh - exp_h) / exp_h))
        if args.sheet_ratio:
            want = args.sheet_ratio * 2 * r
            print("target  the sheet's %.3f needs a %.1f px mark -> scale %.2f" %
                  (args.sheet_ratio, want, args.scale * want / gh))
    if args.save:
        save_review(crop, (0, 0, crop.width - 1, crop.height - 1), args.save)
    return 0


def do_asset(args):
    """The shipping master itself — the asset the Java constants are tied to.

    `CoreLook.MARK_TILE_HEIGHT/WIDTH` are measured here and asserted by the JVM test
    `CoreMarkSpecTest.recordedGeometryIsTheMeasurement`, so this mode is how the numbers are
    re-justified if the mark is ever re-lifted.
    """
    img = Image.open(args.path).convert("RGBA")
    alpha = img.split()[3]
    bb = alpha.point(lambda v: 255 if v > args.alpha_min else 0).getbbox()
    print("asset   %s  %dx%d" % (os.path.basename(args.path), img.width, img.height))
    if not bb:
        print("  the asset is empty - it would draw nothing at all")
        return 2
    gw, gh = bb[2] - bb[0], bb[3] - bb[1]
    hf, wf = gh / float(img.height), gw / float(img.width)
    print("  glyph  x=%d y=%d  %dx%d px  (alpha > %d)" % (bb[0], bb[1], gw, gh, args.alpha_min))
    print("  tile   glyph height %.4f of the tile, width %.4f" % (hf, wf))
    print("  corners alpha=%d / %d  (0 = a transparent tile, as the Core draws it)"
          % (img.getpixel((0, 0))[3], img.getpixel((img.width - 1, img.height - 1))[3]))

    want = (458 / 512.0, 357 / 512.0)          # what CoreLook ships with
    print("")
    print("corelook   MARK_TILE_HEIGHT = 458/512 = %.4f   MARK_TILE_WIDTH = 357/512 = %.4f" % want)
    ok = abs(hf - want[0]) < 0.005 and abs(wf - want[1]) < 0.005
    print("verdict    %s" % ("OK - the constants match the asset"
                             if ok else "MISMATCH - re-lift or update CoreLook.MARK_TILE_*"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description="Measure the Core mark against the design sheets (K-A2).")
    sub = ap.add_subparsers(dest="mode", required=True)

    sh = sub.add_parser("sheet", help="measure a design sheet")
    sh.add_argument("path")
    sh.add_argument("--box", default="", help="x0,y0,x1,y1 crop around ONE Core")
    sh.add_argument("--inner", type=float, default=0.72,
                    help="fraction of the disc radius kept for the glyph")
    sh.add_argument("--disc-ceiling", type=int, default=72, dest="disc_ceiling",
                    help="darkest..brightest a pixel may be and still count as the disc")
    sh.add_argument("--save", default="")

    as_ = sub.add_parser("asset", help="measure the shipping PNG (what CoreLook's constants must equal)")
    as_.add_argument("path", nargs="?", default=DEFAULT_ASSET)
    as_.add_argument("--alpha-min", type=int, default=8, dest="alpha_min",
                     help="the alpha floor the lifting tool uses")

    st = sub.add_parser("shot", help="measure an on-device screenshot")
    st.add_argument("path")
    st.add_argument("--at", required=True, help="x,y the Core was placed at (`at x y` on the channel)")
    st.add_argument("--size-dp", type=int, default=64, dest="size_dp")
    st.add_argument("--density", type=float, default=DENSITY_DEFAULT)
    st.add_argument("--scale", type=float, default=0.56, help="markScale that was applied")
    st.add_argument("--inner", type=float, default=0.55, help="fraction of rIn kept for the glyph")
    st.add_argument("--disc-ceiling", type=int, default=72, dest="disc_ceiling")
    st.add_argument("--sheet-ratio", type=float, default=0.0, dest="sheet_ratio",
                    help="the sheet's mark/disc height ratio, to solve for the matching scale")
    st.add_argument("--save", default="")

    args = ap.parse_args()
    if not os.path.isfile(args.path):
        print("not found: " + args.path)
        return 1
    if args.mode == "sheet":
        return do_sheet(args)
    if args.mode == "asset":
        return do_asset(args)
    return do_shot(args)


if __name__ == "__main__":
    sys.exit(main())
