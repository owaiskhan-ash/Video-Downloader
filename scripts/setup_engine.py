#!/usr/bin/env python3
"""
Fetch the engine's binary dependencies and (optionally) build the Tauri sidecar.

    python3 scripts/setup_engine.py              # ffmpeg + verify
    python3 scripts/setup_engine.py --sidecar    # also build binaries/engine

Why this script exists:

  * ffmpeg is NOT optional. HD video on YouTube/Vimeo/TikTok is served as
    separate DASH video and audio streams, so 1080p+ requires muxing. Without
    ffmpeg you are limited to low-quality pre-merged formats (often 360p).
  * Requiring users to install ffmpeg by hand is the single biggest source of
    "it doesn't work" reports, so we fetch a known-good static build instead.
  * Tauri's `externalBin` expects a PyInstaller onefile binary named
    `binaries/engine-<target-triple>`; that name is fiddly to get right by hand.

Static ffmpeg sources (all official upstream release assets):
  linux   -> johnvansickle.com/ffmpeg  (the build imageio-ffmpeg also ships)
  macOS   -> evermeet.cx/ffmpeg        (long-standing osxexperts builds)
  windows -> gyan.dev/ffmpeg/builds    (the build most Windows tooling uses)
"""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BIN_DIR = ROOT / "src-tauri" / "binaries"
RESOURCES_DIR = ROOT / "resources" / "bin"

# Minimal sanity floor: +faststart needs >= 2.x, AV1 decode needs >= 4.x.
MIN_FFMPEG_MAJOR = 4


def target_triple() -> str:
    """Rust target triple for this host — Tauri names sidecars with it."""
    system = platform.system()
    machine = platform.machine().lower()

    if system == "Linux":
        arch = "aarch64" if machine in ("aarch64", "arm64") else "x86_64"
        return f"{arch}-unknown-linux-gnu"
    if system == "Darwin":
        arch = "aarch64" if machine in ("arm64", "aarch64") else "x86_64"
        return f"{arch}-apple-darwin"
    if system == "Windows":
        return "x86_64-pc-windows-msvc.exe"
    raise SystemExit(f"Unsupported platform: {system}/{machine}")


def sidecar_path() -> Path:
    """Where Tauri expects the sidecar source to live."""
    return BIN_DIR / f"engine-{target_triple()}"


def ffmpeg_url() -> tuple[str, str]:
    """(url, kind) for a static ffmpeg build for this platform."""
    system = platform.system()
    machine = platform.machine().lower()

    if system == "Linux":
        arch = "arm64" if machine in ("aarch64", "arm64") else "amd64"
        return (
            f"https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-{arch}-static.tar.xz",
            "tar.xz",
        )
    if system == "Darwin":
        return ("https://evermeet.cx/ffmpeg/getrelease/ffmpeg/zip", "zip")
    if system == "Windows":
        return (
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
            "zip",
        )
    raise SystemExit(f"Unsupported platform: {system}")


def download(url: str, dest: Path, *, timeout: int = 600) -> Path:
    print(f"  downloading {url}")
    req = urllib.request.Request(url, headers={"user-agent": "video-downloader-setup/0.1"})
    with urllib.request.urlopen(req, timeout=timeout) as resp, dest.open("wb") as fh:
        total = int(resp.headers.get("content-length") or 0)
        done = 0
        while chunk := resp.read(1024 * 512):
            fh.write(chunk)
            done += len(chunk)
            if total:
                pct = done / total * 100
                print(f"\r  {pct:5.1f}%  {done / 1e6:6.1f} MB", end="", flush=True)
        if total:
            print()
    return dest


def extract_ffmpeg(archive: Path, kind: str, out_dir: Path) -> Path | None:
    """Pull the ffmpeg executable out of whichever archive format we got."""
    out_dir.mkdir(parents=True, exist_ok=True)

    if kind == "zip":
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():
                base = Path(name).name.lower()
                if base in ("ffmpeg", "ffmpeg.exe"):
                    target = out_dir / Path(name).name
                    with z.open(name) as src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    return target
        return None

    if kind in ("tar.xz", "tar.gz"):
        mode = "r:xz" if kind == "tar.xz" else "r:gz"
        with tarfile.open(archive, mode) as t:
            for member in t.getmembers():
                if not member.isfile():
                    continue
                base = Path(member.name).name.lower()
                if base in ("ffmpeg", "ffmpeg-static"):
                    src = t.extractfile(member)
                    if src is None:
                        continue
                    target = out_dir / "ffmpeg"
                    with target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    return target
        return None

    return None


def make_executable(path: Path) -> None:
    """Archives and some CIs drop the +x bit; a non-executable ffmpeg fails
    with a confusing PermissionError deep inside a download."""
    if os.name == "nt":
        return
    try:
        mode = path.stat().st_mode
        path.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    except OSError as e:
        print(f"  warning: could not chmod {path}: {e}", file=sys.stderr)


def ffmpeg_major_version(binary: Path) -> int | None:
    try:
        out = subprocess.run(
            [str(binary), "-version"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except Exception:
        return None
    first = (out.stdout or "").splitlines()[0] if out.stdout else ""
    # "ffmpeg version 7.0.2-static ..." or "ffmpeg version n6.0 ..."
    for tok in first.replace("ffmpeg version", "").split():
        tok = tok.lstrip("nNv")
        if tok and tok[0].isdigit():
            try:
                return int(tok.split(".")[0])
            except ValueError:
                continue
    return None


def ensure_ffmpeg(*, force: bool = False) -> Path | None:
    """Make sure a usable ffmpeg exists in resources/bin. Returns its path."""
    RESOURCES_DIR.mkdir(parents=True, exist_ok=True)
    name = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
    dest = RESOURCES_DIR / name

    if dest.is_file() and not force:
        print(f"ffmpeg already present: {dest}")
        return dest

    # Prefer an existing system ffmpeg if it is new enough — no download needed.
    system = shutil.which("ffmpeg")
    if system and not force:
        major = ffmpeg_major_version(Path(system))
        if major and major >= MIN_FFMPEG_MAJOR:
            print(f"using system ffmpeg {major}.x: {system}")
            shutil.copy2(system, dest) if Path(system).is_file() else None
            if dest.is_file():
                return dest
            # `which` may resolve to a wrapper; fall through to downloading.
            print("  could not copy system ffmpeg, downloading a static build")

    url, kind = ffmpeg_url()
    print(f"fetching static ffmpeg for {platform.system()}/{platform.machine()}")

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        archive = tmp_path / f"ffmpeg.{kind}"
        try:
            download(url, archive)
        except Exception as e:  # noqa: BLE001
            print(f"\n  download failed: {type(e).__name__}: {e}", file=sys.stderr)
            print(
                "  Network access to the ffmpeg host may be blocked.\n"
                "  Manual fallback: install ffmpeg yourself and set FFMPEG_PATH,\n"
                "  or `pip install imageio-ffmpeg` (the engine finds it automatically).",
                file=sys.stderr,
            )
            return None

        print(f"  archive sha256 {hashlib.sha256(archive.read_bytes()).hexdigest()[:16]}…")
        found = extract_ffmpeg(archive, kind, tmp_path / "x")
        if found is None:
            print("  could not locate ffmpeg inside the archive", file=sys.stderr)
            return None

        shutil.copy2(found, dest)

    make_executable(dest)

    major = ffmpeg_major_version(dest)
    if major is None:
        print(f"  warning: {dest} did not report a version", file=sys.stderr)
    elif major < MIN_FFMPEG_MAJOR:
        print(f"  warning: ffmpeg {major}.x is older than {MIN_FFMPEG_MAJOR}.x", file=sys.stderr)
    else:
        print(f"ffmpeg {major}.x ready at {dest}")

    return dest if dest.is_file() else None


def build_sidecar() -> Path | None:
    """Bundle engine/ into a single executable Tauri can ship.

    Without this, every user would need Python + yt-dlp installed — which
    defeats the point of a desktop app.

    --onefile costs ~1s of startup unpacking but produces one file that Tauri's
    externalBin can bundle directly. --collect-all yt_dlp is required: yt-dlp
    loads its extractors dynamically, so PyInstaller's static analysis misses
    them and you get a binary that "supports no sites".
    """
    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        print(
            "pyinstaller is not installed.\n"
            "    pip install pyinstaller\n"
            "then re-run with --sidecar.",
            file=sys.stderr,
        )
        return None

    BIN_DIR.mkdir(parents=True, exist_ok=True)
    target = sidecar_path()

    cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--onefile",
        "--name",
        "engine",
        "--distpath",
        str(BIN_DIR / "dist"),
        "--workpath",
        str(BIN_DIR / "build"),
        "--specpath",
        str(BIN_DIR),
        "--collect-all",
        "yt_dlp",
        # Hidden imports yt-dlp resolves lazily at runtime.
        "--hidden-import",
        "yt_dlp.compat._legacy",
        "--hidden-import",
        "yt_dlp.compat._deprecated",
        "--hidden-import",
        "yt_dlp.utils._legacy",
        "--hidden-import",
        "yt_dlp.utils._deprecated",
        "--noconfirm",
        "--clean",
        str(ROOT / "engine_runner.py"),
    ]

    print("building sidecar with PyInstaller…")
    print("  " + " ".join(cmd))
    result = subprocess.run(cmd, cwd=str(ROOT), check=False)
    if result.returncode != 0:
        print("PyInstaller failed", file=sys.stderr)
        return None

    built = BIN_DIR / "dist" / ("engine.exe" if os.name == "nt" else "engine")
    if not built.is_file():
        print(f"expected output not found: {built}", file=sys.stderr)
        return None

    if target.exists():
        target.unlink()
    shutil.copy2(built, target)
    make_executable(target)

    print(f"sidecar ready: {target}")
    print(f"  ({target.stat().st_size / 1e6:.1f} MB)")
    return target


def verify() -> int:
    """Run the engine's own doctor and report."""
    print("\nverifying engine…")
    env = dict(os.environ)
    ff = RESOURCES_DIR / ("ffmpeg.exe" if os.name == "nt" else "ffmpeg")
    if ff.is_file() and not env.get("FFMPEG_PATH"):
        env["FFMPEG_PATH"] = str(ff)

    result = subprocess.run(
        [sys.executable, "-m", "engine", "doctor"],
        cwd=str(ROOT),
        env=env,
        check=False,
    )
    return result.returncode


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sidecar", action="store_true", help="also build the PyInstaller sidecar for Tauri")
    ap.add_argument("--force-ffmpeg", action="store_true", help="re-download ffmpeg even if present")
    ap.add_argument("--skip-ffmpeg", action="store_true", help="only build the sidecar")
    args = ap.parse_args()

    print(f"platform: {platform.system()} {platform.machine()}")
    print(f"triple  : {target_triple()}")
    print()

    if not args.skip_ffmpeg:
        ensure_ffmpeg(force=args.force_ffmpeg)

    if args.sidecar:
        build_sidecar()

    return verify()


if __name__ == "__main__":
    raise SystemExit(main())
