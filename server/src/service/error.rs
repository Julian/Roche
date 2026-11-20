use thiserror::Error;

use crate::{db::CatalogError, util::url_guard::SsrfBlockedError};

/// Transport-agnostic application error returned by all `service::*` operations.
///
/// Mapped to RFC 9457 Problem Details by `error::ApiError` (for JSON API routes) and to
/// HTML error pages by `web::html::WebError` (for browser views).
#[derive(Debug, Error)]
pub enum AppError {
    #[error("Bad request: {0}")]
    BadRequest(String),

    #[allow(dead_code)]
    #[error("Unauthorized: {0}")]
    Unauthorized(String),

    #[error("Invalid bearer token: {0}")]
    TokenInvalid(String),

    #[error("Invalid pairing code: {0}")]
    PairingCodeInvalid(String),

    #[error("Pairing rate limited: {detail}")]
    PairingRateLimited { detail: String, retry_after_seconds: u64 },

    #[error("Not found: {0}")]
    NotFound(String),

    #[error("Conflict: {0}")]
    Conflict(String),

    #[error("Ambiguous lookup: {detail}")]
    AmbiguousLookup { detail: String, candidates: Vec<String> },

    #[error("Immutable collection: {0}")]
    ImmutableCollection(String),

    #[error("SSRF blocked: {0}")]
    SsrfBlocked(String),

    #[error("Payload too large: {detail}")]
    PayloadTooLarge { detail: String, max_bytes: Option<u64> },

    #[error("Upstream fetch failed: {0}")]
    BadGateway(String),

    #[error("Database error: {0}")]
    Database(#[from] sqlx::Error),

    #[error("Internal error: {0}")]
    Internal(#[from] anyhow::Error),
}

impl AppError {
    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::BadRequest(message.into())
    }

    pub fn token_invalid(message: impl Into<String>) -> Self {
        Self::TokenInvalid(message.into())
    }

    pub fn pairing_code_invalid(message: impl Into<String>) -> Self {
        Self::PairingCodeInvalid(message.into())
    }

    pub fn pairing_rate_limited(detail: impl Into<String>, retry_after_seconds: u64) -> Self {
        Self::PairingRateLimited { detail: detail.into(), retry_after_seconds }
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::NotFound(message.into())
    }

    pub fn conflict(message: impl Into<String>) -> Self {
        Self::Conflict(message.into())
    }

    pub fn ambiguous_lookup(detail: impl Into<String>, candidates: Vec<String>) -> Self {
        Self::AmbiguousLookup { detail: detail.into(), candidates }
    }

    pub fn immutable_collection(message: impl Into<String>) -> Self {
        Self::ImmutableCollection(message.into())
    }

    pub fn ssrf_blocked(message: impl Into<String>) -> Self {
        Self::SsrfBlocked(message.into())
    }

    pub fn payload_too_large(detail: impl Into<String>) -> Self {
        Self::PayloadTooLarge { detail: detail.into(), max_bytes: None }
    }

    pub fn bad_gateway(message: impl Into<String>) -> Self {
        Self::BadGateway(message.into())
    }
}

impl From<CatalogError> for AppError {
    fn from(err: CatalogError) -> Self {
        match err {
            CatalogError::Db(db_err) => Self::Database(db_err),
            CatalogError::NotFound(msg) => Self::not_found(msg),
            CatalogError::Conflict(msg) => Self::conflict(msg),
            CatalogError::AmbiguousLookup { ref candidates, .. } => {
                Self::ambiguous_lookup(err.to_string(), candidates.clone())
            }
            CatalogError::ImmutableCollection { .. } => Self::immutable_collection(err.to_string()),
            CatalogError::InvalidInput(_) => Self::bad_request(err.to_string()),
            CatalogError::Internal(msg) => Self::Internal(anyhow::anyhow!("{msg}")),
        }
    }
}

impl From<SsrfBlockedError> for AppError {
    fn from(err: SsrfBlockedError) -> Self {
        Self::ssrf_blocked(err.0)
    }
}
