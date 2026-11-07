//! 右の欄の Claude（Mac・Windows と同じく、上の帯の検索の欄の右のボタンで開く）。
//! つなぐのは設定の「Claude」（つないでいないまま送ると、そこでつなぐよう答える）。答えの中の [mm:ss] は押すとその位置へ飛ぶ（話している曲が鳴っているときだけ）。

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use wave_core::ai::text;

use super::pages;
use super::shell::App;
use crate::ai_chat::AiMessage;

/// 頼みの例（押すとそのまま送る。Mac の AiView と同じ）。
const SUGGESTIONS: [&str; 3] = ["この歌詞ってどういう意味だと思う？", "これに似た曲ある？", "この曲から始まるプレイリストを作って"];

/// 右の欄の中身: 上の段（これまでの会話・新しい会話・閉じる）・会話・頼みの例・入力の欄。
/// on_close は × を押したとき（右の欄を Claude を開く前の状態に戻す。右の欄の切り替えは shell の側が持つ）。
pub fn panel(app: &Rc<App>, on_close: impl Fn() + 'static) -> gtk::Widget {
    add_style();
    let title = gtk::Label::builder().label("Claude に聞く").xalign(0.0).hexpand(true).css_classes(["heading"]).build();
    let new_chat = gtk::Button::builder().label("新しい会話").css_classes(["flat"]).build();
    let close = gtk::Button::builder().icon_name("window-close-symbolic").tooltip_text("閉じる").css_classes(["flat", "circular"]).build();
    let header = gtk::Box::builder().spacing(4).margin_top(12).margin_start(16).margin_end(8).build();
    header.append(&title);
    header.append(&history_button(app));
    header.append(&new_chat);
    header.append(&close);
    close.connect_clicked(move |_| on_close());

    let messages = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(10).margin_start(16).margin_end(16).margin_top(8).margin_bottom(16).build();
    let empty = gtk::Label::builder()
        .label("曲探し・プレイリスト作りを頼めます。再生中の曲について、歌詞の意味や似た曲も聞けます。")
        .wrap(true)
        .xalign(0.0)
        .css_classes(["caption", "dim-label"])
        .build();
    let scroller = gtk::ScrolledWindow::builder().child(&messages).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();

    let suggestions = gtk::Box::builder().orientation(gtk::Orientation::Vertical).margin_start(10).margin_end(16).margin_top(4).margin_bottom(4).build();
    // 入力は複数行（Enter で送り、Shift+Enter で改行。変換中の Enter は確定）
    let input_view = gtk::TextView::builder()
        .wrap_mode(gtk::WrapMode::WordChar)
        .accepts_tab(false)
        .top_margin(8)
        .bottom_margin(8)
        .left_margin(10)
        .right_margin(10)
        .build();
    input_view.update_property(&[gtk::accessible::Property::Placeholder("Claude に聞く")]);
    let placeholder = gtk::Label::builder()
        .label("Claude に聞く")
        .xalign(0.0)
        .halign(gtk::Align::Start)
        .valign(gtk::Align::Start)
        .margin_top(8)
        .margin_start(10)
        .can_target(false)
        .css_classes(["dim-label"])
        .build();
    let input_scroller = gtk::ScrolledWindow::builder()
        .child(&input_view)
        .hscrollbar_policy(gtk::PolicyType::Never)
        .propagate_natural_height(true)
        .max_content_height(140)
        .hexpand(true)
        .build();
    let input_overlay = gtk::Overlay::builder().child(&input_scroller).build();
    input_overlay.add_overlay(&placeholder);
    let input_frame = gtk::Frame::builder().child(&input_overlay).hexpand(true).build();
    let send = gtk::Button::builder().icon_name("mail-send-symbolic").tooltip_text("送る").valign(gtk::Align::End).css_classes(["circular", "suggested-action"]).build();
    let input = gtk::Box::builder().spacing(8).margin_start(16).margin_end(16).margin_bottom(12).margin_top(4).build();
    input.append(&input_frame);
    input.append(&send);

    let column = gtk::Box::new(gtk::Orientation::Vertical, 4);
    column.append(&header);
    column.append(&scroller);
    column.append(&suggestions);
    column.append(&input);

    let buffer = input_view.buffer();
    buffer.connect_changed(move |b| placeholder.set_visible(b.char_count() == 0));
    let send_text = {
        let app = app.clone();
        Rc::new(move |text: &str| app.ai.send(&app, text))
    };
    for suggestion in SUGGESTIONS {
        let label = gtk::Label::builder().label(suggestion).xalign(0.0).wrap(true).build();
        let button = gtk::Button::builder().child(&label).css_classes(["flat"]).build();
        let send_text = send_text.clone();
        button.connect_clicked(move |_| send_text(suggestion));
        suggestions.append(&button);
    }
    // 入力の欄の文を送って空にする（答えの途中は送らない）
    let submit = {
        let (app, buffer, send_text) = (app.clone(), buffer.clone(), send_text.clone());
        Rc::new(move || {
            if app.ai.running.get() {
                return;
            }
            let text = buffer.text(&buffer.start_iter(), &buffer.end_iter(), false).to_string();
            if text.trim().is_empty() {
                return;
            }
            buffer.set_text("");
            send_text(&text);
        })
    };
    {
        // 答えを待っている間は止めるボタン（止めるのはボタンだけ。Enter では止めない）
        let (app, submit) = (app.clone(), submit.clone());
        send.connect_clicked(move |_| {
            if app.ai.running.get() {
                app.ai.stop();
            } else {
                submit();
            }
        });
    }
    {
        // 変換中（preedit がある間）の Enter は入力の側に任せる
        let composing = Rc::new(Cell::new(false));
        {
            let composing = composing.clone();
            input_view.connect_preedit_changed(move |_, preedit| composing.set(!preedit.is_empty()));
        }
        let keys = gtk::EventControllerKey::new();
        keys.set_propagation_phase(gtk::PropagationPhase::Capture);
        keys.connect_key_pressed(move |_, key, _, modifiers| {
            let enter = matches!(key, gtk::gdk::Key::Return | gtk::gdk::Key::KP_Enter | gtk::gdk::Key::ISO_Enter);
            if !enter || composing.get() || modifiers.contains(gtk::gdk::ModifierType::SHIFT_MASK) {
                return gtk::glib::Propagation::Proceed;
            }
            submit();
            gtk::glib::Propagation::Stop
        });
        input_view.add_controller(keys);
    }
    {
        // 開いたら入力の欄に入る
        let input_view = input_view.clone();
        column.connect_map(move |_| {
            let input_view = input_view.clone();
            gtk::glib::idle_add_local_once(move || {
                input_view.grab_focus();
            });
        });
    }
    {
        let app = app.clone();
        new_chat.connect_clicked(move |_| app.ai.clear());
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

/// この欄だけで使う見た目（これまでの会話で、開いている会話の塗り）。
fn add_style() {
    let css = gtk::CssProvider::new();
    css.load_from_string(".ai-open-chat { background-color: alpha(currentColor, 0.1); border-radius: 6px; }");
    if let Some(display) = gtk::gdk::Display::default() {
        gtk::style_context_add_provider_for_display(&display, &css, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
    }
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
        AiMessage::Action(text) => gtk::Label::builder().label(format!("・{text}")).wrap(true).xalign(0.0).css_classes(["caption", "dim-label"]).build().upcast(),
        AiMessage::Songs(songs) => pages::song_list(app, songs).upcast(),
        AiMessage::Failure(text) => gtk::Label::builder().label(text).wrap(true).xalign(0.0).css_classes(["error"]).build().upcast(),
    }
}

/// これまでの会話（この PC の中だけ）。押すと開いて続け、ゴミ箱で消す（消しても小窓は閉じない。Mac と同じ）。
fn history_button(app: &Rc<App>) -> gtk::MenuButton {
    let heading = gtk::Label::builder().label("これまでの会話").xalign(0.0).margin_start(6).margin_top(4).css_classes(["heading"]).build();
    // 題名が読める幅にする（決めないと題名が1文字まで縮む）
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).width_request(320).css_classes(["navigation-sidebar"]).build();
    let scroller = gtk::ScrolledWindow::builder()
        .child(&list)
        .propagate_natural_height(true)
        .propagate_natural_width(true)
        .max_content_height(420)
        .hscrollbar_policy(gtk::PolicyType::Never)
        .build();
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    column.append(&heading);
    column.append(&scroller);
    let popover = gtk::Popover::builder().child(&column).build();
    let button = gtk::MenuButton::builder().icon_name("document-open-recent-symbolic").tooltip_text("これまでの会話").popover(&popover).css_classes(["flat", "circular"]).build();
    // 消したら一覧だけ描き直す（自分を呼ぶので、描き直しの関数を後から入れる）
    let render: Rc<RefCell<Option<Rc<dyn Fn()>>>> = Rc::default();
    let render_fn: Rc<dyn Fn()> = {
        let (app, popover, weak_render) = (app.clone(), popover.downgrade(), Rc::downgrade(&render));
        Rc::new(move || {
            let Some(popover) = popover.upgrade() else { return };
            while let Some(row) = list.first_child() {
                list.remove(&row);
            }
            let conversations = app.ai.history.borrow().clone();
            if conversations.is_empty() {
                list.append(
                    &gtk::Label::builder()
                        .label("まだ会話はありません。会話はこの PC の中だけに残ります。")
                        .wrap(true)
                        .max_width_chars(36)
                        .margin_top(12)
                        .margin_bottom(12)
                        .css_classes(["dim-label"])
                        .build(),
                );
            }
            let open_id = app.ai.conversation_id();
            for c in conversations {
                let title = if c.title.trim().is_empty() { "（質問なし）".to_string() } else { c.title.clone() };
                let row = adw::ActionRow::builder()
                    .title(gtk::glib::markup_escape_text(&title))
                    .subtitle(gtk::glib::markup_escape_text(&when(c.updated_at_ms)))
                    .title_lines(1)
                    .activatable(true)
                    .build();
                // 開いている会話を塗る
                if c.id == open_id {
                    row.add_css_class("ai-open-chat");
                }
                let delete = gtk::Button::builder().icon_name("user-trash-symbolic").tooltip_text("会話を消す").valign(gtk::Align::Center).css_classes(["flat", "circular"]).build();
                row.add_suffix(&delete);
                let (app_open, id_open, pop) = (app.clone(), c.id.clone(), popover.clone());
                row.connect_activated(move |_| {
                    app_open.ai.open(&id_open);
                    pop.popdown();
                });
                let (app_del, id_del, weak_render) = (app.clone(), c.id.clone(), weak_render.clone());
                delete.connect_clicked(move |_| {
                    app_del.ai.delete(&id_del);
                    // 押している部品ごと消すので、描き直しは後で
                    let render = weak_render.upgrade().and_then(|r| r.borrow().clone());
                    gtk::glib::idle_add_local_once(move || {
                        if let Some(render) = render {
                            render();
                        }
                    });
                });
                list.append(&row);
            }
        })
    };
    *render.borrow_mut() = Some(render_fn.clone());
    popover.connect_show(move |_| {
        // render を小窓が持ち続けるよう、ここで握る
        let _ = &render;
        render_fn();
    });
    button
}

/// 会話の日時（2026/11/08 14:05）。
fn when(ms: i64) -> String {
    gtk::glib::DateTime::from_unix_local(ms / 1000).ok().and_then(|d| d.format("%Y/%m/%d %H:%M").ok()).map(|s| s.to_string()).unwrap_or_default()
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
