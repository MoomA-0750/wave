//! 曲・棚の取得（Windows・Android の MusicRepository と同じ呼び方）。

use serde_json::json;

use super::models::{HomePage, SongPage};
use super::parsers;
use crate::innertube::{ApiResult, InnerTube};

pub const SONGS_FILTER: &str = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
pub const HOME: &str = "FEmusic_home";

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
}
