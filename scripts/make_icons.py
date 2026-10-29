#!/usr/bin/env python3
"""
Generate the Tauri app icon set from a drawn 1024x1024 source.

`tauri build` hard-fails if any icon listed in tauri.conf.json is missing, so
these are checked in rather than left to each contributor to produce.

Run:
    python3 scripts/make_icons.py      # draws source, then calls `tauri icon`

The design matches the UI: the same accent blue and the same download-arrow
glyph used in the header of index.html.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: pip install Pillow")

ROOT = Path(__file__).resolve().parent.parent
ICON_DIR = ROOT / "src-tauri" / "icons"
SOURCE = ICON_DIR / "icon-source.png"

SIZE = 1024
# Must match --accent in src/styles.css and the header glyph colour.
ACCENT_TOP = (79, 157, 255)
ACCENT_BOTTOM = (37, 99, 200)
GLYPH = (255, 255, 255)


def rounded_mask(size: int, radius: int, supersample: int = 4) -> Image.Image:
    """Anti-aliased rounded-square mask.

    Supersampling beats trying to compute per-pixel corner coverage by hand, and
    the icon edge is the most visible part of the whole app.
    """
    big = size * supersample
    m = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=radius * supersample, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def gradient(size: int, top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    """Vertical gradient. A flat fill looks cheap at icon sizes."""
    g = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / (size - 1)
        g.putpixel(
            (0, y),
            tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)),
        )
    return g.resize((size, size), Image.BILINEAR)


def draw_glyph(size: int, supersample: int = 4) -> Image.Image:
    """The download arrow + tray, drawn large then downsampled for smooth edges."""
    s = size * supersample
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    u = s / 1024  # work in 1024-space coordinates

    # Arrow shaft
    shaft_half_w = 62 * u
    shaft_top = 236 * u
    shaft_bottom = 566 * u
    d.rounded_rectangle(
        [512 * u - shaft_half_w, shaft_top, 512 * u + shaft_half_w, shaft_bottom],
        radius=26 * u,
        fill=GLYPH + (255,),
    )

    # Arrow head — a triangle whose top edge overlaps the shaft so the join is
    # seamless rather than showing a hairline seam at small sizes.
    head_half_w = 176 * u
    head_top = 520 * u
    head_bottom = 706 * u
    d.polygon(
        [
            (512 * u - head_half_w, head_top),
            (512 * u + head_half_w, head_top),
            (512 * u, head_bottom),
        ],
        fill=GLYPH + (255,),
    )

    # Tray: the surface the arrow lands on.
    tray_half_w = 268 * u
    tray_h = 66 * u
    tray_top = 772 * u
    d.rounded_rectangle(
        [512 * u - tray_half_w, tray_top, 512 * u + tray_half_w, tray_top + tray_h],
        radius=tray_h / 2,
        fill=GLYPH + (255,),
    )

    return img.resize((size, size), Image.LANCZOS)


def build_source() -> Path:
    ICON_DIR.mkdir(parents=True, exist_ok=True)

    radius = int(SIZE * 0.225)  # ~ the macOS/Windows squircle proportion
    mask = rounded_mask(SIZE, radius)
    bg = gradient(SIZE, ACCENT_TOP, ACCENT_BOTTOM)

    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    out.alpha_composite(draw_glyph(SIZE))

    # Slight inner highlight along the top edge reads as depth on a dock/taskbar.
    hl = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hd.rounded_rectangle(
        [8, 8, SIZE - 9, SIZE - 9], radius=radius - 8, outline=(255, 255, 255, 46), width=6
    )
    out.alpha_composite(hl)

    out.save(SOURCE, "PNG")
    return SOURCE


def run_tauri_icon(source: Path) -> bool:
    """`tauri icon` derives every platform format from one square PNG.

    Producing .icns and .ico by hand is genuinely fiddly (icns is a container of
    specific sizes; ico needs embedded PNG/DIB entries), so defer to the CLI.
    """
    cmd = ["npx", "--no-install", "tauri", "icon", str(source), "--output", str(ICON_DIR)]
    print("  " + " ".join(cmd))
    result = subprocess.run(cmd, cwd=str(ROOT), check=False)
    return result.returncode == 0


def main() -> int:
    source = build_source()
    print(f"drew source icon: {source} ({source.stat().st_size / 1024:.0f} KB)")

    if not run_tauri_icon(source):
        print(
            "\n`tauri icon` failed (it needs the @tauri-apps/cli npm package).\n"
            "The source PNG is still written; you can generate the rest with:\n"
            f"    npx tauri icon {source.relative_to(ROOT)}",
            file=sys.stderr,
        )
        return 1

    produced = sorted(p.name for p in ICON_DIR.iterdir() if p.is_file())
    print(f"\ngenerated {len(produced)} icon files:")
    for name in produced:
        print(f"  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
