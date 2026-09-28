"""Offline preview of the Downi Core with the sheet-2 mark (no device needed).

Renders the Core's glass disc + cyan rim + energy perimeter + the shipping mark
(`drawable-nodpi/downi_core_mark.png`) at the Phase A sizes and progress values, so the
owner can sanity-check the mark's size inside the disc before the on-device K-A2 gate.

This is a *mock for review*, not evidence: the real cells (K-A2/K-A3/K-A4) are screenshots
from `tools/core_states.ps1` on the phone.

Usage: python tools/core_mark_preview.py
"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MARK = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable-nodpi", "downi_core_mark.png")
OUT = os.path.join(ROOT, "test_out", "core_visual", "_review_core_offline.jpg")
DENSITY = 2.75             # vivo V2058 (440 dpi) - the phone the gate runs on
MARK_SCALE = 0.63          # CoreHost.DEFAULT_MARK_SCALE (measured at the K-A2 gate)
RIM = ((0x9B, 0xE8, 0xFF), (0x22, 0xD3, 0xEE), (0x3B, 0x82, 0xF6), (0x9B, 0xE8, 0xFF))


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def rim_color(t):
    """Same 4-stop cyan ramp as CoreHost's LinearGradient, walked around the ring."""
    n = len(RIM) - 1
    x = t * n
    i = min(int(x), n - 1)
    return lerp(RIM[i], RIM[i + 1], x - i)


def draw_core(size_px, mark, progress=0.0, ring_alpha=1.0):
    """One Core: glow, glass disc, rim, energy perimeter, mark."""
    s = size_px
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    c = (s - 1) / 2.0
    r = s / 2.0 - 6 * DENSITY / 2.625          # 6 dp inset for the glow, as the view does
    r_glow = r + 6

    d.ellipse((c - r_glow, c - r_glow, c + r_glow, c + r_glow), fill=(0x22, 0xD3, 0xEE, 0x38))
    layer = layer.filter(ImageFilter.GaussianBlur(s * 0.05))
    d = ImageDraw.Draw(layer)

    d.ellipse((c - r, c - r, c + r, c + r), fill=(0x12, 0x17, 0x24, 0xF2))
    for i in range(90):                        # glass shading: light from above
        t = i / 89.0
        rr = r * (1.0 - t)
        d.ellipse((c - rr, c - rr, c + rr, c + rr),
                  fill=(0x1B + int(10 * (1 - t)), 0x24 + int(10 * (1 - t)), 0x36 + int(10 * (1 - t)), 14))

    steps = max(48, s)
    for i in range(steps):                     # rim
        a0 = 360.0 * i / steps
        col = rim_color(i / float(steps))
        d.arc((c - r + 1, c - r + 1, c + r - 1, c + r - 1), a0, a0 + 360.0 / steps + 1,
              fill=(col[0], col[1], col[2], int(255 * ring_alpha)), width=max(1, int(1.6 * DENSITY / 2.625)))

    r_in = r - 1.2 * DENSITY / 2.625
    if progress > 0.0:                         # energy perimeter = progress, no percent text
        d.arc((c - r_in, c - r_in, c + r_in, c + r_in), -90, -90 + 360.0 * progress,
              fill=(0x7D, 0xF9, 0xFF, 255), width=max(2, int(2.6 * DENSITY / 2.625)))

    side = int(round(2 * r_in * MARK_SCALE))
    m = mark.resize((side, side), Image.Resampling.LANCZOS)
    layer.paste(m, (int(round(c - side / 2.0)), int(round(c - side / 2.0))), m)
    return layer


def main():
    mark = Image.open(MARK).convert("RGBA")
    sizes = (48, 56, 64)
    progs = (0.0, 0.25, 0.5, 0.75, 1.0)
    dpx = [int(round(s * DENSITY)) for s in sizes]
    pad = 26
    gap = 34

    row1 = sum(dpx) + gap * (len(dpx) - 1)
    row2 = sum([int(round(64 * DENSITY))] * len(progs)) + gap * (len(progs) - 1)
    w = max(row1, row2) + 2 * pad
    h = int(round(64 * DENSITY)) * 2 + 3 * pad
    out = Image.new("RGBA", (w, h), (8, 12, 18, 255))

    x = pad
    for s in sizes:
        core = draw_core(int(round(s * DENSITY)), mark)
        out.paste(core, (x, pad), core)
        x += core.width + gap

    x = pad
    y = pad * 2 + int(round(64 * DENSITY))
    for p in progs:
        core = draw_core(int(round(64 * DENSITY)), mark, p)
        out.paste(core, (x, y), core)
        x += core.width + gap

    if not os.path.isdir(os.path.dirname(OUT)):
        os.makedirs(os.path.dirname(OUT))
    out.convert("RGB").save(OUT, "JPEG", quality=94)
    print("wrote %s  (%dx%d)  sizes 48/56/64 dp, progress 0/25/50/75/100%% at 64 dp, markScale %.2f"
          % (os.path.relpath(OUT, ROOT), out.width, out.height, MARK_SCALE))


if __name__ == "__main__":
    main()
