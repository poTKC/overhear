#!/usr/bin/env python3
"""Makes the soft drop shadow behind the player artwork as a 9-patch,
app/res/drawable-{mdpi,hdpi,xhdpi}/art_shadow.9.png (committed).

API 18 has no elevation, and Paint.setShadowLayer needs a software layer
(slow on the ST15i), so the shadow is pre-rendered: a rounded rectangle
(RADIUS_DP corners, like RoundedArt) OFFSET_DP below the art, Gaussian
blurred with SIGMA_DP, at most ALPHA black. The image is the art box plus
EXTENT_DP of blur around the shadow (3 sigma, so nothing visible is cut
off): SIDE_DP left and right, TOP_DP above and BOTTOM_DP below the art.

9-patch: the stretch marks (top and left border) are one pixel in the
middle, where the blurred shadow is flat in that direction; the padding box
(right and bottom border) is the art box. In activity_player.xml the
shadow is a plain View inside the 240dp art container, stretched past it by
negative margins of SIDE_DP, TOP_DP and BOTTOM_DP (clipChildren="false"
on the parents), so the art keeps its size and place. (A container with the
9-patch as background and negative margins in the weighted LinearLayout
moved the art 1px.)

Needs Pillow. Run from the repo root: python3 -I tools/make_shadow.py
"""
import os

from PIL import Image, ImageDraw, ImageFilter

RES = os.path.join("app", "res")
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0}

RADIUS_DP = 6       # the artwork's corner radius (PlayerActivity.artRadius)
OFFSET_DP = 4       # straight down
SIGMA_DP = 4        # Gaussian sigma: the blur fades out over about 12dp
EXTENT_DP = 12      # 3 sigma of blur kept around the shadow
ALPHA = 0.40        # black at its strongest
BOX_DP = 40         # the art box inside the 9-patch (stretched to 240dp)

SIDE_DP = EXTENT_DP
TOP_DP = EXTENT_DP - OFFSET_DP
BOTTOM_DP = EXTENT_DP + OFFSET_DP


def render(scale):
    px = lambda dp: int(round(dp * scale))
    side, top, bottom, box = px(SIDE_DP), px(TOP_DP), px(BOTTOM_DP), px(BOX_DP)
    w, h = side + box + side, top + box + bottom
    # Supersample the rounded rectangle; the blur hides any remaining steps.
    ss = 4
    mask = Image.new("L", (w * ss, h * ss), 0)
    y0 = top + px(OFFSET_DP)
    ImageDraw.Draw(mask).rounded_rectangle(
        [side * ss, y0 * ss, (side + box) * ss - 1, (y0 + box) * ss - 1],
        radius=px(RADIUS_DP) * ss, fill=255)
    mask = mask.resize((w, h), Image.LANCZOS)
    mask = mask.filter(ImageFilter.GaussianBlur(SIGMA_DP * scale))
    alpha = mask.point(lambda a: int(round(a * ALPHA)))
    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    shadow.putalpha(alpha)

    # The 9-patch: a 1px border of markers around the image.
    out = Image.new("RGBA", (w + 2, h + 2), (0, 0, 0, 0))
    out.paste(shadow, (1, 1))
    mark = (0, 0, 0, 255)
    cx, cy = side + box // 2, top + px(OFFSET_DP) + box // 2
    out.putpixel((1 + cx, 0), mark)                      # stretch columns
    out.putpixel((0, 1 + cy), mark)                      # stretch rows
    for x in range(side, side + box):                    # padding: the art box
        out.putpixel((1 + x, h + 1), mark)
    for y in range(top, top + box):
        out.putpixel((w + 1, 1 + y), mark)
    return out


def main():
    for density, scale in DENSITIES.items():
        d = os.path.join(RES, "drawable-" + density)
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, "art_shadow.9.png")
        render(scale).save(path, optimize=True)
        print(path)


if __name__ == "__main__":
    main()
