use std::{
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};

use anyhow::Result;
use serde::Deserialize;
use tokio::process::Command;
use url::Url;

use crate::{
    config::MediaConfig,
    db::{Media, MediaRepo, MediaType},
    feeds::YouTubeParser,
    media::MediaStore,
};

const YTDLP_TIMEOUT: Duration = Duration::from_secs(600);
const YTDLP_VERSION_TIMEOUT: Duration = Duration::from_secs(5);
#[cfg(test)]
pub const DEFAULT_MAX_VIDEO_SIZE_MB: u64 = 500;

#[derive(Debug, Default, Deserialize)]
struct YtDlpInfoJson {
    duration: Option<f64>,
    title: Option<String>,
    description: Option<String>,
    uploader: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct VideoMetadata {
    pub title: Option<String>,
    pub description: Option<String>,
    pub uploader: Option<String>,
}

#[derive(Debug, Clone)]
pub struct YtDlpWorker {
    config: MediaConfig,
    media_store: MediaStore,
}

impl YtDlpWorker {
    #[must_use]
    pub const fn new(config: MediaConfig, media_store: MediaStore) -> Self {
        Self { config, media_store }
    }

    /// Builds the argument vector passed to `yt-dlp` without shell interpolation.
    #[cfg(test)]
    pub fn build_args(item_id: &str, url: &Url, output_dir: &Path) -> Result<Vec<String>> {
        Self::build_args_with_limit(item_id, url, output_dir, DEFAULT_MAX_VIDEO_SIZE_MB)
    }

    /// Builds the argument vector passed to `yt-dlp` with an explicit `--max-filesize` cap in MiB.
    pub fn build_args_with_limit(
        item_id: &str,
        url: &Url,
        output_dir: &Path,
        max_video_size_mb: u64,
    ) -> Result<Vec<String>> {
        Self::validate_item_id(item_id)?;
        let canonical_url = YouTubeParser::canonical_watch_url(url)
            .ok_or_else(|| anyhow::anyhow!("URL is not a valid YouTube watch URL: {url}"))?;
        let output_template = output_dir.join("video.%(ext)s");
        let template_str = output_template
            .to_str()
            .ok_or_else(|| anyhow::anyhow!("Output directory path is not valid UTF-8"))?;
        let max_mb = max_video_size_mb.max(1);

        Ok(vec![
            "--ignore-config".to_string(),
            "--no-progress".to_string(),
            "--no-playlist".to_string(),
            "--no-exec".to_string(),
            "--socket-timeout".to_string(),
            "30".to_string(),
            "--max-filesize".to_string(),
            format!("{max_mb}M"),
            "--format".to_string(),
            "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best".to_string(),
            "--merge-output-format".to_string(),
            "mp4".to_string(),
            "--write-thumbnail".to_string(),
            "--write-info-json".to_string(),
            "--output".to_string(),
            template_str.to_string(),
            "--".to_string(),
            canonical_url.into(),
        ])
    }

    /// Checks `yt-dlp --version` asynchronously without blocking Tokio runtime threads.
    pub async fn check_version(&self) -> Result<String> {
        let mut cmd = Command::new(&self.config.ytdlp_path);
        cmd.arg("--version")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        let output = tokio::time::timeout(YTDLP_VERSION_TIMEOUT, cmd.output())
            .await
            .map_err(|_| anyhow::anyhow!("yt-dlp --version timed out"))?
            .map_err(|e| {
                anyhow::anyhow!(
                    "Failed to spawn yt-dlp at '{}': {e}",
                    self.config.ytdlp_path.display()
                )
            })?;
        if !output.status.success() {
            anyhow::bail!("yt-dlp --version exited with {}", output.status);
        }
        Ok(String::from_utf8_lossy(&output.stdout).trim().to_owned())
    }

    pub async fn download_for_item(
        &self,
        media_repo: &MediaRepo<'_>,
        item_id: &str,
        url: &Url,
    ) -> Result<(Vec<Media>, VideoMetadata)> {
        Self::validate_item_id(item_id)?;
        let item_dir = self.item_download_dir(item_id).await?;
        let args =
            Self::build_args_with_limit(item_id, url, &item_dir, self.config.max_video_size_mb)?;

        MediaStore::ensure_private_dir(self.media_store.base_dir()).await?;
        MediaStore::ensure_private_dir(&item_dir).await?;
        let verified_dir = self.media_store.validate_path_async(&item_dir).await?;

        let result = self.spawn_and_register(media_repo, item_id, &verified_dir, &args).await;
        if result.is_err() {
            let _ = tokio::fs::remove_dir_all(&verified_dir).await;
        }
        result
    }

    async fn spawn_and_register(
        &self,
        media_repo: &MediaRepo<'_>,
        item_id: &str,
        verified_dir: &Path,
        args: &[String],
    ) -> Result<(Vec<Media>, VideoMetadata)> {
        let mut cmd = Command::new(&self.config.ytdlp_path);
        cmd.args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);

        let output = tokio::time::timeout(YTDLP_TIMEOUT, cmd.output())
            .await
            .map_err(|_| anyhow::anyhow!("yt-dlp timed out after {}s", YTDLP_TIMEOUT.as_secs()))?
            .map_err(|e| {
                anyhow::anyhow!(
                    "Failed to spawn yt-dlp at '{}': {e}",
                    self.config.ytdlp_path.display()
                )
            })?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            anyhow::bail!("yt-dlp exited with {}: {}", output.status, stderr.trim());
        }

        self.register_downloaded_files(media_repo, item_id, verified_dir).await
    }

    pub async fn register_downloaded_files(
        &self,
        media_repo: &MediaRepo<'_>,
        item_id: &str,
        item_dir: &Path,
    ) -> Result<(Vec<Media>, VideoMetadata)> {
        Self::validate_item_id(item_id)?;
        let verified_dir = self.media_store.validate_path_async(item_dir).await?;

        let info_path = verified_dir.join("video.info.json");
        let info = tokio::fs::read(&info_path).await.map_or_else(
            |_| YtDlpInfoJson::default(),
            |info_bytes| serde_json::from_slice::<YtDlpInfoJson>(&info_bytes).unwrap_or_default(),
        );
        #[allow(clippy::cast_possible_truncation)]
        let duration =
            info.duration.filter(|d| d.is_finite() && *d >= 0.0).map(|d| d.round() as i64);
        let metadata = VideoMetadata {
            title: info.title.map(|s| s.trim().to_owned()).filter(|s| !s.is_empty()),
            description: info.description.map(|s| s.trim().to_owned()).filter(|s| !s.is_empty()),
            uploader: info.uploader.map(|s| s.trim().to_owned()).filter(|s| !s.is_empty()),
        };
        let _ = tokio::fs::remove_file(&info_path).await;

        let mut entries = tokio::fs::read_dir(&verified_dir).await?;
        let mut video_file: Option<(PathBuf, i64)> = None;
        let mut thumbnail_file: Option<(PathBuf, i64)> = None;

        while let Some(entry) = entries.next_entry().await? {
            let path = entry.path();
            let meta = entry.metadata().await?;
            if !meta.is_file() {
                continue;
            }
            let size = i64::try_from(meta.len()).unwrap_or(i64::MAX);
            let ext =
                path.extension().and_then(|e| e.to_str()).map_or_default(str::to_ascii_lowercase);

            match ext.as_str() {
                "mp4" | "webm" | "mkv" | "m4v" if video_file.is_none() => {
                    video_file = Some((path, size));
                }
                "webp" | "jpg" | "jpeg" | "png" if thumbnail_file.is_none() => {
                    thumbnail_file = Some((path, size));
                }
                _ => {
                    let _ = tokio::fs::remove_file(&path).await;
                }
            }
        }

        let Some((video_path, video_size)) = video_file else {
            anyhow::bail!("yt-dlp completed without producing a video file");
        };

        let max_bytes =
            i64::try_from(self.config.max_video_size_mb.max(1).saturating_mul(1024 * 1024))
                .unwrap_or(i64::MAX);
        if video_size > max_bytes {
            anyhow::bail!(
                "Downloaded video exceeds max_video_size_mb limit ({video_size} bytes > {max_bytes} bytes)"
            );
        }

        MediaStore::tighten_private_file_if_exists(&video_path).await;
        if let Some((ref thumb_path, _)) = thumbnail_file {
            MediaStore::tighten_private_file_if_exists(thumb_path).await;
        }

        let video_rel = self.media_store.relative_path_async(&video_path).await?;
        let thumb_rel_and_size = match thumbnail_file {
            Some((thumb_path, size)) => {
                Some((self.media_store.relative_path_async(&thumb_path).await?, size))
            }
            None => None,
        };

        let mut specs = vec![crate::db::NewMediaSpec {
            media_type: MediaType::Video,
            path: &video_rel,
            file_size: Some(video_size),
            duration,
        }];
        if let Some((ref thumb_rel, size)) = thumb_rel_and_size {
            specs.push(crate::db::NewMediaSpec {
                media_type: MediaType::Thumbnail,
                path: thumb_rel,
                file_size: Some(size),
                duration: None,
            });
        }
        let registered = media_repo.create_batch(item_id, &specs).await?;

        Ok((registered, metadata))
    }

    async fn item_download_dir(&self, item_id: &str) -> Result<PathBuf> {
        let base_downloads = self.media_store.resolve_download_dir(&self.config.download_dir);
        let candidate = base_downloads.join(item_id);
        self.media_store.validate_path_async(&candidate).await
    }

    fn validate_item_id(item_id: &str) -> Result<()> {
        if item_id.is_empty()
            || item_id.len() > 64
            || !item_id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
        {
            anyhow::bail!("Invalid item_id for media storage: '{item_id}'");
        }
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::db::{ContentType, Db, NewItem};

    #[test]
    fn test_build_args_enforces_canonical_youtube_url_and_safe_item_id() {
        let url = Url::parse("https://youtu.be/dQw4w9WgXcQ?t=42").unwrap();
        let out_dir = Path::new("/tmp/roche-test/downloads/item-123");
        let args = YtDlpWorker::build_args("item-123", &url, out_dir).unwrap();

        assert!(args.contains(&"--no-playlist".to_string()));
        assert!(args.contains(&"--no-exec".to_string()));
        assert_eq!(args[args.len() - 2], "--");
        assert_eq!(args[args.len() - 1], "https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        // Rejects non-YouTube URLs
        let other = Url::parse("https://example.com/watch?v=dQw4w9WgXcQ").unwrap();
        assert!(YtDlpWorker::build_args("item-123", &other, out_dir).is_err());

        // Rejects path traversal in item_id
        assert!(YtDlpWorker::build_args("../evil", &url, out_dir).is_err());
    }

    #[tokio::test]
    async fn test_register_downloaded_files_parses_info_json_and_creates_media_rows() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let watch_url = Url::parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ").unwrap();
        let item = db
            .items()
            .create(&NewItem {
                feed_id: None,
                title: "Test Video",
                url: &watch_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Video,
            })
            .await
            .unwrap();

        let temp_base =
            std::env::temp_dir().join(format!("roche-ytdlp-test-{}", uuid::Uuid::new_v4()));
        let store = MediaStore::new(&temp_base);
        let cfg = MediaConfig {
            ytdlp_path: PathBuf::from("/usr/local/bin/yt-dlp"),
            download_dir: temp_base.join("downloads"),
            max_concurrent_downloads: 2,
            max_video_size_mb: 500,
        };
        let worker = YtDlpWorker::new(cfg, store);

        let item_dir = temp_base.join("downloads").join(&item.id);
        tokio::fs::create_dir_all(&item_dir).await.unwrap();
        tokio::fs::write(
            item_dir.join("video.info.json"),
            br#"{"duration": 213.4, "title": "Info Title", "uploader": "Channel X"}"#,
        )
        .await
        .unwrap();
        tokio::fs::write(item_dir.join("video.mp4"), b"fake-mp4-video-bytes").await.unwrap();
        tokio::fs::write(item_dir.join("video.webp"), b"fake-webp-thumb").await.unwrap();

        let (media, meta) =
            worker.register_downloaded_files(&db.media(), &item.id, &item_dir).await.unwrap();
        assert_eq!(media.len(), 2);
        assert_eq!(meta.title.as_deref(), Some("Info Title"));
        assert_eq!(meta.uploader.as_deref(), Some("Channel X"));

        let loaded_item = db.items().get(&item.id).await.unwrap().unwrap();
        assert_eq!(loaded_item.duration, Some(213));
        assert!(loaded_item.thumbnail_media_id.is_some());

        // info.json was cleaned up after ingestion
        assert!(!item_dir.join("video.info.json").exists());

        let _ = tokio::fs::remove_dir_all(&temp_base).await;
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn test_download_for_item_does_not_delete_symlink_escape_target() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let watch_url = Url::parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ").unwrap();

        let temp_base =
            std::env::temp_dir().join(format!("roche-ytdlp-sym-{}", uuid::Uuid::new_v4()));
        let outside_dir =
            std::env::temp_dir().join(format!("roche-ytdlp-outside-{}", uuid::Uuid::new_v4()));
        let sentinel = outside_dir.join("item-123").join("keep-me.txt");
        tokio::fs::create_dir_all(sentinel.parent().unwrap()).await.unwrap();
        tokio::fs::write(&sentinel, b"do-not-delete").await.unwrap();

        tokio::fs::create_dir_all(&temp_base).await.unwrap();
        std::os::unix::fs::symlink(&outside_dir, temp_base.join("downloads")).unwrap();

        let store = MediaStore::new(&temp_base);
        let cfg = MediaConfig {
            ytdlp_path: PathBuf::from("/usr/local/bin/yt-dlp"),
            download_dir: temp_base.join("downloads"),
            max_concurrent_downloads: 2,
            max_video_size_mb: 500,
        };
        let worker = YtDlpWorker::new(cfg, store);

        let err = worker.download_for_item(&db.media(), "item-123", &watch_url).await;
        assert!(err.is_err());
        assert!(
            sentinel.exists(),
            "validate_path failure must never delete outside symlink target"
        );

        let _ = tokio::fs::remove_dir_all(&temp_base).await;
        let _ = tokio::fs::remove_dir_all(&outside_dir).await;
    }
}
