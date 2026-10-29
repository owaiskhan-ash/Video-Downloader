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

Push a tag:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

The workflow builds the UI, cross-compiles ffmpeg with the NDK, runs buildozer,
and attaches `video-downloader-<tag>-android-debug.apk` to the release for that
tag. Download it from the release page, or from the run's **Artifacts** section.

A full build takes ~25 minutes from scratch (no caching — see below). The first
verified green run was **v0.1.14**, producing a **31.8 MB** debug APK.

This is the toolchain combination CI proved works, all pinned deliberately:

| Piece | Version | Why pinned |
|---|---|---|
| Runner image | `ubuntu-22.04` | libtool 2.4.6 still defines `LT_SYS_SYMBOL_USCORE`, which the libffi p4a builds calls. 2.4.7 (24.04) and 2.5 (26) removed it → `autoreconf` dies ~20 min in |
| python-for-android | `v2024.01.21` | hostpython3 = CPython **3.11.5**. master and v2026.05.09 build CPython 3.14, whose `venv` ships a self-inconsistent pip → `ImportError: cannot import name 'BuildDependencyInstallError'` |
| JDK | Temurin **17** | runner default (25) is too new for this p4a's Gradle plugin |
| NDK | **r25b** (fetched by buildozer) | p4a v2024.01.21's choice; caps `android.api` at **33** |
| Cython | 3.0.11 | buildozer/p4a compatibility |
| `android.api` | 33 | see NDK row above |

### Option 2 — build locally

Needs: JDK 17, Android SDK + NDK r25b, and ~1 GB of disk. Buildozer installs
anything missing on first run.

```bash
pip install buildozer cython==3.0.11 setuptools
npm install && npm run build          # the UI, packaged as APK assets
bash android/prepare.sh              # copies engine/ + dist/ into android/
ANDROID_NDK=$ANDROID_HOME/ndk/<ver> bash android/ffmpeg_ndk_build.sh both
cd android && buildozer android debug
# -> android/bin/VideoDownloader-0.1-arm64-v8a_armeabi-v7a-debug.apk
```

Install with `adb install <apk>` or by copying it to the device. It is a
**debug** build, so it is sideloadable with no keystore.

### Build traps worth knowing before you touch the CI

Every one of these cost a full ~20-minute CI iteration to find:

- **`p4a.source_dir` must stay absent.** Setting it to `.` points buildozer at a
  local p4a checkout that doesn't exist → `Failed to read python-for-android
  setup.py at ./setup.py`. The key is `p4a.bootstrap`, not the deprecated
  `android.bootstrap`.
- **Don't export `ANDROIDSDK` / `ANDROID_NDK_HOME` expecting buildozer to
  honour them.** It ignores all of them and manages its own toolchain under
  `~/.buildozer/android/platform/`.
- **Seed that directory completely, or not at all.** A dir containing only
  `licenses/` makes buildozer believe an SDK exists, skip installing
  cmdline-tools, then die with `sdkmanager ... does not exist`. The workflow
  copies the runner's *whole* licensed SDK into place.
- **This buildozer looks for `<sdk>/tools/bin/sdkmanager`** — a path no modern
  SDK ships (it's `cmdline-tools/latest/bin/`). The workflow symlinks the legacy
  path onto the real binaries.
- **Accept SDK licences before buildozer runs.** It invokes `sdkmanager`
  non-interactively, so `Accept? (y/N)` gets EOF and defaults to *no*:
  build-tools is silently skipped and you get a misleading `Aidl not found`.
- **No caching of build state.** `~/.buildozer` restored *after* seeding
  clobbered the seeded SDK; `android/.build` restored a venv whose pip was
  mid-upgrade when an earlier run died, giving a site-packages with mixed pip
  versions. A from-scratch build costs ~20 min; a poisoned cache costs a wasted
  iteration each time.

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
