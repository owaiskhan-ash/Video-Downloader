"""
Android entry point (python-for-android / webview bootstrap).

The APK contains no Android-specific app logic. This file does three things and
then gets out of the way:

  1. unpacks the bundled ffmpeg binary to a real, executable file
  2. starts the SAME HTTP+SSE server the browser dev preview runs (devserver.py)
  3. points the bootstrap's WebView at http://127.0.0.1:8787/

Everything else — probing, downloading, muxing, transcoding, progress events —
is the shared engine, unmodified. One engine, three frontends: Tauri desktop,
browser, Android.

Why the WebView is controlled from Python at all: p4a's webview bootstrap may
load its URL from manifest meta-data, or may not, depending on version. Rather
than bet the whole app on that, we set it explicitly via pyjnius and treat the
meta-data route as a bonus.
"""

from __future__ import annotations

import os
import platform
import shutil
import sys
import threading
import time
from pathlib import Path

PORT = 8787
URL = f"http://127.0.0.1:{PORT}/"

# p4a puts the packaged Python code in the app's files dir; devserver/engine
# were copied next to this file by android/prepare.sh.
HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))


def log(msg: str) -> None:
    # p4a forwards stdout to logcat, so print() is our logger.
    print(f"[vd-android] {msg}", flush=True)


# --------------------------------------------------------------------------
# 1. ffmpeg: assets ship compressed and non-executable, so unpack once
# --------------------------------------------------------------------------

def _arch() -> str:
    m = platform.machine().lower()
    if m in ("aarch64", "arm64"):
        return "arm64"
    if m.startswith("armv7"):
        return "armv7"
    return m


def _copy_asset(manager, asset_path: str, dest: Path) -> bool:
    """Stream an APK asset out to the filesystem via the AssetManager."""
    try:
        stream = manager.open(asset_path)
        try:
            dest.parent.mkdir(parents=True, exist_ok=True)
            with dest.open("wb") as out:
                while True:
                    chunk = stream.read(65536)
                    if not chunk:
                        break
                    out.write(chunk)
            return True
        finally:
            stream.close()
    except Exception as e:  # noqa: BLE001 - absence is a normal case
        log(f"asset {asset_path} not present: {type(e).__name__}")
        return False


def install_ffmpeg() -> None:
    """Unpack assets/ffmpeg/<arch>/ffmpeg and export FFMPEG_PATH.

    Without this the engine degrades to low-quality pre-merged streams, so it is
    worth doing properly — but a failure here must not kill the app.
    """
    try:
        from jnius import autoclass  # type: ignore

        activity = autoclass("org.kivy.android.PythonActivity").mActivity
        manager = activity.getAssets()
        files_dir = Path(activity.getFilesDir().getAbsolutePath())
    except Exception as e:  # noqa: BLE001
        log(f"cannot reach AssetManager, skipping ffmpeg unpack: {e}")
        return

    arch = _arch()
    dest = files_dir / "ffmpeg" / "ffmpeg"
    if dest.is_file() and dest.stat().st_size > 0:
        os.chmod(dest, 0o755)
        os.environ["FFMPEG_PATH"] = str(dest)
        log(f"ffmpeg already unpacked: {dest}")
        return

    if _copy_asset(manager, f"ffmpeg/{arch}/ffmpeg", dest):
        os.chmod(dest, 0o755)
        os.environ["FFMPEG_PATH"] = str(dest)
        log(f"ffmpeg unpacked: {dest} ({dest.stat().st_size / 1e6:.1f} MB)")
    else:
        log("WARNING: no bundled ffmpeg; HD muxing and compatibility "
            "transcoding will be unavailable on this device")


# --------------------------------------------------------------------------
# 2. the shared HTTP+SSE server
# --------------------------------------------------------------------------

def start_server() -> None:
    import devserver  # copied in by prepare.sh; identical to the dev preview

    # ~/Downloads does not exist on Android. Use the app-private files dir so
    # downloads survive and need no storage permission on modern Android.
    try:
        from jnius import autoclass  # type: ignore

        activity = autoclass("org.kivy.android.PythonActivity").mActivity
        downloads = Path(activity.getFilesDir().getAbsolutePath()) / "Downloads"
    except Exception:  # noqa: BLE001
        downloads = Path.home() / "Downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    devserver.DEFAULT_OUTDIR = str(downloads)

    server = devserver.ThreadingHTTPServer(("127.0.0.1", PORT), devserver.Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True, name="vd-http").start()
    log(f"engine HTTP server on {URL} (downloads -> {downloads})")


# --------------------------------------------------------------------------
# 3. point the WebView at it
# --------------------------------------------------------------------------

def _run_on_ui_thread(activity, fn) -> None:
    """Android UI objects may only be touched from the UI thread.

    p4a runs main.py on its own thread, so a direct webView.loadUrl() from here
    would throw CalledFromWrongThreadException.
    """
    from jnius import autoclass, PythonJavaClass, java_method  # type: ignore

    Runnable = autoclass("java.lang.Runnable")

    class _Runner(PythonJavaClass):
        __javainterfaces__ = ["java/lang/Runnable"]
        __javacontext__ = "app"

        def __init__(self, fn):
            super().__init__()
            self._fn = fn

        @java_method("()V")
        def run(self):
            self._fn()

    activity.runOnUiThread(_Runner(fn))


def point_webview() -> None:
    """Get the bootstrap's WebView onto our server, trying every known route."""
    try:
        from jnius import autoclass  # type: ignore
    except Exception as e:  # noqa: BLE001
        log(f"pyjnius unavailable; relying on manifest meta-data: {e}")
        return

    try:
        activity = autoclass("org.kivy.android.PythonActivity").mActivity
    except Exception as e:  # noqa: BLE001
        log(f"no PythonActivity handle: {e}")
        return

    def _load():
        view = None
        # Strategy 1: a public webView field (what the webview bootstrap exposes).
        for attr in ("webView", "mWebView", "view"):
            view = getattr(activity, attr, None)
            if view is not None:
                break
        # Strategy 2: a getter.
        if view is None:
            for getter in ("getWebView",):
                fn = getattr(activity, getter, None)
                if callable(fn):
                    try:
                        view = fn()
                    except Exception:  # noqa: BLE001
                        view = None
                    if view is not None:
                        break

        if view is not None:
            view.getSettings().setJavaScriptEnabled(True)
            view.getSettings().setDomStorageEnabled(True)
            view.loadUrl(URL)
            log(f"WebView loaded {URL}")
            return

        # Strategy 3: no WebView exposed — make one and set it as the content
        # view. Last resort, but it turns a blank screen into a working app.
        WebView = autoclass("android.webkit.WebView")
        view = WebView(activity)
        view.getSettings().setJavaScriptEnabled(True)
        view.getSettings().setDomStorageEnabled(True)
        view.loadUrl(URL)
        activity.setContentView(view)
        log(f"created WebView and loaded {URL}")

    try:
        _run_on_ui_thread(activity, _load)
    except Exception as e:  # noqa: BLE001
        log(f"runOnUiThread failed, trying direct load: {e}")
        try:
            _load()
        except Exception as e2:  # noqa: BLE001
            log(f"FATAL: could not load WebView: {e2}")


# --------------------------------------------------------------------------

def main() -> None:
    log(f"starting (python {platform.python_version()}, arch {_arch()})")
    install_ffmpeg()
    start_server()

    # Give the server a beat to bind before the WebView asks for it; the UI
    # also retries via EventSource reconnect, so this is belt-and-braces.
    time.sleep(0.5)
    point_webview()

    # p4a kills the process when main.py returns. Block forever; the HTTP server
    # and SSE threads are daemons and die with the process, which is correct.
    log("main thread parking; engine serving in background")
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
