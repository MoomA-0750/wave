//! アプリで足す「Lo-Fi」のジャンル（Windows の LofiGenre・Android と同じ）。YouTube Music の気分・ジャンルには無いので、
//! ライブ配信と lofi のプレイリストの検索から組んだページを、ジャンルの区切りの先頭のタイルにする。

use regex::Regex;
use std::sync::LazyLock;

use super::models::{HomeItem, HomeShelf, MoodCategory, MoodSection, PlaylistSummary};

/// タイルの params の代わりの印（YouTube の params とぶつからない形）。
pub const PARAMS: &str = "wave:lofi";
pub const TITLE: &str = "Lo-Fi";
/// タイルの色（ARGB）。落ち着いた紫。
pub const COLOR: u32 = 0xFF8E7CC3;
pub const LIVE_QUERY: &str = "lofi hip hop radio";
pub const PLAYLIST_QUERY: &str = "lofi";
pub const LIVE_TITLE: &str = "ライブ配信";
pub const FEATURED_TITLE: &str = "YouTube Music のプレイリスト";
pub const COMMUNITY_TITLE: &str = "みんなのプレイリスト";
pub const MAX_ITEMS: usize = 12;

static GENRE_TITLE: LazyLock<Regex> = LazyLock::new(|| Regex::new("(?i)ジャンル|genre").expect("valid regex"));
static LOFI_TITLE: LazyLock<Regex> = LazyLock::new(|| Regex::new("(?i)lo-?fi|ローファイ|jazzhop|chill|beats").expect("valid regex"));

pub fn category() -> MoodCategory {
    MoodCategory { title: TITLE.into(), params: PARAMS.into(), color: Some(COLOR) }
}

/// ジャンルの格子の先頭に足す。ジャンルの格子は見出しで探し、見つからなければ最後の格子にする。格子が無ければそのまま。二度は足さない。
pub fn add_to(mut sections: Vec<MoodSection>) -> Vec<MoodSection> {
    if sections.is_empty() || sections.iter().any(|s| s.categories.iter().any(|c| c.params == PARAMS)) {
        return sections;
    }
    let index = sections.iter().position(|s| GENRE_TITLE.is_match(&s.title)).unwrap_or(sections.len() - 1);
    sections[index].categories.insert(0, category());
    sections
}

/// YouTube Music のおすすめのプレイリストのうち、lofi らしいもの。1つも無ければ上から順に。
pub fn featured(playlists: Vec<PlaylistSummary>) -> Vec<PlaylistSummary> {
    let matched: Vec<PlaylistSummary> = playlists.iter().filter(|p| LOFI_TITLE.is_match(&p.title)).take(MAX_ITEMS).cloned().collect();
    if matched.is_empty() { playlists.into_iter().take(MAX_ITEMS).collect() } else { matched }
}

pub(crate) fn push_shelf(shelves: &mut Vec<HomeShelf>, title: &str, items: Vec<HomeItem>) {
    if !items.is_empty() {
        shelves.push(HomeShelf { title: title.into(), strapline: None, items });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn section(title: &str) -> MoodSection {
        MoodSection { title: title.into(), categories: vec![MoodCategory { title: "Pop".into(), params: "p".into(), color: None }] }
    }

    #[test]
    fn lofi_goes_first_in_the_genre_section_once() {
        let sections = add_to(vec![section("Moods & moments"), section("Genres")]);
        assert_eq!(sections[1].categories[0].params, PARAMS);
        assert_eq!(sections[0].categories.len(), 1);
        assert_eq!(add_to(sections.clone()), sections);
        // ジャンルの見出しが無ければ最後の区切り
        assert_eq!(add_to(vec![section("A"), section("B")])[1].categories[0].params, PARAMS);
    }
}
