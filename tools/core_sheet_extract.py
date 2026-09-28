"""Downi Fetcher 2.0 — extract the Core's static art from the owner's reference sheet.

The v3.3 plan ships the Core as pre-built art (Lottie later; static first). The art's source is
the owner's own sheet `test_out/refs_v33/C1.png`, so what ships is the approved design, not an
approximation of it. This tool is reproducible and PIL-only (no numpy in this environment):

  1. luminance mask (bg + 25) -> hairline open (k=3) -> largest component -> fill holes
     = the object's SILHOUETTE (its bright parts form a ring; the dark gel body is a hole),
  2. crop tight: silhouette bbox + pad, which keeps the sheet's annotation text out of the tile,
  3. alpha_lum = luminance glow outside the silhouette; alpha = max(alpha_lum, blur(silhouette)),
  4. OPEN the alpha: erases the sheet's 1-9 px annotation hairlines, leader dots and the dashed
     touch circle without touching the orb (opening removes protrusions thinner than its kernel),
  5. report stray islands — anything bright that is NOT the orb (the automated quality gate),
  6. write the 512 px masters into res/drawable-nodpi and print the object/tile ratio CoreHost
     needs to size the art on the disc.

Run:  python tools/core_sheet_extract.py
"""
import os
import collections
from PIL import Image, ImageChops, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHEET = os.path.join(ROOT, 'test_out', 'refs_v33', 'C1.png')
DRAWABLE = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res', 'drawable-nodpi')

MASTER_PX = 512
THR_ABS = 25            # threshold above the sheet's own background
ALPHA_OPEN = 15         # must exceed the sheet's thickest annotation line/dot (hero: ~6 px lines,
                        # ~10 px leader dots; the orb is 358 px thick, so opening cannot touch it)


def val(im):
    """max(R,G,B) per pixel — the right channel for a glowing object on a black sheet."""
    r, g, b = im.split()
    return ImageChops.lighter(ImageChops.lighter(r, g), b)


def pct(im, p):
    hist = im.histogram()
    total = sum(hist)
    want = total * p / 100.0
    run = 0
    for v, n in enumerate(hist):
        run += n
        if run >= want:
            return float(v)
    return 0.0


def nz(im):
    return sum(im.histogram()[1:])


def components(mask, min_area=1):
    """4/8-connected components of a binary 'L' mask, as (area, bbox) pairs."""
    w, h = mask.size
    px = mask.load()
    seen = [[False] * w for _ in range(h)]
    out = []
    for sy in range(h):
        for sx in range(w):
            if not px[sx, sy] or seen[sy][sx]:
                continue
            q = collections.deque([(sx, sy)])
            seen[sy][sx] = True
            n = 0
            x0 = x1 = sx
            y0 = y1 = sy
            px_out = []
            while q:
                x, y = q.popleft()
                n += 1
                px_out.append((x, y))
                x0, x1 = min(x0, x), max(x1, x)
                y0, y1 = min(y0, y), max(y1, y)
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < w and 0 <= ny < h and px[nx, ny] and not seen[ny][nx]:
                        seen[ny][nx] = True
                        q.append((nx, ny))
            if n >= min_area:
                out.append((n, (x0, y0, x1 + 1, y1 + 1), px_out))
    out.sort(reverse=True)
    return out


def largest_component(mask):
    comps = components(mask)
    keep = Image.new('L', mask.size, 0)
    kp = keep.load()
    for x, y in (comps[0][2] if comps else []):
        kp[x, y] = 255
    return keep


def fill_holes(mask):
    """everything not reachable from the border through 'off' is inside (the gel body)."""
    w, h = mask.size
    px = mask.load()
    outside = [[False] * w for _ in range(h)]
    q = collections.deque()
    for x in range(w):
        for y in (0, h - 1):
            if not px[x, y] and not outside[y][x]:
                outside[y][x] = True
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if not px[x, y] and not outside[y][x]:
                outside[y][x] = True
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and not px[nx, ny] and not outside[ny][nx]:
                outside[ny][nx] = True
                q.append((nx, ny))
    solid = Image.new('L', (w, h), 0)
    sp = solid.load()
    for y in range(h):
        for x in range(w):
            if not outside[y][x]:
                sp[x, y] = 255
    return solid


def silhouette(im, close_k=5):
    V = val(im)
    m = V.point(lambda v: 255 if v > pct(V, 5) + THR_ABS else 0)
    m = m.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.MaxFilter(3))
    m = largest_component(m)
    if close_k > 1:
        m = m.filter(ImageFilter.MaxFilter(close_k)).filter(ImageFilter.MinFilter(close_k))
    return fill_holes(m)


def box_for(search, close_k=5, pad_frac=0.10):
    """tight box around the object found in `search`, plus its silhouette in that box."""
    found = silhouette(SHEET_IMG.crop(search), close_k)
    bb = found.getbbox()
    if bb is None:
        raise SystemExit('no object found in %s' % (search,))
    x0, y0 = bb[0] + search[0], bb[1] + search[1]
    x1, y1 = bb[2] + search[0], bb[3] + search[1]
    pw, ph = int(round((x1 - x0) * pad_frac)), int(round((y1 - y0) * pad_frac))
    box = (max(0, x0 - pw), max(0, y0 - ph), min(SHEET_IMG.width, x1 + pw), min(SHEET_IMG.height, y1 + ph))
    tile = SHEET_IMG.crop(box)
    return box, silhouette(tile, close_k)


def alpha_of(tile, solid, alpha_open, floor_cut=36, min_chroma=22, bright_v=120):
    """Alpha = the orb's own light, not the studio it was rendered in.

    The sheet paints the orb on a neutral studio background with a neutral drop shadow. Both are
    *colourless*; the orb's membrane, glow and specular are either saturated (cyan) or bright
    (white). So luminance only counts where the pixel is coloured OR bright — which drops the
    shadow and the sheet's background without touching the glow.
    """
    r, g, b = tile.split()
    V = val(tile)
    Mn = ImageChops.darker(ImageChops.darker(r, g), b)
    chroma = ImageChops.subtract(V, Mn)
    gate = ImageChops.lighter(chroma.point(lambda v: 255 if v > min_chroma else 0),
                              V.point(lambda v: 255 if v > bright_v else 0))
    bg = pct(V, 5)
    floor = bg + 2.0
    gain = 255.0 / max(1.0, 150.0 - floor)
    lum = V.point(lambda v: max(0, min(255, int(round((v - floor) * gain)))))
    lum = ImageChops.multiply(lum, gate)
    a = ImageChops.lighter(lum, solid.filter(ImageFilter.GaussianBlur(1.4)))
    if alpha_open > 1:
        a = a.filter(ImageFilter.MinFilter(alpha_open)).filter(ImageFilter.MaxFilter(alpha_open))
    if floor_cut > 0:
        a = a.point(lambda v: 0 if v <= floor_cut
                    else min(255, int(round((v - floor_cut) * 255.0 / (255.0 - floor_cut)))))
    return a.filter(ImageFilter.GaussianBlur(0.7))


def stray_islands(alpha, keep, min_area=30, feather=5):
    """bright pixels clearly outside the orb — the automated 'tile is annotation-free' gate.
    The orb's own feathered edge is excluded (the silhouette dilated by `feather`)."""
    w, h = alpha.size
    band = keep.filter(ImageFilter.MaxFilter(feather)) if feather > 1 else keep
    ap, kp = alpha.load(), band.load()
    m = Image.new('L', (w, h), 0)
    mp = m.load()
    for y in range(h):
        for x in range(w):
            if ap[x, y] > 40 and kp[x, y] == 0:
                mp[x, y] = 255
    return [(n, bb) for n, bb, _ in components(m, min_area)]


def render(name, box, solid, alpha_open, write):
    tile = SHEET_IMG.crop(box)
    if solid.size != tile.size:
        raise SystemExit('%s: silhouette %s does not match tile %s' % (name, solid.size, tile.size))
    alpha = alpha_of(tile, solid, alpha_open)
    r, g, b = tile.split()
    out = Image.merge('RGBA', (r, g, b, alpha))
    ob = solid.getbbox()
    ow, oh = ob[2] - ob[0], ob[3] - ob[1]
    ratio = max(ow, oh) / float(max(tile.size))
    strays = stray_islands(alpha, solid)
    print('%-18s tile=%-9s obj=%-9s ratio=%.4f alpha_px=%6d strays=%d %s'
          % (name, '%dx%d' % tile.size, '%dx%d' % (ow, oh), ratio, nz(alpha), len(strays),
             strays[:4] if strays else ''))
    master = out.resize((MASTER_PX, MASTER_PX), Image.LANCZOS)
    if write:
        dest = os.path.join(DRAWABLE, name + '.png')
        master.save(dest, optimize=True)
        print('%-18s -> %s (%d bytes)' % ('', dest, os.path.getsize(dest)))
    preview = os.path.join(os.environ.get('TEMP', '.'), name + '_on_blue.png')
    plate = Image.new('RGB', master.size, (32, 72, 160))
    plate.paste(master, (0, 0), master)
    plate.save(preview)
    print('%-18s preview %s' % ('', preview))
    return ratio


def main():
    global SHEET_IMG
    SHEET_IMG = Image.open(SHEET).convert('RGB')
    print('sheet %s %s' % (SHEET, SHEET_IMG.size))

    # THE Core (hero render: the largest, highest-detail object on the sheet)
    hero_box, hero_solid = box_for((250, 150, 700, 620), close_k=5, pad_frac=0.08)
    render('core_orb', hero_box, hero_solid, ALPHA_OPEN, write=True)

    # PAUSED: C1's state row draws the same orb twice at the same size (Normal x0=696, Paused
    # x0=957, both y 776..937), so the Normal silhouette IS the Paused silhouette; only the
    # luminance (its dimmer, receded energy) comes from the Paused render.
    norm_box, norm_solid = box_for((660, 740, 900, 985), close_k=5, pad_frac=0.14)
    render('core_orb_normal_diag', norm_box, norm_solid, 5, write=False)
    dx = 957 - 696
    pause_box = (norm_box[0] + dx, norm_box[1], norm_box[2] + dx, norm_box[3])
    render('core_orb_paused', pause_box, norm_solid, 5, write=True)


if __name__ == '__main__':
    main()

