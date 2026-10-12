import Foundation
import WaveCore

/// 曲を保存するときの形（設定の検索履歴・キューの持ち越しに使う）。
struct SongRecord: Codable, Hashable {
    var videoId: String
    var title: String
    var artists: [ArtistRecord]
    var album: AlbumRecord?
    var durationSeconds: Int?
    var thumbnailUrl: String?
    var kind: String?
    /// ライブ配信（無ければ false。前の版で保存したものにはこの項目が無い）。
    var isLive: Bool?

    struct ArtistRecord: Codable, Hashable {
        var name: String
        var browseId: String?
    }

    struct AlbumRecord: Codable, Hashable {
        var title: String
        var browseId: String?
    }

    init(_ song: Song) {
        videoId = song.videoId
        title = song.title
        artists = song.artists.map { ArtistRecord(name: $0.name, browseId: $0.browseId) }
        album = song.album.map { AlbumRecord(title: $0.title, browseId: $0.browseId) }
        durationSeconds = song.durationSeconds
        thumbnailUrl = song.thumbnailUrl
        kind = song.kind
        isLive = song.isLive ? true : nil
    }

    var song: Song {
        Song(videoId: videoId, title: title, artists: artists.map { ArtistRef(name: $0.name, browseId: $0.browseId) },
             album: album.map { AlbumRef(title: $0.title, browseId: $0.browseId) }, durationSeconds: durationSeconds,
             thumbnailUrl: thumbnailUrl, kind: kind, isLive: isLive == true)
    }
}
