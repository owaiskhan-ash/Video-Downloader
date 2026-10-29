#!/usr/bin/env python3
"""
Dev server: the same engine, over HTTP + SSE instead of Tauri IPC.

This exists so the UI can be developed and demoed without compiling Rust. It is
NOT a mock — it spawns the real `python -m engine` and relays its stdout
verbatim, exactly like src-tauri/src/engine.rs does.

    python3 devserver.py            # serves ./dist on :8787

Endpoints (mirroring the Tauri commands one-for-one):

    GET  /api/events   SSE stream of every engine event
    POST /api/probe    {url}                -> {probe}
    POST /api/download {DownloadRequest}    -> {jobId}
    POST /api/cancel   {jobId}              -> {cancelled}
    GET  /api/jobs                          -> [Job]
    GET  /api/status                        -> {ok, report, ...}
    GET  /api/outdir                        -> {outdir}
    GET  /api/open?path=                    -> file bytes (browser reveal fallback)
    GET  /*                                 -> static files from ./dist

Set VD_ALLOW_LOCAL=1 to let the engine fetch http://127.0.0.1/... (tests only —
the SSRF guard blocks private ranges by default, which is correct for real use).
"""

from __future__ import annotations

import json
import mimetypes
import os
import queue
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
DIST = ROOT / "dist"
ENGINE_DIR = ROOT

HOST = os.environ.get("VD_HOST", "0.0.0.0")
PORT = int(os.environ.get("VD_PORT", "8787"))
PYTHON = os.environ.get("VD_PYTHON", sys.executable)
ALLOW_LOCAL = os.environ.get("VD_ALLOW_LOCAL", "0") == "1"

# Where finished files land. Overridable so the sandbox demo can point at a
# gitignored folder instead of the real ~/Downloads.
DEFAULT_OUTDIR = os.environ.get("VD_OUTDIR") or str(Path.home() / "Downloads")

# Terminal states must not be overwritten by a late event, otherwise a
# cancelled job flips back to "downloading" and never resolves in the UI.
TERMINAL = {"done", "failed", "cancelled"}


# --------------------------------------------------------------------------
# Event hub
# --------------------------------------------------------------------------

class Hub:
    """Fan-out from engine stdout to every connected SSE client."""

    def __init__(self) -> None:
        self._subscribers: set[queue.Queue] = set()
        self._lock = threading.Lock()

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=1000)
        with self._lock:
            self._subscribers.add(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            self._subscribers.discard(q)

    def publish(self, event: dict[str, Any]) -> None:
        payload = json.dumps(event, default=str)
        with self._lock:
            subs = list(self._subscribers)
        for q in subs:
            try:
                q.put_nowait(payload)
            except queue.Full:
                # A slow client must not block the engine reader thread.
                # Dropping progress frames is invisible; dropping the `done`
                # frame is not, so jobs also get reconciled via /api/jobs.
                pass


hub = Hub()


# --------------------------------------------------------------------------
# Job registry
# --------------------------------------------------------------------------

class Registry:
    def __init__(self) -> None:
        self.jobs: dict[str, dict[str, Any]] = {}
        self.children: dict[str, subprocess.Popen] = {}
        self.lock = threading.Lock()

    def create(self, job_id: str, url: str, outdir: str) -> dict[str, Any]:
        job = {
            "id": job_id,
            "url": url,
            "title": None,
            "outdir": outdir,
            "state": "queued",
            "percent": 0.0,
            "path": None,
            "error": None,
            "error_code": None,
            "hint": None,
        }
        with self.lock:
            self.jobs[job_id] = job
        return job

    def _mutate(self, job_id: str, **fields: Any) -> None:
        with self.lock:
            job = self.jobs.get(job_id)
            if not job:
                return
            # Never regress out of a terminal state.
            if job["state"] in TERMINAL and "state" in fields:
                if fields["state"] not in TERMINAL:
                    fields.pop("state")
            # A cancelled job that then errors should stay cancelled: killing
            # the engine makes yt-dlp exit non-zero, which would otherwise look
            # like a failure caused by the user's own cancel click.
            if job["state"] == "cancelled" and fields.get("state") == "failed":
                return
            job.update(fields)

    def set_state(self, job_id: str, state: str) -> None:
        self._mutate(job_id, state=state)

    def set_percent(self, job_id: str, pct: float) -> None:
        self._mutate(job_id, percent=max(0.0, min(100.0, pct)))

    def set_title(self, job_id: str, title: str) -> None:
        self._mutate(job_id, title=title)

    def finish(self, job_id: str, path: str) -> None:
        self._mutate(job_id, state="done", percent=100.0, path=path)

    def fail(self, job_id: str, code: str, message: str, hint: str = "") -> None:
        self._mutate(
            job_id, state="failed", error=message, error_code=code, hint=hint or None
        )

    def cancel(self, job_id: str) -> None:
        self._mutate(job_id, state="cancelled")

    def snapshot(self) -> list[dict[str, Any]]:
        with self.lock:
            return list(self.jobs.values())

    def track(self, job_id: str, proc: subprocess.Popen) -> None:
        with self.lock:
            self.children[job_id] = proc

    def kill(self, job_id: str) -> bool:
        with self.lock:
            proc = self.children.pop(job_id, None)
        if proc is None:
            return False
        self.cancel(job_id)  # mark BEFORE killing; see fail()
        try:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
        except Exception:
            pass
        return True

    def forget(self, job_id: str) -> None:
        with self.lock:
            self.children.pop(job_id, None)


registry = Registry()

STAGE_TO_STATE = {
    "resolving": "resolving",
    "resolved": "resolving",
    "plan": "resolving",
    "downloading": "downloading",
    "postprocessing": "postprocessing",
    "muxing": "postprocessing",
    "transcoding": "transcoding",
}


def apply_event(job_id: str, event: dict[str, Any]) -> None:
    """Mirror engine events into the registry (same logic as engine.rs)."""
    if not job_id:
        return
    kind = event.get("event")
    if kind == "stage":
        stage = event.get("stage", "")
        if stage in STAGE_TO_STATE:
            registry.set_state(job_id, STAGE_TO_STATE[stage])
        if event.get("title"):
            registry.set_title(job_id, str(event["title"]))
    elif kind == "progress":
        pct = event.get("percent")
        if isinstance(pct, (int, float)):
            registry.set_percent(job_id, float(pct))
    elif kind == "done":
        registry.finish(job_id, str(event.get("path", "")))
    elif kind == "error":
        registry.fail(
            job_id,
            str(event.get("code", "unknown")),
            str(event.get("message", "Download failed")),
            str(event.get("hint", "")),
        )
    elif kind == "cancelled":
        registry.cancel(job_id)


def run_engine(args: list[str], stdin_payload: Optional[str], job_id: str) -> int:
    """Spawn the engine and relay its stdout to the hub. Returns exit code."""
    env = dict(os.environ)
    if ALLOW_LOCAL:
        env["ALLOW_LOCAL"] = "1"

    proc = subprocess.Popen(
        [PYTHON, "-m", "engine", *args],
        cwd=str(ENGINE_DIR),
        stdin=subprocess.PIPE if stdin_payload is not None else subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
        env=env,
    )
    registry.track(job_id, proc)

    if stdin_payload is not None and proc.stdin is not None:
        try:
            proc.stdin.write(stdin_payload)
            proc.stdin.close()
        except Exception:
            pass

    stderr_tail: list[str] = []

    def drain_stderr() -> None:
        assert proc.stderr is not None
        for line in proc.stderr:
            line = line.strip()
            if line:
                stderr_tail.append(line)
                if len(stderr_tail) > 5:
                    stderr_tail.pop(0)

    threading.Thread(target=drain_stderr, daemon=True).start()

    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.strip()
        if not line:
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            event = {
                "v": 1,
                "event": "log",
                "job_id": job_id,
                "level": "warn",
                "message": line,
            }
        event.setdefault("job_id", job_id)
        if not event.get("job_id"):
            event["job_id"] = job_id
        apply_event(event["job_id"], event)
        hub.publish(event)

    code = proc.wait()
    registry.forget(job_id)

    # If the engine died without reporting anything, say so. A silent exit is
    # the confusing case — the UI would otherwise spin forever.
    jobs = {j["id"]: j for j in registry.snapshot()}
    job = jobs.get(job_id)
    if job and job["state"] not in TERMINAL:
        detail = " | ".join(stderr_tail[-3:]) if stderr_tail else ""
        event = {
            "v": 1,
            "event": "error",
            "job_id": job_id,
            "code": "engine_crashed",
            "message": f"Engine exited with code {code}. {detail}".strip(),
            "hint": "Check that yt-dlp is installed (pip install yt-dlp).",
        }
        apply_event(job_id, event)
        hub.publish(event)

    return code


# --------------------------------------------------------------------------
# HTTP handler
# --------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "VideoDownloaderDev/0.1"

    # Quiet the default per-request logging; keep errors.
    def log_message(self, fmt: str, *args: Any) -> None:
        if os.environ.get("VD_VERBOSE") == "1":
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # ---- helpers -------------------------------------------------------

    def _send_json(self, obj: Any, status: int = 200) -> None:
        body = json.dumps(obj, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.send_header("cache-control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_error_json(self, message: str, status: int = 400) -> None:
        self._send_json({"error": message}, status)

    def _read_body(self) -> dict[str, Any]:
        length = int(self.headers.get("content-length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            raise ValueError(f"invalid JSON body: {e}") from e
        if not isinstance(data, dict):
            raise ValueError("body must be a JSON object")
        return data

    def _serve_static(self, path: str) -> None:
        rel = path.lstrip("/") or "index.html"
        target = (DIST / rel).resolve()

        # Path traversal guard: the resolved file must stay inside dist/.
        try:
            target.relative_to(DIST.resolve())
        except ValueError:
            self._send_error_json("forbidden", 403)
            return

        if not target.is_file():
            # SPA fallback so a refresh on a client-side route still works.
            target = DIST / "index.html"
            if not target.is_file():
                self._send_error_json(
                    "dist/ not built yet — run: npm run build", 404
                )
                return

        ctype = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("content-type", ctype)
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # ---- routes --------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        route = parsed.path

        if route == "/api/events":
            return self._sse()
        if route == "/api/jobs":
            return self._send_json(registry.snapshot())
        if route == "/api/status":
            return self._status()
        if route == "/api/outdir":
            return self._send_json({"outdir": DEFAULT_OUTDIR})
        if route == "/api/open":
            return self._open_file(parse_qs(parsed.query).get("path", [""])[0])
        if route.startswith("/api/"):
            return self._send_error_json("not found", 404)

        if not DIST.is_dir():
            self._send_json(
                {
                    "error": "Frontend not built. Run `npm install && npm run build`, "
                    "then reload.",
                    "hint": "devserver.py serves ./dist — it does not run Vite.",
                },
                503,
            )
            return
        return self._serve_static(route)

    def do_POST(self) -> None:  # noqa: N802
        route = urlparse(self.path).path
        try:
            body = self._read_body()
        except ValueError as e:
            return self._send_error_json(str(e))

        if route == "/api/probe":
            return self._probe(body)
        if route == "/api/download":
            return self._download(body)
        if route == "/api/cancel":
            job_id = str(body.get("jobId") or body.get("job_id") or "")
            return self._send_json({"cancelled": registry.kill(job_id)})
        return self._send_error_json("not found", 404)

    # ---- implementations -----------------------------------------------

    def _sse(self) -> None:
        """Server-Sent Events.

        SSE rather than WebSocket: the stream is one-way, it survives proxies
        (the sandbox preview is proxied), EventSource reconnects natively, and
        it needs no extra dependency on either side.
        """
        self.send_response(200)
        self.send_header("content-type", "text/event-stream; charset=utf-8")
        # no-transform stops intermediaries buffering the stream, which would
        # make progress appear frozen and then jump.
        self.send_header("cache-control", "no-cache, no-transform")
        self.send_header("connection", "keep-alive")
        self.send_header("x-accel-buffering", "no")
        self.end_headers()

        q = hub.subscribe()
        try:
            self.wfile.write(b": connected\n\n")
            self.wfile.flush()
            while True:
                try:
                    payload = q.get(timeout=15)
                    self.wfile.write(f"data: {payload}\n\n".encode("utf-8"))
                except queue.Empty:
                    # Keepalive comment. Without it, idle proxies close the
                    # connection and the UI silently stops receiving events.
                    self.wfile.write(b": ping\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            hub.unsubscribe(q)

    def _status(self) -> None:
        env = dict(os.environ)
        if ALLOW_LOCAL:
            env["ALLOW_LOCAL"] = "1"
        try:
            out = subprocess.run(
                [PYTHON, "-m", "engine", "doctor"],
                cwd=str(ENGINE_DIR),
                capture_output=True,
                text=True,
                timeout=60,
                env=env,
                check=False,
            )
        except Exception as e:  # noqa: BLE001
            return self._send_json(
                {"ok": False, "isDev": True, "error": str(e), "report": None}
            )

        report = None
        for line in reversed(out.stdout.splitlines()):
            line = line.strip()
            if line.startswith("{"):
                try:
                    report = json.loads(line)
                    break
                except json.JSONDecodeError:
                    continue

        self._send_json(
            {
                "ok": out.returncode == 0,
                "isDev": True,
                "program": f"{PYTHON} -m engine",
                "report": report,
                "stderr": out.stderr[-2000:] if out.stderr else "",
            }
        )

    def _probe(self, body: dict[str, Any]) -> None:
        url = str(body.get("url") or "").strip()
        if not url:
            return self._send_error_json("url is required")

        job_id = f"probe-{uuid.uuid4()}"
        captured: dict[str, Any] = {}

        env = dict(os.environ)
        if ALLOW_LOCAL:
            env["ALLOW_LOCAL"] = "1"

        try:
            proc = subprocess.run(
                [PYTHON, "-m", "engine", "probe", url, "--job-id", job_id],
                cwd=str(ENGINE_DIR),
                capture_output=True,
                text=True,
                timeout=90,
                env=env,
                check=False,
            )
        except subprocess.TimeoutExpired:
            return self._send_error_json("Timed out resolving that URL", 504)
        except Exception as e:  # noqa: BLE001
            return self._send_error_json(str(e), 500)

        for line in proc.stdout.splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            kind = event.get("event")
            if kind == "probe":
                captured = event
                break
            if kind == "error":
                return self._send_error_json(
                    event.get("message", "Could not resolve that URL"), 422
                )

        if not captured:
            detail = (proc.stderr or "").strip().splitlines()[-3:]
            return self._send_error_json(
                "Engine returned no result. " + " | ".join(detail), 500
            )

        self._send_json({"probe": captured})

    def _download(self, body: dict[str, Any]) -> None:
        url = str(body.get("url") or "").strip()
        if not url:
            return self._send_error_json("url is required")

        outdir = str(body.get("outdir") or DEFAULT_OUTDIR)
        quality = str(body.get("quality") or "1080")

        clip_start = body.get("clip_start")
        clip_end = body.get("clip_end")
        if clip_start is not None and clip_end is not None:
            try:
                if float(clip_end) <= float(clip_start):
                    return self._send_error_json("clip end must be after clip start")
            except (TypeError, ValueError):
                return self._send_error_json("clip times must be numbers")

        job_id = str(uuid.uuid4())
        registry.create(job_id, url, outdir)

        spec = {
            "job_id": job_id,
            "url": url,
            "outdir": outdir,
            "quality": quality,
            "compatible": bool(body.get("compatible", True)),
            "subs": bool(body.get("subs", False)),
            "thumbnail": bool(body.get("thumbnail", False)),
            "metadata": bool(body.get("metadata", True)),
            "clip_start": clip_start,
            "clip_end": clip_end,
            "playlist": bool(body.get("playlist", False)),
        }

        # Detached: the HTTP response must return immediately with the job id,
        # and progress then flows over the SSE channel.
        threading.Thread(
            target=run_engine,
            args=(["download", "-"], json.dumps(spec), job_id),
            daemon=True,
        ).start()

        self._send_json({"jobId": job_id}, 202)

    def _open_file(self, path: str) -> None:
        """Browser fallback for 'reveal in folder': stream the file itself."""
        if not path:
            return self._send_error_json("path is required")
        target = Path(path).expanduser()
        if not target.is_file():
            return self._send_error_json("no such file", 404)

        # Only serve files under the configured download dir. Without this the
        # endpoint is an arbitrary-file-read primitive.
        try:
            target.resolve().relative_to(Path(DEFAULT_OUTDIR).resolve())
        except ValueError:
            return self._send_error_json("path outside the download folder", 403)

        ctype = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        size = target.stat().st_size
        self.send_response(200)
        self.send_header("content-type", ctype)
        self.send_header("content-length", str(size))
        self.send_header(
            "content-disposition", f'inline; filename="{target.name}"'
        )
        self.end_headers()
        with target.open("rb") as fh:
            while chunk := fh.read(256 * 1024):
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return


def main() -> int:
    if not DIST.is_dir():
        print(
            "warning: ./dist not found. Build the frontend first:\n"
            "    npm install && npm run build\n",
            file=sys.stderr,
        )

    print(f"engine : {PYTHON} -m engine   (cwd {ENGINE_DIR})")
    print(f"outdir : {DEFAULT_OUTDIR}")
    print(f"local  : {'ALLOWED' if ALLOW_LOCAL else 'blocked (SSRF guard on)'}")
    print(f"serving: http://{HOST}:{PORT}")

    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
