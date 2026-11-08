//! QML を Rust の実行ファイルに組み込む（qrc の qrc:/qt/qml/wave/qml/…）。C++ は窓の写しなど小さな手伝いだけ。

use cxx_qt_build::{CxxQtBuilder, QmlFile, QmlModule};

const QML: &[&str] = &[
    "qml/Main.qml",
    "qml/Shell.qml",
    "qml/LoginPage.qml",
    "qml/TopBar.qml",
    "qml/LibraryPane.qml",
    "qml/HomePage.qml",
    "qml/SearchPage.qml",
    "qml/CollectionPage.qml",
    "qml/ArtistPage.qml",
    "qml/LyricsPage.qml",
    "qml/SettingsPage.qml",
    "qml/NowPlayingPane.qml",
    "qml/QueuePane.qml",
    "qml/PlayerBar.qml",
    "qml/FullView.qml",
    "qml/Art.qml",
    "qml/Card.qml",
    "qml/Shelf.qml",
    "qml/TrackRow.qml",
    "qml/Chip.qml",
    "qml/IconButton.qml",
    "qml/PlayButton.qml",
    "qml/Btn.qml",
    "qml/WMenu.qml",
    "qml/WDialog.qml",
    "qml/AiPane.qml",
    "qml/DevicesPage.qml",
    "qml/DownloadsPage.qml",
    "qml/PodcastPage.qml",
    "qml/FeedSearchPage.qml",
    "qml/QrCode.qml",
    "qml/VideoView.qml",
    "qml/SongMenu.qml",
    "qml/Section.qml",
];

fn main() {
    // Style は色・丸み・行き先（ページを開く）を持つ単一の部品
    let mut module = QmlModule::new("wave").qml_file(QmlFile::from("qml/Style.qml").singleton(true));
    for file in QML {
        module = module.qml_file(*file);
    }
    CxxQtBuilder::new_qml_module(module)
        .qt_module("Gui")
        .qt_module("Quick")
        .qt_module("QuickControls2")
        .qt_module("Network")
        .include_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/cpp"))
        .cpp_file("cpp/helpers.cpp")
        .cpp_file("cpp/videoitem.h")
        .cpp_file("cpp/videoitem.cpp")
        .file("src/bridge.rs")
        .build();
}
