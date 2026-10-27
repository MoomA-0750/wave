//! Claude との会話の履歴（Windows・Android の AiHistory と同じ決まり）。端末の中だけに置き、同期しない（中継は会話を持たない）。
//! 新しく話した順に、最大 MAX 件。ログインの情報は入れない（発言と曲の情報だけ）。

use std::sync::LazyLock;

use regex::Regex;
use serde::{Deserialize, Serialize};

use crate::music::models::Song;

pub const VERSION: u32 = 1;
pub const MAX: usize = 50;
pub const TITLE_MAX: usize = 80;

pub const USER: &str = "user";
pub const ASSISTANT: &str = "assistant";
pub const ACTION: &str = "action";
pub const SONGS: &str = "songs";
pub const FAILURE: &str = "failure";

/// 保存した発言。kind は user・assistant・action・songs・failure。video_id は assistant だけ（時刻を押せる曲）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct SavedMessage {
    pub kind: String,
    #[serde(default)]
    pub text: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub video_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub songs: Option<Vec<Song>>,
}

/// 保存した会話。題名は最初の自分の発言。時刻は Unix ミリ秒。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct Conversation {
    pub id: String,
    pub title: String,
    pub created_at_ms: i64,
    pub updated_at_ms: i64,
    pub messages: Vec<SavedMessage>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct HistoryFile {
    version: u32,
    conversations: Vec<Conversation>,
}

static SPACES: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());

/// 題名（最初の自分の発言を1行にして縮める）。自分の発言が無ければ空。
pub fn title(messages: &[SavedMessage]) -> String {
    let Some(text) = messages.iter().find(|m| m.kind == USER).map(|m| m.text.as_str()) else { return String::new() };
    let text = SPACES.replace_all(text, " ").trim().to_string();
    if text.chars().count() > TITLE_MAX { format!("{}…", text.chars().take(TITLE_MAX - 1).collect::<String>()) } else { text }
}

/// 会話を入れる（同じ id があれば置き換える）。新しく話した順に並べ、MAX 件を超えた古いものは落とす。
pub fn upsert(list: &[Conversation], conversation: Conversation) -> Vec<Conversation> {
    let mut out: Vec<Conversation> = list.iter().filter(|c| c.id != conversation.id).cloned().collect();
    out.push(conversation);
    out.sort_by_key(|c| std::cmp::Reverse(c.updated_at_ms));
    out.truncate(MAX);
    out
}

pub fn remove(list: &[Conversation], id: &str) -> Vec<Conversation> {
    list.iter().filter(|c| c.id != id).cloned().collect()
}

pub fn encode(list: &[Conversation]) -> String {
    serde_json::to_string(&HistoryFile { version: VERSION, conversations: list.to_vec() }).expect("plain data always serializes")
}

/// 読めない・形の違うファイルは空にする。
pub fn decode(text: &str) -> Vec<Conversation> {
    match serde_json::from_str::<HistoryFile>(text) {
        Ok(file) if file.version == VERSION => {
            let mut list = file.conversations;
            list.sort_by_key(|c| std::cmp::Reverse(c.updated_at_ms));
            list.truncate(MAX);
            list
        }
        _ => Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn conversation(id: &str, updated: i64) -> Conversation {
        Conversation { id: id.into(), title: id.into(), created_at_ms: 0, updated_at_ms: updated, messages: vec![] }
    }

    #[test]
    fn keeps_the_newest_max_and_replaces_by_id() {
        let mut list = Vec::new();
        for i in 0..(MAX as i64 + 5) {
            list = upsert(&list, conversation(&format!("c{i}"), i));
        }
        assert_eq!(list.len(), MAX);
        assert_eq!(list[0].id, format!("c{}", MAX + 4));
        list = upsert(&list, conversation("c10", 1_000));
        assert_eq!(list[0].id, "c10");
        assert_eq!(remove(&list, "c10").len(), MAX - 1);
    }

    #[test]
    fn title_and_round_trip() {
        let messages = vec![
            SavedMessage { kind: ACTION.into(), text: "x".into(), video_id: None, songs: None },
            SavedMessage { kind: USER.into(), text: "  what  does\\nit mean? ".replace("\\n", "\n"), video_id: None, songs: None },
        ];
        assert_eq!(title(&messages), "what does it mean?");
        let list = vec![Conversation { id: "x".into(), title: "t".into(), created_at_ms: 1, updated_at_ms: 2, messages }];
        assert_eq!(decode(&encode(&list)), list);
        assert!(decode("not json").is_empty());
        assert!(decode(r#"{"Version":99,"Conversations":[]}"#).is_empty());
    }
}
