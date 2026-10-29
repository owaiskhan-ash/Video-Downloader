# Bundled resources

This directory is committed **on purpose, but empty of binaries**.

`tauri.conf.json` maps it into the app bundle:

```json
"resources": { "../resources": "resources" }
```

Tauri errors at build time if a mapped resource path does not exist, so the
directory itself must survive in git. It is kept non-empty by this file.

## What goes here (and why it is NOT in git)

`resources/bin/ffmpeg` — a static ffmpeg build, fetched by:

```bash
python3 scripts/setup_engine.py
```

Two reasons the binary stays out of version control:

1. **Size.** A static ffmpeg is ~80 MB per platform. Committing three
   platforms would blow past any sensible repo budget for a blob anyone can
   re-fetch.
2. **Reproducibility.** The setup script pins and verifies the upstream build,
   so the binary is always regenerable — unlike a committed copy that quietly
   rots.

At runtime `engine/ffmpeg.py` resolves in this order:

1. `$FFMPEG_PATH`
2. `ffmpeg` on `PATH`
3. **the bundled copy in this directory** ← what packaging relies on
4. the `imageio-ffmpeg` wheel's static build (development convenience)

If you distribute the app, run `scripts/setup_engine.py` before
`npm run tauri:build` so the bundled ffmpeg is included. Without it the app
still runs but degrades to low-quality pre-merged streams and cannot honour
"Maximum compatibility" for VP9/AV1 sources — the status badge warns about this
at launch rather than failing mid-download.
