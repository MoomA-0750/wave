//! 設定の「端末の連携」（Windows の DevicesPage と同じ中身）: 中継の場所・つながり・家の端末の一覧（外す）・端末を追加・ほかの端末の家に加わる。
//! 加入は窓の上の小窓で進める（招く側は wave-pair: の文字列を出して6桁を入れる、加わる側は文字列を貼って6桁を見せる）。

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;
use wave_core::connect::socket::{RelayEndpoint, RelayStatus};

use super::shell::App;
use crate::connect::PairingView;

/// 設定のページに「端末の連携」と「家の端末」を足す。
pub fn add_to(prefs: &adw::PreferencesPage, app: &Rc<App>) {
    let group = adw::PreferencesGroup::builder()
        .title("端末の連携")
        .description("ほかの端末（スマホ・PC）と再生を引き継いだり、ほかの端末の再生を操作したりします")
        .build();
    let relay = adw::EntryRow::builder().title("中継の場所（wss://ホスト:ポート）").show_apply_button(true).build();
    if let Some(url) = app.downloads.settings.borrow().relay_url.as_deref() {
        relay.set_text(url);
    }
    group.add(&relay);
    let status = adw::ActionRow::builder().title("つながり").build();
    group.add(&status);
    // 状態に応じた操作と、家の端末
    let members = adw::PreferencesGroup::builder().title("家の端末").build();
    let actions = gtk::FlowBox::builder().selection_mode(gtk::SelectionMode::None).column_spacing(8).row_spacing(8).margin_top(12).build();
    let add = gtk::Button::builder().label("端末を追加").css_classes(["pill", "suggested-action"]).build();
    let join = gtk::Button::builder().label("ほかの端末の連携に加わる").css_classes(["pill"]).build();
    let create = gtk::Button::builder().label("この PC で新しく始める").css_classes(["pill"]).build();
    let reset = gtk::Button::builder().label("この状態から始める").css_classes(["pill"]).build();
    let forget = gtk::Button::builder().label("この PC の連携の記録を消す").css_classes(["pill", "destructive-action"]).build();
    for button in [&add, &join, &create, &reset, &forget] {
        actions.append(button);
    }
    group.add(&actions);
    prefs.add(&group);
    prefs.add(&members);

    {
        let app = app.clone();
        relay.connect_apply(move |row| {
            let text = row.text().trim().to_string();
            if !text.is_empty() && RelayEndpoint::parse(&text).is_none() {
                app.toast("wss:// で始まる、パスの無い場所を入れてください");
                return;
            }
            app.downloads.update_settings(|s| s.relay_url = (!text.is_empty()).then_some(text));
            app.toast("中継の場所を覚えました。次に wave を開いたときから使います");
        });
    }
    {
        let app = app.clone();
        add.connect_clicked(move |_| {
            app.connect.start_offer();
            open_dialog(&app);
        });
    }
    {
        let app = app.clone();
        join.connect_clicked(move |_| {
            app.connect.cancel_pairing();
            open_dialog(&app);
        });
    }
    {
        let app = app.clone();
        create.connect_clicked(move |_| app.connect.create_household());
    }
    {
        let app = app.clone();
        reset.connect_clicked(move |_| app.connect.accept_store_reset());
    }
    {
        let app = app.clone();
        forget.connect_clicked(move |_| app.connect.forget_household());
    }

    // 変わったときだけ描き直す（毎秒の知らせで行を作り直さない）
    let last = Rc::new(RefCell::new(String::new()));
    let weak_group = members.downgrade();
    let rows: RefCell<Vec<adw::ActionRow>> = RefCell::default();
    let refresh = {
        let app = app.clone();
        move || {
            let Some(members) = weak_group.upgrade() else { return };
            let info = app.connect.info();
            let list = app.connect.members();
            let key = format!("{info:?}{list:?}");
            if *last.borrow() == key {
                return;
            }
            *last.borrow_mut() = key;
            status.set_subtitle(&status_text(&info));
            for row in rows.borrow_mut().drain(..) {
                members.remove(&row);
            }
            members.set_visible(!list.is_empty());
            for member in list {
                let subtitle = match (member.is_self, member.active, member.online) {
                    (_, true, _) => "再生中",
                    (true, _, _) | (_, _, true) => "つながっています",
                    _ => "オフライン",
                };
                let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&member.label)).subtitle(subtitle).build();
                if !member.is_self {
                    let remove = gtk::Button::builder().label("外す").valign(gtk::Align::Center).css_classes(["flat"]).build();
                    let (app, device_id) = (app.clone(), member.device_id.clone());
                    remove.connect_clicked(move |button| {
                        button.set_sensitive(false);
                        app.connect.remove_member(device_id.clone());
                    });
                    row.add_suffix(&remove);
                }
                members.add(&row);
                rows.borrow_mut().push(row);
            }
            let usable = info.configured && !info.unreadable;
            add.set_visible(usable && info.in_household);
            join.set_visible(usable && !info.in_household);
            create.set_visible(usable && !info.in_household);
            reset.set_visible(info.store_reset_pending);
            forget.set_visible(info.unreadable || (info.relay == Some(RelayStatus::NotMember) && info.in_household));
        }
    };
    refresh();
    app.connect.on_change(refresh);
}

fn status_text(info: &crate::connect::ConnectInfo) -> String {
    if !info.configured {
        return if info.unreadable { "端末の鍵を読めません（キーリングを確かめてください）".into() } else { "中継の場所が設定されていません".into() };
    }
    if info.unreadable {
        return "連携の保存を読めません。記録を消すと、ほかの端末から加わり直せます".into();
    }
    let relay = match info.relay {
        Some(RelayStatus::Connected) => "接続中",
        Some(RelayStatus::Connecting) => "接続しています…",
        Some(RelayStatus::TailnetUnreachable) => "届きません（Tailscale につながっているか確かめてください）",
        Some(RelayStatus::NotMember) if info.in_household => "中継がこの PC を連携した端末として受け付けません",
        Some(RelayStatus::NotMember) => "まだ連携していません",
        Some(RelayStatus::Failed) => "つながりませんでした",
        _ => "切断",
    };
    let mut text = format!("中継: {relay}");
    if info.store_reset_pending {
        text.push_str("・中継の状態が作り直されました");
    } else if info.in_household && info.is_writer {
        text.push_str("・この PC で再生中");
    }
    text
}

/// 加入の小窓。中身は連携の手順の様子に合わせて描き直す。
fn open_dialog(app: &Rc<App>) {
    let dialog = adw::Dialog::builder().title("端末の連携").content_width(460).build();
    let body = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).margin_top(24).margin_bottom(24).margin_start(24).margin_end(24).build();
    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&adw::HeaderBar::new());
    // 文字列と QR で窓より高くなることがあるので、中身は流せるようにする
    toolbar.set_content(Some(&gtk::ScrolledWindow::builder().child(&body).hscrollbar_policy(gtk::PolicyType::Never).propagate_natural_height(true).min_content_height(460).build()));
    dialog.set_child(Some(&toolbar));

    let last = Rc::new(RefCell::new(None::<PairingView>));
    let weak_body = body.downgrade();
    let weak_dialog = dialog.downgrade();
    let render = {
        let app = app.clone();
        move || {
            let (Some(body), Some(dialog)) = (weak_body.upgrade(), weak_dialog.upgrade()) else { return };
            let view = app.connect.pairing();
            if last.borrow().as_ref() == Some(&view) {
                return;
            }
            *last.borrow_mut() = Some(view.clone());
            while let Some(child) = body.first_child() {
                body.remove(&child);
            }
            render_view(&app, &body, &dialog, view);
        }
    };
    render();
    app.connect.on_change(render);
    {
        let app = app.clone();
        dialog.connect_closed(move |_| app.connect.cancel_pairing());
    }
    if let Some(window) = app.window() {
        dialog.present(Some(&window));
    }
}

fn text(label: &str, classes: &[&str]) -> gtk::Label {
    gtk::Label::builder().label(label).wrap(true).xalign(0.0).css_classes(classes.to_vec()).build()
}

/// 文字列の QR（Windows と同じく誤り訂正 M）。暗い色で描き、まわりに4マスの白の余白を付ける。
fn qr_texture(text: &str) -> Option<gtk::gdk::MemoryTexture> {
    let (pixels, size) = qr_pixels(text)?;
    let bytes = gtk::glib::Bytes::from_owned(pixels);
    Some(gtk::gdk::MemoryTexture::new(size as i32, size as i32, gtk::gdk::MemoryFormat::G8, &bytes, size))
}

/// QR の灰色の画素（1画素1バイト、0 が黒）と一辺の画素数。
fn qr_pixels(text: &str) -> Option<(Vec<u8>, usize)> {
    const SCALE: usize = 4;
    const QUIET: usize = 4;
    let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M).ok()?;
    let width = code.width();
    let size = (width + QUIET * 2) * SCALE;
    let mut pixels = vec![255u8; size * size];
    for y in 0..width {
        for x in 0..width {
            if code[(x, y)] == qrcode::Color::Dark {
                for dy in 0..SCALE {
                    let row = (y + QUIET) * SCALE + dy;
                    let start = row * size + (x + QUIET) * SCALE;
                    pixels[start..start + SCALE].fill(0);
                }
            }
        }
    }
    Some((pixels, size))
}

/// 結果の見出し（絵と題）。
fn result_heading(icon: &str, title: &str) -> gtk::Box {
    let heading = gtk::Box::builder().spacing(12).build();
    heading.append(&gtk::Image::builder().icon_name(icon).pixel_size(32).build());
    heading.append(&gtk::Label::builder().label(title).css_classes(["title-2"]).build());
    heading
}

fn render_view(app: &Rc<App>, body: &gtk::Box, dialog: &adw::Dialog, view: PairingView) {
    match view {
        // 加わる側の始まり: 家の端末が出した文字列を貼る
        PairingView::Idle => {
            body.append(&text("加わる先の端末で wave の「端末の連携」を開き、「端末を追加」を押してください。出てきた文字列（wave-pair: で始まるもの）を下に貼ります。", &[]));
            let entry = gtk::Entry::builder().placeholder_text("wave-pair:…").build();
            let next = gtk::Button::builder().label("続ける").halign(gtk::Align::End).css_classes(["pill", "suggested-action"]).build();
            body.append(&entry);
            body.append(&next);
            let submit = {
                let (app, entry) = (app.clone(), entry.clone());
                move || app.connect.accept(&entry.text())
            };
            let submit = Rc::new(submit);
            let s = submit.clone();
            next.connect_clicked(move |_| s());
            entry.connect_activate(move |_| submit());
        }
        PairingView::Working(message) => {
            body.append(&adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).build());
            body.append(&text(&message, &["dim-label"]));
        }
        // 招く側: 文字列を見せる（加わる端末が PC ならコピーして貼る）
        PairingView::ShowingOffer(offer) => {
            body.append(&text("加える端末で wave の「端末の連携」を開き、この QR を読み取ってください（PC なら下の文字列をコピーして貼り付けます）。2分で無効になります。", &[]));
            if let Some(texture) = qr_texture(&offer) {
                body.append(&gtk::Picture::builder().paintable(&texture).can_shrink(false).halign(gtk::Align::Center).build());
            }
            let shown = gtk::Label::builder().label(&offer).wrap(true).wrap_mode(gtk::pango::WrapMode::Char).selectable(true).xalign(0.0).css_classes(["monospace", "caption"]).build();
            body.append(&shown);
            let copy = gtk::Button::builder().label("コピー").halign(gtk::Align::End).css_classes(["pill"]).build();
            let app = app.clone();
            copy.connect_clicked(move |button| {
                button.clipboard().set_text(&offer);
                app.toast("コピーしました");
            });
            body.append(&copy);
        }
        PairingView::EnterCode(message) => {
            body.append(&text("加える端末に出ている6桁の数字を入力してください", &[]));
            if let Some(message) = message {
                body.append(&text(&message, &["error"]));
            }
            let entry = gtk::Entry::builder().placeholder_text("000000").max_length(6).input_purpose(gtk::InputPurpose::Digits).css_classes(["title-2"]).build();
            let next = gtk::Button::builder().label("確かめる").halign(gtk::Align::End).css_classes(["pill", "suggested-action"]).build();
            body.append(&entry);
            body.append(&next);
            let submit = {
                let (app, entry) = (app.clone(), entry.clone());
                move || app.connect.enter_code(&entry.text())
            };
            let submit = Rc::new(submit);
            let s = submit.clone();
            next.connect_clicked(move |_| s());
            entry.connect_activate(move |_| submit());
            entry.grab_focus();
        }
        // 加わる側: 6桁を相手に入れてもらう
        PairingView::ShowCode { code, peer } => {
            body.append(&text(&format!("「{peer}」にこの数字を入力してください。入力が済んだら「続ける」を押します。"), &[]));
            body.append(&gtk::Label::builder().label(format!("{} {}", &code[..3.min(code.len())], &code[3.min(code.len())..])).css_classes(["title-1", "numeric"]).build());
            let next = gtk::Button::builder().label("続ける").halign(gtk::Align::End).css_classes(["pill", "suggested-action"]).build();
            let app = app.clone();
            next.connect_clicked(move |_| app.connect.confirm_on_b());
            body.append(&next);
        }
        PairingView::Done(peer) => {
            body.append(&result_heading("object-select-symbolic", "連携しました"));
            body.append(&text(&format!("「{peer}」と連携しました。下の帯の端末のボタンから、再生する端末を選べます。"), &[]));
            let close = gtk::Button::builder().label("閉じる").halign(gtk::Align::Center).css_classes(["pill"]).build();
            let dialog = dialog.clone();
            close.connect_clicked(move |_| {
                dialog.close();
            });
            body.append(&close);
        }
        PairingView::Failed(message) => {
            body.append(&result_heading("dialog-warning-symbolic", "連携できませんでした"));
            body.append(&text(&message, &[]));
            let close = gtk::Button::builder().label("閉じる").halign(gtk::Align::Center).css_classes(["pill"]).build();
            let dialog = dialog.clone();
            close.connect_clicked(move |_| {
                dialog.close();
            });
            body.append(&close);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn qr_reads_back_as_the_offer_text() {
        let offer = "wave-pair:eyJ2IjoxLCJwa0EiOiJ0ZXN0IiwiZUEiOiJ0ZXN0IiwidG9rZW4iOiJ0ZXN0In0";
        let (pixels, size) = qr_pixels(offer).unwrap();
        let mut image = rqrr::PreparedImage::prepare_from_greyscale(size, size, |x, y| pixels[y * size + x]);
        let grids = image.detect_grids();
        assert_eq!(grids.len(), 1);
        let (_, content) = grids[0].decode().unwrap();
        assert_eq!(content, offer);
    }
}
