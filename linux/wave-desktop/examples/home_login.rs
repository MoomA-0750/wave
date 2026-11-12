//! 確かめ用: キーリングのログインでホームを読み、件数か失敗の種類だけを出す（中身・ID は出さない）。

#[path = "../src/keyring.rs"]
mod keyring;
#[path = "../src/runtime.rs"]
mod runtime;

use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

fn main() {
    let ctx = glib::MainContext::default();
    let saved = ctx.block_on(keyring::load());
    println!("login saved={}", saved.is_some());
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    repo.client().set_credentials(saved);
    let r = repo.clone();
    let account = ctx.block_on(runtime::run(async move { r.account().await.map(|_| ()) }));
    println!("account ok={:?}", account);
    let r = repo.clone();
    let anon = MusicRepository::new(InnerTube::new("ja", "JP"));
    let a = ctx.block_on(runtime::run(async move { anon.home(None, None).await.map(|p| p.shelves.len()) }));
    println!("anonymous home={a:?}");
    let result = ctx.block_on(runtime::run(async move { r.home(None, None).await }));
    match result {
        Ok(page) => println!("home shelves={} chips={} continuation={}", page.shelves.len(), page.chips.len(), page.continuation.is_some()),
        Err(e) => println!("home failed: {e:?}"),
    }
}
