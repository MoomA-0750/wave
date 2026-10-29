//! 曲・棚の取得（Windows・Android の MusicRepository と同じ呼び方）。

use serde_json::json;

use super::models::{Account, AddToPlaylistResult, AlbumPage, LikeStatus, MusicVideo, ArtistPage, HomePage, PlaylistPage, PlaylistSummary, SongPage, UpNextPage, VerifiedAccount, AlbumSummary, ArtistSummary, SearchPage, SearchSuggestions, MoodSection, HomeItem};
use super::parsers;
use crate::innertube::{ApiResult, Failure, InnerTube};

pub const SONGS_FILTER: &str = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
pub const ALBUMS_FILTER: &str = "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D";
pub const ARTISTS_FILTER: &str = "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D";
pub const FEATURED_PLAYLISTS_FILTER: &str = "EgeKAQQoADgBahIQBRAEEAkQAxAKEBUQDhAREBA%3D";
pub const COMMUNITY_PLAYLISTS_FILTER: &str = "EgeKAQQoAEABahIQBRAEEAkQAxAKEBUQDhAREBA%3D";
pub const VIDEOS_FILTER: &str = "EgWKAQIQAWoSEAUQBBAJEAMQChAVEA4QERAQ";
pub const HOME: &str = "FEmusic_home";
pub const LIBRARY_PLAYLISTS: &str = "FEmusic_liked_playlists";
/// 高く評価した曲のプレイリスト。
pub const LIKED_SONGS: &str = "LM";
const MAX_LIBRARY_PAGES: usize = 20;
const MAX_PLAYLIST_PAGES: usize = 50;

#[derive(Clone)]
pub struct MusicRepository {
    client: InnerTube,
    /// ライブ配信の検索と HLS の解決（www.youtube.com へ。認証情報を送らない）。
    live: crate::stream::live::LiveClient,
}

impl MusicRepository {
    pub fn new(client: InnerTube) -> Self {
        let (hl, gl) = client.locale();
        let live = crate::stream::live::LiveClient::new(hl, gl);
        Self { client, live }
    }

    pub fn live(&self) -> &crate::stream::live::LiveClient {
        &self.live
    }

    pub fn client(&self) -> &InnerTube {
        &self.client
    }

    /// 曲だけに絞った検索。continuation があれば続きのページ。
    /// 絞った検索（filter は *_FILTER）の生の応答。続きなら filter は使わない。
    async fn search_raw(&self, query: &str, filter: &str, continuation: Option<&str>) -> ApiResult<serde_json::Value> {
        match continuation {
            None => self.client.post("search", json!({ "query": query, "params": filter }), true, &[]).await,
            Some(token) => self.client.post("search", json!({}), true, &[("continuation", token), ("ctoken", token)]).await,
        }
    }

    pub async fn search_albums(&self, query: &str, continuation: Option<&str>) -> ApiResult<SearchPage<AlbumSummary>> {
        Ok(parsers::search_albums(&self.search_raw(query, ALBUMS_FILTER, continuation).await?))
    }

    pub async fn search_artists(&self, query: &str, continuation: Option<&str>) -> ApiResult<SearchPage<ArtistSummary>> {
        Ok(parsers::search_artists(&self.search_raw(query, ARTISTS_FILTER, continuation).await?))
    }

    /// ライブ配信（Windows と同じく、YouTube Music の動画の検索のうちライブの印のあるものと、YouTube のライブの検索を合わせる）。
    /// 続きは YouTube のライブの検索の続き。
    pub async fn search_live(&self, query: &str, continuation: Option<&str>) -> ApiResult<SongPage> {
        if let Some(token) = continuation {
            let (songs, continuation) = self.live.search(query, Some(token)).await.ok_or(Failure::Network)?;
            return Ok(SongPage { songs, continuation });
        }
        let (music, web) = tokio::join!(self.search_raw(query, VIDEOS_FILTER, None), self.live.search(query, None));
        let music_live: Option<Vec<_>> = music.as_ref().ok().map(|root| parsers::search_songs(root).songs.into_iter().filter(|s| s.is_live).collect());
        if music_live.is_none() && web.is_none() {
            return Err(music.err().unwrap_or(Failure::Network));
        }
        let mut songs = music_live.unwrap_or_default();
        let continuation = web.as_ref().and_then(|(_, c)| c.clone());
        for song in web.map(|(s, _)| s).unwrap_or_default() {
            if !songs.iter().any(|s| s.video_id == song.video_id) {
                songs.push(song);
            }
        }
        Ok(SongPage { songs, continuation })
    }

    /// 気分・ジャンルのタイル（ジャンルの区切りの先頭に、アプリで足した Lo-Fi を入れる。Windows と同じ）。
    pub async fn moods(&self) -> ApiResult<Vec<MoodSection>> {
        let root = self.client.post("browse", json!({ "browseId": "FEmusic_moods_and_genres" }), true, &[]).await?;
        Ok(super::lofi::add_to(parsers::moods(&root)))
    }

    /// 気分・ジャンルのタイルのページ（棚の並び）。Lo-Fi はアプリで組んだページ。
    pub async fn mood_category(&self, params: &str) -> ApiResult<HomePage> {
        if params == super::lofi::PARAMS {
            return self.lofi_page().await;
        }
        let root = self.client.post("browse", json!({ "browseId": "FEmusic_moods_and_genres_category", "params": params }), true, &[]).await?;
        Ok(parsers::home_page(&root))
    }

    /// Lo-Fi のページ: ライブ配信・YouTube Music のプレイリスト・みんなのプレイリストの棚。
    async fn lofi_page(&self) -> ApiResult<HomePage> {
        use super::lofi;
        let (lives, featured, community) = tokio::join!(
            self.search_live(lofi::LIVE_QUERY, None),
            self.search_raw(lofi::PLAYLIST_QUERY, FEATURED_PLAYLISTS_FILTER, None),
            self.search_raw(lofi::PLAYLIST_QUERY, COMMUNITY_PLAYLISTS_FILTER, None),
        );
        let mut shelves = Vec::new();
        if let Ok(page) = &lives {
            lofi::push_shelf(&mut shelves, lofi::LIVE_TITLE, page.songs.iter().take(lofi::MAX_ITEMS).cloned().map(HomeItem::Song).collect());
        }
        if let Ok(root) = &featured {
            lofi::push_shelf(&mut shelves, lofi::FEATURED_TITLE, lofi::featured(parsers::search_playlists(root).items).into_iter().map(HomeItem::Playlist).collect());
        }
        if let Ok(root) = &community {
            lofi::push_shelf(&mut shelves, lofi::COMMUNITY_TITLE, parsers::search_playlists(root).items.into_iter().take(lofi::MAX_ITEMS).map(HomeItem::Playlist).collect());
        }
        if shelves.is_empty() {
            return Err(lives.err().unwrap_or(Failure::Network));
        }
        Ok(HomePage { shelves, continuation: None, chips: Vec::new() })
    }

    /// 打ち込み中の候補（ログインの情報は付けない）。
    pub async fn suggestions(&self, input: &str) -> ApiResult<SearchSuggestions> {
        Ok(parsers::search_suggestions(&self.client.post("music/get_search_suggestions", json!({ "input": input }), false, &[]).await?))
    }

    pub async fn search_songs(&self, query: &str, continuation: Option<&str>) -> ApiResult<SongPage> {
        let root = match continuation {
            None => self.client.post("search", json!({ "query": query, "params": SONGS_FILTER }), true, &[]).await?,
            Some(token) => self.client.post("search", json!({}), true, &[("continuation", token), ("ctoken", token)]).await?,
        };
        Ok(parsers::search_songs(&root))
    }

    /// ホーム（FEmusic_home）。chip_params で絞り込み、continuation で続き。
    pub async fn home(&self, continuation: Option<&str>, chip_params: Option<&str>) -> ApiResult<HomePage> {
        let root = match continuation {
            Some(token) => self.client.post("browse", json!({ "continuation": token }), true, &[]).await?,
            None => {
                let mut body = json!({ "browseId": HOME });
                if let Some(params) = chip_params {
                    body["params"] = json!(params);
                }
                self.client.post("browse", body, true, &[]).await?
            }
        };
        Ok(parsers::home_page(&root))
    }

    /// ライブラリのプレイリスト（続きのページまで。途中で失敗したらそこまでの分）。ログイン必須。
    /// 登録したアーティスト（続きのページまで）。2つめは、最後まで読めたか（途中で失敗・打ち切ったら false）。
    pub async fn subscribed_artists(&self) -> ApiResult<(Vec<ArtistSummary>, bool)> {
        let mut items: Vec<ArtistSummary> = Vec::new();
        let mut token: Option<String> = None;
        let mut complete = false;
        for page in 0..MAX_LIBRARY_PAGES {
            let body = match &token {
                Some(t) => json!({ "continuation": t }),
                None => json!({ "browseId": "FEmusic_library_corpus_artists" }),
            };
            let root = match self.logged_in_post("browse", body).await {
                Ok(root) => root,
                Err(e) if page == 0 => return Err(e),
                Err(_) => break,
            };
            for item in parsers::library_cards(&root) {
                if let HomeItem::Artist(a) = item
                    && !items.iter().any(|x| x.browse_id == a.browse_id)
                {
                    items.push(a);
                }
            }
            let next = parsers::library_continuation(&root);
            if next.is_none() || next == token {
                complete = true;
                break;
            }
            token = next;
        }
        Ok((items, complete))
    }

    /// YouTube Music の新作のアルバム。
    pub async fn new_releases(&self) -> ApiResult<Vec<AlbumSummary>> {
        let root = self.client.post("browse", json!({ "browseId": "FEmusic_new_releases_albums" }), true, &[]).await?;
        Ok(parsers::new_releases(&root))
    }

    pub async fn library_playlists(&self) -> ApiResult<Vec<PlaylistSummary>> {
        let mut items: Vec<PlaylistSummary> = Vec::new();
        let mut token: Option<String> = None;
        for page in 0..MAX_LIBRARY_PAGES {
            let body = match &token {
                Some(t) => json!({ "continuation": t }),
                None => json!({ "browseId": LIBRARY_PLAYLISTS }),
            };
            let root = match self.logged_in_post("browse", body).await {
                Ok(root) => root,
                Err(e) if page == 0 => return Err(e),
                Err(_) => break,
            };
            for p in parsers::playlists(&root) {
                if !items.iter().any(|x| x.playlist_id == p.playlist_id) {
                    items.push(p);
                }
            }
            // 同じトークンが返ったら止める
            let next = parsers::library_continuation(&root);
            if next.is_none() || next == token {
                break;
            }
            token = next;
        }
        Ok(items)
    }

    /// プレイリストの曲（高く評価した曲は LM）。continuation で続き。
    pub async fn playlist(&self, playlist_id: &str, continuation: Option<&str>) -> ApiResult<PlaylistPage> {
        let body = match continuation {
            Some(t) => json!({ "continuation": t }),
            None => json!({ "browseId": format!("VL{playlist_id}") }),
        };
        Ok(parsers::playlist_page(&self.logged_in_post("browse", body).await?))
    }

    /// 次に流れる曲（ラジオ）。最初は video_id と playlist_id（RDAMVM<videoId>）、続きは continuation で取る。
    pub async fn up_next(&self, video_id: Option<&str>, playlist_id: Option<&str>, continuation: Option<&str>) -> ApiResult<UpNextPage> {
        let mut body = json!({ "contentCheckOk": true, "racyCheckOk": true });
        if let Some(v) = video_id {
            body["videoId"] = json!(v);
        }
        if let Some(p) = playlist_id {
            body["playlistId"] = json!(p);
        }
        if let Some(c) = continuation {
            body["continuation"] = json!(c);
        }
        Ok(parsers::up_next(&self.client.post("next", body, true, &[]).await?))
    }

    /// ログイン必須の呼び出し。ログインの案内だけが返ったら NotLoggedIn。
    async fn logged_in_post(&self, endpoint: &str, body: serde_json::Value) -> ApiResult<serde_json::Value> {
        if !self.client.is_logged_in() {
            return Err(Failure::NotLoggedIn);
        }
        let root = self.client.post(endpoint, body, true, &[]).await?;
        if parsers::is_sign_in_prompt(&root) { Err(Failure::NotLoggedIn) } else { Ok(root) }
    }

    /// アルバムのページ（MPREb…）。
    pub async fn album(&self, browse_id: &str) -> ApiResult<AlbumPage> {
        let root = self.client.post("browse", json!({ "browseId": browse_id }), true, &[]).await?;
        parsers::album_page(&root, browse_id).ok_or(Failure::Parse)
    }

    /// アーティストのページ（UC…）。
    pub async fn artist(&self, browse_id: &str) -> ApiResult<ArtistPage> {
        let root = self.client.post("browse", json!({ "browseId": browse_id }), true, &[]).await?;
        parsers::artist_page(&root).ok_or(Failure::Parse)
    }

    /// ログイン中のアカウントの名前と写真。
    pub async fn account(&self) -> ApiResult<Account> {
        let root = self.logged_in_post("account/account_menu", json!({})).await?;
        parsers::account(&root).ok_or(Failure::NotLoggedIn)
    }

    /// 今のログインが、保存した DATASYNC_ID のアカウントか（Windows の VerifiedAccountAsync と同じ）。端末の連携でアカウントの照合に使う。
    /// accounts_list の選択中のアカウントがちょうど1件で、その datasyncIdToken（"||" より前）が保存した値と同じで、
    /// 確かめている間にログインが変わっていないときだけ返す。値はログ・画面に出さない。
    pub async fn verified_account(&self) -> Option<VerifiedAccount> {
        let generation = self.client.generation();
        let saved = self.client.data_sync_id()?;
        let root = self.client.post("account/accounts_list", json!({}), true, &[]).await.ok()?;
        let mut selected: Vec<String> = parsers::account_tokens(&root).into_iter().filter(|(s, _)| *s).map(|(_, id)| id).collect();
        selected.dedup();
        (selected.len() == 1 && selected[0] == saved && self.client.generation() == generation).then_some(VerifiedAccount { generation, id: saved })
    }

    /// 曲の歌詞のタブ（MPLY…）の ID（next の最初の応答から）。無ければ None。
    pub async fn lyrics_browse_id(&self, video_id: &str) -> ApiResult<Option<String>> {
        let root = self.client.post("next", json!({ "videoId": video_id, "contentCheckOk": true, "racyCheckOk": true }), true, &[]).await?;
        Ok(parsers::lyrics_browse_id(&root))
    }

    /// 歌詞のタブの時刻なしの本文。無ければ None。
    pub async fn plain_lyrics_of_tab(&self, browse_id: &str) -> ApiResult<Option<crate::lyrics::SongLyrics>> {
        let root = self.client.post("browse", json!({ "browseId": browse_id }), true, &[]).await?;
        Ok(parsers::plain_lyrics(&root))
    }

    /// 曲の評価（next の最初の応答から）。分からなければ None。
    pub async fn like_status(&self, video_id: &str) -> ApiResult<Option<LikeStatus>> {
        let root = self.client.post("next", json!({ "videoId": video_id, "contentCheckOk": true, "racyCheckOk": true }), true, &[]).await?;
        Ok(parsers::like_status(&root, video_id))
    }

    /// 「高く評価」を付ける・外す。ログイン必須。
    pub async fn set_liked(&self, video_id: &str, liked: bool) -> ApiResult<()> {
        if !self.client.is_logged_in() {
            return Err(Failure::NotLoggedIn);
        }
        let endpoint = if liked { "like/like" } else { "like/removelike" };
        self.client.post(endpoint, json!({ "target": { "videoId": video_id } }), true, &[]).await.map(|_| ())
    }

    /// プレイリストの中の曲の videoId を全部読む。最後まで読めなければ None（重複を確かめられない）。
    pub async fn scan_playlist(&self, playlist_id: &str) -> Option<std::collections::HashSet<String>> {
        let mut ids = std::collections::HashSet::new();
        let mut token: Option<String> = None;
        for _ in 0..MAX_PLAYLIST_PAGES {
            let page = self.playlist(playlist_id, token.as_deref()).await.ok()?;
            ids.extend(page.songs.into_iter().map(|s| s.video_id));
            match page.continuation {
                None if page.end_confirmed => return Some(ids),
                None => return None,
                next => token = next,
            }
        }
        None
    }

    /// 曲をプレイリストに追加する。先に全ページを読んで重複を確かめ、読めなければ追加しない（known_empty なら確かめない）。
    pub async fn add_to_playlist(&self, playlist_id: &str, video_id: &str, known_empty: bool) -> AddToPlaylistResult {
        if !known_empty {
            match self.scan_playlist(playlist_id).await {
                None => return AddToPlaylistResult::NotChecked,
                Some(ids) if ids.contains(video_id) => return AddToPlaylistResult::AlreadyInPlaylist,
                Some(_) => {}
            }
        }
        if self.edit_playlist(playlist_id, json!({ "action": "ACTION_ADD_VIDEO", "addedVideoId": video_id })).await {
            AddToPlaylistResult::Added
        } else {
            AddToPlaylistResult::Failed
        }
    }

    /// プレイリストから曲を外す（set_video_id はプレイリストの中の項目の ID）。
    pub async fn remove_from_playlist(&self, playlist_id: &str, video_id: &str, set_video_id: &str) -> bool {
        self.edit_playlist(playlist_id, json!({ "action": "ACTION_REMOVE_VIDEO", "setVideoId": set_video_id, "removedVideoId": video_id })).await
    }

    async fn edit_playlist(&self, playlist_id: &str, action: serde_json::Value) -> bool {
        if !self.client.is_logged_in() {
            return false;
        }
        let id = playlist_id.strip_prefix("VL").unwrap_or(playlist_id);
        let result = self.client.post("browse/edit_playlist", json!({ "playlistId": id, "actions": [action] }), true, &[]).await;
        result.is_ok_and(|root| root.get("status").and_then(|s| s.as_str()) == Some("STATUS_SUCCEEDED"))
    }

    /// 非公開のプレイリストを作る。作った playlistId を返す。
    pub async fn create_playlist(&self, title: &str) -> ApiResult<String> {
        if !self.client.is_logged_in() {
            return Err(Failure::NotLoggedIn);
        }
        let root = self.client.post("playlist/create", json!({ "title": title, "privacyStatus": "PRIVATE" }), true, &[]).await?;
        root.get("playlistId").and_then(|p| p.as_str()).map(String::from).ok_or(Failure::Parse)
    }

    /// 曲に見せる映像（next の最初の応答から）。無ければ None。
    pub async fn music_video(&self, video_id: &str) -> ApiResult<Option<MusicVideo>> {
        let root = self.client.post("next", json!({ "videoId": video_id, "contentCheckOk": true, "racyCheckOk": true }), true, &[]).await?;
        Ok(parsers::music_video_of(&root, video_id))
    }

    /// 再生履歴の曲（最初のページ。新しい順）。ログイン必須。
    /// 再生履歴を区切り（「今日」など）ごとに。ログイン必須。
    pub async fn history_sections(&self) -> ApiResult<Vec<(Option<String>, Vec<super::models::Song>)>> {
        Ok(parsers::history_sections(&self.logged_in_post("browse", json!({ "browseId": "FEmusic_history" })).await?))
    }

    pub async fn history(&self) -> ApiResult<Vec<super::models::Song>> {
        Ok(parsers::history_songs(&self.logged_in_post("browse", json!({ "browseId": "FEmusic_history" })).await?))
    }
}
