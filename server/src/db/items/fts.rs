use sqlx::{QueryBuilder, Sqlite};

use super::{ItemFilter, ItemRepo};

const MAX_SEARCH_TERMS: usize = 16;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchTerm {
    pub fts_clause: String,
    pub like_pattern: String,
}

impl ItemFilter {
    #[must_use]
    pub fn tokenize_search_terms(input: &str) -> Vec<SearchTerm> {
        let mut terms = Vec::new();
        let mut chars = input.chars().peekable();

        while let Some(&ch) = chars.peek() {
            if ch == '"' {
                chars.next();
                let mut phrase_buf = String::new();
                let mut closed = false;
                for next_ch in chars.by_ref() {
                    if next_ch == '"' {
                        closed = true;
                        break;
                    }
                    phrase_buf.push(next_ch);
                }
                let words = Self::extract_word_tokens(&phrase_buf);
                if closed && words.len() > 1 {
                    let joined = words.join(" ");
                    terms.push(SearchTerm {
                        fts_clause: format!("\"{joined}\""),
                        like_pattern: format!("%{}%", crate::db::escape_like_literal(&joined)),
                    });
                } else {
                    for word in words {
                        terms.push(SearchTerm {
                            fts_clause: format!("(\"{word}\" OR \"{word}\"*)"),
                            like_pattern: format!("%{}%", crate::db::escape_like_literal(word)),
                        });
                    }
                }
            } else {
                let mut unquoted_buf = String::new();
                while let Some(&next_ch) = chars.peek() {
                    if next_ch == '"' {
                        break;
                    }
                    unquoted_buf.push(next_ch);
                    chars.next();
                }
                for word in Self::extract_word_tokens(&unquoted_buf) {
                    terms.push(SearchTerm {
                        fts_clause: format!("(\"{word}\" OR \"{word}\"*)"),
                        like_pattern: format!("%{}%", crate::db::escape_like_literal(word)),
                    });
                }
            }
        }

        terms.truncate(MAX_SEARCH_TERMS);
        terms
    }

    #[must_use]
    pub fn build_fts_query(input: &str) -> Option<String> {
        let terms = Self::tokenize_search_terms(input);
        if terms.is_empty() {
            None
        } else {
            Some(terms.into_iter().map(|t| t.fts_clause).collect::<Vec<_>>().join(" AND "))
        }
    }

    fn extract_word_tokens(segment: &str) -> Vec<&str> {
        segment
            .trim_start_matches('#')
            .split(|c: char| !c.is_alphanumeric())
            .filter(|tok| !tok.is_empty())
            .collect()
    }
}

impl ItemRepo<'_> {
    pub(super) fn append_search_where_clause(
        qb: &mut QueryBuilder<Sqlite>,
        raw_search: &str,
        search_terms: &[SearchTerm],
    ) {
        if search_terms.is_empty() {
            let pattern = format!("%{}%", crate::db::escape_like_literal(raw_search));
            qb.push(" AND (i.title LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(i.summary, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(i.creators_json, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(ec.content_html, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM collection_entries ce_fs JOIN feed_sources fs_s ON fs_s.collection_id = ce_fs.collection_id WHERE ce_fs.entry_id = i.id AND COALESCE(fs_s.feed_title, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\') OR EXISTS (SELECT 1 FROM collection_entries ce_ts JOIN collection_specs cs_ts ON cs_ts.collection_id = ce_ts.collection_id JOIN entries e_ts ON e_ts.id = ce_ts.collection_id WHERE ce_ts.entry_id = i.id AND cs_ts.collection_role = 'tag' AND e_ts.title LIKE ")
                .push_bind(pattern)
                .push(" ESCAPE '\\'))");
        } else if let [term] = search_terms {
            qb.push(" AND (fts.fts_rowid IS NOT NULL OR EXISTS (SELECT 1 FROM collection_entries ce_fs JOIN feed_sources fs_s ON fs_s.collection_id = ce_fs.collection_id WHERE ce_fs.entry_id = i.id AND COALESCE(fs_s.feed_title, '') LIKE ")
                .push_bind(term.like_pattern.clone())
                .push(" ESCAPE '\\') OR EXISTS (SELECT 1 FROM collection_entries ce_ts JOIN collection_specs cs_ts ON cs_ts.collection_id = ce_ts.collection_id JOIN entries e_ts ON e_ts.id = ce_ts.collection_id WHERE ce_ts.entry_id = i.id AND cs_ts.collection_role = 'tag' AND e_ts.title LIKE ")
                .push_bind(term.like_pattern.clone())
                .push(" ESCAPE '\\'))");
        } else {
            qb.push(" AND (fts.fts_rowid IS NOT NULL OR (");
            for (idx, term) in search_terms.iter().enumerate() {
                if idx > 0 {
                    qb.push(" AND ");
                }
                qb.push("(i.rowid IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ")
                    .push_bind(term.fts_clause.clone())
                    .push(") OR EXISTS (SELECT 1 FROM collection_entries ce_fs JOIN feed_sources fs_s ON fs_s.collection_id = ce_fs.collection_id WHERE ce_fs.entry_id = i.id AND COALESCE(fs_s.feed_title, '') LIKE ")
                    .push_bind(term.like_pattern.clone())
                    .push(" ESCAPE '\\') OR EXISTS (SELECT 1 FROM collection_entries ce_ts JOIN collection_specs cs_ts ON cs_ts.collection_id = ce_ts.collection_id JOIN entries e_ts ON e_ts.id = ce_ts.collection_id WHERE ce_ts.entry_id = i.id AND cs_ts.collection_role = 'tag' AND e_ts.title LIKE ")
                    .push_bind(term.like_pattern.clone())
                    .push(" ESCAPE '\\'))");
            }
            qb.push("))");
        }
    }
}
