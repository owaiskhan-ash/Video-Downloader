"""Download engine package. See main.py for the CLI entry point."""

from .protocol import Event, FormatInfo, JobSpec, ProbeResult, Quality, emit, fail, log
from .safety import UnsafeURL, assert_safe_url, safe_filename

__all__ = [
    "Event",
    "FormatInfo",
    "JobSpec",
    "ProbeResult",
    "Quality",
    "UnsafeURL",
    "assert_safe_url",
    "emit",
    "fail",
    "log",
    "safe_filename",
]
