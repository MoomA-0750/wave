//! ジャケット・サムネイルの URL と色の求め方（GTK 版と QML 版で共有する。画像を読んで出すのは画面の側）。

use std::sync::LazyLock;

use regex::Regex;

/// 色（0〜1 の赤・緑・青）。
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rgb(pub f64, pub f64, pub f64);

static GOOGLE_SIZE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"=w\d+-h\d+[^/]*$").unwrap());
static YTIMG_NAME: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"/(default|mqdefault|hqdefault|sddefault)\.jpg").unwrap());

/// 出す大きさ（px）に合う画像の URL（googleusercontent は寸法を書き換え、i.ytimg.com は大きい方の名前にする）。
pub fn sized(url: &str, px: u32) -> String {
    if GOOGLE_SIZE.is_match(url) {
        return GOOGLE_SIZE.replace(url, format!("=w{px}-h{px}-l90-rj")).into_owned();
    }
    if px > 120 { YTIMG_NAME.replace(url, "/hqdefault.jpg").into_owned() } else { url.to_string() }
}

/// RGBA の並びから、鮮やかで暗すぎない色を重く見た平均の色。
pub fn weighted_color(rgba: &[u8]) -> Option<Rgb> {
    let (mut r, mut g, mut b, mut w) = (0.0, 0.0, 0.0, 0.0);
    for px in rgba.chunks_exact(4) {
        let (pr, pg, pb) = (f64::from(px[0]), f64::from(px[1]), f64::from(px[2]));
        let (mx, mn) = (pr.max(pg).max(pb), pr.min(pg).min(pb));
        let weight = (mx - mn) / 255.0 * (mx / 255.0) + 0.02;
        r += pr * weight;
        g += pg * weight;
        b += pb * weight;
        w += weight;
    }
    (w > 0.0).then(|| Rgb(r / w / 255.0, g / w / 255.0, b / w / 255.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vivid_pixels_outweigh_gray_ones() {
        // 鮮やかな赤 1 つと灰色 3 つなら、赤に寄る
        let color = weighted_color(&[255, 0, 0, 255, 128, 128, 128, 255, 128, 128, 128, 255, 128, 128, 128, 255]).unwrap();
        assert!(color.0 > 0.85 && color.1 < 0.15, "{color:?}");
        assert_eq!(weighted_color(&[]), None);
    }

    #[test]
    fn resizes_google_and_ytimg_urls() {
        assert_eq!(sized("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 226), "https://lh3.googleusercontent.com/abc=w226-h226-l90-rj");
        assert_eq!(sized("https://i.ytimg.com/vi/x/sddefault.jpg", 300), "https://i.ytimg.com/vi/x/hqdefault.jpg");
        assert_eq!(sized("https://i.ytimg.com/vi/x/default.jpg", 48), "https://i.ytimg.com/vi/x/default.jpg");
    }
}
