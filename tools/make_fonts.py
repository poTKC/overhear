#!/usr/bin/env python3
"""Makes app/assets/fonts/overhear-digits.ttf: Rubik (SIL OFL 1.1), instanced
from the variable font at one weight and cut down to the characters the
player shows in it (clock, battery number, seek times;
StatusStrip.DIGITS_FONT_CHARS).

The original comes from google/fonts at a pinned commit and is checked by
sha256. Rubik declares no Reserved Font Names, so the instance keeps its
Rubik names; its copyright and licence entries are kept too. See
THIRD_PARTY_NOTICES.md.

Needs fontTools (pip install fonttools) and curl.
Run from the repo root: python3 -I tools/make_fonts.py [weight]
"""
import hashlib
import os
import subprocess
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

COMMIT = "e7db6ca603fd4862010383ff17ffd6cc4ffac1c0"  # google/fonts main, 2026-10-23
URL = "https://raw.githubusercontent.com/google/fonts/%s/ofl/rubik/Rubik%%5Bwght%%5D.ttf" % COMMIT
SHA256 = "1b3a7437ba2af80e465e773ed60c5036d1ba6ace492d89046dbcf18fb31e4e88"
CACHE = os.path.join("build", "cache", "fonts", COMMIT[:12], "Rubik[wght].ttf")
OUT = os.path.join("app", "assets", "fonts", "overhear-digits.ttf")
CHARS = "0123456789: "  # keep in step with StatusStrip.DIGITS_FONT_CHARS
WEIGHT = 400  # Regular


def original():
    if not os.path.exists(CACHE):
        os.makedirs(os.path.dirname(CACHE), exist_ok=True)
        subprocess.run(["curl", "-fsSL", "-o", CACHE + ".part", URL], check=True)
        os.rename(CACHE + ".part", CACHE)
    got = hashlib.sha256(open(CACHE, "rb").read()).hexdigest()
    if got != SHA256:
        os.remove(CACHE)
        sys.exit("sha256 mismatch for Rubik[wght].ttf: " + got)
    return CACHE


def make(weight, out):
    font = TTFont(original(), recalcTimestamp=False)  # reproducible output
    font = instancer.instantiateVariableFont(font, {"wght": weight}, updateFontNames=True)
    options = subset.Options()
    options.name_IDs = ["*"]  # keep copyright (0) and licence (13, 14)
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.layout_features = []  # plain figures only
    sub = subset.Subsetter(options)
    sub.populate(unicodes=[ord(c) for c in CHARS])
    sub.subset(font)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    font.save(out)
    print("%s: wght %d, %d bytes, glyphs %s" % (out, weight, os.path.getsize(out), font.getGlyphOrder()))


if __name__ == "__main__":
    make(int(sys.argv[1]) if len(sys.argv) > 1 else WEIGHT, OUT)
