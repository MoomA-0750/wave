//! キューをプレイリストとして保存する（SPEC「キューをプレイリストとして保存」。全端末で同じ決まり）。

use super::feed::AUDIO_PREFIX;
use super::models::Song;

/// 保存する曲の videoId。キューの並びのまま、エピソード（YouTube のポッドキャストの回・RSS の回）とライブを除き、同じ曲は最初の1つだけ。
pub fn video_ids_to_save(queue: &[Song]) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    queue
        .iter()
        .filter(|s| !s.is_live && !s.video_id.is_empty() && !s.video_id.starts_with(AUDIO_PREFIX))
        .filter(|s| s.kind.as_deref() != Some("MUSIC_VIDEO_TYPE_PODCAST_EPISODE"))
        .filter(|s| seen.insert(s.video_id.clone()))
        .map(|s| s.video_id.clone())
        .collect()
}

/// 名前の既定（キューの先頭の曲名のラジオ）。
pub fn default_title(queue: &[Song]) -> String {
    match queue.first() {
        Some(song) => format!("{}のラジオ", song.title),
        None => "ラジオ".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn song(id: &str) -> Song {
        Song { video_id: id.into(), title: format!("曲{id}"), artists: vec![], album: None, duration_seconds: None, thumbnail_url: None, kind: None, is_live: false }
    }

    #[test]
    fn keeps_order_and_drops_episodes_lives_and_duplicates() {
        let mut live = song("live");
        live.is_live = true;
        let mut episode = song("ep");
        episode.kind = Some("MUSIC_VIDEO_TYPE_PODCAST_EPISODE".into());
        let rss = song("ext:https://example.com/a.mp3");
        let queue = vec![song("a"), song("b"), live, song("a"), episode, rss, song("c")];
        assert_eq!(video_ids_to_save(&queue), vec!["a", "b", "c"]);
        assert_eq!(default_title(&queue), "曲aのラジオ");
        assert!(video_ids_to_save(&[]).is_empty());
    }
}
