import AppIntents

/// ショートカットのアプリ・Siri・Spotlight に出す wave の操作（頼みの中身は Widget/WidgetShared.swift）。
/// 言い方は英語をキーにして、ja.lproj/AppShortcuts.strings で日本語にする。
struct WaveShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: PlayPlaylistShortcutIntent(), phrases: [
            "Play \(\.$playlist) in \(.applicationName)",
            "Play a playlist in \(.applicationName)",
        ], shortTitle: "Play a Playlist", systemImageName: "music.note.list")
        AppShortcut(intent: PlayItemShortcutIntent(), phrases: [
            "Play something in \(.applicationName)",
        ], shortTitle: "Play Something", systemImageName: "play.circle")
        AppShortcut(intent: TogglePlayIntent(), phrases: [
            "Play or pause \(.applicationName)",
        ], shortTitle: "Play/Pause", systemImageName: "playpause.fill")
        AppShortcut(intent: NextTrackIntent(), phrases: [
            "Skip to the next song in \(.applicationName)",
        ], shortTitle: "Next Track", systemImageName: "forward.fill")
        AppShortcut(intent: PreviousTrackIntent(), phrases: [
            "Go back to the previous song in \(.applicationName)",
        ], shortTitle: "Previous Track", systemImageName: "backward.fill")
        AppShortcut(intent: ToggleLikeIntent(), phrases: [
            "Like this song in \(.applicationName)",
        ], shortTitle: "Like", systemImageName: "hand.thumbsup")
        AppShortcut(intent: GetNowPlayingIntent(), phrases: [
            "What's playing in \(.applicationName)",
        ], shortTitle: "Get Current Song", systemImageName: "music.note")
    }
}
