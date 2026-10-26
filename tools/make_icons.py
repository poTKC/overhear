#!/usr/bin/env python3
"""Draws the player's PNG icons into app/res/drawable-mdpi (API 18 has no
VectorDrawable). Needs Pillow. Run from the repo root: python3 -I tools/make_icons.py"""
import math
import os
import sys

from PIL import Image, ImageDraw

OUT = os.path.join("app", "res", "drawable-mdpi")
SS = 8  # supersampling
WHITE = (255, 255, 255, 255)


def canvas(size):
    img = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def save(img, size, name):
    img.resize((size, size), Image.LANCZOS).save(os.path.join(OUT, name + ".png"), optimize=True)


def arrow_head(d, tip, direction, length, width):
    """Filled triangle pointing along direction (radians) with its tip at tip."""
    x, y = tip
    bx = x - length * math.cos(direction)
    by = y - length * math.sin(direction)
    nx, ny = -math.sin(direction) * width / 2, math.cos(direction) * width / 2
    d.polygon([(x, y), (bx + nx, by + ny), (bx - nx, by - ny)], fill=WHITE)


def shuffle(size=32):
    img, d = canvas(size)
    s = SS
    w = int(2.4 * s)
    # Two crossing paths, left to right, with arrow heads on the right.
    pts1 = [(5 * s, 9 * s), (11 * s, 9 * s), (20 * s, 23 * s), (24 * s, 23 * s)]
    pts2 = [(5 * s, 23 * s), (11 * s, 23 * s), (20 * s, 9 * s), (24 * s, 9 * s)]
    d.line(pts1, fill=WHITE, width=w, joint="curve")
    d.line(pts2, fill=WHITE, width=w, joint="curve")
    arrow_head(d, (29 * s, 9 * s), 0, 7 * s, 9 * s)
    arrow_head(d, (29 * s, 23 * s), 0, 7 * s, 9 * s)
    save(img, size, "ic_shuffle")


def repeat(one=False, size=32):
    img, d = canvas(size)
    s = SS
    w = int(2.4 * s)
    # A rounded loop: top line going right, bottom line going left.
    d.rounded_rectangle([5 * s, 9 * s, 27 * s, 24 * s], radius=5 * s, outline=WHITE, width=w)
    # Gaps for the arrow heads.
    d.rectangle([17 * s, 6 * s, 23 * s, 12 * s], fill=(0, 0, 0, 0))
    d.rectangle([9 * s, 21 * s, 15 * s, 27 * s], fill=(0, 0, 0, 0))
    arrow_head(d, (23 * s, 9 * s + w // 2 - s), 0, 6 * s, 8 * s)
    arrow_head(d, (9 * s, 24 * s - w // 2 + s), math.pi, 6 * s, 8 * s)
    if one:
        # A small "1" in the middle.
        d.line([(15 * s, 14 * s), (17 * s, 12.5 * s), (17 * s, 20 * s)], fill=WHITE, width=int(2 * s))
    save(img, size, "ic_repeat_one" if one else "ic_repeat")


def queue(size=32):
    img, d = canvas(size)
    s = SS
    w = int(2.4 * s)
    for y in (9, 15, 21):
        d.line([(5 * s, y * s), (19 * s, y * s)], fill=WHITE, width=w)
    d.polygon([(22 * s, 16 * s), (22 * s, 26 * s), (29 * s, 21 * s)], fill=WHITE)
    save(img, size, "ic_queue")


def placeholder(size=240):
    img, d = canvas(size)
    s = SS
    d.rectangle([0, 0, size * s, size * s], fill=(40, 40, 40, 255))
    note = (90, 90, 90, 255)
    # Two eighth notes joined by a beam.
    d.ellipse([78 * s, 140 * s, 108 * s, 162 * s], fill=note)
    d.ellipse([138 * s, 128 * s, 168 * s, 150 * s], fill=note)
    d.rectangle([102 * s, 82 * s, 108 * s, 152 * s], fill=note)
    d.rectangle([162 * s, 70 * s, 168 * s, 140 * s], fill=note)
    d.polygon([(102 * s, 82 * s), (168 * s, 70 * s), (168 * s, 84 * s), (102 * s, 96 * s)], fill=note)
    save(img, size, "art_placeholder")


def main():
    if not os.path.isdir(OUT):
        sys.exit("run from the repo root")
    shuffle()
    repeat()
    repeat(one=True)
    queue()
    placeholder()


main()
