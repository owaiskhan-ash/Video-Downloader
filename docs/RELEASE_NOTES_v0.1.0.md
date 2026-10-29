# v0.1.0 — Video Downloader

Paste a link, get the video in HD — as a file that actually plays everywhere.

First release. The engine, desktop shell, web UI and Android packaging are all
in place; **109 tests pass**.

## What's in it

### Engine (Python, yt-dlp + ffmpeg)
- Probe any of 1800+ sites: title, thumbnail, duration, full format list — before downloading anything.
- Quality presets (4K / 1080p / 720p / 480p / audio-only) with per-preset yt-dlp selectors.
- **Maximum compatibility (default):** delivers H.264 video + AAC audio in `.mp4`. The highest-quality stream a site offers is usually VP9/AV1 in `.webm`, which won't play on older TVs, iMessage, PowerPoint or many phones — so compatibility mode prefers H.264/AAC, then inspects the result and transcodes only when the source wasn't already compatible.
- HD is two streams, not one file: video and audio are fetched separately (DASH) and muxed with `ffmpeg -c copy` — no re-encode, no quality loss.
- Precise clip/trim, subtitles, thumbnails, metadata, playlist mode, resume on failure.
- Structured error codes with actionable hints (age-restricted, region-locked, private, disk full, …) instead of tracebacks.
- SSRF guard, scheme allow-list, byte-safe filename sanitising.

### Desktop (Tauri) + Web UI
- Rust shell spawns the engine and relays its JSON event stream; job registry survives a window reload mid-download.
- Framework-free TypeScript UI: paste → preview → quality picker → live progress with speed/ETA and per-stage status.
- Runs in a browser too, over HTTP+SSE, with no Rust required (`python3 devserver.py`).

### Android
- python-for-android APK: WebView around the same HTTP+SSE server, CPython + yt-dlp embedded. See `docs/ANDROID.md`.

## Getting the APK

The APK is **built by CI and attached to this release** — restricted
environments cannot compile one (Android build-tools and Gradle only download
from hosts they block), so GitHub's runners are the build machine.

- Watch it: **Actions → android-apk** for the `v0.1.0` tag run.
- When the run finishes, `video-downloader-v0.1.0-android-debug.apk` appears in
  the run's **Artifacts**, and is uploaded to this release automatically.
- Build it yourself instead: `bash android/prepare.sh && cd android && buildozer android debug`.

## Building from source

```bash
pip install -r requirements.txt && python3 scripts/setup_engine.py
npm install && npm run build
python3 devserver.py                # browser UI on :8787
npm run tauri:dev                   # desktop app (needs Rust)
python3 -m pytest tests/ -q         # 109 passed
```

## Known limitations

- The Rust shell and the APK have not been compiled in a restricted sandbox (no Rust toolchain, no Android SDK); CI and local builds are the verification path.
- Live-site extraction can't be exercised from restricted networks; the suite validates against real ffmpeg-generated samples over localhost.
- Android: no background downloads yet (killing the app stops the download); first launch is slow while p4a extracts Python.
