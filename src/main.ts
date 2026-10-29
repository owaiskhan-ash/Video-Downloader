/**
 * UI wiring.
 *
 * Deliberately framework-free: the whole app is one paste box, a preview card
 * and a job list. Adding React here would mean a build dependency and a bundle
 * for something that is ~400 lines of DOM updates.
 *
 * All I/O goes through bridge.ts, so this file is identical whether it is
 * running inside Tauri or in the browser dev preview.
 */

import { getBridge, type Bridge } from "./bridge";
import {
  QUALITY_OPTIONS,
  type DownloadRequest,
  type EngineEvent,
  type FormatInfo,
  type Job,
  type JobState,
  type ProbeEvent,
  type Quality,
} from "./types";

/* ------------------------------------------------------------------ *
 * State
 * ------------------------------------------------------------------ */

interface UiJob {
  id: string;
  url: string;
  title: string | null;
  state: JobState;
  percent: number;
  speed: number | null;
  eta: number | null;
  path: string | null;
  size: number | null;
  error: string | null;
  errorCode: string | null;
  hint: string | null;
  stageDetail: string | null;
  el: HTMLElement;
}

let bridge: Bridge;
const jobs = new Map<string, UiJob>();

let currentProbe: ProbeEvent | null = null;
let outdir = "";
let quality: Quality = "1080";

/* ------------------------------------------------------------------ *
 * Element handles
 * ------------------------------------------------------------------ */

const $ = <T extends HTMLElement>(id: string): T => {
  const el = document.getElementById(id);
  if (!el) throw new Error(`missing element #${id}`);
  return el as T;
};

const urlInput = $<HTMLInputElement>("url-input");
const probeBtn = $<HTMLButtonElement>("probe-btn");
const probeError = $("probe-error");
const previewCard = $("preview-card");
const thumb = $<HTMLImageElement>("thumb");
const durationPill = $("duration");
const titleEl = $("title");
const uploaderEl = $("uploader");
const warningsEl = $("warnings");
const qualityPicker = $("quality-picker");
const compatibleToggle = $<HTMLInputElement>("compatible");
const optSubs = $<HTMLInputElement>("opt-subs");
const optThumb = $<HTMLInputElement>("opt-thumb");
const optMeta = $<HTMLInputElement>("opt-meta");
const optPlaylist = $<HTMLInputElement>("opt-playlist");
const clipStart = $<HTMLInputElement>("clip-start");
const clipEnd = $<HTMLInputElement>("clip-end");
const clipClear = $<HTMLButtonElement>("clip-clear");
const formatList = $("format-list");
const outdirEl = $("outdir");
const pickFolderBtn = $<HTMLButtonElement>("pick-folder");
const downloadBtn = $<HTMLButtonElement>("download-btn");
const jobsEl = $("jobs");
const jobsEmpty = $("jobs-empty");
const clearDoneBtn = $<HTMLButtonElement>("clear-done");
const engineBadge = $("engine-badge");

/* ------------------------------------------------------------------ *
 * Formatting helpers
 * ------------------------------------------------------------------ */

function fmtDuration(seconds: number | null | undefined): string {
  if (!seconds || !Number.isFinite(seconds) || seconds <= 0) return "";
  const s = Math.floor(seconds % 60);
  const m = Math.floor((seconds / 60) % 60);
  const h = Math.floor(seconds / 3600);
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

/**
 * Parse a human time like "1:20", "00:01:20", "90s" or "45" into seconds.
 * Returns null for blank/unparseable input — a bad clip range should disable
 * the download, not silently download the whole video.
 */
function parseTime(raw: string): number | null {
  const text = raw.trim();
  if (!text) return null;

  const plain = Number(text);
  if (Number.isFinite(plain) && !text.includes(":")) return Math.max(0, plain);

  const parts = text.split(":").map((p) => p.trim());
  if (parts.length > 3 || parts.some((p) => p === "" || !/^\d+(\.\d+)?$/.test(p))) {
    return null;
  }
  return parts.reduce((acc, p) => acc * 60 + Number(p), 0);
}

function fmtBytes(n: number | null | undefined): string {
  if (!n || n <= 0) return "";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`;
}

function fmtSpeed(bytesPerSec: number | null | undefined): string {
  if (!bytesPerSec || bytesPerSec <= 0) return "";
  return `${fmtBytes(bytesPerSec)}/s`;
}

const STATE_LABEL: Record<JobState, string> = {
  queued: "Queued",
  resolving: "Resolving",
  downloading: "Downloading",
  postprocessing: "Muxing",
  transcoding: "Converting",
  done: "Done",
  failed: "Failed",
  cancelled: "Cancelled",
};

/* ------------------------------------------------------------------ *
 * Quality picker
 * ------------------------------------------------------------------ */

function renderQualityPicker() {
  qualityPicker.replaceChildren();
  for (const opt of QUALITY_OPTIONS) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = opt.label;
    btn.title = opt.note;
    btn.setAttribute("aria-pressed", String(opt.value === quality));
    btn.addEventListener("click", () => {
      quality = opt.value;
      for (const b of qualityPicker.querySelectorAll("button")) {
        b.setAttribute("aria-pressed", String(b === btn));
      }
      renderFormats();
    });
    qualityPicker.append(btn);
  }
}

/* ------------------------------------------------------------------ *
 * Preview
 * ------------------------------------------------------------------ */

function isCompatibleFormat(f: FormatInfo): boolean {
  const v = (f.vcodec || "").toLowerCase();
  const a = (f.acodec || "").toLowerCase();
  const videoOk = f.kind === "audio" || v.startsWith("avc1") || v.startsWith("h264");
  const audioOk = f.kind === "video" || a.startsWith("mp4a") || a.startsWith("aac");
  return videoOk && audioOk;
}

function renderFormats() {
  if (!currentProbe) return;

  // Show the formats that actually matter for the selected quality, best first.
  const cap = quality === "audio" ? null : Number(quality) || null;
  const relevant = currentProbe.formats.filter((f) => {
    if (quality === "audio") return f.kind === "audio";
    if (quality === "best") return true;
    if (f.kind === "audio") return false;
    // `height` is null for audio-only rows and for some muxed fallbacks.
    return cap === null || (f.height ?? 0) <= cap;
  });

  relevant.sort((a, b) => {
    if (quality === "audio") return (b.tbr ?? 0) - (a.tbr ?? 0);
    const byHeight = (b.height ?? 0) - (a.height ?? 0);
    if (byHeight !== 0) return byHeight;
    return (b.tbr ?? 0) - (a.tbr ?? 0);
  });

  const shown = relevant.slice(0, 40);
  formatList.replaceChildren();

  if (shown.length === 0) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = "No formats match this quality — the engine will fall back to the best available.";
    formatList.append(p);
    return;
  }

  for (const f of shown) {
    const row = document.createElement("div");
    row.className = "fmt";

    const id = document.createElement("span");
    id.className = "fmt-id";
    id.textContent = f.format_id;

    const kind = document.createElement("span");
    kind.className = `fmt-kind ${f.kind}`;
    kind.textContent = f.kind;

    const desc = document.createElement("span");
    const bits: string[] = [];
    if (f.kind !== "audio" && f.height) bits.push(`${f.height}p`);
    if (f.fps && f.fps > 30.5) bits.push(`${Math.round(f.fps)}fps`);
    if (f.vcodec && f.vcodec !== "none") bits.push(f.vcodec.split(".")[0]);
    if (f.acodec && f.acodec !== "none") bits.push(f.acodec.split(".")[0]);
    if (f.ext) bits.push(`.${f.ext}`);
    desc.textContent = bits.join(" · ");

    const size = document.createElement("span");
    size.textContent = fmtBytes(f.filesize);

    row.append(id, kind, desc, size);

    // The green tick tells the user which streams survive compatibility mode
    // untouched, which is the whole point of the H.264/AAC toggle.
    if (compatibleToggle.checked && isCompatibleFormat(f)) {
      const tag = document.createElement("span");
      tag.className = "fmt-tag-compat";
      tag.textContent = "✓";
      tag.title = "Already H.264/AAC — no re-encode needed";
      row.append(tag);
    }

    formatList.append(row);
  }
}

function showProbe(p: ProbeEvent) {
  currentProbe = p;

  titleEl.textContent = p.title;
  uploaderEl.textContent = [p.uploader, p.extractor].filter(Boolean).join(" · ");
  durationPill.textContent = fmtDuration(p.duration);

  if (p.thumbnail) {
    thumb.src = p.thumbnail;
    thumb.hidden = false;
    thumb.onerror = () => {
      // Remote thumbnails can 404 or be blocked by CSP; hide rather than show
      // a broken-image icon.
      thumb.hidden = true;
    };
  } else {
    thumb.removeAttribute("src");
    thumb.hidden = true;
  }

  warningsEl.replaceChildren();
  const notices = [...p.warnings];
  if (p.is_playlist && p.entry_count) {
    notices.push(`Playlist with ${p.entry_count} entries.`);
  }
  if (!p.formats.length) {
    notices.push("No format list reported — quality selection may be approximate.");
  }
  for (const w of notices) {
    const div = document.createElement("div");
    div.className = "alert alert-warn";
    div.textContent = w;
    warningsEl.append(div);
  }

  renderFormats();
  previewCard.hidden = false;
}

function showProbeError(message: string, hint?: string) {
  probeError.replaceChildren();
  const strong = document.createElement("strong");
  strong.textContent = message;
  probeError.append(strong);
  if (hint) {
    const span = document.createElement("span");
    span.className = "alert-hint";
    span.textContent = hint;
    probeError.append(span);
  }
  probeError.hidden = false;
}

/* ------------------------------------------------------------------ *
 * Probe flow
 * ------------------------------------------------------------------ */

async function doProbe() {
  const url = urlInput.value.trim();
  probeError.hidden = true;
  if (!url) {
    showProbeError("Paste a link first.");
    return;
  }

  probeBtn.disabled = true;
  const original = probeBtn.textContent;
  probeBtn.innerHTML = '<span class="spin"></span>Checking…';

  try {
    const probe = await bridge.probe(url);
    showProbe(probe);
    previewCard.scrollIntoView({ behavior: "smooth", block: "nearest" });
  } catch (err) {
    previewCard.hidden = true;
    currentProbe = null;
    showProbeError(
      err instanceof Error ? err.message : String(err),
      "Most failures are a private/region-locked video, a typo, or a stale yt-dlp."
    );
  } finally {
    probeBtn.disabled = false;
    probeBtn.textContent = original;
  }
}

/* ------------------------------------------------------------------ *
 * Job rows
 * ------------------------------------------------------------------ */

function createJobRow(id: string, url: string): UiJob {
  const el = document.createElement("div");
  el.className = "job queued";
  el.dataset.jobId = id;

  const title = document.createElement("div");
  title.className = "job-title";
  title.textContent = url;
  title.title = url;

  const state = document.createElement("div");
  state.className = "job-state";
  state.textContent = STATE_LABEL.queued;

  const bar = document.createElement("div");
  bar.className = "job-bar";
  const fill = document.createElement("div");
  fill.className = "job-bar-fill";
  bar.append(fill);

  const detail = document.createElement("div");
  detail.className = "job-detail";

  const actions = document.createElement("div");
  actions.className = "job-actions";

  const cancelBtn = document.createElement("button");
  cancelBtn.type = "button";
  cancelBtn.className = "btn btn-sm btn-danger";
  cancelBtn.textContent = "Cancel";
  cancelBtn.addEventListener("click", async () => {
    cancelBtn.disabled = true;
    try {
      await bridge.cancel(id);
    } catch {
      cancelBtn.disabled = false;
    }
  });
  actions.append(cancelBtn);

  el.append(title, state, bar, detail, actions);
  jobsEl.prepend(el);
  jobsEmpty.hidden = true;

  const job: UiJob = {
    id,
    url,
    title: null,
    state: "queued",
    percent: 0,
    speed: null,
    eta: null,
    path: null,
    size: null,
    error: null,
    errorCode: null,
    hint: null,
    stageDetail: null,
    el,
  };
  jobs.set(id, job);
  renderJob(job);
  return job;
}

function renderJob(job: UiJob) {
  const el = job.el;
  el.className = `job ${job.state}`;

  const titleEl = el.querySelector<HTMLElement>(".job-title");
  if (titleEl) {
    titleEl.textContent = job.title || job.url;
    titleEl.title = job.title || job.url;
  }

  const stateEl = el.querySelector<HTMLElement>(".job-state");
  if (stateEl) {
    stateEl.className = `job-state ${job.state}`;
    stateEl.textContent = STATE_LABEL[job.state] ?? job.state;
  }

  const fill = el.querySelector<HTMLElement>(".job-bar-fill");
  if (fill) fill.style.width = `${Math.max(0, Math.min(100, job.percent))}%`;

  const detail = el.querySelector<HTMLElement>(".job-detail");
  if (detail) {
    detail.replaceChildren();
    const bits: string[] = [];
    if (job.state === "downloading" || job.state === "transcoding") {
      if (job.percent > 0) bits.push(`${job.percent.toFixed(1)}%`);
      const speed = fmtSpeed(job.speed);
      if (speed) bits.push(speed);
      if (job.eta != null && job.eta >= 0) bits.push(`ETA ${fmtDuration(job.eta)}`);
    } else if (job.state === "done") {
      if (job.size) bits.push(fmtBytes(job.size));
      if (job.path) bits.push(job.path.split(/[\\/]/).pop() || "");
    }
    if (job.stageDetail && job.state !== "done") bits.push(job.stageDetail);

    for (const b of bits.filter(Boolean)) {
      const span = document.createElement("span");
      span.textContent = b;
      detail.append(span);
    }
  }

  const actions = el.querySelector<HTMLElement>(".job-actions");
  if (actions) {
    actions.replaceChildren();
    const isTerminal = job.state === "done" || job.state === "failed" || job.state === "cancelled";

    if (!isTerminal) {
      const cancelBtn = document.createElement("button");
      cancelBtn.type = "button";
      cancelBtn.className = "btn btn-sm btn-danger";
      cancelBtn.textContent = "Cancel";
      cancelBtn.addEventListener("click", async () => {
        cancelBtn.disabled = true;
        try {
          await bridge.cancel(job.id);
        } catch {
          cancelBtn.disabled = false;
        }
      });
      actions.append(cancelBtn);
    } else if (job.path) {
      const revealBtn = document.createElement("button");
      revealBtn.type = "button";
      revealBtn.className = "btn btn-sm";
      revealBtn.textContent = "Show file";
      revealBtn.addEventListener("click", () => {
        if (job.path) void bridge.reveal(job.path);
      });
      actions.append(revealBtn);
    }
  }

  // Errors get their own block: a code + message + actionable hint is far more
  // useful than a red progress bar.
  let errEl = el.querySelector<HTMLElement>(".job-error");
  if (job.state === "failed" && job.error) {
    if (!errEl) {
      errEl = document.createElement("div");
      errEl.className = "job-error";
      el.append(errEl);
    }
    errEl.replaceChildren();
    const msg = document.createElement("div");
    msg.textContent = job.errorCode ? `[${job.errorCode}] ${job.error}` : job.error;
    errEl.append(msg);
    if (job.hint) {
      const hint = document.createElement("div");
      hint.className = "job-hint";
      hint.textContent = job.hint;
      errEl.append(hint);
    }
  } else if (errEl) {
    errEl.remove();
  }
}

/* ------------------------------------------------------------------ *
 * Engine events
 * ------------------------------------------------------------------ */

function handleEvent(event: EngineEvent) {
  const jobId = event.job_id;
  // Probe events belong to the preview card, not the job list.
  if (!jobId || jobId.startsWith("probe-")) return;

  const job = jobs.get(jobId);
  if (!job) return;

  switch (event.event) {
    case "stage": {
      job.stageDetail = event.detail ?? null;
      if (event.title) job.title = event.title;
      const map: Record<string, JobState> = {
        resolving: "resolving",
        resolved: "resolving",
        plan: "resolving",
        downloading: "downloading",
        postprocessing: "postprocessing",
        muxing: "postprocessing",
        transcoding: "transcoding",
      };
      const next = map[event.stage];
      if (next && job.state !== "done" && job.state !== "failed" && job.state !== "cancelled") {
        job.state = next;
      }
      // `plan` explains why the bar will pause at 100% before muxing.
      if (event.stage === "plan" && event.detail) job.stageDetail = event.detail;
      break;
    }
    case "progress":
      job.percent = event.percent ?? job.percent;
      job.speed = event.speed ?? null;
      job.eta = event.eta ?? null;
      break;
    case "done":
      job.state = "done";
      job.percent = 100;
      job.path = event.path;
      job.size = event.size ?? null;
      job.stageDetail = null;
      break;
    case "error":
      if (job.state !== "cancelled") {
        job.state = "failed";
        job.error = event.message;
        job.errorCode = event.code;
        job.hint = event.hint ?? null;
      }
      break;
    case "cancelled":
      job.state = "cancelled";
      job.stageDetail = null;
      break;
    case "log":
    case "stderr":
      // Warnings (missing ffmpeg, VP9 transcode notice) are worth surfacing;
      // info logs would just clutter the row.
      if (event.level === "warning" || event.level === "warn" || event.level === "error") {
        job.stageDetail = event.message;
      }
      break;
  }

  renderJob(job);
}

/* ------------------------------------------------------------------ *
 * Download flow
 * ------------------------------------------------------------------ */

function readClip(): { start: number | null; end: number | null; error: string | null } {
  const rawStart = clipStart.value.trim();
  const rawEnd = clipEnd.value.trim();
  if (!rawStart && !rawEnd) return { start: null, end: null, error: null };

  const start = rawStart ? parseTime(rawStart) : 0;
  const end = rawEnd ? parseTime(rawEnd) : null;

  if (start === null) return { start: null, end: null, error: `Can't read start time "${rawStart}". Use 1:20 or 00:01:20.` };
  if (rawEnd && end === null) return { start: null, end: null, error: `Can't read end time "${rawEnd}". Use 2:05 or 00:02:05.` };
  if (end !== null && end <= start) return { start: null, end: null, error: "Clip end must be after clip start." };

  return { start, end, error: null };
}

async function doDownload() {
  if (!currentProbe) return;

  const clip = readClip();
  if (clip.error) {
    showProbeError(clip.error);
    previewCard.scrollIntoView({ behavior: "smooth", block: "nearest" });
    return;
  }
  probeError.hidden = true;

  const request: DownloadRequest = {
    url: currentProbe.url,
    outdir: outdir || ".",
    quality,
    compatible: compatibleToggle.checked,
    subs: optSubs.checked,
    thumbnail: optThumb.checked,
    metadata: optMeta.checked,
    clip_start: clip.start,
    clip_end: clip.end,
    playlist: optPlaylist.checked,
  };

  downloadBtn.disabled = true;
  try {
    const { jobId } = await bridge.startDownload(request);
    createJobRow(jobId, currentProbe.title || request.url);
    jobsEl.scrollIntoView({ behavior: "smooth", block: "nearest" });
  } catch (err) {
    showProbeError(err instanceof Error ? err.message : String(err));
  } finally {
    downloadBtn.disabled = false;
  }
}

/* ------------------------------------------------------------------ *
 * Engine status badge
 * ------------------------------------------------------------------ */

async function refreshEngineStatus() {
  engineBadge.className = "badge";
  engineBadge.textContent = "checking…";

  try {
    const status = await bridge.engineStatus();
    const report = status.report;

    if (!status.ok) {
      engineBadge.classList.add("err");
      engineBadge.textContent = "engine unavailable";
      engineBadge.title = status.error || status.stderr || "Engine failed to start";
      return;
    }

    const yt = report?.yt_dlp;
    const ff = report?.ffmpeg;
    const warns = report?.warnings ?? [];

    if (!yt) {
      engineBadge.classList.add("err");
      engineBadge.textContent = "yt-dlp missing";
      engineBadge.title = "Install with: pip install yt-dlp";
      return;
    }

    if (!ff || warns.length) {
      engineBadge.classList.add("warn");
      engineBadge.textContent = ff ? "ready (with warnings)" : "ready · no ffmpeg";
      engineBadge.title =
        warns.join("\n") ||
        "ffmpeg not found: HD downloads need it to merge video+audio, and compatibility mode needs it to transcode.";
      return;
    }

    engineBadge.classList.add("ok");
    engineBadge.textContent = `ready · yt-dlp ${yt}`;
    const version = (report?.ffmpeg_version || "").split(" ")[2] || "";
    engineBadge.title = `ffmpeg ${version}\n${report?.ffmpeg ?? ""}`;
  } catch (err) {
    engineBadge.classList.add("err");
    engineBadge.textContent = "engine error";
    engineBadge.title = err instanceof Error ? err.message : String(err);
  }
}

/* ------------------------------------------------------------------ *
 * Boot
 * ------------------------------------------------------------------ */

async function init() {
  bridge = await getBridge();

  renderQualityPicker();

  try {
    outdir = await bridge.defaultOutdir();
  } catch {
    outdir = ".";
  }
  outdirEl.textContent = outdir;
  outdirEl.title = outdir;

  void refreshEngineStatus();

  probeBtn.addEventListener("click", () => void doProbe());
  urlInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") void doProbe();
  });
  // Re-pasting a different link invalidates the preview.
  urlInput.addEventListener("input", () => {
    probeError.hidden = true;
  });

  downloadBtn.addEventListener("click", () => void doDownload());
  compatibleToggle.addEventListener("change", renderFormats);

  pickFolderBtn.addEventListener("click", async () => {
    pickFolderBtn.disabled = true;
    try {
      const picked = await bridge.pickFolder();
      if (picked) {
        outdir = picked;
        outdirEl.textContent = picked;
        outdirEl.title = picked;
      }
    } finally {
      pickFolderBtn.disabled = false;
    }
  });

  clipClear.addEventListener("click", () => {
    clipStart.value = "";
    clipEnd.value = "";
    probeError.hidden = true;
  });

  clearDoneBtn.addEventListener("click", () => {
    for (const [id, job] of [...jobs]) {
      if (job.state === "done" || job.state === "failed" || job.state === "cancelled") {
        job.el.remove();
        jobs.delete(id);
      }
    }
    jobsEmpty.hidden = jobs.size > 0;
  });

  bridge.onEvent(handleEvent);

  // Reconcile with the backend on load, so a reload mid-download doesn't
  // orphan jobs that are still running.
  try {
    const existing = await bridge.listJobs();
    for (const j of existing as Job[]) {
      if (jobs.has(j.id)) continue;
      const row = createJobRow(j.id, j.title || j.url);
      row.state = j.state;
      row.percent = j.percent;
      row.title = j.title;
      row.path = j.path;
      row.error = j.error;
      row.errorCode = j.error_code;
      row.hint = j.hint;
      renderJob(row);
    }
  } catch {
    // list_jobs is unavailable in some dev configurations; not fatal.
  }

  urlInput.focus();
}

void init();
