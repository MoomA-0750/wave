//! WaveApp の続き（その2）: 並べ替え・絞り込みのできる曲の一覧（アルバム・プレイリスト）、歌詞から探す、確かめ用の環境変数。

use std::pin::Pin;

use cxx_qt_lib::QString;
use serde_json::{Value, json};
use wave_core::music::collection::{self, SortKey};
use wave_core::music::models::Song;
use wave_core::music::repository::{ORDER_ADDED, ORDER_PUBLISHED, order_params};
use wave_desktop::runtime;

use crate::app::{failure, repo, services};
use crate::bridge::qobject;
use crate::view;

/// 数と単位（「27 曲」「1 時間 50 分」）を離さない（折り返しで「27」と「曲」が別の行に分かれないように）。
pub fn keep_units(text: &str) -> String {
    let mut out = String::new();
    let chars: Vec<char> = text.chars().collect();
    for (i, c) in chars.iter().enumerate() {
        let joins = *c == ' ' && i > 0 && chars[i - 1].is_ascii_digit() && chars.get(i + 1).is_some_and(|n| !n.is_ascii() && !n.is_whitespace());
        out.push(if joins { '\u{a0}' } else { *c });
    }
    out
}

/// プレイリストの最初のページ（並べ方を付けて）。続きは playlist.more で届いた分から順に足す（GTK 版と同じ）。
pub async fn load_playlist(id: String, order: String) -> Value {
    let Some(repo) = repo() else { return failure("not started") };
    let (key, descending) = collection::parse_order(&order);
    let params = match key {
        SortKey::Added => Some(order_params(ORDER_ADDED, descending)),
        SortKey::Published => Some(order_params(ORDER_PUBLISHED, descending)),
        _ => None,
    };
    let (r, i) = (repo.clone(), id.clone());
    let mut page = match runtime::run(async move { r.playlist_in_order(&i, None, params.as_deref()).await }).await {
        Ok(page) => page,
        Err(wave_core::innertube::Failure::NotLoggedIn) => return failure("このプレイリストを見るにはログインしてください"),
        Err(e) => return failure(format!("プレイリストを読み込めませんでした（{e}）")),
    };
    page.set_video_ids.resize(page.songs.len(), None);
    let mut value = view::playlist_page(&id, &page);
    value["subtitle"] = json!(keep_units(&page.subtitle.clone().unwrap_or_default()));
    value["hasSubtitle"] = json!(page.subtitle.as_ref().is_some_and(|s| !s.is_empty()));
    value["sortable"] = json!(page.sortable);
    value
}

/// プレイリストの続きの1ページ。
pub async fn load_playlist_more(id: String, token: String) -> Value {
    let Some(repo) = repo() else { return failure("not started") };
    let (r, i, t) = (repo.clone(), id.clone(), token.clone());
    match runtime::run(async move { r.playlist(&i, Some(&t)).await }).await {
        Ok(mut page) => {
            page.set_video_ids.resize(page.songs.len(), None);
            // 同じトークンが返ってきたら終わり
            let next = page.continuation.clone().filter(|n| *n != token).unwrap_or_default();
            let value = view::playlist_page(&id, &page);
            json!({ "songs": value["songs"], "continuation": next })
        }
        Err(e) => failure(e),
    }
}

/// 歌詞から探す（検索の「歌詞」のタブ。GTK 版と同じ wave-core の search）。
pub async fn search_lyrics(query: String) -> Value {
    let Some(s) = services() else { return failure("not started") };
    let (repo, lyrics) = (s.repo.clone(), s.lyrics.clone());
    let result = runtime::run(async move {
        wave_core::lyrics::search::search(&query, |q| async move { repo.search_top_songs(&q).await }, |song| {
            let lyrics = lyrics.clone();
            async move { lyrics.lyrics(&song).await }
        })
        .await
    })
    .await;
    match result {
        Ok(hits) => json!({
            "items": hits.iter().map(|hit| {
                let mut v = view::song(&hit.song);
                v["quote"] = json!(hit.matched.as_ref().map(|m| format!("{}\u{201C}{}\u{201D}", if m.exact { "" } else { "≈ " }, m.line)).unwrap_or_default());
                v
            }).collect::<Vec<_>>(),
            "continuation": "",
        }),
        Err(e) => failure(e),
    }
}

fn songs_of(json: &QString) -> Vec<Song> {
    let list: Vec<Value> = serde_json::from_str(&json.to_string()).unwrap_or_default();
    list.into_iter().filter_map(|v| serde_json::from_value(v.get("song").cloned().unwrap_or(v)).ok()).collect()
}

impl qobject::WaveApp {
    /// 絞り込み（filter・kind）と並べ方（"Title:desc" の形）を当てた並び（元の位置の番号の JSON の配列）。
    pub fn arrange_songs(&self, songs_json: &QString, filter: &QString, kind: &QString, order: &QString) -> QString {
        let songs = songs_of(songs_json);
        let (key, descending) = collection::parse_order(&order.to_string());
        let kind = kind.to_string();
        let view = collection::arrange(&songs, &filter.to_string(), (!kind.is_empty()).then_some(kind.as_str()), key, descending);
        QString::from(&json!(view).to_string())
    }

    /// 並べ方の選びどころ（sortable でなければ YouTube Music に並べてもらうものを除く）と、今の並べ方の印。
    pub fn sort_choices(&self, sortable: bool, order: &QString) -> QString {
        let (current, descending) = collection::parse_order(&order.to_string());
        let list: Vec<Value> = SortKey::choices(sortable)
            .into_iter()
            .map(|k| {
                let mark = if k != current {
                    ""
                } else if k == SortKey::Custom && !descending {
                    "object-select-symbolic"
                } else if descending {
                    "go-down-symbolic"
                } else {
                    "go-up-symbolic"
                };
                json!({ "name": k.name(), "label": k.label(), "mark": mark, "server": k.server() })
            })
            .collect();
        QString::from(&json!({ "choices": list, "label": current.label() }).to_string())
    }

    /// 並べ方を選んだ（同じものなら向きを変える）。新しい並べ方を返す。
    pub fn choose_order(&self, order: &QString, key: &QString) -> QString {
        let key = SortKey::ALL.into_iter().find(|k| k.name() == key.to_string()).unwrap_or(SortKey::Custom);
        let (k, d) = collection::choose(collection::parse_order(&order.to_string()), key);
        QString::from(&collection::format_order(k, d))
    }

    /// プレイリストごとに覚えた並べ方（無ければ空）。
    pub fn playlist_order(&self, id: &QString) -> QString {
        let saved = services().and_then(|s| s.downloads.settings.borrow().playlist_orders.get(&id.to_string()).cloned());
        QString::from(&saved.unwrap_or_default())
    }

    pub fn set_playlist_order(self: Pin<&mut Self>, id: &QString, order: &QString) {
        if let Some(s) = services() {
            let (id, order) = (id.to_string(), order.to_string());
            s.downloads.update_settings(|st| {
                st.playlist_orders.insert(id, order);
            });
        }
    }

    /// 曲の一覧の補足「N 曲、M 分」（数と単位を離さない）。
    pub fn count_and_length(&self, songs_json: &QString) -> QString {
        QString::from(&keep_units(&collection::count_and_length(&songs_of(songs_json))))
    }

    /// 確かめ用の環境変数（WAVE_WINDOW_SIZE など。WAVE_ で始まるものだけ）。
    pub fn env(&self, name: &QString) -> QString {
        let name = name.to_string();
        if !name.starts_with("WAVE_") {
            return QString::default();
        }
        QString::from(&std::env::var(&name).unwrap_or_default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers_stay_with_units() {
        assert_eq!(keep_units("アルバム • 2026年 • 27 曲、1 時間 50 分"), "アルバム • 2026年 • 27\u{a0}曲、1\u{a0}時間 50\u{a0}分");
        assert_eq!(keep_units("Album 2 songs"), "Album 2 songs");
    }
}
