"""
Tests for format selection and codec classification.

These encode the two decisions that determine whether the app produces a file
the user can actually play:
  * the yt-dlp selector for each quality/compatibility combination
  * the tri-state codec classification (present / absent / unknown)
"""

from __future__ import annotations

import pytest

from engine.main import _classify_format, build_format_selector, needs_transcode
from engine.protocol import FormatInfo, Quality


class TestFormatSelector:
    """The selector string is passed verbatim to yt-dlp, so assert its shape."""

    def test_compatible_mode_prefers_h264_and_aac(self) -> None:
        sel = build_format_selector(Quality.FHD, compatible=True)
        assert "vcodec^=avc1" in sel, "must prefer H.264 video"
        assert "acodec^=mp4a" in sel, "must prefer AAC audio"
        # The mp4 container is what makes the result universally playable.
        assert "[ext=mp4]" in sel

    def test_max_quality_mode_does_not_constrain_codec(self) -> None:
        sel = build_format_selector(Quality.FHD, compatible=False)
        assert "avc1" not in sel, "max-quality mode must allow VP9/AV1"

    @pytest.mark.parametrize(
        ("quality", "expected_cap"),
        [
            (Quality.UHD_4K, "2160"),
            (Quality.FHD, "1080"),
            (Quality.HD, "720"),
            (Quality.SD, "480"),
        ],
    )
    def test_height_cap_per_preset(self, quality: Quality, expected_cap: str) -> None:
        sel = build_format_selector(quality, compatible=True)
        assert f"height<=?{expected_cap}" in sel

    def test_best_has_no_height_cap(self) -> None:
        sel = build_format_selector(Quality.BEST, compatible=False)
        assert "height<=" not in sel

    def test_audio_only_selects_audio(self) -> None:
        sel = build_format_selector(Quality.AUDIO, compatible=True)
        assert sel == "ba/b"
        assert "height" not in sel

    @pytest.mark.parametrize("quality", list(Quality))
    @pytest.mark.parametrize("compatible", [True, False])
    def test_every_selector_ends_in_a_fallback(
        self, quality: Quality, compatible: bool
    ) -> None:
        """A strict selector that matches nothing makes yt-dlp error out.

        Every chain must end permissively so the download degrades to whatever
        is available instead of failing outright.
        """
        sel = build_format_selector(quality, compatible=compatible)
        if quality == Quality.AUDIO:
            return
        tail = sel.split("/")[-1]
        assert tail in ("b", "bv*+ba/b"), f"selector does not degrade gracefully: {sel}"

    @pytest.mark.parametrize("quality", list(Quality))
    def test_selector_is_nonempty(self, quality: Quality) -> None:
        assert build_format_selector(quality, compatible=True).strip()


class TestClassifyFormat:
    """
    Codec fields are tri-state and conflating them is a real bug.

    yt-dlp uses the *string* "none" for "definitely no such track" (DASH
    video-only / audio-only) but Python None for "unknown". The generic
    extractor — which handles any direct file URL — reports None for both,
    because it never probes the file. Treating None as absent mislabels an
    ordinary 1080p .mp4 as audio, which hides it from every video preset.
    """

    def test_dash_video_only(self) -> None:
        f = _classify_format(
            {"format_id": "137", "vcodec": "avc1.640028", "acodec": "none", "height": 1080}
        )
        assert f.kind == "video"
        assert f.height == 1080

    def test_dash_audio_only(self) -> None:
        f = _classify_format(
            {"format_id": "140", "vcodec": "none", "acodec": "mp4a.40.2", "ext": "m4a"}
        )
        assert f.kind == "audio"

    def test_muxed_file(self) -> None:
        f = _classify_format(
            {"format_id": "22", "vcodec": "avc1", "acodec": "mp4a", "height": 720}
        )
        assert f.kind == "muxed"

    def test_unknown_codecs_are_muxed_not_audio(self) -> None:
        """The regression this whole class exists to prevent."""
        f = _classify_format({"format_id": "mp4", "vcodec": None, "acodec": None, "ext": "mp4"})
        assert f.kind == "muxed"
        assert f.kind != "audio"

    def test_unknown_codecs_with_height_is_muxed(self) -> None:
        f = _classify_format(
            {"format_id": "0", "vcodec": None, "acodec": None, "height": 1080, "ext": "mp4"}
        )
        assert f.kind == "muxed"

    def test_vp9_video_only(self) -> None:
        f = _classify_format({"format_id": "248", "vcodec": "vp9", "acodec": "none", "height": 1080})
        assert f.kind == "video"

    def test_filesize_falls_back_to_approx(self) -> None:
        """YouTube reports filesize_approx for DASH streams; prefer the exact
        value but don't show a blank size when only the estimate exists."""
        f = _classify_format({"format_id": "x", "filesize": None, "filesize_approx": 1234})
        assert f.filesize == 1234

    def test_label_includes_height_and_size(self) -> None:
        f = FormatInfo(
            format_id="137",
            kind="video",
            height=1080,
            fps=30.0,
            vcodec="avc1",
            acodec=None,
            ext="mp4",
            filesize=50_000_000,
            tbr=None,
        )
        label = f.label
        assert "1080p" in label
        assert "50 MB" in label

    def test_label_marks_high_fps(self) -> None:
        f = FormatInfo("x", "video", 1080, 60.0, "avc1", None, "mp4", None, None)
        assert "60fps" in f.label

        f30 = FormatInfo("x", "video", 1080, 30.0, "avc1", None, "mp4", None, None)
        assert "fps" not in f30.label, "30fps is the norm; don't clutter the label"

    def test_label_for_audio_uses_bitrate(self) -> None:
        f = FormatInfo("140", "audio", None, None, None, "mp4a", "m4a", None, 128.0)
        assert "128 kbps" in f.label


class TestTranscodeDetection:
    """Compatibility mode must re-encode only when the source needs it."""

    def test_disabled_when_compatibility_off(self, tmp_path) -> None:
        f = tmp_path / "v.webm"
        f.write_bytes(b"x")
        assert needs_transcode(f, compatible=False) == (False, "")

    def test_detects_vp9(self, tmp_path, monkeypatch, ffmpeg_path) -> None:
        sample = tmp_path / "s.webm"
        import shutil

        shutil.copy(str(_vp9_fixture(ffmpeg_path, tmp_path)), str(sample))
        should, reason = needs_transcode(sample, compatible=True)
        assert should is True
        assert "vp9" in reason.lower()

    def test_passes_through_h264(self, tmp_path, ffmpeg_path) -> None:
        sample = _h264_fixture(ffmpeg_path, tmp_path)
        should, reason = needs_transcode(sample, compatible=True)
        assert should is False, f"already-compatible file must not be re-encoded: {reason}"

    def test_missing_file_does_not_crash(self, tmp_path) -> None:
        should, _ = needs_transcode(tmp_path / "nope.mp4", compatible=True)
        assert should is False


# --- fixture helpers -------------------------------------------------------
# Encoding is slow, so these cache per-directory.

def _encode(ffmpeg, tmp_path, name: str, vargs: list[str]):
    from pathlib import Path

    target = Path(tmp_path) / name
    if target.is_file() and target.stat().st_size > 0:
        return target
    import subprocess

    subprocess.run(
        [
            str(ffmpeg), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25:duration=1",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            *vargs, "-shortest", str(target),
        ],
        check=True,
        timeout=180,
    )
    return target


def _vp9_fixture(ffmpeg, tmp_path):
    return _encode(ffmpeg, tmp_path, "vp9.webm", ["-c:v", "libvpx-vp9", "-b:v", "200k", "-c:a", "libopus"])


def _h264_fixture(ffmpeg, tmp_path):
    return _encode(
        ffmpeg, tmp_path, "h264.mp4",
        ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "30", "-pix_fmt", "yuv420p", "-c:a", "aac"],
    )
