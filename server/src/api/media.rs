use std::{io::SeekFrom, sync::Arc};

use axum::{
    body::Body,
    extract::State,
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio_util::io::ReaderStream;

use crate::{
    error::{ApiError, ProblemPath},
    scheduler::FeedFetcher,
    service::RocheService,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum ByteRangeParse {
    IgnoreUnit,
    Satisfiable { start: u64, end: u64 },
    Unsatisfiable,
}

pub(super) async fn media_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let stream = service.media().open_stream(&id).await?;
    let disposition = format!("inline; filename=\"{}\"", stream.filename);

    if let Some(range_val) = headers.get(header::RANGE) {
        let parsed = range_val
            .to_str()
            .ok()
            .map_or(ByteRangeParse::Unsatisfiable, |s| parse_byte_range(s, stream.total_len));
        match parsed {
            ByteRangeParse::IgnoreUnit => {}
            ByteRangeParse::Unsatisfiable => {
                return Ok((
                    StatusCode::RANGE_NOT_SATISFIABLE,
                    [
                        (header::ACCEPT_RANGES, "bytes".to_string()),
                        (header::CONTENT_RANGE, format!("bytes */{}", stream.total_len)),
                    ],
                    Body::empty(),
                )
                    .into_response());
            }
            ByteRangeParse::Satisfiable { start, end } => {
                let len = end - start + 1;
                let mut file = stream.file;
                file.seek(SeekFrom::Start(start))
                    .await
                    .map_err(|e| ApiError::Internal(e.into()))?;
                let body = Body::from_stream(ReaderStream::new(file.take(len)));

                return Ok((
                    StatusCode::PARTIAL_CONTENT,
                    [
                        (header::CONTENT_TYPE, stream.content_type.to_string()),
                        (header::CONTENT_LENGTH, len.to_string()),
                        (header::ACCEPT_RANGES, "bytes".to_string()),
                        (
                            header::CONTENT_RANGE,
                            format!("bytes {start}-{end}/{}", stream.total_len),
                        ),
                        (header::CONTENT_DISPOSITION, disposition),
                        (header::CACHE_CONTROL, "private, max-age=86400".to_string()),
                        (header::X_CONTENT_TYPE_OPTIONS, "nosniff".to_string()),
                    ],
                    body,
                )
                    .into_response());
            }
        }
    }

    let body = Body::from_stream(ReaderStream::new(stream.file));
    Ok((
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, stream.content_type.to_string()),
            (header::CONTENT_LENGTH, stream.total_len.to_string()),
            (header::ACCEPT_RANGES, "bytes".to_string()),
            (header::CONTENT_DISPOSITION, disposition),
            (header::CACHE_CONTROL, "private, max-age=86400".to_string()),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff".to_string()),
        ],
        body,
    )
        .into_response())
}

pub(super) fn parse_byte_range(range_header: &str, total_len: u64) -> ByteRangeParse {
    let trimmed = range_header.trim();
    let Some(spec) = trimmed
        .get(..6)
        .filter(|prefix| prefix.eq_ignore_ascii_case("bytes="))
        .and_then(|_| trimmed.get(6..))
    else {
        return ByteRangeParse::IgnoreUnit;
    };

    if total_len == 0 || spec.contains(',') {
        return ByteRangeParse::Unsatisfiable;
    }
    let Some((start_str, end_str)) = spec.trim().split_once('-') else {
        return ByteRangeParse::Unsatisfiable;
    };
    if start_str.is_empty() {
        if end_str.is_empty() {
            return ByteRangeParse::Unsatisfiable;
        }
        let Ok(suffix) = end_str.parse::<u64>() else {
            return ByteRangeParse::Unsatisfiable;
        };
        if suffix == 0 {
            return ByteRangeParse::Unsatisfiable;
        }
        let start = total_len.saturating_sub(suffix);
        ByteRangeParse::Satisfiable { start, end: total_len - 1 }
    } else {
        let Ok(start) = start_str.parse::<u64>() else {
            return ByteRangeParse::Unsatisfiable;
        };
        if start >= total_len {
            return ByteRangeParse::Unsatisfiable;
        }
        if end_str.is_empty() {
            ByteRangeParse::Satisfiable { start, end: total_len - 1 }
        } else {
            let Ok(end) = end_str.parse::<u64>() else {
                return ByteRangeParse::Unsatisfiable;
            };
            if start > end {
                return ByteRangeParse::Unsatisfiable;
            }
            ByteRangeParse::Satisfiable { start, end: end.min(total_len - 1) }
        }
    }
}

pub(super) async fn media_delete<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.media().delete(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}
