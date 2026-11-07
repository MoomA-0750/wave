//! ジャケット・サムネイルを読み、gtk::Picture に出す。同じ URL は一度だけ読む。

use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::LazyLock;

use gtk::prelude::*;
use regex::Regex;

use crate::runtime;

thread_local! {
    static CACHE: RefCell<HashMap<String, gtk::gdk::Texture>> = RefCell::new(HashMap::new());
    static COLORS: RefCell<HashMap<String, Option<Rgb>>> = RefCell::new(HashMap::new());
}

/// 色（0〜1 の赤・緑・青）。
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rgb(pub f64, pub f64, pub f64);

static HTTP: LazyLock<reqwest::Client> = LazyLock::new(|| {
    reqwest::Client::builder().timeout(std::time::Duration::from_secs(20)).build().expect("the TLS backend is built in")
});
static GOOGLE_SIZE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"=w\d+-h\d+[^/]*$").unwrap());
static YTIMG_NAME: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"/(default|mqdefault|hqdefault|sddefault)\.jpg").unwrap());

/// 出す大きさ（px）に合う画像の URL（googleusercontent は寸法を書き換え、i.ytimg.com は大きい方の名前にする）。
pub fn sized(url: &str, px: u32) -> String {
    if GOOGLE_SIZE.is_match(url) {
        return GOOGLE_SIZE.replace(url, format!("=w{px}-h{px}-l90-rj")).into_owned();
    }
    if px > 120 { YTIMG_NAME.replace(url, "/hqdefault.jpg").into_owned() } else { url.to_string() }
}

/// 角を丸めた正方形の画像。大きさの決まった枠の上に画像を重ねて切り抜くので、横長・縦長の画像でも枠は変わらない
/// （gtk::Picture だけだと、画像の縦横の比に合わせて枠が伸びる）。
#[derive(Clone)]
pub struct Art {
    pub root: gtk::Overlay,
    pub picture: gtk::Picture,
}

pub fn square(size: i32) -> Art {
    let frame = gtk::Box::builder().width_request(size).height_request(size).css_classes(["artwork", "artwork-frame"]).build();
    let picture = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).css_classes(["artwork"]).build();
    picture.set_overflow(gtk::Overflow::Hidden);
    let root = gtk::Overlay::builder().child(&frame).halign(gtk::Align::Start).valign(gtk::Align::Center).build();
    root.add_overlay(&picture);
    root.set_clip_overlay(&picture, true);
    Art { root, picture }
}

impl Art {
    pub fn load(&self, url: Option<&str>, px: u32) {
        load(&self.picture, url, px);
    }

    pub fn add_css_class(&self, class: &str) {
        self.root.first_child().inspect(|f| f.add_css_class(class));
        self.picture.add_css_class(class);
    }
}

/// picture に url の画像を出す（読み終わる前に別の URL を頼まれたら、古い方は捨てる）。
pub fn load(picture: &gtk::Picture, url: Option<&str>, px: u32) {
    let Some(url) = url.filter(|u| u.starts_with("https://")).map(|u| sized(u, px)) else {
        picture.set_paintable(None::<&gtk::gdk::Paintable>);
        return;
    };
    // SAFETY: 同じ型の値だけを入れ、取り出す（読んでいる URL の覚え書き）
    unsafe { picture.set_data("wave-url", url.clone()) };
    if let Some(texture) = CACHE.with(|c| c.borrow().get(&url).cloned()) {
        picture.set_paintable(Some(&texture));
        return;
    }
    picture.set_paintable(None::<&gtk::gdk::Paintable>);
    let picture = picture.clone();
    gtk::glib::spawn_future_local(async move {
        let fetched = url.clone();
        let bytes = runtime::run(async move {
            let response = HTTP.get(&fetched).send().await.ok()?.error_for_status().ok()?;
            response.bytes().await.ok().map(|b| b.to_vec())
        })
        .await;
        let Some(bytes) = bytes else { return };
        let Ok(texture) = gtk::gdk::Texture::from_bytes(&gtk::glib::Bytes::from_owned(bytes)) else { return };
        CACHE.with(|c| c.borrow_mut().insert(url.clone(), texture.clone()));
        // SAFETY: load で入れた String だけを読む
        let current = unsafe { picture.data::<String>("wave-url").map(|p| p.as_ref().clone()) };
        if current.as_deref() == Some(url.as_str()) {
            picture.set_paintable(Some(&texture));
        }
    });
}

/// url の画像を読み、読めたら on_loaded に渡す（同じ URL は一度だけ読む）。
pub fn texture(url: &str, px: u32, on_loaded: impl FnOnce(gtk::gdk::Texture) + 'static) {
    let url = sized(url, px);
    if let Some(texture) = CACHE.with(|c| c.borrow().get(&url).cloned()) {
        on_loaded(texture);
        return;
    }
    gtk::glib::spawn_future_local(async move {
        let fetched = url.clone();
        let bytes = runtime::run(async move { HTTP.get(&fetched).send().await.ok()?.error_for_status().ok()?.bytes().await.ok().map(|b| b.to_vec()) }).await;
        let Some(texture) = bytes.and_then(|b| gtk::gdk::Texture::from_bytes(&gtk::glib::Bytes::from_owned(b)).ok()) else { return };
        CACHE.with(|c| c.borrow_mut().insert(url, texture.clone()));
        on_loaded(texture);
    });
}

/// ジャケットの色（歌詞のページの背景のグラデーションに使う）。Mac・Windows の Artwork と同じ求め方で、
/// 小さく縮めた画像の、鮮やかさで重みを付けた平均の色。求まったら on_color に渡す（同じ URL は一度だけ求める）。
/// 画像を読めなかったときは呼ばない。
pub fn dominant_color(url: Option<&str>, on_color: impl FnOnce(Option<Rgb>) + 'static) {
    let Some(url) = url.filter(|u| u.starts_with("https://")).map(str::to_string) else {
        on_color(None);
        return;
    };
    if let Some(hit) = COLORS.with(|c| c.borrow().get(&url).copied()) {
        on_color(hit);
        return;
    }
    texture(&url.clone(), 48, move |texture| {
        let mut downloader = gtk::gdk::TextureDownloader::new(&texture);
        downloader.set_format(gtk::gdk::MemoryFormat::R8g8b8a8);
        let (bytes, stride) = downloader.download_bytes();
        let width = texture.width().max(0) as usize * 4;
        let pixels: Vec<u8> =
            bytes.chunks(stride.max(1)).take(texture.height().max(0) as usize).flat_map(|row| row[..width.min(row.len())].to_vec()).collect();
        let color = weighted_color(&pixels);
        COLORS.with(|c| c.borrow_mut().insert(url, color));
        on_color(color);
    });
}

/// RGBA の並びから、鮮やかで暗すぎない色を重く見た平均の色。
fn weighted_color(rgba: &[u8]) -> Option<Rgb> {
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
