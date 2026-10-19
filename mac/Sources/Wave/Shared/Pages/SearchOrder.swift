import Foundation
import WaveCore

/// 検索の結果の並べ方（Android の SearchOrder と同じ）。Mac・iPhone の検索で使う。
enum SearchOrder {
    /// トップの検索結果: アーティストの名前が検索語と同じならアーティスト、ほかは最初の曲。
    static func topResult(_ q: String, _ songs: [Song], _ artists: [ArtistSummary], _ albums: [AlbumSummary]) -> HomeItem? {
        let key = normalize(q)
        if let artist = artists.first(where: { normalize($0.name) == key }) { return .artist(artist) }
        if let album = albums.first(where: { normalize($0.title) == key }) { return .album(album) }
        if let song = songs.first { return .song(song) }
        if let artist = artists.first { return .artist(artist) }
        return albums.first.map { .album($0) }
    }

    static func normalize(_ text: String) -> String {
        text.precomposedStringWithCompatibilityMapping.lowercased().filter { !$0.isWhitespace }
    }
}
