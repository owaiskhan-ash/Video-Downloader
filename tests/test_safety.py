"""
Tests for URL validation, the SSRF guard, and filename sanitisation.

These are the security-relevant invariants. The app takes an arbitrary URL from
a human and fetches it server-side, so a regression here is a real
vulnerability, not a cosmetic bug.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from engine.safety import (
    UnsafeURL,
    assert_safe_url,
    ensure_outdir,
    is_private_host,
    safe_filename,
    validate_url,
)


class TestSchemeValidation:
    @pytest.mark.parametrize(
        "url",
        [
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "http://example.com/video.mp4",
            "https://vimeo.com/123456789",
        ],
    )
    def test_allows_http_and_https(self, url: str) -> None:
        assert validate_url(url) == url

    @pytest.mark.parametrize(
        "url",
        [
            "file:///etc/passwd",
            "ftp://example.com/video.mp4",
            "gopher://example.com/",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
        ],
    )
    def test_blocks_non_http_schemes(self, url: str) -> None:
        with pytest.raises(UnsafeURL):
            validate_url(url)

    def test_adds_scheme_when_missing(self) -> None:
        """Users paste 'youtube.com/watch?v=x' constantly; don't reject that."""
        assert validate_url("youtube.com/watch?v=x") == "https://youtube.com/watch?v=x"

    @pytest.mark.parametrize(
        "url",
        [
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "blob:https://example.com/uuid",
        ],
    )
    def test_bare_colon_schemes_are_not_smuggled_as_https(self, url: str) -> None:
        """Regression: these use `scheme:` with no slashes.

        The scheme-detection regex used to require `://`, so these looked
        scheme-less and got `https://` prepended — turning `javascript:alert(1)`
        into `https://javascript:alert(1)`, which then passed the http/https
        allow-list. The auto-prepend must only apply to genuinely bare hosts.
        """
        with pytest.raises(UnsafeURL):
            validate_url(url)
        with pytest.raises(UnsafeURL):
            assert_safe_url(url)

    def test_malformed_port_is_rejected_not_crash(self) -> None:
        """urlparse raises ValueError on a non-numeric port; don't leak it."""
        with pytest.raises(UnsafeURL):
            validate_url("https://example.com:notaport/video")

    def test_rejects_empty(self) -> None:
        with pytest.raises(UnsafeURL):
            validate_url("")
        with pytest.raises(UnsafeURL):
            validate_url("   ")

    def test_rejects_missing_host(self) -> None:
        with pytest.raises(UnsafeURL):
            validate_url("https://")

    def test_rejects_overlong_url(self) -> None:
        with pytest.raises(UnsafeURL):
            validate_url("https://example.com/" + "a" * 3000)


class TestSSRFGuard:
    """Private/loopback/link-local addresses must never be fetched."""

    @pytest.mark.parametrize(
        "host",
        [
            "169.254.169.254",  # cloud metadata — the classic SSRF target
            "192.168.1.1",      # home router / LAN admin
            "10.0.0.5",         # private range
            "172.16.0.1",       # private range
            "127.0.0.1",        # loopback
            "0.0.0.0",          # unspecified
            "224.0.0.1",        # multicast
            "::1",              # IPv6 loopback
            "fe80::1",          # IPv6 link-local
        ],
    )
    def test_blocks_private_addresses(self, host: str) -> None:
        assert is_private_host(host) is True

    def test_blocks_private_url_end_to_end(self) -> None:
        with pytest.raises(UnsafeURL, match="private|loopback|link-local"):
            assert_safe_url("http://169.254.169.254/latest/meta-data/")

    def test_allows_public_url(self) -> None:
        assert assert_safe_url("https://www.youtube.com/watch?v=x").startswith("https://")

    def test_local_escape_hatch(self) -> None:
        """allow_local exists so tests can point the engine at a local server.

        It must be off by default — otherwise the guard is pointless.
        """
        assert is_private_host("127.0.0.1") is True
        assert is_private_host("127.0.0.1", allow_local=True) is False

        with pytest.raises(UnsafeURL):
            assert_safe_url("http://127.0.0.1:8899/video.mp4")
        # ...but permitted when explicitly allowed
        assert_safe_url("http://127.0.0.1:8899/video.mp4", allow_local=True)

    def test_unresolvable_host_is_not_treated_as_private(self) -> None:
        """DNS failure should surface as a download error, not a security block.

        Returning True here would mislabel an ordinary dead link as an attack.
        """
        assert is_private_host("this-host-does-not-exist.invalid") is False


class TestSafeFilename:
    @pytest.mark.parametrize(
        ("raw", "expected"),
        [
            ("My Video: The Best | Ep 1", "My Video_ The Best _ Ep 1"),
            ('quote"mark', "quote_mark"),
            ("back\\slash", "back_slash"),
            ("what?", "what_"),
            ("star*file", "star_file"),
        ],
    )
    def test_strips_unsafe_characters(self, raw: str, expected: str) -> None:
        assert safe_filename(raw) == expected

    def test_strips_path_traversal(self) -> None:
        """A title containing separators must not escape the output directory."""
        result = safe_filename("../../etc/passwd")
        assert "/" not in result
        assert "\\" not in result
        assert ".." not in result.replace("_.._", "")

    def test_collapses_whitespace(self) -> None:
        assert safe_filename("too    many   spaces") == "too many spaces"

    def test_truncates_on_byte_boundary(self) -> None:
        """Filesystem limits are bytes, not characters.

        Naive slicing splits multi-byte sequences and produces mojibake, so
        truncation must decode with errors="ignore" after cutting.
        """
        long_name = "é" * 200  # 2 bytes each in UTF-8
        result = safe_filename(long_name, max_len=120)
        assert len(result.encode("utf-8")) <= 120
        # Every surviving character must still decode cleanly.
        result.encode("utf-8").decode("utf-8")

    def test_preserves_emoji(self) -> None:
        assert safe_filename("emoji 😀 title") == "emoji 😀 title"

    def test_fallback_for_empty(self) -> None:
        assert safe_filename("") == "video"
        assert safe_filename("...") == "video"
        assert safe_filename("", fallback="untitled") == "untitled"

    def test_strips_windows_illegal_trailing_dots(self) -> None:
        """Windows rejects names ending in a dot or space."""
        assert not safe_filename("trailing...").endswith(".")
        assert not safe_filename("trailing   ").endswith(" ")


class TestEnsureOutdir:
    def test_creates_nested_directory(self, tmp_path) -> None:
        target = tmp_path / "a" / "b" / "c"
        assert ensure_outdir(target) == target
        assert target.is_dir()

    def test_accepts_existing_directory(self, tmp_path) -> None:
        assert ensure_outdir(tmp_path) == tmp_path

    def test_expands_user_home(self, tmp_path, monkeypatch) -> None:
        """A literal '~' in the path must be expanded, not created as a folder."""
        monkeypatch.setenv("HOME", str(tmp_path))
        monkeypatch.setenv("USERPROFILE", str(tmp_path))  # Windows equivalent
        result = ensure_outdir("~/vd-test-out")
        assert "~" not in result.parts
        assert result.is_dir()
        assert result.parent == Path.home()

    def test_rejects_file_as_directory(self, tmp_path) -> None:
        a_file = tmp_path / "not-a-dir"
        a_file.write_text("x")
        with pytest.raises(NotADirectoryError):
            ensure_outdir(a_file)
