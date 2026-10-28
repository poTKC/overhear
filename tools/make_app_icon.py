#!/usr/bin/env python3
"""Makes the launcher icon, app/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi}/ic_launcher.png
(48, 72, 96 and 144 px; API 18 has no adaptive icons), from an SVG authored
here and rasterised with rsvg-convert at each size (no downscaling).

"Overhear": music you hear from a nearby device. Every variant is a flat
rounded-square tile with a simple glyph. The default, phone-green-waves, is a
white phone sending two green sound arcs on near-black, in the app's and the
install page's colours (#1ED760 on #121212). The others are the candidates
it was picked from (--sheet shows a selection of them; the original warm
phone/waves/ear set avoided green altogether).

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

DEFAULT = "phone-green-waves"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144}

WHITE = "#ffffff"
CHARCOAL = "#1f2329"
CHARCOAL_TOP = "#2b3038"
INDIGO = "#262a52"
INDIGO_TOP = "#323769"
AMBER = "#ffb02e"
CORAL = "#ff6f59"
GREEN = "#1ed760"
BLACK = "#121212"
BLACK_TOP = "#1f1f1f"
GREEN_TOP = "#3be477"

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


def phone_green_dot():
    """phone, with the speaker in green on the page's near-black."""
    cx, cy = 70, 100
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<rect x="44" y="52" width="52" height="92" rx="12" fill="none" stroke="%s" stroke-width="10"/>' % WHITE,
        '<circle cx="%d" cy="%d" r="13" fill="%s"/>' % (cx, cy, GREEN),
        '<rect x="62" y="64" width="16" height="6" rx="3" fill="%s"/>' % WHITE,
        stroke(arc(cx, cy, 50, 34), WHITE, 11),
        stroke(arc(cx, cy, 74, 32), WHITE, 11),
    ])


def phone_green_waves():
    """phone in white, the sound in green, centred on the tile: the glyph's
    ink (the phone's outer edge at 39 to the round-capped outer arc at about 148)
    is moved 4.25 right and 2 up: between the ink centre (2.5) and the
    optical one (6), as the heavy phone pulls the eye left."""
    cx, cy = 70, 100
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<g transform="translate(4.25 -2)">',
        '<rect x="44" y="52" width="52" height="92" rx="12" fill="none" stroke="%s" stroke-width="10"/>' % WHITE,
        '<circle cx="%d" cy="%d" r="13" fill="%s"/>' % (cx, cy, GREEN),
        '<rect x="62" y="64" width="16" height="6" rx="3" fill="%s"/>' % WHITE,
        stroke(arc(cx, cy, 50, 34), GREEN, 11),
        stroke(arc(cx, cy, 74, 32), GREEN, 11),
        '</g>',
    ])


def phone_on_green():
    """A green tile with the phone and its sound in near-black."""
    cx, cy = 70, 100
    return "".join([
        TILE.format(top=GREEN_TOP, bottom=GREEN),
        '<rect x="44" y="52" width="52" height="92" rx="12" fill="none" stroke="%s" stroke-width="10"/>' % BLACK,
        '<circle cx="%d" cy="%d" r="13" fill="%s"/>' % (cx, cy, BLACK),
        '<rect x="62" y="64" width="16" height="6" rx="3" fill="%s"/>' % BLACK,
        stroke(arc(cx, cy, 50, 34), BLACK, 11),
        stroke(arc(cx, cy, 74, 32), BLACK, 11),
    ])


def note_green():
    """The page's mark: a green note on near-black (the favicon's 24-unit path, scaled)."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<path transform="translate(24 22) scale(6)" fill="%s" '
        'd="M17 4v10.3a3.2 3.2 0 1 1-2-3V7.3l-5 1.2v7.8a3.2 3.2 0 1 1-2-3V6.9z"/>' % GREEN,
    ])


def note_waves_green():
    """A white note sending two green arcs to the right."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<path transform="translate(6 26) scale(5.6)" fill="%s" '
        'd="M17 4v10.3a3.2 3.2 0 1 1-2-3V7.3l-5 1.2v7.8a3.2 3.2 0 1 1-2-3V6.9z"/>' % WHITE,
        stroke(arc(100, 100, 44, 40), GREEN, 11),
        stroke(arc(100, 100, 66, 36), GREEN, 11),
    ])


def waves_green():
    cx, cy = 66, 96
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<circle cx="%d" cy="%d" r="17" fill="%s"/>' % (cx, cy, GREEN),
        stroke(arc(cx, cy, 44, 48), GREEN, 14),
        stroke(arc(cx, cy, 74, 46), GREEN, 14),
    ])


def bars_phone():
    """A phone whose screen shows the app's now-playing bars, in green."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<rect x="54" y="30" width="84" height="132" rx="16" fill="none" stroke="%s" stroke-width="10"/>' % WHITE,
        '<rect x="84" y="42" width="24" height="7" rx="3.5" fill="%s"/>' % WHITE,
        '<rect x="70" y="96" width="14" height="44" rx="4" fill="%s"/>' % GREEN,
        '<rect x="89" y="72" width="14" height="68" rx="4" fill="%s"/>' % GREEN,
        '<rect x="108" y="110" width="14" height="30" rx="4" fill="%s"/>' % GREEN,
    ])


def bars_on_green():
    """Now-playing bars alone, near-black on green."""
    return "".join([
        TILE.format(top=GREEN_TOP, bottom=GREEN),
        '<rect x="46" y="88" width="24" height="60" rx="7" fill="%s"/>' % BLACK,
        '<rect x="84" y="48" width="24" height="100" rx="7" fill="%s"/>' % BLACK,
        '<rect x="122" y="108" width="24" height="40" rx="7" fill="%s"/>' % BLACK,
    ])


def speaker_green():
    """A speaker box: a green cone and a small tweeter."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<rect x="50" y="30" width="92" height="132" rx="14" fill="none" stroke="%s" stroke-width="10"/>' % WHITE,
        '<circle cx="96" cy="64" r="11" fill="%s"/>' % WHITE,
        '<circle cx="96" cy="116" r="30" fill="none" stroke="%s" stroke-width="10"/>' % GREEN,
        '<circle cx="96" cy="116" r="10" fill="%s"/>' % GREEN,
    ])


def ear_green():
    """The name: an ear (white) catching green sound arcs from the left."""
    helix = "M 100 146 C 108 158, 128 156, 132 138 C 135 124, 152 116, 154 94 C 157 64, 132 46, 108 50 C 86 54, 76 72, 78 90"
    fold = "M 100 92 C 100 78, 120 76, 124 90 C 127 102, 114 106, 112 118"
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        stroke(helix, WHITE, 13),
        stroke(fold, WHITE, 11),
        stroke("M 62 82 A 34 34 0 0 0 62 130", GREEN, 11),
        stroke("M 38 68 A 54 54 0 0 0 38 144", GREEN, 11),
    ])


def radio_green():
    """An old-fashioned radio: a green grille and a white dial."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        stroke("M 70 58 L 122 34", WHITE, 9),
        '<rect x="32" y="58" width="128" height="96" rx="18" fill="none" stroke="%s" stroke-width="10"/>' % WHITE,
        '<circle cx="76" cy="106" r="24" fill="%s"/>' % GREEN,
        '<rect x="114" y="84" width="28" height="8" rx="4" fill="%s"/>' % WHITE,
        '<rect x="114" y="102" width="28" height="8" rx="4" fill="%s"/>' % WHITE,
        '<rect x="114" y="120" width="28" height="8" rx="4" fill="%s"/>' % WHITE,
    ])


def phone_note_screen():
    """A phone on green, a near-black screen with a green note on it."""
    return "".join([
        TILE.format(top=GREEN_TOP, bottom=GREEN),
        '<rect x="54" y="28" width="84" height="136" rx="16" fill="%s"/>' % BLACK,
        '<path transform="translate(60 64) scale(3)" fill="%s" '
        'd="M17 4v10.3a3.2 3.2 0 1 1-2-3V7.3l-5 1.2v7.8a3.2 3.2 0 1 1-2-3V6.9z"/>' % GREEN,
    ])


def speaker_fill_white():
    """A solid white speaker box with a green cone."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<rect x="48" y="26" width="96" height="140" rx="16" fill="%s"/>' % WHITE,
        '<circle cx="96" cy="60" r="12" fill="%s"/>' % BLACK,
        '<circle cx="96" cy="116" r="34" fill="%s"/>' % BLACK,
        '<circle cx="96" cy="116" r="24" fill="%s"/>' % GREEN,
        '<circle cx="96" cy="116" r="8" fill="%s"/>' % BLACK,
    ])


def speaker_fill_green():
    """A green tile with a solid near-black speaker box and a green cone."""
    return "".join([
        TILE.format(top=GREEN_TOP, bottom=GREEN),
        '<rect x="48" y="26" width="96" height="140" rx="16" fill="%s"/>' % BLACK,
        '<circle cx="96" cy="60" r="11" fill="%s"/>' % GREEN,
        '<circle cx="96" cy="116" r="32" fill="%s"/>' % GREEN,
        '<circle cx="96" cy="116" r="12" fill="%s"/>' % BLACK,
    ])


def speaker_fill_dark():
    """A dark grey solid speaker box on near-black, the cone in green."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<rect x="48" y="26" width="96" height="140" rx="16" fill="#3a3a3a"/>',
        '<circle cx="96" cy="60" r="11" fill="%s"/>' % WHITE,
        '<circle cx="96" cy="116" r="32" fill="%s"/>' % GREEN,
        '<circle cx="96" cy="116" r="12" fill="#3a3a3a"/>',
    ])


def vinyl_green():
    """A record: near-black disc with grooves, a green label."""
    return "".join([
        TILE.format(top=GREEN_TOP, bottom=GREEN),
        '<circle cx="96" cy="96" r="66" fill="%s"/>' % BLACK,
        '<circle cx="96" cy="96" r="50" fill="none" stroke="#2e2e2e" stroke-width="3"/>',
        '<circle cx="96" cy="96" r="38" fill="none" stroke="#2e2e2e" stroke-width="3"/>',
        '<circle cx="96" cy="96" r="22" fill="%s"/>' % GREEN,
        '<circle cx="96" cy="96" r="5" fill="%s"/>' % BLACK,
    ])


def waveform_green():
    """A row of rounded bars like a sound waveform, green on near-black."""
    hs = [28, 56, 92, 120, 76, 104, 48, 24]
    parts = [TILE.format(top=BLACK_TOP, bottom=BLACK)]
    for k, h in enumerate(hs):
        x = 38 + k * 16
        parts.append('<rect x="%d" y="%g" width="10" height="%d" rx="5" fill="%s"/>' % (x, 96 - h / 2.0, h, GREEN))
    return "".join(parts)


def phone_dock():
    """A phone lying on its side on a stand, the screen showing green bars."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<rect x="30" y="52" width="132" height="80" rx="14" fill="none" stroke="%s" stroke-width="10"/>' % WHITE,
        '<rect x="62" y="92" width="12" height="28" rx="4" fill="%s"/>' % GREEN,
        '<rect x="80" y="72" width="12" height="48" rx="4" fill="%s"/>' % GREEN,
        '<rect x="98" y="84" width="12" height="36" rx="4" fill="%s"/>' % GREEN,
        '<rect x="116" y="100" width="12" height="20" rx="4" fill="%s"/>' % GREEN,
        stroke("M 70 152 L 122 152", WHITE, 10),
        stroke("M 96 132 L 96 152", WHITE, 10),
    ])


def cassette_green():
    """A cassette tape: white shell, green reels."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<rect x="28" y="50" width="136" height="92" rx="12" fill="none" stroke="%s" stroke-width="9"/>' % WHITE,
        '<circle cx="70" cy="90" r="14" fill="none" stroke="%s" stroke-width="8"/>' % GREEN,
        '<circle cx="122" cy="90" r="14" fill="none" stroke="%s" stroke-width="8"/>' % GREEN,
        stroke("M 84 90 L 108 90", WHITE, 6),
        stroke("M 56 142 L 66 120 L 126 120 L 136 142", WHITE, 8),
    ])


def o_bars():
    """The initial: a rounded "O" ring in white with green bars inside."""
    return "".join([
        TILE.format(top=BLACK_TOP, bottom=BLACK),
        '<rect x="34" y="34" width="124" height="124" rx="62" fill="none" stroke="%s" stroke-width="14"/>' % WHITE,
        '<rect x="70" y="96" width="14" height="32" rx="5" fill="%s"/>' % GREEN,
        '<rect x="89" y="70" width="14" height="58" rx="5" fill="%s"/>' % GREEN,
        '<rect x="108" y="86" width="14" height="42" rx="5" fill="%s"/>' % GREEN,
    ])


VARIANTS = {"phone-green-waves": phone_green_waves, "phone-on-green": phone_on_green,
            "bars-phone": bars_phone, "bars-on-green": bars_on_green, "speaker-green": speaker_green,
            "ear-green": ear_green, "radio-green": radio_green, "phone-note-screen": phone_note_screen,
            "phone": phone, "phone-green-dot": phone_green_dot, "note-green": note_green,
            "note-waves-green": note_waves_green, "waves-green": waves_green,
            "speaker-fill-white": speaker_fill_white, "speaker-fill-green": speaker_fill_green,
            "speaker-fill-dark": speaker_fill_dark, "vinyl-green": vinyl_green, "waveform-green": waveform_green,
            "phone-dock": phone_dock, "cassette-green": cassette_green, "o-bars": o_bars}
SHEET = ["speaker-fill-white", "speaker-fill-green", "speaker-fill-dark", "o-bars", "vinyl-green",
         "waveform-green", "phone-dock", "cassette-green"]


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
    names = SHEET
    cell_w, cell_h = 420, 300
    img = Image.new("RGBA", (cell_w * 4, cell_h * 2 * ((len(names) + 3) // 4)), (0, 0, 0, 255))
    draw = ImageDraw.Draw(img)
    for row, bg in enumerate([(18, 18, 18, 255), (236, 236, 236, 255)]):
        fg = (230, 230, 230, 255) if row == 0 else (40, 40, 40, 255)
        for i, name in enumerate(names):
            col, band = i % 4, i // 4
            x0, y0 = col * cell_w, (band * 2 + row) * cell_h
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
