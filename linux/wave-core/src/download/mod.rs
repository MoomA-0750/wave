//! ダウンロード（ADR-0016。Windows の Wave.Core.Downloads と同じ考え）。目録（何を取っているか）・置き場（曲ごとのファイル）・1曲を取る仕組み。

pub mod downloader;
pub mod manifest;
pub mod smart;
pub mod store;
