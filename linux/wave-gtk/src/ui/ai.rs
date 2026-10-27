//! 右の欄の Claude（Mac・Windows と同じく、上の帯の検索の欄の右のボタンで開く）。
//! つないでいなければ接続の文字列（waveai://pair?…）を貼る欄を出す。答えの中の [mm:ss] は押すとその位置へ飛ぶ（話している曲が鳴っているときだけ）。

use std::rc::Rc;

use adw::prelude::*;
use wave_core::ai::text;

use super::pages;
use super::shell::App;
use crate::ai_chat::AiMessage;

/// 頼みの例（押すとそのまま送る）。
const SUGGESTIONS: [&str; 2] = ["この歌詞の意味は？", "似た曲を探して"];

pub fn panel(app: &Rc<App>) -> gtk::Widget {
    let stack = gtk::Stack::new();
    stack.add_named(&pairing(app), Some("pair"));
    stack.add_named(&conversation(app), Some("chat"));
    let refresh = {
        let (app, stack) = (app.clone(), stack.clone());
        move || stack.set_visible_child_name(if app.ai.configured().is_some() { "chat" } else { "pair" })
    };
    refresh();
    app.ai.on_change(refresh);
    stack.upcast()
}

/// つなぐ前: 中継の接続の文字列を貼る。
fn pairing(app: &Rc<App>) -> gtk::Widget {
    let entry = gtk::Entry::builder().placeholder_text("waveai://pair?…").hexpand(true).build();
    let connect = gtk::Button::builder().label("つなぐ").css_classes(["suggested-action", "pill"]).halign(gtk::Align::Center).build();
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
    column.append(&entry);
    column.append(&connect);
    let page = adw::StatusPage::builder()
        .icon_name("chat-message-new-symbolic")
        .title("Claude につなぐ")
        .description("中継（ai-bridge）の接続の文字列を貼ってください。ほかの端末の設定の「Claude」で出る QR の中身と同じものです")
        .child(&column)
        .vexpand(true)
        .build();
    let app = app.clone();
    let submit = move || {
        let (app, text) = (app.clone(), entry.text().to_string());
        let entry = entry.clone();
        gtk::glib::spawn_future_local(async move {
            if app.ai.pair(&text).await {
                entry.set_text("");
                app.toast("Claude につなぎました");
            } else {
                app.toast("接続の文字列を読めませんでした");
            }
        });
    };
    let submit = Rc::new(submit);
    let s = submit.clone();
    connect.connect_clicked(move |_| s());
    page.upcast()
}

/// つないだ後: 会話・頼みの例・入力の欄。
fn conversation(app: &Rc<App>) -> gtk::Widget {
    let title = gtk::Label::builder().label("Claude に聞く").xalign(0.0).hexpand(true).css_classes(["title-3"]).build();
    let new_chat = gtk::Button::builder().icon_name("document-new-symbolic").tooltip_text("新しい会話").css_classes(["flat", "circular"]).build();
    let unpair = gtk::Button::builder().icon_name("network-offline-symbolic").tooltip_text("つなぎ先を外す").css_classes(["flat", "circular"]).build();
    let header = gtk::Box::builder().spacing(4).margin_top(12).margin_start(16).margin_end(8).build();
    header.append(&title);
    header.append(&history_button(app));
    header.append(&new_chat);
    header.append(&unpair);

    let messages = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(10).margin_start(12).margin_end(12).margin_top(8).margin_bottom(8).build();
    let empty = gtk::Label::builder()
        .label("歌詞の意味・似た曲・プレイリスト作りなどを頼めます。今の曲と歌詞を添えて送ります")
        .wrap(true)
        .xalign(0.0)
        .css_classes(["dim-label"])
        .build();
    let scroller = gtk::ScrolledWindow::builder().child(&messages).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();

    let suggestions = gtk::FlowBox::builder().selection_mode(gtk::SelectionMode::None).max_children_per_line(2).margin_start(12).margin_end(12).build();
    let entry = gtk::Entry::builder().placeholder_text("Claude に聞く").hexpand(true).build();
    let send = gtk::Button::builder().icon_name("mail-send-symbolic").tooltip_text("送る").css_classes(["circular", "suggested-action"]).build();
    let input = gtk::Box::builder().spacing(6).margin_start(12).margin_end(12).margin_bottom(12).margin_top(4).build();
    input.append(&entry);
    input.append(&send);

    let column = gtk::Box::new(gtk::Orientation::Vertical, 4);
    column.append(&header);
    column.append(&scroller);
    column.append(&suggestions);
    column.append(&input);

    let send_text = {
        let app = app.clone();
        Rc::new(move |text: &str| app.ai.send(&app, text))
    };
    for suggestion in SUGGESTIONS {
        let button = gtk::Button::builder().label(suggestion).css_classes(["pill", "suggestion"]).build();
        let send_text = send_text.clone();
        button.connect_clicked(move |_| send_text(suggestion));
        suggestions.append(&button);
    }
    {
        let (app, entry_for_send) = (app.clone(), entry.clone());
        let send_for_button = send_text.clone();
        send.connect_clicked(move |_| {
            // 答えを待っている間は止めるボタン
            if app.ai.running.get() {
                app.ai.stop();
                return;
            }
            send_for_button(&entry_for_send.text());
            entry_for_send.set_text("");
        });
        let send_text = send_text.clone();
        entry.connect_activate(move |e| {
            send_text(&e.text());
            e.set_text("");
        });
    }
    {
        let app = app.clone();
        new_chat.connect_clicked(move |_| app.ai.clear());
    }
    {
        let app = app.clone();
        unpair.connect_clicked(move |_| {
            let app = app.clone();
            gtk::glib::spawn_future_local(async move { app.ai.unpair().await });
        });
    }

    let refresh = {
        let (app, messages, scroller, send, suggestions) = (app.clone(), messages.clone(), scroller.clone(), send.clone(), suggestions.clone());
        move || {
            while let Some(child) = messages.first_child() {
                messages.remove(&child);
            }
            let list = app.ai.messages.borrow().clone();
            if list.is_empty() {
                messages.append(&empty);
            }
            for message in list {
                messages.append(&message_widget(&app, message));
            }
            let running = app.ai.running.get();
            if running {
                messages.append(&adw::Spinner::builder().halign(gtk::Align::Start).build());
            }
            send.set_icon_name(if running { "media-playback-stop-symbolic" } else { "mail-send-symbolic" });
            send.set_tooltip_text(Some(if running { "止める" } else { "送る" }));
            suggestions.set_visible(!running);
            // 新しい発言が見えるよう、描き終わってから一番下へ
            let adjustment = scroller.vadjustment();
            gtk::glib::idle_add_local_once(move || adjustment.set_value(adjustment.upper()));
        }
    };
    refresh();
    app.ai.on_change(refresh);
    column.upcast()
}

fn message_widget(app: &Rc<App>, message: AiMessage) -> gtk::Widget {
    match message {
        AiMessage::User(text) => gtk::Label::builder().label(text).wrap(true).xalign(0.0).halign(gtk::Align::End).css_classes(["ai-user"]).build().upcast(),
        AiMessage::Assistant(text, video_id) => {
            let label = gtk::Label::builder().use_markup(true).label(markup_with_timestamps(&text)).wrap(true).xalign(0.0).selectable(true).build();
            // [mm:ss] を押すと、その曲が鳴っていればその位置へ飛ぶ
            let app = app.clone();
            label.connect_activate_link(move |_, uri| {
                let Some(ms) = uri.strip_prefix("seek:").and_then(|v| v.parse::<u64>().ok()) else { return gtk::glib::Propagation::Proceed };
                if video_id.is_some() && app.playback.current().map(|s| s.video_id) == video_id {
                    app.playback.player().seek_ms(ms);
                } else {
                    app.toast("話している曲が鳴っていないので飛べません");
                }
                gtk::glib::Propagation::Stop
            });
            label.upcast()
        }
        AiMessage::Action(text) => gtk::Label::builder().label(text).wrap(true).xalign(0.0).css_classes(["caption", "dim-label"]).build().upcast(),
        AiMessage::Songs(songs) => pages::song_list(app, songs).upcast(),
        AiMessage::Failure(text) => gtk::Label::builder().label(text).wrap(true).xalign(0.0).css_classes(["error"]).build().upcast(),
    }
}

/// これまでの会話（押すと開いて続ける。右の × で消す）。
fn history_button(app: &Rc<App>) -> gtk::MenuButton {
    // 題名が読める幅にする（決めないと題名が1文字まで縮む）
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).width_request(320).css_classes(["navigation-sidebar"]).build();
    let scroller = gtk::ScrolledWindow::builder().child(&list).propagate_natural_height(true).propagate_natural_width(true).max_content_height(420).hscrollbar_policy(gtk::PolicyType::Never).build();
    let popover = gtk::Popover::builder().child(&scroller).build();
    let button = gtk::MenuButton::builder().icon_name("document-open-recent-symbolic").tooltip_text("これまでの会話").popover(&popover).css_classes(["flat", "circular"]).build();
    let app = app.clone();
    popover.connect_show(move |popover| {
        while let Some(row) = list.first_child() {
            list.remove(&row);
        }
        let conversations = app.ai.history.borrow().clone();
        if conversations.is_empty() {
            list.append(&gtk::Label::builder().label("まだ会話はありません").margin_top(12).margin_bottom(12).css_classes(["dim-label"]).build());
        }
        for c in conversations {
            let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&c.title)).title_lines(2).activatable(true).build();
            let delete = gtk::Button::builder().icon_name("user-trash-symbolic").tooltip_text("消す").valign(gtk::Align::Center).css_classes(["flat", "circular"]).build();
            row.add_suffix(&delete);
            let (app_open, id_open, pop) = (app.clone(), c.id.clone(), popover.clone());
            row.connect_activated(move |_| {
                app_open.ai.open(&id_open);
                pop.popdown();
            });
            let (app_del, id_del, pop) = (app.clone(), c.id.clone(), popover.clone());
            delete.connect_clicked(move |_| {
                app_del.ai.delete(&id_del);
                pop.popdown();
            });
            list.append(&row);
        }
    });
    button
}

/// 答えを Pango の書式にする（そのままの文字は逃がし、[mm:ss] だけを押せるリンクにする）。
fn markup_with_timestamps(text: &str) -> String {
    let mut out = String::new();
    let mut at = 0;
    for stamp in text::timestamps(text) {
        out.push_str(&gtk::glib::markup_escape_text(&text[at..stamp.start]));
        out.push_str(&format!("<a href=\"seek:{}\">{}</a>", stamp.position_ms, gtk::glib::markup_escape_text(&text[stamp.start..stamp.end])));
        at = stamp.end;
    }
    out.push_str(&gtk::glib::markup_escape_text(&text[at..]));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timestamps_become_links_and_the_rest_is_escaped() {
        assert_eq!(markup_with_timestamps("a<b [01:23] c"), "a&lt;b <a href=\"seek:83000\">[01:23]</a> c");
    }
}
