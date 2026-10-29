/**
 * Transport abstraction.
 *
 * The UI is written once against this interface. Under Tauri it uses IPC
 * (invoke + event listen). In a plain browser it falls back to HTTP + SSE
 * against devserver.py, which drives the *same* Python engine.
 *
 * That fallback is not a mock: it is the real engine over a different pipe, so
 * the UI can be developed and demoed without compiling Rust. Only the native
 * bits (folder picker, reveal-in-Finder) degrade, and they degrade to browser
 * equivalents.
 */

import type {
  DownloadRequest,
  EngineEvent,
  EngineStatus,
  Job,
  ProbeEvent,
  Transport,
} from "./types";

export interface Bridge {
  readonly transport: Transport;
  probe(url: string): Promise<ProbeEvent>;
  startDownload(req: DownloadRequest): Promise<{ jobId: string }>;
  cancel(jobId: string): Promise<boolean>;
  listJobs(): Promise<Job[]>;
  engineStatus(): Promise<EngineStatus>;
  pickFolder(): Promise<string | null>;
  reveal(path: string): Promise<void>;
  defaultOutdir(): Promise<string>;
  /** Subscribe to all engine events. Returns an unsubscribe function. */
  onEvent(cb: (event: EngineEvent) => void): () => void;
}

/* ------------------------------------------------------------------ *
 * Tauri transport
 * ------------------------------------------------------------------ */

async function tauriBridge(): Promise<Bridge> {
  // Loaded lazily so a browser build never tries to resolve the Tauri modules.
  const { invoke } = await import("@tauri-apps/api/core");
  const { listen } = await import("@tauri-apps/api/event");

  const listeners = new Set<(e: EngineEvent) => void>();
  let unlistenTauri: (() => void) | null = null;

  const ensureListener = async () => {
    if (unlistenTauri) return;
    unlistenTauri = await listen<EngineEvent>("engine-event", (e) => {
      for (const cb of listeners) cb(e.payload);
    });
  };
  await ensureListener();

  return {
    transport: "tauri",

    async probe(url) {
      return await invoke<ProbeEvent>("probe_url", { url });
    },

    async startDownload(req) {
      const res = await invoke<{ job_id: string }>("start_download", { request: req });
      return { jobId: res.job_id };
    },

    async cancel(jobId) {
      return await invoke<boolean>("cancel_job", { jobId });
    },

    async listJobs() {
      return await invoke<Job[]>("list_jobs");
    },

    async engineStatus() {
      return await invoke<EngineStatus>("engine_status");
    },

    async pickFolder() {
      return await invoke<string | null>("pick_folder");
    },

    async reveal(path) {
      await invoke<void>("reveal_path", { path });
    },

    async defaultOutdir() {
      return await invoke<string>("default_outdir");
    },

    onEvent(cb) {
      listeners.add(cb);
      return () => {
        listeners.delete(cb);
      };
    },
  };
}

/* ------------------------------------------------------------------ *
 * HTTP / SSE transport (dev preview)
 * ------------------------------------------------------------------ */

async function httpBridge(): Promise<Bridge> {
  const listeners = new Set<(e: EngineEvent) => void>();
  let source: EventSource | null = null;
  let retryTimer: number | undefined;

  const connect = () => {
    if (source) return;
    source = new EventSource("/api/events");

    source.onmessage = (msg) => {
      if (!msg.data) return;
      try {
        const parsed = JSON.parse(msg.data) as EngineEvent;
        for (const cb of listeners) cb(parsed);
      } catch {
        // A keepalive comment or malformed frame; ignore rather than kill the
        // stream — losing events silently is worse than dropping one.
      }
    };

    source.onerror = () => {
      // EventSource auto-reconnects, but if the server is gone it spins fast.
      // Close and back off so a restart doesn't hammer a dead endpoint.
      source?.close();
      source = null;
      if (retryTimer) window.clearTimeout(retryTimer);
      retryTimer = window.setTimeout(connect, 1500);
    };
  };
  connect();

  const post = async <T,>(path: string, body?: unknown): Promise<T> => {
    const res = await fetch(path, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    let data: unknown = null;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        data = text;
      }
    }
    if (!res.ok) {
      const message =
        (data as { error?: string } | null)?.error ?? `HTTP ${res.status}`;
      throw new Error(message);
    }
    return data as T;
  };

  const get = async <T,>(path: string): Promise<T> => {
    const res = await fetch(path);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return (await res.json()) as T;
  };

  // Defined once as a plain function rather than as a method: calling
  // `this.defaultOutdir()` from inside pickFolder fails to type-check, because
  // `this` in an object literal contextually typed by Promise<Bridge> does not
  // resolve to the object. Sharing the helper also avoids duplicating the call.
  const defaultOutdir = async (): Promise<string> => {
    const res = await get<{ outdir: string }>("/api/outdir");
    return res.outdir;
  };

  return {
    transport: "http",

    async probe(url) {
      const res = await post<{ probe: ProbeEvent }>("/api/probe", { url });
      return res.probe;
    },

    async startDownload(req) {
      return await post<{ jobId: string }>("/api/download", req);
    },

    async cancel(jobId) {
      const res = await post<{ cancelled: boolean }>("/api/cancel", { jobId });
      return res.cancelled;
    },

    async listJobs() {
      return await get<Job[]>("/api/jobs");
    },

    async engineStatus() {
      return await get<EngineStatus>("/api/status");
    },

    async pickFolder() {
      // No native dialog in a browser. Prompt is honest about the limitation
      // and still lets you point the engine at a real path on the server.
      const current = await defaultOutdir();
      return window.prompt("Save downloads to (server-side path):", current);
    },

    async reveal(path) {
      // Best effort in the browser: open the file through the dev server.
      window.open(`/api/open?path=${encodeURIComponent(path)}`, "_blank");
    },

    defaultOutdir,

    onEvent(cb) {
      listeners.add(cb);
      return () => {
        listeners.delete(cb);
      };
    },
  };
}

/* ------------------------------------------------------------------ *
 * Selection
 * ------------------------------------------------------------------ */

/** Tauri injects this into the webview; a browser never has it. */
function detectTauri(): boolean {
  return (
    typeof window !== "undefined" &&
    ("__TAURI_INTERNALS__" in window || "__TAURI__" in window)
  );
}

let cached: Promise<Bridge> | null = null;

export function getBridge(): Promise<Bridge> {
  if (!cached) {
    cached = detectTauri() ? tauriBridge() : httpBridge();
  }
  return cached;
}
