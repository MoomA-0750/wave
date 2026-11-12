//! 確かめ用: 保存したログインと、ブラウザにいまある cookie を比べ、値が変わった cookie の名前だけを出す（値は出さない）。
//! それぞれでアカウントを読めるかも出す。cargo run -p wave-desktop --example login_diag

use std::collections::BTreeMap;

use wave_core::credentials::Credentials;
use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;
use wave_desktop::{browser_login, keyring, runtime};

fn pairs(header: &str) -> BTreeMap<String, String> {
    header.split("; ").filter_map(|p| p.split_once('=')).map(|(k, v)| (k.to_string(), v.to_string())).collect()
}

fn account_ok(ctx: &glib::MainContext, creds: Credentials) -> String {
    let repo = MusicRepository::new(InnerTube::new("ja", "JP"));
    repo.client().set_credentials(Some(creds));
    let r = repo.clone();
    format!("{:?}", ctx.block_on(runtime::run(async move { r.account().await.map(|_| ()) })))
}

fn main() {
    let ctx = glib::MainContext::default();
    let saved = ctx.block_on(keyring::load()).expect("no saved login");
    println!("saved obtained_at={}", saved.obtained_at_epoch_ms);
    println!("saved account={}", account_ok(&ctx, saved.clone()));
    let profiles = browser_login::find(&glib::home_dir());
    let want = std::env::args().nth(1).unwrap_or_else(|| "Zen".into());
    let profile = profiles.iter().find(|p| p.label.starts_with(&want)).expect("profile");
    let header = ctx.block_on(browser_login::read_cookie(profile)).expect("read");
    let (old, new) = (pairs(saved.reveal_cookie()), pairs(&header));
    let changed: Vec<&String> = new.keys().filter(|k| old.get(*k) != new.get(*k)).collect();
    let missing: Vec<&String> = old.keys().filter(|k| !new.contains_key(*k)).collect();
    println!("browser={} changed={changed:?} only_in_saved={missing:?}", profile.label);
    let fresh = Credentials::from_login(&header, saved.visitor_data.as_deref(), saved.reveal_data_sync_id(), 0).expect("fresh");
    println!("browser account={}", account_ok(&ctx, fresh));
}
