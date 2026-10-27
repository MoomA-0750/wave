//! 確かめ用: キーリングにログインの情報があるかだけを出す（中身は出さない）。

#[path = "../src/keyring.rs"]
mod keyring;
#[path = "../src/runtime.rs"]
mod runtime;

fn main() {
    let found = gtk::glib::MainContext::default().block_on(keyring::load());
    println!("login saved={}", found.is_some());
}
