//! 確かめ用: ログインせずに1曲の再生の URL を解き、形式だけを出す（URL は出さない）。
//! cargo run -p wave-core --example resolve -- <videoId か検索の語>

use wave_core::innertube::InnerTube;
use wave_core::stream::player::PlayerCipherProvider;
use wave_core::stream::resolver::StreamResolver;

#[tokio::main]
async fn main() {
    let arg = std::env::args().nth(1).expect("videoId or query");
    let client = InnerTube::new("ja", "JP");
    // 11 文字の ID でなければ、検索の1件目を使う
    let video_id = if arg.len() == 11 {
        arg
    } else {
        let page = wave_core::music::repository::MusicRepository::new(client.clone()).search_songs(&arg, None).await.expect("search");
        page.songs.first().expect("no songs").video_id.clone()
    };
    let started = std::time::Instant::now();
    let resolver = StreamResolver::new(client, PlayerCipherProvider::default());
    match resolver.resolve(&video_id).await {
        Ok(stream) => {
            println!("{stream:?} length={:?} took={:?}", stream.content_length, started.elapsed());
            // URL が実際に読めるか（範囲の付け方ごとに、状態だけ）
            let http = reqwest::Client::new();
            let ua = wave_core::innertube::USER_AGENT;
            // ログインしていない WEB の再生は、po_token が無いと先頭の 1MB ほどしか読ませない（その先は 403）
            for (label, range) in [("none", None), ("open", Some("bytes=0-")), ("start", Some("bytes=0-524287")), ("middle", Some("bytes=2097152-2621439"))] {
                let mut request = http.get(stream.reveal_url()).header("user-agent", ua);
                if let Some(range) = range {
                    request = request.header("range", range);
                }
                println!("fetch {label} status={:?}", request.send().await.map(|r| r.status().as_u16()));
            }
        }
        Err(e) => println!("resolve failed: {e}"),
    }
}
