//! ログインした後に動かすもの一式（再生・ダウンロード・連携・新作・番組・Claude）。GTK 版と QML 版が同じものを使う。
//! 画面を持たないので、知らせ（トースト）や窓の出し入れは画面の側が付ける。

use std::cell::RefCell;
use std::rc::Rc;

use wave_core::music::models::PlaylistSummary;
use wave_core::music::repository::MusicRepository;
use wave_core::stream::player::PlayerCipherProvider;
use wave_core::stream::resolver::StreamResolver;

use crate::{ai_chat, connect, downloads, keyring, playback, player, podcasts, queue_store, releases};

pub struct Services {
    pub repo: MusicRepository,
    pub lyrics: wave_core::lyrics::service::LyricsService,
    pub playback: Rc<playback::Playback>,
    /// 曲の解読（プレイヤーの JS）。音と映像で1つを共有する（映像の流し手は画面の側が作る）。
    pub resolver: StreamResolver,
    /// ダウンロード（取った曲・丸ごと取ったもの）。設定もここが持つ。
    pub downloads: Rc<downloads::Downloads>,
    /// 端末の連携。
    pub connect: Rc<connect::Connect>,
    /// 登録したアーティストの新作。
    pub releases: Rc<releases::Releases>,
    /// RSS の番組（登録と聴いたところ）。
    pub podcasts: Rc<podcasts::Podcasts>,
    /// Claude との会話。
    pub ai: Rc<ai_chat::AiChat>,
    /// 自分で編集できるプレイリスト（曲のメニューの「プレイリストに追加」に並べる）。
    pub editable_playlists: RefCell<Vec<PlaylistSummary>>,
}

impl Services {
    /// ログインできたら（か、ログインせずに使うと決めたら）一式を動かし始める。
    pub async fn start(repo: MusicRepository) -> Rc<Self> {
        let resolver = StreamResolver::new(repo.client().clone(), PlayerCipherProvider::default());
        let downloads = downloads::Downloads::new(resolver.clone(), repo.clone());
        let playback = playback::Playback::new(
            player::Player::new(resolver.clone(), Some(downloads.store())).with_live(repo.live().clone()).with_audio_cache(std::sync::Arc::new(
                // 曲の音を残す（上限 300MB。Windows と同じ）
                wave_core::stream::audio_cache::AudioCache::new(glib::user_cache_dir().join("wave").join("audio"), wave_core::stream::audio_cache::DEFAULT_MAX_BYTES, || {
                    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
                }),
            )),
            repo.clone(),
        );
        // 聴いた曲を数える（スマートダウンロードの「いつも聴いている」に使う）
        downloads.track_listens(&playback);
        // 前回のキューを戻し、以後は変わるたびに書く（同じログインのときだけ）
        queue_store::attach(&playback, repo.client().obtained_at());
        let lyrics = wave_core::lyrics::service::LyricsService::new(
            repo.clone(),
            wave_core::lyrics::lrclib::LrclibClient::default(),
            wave_core::lyrics::timed::TimedLyricsClient::new(&language(), &region()),
        );
        // RSS の番組（登録と聴いたところ）。エピソードは続きから流し、鳴らしている間は聴いたところを書き留める
        let podcasts = podcasts::Podcasts::new(&region());
        {
            let podcasts = podcasts.clone();
            playback.player().set_external_resume(move |url| podcasts.resume_ms(url));
        }
        podcasts.track(&playback);
        // 端末の連携（中継の場所が設定にあるときだけ）
        let relay_url = downloads.settings.borrow().relay_url.clone();
        let connect = connect::Connect::start(repo.clone(), playback.clone(), podcasts.clone(), relay_url).await;
        // 新作の通知は確かめるたびに設定を見る（切り替えがすぐ効く。Mac と同じ）
        let releases = {
            let downloads = downloads.clone();
            releases::Releases::start(repo.clone(), move || downloads.settings.borrow().release_notices)
        };
        let services = Rc::new(Self {
            repo,
            lyrics,
            playback,
            resolver,
            downloads,
            connect,
            releases,
            podcasts,
            ai: Default::default(),
            editable_playlists: Default::default(),
        });
        {
            let services = services.clone();
            glib::spawn_future_local(async move {
                services.ai.load().await;
                // 確かめ用: WAVE_DEMO_AI_ASK の頼みを、曲が流れ始めるのを待ってから送る
                if let Ok(ask) = std::env::var("WAVE_DEMO_AI_ASK") {
                    glib::timeout_future_seconds(6).await;
                    services.ai.send(&services, &ask);
                }
            });
        }
        services
    }

    /// ログアウトの後片付け（キュー・聴いた曲の音・検索の履歴・取った曲・Claude との会話・新作の記録を消し、
    /// キーリングのログイン情報を消す）。済んだら画面の側がログインの入り口に戻す。
    pub async fn log_out(&self) {
        self.playback.clear();
        queue_store::clear();
        // 聴いた曲の音も残さない
        self.playback.player().clear_audio_cache();
        // 検索の履歴も消す（ほかの端末と同じ）
        self.downloads.update_settings(|s| s.search_history.clear());
        // ログアウトしたら、取った曲と Claude との会話も消す（ほかの端末と同じ）
        self.downloads.clear();
        self.ai.wipe();
        self.releases.clear();
        keyring::delete().await;
        self.repo.client().set_credentials(None);
    }
}

/// 表示の言語（YouTube から届く文字の言語）。LANG が日本語なら ja、ほかは en。
pub fn language() -> String {
    let lang = std::env::var("LANG").unwrap_or_default();
    if lang.starts_with("ja") { "ja".into() } else { "en".into() }
}

/// 地域。LANG の地域の部分（ja_JP.UTF-8 なら JP）。読めなければ JP。
pub fn region() -> String {
    std::env::var("LANG")
        .ok()
        .and_then(|l| l.split(['_', '.']).nth(1).map(str::to_uppercase))
        .filter(|r| r.len() == 2)
        .unwrap_or_else(|| "JP".into())
}
