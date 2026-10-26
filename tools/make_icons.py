#!/usr/bin/env python3
"""Makes the app's PNG icons (API 18 has no VectorDrawable) from Remix Icon
(https://remixicon.com, Apache-2.0; see THIRD_PARTY_NOTICES.md).

The SVGs come from the npm package remixicon@4.5.0 (via unpkg) and are
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

VERSION = "4.5.0"
BASE = "https://unpkg.com/remixicon@%s/icons" % VERSION
CACHE = os.path.join("build", "cache", "remixicon", VERSION)
# icons/<Category>/<name>.svg -> sha256 (24 x 24 viewBox, fill="currentColor")
SVGS = {
    "Media/shuffle-line.svg": "69bc287ba0effd35e89e554ae9754e3f9a274ce8b50eab2c5de29dc5e71038cd",
    "Media/repeat-2-line.svg": "04332c70adde08a07683e5fb892d522ef42f4b50b64d5e28dadb43a482c41b95",
    "Media/repeat-one-line.svg": "8f17990ff8708c9be4dfa2fca7afb58f98700faea8cdd1175e9d5e8346a81494",
    "Media/skip-back-fill.svg": "bf02c933bce295539b75acda83d6aa417a021ae4c9081a8a5b245b3e2c501ee3",
    "Media/skip-forward-fill.svg": "196ebc286a2002f8725348b4b2615a0a485e1ca7ac2150ff39e0df6d8d0a4b3c",
    "Media/play-fill.svg": "ac35d2ee814f73004c748273aabbddb57d0e5c1eead3788b889276781876a7c5",
    "Media/pause-fill.svg": "82bfb45bf5b77f055ce984fca319d551c4319036520fd8a0719f41e656431953",
    "Media/play-list-2-line.svg": "ed697dce9bbbde15715cc0b385926ca40731958e12bcceac67b8b950fe44493b",
    "System/settings-3-line.svg": "f2e1fd30e6eadb8d5636f5037cf477f38ef81b235757d3ed290b92b2c002fec1",
    "System/close-line.svg": "db3b4dcebb2d962a0231a191538bf6939620798b18f0ba52d4b91c5a4649ce54",
    "Media/music-2-line.svg": "beaf516b74a065afb95022f9b311ecc981cbdd22af446b96e8a8b353f7b49342",
    "Media/music-2-fill.svg": "70a5c81119274e9b6c0a6286afa125c15414f42d0aa3a799e8c971bebecb1221",
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
        os.makedirs(os.path.dirname(path), exist_ok=True)
        subprocess.run(["curl", "-fsSL", "-o", path + ".part", "%s/%s" % (BASE, name)], check=True)
        os.rename(path + ".part", path)
    data = open(path, "rb").read()
    got = hashlib.sha256(data).hexdigest()
    if got != SVGS[name]:
        os.remove(path)
        sys.exit("sha256 mismatch for %s: %s" % (name, got))
    return data.decode("utf-8")


def glyph(name, color, px):
    """The icon in color, px square (its 24-unit box, little padding)."""
    text = svg(name)
    if 'fill="currentColor"' not in text:
        sys.exit("%s: no fill=currentColor to recolour" % name)
    text = text.replace('fill="currentColor"', 'fill="%s"' % color, 1)
    png = subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px)], input=text.encode("utf-8"),
                         stdout=subprocess.PIPE, check=True).stdout
    return Image.open(io.BytesIO(png)).convert("RGBA")


def out(density, name, img):
    d = os.path.join("app", "res", "drawable-" + density)
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name + ".png"), optimize=True)


def plain(density, f, name, svg_name, color, dp, glyph_dp):
    """A dp square with the glyph at glyph_dp, centred (Remix glyphs nearly fill their box)."""
    c, g = round(dp * f), round(glyph_dp * f)
    img = Image.new("RGBA", (c, c), (0, 0, 0, 0))
    img.alpha_composite(glyph(svg_name, color, g), ((c - g) // 2, (c - g) // 2))
    out(density, name, img)


TOGGLE_GLYPH_DP = 24


def toggle(density, f, name, svg_name, on):
    """28 x 44 dp: the glyph centred, and when on, a 4 dp dot below it, so the
    glyph does not move between the states."""
    w, h, g = round(28 * f), round(44 * f), round(TOGGLE_GLYPH_DP * f)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    top = (h - g) // 2
    img.alpha_composite(glyph(svg_name, GREEN if on else GREY, g), ((w - g) // 2, top))
    if on:
        ss = 8  # supersampled dot
        r = 2 * f * ss
        cx, cy = w / 2 * ss, (top + g + 5 * f) * ss
        dot = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
        ImageDraw.Draw(dot).ellipse((cx - r, cy - r, cx + r, cy + r), fill=GREEN)
        img.alpha_composite(dot.resize((w, h), Image.LANCZOS))
    out(density, name, img)


def placeholder(density, f):
    """240 dp dark square (RoundedArt rounds it) with a grey note."""
    s, n = round(240 * f), round(80 * f)
    img = Image.new("RGBA", (s, s), PLACEHOLDER_BG)
    img.alpha_composite(glyph("Media/music-2-line.svg", NOTE_GREY, n), ((s - n) // 2, (s - n) // 2))
    out(density, "art_placeholder", img)


def main():
    for density, f in DENSITIES.items():
        # player: canvas dp (as the buttons expect), glyph dp
        plain(density, f, "ic_play", "Media/play-fill.svg", BLACK, 36, 28)
        plain(density, f, "ic_pause", "Media/pause-fill.svg", BLACK, 36, 28)
        plain(density, f, "ic_prev", "Media/skip-back-fill.svg", WHITE, 36, 28)
        plain(density, f, "ic_next", "Media/skip-forward-fill.svg", WHITE, 36, 28)
        toggle(density, f, "ic_shuffle_off", "Media/shuffle-line.svg", False)
        toggle(density, f, "ic_shuffle_on", "Media/shuffle-line.svg", True)
        toggle(density, f, "ic_repeat_off", "Media/repeat-2-line.svg", False)
        toggle(density, f, "ic_repeat_on", "Media/repeat-2-line.svg", True)
        toggle(density, f, "ic_repeat_one_on", "Media/repeat-one-line.svg", True)
        plain(density, f, "ic_queue", "Media/play-list-2-line.svg", GREY, 20, 18)
        plain(density, f, "ic_settings", "System/settings-3-line.svg", GREY, 20, 18)
        plain(density, f, "ic_close", "System/close-line.svg", WHITE, 24, 22)
        placeholder(density, f)
        # notification row (40 dp wide buttons) and status bar icon (white on transparent)
        plain(density, f, "ic_n_prev", "Media/skip-back-fill.svg", WHITE, 28, 24)
        plain(density, f, "ic_n_play", "Media/play-fill.svg", WHITE, 28, 24)
        plain(density, f, "ic_n_pause", "Media/pause-fill.svg", WHITE, 28, 24)
        plain(density, f, "ic_n_next", "Media/skip-forward-fill.svg", WHITE, 28, 24)
        plain(density, f, "ic_n_close", "System/close-line.svg", WHITE, 24, 20)
        plain(density, f, "ic_stat_music", "Media/music-2-fill.svg", WHITE, 24, 18)
    for old in ("ic_repeat", "ic_repeat_one", "ic_shuffle"):
        p = os.path.join("app", "res", "drawable-mdpi", old + ".png")
        if os.path.exists(p):
            os.remove(p)


if __name__ == "__main__":
    main()
