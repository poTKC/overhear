#!/usr/bin/env python3
"""Makes the launcher icon, app/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi}/ic_launcher.png
(48, 72, 96 and 144 px; API 18 has no adaptive icons), from an SVG authored
here and rasterised with rsvg-convert at each size (no downscaling).

"Overhear": music you hear from a nearby device. Every variant is a flat
rounded-square tile with a white glyph and one warm accent, and avoids
anything like Spotify's mark (no green, no circle badge, no three stacked
horizontal arcs).

  phone  (default) a small old phone with an amber speaker sending two
         vertical sound arcs to the right: the clearest at 48 px
  waves  a warm dot sending two vertical sound arcs to the right
  ear    an ear catching a coral sound arc

Usage, from the repo root:
  python3 -I tools/make_app_icon.py                   # write the default variant
  python3 -I tools/make_app_icon.py --variant phone   # write another variant
  python3 -I tools/make_app_icon.py --sheet OUT.png   # contact sheet of all variants
                                                      # at 192 and 48 px (writes no icon)

Needs rsvg-convert (brew install librsvg) and Pillow. The PNGs are
committed, so a normal build needs none of this.
"""
import argparse
import io
import math
import os
import subprocess

from PIL import Image, ImageDraw

DEFAULT = "phone"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144}

WHITE = "#ffffff"
CHARCOAL = "#1f2329"
CHARCOAL_TOP = "#2b3038"
INDIGO = "#262a52"
INDIGO_TOP = "#323769"
AMBER = "#ffb02e"
CORAL = "#ff6f59"

# Everything is drawn on a 192-unit canvas. The tile leaves a small margin,
# as Android's legacy icon guidance does (about 1 dp at 48 dp).
TILE = '''<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
  <stop offset="0" stop-color="{top}"/><stop offset="1" stop-color="{bottom}"/></linearGradient></defs>
<rect x="8" y="8" width="176" height="176" rx="40" fill="url(#g)"/>'''


def arc(cx, cy, r, deg):
    """An arc of radius r around (cx, cy), opening to the right, +-deg from horizontal."""
    a = math.radians(deg)
    x = cx + r * math.cos(a)
    return "M %.2f %.2f A %g %g 0 0 1 %.2f %.2f" % (x, cy - r * math.sin(a), r, r, x, cy + r * math.sin(a))


def stroke(d, color, width):
    return '<path d="%s" fill="none" stroke="%s" stroke-width="%g" stroke-linecap="round" stroke-linejoin="round"/>' % (
        d, color, width)


def waves():
    cx, cy = 66, 96
    return "".join([
        TILE.format(top=CHARCOAL_TOP, bottom=CHARCOAL),
        '<circle cx="%d" cy="%d" r="17" fill="%s"/>' % (cx, cy, AMBER),
        stroke(arc(cx, cy, 44, 48), WHITE, 14),
        stroke(arc(cx, cy, 74, 46), WHITE, 14),
    ])


def ear():
    # The outer rim (helix) curls from the lobe up over the top and down the
    # back; the inner fold is a short hook. A coral arc to the left is the
    # sound coming in.
    helix = "M 84 140 C 92 152, 112 150, 116 132 C 119 118, 136 110, 138 88 C 141 58, 116 40, 92 44 C 70 48, 60 66, 62 84"
    fold = "M 84 86 C 84 72, 104 70, 108 84 C 111 96, 98 100, 96 112"
    return "".join([
        TILE.format(top=INDIGO_TOP, bottom=INDIGO),
        stroke(helix, WHITE, 14),
        stroke(fold, WHITE, 12),
        stroke("M 44 102 A 30 30 0 0 0 44 136", CORAL, 12),
    ])


def phone():
    cx, cy = 70, 100
    return "".join([
        TILE.format(top=CHARCOAL_TOP, bottom=CHARCOAL),
        '<rect x="44" y="52" width="52" height="92" rx="12" fill="none" stroke="%s" stroke-width="10"/>' % WHITE,
        '<circle cx="%d" cy="%d" r="13" fill="%s"/>' % (cx, cy, AMBER),
        '<rect x="62" y="64" width="16" height="6" rx="3" fill="%s"/>' % WHITE,
        stroke(arc(cx, cy, 50, 34), WHITE, 11),
        stroke(arc(cx, cy, 74, 32), WHITE, 11),
    ])


VARIANTS = {"waves": waves, "ear": ear, "phone": phone}


def svg(variant):
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="192" height="192" viewBox="0 0 192 192">%s</svg>'
            % VARIANTS[variant]())


def render(variant, px):
    png = subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px)], input=svg(variant).encode("utf-8"),
                         stdout=subprocess.PIPE, check=True).stdout
    return Image.open(io.BytesIO(png)).convert("RGBA")


def write_icons(variant):
    for density, px in SIZES.items():
        d = os.path.join("app", "res", "drawable-" + density)
        os.makedirs(d, exist_ok=True)
        render(variant, px).save(os.path.join(d, "ic_launcher.png"), optimize=True)
        print("wrote %s/ic_launcher.png (%s, %d px)" % (d, variant, px))


def sheet(path):
    """Each variant at 192 px and at 48 px (actual size and 3x nearest-neighbour
    zoom), on a dark and a light launcher background."""
    names = list(VARIANTS)
    cell_w, cell_h = 420, 300
    img = Image.new("RGBA", (cell_w * len(names), cell_h * 2), (0, 0, 0, 255))
    draw = ImageDraw.Draw(img)
    for row, bg in enumerate([(18, 18, 18, 255), (236, 236, 236, 255)]):
        fg = (230, 230, 230, 255) if row == 0 else (40, 40, 40, 255)
        for col, name in enumerate(names):
            x0, y0 = col * cell_w, row * cell_h
            draw.rectangle((x0, y0, x0 + cell_w - 1, y0 + cell_h - 1), fill=bg)
            big, small = render(name, 192), render(name, 48)
            img.alpha_composite(big, (x0 + 20, y0 + 40))
            img.alpha_composite(small, (x0 + 230, y0 + 40))
            img.alpha_composite(small.resize((144, 144), Image.NEAREST), (x0 + 230, y0 + 96))
            draw.text((x0 + 20, y0 + 12), "%s%s" % (name, " (default)" if name == DEFAULT else ""), fill=fg)
            draw.text((x0 + 284, y0 + 52), "48 px", fill=fg)
            draw.text((x0 + 230, y0 + 246), "48 px, 3x zoom", fill=fg)
    img.save(path)
    print("wrote", path)


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--variant", choices=sorted(VARIANTS), default=DEFAULT)
    p.add_argument("--sheet", metavar="PNG", help="write a contact sheet of every variant instead")
    p.add_argument("--svg", action="store_true", help="print the variant's SVG instead")
    a = p.parse_args()
    if a.sheet:
        sheet(a.sheet)
    elif a.svg:
        print(svg(a.variant))
    else:
        write_icons(a.variant)


if __name__ == "__main__":
    main()
