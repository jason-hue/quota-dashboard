from PIL import Image, ImageDraw, ImageFont
import os

BASE = os.path.join(os.path.dirname(__file__), "android", "app", "src", "main", "res")

# (density, launcher size, round size)
DENSITIES = [
    ("mdpi", 48, 48),
    ("hdpi", 72, 72),
    ("xhdpi", 96, 96),
    ("xxhdpi", 144, 144),
    ("xxxhdpi", 192, 192),
]

def gradient(size):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x / size * 0.6 + y / size * 0.4)
            r = int(91 + (106 - 91) * t)
            g = int(141 + (92 - 141) * t)
            b = int(239 + (245 - 239) * t)
            px[x, y] = (r, g, b)
    return img

def font_for(size):
    candidates = [
        "C:/Windows/Fonts/segoeuib.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    ]
    for c in candidates:
        if os.path.exists(c):
            return ImageFont.truetype(c, int(size * 0.58))
    return ImageFont.load_default()

def make_icon(size, round_mask=False):
    img = gradient(size).convert("RGBA")
    d = ImageDraw.Draw(img)
    f = font_for(size)
    text = "Q"
    box = d.textbbox((0, 0), text, font=f)
    tw, th = box[2] - box[0], box[3] - box[1]
    d.text(((size - tw) / 2 - box[0], (size - th) / 2 - box[1] - size * 0.02), text, font=f, fill=(255, 255, 255, 255))
    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        img.putalpha(mask)
    return img

for density, launcher, _ in DENSITIES:
    folder = os.path.join(BASE, "mipmap-" + density)
    os.makedirs(folder, exist_ok=True)
    make_icon(launcher, False).save(os.path.join(folder, "ic_launcher.png"))
    make_icon(launcher, True).save(os.path.join(folder, "ic_launcher_round.png"))
    print(density, launcher)

# adaptive icon foreground (108dp)
for density, size in [("xxxhdpi", 432), ("xxhdpi", 324), ("xhdpi", 216), ("hdpi", 162), ("mdpi", 108)]:
    folder = os.path.join(BASE, "mipmap-" + density)
    os.makedirs(folder, exist_ok=True)
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = make_icon(int(size * 0.62), False)
    fg.alpha_composite(inner, (int(size * 0.19), int(size * 0.19)))
    fg.save(os.path.join(folder, "ic_launcher_foreground.png"))

print("icons done")
