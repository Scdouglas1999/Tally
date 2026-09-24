#!/usr/bin/env python3
"""Draws the TV app icons from Tally's launcher art (app/src/main/res/mipmap-*/ic_launcher: a monitor outline with a
black label bar and the amber tally light, on the near-black ground), at the sizes the TV stores ask for:
  shell/icons/icon-512.png   Tizen config.xml icon (512x512)
  shell/icons/icon-80.png    webOS appinfo.json icon
  shell/icons/icon-130.png   webOS appinfo.json largeIcon
Drawn as vectors (not an upscale of the 192px launcher), proportions measured from it. Run from tv-web/:
    python3 scripts/make-icons.py
"""
from PIL import Image, ImageDraw

GROUND, TEXT, AMBER, BAR, RULE = (0x0E, 0x0F, 0x0E), (0xE3, 0xE5, 0xDE), (0xFF, 0xB0, 0x00), (0x00, 0x00, 0x00), (0x3A, 0x3D, 0x38)


def icon(size: int) -> Image.Image:
    s = size / 384.0  # proportions measured on the launcher drawn at 384px
    scale = 4  # draw big, then downsample: clean edges without anti-aliasing primitives
    big = Image.new("RGB", (size * scale, size * scale), GROUND)
    d = ImageDraw.Draw(big)
    k = s * scale
    left, top, right, bottom, stroke = 64 * k, 108 * k, 320 * k, 276 * k, 13 * k
    d.rectangle([left, top, right, bottom], fill=TEXT)
    d.rectangle([left + stroke, top + stroke, right - stroke, bottom - stroke], fill=GROUND)
    bar_top = 232 * k
    d.rectangle([left + stroke, bar_top, right - stroke, bottom - stroke], fill=BAR)
    d.rectangle([left + stroke, bar_top, right - stroke, bar_top + 2 * k], fill=RULE)
    d.rectangle([88 * k, 238 * k, 106 * k, 256 * k], fill=AMBER)
    return big.resize((size, size), Image.LANCZOS)


for size, name in [(512, "icon-512.png"), (80, "icon-80.png"), (130, "icon-130.png")]:
    icon(size).save(f"shell/icons/{name}", optimize=True)
    print("wrote shell/icons/" + name)
