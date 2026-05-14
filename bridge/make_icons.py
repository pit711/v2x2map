"""
Generates ICO files for its-g5-bridge.exe and its-g5-setup.exe.
Run before building:  python make_icons.py
Requires Pillow:      pip install pillow
"""
import math
import os
from PIL import Image, ImageDraw


# ---------------------------------------------------------------------------
# Bridge icon — dark blue circle with WiFi/antenna waves
# ---------------------------------------------------------------------------
def make_bridge_icon(size: int = 256) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d   = ImageDraw.Draw(img)

    # Outer circle
    p = int(size * 0.04)
    d.ellipse([p, p, size - p, size - p], fill=(21, 101, 192, 255))

    cx  = size / 2
    cy  = size * 0.60          # arc origin slightly below centre
    lw  = max(4, int(size * 0.055))

    # Three concentric WiFi arcs (opening downward)
    for i, r_frac in enumerate([0.16, 0.28, 0.40]):
        rp = r_frac * size
        alpha = max(120, 255 - i * 40)
        d.arc([cx - rp, cy - rp, cx + rp, cy + rp],
              start=205, end=335,
              fill=(255, 255, 255, alpha), width=lw)

    # Centre dot
    dr = int(size * 0.048)
    d.ellipse([cx - dr, cy - dr, cx + dr, cy + dr],
              fill=(255, 255, 255, 255))

    # Mast line from dot upward
    mw = max(3, int(size * 0.03))
    d.rectangle([cx - mw // 2, cy - size * 0.36,
                 cx + mw // 2, cy - dr],
                fill=(255, 255, 255, 230))

    return img


# ---------------------------------------------------------------------------
# Setup icon — slate circle with amber gear
# ---------------------------------------------------------------------------
def make_setup_icon(size: int = 256) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d   = ImageDraw.Draw(img)

    # Outer circle
    p = int(size * 0.04)
    d.ellipse([p, p, size - p, size - p], fill=(55, 71, 79, 255))

    cx, cy = size / 2, size / 2
    teeth  = 8
    r_out  = size * 0.34
    r_in   = size * 0.25
    t_half = 0.18            # half angular width of tooth

    pts = []
    for i in range(teeth):
        base = i * 2 * math.pi / teeth
        mid  = base + math.pi / teeth
        # leading edge of tooth
        pts += [(cx + r_out * math.cos(base - t_half),
                 cy + r_out * math.sin(base - t_half)),
                (cx + r_out * math.cos(base + t_half),
                 cy + r_out * math.sin(base + t_half))]
        # valley between teeth
        pts += [(cx + r_in * math.cos(mid - t_half),
                 cy + r_in * math.sin(mid - t_half)),
                (cx + r_in * math.cos(mid + t_half),
                 cy + r_in * math.sin(mid + t_half))]

    d.polygon(pts, fill=(255, 193, 7, 255))   # amber gear

    # Inner hole
    hr = int(size * 0.13)
    d.ellipse([cx - hr, cy - hr, cx + hr, cy + hr],
              fill=(55, 71, 79, 255))

    # Small centre highlight
    hl = int(size * 0.055)
    d.ellipse([cx - hl, cy - hl, cx + hl, cy + hl],
              fill=(255, 224, 102, 255))

    return img


# ---------------------------------------------------------------------------
# Save as multi-size ICO
# ---------------------------------------------------------------------------
SIZES = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]

def save_ico(img: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    img.save(path, format="ICO", sizes=SIZES)
    kb = os.path.getsize(path) // 1024
    print(f"  {path}  ({kb} KB)")


if __name__ == "__main__":
    print("Generating icons ...")
    save_ico(make_bridge_icon(256), "icons/its-g5-bridge.ico")
    save_ico(make_setup_icon(256),  "icons/its-g5-setup.ico")
    print("Done.")
