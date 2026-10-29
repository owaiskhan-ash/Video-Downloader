# Android

The APK is a thin shell around the same engine and UI everything else uses.

```
┌─────────────────────────────── APK ───────────────────────────────┐
│  p4a webview bootstrap  →  WebView                                │
│                              │  http://127.0.0.1:8787/             │
│  android/main.py  ──────────►│                                    │
│    1. unpack ffmpeg asset    │   src/  (the built web UI)          │
│    2. start devserver.py ────┤     │  /api/* (HTTP + SSE)          │
│    3. loadUrl(...)           │     ▼                              │
│                              │   engine/  (yt-dlp + ffmpeg)       │
└───────────────────────────────────────────────────────────────────┘
```

No Android-specific app logic. `main.py` unpacks the bundled ffmpeg, starts the
identical HTTP+SSE server the browser dev preview runs, and points the WebView
at it. Probing, downloading, muxing, transcoding and progress events are the
shared engine, unmodified.

## Why python-for-android and not Tauri mobile

The engine is Python — yt-dlp has no Rust or JS equivalent with 1800+
extractors. Tauri's Android target ships a Rust shell with **no Python
runtime**, so it cannot run the engine at all. python-for-android embeds
CPython, which is the only way this architecture reaches Android. The cost is a
larger APK (~40–70 MB with ffmpeg + interpreter); the benefit is one engine
maintained in one place for desktop, browser and phone.

## Get an APK

### Option 1 — let CI build it (no local toolchain)

Push a tag, or use **Actions → android-apk → Run workflow**:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

The workflow builds the UI, cross-compiles ffmpeg with the NDK, runs buildozer,
and attaches `video-downloader-<tag>-android-debug.apk` to the release for that
tag. Download it from the release page, or from the run's **Artifacts** section.

### Option 2 — build locally

Needs: JDK 17, Android SDK + NDK, and ~1 GB of disk. Buildozer installs anything
missing on first run.

```bash
pip install buildozer cython setuptools
npm install && npm run build          # the UI, packaged as APK assets
bash android/prepare.sh              # copies engine/ + dist/ into android/
ANDROID_NDK=$ANDROID_HOME/ndk/<ver> bash android/ffmpeg_ndk_build.sh both
cd android && buildozer android debug
# -> android/bin/VideoDownloader-0.1.0-arm64-v8a_armeabi-v7a-debug.apk
```

Install with `adb install <apk>` or by copying it to the device. It is a
**debug** build, so it is sideloadable with no keystore.

## What the APK contains

| Piece | From | Notes |
|---|---|---|
| CPython + yt-dlp | p4a recipes | yt-dlp is pure Python, installs from PyPI |
| Web UI | `dist/` | built by Vite, served from the app's own files |
| Engine + server | `engine/`, `devserver.py` | byte-identical to desktop/browser |
| ffmpeg | `android/ffmpeg_ndk_build.sh` | static binary per arch, unpacked at first run |
| WebView | p4a `webview` bootstrap | no Kivy dependency |

Architectures: `arm64-v8a` and `armeabi-v7a`. x86 is omitted — emulators only,
and it doubles the APK.

## Design decisions worth knowing

**Downloads go to the app-private files dir**, not shared storage. Modern
Android treats shared-storage access as a runtime permission saga; the private
dir needs nothing and survives reinstalls of *data*. The UI's "Show file"
streams the file through `/api/open` so it plays in the WebView.

**Loopback HTTP, not `file://`.** The UI's HTTP bridge talks to `/api/*`
same-origin. Serving the UI from `file:///android_asset/` would break every
fetch. Android's default network security config permits cleartext to
`127.0.0.1`, so no network-security-config XML is needed. If a ROM disagrees,
the symptom is a blank WebView and the fix is a config allowing cleartext to
loopback.

**The WebView is set from Python, defensively.** p4a's webview bootstrap may
load its URL from manifest meta-data (`android.meta_data = url=…` is set, in
case it does). `main.py` also locates the WebView via pyjnius — trying a public
field, then a getter, then constructing one and calling `setContentView` — and
always hops to the UI thread first, because Android throws
`CalledFromWrongThreadException` otherwise. Three fallbacks because this is the
one part that varies between p4a versions and cannot be unit-tested off-device.

**ffmpeg is an asset, unpacked at first run.** APK assets ship compressed and
non-executable, so `main.py` streams the right arch out of the AssetManager to
the files dir, `chmod 0755`, and exports `FFMPEG_PATH` — which the engine's
existing discovery order already honours. Without it the app still runs but
degrades to low-quality pre-merged streams; the status badge says so.

## Known rough edges

- **First launch is slow.** p4a extracts the Python distribution; expect a few
  seconds of blank before the UI appears.
- **No background downloads.** Kill the app and the download dies with it. A p4a
  `service_only` bootstrap could own downloads independently of the UI; that is
  the natural next step.
- **The ffmpeg cross-compile is the slow CI step** (~10 min for both archs) and
  is not cached. Caching `android/assets/ffmpeg/` between runs would cut it.
- **x264 is required on device** because ffmpeg has no native H.264 encoder and
  compatibility mode must be able to transcode VP9/AV1 sources. It is built
  static from the official mirror and linked into the bundled ffmpeg.
