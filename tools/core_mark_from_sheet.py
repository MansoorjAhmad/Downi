"""Lift the Downi Core identity mark out of the owner's design sheet 2 (no redraw).

Owner ruling 2026-09-25: the Core's mark is the sheet-2 mark (the glossy teal
"folded ribbon chevron" + its black/graphite variant) - NOT the app icon
(`downi_app_icon.png`, the speed-D), which stays the launcher/app identity only.

Sheet 2 is raster, so this script crops the mark exactly as the owner drew it:

  sheet 2 (transparent png)                 ->  android/app/src/main/res/drawable-nodpi/
    top band, left  big glyph  (teal)           downi_core_mark.png        (512 px)
    top band, right big glyph  (graphite)       downi_core_mark_dark.png   (512 px)
    bottom band    8 small glyphs (size row)    (measured, feeds the legibility report)

It also writes `test_out/core_visual/_review_mark_sizes.jpg` - the sheet's own
48/32/24/16 px legibility row rebuilt from the extracted asset, so the owner can
approve the shipping asset against the sheet (cell K-A2).

Usage:
    python tools/core_mark_from_sheet.py
    python tools/core_mark_from_sheet.py --sheet "C:\\Users\\Manso\\Desktop\\2" --report-only
"""
import argparse
import os
import sys

from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
OUT_DRAWABLE = os.path.join(RES, "drawable-nodpi")
OUT_REVIEW = os.path.join(ROOT, "test_out", "core_visual")

DEFAULT_SHEET = r"C:\Users\Manso\Desktop\2"
ALPHA_MIN = 8          # the sheet is anti-aliased on transparency; ignore faint dust
BAND_GAP = 24          # px of empty rows that separate the glyph bands
COL_GAP = 16           # px of empty columns that separate glyphs inside a band
MARK_PX = 512          # shipping master size (the old app mark was 512 too)


def key_baked_background(rgb):
    """Some sheets are flat RGB with the transparency checkerboard *baked in*
    (sheet 2: squares ~232 and ~253, both near-neutral). Key it out:

      * the checkerboard is bright (value > ~0.74) AND neutral (saturation < ~0.14);
      * the teal glyph is saturated, the graphite glyph is dark/mid-grey - both survive;
      * soft drop shadows (bright + neutral) are dropped, so no grey halo travels with the mark.

    Interior holes left by light specular pixels are then filled by a binary closing.
    """
    px = rgb.load()
    w, h = rgb.size
    alpha = Image.new("L", (w, h), 0)
    ap = alpha.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            mx = r if r > g else g
            if b > mx:
                mx = b
            mn = r if r < g else g
            if b < mn:
                mn = b
            val = mx / 255.0
            sat = 0.0 if mx == 0 else (mx - mn) / float(mx)
            v_term = (val - 0.74) / 0.08
            s_term = (0.14 - sat) / 0.10
            if v_term > 1.0:
                v_term = 1.0
            if s_term > 1.0:
                s_term = 1.0
            bg = min(v_term, s_term) if (v_term > 0.0 and s_term > 0.0) else 0.0
            ap[x, y] = int(round(255.0 * (1.0 - bg)))

    solid = alpha.point(lambda v: 255 if v > 100 else 0)
    closed = solid.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(9))
    alpha = Image.composite(Image.new("L", (w, h), 255), alpha, closed)   # fill interior holes
    return alpha


def load_mask(path):
    """(rgba image, binary coverage mask) - from a real alpha channel or a keyed sheet."""
    img = Image.open(path)
    if img.mode == "RGB":
        img = img.convert("RGBA")
        alpha = key_baked_background(img.convert("RGB"))
        img.putalpha(alpha)
    elif img.mode != "RGBA":
        img = img.convert("RGBA")
    alpha = img.split()[3]
    if alpha.getextrema()[0] == alpha.getextrema()[1]:
        alpha = key_baked_background(img.convert("RGB"))     # alpha present but empty
        img.putalpha(alpha)
    mask = alpha.point(lambda v: 255 if v > ALPHA_MIN else 0)
    return img, mask


def runs(values, gap):
    """[(start, end)] inclusive runs of truthy values, bridging gaps smaller than `gap`."""
    out = []
    start = None
    last = None
    for i, on in enumerate(values):
        if on:
            if start is None:
                start = i
            last = i
        elif start is not None and i - last >= gap:
            out.append((start, last))
            start = None
    if start is not None:
        out.append((start, last))
    return out


def bands_of(mask, x0, y0, x1, y1):
    """Row bands (glyph rows) with their column clusters inside each band."""
    px = mask.load()
    row_bands = runs([any(px[x, y] for x in range(x0, x1 + 1)) for y in range(y0, y1 + 1)], BAND_GAP)
    out = []
    for (rs, re_) in row_bands:
        top, bottom = y0 + rs, y0 + re_
        col_bands = runs([any(px[x, y] for y in range(top, bottom + 1)) for x in range(x0, x1 + 1)], COL_GAP)
        out.append((top, bottom, [(x0 + cs, x0 + ce) for (cs, ce) in col_bands]))
    return out


def glyph_box(mask, x0, y0, x1, y1):
    """Tight bbox of the glyph inside a rough region, or None when empty."""
    px = mask.load()
    minx, miny, maxx, maxy = x1, y1, x0, y0
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if px[x, y]:
                minx = x if x < minx else minx
                maxx = x if x > maxx else maxx
                miny = y if y < miny else miny
                maxy = y if y > maxy else maxy
    if minx > maxx:
        return None
    return (minx, miny, maxx, maxy)


def to_square(img, box, size, pad_frac=0.06):
    """Crop `box`, centre it on a transparent square canvas, resize to `size`."""
    glyph = img.crop((box[0], box[1], box[2] + 1, box[3] + 1))
    side = int(round(max(glyph.width, glyph.height) * (1.0 + 2.0 * pad_frac)))
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(glyph, ((side - glyph.width) // 2, (side - glyph.height) // 2), glyph)
    return canvas.resize((size, size), Image.Resampling.LANCZOS)



def review_strip(teal, dark, out_path):
    """Rebuild sheet 2's small-size row (48/32/24/16) from the extracted assets."""
    sizes = (48, 32, 24, 16)
    pad = 18
    gap = 26
    w = 2 * (sum(sizes) + gap * (len(sizes) - 1)) + 3 * pad + gap
    h = max(sizes) + 2 * pad
    strip = Image.new("RGBA", (w, h), (10, 14, 22, 255))
    x = pad
    for src in (teal, dark):
        for s in sizes:
            small = src.resize((s, s), Image.Resampling.LANCZOS)
            strip.paste(small, (x, (h - s) // 2), small)
            x += s + gap
        x += gap
    if not os.path.isdir(OUT_REVIEW):
        os.makedirs(OUT_REVIEW)
    strip.convert("RGB").save(out_path, "JPEG", quality=92)
    return out_path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", default=DEFAULT_SHEET, help="owner's design sheet 2 png")
    ap.add_argument("--size", type=int, default=MARK_PX)
    ap.add_argument("--teal-index", type=int, default=0, help="index of the teal glyph in the top band (l->r)")
    ap.add_argument("--dark-index", type=int, default=1, help="index of the graphite glyph in the top band (l->r)")
    ap.add_argument("--report-only", action="store_true")
    args = ap.parse_args()

    if not os.path.isfile(args.sheet):
        print("sheet not found: " + args.sheet)
        return 1

    img, mask = load_mask(args.sheet)
    print("sheet   %s  %dx%d" % (os.path.basename(args.sheet), img.width, img.height))

    bands = bands_of(mask, 0, 0, img.width - 1, img.height - 1)
    print("bands   %d" % len(bands))
    for bi, (top, bottom, cols) in enumerate(bands):
        print("  band %d  y=%d..%d  glyphs=%d" % (bi, top, bottom, len(cols)))
        for ci, (cx0, cx1) in enumerate(cols):
            gb = glyph_box(mask, cx0, top, cx1, bottom)
            print("    [%d] x=%d..%d  tight=%s  %dx%d" %
                  (ci, cx0, cx1, gb, gb[2] - gb[0] + 1, gb[3] - gb[1] + 1))

    if args.report_only:
        return 0

    if not bands or len(bands[0][2]) < 2:
        print("could not find the two big glyphs in the top band")
        return 2

    top, bottom, cols = bands[0]
    teal_box = glyph_box(mask, cols[args.teal_index][0], top, cols[args.teal_index][1], bottom)
    dark_box = glyph_box(mask, cols[args.dark_index][0], top, cols[args.dark_index][1], bottom)

    teal = to_square(img, teal_box, args.size)
    dark = to_square(img, dark_box, args.size)

    if not os.path.isdir(OUT_DRAWABLE):
        os.makedirs(OUT_DRAWABLE)
    teal_path = os.path.join(OUT_DRAWABLE, "downi_core_mark.png")
    dark_path = os.path.join(OUT_DRAWABLE, "downi_core_mark_dark.png")
    teal.save(teal_path, "PNG")
    dark.save(dark_path, "PNG")

    gw, gh = teal_box[2] - teal_box[0] + 1, teal_box[3] - teal_box[1] + 1
    tile = max(gw, gh)
    print("")
    print("teal    %s  (glyph %dx%d inside the %d px tile)" %
          (os.path.relpath(teal_path, ROOT), gw, gh, tile))
    print("dark    %s" % os.path.relpath(dark_path, ROOT))
    print("review  %s" % os.path.relpath(
        review_strip(teal, dark, os.path.join(OUT_REVIEW, "_review_mark_sizes.jpg")), ROOT))
    print("")
    print("the tile is drawn as markScale x the Core's inner disc diameter, so at scale 1.0")
    print("the mark stands %.0f%% of that diameter wide and %.0f%% tall." %
          (100 * gw / float(tile), 100 * gh / float(tile)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
