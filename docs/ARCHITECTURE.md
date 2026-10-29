# Architecture

How the pieces fit, and why they fit that way.

```
┌──────────────────────────────────────────────────────────────┐
│  Tauri webview  (src/)                                        │
│  paste box · quality picker · progress rows                   │
└───────────────┬──────────────────────────────────────────────┘
                │ invoke("start_download", {…})
                │ listen("engine-event")
┌───────────────▼──────────────────────────────────────────────┐
│  Rust shell  (src-tauri/src/)                                 │
│  commands.rs  – validates input, spawns, returns a job id     │
│  engine.rs    – spawns the process, relays stdout → events    │
│  jobs.rs      – job state, so the UI can reload mid-download  │
└───────────────┬──────────────────────────────────────────────┘
                │ spawn + read stdout
┌───────────────▼──────────────────────────────────────────────┐
│  Engine  (engine/)  — Python, packaged as a PyInstaller sidecar│
│  main.py    – probe / download, format selection, transcode   │
│  protocol.py– the newline-delimited JSON event contract       │
│  safety.py  – URL validation, SSRF guard, filename sanitising │
│  ffmpeg.py  – locates a usable ffmpeg                         │
└───────────────┬──────────────────────────────────────────────┘
                │ library calls / subprocess
┌───────────────▼──────────────────────────────────────────────┐
│  yt-dlp (1800+ extractors)   +   ffmpeg (mux / transcode)     │
└──────────────────────────────────────────────────────────────┘
```

## Why a separate engine process

The engine is Python; the shell is Rust. Rather than embed an interpreter
(PyO3, ~20 MB, fragile across platforms), Rust spawns the engine as a sidecar
and reads its stdout.

Three things fall out of that choice:

1. **The protocol is the contract.** `engine/protocol.py` and `src/types.ts`
   mirror each other by hand. Everything crosses as newline-delimited JSON with
   a `v` version field.
2. **stdout must be pure protocol.** Any stray `print()` corrupts the stream, so
   the engine only ever writes via `emit()`. A test
   (`test_stdout_is_pure_protocol`) enforces this.
3. **The engine is independently testable.** 109 tests run it directly with no
   Rust and no browser.

The same engine also serves a browser UI through `devserver.py`, which is not a
mock — it spawns the identical process and relays the identical events. Only the
pipe differs (SSE instead of Tauri IPC). That is what lets the UI be developed
and demoed without compiling Rust.

## The core technical problem: HD is two streams

On YouTube, Vimeo and TikTok, HD is **not** a single file. Video and audio are
separate DASH streams:

```
format 137  →  1080p video only   (vcodec=avc1, acodec=none)
format 140  →  128k audio only    (vcodec=none, acodec=mp4a)
```

So "download in HD" means fetch both, then **mux**:

```bash
ffmpeg -i video.mp4 -i audio.m4a -c copy -movflags +faststart out.mp4
```

`-c copy` remuxes without re-encoding: about a second for a 10-minute video,
zero quality loss. This is why **ffmpeg is a hard dependency** — without it you
are capped at low-quality pre-merged formats, often 360p.

## Compatibility mode (the default)

The highest-quality stream is usually **VP9 or AV1 in `.webm`** — great quality,
but it will not play on older TVs, iMessage, PowerPoint or many phones.

So the default output is **H.264 video + AAC audio in `.mp4`**, in two stages:

1. **Prefer it.** The selector asks for `avc1` + `mp4a` first:
   ```
   bv*[height<=?1080][vcodec^=avc1]+ba[acodec^=mp4a]
     /bv*[height<=?1080][ext=mp4]+ba[ext=m4a]
     /bv*[height<=?1080]+ba
     /b[height<=?1080]
     /bv*+ba/b
   ```
   Every chain ends in a permissive fallback. A strict selector that matches
   nothing makes yt-dlp **error out** instead of quietly dropping quality, and a
   failed download is worse than a 720p one.

2. **Enforce it.** A selector is a *preference*, not a guarantee. If a video
   only exists in VP9, we still get VP9. So after download,
   `needs_transcode()` inspects the actual codecs and re-encodes to H.264/AAC
   only when required.

That second stage matters in both directions, and both are tested:

| Source | Behaviour | Test |
|---|---|---|
| VP9/Opus `.webm` | transcodes → `.mp4` H.264/AAC | `test_vp9_source_is_transcoded_to_h264_aac` |
| H.264/AAC `.mp4` | **passes through untouched** | `test_h264_source_is_not_re_encoded` |

Skipping the second case would burn minutes of CPU and degrade quality on a file
that was already perfect.

Transcode writes to a temp file and only replaces the original on success, so a
failed re-encode never destroys a good download.

## Codec fields are tri-state

A subtle bug worth recording. yt-dlp uses:

- the **string** `"none"` → "this stream definitely has no such track"
- Python **`None`** → "unknown"

The generic extractor (any direct file URL) reports `None` for both codecs,
because it never probes the file. Treating `None` as absent mislabels an ordinary
1080p `.mp4` as an *audio* stream, which then hides it from every video quality
preset in the UI. `_classify_format()` distinguishes them and falls back to
`muxed` when unknown.

## Event flow

```
resolving → resolved → plan → downloading → postprocessing → [transcoding] → done
```

`plan` fires only when the site served separate video and audio streams, and
says so — otherwise the bar hits 100% and appears to stall during the mux.

Progress arrives via `progress_hooks` as `percent` / `speed` / `eta`, one event
per chunk. Rust mirrors every event into `jobs.rs`, so reloading the window
mid-download reconciles from `list_jobs` rather than orphaning the row.

Two flags are both required to keep stdout clean:

```python
"quiet": True,
"noprogress": True,   # quiet alone does NOT silence yt-dlp's progress printer
```

Without `noprogress`, two competing progress lines interleave in stdout and
corrupt the stream.

## Errors are codes, not tracebacks

`classify_error()` maps yt-dlp failures onto a machine-readable `code` plus an
actionable `hint`, so the UI can say *"age-restricted — export cookies from a
signed-in browser"* instead of dumping a stack trace.

Covered: `age_restricted`, `private`, `members_only`, `unavailable`,
`geo_blocked`, `unsupported_url`, `forbidden`, `not_found`, `network`,
`format_unavailable`, `disk_full`, `ffmpeg`, `unsafe_url`, `engine_crashed`.

The ordering of that table matters — it is checked top-to-bottom, so specific
messages must come before generic ones (`"tls"` would otherwise swallow more
precise network errors).

## Security

The app takes an arbitrary URL from a human and fetches it server-side. Guards
live in `engine/safety.py`:

- **SSRF.** Rejects non-http(s) schemes, and any host resolving to
  loopback / private / link-local / reserved ranges. `169.254.169.254` is cloud
  metadata; `192.168.x.x` is the user's own LAN.
- **Bare-colon schemes.** `javascript:alert(1)` and `data:text/html,…` use
  `scheme:` with *no* slashes. Matching on `://` made them look scheme-less, so
  they were auto-prefixed to `https://javascript:alert(1)` and passed the
  allow-list. The regex now matches a bare colon.
- **Malformed ports.** `.hostname` tolerates `:notaport`; `.port` raises. Both
  are caught and reported as a clean error.
- **Filenames.** Titles arrive from the internet. `safe_filename()` strips path
  separators and control characters, and truncates on a **byte** boundary —
  naive character slicing splits multi-byte emoji and produces mojibake names.
- **No shell.** The engine is invoked as an argument list, never a shell string.
- **Cookies are credentials.** Never accepted as a form field in a public
  deployment, never logged, gitignored by pattern.

`ALLOW_LOCAL=1` exists so tests can point the engine at a local HTTP server. It
is **off** by default and must never be enabled in a server deployment.

## ffmpeg discovery

`engine/ffmpeg.py` resolves in order:

1. `$FFMPEG_PATH`
2. `ffmpeg` on `PATH`
3. bundled binary in `resources/bin/`
4. the `imageio-ffmpeg` wheel's static build (dev convenience)

If none is found the engine **degrades rather than crashing** — single-file
downloads still work — and emits a warning the UI shows in the status badge.

`scripts/setup_engine.py` fetches a known-good static build per platform so
users never install ffmpeg by hand.

## Packaging

`scripts/setup_engine.py --sidecar` bundles the engine with PyInstaller into
`src-tauri/binaries/engine-<target-triple>`, which Tauri's `externalBin` ships.

`--collect-all yt_dlp` is **required**: yt-dlp discovers extractors dynamically,
so static analysis misses them and you get a binary that starts fine and reports
"Unsupported URL" for every site — a failure that looks like a network problem.

## Deployment constraints

| Target | Verdict |
|---|---|
| Desktop (Tauri) | ✅ Files land in `~/Downloads`; no storage or timeout limits |
| Local / LAN | ✅ |
| VPS + Docker | ✅ Needs real disk and bandwidth |
| **Vercel / serverless** | ❌ Ephemeral FS, ~10–60 s timeout, no long-lived process, no ffmpeg. A 500 MB download cannot complete |

Operational chores designed in from the start: **auto-update yt-dlp** (extractors
rot weekly — most "it broke" reports are just a stale version) and **sweep
`.part` files** from failed jobs.

## Legal

Downloading content you own, that is Creative Commons, public domain, or
explicitly permitted is fine, and yt-dlp is legitimate widely-used software.

But most platforms' ToS prohibit downloading, and much of what they host is
other people's copyrighted work. A **personal/local tool** is defensible; a
**public service** downloading arbitrary links is what gets DMCA'd and
de-platformed. DRM-protected streams (Netflix, Prime, Disney+) are a different
matter entirely — circumventing them is illegal in most jurisdictions, yt-dlp
will not do it, and neither should you.

The UI carries a permanent notice to that effect.
