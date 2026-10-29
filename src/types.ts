/**
 * Typed mirror of the engine's event protocol (engine/protocol.py).
 *
 * These two files must stay in sync by hand. The protocol version field is the
 * guard: if the engine bumps `v` and the UI doesn't know it, we surface that
 * rather than silently mis-rendering events.
 */

export const PROTOCOL_VERSION = 1;

export type Quality = "best" | "2160" | "1080" | "720" | "480" | "audio";

export const QUALITY_OPTIONS: { value: Quality; label: string; note: string }[] = [
  { value: "best", label: "Best", note: "Highest the site offers" },
  { value: "2160", label: "4K", note: "Up to 2160p" },
  { value: "1080", label: "1080p", note: "Full HD" },
  { value: "720", label: "720p", note: "HD" },
  { value: "480", label: "480p", note: "Smaller file" },
  { value: "audio", label: "Audio", note: "Sound only → .m4a" },
];

export interface FormatInfo {
  format_id: string;
  kind: "video" | "audio" | "muxed";
  height: number | null;
  fps: number | null;
  vcodec: string | null;
  acodec: string | null;
  ext: string | null;
  filesize: number | null;
  tbr: number | null;
}

/** Event: `probe` — metadata + formats, nothing downloaded yet. */
export interface ProbeEvent {
  v: number;
  event: "probe";
  job_id: string;
  ts: number;
  url: string;
  title: string;
  uploader: string | null;
  duration: number | null;
  extractor: string | null;
  thumbnail: string | null;
  is_live: boolean;
  is_playlist: boolean;
  entry_count: number | null;
  formats: FormatInfo[];
  warnings: string[];
}

/** Event: `progress` — a chunk of one stream landing on disk. */
export interface ProgressEvent {
  v: number;
  event: "progress";
  job_id: string;
  ts: number;
  stage: Stage;
  percent: number;
  downloaded?: number | null;
  total?: number | null;
  speed?: number | null;
  eta?: number | null;
  filename?: string;
}

export type Stage =
  | "resolving"
  | "resolved"
  | "plan"
  | "downloading"
  | "postprocessing"
  | "muxing"
  | "transcoding";

/** Event: `stage` — coarse phase changes, used for the status line. */
export interface StageEvent {
  v: number;
  event: "stage";
  job_id: string;
  ts: number;
  stage: Stage;
  detail?: string;
  title?: string;
  duration?: number | null;
  requested_formats?: string[];
}

export interface LogEvent {
  v: number;
  event: "log" | "stderr";
  job_id: string;
  ts: number;
  level: "info" | "warning" | "warn" | "error";
  message: string;
}

export interface DoneEvent {
  v: number;
  event: "done";
  job_id: string;
  ts: number;
  path: string;
  filename: string;
  size: number;
}

/**
 * Event: `error` — always paired with a machine-readable `code` so the UI can
 * offer the right remedy instead of dumping a traceback.
 */
export interface ErrorEvent {
  v: number;
  event: "error";
  job_id: string;
  ts: number;
  code: string;
  message: string;
  hint?: string;
}

export interface CancelledEvent {
  v: number;
  event: "cancelled";
  job_id: string;
  ts: number;
  reason: string;
}

export interface DoctorEvent {
  v: number;
  event: "doctor";
  yt_dlp: string | null;
  ffmpeg: string | null;
  ffmpeg_version?: string | null;
  warnings: string[];
}

export type EngineEvent =
  | ProbeEvent
  | ProgressEvent
  | StageEvent
  | LogEvent
  | DoneEvent
  | ErrorEvent
  | CancelledEvent;

/** Mirrors jobs.JobState in Rust. */
export type JobState =
  | "queued"
  | "resolving"
  | "downloading"
  | "postprocessing"
  | "transcoding"
  | "done"
  | "failed"
  | "cancelled";

export interface Job {
  id: string;
  url: string;
  title: string | null;
  outdir: string;
  state: JobState;
  percent: number;
  path: string | null;
  error: string | null;
  error_code: string | null;
  hint: string | null;
}

/** What the UI sends to start a download. Mirrors commands.DownloadRequest. */
export interface DownloadRequest {
  url: string;
  outdir: string;
  quality: Quality;
  compatible: boolean;
  subs: boolean;
  thumbnail: boolean;
  metadata: boolean;
  clip_start: number | null;
  clip_end: number | null;
  playlist: boolean;
}

export interface EngineStatus {
  ok: boolean;
  isDev: boolean;
  program?: string;
  error?: string;
  report?: DoctorEvent | null;
  stderr?: string;
}

/** Which transport the UI is talking to. See bridge.ts. */
export type Transport = "tauri" | "http";
