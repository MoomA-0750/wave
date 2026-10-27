//! Google アカウントへのログイン（Windows の WebView2・Android の WebView と同じやり方を WebKitGTK で）。
//! music.youtube.com に着いたら cookie とページの yt.config_ の VISITOR_DATA・DATASYNC_ID を読み、キーリングに保存する。
//! 開いてよいのはログインに要る Google・YouTube のホストだけで、ほかは止める（利用者が押したリンクはふだんのブラウザで開く）。
//! ブラウザの記録（cookie など）は使い捨てにし、ディスクに残さない。

use std::cell::Cell;
use std::rc::Rc;
use std::time::Duration;

use adw::prelude::*;
use webkit6::prelude::*;
use wave_core::credentials::Credentials;

use crate::keyring;

const START_URL: &str = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F";
const LANDING_HOST: &str = "music.youtube.com";

/// メインフレームで開いてよいホスト（利用者がページを置ける sites・docs などは含めない）。
const LOGIN_HOSTS: [&str; 14] = [
    "accounts.google.com", "accounts.google.co.jp", "accounts.youtube.com", "myaccount.google.com", "gds.google.com",
    "consent.google.com", "consent.youtube.com", "google.com", "www.google.com", "www.google.co.jp",
    "youtube.com", "www.youtube.com", "m.youtube.com", LANDING_HOST,
];

pub fn is_allowed(uri: &str) -> bool {
    gtk::glib::Uri::parse(uri, gtk::glib::UriFlags::NONE)
        .is_ok_and(|u| u.scheme() == "https" && u.port() == -1 && u.host().is_some_and(|h| LOGIN_HOSTS.contains(&h.as_str())))
}

fn host_of(uri: &str) -> String {
    gtk::glib::Uri::parse(uri, gtk::glib::UriFlags::NONE).ok().and_then(|u| u.host()).map(|h| h.to_string()).unwrap_or_default()
}

/// ログインの窓を開く。保存まで済んだら on_done(Some(credentials))、閉じたら on_done(None)。
pub fn open(parent: &impl IsA<gtk::Window>, on_done: impl Fn(Option<Credentials>) + 'static) {
    let session = webkit6::NetworkSession::new_ephemeral();
    let view = webkit6::WebView::builder().network_session(&session).build();
    if let Some(settings) = webkit6::prelude::WebViewExt::settings(&view) {
        // 実アカウントでログイン中は開発者ツールを使わせない（Android 版の決定7と同じ）
        settings.set_enable_developer_extras(false);
    }
    view.connect_decide_policy(|_, decision, kind| {
        if kind != webkit6::PolicyDecisionType::NavigationAction && kind != webkit6::PolicyDecisionType::NewWindowAction {
            return false;
        }
        let Some(navigation) = decision.downcast_ref::<webkit6::NavigationPolicyDecision>() else { return false };
        let Some(action) = navigation.navigation_action() else { return false };
        let Some(uri) = action.request().and_then(|r| r.uri()) else { return false };
        if is_allowed(&uri) {
            return false;
        }
        decision.ignore();
        eprintln!("login blocked host={}", host_of(&uri));
        // 利用者が自分で押したリンクだけ、ふだんのブラウザで開く
        if action.is_user_gesture() && uri.starts_with("https://") {
            gtk::UriLauncher::new(&uri).launch(None::<&gtk::Window>, None::<&gtk::gio::Cancellable>, |_| {});
        }
        true
    });
    let status = gtk::Label::builder().wrap(true).visible(false).margin_top(12).margin_bottom(12).build();
    let host = adw::WindowTitle::new("wave にログイン", "");
    let header = adw::HeaderBar::builder().title_widget(&host).build();
    let body = gtk::Box::new(gtk::Orientation::Vertical, 0);
    body.append(&status);
    view.set_vexpand(true);
    body.append(&view);
    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&header);
    toolbar.set_content(Some(&body));
    let window = adw::Window::builder()
        .transient_for(parent)
        .modal(true)
        .title("wave にログイン")
        .default_width(520)
        .default_height(760)
        .content(&toolbar)
        .build();

    let on_done = Rc::new(on_done);
    let finished = Rc::new(Cell::new(false));
    let finishing = Rc::new(Cell::new(false));
    {
        let host = host.clone();
        view.connect_uri_notify(move |view| host.set_subtitle(&view.uri().map(|u| host_of(&u)).unwrap_or_default()));
    }
    {
        let (window, status, finished, finishing, on_done) = (window.clone(), status.clone(), finished.clone(), finishing.clone(), on_done.clone());
        view.connect_load_changed(move |view, event| {
            if event != webkit6::LoadEvent::Finished || finishing.get() {
                return;
            }
            if view.uri().is_none_or(|u| host_of(&u) != LANDING_HOST) {
                return;
            }
            let (view, window, status, finished, finishing, on_done) =
                (view.clone(), window.clone(), status.clone(), finished.clone(), finishing.clone(), on_done.clone());
            finishing.set(true);
            gtk::glib::spawn_future_local(async move {
                match finish(&view).await {
                    Some(credentials) if keyring::save(&credentials).await => {
                        finished.set(true);
                        on_done(Some(credentials));
                        window.close();
                    }
                    result => {
                        status.set_label(if result.is_none() { "ログイン情報を取得できませんでした" } else { "ログイン情報を保存できませんでした" });
                        status.set_visible(true);
                        finishing.set(false);
                    }
                }
            });
        });
    }
    {
        let (finished, on_done) = (finished.clone(), on_done.clone());
        window.connect_close_request(move |_| {
            if !finished.get() {
                on_done(None);
            }
            gtk::glib::Propagation::Proceed
        });
    }
    view.load_uri(START_URL);
    window.present();
}

/// 着いたページから cookie と yt.config_ の値を読む（読めるまで少し待つ）。SAPISID 系の cookie が無ければ None。
async fn finish(view: &webkit6::WebView) -> Option<Credentials> {
    let cookie = cookie_of(view).await?;
    Credentials::from_login(&cookie, None, None, 0)?;
    let (mut visitor, mut data_sync) = (None, None);
    for attempt in 0..20 {
        if visitor.is_some() && data_sync.is_some() {
            break;
        }
        if attempt > 0 {
            gtk::glib::timeout_future(Duration::from_millis(500)).await;
        }
        visitor = visitor.or(read_config(view, "VISITOR_DATA").await);
        data_sync = data_sync.or(read_config(view, "DATASYNC_ID").await);
    }
    let cookie = cookie_of(view).await?;
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
    let credentials = Credentials::from_login(&cookie, visitor.as_deref(), data_sync.as_deref(), now);
    eprintln!("login landed visitor={} dataSync={} ok={}", visitor.is_some(), data_sync.is_some(), credentials.is_some());
    credentials
}

async fn cookie_of(view: &webkit6::WebView) -> Option<String> {
    let manager = view.network_session()?.cookie_manager()?;
    let cookies = manager.cookies_future(&format!("https://{LANDING_HOST}")).await.ok()?;
    let joined: Vec<String> = cookies
        .into_iter()
        .filter_map(|mut c| Some(format!("{}={}", c.name()?, c.value()?)))
        .collect();
    (!joined.is_empty()).then(|| joined.join("; "))
}

async fn read_config(view: &webkit6::WebView, key: &str) -> Option<String> {
    let script = format!("String(window.yt?.config_?.{key} ?? '')");
    let value = view.evaluate_javascript_future(&script, None, None).await.ok()?;
    let text = value.to_str().to_string();
    (!text.is_empty()).then_some(text)
}
