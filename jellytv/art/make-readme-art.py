#!/usr/bin/env python3
"""Renders the README header (1600x440) and the GitHub social preview (1280x640) in the Tally brand system:
near-black ground, an amber rule along the top, the amber tally square, the name in IBM Plex Sans Bold (tracked),
and a mono tagline. Run from the repository root: python3 jellytv/art/make-readme-art.py

The header is an animated PNG that plays once: the tally light is off, flickers on like a lamp warming up and
settles with a soft glow, then the top rule sweeps across ("on air"). Its default image is the final frame, so a
viewer that cannot animate APNG shows the light on."""
from PIL import Image, ImageDraw, ImageFilter, ImageFont

GROUND, AMBER, TEXT, MUTED, RULE = (14, 15, 14), (255, 176, 0), (227, 229, 222), (139, 144, 132), (42, 44, 42)
LAMP_OFF = (40, 35, 26)  # unlit tally glass
GLOW_ALPHA = 0.30
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


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def art(w, h, cap, tagline, sub, out=None, tag_scale=0.26, tag_track=0.07, lamp=1.0, glow=1.0, rule=1.0):
    """lamp: 0 off .. 1 fully lit; glow: 0..1 strength of the halo; rule: 0..1 share of the top rule drawn."""
    im = Image.new("RGB", (w, h), GROUND)
    d = ImageDraw.Draw(im)
    if rule > 0:
        d.rectangle([0, 0, max(1, round(w * rule)) - 1, max(4, h // 90) - 1], fill=AMBER)
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
    box = [x0, cap_top + (cap_h - sq) / 2, x0 + sq - 1, cap_top + (cap_h + sq) / 2 - 1]
    if glow > 0:
        halo = Image.new("L", (w, h), 0)
        pad = sq * 0.18
        ImageDraw.Draw(halo).rectangle([box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad], fill=255)
        halo = halo.filter(ImageFilter.GaussianBlur(sq * 0.45)).point(lambda v: round(v * GLOW_ALPHA * glow))
        im.paste(Image.new("RGB", (w, h), AMBER), (0, 0), halo)
        d = ImageDraw.Draw(im)
    d.rectangle(box, fill=mix(LAMP_OFF, AMBER, lamp))
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
    if out:
        im.save(out, optimize=True)
    return im


def ease_out(t):
    return 1 - (1 - t) ** 3


def header_animation(out):
    """(lamp, glow, rule, milliseconds) per frame: dark, two flickers, a warm-up, then the rule sweeps."""
    steps = [(0, 0, 0, 900), (0.55, 0.15, 0, 60), (0, 0, 0, 90), (0.85, 0.3, 0, 50), (0.1, 0, 0, 140)]
    steps += [(ease_out(i / 6), ease_out(i / 6) * 0.7, 0, 30) for i in range(1, 7)]
    steps += [(1, 0.7 + 0.3 * ease_out(i / 8), ease_out(i / 8), 33) for i in range(1, 9)]
    steps[-1] = (1, 1, 1, 1000)
    args = (1600, 440, 132, "LIVE SPORTS  ·  YOUR LIBRARY  ·  WATCH TOGETHER", None)
    final = art(*args)
    frames = [art(*args, lamp=l, glow=g, rule=r) for l, g, r, _ in steps]
    final.save(out, save_all=True, append_images=frames, default_image=True, loop=1,
               duration=[0] + [ms for *_, ms in steps], optimize=True)


header_animation(OUT + "header.png")
art(1280, 640, 150, "LIVE SPORTS  ·  YOUR LIBRARY  ·  WATCH TOGETHER", "A JELLYFIN CLIENT FOR ANDROID TV",
    OUT + "social-preview.png", tag_scale=0.155, tag_track=0.035)
print("ok")
