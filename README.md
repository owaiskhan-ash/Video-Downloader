# Video Downloader

Paste a link, get the video in HD — as a file that actually plays everywhere.

A **Tauri desktop app**: Python engine (yt-dlp + ffmpeg) behind a Rust shell,
with a web UI. Defaults to 1080p and **H.264/AAC `.mp4`**, because the
highest-quality stream a site offers is usually VP9 or AV1 in `.webm`, which
won't play on older TVs, iMessage, PowerPoint or many phones.

```
paste URL → probe (title, thumbnail, formats) → pick quality
          → download video + audio streams → mux (no re-encode)
          → transcode only if the source wasn't already H.264/AAC
```

## Why "HD" is harder than it looks

HD video is **not a single file**. YouTube, Vimeo and TikTok serve video and
audio as separate DASH streams:

```
format 137  →  1080p video only   (vcodec=avc1, acodec=none)
format 140  →  128k audio only    (vcodec=none, acodec=mp4a)
```

So an HD download fetches both and muxes them:

```bash
ffmpeg -i video.mp4 -i audio.m4a -c copy -movflags +faststart out.mp4
```

`-c copy` remuxes without re-encoding — about a second for a 10-minute video,
zero quality loss. **This is why ffmpeg is a hard dependency:** without it you
are capped at low-quality pre-merged formats, often 360p.

## Quick start

```bash
# 1. engine dependencies
pip install -r requirements.txt
python3 scripts/setup_engine.py          # fetches a static ffmpeg

# 2. verify it works
python3 -m engine doctor

# 3. run the UI in a browser (no Rust needed)
npm install && npm run build
python3 devserver.py                     # http://localhost:8787
```

For the real desktop app you also need [Rust](https://rustup.rs) and the
[Tauri prerequisites](https://tauri.app/start/prerequisites/):

```bash
python3 scripts/setup_engine.py --sidecar   # bundle engine as a PyInstaller sidecar
npm run tauri:dev                           # develop
npm run tauri:build                         # produce an installer
```

## Android

The same engine and UI ship as an APK via python-for-android — a WebView around
the local HTTP+SSE server, with CPython + yt-dlp embedded. Tauri mobile is not
used because it has no Python runtime, and the engine *is* Python.

**Installable debug APK:** see the
[latest release](https://github.com/owaiskhan-ash/Video-Downloader/releases) —
`video-downloader-<tag>-android-debug.apk`, ~30 MB, `arm64-v8a` + `armeabi-v7a`.
Sideload it (allow "install unknown apps"); it is a debug build, so no keystore.

```bash
git tag v0.2.0 && git push origin v0.2.0    # CI builds + attaches the APK (~25 min)
```

or locally: `bash android/prepare.sh && cd android && buildozer android debug`.

Details, the pinned toolchain that CI verified, build traps and known rough
edges: **[docs/ANDROID.md](docs/ANDROID.md)**.

## Layout

```
engine/          Python engine — the part that does the work
  main.py        probe / download, format selection, transcode
  protocol.py    the newline-delimited JSON event contract
  safety.py      URL validation, SSRF guard, filename sanitising
  ffmpeg.py      locates a usable ffmpeg
src-tauri/       Rust shell — spawns the engine, relays its events
src/             UI (TypeScript, no framework)
devserver.py     same engine over HTTP+SSE, for browser development
scripts/         dependency setup and sidecar build
tests/           109 tests, engine only — no Rust or browser required
docs/
  ARCHITECTURE.md   how it fits together and why
  IDEAS.md          the original design exploration and roadmap
poc/               the original standalone prototype
```

## Tests

```bash
python3 -m pytest tests/ -q        # 109 passed
```

The suite covers the parts that are easy to get subtly wrong:

- **SSRF and scheme smuggling** — `javascript:` and `data:` URLs use a bare
  colon, so a `://`-based check auto-prefixes them to `https://` and lets them
  through the allow-list.
- **Codec tri-state** — yt-dlp means "definitely absent" by the string `"none"`
  and "unknown" by `None`. Conflating them labels an ordinary 1080p `.mp4` as
  audio and hides it from every video preset.
- **Transcode in both directions** — a VP9 source must be re-encoded; an
  H.264 source must be left alone.
- **stdout purity** — `quiet: True` alone does *not* silence yt-dlp's progress
  printer; `noprogress: True` is also required, or two progress lines interleave
  and corrupt the event stream.

End-to-end tests encode real sample videos with ffmpeg and serve them over
localhost; they skip cleanly when ffmpeg is unavailable.

## Status

Engine complete and tested. UI complete. Rust shell written but **not yet
compiled** — the sandbox has no Rust toolchain or webkit2gtk, so build it on
your own machine.

Live site extraction (YouTube etc.) also can't be verified in the sandbox: only
github/npm/pypi are reachable there. Everything was validated against locally
served video files.

## Legal

Only download videos you have the rights to. Most platforms' terms prohibit
downloading, and much of what they host is other people's copyrighted work. A
personal/local tool is a defensible thing to build; a public service that
downloads arbitrary links is what gets DMCA'd and de-platformed.

DRM-protected streams (Netflix, Prime, Disney+) are not supported and must not
be circumvented — that is illegal in most jurisdictions.
