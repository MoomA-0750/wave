//! 中継（ai-bridge の /v1/run）へ送る頼み（Windows・Android・Mac の AiAgentSpec と同じ）。
//! システムプロンプトと道具の定義は ai/agent.json（ほかの OS と同じファイルを埋め込む）。

use serde_json::{Value, json};

use crate::music::models::Song;

/// リポジトリの ai/agent.json（Android・Mac・Windows と同じもの）。
const AGENT_JSON: &str = include_str!("../../../../ai/agent.json");

pub struct AgentSpec {
    system: String,
    tools: Value,
    max_lyrics_chars: usize,
}

impl AgentSpec {
    /// アプリに埋め込んだ ai/agent.json。
    pub fn bundled() -> Self {
        Self::parse(AGENT_JSON).expect("ai/agent.json is valid")
    }

    pub fn parse(json: &str) -> Option<Self> {
        let root: Value = serde_json::from_str(json).ok()?;
        Some(Self {
            system: root.get("system")?.as_str()?.to_string(),
            tools: root.get("tools").filter(|t| t.is_array())?.clone(),
            max_lyrics_chars: root.get("max_lyrics_chars").and_then(Value::as_u64).unwrap_or(20_000) as usize,
        })
    }

    /// /v1/run の本文。history は (role, content) で、role は "user" か "assistant"。
    pub fn body(&self, task: &str, history: &[(String, String)], song: Option<&Song>, lyrics: Option<&str>) -> Value {
        json!({ "system": self.system, "prompt": self.prompt(task, history, song, lyrics), "tools": self.tools })
    }

    pub fn prompt(&self, task: &str, history: &[(String, String)], song: Option<&Song>, lyrics: Option<&str>) -> String {
        let mut out = String::new();
        if let Some(song) = song {
            out.push_str("<now_playing>\n");
            out.push_str(&format!("題名: {}\n", song.title));
            out.push_str(&format!("アーティスト: {}\n", song.artists_text()));
            out.push_str(&format!("アルバム: {}\n", song.album.as_ref().map(|a| a.title.as_str()).unwrap_or("")));
            out.push_str(&format!("video_id: {}\n", song.video_id));
            out.push_str("</now_playing>\n\n");
        }
        if let Some(lyrics) = lyrics.filter(|l| !l.is_empty()) {
            let clipped: String = lyrics.chars().take(self.max_lyrics_chars).collect();
            out.push_str(&format!("<lyrics>\n{clipped}\n</lyrics>\n\n"));
        }
        if !history.is_empty() {
            out.push_str("<conversation>\n");
            for (role, content) in history {
                out.push_str(if role == "user" { "利用者: " } else { "アシスタント: " });
                out.push_str(content);
                out.push('\n');
            }
            out.push_str("</conversation>\n\n");
        }
        out.push_str(task);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::music::models::{AlbumRef, ArtistRef};

    #[test]
    fn bundled_spec_has_the_shared_tools() {
        let body = AgentSpec::bundled().body("hi", &[], None, None);
        let names: Vec<&str> = body["tools"].as_array().unwrap().iter().filter_map(|t| t["name"].as_str()).collect();
        assert!(names.contains(&"search_songs") && names.contains(&"play_songs") && names.contains(&"create_playlist"));
        assert_eq!(body["prompt"], "hi");
    }

    #[test]
    fn prompt_carries_song_lyrics_and_history() {
        let song = Song {
            video_id: "abcdefghijk".into(),
            title: "T".into(),
            artists: vec![ArtistRef { name: "A".into(), browse_id: None }],
            album: Some(AlbumRef { title: "Al".into(), browse_id: None }),
            duration_seconds: None,
            thumbnail_url: None,
            kind: None,
            is_live: false,
        };
        let prompt = AgentSpec::bundled().prompt("意味は？", &[("user".into(), "前".into()), ("assistant".into(), "答".into())], Some(&song), Some("[00:01] 歌"));
        assert!(prompt.starts_with("<now_playing>\n題名: T\nアーティスト: A\nアルバム: Al\nvideo_id: abcdefghijk\n</now_playing>"));
        assert!(prompt.contains("<lyrics>\n[00:01] 歌\n</lyrics>"));
        assert!(prompt.contains("利用者: 前\nアシスタント: 答\n"));
        assert!(prompt.ends_with("意味は？"));
    }
}
