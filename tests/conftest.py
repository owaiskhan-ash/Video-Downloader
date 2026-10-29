"""
Shared fixtures.

The end-to-end test needs a real video served over HTTP, because yt-dlp's
generic extractor only reports formats for URLs it can actually reach. Both the
fixture and ffmpeg are optional: tests that need them skip cleanly rather than
failing, so the suite runs on a fresh checkout with nothing installed.
"""

from __future__ import annotations

import functools
import http.server
import os
import shutil
import socket
import subprocess
import sys
import threading
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from engine import ffmpeg as ff  # noqa: E402


def ffmpeg_binary() -> Path | None:
    """A usable ffmpeg, or None. Honours FFMPEG_PATH like the engine does."""
    env = os.environ.get("FFMPEG_PATH")
    if env and Path(env).is_file():
        return Path(env)
    found = ff.find_ffmpeg()
    return found


@pytest.fixture(scope="session")
def ffmpeg_path() -> Path:
    path = ffmpeg_binary()
    if path is None:
        pytest.skip("ffmpeg not available (set FFMPEG_PATH or pip install imageio-ffmpeg)")
    return path


def _free_port() -> int:
    """Ask the OS for an unused port instead of hardcoding one.

    Hardcoded ports make the suite fail whenever a previous run leaked a server
    or two developers run tests concurrently.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


@functools.lru_cache(maxsize=None)
def _make_sample(ffmpeg: str, codec: str, directory: str) -> str:
    """Generate a short sample video. Cached: encoding is the slow part."""
    out = Path(directory)
    out.mkdir(parents=True, exist_ok=True)

    if codec == "h264":
        name, vargs = "sample_h264.mp4", ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "28", "-pix_fmt", "yuv420p", "-c:a", "aac"]
    elif codec == "vp9":
        name, vargs = "sample_vp9.webm", ["-c:v", "libvpx-vp9", "-b:v", "300k", "-c:a", "libopus"]
    else:
        raise ValueError(codec)

    target = out / name
    if target.is_file() and target.stat().st_size > 0:
        return str(target)

    subprocess.run(
        [
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25:duration=2",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            *vargs, "-shortest", str(target),
        ],
        check=True,
        timeout=300,
    )
    return str(target)


@pytest.fixture(scope="session")
def media_server(tmp_path_factory, pytestconfig):
    """Serve generated samples over localhost HTTP for the whole session."""
    ffmpeg = ffmpeg_binary()
    if ffmpeg is None:
        pytest.skip("ffmpeg not available")

    directory = tmp_path_factory.mktemp("media")
    files = {
        "h264": Path(_make_sample(str(ffmpeg), "h264", str(directory))),
        "vp9": Path(_make_sample(str(ffmpeg), "vp9", str(directory))),
    }

    port = _free_port()

    # Can't subclass a functools.partial, so define the quiet handler first and
    # bind `directory` via partial at server-construction time instead.
    class QuietHandler(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *args) -> None:
            """Silence per-request logging; the test output is noisy enough."""

    handler = functools.partial(QuietHandler, directory=str(directory))

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()

    try:
        yield {
            "port": port,
            "base": f"http://127.0.0.1:{port}",
            "h264_url": f"http://127.0.0.1:{port}/{files['h264'].name}",
            "vp9_url": f"http://127.0.0.1:{port}/{files['vp9'].name}",
            "ffmpeg": ffmpeg,
        }
    finally:
        httpd.shutdown()
        httpd.server_close()


@pytest.fixture
def allow_local(monkeypatch):
    """Permit localhost fetches — the SSRF guard blocks them by default."""
    monkeypatch.setenv("ALLOW_LOCAL", "1")
