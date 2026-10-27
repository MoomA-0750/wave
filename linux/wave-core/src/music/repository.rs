//! 曲・棚の取得（Windows・Android の MusicRepository と同じ呼び方）。

use serde_json::json;

use super::models::{HomePage, PlaylistPage, PlaylistSummary, SongPage, UpNextPage};
use super::parsers;
use crate::innertube::{ApiResult, Failure, InnerTube};

pub const SONGS_FILTER: &str = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
pub const HOME: &str = "FEmusic_home";
pub const LIBRARY_PLAYLISTS: &str = "FEmusic_liked_playlists";
/// 高く評価した曲のプレイリスト。
pub const LIKED_SONGS: &str = "LM";
const MAX_LIBRARY_PAGES: usize = 20;

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
}
