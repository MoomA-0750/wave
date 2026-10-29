//! 新作の見張り（Windows の ReleaseWatchState・Android と同じ）と、ホームの新作の棚の決まり（HomeShelves）。
//! アーティストごとに前に見たアルバム・動画の ID を覚え、増えたアルバム・シングルのうち今年のものを新作とする。
//! 初めて見るアーティストは知らせず、今年の作品だけを「最近の新作」として覚える。

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use regex::Regex;
use serde::{Deserialize, Serialize};

use super::models::{AlbumSummary, ArtistPage, HomeItem, HomeShelf};

const MAX_IDS_PER_ARTIST: usize = 300;
const MAX_RECENT: usize = 60;
pub const RECENT_MAX_AGE_MS: i64 = 45 * 24 * 60 * 60 * 1000;
/// ホームの YouTube Music の新作の棚の件数。
pub const NEW_RELEASES_COUNT: usize = 20;

static YEAR: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\b(19|20)\d\d\b").expect("valid regex"));

/// 登録したアーティストの新作（アルバム・シングル）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewRelease {
    pub artist_id: String,
    pub artist_name: String,
    pub title: String,
    pub album_browse_id: Option<String>,
    pub video_id: Option<String>,
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub found_at_epoch_ms: i64,
    /// 初めて見たときに、今年の作品として覚えたもの（知らせていない）。
    #[serde(default)]
    pub seeded: bool,
}

impl NewRelease {
    pub fn key(&self) -> &str {
        self.album_browse_id.as_deref().or(self.video_id.as_deref()).unwrap_or(&self.title)
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReleaseWatchState {
    pub seen: HashMap<String, HashSet<String>>,
    pub recent: Vec<NewRelease>,
}

impl ReleaseWatchState {
    /// アーティストのページを見た。新しく見つけた新作と、次の状態。
    pub fn observe(&self, artist_id: &str, page: &ArtistPage, now_epoch_ms: i64, year: Option<i32>) -> (Vec<NewRelease>, ReleaseWatchState) {
        let mut albums: Vec<&AlbumSummary> = Vec::new();
        for album in page.album_shelves.iter().flat_map(|s| &s.albums) {
            if !albums.iter().any(|a| a.browse_id == album.browse_id) {
                albums.push(album);
            }
        }
        let ids: Vec<String> = albums.iter().map(|a| format!("a:{}", a.browse_id)).chain(page.videos.iter().map(|v| format!("v:{}", v.video_id))).collect();
        // 棚が読めなかった（空）ときは覚えている分を消さない
        if ids.is_empty() {
            return (Vec::new(), self.clone());
        }
        let before = self.seen.get(artist_id);
        let merged: HashSet<String> = ids.iter().cloned().chain(before.into_iter().flatten().filter(|i| !ids.contains(i)).cloned()).take(MAX_IDS_PER_ARTIST).collect();
        let mut next = self.clone();
        next.seen.insert(artist_id.to_string(), merged);
        next.recent = listed(&self.recent, artist_id, page, year);
        let release = |a: &AlbumSummary, seeded: bool| NewRelease {
            artist_id: artist_id.to_string(),
            artist_name: page.name.clone(),
            title: a.title.clone(),
            album_browse_id: Some(a.browse_id.clone()),
            video_id: None,
            thumbnail_url: a.thumbnail_url.clone(),
            found_at_epoch_ms: now_epoch_ms,
            seeded,
        };
        let this_year = year.map(|y| y.to_string());
        let of_this_year = |a: &AlbumSummary| this_year.as_ref().is_none_or(|y| a.subtitle.as_deref().is_some_and(|s| s.contains(y.as_str())));
        let Some(before) = before else {
            if year.is_none() {
                return (Vec::new(), next);
            }
            let seeds = albums.iter().filter(|a| of_this_year(a)).map(|a| release(a, true)).collect();
            return (Vec::new(), next.with_recent(seeds));
        };
        // 新作は初めて見た今年の作品に限る（棚の中身は入れ替わるので、古い作品が入り直しただけで知らせない）。動画は覚えるだけ
        let fresh: Vec<NewRelease> = albums.iter().filter(|a| !before.contains(&format!("a:{}", a.browse_id)) && of_this_year(a)).map(|a| release(a, false)).collect();
        let next = next.with_recent(fresh.clone());
        (fresh, next)
    }

    fn with_recent(mut self, added: Vec<NewRelease>) -> Self {
        if added.is_empty() {
            return self;
        }
        let mut all: Vec<NewRelease> = Vec::new();
        for r in added.into_iter().chain(self.recent.drain(..)) {
            if !all.iter().any(|x| x.key() == r.key()) {
                all.push(r);
            }
        }
        let (fresh, seeded): (Vec<_>, Vec<_>) = all.into_iter().partition(|r| !r.seeded);
        self.recent = fresh.into_iter().chain(seeded).take(MAX_RECENT).collect();
        self
    }

    /// 登録をやめたアーティストの記録を捨てる。
    pub fn retain(&self, artist_ids: &HashSet<String>) -> Self {
        Self {
            seen: self.seen.iter().filter(|(k, _)| artist_ids.contains(*k)).map(|(k, v)| (k.clone(), v.clone())).collect(),
            recent: self.recent.iter().filter(|r| artist_ids.contains(&r.artist_id)).cloned().collect(),
        }
    }

    /// 古い新作（と、前に覚えた動画）を外す。
    pub fn expire(&self, now_epoch_ms: i64) -> Self {
        Self { seen: self.seen.clone(), recent: self.recent.iter().filter(|r| now_epoch_ms - r.found_at_epoch_ms <= RECENT_MAX_AGE_MS && r.video_id.is_none()).cloned().collect() }
    }
}

/// アーティストのページから消えた新作を外す（同じ名前の別人の作品が後で外されることがある）。棚がどれも今年より前の作品まで並べているのに無いものだけ。
pub fn listed(releases: &[NewRelease], artist_id: &str, page: &ArtistPage, year: Option<i32>) -> Vec<NewRelease> {
    let shelves: Vec<_> = page.album_shelves.iter().filter(|s| !s.albums.is_empty()).collect();
    let Some(year) = year else { return releases.to_vec() };
    if shelves.is_empty() || !shelves.iter().all(|s| s.albums.iter().any(|a| year_of(a.subtitle.as_deref()).is_some_and(|y| y < year))) {
        return releases.to_vec();
    }
    let ids: HashSet<&str> = shelves.iter().flat_map(|s| &s.albums).map(|a| a.browse_id.as_str()).collect();
    releases.iter().filter(|r| r.artist_id != artist_id || r.album_browse_id.as_deref().is_none_or(|id| ids.contains(id))).cloned().collect()
}

/// 補足（「Single • 2023」など）の年。
fn year_of(subtitle: Option<&str>) -> Option<i32> {
    YEAR.find_iter(subtitle?).last().and_then(|m| m.as_str().parse().ok())
}

/// 棚に出す並び: アーティストごとに per_artist 件まで、アーティストが交互になるように（見つけた新作を先に）。
pub fn diversified(releases: &[NewRelease], per_artist: usize) -> Vec<NewRelease> {
    fn round_robin(items: Vec<&NewRelease>, per: usize) -> Vec<NewRelease> {
        let mut groups: Vec<(String, Vec<&NewRelease>)> = Vec::new();
        for r in items {
            match groups.iter_mut().find(|(id, _)| *id == r.artist_id) {
                Some((_, g)) => g.push(r),
                None => groups.push((r.artist_id.clone(), vec![r])),
            }
        }
        (0..per).flat_map(|round| groups.iter().filter_map(move |(_, g)| g.get(round).map(|r| (*r).clone()))).collect()
    }
    let mut out = round_robin(releases.iter().filter(|r| !r.seeded).collect(), per_artist);
    out.extend(round_robin(releases.iter().filter(|r| r.seeded).collect(), per_artist));
    out
}

/// YouTube Music の新作の棚が、ホームに来ている新作の棚と重なるか（アルバムが3枚以上同じ棚にある）。重なるなら出さない。
pub fn duplicates_new_releases(releases: &[AlbumSummary], shelves: &[HomeShelf]) -> bool {
    let ids: HashSet<&str> = releases.iter().take(NEW_RELEASES_COUNT).map(|a| a.browse_id.as_str()).collect();
    !ids.is_empty() && shelves.iter().any(|s| s.items.iter().filter(|i| matches!(i, HomeItem::Album(a) if ids.contains(a.browse_id.as_str()))).count() >= 3)
}

/// YouTube Music の新作の棚。新作が無ければ None。
pub fn new_releases_shelf(title: &str, releases: Vec<AlbumSummary>) -> Option<HomeShelf> {
    (!releases.is_empty()).then(|| HomeShelf { title: title.into(), strapline: None, items: releases.into_iter().take(NEW_RELEASES_COUNT).map(HomeItem::Album).collect() })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::music::models::{AlbumShelf, Song};

    fn page(albums: &[&str], videos: &[&str]) -> ArtistPage {
        ArtistPage {
            name: "A".into(),
            thumbnail_url: None,
            top_songs: Vec::new(),
            all_songs_playlist_id: None,
            album_shelves: vec![AlbumShelf {
                title: "Albums".into(),
                albums: albums
                    .iter()
                    .map(|id| AlbumSummary {
                        browse_id: id.to_string(),
                        title: format!("title-{id}"),
                        subtitle: Some(if id.ends_with("new") { "Single • 2026" } else { "Album • 2019" }.into()),
                        thumbnail_url: None,
                    })
                    .collect(),
            }],
            videos: videos
                .iter()
                .map(|v| Song { video_id: v.to_string(), title: format!("video-{v}"), artists: vec![], album: None, duration_seconds: None, thumbnail_url: None, kind: None, is_live: false })
                .collect(),
        }
    }

    fn set(items: &[&str]) -> HashSet<String> {
        items.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn the_first_look_only_remembers_and_seeds_this_year() {
        let (fresh, state) = ReleaseWatchState::default().observe("UC1", &page(&["MPold", "MPnew"], &["v1"]), 1, Some(2026));
        assert!(fresh.is_empty());
        assert_eq!(state.seen["UC1"], set(&["a:MPold", "a:MPnew", "v:v1"]));
        assert_eq!(state.recent.len(), 1);
        assert!(state.recent[0].seeded);
    }

    #[test]
    fn only_new_albums_of_this_year_are_reported_newest_first() {
        let (_, first) = ReleaseWatchState::default().observe("UC1", &page(&["MPold", "MPnew"], &["v1"]), 1, Some(2026));
        let (fresh, second) = first.observe("UC1", &page(&["MP3new", "MP2", "MPold", "MPnew"], &["v2", "v1"]), 2, Some(2026));
        assert_eq!(fresh.iter().map(|r| r.album_browse_id.clone().unwrap()).collect::<Vec<_>>(), ["MP3new"]);
        assert!(second.seen["UC1"].contains("a:MP2") && second.seen["UC1"].contains("v:v2"));
        assert_eq!(second.recent.iter().map(|r| r.key()).collect::<Vec<_>>(), ["MP3new", "MPnew"]);
        let decoded: ReleaseWatchState = serde_json::from_str(&serde_json::to_string(&second).unwrap()).unwrap();
        assert_eq!(decoded, second);
    }

    #[test]
    fn an_empty_page_does_not_forget() {
        let (_, first) = ReleaseWatchState::default().observe("UC1", &page(&["MP1"], &[]), 1, None);
        let (fresh, second) = first.observe("UC1", &page(&[], &[]), 2, None);
        assert!(fresh.is_empty());
        assert_eq!(first, second);
    }

    #[test]
    fn diversified_takes_two_per_artist_in_turns() {
        let r = |a: &str, t: &str| NewRelease {
            artist_id: a.into(),
            artist_name: a.into(),
            title: t.into(),
            album_browse_id: Some(t.into()),
            video_id: None,
            thumbnail_url: None,
            found_at_epoch_ms: 0,
            seeded: false,
        };
        let releases = vec![r("A", "a1"), r("A", "a2"), r("A", "a3"), r("B", "b1")];
        assert_eq!(diversified(&releases, 2).iter().map(|r| r.title.as_str()).collect::<Vec<_>>(), ["a1", "b1", "a2"]);
    }

    #[test]
    fn releases_that_leave_the_artist_page_are_dropped() {
        let (_, first) = ReleaseWatchState::default().observe("UC1", &page(&["MPold"], &[]), 1, Some(2026));
        let (_, second) = first.observe("UC1", &page(&["MPothernew", "MPold"], &[]), 2, Some(2026));
        assert_eq!(second.recent.iter().map(|r| r.key()).collect::<Vec<_>>(), ["MPothernew"]);
        let (_, third) = second.observe("UC1", &page(&["MPold"], &[]), 3, Some(2026));
        assert!(third.recent.is_empty());
        assert_eq!(listed(&second.recent, "UC1", &page(&["MP4new", "MP5new"], &[]), Some(2026)), second.recent);
        assert_eq!(listed(&second.recent, "UC2", &page(&["MPold"], &[]), Some(2026)), second.recent);
    }
}
