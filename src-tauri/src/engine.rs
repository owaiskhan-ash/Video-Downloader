//! Spawning the engine and relaying its JSON-line protocol into Tauri events.
//!
//! The engine writes newline-delimited JSON to stdout (see engine/protocol.py).
//! Rust never parses video sites — it manages processes and forwards events.
//! That keeps the brittle, fast-moving part (extractors) in Python where
//! yt-dlp lives, and the UI decoupled from it.

use crate::jobs::{JobState, SharedRegistry};
use serde_json::Value;
use std::path::PathBuf;
use std::process::Stdio;
use tauri::{AppHandle, Emitter, Manager};
use thiserror::Error;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Child;
use tokio::sync::watch;

#[derive(Debug, Error)]
pub enum EngineError {
    #[error("engine not found (looked for: {0})")]
    NotFound(String),
    #[error("failed to spawn engine: {0}")]
    Spawn(String),
}

/// Fully-resolved launch description.
#[derive(Debug, Clone)]
pub struct EngineLaunch {
    pub program: PathBuf,
    pub args: Vec<String>,
    /// Working directory. Matters in dev: `python -m engine` only resolves the
    /// package when cwd is the repo root.
    pub cwd: Option<PathBuf>,
    pub is_dev: bool,
}

/// Emitted for every engine event, plus synthesised ones from this module.
pub const EVENT: &str = "engine-event";

/// Resolve how to launch the engine.
///
/// Order:
///   1. `$VD_ENGINE_CMD` (+ `$VD_ENGINE_ARGS`, unit-separator delimited)
///   2. `$VD_PYTHON` with the repo's `engine/` package (dev, no sidecar build)
///   3. packaged sidecar next to the executable
///
/// The dev paths exist because compiling a PyInstaller sidecar on every run
/// makes the inner loop miserable, and because you can't build a Linux sidecar
/// on a Windows host anyway.
pub fn resolve(app: &AppHandle) -> Result<EngineLaunch, EngineError> {
    if let Ok(prog) = std::env::var("VD_ENGINE_CMD") {
        let args: Vec<String> = std::env::var("VD_ENGINE_ARGS")
            .unwrap_or_default()
            .split('\u{1f}') // unit separator: args may contain spaces
            .filter(|s| !s.is_empty())
            .map(String::from)
            .collect();
        return Ok(EngineLaunch {
            program: PathBuf::from(prog),
            args,
            cwd: std::env::var("VD_CWD").ok().map(PathBuf::from),
            is_dev: true,
        });
    }

    if let Ok(py) = std::env::var("VD_PYTHON") {
        if let Some(root) = repo_root() {
            return Ok(EngineLaunch {
                program: PathBuf::from(py),
                args: vec!["-m".into(), "engine".into()],
                cwd: Some(root),
                is_dev: true,
            });
        }
    }

    let tried = sidecar_candidates();
    for cand in &tried {
        if cand.is_file() {
            return Ok(EngineLaunch {
                program: cand.clone(),
                args: vec![],
                cwd: app.path().resource_dir().ok(),
                is_dev: false,
            });
        }
    }

    Err(EngineError::NotFound(
        tried
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
            .join(", "),
    ))
}

/// Where a packaged build puts the PyInstaller sidecar.
///
/// Tauri's `externalBin` stores sources as `binaries/engine-<target-triple>`
/// and bundles them beside the executable. Different versions/platforms leave
/// the triple in the filename or strip it, so probe both.
fn sidecar_candidates() -> Vec<PathBuf> {
    let base_name = if cfg!(windows) { "engine.exe" } else { "engine" };
    let triple = target_triple();

    let mut roots: Vec<PathBuf> = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            roots.push(dir.to_path_buf());
        }
    }
    if let Some(dir) = std::env::var("VD_SIDECAR_DIR").ok().map(PathBuf::from) {
        roots.push(dir);
    }

    let mut out = Vec::new();
    for root in roots {
        out.push(root.join(base_name.clone()));
        out.push(root.join(format!("engine-{triple}")));
        out.push(root.join("binaries").join(base_name.clone()));
        out.push(root.join("binaries").join(format!("engine-{triple}")));
    }
    out
}

pub fn target_triple() -> String {
    // Matches Rust's own triple for the host, which is what Tauri expects.
    if cfg!(all(target_os = "linux", target_arch = "x86_64")) {
        "x86_64-unknown-linux-gnu".into()
    } else if cfg!(all(target_os = "linux", target_arch = "aarch64")) {
        "aarch64-unknown-linux-gnu".into()
    } else if cfg!(all(target_os = "macos", target_arch = "x86_64")) {
        "x86_64-apple-darwin".into()
    } else if cfg!(all(target_os = "macos", target_arch = "aarch64")) {
        "aarch64-apple-darwin".into()
    } else if cfg!(all(target_os = "windows", target_arch = "x86_64")) {
        "x86_64-pc-windows-msvc.exe".into()
    } else {
        format!("{}-{}", std::env::consts::OS, std::env::consts::ARCH)
    }
}

fn repo_root() -> Option<PathBuf> {
    if let Ok(manifest) = std::env::var("CARGO_MANIFEST_DIR") {
        return PathBuf::from(manifest).parent().map(|p| p.to_path_buf());
    }
    // target/debug/app -> repo root is three levels up.
    std::env::current_exe().ok()?.ancestors().nth(3).map(|p| p.to_path_buf())
}

/// Launch the engine and stream its stdout as Tauri events.
///
/// Returns the child (so the caller can kill it) and a receiver that flips to
/// `true` when the process exits.
///
/// `child.stdout.take()` is the key move: the read half goes to the relay task
/// while the Child itself goes back to the registry for cancellation. Without
/// that split you cannot both stream output and kill on demand.
pub async fn spawn(
    app: &AppHandle,
    launch: &EngineLaunch,
    args: &[String],
    stdin_payload: Option<String>,
    job_id: String,
) -> Result<(Child, watch::Receiver<bool>), EngineError> {
    let mut cmd = tokio::process::Command::new(&launch.program);

    let mut full_args = launch.args.clone();
    full_args.extend(args.iter().cloned());
    cmd.args(&full_args);

    if let Some(cwd) = &launch.cwd {
        cmd.current_dir(cwd);
    }

    cmd.stdin(if stdin_payload.is_some() {
        Stdio::piped()
    } else {
        Stdio::null()
    })
    .stdout(Stdio::piped())
    .stderr(Stdio::piped())
    // Detach from the parent's console so a packaged GUI app doesn't pop one.
    .kill_on_drop(true);

    let mut child = cmd.spawn().map_err(|e| EngineError::Spawn(format!("{e}")))?;

    if let Some(payload) = stdin_payload {
        if let Some(mut stdin) = child.stdin.take() {
            // Spawn the write so a full pipe can't deadlock the reader task.
            tokio::spawn(async move {
                let _ = stdin.write_all(payload.as_bytes()).await;
                // Closing stdin is what makes the engine's read() return.
                drop(stdin);
            });
        }
    }

    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| EngineError::Spawn("could not capture stdout".into()))?;
    let stderr = child.stderr.take();

    let (exited_tx, exited_rx) = watch::channel(false);

    let app_relay = app.clone();
    let relay_job_id = job_id.clone();
    tokio::spawn(relay_stdout(app_relay, stdout, relay_job_id, exited_tx));

    if let Some(stderr) = stderr {
        let app_err = app.clone();
        let err_job_id = job_id;
        tokio::spawn(relay_stderr(app_err, stderr, err_job_id));
    }

    Ok((child, exited_rx))
}

/// Forward each JSON line on stdout as an `engine-event`.
async fn relay_stdout(
    app: AppHandle,
    stdout: tokio::process::ChildStdout,
    job_id: String,
    exited_tx: watch::Sender<bool>,
) {
    let mut reader = BufReader::new(stdout).lines();

    while let Ok(Some(line)) = reader.next_line().await {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        match serde_json::from_str::<Value>(trimmed) {
            Ok(mut v) => {
                // Stamp the job id when the engine omitted it, so the UI can
                // always route the event to the right row.
                if v.get("job_id").and_then(|j| j.as_str()).unwrap_or("").is_empty() {
                    if let Some(obj) = v.as_object_mut() {
                        obj.insert("job_id".into(), Value::String(job_id.clone()));
                    }
                }
                let _ = app.emit(EVENT, v);
            }
            Err(_) => {
                // Non-JSON stdout means something wrote outside the protocol (a
                // stray print, a dependency warning). Surface it rather than
                // dropping it — it is usually the clue when things go wrong.
                let _ = app.emit(
                    EVENT,
                    serde_json::json!({
                        "v": 1,
                        "event": "log",
                        "job_id": job_id,
                        "level": "warn",
                        "message": trimmed,
                    }),
                );
            }
        }
    }

    let _ = exited_tx.send(true);
}

/// Collect stderr for crash diagnostics.
async fn relay_stderr(app: AppHandle, stderr: tokio::process::ChildStderr, job_id: String) {
    let mut reader = BufReader::new(stderr).lines();
    let mut tail: Vec<String> = Vec::new();

    while let Ok(Some(line)) = reader.next_line().await {
        let trimmed = line.trim().to_string();
        if trimmed.is_empty() {
            continue;
        }
        tail.push(trimmed);
        if tail.len() > 5 {
            tail.remove(0);
        }
    }

    if !tail.is_empty() {
        // The engine reports its own failures on stdout as structured `error`
        // events. stderr is for the cases it could not report: interpreter
        // crashes, missing modules, OOM kills.
        let _ = app.emit(
            EVENT,
            serde_json::json!({
                "v": 1,
                "event": "stderr",
                "job_id": job_id,
                "level": "error",
                "message": tail.join("\n"),
            }),
        );
    }
}

/// Map an engine `stage` onto a job state, ignoring unknown stages.
pub fn state_for_stage(stage: &str) -> Option<JobState> {
    match stage {
        "resolving" | "resolved" | "plan" => Some(JobState::Resolving),
        "downloading" => Some(JobState::Downloading),
        "postprocessing" | "muxing" => Some(JobState::Postprocessing),
        "transcoding" => Some(JobState::Transcoding),
        _ => None,
    }
}

/// Keep the registry in sync from engine events, so `list_jobs` is accurate
/// even when the UI is not listening (or reloads mid-download).
pub async fn apply_event(registry: &SharedRegistry, job_id: &str, event: &Value) {
    if job_id.is_empty() {
        return;
    }
    match event.get("event").and_then(|v| v.as_str()) {
        Some("stage") => {
            if let Some(stage) = event.get("stage").and_then(|v| v.as_str()) {
                if let Some(state) = state_for_stage(stage) {
                    registry.set_state(job_id, state).await;
                }
            }
            // `resolved` carries the title; grab it for the history list.
            if let Some(title) = event.get("title").and_then(|v| v.as_str()) {
                registry.set_title(job_id, title).await;
            }
        }
        Some("progress") => {
            if let Some(pct) = event.get("percent").and_then(|v| v.as_f64()) {
                registry.set_percent(job_id, pct).await;
            }
        }
        Some("done") => {
            if let Some(path) = event.get("path").and_then(|v| v.as_str()) {
                registry.finish(job_id, path).await;
            }
        }
        Some("error") => {
            registry
                .mark_failed(
                    job_id,
                    event.get("code").and_then(|v| v.as_str()).unwrap_or("unknown"),
                    event
                        .get("message")
                        .and_then(|v| v.as_str())
                        .unwrap_or("Download failed"),
                    event.get("hint").and_then(|v| v.as_str()).unwrap_or(""),
                )
                .await;
        }
        Some("cancelled") => registry.mark_cancelled(job_id).await,
        _ => {}
    }
}
