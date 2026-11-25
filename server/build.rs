use std::{
    env,
    error::Error,
    fmt::Write as _,
    fs,
    path::{Path, PathBuf},
};

fn collect_files(dir: &Path, out: &mut Vec<PathBuf>) -> Result<(), Box<dyn Error>> {
    println!("cargo:rerun-if-changed={}", dir.display());
    let mut entries = Vec::new();
    for entry_res in fs::read_dir(dir)? {
        let entry = entry_res?;
        let ft = entry.file_type()?;
        if ft.is_symlink() {
            return Err(format!(
                "refusing to embed symlink inside web/dist: {}",
                entry.path().display()
            )
            .into());
        }
        entries.push((entry.path(), ft.is_dir(), ft.is_file()));
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));

    for (path, is_dir, is_file) in entries {
        if is_dir {
            collect_files(&path, out)?;
        } else if is_file {
            println!("cargo:rerun-if-changed={}", path.display());
            out.push(path);
        }
    }
    Ok(())
}

fn mime_for_path(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase()
        .as_str()
    {
        "html" | "htm" => "text/html; charset=utf-8",
        "js" | "mjs" => "application/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "svg" => "image/svg+xml",
        "json" => "application/json; charset=utf-8",
        "ico" => "image/x-icon",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "woff2" => "font/woff2",
        "woff" => "font/woff",
        "wasm" => "application/wasm",
        "txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}

fn extract_stylesheet_href(index_html: &str) -> Option<String> {
    let mut rest = index_html;
    while let Some(pos) = rest.find("<link") {
        rest = &rest[pos..];
        let Some(end) = rest.find('>') else {
            break;
        };
        let tag = &rest[..=end];
        rest = &rest[end + 1..];
        if !tag.contains("stylesheet") {
            continue;
        }
        if let Some(href_idx) = tag.find("href=\"") {
            let after = &tag[href_idx + 6..];
            if let Some(close) = after.find('"') {
                let href = &after[..close];
                if href.starts_with('/') {
                    return Some(href.to_owned());
                }
            }
        }
    }
    None
}

fn main() -> Result<(), Box<dyn Error>> {
    let manifest_dir =
        PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").ok_or("missing CARGO_MANIFEST_DIR")?);
    let out_dir = PathBuf::from(env::var_os("OUT_DIR").ok_or("missing OUT_DIR")?);
    let dist_dir = manifest_dir.join("../web/dist");

    let index_path = dist_dir.join("index.html");
    if !index_path.is_file() {
        println!("cargo:rerun-if-changed={}", dist_dir.display());
        return Err(
            "web/dist/index.html not found; build the Svelte frontend first with `pnpm --dir web build`"
                .into(),
        );
    }

    let dist_canon = dist_dir.canonicalize()?;
    let index_canon = index_path.canonicalize()?;
    let index_contents = fs::read_to_string(&index_canon)?;
    if !index_contents.contains("<html lang=\"en\">") {
        return Err(
            "web/dist/index.html must contain `<html lang=\"en\">` for server-side theme injection"
                .into(),
        );
    }

    let mut files = Vec::new();
    collect_files(&dist_canon, &mut files)?;

    let mut embedded_paths: Vec<String> = Vec::new();
    let mut entries_src = String::new();

    for file_path in &files {
        let rel = file_path.strip_prefix(&dist_canon)?;
        let mut parts = Vec::new();
        for comp in rel.components() {
            let s = comp.as_os_str().to_str().ok_or("non-UTF-8 path component inside web/dist")?;
            parts.push(s);
        }
        let rel_str = parts.join("/");
        if rel_str == "index.html" {
            continue;
        }
        let url_path = format!("/{rel_str}");
        let content_type = mime_for_path(file_path);
        let immutable = url_path.starts_with("/assets/");
        let abs_str = file_path.to_str().ok_or("non-UTF-8 absolute path inside web/dist")?;
        writeln!(
            entries_src,
            "    EmbeddedAsset {{ path: {url_path:?}, content_type: {content_type:?}, immutable: {immutable}, bytes: include_bytes!({abs_str:?}) }},",
        )?;
        embedded_paths.push(url_path);
    }

    let main_css_href = extract_stylesheet_href(&index_contents)
        .filter(|href| embedded_paths.iter().any(|p| p == href))
        .or_else(|| {
            embedded_paths
                .iter()
                .find(|p| {
                    p.starts_with("/assets/")
                        && Path::new(p)
                            .extension()
                            .is_some_and(|ext| ext.eq_ignore_ascii_case("css"))
                })
                .cloned()
        });

    let index_abs = index_canon.to_str().ok_or("non-UTF-8 path for web/dist/index.html")?;
    let css_const = main_css_href
        .as_deref()
        .map_or_else(|| "None".to_owned(), |href| format!("Some({href:?})"));

    let generated = format!(
        "#[derive(Debug, Clone, Copy)]\n\
         pub struct EmbeddedAsset {{\n\
             pub path: &'static str,\n\
             pub content_type: &'static str,\n\
             pub immutable: bool,\n\
             pub bytes: &'static [u8],\n\
         }}\n\
         \n\
         pub const INDEX_HTML: &str = include_str!({index_abs:?});\n\
         pub const MAIN_CSS_HREF: Option<&str> = {css_const};\n\
         \n\
         pub const EMBEDDED_ASSETS: &[EmbeddedAsset] = &[\n\
         {entries_src}];\n"
    );

    fs::write(out_dir.join("spa_assets.rs"), generated)?;
    Ok(())
}
