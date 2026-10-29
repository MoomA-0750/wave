//! ポッドキャスト（YouTube の番組）のモデルと、InnerTube の応答の読み取り（Windows の Podcast.cs・MusicParsers.Podcasts.cs と同じ規則）。
//! エピソードは曲と同じく Song の videoId で流す（番組名を Song.artists に入れる）。RSS の番組も同じ形で表す（feed.rs）。

use serde_json::Value;

use super::models::{ArtistRef, SearchPage, Song};
use super::parsers::responsive_list_item_song;
use crate::json::{JsonPath, find_all};

const PODCAST_PAGE_TYPE: &str = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE";
const SEPARATOR: &str = " • ";

/// 番組の browseId の頭（MPSP + 番組のプレイリスト PL…）。
pub const PODCAST_PREFIX: &str = "MPSP";

/// ポッドキャストの番組のカード（検索・ライブラリ）。browse_id は MPSP…（RSS の番組は rss: + フィードの URL）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PodcastSummary {
    pub browse_id: String,
    pub title: String,
    pub author: Option<String>,
    pub thumbnail_url: Option<String>,
}

impl PodcastSummary {
    /// 番組のエピソードの一覧のプレイリスト（PL…）。保存と、エピソードを続けて流すのに使う。
    pub fn playlist_id(&self) -> &str {
        self.browse_id.strip_prefix(PODCAST_PREFIX).unwrap_or(&self.browse_id)
    }
}

/// ポッドキャストのエピソード。published_text・duration_text は応答の表示のまま。progress_percent は聴いたところ（1〜100、無ければ None）。
#[derive(Debug, Clone, PartialEq)]
pub struct PodcastEpisode {
    pub song: Song,
    pub published_text: Option<String>,
    pub duration_text: Option<String>,
    pub description: Option<String>,
    pub progress_percent: Option<u8>,
    pub show_browse_id: Option<String>,
}

impl PodcastEpisode {
    /// 検索の行の「番組 • 日付」。
    pub fn subtitle(&self) -> String {
        join_non_empty(&[Some(self.song.artists_text()), self.published_text.clone()])
    }

    /// 番組のページの行の「日付 • 長さ」。
    pub fn date_and_length(&self) -> String {
        join_non_empty(&[self.published_text.clone(), self.duration_text.clone()])
    }
}

fn join_non_empty(parts: &[Option<String>]) -> String {
    parts.iter().flatten().filter(|s| !s.is_empty()).cloned().collect::<Vec<_>>().join(SEPARATOR)
}

/// 番組のページ（MPSP…）。「新しいエピソード」（VLRDPN）も同じ形で読む。saved はライブラリに保存しているか（ログインしていない・分からなければ None）。
#[derive(Debug, Clone, PartialEq)]
pub struct PodcastPage {
    pub browse_id: String,
    pub title: String,
    pub author: Option<String>,
    pub description: Option<String>,
    pub thumbnail_url: Option<String>,
    pub episodes: Vec<PodcastEpisode>,
    pub continuation: Option<String>,
    pub saved: Option<bool>,
}

impl PodcastPage {
    pub fn summary(&self) -> PodcastSummary {
        PodcastSummary { browse_id: self.browse_id.clone(), title: self.title.clone(), author: self.author.clone(), thumbnail_url: self.thumbnail_url.clone() }
    }

    /// 番組のプレイリスト（PL…）。「新しいエピソード」（VLRDPN）なら RDPN（再生の出どころ）。
    pub fn playlist_id(&self) -> String {
        let id = self.browse_id.strip_prefix(PODCAST_PREFIX).unwrap_or(&self.browse_id);
        id.strip_prefix("VL").unwrap_or(id).to_string()
    }
}

/// ポッドキャスト（番組）に絞った検索の最初のページと続きのページ。
pub fn search_podcasts(root: &Value) -> SearchPage<PodcastSummary> {
    search_page_of(root, podcast_row)
}

/// エピソードに絞った検索。行の2列目は「日付 • 番組」。
pub fn search_episodes(root: &Value) -> SearchPage<PodcastEpisode> {
    search_page_of(root, episode_row)
}

/// ライブラリのポッドキャスト（FEmusic_library_non_music_audio_list）。格子（カード）と棚（行）のどちらの形でも読む。
pub fn library_podcasts(root: &Value) -> Vec<PodcastSummary> {
    let mut output: Vec<PodcastSummary> = Vec::new();
    let cards = find_all(root, "musicTwoRowItemRenderer").into_iter().filter_map(podcast_card);
    let rows = find_all(root, "musicResponsiveListItemRenderer").into_iter().filter_map(podcast_row);
    for show in cards.chain(rows) {
        if !output.iter().any(|p| p.browse_id == show.browse_id) {
            output.push(show);
        }
    }
    output
}

/// 番組のページ（browse MPSP…）の最初のページ。取れなければ None。
pub fn podcast_page(root: &Value, browse_id: &str) -> Option<PodcastPage> {
    let header = find_all(root, "musicResponsiveHeaderRenderer").into_iter().next()?;
    let title = text_of(header.get("title"))?;
    let shelf = root
        .path(&["contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents"])
        .items()
        .into_iter()
        .find_map(|i| i.path(&["musicShelfRenderer"]).filter(|s| s.is_object()));
    let show = ArtistRef { name: title.clone(), browse_id: None };
    // 保存のボタン。ログインしていなければ押すとログインの案内（likeEndpoint を持たない）なので、状態は分からないとする
    let toggle = header.path(&["buttons"]).items().into_iter().find_map(|b| b.path(&["toggleButtonRenderer"]).filter(|t| t.is_object()));
    // 「新しいエピソード」（VLRDPN）も同じ形で返るが、保存するものではない
    let saved = toggle
        .filter(|t| browse_id.starts_with(PODCAST_PREFIX) && !find_all(t, "likeEndpoint").is_empty())
        .and_then(|t| t.path(&["isToggled"]).and_then(Value::as_bool));
    let episodes = shelf.map(|s| multi_row_episodes(s, &show, Some(browse_id))).unwrap_or_default();
    Some(PodcastPage {
        browse_id: browse_id.to_string(),
        title,
        author: text_of(header.path(&["straplineTextOne"])),
        description: text_of(header.path(&["description", "musicDescriptionShelfRenderer", "description"])),
        thumbnail_url: last_thumbnail_url(header.path(&["thumbnail"])),
        episodes,
        continuation: shelf.and_then(next_continuation),
        saved,
    })
}

/// 番組のページのエピソードの続き（continuationContents.musicShelfContinuation）。番組名は行に無いので、呼ぶ側の show を入れる。
pub fn podcast_episodes(root: &Value, show: &ArtistRef, show_browse_id: Option<&str>) -> SearchPage<PodcastEpisode> {
    match root.path(&["continuationContents", "musicShelfContinuation"]).filter(|s| s.is_object()) {
        Some(shelf) => SearchPage { items: multi_row_episodes(shelf, show, show_browse_id), continuation: next_continuation(shelf) },
        None => SearchPage { items: Vec::new(), continuation: None },
    }
}

fn multi_row_episodes(shelf: &Value, show: &ArtistRef, show_browse_id: Option<&str>) -> Vec<PodcastEpisode> {
    shelf
        .path(&["contents"])
        .items()
        .into_iter()
        .filter_map(|i| i.path(&["musicMultiRowListItemRenderer"]).filter(|r| r.is_object()))
        .filter_map(|row| multi_row_episode(row, show, show_browse_id))
        .collect()
}

/// 番組のページのエピソードの行。題・説明・「再生回数 • 日付」・長さ・聴いたところ。
fn multi_row_episode(row: &Value, show: &ArtistRef, show_browse_id: Option<&str>) -> Option<PodcastEpisode> {
    let watch = row
        .path(&["onTap", "watchEndpoint"])
        .filter(|w| w.is_object())
        .or_else(|| row.get("overlay").and_then(|o| find_all(o, "watchEndpoint").into_iter().next()));
    let video_id = watch.path(&["videoId"]).str_()?;
    let title = text_of(row.get("title"))?;
    let progress = row.path(&["playbackProgress", "musicPlaybackProgressRenderer"]);
    let subtitle_runs = row.path(&["subtitle", "runs"]).items();
    // 「新しいエピソード」のように番組がまざる一覧では、行の補足に番組が付く（あればそれを使う）
    let show_run = subtitle_runs.iter().copied().chain(row.path(&["secondTitle", "runs"]).items()).find(|r| page_type_of(r) == Some(PODCAST_PAGE_TYPE));
    let mut show = show.clone();
    let mut show_browse_id = show_browse_id.map(String::from);
    if let Some(run) = show_run
        && let Some(name) = run.path(&["text"]).str_()
    {
        show = ArtistRef { name: name.to_string(), browse_id: None };
        show_browse_id = run.path(&["navigationEndpoint", "browseEndpoint", "browseId"]).str_().map(String::from).or(show_browse_id);
    }
    // 「2294回視聴 • 6 日前」の最後が日付（再生回数が無ければ日付だけ）
    let published = last_text(subtitle_runs.iter().copied().filter(|r| !show_run.is_some_and(|s| std::ptr::eq(*r, s))));
    let duration = last_text(progress.path(&["durationText", "runs"]).items().into_iter());
    let percent = progress.path(&["playbackProgressPercentage"]).and_then(Value::as_f64).map(|d| d as i64).filter(|p| (1..=100).contains(p));
    let song = Song {
        video_id: video_id.to_string(),
        title,
        artists: vec![show],
        album: None,
        duration_seconds: None,
        thumbnail_url: last_thumbnail_url(row.get("thumbnail")),
        kind: watch.path(&["watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType"]).str_().map(String::from),
        is_live: false,
    };
    Some(PodcastEpisode {
        song,
        published_text: published,
        duration_text: duration,
        description: text_of(row.get("description")),
        progress_percent: percent.map(|p| p as u8),
        show_browse_id,
    })
}

fn last_text<'a>(runs: impl Iterator<Item = &'a Value>) -> Option<String> {
    runs.filter_map(|r| r.path(&["text"]).str_()).filter(|t| !t.trim().is_empty() && *t != SEPARATOR).last().map(String::from)
}

/// 検索・ライブラリの番組の行（ページ種別が番組のもの）。
fn podcast_row(item: &Value) -> Option<PodcastSummary> {
    let browse = item.path(&["navigationEndpoint", "browseEndpoint"]);
    if page_type_of_browse(browse) != Some(PODCAST_PAGE_TYPE) {
        return None;
    }
    let columns = flex_column_runs(item);
    let id = browse.path(&["browseId"]).str_()?;
    let title = runs_text(columns.first().map(Vec::as_slice).unwrap_or_default())?;
    let detail: Vec<&Value> = columns.iter().skip(1).flatten().copied().collect();
    Some(PodcastSummary { browse_id: id.to_string(), title, author: runs_text(&detail), thumbnail_url: last_thumbnail_url(item.get("thumbnail")) })
}

/// ライブラリ・ホームの番組のカード（musicTwoRowItemRenderer）。
fn podcast_card(item: &Value) -> Option<PodcastSummary> {
    let browse = item.path(&["navigationEndpoint", "browseEndpoint"]);
    let id = browse.path(&["browseId"]).str_()?;
    if page_type_of_browse(browse) != Some(PODCAST_PAGE_TYPE) && !id.starts_with(PODCAST_PREFIX) {
        return None;
    }
    let title = text_of(item.get("title"))?;
    Some(PodcastSummary { browse_id: id.to_string(), title, author: text_of(item.get("subtitle")), thumbnail_url: last_thumbnail_url(item.get("thumbnailRenderer")) })
}

/// エピソードの検索の行。2列目の「日付 • 番組」から日付と番組を取る（番組名は曲のアーティストとして入る）。
fn episode_row(item: &Value) -> Option<PodcastEpisode> {
    let song = responsive_list_item_song(item)?;
    let columns = flex_column_runs(item);
    let detail: Vec<&Value> = columns.iter().skip(1).flatten().copied().collect();
    let show_run = detail.iter().copied().find(|r| page_type_of(r) == Some(PODCAST_PAGE_TYPE));
    let published = detail
        .iter()
        .copied()
        .filter(|r| !show_run.is_some_and(|s| std::ptr::eq(*r, s)))
        .filter_map(|r| r.path(&["text"]).str_())
        .find(|t| !t.trim().is_empty() && *t != SEPARATOR)
        .map(String::from);
    Some(PodcastEpisode {
        song,
        published_text: published,
        duration_text: None,
        description: None,
        progress_percent: None,
        show_browse_id: show_run.and_then(|r| r.path(&["navigationEndpoint", "browseEndpoint", "browseId"]).str_()).map(String::from),
    })
}

// ---- 以下は parsers.rs の小さな道具と同じもの（parsers.rs では非公開のため、ここにも置く） ----

fn search_page_of<'a, T>(root: &'a Value, row: impl Fn(&'a Value) -> Option<T>) -> SearchPage<T> {
    let shelf = root.path(&["continuationContents", "musicShelfContinuation"]).or_else(|| {
        root.path(&["contents", "tabbedSearchResultsRenderer", "tabs", "0", "tabRenderer", "content", "sectionListRenderer", "contents"])
            .items()
            .into_iter()
            .find_map(|i| i.path(&["musicShelfRenderer"]))
    });
    match shelf {
        Some(shelf) => SearchPage {
            items: shelf.path(&["contents"]).items().into_iter().filter_map(|i| i.path(&["musicResponsiveListItemRenderer"])).filter_map(row).collect(),
            continuation: next_continuation(shelf),
        },
        None => SearchPage { items: Vec::new(), continuation: None },
    }
}

fn flex_column_runs(item: &Value) -> Vec<Vec<&Value>> {
    item.path(&["flexColumns"])
        .items()
        .into_iter()
        .map(|c| c.path(&["musicResponsiveListItemFlexColumnRenderer", "text", "runs"]).items())
        .collect()
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
    node.path(&["musicThumbnailRenderer", "thumbnail", "thumbnails"]).items().last().and_then(|t| t.path(&["url"]).str_()).map(String::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture_text(name: &str) -> String {
        // 手本の応答は Android の core と共通（core/src/desktopTest/resources/fixtures）
        let path = format!("{}/../../core/src/desktopTest/resources/fixtures/{name}.json", env!("CARGO_MANIFEST_DIR"));
        std::fs::read_to_string(path).unwrap()
    }

    fn fixture(name: &str) -> Value {
        serde_json::from_str(&fixture_text(name)).unwrap()
    }

    fn artist(name: &str) -> ArtistRef {
        ArtistRef { name: name.to_string(), browse_id: None }
    }

    #[test]
    fn podcast_search_reads_shows() {
        let page = search_podcasts(&fixture("search_podcasts"));
        assert_eq!(page.items.len(), 8);
        let first = &page.items[0];
        assert_eq!(first.browse_id, "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv");
        assert_eq!(first.playlist_id(), "PLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv");
        assert_eq!(first.title, "Rebuild");
        assert_eq!(first.author.as_deref(), Some("Rebuild Podcast"));
        assert!(first.thumbnail_url.as_deref().unwrap().starts_with("https://"));
        assert!(page.items.iter().all(|p| p.browse_id.starts_with("MPSP")));
        assert!(page.continuation.is_some());
    }

    #[test]
    fn episode_search_takes_date_and_show_not_artist() {
        let page = search_episodes(&fixture("search_episodes"));
        assert_eq!(page.items.len(), 8);
        let first = &page.items[0];
        assert_eq!(first.song.video_id, "ZVIAE__9jhY");
        assert!(first.song.title.starts_with("How To Rebuild Your Character"));
        // 日付をアーティストと取り違えず、番組をアーティストの代わりにする
        assert_eq!(first.song.artists, vec![artist("Munroe Global Available Teachings")]);
        assert_eq!(first.published_text.as_deref(), Some("2018/04/28"));
        assert_eq!(first.show_browse_id.as_deref(), Some("MPSPPLiSxXTy3ZWc52InDNx3QUGeewlp3jNmRW"));
        assert_eq!(first.song.kind.as_deref(), Some("MUSIC_VIDEO_TYPE_PODCAST_EPISODE"));
        assert_eq!(first.subtitle(), "Munroe Global Available Teachings • 2018/04/28");
        assert!(page.items.iter().all(|e| e.show_browse_id.as_deref().is_some_and(|id| id.starts_with("MPSP")) && e.published_text.is_some()));
        assert!(page.continuation.is_some());
    }

    #[test]
    fn podcast_page_reads_header_and_episodes() {
        let page = podcast_page(&fixture("browse_podcast"), "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv").unwrap();
        assert_eq!(page.title, "Rebuild");
        assert_eq!(page.author.as_deref(), Some("Rebuild Podcast"));
        assert!(page.description.as_deref().unwrap().starts_with("ウェブ開発、プログラミング"));
        assert!(page.thumbnail_url.as_deref().unwrap().starts_with("https://"));
        assert_eq!(page.playlist_id(), "PLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv");
        // ログインしていない応答では、保存しているかは分からない
        assert_eq!(page.saved, None);
        assert_eq!(page.episodes.len(), 8);
        let first = &page.episodes[0];
        assert_eq!(first.song.video_id, "mLtB1Fr7o64");
        assert_eq!(first.song.title, "433: That Meeting Was So Jev (らんぶる)");
        assert_eq!(first.song.artists, vec![artist("Rebuild")]);
        assert_eq!(first.published_text.as_deref(), Some("6 日前"));
        assert_eq!(first.duration_text.as_deref(), Some("1 時間 37 分"));
        assert!(first.description.as_deref().unwrap().starts_with("らんぶるさんをゲストに迎えて"));
        assert_eq!(first.progress_percent, None);
        assert!(first.song.thumbnail_url.as_deref().unwrap().starts_with("https://"));
        assert!(page.continuation.is_some());
    }

    #[test]
    fn new_episodes_take_show_from_each_row_and_are_not_savable() {
        // 「新しいエピソード」（VLRDPN）は番組のページと同じ形で、行ごとに番組が付く（ログインが要るので番組の行を差し込んで確かめる）
        let show_run = r#"{"text":"Other Show","navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPLother","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"}}}}}"#;
        let text = fixture_text("browse_podcast").replacen(r#"{"text":"2294回視聴"}"#, show_run, 1);
        let page = podcast_page(&serde_json::from_str(&text).unwrap(), "VLRDPN").unwrap();
        assert_eq!(page.playlist_id(), "RDPN");
        assert_eq!(page.saved, None);
        let first = &page.episodes[0];
        assert_eq!(first.song.artists, vec![artist("Other Show")]);
        assert_eq!(first.show_browse_id.as_deref(), Some("MPSPPLother"));
        assert_eq!(first.published_text.as_deref(), Some("6 日前"));
        assert_eq!(page.episodes[1].song.artists, vec![artist(&page.title)]);
    }

    #[test]
    fn podcast_continuation_reads_episodes() {
        let show = artist("Rebuild");
        let page = podcast_episodes(&fixture("browse_podcast_continuation"), &show, Some("MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv"));
        assert_eq!(page.items.len(), 4);
        let first = &page.items[0];
        assert_eq!(first.song.video_id, "h61yHZbIQvk");
        assert_eq!(first.song.title, "413: Always Fully Hyped (yuka)");
        assert_eq!(first.song.artists, vec![show]);
        assert_eq!(first.published_text.as_deref(), Some("2025/09/08"));
        assert_eq!(first.duration_text.as_deref(), Some("1 時間 48 分"));
        assert_eq!(page.continuation, None);
    }

    #[test]
    fn library_podcasts_read_cards_and_rows() {
        // ログインが要るので、カードの形で確かめる（番組以外のカードは除く）
        let card = r#"{"contents":{"gridRenderer":{"items":[
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"New episodes"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDPN","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}},
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Rebuild"}]},"subtitle":{"runs":[{"text":"Rebuild Podcast"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"}}}}}}
            ]}}}"#;
        let shows = library_podcasts(&serde_json::from_str(card).unwrap());
        assert_eq!(
            shows,
            vec![PodcastSummary {
                browse_id: "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv".into(),
                title: "Rebuild".into(),
                author: Some("Rebuild Podcast".into()),
                thumbnail_url: None
            }]
        );
        assert_eq!(library_podcasts(&fixture("search_podcasts")).len(), 8);
    }

    #[test]
    fn unknown_shapes_give_empty_results() {
        let empty = serde_json::json!({});
        assert!(search_podcasts(&empty).items.is_empty());
        assert!(search_episodes(&empty).items.is_empty());
        assert!(podcast_page(&empty, "MPSPx").is_none());
        assert!(library_podcasts(&empty).is_empty());
    }
}
