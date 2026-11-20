use url::Url;

use crate::{
    domain::ContentType,
    util::media_urls::{
        arxiv_pdf_url, extract_youtube_video_id, is_direct_audio_url, is_direct_pdf_url,
    },
};

pub struct UrlClassifier;

impl UrlClassifier {
    #[must_use]
    pub fn classify(url: &Url) -> ContentType {
        if arxiv_pdf_url(url).is_some() {
            ContentType::Paper
        } else if extract_youtube_video_id(url).is_some() {
            ContentType::Video
        } else if is_direct_pdf_url(url) {
            ContentType::Pdf
        } else if is_direct_audio_url(url) {
            ContentType::Audio
        } else {
            ContentType::Article
        }
    }

    #[must_use]
    pub fn resolve_pdf_url(url: &Url) -> Option<Url> {
        arxiv_pdf_url(url).or_else(|| is_direct_pdf_url(url).then(|| url.clone()))
    }

    #[must_use]
    pub fn is_valid_pdf_bytes(bytes: &[u8]) -> bool {
        bytes.starts_with(b"%PDF-")
    }
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
#[must_use]
pub fn infer_feed_type(url: &Url) -> crate::domain::FeedType {
    if crate::util::media_urls::is_arxiv_host(url.host_str()) {
        crate::domain::FeedType::Arxiv
    } else if crate::util::media_urls::is_youtube_url(url) {
        crate::domain::FeedType::Youtube
    } else {
        crate::domain::FeedType::Rss
    }
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
