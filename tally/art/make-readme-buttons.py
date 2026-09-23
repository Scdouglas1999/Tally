#!/usr/bin/env python3
"""Renders the README's buttons as SVG, in the app's button style: square, a 1.5px frame, mono tracked caps.
The primary one is filled amber, like a focused button in the app. The lettering is converted to outlines (IBM Plex
Mono SemiBold), because GitHub shows SVGs as images and an image cannot load a font.
Run from the repository root: python3 tally/art/make-readme-buttons.py"""
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

MONO = "app/src/main/res/font/ibm_plex_mono_semibold.ttf"
OUT = "tally/readme/"
AMBER, GROUND, TEXT, FRAME = "#FFB000", "#0E0F0E", "#E3E5DE", "#4A4D47"
SIZE, TRACK, HEIGHT, PAD, STROKE = 15, 0.16, 48, 26, 1.5


def text_path(font, text, size, tracking, x0, baseline):
    """SVG path data for text set at `size` px with `tracking` em of extra space between letters."""
    glyphs, cmap, hmtx = font.getGlyphSet(), font.getBestCmap(), font["hmtx"]
    scale = size / font["head"].unitsPerEm
    pen = SVGPathPen(glyphs)
    x = x0
    for ch in text:
        name = cmap[ord(ch)]
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
        x += hmtx[name][0] * scale + tracking * size
    return pen.getCommands(), x - tracking * size - x0


def button(name, label, fill, ink, frame):
    font = TTFont(MONO)
    cap = font["OS/2"].sCapHeight * SIZE / font["head"].unitsPerEm
    _, w = text_path(font, label, SIZE, TRACK, 0, 0)
    width = round(w + 2 * PAD)
    d, _ = text_path(font, label, SIZE, TRACK, PAD, (HEIGHT + cap) / 2)
    inset = STROKE / 2
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{HEIGHT}" viewBox="0 0 {width} {HEIGHT}" '
        f'role="img" aria-label="{label.title()}">'
        f'<title>{label.title()}</title>'
        f'<rect x="{inset}" y="{inset}" width="{width - STROKE}" height="{HEIGHT - STROKE}" fill="{fill}" '
        f'stroke="{frame}" stroke-width="{STROKE}"/>'
        f'<path d="{d}" fill="{ink}"/></svg>\n'
    )
    with open(OUT + name, "w") as f:
        f.write(svg)
    print(OUT + name, width, "x", HEIGHT)


button("button-download.svg", "DOWNLOAD FOR ANDROID TV", AMBER, GROUND, AMBER)
button("button-install.svg", "HOW TO INSTALL", GROUND, TEXT, FRAME)
button("button-releases.svg", "WHAT'S NEW", GROUND, TEXT, FRAME)
