"""Generate all DOWNI launcher icon densities + splash + web assets from one logo PNG.

Usage: python tools/generate_icons.py <path-to-logo.png>
Logo should be square, 1024x1024+, transparent background.
Output filenames are kept stable (omni_app_icon.png etc.) so manifest references stay valid.
"""
import sys
import os
from PIL import Image

if len(sys.argv) < 2:
    print("Usage: python tools/generate_icons.py <path-to-logo.png>")
    sys.exit(1)

source_logo = sys.argv[1]
base_res = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "android", "app", "src", "main", "res")
www_assets = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "www", "assets")
os.makedirs(www_assets, exist_ok=True)

img = Image.open(source_logo).convert("RGBA")
if img.width != img.height:
    side = min(img.width, img.height)
    left = (img.width - side) // 2
    top = (img.height - side) // 2
    img = img.crop((left, top, left + side, top + side))

# Sizes for mipmap
densities = {
    "mipmap-mdpi": (48, 48),
    "mipmap-hdpi": (72, 72),
    "mipmap-xhdpi": (96, 96),
    "mipmap-xxhdpi": (144, 144),
    "mipmap-xxxhdpi": (192, 192)
}

# Adaptive icon foreground canvas (108dp) per density; glyph stays in the 66% safe zone
fg_densities = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432
}

for folder, size in densities.items():
    target_dir = os.path.join(base_res, folder)
    os.makedirs(target_dir, exist_ok=True)
    resized = img.resize(size, Image.Resampling.LANCZOS)
    resized.save(os.path.join(target_dir, "ic_launcher.png"), "PNG")
    resized.save(os.path.join(target_dir, "ic_launcher_round.png"), "PNG")
    resized.save(os.path.join(target_dir, "omni_app_icon.png"), "PNG")

for folder, canvas in fg_densities.items():
    target_dir = os.path.join(base_res, folder)
    os.makedirs(target_dir, exist_ok=True)
    fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    glyph = img.resize((int(canvas * 0.62), int(canvas * 0.62)), Image.Resampling.LANCZOS)
    offset = (canvas - glyph.width) // 2
    fg.paste(glyph, (offset, offset), glyph)
    fg.save(os.path.join(target_dir, "ic_launcher_foreground.png"), "PNG")

# Save 512x512 high res for drawable & www
res_512 = img.resize((512, 512), Image.Resampling.LANCZOS)
res_512.save(os.path.join(base_res, "drawable", "omni_app_icon.png"), "PNG")
res_512.save(os.path.join(base_res, "drawable", "splash.png"), "PNG")
res_512.save(os.path.join(www_assets, "logo.png"), "PNG")
res_128 = img.resize((128, 128), Image.Resampling.LANCZOS)
res_128.save(os.path.join(www_assets, "icon.png"), "PNG")

# Regenerate orientation splash screens: keep each existing file's dimensions,
# fill with black, center the new logo at ~55% width
for sub in os.listdir(base_res):
    if not (sub.startswith("drawable-land-") or sub.startswith("drawable-port-")):
        continue
    splash_path = os.path.join(base_res, sub, "splash.png")
    if not os.path.exists(splash_path):
        continue
    with Image.open(splash_path) as old:
        w, h = old.size
    canvas = Image.new("RGB", (w, h), (0, 0, 0))
    target_w = int(w * 0.55)
    target_h = int(target_w * img.height / img.width)
    if target_h > h * 0.7:
        target_h = int(h * 0.7)
        target_w = int(target_h * img.width / img.height)
    logo = img.resize((target_w, target_h), Image.Resampling.LANCZOS)
    canvas.paste(logo, ((w - target_w) // 2, (h - target_h) // 2), logo)
    canvas.save(splash_path, "PNG")

print("Successfully generated all DOWNI app icon densities, adaptive foregrounds, splashes and web logo assets!")
