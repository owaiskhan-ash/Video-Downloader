#!/usr/bin/env python3
"""Generate Teal's PNG launcher icons for API 24/25.

API 26+ uses the adaptive icon in mipmap-anydpi-v26/, but older devices need a
bitmap in each density bucket. Drawing the same glyph as the vector (arrow into a
tray, teal on near-black) keeps the icon identical across versions.

    python3 scripts/make_icons.py
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

# mdpi -> xxxhdpi, the densities Android expects a launcher bitmap for.
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BACKGROUND = (8, 17, 15, 255)      # #08110F
TEAL = (45, 212, 191, 255)         # #2DD4BF
SUPERSAMPLE = 8                    # draw big, shrink: cheap antialiasing


def draw_glyph(size: int) -> Image.Image:
    """The download arrow + tray, scaled from the 108dp vector geometry."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size / 108.0
    w = max(2, round(8 * s))

    def pt(x: float, y: float) -> tuple[float, float]:
        return (x * s, y * s)

    # shaft
    d.line([pt(54, 30), pt(54, 62)], fill=TEAL, width=w)
    # arrowhead
    d.line([pt(40, 50), pt(54, 64), pt(68, 50)], fill=TEAL, width=w, joint="curve")
    # tray
    d.line([pt(34, 78), pt(74, 78)], fill=TEAL, width=w)

    for cx, cy in ((54, 30), (40, 50), (54, 64), (68, 50), (34, 78), (74, 78)):
        r = w / 2
        x, y = pt(cx, cy)
        d.ellipse([x - r, y - r, x + r, y + r], fill=TEAL)
    return img


def make(size: int, round_icon: bool) -> Image.Image:
    big = size * SUPERSAMPLE
    canvas = Image.new("RGBA", (big, big), BACKGROUND)
    canvas.alpha_composite(draw_glyph(big))
    if round_icon:
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, big, big], fill=255)
        canvas.putalpha(mask)
    return canvas.resize((size, size), Image.LANCZOS)


def main() -> None:
    for density, size in DENSITIES.items():
        out = RES / f"mipmap-{density}"
        out.mkdir(parents=True, exist_ok=True)
        make(size, round_icon=False).save(out / "ic_launcher.png")
        make(size, round_icon=True).save(out / "ic_launcher_round.png")
        print(f"  mipmap-{density}: ic_launcher.png + ic_launcher_round.png ({size}x{size})")


if __name__ == "__main__":
    main()
