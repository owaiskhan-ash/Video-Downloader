"""
Shared types and the event protocol for the download engine.

The engine talks to its parent process (Tauri/Rust, or a plain terminal) using
newline-delimited JSON on stdout. Everything else on stdout is noise, so the
engine never uses print() directly — only emit().

Why a line-based JSON protocol instead of a library import:
  * Rust can't call Python in-process without PyO3 + a bundled interpreter.
  * Spawning `engine` as a sidecar and reading its stdout is the idiomatic
    Tauri pattern, works identically in dev and in a packaged build, and lets
    you swap the engine implementation without touching the UI.
"""

from __future__ import annotations

import json
import sys
import time
from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any, Optional

PROTOCOL_VERSION = 1


# --------------------------------------------------------------------------
# Quality presets
# --------------------------------------------------------------------------

class Quality(str, Enum):
    """User-facing quality choices. Mapped to yt-dlp selectors in engine.main."""

    BEST = "best"          # highest available, whatever codec that turns out to be
    UHD_4K = "2160"        # 4K
    FHD = "1080"           # Full HD  <- default
    HD = "720"             # HD
    SD = "480"             # small files, slow connections
    AUDIO = "audio"        # audio only -> m4a


@dataclass(frozen=True)
class FormatInfo:
    """One selectable stream as reported by the site."""

    format_id: str
    kind: str                       # "video" | "audio" | "muxed"
    height: Optional[int]
    fps: Optional[float]
    vcodec: Optional[str]
    acodec: Optional[str]
    ext: Optional[str]
    filesize: Optional[int]
    tbr: Optional[float]

    @property
    def label(self) -> str:
        bits: list[str] = []
        if self.kind == "audio":
            bits.append(f"{(self.tbr or 0):.0f} kbps")
        else:
            bits.append(f"{self.height or '?'}p")
            if self.fps and self.fps > 30.5:
                bits.append(f"{self.fps:.0f}fps")
        if self.vcodec and self.vcodec != "none":
            bits.append(self.vcodec.split(".")[0])
        if self.acodec and self.acodec != "none":
            bits.append(self.acodec.split(".")[0])
        if self.filesize:
            bits.append(f"{self.filesize / 1e6:.0f} MB")
        return " ".join(bits)


@dataclass
class ProbeResult:
    """What we know about a URL before committing to a download."""

    url: str
    title: str
    uploader: Optional[str] = None
    duration: Optional[float] = None
    extractor: Optional[str] = None
    thumbnail: Optional[str] = None
    is_live: bool = False
    is_playlist: bool = False
    entry_count: Optional[int] = None
    formats: list[FormatInfo] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["formats"] = [asdict(f) for f in self.formats]
        return d


@dataclass
class JobSpec:
    """Everything needed to run one download."""

    job_id: str
    url: str
    outdir: str
    quality: Quality = Quality.FHD
    compatible: bool = True          # force H.264/AAC mp4; transcode if needed
    subs: bool = False
    thumbnail: bool = False
    metadata: bool = True
    clip_start: Optional[float] = None   # seconds; None = whole video
    clip_end: Optional[float] = None
    playlist: bool = False

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["quality"] = self.quality.value
        return d


# --------------------------------------------------------------------------
# Event protocol
# --------------------------------------------------------------------------

class Event(str, Enum):
    LOG = "log"
    PROBE = "probe"
    PROGRESS = "progress"
    STAGE = "stage"
    DONE = "done"
    ERROR = "error"
    CANCELLED = "cancelled"


def emit(event: Event, job_id: str = "", **payload: Any) -> None:
    """Write one JSON event to stdout and flush immediately.

    Flushing per line is essential: the Rust side reads this stream live to
    drive the progress bar. Buffered output would make progress appear to
    stall and then jump to 100%.
    """
    record = {
        "v": PROTOCOL_VERSION,
        "event": event.value,
        "job_id": job_id,
        "ts": round(time.time(), 3),
        **payload,
    }
    sys.stdout.write(json.dumps(record, default=str) + "\n")
    sys.stdout.flush()


def log(msg: str, job_id: str = "", level: str = "info") -> None:
    emit(Event.LOG, job_id, level=level, message=msg)


def fail(job_id: str, message: str, *, hint: str = "", code: str = "download_failed") -> "NoReturn":  # type: ignore[name-defined]
    """Emit a structured error and exit non-zero.

    Errors carry a machine-readable `code` so the UI can offer the right
    remedy ("update yt-dlp", "video is private", "age-restricted — sign in")
    instead of dumping a traceback on the user.
    """
    emit(Event.ERROR, job_id, code=code, message=message, hint=hint)
    sys.exit(1)
