#!/usr/bin/env python3
"""
Download engine.

Speaks newline-delimited JSON on stdout (see protocol.py). Two entry points:

    python -m engine probe <url>              # inspect before downloading
    python -m engine download <spec.json>     # run a job

The download path is deliberately staged so the UI can show *what* is happening:

    resolving -> downloading(video) -> downloading(audio) -> muxing
              -> [transcoding] -> done

"transcoding" only appears in compatibility mode when the source was VP9/AV1.
"""

from __future__ import annotations

import json
import os
import re
import signal
import subprocess
import sys
from pathlib import Path
from typing import Any, Optional

from . import ffmpeg as ff
from .protocol import (
    Event,
    FormatInfo,
    JobSpec,
    ProbeResult,
    Quality,
    emit,
    fail,
    log,
)
from .safety import UnsafeURL, assert_safe_url, ensure_outdir

# Allow the engine to be run from a repo checkout without installing it.
try:
    import yt_dlp
except ImportError:  # pragma: no cover
    sys.stderr.write("yt-dlp is not installed. Run: pip install yt-dlp\n")
    sys.exit(2)


# --------------------------------------------------------------------------
# Format selection — the heart of the app
# --------------------------------------------------------------------------
#
# Key insight: on YouTube/Vimeo/TikTok, HD is NOT a single file. Video and
# audio are separate DASH streams:
#     format 137 -> 1080p video only  (vcodec=avc1, acodec=none)
#     format 140 -> 128k  audio only  (vcodec=none, acodec=mp4a)
# So "download in HD" means: fetch both, then mux with `ffmpeg -c copy`.
# `-c copy` remuxes without re-encoding: ~1s for a 10-minute video, zero
# quality loss. Re-encoding by accident is the classic beginner bug.
#
# `bv*+ba/b` reads as: best video + best audio, else best pre-merged file.

_HEIGHT = {
    Quality.UHD_4K: 2160,
    Quality.FHD: 1080,
    Quality.HD: 720,
    Quality.SD: 480,
}


def build_format_selector(quality: Quality, compatible: bool) -> str:
    """Return a yt-dlp format selector for the user's choices.

    Compatibility mode prefers H.264 video + AAC audio in an mp4 container.
    That combination plays on essentially everything — TVs, iMessage,
    PowerPoint, phones, editors — whereas the *highest* quality stream is often
    VP9 or AV1 in a .webm, which many players and devices reject.

    Each preference chain ends in a permissive fallback, because a strict
    selector that matches nothing makes yt-dlp error out entirely instead of
    silently dropping to 360p. Degrading gracefully beats failing.
    """
    if quality == Quality.AUDIO:
        return "ba/b"

    cap = _HEIGHT.get(quality)
    cap_clause = f"[height<=?{cap}]" if cap else ""

    if compatible:
        # avc1 = H.264. Prefer it, then any mp4 video, then anything at all.
        return (
            f"bv*{cap_clause}[vcodec^=avc1]+ba[acodec^=mp4a]/"
            f"bv*{cap_clause}[ext=mp4]+ba[ext=m4a]/"
            f"bv*{cap_clause}+ba/"
            f"b{cap_clause}/"
            "bv*+ba/b"
        )

    # Max quality: take whatever is best, including VP9/AV1 webm.
    return f"bv*{cap_clause}+ba/b{cap_clause}/bv*+ba/b"


def needs_transcode(video_path: Path, compatible: bool) -> tuple[bool, str]:
    """After the download, check whether the result is actually compatible.

    yt-dlp's selector is a *preference*, not a guarantee: if a video only
    exists in VP9, compatibility mode still downloads VP9 (correct — better a
    file than an error). We detect that here and re-encode to H.264/AAC.

    Returns (should_transcode, reason).
    """
    if not compatible:
        return False, ""

    vcodec, acodec = inspect_codecs(video_path)
    if vcodec is None:
        return False, ""  # couldn't determine; don't destroy a good file

    bad_v = not vcodec.lower().startswith(("h264", "avc1"))
    bad_a = acodec is not None and not acodec.lower().startswith(("aac", "mp4a"))

    if bad_v:
        return True, f"video codec {vcodec} is not H.264"
    if bad_a:
        return True, f"audio codec {acodec} is not AAC"
    return False, ""


def inspect_codecs(path: Path) -> tuple[Optional[str], Optional[str]]:
    """Return (video_codec, audio_codec) using ffmpeg -i, or (None, None)."""
    binary = ff.find_ffmpeg()
    if binary is None:
        return None, None
    try:
        out = subprocess.run(
            [str(binary), "-hide_banner", "-i", str(path)],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
    except Exception:
        return None, None

    text = out.stderr  # ffmpeg writes probe info to stderr even on success
    v = re.search(r"Stream #\d+:\d+.*?: Video: ([\w.]+)", text)
    a = re.search(r"Stream #\d+:\d+.*?: Audio: ([\w.]+)", text)
    return (v.group(1) if v else None, a.group(1) if a else None)


def _classify_format(f: dict[str, Any]) -> FormatInfo:
    """Turn a raw yt-dlp format dict into a FormatInfo.

    Codec fields are tri-state, and conflating the two empty cases is a real
    bug: yt-dlp uses the *string* "none" to mean "this stream definitely has no
    such track" (DASH video-only / audio-only), but Python `None` to mean
    "unknown". The generic extractor — which handles any direct file URL —
    reports `None` for both codecs because it never probes the file. Treating
    `None` as absent mislabels an ordinary 1080p .mp4 as an audio stream, which
    then makes the UI hide it from every video quality preset.

    So: classify on the explicit "none" marker only, and fall back to "muxed"
    when the codecs are simply unknown.
    """
    vcodec = f.get("vcodec")
    acodec = f.get("acodec")

    has_video = vcodec not in (None, "none")
    has_audio = acodec not in (None, "none")
    no_video = vcodec == "none"
    no_audio = acodec == "none"

    if no_video or (has_audio and not has_video and vcodec is None and f.get("height") is None):
        kind = "audio"
    elif no_audio or (has_video and not has_audio):
        kind = "video"
    else:
        # Both tracks present, or unknown (direct file URL) -> a single
        # self-contained file the user can play as-is.
        kind = "muxed"

    return FormatInfo(
        format_id=str(f.get("format_id") or ""),
        kind=kind,
        height=f.get("height"),
        fps=f.get("fps"),
        vcodec=vcodec,
        acodec=acodec,
        ext=f.get("ext"),
        filesize=f.get("filesize") or f.get("filesize_approx"),
        tbr=f.get("tbr"),
    )


# --------------------------------------------------------------------------
# Probe
# --------------------------------------------------------------------------

def probe(url: str, *, allow_local: bool = False, job_id: str = "probe") -> ProbeResult:
    """Resolve a URL to metadata + formats WITHOUT downloading anything.

    `job_id` is threaded through every event so the UI can correlate a probe
    with the button press that triggered it. Emitting a hardcoded id instead
    means two rapid pastes produce indistinguishable events.
    """
    try:
        url = assert_safe_url(url, allow_local=allow_local)
    except UnsafeURL as e:
        fail(job_id, str(e), code="unsafe_url", hint="Paste an http(s) link to a video page.")

    opts = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,   # gotcha: `quiet` alone does NOT silence progress
        "skip_download": True,
        "noplaylist": True,
        "extract_flat": "in_playlist",
    }

    log(f"Resolving {url}", job_id)
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except yt_dlp.utils.DownloadError as e:
        code, hint = classify_error(str(e))
        fail(job_id, str(e), code=code, hint=hint)
    except Exception as e:  # noqa: BLE001
        fail(job_id, f"{type(e).__name__}: {e}", code="probe_failed")

    if info is None:
        fail(job_id, "Extractor returned no information", code="probe_failed")

    warnings: list[str] = []
    if info.get("age_limit", 0) and info.get("age_limit") >= 18:
        warnings.append("Age-restricted. May require a signed-in session cookie.")
    if info.get("is_live"):
        warnings.append("This is a live stream. Downloading captures it as it airs.")
    if info.get("_type") == "playlist":
        warnings.append("This URL is a playlist, not a single video.")

    formats = [_classify_format(f) for f in (info.get("formats") or []) if f.get("format_id")]

    result = ProbeResult(
        url=url,
        title=info.get("title") or "Untitled",
        uploader=info.get("uploader") or info.get("channel"),
        duration=info.get("duration"),
        extractor=info.get("extractor") or info.get("extractor_key"),
        thumbnail=info.get("thumbnail"),
        is_live=bool(info.get("is_live")),
        is_playlist=info.get("_type") == "playlist",
        entry_count=len(info.get("entries") or []) or None,
        formats=formats,
        warnings=warnings,
    )
    emit(Event.PROBE, job_id, **result.to_dict())
    return result


# --------------------------------------------------------------------------
# Download
# --------------------------------------------------------------------------

_CANCELLED = False


def _on_signal(signum, _frame):  # pragma: no cover - platform dependent
    """SIGTERM/SIGINT -> stop cleanly and leave the .part file for resuming."""
    global _CANCELLED
    _CANCELLED = True
    emit(Event.CANCELLED, os.environ.get("JOB_ID", ""), reason="signal")
    sys.exit(130)


def _hook_factory(job_id: str, stage: str):
    def hook(d: dict[str, Any]) -> None:
        if _CANCELLED:
            raise yt_dlp.utils.DownloadCancelled("Cancelled by user")

        if d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            done = d.get("downloaded_bytes") or 0
            emit(
                Event.PROGRESS,
                job_id,
                stage=stage,
                percent=round(done / total * 100, 2) if total else 0.0,
                downloaded=done,
                total=total or None,
                speed=d.get("speed"),
                eta=d.get("eta"),
                filename=Path(d.get("filename") or "").name,
            )
        elif d.get("status") == "finished":
            emit(
                Event.PROGRESS,
                job_id,
                stage=stage,
                percent=100.0,
                downloaded=d.get("total_bytes"),
                total=d.get("total_bytes"),
                speed=None,
                eta=0,
                filename=Path(d.get("filename") or "").name,
            )

    return hook


def build_ydl_opts(spec: JobSpec, outdir: Path, ffmpeg_path: Optional[Path]) -> dict[str, Any]:
    """Translate a JobSpec into yt-dlp options."""
    postprocessors: list[dict[str, Any]] = []

    if spec.metadata:
        postprocessors.append({"key": "FFmpegMetadata", "add_metadata": True})
    if spec.thumbnail:
        postprocessors.append({"key": "EmbedThumbnail", "already_have_thumbnail": False})

    if spec.quality == Quality.AUDIO:
        # Extract to m4a (AAC) rather than mp3: m4a keeps the source codec and
        # avoids a lossy transcode of an already-lossy stream.
        postprocessors.insert(
            0,
            {
                "key": "FFmpegExtractAudio",
                "preferredcodec": "m4a",
                "preferredquality": "192",
            },
        )

    if spec.clip_start is not None or spec.clip_end is not None:
        # Download only the requested time range. --download-sections with
        # force_keyframes re-encodes just that window, so it is accurate to the
        # frame rather than snapping to the nearest keyframe.
        start = spec.clip_start if spec.clip_start is not None else 0.0
        end = spec.clip_end if spec.clip_end is not None else float("inf")
        if end != float("inf") and end <= start:
            raise ValueError(
                f"Clip end ({end}s) must be after clip start ({start}s)"
            )
        end_str = "inf" if end == float("inf") else f"{end:.3f}"

    opts: dict[str, Any] = {
        "format": build_format_selector(spec.quality, spec.compatible),
        "outtmpl": str(outdir / "%(title).100B [%(id)s].%(ext)s"),
        "merge_output_format": "mp4",
        "noplaylist": not spec.playlist,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "retries": 5,
        "fragment_retries": 5,
        "concurrent_fragment_downloads": 4,  # parallel DASH chunks
        "continue_dl": True,                  # resume .part files
        "postprocessors": postprocessors,
        "writethumbnail": spec.thumbnail,
        "writesubtitles": spec.subs,
        "subtitleslangs": ["en", "en-US", "en-GB"] if spec.subs else None,
        "embedsubtitles": spec.subs,
        # Restrict to characters that survive every filesystem; titles arrive
        # from the internet and routinely contain slashes, quotes and emoji.
        "restrictfilenames": False,
        "windowsfilenames": True,
        "overwrites": False,
        "nocheckcertificate": False,
    }

    if ffmpeg_path:
        opts["ffmpeg_location"] = str(ffmpeg_path)

    if spec.clip_start is not None or spec.clip_end is not None:
        opts["download_sections"] = [f"*{start:.3f}-{end_str}"]
        opts["force_keyframes_at_cuts"] = True

    return opts


def transcode_to_compatible(
    src: Path, ffmpeg_path: Path, job_id: str
) -> Path:
    """Re-encode a VP9/AV1 or non-AAC result into H.264/AAC .mp4.

    This is the slow path and the UI must say so — it can take several minutes.
    We write to a temp file and only replace the original on success, so a
    failed transcode never destroys the (already correct, if incompatible)
    download.

    -crf 20 + -preset slow is a good quality/size/time balance for archival.
    +faststart moves the moov atom to the front so the file streams in a
    browser instead of downloading fully before playing.
    """
    tmp = src.with_suffix(".compat.mp4")
    cmd = [
        str(ffmpeg_path),
        "-hide_banner",
        "-y",
        "-i", str(src),
        "-c:v", "libx264",
        "-preset", "slow",
        "-crf", "20",
        "-pix_fmt", "yuv420p",      # 8-bit 4:2:0 — required for wide playback
        "-profile:v", "high",
        "-level", "4.2",
        "-c:a", "aac",
        "-b:a", "192k",
        "-movflags", "+faststart",
        "-progress", "pipe:1",
        "-nostats",
        str(tmp),
    ]

    emit(Event.STAGE, job_id, stage="transcoding", detail="Converting to H.264/AAC for compatibility")

    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1
    )

    duration = None
    vcodec, _ = inspect_codecs(src)
    # Estimate total duration for percent reporting.
    try:
        probe_cmd = [str(ffmpeg_path), "-hide_banner", "-i", str(src)]
        p = subprocess.run(probe_cmd, capture_output=True, text=True, timeout=60, check=False)
        m = re.search(r"Duration: (\d+):(\d+):(\d+\.\d+)", p.stderr)
        if m:
            duration = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
    except Exception:
        duration = None

    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.strip()
        if line.startswith("out_time_ms=") and duration:
            try:
                us = int(line.split("=", 1)[1])
                pct = min(99.9, us / 1e6 / duration * 100)
                emit(Event.PROGRESS, job_id, stage="transcoding", percent=round(pct, 2))
            except (ValueError, ZeroDivisionError):
                pass

    _, stderr = proc.communicate()
    if proc.returncode != 0:
        # Keep the original; tell the user why the transcode failed.
        if tmp.exists():
            tmp.unlink(missing_ok=True)
        tail = "\n".join((stderr or "").splitlines()[-6:])
        log(f"Transcode failed, keeping original file. ffmpeg said:\n{tail}", job_id, "warning")
        return src

    src.unlink(missing_ok=True)
    final = src.with_suffix(".mp4")
    tmp.replace(final)
    return final


def classify_error(msg: str) -> tuple[str, str]:
    """Map a yt-dlp error onto a code + actionable hint for the UI."""
    m = msg.lower()
    table = [
        ("sign in to confirm your age", "age_restricted",
         "This video is age-restricted. Export cookies from a signed-in browser and point the app at them."),
        ("private video", "private",
         "This video is private. Only the owner's account can access it."),
        ("members-only", "members_only",
         "This video is for channel members only."),
        ("video unavailable", "unavailable",
         "The video is unavailable — it may be deleted, region-locked, or the link is wrong."),
        ("not available in your country", "geo_blocked",
         "This video is region-locked and not available from your network."),
        ("unsupported url", "unsupported_url",
         "yt-dlp doesn't recognise this site. Check for a typo, or update yt-dlp."),
        ("http error 403", "forbidden",
         "The server refused the request (403). Often a stale yt-dlp — try updating."),
        ("http error 404", "not_found",
         "Nothing at that URL (404). Check the link."),
        ("unable to download webpage", "network",
         "Couldn't reach the site. Check your connection, VPN, or firewall."),
        ("tls", "network",
         "TLS handshake failed. A proxy or firewall may be interfering."),
        ("requested format is not available", "format_unavailable",
         "That quality isn't offered for this video. Try a lower preset."),
        ("no space left", "disk_full",
         "Disk full. Free up space and resume — partial downloads are kept."),
        ("ffmpeg", "ffmpeg",
         "ffmpeg is missing or failed. HD downloads need it to mux video+audio."),
    ]
    for needle, code, hint in table:
        if needle in m:
            return code, hint
    return "download_failed", ""


def download(spec: JobSpec, *, allow_local: bool = False) -> Path:
    job_id = spec.job_id
    os.environ["JOB_ID"] = job_id

    for sig in (getattr(signal, "SIGTERM", None), getattr(signal, "SIGINT", None)):
        if sig:
            try:
                signal.signal(sig, _on_signal)
            except (ValueError, OSError):
                pass

    ffmpeg_path, ff_warnings = ff.resolve()
    for w in ff_warnings:
        log(w, job_id, "warning")
    if ffmpeg_path:
        log(f"ffmpeg: {ffmpeg_path.name}", job_id)

    try:
        outdir = ensure_outdir(spec.outdir)
    except (OSError, NotADirectoryError) as e:
        fail(job_id, f"Cannot use output directory: {e}", code="bad_outdir")

    try:
        url = assert_safe_url(spec.url, allow_local=allow_local)
    except UnsafeURL as e:
        fail(job_id, str(e), code="unsafe_url")

    opts = build_ydl_opts(spec, outdir, ffmpeg_path)
    opts["progress_hooks"] = [_hook_factory(job_id, "downloading")]

    # Gotcha (verified against yt_dlp/postprocessor/common.py:167):
    #   d["postprocessor"] = self.pp_key()
    # i.e. it is a plain STRING like "FFmpegMetadata", not an object. Calling
    # .get("name") on it raises AttributeError — and because this fires *after*
    # the bytes are already on disk, it looks like a successful download that
    # then dies at 100%. Handle both shapes defensively.
    def pp_hook(d: dict[str, Any]) -> None:
        if d.get("status") != "started":
            return
        pp = d.get("postprocessor")
        name = pp.get("name", "") if isinstance(pp, dict) else str(pp or "")
        emit(Event.STAGE, job_id, stage="postprocessing", detail=name)

    opts["postprocessor_hooks"] = [pp_hook]

    emit(Event.STAGE, job_id, stage="resolving", detail=url)

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if info is None:
                fail(job_id, "Extractor returned no information", code="probe_failed")

            # The default template is "%(title)s [%(id)s]", which disambiguates
            # nicely on real sites ("Some Video [dQw4w9WgXcQ].mp4"). But the
            # generic extractor derives both fields from the filename, so a
            # direct link produces "Clip [Clip].mp4". Drop the redundant suffix
            # when the two fields carry no extra information.
            #
            # Safe to mutate here because we resolve (download=False) before the
            # real download, and outtmpl is read at download time.
            if not spec.playlist:
                vid = str(info.get("id") or "")
                vtitle = str(info.get("title") or "")
                if vid and vtitle and (vid == vtitle or vid in vtitle):
                    ydl.params["outtmpl"] = {"default": str(outdir / "%(title).120B.%(ext)s")}

            emit(
                Event.STAGE,
                job_id,
                stage="resolved",
                title=info.get("title"),
                duration=info.get("duration"),
                requested_formats=[
                    f.get("format_id") for f in (info.get("requested_formats") or [])
                ]
                or [info.get("format_id")],
            )

            # Separate video/audio streams -> a mux step is coming. Telling the
            # user up front avoids a "stuck at 100%" impression during muxing.
            req = info.get("requested_formats") or []
            if len(req) > 1:
                emit(
                    Event.STAGE,
                    job_id,
                    stage="plan",
                    detail="Video and audio are separate streams; they will be merged without re-encoding.",
                )

            emit(Event.STAGE, job_id, stage="downloading")
            result = ydl.extract_info(url, download=True)

            path = Path(ydl.prepare_filename(result))
            # After postprocessing the extension may have changed (webm -> mp4,
            # audio extraction -> m4a). Find the real file.
            if not path.exists():
                candidates = sorted(
                    outdir.glob(f"{path.stem}.*"),
                    key=lambda p: p.stat().st_mtime,
                    reverse=True,
                )
                candidates = [c for c in candidates if c.suffix.lower() != ".part"]
                if candidates:
                    path = candidates[0]

            if not path.exists():
                fail(job_id, f"Download reported success but '{path.name}' is missing",
                     code="missing_output")

    except yt_dlp.utils.DownloadCancelled:
        emit(Event.CANCELLED, job_id, reason="user")
        sys.exit(130)
    except yt_dlp.utils.DownloadError as e:
        code, hint = classify_error(str(e))
        fail(job_id, str(e), code=code, hint=hint)
    except Exception as e:  # noqa: BLE001
        fail(job_id, f"{type(e).__name__}: {e}", code="internal")

    # ---- Compatibility enforcement ---------------------------------------
    # The format selector only *prefers* H.264. If the source was VP9/AV1 we
    # now have a file many players will refuse, so re-encode it.
    if spec.quality != Quality.AUDIO and ffmpeg_path:
        should, reason = needs_transcode(path, spec.compatible)
        if should:
            log(f"Source is {reason} — transcoding to H.264/AAC (this can take a while)",
                job_id, "warning")
            path = transcode_to_compatible(path, ffmpeg_path, job_id)

    emit(
        Event.DONE,
        job_id,
        path=str(path),
        filename=path.name,
        size=path.stat().st_size,
    )
    return path


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def _usage() -> str:
    return (
        "usage:\n"
        "  python -m engine probe <url> [--job-id ID]\n"
        "  python -m engine download <spec.json | ->\n"
        "  python -m engine formats        # debug: print selectors per preset\n"
        "  python -m engine doctor         # debug: check yt-dlp + ffmpeg\n"
        "\n"
        "Both write newline-delimited JSON events to stdout.\n"
        "Set ALLOW_LOCAL=1 to permit localhost URLs (tests only).\n"
    )


def _flag(argv: list[str], name: str) -> Optional[str]:
    """Tiny `--name value` reader; avoids an argparse dependency in the sidecar."""
    for i, tok in enumerate(argv):
        if tok == name and i + 1 < len(argv):
            return argv[i + 1]
        if tok.startswith(name + "="):
            return tok.split("=", 1)[1]
    return None


def doctor() -> int:
    """Report whether the two hard dependencies are usable.

    Separate from `probe` because the failure modes differ: a missing ffmpeg
    degrades quality, a missing yt-dlp is fatal. The UI runs this on launch and
    shows a banner instead of letting the user discover it mid-download.
    """
    report: dict[str, Any] = {"yt_dlp": None, "ffmpeg": None, "warnings": []}

    report["yt_dlp"] = getattr(yt_dlp.version, "__version__", "unknown")

    path, warnings = ff.resolve()
    report["ffmpeg"] = str(path) if path else None
    report["ffmpeg_version"] = ff.ffmpeg_version(path) if path else None
    report["warnings"].extend(warnings)

    version_line = ff.ffmpeg_version(path) if path else None
    if version_line:
        report["ffmpeg_version"] = version_line

    # Single line, like every other event. Pretty-printing with indent=2 here
    # would break the newline-delimited protocol: the consumer scans lines in
    # reverse for the first one that parses as JSON, and a multi-line blob makes
    # it match an unrelated earlier event instead of this report.
    print(json.dumps({"v": 1, "event": "doctor", "job_id": "doctor", **report}, default=str))
    sys.stdout.flush()
    return 0 if report["yt_dlp"] else 1


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        sys.stderr.write(_usage())
        return 2

    cmd = argv[1]
    allow_local = os.environ.get("ALLOW_LOCAL") == "1"

    if cmd == "doctor":
        return doctor()

    if cmd == "probe":
        if len(argv) < 3:
            sys.stderr.write(_usage())
            return 2
        job_id = _flag(argv, "--job-id") or "probe"
        probe(argv[2], allow_local=allow_local, job_id=job_id)
        return 0

    if cmd == "download":
        if len(argv) < 3:
            sys.stderr.write(_usage())
            return 2
        raw = sys.stdin.read() if argv[2] == "-" else Path(argv[2]).read_text()
        data = json.loads(raw)
        spec = JobSpec(
            job_id=str(data["job_id"]),
            url=data["url"],
            outdir=data["outdir"],
            quality=Quality(data.get("quality", "1080")),
            compatible=bool(data.get("compatible", True)),
            subs=bool(data.get("subs", False)),
            thumbnail=bool(data.get("thumbnail", False)),
            metadata=bool(data.get("metadata", True)),
            clip_start=data.get("clip_start"),
            clip_end=data.get("clip_end"),
            playlist=bool(data.get("playlist", False)),
        )
        download(spec, allow_local=allow_local)
        return 0

    if cmd in ("formats", "selector"):
        # Debug helper: show the selector each preset produces.
        for q in Quality:
            for compat in (True, False):
                print(f"{q.value:<7} compatible={compat!s:<5} "
                      f"{build_format_selector(q, compat)}")
        return 0

    sys.stderr.write(_usage())
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
