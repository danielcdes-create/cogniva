use axum::{
    extract::Path,
    http::{header, StatusCode},
    response::{IntoResponse, Response},
};

const MISSIONS_CSP: &str = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; media-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

fn document(mime: &'static str, body: &'static str) -> Response {
    (
        [
            (header::CONTENT_TYPE, mime),
            (header::CACHE_CONTROL, "no-store"),
            (header::CONTENT_SECURITY_POLICY, MISSIONS_CSP),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
            (header::REFERRER_POLICY, "no-referrer"),
        ],
        body,
    )
        .into_response()
}

pub async fn index() -> Response {
    document(
        "text/html; charset=utf-8",
        include_str!("../../../web-missions/index.html"),
    )
}

pub async fn asset(Path(name): Path<String>) -> Response {
    let (mime, body) = match name.as_str() {
        "app.js" => (
            "text/javascript; charset=utf-8",
            include_str!("../../../web-missions/app.js"),
        ),
        "domain.js" => (
            "text/javascript; charset=utf-8",
            include_str!("../../../web-missions/domain.js"),
        ),
        "style.css" => (
            "text/css; charset=utf-8",
            include_str!("../../../web-missions/style.css"),
        ),
        _ => return StatusCode::NOT_FOUND.into_response(),
    };
    document(mime, body)
}
