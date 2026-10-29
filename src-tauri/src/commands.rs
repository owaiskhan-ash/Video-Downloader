//! Commands the webview can invoke.
//!
//! Everything here is thin: validate, launch the engine, relay events. No video
//! logic lives in Rust.

use crate::engine::{self, EVENT};
use crate::jobs::{Job, SharedRegistry};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::oneshot;
use uuid::Uuid;

/// What the UI sends to start a download. Mirrors engine.protocol.JobSpec.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadRequest {
    pub url: String,
    pub outdir: String,
    #[serde(default = "default_quality")]
    pub quality: String,
    #[serde(default = "default_true")]
    pub compatible: bool,
    #[serde(default)]
    pub subs: bool,
    #[serde(default)]
    pub thumbnail: bool,
    #[serde(default = "default_true")]
    pub metadata: bool,
    #[serde(default)]
    pub clip_start: Option<f64>,
    #[serde(default)]
    pub clip_end: Option<f64>,
    #[serde(default)]
    pub playlist: bool,
}

fn default_quality() -> String {
    "1080".into()
}
fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JobStarted {
    pub job_id: String,
}

#[derive(Debug, thiserror::Error)]
pub enum CmdError {
    #[error("{0}")]
    Engine(String),
    #[error("{0}")]
    BadInput(String),
}

// Tauri needs errors to serialise to something the frontend can read.
impl Serialize for CmdError {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.to_string())
    }
}

/// Resolve a URL to metadata + available formats WITHOUT downloading.
///
/// Runs the engine to completion and returns the single `probe` event. This is
/// what powers the preview card (thumbnail, title, duration, format list) the
/// user sees before committing to a download.
#[tauri::command]
pub async fn probe_url(
    app: AppHandle,
    url: String,
) -> Result<Value, CmdError> {
    let trimmed = url.trim().to_string();
    if trimmed.is_empty() {
        return Err(CmdError::BadInput("URL is empty".into()));
    }

    let launch = engine::resolve(&app).map_err(|e| CmdError::Engine(e.to_string()))?;
    let job_id = format!("probe-{}", Uuid::new_v4());

    let (child, mut exited) = engine::spawn(
        &app,
        &launch,
        &["probe".into(), trimmed.clone(), "--job-id".into(), job_id.clone()],
        None,
        job_id.clone(),
    )
    .await
    .map_err(|e| CmdError::Engine(e.to_string()))?;

    // Collect events for this probe until the process exits.
    let (tx, rx) = oneshot::channel::<Option<Value>>();
    let tx = std::sync::Mutex::new(Some(tx));
    let probe_job = job_id.clone();

    let unlisten = app.listen(EVENT, move |event| {
        let Ok(v) = serde_json::from_str::<Value>(event.payload()) else {
            return;
        };
        if v.get("job_id").and_then(|j| j.as_str()) != Some(probe_job.as_str()) {
            return;
        }
        let kind = v.get("event").and_then(|e| e.as_str()).unwrap_or("");
        if kind == "probe" || kind == "error" {
            if let Ok(mut guard) = tx.lock() {
                if let Some(tx) = guard.take() {
                    let _ = tx.send(Some(v));
                }
            }
        }
    });

    // Guard against a hung extractor: don't block the UI forever.
    let result = tokio::select! {
        r = rx => r.unwrap_or(None),
        _ = exited.changed() => None,
        _ = tokio::time::sleep(std::time::Duration::from_secs(60)) => None,
    };

    unlisten();
    let _ = child.start_kill();

    match result {
        Some(v) if v.get("event").and_then(|e| e.as_str()) == Some("error") => Err(
            CmdError::Engine(
                v.get("message")
                    .and_then(|m| m.as_str())
                    .unwrap_or("Could not resolve that URL")
                    .to_string(),
            ),
        ),
        Some(v) => Ok(v),
        None => Err(CmdError::Engine(
            "Engine returned no result (timed out or crashed)".into(),
        )),
    }
}

/// Queue a download. Returns immediately with a job id; progress arrives as
/// `engine-event` emissions.
#[tauri::command]
pub async fn start_download(
    app: AppHandle,
    registry: State<'_, SharedRegistry>,
    request: DownloadRequest,
) -> Result<JobStarted, CmdError> {
    let url = request.url.trim().to_string();
    if url.is_empty() {
        return Err(CmdError::BadInput("URL is empty".into()));
    }
    if request.outdir.trim().is_empty() {
        return Err(CmdError::BadInput("No output folder selected".into()));
    }

    // Reject a clip range that can't work before spawning anything.
    if let (Some(s), Some(e)) = (request.clip_start, request.clip_end) {
        if e <= s {
            return Err(CmdError::BadInput(
                "Clip end must be after clip start".into(),
            ));
        }
    }

    let launch = engine::resolve(&app).map_err(|e| CmdError::Engine(e.to_string()))?;
    let job_id = Uuid::new_v4().to_string();

    // The engine reads its spec from stdin as JSON. Building it here (rather
    // than passing 10 CLI flags) keeps the interface stable as options grow.
    let spec = json!({
        "job_id": job_id,
        "url": url,
        "outdir": request.outdir,
        "quality": request.quality,
        "compatible": request.compatible,
        "subs": request.subs,
        "thumbnail": request.thumbnail,
        "metadata": request.metadata,
        "clip_start": request.clip_start,
        "clip_end": request.clip_end,
        "playlist": request.playlist,
    });

    registry.new_job(&job_id, &url, &request.outdir).await;

    let (child, mut exited) = engine::spawn(
        &app,
        &launch,
        &["download".into(), "-".into()],
        Some(spec.to_string()),
        job_id.clone(),
    )
    .await
    .map_err(|e| {
        let message = e.to_string();
        // Don't leave a phantom "queued" row if we never launched.
        let reg = registry.inner().clone();
        let id = job_id.clone();
        tokio::spawn(async move {
            reg.mark_failed(&id, "spawn_failed", &message, "").await;
        });
        CmdError::Engine(message)
    })?;

    registry.track_child(&job_id, child).await;

    // Mirror engine events into the registry so list_jobs stays truthful even
    // if the webview reloads mid-download.
    let reg = registry.inner().clone();
    let app_for_listener = app.clone();
    let listener_job = job_id.clone();

    let unlisten = app.listen(EVENT, move |event| {
        let Ok(v) = serde_json::from_str::<Value>(event.payload()) else {
            return;
        };
        if v.get("job_id").and_then(|j| j.as_str()) != Some(listener_job.as_str()) {
            return;
        }
        let reg = reg.clone();
        let v = v.clone();
        let id = listener_job.clone();
        // apply_event is async; the listener callback is not.
        tokio::spawn(async move { engine::apply_event(&reg, &id, &v).await });
    });

    // Reap: when the process exits, stop tracking and unlisten.
    let reg_reap = registry.inner().clone();
    let reap_id = job_id.clone();
    tokio::spawn(async move {
        let _ = exited.changed().await;
        reg_reap.forget_child(&reap_id).await;
        // If the engine died without emitting done/error, say so. The reader
        // task may have already handled it; this is the belt-and-braces path.
        let jobs = reg_reap.snapshot().await;
        if let Some(job) = jobs.iter().find(|j| j.id == reap_id) {
            if !job.state.is_terminal() {
                reg_reap
                    .mark_failed(
                        &reap_id,
                        "engine_crashed",
                        "Engine exited before the download completed",
                        "Check that Python and yt-dlp are installed.",
                    )
                    .await;
                let _ = app_for_listener.emit(
                    EVENT,
                    json!({
                        "v": 1,
                        "event": "error",
                        "job_id": reap_id,
                        "code": "engine_crashed",
                        "message": "Engine exited before the download completed",
                    }),
                );
            }
        }
        unlisten();
    });

    Ok(JobStarted { job_id })
}

/// Cancel a running download.
///
/// Partial `.part` files are intentionally left in place: yt-dlp resumes them,
/// so a cancelled 80% download continues from 80% rather than restarting.
#[tauri::command]
pub async fn cancel_job(
    registry: State<'_, SharedRegistry>,
    job_id: String,
) -> Result<bool, CmdError> {
    Ok(registry.kill(&job_id).await)
}

/// Snapshot of every job this session, for the history list.
#[tauri::command]
pub async fn list_jobs(registry: State<'_, SharedRegistry>) -> Result<Vec<Job>, CmdError> {
    Ok(registry.snapshot().await)
}

/// Dependency check run at launch: yt-dlp version, ffmpeg path, warnings.
///
/// Surfacing a missing ffmpeg here (rather than at 100% of a download) is the
/// difference between "HD unavailable" and a confused user.
#[tauri::command]
pub async fn engine_status(app: AppHandle) -> Result<Value, CmdError> {
    let launch = match engine::resolve(&app) {
        Ok(l) => l,
        Err(e) => {
            return Ok(json!({
                "ok": false,
                "error": e.to_string(),
                "isDev": false,
            }))
        }
    };

    let output = tokio::process::Command::new(&launch.program)
        .args({
            let mut a = launch.args.clone();
            a.push("doctor".into());
            a
        })
        .current_dir(launch.cwd.clone().unwrap_or_else(|| PathBuf::from(".")))
        .output()
        .await
        .map_err(|e| CmdError::Engine(format!("could not run engine: {e}")))?;

    let stdout = String::from_utf8_lossy(&output.stdout);

    // doctor prints one log line then the JSON report; take the last JSON blob.
    let report = stdout
        .lines()
        .rev()
        .find_map(|line| serde_json::from_str::<Value>(line.trim()).ok());

    Ok(json!({
        "ok": output.status.success(),
        "isDev": launch.is_dev,
        "program": launch.program.display().to_string(),
        "report": report,
        "stderr": String::from_utf8_lossy(&output.stderr),
    }))
}

/// Native folder picker for the download destination.
#[tauri::command]
pub async fn pick_folder(app: AppHandle) -> Result<Option<String>, CmdError> {
    use tauri_plugin_dialog::DialogExt;

    let (tx, rx) = oneshot::channel::<Option<PathBuf>>();
    let tx = std::sync::Mutex::new(Some(tx));

    app.dialog()
        .file()
        .set_title("Choose where to save downloads")
        .pick_folder(move |picked| {
            if let Ok(mut guard) = tx.lock() {
                if let Some(tx) = guard.take() {
                    // and_then, not map(...).flatten(): same result, but the
                    // latter trips clippy's map_flatten lint.
                    let _ = tx.send(picked.and_then(|p| p.into_path().ok()));
                }
            }
        });

    // A folder dialog has no timeout — the user may leave it open. Await it
    // rather than blocking a thread.
    //
    // The channel carries Option<PathBuf> but the command is declared to return
    // Option<String>, so convert explicitly — without this the function does not
    // type-check, and Tauri commands must return something serialisable.
    let picked = rx
        .await
        .map_err(|_| CmdError::Engine("dialog closed unexpectedly".into()))?;

    Ok(picked.map(|p| p.display().to_string()))
}

/// Reveal a finished file in Finder / Explorer / Files.
#[tauri::command]
pub async fn reveal_path(app: AppHandle, path: String) -> Result<(), CmdError> {
    use tauri_plugin_opener::OpenerExt;

    let p = PathBuf::from(&path);
    if !p.exists() {
        return Err(CmdError::BadInput(format!("No such file: {path}")));
    }

    app.opener()
        .reveal_item_in_dir(p)
        .map_err(|e| CmdError::Engine(format!("could not open folder: {e}")))
}

/// Default download folder, so the UI has somewhere sane before the user picks.
#[tauri::command]
pub async fn default_outdir(app: AppHandle) -> Result<String, CmdError> {
    let dirs = [
        app.path().download_dir(),
        app.path().video_dir(),
        app.path().document_dir(),
        app.path().home_dir(),
    ];
    for d in dirs.into_iter().flatten() {
        if d.is_dir() {
            return Ok(d.display().to_string());
        }
    }
    Ok(".".into())
}

#[tauri::command]
pub fn app_version(app: AppHandle) -> Result<String, CmdError> {
    Ok(app.package_info().version.to_string())
}
