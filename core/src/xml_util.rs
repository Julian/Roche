use quick_xml::{
    Reader, XmlVersion,
    escape::unescape,
    events::{BytesRef, BytesStart, BytesText, Event, attributes::Attribute},
};

/// Decodes a `quick_xml` attribute value using XML normalization, falling back to the raw value
/// if entity decoding fails.
#[must_use]
pub fn attr_value(attr: &Attribute<'_>) -> String {
    attr.normalized_value(XmlVersion::default())
        .map_or_else(|_| attr.value.as_ref().to_owned(), Into::into)
}

/// Iterates over all attributes of `tag` (with checks disabled for HTML/feed leniency), yielding
/// `(key, decoded_value)` pairs.
pub fn iter_attrs<'a>(tag: &'a BytesStart<'a>) -> impl Iterator<Item = (&'a str, String)> + 'a {
    let mut attrs = tag.attributes();
    attrs.with_checks(false);
    attrs.flatten().map(|attr| (attr.key.into_inner(), attr_value(&attr)))
}

/// Finds the first attribute on `tag` whose name matches `name` ASCII-case-insensitively and
/// returns its decoded value.
#[must_use]
pub fn find_attr_ignore_ascii_case(tag: &BytesStart<'_>, name: &str) -> Option<String> {
    iter_attrs(tag).find_map(|(k, v)| k.eq_ignore_ascii_case(name).then_some(v))
}

/// Finds the first attribute on `tag` whose name matches `name` ASCII-case-insensitively and
/// returns its trimmed value if non-empty.
#[must_use]
pub fn find_non_empty_attr(tag: &BytesStart<'_>, name: &str) -> Option<String> {
    iter_attrs(tag).find_map(|(k, v)| {
        if k.eq_ignore_ascii_case(name) {
            let trimmed = v.trim();
            if !trimmed.is_empty() {
                return Some(trimmed.to_owned());
            }
        }
        None
    })
}

/// Decodes an XML/HTML text event, falling back to the raw text on invalid/unknown entities.
#[must_use]
pub fn decode_text(text: &BytesText<'_>) -> String {
    unescape(text.as_ref()).map_or_else(|_| text.to_string(), Into::into)
}

/// Decodes an XML/HTML general entity reference (`&...;`), falling back to the raw entity string
/// when the entity is not recognized by `quick_xml::escape::unescape`.
#[must_use]
pub fn decode_entity(entity: &BytesRef<'_>) -> String {
    let raw = format!("&{};", entity.as_ref());
    if let Ok(decoded) = unescape(&raw) {
        return decoded.into_owned();
    }
    raw
}

/// Reads all text, entity, and CDATA content inside an element until its matching `end_tag`.
/// Unknown entities fall back to their raw representation instead of aborting mid-element.
#[must_use]
pub fn read_element_text(reader: &mut Reader<&[u8]>, end_tag: &str) -> Option<String> {
    let mut out = String::new();
    loop {
        match reader.read_event() {
            Ok(Event::Text(text)) => {
                out.push_str(&decode_text(&text));
            }
            Ok(Event::GeneralRef(entity)) => {
                out.push_str(&decode_entity(&entity));
            }
            Ok(Event::CData(cdata)) => {
                out.push_str(cdata.as_ref());
            }
            Ok(Event::End(tag)) if tag.name().as_ref() == end_tag => break,
            Ok(Event::Eof) | Err(_) => return None,
            _ => {}
        }
    }
    Some(out)
}
