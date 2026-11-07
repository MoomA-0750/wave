//! 一覧（プレイリスト・アルバム）の中の検索と並べ替え、検索のトップの結果（Mac の CollectionModel・SearchOrder と同じ決まり）。

use super::models::{AlbumSummary, ArtistSummary, HomeItem, Song};
use crate::lyrics::search::normalize;

/// 一覧の並べ方。Added・Published は YouTube Music に並べてもらう（並べ替えられるプレイリストのときだけ選べる）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SortKey {
    Custom,
    Added,
    Published,
    Title,
    Artist,
    Album,
    Duration,
}

impl SortKey {
    pub const ALL: [SortKey; 7] = [SortKey::Custom, SortKey::Added, SortKey::Published, SortKey::Title, SortKey::Artist, SortKey::Album, SortKey::Duration];

    pub fn label(self) -> &'static str {
        match self {
            SortKey::Custom => "カスタム順",
            SortKey::Added => "追加した日",
            SortKey::Published => "リリース日",
            SortKey::Title => "タイトル",
            SortKey::Artist => "アーティスト",
            SortKey::Album => "アルバム",
            SortKey::Duration => "再生時間",
        }
    }

    /// 設定に覚える名前（Mac と同じ）。
    pub fn name(self) -> &'static str {
        match self {
            SortKey::Custom => "Custom",
            SortKey::Added => "Added",
            SortKey::Published => "Published",
            SortKey::Title => "Title",
            SortKey::Artist => "Artist",
            SortKey::Album => "Album",
            SortKey::Duration => "Duration",
        }
    }

    pub fn server(self) -> bool {
        matches!(self, SortKey::Added | SortKey::Published)
    }

    /// 選べる並べ方（並べ替えられないプレイリスト・アルバムでは、YouTube Music に並べてもらうものを除く）。
    pub fn choices(sortable: bool) -> Vec<SortKey> {
        Self::ALL.into_iter().filter(|k| sortable || !k.server()).collect()
    }
}

/// 覚えた並べ方（"Title:desc" の形。Mac と同じ）を読む。
pub fn parse_order(saved: &str) -> (SortKey, bool) {
    let mut parts = saved.split(':');
    let key = parts.next().and_then(|n| SortKey::ALL.into_iter().find(|k| k.name() == n)).unwrap_or(SortKey::Custom);
    (key, parts.next() == Some("desc"))
}

pub fn format_order(key: SortKey, descending: bool) -> String {
    format!("{}:{}", key.name(), if descending { "desc" } else { "asc" })
}

/// 同じ並べ方をもう一度選ぶと向きを変え、替えたら既定の向き（追加した日は新しい順）にする。
pub fn choose(current: (SortKey, bool), key: SortKey) -> (SortKey, bool) {
    if current.0 == key { (key, !current.1) } else { (key, key == SortKey::Added) }
}

/// 一覧の中の検索に合うか: 空白（全角も）で区切った語をすべて含む。大文字・小文字、全角・半角、ひらがな・カタカナは区別しない。
pub fn matches(song: &Song, filter: &str) -> bool {
    let text = normalize(&format!("{} {} {}", song.title, song.artists_text(), song.album.as_ref().map(|a| a.title.as_str()).unwrap_or("")));
    filter.split([' ', '\u{3000}']).map(normalize).filter(|t| !t.is_empty()).all(|t| text.contains(&t))
}

/// 絞り込みと並べ替えを当てた行の並び（元の位置の番号）。kind は曲の種類（MUSIC_VIDEO_TYPE_…）。
/// YouTube Music に並べてもらったもの（追加した日・リリース日）はその並びのまま。カスタム順の逆は手元で逆にする。
pub fn arrange(songs: &[Song], filter: &str, kind: Option<&str>, key: SortKey, descending: bool) -> Vec<usize> {
    let mut view: Vec<usize> = (0..songs.len()).filter(|&i| matches(&songs[i], filter) && kind.is_none_or(|k| songs[i].kind.as_deref() == Some(k))).collect();
    let lower = |s: &str| s.to_lowercase();
    match key {
        SortKey::Title => sort_by(&mut view, descending, |i| lower(&songs[i].title)),
        SortKey::Artist => sort_by(&mut view, descending, |i| lower(&songs[i].artists_text())),
        SortKey::Album => sort_by(&mut view, descending, |i| songs[i].album.as_ref().map(|a| lower(&a.title)).unwrap_or_default()),
        SortKey::Duration => sort_by(&mut view, descending, |i| songs[i].duration_seconds.unwrap_or(0)),
        SortKey::Custom if descending => view.reverse(),
        _ => {}
    }
    view
}

/// 同じ値なら元の並びのまま（向きを変えても）。
fn sort_by<T: Ord>(view: &mut [usize], descending: bool, key: impl Fn(usize) -> T) {
    view.sort_by(|&a, &b| {
        let order = key(a).cmp(&key(b));
        let order = if descending { order.reverse() } else { order };
        order.then(a.cmp(&b))
    });
}

/// 検索のトップの結果: 名前が検索語と同じアーティスト、題が同じアルバム、ほかは最初の曲（Mac・Android の SearchOrder と同じ）。
pub fn top_result(query: &str, songs: &[Song], artists: &[ArtistSummary], albums: &[AlbumSummary]) -> Option<HomeItem> {
    let key = normalize(query);
    if let Some(a) = artists.iter().find(|a| normalize(&a.name) == key) {
        return Some(HomeItem::Artist(a.clone()));
    }
    if let Some(a) = albums.iter().find(|a| normalize(&a.title) == key) {
        return Some(HomeItem::Album(a.clone()));
    }
    songs
        .first()
        .map(|s| HomeItem::Song(s.clone()))
        .or_else(|| artists.first().map(|a| HomeItem::Artist(a.clone())))
        .or_else(|| albums.first().map(|a| HomeItem::Album(a.clone())))
}

/// 曲の一覧の補足「N 曲、M 分」（Mac の L.songs・L.length と同じ）。
pub fn count_and_length(songs: &[Song]) -> String {
    let total: u32 = songs.iter().filter_map(|s| s.duration_seconds).sum();
    let (h, m) = (total / 3600, total / 60 % 60);
    let length = if h > 0 { format!("{h} 時間 {m} 分") } else { format!("{} 分", total.div_ceil(60)) };
    format!("{} 曲、{length}", songs.len())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::music::models::{AlbumRef, ArtistRef};

    fn song(title: &str, artist: &str, album: &str, seconds: u32, kind: &str) -> Song {
        Song {
            video_id: format!("{title}-{artist}"),
            title: title.into(),
            artists: vec![ArtistRef { name: artist.into(), browse_id: None }],
            album: Some(AlbumRef { title: album.into(), browse_id: None }),
            duration_seconds: Some(seconds),
            thumbnail_url: None,
            kind: Some(kind.into()),
            is_live: false,
        }
    }

    fn sample() -> Vec<Song> {
        vec![
            song("さくら", "いきものがかり", "ライフ", 300, "MUSIC_VIDEO_TYPE_ATV"),
            song("Lemon", "米津玄師", "STRAY SHEEP", 250, "MUSIC_VIDEO_TYPE_OMV"),
            song("アイドル", "YOASOBI", "THE BOOK 3", 210, "MUSIC_VIDEO_TYPE_ATV"),
        ]
    }

    #[test]
    fn filter_needs_every_term_and_ignores_case_width_and_kana() {
        let songs = sample();
        assert_eq!(arrange(&songs, "ｌｅｍｏｎ", None, SortKey::Custom, false), vec![1]);
        assert_eq!(arrange(&songs, "サクラ", None, SortKey::Custom, false), vec![0]);
        assert_eq!(arrange(&songs, "yoasobi\u{3000}あいどる", None, SortKey::Custom, false), vec![2]);
        assert_eq!(arrange(&songs, "lemon yoasobi", None, SortKey::Custom, false), Vec::<usize>::new());
        assert_eq!(arrange(&songs, "  ", None, SortKey::Custom, false), vec![0, 1, 2]);
    }

    #[test]
    fn kinds_and_sorts_including_reversed_custom_order() {
        let songs = sample();
        assert_eq!(arrange(&songs, "", Some("MUSIC_VIDEO_TYPE_ATV"), SortKey::Custom, false), vec![0, 2]);
        assert_eq!(arrange(&songs, "", None, SortKey::Custom, true), vec![2, 1, 0]);
        assert_eq!(arrange(&songs, "", None, SortKey::Duration, false), vec![2, 1, 0]);
        assert_eq!(arrange(&songs, "", None, SortKey::Duration, true), vec![0, 1, 2]);
        assert_eq!(arrange(&songs, "", None, SortKey::Title, false), vec![1, 0, 2]);
        // YouTube Music に並べてもらったものは、届いた並びのまま
        assert_eq!(arrange(&songs, "", None, SortKey::Added, true), vec![0, 1, 2]);
    }

    #[test]
    fn choosing_the_same_order_flips_it_and_added_starts_newest_first() {
        assert_eq!(choose((SortKey::Custom, false), SortKey::Custom), (SortKey::Custom, true));
        assert_eq!(choose((SortKey::Custom, true), SortKey::Added), (SortKey::Added, true));
        assert_eq!(choose((SortKey::Added, true), SortKey::Title), (SortKey::Title, false));
        assert_eq!(parse_order(&format_order(SortKey::Duration, true)), (SortKey::Duration, true));
        assert_eq!(parse_order("nonsense"), (SortKey::Custom, false));
        assert_eq!(SortKey::choices(false).len(), 5);
        assert_eq!(SortKey::choices(true).len(), 7);
    }

    #[test]
    fn top_result_prefers_an_exact_artist_then_album_then_the_first_song() {
        let songs = sample();
        let artist = ArtistSummary { browse_id: "UC1".into(), name: "YOASOBI".into(), subtitle: None, thumbnail_url: None };
        let album = AlbumSummary { browse_id: "MPRE1".into(), title: "THE BOOK 3".into(), subtitle: None, thumbnail_url: None };
        assert!(matches!(top_result("ｙｏａｓｏｂｉ", &songs, std::slice::from_ref(&artist), &[]), Some(HomeItem::Artist(_))));
        assert!(matches!(top_result("the book 3", &songs, std::slice::from_ref(&artist), std::slice::from_ref(&album)), Some(HomeItem::Album(_))));
        assert!(matches!(top_result("ほか", &songs, &[artist], &[album]), Some(HomeItem::Song(s)) if s.title == "さくら"));
        assert_eq!(top_result("x", &[], &[], &[]), None);
    }

    #[test]
    fn count_and_length_reads_like_the_mac() {
        assert_eq!(count_and_length(&sample()), "3 曲、13 分");
        let long: Vec<Song> = (0..20).map(|i| song(&format!("t{i}"), "a", "b", 400, "x")).collect();
        assert_eq!(count_and_length(&long), "20 曲、2 時間 13 分");
        assert_eq!(count_and_length(&[]), "0 曲、0 分");
    }
}
