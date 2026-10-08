"""Single source of truth for the Kópia icon.

Emits both the PNG preview and the Android vector drawable from the same
coordinates, so what gets reviewed is exactly what ships. Geometry is pulled 15%
toward the centre because launchers mask adaptive icons to arbitrary shapes and
a circular mask would clip the corners of the sheets.
"""
import sys

from PIL import Image, ImageDraw

CANVAS = 108.0
CENTRE = 54.0
SHRINK = 0.85
TEAL = "#0B7285"
WHITE = "#FFFFFF"
TEAL_RGB = (11, 114, 133, 255)
WHITE_RGB = (255, 255, 255, 255)


def p(v):
    """Scale a coordinate toward the centre."""
    return CENTRE + (v - CENTRE) * SHRINK


def d(v):
    """Scale a distance."""
    return v * SHRINK


# back sheet, front sheet, the transparent gap between them, the arrow
BACK = (p(36), p(20), p(84), p(72), d(8))
GAP = (p(19), p(31), p(77), p(93), d(12))
FRONT = (p(24), p(36), p(72), p(88), d(8))
ARROW_CX, ARROW_TOP, ARROW_BOTTOM = p(48), p(47), p(79)
ARROW_HALF, ARROW_STEM = d(14), d(4.5)
ARROW_HEAD_TOP = ARROW_BOTTOM - (ARROW_BOTTOM - ARROW_TOP) * 0.45


def rounded_rect_path(x0, y0, x1, y1, r):
    return (
        f"M{x0 + r:.2f},{y0:.2f} H{x1 - r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x1:.2f},{y0 + r:.2f} "
        f"V{y1 - r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x1 - r:.2f},{y1:.2f} H{x0 + r:.2f} "
        f"A{r:.2f},{r:.2f} 0 0 1 {x0:.2f},{y1 - r:.2f} V{y0 + r:.2f} "
        f"A{r:.2f},{r:.2f} 0 0 1 {x0 + r:.2f},{y0:.2f} Z"
    )


def arrow_path():
    x0, x1 = ARROW_CX - ARROW_STEM, ARROW_CX + ARROW_STEM
    return (
        f"M{x0:.2f},{ARROW_TOP:.2f} H{x1:.2f} V{ARROW_HEAD_TOP:.2f} "
        f"H{ARROW_CX + ARROW_HALF:.2f} L{ARROW_CX:.2f},{ARROW_BOTTOM:.2f} "
        f"L{ARROW_CX - ARROW_HALF:.2f},{ARROW_HEAD_TOP:.2f} "
        f"H{x0:.2f} Z"
    )


def preview(px):
    s = px / CANVAS
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    g = ImageDraw.Draw(img)
    g.rounded_rectangle([0, 0, px - 1, px - 1], radius=px * 0.24, fill=TEAL_RGB)
    for (x0, y0, x1, y1, r), colour in ((BACK, WHITE_RGB), (GAP, TEAL_RGB), (FRONT, WHITE_RGB)):
        g.rounded_rectangle([x0 * s, y0 * s, x1 * s, y1 * s], radius=r * s, fill=colour)
    g.polygon([
        ((ARROW_CX - ARROW_STEM) * s, ARROW_TOP * s),
        ((ARROW_CX + ARROW_STEM) * s, ARROW_TOP * s),
        ((ARROW_CX + ARROW_STEM) * s, ARROW_HEAD_TOP * s),
        ((ARROW_CX + ARROW_HALF) * s, ARROW_HEAD_TOP * s),
        (ARROW_CX * s, ARROW_BOTTOM * s),
        ((ARROW_CX - ARROW_HALF) * s, ARROW_HEAD_TOP * s),
        ((ARROW_CX - ARROW_STEM) * s, ARROW_HEAD_TOP * s),
    ], fill=TEAL_RGB)
    return img


def vector_xml():
    # The gap and the arrow are painted in the background colour rather than cut
    # out: the background layer is a flat colour, so it renders identically and
    # keeps the path data simple.
    paths = [
        (rounded_rect_path(*BACK), WHITE),
        (rounded_rect_path(*GAP), TEAL),
        (rounded_rect_path(*FRONT), WHITE),
        (arrow_path(), TEAL),
    ]
    body = "\n".join(
        f'    <path\n        android:fillColor="{colour}"\n        android:pathData="{data}" />'
        for data, colour in paths
    )
    return (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        f"{body}\n"
        "</vector>\n"
    )


if __name__ == '__main__':
    out = sys.argv[1]
    sheet = Image.new('RGBA', (180 + 48 + 72, 180), (38, 38, 38, 255))
    big = preview(180)
    sheet.paste(big, (0, 0), big)
    small = preview(48)
    sheet.paste(small, (196, 66), small)
    mid = preview(72)
    sheet.paste(mid, (248, 54), mid)
    sheet.save(f"{out}/final.png")
    with open(f"{out}/ic_launcher_foreground.xml", "w") as f:
        f.write(vector_xml())
    print(f"{out}/final.png")
    print(f"{out}/ic_launcher_foreground.xml")
