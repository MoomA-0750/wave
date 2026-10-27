//! core の通信は tokio の上で動かし、結果を GTK の主スレッドへ戻す。

use std::future::Future;
use std::sync::LazyLock;

static RUNTIME: LazyLock<tokio::runtime::Runtime> =
    LazyLock::new(|| tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().expect("tokio runtime"));

/// 裏（tokio）で動かし、終わりを主スレッドで待てる形にする。
pub async fn run<T: Send + 'static>(future: impl Future<Output = T> + Send + 'static) -> T {
    RUNTIME.spawn(future).await.expect("background task panicked")
}

/// 主スレッドでも tokio の中でもない糸（GStreamer の流しの糸）から、裏の仕事を待つ。
pub fn block_on<T>(future: impl Future<Output = T>) -> T {
    RUNTIME.block_on(future)
}

/// 裏（tokio）で動かしっぱなしにする（止めるときは返したものの abort）。
pub fn spawn<T: Send + 'static>(future: impl Future<Output = T> + Send + 'static) -> tokio::task::JoinHandle<T> {
    RUNTIME.spawn(future)
}
