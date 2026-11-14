//! 確かめ用: 標準入力で渡したログイン（キーリングの中身）で、アルバムのページの保存の状態（AlbumPage::saved）を出し、
//! save・unsave を付ければ保存・外してから読み直して出す。ログインの中身・ID は出さない。
//! secret-tool lookup application xyz.mooma0750.wave kind youtube-music-login | cargo run -p wave-core --example album_save -- <検索の語> [save|unsave]

use std::io::Read;

use wave_core::credentials::Credentials;
use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

#[tokio::main]
async fn main() {
    let query = std::env::args().nth(1).expect("query");
    let action = std::env::args().nth(2);
    let mut secret = String::new();
    std::io::stdin().read_to_string(&mut secret).expect("stdin");
    let client = InnerTube::new("ja", "JP");
    client.set_credentials(Some(Credentials::deserialize(secret.trim()).expect("credentials")));
    let repo = MusicRepository::new(client);
    let album = repo.search_albums(&query, None).await.expect("search").items.into_iter().next().expect("no albums");
    let show = |page: &wave_core::music::models::AlbumPage| {
        let target = page.save_playlist_id.as_deref().map(|s| s.chars().take(8).collect::<String>());
        println!("{} saved={:?} target={:?}", page.title, page.saved, target);
    };
    let page = repo.album(&album.browse_id).await.expect("album");
    show(&page);
    if let Some(action) = action {
        let id = page.save_playlist_id.clone().expect("save playlist id");
        println!("{action}: {:?}", repo.set_playlist_saved(&id, action == "save").await.map(|_| "ok"));
        show(&repo.album(&album.browse_id).await.expect("album"));
    }
}
