//! 一覧を流し終えたあとに、最後の曲から始まるラジオへつなぐ判断（Mac の QueueRadio・Android の QueueSession と同じ。決定 E4）。
//! 再生の部品から切り出して、試験で確かめられるようにしたもの。

use super::feed;
use super::models::Song;

/// 残りがこの件数以下になったら続きを取る（Android の FETCH_WHEN_REMAINING_AT_MOST と同じ）。
pub const FETCH_WHEN_REMAINING_AT_MOST: usize = 2;

/// 曲のラジオの playlistId（Android の radioPlaylistIdOf と同じ）。
pub fn playlist_id(seed: &str) -> String {
    format!("RDAMVM{seed}")
}

/// ラジオの playlistId から種の曲を戻す（ほかの端末から引き取ったラジオは種を持たないので）。
pub fn seed_of(playlist_id: &str) -> Option<&str> {
    playlist_id.strip_prefix("RDAMVM").filter(|s| !s.is_empty())
}

/// その曲からラジオを作れるか。ライブ配信は終わらずに流れ続け、RSS のエピソードは YouTube のラジオが作れない（ADR-0012）。
pub fn can_seed(song: &Song) -> bool {
    !song.is_live && !feed::is_external(&song.video_id)
}

/// 残りが少なくなって、続きを取る頃合いか。index は今の曲の位置。
pub fn near_end(count: usize, index: usize) -> bool {
    index < count && count - 1 - index <= FETCH_WHEN_REMAINING_AT_MOST
}

/// 一覧の終わりが近いときに、ラジオへ切り替えるなら種の曲の videoId を返す（Android の switchToRadio）。
/// すでにラジオのとき・一覧の残りをまだ読んでいるとき・全体リピート中・「次に流れる曲」を消して流れを止めたとき・
/// 最後の曲がライブ配信か RSS のエピソードのときは切り替えない。
pub fn switch_seed(queue: &[Song], index: usize, radio: bool, repeat_all: bool, rest_pending: bool, flow_stopped: bool) -> Option<String> {
    if radio || rest_pending || repeat_all || flow_stopped || !near_end(queue.len(), index) {
        return None;
    }
    queue.last().filter(|last| can_seed(last)).map(|last| last.video_id.clone())
}

/// 一覧を鳴らし始めるときの種の曲。出どころ（プレイリスト・アルバムなど）の無い一覧（検索の結果など）は、
/// 初めから最後の曲のラジオを続きにする（1曲だけならその曲）。出どころがあれば、終わりが近づいたときに切り替える（None）。
pub fn initial_seed(songs: &[Song], has_source: bool) -> Option<String> {
    if has_source && songs.len() > 1 {
        return None;
    }
    songs.last().filter(|last| can_seed(last)).map(|last| last.video_id.clone())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn song(id: &str) -> Song {
        Song { video_id: id.into(), title: id.into(), artists: vec![], album: None, duration_seconds: None, thumbnail_url: None, kind: None, is_live: false }
    }

    fn songs(n: usize) -> Vec<Song> {
        (0..n).map(|i| song(&format!("v{i}"))).collect()
    }

    #[test]
    fn near_end_is_the_last_three_songs() {
        assert!(!near_end(10, 6));
        assert!(near_end(10, 7));
        assert!(near_end(10, 9));
        assert!(!near_end(10, 10));
        assert!(near_end(1, 0));
    }

    #[test]
    fn a_playlist_switches_to_the_radio_of_its_last_song_near_the_end() {
        let queue = songs(5);
        assert_eq!(switch_seed(&queue, 1, false, false, false, false), None);
        assert_eq!(switch_seed(&queue, 2, false, false, false, false).as_deref(), Some("v4"));
        assert_eq!(switch_seed(&queue, 4, false, false, false, false).as_deref(), Some("v4"));
    }

    #[test]
    fn no_switch_while_radio_rest_pending_repeat_all_or_stopped() {
        let queue = songs(3);
        assert_eq!(switch_seed(&queue, 2, true, false, false, false), None);
        assert_eq!(switch_seed(&queue, 2, false, true, false, false), None);
        assert_eq!(switch_seed(&queue, 2, false, false, true, false), None);
        assert_eq!(switch_seed(&queue, 2, false, false, false, true), None);
    }

    #[test]
    fn live_and_rss_episodes_do_not_seed_a_radio() {
        let mut live = song("live");
        live.is_live = true;
        let episode = song(&feed::audio_video_id("https://example.com/a.mp3"));
        assert_eq!(switch_seed(&[song("a"), live], 1, false, false, false, false), None);
        assert_eq!(switch_seed(&[song("a"), episode.clone()], 1, false, false, false, false), None);
        assert_eq!(initial_seed(&[episode], false), None);
    }

    #[test]
    fn lists_without_a_source_start_with_the_radio_of_the_last_song() {
        assert_eq!(initial_seed(&songs(3), false).as_deref(), Some("v2"));
        assert_eq!(initial_seed(&songs(3), true), None);
        // 1曲だけなら、出どころがあってもその曲のラジオ
        assert_eq!(initial_seed(&songs(1), true).as_deref(), Some("v0"));
        assert_eq!(initial_seed(&[], false), None);
    }

    #[test]
    fn radio_playlist_ids_round_trip() {
        assert_eq!(playlist_id("abc"), "RDAMVMabc");
        assert_eq!(seed_of("RDAMVMabc"), Some("abc"));
        assert_eq!(seed_of("PL123"), None);
        assert_eq!(seed_of("RDAMVM"), None);
    }
}
