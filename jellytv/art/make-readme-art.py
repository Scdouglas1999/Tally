#!/usr/bin/env python3
"""Renders the README header (1600x440) and the GitHub social preview (1280x640) in the Tally brand system:
near-black ground, an amber rule along the top, the amber tally square, the name in IBM Plex Sans Bold (tracked),
and a mono tagline. Run from the repository root: python3 jellytv/art/make-readme-art.py"""
from PIL import Image, ImageDraw, ImageFont

GROUND, AMBER, TEXT, MUTED, RULE = (14, 15, 14), (255, 176, 0), (227, 229, 222), (139, 144, 132), (42, 44, 42)
SANS = "app/src/main/res/font/ibm_plex_sans_bold.ttf"
MONO = "app/src/main/res/font/ibm_plex_mono_medium.ttf"
OUT = "jellytv/readme/"


def tracked(draw, xy, text, font, fill, tracking):
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill)
        x += font.getlength(ch) + tracking
    return x


def width(text, font, tracking):
    return sum(font.getlength(c) for c in text) + tracking * (len(text) - 1)


def art(w, h, cap, tagline, sub, out, tag_scale=0.26, tag_track=0.07):
    im = Image.new("RGB", (w, h), GROUND)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, w - 1, max(4, h // 90) - 1], fill=AMBER)
    sans = ImageFont.truetype(SANS, cap)
    track = cap * 0.30
    name_w = width("TALLY", sans, track)
    sq = int(cap * 0.52)
    gap = int(cap * 0.62)
    total = sq + gap + name_w
    x0 = (w - total) / 2
    cap_top = h * 0.36
    top_off = sans.getbbox("H")[1]
    cap_h = sans.getbbox("H")[3] - top_off
    d.rectangle([x0, cap_top + (cap_h - sq) / 2, x0 + sq - 1, cap_top + (cap_h + sq) / 2 - 1], fill=AMBER)
    tracked(d, (x0 + sq + gap, cap_top - top_off), "TALLY", sans, TEXT, track)
    mono = ImageFont.truetype(MONO, int(cap * tag_scale))
    mtrack = cap * tag_track
    y = cap_top + cap_h + cap * 0.62
    tw = width(tagline, mono, mtrack)
    tracked(d, ((w - tw) / 2, y), tagline, mono, MUTED, mtrack)
    if sub:
        small = ImageFont.truetype(MONO, int(cap * 0.12))
        sw = width(sub, small, cap * 0.035)
        line_y = y + cap * 0.55
        d.line([(w * 0.35, line_y), (w * 0.65, line_y)], fill=RULE, width=2)
        tracked(d, ((w - sw) / 2, line_y + cap * 0.3), sub, small, MUTED, cap * 0.035)
    im.save(out, optimize=True)


art(1600, 440, 132, "LIVE SPORTS  ·  YOUR LIBRARY  ·  WATCH TOGETHER", None, OUT + "header.png")
art(1280, 640, 150, "LIVE SPORTS  ·  YOUR LIBRARY  ·  WATCH TOGETHER", "A JELLYFIN CLIENT FOR ANDROID TV",
    OUT + "social-preview.png", tag_scale=0.155, tag_track=0.035)
print("ok")
