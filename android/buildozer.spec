# Buildozer spec — packages the Python engine + web UI into an APK.
#
# WHY BUILDOZER AND NOT TAURI MOBILE:
#   The engine is Python (yt-dlp has no Rust/JS equivalent with 1800+
#   extractors). Tauri's Android target ships a Rust shell with no Python
#   runtime, so it cannot run the engine. python-for-android embeds CPython,
#   which is the only way this architecture reaches Android.
#
# The APK is a thin shell: p4a's webview bootstrap shows a WebView, and main.py
# starts the same HTTP+SSE server the browser dev preview uses (devserver.py),
# pointed at 127.0.0.1. One engine, three frontends: Tauri desktop, browser,
# Android.
#
# Build:
#   pip install buildozer
#   bash android/prepare.sh          # copies engine/ + dist/ in
#   cd android && buildozer android debug
#
# CI (.github/workflows/android-apk.yml) does exactly this on a GitHub runner,
# because the Android SDK/NDK cannot be downloaded in restricted sandboxes.

[app]
title = Video Downloader
package.name = videodownloader
package.domain = app.videodownloader

# android/ after prepare.sh has copied engine/ and dist/ inside it.
source.dir = .
source.include_exts = py,html,js,css,json,png,jpg,jpeg,svg,webmanifest,txt
source.exclude_patterns =
    */__pycache__/*,
    *.pyc,
    .build/*,
    bin/*,
    tests/*

version = 0.1.0

# python3      – the interpreter the engine runs on
# pyjnius      – lets main.py reach the WebView / activity from Python
# yt-dlp       – pure Python, installs from PyPI into the APK
#   (imageio-ffmpeg is deliberately NOT here: on Android we ship a native
#    ffmpeg binary cross-compiled by android/ffmpeg_ndk_build.sh instead)
requirements = python3,pyjnius,yt-dlp

# webview bootstrap: native WebView + our Python on a background thread.
# This is what makes "Python backend + web frontend" work without Kivy.
android.bootstrap = webview

# The bootstrap, if it honours manifest meta-data, loads this URL. main.py also
# sets it explicitly via pyjnius as a fallback, so a bootstrap that ignores
# meta-data still ends up on the right page.
android.meta_data = url=http://127.0.0.1:8787/

android.permissions = INTERNET, ACCESS_NETWORK_STATE

# arm64 covers essentially all phones since 2017; armeabi-v7a keeps older
# devices working. x86 is omitted — emulators only, and it doubles APK size.
android.archs = arm64-v8a, armeabi-v7a

android.api = 34
android.minapi = 24
android.allow_backup = True

# Loopback HTTP: Android's default network security config permits cleartext to
# 127.0.0.1, which is what the embedded server listens on. If a device/ROM
# disagrees, the symptom is a blank WebView, and the fix is a network security
# config — see docs/ANDROID.md.
android.enable_androidx = True

# The ffmpeg binary and the built UI are packaged as app assets and unpacked to
# the private files dir at first run (assets ship compressed and non-executable).
android.add_assets = assets

p4a.source_dir = .

[buildozer]
buildozer.directory = .build
log_level = 2
warn_on_root = 1
