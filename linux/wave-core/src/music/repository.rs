//! 曲・棚の取得（Windows・Android の MusicRepository と同じ呼び方）。

use serde_json::json;

use super::models::{Account, AddToPlaylistResult, AlbumPage, LikeStatus, ArtistPage, HomePage, PlaylistPage, PlaylistSummary, SongPage, UpNextPage};
use super::parsers;
use crate::innertube::{ApiResult, Failure, InnerTube};

pub const SONGS_FILTER: &str = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
pub const HOME: &str = "FEmusic_home";
pub const LIBRARY_PLAYLISTS: &str = "FEmusic_liked_playlists";
/// 高く評価した曲のプレイリスト。
pub const LIKED_SONGS: &str = "LM";
const MAX_LIBRARY_PAGES: usize = 20;
const MAX_PLAYLIST_PAGES: usize = 50;

#[derive(Clone)]
pub struct MusicRepository {
    client: InnerTube,
}

impl MusicRepository {
    pub fn new(client: InnerTube) -> Self {
        Self { client }
    }

    pub fn client(&self) -> &InnerTube {
        &self.client
    }

    /// 曲だけに絞った検索。continuation があれば続きのページ。
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
}
