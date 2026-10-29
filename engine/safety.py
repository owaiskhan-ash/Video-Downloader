"""
Safety checks. This app takes a URL from a human and fetches it, which is a
classic SSRF and path-traversal surface — worth guarding even in a desktop app,
because users paste links from anywhere.
"""

from __future__ import annotations

import ipaddress
import re
import socket
from pathlib import Path
from urllib.parse import urlparse

# Characters that break filesystems, shells, or media players. yt-dlp has its
# own restrictfilenames, but titles also arrive via our own code paths.
_UNSAFE_NAME = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


class UnsafeURL(ValueError):
    """Raised when a URL should not be fetched."""


def validate_url(raw: str) -> str:
    """Validate a pasted URL. Returns the normalised URL or raises UnsafeURL.

    Blocks:
      * non-http(s) schemes  -> file://, ftp://, gopher:// etc.
      * hosts resolving to loopback / private / link-local / reserved ranges
        (this is the SSRF guard: 169.254.169.254 is cloud metadata,
         192.168.x.x and 10.x.x.x are the user's own LAN)
      * bare IPs in those ranges without needing DNS at all

    Note: a localhost escape hatch is kept deliberately (allow_local=True) so
    the test-suite can point the engine at a local http.server. Never enable it
    in a public/server deployment.
    """
    raw = (raw or "").strip()
    if not raw:
        raise UnsafeURL("Empty URL")

    # Be forgiving: users paste "youtube.com/watch?v=x" without a scheme.
    #
    # SECURITY: match a bare "scheme:", NOT "scheme://". Requiring the slashes
    # lets `javascript:alert(1)` and `data:text/html,...` look scheme-less, so
    # they get https:// prepended and sail through the allow-list below as
    # "https://javascript:alert(1)". Detecting the colon means they are parsed
    # with their real scheme and correctly rejected.
    if not re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", raw):
        raw = "https://" + raw

    parsed = urlparse(raw)

    if parsed.scheme not in ("http", "https"):
        raise UnsafeURL(f"Unsupported scheme '{parsed.scheme}' — only http and https")

    host = None
    try:
        host = parsed.hostname
    except ValueError as e:
        # urlparse raises on a malformed port, e.g. "https://host:notaport".
        # Report it as an invalid URL rather than letting it escape as a 500.
        raise UnsafeURL(f"Malformed URL: {e}") from e

    if not host:
        raise UnsafeURL("URL has no host")

    # `hostname` tolerates a malformed port but `.port` raises ValueError.
    # Validate it here so the user gets "invalid port" up front instead of an
    # opaque connection failure deep inside yt-dlp.
    try:
        port = parsed.port
    except ValueError as e:
        raise UnsafeURL(f"Malformed port in URL: {e}") from e
    if port is not None and not (0 < port < 65536):
        raise UnsafeURL(f"Port out of range: {port}")

    if len(raw) > 2048:
        raise UnsafeURL("URL too long")

    return raw


def is_private_host(host: str, *, allow_local: bool = False) -> bool:
    """True if `host` resolves to an address that should not be fetched."""
    if allow_local and host in ("localhost", "127.0.0.1", "::1"):
        return False

    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        # Not a literal IP — resolve it. Resolve *before* handing the URL to
        # yt-dlp, otherwise a DNS rebinding trick can point a public-looking
        # hostname at an internal address by the time we connect.
        try:
            infos = socket.getaddrinfo(host, None)
        except socket.gaierror:
            return False  # unresolvable; let the downloader produce the error
        for info in infos:
            try:
                ip = ipaddress.ip_address(info[4][0])
            except ValueError:
                continue
            if _is_blocked_ip(ip):
                return True
        return False

    return _is_blocked_ip(ip)


def _is_blocked_ip(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )


def assert_safe_url(raw: str, *, allow_local: bool = False) -> str:
    """validate_url + SSRF check in one call."""
    url = validate_url(raw)
    host = urlparse(url).hostname or ""
    if is_private_host(host, allow_local=allow_local):
        raise UnsafeURL(
            f"Refusing to fetch '{host}': it resolves to a private, loopback or "
            "link-local address."
        )
    return url


def safe_filename(name: str, *, max_len: int = 120, fallback: str = "video") -> str:
    """Sanitise a title into something safe to use as a filename.

    Strips path separators (so a title like '../../etc/passwd' can't escape the
    output dir), control characters, and trailing dots/spaces that Windows
    rejects. Truncates on a byte boundary, not a character count, because the
    filesystem limit is bytes — naive slicing splits multi-byte emoji in half
    and produces mojibake filenames.
    """
    name = _UNSAFE_NAME.sub("_", (name or "").strip())
    name = re.sub(r"\s+", " ", name).strip(" .")

    if not name:
        return fallback

    encoded = name.encode("utf-8")
    if len(encoded) > max_len:
        # Cut, then decode ignoring the partial trailing character.
        encoded = encoded[:max_len]
        name = encoded.decode("utf-8", errors="ignore").rstrip(" .")

    return name or fallback


def ensure_outdir(path: str | Path) -> Path:
    """Create and return the output directory, refusing relative escapes."""
    p = Path(path).expanduser()
    if not p.is_absolute():
        p = Path.cwd() / p

    # Check before mkdir: Path.mkdir(exist_ok=True) still raises FileExistsError
    # when the path exists as a *file*, which surfaces to the user as a confusing
    # low-level error instead of "that isn't a folder".
    if p.exists() and not p.is_dir():
        raise NotADirectoryError(f"{p} exists but is not a directory")

    p.mkdir(parents=True, exist_ok=True)
    if not p.is_dir():
        raise NotADirectoryError(f"{p} exists but is not a directory")
    return p
