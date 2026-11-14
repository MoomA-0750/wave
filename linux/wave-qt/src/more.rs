//! WaveApp の続き: Claude の欄・端末の連携・ダウンロード・ポッドキャスト・プレイリストの作成と外す・映像（MV）・新作の通知。
//! 中身は GTK 版と同じ wave-desktop のものを呼び、画面に出す形（JSON）に直すだけ。

use std::cell::RefCell;
use std::pin::Pin;
use std::rc::Rc;
use std::time::Duration;

use cxx_qt_lib::QString;
use gst::prelude::*;
use serde_json::{Value, json};
use wave_core::connect::socket::{RelayEndpoint, RelayStatus};
use wave_core::download::manifest::{KIND_ALBUM, KIND_LIKED};
use wave_core::music::feed;
use wave_core::music::models::{MusicVideo, Song};
use wave_core::music::podcast::PodcastPage;
use wave_core::music::repository::NEW_EPISODES_BROWSE;
use wave_desktop::ai_chat::AiMessage;
use wave_desktop::connect::{ConnectInfo, MemberView, PairingView};
use wave_desktop::downloads::SongState;
use wave_desktop::{lang, runtime};
use wave_desktop::services::Services;

use crate::app::{core_thread, failure, next_token, parse_song, request_sync, services, toast};
use crate::bridge::qobject;
use crate::view;

fn bump(f: fn(Pin<&mut qobject::WaveApp>)) {
    if let Some(thread) = core_thread() {
        let _ = thread.queue(f);
    }
}

fn bump_ai(mut app: Pin<&mut qobject::WaveApp>) {
    let v = *app.ai_version() + 1;
    app.as_mut().set_ai_version(v);
}

fn bump_devices(mut app: Pin<&mut qobject::WaveApp>) {
    let v = *app.devices_version() + 1;
    app.as_mut().set_devices_version(v);
}

fn bump_downloads(mut app: Pin<&mut qobject::WaveApp>) {
    let v = *app.downloads_version() + 1;
    app.as_mut().set_downloads_version(v);
}

fn reply(token: i32, value: Value) {
    let text = value.to_string();
    if let Some(thread) = core_thread() {
        let _ = thread.queue(move |app| app.loaded(token, QString::from(&text)));
    }
}

thread_local! {
    /// ダウンロードの見張りを付けておく入れ物（Downloads::watch は glib の物が生きている間だけ呼ぶ）。
    static WATCH_HOLDER: RefCell<Option<glib::Object>> = const { RefCell::new(None) };
    static VIDEO: RefCell<Option<Video>> = const { RefCell::new(None) };
}

/// 主の画面に入ったときに、見張りを付ける（Claude・連携・ダウンロードの変化で QML に読み直させる。映像・新作の通知）。
pub fn attach(services: &Rc<Services>) {
    services.ai.on_change(|| bump(bump_ai));
    services.connect.on_change(|| bump(bump_devices));
    let holder = glib::Object::new::<glib::Object>();
    services.downloads.watch(&holder, |_| bump(bump_downloads));
    WATCH_HOLDER.with(|h| *h.borrow_mut() = Some(holder));
    // 連携の様子（中継のつながりなど）は知らせが来ないこともあるので、1秒おきにも読み直させる（GTK 版のページと同じ）
    glib::timeout_add_local(Duration::from_secs(1), || {
        if crate::app::services().is_none() {
            return glib::ControlFlow::Break;
        }
        bump(bump_devices);
        glib::ControlFlow::Continue
    });
    // 新作の通知は freedesktop の通知で出し、押したらそのアルバム（無ければアーティスト）を開く
    wave_desktop::releases::set_notifier(|release| {
        let (kind, id) = match &release.album_browse_id {
            Some(album) => ("album", album.clone()),
            None => ("artist", release.artist_id.clone()),
        };
        let title = release.title.clone();
        wave_desktop::desktop_notify::notify("xyz.mooma0750.wave", &if lang::ja() { format!("{} の新作", release.artist_name) } else { format!("New from {}", release.artist_name) }, &release.title, move || {
            let (kind, id, title) = (kind.to_string(), id.clone(), title.clone());
            if let Some(thread) = core_thread() {
                let _ = thread.queue(move |mut app| {
                    app.as_mut().raise_requested();
                    app.open_requested(QString::from(&kind), QString::from(&id), QString::from(&title));
                });
            }
        });
    });
    Video::start(services);
}

pub fn detach() {
    WATCH_HOLDER.with(|h| *h.borrow_mut() = None);
    VIDEO.with(|v| {
        if let Some(video) = v.borrow_mut().take() {
            video.player.stop();
        }
    });
    crate::bridge::qobject::wave_video_clear();
}

// MARK: 映像（MV）

/// 曲に重ねる MV。GStreamer の appsink で RGBA の絵を受け取り、QML の VideoFrame（C++）に渡して描く。
/// qml6glsink は GPU（OpenGL）の描き方にしか描けず、software の描き方・ほかの RHI では映らないので使わない。
struct Video {
    player: wave_desktop::video::VideoPlayer,
    /// 今の曲の MV と、それを探した曲。
    mv: Option<MusicVideo>,
    mv_for: Option<String>,
    show: bool,
    /// "idle"・"loading"・"failed"・"ready"
    load: &'static str,
    gap: bool,
}

impl Video {
    fn start(services: &Rc<Services>) {
        let Some(player) = make_player(services) else {
            eprintln!("video unavailable (appsink)");
            return;
        };
        VIDEO.with(|v| *v.borrow_mut() = Some(Video { player, mv: None, mv_for: None, show: false, load: "idle", gap: true }));
        let s = services.clone();
        services.playback.on_change(move || song_changed(&s));
        song_changed(services);
        glib::timeout_add_local(Duration::from_millis(300), || {
            let Some(s) = crate::app::services() else { return glib::ControlFlow::Break };
            let changed = VIDEO.with(|v| {
                let mut v = v.borrow_mut();
                let Some(video) = v.as_mut() else { return false };
                if !video.show || video.load != "ready" {
                    return false;
                }
                let Some(mv) = video.mv.clone() else { return false };
                let player = s.playback.player();
                let showing = video.player.sync(&mv, player.position_ms().unwrap_or(0), player.is_playing());
                let changed = video.gap == showing;
                video.gap = !showing;
                changed
            });
            if changed {
                publish();
            }
            glib::ControlFlow::Continue
        });
    }
}

/// appsink の絵を1枚、QML の VideoFrame に渡す。
fn show_sample(sample: gst::Sample) -> Result<gst::FlowSuccess, gst::FlowError> {
    let (Some(buffer), Some(caps)) = (sample.buffer(), sample.caps()) else { return Ok(gst::FlowSuccess::Ok) };
    let Ok(info) = gst_video::VideoInfo::from_caps(caps) else { return Ok(gst::FlowSuccess::Ok) };
    if let Ok(map) = buffer.map_readable() {
        let stride = info.stride()[0];
        // 最初の1枚だけ大きさを記録に残す（映らないときに、絵が届いているかを分ける）
        if !FIRST_FRAME_SEEN.swap(true, std::sync::atomic::Ordering::Relaxed) {
            eprintln!("video first frame {}x{}", info.width(), info.height());
        }
        crate::bridge::qobject::wave_video_frame(map.as_slice(), info.width() as i32, info.height() as i32, stride);
    }
    Ok(gst::FlowSuccess::Ok)
}

/// 映像の最初の1枚を記録したか（MV を読み直すたびに戻す）。
static FIRST_FRAME_SEEN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn make_player(services: &Rc<Services>) -> Option<wave_desktop::video::VideoPlayer> {
    gst::init().ok()?;
    let caps = gst::Caps::builder("video/x-raw").field("format", "RGBA").build();
    let sink = gst_app::AppSink::builder().caps(&caps).max_buffers(2).drop(true).sync(true).build();
    // 鳴っている間の絵（new_sample）と、止まっている間・シークした直後の1枚（new_preroll）を同じように描く
    sink.set_callbacks(
        gst_app::AppSinkCallbacks::builder()
            .new_sample(|sink| show_sample(sink.pull_sample().map_err(|_| gst::FlowError::Eos)?))
            .new_preroll(|sink| show_sample(sink.pull_preroll().map_err(|_| gst::FlowError::Eos)?))
            .build(),
    );
    wave_desktop::video::VideoPlayer::with_sink(services.resolver.clone(), sink.upcast_ref())
}

/// 曲が変わったら、その曲の MV を探す（「動画」は曲が変わったら「曲」に戻す。GTK 版と同じ）。
fn song_changed(s: &Rc<Services>) {
    let id = s.playback.current().map(|song| song.video_id);
    let same = VIDEO.with(|v| v.borrow().as_ref().is_some_and(|video| video.mv_for == id));
    if same {
        return;
    }
    VIDEO.with(|v| {
        if let Some(video) = v.borrow_mut().as_mut() {
            video.mv_for = id.clone();
            video.mv = None;
            video.show = false;
            video.load = "idle";
            video.gap = true;
            video.player.stop();
        }
    });
    crate::bridge::qobject::wave_video_clear();
    publish();
    let Some(id) = id else { return };
    let repo = s.repo.clone();
    glib::spawn_future_local(async move {
        let wanted = id.clone();
        let found = runtime::run(async move { repo.music_video(&wanted).await }).await.ok().flatten();
        let demo = VIDEO.with(|v| {
            let mut v = v.borrow_mut();
            let Some(video) = v.as_mut() else { return false };
            if video.mv_for.as_deref() != Some(id.as_str()) {
                return false;
            }
            let has = found.is_some();
            eprintln!("video mv found={has}");
            video.mv = found;
            // 確かめ用: WAVE_DEMO_VIDEO があれば動画にする
            has && std::env::var_os("WAVE_DEMO_VIDEO").is_some()
        });
        publish();
        if demo {
            set_video(true);
        }
    });
}

fn set_video(on: bool) {
    let load = VIDEO.with(|v| {
        let mut v = v.borrow_mut();
        let video = v.as_mut()?;
        video.show = on;
        if !on {
            video.player.stop();
            video.load = "idle";
            return None;
        }
        let mv = video.mv.clone()?;
        video.load = "loading";
        Some(mv)
    });
    if !on {
        crate::bridge::qobject::wave_video_clear();
    }
    publish();
    let Some(mv) = load else { return };
    glib::spawn_future_local(async move {
        // VideoPlayer::load は await の間も借りるので、借りずに済むよう一度取り出す
        let Some(video) = VIDEO.with(|v| v.borrow_mut().take()) else { return };
        FIRST_FRAME_SEEN.store(false, std::sync::atomic::Ordering::Relaxed);
        let ok = video.player.load(&mv).await;
        eprintln!("video load ok={ok}");
        VIDEO.with(|v| {
            let mut slot = v.borrow_mut();
            let mut video = video;
            if video.mv.as_ref().map(|m| &m.video_id) == Some(&mv.video_id) && video.show {
                video.load = if ok { "ready" } else { "failed" };
            }
            *slot = Some(video);
        });
        publish();
    });
}

fn publish() {
    let (has, show, state) = VIDEO.with(|v| {
        let v = v.borrow();
        let Some(video) = v.as_ref() else { return (false, false, "off") };
        let has = video.mv.is_some();
        let state = if !(video.show && has) {
            "off"
        } else {
            match video.load {
                "failed" => "failed",
                "ready" if video.gap => "gap",
                "ready" => "showing",
                _ => "loading",
            }
        };
        (has, video.show && has, state)
    });
    if let Some(thread) = core_thread() {
        let _ = thread.queue(move |mut app| {
            app.as_mut().set_has_video(has);
            app.as_mut().set_show_video(show);
            app.set_video_state(QString::from(state));
        });
    }
}

// MARK: Claude

fn assistant_html(text: &str) -> String {
    let escape = |t: &str| t.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('\n', "<br>");
    let mut out = String::new();
    let mut at = 0;
    for stamp in wave_core::ai::text::timestamps(text) {
        out.push_str(&escape(&text[at..stamp.start]));
        out.push_str(&format!("<a href=\"{}\">{}</a>", stamp.position_ms, escape(&text[stamp.start..stamp.end])));
        at = stamp.end;
    }
    out.push_str(&escape(&text[at..]));
    out
}

// MARK: 端末の連携

fn banner(info: &ConnectInfo) -> Value {
    if !info.configured {
        let message = if info.unreadable { lang::t("この PC の連携の鍵を読めませんでした（キーリングを確かめてください）", "Couldn't read this PC's device keys (check the keyring)") } else { lang::t("ページの下の「中継の場所」に入れると使えます", "Enter it under “Relay address” at the bottom of this page to use this") };
        return json!({ "severity": "info", "title": lang::t("中継の場所が設定されていません", "The relay isn't configured"), "message": message, "action": "", "actionLabel": "" });
    }
    if info.unreadable {
        return json!({ "severity": "error", "title": lang::t("連携の保存を読めません", "Couldn't read the saved device group"), "message": lang::t("リセットすると、この PC はもう一度ほかの端末から加わり直します", "Reset it to join your devices again from another device"), "action": "forget_household", "actionLabel": lang::t("リセット", "Reset") });
    }
    if info.in_household && info.relay == Some(RelayStatus::NotMember) {
        return json!({ "severity": "error", "title": lang::t("中継がこの PC を連携した端末として受け付けません", "The relay no longer accepts this PC"), "message": lang::t("ほかの端末から外された可能性があります", "It may have been removed by another device"), "action": "forget_household", "actionLabel": lang::t("この PC の連携の記録を消す", "Forget the group on this PC") });
    }
    if info.store_reset_pending {
        return json!({ "severity": "warning", "title": lang::t("中継の状態が作り直されました", "The relay's state was reset"), "message": "", "action": "accept_store_reset", "actionLabel": lang::t("この状態から始める", "Start from this state") });
    }
    Value::Null
}

fn relay_text(info: &ConnectInfo) -> &'static str {
    if !info.configured {
        return lang::t("設定されていません", "Not configured");
    }
    match info.relay {
        Some(RelayStatus::Connected) => lang::t("接続中", "Connected"),
        Some(RelayStatus::Connecting) => lang::t("接続しています…", "Connecting…"),
        Some(RelayStatus::TailnetUnreachable) => lang::t("届きません（Tailscale につながっているか確かめてください）", "Unreachable (check that Tailscale is connected)"),
        Some(RelayStatus::NotMember) if info.in_household => lang::t("受け付けられません", "Not accepted"),
        Some(RelayStatus::NotMember) => lang::t("まだ連携していません", "Not set up yet"),
        Some(RelayStatus::Failed) => lang::t("つながりませんでした", "Couldn't connect"),
        Some(RelayStatus::Disconnected) | None => lang::t("切断", "Disconnected"),
    }
}

fn pairing_json(view: &PairingView) -> Value {
    let (kind, text) = match view {
        PairingView::Idle => ("idle", String::new()),
        PairingView::Working(m) => ("working", m.clone()),
        PairingView::Failed(m) => ("failed", m.clone()),
        PairingView::ShowingOffer(_) => ("offer", lang::t("加える端末で wave の「端末の連携」を開き、この QR を読み取ってください（PC なら下の文字列をコピーして貼り付けます）。2分で無効になります。", "On the device you're adding, open Devices in wave and scan this QR code (on a PC, copy the text below and paste it). Expires in 2 minutes.").into()),
        PairingView::EnterCode(m) => ("code", format!("{}{}", m.as_ref().map(|m| format!("{m}\n")).unwrap_or_default(), lang::t("加える端末に出ている6桁の数字を入力してください", "Enter the 6-digit code shown on the device you're adding"))),
        PairingView::ShowCode { peer, .. } => ("show", if lang::ja() { format!("「{peer}」に、この6桁の数字を入力してください") } else { format!("Enter this 6-digit code on “{peer}”") }),
        PairingView::Done { peer, grant_device_id: Some(_) } => ("done", if lang::ja() { format!("「{peer}」を加えました。プレイリストなどを使えるようにするには、ログインを渡してください。") } else { format!("Added “{peer}”. Give it your login so it can use your playlists and more.") }),
        PairingView::Done { peer, grant_device_id: None } => ("done", if lang::ja() { format!("「{peer}」と連携しました") } else { format!("Connected with “{peer}”") }),
    };
    let mut value = json!({ "kind": kind, "text": text, "offer": "", "code": "", "qr": [], "grantId": "" });
    match view {
        PairingView::ShowingOffer(offer) => {
            value["offer"] = json!(offer);
            value["qr"] = json!(qr_rows(offer));
        }
        PairingView::ShowCode { code, .. } => value["code"] = json!(code),
        PairingView::Done { grant_device_id: Some(id), .. } => value["grantId"] = json!(id),
        _ => {}
    }
    value
}

/// QR を行ごとの「1」（黒）と「0」（白）の文字列にする（QML の Canvas で描く）。
fn qr_rows(text: &str) -> Vec<String> {
    let Ok(code) = qrcode::QrCode::new(text.as_bytes()) else { return Vec::new() };
    let width = code.width();
    code.to_colors().chunks(width).map(|row| row.iter().map(|c| if *c == qrcode::Color::Dark { '1' } else { '0' }).collect()).collect()
}

fn member_json(m: &MemberView, logged_in: bool) -> Value {
    let status = if m.active {
        lang::t("再生中の端末", "Active device")
    } else if m.online {
        lang::t("オンライン", "Online")
    } else {
        lang::t("オフライン", "Offline")
    };
    let icon = match m.kind.as_deref() {
        Some("api") => "network-server-symbolic",
        Some("pc") => "computer-symbolic",
        _ if m.is_self => "computer-symbolic",
        _ => "phone-symbolic",
    };
    json!({
        "id": m.device_id,
        "label": if m.is_controller { format!("{}{}", m.label, lang::t("（操作だけ）", " (remote only)")) } else { m.label.clone() },
        "name": m.label,
        "status": status,
        "icon": icon,
        "self": m.is_self,
        "online": m.online,
        "kind": m.kind.clone().unwrap_or_default(),
        "canGrant": m.kind.as_deref() == Some("api") && logged_in,
    })
}

// MARK: ダウンロード

fn state_code(s: &Services, video_id: &str) -> i32 {
    match s.downloads.state(video_id) {
        SongState::None => 0,
        SongState::Waiting => 1,
        SongState::Downloading => 2,
        SongState::Done => 3,
    }
}

// MARK: ポッドキャスト

pub async fn load_podcast(browse_id: String, title: String, continuation: Option<String>) -> Value {
    let Some(s) = services() else { return failure("not started") };
    let new_episodes = browse_id == NEW_EPISODES_BROWSE;
    // 続き（RSS は読んだ数から、YouTube はトークンで）
    if let Some(token) = continuation {
        return match feed::feed_url_of(&browse_id) {
            Some(url) => {
                let from: usize = token.parse().unwrap_or(0);
                match s.podcasts.show(url, false).await {
                    Some(show) => {
                        let library = s.podcasts.library.clone();
                        let page = show.to_page(None, from + feed::PAGE_SIZE, |u| library.position(u));
                        let more = show.item_count() > from + feed::PAGE_SIZE;
                        json!({ "episodes": page.episodes.iter().skip(from).map(view::episode).collect::<Vec<_>>(), "continuation": if more { (from + feed::PAGE_SIZE).to_string() } else { String::new() } })
                    }
                    None => failure("feed"),
                }
            }
            None => {
                let summary = wave_core::music::models::ArtistRef { name: title.clone(), browse_id: Some(browse_id.clone()) };
                let (repo, browse) = (s.repo.clone(), browse_id.clone());
                match runtime::run(async move { repo.podcast_episodes(&token, &summary, Some(&browse)).await }).await {
                    Ok(page) => json!({ "episodes": page.items.iter().map(view::episode).collect::<Vec<_>>(), "continuation": page.continuation.unwrap_or_default() }),
                    Err(e) => failure(e),
                }
            }
        };
    }
    let loaded: Option<PodcastPage> = match feed::feed_url_of(&browse_id) {
        Some(url) => s.podcasts.show(url, false).await.map(|show| {
            let library = s.podcasts.library.clone();
            show.to_page(Some(library.is_subscribed(url)), feed::PAGE_SIZE, |u| library.position(u))
        }),
        None => {
            let (repo, browse) = (s.repo.clone(), browse_id.clone());
            match runtime::run(async move { repo.podcast(&browse).await }).await {
                Ok(page) => Some(page),
                // 保存した番組が無いアカウントの「新しいエピソード」は番組のページの形で返らない。空として出す（GTK 版・Mac と同じ）
                Err(_) if new_episodes => Some(PodcastPage { browse_id: browse_id.clone(), title: title.clone(), author: None, description: None, thumbnail_url: None, episodes: Vec::new(), continuation: None, saved: None }),
                Err(_) => None,
            }
        }
    };
    let Some(mut page) = loaded else { return failure("podcast") };
    // 「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012）
    if new_episodes {
        let feeds = s.podcasts.subscribed_shows().await;
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64);
        let library = s.podcasts.library.clone();
        let refs: Vec<&feed::FeedShow> = feeds.iter().map(|f| f.as_ref()).collect();
        page.episodes = wave_core::music::new_episodes::merge_refs(&page.episodes, &refs, now, |u| library.position(u));
        page.title = lang::t("新しいエピソード", "New episodes").into();
    }
    let is_feed = feed::is_feed_show(&page.browse_id);
    let continuation = match feed::feed_url_of(&page.browse_id) {
        Some(url) => match s.podcasts.show(url, false).await {
            Some(show) if show.item_count() > feed::PAGE_SIZE => feed::PAGE_SIZE.to_string(),
            _ => String::new(),
        },
        None => page.continuation.clone().unwrap_or_default(),
    };
    let summary = page.summary();
    json!({
        "kind": "podcast",
        "id": page.browse_id,
        "title": page.title,
        "author": page.author.clone().unwrap_or_default(),
        "description": page.description.clone().unwrap_or_default(),
        "art": view::image(page.thumbnail_url.as_deref(), view::LARGE),
        "episodes": page.episodes.iter().map(view::episode).collect::<Vec<_>>(),
        "continuation": continuation,
        // 保存（YouTube はログイン中だけ）・登録（RSS）のボタン
        "saved": page.saved,
        "canSave": !new_episodes && (is_feed || (s.repo.client().is_logged_in() && page.saved.is_some())),
        "isFeed": is_feed,
        "newEpisodes": new_episodes,
        "show": { "browseId": summary.browse_id, "title": summary.title, "author": summary.author, "thumbnailUrl": summary.thumbnail_url, "playlistId": page.playlist_id() },
    })
}

pub async fn search_feeds(text: String) -> Value {
    let Some(s) = services() else { return failure("not started") };
    // URL ならその番組を開く
    if let Some(url) = feed::normalize_feed_url(&text) {
        return json!({ "open": feed::show_browse_id(&url) });
    }
    let client = s.podcasts.client.clone();
    match runtime::run(async move { client.search(&text).await }).await {
        Some(shows) => json!({ "items": shows.iter().map(view::podcast).collect::<Vec<_>>() }),
        None => failure(lang::t("検索できませんでした", "Couldn't search")),
    }
}

impl qobject::WaveApp {
    // MARK: Claude

    pub fn ai_json(&self) -> QString {
        let Some(s) = services() else { return QString::from("{}") };
        let messages: Vec<Value> = s
            .ai
            .messages
            .borrow()
            .iter()
            .map(|m| match m {
                AiMessage::User(t) => json!({ "kind": "user", "text": t }),
                AiMessage::Assistant(t, video) => json!({ "kind": "assistant", "text": t, "html": assistant_html(t), "videoId": video.clone().unwrap_or_default() }),
                AiMessage::Action(t) => json!({ "kind": "action", "text": format!("{}{t}", lang::t("・", "• ")) }),
                AiMessage::Songs(songs) => json!({ "kind": "songs", "songs": view::songs(songs) }),
                AiMessage::Failure(t) => json!({ "kind": "failure", "text": t }),
            })
            .collect();
        let open = s.ai.conversation_id();
        let history: Vec<Value> = s.ai.history.borrow().iter().map(|c| json!({ "id": c.id, "title": c.title, "current": c.id == open })).collect();
        QString::from(&json!({ "messages": messages, "history": history, "running": s.ai.running.get(), "configured": s.ai.configured().unwrap_or_default() }).to_string())
    }

    pub fn ai_send(self: Pin<&mut Self>, text: &QString) {
        if let Some(s) = services() {
            s.ai.send(&s, &text.to_string());
        }
    }

    pub fn ai_stop(self: Pin<&mut Self>) {
        if let Some(s) = services() {
            s.ai.stop();
        }
    }

    pub fn ai_clear(self: Pin<&mut Self>) {
        if let Some(s) = services() {
            s.ai.clear();
        }
    }

    pub fn ai_open(self: Pin<&mut Self>, id: &QString) {
        if let Some(s) = services() {
            s.ai.open(&id.to_string());
        }
    }

    pub fn ai_delete(self: Pin<&mut Self>, id: &QString) {
        if let Some(s) = services() {
            s.ai.delete(&id.to_string());
        }
    }

    pub fn ai_pair(self: Pin<&mut Self>, text: &QString) -> i32 {
        let token = next_token();
        let text = text.to_string();
        glib::spawn_future_local(async move {
            let ok = match services() {
                Some(s) => s.ai.pair(&text).await,
                None => false,
            };
            reply(token, json!({ "ok": ok }));
        });
        token
    }

    pub fn ai_unpair(self: Pin<&mut Self>) {
        glib::spawn_future_local(async move {
            if let Some(s) = services() {
                s.ai.unpair().await;
            }
        });
    }

    pub fn ai_seek(self: Pin<&mut Self>, video_id: &QString, position_ms: i32) {
        let Some(s) = services() else { return };
        let video_id = video_id.to_string();
        if !video_id.is_empty() && s.playback.current().map(|song| song.video_id) == Some(video_id) {
            s.playback.player().seek_ms(position_ms.max(0) as u64);
        } else {
            toast(lang::t("話している曲が鳴っていないので飛べません", "That song isn't playing, so wave can't jump there"));
        }
    }

    // MARK: 端末の連携

    pub fn devices_json(&self) -> QString {
        let Some(s) = services() else { return QString::from("{}") };
        let info = s.connect.info();
        let view = s.connect.pairing();
        let logged_in = s.repo.client().is_logged_in();
        let members = s.connect.members();
        let mirror = s.connect.mirror().filter(|_| info.in_household && info.relay != Some(RelayStatus::NotMember) && !info.is_writer);
        let usable = info.configured && !info.unreadable;
        let mut pairing = pairing_json(&view);
        // ログインを渡す相手（加えたのがログインをまだ持たない wave API のとき）
        if let PairingView::Done { grant_device_id: Some(id), .. } = &view
            && let Some(member) = members.iter().find(|m| &m.device_id == id).filter(|_| logged_in)
        {
            pairing["grantLabel"] = json!(member.label);
        } else {
            pairing["grantId"] = json!("");
        }
        // 下の帯の端末の小窓: 自分を先に、その後はオンラインのものを先に（API の端末は出さない）
        let mut pickable: Vec<&MemberView> = members.iter().filter(|m| m.kind.as_deref() != Some("api") && !m.is_controller).collect();
        pickable.sort_by_key(|m| (!m.is_self, !(m.online || m.is_self)));
        let relay_url = s.downloads.settings.borrow().relay_url.clone().unwrap_or_default();
        let value = json!({
            "configured": info.configured,
            "usable": usable,
            "inHousehold": info.in_household,
            "banner": banner(&info),
            "showJoin": usable && !info.in_household && matches!(view, PairingView::Idle | PairingView::Failed(_)),
            "pairing": pairing,
            "members": if info.in_household { members.iter().map(|m| member_json(m, logged_in)).collect::<Vec<_>>() } else { Vec::new() },
            "pickable": pickable.iter().filter(|m| mirror.as_ref().is_none_or(|r| r.device_id != m.device_id) || m.is_self).map(|m| member_json(m, logged_in)).collect::<Vec<_>>(),
            "mirror": mirror.as_ref().map(|m| json!({
                "label": m.label,
                "status": if !lang::ja() { format!("{} on {}", if m.playing { "Playing" } else { "Paused" }, m.label) } else if m.playing { format!("{}で再生中", m.label) } else { format!("{}で一時停止中", m.label) },
                "song": view::song(&m.song),
            })),
            "relay": format!("{}{}", lang::t("中継: ", "Relay: "), relay_text(&info)),
            "relayUrl": relay_url,
            "loggedIn": logged_in,
        });
        QString::from(&value.to_string())
    }

    pub fn connect_action(self: Pin<&mut Self>, name: &QString, arg: &QString) {
        let Some(s) = services() else { return };
        let arg = arg.to_string();
        match name.to_string().as_str() {
            "start_offer" => s.connect.start_offer(),
            "accept" => s.connect.accept(arg.trim()),
            "create_household" => s.connect.create_household(),
            "confirm_on_b" => s.connect.confirm_on_b(),
            "cancel_pairing" => s.connect.cancel_pairing(),
            "forget_household" => s.connect.forget_household(),
            "accept_store_reset" => s.connect.accept_store_reset(),
            "transfer_here" => s.connect.transfer_here(),
            "play_on_device" => s.connect.play_on_device(&arg),
            "set_relay" => {
                let text = arg.trim().to_string();
                if !text.is_empty() && RelayEndpoint::parse(&text).is_none() {
                    toast(lang::t("wss:// で始まる、パスの無い場所を入れてください", "Enter an address that starts with wss:// and has no path"));
                    return;
                }
                s.downloads.update_settings(|st| st.relay_url = (!text.is_empty()).then_some(text));
                toast(lang::t("中継の場所を保存しました。次に wave を開いたときから使います", "Relay address saved. It takes effect the next time you open wave"));
            }
            other => eprintln!("connect action unknown={other}"),
        }
        bump(bump_devices);
    }

    pub fn connect_enter_code(self: Pin<&mut Self>, code: &QString) -> bool {
        services().is_some_and(|s| s.connect.enter_code(code.to_string().trim()))
    }

    pub fn connect_remove(self: Pin<&mut Self>, device_id: &QString, label: &QString) {
        let Some(s) = services() else { return };
        let (id, label) = (device_id.to_string(), label.to_string());
        glib::spawn_future_local(async move {
            let removed = s.connect.remove_member(&id).await;
            toast(&if removed { if lang::ja() { format!("「{label}」を外しました") } else { format!("Removed “{label}”") } } else { lang::t("外せませんでした", "Couldn't remove the device").into() });
        });
    }

    pub fn connect_grant(self: Pin<&mut Self>, device_id: &QString, label: &QString) {
        let Some(s) = services() else { return };
        let (id, label) = (device_id.to_string(), label.to_string());
        glib::spawn_future_local(async move {
            let sent = s.connect.grant_login(&id).await;
            toast(&if sent { if lang::ja() { format!("「{label}」にログインを渡しました") } else { format!("Gave your login to “{label}”") } } else { lang::t("ログインを渡せませんでした", "Couldn't give your login").into() });
        });
    }

    // MARK: ダウンロード

    pub fn downloads_json(&self, category: &QString) -> QString {
        let Some(s) = services() else { return QString::from("{}") };
        let downloads = &s.downloads;
        let manifest = downloads.manifest();
        let settings = downloads.settings.borrow().clone();
        let wanted = manifest.wanted_songs();
        let done = |id: &str| downloads.state(id) == SongState::Done;
        let pending: Vec<&Song> = wanted.iter().filter(|song| !done(&song.video_id)).collect();
        let mut status = format!("{}{}", lang::t("使っている容量: ", "Storage used: "), wave_desktop::downloads::format_bytes(downloads.total_bytes()));
        let mut progress = -1.0;
        if !pending.is_empty() {
            status.push_str(&if lang::ja() { format!(" • 残り {} 曲 • ", pending.len()) } else { format!(" • {} left • ", lang::songs(pending.len())) });
            let active = downloads.progress.lock().unwrap().clone();
            if !downloads.network_allows() {
                status.push_str(if downloads.online() { lang::t("Wi-Fi など従量制でない通信を待っています", "Waiting for Wi-Fi or another unmetered network") } else { lang::t("通信を待っています", "Waiting for a network") });
            } else if let Some((id, got, total)) = active {
                let title = wanted.iter().find(|song| song.video_id == id).map(|song| song.title.clone()).unwrap_or_default();
                let percent = if total > 0 { got * 100 / total } else { 0 };
                status.push_str(&if lang::ja() { format!("取っています: {title}（{percent}%）") } else { format!("Downloading: {title} ({percent}%)") });
                progress = if total > 0 { got as f64 / total as f64 } else { 0.0 };
            } else {
                status.push_str(lang::t("待っています", "Waiting"));
            }
        }
        let category = category.to_string();
        let smart: Vec<Value> = manifest
            .smart
            .iter()
            .filter(|t| category.is_empty() || format!("{:?}", t.category) == category)
            .map(|t| {
                let mut v = view::song(&t.song);
                v["state"] = json!(state_code(&s, &t.song.video_id));
                v
            })
            .collect();
        let collections: Vec<Value> = manifest
            .collections
            .iter()
            .map(|c| {
                let kind = if c.kind == KIND_ALBUM { lang::t("アルバム", "Album") } else if c.kind == KIND_LIKED { lang::t("プレイリスト • 自動", "Playlist • Auto") } else { lang::t("プレイリスト", "Playlist") };
                let got = c.songs.iter().filter(|song| done(&song.video_id)).count();
                let count = if got >= c.songs.len() { lang::songs(c.songs.len()) } else { format!("{got} / {}", lang::songs(c.songs.len())) };
                json!({ "kind": if c.kind == KIND_ALBUM { "album" } else { "playlist" }, "id": c.id, "title": c.title, "subtitle": format!("{kind} • {count}"), "thumb": view::image(c.thumbnail_url.as_deref(), 120), "round": false })
            })
            .collect();
        let rows = |external: bool| -> Vec<Value> {
            manifest
                .singles
                .iter()
                .filter(|single| feed::is_external(&single.song.video_id) == external)
                .map(|single| {
                    let mut v = view::song(&single.song);
                    v["state"] = json!(state_code(&s, &single.song.video_id));
                    v
                })
                .collect()
        };
        let value = json!({
            "status": status,
            "progress": progress,
            "empty": downloads.is_empty(),
            "smartOn": settings.smart_downloads,
            "smartCount": settings.smart_download_count,
            "counts": wave_core::download::smart::COUNTS,
            "smart": smart,
            "collections": collections,
            "songs": rows(false),
            "episodes": rows(true),
        });
        QString::from(&value.to_string())
    }

    pub fn download_state(&self, video_id: &QString) -> i32 {
        services().map_or(0, |s| state_code(&s, &video_id.to_string()))
    }

    pub fn download_song(self: Pin<&mut Self>, song_json: &QString) {
        if let (Some(s), Some(song)) = (services(), parse_song(song_json)) {
            s.downloads.download_song(&song);
            toast(lang::t("ダウンロードに追加しました", "Added to downloads"));
        }
    }

    pub fn remove_download(self: Pin<&mut Self>, video_id: &QString) {
        if let Some(s) = services() {
            s.downloads.remove_song(&video_id.to_string());
        }
    }

    pub fn download_collection(self: Pin<&mut Self>, kind: &QString, id: &QString, title: &QString, thumbnail: &QString, songs_json: &QString) {
        let Some(s) = services() else { return };
        let list: Vec<Value> = serde_json::from_str(&songs_json.to_string()).unwrap_or_default();
        let songs: Vec<Song> = list.into_iter().filter_map(|v| serde_json::from_value(v.get("song").cloned().unwrap_or(v)).ok()).collect();
        let thumbnail = thumbnail.to_string();
        s.downloads.download_collection(&kind.to_string(), &id.to_string(), &title.to_string(), (!thumbnail.is_empty()).then_some(thumbnail.as_str()), songs);
        toast(lang::t("ダウンロードに追加しました", "Added to downloads"));
    }

    pub fn remove_collection(self: Pin<&mut Self>, id: &QString) {
        if let Some(s) = services() {
            s.downloads.remove_collection(&id.to_string());
        }
    }

    pub fn clear_downloads(self: Pin<&mut Self>) {
        if let Some(s) = services() {
            s.downloads.clear();
            toast(lang::t("ダウンロードをすべて削除しました", "All downloads deleted"));
        }
    }

    // MARK: ポッドキャスト・プレイリスト

    pub fn set_podcast_saved(self: Pin<&mut Self>, show_json: &QString, saved: bool) -> i32 {
        let token = next_token();
        let Some(s) = services() else { return token };
        let show: Value = serde_json::from_str(&show_json.to_string()).unwrap_or_default();
        let browse_id = show["browseId"].as_str().unwrap_or_default().to_string();
        match feed::feed_url_of(&browse_id) {
            // RSS の番組はこの端末の登録
            Some(url) => {
                if saved {
                    let mut subscription = feed::FeedSubscription::new(url, show["title"].as_str().unwrap_or_default());
                    subscription.author = show["author"].as_str().map(String::from);
                    subscription.thumbnail_url = show["thumbnailUrl"].as_str().map(String::from);
                    s.podcasts.library.subscribe(subscription);
                    toast(lang::t("番組を登録しました", "Subscribed to the show"));
                } else {
                    s.podcasts.library.unsubscribe(url);
                    toast(lang::t("登録を解除しました", "Unsubscribed"));
                }
                bump(|mut app| {
                    let v = *app.library_version() + 1;
                    app.as_mut().set_library_version(v);
                });
                reply(token, json!({ "ok": true }));
            }
            // YouTube の番組は書けてから表示を変える（失敗したら戻す）
            None => {
                let playlist_id = show["playlistId"].as_str().unwrap_or_default().to_string();
                glib::spawn_future_local(async move {
                    let repo = s.repo.clone();
                    let ok = runtime::run(async move { repo.set_podcast_saved(&playlist_id, saved).await }).await.is_ok();
                    if ok {
                        crate::app::load_library(&s);
                    } else {
                        toast(lang::t("ライブラリを変更できませんでした", "Couldn't change your library"));
                    }
                    reply(token, json!({ "ok": ok }));
                });
            }
        }
        token
    }

    pub fn set_album_saved(self: Pin<&mut Self>, playlist_id: &QString, saved: bool) -> i32 {
        let token = next_token();
        let Some(s) = services() else { return token };
        let playlist_id = playlist_id.to_string();
        glib::spawn_future_local(async move {
            let repo = s.repo.clone();
            let ok = runtime::run(async move { repo.set_playlist_saved(&playlist_id, saved).await }).await.is_ok();
            if ok {
                crate::app::load_library(&s);
            } else {
                toast(lang::t("ライブラリを変更できませんでした", "Couldn't change your library"));
            }
            reply(token, json!({ "ok": ok }));
        });
        token
    }

    pub fn create_playlist(self: Pin<&mut Self>, title: &QString, song_json: &QString) -> i32 {
        let token = next_token();
        let Some(s) = services() else { return token };
        let title = title.to_string().trim().to_string();
        let song = parse_song(song_json);
        glib::spawn_future_local(async move {
            if title.is_empty() {
                reply(token, json!({ "ok": false }));
                return;
            }
            let (repo, t) = (s.repo.clone(), title.clone());
            match runtime::run(async move { repo.create_playlist(&t).await }).await {
                Ok(id) => {
                    // 作ったばかりの空のプレイリストに入れる（重複を確かめる読み込みは要らない）
                    if let Some(song) = song {
                        let (repo, pid, vid) = (s.repo.clone(), id.clone(), song.video_id.clone());
                        let result = runtime::run(async move { repo.add_to_playlist(&pid, &vid, true).await }).await;
                        toast(if result == wave_core::music::models::AddToPlaylistResult::Added { lang::t("プレイリストを作成して追加しました", "Created the playlist and added the song") } else { lang::t("プレイリストを作成しました（曲は追加できませんでした）", "Created the playlist (couldn't add the song)") });
                    } else {
                        toast(&if lang::ja() { format!("「{title}」を作成しました") } else { format!("Created “{title}”") });
                    }
                    crate::app::load_library_after_create(&s);
                    reply(token, json!({ "ok": true, "id": id }));
                }
                Err(e) => {
                    eprintln!("create playlist failed: {e}");
                    toast(lang::t("プレイリストを作成できませんでした", "Couldn't create the playlist"));
                    reply(token, json!({ "ok": false }));
                }
            }
        });
        token
    }

    pub fn remove_from_playlist(self: Pin<&mut Self>, playlist_id: &QString, video_id: &QString, set_video_id: &QString) -> i32 {
        let token = next_token();
        let Some(s) = services() else { return token };
        let (pid, vid, sid) = (playlist_id.to_string(), video_id.to_string(), set_video_id.to_string());
        glib::spawn_future_local(async move {
            let repo = s.repo.clone();
            let ok = runtime::run(async move { repo.remove_from_playlist(&pid, &vid, &sid).await }).await;
            toast(if ok { lang::t("プレイリストから削除しました", "Removed from playlist") } else { lang::t("削除できませんでした", "Couldn't remove it") });
            reply(token, json!({ "ok": ok }));
        });
        token
    }

    pub fn set_video_on(self: Pin<&mut Self>, on: bool) {
        set_video(on);
        request_sync();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timestamps_become_links_and_text_is_escaped() {
        assert_eq!(assistant_html("サビは [01:23] <b>\n次"), "サビは <a href=\"83000\">[01:23]</a> &lt;b&gt;<br>次");
    }

    #[test]
    fn qr_is_square() {
        let rows = qr_rows("wave-pair:abc");
        assert!(!rows.is_empty());
        assert!(rows.iter().all(|r| r.len() == rows.len()));
    }
}

