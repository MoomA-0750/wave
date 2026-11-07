//! 設定のページ（右上のアカウントのメニューの「設定」から）。Mac の SettingsPage と同じ並び:
//! アカウント → 再生 → ダウンロード → Discord → Claude → 言語 → このアプリについて。

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::Duration;

use adw::prelude::*;
use wave_core::download::smart::{COUNTS, DownloadNetwork};

use super::shell::App;
use super::{images, pages};
use crate::runtime;

pub fn page(app: &Rc<App>) -> adw::NavigationPage {
    let prefs = adw::PreferencesPage::new();
    prefs.add(&account_group(app, &prefs));
    prefs.add(&play_group(app));
    prefs.add(&downloads_group(app, &prefs));
    prefs.add(&discord_group(app));
    prefs.add(&claude_group(app));
    prefs.add(&language_group(app));
    prefs.add(&about_group());
    adw::NavigationPage::builder().title("設定").tag("settings").child(&prefs).build()
}

/// アカウント: 写真・名前・状態の文（Premium 音質の可否）・ログアウト（確かめてから）。
fn account_group(app: &Rc<App>, prefs: &adw::PreferencesPage) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().title("アカウント").build();
    let avatar = adw::Avatar::builder().size(56).show_initials(true).build();
    let row = adw::ActionRow::builder().build();
    row.add_prefix(&avatar);
    let logout = gtk::Button::builder().label("ログアウト").valign(gtk::Align::Center).build();
    row.add_suffix(&logout);
    group.add(&row);

    let logged_in = app.repo.client().is_logged_in();
    logout.set_visible(logged_in);
    // 名前（None はまだ確かめている。Some(None) は取れなかった）と Premium 音質（None はまだ確かめている）
    let name: Rc<RefCell<Option<Option<String>>>> = Rc::default();
    let premium: Rc<Cell<Option<Option<bool>>>> = Rc::default();
    let show = {
        let (row, name, premium) = (row.clone(), name.clone(), premium.clone());
        Rc::new(move || {
            let (title, state) = account_texts(logged_in, name.borrow().clone(), premium.get());
            row.set_title(&gtk::glib::markup_escape_text(&title));
            row.set_subtitle(&gtk::glib::markup_escape_text(&state));
        })
    };
    show();
    if logged_in {
        let (repo, show_name, avatar, name) = (app.repo.clone(), show.clone(), avatar.clone(), name.clone());
        gtk::glib::spawn_future_local(async move {
            let show = show_name;
            let account = runtime::run(async move { repo.account().await }).await.ok();
            let text = account.as_ref().map(|a| a.name.clone().unwrap_or_default()).filter(|n| !n.is_empty());
            avatar.set_text(text.as_deref());
            if let Some(url) = account.and_then(|a| a.photo_url) {
                images::texture(&url, 112, move |texture| avatar.set_custom_image(Some(&texture)));
            }
            *name.borrow_mut() = Some(text);
            show();
        });
        // 開くたびに確かめる（Windows・Android と同じ）
        let app = app.clone();
        gtk::glib::spawn_future_local(async move {
            premium.set(Some(app.playback.player().premium_audio().await));
            show();
        });
    }
    let prefs = prefs.clone();
    logout.connect_clicked(move |_| {
        let prefs = prefs.clone();
        gtk::glib::spawn_future_local(async move {
            let dialog = adw::AlertDialog::new(Some("ログアウトしますか？"), Some("この PC に保存したログイン情報を消します。"));
            dialog.add_responses(&[("cancel", "キャンセル"), ("logout", "ログアウト")]);
            dialog.set_response_appearance("logout", adw::ResponseAppearance::Destructive);
            dialog.set_close_response("cancel");
            if dialog.choose_future(Some(&prefs)).await == "logout" {
                crate::log_out();
            }
        });
    });
    group
}

/// アカウントの行の題と状態の文（Mac の AccountState.titleText・stateText と同じ）。
/// name: None はまだ確かめている、Some(None) は取れなかった。premium: None はまだ確かめている、Some(None) は分からなかった。
fn account_texts(logged_in: bool, name: Option<Option<String>>, premium: Option<Option<bool>>) -> (String, String) {
    if !logged_in {
        return ("ログインしていません".into(), "ログインすると、ライブラリやおすすめが使えます".into());
    }
    let title = match &name {
        Some(Some(n)) => n.clone(),
        Some(None) => "アカウントを取得できませんでした".into(),
        None => "ログイン中".into(),
    };
    if name.is_none() && premium.is_none() {
        return (title, "アカウントを確かめています…".into());
    }
    let state = match premium {
        Some(Some(true)) => "使えます",
        Some(Some(false)) => "使えません",
        Some(None) => "分かりません",
        None => "確かめています…",
    };
    (title, format!("Premium 音質: {state}"))
}

/// 再生: 再生履歴に残す・新作の通知。
fn play_group(app: &Rc<App>) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().title("再生").build();
    let settings = app.downloads.settings.borrow().clone();
    let send_history = adw::SwitchRow::builder()
        .title("再生した曲を YouTube Music の再生履歴に残す")
        .subtitle("オフにすると、wave で聴いた曲は履歴やおすすめに使われません")
        .active(settings.send_history)
        .build();
    let notices = adw::SwitchRow::builder()
        .title("新作の通知")
        .subtitle("登録したアーティストのアルバム・シングル・動画が出たら知らせる（wave が動いている間、6時間おきに確かめる）")
        .active(settings.release_notices)
        .build();
    group.add(&send_history);
    group.add(&notices);
    {
        let app = app.clone();
        send_history.connect_active_notify(move |row| {
            let on = row.is_active();
            app.downloads.update_settings(|s| s.send_history = on);
        });
    }
    {
        // 新作の確かめは毎回この設定を見るので、切り替えはすぐ効く
        let app = app.clone();
        notices.connect_active_notify(move |row| {
            let on = row.is_active();
            app.downloads.update_settings(|s| s.release_notices = on);
        });
    }
    group
}

/// ダウンロード: 通信・スマートダウンロードと曲数・容量と進み具合（開く・すべて削除）。
fn downloads_group(app: &Rc<App>, prefs: &adw::PreferencesPage) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().title("ダウンロード").build();
    let settings = app.downloads.settings.borrow().clone();
    let network = adw::ComboRow::builder()
        .title("ダウンロードに使う通信")
        .model(&gtk::StringList::new(&["Wi-Fi など従量制でない通信だけ", "どの通信でも"]))
        .selected(if settings.download_network == DownloadNetwork::Any { 1 } else { 0 })
        .build();
    let smart = adw::SwitchRow::builder()
        .title("スマートダウンロード")
        .subtitle("いつも聴いている曲・好きそうな曲・おすすめから、聴きそうな曲を毎日選び直して取っておく")
        .active(settings.smart_downloads)
        .build();
    let labels: Vec<String> = COUNTS.iter().map(|c| format!("{c} 曲")).collect();
    // 曲数はスマートダウンロードを入れている間だけ出す（Mac と同じ）
    let count = adw::ComboRow::builder()
        .title("曲数")
        .model(&gtk::StringList::new(&labels.iter().map(String::as_str).collect::<Vec<_>>()))
        .selected(COUNTS.iter().position(|c| *c == settings.smart_download_count).unwrap_or(1) as u32)
        .visible(settings.smart_downloads)
        .build();
    let usage = adw::ActionRow::builder().title("ダウンロード").build();
    let open = gtk::Button::builder().label("開く").valign(gtk::Align::Center).build();
    let clear = gtk::Button::builder().label("すべて削除").valign(gtk::Align::Center).css_classes(["destructive-action"]).build();
    usage.add_suffix(&open);
    usage.add_suffix(&clear);
    for row in [network.upcast_ref::<gtk::Widget>(), smart.upcast_ref(), count.upcast_ref(), usage.upcast_ref()] {
        group.add(row);
    }
    {
        let app = app.clone();
        network.connect_selected_notify(move |row| {
            let value = if row.selected() == 1 { DownloadNetwork::Any } else { DownloadNetwork::Unmetered };
            app.downloads.update_settings(|s| s.download_network = value);
        });
    }
    {
        let (app, count) = (app.clone(), count.clone());
        smart.connect_active_notify(move |row| {
            let on = row.is_active();
            count.set_visible(on);
            app.downloads.update_settings(|s| s.smart_downloads = on);
        });
    }
    {
        let app = app.clone();
        count.connect_selected_notify(move |row| {
            let value = COUNTS[row.selected() as usize % COUNTS.len()];
            app.downloads.update_settings(|s| s.smart_download_count = value);
        });
    }
    {
        let app = app.clone();
        open.connect_clicked(move |_| app.push(pages::downloads(&app)));
    }
    {
        let (app, prefs) = (app.clone(), prefs.clone());
        clear.connect_clicked(move |_| {
            let (app, prefs) = (app.clone(), prefs.clone());
            gtk::glib::spawn_future_local(async move {
                let dialog = adw::AlertDialog::new(Some("ダウンロードをすべて削除しますか？"), Some("この PC に取った曲をすべて消します。"));
                dialog.add_responses(&[("cancel", "キャンセル"), ("delete", "すべて削除")]);
                dialog.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
                dialog.set_close_response("cancel");
                if dialog.choose_future(Some(&prefs)).await == "delete" {
                    app.downloads.clear();
                }
            });
        });
    }
    let refresh = {
        let (downloads, clear) = (app.downloads.clone(), clear.clone());
        move |usage: &adw::ActionRow| {
            usage.set_subtitle(&gtk::glib::markup_escape_text(&downloads.status_text()));
            // 何も無いときは押せない
            clear.set_sensitive(!downloads.is_empty());
        }
    };
    let refresh = Rc::new(refresh);
    {
        let refresh = refresh.clone();
        app.downloads.watch(&usage, move |usage| refresh(usage));
    }
    // 取っている途中の % は裏で進むので、開いている間は1秒おきに書き直す（ページを閉じたら止める）
    let weak = usage.downgrade();
    gtk::glib::timeout_add_local(Duration::from_secs(1), move || {
        let Some(usage) = weak.upgrade() else { return gtk::glib::ControlFlow::Break };
        refresh(&usage);
        gtk::glib::ControlFlow::Continue
    });
    group
}

/// Discord: 入り切り・Application ID（打つたびに保存。数字以外は落とす）・状態の文（2秒おき）。
fn discord_group(app: &Rc<App>) -> adw::PreferencesGroup {
    let settings = app.downloads.settings.borrow().clone();
    let group = adw::PreferencesGroup::builder()
        .title("Discord")
        .description("discord.com/developers/applications で「wave」という名前のアプリケーションを作り、その Application ID を貼ってください。")
        .build();
    let on = adw::SwitchRow::builder()
        .title("Discord に再生中の曲を出す")
        .subtitle("この PC の Discord のアプリに「聴いています」を出します。スマホなどほかの端末で鳴らしている曲も出します")
        .active(settings.discord_presence)
        .build();
    let id = adw::EntryRow::builder().title("Application ID").text(settings.discord_client_id.as_deref().unwrap_or("")).build();
    let state = gtk::Label::builder().xalign(0.0).wrap(true).margin_top(8).css_classes(["caption", "dim-label"]).build();
    group.add(&on);
    group.add(&id);
    group.add(&state);
    // 文の部品は弱く持つ（ページを閉じたら、2秒おきの書き直しが止まるように）
    let show_state = {
        let (app, state) = (app.clone(), state.downgrade());
        Rc::new(move || {
            let Some(state) = state.upgrade() else { return };
            let text = discord_state_text(&app);
            state.set_visible(!text.is_empty());
            state.set_label(text);
        })
    };
    show_state();
    {
        let (app, show_state) = (app.clone(), show_state.clone());
        on.connect_active_notify(move |row| {
            let on = row.is_active();
            app.downloads.update_settings(|s| s.discord_presence = on);
            crate::discord_status::push();
            show_state();
        });
    }
    {
        let (app, show_state) = (app.clone(), show_state.clone());
        id.connect_changed(move |row| {
            let text = row.text().to_string();
            let digits = digits_only(&text);
            if digits != text {
                // 貼るときに混ざった空白などは落とす（書き換えは変わった知らせの外で）
                let row = row.clone();
                gtk::glib::idle_add_local_once(move || {
                    row.set_text(&digits);
                    row.set_position(-1);
                });
                return;
            }
            let value = (!digits.is_empty()).then_some(digits);
            if app.downloads.settings.borrow().discord_client_id == value {
                return;
            }
            app.downloads.update_settings(|s| s.discord_client_id = value);
            crate::discord_status::push();
            show_state();
        });
    }
    {
        // つなぎ具合は裏で変わるので、開いている間は2秒おきに書き直す（ページを閉じたら止める）
        let weak = state.downgrade();
        gtk::glib::timeout_add_local(Duration::from_secs(2), move || {
            if weak.upgrade().is_none() {
                return gtk::glib::ControlFlow::Break;
            }
            show_state();
            gtk::glib::ControlFlow::Continue
        });
    }
    group
}

fn digits_only(text: &str) -> String {
    text.chars().filter(char::is_ascii_digit).collect()
}

/// Discord の状態の文（切っていれば空。Mac の discordStateText と同じ）。
fn discord_state_text(app: &App) -> &'static str {
    let settings = app.downloads.settings.borrow();
    if !settings.discord_presence {
        return "";
    }
    if settings.discord_client_id.is_none() {
        return "Application ID を入れると出せます";
    }
    crate::discord_status::status_text()
}

/// Claude: 説明・接続の文字列の欄（伏せ字）・接続・接続を外す・状態の文。トークンはキーリングに置く。
fn claude_group(app: &Rc<App>) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder()
        .title("Claude")
        .description("家の PC で ai-bridge の ai_bridge.py --name wave --pair を動かし、出てきた waveai://pair?… の文字列を貼ってください。")
        .build();
    let state = adw::ActionRow::builder().build();
    let unpair = gtk::Button::builder().label("接続を外す").valign(gtk::Align::Center).build();
    state.add_suffix(&unpair);
    // 題の & は書式として読まれるので、書式を切る
    let entry = adw::PasswordEntryRow::builder().title("waveai://pair?u=…&t=…").use_markup(false).build();
    let connect = gtk::Button::builder().label("接続").valign(gtk::Align::Center).css_classes(["suggested-action"]).build();
    entry.add_suffix(&connect);
    group.add(&state);
    group.add(&entry);
    let submit = {
        let (app, entry) = (app.clone(), entry.clone());
        Rc::new(move || {
            let (app, entry) = (app.clone(), entry.clone());
            let text = entry.text().trim().to_string();
            if text.is_empty() {
                return;
            }
            gtk::glib::spawn_future_local(async move {
                if app.ai.pair(&text).await {
                    entry.set_text("");
                } else {
                    app.toast("wave の Claude の中継の接続の文字列ではありません");
                }
            });
        })
    };
    {
        let submit = submit.clone();
        connect.connect_clicked(move |_| submit());
    }
    // Enter でもつなぐ
    entry.connect_entry_activated(move |_| submit());
    {
        let app = app.clone();
        unpair.connect_clicked(move |_| {
            let app = app.clone();
            gtk::glib::spawn_future_local(async move { app.ai.unpair().await });
        });
    }
    let refresh = {
        let (app, state, unpair) = (app.clone(), state.downgrade(), unpair.downgrade());
        move || {
            let (Some(state), Some(unpair)) = (state.upgrade(), unpair.upgrade()) else { return };
            let name = app.ai.configured();
            let text = match &name {
                Some(name) => format!("{name} の中継につないでいます"),
                None => "家の PC の Claude Code で、歌詞の意味・似た曲・プレイリスト作りを頼めます".into(),
            };
            state.set_title(&gtk::glib::markup_escape_text(&text));
            unpair.set_visible(name.is_some());
        }
    };
    refresh();
    app.ai.on_change(refresh);
    group
}

/// 言語: Linux は画面の言語を選ばない（LANG に従う）ので、曲名の表記だけ。
fn language_group(app: &Rc<App>) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().title("言語").build();
    let original_titles = adw::SwitchRow::builder()
        .title("曲名を元の表記で")
        .subtitle("曲名・アーティスト名・アルバム名を、曲の言語のまま出します（洋楽をカタカナに、邦楽をローマ字にしません）。YouTube に2回問い合わせるので、ページの表示が少し遅くなります")
        .active(app.downloads.settings.borrow().original_titles)
        .build();
    group.add(&original_titles);
    {
        // 次の読み取り（ページを開く・検索する）から効く
        let app = app.clone();
        original_titles.connect_active_notify(move |row| {
            let on = row.is_active();
            app.downloads.update_settings(|s| s.original_titles = on);
            app.repo.client().set_original_titles(on);
        });
    }
    group
}

/// このアプリについて: 版・ログインの置き場所・データの場所（選んでコピーできる）。
fn about_group() -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().title("このアプリについて").build();
    let version = adw::ActionRow::builder()
        .title(concat!("wave for Linux ", env!("CARGO_PKG_VERSION")))
        .subtitle("ログイン情報と Claude の接続は GNOME のキーリング（Secret Service）に保存し、ファイルには書きません。")
        .build();
    let data = gtk::glib::user_data_dir().join("wave");
    let place = adw::ActionRow::builder()
        .title("データの場所")
        .subtitle(gtk::glib::markup_escape_text(&data.display().to_string()))
        .subtitle_selectable(true)
        .build();
    group.add(&version);
    group.add(&place);
    group
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn account_texts_follow_the_checks() {
        assert_eq!(account_texts(false, None, None), ("ログインしていません".into(), "ログインすると、ライブラリやおすすめが使えます".into()));
        assert_eq!(account_texts(true, None, None), ("ログイン中".into(), "アカウントを確かめています…".into()));
        assert_eq!(account_texts(true, Some(Some("名前".into())), None), ("名前".into(), "Premium 音質: 確かめています…".into()));
        assert_eq!(account_texts(true, Some(Some("名前".into())), Some(Some(true))), ("名前".into(), "Premium 音質: 使えます".into()));
        assert_eq!(account_texts(true, Some(None), Some(None)), ("アカウントを取得できませんでした".into(), "Premium 音質: 分かりません".into()));
        assert_eq!(account_texts(true, None, Some(Some(false))), ("ログイン中".into(), "Premium 音質: 使えません".into()));
    }

    #[test]
    fn discord_id_keeps_only_digits() {
        assert_eq!(digits_only(" 12 34a\n56 "), "123456");
    }
}
