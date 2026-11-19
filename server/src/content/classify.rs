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

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_url_classification_and_pdf_resolution() {
        let arxiv = Url::parse("https://arxiv.org/abs/2301.07041v2").unwrap();
        assert_eq!(UrlClassifier::classify(&arxiv), ContentType::Paper);
        assert_eq!(classify_url(&arxiv), ContentType::Paper);
        assert_eq!(
            resolve_pdf_url(&arxiv).unwrap().as_str(),
            "https://arxiv.org/pdf/2301.07041v2.pdf"
        );

        let pdf = Url::parse("https://example.com/papers/whitepaper.pdf").unwrap();
        assert_eq!(classify_url(&pdf), ContentType::Pdf);
        assert_eq!(resolve_pdf_url(&pdf).unwrap().as_str(), pdf.as_str());

        let yt = Url::parse("https://youtu.be/dQw4w9WgXcQ").unwrap();
        assert_eq!(classify_url(&yt), ContentType::Video);
        assert!(resolve_pdf_url(&yt).is_none());
    }
}
