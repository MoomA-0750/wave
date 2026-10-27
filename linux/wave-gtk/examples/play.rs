//! 確かめ用: 検索の1件目を GStreamer で鳴らし、位置が進むかを出す（曲名・URL は出さない）。
//! スピーカーを鳴らさないなら WAVE_AUDIO_SINK=fakesink cargo run -p wave-gtk --example play -- <検索の語>

#[path = "../src/keyring.rs"]
mod keyring;
#[path = "../src/player.rs"]
mod player;
#[path = "../src/runtime.rs"]
mod runtime;

use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;
use wave_core::stream::player::PlayerCipherProvider;
use wave_core::stream::resolver::StreamResolver;

fn main() {
    let query = std::env::args().nth(1).expect("query");
    let main_loop = gtk::glib::MainLoop::new(None, false);
    let context = gtk::glib::MainContext::default();
    let quit = main_loop.clone();
    context.spawn_local(async move {
        let client = InnerTube::new("ja", "JP");
        // ログインしていれば、その情報で解く（ログインしていない WEB の再生は先頭の 1MB ほどで 403 になる）
        let saved = keyring::load().await;
        println!("logged in={}", saved.is_some());
        client.set_credentials(saved);
        let repo = MusicRepository::new(client.clone());
        let page = runtime::run(async move { repo.search_songs(&query, None).await }).await.expect("search");
        let song = page.songs.into_iter().next().expect("no songs");
        let player = player::Player::new(StreamResolver::new(client, PlayerCipherProvider::default()), None);
        let started = std::time::Instant::now();
        match player.play(&song.video_id).await {
            Ok(()) => {
                for _ in 0..6 {
                    gtk::glib::timeout_future_seconds(1).await;
                    println!("t={:.1}s playing={} position={:?}ms duration={:?}ms", started.elapsed().as_secs_f32(), player.is_playing(), player.position_ms(), player.duration_ms());
                }
            }
            Err(e) => println!("play failed: {e}"),
        }
        quit.quit();
    });
    main_loop.run();
}
