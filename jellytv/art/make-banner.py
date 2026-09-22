#!/usr/bin/env python3
"""Renders the Android TV launcher banner (xhdpi 320x180) and its adaptive foreground for the app's name.

The look matches the rest of the brand: near-black ground, an amber rule along the top, an amber square (the tally
light) and the name in IBM Plex Sans Bold, uppercase, widely tracked. Run from the repository root:
    python3 jellytv/art/make-banner.py TALLY
"""
import sys
from PIL import Image, ImageDraw, ImageFont
import numpy as np

NAME = (sys.argv[1] if len(sys.argv) > 1 else "TALLY").upper()
W, H = 320, 180
GROUND, AMBER, TEXT = (0x0E, 0x0F, 0x0E, 255), (0xFF, 0xB0, 0x00, 255), (0xE3, 0xE5, 0xDE, 255)
FONT = "app/src/main/res/font/ibm_plex_sans_bold.ttf"
RES = "app/src/main/res/mipmap-xhdpi/"
CAP_TOP, CAP_BOTTOM, TEXT_X, TRACKING = 77, 105, 58, 0.30  # tracking as a fraction of the font size

def rule_height() -> int:
    """The top rule of the existing banner, so the new one matches it exactly."""
    im = np.array(Image.open(RES + "ic_banner.png").convert("RGB")).astype(int)
    amber = (im[:, :, 0] > 200) & (im[:, :, 1] > 140) & (im[:, :, 1] < 200) & (im[:, :, 2] < 60)
    rows = [y for y in range(40) if amber[y].sum() > W * 0.9]
    return (max(rows) + 1) if rows else 4

def font_for_cap_height(px: int) -> ImageFont.FreeTypeFont:
    for size in range(20, 80):
        f = ImageFont.truetype(FONT, size)
        l, t, r, b = f.getbbox("H")
        if b - t >= px:
            return f
    return ImageFont.truetype(FONT, 42)

def draw(canvas: Image.Image, with_rule: bool, rule: int) -> None:
    d = ImageDraw.Draw(canvas)
    if with_rule:
        d.rectangle([0, 0, W - 1, rule - 1], fill=AMBER)
    d.rectangle([24, 83, 38, 96], fill=AMBER)
    f = font_for_cap_height(CAP_BOTTOM - CAP_TOP + 1)
    top_offset = f.getbbox("H")[1]
    x = TEXT_X
    for ch in NAME:
        d.text((x, CAP_TOP - top_offset), ch, font=f, fill=TEXT)
        x += f.getlength(ch) + f.size * TRACKING

rule = rule_height()
banner = Image.new("RGBA", (W, H), GROUND)
draw(banner, True, rule)
banner.save(RES + "ic_banner.png")
fg = Image.new("RGBA", (W, H), (0, 0, 0, 0))
draw(fg, False, rule)
fg.save(RES + "ic_banner_foreground.png")
print(f"banner for {NAME}: rule {rule}px")
