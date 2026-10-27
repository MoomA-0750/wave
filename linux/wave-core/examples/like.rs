//! 確かめ用: ログインせずに検索の1件目の評価を読む（ログインしていないので、読めても「評価なし」になる）。
//! cargo run -p wave-core --example like -- <検索の語>

use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

#[tokio::main]
async fn main() {
    let query = std::env::args().nth(1).expect("query");
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    let song = repo.search_songs(&query, None).await.expect("search").songs.into_iter().next().expect("no songs");
    println!("like status={:?}", repo.like_status(&song.video_id).await);
}
