import Foundation
import WaveCore

/// マイライブラリの1行（Mac の左の欄・iPhone のライブラリのタブ。先頭の「高く評価した曲」は item が nil）。
struct LibraryEntry: Identifiable, Hashable {
    var title: String
    var subtitle: String?
    var thumbnailUrl: String?
    var item: HomeItem?
    var isLiked = false
    /// 保存したポッドキャストの番組。
    var podcast: PodcastSummary?
    /// 「新しいエピソード」（保存した番組の新着の自動のプレイリスト。ポッドキャストで絞ったときだけ先頭に出す）。
    var isNewEpisodes = false
    var id: String { item?.key ?? podcast.map { "pod-" + $0.browseId } ?? (isNewEpisodes ? "new-episodes" : "liked") }
    var isArtist: Bool { item?.isArtist ?? false }
}

extension LibraryEntry {
    /// 出す行: 種類（playlist・artist・album・podcast、nil はすべて）と語で絞り、並べ（alpha・recent、ほかは追加した順）、先頭に「高く評価した曲」。
    /// matchSubtitle なら語を補足の行（種類・アーティスト）にも当てる（iPhone）。
    @MainActor
    static func list(_ lib: LibraryState, kind: String?, filter: String, sort: String, matchSubtitle: Bool) -> [LibraryEntry] {
        let filter = filter.trimmingCharacters(in: .whitespaces)
        var entries: [LibraryEntry] = []
        if kind == nil || kind == "playlist" {
            entries += lib.playlists.map {
                LibraryEntry(title: $0.title, subtitle: L.t("プレイリスト", "Playlist") + (($0.subtitle ?? "").isEmpty ? "" : " • " + $0.subtitle!),
                             thumbnailUrl: $0.thumbnailUrl, item: .playlist($0))
            }
        }
        if kind == nil || kind == "artist" {
            entries += lib.artists.map { LibraryEntry(title: $0.name, subtitle: L.t("アーティスト", "Artist"), thumbnailUrl: $0.thumbnailUrl, item: .artist($0)) }
        }
        if kind == nil || kind == "album" {
            entries += lib.albums.map {
                LibraryEntry(title: $0.title, subtitle: L.t("アルバム", "Album") + (($0.subtitle ?? "").isEmpty ? "" : " • " + $0.subtitle!),
                             thumbnailUrl: $0.thumbnailUrl, item: .album($0))
            }
        }
        if kind == nil || kind == "podcast" {
            entries += lib.allPodcasts.map {
                LibraryEntry(title: $0.title, subtitle: L.t("ポッドキャスト", "Podcast") + (($0.author ?? "").isEmpty ? "" : " • " + $0.author!),
                             thumbnailUrl: $0.thumbnailUrl, podcast: $0)
            }
        }
        if !filter.isEmpty {
            entries = entries.filter {
                $0.title.localizedCaseInsensitiveContains(filter) || (matchSubtitle && ($0.subtitle ?? "").localizedCaseInsensitiveContains(filter))
            }
        }
        if sort == "alpha" {
            entries.sort { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        } else if sort == "recent" {
            // 最近再生したものを先に、残りは追加した順のまま（Android と同じ）
            var rank: [String: Int] = [:]
            for (i, key) in lib.recentKeys.enumerated() where rank[key] == nil { rank[key] = i }
            entries = entries.enumerated().sorted { a, b in
                let ra = a.element.item.flatMap(LibraryState.keyOf).flatMap { rank[$0] } ?? Int.max
                let rb = b.element.item.flatMap(LibraryState.keyOf).flatMap { rank[$0] } ?? Int.max
                return ra != rb ? ra < rb : a.offset < b.offset
            }.map(\.element)
        }
        // 高く評価した曲はいつも先頭（Spotify の「お気に入りの曲」と同じ）
        let likedTitle = L.t("高く評価した曲", "Liked Songs")
        if (kind == nil || kind == "playlist") && (filter.isEmpty || likedTitle.localizedCaseInsensitiveContains(filter)) {
            entries.insert(LibraryEntry(title: likedTitle, subtitle: L.t("プレイリスト • 自動", "Playlist • Auto"), thumbnailUrl: lib.likedThumbnail, isLiked: true), at: 0)
        }
        // ポッドキャストで絞ったときは「新しいエピソード」を先頭に（高く評価した曲と同じく留める。Android と同じ）
        let newEpisodesTitle = L.t("新しいエピソード", "New episodes")
        if kind == "podcast" && (filter.isEmpty || newEpisodesTitle.localizedCaseInsensitiveContains(filter)) {
            entries.insert(LibraryEntry(title: newEpisodesTitle, subtitle: L.t("プレイリスト • 自動", "Playlist • Auto"), isNewEpisodes: true), at: 0)
        }
        return entries
    }
}
