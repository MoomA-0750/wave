//! 端末の連携のページ（Mac の DevicesPageView と同じ並び）: 知らせの帯・ほかの端末で鳴っている曲・はじめる（家に加わる）・
//! 加入の手順（QR と6桁）・連携している端末・中継。中央のページとして積む（アカウントのメニューと、下の帯の端末の小窓から開く）。

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use wave_core::connect::socket::{RelayEndpoint, RelayStatus};

use super::images;
use super::shell::App;
use crate::connect::{ConnectInfo, MemberView, Mirror, PairingView};

/// ページのタグ（同じページを重ねて開かない）。
const TAG: &str = "devices";

const STYLE: &str = "
.devices-banner { padding: 12px; border-radius: 8px; }
.devices-banner.sev-info { background-color: alpha(@accent_color, 0.12); }
.devices-banner.sev-info > image { color: @accent_color; }
.devices-banner.sev-warning { background-color: alpha(@warning_color, 0.12); }
.devices-banner.sev-warning > image { color: @warning_color; }
.devices-banner.sev-error { background-color: alpha(@error_color, 0.12); }
.devices-banner.sev-error > image { color: @error_color; }
.devices-card { padding: 16px; }
.devices-code { font-size: 44px; font-weight: 600; letter-spacing: 8px; }
.devices-remote-status { color: @accent_color; }
";

thread_local! {
    static STYLE_ADDED: Cell<bool> = const { Cell::new(false) };
}

fn add_style() {
    if STYLE_ADDED.with(|s| s.replace(true)) {
        return;
    }
    let css = gtk::CssProvider::new();
    css.load_from_string(STYLE);
    if let Some(display) = gtk::gdk::Display::default() {
        gtk::style_context_add_provider_for_display(&display, &css, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
    }
}

/// 端末の連携のページを開く（もう開いていれば何もしない）。
pub fn open_page(app: &Rc<App>) {
    if app.visible_kind().as_deref() == Some(TAG) {
        return;
    }
    app.push(page(app));
}

/// アカウントのメニューの「端末の連携」（Mac と同じく、ログインしていて中継が設定済みのときだけ。そうでなければ None）。
/// 押すと、入っている小窓を閉じてページを開く。
#[allow(dead_code, reason = "shell.rs のアカウントのメニューからつなぐ（下の帯・メニューの担当が足す）")]
pub fn menu_button(app: &Rc<App>) -> Option<gtk::Button> {
    if !app.connect.info().configured || !app.repo.client().is_logged_in() {
        return None;
    }
    let button = gtk::Button::builder().label("端末の連携").css_classes(["flat"]).build();
    let app = app.clone();
    button.connect_clicked(move |button| {
        close_popover(button);
        open_page(&app);
    });
    Some(button)
}

/// 下の帯の端末の小窓の「端末の連携の設定」のリンク。押すと、入っている小窓を閉じてページを開く。
#[allow(dead_code, reason = "shell.rs の端末の小窓からつなぐ（下の帯の担当が足す）")]
pub fn settings_link(app: &Rc<App>) -> gtk::Button {
    let button = gtk::Button::builder().label("端末の連携の設定").css_classes(["flat"]).build();
    let app = app.clone();
    button.connect_clicked(move |button| {
        close_popover(button);
        open_page(&app);
    });
    button
}

#[allow(dead_code, reason = "menu_button・settings_link が使う")]
fn close_popover(widget: &impl IsA<gtk::Widget>) {
    if let Some(popover) = widget.ancestor(gtk::Popover::static_type()).and_downcast::<gtk::Popover>() {
        popover.popdown();
    }
}

/// 設定のページの「端末の連携」（中身は独立したページへ移した。開く行だけ置く）。
pub fn add_to(prefs: &adw::PreferencesPage, app: &Rc<App>) {
    let group = adw::PreferencesGroup::builder().title("端末の連携").build();
    let row = adw::ActionRow::builder().title("端末の連携").subtitle("ほかの端末（スマホ・PC）と再生を引き継ぎます").activatable(true).build();
    row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
    let app = app.clone();
    row.connect_activated(move |_| open_page(&app));
    group.add(&row);
    prefs.add(&group);
}

/// 端末の連携のページ。
pub fn page(app: &Rc<App>) -> adw::NavigationPage {
    add_style();
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).margin_top(32).margin_bottom(32).margin_start(40).margin_end(40).build();
    column.append(&gtk::Label::builder().label("端末の連携").xalign(0.0).css_classes(["title-1"]).build());
    column.append(&text(
        "同じアカウントのスマホと PC のあいだで、再生を引き継げます。端末どうしの通信は暗号化され、中継のサーバーは中身を読めません。",
        &["dim-label"],
    ));
    column.last_child().inspect(|w| w.set_margin_bottom(16));

    let banner = adw::Bin::new();
    let remote = RemoteCard::new(app);
    let join = JoinPanel::new(app);
    let pairing = adw::Bin::new();
    let household = Household::new(app);
    let relay_text = text("", &["dim-label"]);
    relay_text.set_margin_top(16);
    let relay = relay_group(app);
    for widget in [banner.upcast_ref::<gtk::Widget>(), remote.root.upcast_ref(), join.root.upcast_ref(), pairing.upcast_ref(), household.root.upcast_ref(), relay_text.upcast_ref(), relay.upcast_ref()] {
        column.append(widget);
    }

    let clamp = adw::Clamp::builder().maximum_size(880).tightening_threshold(880).child(&column).build();
    let scroller = gtk::ScrolledWindow::builder().child(&clamp).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();
    let page = adw::NavigationPage::builder().title("端末の連携").tag(TAG).child(&scroller).build();
    // 確かめ用: WAVE_DEMO_DEVICES=bottom なら、開いて少ししたらページの下（中継）まで送る
    if std::env::var("WAVE_DEMO_DEVICES").as_deref() == Ok("bottom") {
        let adjustment = scroller.vadjustment();
        gtk::glib::timeout_add_local_once(std::time::Duration::from_secs(3), move || adjustment.set_value(adjustment.upper()));
    }

    // 変わったところだけ描き直す（入れかけの文字列を消さない）
    let last_banner = RefCell::new(None::<ConnectInfo>);
    let last_pairing = RefCell::new(None::<(PairingView, bool, bool)>);
    let weak_column = column.downgrade();
    let refresh: Rc<dyn Fn()> = {
        let app = app.clone();
        Rc::new(move || {
            if weak_column.upgrade().is_none() {
                return;
            }
            let info = app.connect.info();
            let view = app.connect.pairing();
            let members = app.connect.members();
            let logged_in = app.repo.client().is_logged_in();
            if last_banner.borrow().as_ref() != Some(&info) {
                *last_banner.borrow_mut() = Some(info.clone());
                banner.set_child(status_bar(&app, &info).as_ref());
            }
            // 再生中の端末（同じアカウントで、ほかの端末が鳴らしているとき。mirror がアカウントと書き手を確かめる）
            let mirror = app.connect.mirror().filter(|_| info.in_household && info.relay != Some(RelayStatus::NotMember) && !info.is_writer);
            remote.show(mirror.as_ref());
            // 家に入っていない
            let usable = info.configured && !info.unreadable;
            join.root.set_visible(usable && !info.in_household && matches!(view, PairingView::Idle | PairingView::Failed(_)));
            if let PairingView::Done { .. } = view {
                join.entry.set_text("");
            }
            // 加入の手順
            let grant_member = match &view {
                PairingView::Done { grant_device_id: Some(id), .. } if logged_in => members.iter().find(|m| &m.device_id == id).cloned(),
                _ => None,
            };
            let key = (view.clone(), logged_in, grant_member.is_some());
            if last_pairing.borrow().as_ref() != Some(&key) {
                *last_pairing.borrow_mut() = Some(key);
                pairing.set_child(pairing_panel(&app, &view, grant_member).as_ref());
            }
            // 家の端末
            household.root.set_visible(info.in_household);
            household.show(&members, logged_in);
            relay_text.set_label(&format!("中継: {}", relay_text_of(&info)));
        })
    };
    refresh();
    {
        let refresh = refresh.clone();
        app.connect.on_change(move || refresh());
    }
    // ほかの端末の位置は、知らせが無くても進むので毎秒描き直す
    {
        let weak = page.downgrade();
        let refresh = refresh.clone();
        gtk::glib::timeout_add_local(std::time::Duration::from_secs(1), move || {
            if weak.upgrade().is_none() {
                return gtk::glib::ControlFlow::Break;
            }
            refresh();
            gtk::glib::ControlFlow::Continue
        });
    }
    // ページを離れたら、途中の加入を取り下げる（相手に6桁を見せている間は残す）
    {
        let app = app.clone();
        page.connect_hidden(move |_| {
            if !matches!(app.connect.pairing(), PairingView::ShowCode { .. } | PairingView::Idle | PairingView::Done { .. } | PairingView::Failed(_)) {
                app.connect.cancel_pairing();
            }
        });
    }
    page
}

fn text(label: &str, classes: &[&str]) -> gtk::Label {
    gtk::Label::builder().label(label).wrap(true).wrap_mode(gtk::pango::WrapMode::WordChar).xalign(0.0).css_classes(classes.to_vec()).build()
}

/// カード（Mac の card()）。
fn card() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).css_classes(["card", "devices-card"]).build()
}

/// 見出し（「はじめる」「連携している端末」）。
fn heading(label: &str) -> gtk::Label {
    gtk::Label::builder().label(label).xalign(0.0).hexpand(true).css_classes(["heading"]).build()
}

// --- 知らせの帯（Windows の InfoBar） ---

#[derive(Clone, Copy)]
enum Severity {
    Info,
    Warning,
    Error,
}

fn status_bar(app: &Rc<App>, info: &ConnectInfo) -> Option<gtk::Widget> {
    let connect = &app.connect;
    if !info.configured {
        let message = if info.unreadable { "この PC の連携の鍵を読めませんでした（キーリングを確かめてください）" } else { "ページの下の「中継の場所」に入れると使えます" };
        return Some(banner(Severity::Info, "中継の場所が設定されていません", Some(message), None));
    }
    if info.unreadable {
        // Android の「リセット」と同じ: 読めない保存を消して、加わり直せるようにする
        let c = connect.clone();
        return Some(banner(
            Severity::Error,
            "連携の保存を読めません",
            Some("リセットすると、この PC はもう一度ほかの端末から加わり直します。"),
            Some(("リセット", Box::new(move || c.forget_household()))),
        ));
    }
    if info.in_household && info.relay == Some(RelayStatus::NotMember) {
        let c = connect.clone();
        return Some(banner(
            Severity::Error,
            "中継がこの PC を連携した端末として受け付けません",
            Some("ほかの端末から外された可能性があります。"),
            Some(("この PC の連携の記録を消す", Box::new(move || c.forget_household()))),
        ));
    }
    if info.store_reset_pending {
        let c = connect.clone();
        return Some(banner(Severity::Warning, "中継の状態が作り直されました", None, Some(("この状態から始める", Box::new(move || c.accept_store_reset())))));
    }
    None
}

type Action = (&'static str, Box<dyn Fn()>);

fn banner(severity: Severity, title: &str, message: Option<&str>, action: Option<Action>) -> gtk::Widget {
    let (icon, class) = match severity {
        Severity::Info => ("dialog-information-symbolic", "sev-info"),
        Severity::Warning => ("dialog-warning-symbolic", "sev-warning"),
        Severity::Error => ("dialog-error-symbolic", "sev-error"),
    };
    let row = gtk::Box::builder().spacing(12).css_classes(["devices-banner", class]).build();
    row.append(&gtk::Image::builder().icon_name(icon).valign(gtk::Align::Start).margin_top(2).build());
    let body = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).hexpand(true).build();
    body.append(&text(title, &["heading"]));
    if let Some(message) = message.filter(|m| !m.is_empty()) {
        body.append(&text(message, &[]));
    }
    row.append(&body);
    if let Some((label, on_click)) = action {
        let button = gtk::Button::builder().label(label).valign(gtk::Align::Center).build();
        button.connect_clicked(move |_| on_click());
        row.append(&button);
    }
    row.upcast()
}

/// 中継の状態（ページの下の「中継: …」）。
fn relay_text_of(info: &ConnectInfo) -> &'static str {
    if !info.configured {
        return "設定されていません";
    }
    match info.relay {
        Some(RelayStatus::Connected) => "接続中",
        Some(RelayStatus::Connecting) => "接続しています…",
        Some(RelayStatus::TailnetUnreachable) => "届きません（Tailscale につながっているか確かめてください）",
        Some(RelayStatus::NotMember) if info.in_household => "受け付けられません",
        Some(RelayStatus::NotMember) => "まだ連携していません",
        Some(RelayStatus::Failed) => "つながりませんでした",
        Some(RelayStatus::Disconnected) | None => "切断",
    }
}

/// 中継の場所（Mac は設定ファイルで書く。Linux は手で書かせるより親切なので、ページの下に小さく置く）。
fn relay_group(app: &Rc<App>) -> gtk::Widget {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list"]).margin_top(4).build();
    let relay = adw::EntryRow::builder().title("中継の場所（wss://ホスト:ポート）").show_apply_button(true).build();
    if let Some(url) = app.downloads.settings.borrow().relay_url.as_deref() {
        relay.set_text(url);
    }
    list.append(&relay);
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
    list.upcast()
}

// --- 再生中の端末 ---

struct RemoteCard {
    root: gtk::Box,
    art: images::Art,
    status: gtk::Label,
    title: gtk::Label,
    detail: gtk::Label,
    url: RefCell<Option<String>>,
}

impl RemoteCard {
    fn new(app: &Rc<App>) -> Self {
        let root = gtk::Box::builder().spacing(16).css_classes(["card", "devices-card"]).visible(false).build();
        let art = images::square(64);
        let status = gtk::Label::builder().xalign(0.0).css_classes(["caption", "devices-remote-status"]).build();
        let title = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["heading"]).build();
        let detail = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["dim-label"]).build();
        let texts = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).valign(gtk::Align::Center).hexpand(true).build();
        texts.append(&status);
        texts.append(&title);
        texts.append(&detail);
        let transfer = gtk::Button::builder().label("この PC で再生").valign(gtk::Align::Center).css_classes(["suggested-action"]).build();
        let a = app.clone();
        transfer.connect_clicked(move |_| a.connect.transfer_here());
        root.append(&art.root);
        root.append(&texts);
        root.append(&transfer);
        Self { root, art, status, title, detail, url: RefCell::default() }
    }

    fn show(&self, mirror: Option<&Mirror>) {
        let Some(mirror) = mirror else {
            self.root.set_visible(false);
            return;
        };
        self.root.set_visible(true);
        self.status.set_label(&if mirror.playing { format!("{}で再生中", mirror.label) } else { format!("{}で一時停止中", mirror.label) });
        self.title.set_label(if mirror.song.title.is_empty() { "曲" } else { &mirror.song.title });
        let seconds = u32::try_from(mirror.position_ms.max(0) / 1000).unwrap_or(u32::MAX);
        self.detail.set_label(&format!("{} · {}", mirror.song.artists_text(), super::pages::format_seconds(seconds)));
        if *self.url.borrow() != mirror.song.thumbnail_url {
            *self.url.borrow_mut() = mirror.song.thumbnail_url.clone();
            self.art.load(mirror.song.thumbnail_url.as_deref(), 128);
        }
    }
}

// --- 家に入っていない ---

struct JoinPanel {
    root: gtk::Box,
    entry: gtk::Entry,
}

impl JoinPanel {
    fn new(app: &Rc<App>) -> Self {
        let root = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).visible(false).build();
        let title = heading("はじめる");
        title.set_margin_top(8);
        root.append(&title);
        let body = card();
        body.append(&text("連携済みのスマホの wave で「端末の連携」→「端末を追加」を押し、「文字で共有」で送った文字列をここに貼り付けてください。", &[]));
        let entry = gtk::Entry::builder().placeholder_text("wave-pair:…").build();
        body.append(&entry);
        let buttons = gtk::Box::builder().spacing(8).build();
        let join = gtk::Button::builder().label("加わる").css_classes(["suggested-action"]).build();
        let create = gtk::Button::builder().label("この PC で新しく始める").build();
        buttons.append(&join);
        buttons.append(&create);
        body.append(&buttons);
        root.append(&body);
        let submit: Rc<dyn Fn()> = {
            let (app, entry) = (app.clone(), entry.clone());
            Rc::new(move || {
                let offer = entry.text();
                if offer.trim().is_empty() {
                    return;
                }
                app.connect.accept(&offer);
            })
        };
        let s = submit.clone();
        join.connect_clicked(move |_| s());
        entry.connect_activate(move |_| submit());
        let app = app.clone();
        create.connect_clicked(move |_| app.connect.create_household());
        Self { root, entry }
    }
}

// --- 加入の手順 ---

/// 手順の説明（Mac の DevicesText.pairing と同じ文）。
fn pairing_text(view: &PairingView) -> String {
    match view {
        PairingView::Idle => String::new(),
        PairingView::Working(message) | PairingView::Failed(message) => message.clone(),
        PairingView::ShowingOffer(_) => "加える端末で wave の「端末の連携」を開き、この QR を読み取ってください（PC なら下の文字列をコピーして貼り付けます）。2分で無効になります。".into(),
        PairingView::EnterCode(message) => format!("{}加える端末に出ている6桁の数字を入力してください", message.as_ref().map(|m| format!("{m}\n")).unwrap_or_default()),
        PairingView::ShowCode { peer, .. } => format!("「{peer}」に、この6桁の数字を入力してください"),
        PairingView::Done { peer, grant_device_id: Some(_) } => format!("「{peer}」を加えました。プレイリストなどを使えるようにするには、ログインを渡してください。"),
        PairingView::Done { peer, grant_device_id: None } => format!("「{peer}」と連携しました"),
    }
}

/// 加入の手順のカード（Idle のときは無し）。grant は、ログインを渡す相手（加えたのがログインを持たない wave API のとき）。
fn pairing_panel(app: &Rc<App>, view: &PairingView, grant: Option<MemberView>) -> Option<gtk::Widget> {
    if *view == PairingView::Idle {
        return None;
    }
    let body = card();
    body.append(&text(&pairing_text(view), if matches!(view, PairingView::Failed(_)) { &["error"] } else { &[] }));
    match view {
        PairingView::ShowingOffer(offer) => {
            if let Some(texture) = qr_texture(offer) {
                body.append(&gtk::Picture::builder().paintable(&texture).can_shrink(false).halign(gtk::Align::Start).build());
            }
            let copy = gtk::Button::builder().label("文字列をコピー").halign(gtk::Align::Start).build();
            let (app, offer) = (app.clone(), offer.clone());
            copy.connect_clicked(move |button| {
                button.clipboard().set_text(&offer);
                app.toast("コピーしました");
            });
            body.append(&copy);
        }
        PairingView::ShowCode { code, .. } if code.chars().count() == 6 => {
            let (head, tail) = code.split_at(3);
            body.append(&gtk::Label::builder().label(format!("{head} {tail}")).xalign(0.0).css_classes(["devices-code", "numeric"]).build());
        }
        PairingView::EnterCode(_) => {
            let row = gtk::Box::builder().spacing(8).build();
            let entry = gtk::Entry::builder().placeholder_text("000000").max_length(6).width_request(160).input_purpose(gtk::InputPurpose::Digits).build();
            let confirm = gtk::Button::builder().label("確認").css_classes(["suggested-action"]).build();
            row.append(&entry);
            row.append(&confirm);
            body.append(&row);
            // 数字以外は落とし、6桁に満たなければ送らない（connect の enter_code が確かめる）
            let submit: Rc<dyn Fn()> = {
                let (app, entry) = (app.clone(), entry.clone());
                Rc::new(move || {
                    if app.connect.enter_code(&entry.text()) {
                        entry.set_text("");
                    }
                })
            };
            let s = submit.clone();
            confirm.connect_clicked(move |_| s());
            entry.connect_activate(move |_| submit());
            let weak = entry.downgrade();
            gtk::glib::idle_add_local_once(move || {
                if let Some(entry) = weak.upgrade() {
                    entry.grab_focus();
                }
            });
        }
        PairingView::Working(_) => {
            body.append(&adw::Spinner::builder().width_request(16).height_request(16).halign(gtk::Align::Start).build());
        }
        _ => {}
    }
    let buttons = gtk::Box::builder().spacing(8).build();
    if let PairingView::ShowCode { .. } = view {
        let next = gtk::Button::builder().label("相手に入力したら、続ける").css_classes(["suggested-action"]).build();
        let app = app.clone();
        next.connect_clicked(move |_| app.connect.confirm_on_b());
        buttons.append(&next);
    }
    // 加えたのがログインをまだ持たない wave API なら、続けてログインを渡す
    if let Some(member) = grant {
        let share = gtk::Button::builder().label("ログインを渡す").css_classes(["suggested-action"]).build();
        let app = app.clone();
        share.connect_clicked(move |_| confirm_grant(&app, &member));
        buttons.append(&share);
    }
    let finished = matches!(view, PairingView::Done { .. } | PairingView::Failed(_));
    let close = gtk::Button::builder().label(if finished { "閉じる" } else { "キャンセル" }).build();
    let app = app.clone();
    close.connect_clicked(move |_| app.connect.cancel_pairing());
    buttons.append(&close);
    body.append(&buttons);
    Some(body.upcast())
}

/// 文字列の QR（Mac・Windows と同じく誤り訂正 M）。暗い色で描き、まわりに4マスの白の余白を付ける。
fn qr_texture(text: &str) -> Option<gtk::gdk::MemoryTexture> {
    let (pixels, size) = qr_pixels(text)?;
    let bytes = gtk::glib::Bytes::from_owned(pixels);
    Some(gtk::gdk::MemoryTexture::new(size as i32, size as i32, gtk::gdk::MemoryFormat::G8, &bytes, size))
}

/// QR の灰色の画素（1画素1バイト、0 が黒）と一辺の画素数。一辺が 280 px（Mac の大きさ）以上になるよう、1マスを整数倍で広げる。
fn qr_pixels(text: &str) -> Option<(Vec<u8>, usize)> {
    const QUIET: usize = 4;
    const MIN_SIZE: usize = 280;
    let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M).ok()?;
    let width = code.width();
    let scale = MIN_SIZE.div_ceil(width + QUIET * 2).max(1);
    let size = (width + QUIET * 2) * scale;
    let mut pixels = vec![255u8; size * size];
    for y in 0..width {
        for x in 0..width {
            if code[(x, y)] == qrcode::Color::Dark {
                for dy in 0..scale {
                    let row = (y + QUIET) * scale + dy;
                    let start = row * size + (x + QUIET) * scale;
                    pixels[start..start + scale].fill(0);
                }
            }
        }
    }
    Some((pixels, size))
}

// --- 家の端末 ---

struct Household {
    root: gtk::Box,
    list: gtk::ListBox,
    app: Rc<App>,
    last: RefCell<Option<(Vec<MemberView>, bool)>>,
}

impl Household {
    fn new(app: &Rc<App>) -> Self {
        let root = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).visible(false).build();
        let header = gtk::Box::builder().spacing(8).margin_top(8).build();
        header.append(&heading("連携している端末"));
        let add = gtk::Button::builder().child(&adw::ButtonContent::builder().icon_name("list-add-symbolic").label("端末を追加").build()).build();
        let a = app.clone();
        add.connect_clicked(move |_| a.connect.start_offer());
        header.append(&add);
        root.append(&header);
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list"]).build();
        root.append(&list);
        Self { root, list, app: app.clone(), last: RefCell::default() }
    }

    fn show(&self, members: &[MemberView], logged_in: bool) {
        let key = (members.to_vec(), logged_in);
        if self.last.borrow().as_ref() == Some(&key) {
            return;
        }
        *self.last.borrow_mut() = Some(key);
        self.list.remove_all();
        for member in members {
            self.list.append(&member_row(&self.app, member, logged_in));
        }
    }
}

/// 一覧の表示名（表示と操作だけの端末には「（操作だけ）」を付ける）。
fn member_label(member: &MemberView) -> String {
    if member.is_controller { format!("{}（操作だけ）", member.label) } else { member.label.clone() }
}

/// 端末の絵（wave API はサーバー、PC（この PC を含む）はコンピューター、ほかはスマホ）。
fn member_icon(member: &MemberView) -> &'static str {
    match member.kind.as_deref() {
        Some("api") => "network-server-symbolic",
        Some("pc") => "computer-symbolic",
        _ if member.is_self => "computer-symbolic",
        _ => "phone-symbolic",
    }
}

fn member_row(app: &Rc<App>, member: &MemberView, logged_in: bool) -> adw::ActionRow {
    let status = if member.active {
        "再生中の端末"
    } else if member.online {
        "オンライン"
    } else {
        "オフライン"
    };
    let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&member_label(member))).subtitle(status).build();
    row.add_prefix(&gtk::Image::builder().icon_name(member_icon(member)).pixel_size(20).width_request(28).build());
    // ログインしているときだけ出す（渡すログインが無い）
    if member.kind.as_deref() == Some("api") && logged_in {
        let grant = gtk::Button::builder().label("ログインを渡す").valign(gtk::Align::Center).build();
        let (app, member) = (app.clone(), member.clone());
        grant.connect_clicked(move |_| confirm_grant(&app, &member));
        row.add_suffix(&grant);
    }
    if !member.is_self {
        let remove = gtk::Button::builder().label("外す").valign(gtk::Align::Center).build();
        let (app, member) = (app.clone(), member.clone());
        remove.connect_clicked(move |_| confirm_remove(&app, &member));
        row.add_suffix(&remove);
    }
    row
}

/// 端末を外す前に確かめる。
fn confirm_remove(app: &Rc<App>, member: &MemberView) {
    let dialog = adw::AlertDialog::builder()
        .heading("端末を外しますか？")
        .body(format!("「{}」は、もう再生を引き継げなくなります。もう一度加えるには、QR と6桁の手順が要ります。", member.label))
        .build();
    dialog.add_responses(&[("cancel", "キャンセル"), ("remove", "外す")]);
    dialog.set_response_appearance("remove", adw::ResponseAppearance::Destructive);
    dialog.set_default_response(Some("cancel"));
    dialog.set_close_response("cancel");
    let (app2, member) = (app.clone(), member.clone());
    dialog.connect_response(None, move |_, response| {
        if response != "remove" {
            return;
        }
        let (app, member) = (app2.clone(), member.clone());
        gtk::glib::spawn_future_local(async move {
            let removed = app.connect.remove_member(&member.device_id).await;
            app.toast(&if removed { format!("「{}」を外しました", member.label) } else { "外せませんでした".into() });
        });
    });
    if let Some(window) = app.window() {
        dialog.present(Some(&window));
    }
}

/// wave API にログインを渡す前に確かめる（Mac の DevicesText.grantMessage と同じ文）。
fn confirm_grant(app: &Rc<App>, member: &MemberView) {
    let label = member_label(member);
    let dialog = adw::AlertDialog::builder()
        .heading("ログインを渡しますか？")
        .body(format!(
            "「{label}」に、この端末の YouTube のログインを渡します。ほかのアプリ（home-dashboard など）から、あなたのプレイリスト・高く評価した曲・おすすめを使えるようになります。wave API を動かしている機械に入れる人は、それらを読めます。"
        ))
        .build();
    dialog.add_responses(&[("cancel", "やめる"), ("grant", "渡す")]);
    dialog.set_response_appearance("grant", adw::ResponseAppearance::Suggested);
    dialog.set_default_response(Some("cancel"));
    dialog.set_close_response("cancel");
    let (app2, device_id) = (app.clone(), member.device_id.clone());
    dialog.connect_response(None, move |_, response| {
        if response != "grant" {
            return;
        }
        let (app, device_id, label) = (app2.clone(), device_id.clone(), label.clone());
        gtk::glib::spawn_future_local(async move {
            let sent = app.connect.grant_login(&device_id).await;
            app.toast(&if sent { format!("「{label}」にログインを渡しました") } else { "ログインを渡せませんでした".into() });
        });
    });
    if let Some(window) = app.window() {
        dialog.present(Some(&window));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn qr_reads_back_as_the_offer_text() {
        let offer = "wave-pair:eyJ2IjoxLCJwa0EiOiJ0ZXN0IiwiZUEiOiJ0ZXN0IiwidG9rZW4iOiJ0ZXN0In0";
        let (pixels, size) = qr_pixels(offer).unwrap();
        assert!(size >= 280);
        let mut image = rqrr::PreparedImage::prepare_from_greyscale(size, size, |x, y| pixels[y * size + x]);
        let grids = image.detect_grids();
        assert_eq!(grids.len(), 1);
        let (_, content) = grids[0].decode().unwrap();
        assert_eq!(content, offer);
    }

    fn member(kind: Option<&str>, is_self: bool, is_controller: bool) -> MemberView {
        MemberView { device_id: "d".into(), label: "Pixel".into(), is_self, online: true, active: false, kind: kind.map(String::from), is_controller }
    }

    #[test]
    fn member_label_marks_controllers() {
        assert_eq!(member_label(&member(None, false, true)), "Pixel（操作だけ）");
        assert_eq!(member_label(&member(None, false, false)), "Pixel");
    }

    #[test]
    fn member_icon_by_kind() {
        assert_eq!(member_icon(&member(Some("api"), false, false)), "network-server-symbolic");
        assert_eq!(member_icon(&member(Some("pc"), false, false)), "computer-symbolic");
        assert_eq!(member_icon(&member(None, true, false)), "computer-symbolic");
        assert_eq!(member_icon(&member(Some("phone"), false, false)), "phone-symbolic");
    }

    #[test]
    fn done_text_asks_for_the_login_when_the_peer_has_none() {
        let api = PairingView::Done { peer: "wave API".into(), grant_device_id: Some("x".into()) };
        assert_eq!(pairing_text(&api), "「wave API」を加えました。プレイリストなどを使えるようにするには、ログインを渡してください。");
        let phone = PairingView::Done { peer: "Pixel".into(), grant_device_id: None };
        assert_eq!(pairing_text(&phone), "「Pixel」と連携しました");
    }
}
