#!/usr/bin/env bash
# Stage the shared code into android/ so buildozer can package it.
#
# buildozer's source.dir must contain main.py, and it packages what it finds
# there. Rather than duplicate the engine or the UI into this directory (two
# copies that would drift), CI and local builds copy them in here, and
# .gitignore keeps the copies out of git.
#
# Usage:  bash android/prepare.sh
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$REPO/android"

echo "==> copying engine/ and dist/ into android/"

# The engine is imported by devserver.py and main.py at runtime.
rm -rf "$ANDROID_DIR/engine" "$ANDROID_DIR/dist"
cp -r "$REPO/engine" "$ANDROID_DIR/engine"
find "$ANDROID_DIR/engine" -name '__pycache__' -type d -prune -exec rm -rf {} +

# The built UI. Must exist; build it first if not.
if [ ! -f "$REPO/dist/index.html" ]; then
  echo "    dist/ missing - building frontend (npm run build)"
  (cd "$REPO" && npm install --no-audit --no-fund && npm run build)
fi
cp -r "$REPO/dist" "$ANDROID_DIR/dist"

# devserver.py is reused verbatim as the in-app server.
cp "$REPO/devserver.py" "$ANDROID_DIR/devserver.py"

# Assets dir holds the native ffmpeg binaries (see ffmpeg_ndk_build.sh).
mkdir -p "$ANDROID_DIR/assets/ffmpeg"
if compgen -G "$ANDROID_DIR/assets/ffmpeg/*/ffmpeg" > /dev/null; then
  echo "==> bundled ffmpeg:"
  du -h "$ANDROID_DIR"/assets/ffmpeg/*/ffmpeg | sed 's/^/    /'
else
  echo "==> WARNING: no ffmpeg binaries in android/assets/ffmpeg/"
  echo "    The APK will build, but HD muxing and compatibility transcoding"
  echo "    will be unavailable on device. Run android/ffmpeg_ndk_build.sh first."
fi

echo "==> staged:"
du -sh "$ANDROID_DIR/engine" "$ANDROID_DIR/dist" | sed 's/^/    /'
echo "done. Next: cd android && buildozer android debug"
