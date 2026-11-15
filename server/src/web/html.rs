use std::fmt::{self, Write as _};

use axum::{
    http::{HeaderValue, StatusCode, header},
    response::{Html, IntoResponse, Response},
};

use crate::error::ApiError;

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

/// Ensures an external link uses `http://` or `https://` before rendering in an `href`.
#[must_use]
pub(super) fn safe_external_href(raw: &str) -> Option<&str> {
    let trimmed = raw.trim();
    if trimmed.starts_with("https://") || trimmed.starts_with("http://") {
        Some(trimmed)
    } else {
        None
    }
}

/// Validates a relative redirect target so user-supplied `return_to` values cannot trigger open redirects.
#[must_use]
pub(super) fn safe_local_redirect(candidate: Option<&str>, fallback: &str) -> String {
    if let Some(raw) = candidate.map(str::trim)
        && raw.starts_with('/')
        && !raw.starts_with("//")
        && !raw.contains('\\')
        && !raw.bytes().any(|b| b < 0x20 || b == 0x7f)
    {
        return raw.to_owned();
    }
    fallback.to_owned()
}

pub(super) fn redirect_see_other(location: &str) -> Response {
    let safe_loc = safe_local_redirect(Some(location), "/");
    let mut resp = StatusCode::SEE_OTHER.into_response();
    if let Ok(val) = HeaderValue::from_str(&safe_loc) {
        resp.headers_mut().insert(header::LOCATION, val);
    }
    resp
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum NavSection {
    Home,
    Inbox,
    Feeds,
    Starred,
    Lists,
    Rank,
    Diary,
    Queue,
    None,
}

#[derive(Debug, Default, Clone, serde::Deserialize)]
pub(super) struct FlashQuery {
    pub notice: Option<String>,
    pub err: Option<String>,
}

pub(super) struct PageShell<'a> {
    pub title: &'a str,
    pub active: NavSection,
    pub csrf_token: &'a str,
    pub flash: &'a FlashQuery,
    pub body_html: &'a str,
}

const NAV_ITEMS: [(NavSection, &str, &str); 8] = [
    (NavSection::Home, "/", "Home"),
    (NavSection::Inbox, "/inbox", "Inbox"),
    (NavSection::Feeds, "/feeds", "Feeds"),
    (NavSection::Starred, "/starred", "Starred"),
    (NavSection::Lists, "/lists", "Lists"),
    (NavSection::Rank, "/rank", "Rank"),
    (NavSection::Diary, "/diary", "Diary"),
    (NavSection::Queue, "/queue", "Queue"),
];

pub(super) fn render_page(shell: &PageShell<'_>) -> Html<String> {
    let mut out = String::with_capacity(shell.body_html.len() + 2_400);
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
           <title>{}</title>\n\
           <link rel=\"stylesheet\" href=\"/static/roche.css\">\n\
         </head>\n\
         <body data-csrf=\"{}\">\n\
         <header class=\"masthead\">\n\
           <div class=\"masthead-top\">\n\
             <h1><a href=\"/\">Roche</a></h1>\n\
             <form class=\"logout-form\" method=\"post\" action=\"/logout\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\n\
               <button type=\"submit\" class=\"link-btn\">Sign out</button>\n\
             </form>\n\
           </div>\n\
           <nav id=\"roche-nav\" class=\"primary-nav\">",
        esc(&page_title),
        esc(shell.csrf_token),
        esc(shell.csrf_token),
    );

    for (sec, href, label) in NAV_ITEMS {
        let cls = if sec == shell.active { " class=\"is-active\"" } else { "" };
        let _ = write!(out, "<a href=\"{href}\"{cls}>{label}</a>");
    }

    out.push_str("</nav>\n</header>\n<main id=\"roche-main\">\n");

    if let Some(msg) = shell.flash.err.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let _ = writeln!(out, "<div class=\"notice notice-error\">{}</div>", esc(msg));
    } else if let Some(msg) = shell.flash.notice.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        let _ = writeln!(out, "<div class=\"notice\">{}</div>", esc(msg));
    }

    out.push_str(shell.body_html);

    out.push_str(
        "\n</main>\n\
         <footer class=\"colophon\">\n\
           <span>&#10086;</span>\n\
           <span>Roche &middot; Reader, Podcasts &amp; Ranked Library</span>\n\
         </footer>\n\
         <div id=\"roche-player-bar\" class=\"player-bar\" hidden>\n\
           <div class=\"player-inner\">\n\
             <div class=\"player-meta\">\n\
               <div id=\"roche-player-title\" class=\"player-title\">Episode</div>\n\
               <div id=\"roche-player-sub\" class=\"player-sub\">Podcast</div>\n\
             </div>\n\
             <audio id=\"roche-audio\" controls preload=\"metadata\"></audio>\n\
             <button type=\"button\" id=\"roche-player-speed\" class=\"btn btn-small\">1x</button>\n\
             <a id=\"roche-player-link\" href=\"/inbox\" class=\"btn btn-small\">Notes</a>\n\
             <button type=\"button\" id=\"roche-player-close\" class=\"btn btn-small btn-ghost\" aria-label=\"Close player\">&times;</button>\n\
           </div>\n\
         </div>\n\
         <script src=\"/static/roche.js\" defer></script>\n\
         </body>\n\
         </html>\n",
    );

    Html(out)
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

    if let Some(err) = error_msg.map(str::trim).filter(|s| !s.is_empty()) {
        let _ = writeln!(out, "<div class=\"notice notice-error\">{}</div>", esc(err));
    }

    if let Some(ts_login) = tailscale_user.map(str::trim).filter(|s| !s.is_empty()) {
        let _ = writeln!(
            out,
            "<form method=\"post\" action=\"/login/tailscale\" style=\"margin-bottom:1.1rem\">\n\
               <button type=\"submit\" class=\"btn btn-primary\" style=\"width:100%\">Sign in as {}</button>\n\
             </form>\n",
            esc(ts_login)
        );
    }

    out.push_str(
        "<form method=\"post\" action=\"/login\">\n\
           <div class=\"field\" style=\"margin-bottom:0.85rem\">\n\
             <label for=\"pairing_code\">Pairing Code</label>\n\
             <input id=\"pairing_code\" name=\"pairing_code\" type=\"password\" required autocomplete=\"one-time-code\" autofocus placeholder=\"e.g. 4f9a-82c1\">\n\
           </div>\n\
           <div class=\"field\" style=\"margin-bottom:1.1rem\">\n\
             <label for=\"device_name\">Browser Label (optional)</label>\n\
             <input id=\"device_name\" name=\"device_name\" type=\"text\" maxlength=\"80\" placeholder=\"Roche Web\">\n\
           </div>\n\
           <button type=\"submit\" class=\"btn btn-primary\">Sign in</button>\n\
         </form>\n",
    );

    if tailscale_enabled {
        out.push_str(
            "<p class=\"meta\" style=\"margin-top:1.25rem;margin-bottom:0;font-size:0.82rem\">\
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
            Html(format!(
                "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">\
                 <title>{}</title><link rel=\"stylesheet\" href=\"/static/roche.css\"></head>\
                 <body><main>{}</main></body></html>",
                esc(heading),
                body
            ))
        },
        |csrf| {
            render_page(&PageShell {
                title: heading,
                active: NavSection::None,
                csrf_token: csrf,
                flash: &FlashQuery::default(),
                body_html: &body,
            })
        },
    );
    (status, html).into_response()
}

pub(super) fn web_api_error_response(err: &ApiError, csrf_token: Option<&str>) -> Response {
    let (status, heading, detail) = match err {
        ApiError::BadRequest(msg) => (StatusCode::BAD_REQUEST, "Invalid Request", msg.clone()),
        ApiError::UnsupportedMediaType(msg) => {
            (StatusCode::UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", msg.clone())
        }
        ApiError::Unauthorized(msg) => (StatusCode::UNAUTHORIZED, "Unauthorized", msg.clone()),
        ApiError::NotFound(msg) => (StatusCode::NOT_FOUND, "Not Found", msg.clone()),
        ApiError::Conflict(msg) => (StatusCode::CONFLICT, "Conflict", msg.clone()),
        ApiError::TooManyRequests(msg) => {
            (StatusCode::TOO_MANY_REQUESTS, "Too Many Attempts", msg.clone())
        }
        ApiError::BadGateway(msg) => (StatusCode::BAD_GATEWAY, "Upstream Error", msg.clone()),
        ApiError::MethodNotAllowed(msg) => {
            (StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed", msg.clone())
        }
        ApiError::PayloadTooLarge(msg) => {
            (StatusCode::PAYLOAD_TOO_LARGE, "Payload Too Large", msg.clone())
        }
        ApiError::Database(_) | ApiError::Internal(_) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Server Error",
            "An internal error occurred while processing your request.".to_owned(),
        ),
    };
    render_error_page(status, heading, &detail, csrf_token)
}
