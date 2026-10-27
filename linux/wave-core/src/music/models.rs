//! 画面に依らない曲・棚などの表現（Windows の Wave.Core.Music.Models と同じ形）。

/// 曲。
#[derive(Debug, Clone, PartialEq)]
pub struct Song {
    pub video_id: String,
    pub title: String,
    pub artists: Vec<ArtistRef>,
    pub album: Option<AlbumRef>,
    pub duration_seconds: Option<u32>,
    pub thumbnail_url: Option<String>,
    /// 曲の種類（musicVideoType。MUSIC_VIDEO_TYPE_ATV は音源、_OMV は公式の MV、_UGC は投稿者の動画）。
    pub kind: Option<String>,
    /// ライブ配信（長さもシークも無い）。
    pub is_live: bool,
}

impl Song {
    pub fn artists_text(&self) -> String {
        self.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", ")
    }

    /// 「アーティスト • アルバム」の1行。
    pub fn subtitle(&self) -> String {
        [Some(self.artists_text()), self.album.as_ref().map(|a| a.title.clone())]
            .into_iter()
            .flatten()
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
            .join(" • ")
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ArtistRef {
    pub name: String,
    pub browse_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct AlbumRef {
    pub title: String,
    pub browse_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SongPage {
    pub songs: Vec<Song>,
    pub continuation: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PlaylistSummary {
    pub playlist_id: String,
    pub title: String,
    pub subtitle: Option<String>,
    pub thumbnail_url: Option<String>,
    pub editable: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct AlbumSummary {
    pub browse_id: String,
    pub title: String,
    pub subtitle: Option<String>,
    pub thumbnail_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ArtistSummary {
    pub browse_id: String,
    pub name: String,
    pub subtitle: Option<String>,
    pub thumbnail_url: Option<String>,
}

/// ホームの棚の1枚。曲・アルバム・プレイリスト・アーティストが混ざる。
#[derive(Debug, Clone, PartialEq)]
pub enum HomeItem {
    Song(Song),
    Album(AlbumSummary),
    Playlist(PlaylistSummary),
    Artist(ArtistSummary),
}

impl HomeItem {
    pub fn title(&self) -> &str {
        match self {
            HomeItem::Song(s) => &s.title,
            HomeItem::Album(a) => &a.title,
            HomeItem::Playlist(p) => &p.title,
            HomeItem::Artist(a) => &a.name,
        }
    }

    pub fn subtitle(&self) -> Option<String> {
        match self {
            HomeItem::Song(s) => Some(s.subtitle()),
            HomeItem::Album(a) => a.subtitle.clone(),
            HomeItem::Playlist(p) => p.subtitle.clone(),
            HomeItem::Artist(a) => a.subtitle.clone(),
        }
    }

    pub fn thumbnail_url(&self) -> Option<&str> {
        match self {
            HomeItem::Song(s) => s.thumbnail_url.as_deref(),
            HomeItem::Album(a) => a.thumbnail_url.as_deref(),
            HomeItem::Playlist(p) => p.thumbnail_url.as_deref(),
            HomeItem::Artist(a) => a.thumbnail_url.as_deref(),
        }
    }
}

/// ホームの棚。見出しは応答の表示のまま（言語に依るので判定に使わない）。
#[derive(Debug, Clone, PartialEq)]
pub struct HomeShelf {
    pub title: String,
    pub strapline: Option<String>,
    pub items: Vec<HomeItem>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct HomeChip {
    pub title: String,
    pub params: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct HomePage {
    pub shelves: Vec<HomeShelf>,
    pub continuation: Option<String>,
    pub chips: Vec<HomeChip>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PlaylistPage {
    pub title: Option<String>,
    pub songs: Vec<Song>,
    pub continuation: Option<String>,
    pub subtitle: Option<String>,
    pub thumbnail_url: Option<String>,
}

/// 次に流れる曲（ラジオ）。
#[derive(Debug, Clone, PartialEq)]
pub struct UpNextPage {
    pub songs: Vec<Song>,
    pub continuation: Option<String>,
    pub playlist_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct AlbumPage {
    pub browse_id: String,
    pub title: String,
    pub subtitle: Option<String>,
    pub artists: Vec<ArtistRef>,
    pub thumbnail_url: Option<String>,
    pub playlist_id: Option<String>,
    pub songs: Vec<Song>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct AlbumShelf {
    pub title: String,
    pub albums: Vec<AlbumSummary>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ArtistPage {
    pub name: String,
    pub thumbnail_url: Option<String>,
    pub top_songs: Vec<Song>,
    /// 「すべての曲」のプレイリスト。
    pub all_songs_playlist_id: Option<String>,
    pub album_shelves: Vec<AlbumShelf>,
    /// 動画の棚（MV・YouTube の動画）。
    pub videos: Vec<Song>,
}

/// ログイン中のアカウント（名前と写真）。
#[derive(Debug, Clone, PartialEq)]
pub struct Account {
    pub name: Option<String>,
    pub photo_url: Option<String>,
}

/// 曲の評価。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LikeStatus {
    Like,
    Dislike,
    Indifferent,
}
