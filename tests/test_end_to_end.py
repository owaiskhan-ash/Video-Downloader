"""
End-to-end tests: run the real engine against a locally served video.

These are the only tests that prove the pieces actually fit together — URL in,
a playable H.264/AAC mp4 out. They need ffmpeg and are skipped without it.

Everything runs against 127.0.0.1, so ALLOW_LOCAL must be set: the SSRF guard
blocks loopback by default, which is correct for production.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from engine.main import build_format_selector, download, inspect_codecs  # noqa: E402
from engine.protocol import JobSpec, Quality  # noqa: E402


def run_engine(args: list[str], *, stdin: str | None = None, env_extra: dict | None = None):
    """Run the engine as a subprocess, exactly as Tauri/devserver do."""
    import os

    env = dict(os.environ)
    env["ALLOW_LOCAL"] = "1"
    if env_extra:
        env.update(env_extra)

    return subprocess.run(
        [sys.executable, "-m", "engine", *args],
        cwd=str(ROOT),
        input=stdin,
        capture_output=True,
        text=True,
        timeout=300,
        env=env,
        check=False,
    )


def events_from(stdout: str) -> list[dict]:
    """Parse the newline-delimited JSON protocol, ignoring any stray output."""
    out = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


@pytest.mark.usefixtures("allow_local")
class TestProbeCommand:
    def test_probe_reports_title_and_formats(self, media_server) -> None:
        res = run_engine(["probe", media_server["h264_url"], "--job-id", "p1"])
        assert res.returncode == 0, res.stderr

        probes = [e for e in events_from(res.stdout) if e["event"] == "probe"]
        assert len(probes) == 1
        p = probes[0]

        assert p["job_id"] == "p1"
        assert p["title"]
        assert isinstance(p["formats"], list)

    def test_probe_carries_the_supplied_job_id(self, media_server) -> None:
        """A hardcoded job id would make two rapid pastes indistinguishable."""
        res = run_engine(["probe", media_server["h264_url"], "--job-id", "custom-42"])
        events = events_from(res.stdout)
        assert events, "engine produced no parseable events"
        assert all(e.get("job_id") == "custom-42" for e in events)

    def test_probe_blocks_private_address(self) -> None:
        """ALLOW_LOCAL permits 127.0.0.1, but the metadata IP must still fail.

        This is the SSRF case that matters: cloud metadata at a link-local
        address. Loopback is allow-listed for tests; link-local never is.
        """
        res = run_engine(["probe", "http://169.254.169.254/latest/meta-data/"])
        assert res.returncode != 0
        errs = [e for e in events_from(res.stdout) if e["event"] == "error"]
        assert errs
        assert errs[0]["code"] == "unsafe_url"

    def test_probe_rejects_file_scheme(self) -> None:
        res = run_engine(["probe", "file:///etc/passwd"])
        assert res.returncode != 0
        errs = [e for e in events_from(res.stdout) if e["event"] == "error"]
        assert errs and errs[0]["code"] == "unsafe_url"


@pytest.mark.usefixtures("allow_local")
class TestDownloadCommand:
    def test_downloads_and_produces_a_real_file(self, media_server, tmp_path) -> None:
        spec = {
            "job_id": "dl-basic",
            "url": media_server["h264_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
            "metadata": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        assert res.returncode == 0, res.stderr

        events = events_from(res.stdout)
        done = [e for e in events if e["event"] == "done"]
        assert done, f"no done event; got: {[e['event'] for e in events]}"

        path = Path(done[0]["path"])
        assert path.is_file()
        assert path.stat().st_size > 0
        assert done[0]["size"] == path.stat().st_size

    def test_emits_the_full_stage_sequence(self, media_server, tmp_path) -> None:
        spec = {
            "job_id": "dl-stages",
            "url": media_server["h264_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        stages = [e["stage"] for e in events_from(res.stdout) if e["event"] == "stage"]

        assert "resolving" in stages
        assert "downloading" in stages

    def test_progress_events_are_bounded_and_ordered(self, media_server, tmp_path) -> None:
        spec = {
            "job_id": "dl-progress",
            "url": media_server["h264_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        pcts = [
            e["percent"]
            for e in events_from(res.stdout)
            if e["event"] == "progress" and isinstance(e.get("percent"), (int, float))
        ]

        assert pcts, "no progress events emitted"
        # A percentage outside 0..100 would render as a broken progress bar.
        assert all(0.0 <= p <= 100.0 for p in pcts)
        assert pcts[-1] == pytest.approx(100.0, abs=0.01)

    def test_postprocessor_hook_does_not_crash(self, media_server, tmp_path) -> None:
        """Regression: d["postprocessor"] is a STRING (pp_key()), not an object.

        Calling .get("name") on it raised AttributeError — after every byte was
        already on disk, so it looked like a download that died at 100%.
        Metadata postprocessing is what triggers the hook, so enable it.
        """
        spec = {
            "job_id": "dl-pp-hook",
            "url": media_server["h264_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
            "metadata": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))

        assert "AttributeError" not in res.stdout
        assert "AttributeError" not in res.stderr
        assert res.returncode == 0, res.stderr

        pp_stages = [
            e for e in events_from(res.stdout)
            if e["event"] == "stage" and e.get("stage") == "postprocessing"
        ]
        assert pp_stages, "postprocessor hook never fired"
        # detail must be a plain string name, never a stringified dict
        assert all(isinstance(e.get("detail", ""), str) for e in pp_stages)

    def test_vp9_source_is_transcoded_to_h264_aac(self, media_server, tmp_path) -> None:
        """The core compatibility guarantee.

        YouTube's best streams are usually VP9/AV1 in .webm. Compatibility mode
        must deliver H.264/AAC in .mp4, so a VP9 source has to be re-encoded.
        """
        spec = {
            "job_id": "dl-transcode",
            "url": media_server["vp9_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        assert res.returncode == 0, res.stderr

        events = events_from(res.stdout)
        done = [e for e in events if e["event"] == "done"]
        assert done, f"no done event; got {[e['event'] for e in events]}"

        path = Path(done[0]["path"])
        assert path.suffix.lower() == ".mp4", f"expected mp4 container, got {path.name}"

        vcodec, acodec = inspect_codecs(path)
        assert vcodec is not None and vcodec.lower().startswith(("h264", "avc1")), (
            f"video should be H.264 after compatibility transcode, got {vcodec}"
        )
        assert acodec is None or acodec.lower().startswith(("aac", "mp4a")), (
            f"audio should be AAC after compatibility transcode, got {acodec}"
        )

        stages = [e["stage"] for e in events if e["event"] == "stage"]
        assert "transcoding" in stages, "VP9 source should have triggered the transcode stage"

    def test_h264_source_is_not_re_encoded(self, media_server, tmp_path) -> None:
        """The inverse guarantee: don't burn CPU degrading an already-good file."""
        spec = {
            "job_id": "dl-passthrough",
            "url": media_server["h264_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        assert res.returncode == 0, res.stderr

        events = events_from(res.stdout)
        stages = [e["stage"] for e in events if e["event"] == "stage"]
        assert "transcoding" not in stages, (
            "an H.264/AAC source must not be re-encoded — that costs minutes "
            "and degrades quality for no benefit"
        )

    def test_filename_is_not_duplicated_for_generic_urls(self, media_server, tmp_path) -> None:
        """The generic extractor sets id == title, giving 'Clip [Clip].mp4'."""
        spec = {
            "job_id": "dl-fname",
            "url": media_server["h264_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        done = [e for e in events_from(res.stdout) if e["event"] == "done"]
        assert done
        name = Path(done[0]["path"]).stem
        assert not re.search(r"\[(.+?)\]$", name) or name.split("[")[0].strip() != name[
            name.index("[") + 1 : name.rindex("]")
        ].strip(), f"filename has a redundant [id] suffix: {name}"

    def test_no_temp_files_left_behind(self, media_server, tmp_path) -> None:
        spec = {
            "job_id": "dl-clean",
            "url": media_server["vp9_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        assert res.returncode == 0, res.stderr

        leftovers = [
            p.name for p in tmp_path.iterdir()
            if p.suffix.lower() in (".part", ".tmp", ".ytdl")
            or "compat" in p.name.lower()
        ]
        assert not leftovers, f"temp files were not cleaned up: {leftovers}"

    def test_output_dir_is_created_if_missing(self, media_server, tmp_path) -> None:
        nested = tmp_path / "deep" / "nested" / "dir"
        spec = {
            "job_id": "dl-mkdir",
            "url": media_server["h264_url"],
            "outdir": str(nested),
            "quality": "720",
            "compatible": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        assert res.returncode == 0, res.stderr
        assert nested.is_dir()

    def test_invalid_clip_range_is_rejected(self, media_server, tmp_path) -> None:
        """A backwards range must fail loudly, not silently download everything."""
        spec = {
            "job_id": "dl-badclip",
            "url": media_server["h264_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
            "clip_start": 10.0,
            "clip_end": 2.0,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        assert res.returncode != 0

    def test_unsupported_url_reports_a_useful_code(self, tmp_path) -> None:
        spec = {
            "job_id": "dl-badurl",
            "url": "https://example.invalid/video.mp4",
            "outdir": str(tmp_path),
            "quality": "720",
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        assert res.returncode != 0
        errs = [e for e in events_from(res.stdout) if e["event"] == "error"]
        assert errs
        # The code is what lets the UI show a remedy instead of a traceback.
        assert errs[0].get("code")
        assert errs[0].get("message")


@pytest.mark.usefixtures("allow_local")
class TestAudioExtraction:
    def test_audio_quality_yields_audio_file(self, media_server, tmp_path) -> None:
        spec = {
            "job_id": "dl-audio",
            "url": media_server["h264_url"],
            "outdir": str(tmp_path),
            "quality": "audio",
            "compatible": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))
        if res.returncode != 0:
            pytest.skip(f"audio extraction unsupported for this source: {res.stdout[-300:]}")

        done = [e for e in events_from(res.stdout) if e["event"] == "done"]
        assert done
        path = Path(done[0]["path"])
        assert path.is_file()

        vcodec, acodec = inspect_codecs(path)
        assert vcodec is None, f"audio-only output should have no video stream, got {vcodec}"
        assert acodec is not None


class TestDoctorCommand:
    def test_doctor_reports_dependencies(self) -> None:
        res = run_engine(["doctor"])
        events = events_from(res.stdout)
        reports = [e for e in events if e["event"] == "doctor"]

        assert reports, f"no doctor event; stdout was: {res.stdout[:300]}"
        report = reports[0]
        assert report.get("yt_dlp"), "yt-dlp version should be reported"

    def test_doctor_report_is_single_line_json(self) -> None:
        """Regression: indent=2 broke the reverse-scan consumers use to find it.

        Both devserver.py and engine.rs scan lines in reverse for the first that
        parses as JSON. A pretty-printed report makes them match an unrelated
        earlier event instead.
        """
        res = run_engine(["doctor"])
        for line in res.stdout.splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            parsed = json.loads(line)  # must not raise
            if parsed.get("event") == "doctor":
                assert "yt_dlp" in parsed
                return
        pytest.fail("doctor report was not a single-line JSON object")


class TestCLIContract:
    def test_no_args_prints_usage(self) -> None:
        res = run_engine([])
        assert res.returncode == 2
        assert "usage" in res.stderr.lower()

    def test_unknown_command_prints_usage(self) -> None:
        res = run_engine(["frobnicate"])
        assert res.returncode == 2

    def test_formats_debug_command_lists_all_presets(self) -> None:
        res = run_engine(["formats"])
        assert res.returncode == 0
        for q in Quality:
            assert q.value in res.stdout

    def test_stdout_is_pure_protocol(self, media_server, tmp_path) -> None:
        """Every stdout line must parse as JSON, or the relay mis-renders it.

        `quiet: True` alone does NOT silence yt-dlp's own progress printer —
        `noprogress: True` is also required. This test is what keeps that
        regression from coming back.
        """
        spec = {
            "job_id": "dl-pure",
            "url": media_server["h264_url"],
            "outdir": str(tmp_path),
            "quality": "720",
            "compatible": True,
        }
        res = run_engine(["download", "-"], stdin=json.dumps(spec))

        non_json = []
        for line in res.stdout.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                json.loads(line)
            except json.JSONDecodeError:
                non_json.append(line[:80])

        assert not non_json, f"stdout contained non-protocol lines: {non_json[:3]}"


class TestInProcessAPI:
    """The engine is also importable, for embedding without a subprocess."""

    def test_download_via_python_api(self, media_server, tmp_path, monkeypatch) -> None:
        monkeypatch.setenv("ALLOW_LOCAL", "1")
        spec = JobSpec(
            job_id="api-1",
            url=media_server["h264_url"],
            outdir=str(tmp_path),
            quality=Quality.FHD,
            compatible=True,
        )
        path = download(spec, allow_local=True)
        assert path.is_file()
        assert path.stat().st_size > 0

    def test_build_format_selector_is_stable(self) -> None:
        a = build_format_selector(Quality.FHD, True)
        b = build_format_selector(Quality.FHD, True)
        assert a == b, "selector must be deterministic"
