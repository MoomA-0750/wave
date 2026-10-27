//! 確かめ用: 検索の1件目の歌詞を探し、どこから取れたかと行数だけを出す（本文は出さない）。
//! cargo run -p wave-core --example lyrics -- <検索の語>

use wave_core::innertube::InnerTube;
use wave_core::lyrics::lrclib::LrclibClient;
use wave_core::lyrics::service::{LyricsResult, LyricsService};
use wave_core::lyrics::timed::TimedLyricsClient;
use wave_core::lyrics::SongLyrics;
use wave_core::music::repository::MusicRepository;

#[tokio::main]
async fn main() {
    let query = std::env::args().nth(1).expect("query");
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    let song = repo.search_songs(&query, None).await.expect("search").songs.into_iter().next().expect("no songs");
    let tab = repo.lyrics_browse_id(&song.video_id).await;
    println!("tab found={:?}", tab.as_ref().map(|t| t.is_some()));
    if let Ok(Some(id)) = &tab {
        let timed = TimedLyricsClient::new("ja", "JP").timed(id).await;
        println!("youtube timed={}", match timed { wave_core::lyrics::timed::TimedResult::Found(_) => "found", wave_core::lyrics::timed::TimedResult::NotFound => "not found", _ => "failed" });
    }
    let lrclib = LrclibClient::default().synced(&song.title, &song.artists[0].name, song.album.as_ref().map(|a| a.title.as_str()), song.duration_seconds).await;
    println!("lrclib={}", match lrclib { wave_core::lyrics::lrclib::LrclibResult::Found(l) => format!("found {} lines", l.len()), wave_core::lyrics::lrclib::LrclibResult::NotFound => "not found".into(), _ => "failed".into() });
    let service = LyricsService::new(repo.clone(), LrclibClient::default(), TimedLyricsClient::new("ja", "JP"));
    match service.lyrics(&song).await {
        LyricsResult::Found(SongLyrics::Synced { lines, source }) => println!("result synced {} lines source={source:?}", lines.len()),
        LyricsResult::Found(SongLyrics::Plain { .. }) => println!("result plain"),
        other => println!("result {other:?}"),
    }
}
