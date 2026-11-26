#![allow(clippy::unwrap_used, clippy::too_many_lines)]

use std::{path::PathBuf, sync::Arc};

use chrono::Utc;
use url::Url;

use super::*;
use crate::{
    db::{
        ActivityEntityType, CollectionRole, ConsumptionStatus, ContentType, Db, EntryIdentifier,
        EntryKind, ManualItemInput, WANT_TO_READ_COLLECTION_ID,
    },
    media::MediaStore,
};

fn build_test_pdf(content_stream: &str) -> Vec<u8> {
    let mut pdf = Vec::new();
    pdf.extend_from_slice(b"%PDF-1.5\n");
    pdf.extend_from_slice(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
    pdf.extend_from_slice(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
    pdf.extend_from_slice(b"3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>\nendobj\n");
    pdf.extend_from_slice(
        format!("4 0 obj\n<< /Length {} >>\nstream\n", content_stream.len()).as_bytes(),
    );
    pdf.extend_from_slice(content_stream.as_bytes());
    pdf.extend_from_slice(b"\nendstream\nendobj\n%%EOF\n");
    pdf
}

#[test]
fn test_extract_canonical_html_url_handles_html5_scripts_templates_and_relative_hrefs() {
    let html = r#"<!doctype html>
    <html>
      <head>
        <meta charset=utf-8>
        <script async>if (a < b && c > d) { var s = '<link rel="canonical" href="https://evil.example/spoof">'; }</script>
        <template><link rel="canonical" href="https://evil.example/template"></template>
        <meta property="og:url" content="https://example.org/og-fallback">
        <link rel="alternate canonical" href="https://example.org/essays/types">
      </head>
      <body><p>Hello</p></body>
    </html>"#;
    assert_eq!(
        extract_canonical_html_url(html).as_ref().map(Url::as_str),
        Some("https://example.org/essays/types")
    );

    let relative_html =
        r#"<html><head><link rel="canonical" href="/blog/2026/study/"></head></html>"#;
    let base = Url::parse("https://metr.org/").unwrap();
    assert_eq!(
        extract_canonical_html_url_with_base(relative_html, Some(&base)).as_ref().map(Url::as_str),
        Some("https://metr.org/blog/2026/study/")
    );

    let og_only =
        r#"<html><head><meta property="og:url" content="https://example.org/post"></head></html>"#;
    assert_eq!(
        extract_canonical_html_url(og_only).as_ref().map(Url::as_str),
        Some("https://example.org/post")
    );

    let private_canonical =
        r#"<html><head><link rel="canonical" href="http://127.0.0.1/secret"></head></html>"#;
    assert!(extract_canonical_html_url(private_canonical).is_none());
}

#[test]
fn test_derive_pdf_canonical_url_encodes_special_doi_chars() {
    let sici = "10.1002/(sici)1097-0258(19980815)17:15/16<1661::aid-sim968>3.0.co;2-2#frag?x=1";
    let derived = derive_pdf_canonical_url(None, Some(sici), None).unwrap();
    assert!(derived.starts_with("https://doi.org/10.1002/"));
    assert!(!derived.contains('<') && !derived.contains('>') && !derived.contains('#'));
    assert!(derived.contains("%3C1661") && derived.contains("%3E3.0.co"));
}

#[tokio::test]
async fn test_import_local_documents_dry_run_live_reader_media_and_sync() {
    let temp_dir = std::env::temp_dir().join(format!("roche-import-docs-{}", uuid::Uuid::new_v4()));
    let docs_dir = temp_dir.join("docs");
    let media_dir = temp_dir.join("media");
    tokio::fs::create_dir_all(&docs_dir).await.unwrap();

    let pdf_stream = "\
BT\n\
/F1 16 Tf\n\
72 720 Td (A Large Scale Study of Programming Languages and Code Quality in Github) Tj\n\
/F1 11 Tf\n\
0 -24 Td (Baishakhi Ray and Daryl Posnett) Tj\n\
0 -18 Td (Proceedings DOI: 10.1145/2635868  arXiv:1408.1234v2) Tj\n\
0 -22 Td (We analyze 729 open-source GitHub repositories across 17 languages to measure defect rates.) Tj\n\
ET\n";
    let pdf_bytes = build_test_pdf(pdf_stream);
    tokio::fs::write(docs_dir.join("ray_2014.pdf"), &pdf_bytes).await.unwrap();

    // Second PDF has no DOI, no arXiv, and no URL to verify URL-less local PDFs open in Reader.
    let urlless_stream = "\
BT\n\
/F1 16 Tf\n\
72 720 Td (Internal Technical Report on Build Latency) Tj\n\
/F1 11 Tf\n\
0 -24 Td (Jane Researcher) Tj\n\
0 -22 Td (Measuring incremental compilation latency across monorepo services.) Tj\n\
ET\n";
    tokio::fs::write(docs_dir.join("internal_report.pdf"), build_test_pdf(urlless_stream))
        .await
        .unwrap();

    let html_doc = r#"<!doctype html>
    <html>
      <head>
        <title>Empirical Evidence on Static Types</title>
        <link rel="canonical" href="https://example.org/empirical-pl/">
      </head>
      <body>
        <article>
          <h1>Empirical Evidence on Static Types</h1>
          <p>An overview of <a href="study.html">controlled experiments</a> on static typing.</p>
        </article>
      </body>
    </html>"#;
    tokio::fs::write(docs_dir.join("danluu.html"), html_doc).await.unwrap();

    let manifest = LocalDocsImportManifest {
        base_dir: Some(docs_dir.clone()),
        collections: vec![LocalDocCollectionSpec {
            ref_key: Some("ese".to_owned()),
            title: "Empirical Software Engineering".to_owned(),
            slug: Some("empirical_software_engineering".to_owned()),
            role: CollectionRole::List,
            summary: Some("Curated ESE reading corpus".to_owned()),
            color: None,
        }],
        documents: vec![
            LocalDocSpec {
                path: PathBuf::from("ray_2014.pdf"),
                title: Some(
                    "A Large Scale Study of Programming Languages and Code Quality in Github"
                        .to_owned(),
                ),
                creators: vec!["Baishakhi Ray".to_owned(), "Daryl Posnett".to_owned()],
                release_year: Some(2014),
                metadata: Some(
                    serde_json::json!({ "topics": ["PL & Types"], "venue": "FSE 2014" }),
                ),
                collections: vec!["ese".to_owned()],
                position: Some(1.0),
                want_to_read: true,
                tags: vec!["PL & Types".to_owned()],
                ..Default::default()
            },
            LocalDocSpec {
                path: PathBuf::from("danluu.html"),
                creators: vec!["Dan Luu".to_owned()],
                release_year: Some(2017),
                metadata: Some(serde_json::json!({ "topics": ["PL & Types"] })),
                collections: vec!["empirical_software_engineering".to_owned()],
                position: Some(2.0),
                want_to_read: true,
                tags: vec!["PL & Types".to_owned()],
                ..Default::default()
            },
            LocalDocSpec {
                path: PathBuf::from("internal_report.pdf"),
                title: Some("Internal Technical Report on Build Latency".to_owned()),
                creators: vec!["Jane Researcher".to_owned()],
                release_year: Some(2025),
                collections: vec!["ese".to_owned()],
                position: Some(3.0),
                want_to_read: true,
                tags: vec!["Performance".to_owned()],
                ..Default::default()
            },
        ],
    };

    let mock_fetcher = Arc::new(|url: &Url| -> Option<String> {
        let s = url.as_str();
        if s.contains("export.arxiv.org/api/query") && s.contains("1408.1234") {
            Some(
                r#"<?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:arxiv="http://arxiv.org/schemas/atom">
                  <entry>
                    <id>http://arxiv.org/abs/1408.1234v2</id>
                    <published>2014-08-05T00:00:00Z</published>
                    <title>A Large Scale Study of Programming Languages and Code Quality in Github</title>
                    <summary>Empirical study of 729 GitHub projects across 17 languages.</summary>
                    <author><name>Baishakhi Ray</name></author>
                    <author><name>Daryl Posnett</name></author>
                    <arxiv:doi>10.1145/2635868.2635922</arxiv:doi>
                  </entry>
                </feed>"#
                    .to_owned(),
            )
        } else if s.contains("api.crossref.org/works/10.1145/2635868.2635922") {
            Some(
                r#"{
                    "message": {
                        "type": "proceedings-article",
                        "DOI": "10.1145/2635868.2635922",
                        "title": ["A Large Scale Study of Programming Languages and Code Quality in Github"],
                        "author": [
                            {"given": "Baishakhi", "family": "Ray"},
                            {"given": "Daryl", "family": "Posnett"}
                        ],
                        "issued": {"date-parts": [[2014, 11, 11]]}
                    }
                }"#
                .to_owned(),
            )
        } else {
            None
        }
    });

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let media_store = MediaStore::new(&media_dir);

    // 1. Dry-run simulates everything without touching DB or media_dir
    let dry_opts = LocalDocsImportOptions {
        enrich: true,
        reflow_pdfs: true,
        dry_run: true,
        ..Default::default()
    };
    let dry_report = import_local_documents_with_fetcher(
        &db,
        &media_store,
        &manifest,
        &dry_opts,
        Some(Arc::clone(&mock_fetcher)),
    )
    .await
    .unwrap();
    assert!(dry_report.dry_run);
    assert_eq!(dry_report.documents_total, 3);
    assert_eq!(dry_report.entries_created, 3);
    assert_eq!(dry_report.dois_detected, 1);
    assert_eq!(dry_report.arxiv_ids_detected, 1);
    assert_eq!(dry_report.added_to_want_to_read, 3);
    assert!(!media_dir.exists(), "dry-run must never create files in media_dir");
    assert!(db.catalog().get_collection_summary("empirical_software_engineering").await.is_err());

    // 2. Live import commits DB + writes PDFs to media_dir
    let live_opts = LocalDocsImportOptions {
        enrich: true,
        reflow_pdfs: true,
        dry_run: false,
        ..Default::default()
    };
    let live_report = import_local_documents_with_fetcher(
        &db,
        &media_store,
        &manifest,
        &live_opts,
        Some(Arc::clone(&mock_fetcher)),
    )
    .await
    .unwrap();
    assert!(!live_report.dry_run);
    assert_eq!(live_report.entries_created, 3);
    assert_eq!(live_report.pdf_media_stored, 2);
    assert_eq!(live_report.pdf_media_reused, 0);
    assert_eq!(live_report.dois_detected, 1);
    assert_eq!(live_report.arxiv_ids_detected, 1);

    let ray = &live_report.items[0];
    assert_eq!(ray.doi.as_deref(), Some("10.1145/2635868.2635922"));
    assert_eq!(ray.arxiv_id.as_deref(), Some("1408.1234"));
    assert_eq!(ray.url.as_deref(), Some("https://doi.org/10.1145/2635868.2635922"));
    assert!(ray.pdf_stored_path.is_some());

    let html_item = &live_report.items[1];
    assert_eq!(html_item.kind, EntryKind::Article);
    assert_eq!(html_item.url.as_deref(), Some("https://example.org/empirical-pl/"));
    assert!(html_item.content_html_bytes > 0);
    assert!(html_item.pdf_stored_path.is_none());

    let urlless = &live_report.items[2];
    assert!(urlless.url.is_none());
    assert!(urlless.pdf_stored_path.is_some());

    let ese_col =
        db.catalog().get_collection_summary("empirical_software_engineering").await.unwrap();
    assert_eq!(ese_col.member_count, 3);
    let wtr_col = db.catalog().get_collection_summary(WANT_TO_READ_COLLECTION_ID).await.unwrap();
    assert_eq!(wtr_col.member_count, 3);

    let all_ids: Vec<String> = live_report.items.iter().map(|i| i.entry_id.clone()).collect();
    let openable = db.items().reader_openable_ids(&all_ids).await.unwrap();
    assert_eq!(openable.len(), 3, "all 3 imported docs (including URL-less PDF) must be openable");

    let loaded_html_item = db.items().get(&html_item.entry_id).await.unwrap().unwrap();
    assert!(
        loaded_html_item
            .content_html
            .as_deref()
            .unwrap_or("")
            .contains("https://example.org/empirical-pl/study.html")
    );

    let loaded_ray_item = db.items().get(&ray.entry_id).await.unwrap().unwrap();
    assert_eq!(loaded_ray_item.content_type, ContentType::Pdf);
    let stored_disk_bytes =
        tokio::fs::read(media_dir.join(ray.pdf_stored_path.as_deref().unwrap())).await.unwrap();
    assert_eq!(stored_disk_bytes, pdf_bytes);
    assert!(
        db.media().find_orphaned().await.unwrap().is_empty(),
        "imported PDFs must not be treated as orphaned by MediaRepo::find_orphaned"
    );

    let delta = db
        .sync()
        .collect_peer_sync_response_with_budget(0, 100, 1024 * 1024, String::new(), Utc::now())
        .await
        .unwrap();
    for id in &all_ids {
        assert!(
            delta.entries.iter().any(|e| &e.id == id),
            "imported entry {id} must be exported in peer sync delta"
        );
    }

    let _ = tokio::fs::remove_dir_all(&temp_dir).await;
}

#[tokio::test]
async fn test_import_local_documents_idempotent_reimport_preserves_consumed_and_upgrades_offline() {
    let temp_dir =
        std::env::temp_dir().join(format!("roche-import-idemp-{}", uuid::Uuid::new_v4()));
    let docs_dir = temp_dir.join("docs");
    let media_dir = temp_dir.join("media");
    tokio::fs::create_dir_all(&docs_dir).await.unwrap();

    let stream = "\
BT\n\
/F1 16 Tf\n\
72 720 Td (On the Criteria To Be Used in Decomposing Systems into Modules) Tj\n\
/F1 11 Tf\n\
0 -24 Td (David L. Parnas) Tj\n\
ET\n";
    tokio::fs::write(docs_dir.join("parnas_1972.pdf"), build_test_pdf(stream)).await.unwrap();

    let manifest = LocalDocsImportManifest {
        base_dir: Some(docs_dir.clone()),
        collections: vec![LocalDocCollectionSpec {
            ref_key: Some("ese".to_owned()),
            title: "Empirical SE".to_owned(),
            slug: Some("empirical_se".to_owned()),
            role: CollectionRole::List,
            summary: None,
            color: None,
        }],
        documents: vec![LocalDocSpec {
            path: PathBuf::from("parnas_1972.pdf"),
            title: Some(
                "On the Criteria To Be Used in Decomposing Systems into Modules".to_owned(),
            ),
            creators: vec!["David L. Parnas".to_owned()],
            release_year: Some(1972),
            // Explicitly listing `want:read` in `collections` must ALSO obey `has_consumed`
            collections: vec!["ese".to_owned(), "want:read".to_owned()],
            position: Some(1.0),
            want_to_read: true,
            tags: vec!["Architecture".to_owned()],
            ..Default::default()
        }],
    };

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let media_store = MediaStore::new(&media_dir);

    let offline_opts = LocalDocsImportOptions {
        enrich: false,
        reflow_pdfs: false,
        dry_run: false,
        ..Default::default()
    };
    let r1 = import_local_documents_with_fetcher::<fn(&Url) -> Option<String>>(
        &db,
        &media_store,
        &manifest,
        &offline_opts,
        None,
    )
    .await
    .unwrap();
    assert_eq!(r1.entries_created, 1);
    assert_eq!(r1.pdf_media_stored, 1);
    assert!(r1.items[0].doi.is_none());
    let entry_id = r1.items[0].entry_id.clone();

    // User finishes reading the paper (auto-removes from Want to Read)
    db.catalog()
        .log_consumption(&entry_id, ConsumptionStatus::Consumed, Some("Classic"), None)
        .await
        .unwrap();
    assert_eq!(
        db.catalog().get_collection_summary(WANT_TO_READ_COLLECTION_ID).await.unwrap().member_count,
        0
    );

    // Re-import with online enrichment finding DOI -> upgrades SAME entry_id in-place and
    // must NOT re-add to Want to Read (even though `want:read` was also listed in `collections`!)
    let mock_fetcher = Arc::new(|url: &Url| -> Option<String> {
        if url.as_str().contains("api.crossref.org/works?query.bibliographic=") {
            Some(
                r#"{
                    "message": {
                        "items": [
                            {
                                "type": "journal-article",
                                "DOI": "10.1145/361598.361623",
                                "title": ["On the criteria to be used in decomposing systems into modules"],
                                "author": [{"given": "D. L.", "family": "Parnas"}],
                                "issued": {"date-parts": [[1972, 12]]}
                            }
                        ]
                    }
                }"#
                .to_owned(),
            )
        } else {
            None
        }
    });
    let enriched_opts = LocalDocsImportOptions {
        enrich: true,
        reflow_pdfs: false,
        dry_run: false,
        ..Default::default()
    };
    let r2 = import_local_documents_with_fetcher(
        &db,
        &media_store,
        &manifest,
        &enriched_opts,
        Some(Arc::clone(&mock_fetcher)),
    )
    .await
    .unwrap();
    assert_eq!(r2.entries_created, 0, "must upgrade offline entry in-place without duplicating");
    assert_eq!(r2.entries_updated, 1);
    assert_eq!(r2.items[0].entry_id, entry_id);
    assert_eq!(r2.items[0].doi.as_deref(), Some("10.1145/361598.361623"));
    assert_eq!(r2.items[0].url.as_deref(), Some("https://doi.org/10.1145/361598.361623"));
    assert!(!r2.items[0].added_to_want_to_read);
    assert_eq!(r2.pdf_media_reused, 1);
    assert_eq!(r2.pdf_media_stored, 0);
    assert_eq!(
        db.catalog().get_collection_summary(WANT_TO_READ_COLLECTION_ID).await.unwrap().member_count,
        0,
        "consumed entry must not be re-added to Want to Read on re-import"
    );

    // Second enriched re-import produces zero sync_seq churn
    let seq_before: i64 = sqlx::query_scalar("SELECT current_seq FROM sync_clock WHERE id = 1")
        .fetch_one(db.pool())
        .await
        .unwrap();
    let r3 = import_local_documents_with_fetcher(
        &db,
        &media_store,
        &manifest,
        &enriched_opts,
        Some(mock_fetcher),
    )
    .await
    .unwrap();
    let seq_after: i64 = sqlx::query_scalar("SELECT current_seq FROM sync_clock WHERE id = 1")
        .fetch_one(db.pool())
        .await
        .unwrap();
    assert_eq!(r3.pdf_media_reused, 1);
    assert_eq!(seq_before, seq_after, "idempotent re-import must not churn sync_seq");

    let _ = tokio::fs::remove_dir_all(&temp_dir).await;
}

#[tokio::test]
async fn test_reimport_clears_tombstones_refreshes_modified_html_upgrades_article_to_pdf_and_rejects_duplicates()
 {
    let temp_dir =
        std::env::temp_dir().join(format!("roche-import-edges-{}", uuid::Uuid::new_v4()));
    let docs_dir = temp_dir.join("docs");
    let media_dir = temp_dir.join("media");
    tokio::fs::create_dir_all(&docs_dir).await.unwrap();

    // 1. HTML modified between runs must overwrite stale `entry_content.content_html` AND clear tombstones
    let html_path = docs_dir.join("essay.html");
    tokio::fs::write(
        &html_path,
        "<html><head><title>Essay</title><link rel=\"canonical\" href=\"https://example.org/essay\"></head><body><article><p>Version one body prose.</p></article></body></html>",
    )
    .await
    .unwrap();

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let media_store = MediaStore::new(&media_dir);
    let opts = LocalDocsImportOptions::default();

    let html_manifest = LocalDocsImportManifest {
        base_dir: Some(docs_dir.clone()),
        documents: vec![LocalDocSpec { path: PathBuf::from("essay.html"), ..Default::default() }],
        ..Default::default()
    };
    let r1 = import_local_documents(&db, &media_store, &html_manifest, &opts).await.unwrap();
    let essay_id = r1.items[0].entry_id.clone();

    // Record realistic Item (`manual\0<url>`) and Entry tombstones as if the item was deleted
    {
        let mut tx = db.begin_write().await.unwrap();
        let nat_key = roche_core::identity::item_natural_key(None, "https://example.org/essay");
        crate::db::SyncRepo::record_tombstone(
            &mut tx,
            ActivityEntityType::Item,
            &nat_key,
            Utc::now(),
        )
        .await
        .unwrap();
        crate::db::SyncRepo::record_tombstone(
            &mut tx,
            ActivityEntityType::Entry,
            &essay_id,
            Utc::now(),
        )
        .await
        .unwrap();
        tx.commit().await.unwrap();
    }

    // Modify HTML content on disk and re-import
    tokio::fs::write(
        &html_path,
        "<html><head><title>Essay</title><link rel=\"canonical\" href=\"https://example.org/essay\"></head><body><article><p>Version two updated prose.</p></article></body></html>",
    )
    .await
    .unwrap();
    let r2 = import_local_documents(&db, &media_store, &html_manifest, &opts).await.unwrap();
    assert_eq!(r2.items[0].entry_id, essay_id);
    let updated_item = db.items().get(&essay_id).await.unwrap().unwrap();
    assert!(
        updated_item.content_html.as_deref().unwrap_or("").contains("Version two updated prose"),
        "re-importing modified HTML must refresh entry_content.content_html"
    );
    let remaining_tombs: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM sync_tombstones")
        .fetch_one(db.pool())
        .await
        .unwrap();
    assert_eq!(remaining_tombs, 0, "re-import must clear both Item and Entry tombstones");

    // 2. Previously saved web Article (`content_type = 'article'`) upgrades to `'pdf'` on PDF import
    let web_url = Url::parse("https://doi.org/10.1145/1234.5678").unwrap();
    let (saved_article, _) = db
        .items()
        .save_manual_input_with_tags(&ManualItemInput {
            url: &web_url,
            title: "Saved Publisher Page",
            summary: None,
            content_html: Some("<p>Publisher stub</p>"),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &[],
        })
        .await
        .unwrap();
    assert_eq!(saved_article.content_type, ContentType::Article);
    let hlc_before_upgrade: String = sqlx::query_scalar("SELECT hlc FROM entries WHERE id = ?")
        .bind(&saved_article.id)
        .fetch_one(db.pool())
        .await
        .unwrap();

    tokio::fs::write(
        docs_dir.join("camera_ready.pdf"),
        build_test_pdf("BT /F1 14 Tf 72 720 Td (Camera Ready Paper Full Prose Text) Tj ET"),
    )
    .await
    .unwrap();
    let pdf_upgrade_manifest = LocalDocsImportManifest {
        base_dir: Some(docs_dir.clone()),
        documents: vec![LocalDocSpec {
            path: PathBuf::from("camera_ready.pdf"),
            title: Some("Saved Publisher Page".to_owned()),
            // Passing DOI via `identifiers` (not top-level `doi`) must fold into hints & canonical URL!
            identifiers: vec![EntryIdentifier {
                scheme: "doi".to_owned(),
                value: "10.1145/1234.5678".to_owned(),
            }],
            ..Default::default()
        }],
        ..Default::default()
    };
    let r_up =
        import_local_documents(&db, &media_store, &pdf_upgrade_manifest, &opts).await.unwrap();
    assert_eq!(r_up.items[0].entry_id, saved_article.id);
    assert_eq!(r_up.items[0].doi.as_deref(), Some("10.1145/1234.5678"));
    let upgraded = db.items().get(&saved_article.id).await.unwrap().unwrap();
    assert_eq!(upgraded.content_type, ContentType::Pdf);
    let hlc_after_upgrade: String = sqlx::query_scalar("SELECT hlc FROM entries WHERE id = ?")
        .bind(&saved_article.id)
        .fetch_one(db.pool())
        .await
        .unwrap();
    assert!(
        hlc_after_upgrade > hlc_before_upgrade,
        "upgrading Article -> Pdf must advance entries.hlc so PeerSync propagates the upgrade"
    );

    // 3. Two distinct files resolving to the same entry_id in one manifest must conflict & roll back
    tokio::fs::write(
        docs_dir.join("copy_a.pdf"),
        build_test_pdf("BT /F1 12 Tf 72 720 Td (Duplicate Paper A) Tj ET"),
    )
    .await
    .unwrap();
    tokio::fs::write(
        docs_dir.join("copy_b.pdf"),
        build_test_pdf("BT /F1 12 Tf 72 720 Td (Duplicate Paper B) Tj ET"),
    )
    .await
    .unwrap();
    let dup_manifest = LocalDocsImportManifest {
        base_dir: Some(docs_dir.clone()),
        documents: vec![
            LocalDocSpec {
                path: PathBuf::from("copy_a.pdf"),
                title: Some("Same Paper".to_owned()),
                doi: Some("10.1145/9999.0001".to_owned()),
                ..Default::default()
            },
            LocalDocSpec {
                path: PathBuf::from("copy_b.pdf"),
                title: Some("Same Paper".to_owned()),
                doi: Some("10.1145/9999.0001".to_owned()),
                ..Default::default()
            },
        ],
        ..Default::default()
    };
    assert!(import_local_documents(&db, &media_store, &dup_manifest, &opts).await.is_err());

    // 4. Distinct URLs with identical title+author must NOT falsely merge into an earlier entry
    tokio::fs::write(
        docs_dir.join("notes_a.html"),
        "<html><head><title>Notes</title></head><body><p>Notes A</p></body></html>",
    )
    .await
    .unwrap();
    tokio::fs::write(
        docs_dir.join("notes_b.html"),
        "<html><head><title>Notes</title></head><body><p>Notes B</p></body></html>",
    )
    .await
    .unwrap();
    let m_a = LocalDocsImportManifest {
        base_dir: Some(docs_dir.clone()),
        documents: vec![LocalDocSpec {
            path: PathBuf::from("notes_a.html"),
            title: Some("Notes".to_owned()),
            creators: vec!["Alice".to_owned()],
            url: Some("https://a.example/notes".to_owned()),
            ..Default::default()
        }],
        ..Default::default()
    };
    let m_b = LocalDocsImportManifest {
        base_dir: Some(docs_dir.clone()),
        documents: vec![LocalDocSpec {
            path: PathBuf::from("notes_b.html"),
            title: Some("Notes".to_owned()),
            creators: vec!["Alice".to_owned()],
            url: Some("https://b.example/notes".to_owned()),
            ..Default::default()
        }],
        ..Default::default()
    };
    let ra = import_local_documents(&db, &media_store, &m_a, &opts).await.unwrap();
    let rb = import_local_documents(&db, &media_store, &m_b, &opts).await.unwrap();
    assert_ne!(
        ra.items[0].entry_id, rb.items[0].entry_id,
        "distinct URLs with same title/author must remain distinct entries"
    );

    // 5. Empty HTML with no URL must NOT report as reader-openable, while PDF Book MUST be openable
    tokio::fs::write(
        docs_dir.join("empty_body.html"),
        "<html><head><title>Blank</title></head><body></body></html>",
    )
    .await
    .unwrap();
    tokio::fs::write(
        docs_dir.join("textbook.pdf"),
        build_test_pdf("BT /F1 14 Tf 72 720 Td (Systems Engineering Textbook) Tj ET"),
    )
    .await
    .unwrap();
    let openable_manifest = LocalDocsImportManifest {
        base_dir: Some(docs_dir),
        documents: vec![
            LocalDocSpec { path: PathBuf::from("empty_body.html"), ..Default::default() },
            LocalDocSpec {
                path: PathBuf::from("textbook.pdf"),
                kind: Some(EntryKind::Book),
                title: Some("Systems Engineering Textbook".to_owned()),
                ..Default::default()
            },
        ],
        ..Default::default()
    };
    let r_open =
        import_local_documents(&db, &media_store, &openable_manifest, &opts).await.unwrap();
    let empty_html_id = &r_open.items[0].entry_id;
    let pdf_book_id = &r_open.items[1].entry_id;
    let openable_set = db
        .items()
        .reader_openable_ids(&[empty_html_id.clone(), pdf_book_id.clone()])
        .await
        .unwrap();
    assert!(
        !openable_set.contains(empty_html_id),
        "URL-less HTML with empty content_html must not be reported as reader-openable"
    );
    assert!(
        openable_set.contains(pdf_book_id),
        "local PDF Book with cached PDF media must be reader-openable"
    );

    let _ = tokio::fs::remove_dir_all(&temp_dir).await;
}

#[tokio::test]
async fn test_import_local_documents_validation_rejects_bad_inputs() {
    let temp_dir = std::env::temp_dir().join(format!("roche-import-val-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&temp_dir).await.unwrap();
    tokio::fs::write(temp_dir.join("notes.md"), b"# Markdown").await.unwrap();
    tokio::fs::write(temp_dir.join("fake.pdf"), b"not actually a pdf").await.unwrap();
    tokio::fs::write(temp_dir.join("real.pdf"), build_test_pdf("BT /F1 12 Tf (Hi) Tj ET"))
        .await
        .unwrap();

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let media_store = MediaStore::new(temp_dir.join("media"));
    let opts = LocalDocsImportOptions::default();

    // Unsupported extension (.md)
    let bad_ext = LocalDocsImportManifest {
        base_dir: Some(temp_dir.clone()),
        documents: vec![LocalDocSpec { path: PathBuf::from("notes.md"), ..Default::default() }],
        ..Default::default()
    };
    assert!(import_local_documents(&db, &media_store, &bad_ext, &opts).await.is_err());

    // Bad PDF header
    let bad_pdf = LocalDocsImportManifest {
        base_dir: Some(temp_dir.clone()),
        documents: vec![LocalDocSpec { path: PathBuf::from("fake.pdf"), ..Default::default() }],
        ..Default::default()
    };
    assert!(import_local_documents(&db, &media_store, &bad_pdf, &opts).await.is_err());

    // Disallowed URL schemes / synthetic hosts / private IPs
    for bad_url in
        ["local-pdf://abc", "https://roche.local/doc", "http://127.0.0.1/doc", "file:///etc/passwd"]
    {
        let m = LocalDocsImportManifest {
            base_dir: Some(temp_dir.clone()),
            documents: vec![LocalDocSpec {
                path: PathBuf::from("real.pdf"),
                url: Some(bad_url.to_owned()),
                ..Default::default()
            }],
            ..Default::default()
        };
        assert!(
            import_local_documents(&db, &media_store, &m, &opts).await.is_err(),
            "expected URL '{bad_url}' to be rejected"
        );
    }

    // Non-object metadata rejected
    let bad_meta = LocalDocsImportManifest {
        base_dir: Some(temp_dir.clone()),
        documents: vec![LocalDocSpec {
            path: PathBuf::from("real.pdf"),
            metadata: Some(serde_json::json!(["not", "an", "object"])),
            ..Default::default()
        }],
        ..Default::default()
    };
    assert!(import_local_documents(&db, &media_store, &bad_meta, &opts).await.is_err());

    // Reserved identifier scheme and private URL inside `identifiers` rejected
    let bad_ident = LocalDocsImportManifest {
        base_dir: Some(temp_dir.clone()),
        documents: vec![LocalDocSpec {
            path: PathBuf::from("real.pdf"),
            identifiers: vec![EntryIdentifier {
                scheme: "slug".to_owned(),
                value: "hijack".to_owned(),
            }],
            ..Default::default()
        }],
        ..Default::default()
    };
    assert!(import_local_documents(&db, &media_store, &bad_ident, &opts).await.is_err());

    let bad_url_ident = LocalDocsImportManifest {
        base_dir: Some(temp_dir.clone()),
        documents: vec![LocalDocSpec {
            path: PathBuf::from("real.pdf"),
            identifiers: vec![EntryIdentifier {
                scheme: "url".to_owned(),
                value: "http://127.0.0.1/private".to_owned(),
            }],
            ..Default::default()
        }],
        ..Default::default()
    };
    assert!(import_local_documents(&db, &media_store, &bad_url_ident, &opts).await.is_err());

    // Collection kind rejected
    let bad_kind = LocalDocsImportManifest {
        base_dir: Some(temp_dir.clone()),
        documents: vec![LocalDocSpec {
            path: PathBuf::from("real.pdf"),
            kind: Some(EntryKind::Collection),
            ..Default::default()
        }],
        ..Default::default()
    };
    assert!(import_local_documents(&db, &media_store, &bad_kind, &opts).await.is_err());

    let _ = tokio::fs::remove_dir_all(&temp_dir).await;
}

#[test]
fn test_is_retryable_fetch_error_retries_rate_limits_and_skips_4xx() {
    assert!(is_retryable_fetch_error("Upstream server returned HTTP 429 Too Many Requests"));
    assert!(is_retryable_fetch_error("Upstream server returned HTTP 408 Request Timeout"));
    assert!(is_retryable_fetch_error("Upstream server returned HTTP 503 Service Unavailable"));
    assert!(is_retryable_fetch_error("error sending request: operation timed out"));
    assert!(!is_retryable_fetch_error("Upstream server returned HTTP 404 Not Found"));
    assert!(!is_retryable_fetch_error("Upstream server returned HTTP 403 Forbidden"));
    assert!(!is_retryable_fetch_error("Upstream server returned HTTP 400 Bad Request"));
    assert!(!is_retryable_fetch_error("Host resolved to disallowed IP address"));
}
