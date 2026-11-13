//! 確かめ用: キーリングのログインで自分のプレイリストを1つずつ最後まで読み、ページ数・曲数・終わりを確かめられたかだけを出す（名前・ID は出さない）。

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
    let lists = ctx.block_on(runtime::run(async move { r.library_playlists().await }));
    let lists = match lists {
        Ok(l) => l,
        Err(e) => return println!("library failed: {e:?}"),
    };
    println!("playlists={}", lists.len());
    for (i, p) in lists.iter().enumerate().take(40) {
        let r = repo.clone();
        let id = p.playlist_id.clone();
        let out = ctx.block_on(runtime::run(async move {
            let mut token: Option<String> = None;
            let mut pages = 0;
            let mut songs = 0;
            loop {
                match r.playlist(&id, token.as_deref()).await {
                    Err(e) => return format!("error page={pages} {e:?}"),
                    Ok(page) => {
                        pages += 1;
                        songs += page.songs.len();
                        match page.continuation {
                            None if page.end_confirmed => return format!("ok pages={pages} songs={songs} editable={}", page.editable),
                            None => return format!("UNCONFIRMED pages={pages} songs={songs} last_page_songs={}", page.songs.len()),
                            next => token = next,
                        }
                        if pages >= 50 { return format!("too many pages songs={songs}"); }
                    }
                }
            }
        }));
        println!("#{i} {out}");
    }
}
