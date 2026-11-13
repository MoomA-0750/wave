//! 確かめ用: キーリングのログインで検索の1件目の next を読み、評価の入っている場所（JSON の道）と値だけを出す。
//! cargo run -p wave-desktop --example next_probe -- <検索の語>

#[path = "../src/keyring.rs"]
mod keyring;
#[path = "../src/runtime.rs"]
mod runtime;

use serde_json::{json, Value};
use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

fn walk(v: &Value, path: String, out: &mut Vec<String>) {
    match v {
        Value::Object(m) => {
            for (k, c) in m {
                let p = format!("{path}.{k}");
                if k == "likeStatus" || k == "likeButtonRenderer" || k == "likeButtonViewModel" || k == "toggleButtonViewModel" && path.contains("ike") {
                    let shown = if c.is_string() { c.to_string() } else { "{…}".into() };
                    out.push(format!("{p} = {shown}"));
                }
                walk(c, p, out);
            }
        }
        Value::Array(a) => {
            for (i, c) in a.iter().enumerate() {
                walk(c, format!("{path}[{i}]"), out);
            }
        }
        _ => {}
    }
}

fn main() {
    let query = std::env::args().nth(1).expect("query");
    let ctx = glib::MainContext::default();
    let saved = ctx.block_on(keyring::load());
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    repo.client().set_credentials(saved);
    let r = repo.clone();
    ctx.block_on(runtime::run(async move {
        let song = r.search_songs(&query, None).await.expect("search").songs.into_iter().next().expect("no songs");
        println!("like_status={:?}", r.like_status(&song.video_id).await);
        let root = r.client().post("next", json!({ "videoId": song.video_id, "contentCheckOk": true, "racyCheckOk": true }), true, &[]).await.expect("next");
        let mut out = Vec::new();
        walk(&root, String::new(), &mut out);
        for l in out.iter().take(30) { println!("{l}"); }
        let lb = &root["playerOverlays"]["playerOverlayRenderer"]["videoActionBar"]["videoActionBarViewModel"]["buttons"][0]["buttonViewModel"]["segmentedLikeDislikeButtonViewModel"]["likeButtonViewModel"]["likeButtonViewModel"];
        println!("entity={}", lb["likeStatusEntity"]);
        println!("toggle keys={:?}", lb["toggleButtonViewModel"]["toggleButtonViewModel"].as_object().map(|o| o.keys().cloned().collect::<Vec<_>>()));
        println!("mutation={}", root["frameworkUpdates"]["entityBatchUpdate"]["mutations"][0]);
        let s = lb.to_string();
        println!("mentions videoId={}", s.contains(&song.video_id));
        fn find(v: &Value, path: String, id: &str) { match v { Value::Object(m) => for (k, c) in m { find(c, format!("{path}.{k}"), id) }, Value::Array(a) => for (i, c) in a.iter().enumerate() { find(c, format!("{path}[{i}]"), id) }, Value::String(x) if x == id => println!("vid at {path}"), _ => {} } }
        find(lb, String::new(), &song.video_id);
        if let Ok(out) = std::env::var("NEXT_PROBE_SAVE") {
            let bar = &root["playerOverlays"]["playerOverlayRenderer"]["videoActionBar"]["videoActionBarViewModel"]["buttons"][0];
            let trimmed = json!({ "playerOverlays": { "playerOverlayRenderer": { "videoActionBar": { "videoActionBarViewModel": { "buttons": [bar] } } } } });
            std::fs::write(out, serde_json::to_string_pretty(&trimmed).unwrap()).unwrap();
        }
        let overlay = &root["playerOverlays"]["playerOverlayRenderer"];
        println!("overlay keys={:?}", overlay.as_object().map(|o| o.keys().cloned().collect::<Vec<_>>()));
        if let Some(actions) = overlay["actions"].as_array() {
            for a in actions { println!("action keys={:?}", a.as_object().map(|o| o.keys().cloned().collect::<Vec<_>>())); }
        }
    }));
}
