//! 全画面の再生中の画面（Mac の FullView。下の帯の全画面のボタン・F11 で出す）。上の帯とページの段を覆い、下の帯は出したまま。
//! 背景にジャケットのぼかし、左に大きなジャケットか映像と曲名・アーティスト（押せる）、右に流れる歌詞。右上の終えるボタン・Esc で戻る。
//! 流れる歌詞（lyrics_follower）は、右の欄の再生中の歌詞の箱でも使う。

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use wave_core::lyrics::service::LyricsResult;
use wave_core::lyrics::{LyricLine, SongLyrics, lrc};

use super::images;
use super::shell::{self, App, Place};
use crate::runtime;

pub fn build(app: &Rc<App>) -> gtk::Widget {
    // 背景: ジャケットを薄く広げ、下ほど暗くする
    let background = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).hexpand(true).vexpand(true).build();
    background.set_opacity(0.25);
    let root = gtk::Overlay::builder().child(&background).css_classes(["full-view"]).build();
    root.set_overflow(gtk::Overflow::Hidden);
    root.add_overlay(&gtk::Box::builder().hexpand(true).vexpand(true).css_classes(["full-shade"]).build());

    // 左に曲（3）、右に歌詞（2）。Mac・Windows と同じ割り付け
    let switch = shell::song_video_switch(app);
    switch.set_halign(gtk::Align::Start);
    let media = shell::video_or_cover(app, Place::Full);
    let title = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["full-title"]).build();
    let artist = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["full-artist"]).build();
    shell::album_link(app, &title);
    shell::artist_link(app, &artist);
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    text.append(&title);
    text.append(&artist);
    let left = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(20).build();
    left.append(&switch);
    left.append(&media);
    left.append(&text);
    // 右の歌詞は歌詞のページ（ui/lyrics.rs）をそのまま大きく出す。背景は全画面の画面のものを使うので、ページのジャケットの色の背景は外す
    let lyrics = super::lyrics::page(app);
    if let Some(child) = lyrics.child() {
        child.remove_css_class("lyrics-page");
    }
    lyrics.set_vexpand(true);
    let grid = gtk::Grid::builder().column_homogeneous(true).column_spacing(56).margin_top(56).margin_start(56).margin_end(56).margin_bottom(32).build();
    grid.attach(&left, 0, 0, 3, 1);
    grid.attach(&lyrics, 3, 0, 2, 1);
    root.add_overlay(&grid);

    let exit = gtk::Button::builder()
        .icon_name("view-restore-symbolic")
        .tooltip_text("全画面を終える (Esc)")
        .halign(gtk::Align::End)
        .valign(gtk::Align::Start)
        .margin_top(16)
        .margin_end(16)
        .css_classes(["flat", "circular"])
        .build();
    {
        let app = app.clone();
        exit.connect_clicked(move |_| app.set_full_screen(false));
    }
    root.add_overlay(&exit);

    let shown: Rc<RefCell<Option<String>>> = Rc::default();
    let refresh = {
        let weak = Rc::downgrade(app);
        move || {
            let Some(app) = weak.upgrade() else { return };
            let song = app.shown_song();
            let id = song.as_ref().map(|s| s.video_id.clone());
            if *shown.borrow() == id {
                return;
            }
            *shown.borrow_mut() = id;
            images::load(&background, song.as_ref().and_then(|s| s.thumbnail_url.as_deref()), 600);
            title.set_label(song.as_ref().map_or("", |s| s.title.as_str()));
            artist.set_label(&song.as_ref().map(|s| s.artists_text()).unwrap_or_default());
        }
    };
    let refresh = Rc::new(refresh);
    refresh();
    let r = refresh.clone();
    app.playback.on_change(move || r());
    app.connect.on_change(move || refresh());
    root.upcast()
}

/// 流れる歌詞: 時刻付きなら歌っている行を強調して上から4割の所へなめらかに送り、時刻なしは本文をそのまま出す。
/// big は全画面の大きな字（行を押すとその時刻へ飛び、手で送れる）、そうでなければ右の欄の箱の小さな字（送るのは自動だけ）。
/// on_found には、この曲の歌詞があるかを渡す（無い曲では箱ごと隠すのに使う）。
pub(super) fn lyrics_follower(app: &Rc<App>, big: bool, on_found: impl Fn(bool) + 'static) -> gtk::ScrolledWindow {
    let column = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(if big { 14 } else { 6 })
        .margin_bottom(if big { 320 } else { 120 })
        .css_classes([if big { "follow-big" } else { "follow-small" }])
        .build();
    let scroller = gtk::ScrolledWindow::builder()
        .child(&column)
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vscrollbar_policy(if big { gtk::PolicyType::Automatic } else { gtk::PolicyType::External })
        .vexpand(true)
        .build();
    let on_found: Rc<dyn Fn(bool)> = Rc::new(on_found);
    let lines: Rc<RefCell<Vec<LyricLine>>> = Rc::default();
    let labels: Rc<RefCell<Vec<gtk::Label>>> = Rc::default();
    let shown_song: Rc<RefCell<Option<Option<String>>>> = Rc::default();
    let highlighted: Rc<Cell<Option<usize>>> = Rc::default();

    let load = {
        let (weak, column, scroller, lines, labels, shown_song, highlighted) =
            (Rc::downgrade(app), column.clone(), scroller.clone(), lines.clone(), labels.clone(), shown_song.clone(), highlighted.clone());
        move || {
            let Some(app) = weak.upgrade() else { return };
            let song = app.shown_song();
            let id = song.as_ref().map(|s| s.video_id.clone());
            if shown_song.borrow().as_ref() == Some(&id) {
                return;
            }
            *shown_song.borrow_mut() = Some(id);
            while let Some(child) = column.first_child() {
                column.remove(&child);
            }
            lines.borrow_mut().clear();
            labels.borrow_mut().clear();
            highlighted.set(None);
            scroller.vadjustment().set_value(0.0);
            let Some(song) = song else {
                on_found(false);
                return;
            };
            let (column, lines, labels, shown_song, on_found) = (column.clone(), lines.clone(), labels.clone(), shown_song.clone(), on_found.clone());
            gtk::glib::spawn_future_local(async move {
                let service = app.lyrics.clone();
                let wanted = song.clone();
                let result = runtime::run(async move { service.lyrics(&wanted).await }).await;
                // 読んでいる間に曲が変わっていたら捨てる
                if *shown_song.borrow() != Some(Some(song.video_id.clone())) {
                    return;
                }
                match result {
                    LyricsResult::Found(SongLyrics::Synced { lines: found, .. }) => {
                        for line in &found {
                            let label = gtk::Label::builder().label(if line.text.is_empty() { "♪" } else { &line.text }).xalign(0.0).wrap(true).css_classes(["follow-line"]).build();
                            if big {
                                let click = gtk::GestureClick::new();
                                let (app, start) = (app.clone(), line.start_ms);
                                click.connect_released(move |_, _, _, _| app.seek_ms(start));
                                label.add_controller(click);
                                label.set_cursor_from_name(Some("pointer"));
                            }
                            column.append(&label);
                            labels.borrow_mut().push(label);
                        }
                        *lines.borrow_mut() = found;
                        on_found(true);
                    }
                    LyricsResult::Found(SongLyrics::Plain { text, .. }) => {
                        column.append(&gtk::Label::builder().label(&text).xalign(0.0).wrap(true).css_classes(["follow-plain"]).build());
                        on_found(true);
                    }
                    LyricsResult::None | LyricsResult::Failed => on_found(false),
                }
            });
        }
    };
    let load = Rc::new(load);
    load();
    let l = load.clone();
    app.playback.on_change(move || l());
    app.connect.on_change(move || load());

    // 歌っている行を 0.2 秒ごとに合わせ、変わったら寄せる（見えていない間は何もしない）
    let (weak_app, weak_scroller) = (Rc::downgrade(app), scroller.downgrade());
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(200), move || {
        let (Some(app), Some(scroller)) = (weak_app.upgrade(), weak_scroller.upgrade()) else { return gtk::glib::ControlFlow::Break };
        if !scroller.is_mapped() {
            return gtk::glib::ControlFlow::Continue;
        }
        let now = lrc::current_index(&lines.borrow(), app.position_ms());
        if now != highlighted.get() {
            let labels = labels.borrow();
            if let Some(old) = highlighted.get().and_then(|i| labels.get(i)) {
                old.remove_css_class("follow-current");
            }
            if let Some(label) = now.and_then(|i| labels.get(i)) {
                label.add_css_class("follow-current");
                center_on(&scroller, label);
            }
            highlighted.set(now);
        }
        gtk::glib::ControlFlow::Continue
    });
    scroller
}

/// その行が見える範囲の上から4割の所に来るように、なめらかに送る。
fn center_on(scroller: &gtk::ScrolledWindow, label: &gtk::Label) {
    let Some(child) = scroller.child() else { return };
    let Some(bounds) = label.compute_bounds(&child) else { return };
    let adjustment = scroller.vadjustment();
    let target = (bounds.y() as f64 + bounds.height() as f64 / 2.0 - adjustment.page_size() * 0.4)
        .clamp(adjustment.lower(), (adjustment.upper() - adjustment.page_size()).max(adjustment.lower()));
    let from = adjustment.value();
    let target_fn = adw::CallbackAnimationTarget::new(move |value| adjustment.set_value(value));
    let animation = adw::TimedAnimation::builder().widget(scroller).value_from(from).value_to(target).duration(350).target(&target_fn).build();
    animation.play();
}
