use std::{
    collections::HashMap,
    fmt::{self, Write as _},
    str::FromStr,
};

use axum::{
    http::{HeaderValue, StatusCode, header},
    response::{Html, IntoResponse, Response},
};
use serde::Deserialize;

use super::{auth::WebSession, links::render_creator_links};
use crate::{
    db::{CatalogError, ConflictError, ConsumptionLogView},
    domain::{ConsumptionStatus, ContentType, FeedType},
    service::AppError,
    util::url_guard::SsrfBlockedError,
};

/// HTML-escapes interpolated text (`&`, `<`, `>`, `"`, `'`) with zero intermediate heap allocation.
#[derive(Debug, Clone, Copy)]
pub(super) struct Esc<'a>(pub &'a str);

impl fmt::Display for Esc<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for ch in self.0.chars() {
            match ch {
                '&' => f.write_str("&amp;")?,
                '<' => f.write_str("&lt;")?,
                '>' => f.write_str("&gt;")?,
                '"' => f.write_str("&quot;")?,
                '\'' => f.write_str("&#39;")?,
                other => f.write_char(other)?,
            }
        }
        Ok(())
    }
}

#[must_use]
pub(super) const fn esc(s: &str) -> Esc<'_> {
    Esc(s)
}

#[must_use]
pub(super) fn urlencode(raw: &str) -> String {
    url::form_urlencoded::byte_serialize(raw.as_bytes()).collect()
}

/// Validates a relative redirect target so user-supplied `return_to` values cannot trigger open redirects.
#[must_use]
pub(super) fn safe_local_redirect(candidate: Option<&str>, fallback: &str) -> String {
    if let Some(raw) = candidate.map(str::trim)
        && raw.starts_with('/')
        && !raw.starts_with("//")
        && !raw.contains('\\')
        && raw.is_ascii()
        && !raw.bytes().any(|b| b < 0x20 || b == 0x7f)
    {
        return raw.to_owned();
    }
    fallback.to_owned()
}

pub(super) fn redirect_see_other(location: &str) -> Response {
    let safe_loc = safe_local_redirect(Some(location), "/");
    let mut resp = StatusCode::SEE_OTHER.into_response();
    let val = HeaderValue::from_str(&safe_loc).unwrap_or_else(|_| HeaderValue::from_static("/"));
    resp.headers_mut().insert(header::LOCATION, val);
    resp
}

pub(super) fn redirect_with_flash(base: &str, param: &str, msg: &str) -> Response {
    let safe_base = safe_local_redirect(Some(base), "/");
    let (path_and_query, fragment) = match safe_base.split_once('#') {
        Some((before, frag)) => (before, Some(frag)),
        None => (safe_base.as_str(), None),
    };
    let sep = if path_and_query.contains('?') { '&' } else { '?' };
    let mut loc = format!("{path_and_query}{sep}{param}={}", urlencode(msg));
    if let Some(frag) = fragment {
        loc.push('#');
        loc.push_str(frag);
    }
    redirect_see_other(&loc)
}

#[must_use]
pub(super) fn parse_csv_list(raw: Option<&str>) -> Vec<String> {
    raw.map_or_default(|s| {
        s.split(',').map(str::trim).filter(|c| !c.is_empty()).map(ToOwned::to_owned).collect()
    })
}

/// Parses an HTML form boolean field (`"1"`, `"true"`, `"on"`, case-insensitive).
#[must_use]
pub(super) fn parse_form_bool(raw: Option<&str>) -> bool {
    raw.map(str::trim)
        .is_some_and(|v| v == "1" || v.eq_ignore_ascii_case("true") || v.eq_ignore_ascii_case("on"))
}

/// Deserializes a query boolean (`true`/`false`/`1`/`0`) safely inside `#[serde(flatten)]`
/// structs where `serde_urlencoded` delivers values as strings via `FlatMapDeserializer`.
pub(super) fn de_query_bool<'de, D>(deserializer: D) -> Result<bool, D::Error>
where
    D: serde::Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum BoolOrStr {
        Bool(bool),
        Str(String),
    }

    match Option::<BoolOrStr>::deserialize(deserializer)? {
        None => Ok(false),
        Some(BoolOrStr::Bool(b)) => Ok(b),
        Some(BoolOrStr::Str(s)) => match s.trim() {
            "true" | "1" => Ok(true),
            "false" | "0" | "" => Ok(false),
            other => {
                Err(serde::de::Error::custom(format!("invalid boolean query value '{other}'")))
            }
        },
    }
}

/// Deserializes an optional `FromStr` query parameter (e.g. `Option<usize>`, `Option<i32>`)
/// safely inside `#[serde(flatten)]` structs under `serde_urlencoded`.
pub(super) fn de_opt_from_str<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: FromStr,
    T::Err: fmt::Display,
{
    Option::<String>::deserialize(deserializer)?.map_or_else(
        || Ok(None),
        |s| {
            let trimmed = s.trim();
            if trimmed.is_empty() {
                Ok(None)
            } else {
                trimmed.parse::<T>().map(Some).map_err(serde::de::Error::custom)
            }
        },
    )
}

#[must_use]
pub(super) fn rank_collection_href(slug: &str, want: bool) -> String {
    let normalized = if want { slug.strip_prefix("want:").unwrap_or(slug) } else { slug };
    if want {
        format!("/rank/{}?want=true", urlencode(normalized))
    } else {
        format!("/rank/{}", urlencode(normalized))
    }
}

pub(super) const ARCHIVE_BOX_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.45\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"2\" y=\"2.5\" width=\"12\" height=\"3\" rx=\"0.75\"/><path d=\"M3 5.5v7a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1v-7\"/><path d=\"M6.25 8.75h3.5\"/></svg>";

#[must_use]
pub(super) const fn content_type_label(ct: ContentType) -> &'static str {
    match ct {
        ContentType::Article => "Article",
        ContentType::Video => "Video",
        ContentType::Paper => "Paper",
        ContentType::Audio => "Audio",
        ContentType::Pdf => "PDF",
    }
}

#[must_use]
pub(super) const fn content_type_badge_class(ct: ContentType) -> &'static str {
    match ct {
        ContentType::Article => "",
        ContentType::Audio => " badge-podcast",
        ContentType::Paper => " badge-paper",
        ContentType::Pdf => " badge-pdf",
        ContentType::Video => " badge-video",
    }
}

pub(super) fn render_feed_favicon(
    out: &mut String,
    raw_url: Option<&str>,
    label: &str,
    small: bool,
) {
    let host = raw_url
        .and_then(|u| url::Url::parse(u).ok())
        .and_then(|u| u.host_str().map(str::to_ascii_lowercase))
        .filter(|h| !h.is_empty());
    let seed = host.as_deref().unwrap_or(label);
    let letter = label
        .chars()
        .chain(seed.chars())
        .find(|c| c.is_alphanumeric())
        .map_or('?', |c| c.to_ascii_uppercase());
    let tone = seed.bytes().fold(0usize, |acc, b| acc.wrapping_add(usize::from(b))) % 5;
    let sz_cls = if small { " feed-favicon-sm" } else { "" };
    let _ = write!(
        out,
        "<span class=\"feed-favicon{sz_cls} fav-tone-{tone}\" aria-hidden=\"true\">{letter}"
    );
    if let Some(h) = host {
        let _ = write!(
            out,
            "<img src=\"https://icons.duckduckgo.com/ip3/{}.ico\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\">",
            esc(&h),
        );
    }
    out.push_str("</span>");
}

#[must_use]
pub(super) const fn feed_type_label(ft: FeedType) -> &'static str {
    match ft {
        FeedType::Rss => "RSS",
        FeedType::Podcast => "Podcast",
        FeedType::Youtube => "YouTube",
        FeedType::Arxiv => "arXiv",
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum CreatorYearStyle {
    /// Inline ` <span class="text-soft">&mdash; ...</span>` + optional ` <span class="text-faint">(YYYY)</span>`.
    InlineSoft { open_new_tab: bool },
    /// Ranked table cell `<span class="by">...</span>` + optional `<span class="year">(YYYY)</span>`.
    TableByYear,
    /// Inline Want queue ` <span class="by">&mdash; ...</span>`.
    QueueDash,
    /// Draw sleeve block `<div class="album-artist">... (YYYY)</div>\n`.
    DrawArtistBlock,
}

/// Renders creator links and optional release year consistently across all catalog views.
pub(super) fn render_creator_year_meta(
    out: &mut String,
    creators: &[String],
    release_year: Option<i32>,
    artist_links: &HashMap<String, String>,
    style: CreatorYearStyle,
) {
    match style {
        CreatorYearStyle::InlineSoft { open_new_tab } => {
            if !creators.is_empty() {
                out.push_str(" <span class=\"text-soft\">&mdash; ");
                render_creator_links(out, creators, artist_links, open_new_tab);
                out.push_str("</span>");
            }
            if let Some(yr) = release_year {
                let _ = write!(out, " <span class=\"text-faint\">({yr})</span>");
            }
        }
        CreatorYearStyle::TableByYear => {
            if !creators.is_empty() {
                out.push_str("<span class=\"by\">");
                render_creator_links(out, creators, artist_links, false);
                out.push_str("</span>");
            }
            if let Some(yr) = release_year {
                let _ = write!(out, "<span class=\"year\">({yr})</span>");
            }
        }
        CreatorYearStyle::QueueDash => {
            if !creators.is_empty() {
                out.push_str(" <span class=\"by\">&mdash; ");
                render_creator_links(out, creators, artist_links, false);
                out.push_str("</span>");
            }
        }
        CreatorYearStyle::DrawArtistBlock => {
            if !creators.is_empty() || release_year.is_some() {
                out.push_str("<div class=\"album-artist\">");
                render_creator_links(out, creators, artist_links, false);
                if let Some(yr) = release_year {
                    if creators.is_empty() {
                        let _ = write!(out, "{yr}");
                    } else {
                        let _ = write!(out, " <span class=\"album-year\">({yr})</span>");
                    }
                }
                out.push_str("</div>\n");
            }
        }
    }
}

pub(super) fn render_inline_csrf_form(
    out: &mut String,
    action: &str,
    csrf: &str,
    hidden_fields: &[(&str, &str)],
    confirm: Option<&str>,
    button_html: &str,
) {
    render_inline_csrf_form_with_class(
        out,
        action,
        csrf,
        None,
        hidden_fields,
        confirm,
        button_html,
    );
}

pub(super) fn render_inline_csrf_form_with_class(
    out: &mut String,
    action: &str,
    csrf: &str,
    extra_class: Option<&str>,
    hidden_fields: &[(&str, &str)],
    confirm: Option<&str>,
    button_html: &str,
) {
    let class_attr = extra_class
        .map_or_else(|| "inline-form".to_owned(), |extra| format!("inline-form {extra}"));
    let confirm_attr =
        confirm.map_or_else(String::new, |msg| format!(" data-confirm=\"{}\"", esc(msg)));
    let _ = write!(
        out,
        "<form method=\"post\" action=\"{}\" class=\"{}\"{confirm_attr}>\
           <input type=\"hidden\" name=\"_csrf\" value=\"{}\">",
        esc(action),
        esc(&class_attr),
        esc(csrf),
    );
    for (name, value) in hidden_fields {
        let _ =
            write!(out, "<input type=\"hidden\" name=\"{}\" value=\"{}\">", esc(name), esc(value));
    }
    out.push_str(button_html);
    out.push_str("</form>");
}

pub(super) fn render_diary_row(
    out: &mut String,
    row: &ConsumptionLogView,
    artist_links: &HashMap<String, String>,
    show_notes: bool,
) {
    render_diary_row_with_tier(out, row, artist_links, show_notes, None);
}

pub(super) fn render_diary_row_with_tier(
    out: &mut String,
    row: &ConsumptionLogView,
    artist_links: &HashMap<String, String>,
    show_notes: bool,
    tier: Option<char>,
) {
    let _ = write!(
        out,
        "<div class=\"diary-row\">\
           <span class=\"date\">{}</span>\
           <span class=\"diary-title\"><a href=\"/entries/{}\">{}</a>",
        row.ts.format("%b %d"),
        esc(&urlencode(&row.entry.id)),
        esc(&row.entry.title),
    );
    render_creator_year_meta(
        out,
        &row.entry.creators,
        None,
        artist_links,
        CreatorYearStyle::InlineSoft { open_new_tab: false },
    );
    if row.status != ConsumptionStatus::Consumed {
        let _ = write!(out, " <span class=\"status-tag\">({})</span>", row.status.as_str());
    }
    out.push_str("</span><span class=\"diary-meta\">");
    if let Some(t) = tier {
        let _ = write!(
            out,
            "<span class=\"tier-pill tier-{t}\" title=\"Tier {t}\" aria-label=\"Tier {t}\">{t}</span>"
        );
    }
    let _ =
        write!(out, "<span class=\"kind\">{}</span></span>", esc(row.entry.kind.display_label()));
    if show_notes && let Some(notes) = row.notes.as_deref().filter(|s| !s.is_empty()) {
        let _ = writeln!(out, "<div class=\"notes\">{}</div>", esc(notes));
    }
    out.push_str("</div>\n");
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum NavSection {
    Home,
    Inbox,
    Starred,
    Feeds,
    Lists,
    Rank,
    Diary,
    Queue,
    None,
}

impl NavSection {
    const fn default_path(self) -> &'static str {
        match self {
            Self::Home | Self::None => "/",
            Self::Inbox => "/inbox",
            Self::Starred => "/starred",
            Self::Feeds => "/feeds",
            Self::Lists => "/lists",
            Self::Queue => "/queue",
            Self::Diary => "/diary",
            Self::Rank => "/rank",
        }
    }
}

#[derive(Debug, Default, Clone, Deserialize)]
pub(super) struct FlashQuery {
    #[serde(default)]
    pub notice: Option<String>,
    #[serde(default)]
    pub err: Option<String>,
}

pub(super) struct PageShell<'a> {
    pub title: &'a str,
    pub active: NavSection,
    pub csrf_token: &'a str,
    pub flash: &'a FlashQuery,
    pub is_refreshing: bool,
    pub body_html: &'a str,
}

const NAV_ITEMS: [(NavSection, &str, &str); 8] = [
    (NavSection::Home, "/", "Home"),
    (NavSection::Inbox, "/inbox", "Inbox"),
    (NavSection::Starred, "/starred", "Starred"),
    (NavSection::Feeds, "/feeds", "Feeds"),
    (NavSection::Lists, "/lists", "Lists"),
    (NavSection::Queue, "/queue", "Queue"),
    (NavSection::Diary, "/diary", "Diary"),
    (NavSection::Rank, "/rank", "Rank"),
];

pub(super) fn render_page(shell: &PageShell<'_>) -> Html<String> {
    let mut out = String::with_capacity(shell.body_html.len() + 3_200);
    let page_title = if shell.title.is_empty() || shell.title == "Roche" {
        "Roche".to_owned()
    } else {
        format!("{} — Roche", shell.title)
    };

    let _ = write!(
        out,
        "<!doctype html>\n\
         <html lang=\"en\">\n\
         <head>\n\
           <meta charset=\"utf-8\">\n\
           <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n\
           <title>{title}</title>\n\
           <link rel=\"stylesheet\" href=\"/static/roche.css\">\n\
         </head>\n\
         <body data-csrf=\"{csrf}\">\n\
         <a class=\"skip-link\" href=\"#roche-main\">Skip to content</a>\n",
        title = esc(&page_title),
        csrf = esc(shell.csrf_token),
    );

    render_masthead(&mut out, shell);
    out.push_str("<main id=\"roche-main\" tabindex=\"-1\">\n");

    if let Some(msg) = shell.flash.err.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let _ =
            writeln!(out, "<div class=\"notice notice-error\" role=\"alert\">{}</div>", esc(msg));
    } else if let Some(msg) = shell.flash.notice.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        let _ = writeln!(out, "<div class=\"notice\" role=\"status\">{}</div>", esc(msg));
    }

    out.push_str(shell.body_html);

    let _ = write!(
        out,
        "\n</main>\n\
         <footer class=\"colophon\">\n\
           <span class=\"colophon-brand\"><span aria-hidden=\"true\">&#10086;</span> <span>Roche &middot; Reader, Podcasts &amp; Ranked Library</span></span>\n\
           <form class=\"logout-form\" method=\"post\" action=\"/logout\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <button type=\"submit\" class=\"link-btn\">Sign out</button>\n\
           </form>\n\
         </footer>\n\
         <aside id=\"roche-player-bar\" class=\"player-bar\" aria-label=\"Audio player\" hidden>\n\
           <div class=\"player-inner\">\n\
             <div class=\"player-meta\">\n\
               <div id=\"roche-player-title\" class=\"player-title\">Episode</div>\n\
               <div id=\"roche-player-sub\" class=\"player-sub\">Podcast</div>\n\
             </div>\n\
             <audio id=\"roche-audio\" controls preload=\"metadata\" aria-label=\"Podcast audio player\"></audio>\n\
             <button type=\"button\" id=\"roche-player-speed\" class=\"btn btn-small\" aria-label=\"Playback speed: 1x\">1x</button>\n\
             <a id=\"roche-player-link\" href=\"/\" class=\"btn btn-small\" aria-label=\"Open episode notes\">Notes</a>\n\
             <button type=\"button\" id=\"roche-player-close\" class=\"btn btn-small btn-ghost\" aria-label=\"Close player\"><span aria-hidden=\"true\">&times;</span></button>\n\
           </div>\n\
         </aside>\n\
         <script src=\"/static/roche.js\" defer></script>\n\
         </body>\n\
         </html>\n",
        csrf = esc(shell.csrf_token),
    );

    Html(out)
}

fn render_masthead(out: &mut String, shell: &PageShell<'_>) {
    let refresh_return = shell.active.default_path();
    // TODO(backend): point global masthead search to unified items+catalog-entries search endpoint
    let _ = write!(
        out,
        "<header class=\"masthead\" id=\"roche-masthead\">\n\
           <div class=\"masthead-top\">\n\
             <h1><a href=\"/\">Roche</a></h1>\n\
             <form method=\"get\" action=\"/inbox\" class=\"masthead-search\" id=\"roche-masthead-search\" role=\"search\">\n\
               <input type=\"hidden\" name=\"status\" value=\"all\">\n\
               <svg class=\"icon search-icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" aria-hidden=\"true\"><circle cx=\"7\" cy=\"7\" r=\"4.5\"/><path d=\"M10.5 10.5L14 14\" stroke-linecap=\"round\"/></svg>\n\
               <input type=\"search\" name=\"q\" id=\"roche-global-search\" class=\"masthead-search-input\" placeholder=\"Search…\" aria-label=\"Search Roche\">\n\
               <kbd class=\"search-kbd\" aria-hidden=\"true\">/</kbd>\n\
             </form>\n\
             <div class=\"masthead-utils\">\n\
               <button type=\"button\" id=\"roche-search-toggle\" class=\"util-btn masthead-search-toggle\" aria-label=\"Search\" aria-expanded=\"false\" aria-controls=\"roche-masthead-search\">\n\
                 <svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" aria-hidden=\"true\"><circle cx=\"7\" cy=\"7\" r=\"4.5\"/><path d=\"M10.5 10.5L14 14\" stroke-linecap=\"round\"/></svg>\n\
               </button>\n\
               <form id=\"roche-masthead-refresh\" class=\"inline-form\" method=\"post\" action=\"/web/feeds/refresh-all\">\n\
                 <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
                 <input type=\"hidden\" name=\"return_to\" value=\"{refresh_return}\">\n\
                 <button type=\"submit\" class=\"util-btn\" title=\"Refresh all feeds\" aria-label=\"Refresh all feeds\">\n\
                   <svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" aria-hidden=\"true\"><path d=\"M13.5 8a5.5 5.5 0 1 1-1.4-3.7\" stroke-linecap=\"round\"/><path d=\"M13.5 2.5v2.8h-2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>\n\
                   <span class=\"util-label\">Refresh</span>\n\
                 </button>\n\
               </form>\n\
               <button type=\"button\" id=\"roche-theme-toggle\" class=\"util-btn\" title=\"Switch color theme\" aria-label=\"Switch color theme\">\n\
                 <svg class=\"icon icon-moon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.45\" aria-hidden=\"true\"><path d=\"M13.2 9.9A5.5 5.5 0 0 1 6.1 2.8a5.5 5.5 0 1 0 7.1 7.1Z\" stroke-linejoin=\"round\"/></svg>\n\
                 <svg class=\"icon icon-sun\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.45\" aria-hidden=\"true\"><circle cx=\"8\" cy=\"8\" r=\"3\"/><path d=\"M8 1.5v1.6M8 12.9v1.6M1.5 8h1.6M12.9 8h1.6M3.4 3.4l1.1 1.1M11.5 11.5l1.1 1.1M12.6 3.4l-1.1 1.1M4.5 11.5l-1.1 1.1\" stroke-linecap=\"round\"/></svg>\n\
               </button>\n\
             </div>\n\
           </div>\n\
           <nav id=\"roche-nav\" class=\"primary-nav\" aria-label=\"Primary\">",
        csrf = esc(shell.csrf_token),
        refresh_return = esc(refresh_return),
    );
    for (sec, href, label) in NAV_ITEMS {
        let attrs =
            if sec == shell.active { " class=\"is-active\" aria-current=\"page\"" } else { "" };
        let _ = write!(out, "<a href=\"{href}\"{attrs}>{label}</a>");
    }
    let refresh_hidden = if shell.is_refreshing { "" } else { " hidden" };
    let refresh_active = if shell.is_refreshing { "1" } else { "0" };
    let _ = write!(
        out,
        "</nav>\n\
         <div id=\"roche-refresh-banner\" class=\"refresh-banner\" data-active=\"{refresh_active}\" role=\"status\" aria-live=\"polite\"{refresh_hidden}>\n\
           <span class=\"refresh-spinner\" aria-hidden=\"true\">&#8635;</span>\n\
           <span class=\"js-refresh-label\">Refreshing feeds &amp; metadata in background&hellip;</span>\n\
         </div>\n\
         </header>\n",
    );
}

pub(super) fn render_login_page(
    error_msg: Option<&str>,
    tailscale_enabled: bool,
    tailscale_user: Option<&str>,
) -> Html<String> {
    let mut out = String::with_capacity(2_560);
    out.push_str(
        "<!doctype html>\n\
         <html lang=\"en\">\n\
         <head>\n\
           <meta charset=\"utf-8\">\n\
           <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n\
           <title>Sign in — Roche</title>\n\
           <link rel=\"stylesheet\" href=\"/static/roche.css\">\n\
         </head>\n\
         <body>\n\
         <header class=\"masthead\">\n\
           <div class=\"masthead-top\"><h1>Roche</h1></div>\n\
         </header>\n\
         <main>\n\
         <div class=\"login-box\">\n\
           <h2>Pair Browser</h2>\n\
           <p class=\"lede\">Run <code>roche pair</code> on your server and enter the one-time pairing code below.</p>\n",
    );

    let trimmed_err = error_msg.map(str::trim).filter(|s| !s.is_empty());
    if let Some(err) = trimmed_err {
        let _ = writeln!(
            out,
            "<div id=\"login-error\" class=\"notice notice-error\" role=\"alert\">{}</div>",
            esc(err)
        );
    }

    if let Some(ts_login) = tailscale_user.map(str::trim).filter(|s| !s.is_empty()) {
        let _ = writeln!(
            out,
            "<form method=\"post\" action=\"/login/tailscale\" class=\"mb-lg\">\n\
               <button type=\"submit\" class=\"btn btn-primary w-full\">Sign in as {}</button>\n\
             </form>\n",
            esc(ts_login)
        );
    }

    let pairing_attrs = if trimmed_err.is_some() {
        "aria-invalid=\"true\" aria-describedby=\"login-error\""
    } else {
        "autofocus"
    };
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/login\">\n\
           <div class=\"field mb-md\">\n\
             <label for=\"pairing_code\">Pairing Code</label>\n\
             <input id=\"pairing_code\" name=\"pairing_code\" type=\"password\" required autocomplete=\"one-time-code\" {pairing_attrs} placeholder=\"e.g. 4f9a-82c1\">\n\
           </div>\n\
           <div class=\"field mb-lg\">\n\
             <label for=\"device_name\">Browser Label (optional)</label>\n\
             <input id=\"device_name\" name=\"device_name\" type=\"text\" maxlength=\"80\" placeholder=\"Roche Web\">\n\
           </div>\n\
           <button type=\"submit\" class=\"btn btn-primary\">Sign in</button>\n\
         </form>\n",
    );

    if tailscale_enabled {
        out.push_str(
            "<p class=\"meta login-tip\">\
             Tip: when served via local <code>tailscale serve</code>, add your Tailnet login to \
             <code>tailscale.auto_auth_users</code> in <code>config.toml</code> for automatic sign-in.\
             </p>\n",
        );
    }

    out.push_str("</div>\n</main>\n</body>\n</html>\n");
    Html(out)
}

#[must_use]
pub(super) fn render_error_page(
    status: StatusCode,
    heading: &str,
    detail: &str,
    csrf_token: Option<&str>,
) -> Response {
    let mut body = String::with_capacity(512);
    let _ = write!(
        body,
        "<h2>{}</h2>\n<p class=\"lede\">{}</p>\n<p><a class=\"btn\" href=\"/\">Return Home</a></p>",
        esc(heading),
        esc(detail)
    );
    let html = csrf_token.map_or_else(
        || {
            let mut unauthed_body = String::with_capacity(512);
            let _ = write!(
                unauthed_body,
                "<h1>{}</h1>\n<p class=\"lede\">{}</p>\n<p><a class=\"btn\" href=\"/\">Return Home</a></p>",
                esc(heading),
                esc(detail)
            );
            Html(format!(
                "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">\
                 <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\
                 <title>{}</title><link rel=\"stylesheet\" href=\"/static/roche.css\"></head>\
                 <body><main>{}</main></body></html>",
                esc(heading),
                unauthed_body
            ))
        },
        |csrf| {
            render_page(&PageShell {
                title: heading,
                active: NavSection::None,
                csrf_token: csrf,
                flash: &FlashQuery::default(),
                is_refreshing: false,
                body_html: &body,
            })
        },
    );
    (status, html).into_response()
}

/// Web view error type converting transport-agnostic [`AppError`] directly into an
/// authenticated HTML error response (including `Retry-After` headers when rate-limited).
#[derive(Debug)]
pub(super) struct WebError {
    err: AppError,
    csrf_token: Option<String>,
}

impl WebError {
    #[must_use]
    pub(super) fn from_app(err: AppError, csrf_token: Option<&str>) -> Self {
        Self { err, csrf_token: csrf_token.map(ToOwned::to_owned) }
    }
}

pub(super) trait WithWebCsrf<T> {
    fn web_err(self, session: &WebSession) -> Result<T, WebError>;
}

impl<T> WithWebCsrf<T> for Result<T, AppError> {
    fn web_err(self, session: &WebSession) -> Result<T, WebError> {
        self.map_err(|err| WebError::from_app(err, Some(&session.csrf_token)))
    }
}

fn classify_web_app_error(err: &AppError) -> (StatusCode, &'static str, String, Option<u64>) {
    match err {
        AppError::BadRequest(msg)
        | AppError::ImmutableCollection(msg)
        | AppError::SsrfBlocked(msg) => {
            (StatusCode::BAD_REQUEST, "Invalid Request", msg.clone(), None)
        }
        AppError::Unauthorized(msg)
        | AppError::TokenInvalid(msg)
        | AppError::PairingCodeInvalid(msg) => {
            (StatusCode::UNAUTHORIZED, "Unauthorized", msg.clone(), None)
        }
        AppError::NotFound(msg) => (StatusCode::NOT_FOUND, "Not Found", msg.clone(), None),
        AppError::Conflict(msg) => (StatusCode::CONFLICT, "Conflict", msg.clone(), None),
        AppError::AmbiguousLookup { detail, .. } => {
            (StatusCode::CONFLICT, "Ambiguous Lookup", detail.clone(), None)
        }
        AppError::PairingRateLimited { detail, retry_after_seconds } => (
            StatusCode::TOO_MANY_REQUESTS,
            "Too Many Attempts",
            detail.clone(),
            Some(*retry_after_seconds),
        ),
        AppError::BadGateway(msg) => (StatusCode::BAD_GATEWAY, "Upstream Error", msg.clone(), None),
        AppError::PayloadTooLarge { detail, .. } => {
            (StatusCode::PAYLOAD_TOO_LARGE, "Payload Too Large", detail.clone(), None)
        }
        AppError::Database(sqlx_err) => classify_web_sqlx_error(sqlx_err),
        AppError::Internal(inner) => classify_web_internal_error(inner),
    }
}

fn classify_web_sqlx_error(
    sqlx_err: &sqlx::Error,
) -> (StatusCode, &'static str, String, Option<u64>) {
    if let sqlx::Error::Database(db_err) = sqlx_err
        && (db_err.is_unique_violation() || db_err.message().contains("UNIQUE constraint failed"))
    {
        return (
            StatusCode::CONFLICT,
            "Conflict",
            "A resource with the same unique identifier already exists.".to_owned(),
            None,
        );
    }
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Server Error",
        "An internal error occurred while processing your request.".to_owned(),
        None,
    )
}

fn classify_web_internal_error(
    inner: &anyhow::Error,
) -> (StatusCode, &'static str, String, Option<u64>) {
    if let Some(conflict) = inner.downcast_ref::<ConflictError>() {
        return (StatusCode::CONFLICT, "Conflict", conflict.0.clone(), None);
    }
    if let Some(ssrf) = inner.downcast_ref::<SsrfBlockedError>() {
        return (StatusCode::BAD_REQUEST, "Invalid Request", ssrf.0.clone(), None);
    }
    if let Some(cat) = inner.downcast_ref::<CatalogError>() {
        return match cat {
            CatalogError::Db(db_err) => classify_web_sqlx_error(db_err),
            CatalogError::NotFound(msg) => (StatusCode::NOT_FOUND, "Not Found", msg.clone(), None),
            CatalogError::Conflict(msg) => (StatusCode::CONFLICT, "Conflict", msg.clone(), None),
            CatalogError::AmbiguousLookup { .. } => {
                (StatusCode::CONFLICT, "Ambiguous Lookup", cat.to_string(), None)
            }
            CatalogError::ImmutableCollection { .. } | CatalogError::InvalidInput(_) => {
                (StatusCode::BAD_REQUEST, "Invalid Request", cat.to_string(), None)
            }
            CatalogError::Internal(_) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Server Error",
                "An internal error occurred while processing your request.".to_owned(),
                None,
            ),
        };
    }
    if let Some(sqlx_err) = inner.downcast_ref::<sqlx::Error>() {
        return classify_web_sqlx_error(sqlx_err);
    }
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Server Error",
        "An internal error occurred while processing your request.".to_owned(),
        None,
    )
}

impl IntoResponse for WebError {
    fn into_response(self) -> Response {
        let (status, heading, detail, retry_after) = classify_web_app_error(&self.err);
        let mut resp = render_error_page(status, heading, &detail, self.csrf_token.as_deref());
        if let Some(secs) = retry_after
            && let Ok(val) = HeaderValue::from_str(&secs.to_string())
        {
            resp.headers_mut().insert(header::RETRY_AFTER, val);
        }
        resp
    }
}
