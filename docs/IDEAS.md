# Video Downloader — Design Ideas

**Short answer: yes, absolutely possible.** It's a well-trodden problem and you
can have a working version in a weekend. Below is what I verified works, the
architecture I'd recommend, and the ideas worth stealing.

---

## 0. What I already proved in this repo

`poc/engine_poc.py` runs the real pipeline and works:

```
paste URL → resolve formats → pick best HD → download w/ progress → mux to .mp4
```

Verified in the sandbox against a served 1080p file:

```
[1/4] Resolving: http://127.0.0.1:8899/in.mp4
[2/4] Title : in   Site: generic
[3/4] Downloading best HD video + audio ...
  [#########################] 100.0%   474.17 MB/s  ETA 0s
[4/4] Done -> downloads/in [in].mp4  (4.1 MB)

Stream #0:0 Video: h264 (High), 1920x1080, 30 fps
Stream #0:1 Audio: aac (LC), 44100 Hz
```

Environment notes from that run:

| Thing | Status |
|---|---|
| `yt-dlp` 2026.10.26 | installs fine via pip, importable as a Python module |
| `ffmpeg` | **not** on the system, no root for `apt-get` |
| ffmpeg workaround | `imageio-ffmpeg` wheel ships a static `ffmpeg 7.0.2` build (libx264, libx265, libdav1d, libvpx) — extracted and working |
| Live site extraction | **blocked in this sandbox** — only github/npm/pypi are reachable, so youtube.com etc. can't be contacted from here |

That last row matters: the code is correct, but end-to-end tests against real
sites have to run on your machine or a real server, not in this sandbox.

---

## 1. The one technical insight that decides everything

**"HD" is not a single file you download.** On YouTube, Vimeo, TikTok and most
modern platforms, video and audio are *separate streams* (DASH / HLS):

```
format 137  →  1080p  video only   (vcodec=avc1, acodec=none)
format 140  →  128kbps audio only  (vcodec=none, acodec=mp4a)
```

So a downloader that "grabs HD" must **fetch two streams and mux them**:

```bash
ffmpeg -i video.mp4 -i audio.m4a -c copy -movflags +faststart out.mp4
```

`-c copy` is the important part — it *remuxes* (repacks the container) instead
of re-encoding. It takes ~1 second for a 10-minute video and loses **zero**
quality. Beginners re-encode by accident and wonder why a 1080p download takes
20 minutes and looks worse.

This is also why **ffmpeg is not optional**. Without it you're capped at the
low-quality pre-merged formats (usually ≤720p, often 360p).

---

## 2. Don't write extractors. Stand on yt-dlp.

`yt-dlp` supports **1800+ sites** and is patched within days when a site changes
its layout. Writing your own scraper for even one major site is a permanent
maintenance treadmill — YouTube alone changes its player JS regularly and now
gates some formats behind PO tokens / session cookies.

```python
import yt_dlp   # use it as a library, NOT by shelling out — see §6
```

Rule of thumb: yt-dlp is the engine, you build the product around it.

---

## 3. Three ways to build it — pick your scope

### Option A — CLI (one evening)
Thin wrapper over yt-dlp. `vidl <url> --quality 1080`. Good for learning,
useless as a product.

### Option B — Web app (recommended, ~3–5 days) ⭐
```
Browser ──paste URL──> FastAPI ──> job queue ──> yt-dlp worker ──> ffmpeg mux
   ▲                                                   │
   └────────── progress bar ◀── SSE/WebSocket ◀────────┘
```
- **Backend:** FastAPI + `yt-dlp` + SQLite (job history) + a worker pool
- **Progress:** Server-Sent Events. SSE beats WebSockets here — it's one-way,
  survives proxies, and is ~10 lines of code.
- **Frontend:** one input, one button, a progress ring, a downloads list.
  Vanilla or React, doesn't matter at this size.

### Option C — Desktop app (best real-world UX)
Tauri or Electron wrapping the same engine, saving straight to
`~/Downloads`. Solves the two things that break web versions: **disk space**
and **file delivery**. If your goal is "an app people actually use daily,"
this is the strongest option.

---

## 4. Format selection — the cheat sheet

This single string does 80% of the work:

```python
"format": "bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b"
```

Read as: *best video up to 1080p **plus** best audio → else best pre-merged
≤1080p → else best anything.*

| User wants | Selector |
|---|---|
| Best available | `bv*+ba/b` |
| 1080p cap | `bv*[height<=1080]+ba/b` |
| 4K | `bv*[height<=2160]+ba/b` |
| "HD but small file" | `bv*[height<=720][ext=mp4]+ba[ext=m4a]/b[height<=720]` |
| Audio only (podcast) | `ba/b` + `postprocessors: FFmpegExtractAudio` |
| Widely compatible | prefer `vcodec=avc1` + `acodec=mp4a` → `.mp4` |

Compatibility trap: the *highest* quality stream is often **AV1** or **VP9** in
`.webm`. Great quality, but won't play on older TVs, iMessage, PowerPoint, or
some phones. Offer a "Maximum compatibility (H.264/AAC .mp4)" toggle — it's a
two-line change and users will love you for it.

---

## 5. Feature ideas, roughly in order of value

**Core (MVP)**
1. Paste → auto-detect platform → show thumbnail, title, duration, channel
   *before* downloading. Instant trust signal.
2. Quality picker: 4K / 1080p / 720p / Audio-only, with real file-size estimates.
3. Live progress: %, speed, ETA, and a **per-stream** bar (video then audio then muxing).
4. Cancel button. Non-negotiable — long downloads get abandoned.
5. Resume on failure (yt-dlp does this via `.part` files; expose it).

**High leverage, low effort**
6. **Playlist / batch mode** — paste a playlist URL or a textarea of links, queue them all.
7. **Clip it** — "download only 00:01:20 → 00:02:05". Uses ffmpeg `-ss/-to`,
   and it's the feature that makes people share your app.
8. Subtitles toggle (embed or `.srt` sidecar).
9. Audio-only → MP3/M4A/OPUS for music and podcasts.
10. Thumbnail + metadata writing (`FFmpegMetadata` postprocessor) so files look
    right in a media player.
11. Download history in SQLite with re-download and "open folder" actions.
12. Disk-space guard: check free space before starting, warn under ~2 GB.

**Differentiators (pick one, don't build all)**
13. **Browser extension** — a download button injected into the video page.
    This is how the category leaders actually win.
14. **Telegram / Discord bot** — paste link in chat, get file back. Enormous in
    India and SE Asia; very little frontend work.
15. SponsorBlock integration (`--sponsorblock-remove`) to cut sponsors automatically.
16. "Watch folder" — subscribe to a channel, auto-download new uploads.
17. Server-side preview so users can trim *before* committing to a full download.

---

## 6. Security & correctness — the part that bites people

This app takes an arbitrary URL from a stranger and fetches it. That's a loaded gun.

- **SSRF.** A user can paste `http://169.254.169.254/` (cloud metadata) or
  `http://192.168.1.1/admin` and make *your server* probe your internal network.
  Resolve DNS first, reject private/loopback/link-local ranges, allow `http/https` only.
- **Never build shell strings.** Use `subprocess.run([...], shell=False)` with
  argument lists. Filenames come from the internet and will contain quotes,
  `$(...)`, and newlines.
- **Use yt-dlp as a library**, not `os.system("yt-dlp ...")`. You get progress
  hooks, structured errors, and no injection surface.
- **Filenames.** yt-dlp's `%(title)s` can produce path separators and 200-char
  names. Constrain with `%(title).80B` and `restrictfilenames`, and always join
  onto a fixed base dir — never trust the returned path blindly.
- **Zip-bomb equivalents.** Cap duration and filesize; a pasted 10-hour 4K
  livestream will fill your disk.
- **Cookies.** Private/age-restricted videos need a session cookie. That's a
  credential — never accept it as a form field in a public deployment, never
  log it, never store it in plaintext next to the DB.

---

## 7. Deployment reality check

| Target | Verdict |
|---|---|
| Local machine / LAN | ✅ Perfect. No storage or bandwidth limits. |
| Desktop app (Tauri) | ✅ Best UX. Files land in `~/Downloads`. |
| VPS + Docker | ✅ Works. Needs real disk (50 GB+) and bandwidth budget. |
| **Vercel / Netlify / Cloud Run** | ❌ **Serverless won't work.** Ephemeral filesystem, ~10–60s function timeout, no long-lived process, no ffmpeg. A 500 MB download cannot complete there. |
| Railway / Render / Fly.io | ⚠️ Possible with a persistent disk attached and a worker dyno. |

If you want a public web version: a small VPS (or Fly.io with a volume) running
Docker + a job queue. If you want it *easy*: desktop app.

Two operational chores to design in from day one:
- **Auto-update yt-dlp** on a schedule (`pip install -U yt-dlp`). Extractors rot
  weekly. Most "my downloader broke" reports are just a stale yt-dlp.
- **Temp file cleanup.** Failed jobs leave `.part` files. Sweep them.

---

## 8. Legal / ToS — the honest paragraph

Downloading content you own, that's Creative Commons, public domain, or
explicitly permitted is fine, and yt-dlp is legitimate widely-used software.

But most platforms' Terms of Service prohibit downloading, and much of what's
hosted there is other people's copyrighted work. Practically, that means:

- A **personal/local tool** is a normal, defensible thing to build.
- A **public service** that downloads arbitrary YouTube links is the thing that
  gets DMCA'd, blocked, and de-platformed — it's also exactly the model of the
  sketchy ad-malware sites you're probably trying to build something better than.
- DRM-protected content (Netflix, Prime, Disney+) is a different matter
  entirely: circumventing it is illegal in most jurisdictions. yt-dlp won't and
  you shouldn't try.

None of this blocks the project. It just points at the same conclusion the
deployment table does: **build it as a personal tool or desktop app first**, and
add a clear "only download what you have the rights to" notice in the UI.

---

## 9. Suggested stack

```
Engine      yt-dlp (library) + static ffmpeg via imageio-ffmpeg
Backend     Python 3.11 + FastAPI + SQLite + RQ/arq worker queue
Transport   Server-Sent Events for progress
Frontend    React or plain HTML/CSS/JS — a single paste box
Packaging   Docker for a server, Tauri for desktop
```

Python for the backend because yt-dlp *is* Python — using it in-process gives
you progress hooks and typed errors for free. Node is a fine frontend; driving
yt-dlp from Node means shelling out to a subprocess, which loses all of that.

---

## 10. Build order

1. **Day 1** — Engine class wrapping yt-dlp. URL in, file out. ✅ *already prototyped*
2. **Day 2** — FastAPI: `POST /jobs`, `GET /jobs/{id}/events` (SSE). Run one job.
3. **Day 3** — Frontend: paste box, format preview, quality picker, progress ring.
4. **Day 4** — Queue, cancel, history, disk guard, cleanup.
5. **Day 5** — Docker, auto-update of yt-dlp, error handling for the 20 ways
   a download can fail.
6. **Then** — pick *one* differentiator (§5, items 13–17) and polish it.

The mistake to avoid: building the UI before the engine reliably handles the
awkward cases — playlists, live streams, region-locked videos, age-gated
videos, 8-hour podcasts, filenames with emoji. Get the engine boring and
bulletproof first; the UI is the easy part.
