use chrono::{DateTime, SubsecRound, Utc};

/// Returns the current UTC timestamp truncated to milliseconds (`YYYY-MM-DDTHH:MM:SS.sss+00:00`).
#[must_use]
pub fn now_utc() -> DateTime<Utc> {
    Utc::now().trunc_subsecs(3)
}
