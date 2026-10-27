//! 中央の歌詞のページ（Windows の歌詞のページと同じ。下の帯の歌詞のボタンで開く）。
//! 時刻付きなら歌っている行を強調して真ん中へ寄せ続け、行を押すとその時刻へ飛ぶ。時刻なしは本文をそのまま出す。

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use wave_core::lyrics::service::LyricsResult;
use wave_core::lyrics::{LyricLine, SongLyrics, lrc};

use super::shell::App;
use crate::runtime;

pub fn page(app: &Rc<App>) -> adw::NavigationPage {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(14).margin_top(24).margin_bottom(240).margin_start(32).margin_end(32).build();
    let scroller = gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();
    let status = adw::StatusPage::builder().icon_name("text-x-generic-symbolic").vexpand(true).build();
    let stack = gtk::Stack::new();
    stack.add_named(&scroller, Some("lines"));
    stack.add_named(&status, Some("status"));

    let lines: Rc<RefCell<Vec<LyricLine>>> = Rc::default();
    let labels: Rc<RefCell<Vec<gtk::Label>>> = Rc::default();
    let shown_song: Rc<RefCell<Option<String>>> = Rc::default();
    let highlighted: Rc<Cell<Option<usize>>> = Rc::default();

    let load = {
        let (app, column, stack, status, lines, labels, shown_song, highlighted) =
            (app.clone(), column.clone(), stack.clone(), status.clone(), lines.clone(), labels.clone(), shown_song.clone(), highlighted.clone());
        move || {
            let song = app.playback.current();
            let id = song.as_ref().map(|s| s.video_id.clone());
            if *shown_song.borrow() == id {
                return;
            }
            *shown_song.borrow_mut() = id.clone();
            while let Some(child) = column.first_child() {
                column.remove(&child);
            }
            lines.borrow_mut().clear();
            labels.borrow_mut().clear();
            highlighted.set(None);
            let Some(song) = song else {
                status.set_title("再生していません");
                stack.set_visible_child_name("status");
                return;
            };
            status.set_title("歌詞を探しています…");
            stack.set_visible_child_name("status");
            let (app, column, stack, status, lines, labels, shown_song) =
                (app.clone(), column.clone(), stack.clone(), status.clone(), lines.clone(), labels.clone(), shown_song.clone());
            gtk::glib::spawn_future_local(async move {
                let service = app.lyrics.clone();
                let wanted = song.clone();
                let result = runtime::run(async move { service.lyrics(&wanted).await }).await;
                // 読んでいる間に曲が変わっていたら捨てる
                if *shown_song.borrow() != Some(song.video_id.clone()) {
                    return;
                }
                match result {
                    LyricsResult::Found(SongLyrics::Synced { lines: found, source }) => {
                        for line in &found {
                            let label = gtk::Label::builder()
                                .label(if line.text.is_empty() { "♪" } else { &line.text })
                                .xalign(0.0)
                                .wrap(true)
                                .css_classes(["lyric-line"])
                                .build();
                            let click = gtk::GestureClick::new();
                            let (app, start) = (app.clone(), line.start_ms);
                            click.connect_released(move |_, _, _, _| app.playback.player().seek_ms(start));
                            label.add_controller(click);
                            label.set_cursor_from_name(Some("pointer"));
                            column.append(&label);
                            labels.borrow_mut().push(label);
                        }
                        *lines.borrow_mut() = found;
                        append_source(&column, source.as_deref());
                        stack.set_visible_child_name("lines");
                    }
                    LyricsResult::Found(SongLyrics::Plain { text, source }) => {
                        column.append(&gtk::Label::builder().label(&text).xalign(0.0).wrap(true).selectable(true).css_classes(["lyric-plain"]).build());
                        append_source(&column, source.as_deref());
                        stack.set_visible_child_name("lines");
                    }
                    LyricsResult::None => {
                        status.set_title("この曲の歌詞は見つかりませんでした");
                    }
                    LyricsResult::Failed => {
                        status.set_title("歌詞を読み込めませんでした");
                    }
                }
            });
        }
    };
    load();
    app.playback.on_change(load);

    // 歌っている行を 0.2 秒ごとに合わせ、変わったら真ん中へ寄せる
    let (app_for_tick, weak_scroller) = (app.clone(), scroller.downgrade());
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(200), move || {
        let Some(scroller) = weak_scroller.upgrade() else { return gtk::glib::ControlFlow::Break };
        let position = app_for_tick.playback.player().position_ms().unwrap_or(0);
        let now = lrc::current_index(&lines.borrow(), position);
        if now != highlighted.get() {
            let labels = labels.borrow();
            if let Some(old) = highlighted.get().and_then(|i| labels.get(i)) {
                old.remove_css_class("lyric-current");
            }
            if let Some(label) = now.and_then(|i| labels.get(i)) {
                label.add_css_class("lyric-current");
                center_on(&scroller, label);
            }
            highlighted.set(now);
        }
        gtk::glib::ControlFlow::Continue
    });

    adw::NavigationPage::builder().title("歌詞").tag("lyrics").child(&stack).build()
}

fn append_source(column: &gtk::Box, source: Option<&str>) {
    if let Some(source) = source {
        column.append(&gtk::Label::builder().label(source).xalign(0.0).margin_top(16).css_classes(["caption", "dim-label"]).build());
    }
}

/// その行が見える範囲の真ん中より少し上に来るように、なめらかに送る。
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
