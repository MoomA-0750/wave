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

/// アルバムに絞った検索。
pub fn search_albums(root: &Value) -> SearchPage<AlbumSummary> {
    search_page_of(root, album_row)
}

/// アーティストに絞った検索。
pub fn search_artists(root: &Value) -> SearchPage<ArtistSummary> {
    search_page_of(root, artist_row)
}

/// 打ち込み中の候補。1つめの区切りに語の候補、2つめに曲・アルバム・アーティストの行。
pub fn search_suggestions(root: &Value) -> SearchSuggestions {
    let contents: Vec<&Value> = root.path(&["contents"]).items().into_iter().flat_map(|i| i.path(&["searchSuggestionsSectionRenderer", "contents"]).items()).collect();
    let mut queries: Vec<String> = Vec::new();
    for item in &contents {
        let Some(s) = item.path(&["searchSuggestionRenderer"]).or_else(|| item.path(&["historySuggestionRenderer"])) else { continue };
        let query = s.path(&["navigationEndpoint", "searchEndpoint", "query"]).str_().map(String::from).or_else(|| runs_text(&s.path(&["suggestion", "runs"]).items()));
        if let Some(query) = query.filter(|q| !queries.contains(q)) {
            queries.push(query);
        }
    }
    let rows: Vec<&Value> = contents.iter().filter_map(|i| i.path(&["musicResponsiveListItemRenderer"])).collect();
    SearchSuggestions {
        queries,
        songs: rows.iter().filter(|r| page_type_of_browse(r.path(&["navigationEndpoint", "browseEndpoint"])).is_none()).filter_map(|r| responsive_list_item_song(r)).collect(),
        albums: rows.iter().filter_map(|r| album_row(r)).collect(),
        artists: rows.iter().filter_map(|r| artist_row(r)).collect(),
    }
}

fn search_page_of<'a, T>(root: &'a Value, row: impl Fn(&'a Value) -> Option<T>) -> SearchPage<T> {
    match search_shelf(root) {
        Some(shelf) => SearchPage {
            items: shelf.path(&["contents"]).items().into_iter().filter_map(|i| i.path(&["musicResponsiveListItemRenderer"])).filter_map(row).collect(),
            continuation: next_continuation(shelf),
        },
        None => SearchPage { items: Vec::new(), continuation: None },
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

/// ライブラリのプレイリスト一覧などのカード。
pub fn playlists(root: &Value) -> Vec<PlaylistSummary> {
    let mut seen = std::collections::HashSet::new();
    find_all(root, "musicTwoRowItemRenderer")
        .into_iter()
        .filter_map(|item| {
            let browse = item.path(&["navigationEndpoint", "browseEndpoint"]);
            let browse_id = browse.path(&["browseId"]).str_()?;
            if page_type_of_browse(browse) != Some("MUSIC_PAGE_TYPE_PLAYLIST") && !browse_id.starts_with("VL") {
                return None;
            }
            Some(PlaylistSummary {
                playlist_id: remove_vl(browse_id),
                title: text_of(item.path(&["title"]))?,
                subtitle: text_of(item.path(&["subtitle"])),
                thumbnail_url: last_thumbnail_url(item.path(&["thumbnailRenderer"])),
                editable: menu_icons(item).iter().any(|i| i == EDIT_ICON),
            })
        })
        .filter(|p| seen.insert(p.playlist_id.clone()))
        .collect()
}

/// 再生履歴（FEmusic_history）の曲（「今日」「今週」などの区切りをまとめて新しい順）。
/// 再生履歴の区切り（「今日」「昨日」など）ごとの曲。
pub fn history_sections(root: &Value) -> Vec<(Option<String>, Vec<Song>)> {
    let container = root
        .path(&["continuationContents", "sectionListContinuation"])
        .or_else(|| root.path(&["contents", "singleColumnBrowseResultsRenderer", "tabs", "0", "tabRenderer", "content", "sectionListRenderer"]));
    container
        .map(|c| {
            find_all(c, "musicShelfRenderer")
                .into_iter()
                .map(|shelf| (shelf.path(&["title", "runs", "0", "text"]).str_().map(String::from), shelf_songs(shelf)))
                .filter(|(_, songs)| !songs.is_empty())
                .collect()
        })
        .unwrap_or_default()
}

pub fn history_songs(root: &Value) -> Vec<Song> {
    let container = root
        .path(&["continuationContents", "sectionListContinuation"])
        .or_else(|| root.path(&["contents", "singleColumnBrowseResultsRenderer", "tabs", "0", "tabRenderer", "content", "sectionListRenderer"]));
    container.map(|c| find_all(c, "musicShelfRenderer").into_iter().flat_map(shelf_songs).collect()).unwrap_or_default()
}

/// ライブラリの一覧の続きのトークン。
pub fn library_continuation(root: &Value) -> Option<String> {
    find_all(root, "nextContinuationData")
        .into_iter()
        .find_map(|c| c.path(&["continuation"]).str_())
        .or_else(|| {
            find_all(root, "continuationItemRenderer")
                .into_iter()
                .find_map(|c| c.path(&["continuationEndpoint", "continuationCommand", "token"]).str_())
        })
        .map(String::from)
}

/// プレイリストのページ（最初のページと続き）。
pub fn playlist_page(root: &Value) -> PlaylistPage {
    let appended = root
        .path(&["onResponseReceivedActions"])
        .items()
        .into_iter()
        .find_map(|a| a.path(&["appendContinuationItemsAction", "continuationItems"]).filter(|v| v.is_array()));
    let shelf = find_all(root, "musicPlaylistShelfRenderer").into_iter().next();
    let items = match appended {
        Some(list) => list.items(),
        None => shelf.path(&["contents"]).items(),
    };
    let rows: Vec<(Song, Option<String>)> = items
        .iter()
        .filter_map(|i| i.path(&["musicResponsiveListItemRenderer"]))
        .filter_map(|row| Some((responsive_list_item_song(row)?, row.path(&["playlistItemData", "playlistSetVideoId"]).str_().map(String::from))))
        .collect();
    let continuation = items
        .last()
        .and_then(|last| last.path(&["continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token"]).str_())
        .or_else(|| shelf.path(&["continuations"]).items().into_iter().find_map(|c| c.path(&["nextContinuationData", "continuation"]).str_()))
        .map(String::from);
    let header = find_all(root, "musicResponsiveHeaderRenderer").into_iter().next().or_else(|| find_all(root, "musicDetailHeaderRenderer").into_iter().next());
    let subtitle = header.map(|h| {
        [text_of(h.path(&["subtitle"])), text_of(h.path(&["secondSubtitle"]))].into_iter().flatten().collect::<Vec<_>>().join(" • ")
    });
    let thumbnail = header.and_then(|h| {
        last_thumbnail_url(h.path(&["thumbnail"])).or_else(|| {
            find_all(h, "musicThumbnailRenderer")
                .into_iter()
                .find_map(|t| t.path(&["thumbnail", "thumbnails"]).items().last().and_then(|u| u.path(&["url"]).str_()).map(String::from))
        })
    });
    let song_area = appended.or(shelf);
    let end_confirmed = continuation.is_none() && song_area.is_some_and(|area| !contains_key_like(area, "continuation"));
    let (songs, set_video_ids) = rows.into_iter().unzip();
    PlaylistPage {
        title: text_of(header.path(&["title"])),
        songs,
        set_video_ids,
        editable: !find_all(root, "musicEditablePlaylistDetailHeaderRenderer").is_empty(),
        end_confirmed,
        continuation,
        subtitle: subtitle.filter(|s| !s.is_empty()),
        thumbnail_url: thumbnail,
    }
}

const CONTINUATION_CONTAINERS: [&str; 2] = ["appendContinuationItemsAction", "continuationItems"];

/// 名前に fragment を含むキーがあるか（続きを入れる入れ物の名前は数えない）。
fn contains_key_like(node: &Value, fragment: &str) -> bool {
    match node {
        Value::Object(map) => map.iter().any(|(key, value)| {
            (!CONTINUATION_CONTAINERS.contains(&key.as_str()) && key.to_lowercase().contains(fragment)) || contains_key_like(value, fragment)
        }),
        Value::Array(list) => list.iter().any(|v| contains_key_like(v, fragment)),
        _ => false,
    }
}

/// 次に流れる曲（ラジオ）の最初の応答と続き。
pub fn up_next(root: &Value) -> UpNextPage {
    let panel = root.path(&["continuationContents", "playlistPanelContinuation"]).or_else(|| {
        root.path(&["contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs", "0", "tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer"])
    });
    let Some(panel) = panel else {
        return UpNextPage { songs: Vec::new(), continuation: None, playlist_id: None };
    };
    // ログイン中は、MV と対になった曲が playlistPanelVideoWrapperRenderer に包まれて来る。包みの中の曲も読む
    let songs = panel
        .path(&["contents"])
        .items()
        .into_iter()
        .filter_map(|i| i.path(&["playlistPanelVideoRenderer"]).or_else(|| i.path(&["playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer"])))
        .filter_map(playlist_panel_song)
        .collect();
    let continuation = panel
        .path(&["continuations"])
        .items()
        .into_iter()
        .find_map(|c| c.path(&["nextRadioContinuationData", "continuation"]).str_().or_else(|| c.path(&["nextContinuationData", "continuation"]).str_()))
        .map(String::from);
    UpNextPage { songs, continuation, playlist_id: panel.path(&["playlistId"]).str_().map(String::from) }
}

/// ラジオ・キューの行（playlistPanelVideoRenderer）。
pub fn playlist_panel_song(item: &Value) -> Option<Song> {
    let video_id = item.path(&["videoId"]).str_().or_else(|| item.path(&["navigationEndpoint", "watchEndpoint", "videoId"]).str_())?;
    let title = text_of(item.path(&["title"]))?;
    let mut byline = item.path(&["longBylineText", "runs"]).items();
    if byline.is_empty() {
        byline = item.path(&["shortBylineText", "runs"]).items();
    }
    Some(Song {
        video_id: video_id.to_string(),
        title,
        artists: artists_of(&byline),
        album: album_of(&byline),
        duration_seconds: duration_of(&item.path(&["lengthText", "runs"]).items()),
        thumbnail_url: item.path(&["thumbnail", "thumbnails"]).items().last().and_then(|t| t.path(&["url"]).str_()).map(String::from),
        kind: item.path(&["navigationEndpoint"]).path(&MUSIC_CONFIG).str_().map(String::from),
        is_live: false,
    })
}

/// account_menu の応答から、ログイン中のアカウントの名前と写真。アカウントのヘッダが無ければ None。
/// accounts_list の応答から、各アカウントの（選択中か, datasyncIdToken の "||" より前）を集める。
pub fn account_tokens(root: &Value) -> Vec<(bool, String)> {
    fn walk(node: &Value, selected: bool, out: &mut Vec<(bool, String)>) {
        match node {
            Value::Object(map) => {
                let is_selected = selected || map.get("isSelected").is_some_and(|v| v == &Value::Bool(true) || v.as_str() == Some("true"));
                for (name, value) in map {
                    if name == "datasyncIdToken"
                        && let Some(id) = crate::credentials::normalize_data_sync_id(value.as_str())
                    {
                        out.push((is_selected, id));
                    }
                    walk(value, is_selected, out);
                }
            }
            Value::Array(items) => items.iter().for_each(|item| walk(item, selected, out)),
            _ => {}
        }
    }
    let mut out = Vec::new();
    walk(root, false, &mut out);
    out
}

pub fn account(root: &Value) -> Option<Account> {
    let header = find_all(root, "activeAccountHeaderRenderer").into_iter().next()?;
    Some(Account {
        name: header.path(&["accountName", "runs", "0", "text"]).str_().map(String::from),
        photo_url: header.path(&["accountPhoto", "thumbnails"]).items().last().and_then(|t| t.path(&["url"]).str_()).map(String::from),
    })
}

/// next の最初の応答から、頼んだ曲の評価（別の曲の評価のボタンなら None）。
pub fn like_status(root: &Value, video_id: &str) -> Option<LikeStatus> {
    let button = root
        .path(&["playerOverlays", "playerOverlayRenderer", "actions"])
        .items()
        .into_iter()
        .find_map(|a| a.path(&["likeButtonRenderer"]))?;
    if button.path(&["target", "videoId"]).str_() != Some(video_id) {
        return None;
    }
    match button.path(&["likeStatus"]).str_()? {
        "LIKE" => Some(LikeStatus::Like),
        "DISLIKE" => Some(LikeStatus::Dislike),
        "INDIFFERENT" => Some(LikeStatus::Indifferent),
        _ => None,
    }
}

/// next の最初の応答から、その曲に見せる映像。音源（ATV）なら対になった MV と時刻の対応、MV・動画ならその曲そのもの。
/// 対応表が無い MV は時刻を合わせられないので使わない。
pub fn music_video_of(root: &Value, video_id: &str) -> Option<MusicVideo> {
    let panel = root.path(&[
        "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs", "0",
        "tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer",
    ]);
    let as_ms = |v: Option<&Value>| v.and_then(|v| v.as_str().and_then(|s| s.parse().ok()).or_else(|| v.as_u64()));
    for item in panel.path(&["contents"]).items() {
        let wrapper = item.path(&["playlistPanelVideoWrapperRenderer"]);
        let renderer = item.path(&["playlistPanelVideoRenderer"]).or_else(|| wrapper.path(&["primaryRenderer", "playlistPanelVideoRenderer"]));
        if renderer.path(&["videoId"]).str_() != Some(video_id) {
            continue;
        }
        let kind = renderer.path(&["navigationEndpoint"]).path(&MUSIC_CONFIG).str_()?;
        if kind != "MUSIC_VIDEO_TYPE_ATV" {
            return Some(MusicVideo { video_id: video_id.to_string(), segments: Vec::new() });
        }
        let counterpart = wrapper.path(&["counterpart", "0"]);
        let counterpart_id = counterpart.path(&["counterpartRenderer", "playlistPanelVideoRenderer", "videoId"]).str_()?;
        let segments: Vec<TimeSegment> = counterpart
            .path(&["segmentMap", "segment"])
            .items()
            .into_iter()
            .filter_map(|s| {
                Some(TimeSegment {
                    primary_start_ms: as_ms(s.path(&["primaryVideoStartTimeMilliseconds"]))?,
                    counterpart_start_ms: as_ms(s.path(&["counterpartVideoStartTimeMilliseconds"]))?,
                    duration_ms: as_ms(s.path(&["durationMilliseconds"]))?,
                })
            })
            .collect();
        return (!segments.is_empty()).then(|| MusicVideo { video_id: counterpart_id.to_string(), segments });
    }
    None
}

/// next の最初の応答から、歌詞のタブ（MPLY…）の ID。
pub fn lyrics_browse_id(root: &Value) -> Option<String> {
    root.path(&["contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs"])
        .items()
        .into_iter()
        .map(|t| t.path(&["tabRenderer", "endpoint", "browseEndpoint"]))
        .find(|b| page_type_of_browse(*b) == Some("MUSIC_PAGE_TYPE_TRACK_LYRICS"))
        .and_then(|b| b.path(&["browseId"]).str_())
        .map(String::from)
}

/// 歌詞のタブの時刻なしの本文と出典。
pub fn plain_lyrics(root: &Value) -> Option<crate::lyrics::SongLyrics> {
    let shelf = root.path(&["contents", "sectionListRenderer", "contents"]).items().into_iter().find_map(|i| i.path(&["musicDescriptionShelfRenderer"]));
    Some(crate::lyrics::SongLyrics::Plain { text: text_of(shelf.path(&["description"]))?, source: text_of(shelf.path(&["footer"])) })
}

const CONTENT_RENDERERS: [&str; 6] = [
    "musicTwoRowItemRenderer", "musicResponsiveListItemRenderer", "musicResponsiveHeaderRenderer",
    "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer", "musicPlaylistShelfRenderer",
];

/// ログインの案内だけが返った（中身の部品が1つも無い）。
pub fn is_sign_in_prompt(root: &Value) -> bool {
    !find_all(root, "messageRenderer").is_empty() && CONTENT_RENDERERS.iter().all(|r| find_all(root, r).is_empty())
}

const ALBUM_PLAYLIST_PREFIX: &str = "OLAK5uy_";

/// アルバムのページ。見出しが無ければ None。
pub fn album_page(root: &Value, browse_id: &str) -> Option<AlbumPage> {
    let header = find_all(root, "musicResponsiveHeaderRenderer").into_iter().next()?;
    let title = text_of(header.path(&["title"]))?;
    let artists = artists_of(&header.path(&["straplineTextOne", "runs"]).items());
    let thumbnail = last_thumbnail_url(header.path(&["thumbnail"]));
    let playlist_id = header
        .path(&["buttons"])
        .items()
        .into_iter()
        .find_map(|b| b.path(&["musicPlayButtonRenderer", "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId"]).str_())
        .map(String::from)
        .or_else(|| canonical_list(root.path(&["microformat", "microformatDataRenderer", "urlCanonical"]).str_()))
        .or_else(|| {
            find_all(root, "watchPlaylistEndpoint")
                .into_iter()
                .find_map(|w| w.path(&["playlistId"]).str_().filter(|p| p.starts_with(ALBUM_PLAYLIST_PREFIX)).map(String::from))
        });
    let album = AlbumRef { title: title.clone(), browse_id: Some(browse_id.to_string()) };
    let shelf = root
        .path(&["contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents"])
        .items()
        .into_iter()
        .find_map(|i| i.path(&["musicShelfRenderer"]));
    let songs = shelf
        .path(&["contents"])
        .items()
        .into_iter()
        .filter_map(|row| {
            let item = row.path(&["musicResponsiveListItemRenderer"])?;
            let mut song = responsive_list_item_song(item)?;
            let row_runs: Vec<&Value> = flex_column_runs(item).into_iter().skip(1).flatten().collect();
            let typed = typed_artists_of(&row_runs);
            song.artists = if typed.is_empty() { artists.clone() } else { typed };
            song.album = song.album.or_else(|| Some(album.clone()));
            song.thumbnail_url = song.thumbnail_url.or_else(|| thumbnail.clone());
            Some(song)
        })
        .collect();
    Some(AlbumPage { browse_id: browse_id.to_string(), title, subtitle: text_of(header.path(&["subtitle"])), artists, thumbnail_url: thumbnail, playlist_id, songs })
}

fn canonical_list(url: Option<&str>) -> Option<String> {
    let rest = &url?[url?.find("list=")? + 5..];
    let id = rest.split('&').next()?;
    id.starts_with(ALBUM_PLAYLIST_PREFIX).then(|| id.to_string())
}

/// アーティストのページ。見出しが無ければ None。
pub fn artist_page(root: &Value) -> Option<ArtistPage> {
    let header = root.path(&["header", "musicImmersiveHeaderRenderer"]).or_else(|| root.path(&["header", "musicVisualHeaderRenderer"]))?;
    let name = text_of(header.path(&["title"]))?;
    let sections = root.path(&["contents", "singleColumnBrowseResultsRenderer", "tabs", "0", "tabRenderer", "content", "sectionListRenderer", "contents"]).items();
    let song_shelf = sections.iter().find_map(|s| s.path(&["musicShelfRenderer"]));
    let all_songs = song_shelf
        .path(&["title", "runs", "0", "navigationEndpoint", "browseEndpoint", "browseId"])
        .str_()
        .or_else(|| song_shelf.path(&["bottomEndpoint", "browseEndpoint", "browseId"]).str_());
    let album_shelves = sections
        .iter()
        .filter_map(|section| {
            let carousel = section.path(&["musicCarouselShelfRenderer"])?;
            let albums: Vec<AlbumSummary> = carousel
                .path(&["contents"])
                .items()
                .into_iter()
                .filter_map(|card| {
                    let item = card.path(&["musicTwoRowItemRenderer"])?;
                    let browse = item.path(&["navigationEndpoint", "browseEndpoint"]);
                    if page_type_of_browse(browse) != Some("MUSIC_PAGE_TYPE_ALBUM") {
                        return None;
                    }
                    Some(AlbumSummary {
                        browse_id: browse.path(&["browseId"]).str_()?.to_string(),
                        title: text_of(item.path(&["title"]))?,
                        subtitle: text_of(item.path(&["subtitle"])),
                        thumbnail_url: last_thumbnail_url(item.path(&["thumbnailRenderer"])),
                    })
                })
                .collect();
            (!albums.is_empty()).then(|| AlbumShelf {
                title: text_of(carousel.path(&["header", "musicCarouselShelfBasicHeaderRenderer", "title"])).unwrap_or_default(),
                albums,
            })
        })
        .collect();
    // 動画の棚: カードが曲の再生（watchEndpoint）になっている棚
    let videos = sections
        .iter()
        .flat_map(|section| section.path(&["musicCarouselShelfRenderer", "contents"]).items())
        .filter_map(|card| {
            let item = card.path(&["musicTwoRowItemRenderer"]);
            let watch = item.path(&["navigationEndpoint", "watchEndpoint"]);
            Some(Song {
                video_id: watch.path(&["videoId"]).str_()?.to_string(),
                title: text_of(item.path(&["title"]))?,
                artists: vec![ArtistRef { name: name.clone(), browse_id: None }],
                album: None,
                duration_seconds: None,
                thumbnail_url: last_thumbnail_url(item.path(&["thumbnailRenderer"])),
                kind: watch.path(&["watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType"]).str_().map(String::from),
                is_live: false,
            })
        })
        .collect();
    Some(ArtistPage {
        name,
        thumbnail_url: last_thumbnail_url(header.path(&["thumbnail"])),
        top_songs: song_shelf.map(shelf_songs).unwrap_or_default(),
        all_songs_playlist_id: all_songs.and_then(|id| id.strip_prefix("VL")).map(String::from),
        album_shelves,
        videos,
    })
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

fn typed_artists_of(runs: &[&Value]) -> Vec<ArtistRef> {
    runs.iter()
        .filter(|r| matches!(page_type_of(r), Some(ARTIST_PAGE_TYPE | "MUSIC_PAGE_TYPE_USER_CHANNEL")))
        .filter_map(|run| {
            // チャンネル（USER_CHANNEL）はアーティストのページとして開けないので、名前だけにする
            let browse_id = (page_type_of(run) == Some(ARTIST_PAGE_TYPE))
                .then(|| run.path(&["navigationEndpoint", "browseEndpoint", "browseId"]).str_().map(String::from))
                .flatten();
            Some(ArtistRef { name: run.path(&["text"]).str_()?.to_string(), browse_id })
        })
        .collect()
}

/// アーティストの runs。ページ種別が付いていればそれを、無ければ区切り（" • "）の前をアーティストとみなす。
fn artists_of(runs: &[&Value]) -> Vec<ArtistRef> {
    let typed = typed_artists_of(runs);
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
    fn up_next_pages() {
        let first = up_next(&fixture("next_radio"));
        assert_eq!(first.songs.len(), 4);
        assert!(first.songs.iter().all(|s| !s.artists.is_empty() && s.duration_seconds.is_some()));
        assert!(first.playlist_id.as_deref().unwrap().starts_with("RDAMVM"));
        assert!(first.continuation.is_some());
        let next = up_next(&fixture("next_radio_continuation"));
        assert_eq!(next.songs.len(), 4);
        assert_ne!(first.songs.iter().map(|s| &s.video_id).collect::<Vec<_>>(), next.songs.iter().map(|s| &s.video_id).collect::<Vec<_>>());
    }

    #[test]
    fn playlist_pages_and_sign_in_prompt() {
        let page = playlist_page(&fixture("browse_playlist"));
        assert!(!page.songs.is_empty());
        assert!(page.title.is_some());
        assert_eq!(page.songs.len(), page.set_video_ids.len());
        assert!(!playlist_page(&fixture("browse_playlist_continuation")).songs.is_empty());
        assert!(is_sign_in_prompt(&fixture("browse_library_logged_out")));
        assert!(!is_sign_in_prompt(&fixture("browse_playlist")));
    }

    #[test]
    fn library_playlists_from_home_cards() {
        let list = playlists(&fixture("browse_home_playlists"));
        assert!(!list.is_empty());
        assert!(list.iter().all(|p| !p.playlist_id.starts_with("VL")));
    }

    #[test]
    fn album_page_fills_artists_album_and_artwork() {
        let page = album_page(&fixture("browse_album"), "MPREb_test").unwrap();
        assert_eq!(page.songs.len(), 4);
        assert!(page.playlist_id.as_deref().unwrap().starts_with("OLAK5uy_"));
        assert_eq!(page.artists.len(), 1);
        assert!(page.artists[0].browse_id.as_deref().unwrap().starts_with("UC"));
        for song in &page.songs {
            assert!(song.duration_seconds.is_some());
            assert_eq!(song.artists, page.artists);
            assert_eq!(song.album, Some(AlbumRef { title: page.title.clone(), browse_id: Some("MPREb_test".into()) }));
            assert_eq!(song.thumbnail_url, page.thumbnail_url);
        }
        let text = std::fs::read_to_string(format!("{}/../../core/src/desktopTest/resources/fixtures/browse_album.json", env!("CARGO_MANIFEST_DIR"))).unwrap();
        let greyed = text.replacen(
            "\"musicResponsiveListItemRenderer\":{",
            "\"musicResponsiveListItemRenderer\":{\"musicItemRendererDisplayPolicy\":\"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT\",",
            1,
        );
        assert_eq!(album_page(&serde_json::from_str(&greyed).unwrap(), "MPREb_test").unwrap().songs.len(), 3);
    }

    #[test]
    fn artist_page_shelves() {
        let page = artist_page(&fixture("browse_artist")).unwrap();
        assert_eq!(page.top_songs.len(), 5);
        assert!(page.all_songs_playlist_id.as_deref().unwrap().starts_with("OLAK5uy_"));
        assert_eq!(page.album_shelves.len(), 2);
        assert!(page.album_shelves.iter().flat_map(|s| &s.albums).all(|a| a.browse_id.starts_with("MPREb_")));
        assert_eq!(page.videos.len(), 6);
        assert!(page.videos.iter().all(|v| v.artists[0].name == page.name));
        assert!(artist_page(&fixture("browse_album")).is_none());
        assert!(album_page(&fixture("search_songs"), "MPREb_x").is_none());
    }

    #[test]
    fn account_from_the_menu_header() {
        let root: Value = serde_json::from_str(
            r#"{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":{
                "accountName":{"runs":[{"text":"Name"}]},"accountPhoto":{"thumbnails":[{"url":"https://a/s48"},{"url":"https://a/s88"}]}}}}}}}]}"#,
        )
        .unwrap();
        assert_eq!(account(&root), Some(Account { name: Some("Name".into()), photo_url: Some("https://a/s88".into()) }));
        assert_eq!(account(&serde_json::json!({ "actions": [] })), None);
    }

    #[test]
    fn lyrics_tab_and_plain_text() {
        assert!(lyrics_browse_id(&fixture("next_radio")).unwrap().starts_with("MPLY"));
        let Some(crate::lyrics::SongLyrics::Plain { text, .. }) = plain_lyrics(&fixture("browse_lyrics")) else { panic!("no lyrics") };
        assert!(!text.trim().is_empty());
    }

    #[test]
    fn like_status_is_read_only_for_the_requested_song() {
        let text = std::fs::read_to_string(format!("{}/../../core/src/desktopTest/resources/fixtures/next_radio.json", env!("CARGO_MANIFEST_DIR"))).unwrap();
        let compact = text.replace(' ', "");
        let at = compact.find("\"target\":{\"videoId\":\"").unwrap() + "\"target\":{\"videoId\":\"".len();
        let video_id = &compact[at..at + compact[at..].find('"').unwrap()];
        assert_eq!(like_status(&fixture("next_radio"), video_id), Some(LikeStatus::Indifferent));
        assert_eq!(like_status(&fixture("next_radio"), "other"), None);
        let liked: Value = serde_json::from_str(&text.replace("\"INDIFFERENT\"", "\"LIKE\"")).unwrap();
        assert_eq!(like_status(&liked, video_id), Some(LikeStatus::Like));
    }

    #[test]
    fn music_video_maps_song_time_to_video_time() {
        let video = MusicVideo {
            video_id: "v".into(),
            segments: vec![TimeSegment { primary_start_ms: 0, counterpart_start_ms: 12_000, duration_ms: 60_000 }],
        };
        assert_eq!(video.video_position_of(1_000), Some(13_000));
        assert_eq!(video.video_position_of(61_000), None);
        assert_eq!(MusicVideo { video_id: "v".into(), segments: vec![] }.video_position_of(5), Some(5));
        // 手本の応答の曲はどれも音源ではないので、曲そのものを映像にする
        let root = fixture("next_radio");
        let first = up_next(&root).songs.into_iter().next().unwrap();
        if first.kind.as_deref() != Some("MUSIC_VIDEO_TYPE_ATV") {
            assert_eq!(music_video_of(&root, &first.video_id).map(|v| v.video_id), Some(first.video_id));
        }
        assert_eq!(music_video_of(&root, "nope"), None);
    }

    #[test]
    fn durations() {
        assert_eq!(parse_duration("4:22"), Some(262));
        assert_eq!(parse_duration("1:02:03"), Some(3723));
        assert_eq!(parse_duration("2024"), None);
    }

    #[test]
    fn account_tokens_marks_only_the_selected_account() {
        let root: Value = serde_json::json!({ "actions": [{ "contents": [
            { "accountItem": { "isSelected": true, "serviceEndpoint": { "x": { "datasyncIdToken": "111||222" } } } },
            { "accountItem": { "isSelected": false, "serviceEndpoint": { "x": { "datasyncIdToken": "333||" } } } }
        ] }] });
        assert_eq!(account_tokens(&root), vec![(true, "111".to_string()), (false, "333".to_string())]);
    }

    #[test]
    fn history_keeps_each_section_title_with_its_songs() {
        let item = |id: &str, title: &str| {
            serde_json::json!({ "musicResponsiveListItemRenderer": {
                "playlistItemData": { "videoId": id },
                "flexColumns": [{ "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": title }] } } }]
            } })
        };
        let shelf = |title: &str, items: Vec<Value>| serde_json::json!({ "musicShelfRenderer": { "title": { "runs": [{ "text": title }] }, "contents": items } });
        let root = serde_json::json!({ "contents": { "singleColumnBrowseResultsRenderer": { "tabs": [{ "tabRenderer": { "content": { "sectionListRenderer": { "contents": [
            shelf("Today", vec![item("aaaaaaaaaaa", "One"), item("bbbbbbbbbbb", "Two")]),
            shelf("Yesterday", vec![item("ccccccccccc", "Three")])
        ] } } } }] } } });
        let sections = history_sections(&root);
        assert_eq!(sections.len(), 2);
        assert_eq!(sections[0].0.as_deref(), Some("Today"));
        assert_eq!(sections[0].1.iter().map(|s| s.title.as_str()).collect::<Vec<_>>(), ["One", "Two"]);
        assert_eq!(sections[1].0.as_deref(), Some("Yesterday"));
        assert_eq!(history_songs(&root).len(), 3);
    }

    #[test]
    fn search_albums_and_artists_read_rows_that_open_pages() {
        let albums = search_albums(&fixture("search_albums"));
        assert!(!albums.items.is_empty());
        assert!(albums.items.iter().all(|a| a.browse_id.starts_with("MPRE") && !a.title.is_empty()));
        let artists = search_artists(&fixture("search_artists"));
        assert!(!artists.items.is_empty());
        assert!(artists.items.iter().all(|a| a.browse_id.starts_with("UC") && !a.name.is_empty()));
    }

    #[test]
    fn search_suggestions_have_queries_and_rows() {
        let suggestions = search_suggestions(&fixture("search_suggestions"));
        assert_eq!(suggestions.queries.first().map(String::as_str), Some("yoasobi"));
        assert!(!suggestions.songs.is_empty() || !suggestions.albums.is_empty() || !suggestions.artists.is_empty());
    }

    #[test]
    fn video_search_marks_live_streams() {
        let page = search_songs(&fixture("search_videos_live"));
        assert!(page.songs.iter().any(|s| s.is_live));
    }
}
