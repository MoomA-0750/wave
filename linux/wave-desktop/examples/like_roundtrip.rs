//! 確かめ用: キーリングのログインで、検索の1件目を高く評価して読み直し、外して読み直す（本物の評価を一度付けて戻す）。
//! cargo run -p wave-desktop --example like_roundtrip -- <検索の語>

#[path = "../src/keyring.rs"]
mod keyring;
#[path = "../src/runtime.rs"]
mod runtime;

use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

fn main() {
    let query = std::env::args().nth(1).expect("query");
    let creds = glib::MainContext::default().block_on(keyring::load()).expect("login saved");
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    repo.client().set_credentials(Some(creds));
    runtime::block_on(async move {
        let song = repo.search_songs(&query, None).await.expect("search").songs.into_iter().next().expect("no songs");
        let before = repo.like_status(&song.video_id).await;
        println!("before={before:?}");
        println!("like={:?}", repo.set_liked(&song.video_id, true).await.is_ok());
        println!("after like={:?}", repo.like_status(&song.video_id).await);
        // 元が評価済みでなければ外して戻す
        if !matches!(before, Ok(Some(wave_core::music::models::LikeStatus::Like))) {
            println!("unlike={:?}", repo.set_liked(&song.video_id, false).await.is_ok());
            println!("after unlike={:?}", repo.like_status(&song.video_id).await);
        }
    });
}
