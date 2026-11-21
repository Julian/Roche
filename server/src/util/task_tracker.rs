use std::{
    future::Future,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    time::Duration,
};

use tokio::sync::Notify;

/// Lightweight background task registry used to track spawned tasks and wait for them
/// to finish (up to a bounded deadline) during graceful shutdown.
#[derive(Debug, Clone, Default)]
pub struct TaskTracker {
    inner: Arc<TaskTrackerInner>,
}

#[derive(Debug, Default)]
struct TaskTrackerInner {
    active: AtomicUsize,
    closed: AtomicBool,
    idle: Notify,
}

struct TaskGuard {
    inner: Arc<TaskTrackerInner>,
}

impl Drop for TaskGuard {
    fn drop(&mut self) {
        if self.inner.active.fetch_sub(1, Ordering::SeqCst) == 1 {
            self.inner.idle.notify_waiters();
        }
    }
}

impl TaskTracker {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Spawns a tracked background task on the current Tokio runtime.
    /// Returns `false` and skips spawning if the tracker has already been closed for shutdown.
    pub fn spawn<F>(&self, fut: F) -> bool
    where
        F: Future<Output = ()> + Send + 'static,
    {
        if self.inner.closed.load(Ordering::SeqCst) {
            return false;
        }
        self.inner.active.fetch_add(1, Ordering::SeqCst);
        if self.inner.closed.load(Ordering::SeqCst) {
            if self.inner.active.fetch_sub(1, Ordering::SeqCst) == 1 {
                self.inner.idle.notify_waiters();
            }
            return false;
        }
        let guard = TaskGuard { inner: Arc::clone(&self.inner) };
        tokio::spawn(async move {
            let _guard = guard;
            fut.await;
        });
        true
    }

    /// Marks the tracker closed so no new background tasks are accepted.
    pub fn close(&self) {
        self.inner.closed.store(true, Ordering::SeqCst);
    }

    /// Returns the number of currently active tracked tasks.
    #[must_use]
    pub fn active_count(&self) -> usize {
        self.inner.active.load(Ordering::SeqCst)
    }

    /// Closes the tracker and waits until all active tasks finish or `timeout` elapses.
    /// Returns `true` if all tasks drained cleanly before the deadline.
    pub async fn close_and_wait(&self, timeout: Duration) -> bool {
        self.close();
        tokio::time::timeout(timeout, async {
            loop {
                let notified = self.inner.idle.notified();
                tokio::pin!(notified);
                notified.as_mut().enable();
                if self.inner.active.load(Ordering::SeqCst) == 0 {
                    return;
                }
                notified.await;
            }
        })
        .await
        .is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_task_tracker_waits_for_completion_and_rejects_after_close() {
        let tracker = TaskTracker::new();
        let done = Arc::new(AtomicBool::new(false));
        let done_clone = Arc::clone(&done);
        assert!(tracker.spawn(async move {
            tokio::time::sleep(Duration::from_millis(25)).await;
            done_clone.store(true, Ordering::Release);
        }));
        assert_eq!(tracker.active_count(), 1);
        assert!(tracker.close_and_wait(Duration::from_secs(2)).await);
        assert!(done.load(Ordering::Acquire));
        assert_eq!(tracker.active_count(), 0);
        assert!(!tracker.spawn(async {}));
    }
}
