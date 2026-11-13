//! 確かめ用: 自分のプレイリストの browse の、続き（おすすめ・関連）の形を出す（名前・ID は出さない）。
#[path = "../src/keyring.rs"]
mod keyring;
#[path = "../src/runtime.rs"]
mod runtime;
use serde_json::{json, Value};
use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;
fn keys(v: &Value) -> Vec<String> { v.as_object().map(|o| o.keys().cloned().collect()).unwrap_or_default() }
fn conts(v: &Value, path: String, out: &mut Vec<String>) {
    match v {
        Value::Object(m) => for (k, c) in m { let p = format!("{path}.{k}"); if k.ends_with("ContinuationData") || k == "continuationCommand" { out.push(p.clone()); } conts(c, p, out) },
        Value::Array(a) => for (i, c) in a.iter().enumerate() { conts(c, format!("{path}[{i}]"), out) },
        _ => {}
    }
}
fn main() {
    let ctx = glib::MainContext::default();
    let saved = ctx.block_on(keyring::load());
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    repo.client().set_credentials(saved);
    let r = repo.clone();
    ctx.block_on(runtime::run(async move {
        let lists = r.library_playlists().await.unwrap();
        let mut n = 0;
        for p in lists {
            let root = r.client().post("browse", json!({ "browseId": format!("VL{}", p.playlist_id) }), true, &[]).await.unwrap();
            let page = wave_core::music::parsers::playlist_page(&root);
            if !page.editable || page.songs.len() < 5 { continue; }
            n += 1; if n > 1 { break; }
            let mut out = Vec::new(); conts(&root, String::new(), &mut out);
            for o in &out { println!("cont at {o}"); }
            let sec = &root["contents"]["twoColumnBrowseResultsRenderer"]["secondaryContents"]["sectionListRenderer"];
            println!("section keys={:?}", keys(sec));
            for c in sec["contents"].as_array().unwrap_or(&vec![]) { println!("section item={:?}", keys(c)); }
            if let Some(token) = sec["continuations"][0]["nextContinuationData"]["continuation"].as_str() {
                let next = r.client().post("browse", json!({ "continuation": token }), true, &[]).await.unwrap();
                let s = &next["continuationContents"]["sectionListContinuation"];
                println!("next keys={:?} cc={:?}", keys(&next), keys(&next["continuationContents"]));
                for c in s["contents"].as_array().unwrap_or(&vec![]) {
                    for (k, v) in c.as_object().unwrap() {
                        let title = v["header"]["musicCarouselShelfBasicHeaderRenderer"]["title"]["runs"][0]["text"].as_str().or(v["title"]["runs"][0]["text"].as_str()).unwrap_or("?");
                        println!("shelf {k} title={title} items={} item0={:?}", v["contents"].as_array().map(|a| a.len()).unwrap_or(0), v["contents"][0].as_object().map(|o| o.keys().cloned().collect::<Vec<_>>()));
                    }
                }
                let fx = format!("{}/../../core/src/desktopTest/resources/fixtures", env!("CARGO_MANIFEST_DIR"));
                let shelf = &s["contents"][0];
                std::fs::write(format!("{fx}/browse_playlist_suggestions.json"), serde_json::to_string_pretty(&json!({ "continuationContents": { "sectionListContinuation": { "contents": [shelf] } } })).unwrap()).unwrap();
                if let Some(reload) = shelf["musicShelfRenderer"]["continuations"][0]["reloadContinuationData"]["continuation"].as_str() {
                    let again = r.client().post("browse", json!({ "continuation": reload }), true, &[]).await.unwrap();
                    println!("reload cc={:?}", keys(&again["continuationContents"]));
                    let m = &again["continuationContents"]["musicShelfContinuation"];
                    println!("reload items={} same_first={}", m["contents"].as_array().map(|a| a.len()).unwrap_or(0), m["contents"][0] == shelf["musicShelfRenderer"]["contents"][0]);
                    let mut out = Vec::new(); conts(&again, String::new(), &mut out);
                    for o in &out { println!("reload cont at {o}"); }
                    std::fs::write(format!("{fx}/browse_playlist_suggestions_reload.json"), serde_json::to_string_pretty(&json!({ "continuationContents": again["continuationContents"].clone() })).unwrap()).unwrap();
                }
                let mut out = Vec::new(); conts(&next, String::new(), &mut out);
                for o in &out { println!("next cont at {o}"); }
            }
        }
    }));
}
