#!/usr/bin/env python3
"""Turns full-resolution emulator captures into the README's images: JPEGs with a thin frame (a dark screenshot on
GitHub's dark theme otherwise has no visible edge) and animated WebPs (libwebp_anim: plain libwebp keeps only the first frame) from screen recordings, framed the same way.
Run from the repository root:
  python3 tally/art/make-readme-shots.py <captures dir>
The captures dir holds the 1920x1080 PNGs and MP4s named in SHOTS and CLIPS, and the phone's 1080x2400 (portrait) and
2400x1080 (landscape) PNGs named in PHONES; anything missing is skipped. Phone screenshots are set side by side on a
transparent background, each with the same thin frame, so the composite sits on GitHub's light and dark themes alike."""
import os
import subprocess
import sys

from PIL import Image, ImageDraw

OUT = "tally/readme/"
FRAME, FRAME_PX = (58, 61, 56), 2

# name -> output width. Full-width images get more pixels than the ones shown two to a row.
SHOTS = {
    "library": 1280, "film": 1280, "series": 1280, "album": 1280,
    "live": 1280, "multiview": 1280, "together": 1280, "surprise": 1280,
}
# name -> (ffmpeg video filter before the frame, fps, quality)
CLIPS = {
    # the dark backdrops band below about q 95; the tour stays under ~8 MB at 960 wide and 20 fps
    "tour": ("scale=960:-2:flags=lanczos", 20, 95),
    # the Sports page's focused-game panel, where the score rolls (crop found in the 1920x1080 recording)
    "score": ("crop=1628:504:218:148", 24, 92),
}
# composite name -> the phone captures in it, left to right, all scaled to one height
PHONES = {
    "phone": ["phone-home", "phone-film", "phone-sheet", "phone-music"],
    "phone-live": ["phone-games", "phone-player"],
}
PHONE_HEIGHT, PHONE_GAP, PHONE_WIDTH = 900, 36, 1720


def framed(im):
    d = ImageDraw.Draw(im)
    for i in range(FRAME_PX):
        d.rectangle([i, i, im.width - 1 - i, im.height - 1 - i], outline=FRAME)
    return im


def shot(src, name, width):
    im = Image.open(src).convert("RGB")
    im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    framed(im).save(f"{OUT}{name}.jpg", quality=86, optimize=True, progressive=True)
    print(f"{OUT}{name}.jpg", os.path.getsize(f"{OUT}{name}.jpg") // 1024, "KB")


def clip(src, name, pre, fps, quality):
    t = FRAME_PX
    color = "0x%02X%02X%02X" % FRAME
    chain = ",".join(f for f in [pre, f"fps={fps}", f"drawbox=x=0:y=0:w=iw:h=ih:color={color}:t={t}"] if f)
    out = f"{OUT}{name}.webp"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-vf", chain, "-vcodec", "libwebp_anim", "-lossless", "0",
         "-q:v", str(quality), "-compression_level", "6", "-loop", "0", "-an", out],
        check=True,
    )
    print(out, os.path.getsize(out) // 1024, "KB")


def phones(src, name, captures):
    ims = [Image.open(os.path.join(src, c + ".png")).convert("RGB") for c in captures]
    ims = [framed(im.resize((round(im.width * PHONE_HEIGHT / im.height), PHONE_HEIGHT), Image.LANCZOS)) for im in ims]
    sheet = Image.new("RGBA", (sum(im.width for im in ims) + PHONE_GAP * (len(ims) - 1), PHONE_HEIGHT), (0, 0, 0, 0))
    x = 0
    for im in ims:
        sheet.paste(im, (x, 0))
        x += im.width + PHONE_GAP
    # no wider than the other full-width images need
    if sheet.width > PHONE_WIDTH:
        sheet = sheet.resize((PHONE_WIDTH, round(sheet.height * PHONE_WIDTH / sheet.width)), Image.LANCZOS)
    sheet.save(f"{OUT}{name}.webp", quality=88, method=6)
    print(f"{OUT}{name}.webp", sheet.size, os.path.getsize(f"{OUT}{name}.webp") // 1024, "KB")


def main():
    src = sys.argv[1]
    for name, width in SHOTS.items():
        path = os.path.join(src, name + ".png")
        if os.path.exists(path):
            shot(path, name, width)
    for name, captures in PHONES.items():
        if all(os.path.exists(os.path.join(src, c + ".png")) for c in captures):
            phones(src, name, captures)
    for name, (pre, fps, quality) in CLIPS.items():
        # a trimmed or cropped cut of a recording, if one was made, wins over the raw recording
        for candidate in (name + "-cut.mp4", name + ".mp4"):
            path = os.path.join(src, candidate)
            if os.path.exists(path):
                clip(path, name, pre, fps, quality)
                break


if __name__ == "__main__":
    main()
