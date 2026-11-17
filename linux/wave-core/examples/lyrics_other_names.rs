//! 確かめ用: 検索の1件目の曲について、LRCLIB の候補が元の名前では一致せず、もう1つの言語での名前（別名）で一致するかを出す。
//! 歌詞の本文は出さない（一致したか・候補の数・アーティスト名・行数だけ）。ログインはしない。
//! cargo run -p wave-core --example lyrics_other_names -- <検索の語>

use std::collections::BTreeMap;

use wave_core::innertube::InnerTube;
use wave_core::lyrics::lrclib::{LrclibClient, LrclibResult, search_titles};
use wave_core::music::repository::MusicRepository;

#[tokio::main]
async fn main() {
    let query = std::env::args().nth(1).expect("query");
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    let song = repo.search_songs(&query, None).await.expect("search").songs.into_iter().next().expect("no songs");
    let artist = song.artists[0].name.clone();
    println!("song title={} artist={} duration={:?}", song.title, artist, song.duration_seconds);

    // LRCLIB の検索の候補（アーティスト名付き・曲名だけ）のアーティスト名と数
    let http = reqwest::Client::new();
    for t in search_titles(&song.title) {
        for with_artist in [true, false] {
            let mut q = vec![("track_name", t.as_str())];
            if with_artist {
                q.push(("artist_name", artist.as_str()));
            }
            let tracks: Vec<serde_json::Value> = http.get(url::Url::parse_with_params("https://lrclib.net/api/search", &q).unwrap()).header("user-agent", "wave-linux-check").send().await.unwrap().json().await.unwrap();
            let mut artists: BTreeMap<String, usize> = BTreeMap::new();
            for tr in tracks.iter().filter(|tr| tr["syncedLyrics"].as_str().is_some_and(|s| !s.is_empty())) {
                *artists.entry(tr["artistName"].as_str().unwrap_or("").to_string()).or_default() += 1;
            }
            println!("lrclib search title={t:?} with_artist={with_artist}: {} candidates, synced by artist {:?}", tracks.len(), artists);
        }
    }

    let other = repo.names_in_other_language(&song.video_id).await;
    println!("other names={other:?}");
    let describe = |r: LrclibResult| match r {
        LrclibResult::Found(l) => format!("found {} lines", l.len()),
        LrclibResult::NotFound => "not found".into(),
        LrclibResult::Failed => "failed".into(),
    };
    let album = song.album.as_ref().map(|a| a.title.as_str());
    let lrclib = LrclibClient::default();
    println!("without other names: {}", describe(lrclib.synced(&song.title, &artist, album, song.duration_seconds).await));
    let calls = std::sync::atomic::AtomicUsize::new(0);
    let with = lrclib
        .synced_with_other_names(&song.title, &artist, album, song.duration_seconds, || {
            calls.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            let repo = repo.clone();
            let id = song.video_id.clone();
            async move { repo.names_in_other_language(&id).await.ok().flatten() }
        })
        .await;
    println!("with other names: {} (asked for other names {} times)", describe(with), calls.load(std::sync::atomic::Ordering::Relaxed));
}
