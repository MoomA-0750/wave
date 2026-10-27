//! InnerTube の応答（JSON）からモデルへの純粋な変換（Windows の MusicParsers・Android の MusicParsers と同じ規則）。
//! 形が変わっても落とさず、取れない項目は飛ばす。

use std::sync::LazyLock;

use regex::Regex;
use serde_json::Value;

use super::models::*;
use crate::json::{JsonPath, find_all};

const SEPARATOR: &str = " • ";
const ARTIST_PAGE_TYPE: &str = "MUSIC_PAGE_TYPE_ARTIST";
const LIBRARY_ARTIST_PAGE_TYPE: &str = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST";
const LIBRARY_ARTIST_PREFIX: &str = "MPLA";
const PODCAST_PAGE_TYPE: &str = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE";
const EDIT_ICON: &str = "EDIT";
const GREY_OUT: &str = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT";
const MUSIC_CONFIG: [&str; 4] = ["watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType"];

pub fn search_songs(root: &Value) -> SongPage {
    match search_shelf(root) {
        Some(shelf) => SongPage { songs: shelf_songs(shelf), continuation: next_continuation(shelf) },
        None => SongPage { songs: Vec::new(), continuation: None },
    }
}

fn search_shelf(root: &Value) -> Option<&Value> {
    root.path(&["continuationContents", "musicShelfContinuation"]).or_else(|| {
        root.path(&["contents", "tabbedSearchResultsRenderer", "tabs", "0", "tabRenderer", "content", "sectionListRenderer", "contents"])
            .items()
            .into_iter()
            .find_map(|i| i.path(&["musicShelfRenderer"]))
    })
}

/// ホーム（FEmusic_home）と気分・ジャンルのページ。棚ごとに読める項目だけを取る。
pub fn home_page(root: &Value) -> HomePage {
    let list = root
        .path(&["continuationContents", "sectionListContinuation"])
        .or_else(|| root.path(&["contents", "singleColumnBrowseResultsRenderer", "tabs", "0", "tabRenderer", "content", "sectionListRenderer"]));
    let Some(list) = list else {
        return HomePage { shelves: Vec::new(), continuation: None, chips: Vec::new() };
    };
    let shelves = list
        .path(&["contents"])
        .items()
        .into_iter()
        .filter_map(|section| {
            let carousel = section.path(&["musicCarouselShelfRenderer"])?;
            let header = carousel.path(&["header", "musicCarouselShelfBasicHeaderRenderer"]);
            let items: Vec<HomeItem> = carousel.path(&["contents"]).items().into_iter().filter_map(home_item_of).collect();
            if items.is_empty() {
                return None;
            }
            Some(HomeShelf {
                title: text_of(header.path(&["title"])).unwrap_or_default(),
                strapline: text_of(header.path(&["strapline"])),
                items,
            })
        })
        .collect();
    let continuation = list
        .path(&["continuations"])
        .items()
        .into_iter()
        .find_map(|c| c.path(&["nextContinuationData", "continuation"]).str_().map(String::from));
    let chips = list
        .path(&["header"])
        .map(|h| find_all(h, "chipCloudChipRenderer"))
        .unwrap_or_default()
        .into_iter()
        .filter_map(|chip| {
            let browse = chip.path(&["navigationEndpoint", "browseEndpoint"]);
            if browse.path(&["browseId"]).str_() != Some("FEmusic_home") {
                return None;
            }
            Some(HomeChip { title: text_of(chip.path(&["text"]))?, params: browse.path(&["params"]).str_()?.to_string() })
        })
        .collect();
    HomePage { shelves, continuation, chips }
}

fn home_item_of(card: &Value) -> Option<HomeItem> {
    if let Some(row) = card.path(&["musicResponsiveListItemRenderer"]) {
        if let Some(album) = album_row(row) {
            return Some(HomeItem::Album(album));
        }
        if let Some(artist) = artist_row(row) {
            return Some(HomeItem::Artist(artist));
        }
        return responsive_list_item_song(row).map(HomeItem::Song);
    }
    let item = card.path(&["musicTwoRowItemRenderer"])?;
    let title = text_of(item.path(&["title"]))?;
    let subtitle_runs = item.path(&["subtitle", "runs"]).items();
    let subtitle = runs_text(&subtitle_runs);
    let thumbnail = last_thumbnail_url(item.path(&["thumbnailRenderer"]));
    let endpoint = item.path(&["navigationEndpoint"]);
    if let Some(video_id) = endpoint.path(&["watchEndpoint", "videoId"]).str_() {
        return Some(HomeItem::Song(Song {
            video_id: video_id.to_string(),
            title,
            artists: artists_of(&subtitle_runs),
            album: album_of(&subtitle_runs),
            duration_seconds: None,
            thumbnail_url: thumbnail,
            kind: None,
            is_live: false,
        }));
    }
    let browse = endpoint.path(&["browseEndpoint"]);
    let browse_id = browse.path(&["browseId"]).str_()?.to_string();
    match page_type_of_browse(browse) {
        Some("MUSIC_PAGE_TYPE_ALBUM") => Some(HomeItem::Album(AlbumSummary { browse_id, title, subtitle, thumbnail_url: thumbnail })),
        Some(ARTIST_PAGE_TYPE) => Some(HomeItem::Artist(ArtistSummary { browse_id, name: title, subtitle, thumbnail_url: thumbnail })),
        Some("MUSIC_PAGE_TYPE_PLAYLIST") => Some(HomeItem::Playlist(PlaylistSummary {
            playlist_id: remove_vl(&browse_id),
            title,
            subtitle,
            thumbnail_url: thumbnail,
            editable: menu_icons(item).iter().any(|i| i == EDIT_ICON),
        })),
        _ => None,
    }
}

fn album_row(item: &Value) -> Option<AlbumSummary> {
    let browse = item.path(&["navigationEndpoint", "browseEndpoint"]);
    if page_type_of_browse(browse) != Some("MUSIC_PAGE_TYPE_ALBUM") {
        return None;
    }
    let columns = flex_column_runs(item);
    Some(AlbumSummary {
        browse_id: browse.path(&["browseId"]).str_()?.to_string(),
        title: runs_text(columns.first()?)?,
        subtitle: runs_text(&columns.iter().skip(1).flatten().copied().collect::<Vec<_>>()),
        thumbnail_url: last_thumbnail_url(item.path(&["thumbnail"])),
    })
}

fn artist_row(item: &Value) -> Option<ArtistSummary> {
    let browse = item.path(&["navigationEndpoint", "browseEndpoint"]);
    let browse_id = browse.path(&["browseId"]).str_()?;
    let artist_id = match page_type_of_browse(browse) {
        Some(ARTIST_PAGE_TYPE) => browse_id,
        // ライブラリのアーティスト（MPLA + チャンネルの ID）。アーティストのページはチャンネルの ID で開く
        Some(LIBRARY_ARTIST_PAGE_TYPE) => browse_id.strip_prefix(LIBRARY_ARTIST_PREFIX)?,
        _ => return None,
    };
    let columns = flex_column_runs(item);
    Some(ArtistSummary {
        browse_id: artist_id.to_string(),
        name: runs_text(columns.first()?)?,
        subtitle: runs_text(&columns.iter().skip(1).flatten().copied().collect::<Vec<_>>()),
        thumbnail_url: last_thumbnail_url(item.path(&["thumbnail"])),
    })
}

/// カードのメニューにあるアイコンの種類（EDIT があれば自分のプレイリスト）。
fn menu_icons(card: &Value) -> Vec<String> {
    fn walk(node: &Value, icons: &mut Vec<String>) {
        match node {
            Value::Object(map) => {
                for (name, value) in map {
                    match value.as_str() {
                        Some(icon) if name == "iconType" => icons.push(icon.to_string()),
                        _ => walk(value, icons),
                    }
                }
            }
            Value::Array(list) => list.iter().for_each(|v| walk(v, icons)),
            _ => {}
        }
    }
    let mut icons = Vec::new();
    if let Some(menu) = card.path(&["menu"]) {
        walk(menu, &mut icons);
    }
    icons
}

/// 検索・プレイリストの行（musicResponsiveListItemRenderer）。
pub fn responsive_list_item_song(item: &Value) -> Option<Song> {
    // 地域・権利の都合で再生できない行（グレーアウト）は除く
    if item.path(&["musicItemRendererDisplayPolicy"]).str_() == Some(GREY_OUT) {
        return None;
    }
    let columns = flex_column_runs(item);
    let empty = Vec::new();
    let title_runs = columns.first().unwrap_or(&empty);
    let play = item.path(&["overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint"]);
    let video_id = item
        .path(&["playlistItemData", "videoId"])
        .str_()
        .or_else(|| title_runs.iter().find_map(|r| r.path(&["navigationEndpoint", "watchEndpoint", "videoId"]).str_()))
        .or_else(|| play.path(&["watchEndpoint", "videoId"]).str_())?;
    let title = runs_text(title_runs)?;
    let detail_runs: Vec<&Value> = columns.iter().skip(1).flatten().copied().collect();
    // ライブ配信には「ライブ」のバッジが付き、長さが無い（文字は言語に依るので、バッジの種類で見分ける）
    let live = item.path(&["badges"]).items().iter().any(|b| b.get("liveBadgeRenderer").is_some());
    let fixed_runs: Vec<&Value> = item
        .path(&["fixedColumns"])
        .items()
        .into_iter()
        .flat_map(|c| c.path(&["musicResponsiveListItemFixedColumnRenderer", "text", "runs"]).items())
        .collect();
    let kind = title_runs
        .iter()
        .find_map(|r| r.path(&["navigationEndpoint"]).path(&MUSIC_CONFIG).str_())
        .or_else(|| play.path(&MUSIC_CONFIG).str_());
    Some(Song {
        video_id: video_id.to_string(),
        title,
        artists: artists_of(&detail_runs),
        album: album_of(&detail_runs),
        duration_seconds: if live { None } else { duration_of(&fixed_runs).or_else(|| duration_of(&detail_runs)) },
        thumbnail_url: last_thumbnail_url(item.path(&["thumbnail"])),
        kind: kind.map(String::from),
        is_live: live,
    })
}

fn shelf_songs(shelf: &Value) -> Vec<Song> {
    shelf
        .path(&["contents"])
        .items()
        .into_iter()
        .filter_map(|i| i.path(&["musicResponsiveListItemRenderer"]))
        .filter_map(responsive_list_item_song)
        .collect()
}

fn flex_column_runs(item: &Value) -> Vec<Vec<&Value>> {
    item.path(&["flexColumns"])
        .items()
        .into_iter()
        .map(|c| c.path(&["musicResponsiveListItemFlexColumnRenderer", "text", "runs"]).items())
        .collect()
}

/// アーティストの runs。ページ種別が付いていればそれを、無ければ区切り（" • "）の前をアーティストとみなす。
fn artists_of(runs: &[&Value]) -> Vec<ArtistRef> {
    let typed: Vec<ArtistRef> = runs
        .iter()
        .filter(|r| matches!(page_type_of(r), Some(ARTIST_PAGE_TYPE | "MUSIC_PAGE_TYPE_USER_CHANNEL")))
        .filter_map(|run| {
            // チャンネル（USER_CHANNEL）はアーティストのページとして開けないので、名前だけにする
            let browse_id = (page_type_of(run) == Some(ARTIST_PAGE_TYPE))
                .then(|| run.path(&["navigationEndpoint", "browseEndpoint", "browseId"]).str_().map(String::from))
                .flatten();
            Some(ArtistRef { name: run.path(&["text"]).str_()?.to_string(), browse_id })
        })
        .collect();
    if !typed.is_empty() {
        return typed;
    }
    // ポッドキャストのエピソードは、番組をアーティストの代わりにする
    if let Some(show) = runs.iter().find(|r| page_type_of(r) == Some(PODCAST_PAGE_TYPE)).and_then(|r| r.path(&["text"]).str_()) {
        return vec![ArtistRef { name: show.to_string(), browse_id: None }];
    }
    runs.iter()
        .map_while(|r| r.path(&["text"]).str_().filter(|t| *t != SEPARATOR))
        .filter(|t| !t.trim().is_empty() && *t != ", " && *t != " & " && parse_duration(t).is_none())
        .map(|t| ArtistRef { name: t.to_string(), browse_id: None })
        .collect()
}

fn album_of(runs: &[&Value]) -> Option<AlbumRef> {
    let run = runs.iter().find(|r| page_type_of(r) == Some("MUSIC_PAGE_TYPE_ALBUM"))?;
    Some(AlbumRef {
        title: run.path(&["text"]).str_()?.to_string(),
        browse_id: run.path(&["navigationEndpoint", "browseEndpoint", "browseId"]).str_().map(String::from),
    })
}

fn page_type_of(run: &Value) -> Option<&str> {
    page_type_of_browse(run.path(&["navigationEndpoint", "browseEndpoint"]))
}

fn page_type_of_browse(browse: Option<&Value>) -> Option<&str> {
    browse.path(&["browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType"]).str_()
}

fn next_continuation(shelf: &Value) -> Option<String> {
    shelf
        .path(&["continuations"])
        .items()
        .into_iter()
        .find_map(|c| c.path(&["nextContinuationData", "continuation"]).str_())
        .or_else(|| {
            shelf.path(&["contents"]).items().last().and_then(|last| {
                last.path(&["continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token"]).str_()
            })
        })
        .map(String::from)
}

fn text_of(node: Option<&Value>) -> Option<String> {
    runs_text(&node.path(&["runs"]).items())
}

fn runs_text(runs: &[&Value]) -> Option<String> {
    let text: String = runs.iter().filter_map(|r| r.path(&["text"]).str_()).collect();
    (!text.trim().is_empty()).then_some(text)
}

fn last_thumbnail_url(node: Option<&Value>) -> Option<String> {
    node.path(&["musicThumbnailRenderer", "thumbnail", "thumbnails"])
        .items()
        .last()
        .and_then(|t| t.path(&["url"]).str_())
        .map(String::from)
}

fn duration_of(runs: &[&Value]) -> Option<u32> {
    runs.iter().find_map(|r| r.path(&["text"]).str_().and_then(parse_duration))
}

fn remove_vl(id: &str) -> String {
    id.strip_prefix("VL").unwrap_or(id).to_string()
}

static DURATION: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{1,2}(:\d{2}){1,2}$").unwrap());

/// "4:22" や "1:02:03" を秒に。それ以外は None。
pub fn parse_duration(text: &str) -> Option<u32> {
    if !DURATION.is_match(text) {
        return None;
    }
    text.split(':').try_fold(0u32, |total, part| Some(total * 60 + part.parse::<u32>().ok()?))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> Value {
        // 手本の応答は Android の core と共通（core/src/desktopTest/resources/fixtures）
        let path = format!("{}/../../core/src/desktopTest/resources/fixtures/{name}.json", env!("CARGO_MANIFEST_DIR"));
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    #[test]
    fn search_songs_first_page() {
        let page = search_songs(&fixture("search_songs"));
        assert_eq!(page.songs.len(), 3);
        let song = &page.songs[0];
        assert!(!song.video_id.trim().is_empty());
        assert!(!song.artists.is_empty());
        assert!(song.album.is_some());
        assert!(song.duration_seconds.is_some());
        assert!(song.thumbnail_url.as_deref().unwrap().starts_with("https://"));
        assert!(page.continuation.is_some());
    }

    #[test]
    fn search_songs_continuation_page() {
        let page = search_songs(&fixture("search_songs_continuation"));
        assert_eq!(page.songs.len(), 3);
        assert!(page.songs.iter().all(|s| !s.artists.is_empty() && s.duration_seconds.is_some()));
        assert!(page.continuation.is_some());
    }

    #[test]
    fn home_page_shelves_and_chips() {
        let page = home_page(&fixture("browse_home"));
        assert_eq!(page.shelves.len(), 2);
        for shelf in &page.shelves {
            assert_eq!(shelf.items.len(), 10);
            assert!(shelf.items.iter().all(|i| matches!(i, HomeItem::Playlist(_))));
        }
        let HomeItem::Playlist(first) = &page.shelves[0].items[0] else { unreachable!() };
        assert!(!first.playlist_id.starts_with("VL"));
        assert!(page.continuation.is_some());
        assert_eq!(page.chips.len(), 10);
        assert_eq!(page.chips[0].title, "Sleep");
    }

    #[test]
    fn durations() {
        assert_eq!(parse_duration("4:22"), Some(262));
        assert_eq!(parse_duration("1:02:03"), Some(3723));
        assert_eq!(parse_duration("2024"), None);
    }
}
