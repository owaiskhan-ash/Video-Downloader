//! Tauri shell around the Python download engine.
//!
//! ```text
//!   [ webview UI ] --invoke--> [ commands ] --spawn--> [ engine (yt-dlp) ]
//!          ^                                                |
//!          |                                                |
//!          +----------- emit("engine-event") <-- stdout ----+
//! ```
//!
//! The engine is a separate process writing newline-delimited JSON to stdout
//! (see engine/protocol.py). Rust never parses video sites — it manages
//! processes and forwards events. That keeps the brittle, fast-moving part
//! (extractors) in Python where yt-dlp lives, and the UI decoupled from it.

mod commands;
mod engine;
mod jobs;

use jobs::JobRegistry;

pub fn run() {
    tauri::Builder::default()
        // No tauri-plugin-shell: the engine is spawned with tokio::process so
        // dev (`python -m engine`) and the packaged sidecar share one code path.
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_store::Builder::default().build())
        // NOTE: manage the *shared* (Arc) registry. commands.rs takes
        // State<'_, SharedRegistry>, so managing a bare JobRegistry would fail
        // to resolve at runtime with "state not found".
        .manage(JobRegistry::shared())
        .invoke_handler(tauri::generate_handler![
            commands::probe_url,
            commands::start_download,
            commands::cancel_job,
            commands::list_jobs,
            commands::engine_status,
            commands::pick_folder,
            commands::reveal_path,
            commands::default_outdir,
            commands::app_version,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
