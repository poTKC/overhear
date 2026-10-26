#!/usr/bin/env python3
"""Makes app/assets/fonts/st15i-digits.ttf: Varela Round (SIL OFL 1.1) cut
down to the characters the player shows in it (clock, battery number, seek
times; StatusStrip.DIGITS_FONT_CHARS).

The original comes from google/fonts at a pinned commit and is checked by
sha256. Varela Round declares the Reserved Font Names "Varela" and "Varela
Round", and under the OFL a subset is a Modified Version, which must not use
them: the subset is renamed "ST15i Digits" (name table and file name), and
its copyright and licence entries are kept. See THIRD_PARTY_NOTICES.md.

Needs fontTools (pip install fonttools) and curl.
Run from the repo root: python3 -I tools/make_fonts.py
"""
import hashlib
import os
import subprocess
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

COMMIT = "e7db6ca603fd4862010383ff17ffd6cc4ffac1c0"  # google/fonts main, 2026-10-23
URL = "https://raw.githubusercontent.com/google/fonts/%s/ofl/varelaround/VarelaRound-Regular.ttf" % COMMIT
SHA256 = "e1e47eb66dbc2ddc106661338e712d9176c9e83c669a82fde155324823d03aa2"
CACHE = os.path.join("build", "cache", "fonts", COMMIT[:12], "VarelaRound-Regular.ttf")
OUT = os.path.join("app", "assets", "fonts", "st15i-digits.ttf")
CHARS = "0123456789: "  # keep in step with StatusStrip.DIGITS_FONT_CHARS
FAMILY = "ST15i Digits"


def original():
    if not os.path.exists(CACHE):
        os.makedirs(os.path.dirname(CACHE), exist_ok=True)
        subprocess.run(["curl", "-fsSL", "-o", CACHE + ".part", URL], check=True)
        os.rename(CACHE + ".part", CACHE)
    got = hashlib.sha256(open(CACHE, "rb").read()).hexdigest()
    if got != SHA256:
        os.remove(CACHE)
        sys.exit("sha256 mismatch for VarelaRound-Regular.ttf: " + got)
    return CACHE


def main():
    font = TTFont(original(), recalcTimestamp=False)  # reproducible output
    options = subset.Options()
    options.name_IDs = ["*"]  # keep copyright (0) and licence (13, 14)
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.layout_features = []  # plain figures only (no frac numr/dnom)
    sub = subset.Subsetter(options)
    sub.populate(unicodes=[ord(c) for c in CHARS])
    sub.subset(font)
    # Reserved Font Names: the Modified Version gets its own name.
    names = font["name"]
    for rec in list(names.names):
        if rec.nameID in (1, 4, 16, 18, 21):
            names.setName(FAMILY, rec.nameID, rec.platformID, rec.platEncID, rec.langID)
        elif rec.nameID in (3, 6, 20):
            names.setName("ST15iDigits-Regular", rec.nameID, rec.platformID, rec.platEncID, rec.langID)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    font.save(OUT)
    print("%s: %d bytes, glyphs %s" % (OUT, os.path.getsize(OUT), font.getGlyphOrder()))


if __name__ == "__main__":
    main()
