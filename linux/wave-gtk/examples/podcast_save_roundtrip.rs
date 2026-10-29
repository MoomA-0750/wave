//! 確かめ用: キーリングのログインで、番組をライブラリに保存して一覧を読み直し、外して読み直す（本物のライブラリを一度変えて戻す）。
//! cargo run -p wave-gtk --example podcast_save_roundtrip -- <番組の browseId（MPSP…）>

#[path = "../src/keyring.rs"]
mod keyring;
#[path = "../src/runtime.rs"]
mod runtime;

use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

fn main() {
    let browse_id = std::env::args().nth(1).expect("browse id");
    let creds = gtk::glib::MainContext::default().block_on(keyring::load()).expect("login saved");
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    repo.client().set_credentials(Some(creds));
    runtime::block_on(async move {
        let page = repo.podcast(&browse_id).await.expect("podcast");
        let saved_in = |list: &[wave_core::music::podcast::PodcastSummary]| list.iter().any(|p| p.browse_id == browse_id);
        println!("page saved={:?}", page.saved);
        // 確かめだけ（CHECK_ONLY があれば書き込まない）
        if std::env::var_os("CHECK_ONLY").is_some() {
            println!("in library={:?}", repo.library_podcasts().await.map(|l| saved_in(&l)));
            return;
        }
        println!("save={:?}", repo.set_podcast_saved(&page.playlist_id(), true).await.is_ok());
        println!("in library={:?}", repo.library_podcasts().await.map(|l| saved_in(&l)));
        println!("unsave={:?}", repo.set_podcast_saved(&page.playlist_id(), false).await.is_ok());
        println!("in library={:?}", repo.library_podcasts().await.map(|l| saved_in(&l)));
    });
}
