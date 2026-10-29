use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::process::Child;
use tokio::sync::RwLock;

/// A download the user has started in this session.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum JobState {
    Queued,
    Resolving,
    Downloading,
    Postprocessing,
    Transcoding,
    Done,
    Failed,
    Cancelled,
}

impl JobState {
    /// Terminal states must not be overwritten by a late-arriving event.
    /// Without this, a stray `progress` line after cancellation flips the row
    /// back to "downloading" and the UI shows a job that can never finish.
    pub fn is_terminal(&self) -> bool {
        matches!(self, JobState::Done | JobState::Failed | JobState::Cancelled)
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Job {
    pub id: String,
    pub url: String,
    pub title: Option<String>,
    pub outdir: String,
    pub state: JobState,
    pub percent: f64,
    pub path: Option<String>,
    pub error: Option<String>,
    pub error_code: Option<String>,
    pub hint: Option<String>,
}

/// Shared job state.
///
/// `Arc<RwLock<_>>` rather than a plain `Mutex`: the stdout reader task holds
/// this for the whole life of a download while the UI concurrently calls
/// `cancel_job` and `list_jobs`. Holding a std Mutex across an `.await` would
/// risk blocking the runtime; RwLock also lets UI polling reads overlap.
#[derive(Default)]
pub struct JobRegistry {
    jobs: RwLock<HashMap<String, Job>>,
    running: RwLock<HashMap<String, Child>>,
}

pub type SharedRegistry = Arc<JobRegistry>;

impl JobRegistry {
    pub fn shared() -> SharedRegistry {
        Arc::new(Self::default())
    }

    pub async fn new_job(&self, id: &str, url: &str, outdir: &str) -> Job {
        let job = Job {
            id: id.to_string(),
            url: url.to_string(),
            title: None,
            outdir: outdir.to_string(),
            state: JobState::Queued,
            percent: 0.0,
            path: None,
            error: None,
            error_code: None,
            hint: None,
        };
        self.jobs.write().await.insert(id.to_string(), job.clone());
        job
    }

    pub async fn set_state(&self, id: &str, state: JobState) {
        let mut jobs = self.jobs.write().await;
        if let Some(j) = jobs.get_mut(id) {
            if !j.state.is_terminal() {
                j.state = state;
            }
        }
    }

    pub async fn set_percent(&self, id: &str, percent: f64) {
        let mut jobs = self.jobs.write().await;
        if let Some(j) = jobs.get_mut(id) {
            if !j.state.is_terminal() {
                j.percent = percent.clamp(0.0, 100.0);
            }
        }
    }

    pub async fn set_title(&self, id: &str, title: &str) {
        if let Some(j) = self.jobs.write().await.get_mut(id) {
            j.title = Some(title.to_string());
        }
    }

    pub async fn finish(&self, id: &str, path: &str) {
        if let Some(j) = self.jobs.write().await.get_mut(id) {
            j.state = JobState::Done;
            j.percent = 100.0;
            j.path = Some(path.to_string());
        }
    }

    pub async fn mark_failed(&self, id: &str, code: &str, message: &str, hint: &str) {
        if let Some(j) = self.jobs.write().await.get_mut(id) {
            // A cancelled job that then reports an error should stay cancelled:
            // killing the engine makes yt-dlp exit non-zero, which would
            // otherwise show the user a scary failure for their own action.
            if j.state == JobState::Cancelled {
                return;
            }
            j.state = JobState::Failed;
            j.error = Some(message.to_string());
            j.error_code = Some(code.to_string());
            j.hint = if hint.is_empty() { None } else { Some(hint.to_string()) };
        }
    }

    pub async fn mark_cancelled(&self, id: &str) {
        if let Some(j) = self.jobs.write().await.get_mut(id) {
            j.state = JobState::Cancelled;
        }
    }

    pub async fn track_child(&self, id: &str, child: Child) {
        self.running.write().await.insert(id.to_string(), child);
    }

    /// Kill the engine process for a job.
    ///
    /// Returns false when the job had already exited — a normal race (user
    /// clicks cancel as the download completes), not an error worth surfacing.
    pub async fn kill(&self, id: &str) -> bool {
        let mut child = match self.running.write().await.remove(id) {
            Some(c) => c,
            None => return false,
        };
        // Mark cancelled *before* killing so the reader task's terminal
        // error event is ignored (see mark_failed).
        self.mark_cancelled(id).await;
        let _ = child.start_kill();
        let _ = child.wait().await;
        true
    }

    pub async fn forget_child(&self, id: &str) {
        self.running.write().await.remove(id);
    }

    pub async fn snapshot(&self) -> Vec<Job> {
        self.jobs.read().await.values().cloned().collect()
    }

    pub async fn is_running(&self, id: &str) -> bool {
        self.running.read().await.contains_key(id)
    }
}
