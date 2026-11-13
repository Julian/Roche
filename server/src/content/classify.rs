use url::Url;

use crate::{
    db::ContentType,
    feeds::{ArxivParser, YouTubeParser},
};

pub struct UrlClassifier;

impl UrlClassifier {
    #[must_use]
    pub fn is_direct_pdf_url(url: &Url) -> bool {
        let raw_path = url.path();
        std::path::Path::new(raw_path)
            .extension()
            .is_some_and(|ext| ext.eq_ignore_ascii_case("pdf"))
            || raw_path.to_ascii_lowercase().ends_with("/pdf")
    }

    #[must_use]
    pub fn is_direct_audio_url(url: &Url) -> bool {
        let raw_path = url.path();
        std::path::Path::new(raw_path).extension().is_some_and(|ext| {
            matches!(
                ext.to_str().unwrap_or("").to_ascii_lowercase().as_str(),
                "mp3" | "m4a" | "aac" | "ogg" | "oga" | "opus" | "wav" | "flac"
            )
        })
    }

    #[must_use]
    pub fn classify(url: &Url) -> ContentType {
        if ArxivParser::pdf_url_for_paper(url).is_some() {
            ContentType::Paper
        } else if YouTubeParser::extract_video_id(url).is_some() {
            ContentType::Video
        } else if Self::is_direct_pdf_url(url) {
            ContentType::Pdf
        } else if Self::is_direct_audio_url(url) {
            ContentType::Audio
        } else {
            ContentType::Article
        }
    }

    #[must_use]
    pub fn resolve_pdf_url(url: &Url) -> Option<Url> {
        ArxivParser::pdf_url_for_paper(url)
            .or_else(|| Self::is_direct_pdf_url(url).then(|| url.clone()))
    }

    #[must_use]
    pub fn is_valid_pdf_bytes(bytes: &[u8]) -> bool {
        bytes.starts_with(b"%PDF-")
    }
}

#[must_use]
pub fn is_direct_pdf_url(url: &Url) -> bool {
    UrlClassifier::is_direct_pdf_url(url)
}

#[must_use]
pub fn is_direct_audio_url(url: &Url) -> bool {
    UrlClassifier::is_direct_audio_url(url)
}

#[must_use]
pub fn classify_url(url: &Url) -> ContentType {
    UrlClassifier::classify(url)
}

#[must_use]
pub fn resolve_pdf_url(url: &Url) -> Option<Url> {
    UrlClassifier::resolve_pdf_url(url)
}

#[must_use]
pub fn is_valid_pdf_bytes(bytes: &[u8]) -> bool {
    UrlClassifier::is_valid_pdf_bytes(bytes)
}
