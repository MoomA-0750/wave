//! スマートダウンロードの曲選び（ADR-0016 の 10・11・12。Windows の SmartDownloads と同じ）。3つの分類（いつも聴いている・好きそう・おすすめ）から
//! 1曲ずつ順番に取り、同じアーティストは全体で2曲まで、分類どうしで重なった曲は先に入った分類に入れる。足りない分類の分は、ほかの分類で埋める。
//! あわせて、この端末での再生回数と、ダウンロードに使う通信の決まり。

use std::collections::{HashMap, HashSet, VecDeque};

use serde::{Deserialize, Serialize};

use crate::music::models::{HomeItem, HomeShelf, PlaylistSummary, Song};

/// 選べる曲数と既定。
pub const COUNTS: [usize; 4] = [25, 50, 100, 200];
pub const DEFAULT_COUNT: usize = 50;
/// 「好きそう」のラジオの種にする「いつも聴いている」の上位の曲数。
pub const MAX_SEEDS: usize = 5;
pub const MAX_PER_ARTIST: usize = 2;
/// 選び直す間隔（1日1回）。
pub const REFRESH_INTERVAL_MS: i64 = 24 * 60 * 60 * 1000;
/// これより長い曲は選ばない（再生履歴に出る1時間の動画などを取らない）。長さが分からなければ選んでよい。
pub const MAX_DURATION_SECONDS: u32 = 15 * 60;

const KIND_SONG: &str = "MUSIC_VIDEO_TYPE_ATV";
const KIND_MUSIC_VIDEO: &str = "MUSIC_VIDEO_TYPE_OMV";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SmartCategory {
    /// いつも聴いている。
    Frequent,
    /// 好きそう（聴いたことのない曲）。
    Likely,
    /// おすすめ。
    Recommended,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct SmartTrack {
    pub song: Song,
    pub category: SmartCategory,
}

pub fn normalize_count(count: usize) -> usize {
    if COUNTS.contains(&count) { count } else { DEFAULT_COUNT }
}

/// 選び直す頃か。
pub fn is_due(refreshed_at_ms: i64, now_ms: i64) -> bool {
    refreshed_at_ms <= 0 || now_ms - refreshed_at_ms >= REFRESH_INTERVAL_MS || now_ms < refreshed_at_ms
}

/// 曲（音源・公式の MV）で、ライブでも長すぎもしないか。投稿者の動画は選ばない。種類・長さが分からなければ選んでよい。
fn usable(song: &Song) -> bool {
    let music = song.kind.as_deref().is_none_or(|k| k == KIND_SONG || k == KIND_MUSIC_VIDEO);
    !song.is_live && music && song.duration_seconds.is_none_or(|d| d <= MAX_DURATION_SECONDS)
}

/// この端末で曲を聴いた回数と、最後に聴いた時刻。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct PlayCount {
    pub song: Song,
    pub count: u32,
    pub last_played_ms: i64,
}

/// この端末での曲ごとの再生回数（2000 曲まで。超えたら最後に聴いたのが古い曲から忘れる）。
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct PlayCounts {
    pub entries: Vec<PlayCount>,
}

impl PlayCounts {
    pub const MAX_SONGS: usize = 2000;

    /// 1回聴いた（30秒か長さの半分を聴いたとき）。ライブ配信は数えない。
    pub fn record(&self, song: &Song, now_ms: i64) -> Self {
        if song.is_live {
            return self.clone();
        }
        let mut entries = self.entries.clone();
        match entries.iter_mut().find(|e| e.song.video_id == song.video_id) {
            Some(e) => {
                e.count += 1;
                e.last_played_ms = now_ms;
                e.song = song.clone();
            }
            None => entries.push(PlayCount { song: song.clone(), count: 1, last_played_ms: now_ms }),
        }
        if entries.len() > Self::MAX_SONGS {
            entries.sort_by_key(|e| std::cmp::Reverse(e.last_played_ms));
            entries.truncate(Self::MAX_SONGS);
        }
        Self { entries }
    }

    pub fn encode(&self) -> String {
        serde_json::to_string(self).expect("plain data always serializes")
    }

    pub fn decode(text: &str) -> Self {
        serde_json::from_str(text).unwrap_or_default()
    }
}

/// 1回と数えるまでに聴く長さ（再生履歴を送るのと同じ: 30秒、短い曲は長さの半分）。
pub fn listen_threshold_ms(duration_ms: Option<u64>) -> u64 {
    match duration_ms {
        Some(d) if d > 0 => 30_000.min(d / 2),
        _ => 30_000,
    }
}

/// 「いつも聴いている」の候補: この端末の再生回数と、YouTube の再生履歴（新しい順）に出る曲。回数（履歴に出た回も1回と数える）の多い順、
/// 同じなら新しい順（この端末で最後に聴いた時刻。履歴にだけある曲は履歴の並び）。
pub fn frequent(counts: &[PlayCount], history: &[Song]) -> Vec<Song> {
    let mut entries: HashMap<String, (Song, u32, i64, usize)> = HashMap::new();
    for c in counts.iter().filter(|c| usable(&c.song)) {
        entries.insert(c.song.video_id.clone(), (c.song.clone(), c.count, c.last_played_ms, usize::MAX));
    }
    for (rank, song) in history.iter().filter(|s| usable(s)).enumerate() {
        let rank = rank + 1;
        entries
            .entry(song.video_id.clone())
            .and_modify(|e| {
                e.1 += 1;
                e.3 = e.3.min(rank);
            })
            .or_insert((song.clone(), 1, 0, rank));
    }
    let mut list: Vec<_> = entries.into_values().collect();
    list.sort_by(|a, b| b.1.cmp(&a.1).then(b.2.cmp(&a.2)).then(a.3.cmp(&b.3)));
    list.into_iter().map(|e| e.0).collect()
}

/// 「好きそう」の候補: 種ごとのラジオから、聴いたことのある曲を除き、種ごとに順番に1曲ずつ。
pub fn likely(radios: &[Vec<Song>], known: &HashSet<String>) -> Vec<Song> {
    let mut queues: Vec<VecDeque<&Song>> = radios.iter().map(|r| r.iter().filter(|s| usable(s) && !known.contains(&s.video_id)).collect()).collect();
    let (mut result, mut seen) = (Vec::new(), HashSet::new());
    while queues.iter().any(|q| !q.is_empty()) {
        for queue in queues.iter_mut() {
            while let Some(song) = queue.pop_front() {
                if seen.insert(song.video_id.clone()) {
                    result.push(song.clone());
                    break;
                }
            }
        }
    }
    result
}

/// 「おすすめ」の候補: ホームの棚のうち、曲が並んだ棚（半分より多くが曲）の曲。
pub fn recommended(shelves: &[HomeShelf]) -> Vec<Song> {
    let mut seen = HashSet::new();
    shelves
        .iter()
        .filter(|s| !s.items.is_empty() && s.items.iter().filter(|i| matches!(i, HomeItem::Song(_))).count() * 2 > s.items.len())
        .flat_map(|s| s.items.iter())
        .filter_map(|i| match i {
            HomeItem::Song(song) => Some(song),
            _ => None,
        })
        .filter(|s| usable(s) && seen.insert(s.video_id.clone()))
        .cloned()
        .collect()
}

/// ホームの最初のミックス（RD で始まるプレイリスト。無ければ最初のプレイリスト）。「おすすめ」が足りないときに使う。
pub fn first_mix(shelves: &[HomeShelf]) -> Option<PlaylistSummary> {
    let playlists: Vec<&PlaylistSummary> = shelves
        .iter()
        .flat_map(|s| s.items.iter())
        .filter_map(|i| match i {
            HomeItem::Playlist(p) => Some(p),
            _ => None,
        })
        .collect();
    playlists.iter().find(|p| p.playlist_id.starts_with("RD")).or(playlists.first()).map(|p| (*p).clone())
}

/// アーティストの数え方（最初のアーティストの ID、無ければ名前）。アーティストの無い曲は数えない。
fn artist_key(song: &Song) -> Option<String> {
    song.artists.first().map(|a| a.browse_id.clone().unwrap_or_else(|| format!("name:{}", a.name.trim().to_lowercase())))
}

/// 3つの分類から1曲ずつ順番に、count 曲まで選ぶ。
pub fn select(frequent: &[Song], likely: &[Song], recommended: &[Song], count: usize) -> Vec<SmartTrack> {
    let sources = [(SmartCategory::Frequent, frequent), (SmartCategory::Likely, likely), (SmartCategory::Recommended, recommended)];
    let mut cursors = [0usize; 3];
    let mut chosen: Vec<SmartTrack> = Vec::new();
    let mut ids = HashSet::new();
    let mut per_artist: HashMap<String, usize> = HashMap::new();
    // 1周で1曲ずつ。取れる曲が残っていない分類は飛ばす（ほかの分類で埋まる）
    let mut progressed = true;
    while chosen.len() < count && progressed {
        progressed = false;
        for (i, (category, songs)) in sources.iter().enumerate() {
            if chosen.len() >= count {
                break;
            }
            while cursors[i] < songs.len() {
                let song = &songs[cursors[i]];
                cursors[i] += 1;
                if !usable(song) || ids.contains(&song.video_id) {
                    continue;
                }
                let artist = artist_key(song);
                if artist.as_ref().is_some_and(|a| per_artist.get(a).copied().unwrap_or(0) >= MAX_PER_ARTIST) {
                    continue;
                }
                ids.insert(song.video_id.clone());
                if let Some(a) = artist {
                    *per_artist.entry(a).or_default() += 1;
                }
                chosen.push(SmartTrack { song: song.clone(), category: *category });
                progressed = true;
                break;
            }
        }
    }
    chosen
}

/// ダウンロードに使う通信（ADR-0016 の 8）。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DownloadNetwork {
    /// 従量制でない通信だけ（既定）。
    #[default]
    Unmetered,
    /// どの通信でも。
    Any,
}

/// 今の通信で取ってよいか。
pub fn allows(setting: DownloadNetwork, connected: bool, metered: bool) -> bool {
    connected && (setting == DownloadNetwork::Any || !metered)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::music::models::{AlbumSummary, ArtistRef};

    fn s(id: &str, artist: &str) -> Song {
        Song {
            video_id: id.into(),
            title: id.into(),
            artists: vec![ArtistRef { name: artist.into(), browse_id: None }],
            album: None,
            duration_seconds: Some(200),
            thumbnail_url: None,
            kind: None,
            is_live: false,
        }
    }

    fn ids(songs: &[Song]) -> Vec<&str> {
        songs.iter().map(|s| s.video_id.as_str()).collect()
    }

    #[test]
    fn frequent_merges_counts_and_history() {
        let counts = [PlayCount { song: s("a", "x"), count: 3, last_played_ms: 10 }, PlayCount { song: s("b", "x"), count: 3, last_played_ms: 20 }, PlayCount { song: s("c", "x"), count: 1, last_played_ms: 30 }];
        // 履歴に出た d・e は1回と数え、c は 1+1 回になる
        assert_eq!(ids(&frequent(&counts, &[s("d", "x"), s("c", "x"), s("e", "x")])), ["b", "a", "c", "d", "e"]);
    }

    #[test]
    fn likely_takes_unheard_songs_from_each_seed_in_turn() {
        let radios = vec![vec![s("seed1", "x"), s("x1", "x"), s("x2", "x")], vec![s("y1", "x"), s("x1", "x"), s("y2", "x")]];
        assert_eq!(ids(&likely(&radios, &HashSet::from(["seed1".to_string()]))), ["x1", "y1", "x2", "y2"]);
    }

    #[test]
    fn recommended_comes_from_song_shelves_and_the_first_mix() {
        let song = HomeItem::Song(s("r1", "x"));
        let playlist = |id: &str| HomeItem::Playlist(PlaylistSummary { playlist_id: id.into(), title: id.into(), subtitle: None, thumbnail_url: None, editable: false });
        let shelves = vec![
            HomeShelf { title: "albums".into(), strapline: None, items: vec![HomeItem::Album(AlbumSummary { browse_id: "MP1".into(), title: "a".into(), subtitle: None, thumbnail_url: None }), song.clone()] },
            HomeShelf { title: "quick picks".into(), strapline: None, items: vec![song, HomeItem::Song(s("r2", "x"))] },
            HomeShelf { title: "mixes".into(), strapline: None, items: vec![playlist("PLx"), playlist("RDTMAK1")] },
        ];
        assert_eq!(ids(&recommended(&shelves)), ["r1", "r2"]);
        assert_eq!(first_mix(&shelves).unwrap().playlist_id, "RDTMAK1");
    }

    #[test]
    fn selection_takes_one_from_each_category_in_turn() {
        let picked = select(&[s("f1", "A"), s("f2", "B"), s("f3", "C")], &[s("l1", "D"), s("l2", "E")], &[s("r1", "F"), s("r2", "G")], 5);
        assert_eq!(picked.iter().map(|t| t.song.video_id.as_str()).collect::<Vec<_>>(), ["f1", "l1", "r1", "f2", "l2"]);
        assert_eq!(picked[2].category, SmartCategory::Recommended);
    }

    #[test]
    fn selection_caps_artists_dedups_and_fills_short_categories() {
        let mut live = s("live", "E");
        live.is_live = true;
        let picked = select(&[s("f1", "A"), s("f2", "A"), s("f3", "A"), s("f4", "B")], &[], &[s("f1", "A"), s("r1", "C"), s("r2", "D"), live], 10);
        assert_eq!(picked.iter().map(|t| t.song.video_id.as_str()).collect::<Vec<_>>(), ["f1", "r1", "f2", "r2", "f4"]);
        assert_eq!(picked[0].category, SmartCategory::Frequent);
    }

    #[test]
    fn long_songs_and_ordinary_videos_are_not_picked() {
        let mut hour = s("hour", "a");
        hour.duration_seconds = Some(3600);
        let mut unknown = s("unknown", "b");
        unknown.duration_seconds = None;
        let mut video = s("video", "c");
        video.kind = Some("MUSIC_VIDEO_TYPE_UGC".into());
        let mut mv = s("mv", "d");
        mv.kind = Some(KIND_MUSIC_VIDEO.into());
        let picked = select(&[hour.clone(), unknown, video, mv], &[], &[], 10);
        assert_eq!(picked.iter().map(|t| t.song.video_id.as_str()).collect::<Vec<_>>(), ["unknown", "mv"]);
        assert!(frequent(&[], &[hour]).is_empty());
    }

    #[test]
    fn refresh_counts_and_network_rules() {
        assert!(is_due(0, 100));
        assert!(!is_due(100, 100 + REFRESH_INTERVAL_MS - 1));
        assert!(is_due(100, 100 + REFRESH_INTERVAL_MS));
        assert_eq!(normalize_count(7), DEFAULT_COUNT);
        assert_eq!(normalize_count(200), 200);
        assert!(allows(DownloadNetwork::Unmetered, true, false));
        assert!(!allows(DownloadNetwork::Unmetered, true, true));
        assert!(allows(DownloadNetwork::Any, true, true));
        assert!(!allows(DownloadNetwork::Any, false, false));
        assert_eq!(listen_threshold_ms(Some(40_000)), 20_000);
        assert_eq!(listen_threshold_ms(Some(300_000)), 30_000);
        let counts = PlayCounts::default().record(&s("a", "x"), 1).record(&s("a", "x"), 2);
        assert_eq!(counts.entries[0].count, 2);
        assert_eq!(PlayCounts::decode(&counts.encode()), counts);
    }
}
