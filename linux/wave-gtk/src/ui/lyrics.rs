//! 中央の歌詞のページ（Mac の歌詞のページと同じ。下の帯の歌詞のボタンで開く）。
//! 時刻付きなら歌っている行を明るく少し大きく、過ぎた行と先の行は薄くして、真ん中より少し上へ寄せ続ける。
//! 行を押すとその時刻へ飛ぶ。時刻なしは本文をそのまま出す。出典は歌詞に重ねず下の段に、背景はジャケットの色のグラデーション。
//! ほかの端末を映している間は、その端末の曲と位置で合わせ、行を押すとその端末をシークする。

use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use wave_core::lyrics::service::LyricsResult;
use wave_core::lyrics::{LyricLine, SongLyrics, lrc};
use wave_core::music::models::Song;

use super::images;
use super::shell::App;
use crate::runtime;

const STYLE: &str = "
.lyrics-line { font-size: 30px; font-weight: 700; opacity: 0.4; transform: scale(0.96); transform-origin: left center;
  transition: opacity 350ms ease-out, transform 350ms ease-out; }
.lyrics-line.lyrics-sub { font-size: 21px; font-weight: 600; }
.lyrics-line.lyrics-past { opacity: 0.55; }
.lyrics-line.lyrics-now { opacity: 1; transform: none; }
.lyrics-plain { font-size: 26px; font-weight: 600; line-height: 1.7; }
.lyrics-message { font-size: 20px; font-weight: 600; }
";

thread_local! {
    /// 背景のグラデーション（ジャケットの色）。開いている歌詞のページは 1 枚なので、窓全体で 1 つを書き換える。
    static BACKDROP: gtk::CssProvider = {
        let provider = gtk::CssProvider::new();
        if let Some(display) = gtk::gdk::Display::default() {
            let style = gtk::CssProvider::new();
            style.load_from_string(STYLE);
            gtk::style_context_add_provider_for_display(&display, &style, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
            gtk::style_context_add_provider_for_display(&display, &provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
        }
        provider
    };
}

fn set_backdrop(color: Option<images::Rgb>) {
    let css = match color {
        Some(images::Rgb(r, g, b)) => {
            let (r, g, b) = ((r * 255.0).round(), (g * 255.0).round(), (b * 255.0).round());
            format!(".lyrics-page {{ background-image: linear-gradient(to bottom, rgba({r},{g},{b},0.67), rgba({r},{g},{b},0.16)); }}")
        }
        None => String::new(),
    };
    BACKDROP.with(|p| p.load_from_string(&css));
}

/// ページの中身（ページが閉じられたら一緒に捨てる）。
struct Lyrics {
    app: Rc<App>,
    column: gtk::Box,
    scroller: gtk::ScrolledWindow,
    stack: gtk::Stack,
    message: gtk::Label,
    retry: gtk::Button,
    source: gtk::Label,
    lines: RefCell<Vec<LyricLine>>,
    labels: RefCell<Vec<gtk::Label>>,
    /// 出している曲（video_id。曲が無いときは Some(None)、まだ何も出していなければ None）。
    shown_song: RefCell<Option<Option<String>>>,
    /// 歌っている行の範囲。
    current: Cell<Option<(usize, usize)>>,
}

impl Lyrics {
    fn showing(&self, video_id: &str) -> bool {
        self.shown_song.borrow().as_ref().and_then(|s| s.as_deref()) == Some(video_id)
    }
}

/// 今の曲（ほかの端末を映していればその端末の曲）。
fn now_song(app: &App) -> Option<Song> {
    app.connect.mirror().map(|m| m.song).or_else(|| app.playback.current())
}

/// 今の再生位置（ほかの端末を映していればその端末の位置）。
fn now_position_ms(app: &App) -> u64 {
    app.connect.mirror().map(|m| m.position_ms.max(0) as u64).unwrap_or_else(|| app.playback.position_ms())
}

pub fn page(app: &Rc<App>) -> adw::NavigationPage {
    BACKDROP.with(|_| ());
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).margin_top(18).margin_bottom(240).margin_start(40).margin_end(40).build();
    let scroller = gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();
    let message = gtk::Label::builder().wrap(true).justify(gtk::Justification::Center).css_classes(["lyrics-message"]).build();
    let retry = gtk::Button::builder().label("もう一度").halign(gtk::Align::Center).visible(false).css_classes(["pill"]).build();
    let status = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).halign(gtk::Align::Center).valign(gtk::Align::Center).build();
    status.append(&message);
    status.append(&retry);
    let stack = gtk::Stack::builder().vexpand(true).build();
    stack.add_named(&scroller, Some("lines"));
    stack.add_named(&status, Some("status"));
    // 出典は歌詞に重ねず、下の段に置く
    let source = gtk::Label::builder()
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .margin_top(8)
        .margin_bottom(12)
        .margin_start(40)
        .margin_end(40)
        .css_classes(["caption", "dim-label"])
        .build();
    let root = gtk::Box::builder().orientation(gtk::Orientation::Vertical).css_classes(["lyrics-page"]).build();
    root.append(&stack);
    root.append(&source);

    let state = Rc::new(Lyrics {
        app: app.clone(),
        column,
        scroller,
        stack,
        message,
        retry,
        source,
        lines: RefCell::default(),
        labels: RefCell::default(),
        shown_song: RefCell::default(),
        current: Cell::default(),
    });
    state.retry.connect_clicked({
        let weak = Rc::downgrade(&state);
        move |_| {
            if let Some(state) = weak.upgrade() {
                *state.shown_song.borrow_mut() = None;
                load(&state);
            }
        }
    });
    load(&state);
    let weak = Rc::downgrade(&state);
    let on_change = move || {
        if let Some(state) = weak.upgrade() {
            load(&state);
        }
    };
    app.playback.on_change(on_change.clone());
    // ほかの端末を映し始めた・やめた・その端末の曲が変わったときも
    app.connect.on_change(on_change);

    // 歌っている行を 0.2 秒ごとに合わせる
    let weak: Weak<Lyrics> = Rc::downgrade(&state);
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(200), move || {
        let Some(state) = weak.upgrade() else { return gtk::glib::ControlFlow::Break };
        follow(&state);
        gtk::glib::ControlFlow::Continue
    });

    let page = adw::NavigationPage::builder().title("歌詞").tag("lyrics").child(&root).build();
    // ページが捨てられるまで中身を持っておく
    page.connect_destroy(move |_| {
        let _ = &state;
    });
    page
}

fn show_message(state: &Lyrics, text: &str, retry: bool) {
    state.message.set_label(text);
    state.retry.set_visible(retry);
    state.stack.set_visible_child_name("status");
}

/// 曲が変わっていたら、その曲の歌詞を出し直す。
fn load(state: &Rc<Lyrics>) {
    let song = now_song(&state.app);
    let id = song.as_ref().map(|s| s.video_id.clone());
    if state.shown_song.borrow().as_ref() == Some(&id) {
        return;
    }
    *state.shown_song.borrow_mut() = Some(id);
    while let Some(child) = state.column.first_child() {
        state.column.remove(&child);
    }
    state.lines.borrow_mut().clear();
    state.labels.borrow_mut().clear();
    state.current.set(None);
    state.source.set_label("");
    let Some(song) = song else {
        set_backdrop(None);
        show_message(state, "曲を再生すると、ここに歌詞が出ます", false);
        return;
    };
    set_backdrop(None);
    let (weak, video_id) = (Rc::downgrade(state), song.video_id.clone());
    images::dominant_color(song.thumbnail_url.as_deref(), move |color| {
        // 色を求めている間に曲が変わっていたら捨てる
        if weak.upgrade().is_some_and(|s| s.showing(&video_id)) {
            set_backdrop(color);
        }
    });
    if song.is_live {
        show_message(state, "ライブ配信には歌詞がありません", false);
        return;
    }
    // ポッドキャストのエピソードなど YouTube Music の外の音には歌詞を探さない
    if wave_core::music::feed::is_external(&song.video_id) {
        show_message(state, "この曲の歌詞は見つかりませんでした", false);
        return;
    }
    show_message(state, "歌詞を探しています…", false);
    let state = state.clone();
    gtk::glib::spawn_future_local(async move {
        let service = state.app.lyrics.clone();
        let wanted = song.clone();
        let result = runtime::run(async move { service.lyrics(&wanted).await }).await;
        // 読んでいる間に曲が変わっていたら捨てる
        if !state.showing(&song.video_id) {
            return;
        }
        match result {
            LyricsResult::Found(SongLyrics::Synced { lines, source }) => {
                show_synced(&state, lines);
                if let Some(source) = source {
                    state.source.set_label(&wave_desktop::labels::lyrics_source(&source));
                }
                state.stack.set_visible_child_name("lines");
                follow(&state);
            }
            LyricsResult::Found(SongLyrics::Plain { text, source }) => {
                let label = gtk::Label::builder().label(&text).xalign(0.0).wrap(true).selectable(true).margin_top(22).css_classes(["lyrics-plain"]).build();
                state.column.append(&label);
                state.source.set_label(source.as_deref().unwrap_or(""));
                state.stack.set_visible_child_name("lines");
            }
            LyricsResult::None => show_message(&state, "この曲の歌詞は見つかりませんでした", false),
            LyricsResult::Failed => show_message(&state, "歌詞を読み込めませんでした", true),
        }
    });
}

fn show_synced(state: &Rc<Lyrics>, lines: Vec<LyricLine>) {
    for (i, line) in lines.iter().enumerate() {
        // 同じ時刻の行（原文に続く読み・訳）は、前の行に添える小さな行として出す
        let sub = lrc::is_sub_line(&lines, i);
        let label = gtk::Label::builder()
            .label(if line.text.trim().is_empty() { "♪" } else { &line.text })
            .xalign(0.0)
            .wrap(true)
            .margin_top(if sub { 2 } else { 22 })
            .css_classes(if sub { ["lyrics-line", "lyrics-sub"].as_slice() } else { ["lyrics-line"].as_slice() })
            .build();
        let click = gtk::GestureClick::new();
        let (app, start) = (state.app.clone(), line.start_ms);
        // ほかの端末を映していれば、その端末をシークする
        click.connect_released(move |_, _, _, _| match app.connect.mirror() {
            Some(_) => app.connect.remote_seek(start as i64),
            None => app.playback.player().seek_ms(start),
        });
        label.add_controller(click);
        label.set_cursor_from_name(Some("pointer"));
        state.column.append(&label);
        state.labels.borrow_mut().push(label);
    }
    *state.lines.borrow_mut() = lines;
}

/// 歌っている行を明るくし、過ぎた行を少し薄く、先の行をもっと薄くする。変わったら真ん中より少し上へ寄せる。
fn follow(state: &Lyrics) {
    let now = lrc::current_range(&state.lines.borrow(), now_position_ms(&state.app));
    if now == state.current.get() {
        return;
    }
    state.current.set(now);
    let labels = state.labels.borrow();
    for (i, label) in labels.iter().enumerate() {
        let (current, past) = match now {
            Some((first, last)) => (first <= i && i <= last, i < first),
            None => (false, false),
        };
        set_class(label, "lyrics-now", current);
        set_class(label, "lyrics-past", past);
    }
    if let Some(label) = now.and_then(|(first, _)| labels.get(first)) {
        scroll_to(&state.scroller, label);
    }
}

fn set_class(widget: &impl IsA<gtk::Widget>, class: &str, on: bool) {
    if on {
        widget.add_css_class(class);
    } else {
        widget.remove_css_class(class);
    }
}

/// その行が見える範囲の真ん中より少し上（上から 35%）に来るように、なめらかに送る。
fn scroll_to(scroller: &gtk::ScrolledWindow, label: &gtk::Label) {
    let Some(child) = scroller.child() else { return };
    let Some(bounds) = label.compute_bounds(&child) else { return };
    let adjustment = scroller.vadjustment();
    let target = (f64::from(bounds.y()) + f64::from(bounds.height()) * 0.35 - adjustment.page_size() * 0.35)
        .clamp(adjustment.lower(), (adjustment.upper() - adjustment.page_size()).max(adjustment.lower()));
    let from = adjustment.value();
    let target_fn = adw::CallbackAnimationTarget::new(move |value| adjustment.set_value(value));
    let animation = adw::TimedAnimation::builder()
        .widget(scroller)
        .value_from(from)
        .value_to(target)
        .duration(450)
        .easing(adw::Easing::EaseInOutCubic)
        .target(&target_fn)
        .build();
    animation.play();
}
