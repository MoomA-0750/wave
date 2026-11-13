//! 設定のページ（右上のアカウントのメニューの「設定」から）。Mac の SettingsPage と同じ並び:
//! アカウント → 再生 → ダウンロード → Discord → Claude → 言語 → このアプリについて。

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::Duration;

use adw::prelude::*;
use wave_core::download::smart::{COUNTS, DownloadNetwork};

use super::shell::App;
use super::{images, pages};
use crate::lang;
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
    // 確かめ用: WAVE_DEMO_PAGE=settings-bottom なら、開いて少ししてからページの下（言語・このアプリについて）まで送る
    if std::env::var("WAVE_DEMO_PAGE").as_deref() == Ok("settings-bottom") {
        let prefs = prefs.clone();
        gtk::glib::timeout_add_local_once(std::time::Duration::from_millis(1500), move || {
            let mut child = prefs.first_child();
            while let Some(widget) = child {
                if let Some(scroller) = widget.downcast_ref::<gtk::ScrolledWindow>() {
                    let adj = scroller.vadjustment();
                    adj.set_value(adj.upper() - adj.page_size());
                    break;
                }
                child = widget.first_child();
            }
        });
    }
    adw::NavigationPage::builder().title(lang::t("設定", "Settings")).tag("settings").child(&prefs).build()
}

/// アカウント: 写真・名前・状態の文（Premium 音質の可否）・ログアウト（確かめてから）。
fn account_group(app: &Rc<App>, prefs: &adw::PreferencesPage) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().title(lang::t("アカウント", "Account")).build();
    let avatar = adw::Avatar::builder().size(56).show_initials(true).build();
    let row = adw::ActionRow::builder().build();
    row.add_prefix(&avatar);
    let logout = gtk::Button::builder().label(lang::t("ログアウト", "Log out")).valign(gtk::Align::Center).build();
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
            let dialog = adw::AlertDialog::new(Some(lang::t("ログアウトしますか？", "Log out?")), Some(lang::t("この PC に保存したログイン情報を消します。", "This removes your login saved on this PC.")));
            dialog.add_responses(&[("cancel", lang::t("キャンセル", "Cancel")), ("logout", lang::t("ログアウト", "Log out"))]);
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
        return (lang::t("ログインしていません", "Not logged in").into(), lang::t("ログインすると、ライブラリやおすすめが使えます", "Log in to use your library and recommendations").into());
    }
    let title = match &name {
        Some(Some(n)) => n.clone(),
        Some(None) => lang::t("アカウントを取得できませんでした", "Couldn't get your account").into(),
        None => lang::t("ログイン中", "Logged in").into(),
    };
    if name.is_none() && premium.is_none() {
        return (title, lang::t("アカウントを確かめています…", "Checking account…").into());
    }
    let state = match premium {
        Some(Some(true)) => lang::t("使えます", "Available"),
        Some(Some(false)) => lang::t("使えません", "Unavailable"),
        Some(None) => lang::t("分かりません", "Unknown"),
        None => lang::t("確かめています…", "Checking…"),
    };
    (title, format!("{}{state}", lang::t("Premium 音質: ", "Premium audio: ")))
}

/// 再生: 再生履歴に残す・新作の通知。
fn play_group(app: &Rc<App>) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().title(lang::t("再生", "Play")).build();
    let settings = app.downloads.settings.borrow().clone();
    let send_history = adw::SwitchRow::builder()
        .title(lang::t("再生した曲を YouTube Music の再生履歴に残す", "Save played songs to your YouTube Music history"))
        .subtitle(lang::t("オフにすると、wave で聴いた曲は履歴やおすすめに使われません", "When off, songs you play in wave won’t be used for history or recommendations"))
        .active(settings.send_history)
        .build();
    let notices = adw::SwitchRow::builder()
        .title(lang::t("新作の通知", "New release notifications"))
        .subtitle(lang::t("登録したアーティストのアルバム・シングル・動画が出たら知らせる（wave が動いている間、6時間おきに確かめる）", "Notify when artists you subscribe to release albums, singles or videos (checked every 6 hours while wave is running)"))
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
    let group = adw::PreferencesGroup::builder().title(lang::t("ダウンロード", "Downloads")).build();
    let settings = app.downloads.settings.borrow().clone();
    let network = adw::ComboRow::builder()
        .title(lang::t("ダウンロードに使う通信", "Download using"))
        .model(&gtk::StringList::new(&[lang::t("Wi-Fi など従量制でない通信だけ", "Wi-Fi and other unmetered networks only"), lang::t("どの通信でも", "Any network")]))
        .selected(if settings.download_network == DownloadNetwork::Any { 1 } else { 0 })
        .build();
    let smart = adw::SwitchRow::builder()
        .title(lang::t("スマートダウンロード", "Smart downloads"))
        .subtitle(lang::t("いつも聴いている曲・好きそうな曲・おすすめから、聴きそうな曲を毎日選び直して取っておく", "Keeps songs you're likely to play from what you play often, songs you might like and recommendations, refreshed every day"))
        .active(settings.smart_downloads)
        .build();
    let labels: Vec<String> = COUNTS.iter().map(|c| lang::songs(*c as usize)).collect();
    // 曲数はスマートダウンロードを入れている間だけ出す（Mac と同じ）
    let count = adw::ComboRow::builder()
        .title(lang::t("曲数", "Number of songs"))
        .model(&gtk::StringList::new(&labels.iter().map(String::as_str).collect::<Vec<_>>()))
        .selected(COUNTS.iter().position(|c| *c == settings.smart_download_count).unwrap_or(1) as u32)
        .visible(settings.smart_downloads)
        .build();
    let usage = adw::ActionRow::builder().title(lang::t("ダウンロード", "Downloads")).build();
    let open = gtk::Button::builder().label(lang::t("開く", "Open")).valign(gtk::Align::Center).build();
    let clear = gtk::Button::builder().label(lang::t("すべて削除", "Delete all")).valign(gtk::Align::Center).css_classes(["destructive-action"]).build();
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
                let dialog = adw::AlertDialog::new(Some(lang::t("ダウンロードをすべて削除しますか？", "Delete all downloads?")), Some(lang::t("この PC に取った曲をすべて消します。", "This removes every song saved on this PC.")));
                dialog.add_responses(&[("cancel", lang::t("キャンセル", "Cancel")), ("delete", lang::t("すべて削除", "Delete all"))]);
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
        .description(lang::t("discord.com/developers/applications で「wave」という名前のアプリケーションを作り、その Application ID を貼ってください。", "Create an application named “wave” at discord.com/developers/applications and paste its Application ID."))
        .build();
    let on = adw::SwitchRow::builder()
        .title(lang::t("Discord に再生中の曲を出す", "Show what you’re playing on Discord"))
        .subtitle(lang::t("この PC の Discord のアプリに「聴いています」を出します。スマホなどほかの端末で鳴らしている曲も出します", "Shows “Listening to” through the Discord app on this PC, including songs playing on your other devices"))
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
        return lang::t("Application ID を入れると出せます", "Enter the Application ID to turn this on");
    }
    crate::discord_status::status_text()
}

/// Claude: 説明・接続の文字列の欄（伏せ字）・接続・接続を外す・状態の文。トークンはキーリングに置く。
fn claude_group(app: &Rc<App>) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder()
        .title("Claude")
        .description(lang::t("家の PC で ai-bridge の ai_bridge.py --name wave --pair を動かし、出てきた waveai://pair?… の文字列を貼ってください。", "Run ai_bridge.py --name wave --pair from ai-bridge on your PC and paste the waveai://pair?… text it prints."))
        .build();
    let state = adw::ActionRow::builder().build();
    let unpair = gtk::Button::builder().label(lang::t("接続を外す", "Disconnect")).valign(gtk::Align::Center).build();
    state.add_suffix(&unpair);
    // 題の & は書式として読まれるので、書式を切る
    let entry = adw::PasswordEntryRow::builder().title("waveai://pair?u=…&t=…").use_markup(false).build();
    let connect = gtk::Button::builder().label(lang::t("接続", "Connect")).valign(gtk::Align::Center).css_classes(["suggested-action"]).build();
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
                    app.toast(lang::t("wave の Claude の中継の接続の文字列ではありません", "That isn't a wave Claude bridge pairing text"));
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
                Some(name) => if lang::ja() { format!("{name} の中継につないでいます") } else { format!("Connected to {name}") },
                None => lang::t("家の PC の Claude Code で、歌詞の意味・似た曲・プレイリスト作りを頼めます", "Ask about lyrics, find similar songs and make playlists with Claude Code on your PC").into(),
            };
            state.set_title(&gtk::glib::markup_escape_text(&text));
            unpair.set_visible(name.is_some());
        }
    };
    refresh();
    app.ai.on_change(refresh);
    group
}

/// 画面の言語の選び肢（設定の ui_language の値と、出す名前）。言語の名前はその言語で書く。
const UI_LANGUAGES: [&str; 3] = ["", "ja", "en"];

/// 言語: 画面の言語（端末に合わせる・日本語・English。次の起動から）と曲名の表記。
fn language_group(app: &Rc<App>) -> adw::PreferencesGroup {
    // 説明は行に付けると選び肢の字が切れるので、段の説明に置く
    let group = adw::PreferencesGroup::builder()
        .title(lang::t("言語", "Language"))
        .description(lang::t(
            "画面の言語は次の起動から反映されます。YouTube から届く文字（棚の見出しなど）も画面の言語になります",
            "The app language takes effect the next time you open wave. Text from YouTube (such as shelf titles) also uses it",
        ))
        .build();
    let current = app.downloads.settings.borrow().ui_language.clone();
    let ui_language = adw::ComboRow::builder()
        .title(lang::t("画面の言語", "App language"))
        .model(&gtk::StringList::new(&[lang::t("端末に合わせる", "System"), "日本語", "English"]))
        .selected(UI_LANGUAGES.iter().position(|v| *v == current).unwrap_or(0) as u32)
        .build();
    group.add(&ui_language);
    {
        let app = app.clone();
        ui_language.connect_selected_notify(move |row| {
            let value = UI_LANGUAGES.get(row.selected() as usize).copied().unwrap_or("");
            if app.downloads.settings.borrow().ui_language == value {
                return;
            }
            app.downloads.update_settings(|s| s.ui_language = value.to_string());
            app.toast(lang::t("次の起動から変わります", "Takes effect the next time you open wave"));
        });
    }
    let original_titles = adw::SwitchRow::builder()
        .title(lang::t("曲名を元の表記で", "Show titles as released"))
        .subtitle(lang::t("曲名・アーティスト名・アルバム名を、曲の言語のまま出します（洋楽をカタカナに、邦楽をローマ字にしません）。YouTube に2回問い合わせるので、ページの表示が少し遅くなります", "Keep song, artist, and album names in their own language (no katakana for Western songs, no romaji for Japanese songs). wave asks YouTube twice, so pages load a little slower"))
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
    let group = adw::PreferencesGroup::builder().title(lang::t("このアプリについて", "About")).build();
    let version = adw::ActionRow::builder()
        .title(concat!("wave for Linux ", env!("CARGO_PKG_VERSION")))
        .subtitle(lang::t("ログイン情報と Claude の接続は GNOME のキーリング（Secret Service）に保存し、ファイルには書きません。", "Your login and Claude connection are stored in the GNOME keyring (Secret Service), never in files."))
        .build();
    let data = gtk::glib::user_data_dir().join("wave");
    let place = adw::ActionRow::builder()
        .title(lang::t("データの場所", "Data folder"))
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
        assert_eq!(account_texts(false, None, None), (lang::t("ログインしていません", "Not logged in").into(), lang::t("ログインすると、ライブラリやおすすめが使えます", "Log in to use your library and recommendations").into()));
        assert_eq!(account_texts(true, None, None), (lang::t("ログイン中", "Logged in").into(), lang::t("アカウントを確かめています…", "Checking account…").into()));
        assert_eq!(account_texts(true, Some(Some("名前".into())), None), ("名前".into(), lang::t("Premium 音質: 確かめています…", "Premium audio: Checking…").into()));
        assert_eq!(account_texts(true, Some(Some("名前".into())), Some(Some(true))), ("名前".into(), lang::t("Premium 音質: 使えます", "Premium audio: Available").into()));
        assert_eq!(account_texts(true, Some(None), Some(None)), (lang::t("アカウントを取得できませんでした", "Couldn't get your account").into(), lang::t("Premium 音質: 分かりません", "Premium audio: Unknown").into()));
        assert_eq!(account_texts(true, None, Some(Some(false))), (lang::t("ログイン中", "Logged in").into(), lang::t("Premium 音質: 使えません", "Premium audio: Unavailable").into()));
    }

    #[test]
    fn discord_id_keeps_only_digits() {
        assert_eq!(digits_only(" 12 34a\n56 "), "123456");
    }
}
