#!/usr/bin/env python3
"""
Proof-of-concept for the core "paste a link -> grab it in HD" engine.

This is the exact skeleton the real app would use. It demonstrates the four
hard parts in ~80 lines:

  1. Resolve a pasted URL into available formats (yt-dlp extractors).
  2. Pick the best HD video + best audio (format selection).
  3. Stream both to disk while reporting real progress (progress hooks).
  4. Mux them into one .mp4 WITHOUT re-encoding (ffmpeg -c copy).

Usage:
    python3 poc/engine_poc.py <url> [output_dir]

Tested locally with:
    python3 -m http.server 8899 &        # serve a folder containing a video
    python3 poc/engine_poc.py http://127.0.0.1:8899/in.mp4
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import yt_dlp

# Where to find ffmpeg. `imageio-ffmpeg` ships a static build, which avoids
# making the user install ffmpeg by hand. Override with $FFMPEG if needed.
FFMPEG = shutil.which("ffmpeg") or str(Path.home() / ".cache/ffmpeg/ffmpeg")


def progress_hook(d: dict) -> None:
    """Called continuously by yt-dlp. This is what you pipe to the frontend."""
    if d["status"] == "downloading":
        total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
        done = d.get("downloaded_bytes", 0)
        pct = (done / total * 100) if total else 0.0
        speed = d.get("speed") or 0
        eta = d.get("eta")
        bar = "#" * int(pct // 4)
        print(
            f"\r  [{bar:<25}] {pct:5.1f}%  "
            f"{speed / 1e6:5.2f} MB/s  ETA {eta if eta is not None else '?'}s   ",
            end="",
            flush=True,
        )
    elif d["status"] == "finished":
        print("\n  -> stream complete")


def download(url: str, outdir: Path) -> Path | None:
    outdir.mkdir(parents=True, exist_ok=True)

    # `bv*+ba/b` = best video (any codec) + best audio, fall back to best
    # pre-merged single file. This is the "give me HD" selector.
    opts = {
        "format": "bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b",
        "outtmpl": str(outdir / "%(title).80B [%(id)s].%(ext)s"),
        "merge_output_format": "mp4",
        "ffmpeg_location": FFMPEG,
        "noplaylist": True,
        "progress_hooks": [progress_hook],
        "quiet": True,
        "no_warnings": True,
        # Gotcha: `quiet` alone does NOT silence yt-dlp's own progress printer.
        # Without `noprogress` you get two competing progress lines interleaved
        # in stdout, which corrupts any log you stream to a browser.
        "noprogress": True,
        "retries": 3,
    }

    print(f"[1/4] Resolving: {url}")
    with yt_dlp.YoutubeDL(opts) as ydl:
        # --- Step 1: inspect what the site actually offers -----------------
        info = ydl.extract_info(url, download=False)

        print(f"[2/4] Title : {info.get('title')}")
        print(f"      Site  : {info.get('extractor')}")
        print(f"      Length: {info.get('duration')}s")
        print("      Available formats:")
        for f in (info.get("formats") or [])[-8:]:
            kind = "video" if f.get("vcodec") != "none" else "audio"
            print(
                f"        {f.get('format_id'):>6}  {kind:<5} "
                f"{f.get('height') or '-':>4}p  {f.get('vcodec') or f.get('acodec')}"
            )

        # --- Steps 3 + 4: download and (if needed) mux ---------------------
        print("[3/4] Downloading best HD video + audio ...")
        result = ydl.extract_info(url, download=True)
        path = Path(ydl.prepare_filename(result))

    # yt-dlp already muxed for us when ffmpeg is present; this is the manual
    # equivalent if you ever do the two-stream download yourself:
    #   ffmpeg -i video.mp4 -i audio.m4a -c copy -movflags +faststart out.mp4
    print(f"[4/4] Done -> {path}  ({path.stat().st_size / 1e6:.1f} MB)")
    return path


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    target = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("./downloads")
    try:
        download(sys.argv[1], target)
    except subprocess.CalledProcessError as e:
        sys.exit(f"ffmpeg failed: {e}")
    except Exception as e:  # noqa: BLE001
        sys.exit(f"Download failed: {type(e).__name__}: {e}")
