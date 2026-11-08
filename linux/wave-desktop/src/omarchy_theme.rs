//! Omarchy のテーマの色（colors.toml）と Hyprland の角の丸みを読み、変わったら知らせる（QML 版の画面が使う）。
//! 色の対応は omarchy-gtk-tint と同じにして、GTK のアプリと見た目をそろえる。
//! colors.toml は `$XDG_STATE_HOME/omarchy/current/theme/colors.toml`（無ければ `~/.local/state/…`）。
//! テーマを切り替えると、ファイルもフォルダも置き換わるので、変わるたびに見張りを付け直す。

use std::cell::RefCell;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::Duration;

use gio::prelude::*;

/// 画面に当てる色（"#rrggbb"）。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Palette {
    /// 暗いテーマか（`mode`。無ければ背景の明るさで決める）。
    pub dark: bool,
    pub accent: String,
    /// アクセントの上の文字。
    pub on_accent: String,
    /// 窓の地。
    pub background: String,
    /// 一覧・入力欄・横の欄。
    pub view: String,
    /// 見出しの帯・カード・ダイアログ・ポップオーバー。
    pub raised: String,
    pub foreground: String,
    /// 境目の線。
    pub border: String,
    pub red: String,
    pub green: String,
    pub yellow: String,
}

/// colors.toml の場所。
pub fn colors_path() -> PathBuf {
    state_dir().join("omarchy").join("current").join("theme").join("colors.toml")
}

fn state_dir() -> PathBuf {
    std::env::var_os("XDG_STATE_HOME").filter(|v| !v.is_empty()).map(PathBuf::from).unwrap_or_else(|| glib::home_dir().join(".local").join("state"))
}

/// `key = "#rrggbb"` の並びを読む（表や配列は使っていない。omarchy-gtk-tint と同じ読み方）。
pub fn parse_colors(text: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for line in text.lines() {
        let line = line.trim();
        if line.starts_with('#') || line.starts_with('[') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else { continue };
        let key = key.trim();
        if key.is_empty() || !key.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            continue;
        }
        // 値の後ろの注釈（"…" の外の #…）は捨てる
        let value = value.trim();
        let value = match value.strip_prefix('"') {
            Some(rest) => rest.split('"').next().unwrap_or(""),
            None => value.split('#').next().unwrap_or("").trim(),
        };
        map.insert(key.to_string(), value.to_string());
    }
    map
}

/// 読んだ色から、画面に当てる色を決める。background と foreground が無ければ None（Omarchy でないとみなす）。
pub fn palette(colors: &HashMap<String, String>) -> Option<Palette> {
    let color = |key: &str| colors.get(key).and_then(|v| normalize(v));
    let background = color("background")?;
    let foreground = color("foreground")?;
    let pick = |a: &str, b: &str| color(a).or_else(|| color(b));
    let accent = pick("accent", "blue").unwrap_or_else(|| foreground.clone());
    let view = color("dark_background").unwrap_or_else(|| background.clone());
    let raised = color("lighter_background").unwrap_or_else(|| background.clone());
    let border = pick("muted", "lighter_background").unwrap_or_else(|| raised.clone());
    let red = pick("red", "accent").unwrap_or_else(|| accent.clone());
    let green = pick("green", "accent").unwrap_or_else(|| accent.clone());
    let yellow = pick("yellow", "accent").unwrap_or_else(|| accent.clone());
    let dark = match colors.get("mode").map(|m| m.trim().to_ascii_lowercase()) {
        Some(m) if m == "dark" => true,
        Some(m) if m == "light" => false,
        _ => luminance(&background) < 0.5,
    };
    let on_accent = on(&accent, &background, &foreground);
    Some(Palette { dark, accent, on_accent, background, view, raised, foreground, border, red, green, yellow })
}

/// "#RGB"・"#RRGGBB"・"#RRGGBBAA"（# 無しも）を "#rrggbb" にそろえる。読めなければ None。
fn normalize(value: &str) -> Option<String> {
    let hex = value.trim().trim_start_matches('#');
    let hex = match hex.len() {
        3 => hex.chars().flat_map(|c| [c, c]).collect::<String>(),
        6 => hex.to_string(),
        8 => hex[..6].to_string(),
        _ => return None,
    };
    hex.chars().all(|c| c.is_ascii_hexdigit()).then(|| format!("#{}", hex.to_ascii_lowercase()))
}

fn channels(color: &str) -> (f64, f64, f64) {
    let hex = color.trim_start_matches('#');
    let c = |i: usize| f64::from(u8::from_str_radix(&hex[i..i + 2], 16).unwrap_or(0)) / 255.0;
    (c(0), c(2), c(4))
}

/// 相対輝度（WCAG）。
fn luminance(color: &str) -> f64 {
    let lin = |v: f64| if v <= 0.03928 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) };
    let (r, g, b) = channels(color);
    0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
}

fn contrast(a: &str, b: &str) -> f64 {
    let (la, lb) = (luminance(a), luminance(b));
    (la.max(lb) + 0.05) / (la.min(lb) + 0.05)
}

/// 色の上に載せる文字: テーマの背景色と文字色のうち、その色と差が大きい方。
pub fn on(color: &str, background: &str, foreground: &str) -> String {
    if contrast(color, background) >= contrast(color, foreground) { background.to_string() } else { foreground.to_string() }
}

/// 今のテーマの色（Omarchy でなければ None）。
pub fn read_palette() -> Option<Palette> {
    read_palette_at(&colors_path())
}

pub fn read_palette_at(path: &Path) -> Option<Palette> {
    std::fs::read_to_string(path).ok().and_then(|text| palette(&parse_colors(&text)))
}

/// Hyprland の窓の角の丸み（`hyprctl getoption decoration:rounding -j` の int）。Hyprland でなければ None。
pub fn hypr_rounding() -> Option<i32> {
    // 確かめ用: WAVE_THEME_ROUNDING で丸みを決め打ちにする（Hyprland の外で撮るとき）
    if let Some(value) = std::env::var("WAVE_THEME_ROUNDING").ok().and_then(|v| v.parse().ok()) {
        return Some(value);
    }
    std::env::var_os("HYPRLAND_INSTANCE_SIGNATURE")?;
    let output = std::process::Command::new("hyprctl").args(["getoption", "decoration:rounding", "-j"]).output().ok()?;
    parse_rounding(&String::from_utf8_lossy(&output.stdout))
}

pub fn parse_rounding(json: &str) -> Option<i32> {
    let value: serde_json::Value = serde_json::from_str(json).ok()?;
    value.get("int").and_then(|v| v.as_i64()).map(|v| v.clamp(0, 64) as i32)
}

/// colors.toml を見張り、変わったら（テーマを切り替えたら）on_change を呼ぶ。
/// 見張るのは colors.toml の入ったフォルダと、その上の current（テーマのフォルダごと置き換わるため）。変わるたびに付け直す。
pub struct ThemeWatcher {
    path: PathBuf,
    monitors: RefCell<Vec<gio::FileMonitor>>,
    pending: RefCell<Option<glib::SourceId>>,
    on_change: Box<dyn Fn()>,
}

impl ThemeWatcher {
    pub fn start(on_change: impl Fn() + 'static) -> Rc<Self> {
        Self::start_at(colors_path(), on_change)
    }

    pub fn start_at(path: PathBuf, on_change: impl Fn() + 'static) -> Rc<Self> {
        let watcher = Rc::new(Self { path, monitors: RefCell::default(), pending: RefCell::default(), on_change: Box::new(on_change) });
        watcher.attach();
        watcher
    }

    /// 今見張っているフォルダ（確かめ用）。
    pub fn watched(&self) -> Vec<PathBuf> {
        self.watch_dirs().into_iter().filter(|d| d.is_dir()).collect()
    }

    fn watch_dirs(&self) -> Vec<PathBuf> {
        // colors.toml のフォルダ（theme）、その上（current）、さらに上（omarchy。current ごと作り直されたとき）
        self.path.ancestors().skip(1).take(3).map(Path::to_path_buf).collect()
    }

    fn attach(self: &Rc<Self>) {
        let mut monitors = Vec::new();
        for dir in self.watch_dirs() {
            if !dir.is_dir() {
                continue;
            }
            let Ok(monitor) = gio::File::for_path(&dir).monitor_directory(gio::FileMonitorFlags::WATCH_MOVES, gio::Cancellable::NONE) else { continue };
            let weak = Rc::downgrade(self);
            monitor.connect_changed(move |_, _, _, _| {
                if let Some(this) = weak.upgrade() {
                    this.changed();
                }
            });
            monitors.push(monitor);
        }
        for old in self.monitors.replace(monitors) {
            old.cancel();
        }
    }

    /// 切り替えは何回かに分けて書かれるので、少し待ってから1回だけ読み直す。
    fn changed(self: &Rc<Self>) {
        if let Some(old) = self.pending.borrow_mut().take() {
            old.remove();
        }
        let weak = Rc::downgrade(self);
        let id = glib::timeout_add_local_once(Duration::from_millis(150), move || {
            let Some(this) = weak.upgrade() else { return };
            this.pending.borrow_mut().take();
            this.attach();
            (this.on_change)();
        });
        *self.pending.borrow_mut() = Some(id);
    }
}

impl Drop for ThemeWatcher {
    fn drop(&mut self) {
        for monitor in self.monitors.borrow().iter() {
            monitor.cancel();
        }
        if let Some(id) = self.pending.borrow_mut().take() {
            id.remove();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CATPPUCCIN: &str = r##"mode = "dark"

accent = "#89b4fa"
muted = "#585b70"
background = "#1e1e2e"
dark_background = "#161622"
lighter_background = "#313244"
foreground = "#cdd6f4"
red = "#f38ba8"
yellow = "#f9e2af"
green = "#a6e3a1"
blue = "#89b4fa"
"##;

    #[test]
    fn reads_catppuccin() {
        let p = palette(&parse_colors(CATPPUCCIN)).unwrap();
        assert!(p.dark);
        assert_eq!(p.accent, "#89b4fa");
        assert_eq!(p.background, "#1e1e2e");
        assert_eq!(p.view, "#161622");
        assert_eq!(p.raised, "#313244");
        assert_eq!(p.border, "#585b70");
        assert_eq!(p.red, "#f38ba8");
        // 明るい青の上は、背景の暗い色の方が読みやすい
        assert_eq!(p.on_accent, "#1e1e2e");
    }

    #[test]
    fn light_theme_and_fallbacks() {
        // mode が無ければ背景の明るさで決める。accent が無ければ blue、dark_background が無ければ background
        let p = palette(&parse_colors("background = \"#FFFCF0\"\nforeground = \"#100F0F\"\nblue = \"#205EA6\"  # 注釈\n")).unwrap();
        assert!(!p.dark);
        assert_eq!(p.accent, "#205ea6");
        assert_eq!(p.view, "#fffcf0");
        assert_eq!(p.red, "#205ea6");
        // 濃い青の上は、明るい背景の色
        assert_eq!(p.on_accent, "#fffcf0");
    }

    #[test]
    fn missing_or_broken_file_is_not_omarchy() {
        assert_eq!(palette(&parse_colors("accent = \"#89b4fa\"\n")), None);
        assert_eq!(palette(&parse_colors("background = \"nope\"\nforeground = \"#fff\"\n")), None);
        assert_eq!(read_palette_at(Path::new("/nonexistent/colors.toml")), None);
    }

    #[test]
    fn reads_rounding() {
        assert_eq!(parse_rounding(r#"{"option": "decoration:rounding", "int": 10, "set": true}"#), Some(10));
        assert_eq!(parse_rounding(r#"{"option": "decoration:rounding", "int": 0, "set": false}"#), Some(0));
        assert_eq!(parse_rounding("HYPRLAND_INSTANCE_SIGNATURE not set!"), None);
    }

    /// テーマを切り替えた（フォルダごと置き換えた）あとも、見張りが付け直されて次の書き換えにも気づく。
    #[test]
    fn rewatches_after_theme_folder_is_replaced() {
        let root = std::env::temp_dir().join(format!("wave-theme-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let theme = root.join("omarchy").join("current").join("theme");
        std::fs::create_dir_all(&theme).unwrap();
        let path = theme.join("colors.toml");
        std::fs::write(&path, CATPPUCCIN).unwrap();
        let context = glib::MainContext::default();
        let _guard = context.acquire().unwrap();
        let count = Rc::new(std::cell::Cell::new(0));
        let seen = count.clone();
        let watcher = ThemeWatcher::start_at(path.clone(), move || seen.set(seen.get() + 1));
        let wait = |want: usize| {
            let deadline = std::time::Instant::now() + Duration::from_secs(5);
            while count.get() < want && std::time::Instant::now() < deadline {
                context.iteration(false);
                std::thread::sleep(Duration::from_millis(10));
            }
            count.get()
        };
        // テーマのフォルダを丸ごと置き換える（Omarchy の切り替えと同じ）
        let staged = root.join("omarchy").join("current").join("theme.new");
        std::fs::create_dir_all(&staged).unwrap();
        std::fs::write(staged.join("colors.toml"), "background = \"#ffffff\"\nforeground = \"#000000\"\n").unwrap();
        std::fs::remove_dir_all(&theme).unwrap();
        std::fs::rename(&staged, &theme).unwrap();
        assert!(wait(1) >= 1, "置き換えに気づかなかった");
        assert_eq!(read_palette_at(&path).map(|p| p.dark), Some(false));
        // 付け直した見張りで、新しいフォルダの中の書き換えにも気づく
        let before = count.get();
        std::fs::write(&path, CATPPUCCIN).unwrap();
        assert!(wait(before + 1) > before, "付け直した後の書き換えに気づかなかった");
        assert!(watcher.watched().contains(&theme));
        drop(watcher);
        let _ = std::fs::remove_dir_all(&root);
    }
}
