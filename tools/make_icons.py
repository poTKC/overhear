#!/usr/bin/env python3
"""Makes the app's PNG icons (API 18 has no VectorDrawable) from Google's
Material Symbols, Rounded style (Apache-2.0; see THIRD_PARTY_NOTICES.md).

The SVGs come from google/material-design-icons at a pinned commit and are
checked against the sha256 below, then recoloured and rasterised with
rsvg-convert, and composed (toggle dot, placeholder) with Pillow, into
app/res/drawable-{mdpi,hdpi,xhdpi}. The PNGs are committed, so a normal
build needs none of this.

Needs rsvg-convert (brew install librsvg), Pillow and curl.
Run from the repo root: python3 -I tools/make_icons.py
"""
import hashlib
import io
import os
import subprocess
import sys

from PIL import Image, ImageDraw

COMMIT = "10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7"  # google/material-design-icons master, 2026-10-23
BASE = "https://raw.githubusercontent.com/google/material-design-icons/%s/symbols/web" % COMMIT
CACHE = os.path.join("build", "cache", "material-symbols", COMMIT[:12])
# file -> sha256 (weight 400, grade 0, optical size 24; fill1 = filled)
SVGS = {
    "play_arrow_fill1_24px.svg": "a0a39a2aef0c113c0ce19dfe2f59dbc363d9619d7af5a97f0cbd286c880d29e8",
    "pause_fill1_24px.svg": "25be92a75759fac11fa809ba44340de5327657e896dcd6363fc480b2191d2809",
    "skip_previous_fill1_24px.svg": "273da62928c7633ff6045339d9967d069a0157e61e3669d65d7d4231dd0532c3",
    "skip_next_fill1_24px.svg": "9095cabdee09f64e22594d2393ee50b06412ee1a5f5da67236e111803a66b211",
    "shuffle_24px.svg": "6c07ebaa88385637a58c81a86b7e5a9bcdfdbcce9f757e091626a42006b26629",
    "repeat_24px.svg": "25166d52f33baa07cab65832d8cdb0538ea77af0e2ad7b1db941ca0661064efd",
    "repeat_one_24px.svg": "e2f808170e8c8c418e648f986ec5a0e4514f10883f58b4556cc0ab83f1548694",
    "queue_music_24px.svg": "1c5925fd0234d7d0121f7f5c980ef533dd911669e084972b42a86b8ba7daa94e",
    "settings_24px.svg": "6cd47de90647ece6b00922f497f8c4ea9c1649548cf15828d1df9d78d5bdf30d",
    "close_24px.svg": "fabc7e16160e93ed11d6d72b87f5c67092d04abfcc1fc2410fab1abe075cd8fc",
    "music_note_24px.svg": "6678b23e4e6ef7e9d46362d9085d7e704eeda30adee2d7cb42a3930d3342b11a",
}
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0}

WHITE = "#ffffff"
BLACK = "#000000"
GREY = "#b3b3b3"  # inactive toggles, small buttons
GREEN = "#1db954"  # active toggles
NOTE_GREY = "#7a7a7a"
PLACEHOLDER_BG = (0x28, 0x28, 0x28, 255)


def svg(name):
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        icon = name.split("_24px")[0].replace("_fill1", "")
        url = "%s/%s/materialsymbolsrounded/%s" % (BASE, icon, name)
        subprocess.run(["curl", "-fsSL", "-o", path + ".part", url], check=True)
        os.rename(path + ".part", path)
    data = open(path, "rb").read()
    got = hashlib.sha256(data).hexdigest()
    if got != SVGS[name]:
        os.remove(path)
        sys.exit("sha256 mismatch for %s: %s" % (name, got))
    return data.decode("utf-8")


def glyph(name, color, px):
    """The symbol in color, px square (the 24 dp symbol's own padding included)."""
    text = svg(name).replace("<svg ", '<svg fill="%s" ' % color, 1)
    png = subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px)], input=text.encode("utf-8"),
                         stdout=subprocess.PIPE, check=True).stdout
    return Image.open(io.BytesIO(png)).convert("RGBA")


def out(density, name, img):
    d = os.path.join("app", "res", "drawable-" + density)
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name + ".png"), optimize=True)


def plain(density, f, name, svg_name, color, dp):
    out(density, name, glyph(svg_name, color, round(dp * f)))


def toggle(density, f, name, svg_name, on):
    """28 x 44 dp: the glyph centred, and when on, a 4 dp dot below it, so the
    glyph does not move between the states."""
    w, h, g = round(28 * f), round(44 * f), round(28 * f)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    top = (h - g) // 2
    img.alpha_composite(glyph(svg_name, GREEN if on else GREY, g), (0, top))
    if on:
        ss = 8  # supersampled dot
        r = 2 * f * ss
        cx, cy = w / 2 * ss, (top + g + 4 * f) * ss
        dot = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
        ImageDraw.Draw(dot).ellipse((cx - r, cy - r, cx + r, cy + r), fill=GREEN)
        img.alpha_composite(dot.resize((w, h), Image.LANCZOS))
    out(density, name, img)


def placeholder(density, f):
    """240 dp dark square (RoundedArt rounds it) with a grey note."""
    s, n = round(240 * f), round(96 * f)
    img = Image.new("RGBA", (s, s), PLACEHOLDER_BG)
    img.alpha_composite(glyph("music_note_24px.svg", NOTE_GREY, n), ((s - n) // 2, (s - n) // 2))
    out(density, "art_placeholder", img)


def main():
    for density, f in DENSITIES.items():
        # player
        plain(density, f, "ic_play", "play_arrow_fill1_24px.svg", BLACK, 36)
        plain(density, f, "ic_pause", "pause_fill1_24px.svg", BLACK, 36)
        plain(density, f, "ic_prev", "skip_previous_fill1_24px.svg", WHITE, 36)
        plain(density, f, "ic_next", "skip_next_fill1_24px.svg", WHITE, 36)
        toggle(density, f, "ic_shuffle_off", "shuffle_24px.svg", False)
        toggle(density, f, "ic_shuffle_on", "shuffle_24px.svg", True)
        toggle(density, f, "ic_repeat_off", "repeat_24px.svg", False)
        toggle(density, f, "ic_repeat_on", "repeat_24px.svg", True)
        toggle(density, f, "ic_repeat_one_on", "repeat_one_24px.svg", True)
        plain(density, f, "ic_queue", "queue_music_24px.svg", GREY, 20)
        plain(density, f, "ic_settings", "settings_24px.svg", GREY, 20)
        plain(density, f, "ic_close", "close_24px.svg", WHITE, 24)
        placeholder(density, f)
        # notification row (40 dp wide buttons) and status bar icon
        plain(density, f, "ic_n_prev", "skip_previous_fill1_24px.svg", WHITE, 28)
        plain(density, f, "ic_n_play", "play_arrow_fill1_24px.svg", WHITE, 28)
        plain(density, f, "ic_n_pause", "pause_fill1_24px.svg", WHITE, 28)
        plain(density, f, "ic_n_next", "skip_next_fill1_24px.svg", WHITE, 28)
        plain(density, f, "ic_n_close", "close_24px.svg", WHITE, 24)
        plain(density, f, "ic_stat_music", "music_note_24px.svg", WHITE, 24)
    for old in ("ic_repeat", "ic_repeat_one", "ic_shuffle"):
        p = os.path.join("app", "res", "drawable-mdpi", old + ".png")
        if os.path.exists(p):
            os.remove(p)


if __name__ == "__main__":
    main()
