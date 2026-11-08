//! wave-core の値を、QML がそのまま使える JSON に直す（画面の部品は JSON.parse した値を model にする）。
//! 曲は "song" に元の値（Song を serde で書いたもの）を持たせ、鳴らすときはそれをそのまま Rust に返す。

use serde_json::{Value, json};
use wave_core::music::models::{AlbumPage, AlbumSummary, ArtistPage, ArtistSummary, HomeItem, HomeShelf, PlaylistPage, PlaylistSummary, Song};
use wave_core::music::podcast::{PodcastEpisode, PodcastSummary};
use wave_desktop::art::sized;

/// 一覧の小さな画像の大きさ（px）。
const THUMB: u32 = 120;
/// カード・ページの見出しの画像の大きさ（px）。
const CARD: u32 = 320;
/// 再生中の大きな画像（px）。
pub const LARGE: u32 = 544;

pub fn image(url: Option<&str>, px: u32) -> String {
    url.filter(|u| u.starts_with("https://")).map(|u| sized(u, px)).unwrap_or_default()
}

/// 秒を「3:45」「1:02:03」に。
pub fn format_seconds(seconds: u32) -> String {
    let (h, m, s) = (seconds / 3600, seconds / 60 % 60, seconds % 60);
    if h > 0 { format!("{h}:{m:02}:{s:02}") } else { format!("{m}:{s:02}") }
}

pub fn song(s: &Song) -> Value {
    json!({
        "kind": "song",
        "id": s.video_id,
        "title": s.title,
        "subtitle": s.subtitle(),
        "artists": s.artists_text(),
        "artistId": s.artists.iter().find_map(|a| a.browse_id.clone()).unwrap_or_default(),
        "album": s.album.as_ref().map(|a| a.title.clone()).unwrap_or_default(),
        "albumId": s.album.as_ref().and_then(|a| a.browse_id.clone()).unwrap_or_default(),
        "thumb": image(s.thumbnail_url.as_deref(), THUMB),
        "art": image(s.thumbnail_url.as_deref(), CARD),
        "duration": s.duration_seconds.map(format_seconds).unwrap_or_default(),
        "live": s.is_live,
        "round": false,
        "song": s,
    })
}

pub fn songs(list: &[Song]) -> Vec<Value> {
    list.iter().map(song).collect()
}

pub fn album(a: &AlbumSummary) -> Value {
    json!({ "kind": "album", "id": a.browse_id, "title": a.title, "subtitle": a.subtitle.clone().unwrap_or_default(), "thumb": image(a.thumbnail_url.as_deref(), THUMB), "art": image(a.thumbnail_url.as_deref(), CARD), "round": false })
}

pub fn artist(a: &ArtistSummary) -> Value {
    json!({ "kind": "artist", "id": a.browse_id, "title": a.name, "subtitle": a.subtitle.clone().unwrap_or_default(), "thumb": image(a.thumbnail_url.as_deref(), THUMB), "art": image(a.thumbnail_url.as_deref(), CARD), "round": true })
}

pub fn playlist(p: &PlaylistSummary) -> Value {
    json!({ "kind": "playlist", "id": p.playlist_id, "title": p.title, "subtitle": p.subtitle.clone().unwrap_or_default(), "thumb": image(p.thumbnail_url.as_deref(), THUMB), "art": image(p.thumbnail_url.as_deref(), CARD), "round": false, "editable": p.editable })
}

pub fn podcast(p: &PodcastSummary) -> Value {
    json!({ "kind": "podcast", "id": p.browse_id, "title": p.title, "subtitle": p.author.clone().unwrap_or_default(), "thumb": image(p.thumbnail_url.as_deref(), THUMB), "art": image(p.thumbnail_url.as_deref(), CARD), "round": false })
}

pub fn episode(e: &PodcastEpisode) -> Value {
    let mut value = song(&e.song);
    value["subtitle"] = Value::String(e.subtitle());
    value["duration"] = Value::String(e.duration_text.clone().unwrap_or_default());
    value
}

pub fn home_item(item: &HomeItem) -> Value {
    match item {
        HomeItem::Song(s) => song(s),
        HomeItem::Album(a) => album(a),
        HomeItem::Playlist(p) => playlist(p),
        HomeItem::Artist(a) => artist(a),
    }
}

pub fn shelf(title: &str, strapline: Option<&str>, items: &[HomeItem]) -> Value {
    json!({ "title": title, "strapline": strapline.unwrap_or_default(), "items": items.iter().map(home_item).collect::<Vec<_>>() })
}

pub fn shelves(list: &[HomeShelf]) -> Vec<Value> {
    list.iter().map(|s| shelf(&s.title, s.strapline.as_deref(), &s.items)).collect()
}

pub fn album_page(page: &AlbumPage) -> Value {
    json!({
        "kind": "album",
        "id": page.browse_id,
        "title": page.title,
        "subtitle": page.subtitle.clone().unwrap_or_default(),
        "artists": page.artists.iter().map(|a| json!({ "name": a.name, "id": a.browse_id.clone().unwrap_or_default() })).collect::<Vec<_>>(),
        "art": image(page.thumbnail_url.as_deref(), LARGE),
        "playlistId": page.playlist_id.clone().unwrap_or_default(),
        "songs": songs(&page.songs),
        "continuation": "",
        "editable": false,
    })
}

pub fn playlist_page(id: &str, page: &PlaylistPage) -> Value {
    let mut rows = songs(&page.songs);
    for (row, set_id) in rows.iter_mut().zip(page.set_video_ids.iter()) {
        row["setVideoId"] = Value::String(set_id.clone().unwrap_or_default());
    }
    json!({
        "kind": "playlist",
        "id": id,
        "title": page.title.clone().unwrap_or_default(),
        "subtitle": page.subtitle.clone().unwrap_or_default(),
        "artists": [],
        "art": image(page.thumbnail_url.as_deref(), LARGE),
        "playlistId": id,
        "songs": rows,
        "continuation": page.continuation.clone().unwrap_or_default(),
        "editable": page.editable,
    })
}

pub fn artist_page(id: &str, page: &ArtistPage) -> Value {
    json!({
        "kind": "artist",
        "id": id,
        "title": page.name,
        "art": image(page.thumbnail_url.as_deref(), LARGE),
        "topSongs": songs(&page.top_songs),
        "allSongsPlaylistId": page.all_songs_playlist_id.clone().unwrap_or_default(),
        "albumShelves": page.album_shelves.iter().map(|s| json!({ "title": s.title, "strapline": "", "items": s.albums.iter().map(album).collect::<Vec<_>>() })).collect::<Vec<_>>(),
        "videos": songs(&page.videos),
        "channelId": page.channel_id.clone().unwrap_or_default(),
        "subscribed": match page.subscribed { Some(true) => 1, Some(false) => 0, None => -1 },
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use wave_core::music::models::{AlbumRef, ArtistRef};

    fn sample() -> Song {
        Song {
            video_id: "abc".into(),
            title: "曲".into(),
            artists: vec![ArtistRef { name: "歌う人".into(), browse_id: Some("UC1".into()) }],
            album: Some(AlbumRef { title: "盤".into(), browse_id: Some("MPRE1".into()) }),
            duration_seconds: Some(225),
            thumbnail_url: Some("https://lh3.googleusercontent.com/x=w60-h60-l90-rj".into()),
            kind: None,
            is_live: false,
        }
    }

    #[test]
    fn song_round_trips_for_playing() {
        let value = song(&sample());
        assert_eq!(value["duration"], "3:45");
        assert_eq!(value["subtitle"], "歌う人 • 盤");
        assert_eq!(value["thumb"], "https://lh3.googleusercontent.com/x=w120-h120-l90-rj");
        // QML から返ってきた "song" をそのまま読める
        let back: Song = serde_json::from_value(value["song"].clone()).unwrap();
        assert_eq!(back, sample());
    }

    #[test]
    fn formats_long_durations() {
        assert_eq!(format_seconds(59), "0:59");
        assert_eq!(format_seconds(3723), "1:02:03");
    }

    #[test]
    fn images_need_https() {
        assert_eq!(image(Some("http://x/y.jpg"), 120), "");
        assert_eq!(image(None, 120), "");
    }
}
