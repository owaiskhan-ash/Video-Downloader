#!/usr/bin/env python3
"""
PyInstaller entry point for the Tauri sidecar.

`python -m engine` cannot be used as a PyInstaller entry point directly — it
expects a module path, not a runnable script. This thin wrapper gives the
freezer something concrete to start from while keeping all real logic in the
engine package.

Build with:  python3 scripts/setup_engine.py --sidecar

One PyInstaller caveat that matters here: yt-dlp discovers its extractors by
walking its own package at runtime. Static analysis misses them, so the build
must pass `--collect-all yt_dlp` (setup_engine.py does). A binary built without
it starts fine and then reports "Unsupported URL" for every site — a confusing
failure that looks like a network problem.
"""

from __future__ import annotations

import sys

from engine.main import main

if __name__ == "__main__":
    # Freeze support must come before anything that may spawn subprocesses,
    # otherwise a frozen Windows build re-launches itself recursively.
    import multiprocessing

    multiprocessing.freeze_support()
    sys.exit(main(sys.argv))
