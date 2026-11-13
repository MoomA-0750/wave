//! Theme（QML の Theme）: Omarchy の colors.toml を読んで見張り、色と角の丸みを QML に渡す。読み方は wave-desktop の omarchy_theme。

use std::cell::RefCell;
use std::collections::HashMap;
use std::path::PathBuf;
use std::pin::Pin;
use std::rc::Rc;

use cxx_qt::Threading;
use gio::prelude::*;
use cxx_qt_lib::QString;
use wave_desktop::omarchy_theme::{self, ThemeWatcher};

use crate::bridge::qobject;

#[derive(Default)]
pub struct ThemeRust {
    pub omarchy: bool,
    pub dark: bool,
    pub accent: QString,
    pub accent_text: QString,
    pub background: QString,
    pub view: QString,
    pub raised: QString,
    pub foreground: QString,
    pub border: QString,
    pub red: QString,
    pub green: QString,
    pub yellow: QString,
    pub rounding: i32,
    pub font_family: QString,
    pub font_points: f64,
    pub ja: bool,
}

thread_local! {
    static WATCHER: RefCell<Option<Rc<ThemeWatcher>>> = const { RefCell::new(None) };
    static ICONS: RefCell<HashMap<String, String>> = RefCell::new(HashMap::new());
    static FONT_SETTINGS: RefCell<Option<gio::Settings>> = const { RefCell::new(None) };
}

impl cxx_qt::Initialize for qobject::Theme {
    fn initialize(mut self: Pin<&mut Self>) {
        self.as_mut().set_ja(wave_desktop::lang::ja());
        self.as_mut().reload();
        // テーマを切り替えたら（colors.toml が置き換わったら）その場で色を変える
        let thread = self.qt_thread();
        let watcher = ThemeWatcher::start(move || {
            let _ = thread.queue(|theme| theme.reload());
        });
        WATCHER.with(|w| *w.borrow_mut() = Some(watcher));
        // 本体の UI の文字を変えたら、その場で変える
        if let Some(settings) = interface_settings() {
            let thread = self.qt_thread();
            settings.connect_changed(Some("font-name"), move |_, _| {
                let _ = thread.queue(|theme| theme.reload_font());
            });
            FONT_SETTINGS.with(|f| *f.borrow_mut() = Some(settings));
        }
        self.as_mut().reload_font();
    }
}

impl qobject::Theme {
    fn reload_font(mut self: Pin<&mut Self>) {
        let name = interface_settings().map(|s| s.string("font-name").to_string()).unwrap_or_default();
        let (family, points) = parse_font_name(&name).unwrap_or_default();
        eprintln!("theme font {family:?} {points}");
        self.as_mut().set_font_family(QString::from(&family));
        self.as_mut().set_font_points(points);
    }

    pub fn reload(mut self: Pin<&mut Self>) {
        let rounding = omarchy_theme::hypr_rounding().unwrap_or(-1);
        self.as_mut().set_rounding(rounding);
        match omarchy_theme::read_palette() {
            Some(p) => {
                let mut this = self.as_mut();
                this.as_mut().set_dark(p.dark);
                this.as_mut().set_accent(QString::from(&p.accent));
                this.as_mut().set_accent_text(QString::from(&p.on_accent));
                this.as_mut().set_background(QString::from(&p.background));
                this.as_mut().set_view(QString::from(&p.view));
                this.as_mut().set_raised(QString::from(&p.raised));
                this.as_mut().set_foreground(QString::from(&p.foreground));
                this.as_mut().set_border(QString::from(&p.border));
                this.as_mut().set_red(QString::from(&p.red));
                this.as_mut().set_green(QString::from(&p.green));
                this.as_mut().set_yellow(QString::from(&p.yellow));
                // 色を全部入れてから omarchy を立てる（QML は omarchy を見て色を使い始める）
                this.as_mut().set_omarchy(true);
                eprintln!("theme omarchy dark={} rounding={rounding}", p.dark);
            }
            None => {
                self.as_mut().set_omarchy(false);
                eprintln!("theme default rounding={rounding}");
            }
        }
    }

    pub fn icon(&self, name: &QString) -> QString {
        let name = name.to_string();
        QString::from(&icon_path(&name))
    }
}

/// アイコンの SVG を、入っている Adwaita のテーマから探す（GTK 版と同じ絵。wave の本体は GTK4 に依るので入っている）。
pub fn icon_path(name: &str) -> String {
    if let Some(hit) = ICONS.with(|i| i.borrow().get(name).cloned()) {
        return hit;
    }
    let file = format!("{name}.svg");
    let found = data_dirs()
        .into_iter()
        .flat_map(|d| ["symbolic", "scalable"].map(|kind| d.join("icons").join("Adwaita").join(kind)))
        .filter_map(|dir| std::fs::read_dir(&dir).ok())
        .flat_map(|entries| entries.flatten().map(|e| e.path().join(&file)))
        .find(|path| path.is_file())
        .map(|path| format!("file://{}", path.display()))
        .unwrap_or_default();
    ICONS.with(|i| i.borrow_mut().insert(name.to_string(), found.clone()));
    found
}

fn data_dirs() -> Vec<PathBuf> {
    let mut dirs = vec![glib::user_data_dir()];
    dirs.extend(glib::system_data_dirs());
    dirs.push(PathBuf::from("/usr/share"));
    dirs
}

/// GNOME の org.gnome.desktop.interface（GTK 版が文字に使うのと同じ設定）。スキーマが無ければ None
fn interface_settings() -> Option<gio::Settings> {
    const SCHEMA: &str = "org.gnome.desktop.interface";
    let source = gio::SettingsSchemaSource::default()?;
    let schema = source.lookup(SCHEMA, true)?;
    schema.has_key("font-name").then(|| gio::Settings::new(SCHEMA))
}

/// "Murecho 11"・"Noto Sans Bold 10.5" のような Pango の書き方から、字の名前と大きさ（ポイント）を取る。
/// 太さ・斜体の語は落とす（UI の文字は普通の太さで使う）。
pub fn parse_font_name(name: &str) -> Option<(String, f64)> {
    const STYLES: &[&str] = &[
        "thin", "ultra-light", "extra-light", "light", "semi-light", "book", "regular", "normal", "medium", "semi-bold",
        "demi-bold", "bold", "ultra-bold", "extra-bold", "heavy", "black", "italic", "oblique", "condensed", "expanded",
    ];
    let mut words: Vec<&str> = name.trim().trim_end_matches(',').split_whitespace().collect();
    let points = words.last().and_then(|w| w.trim_end_matches("px").parse::<f64>().ok());
    if points.is_some() {
        words.pop();
    }
    while words.len() > 1 && words.last().is_some_and(|w| STYLES.contains(&w.to_ascii_lowercase().as_str())) {
        words.pop();
    }
    let family = words.join(" ");
    (!family.is_empty()).then_some((family, points.unwrap_or(0.0)))
}

#[cfg(test)]
mod tests {
    use super::parse_font_name;

    #[test]
    fn reads_family_and_size() {
        assert_eq!(parse_font_name("Murecho 11"), Some(("Murecho".into(), 11.0)));
        assert_eq!(parse_font_name("Noto Sans CJK JP Bold 10.5"), Some(("Noto Sans CJK JP".into(), 10.5)));
        assert_eq!(parse_font_name("Adwaita Sans 11"), Some(("Adwaita Sans".into(), 11.0)));
        assert_eq!(parse_font_name("Cantarell"), Some(("Cantarell".into(), 0.0)));
        assert_eq!(parse_font_name(""), None);
    }
}
