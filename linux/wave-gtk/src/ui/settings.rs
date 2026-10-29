//! 設定のページ（右上のアカウントのメニューの「設定」から）。ダウンロード（スマートダウンロード・曲数・通信・使っている容量・すべて削除）と Claude のつなぎ先。

use std::rc::Rc;

use adw::prelude::*;
use wave_core::download::smart::{COUNTS, DownloadNetwork};

use super::shell::App;
use crate::downloads::format_bytes;

pub fn page(app: &Rc<App>) -> adw::NavigationPage {
    let prefs = adw::PreferencesPage::new();

    // アカウント: Premium 音質を使えるか（Windows・Android と同じ。開くたびに確かめる）
    let account = adw::PreferencesGroup::builder().title("アカウント").build();
    let premium = adw::ActionRow::builder().title("Premium 音質").subtitle("確かめています…").build();
    account.add(&premium);
    prefs.add(&account);
    {
        let (app, premium) = (app.clone(), premium.clone());
        gtk::glib::spawn_future_local(async move {
            let found = app.playback.player().premium_audio().await;
            premium.set_subtitle(match found {
                Some(true) => "使えます（256kbps）",
                Some(false) => "使えません",
                None => "分かりません",
            });
        });
    }

    let downloads = adw::PreferencesGroup::builder().title("ダウンロード").build();
    let settings = app.downloads.settings.borrow().clone();
    let smart = adw::SwitchRow::builder()
        .title("スマートダウンロード")
        .subtitle("いつも聴いている・好きそう・おすすめの曲を、1日1回選び直して取っておく")
        .active(settings.smart_downloads)
        .build();
    let labels: Vec<String> = COUNTS.iter().map(|c| format!("{c} 曲")).collect();
    let count = adw::ComboRow::builder()
        .title("取っておく曲数")
        .model(&gtk::StringList::new(&labels.iter().map(String::as_str).collect::<Vec<_>>()))
        .selected(COUNTS.iter().position(|c| *c == settings.smart_download_count).unwrap_or(1) as u32)
        .sensitive(settings.smart_downloads)
        .build();
    let network = adw::ComboRow::builder()
        .title("ダウンロードに使う通信")
        .model(&gtk::StringList::new(&["従量制でない通信だけ", "どの通信でも"]))
        .selected(if settings.download_network == DownloadNetwork::Any { 1 } else { 0 })
        .build();
    let usage = adw::ActionRow::builder().title("使っている容量").build();
    let clear = gtk::Button::builder().label("すべて削除").valign(gtk::Align::Center).css_classes(["destructive-action"]).build();
    usage.add_suffix(&clear);
    for row in [smart.upcast_ref::<gtk::Widget>(), count.upcast_ref(), network.upcast_ref(), usage.upcast_ref()] {
        downloads.add(row);
    }
    prefs.add(&downloads);

    let history = adw::PreferencesGroup::builder().title("再生履歴と新作").build();
    let send_history = adw::SwitchRow::builder()
        .title("聴いた曲を再生履歴に載せる")
        .subtitle("30秒（短い曲は半分）聴いた曲を、YouTube Music の再生履歴に送る")
        .active(app.downloads.settings.borrow().send_history)
        .build();
    history.add(&send_history);
    let notices = adw::SwitchRow::builder()
        .title("登録したアーティストの新作を知らせる")
        .subtitle("6時間おきに確かめ、よく聴くアーティストの新作を通知する。新作はホームにも出す（次に開いたときから）")
        .active(app.downloads.settings.borrow().release_notices)
        .build();
    history.add(&notices);
    {
        let app = app.clone();
        notices.connect_active_notify(move |row| {
            let on = row.is_active();
            app.downloads.update_settings(|s| s.release_notices = on);
        });
    }
    prefs.add(&history);
    {
        let app = app.clone();
        send_history.connect_active_notify(move |row| {
            let on = row.is_active();
            app.downloads.update_settings(|s| s.send_history = on);
        });
    }

    super::devices::add_to(&prefs, app);

    let claude = adw::PreferencesGroup::builder().title("Claude").build();
    let bridge = adw::ActionRow::builder().title("中継（ai-bridge）").build();
    let unpair = gtk::Button::builder().label("外す").valign(gtk::Align::Center).build();
    bridge.add_suffix(&unpair);
    claude.add(&bridge);
    prefs.add(&claude);

    {
        let (app, count) = (app.clone(), count.clone());
        smart.connect_active_notify(move |row| {
            let on = row.is_active();
            count.set_sensitive(on);
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
        network.connect_selected_notify(move |row| {
            let value = if row.selected() == 1 { DownloadNetwork::Any } else { DownloadNetwork::Unmetered };
            app.downloads.update_settings(|s| s.download_network = value);
        });
    }
    {
        let app = app.clone();
        clear.connect_clicked(move |_| {
            app.downloads.clear();
            app.toast("ダウンロードをすべて削除しました");
        });
    }
    {
        let app = app.clone();
        unpair.connect_clicked(move |_| {
            let app = app.clone();
            gtk::glib::spawn_future_local(async move { app.ai.unpair().await });
        });
    }
    let refresh_bridge = {
        let (app, bridge, unpair) = (app.clone(), bridge.clone(), unpair.clone());
        move || {
            let name = app.ai.configured();
            bridge.set_subtitle(&gtk::glib::markup_escape_text(name.as_deref().unwrap_or("つないでいません（Claude の欄でつなぐ）")));
            unpair.set_visible(name.is_some());
        }
    };
    refresh_bridge();
    app.ai.on_change(refresh_bridge);
    let downloads_ref = app.downloads.clone();
    app.downloads.watch(&usage, move |usage| {
        let mut text = format_bytes(downloads_ref.total_bytes());
        if !downloads_ref.network_allows() {
            text.push_str("・今の通信では取らない設定です");
        }
        usage.set_subtitle(&text);
    });
    adw::NavigationPage::builder().title("設定").tag("settings").child(&prefs).build()
}
