//! 確かめ用: ログインせずにホームと検索を読み、件数だけを出す（中身・ID は出さない）。
//! cargo run -p wave-core --example probe

use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

#[tokio::main]
async fn main() {
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    match repo.home(None, None).await {
        Ok(page) => println!("home shelves={} chips={} continuation={}", page.shelves.len(), page.chips.len(), page.continuation.is_some()),
        Err(e) => println!("home failed: {e}"),
    }
    match repo.search_songs("supercell", None).await {
        Ok(page) => println!("search songs={} continuation={}", page.songs.len(), page.continuation.is_some()),
        Err(e) => println!("search failed: {e}"),
    }
}
