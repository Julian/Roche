use thiserror::Error;

use crate::{
    db::{CatalogError, ConflictError},
    util::url_guard::SsrfBlockedError,
};

/// Transport-agnostic application error returned by all `service::*` operations.
///
/// Normalizes `ConflictError`, `SsrfBlockedError`, `CatalogError`, and `sqlx` unique-constraint
/// violations in one place so both `api::error::ApiError` and `web::html::WebError` can map
/// directly without re-downcasting inner error chains.
#[derive(Debug, Error)]
pub enum AppError {
    #[error("Bad request: {0}")]
    BadRequest(String),

    #[error("Forbidden: {0}")]
    Forbidden(String),

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
    Database(sqlx::Error),

    #[error("Internal error: {0}")]
    Internal(anyhow::Error),
}

impl AppError {
    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::BadRequest(message.into())
    }

    pub fn forbidden(message: impl Into<String>) -> Self {
        Self::Forbidden(message.into())
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

    fn is_unique_sqlx_error(err: &sqlx::Error) -> bool {
        matches!(
            err,
            sqlx::Error::Database(db_err)
                if db_err.is_unique_violation()
                    || db_err.message().contains("UNIQUE constraint failed")
        )
    }

    fn from_catalog_error_ref(err: &CatalogError) -> Option<Self> {
        match err {
            CatalogError::Db(db_err) => {
                if Self::is_unique_sqlx_error(db_err) {
                    Some(Self::conflict(
                        "A resource with the same unique identifier already exists",
                    ))
                } else {
                    None
                }
            }
            CatalogError::NotFound(msg) => Some(Self::not_found(msg.clone())),
            CatalogError::Conflict(msg) => Some(Self::conflict(msg.clone())),
            CatalogError::AmbiguousLookup { candidates, .. } => {
                Some(Self::ambiguous_lookup(err.to_string(), candidates.clone()))
            }
            CatalogError::ImmutableCollection { .. } => {
                Some(Self::immutable_collection(err.to_string()))
            }
            CatalogError::InvalidInput(_) => Some(Self::bad_request(err.to_string())),
            CatalogError::Internal(_) => None,
        }
    }
}

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        if Self::is_unique_sqlx_error(&err) {
            Self::conflict("A resource with the same unique identifier already exists")
        } else {
            Self::Database(err)
        }
    }
}

impl From<CatalogError> for AppError {
    fn from(err: CatalogError) -> Self {
        match err {
            CatalogError::Db(db_err) => Self::from(db_err),
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

impl From<ConflictError> for AppError {
    fn from(err: ConflictError) -> Self {
        Self::conflict(err.0)
    }
}

impl From<anyhow::Error> for AppError {
    fn from(err: anyhow::Error) -> Self {
        if let Some(conflict) = err.downcast_ref::<ConflictError>() {
            return Self::conflict(conflict.0.clone());
        }
        if let Some(ssrf) = err.downcast_ref::<SsrfBlockedError>() {
            return Self::ssrf_blocked(ssrf.0.clone());
        }
        if let Some(cat_err) = err.downcast_ref::<CatalogError>()
            && let Some(mapped) = Self::from_catalog_error_ref(cat_err)
        {
            return mapped;
        }
        if let Some(sqlx_err) = err.downcast_ref::<sqlx::Error>()
            && Self::is_unique_sqlx_error(sqlx_err)
        {
            return Self::conflict("A resource with the same unique identifier already exists");
        }
        Self::Internal(err)
    }
}
