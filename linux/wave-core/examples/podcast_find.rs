//! 確かめ用: 番組を検索して、最初の数件の browse_id と題を出す（ログインなし）。
//! cargo run -p wave-core --example podcast_find -- <検索の語>
use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

#[tokio::main]
async fn main() {
    let query = std::env::args().nth(1).expect("query");
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    for p in repo.search_podcasts(&query, None).await.expect("search").items.into_iter().take(3) {
        println!("{} {}", p.browse_id, p.title);
    }
}
