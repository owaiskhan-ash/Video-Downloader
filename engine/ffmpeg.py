"""
Locating a usable ffmpeg.

ffmpeg is NOT optional for HD downloads: YouTube and friends serve video and
audio as separate DASH streams, so 1080p+ requires downloading both and muxing
them. Without ffmpeg you are limited to low-quality pre-merged formats.

We look in this order:
  1. $FFMPEG_PATH                     (explicit override)
  2. ffmpeg on PATH                   (user already has it)
  3. bundled binary next to the app   (resources/bin/ffmpeg-<target-triple>)
  4. imageio-ffmpeg wheel's static build (dev convenience — no root needed)

If none is found we do NOT crash immediately: single-file downloads still work,
so we degrade and warn instead.
"""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Optional

# Tauri sidecar/resource naming uses the Rust target triple.
_TARGET_TRIPLE = {
    ("Linux", "x86_64"): "x86_64-unknown-linux-gnu",
    ("Linux", "aarch64"): "aarch64-unknown-linux-gnu",
    ("Darwin", "x86_64"): "x86_64-apple-darwin",
    ("Darwin", "arm64"): "aarch64-apple-darwin",
    ("Windows", "AMD64"): "x86_64-pc-windows-msvc.exe",
}


def target_triple() -> str:
    return _TARGET_TRIPLE.get(
        (platform.system(), platform.machine()),
        f"{platform.system().lower()}-{platform.machine()}",
    )


def _bundled_candidates() -> list[Path]:
    """Places a packaged build would put ffmpeg, relative to this file."""
    here = Path(__file__).resolve()
    roots = [here.parent.parent, Path(sys.executable).resolve().parent]
    names = ["ffmpeg", f"ffmpeg-{target_triple()}"]
    if os.name == "nt":
        names += ["ffmpeg.exe", f"ffmpeg-{target_triple()}.exe"]

    out: list[Path] = []
    for root in roots:
        for sub in ("resources/bin", "bin", "", "_internal/resources/bin"):
            for name in names:
                out.append(root / sub / name)
    return out


def _imageio_ffmpeg() -> Optional[Path]:
    """Dev fallback: imageio-ffmpeg ships a static ffmpeg in its wheel.

    This is how the sandbox got a working ffmpeg with no root and no apt.
    Good for development; for distribution prefer a bundled binary so you
    don't ship a 30 MB wheel as an implicit dependency.
    """
    try:
        import imageio_ffmpeg  # type: ignore

        return Path(imageio_ffmpeg.get_ffmpeg_exe())
    except Exception:
        return None


def find_ffmpeg() -> Optional[Path]:
    env = os.environ.get("FFMPEG_PATH")
    if env:
        p = Path(env).expanduser()
        if p.is_file():
            return p

    on_path = shutil.which("ffmpeg")
    if on_path:
        return Path(on_path)

    for cand in _bundled_candidates():
        if cand.is_file():
            return cand

    return _imageio_ffmpeg()


def ensure_executable(p: Path) -> Path:
    """Bundled binaries lose their +x bit through some archives/CIs."""
    if os.name != "nt":
        try:
            mode = p.stat().st_mode
            p.chmod(mode | 0o111)
        except OSError:
            pass
    return p


def ffmpeg_version(binary: Path) -> Optional[str]:
    try:
        out = subprocess.run(
            [str(binary), "-version"],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        return out.stdout.splitlines()[0] if out.stdout else None
    except Exception:
        return None


def has_encoder(binary: Path, encoder: str) -> bool:
    """Check the build actually supports libx264 / aac before promising it."""
    try:
        out = subprocess.run(
            [str(binary), "-hide_banner", "-encoders"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except Exception:
        return False
    return any(encoder in line for line in out.stdout.splitlines())


def resolve() -> tuple[Optional[Path], list[str]]:
    """Returns (path_or_None, human_readable_warnings)."""
    warnings: list[str] = []
    path = find_ffmpeg()

    if path is None:
        warnings.append(
            "ffmpeg not found. Downloads will fall back to pre-merged formats "
            "(usually 360p or lower) and cannot be transcoded for compatibility. "
            "Install ffmpeg, or set FFMPEG_PATH."
        )
        return None, warnings

    path = ensure_executable(path)

    if not has_encoder(path, "libx264"):
        warnings.append(
            f"{path.name} was built without libx264 — 'Maximum compatibility' "
            "mode will not be able to transcode VP9/AV1 sources."
        )

    return path, warnings
