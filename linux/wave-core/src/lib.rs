//! wave の共通部分（YouTube Music とのやり取り・再生の URL・歌詞・キュー）。
//! 画面の部品（GTK など）には頼らない。後で UniFFI でほかの OS からも呼べるよう、外へ出す型は単純にしておく。

/// この core の版（画面に出して、どの core で動いているかを確かめる）。
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
