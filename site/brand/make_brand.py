# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0

"""Generates Praecepta's brand SVGs: the tabula ansata mark, favicon, wordmarks and social image.
Letters are Cinzel Bold / EB Garamond Italic outlines, so the files need no fonts.

    pip install fonttools
    python make_brand.py <fonts directory> <output directory>

The fonts directory holds Cinzel.ttf and EBGaramond-Italic.ttf, the variable fonts from
https://github.com/google/fonts (ofl/cinzel/Cinzel[wght].ttf, ofl/ebgaramond/EBGaramond-Italic[wght].ttf).
Copy mark.svg, wordmark-*.svg and social.svg to site/brand/ and favicon.svg to site/, then render
social.png (1280 x 640), e.g. with a headless Chrome:

    chrome --headless --hide-scrollbars --window-size=1280,640 --screenshot=social.png social.svg
"""
import sys
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

SRC = Path(sys.argv[1])  # directory with Cinzel.ttf and EBGaramond-Italic.ttf
OUT = Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)

BRONZE = "#8C5A2B"
MOULDING = "#E2BE8A"
LETTER = "#F7E9CF"
INK = "#2B2420"
INK_DARK = "#EDE4D6"
TRAVERTINE = "#F4EEE3"
VERDIGRIS = "#3E7C6E"
HEADER = """<!-- SPDX-FileCopyrightText: Bruno Pacheco -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
"""


def num(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


class Face:
    def __init__(self, path, weight):
        self.font = instantiateVariableFont(TTFont(path), {"wght": weight})
        self.glyphs = self.font.getGlyphSet()
        self.cmap = self.font.getBestCmap()
        self.upm = self.font["head"].unitsPerEm

    def width(self, text, size, tracking=0.0):
        scale = size / self.upm
        advances = [self.glyphs[self.cmap[ord(c)]].width * scale for c in text]
        return sum(advances) + tracking * size * (len(text) - 1)

    def path(self, text, size, x, baseline, tracking=0.0):
        scale = size / self.upm
        parts = []
        for c in text:
            name = self.cmap[ord(c)]
            pen = SVGPathPen(self.glyphs, ntos=num)
            self.glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
            parts.append(pen.getCommands())
            x += self.glyphs[name].width * scale + tracking * size
        return " ".join(p for p in parts if p)


# the wordmark uses the Æ ligature; everywhere else the name is spelled Praecepta
WORDMARK = "PR\u00c6CEPTA"
CINZEL = Face(SRC / "Cinzel.ttf", 700)
GARAMOND = Face(SRC / "EBGaramond-Italic.ttf", 400)


def circle(cx, cy, r):
    return f"M{num(cx - r)} {num(cy)} a{num(r)} {num(r)} 0 1 0 {num(2 * r)} 0 a{num(r)} {num(r)} 0 1 0 {num(-2 * r)} 0Z"


def tablet(x, y, w, h, holes=True, moulding=True, letter=True):
    """A tabula ansata whose body is (x, y, w, h); the dovetail handles add h * 0.32 on each side."""
    ear = h * 0.27
    neck = h * 0.15  # half the height where a handle meets the body
    mid = y + h / 2
    body = f"M{num(x)} {num(y)}H{num(x + w)}V{num(y + h)}H{num(x)}Z"
    left = (f"M{num(x)} {num(mid - neck)}L{num(x - ear)} {num(y + h * 0.16)}"
            f"V{num(y + h * 0.84)}L{num(x)} {num(mid + neck)}Z")
    right = (f"M{num(x + w)} {num(mid - neck)}L{num(x + w + ear)} {num(y + h * 0.16)}"
             f"V{num(y + h * 0.84)}L{num(x + w)} {num(mid + neck)}Z")
    shape = body + left + right
    if holes:  # the nail holes that fixed a tablet to a wall
        shape += circle(x - ear * 0.55, mid, h * 0.05) + circle(x + w + ear * 0.55, mid, h * 0.05)
    out = [f'<path fill="{BRONZE}" fill-rule="evenodd" d="{shape}"/>']
    if moulding:
        inset = h * 0.09
        out.append(f'<rect x="{num(x + inset)}" y="{num(y + inset)}" width="{num(w - 2 * inset)}" '
                   f'height="{num(h - 2 * inset)}" fill="none" stroke="{MOULDING}" stroke-width="{num(h * 0.028)}"/>')
    if letter:
        cap = h * 0.56
        size = cap / 0.7
        pw = CINZEL.width("P", size)
        out.append(f'<path fill="{LETTER}" d="{CINZEL.path("P", size, x + (w - pw) / 2, mid + cap / 2)}"/>')
    return "\n  ".join(out)


def svg(name, width, height, body, title, extra=""):
    text = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {num(width)} {num(height)}" '
            f'width="{num(width)}" height="{num(height)}" role="img" aria-label="{title}"{extra}>\n'
            f"{HEADER}  <title>{title}</title>\n  {body}\n</svg>\n")
    (OUT / name).write_text(text)


# the mark: a bronze tablet with handles and an inscribed P
svg("mark.svg", 112, 64, tablet(20, 4, 72, 56), "Praecepta")

# favicon: the same tablet, no nail holes, thinner proportions for 16-32 px
svg("favicon.svg", 32, 32, tablet(6.5, 4, 19, 24, holes=False, moulding=False), "Praecepta")

# wordmarks: mark + PRAECEPTA, for light and dark backgrounds
for variant, colour in (("light", INK), ("dark", INK_DARK)):
    size, tracking = 44, 0.08
    text_x = 128
    text_w = CINZEL.width(WORDMARK, size, tracking)
    body = tablet(20, 4, 72, 56) + f'\n  <path fill="{colour}" d="{CINZEL.path(WORDMARK, size, text_x, 47, tracking)}"/>'
    svg(f"wordmark-{variant}.svg", text_x + text_w + 4, 64, body, "Praecepta")

# social image, 1280 x 640, for link previews
W, H = 1280, 640
title_size, tagline_size = 104, 46
title_w = CINZEL.width(WORDMARK, title_size, 0.08)
tagline = "DMN decision tables for Java"
tagline_w = GARAMOND.width(tagline, tagline_size)
rule_y = 452
body = "\n  ".join([
    f'<rect width="{W}" height="{H}" fill="{TRAVERTINE}"/>',
    f'<rect x="28" y="28" width="{W - 56}" height="{H - 56}" fill="none" stroke="{MOULDING}" stroke-width="3"/>',
    tablet(W / 2 - 108, 70, 216, 168),
    f'<path fill="{INK}" d="{CINZEL.path(WORDMARK, title_size, (W - title_w) / 2, 398, 0.08)}"/>',
    f'<rect x="{W / 2 - 150}" y="{rule_y - 1.5}" width="120" height="3" fill="{BRONZE}"/>',
    f'<path fill="{BRONZE}" d="{circle(W / 2, rule_y, 5)}"/>',
    f'<rect x="{W / 2 + 30}" y="{rule_y - 1.5}" width="120" height="3" fill="{BRONZE}"/>',
    f'<path fill="{VERDIGRIS}" d="{GARAMOND.path(tagline, tagline_size, (W - tagline_w) / 2, 530)}"/>',
])
svg("social.svg", W, H, body, "Praecepta: DMN decision tables for Java")
print("written", sorted(p.name for p in OUT.iterdir()))
