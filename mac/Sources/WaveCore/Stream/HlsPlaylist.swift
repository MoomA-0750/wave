import Foundation

/// 断片化 MP4（YouTube の DASH 用のファイル）の目次（sidx の箱）。区切りごとのバイトの場所と長さ。
public struct SegmentIndex: Equatable, Sendable {
    public struct Segment: Equatable, Sendable {
        public var offset: Int64
        public var size: Int64
        public var duration: Double
    }

    public var segments: [Segment]

    /// indexStart からの sidx の箱のバイトを読む。読めない形（ほかの sidx を指すもの・長さが合わないもの）なら nil。
    public static func parse(_ data: Data, indexStart: Int64) -> SegmentIndex? {
        let b = [UInt8](data)
        func u32(_ at: Int) -> UInt64? {
            guard at >= 0, at + 4 <= b.count else { return nil }
            return UInt64(b[at]) << 24 | UInt64(b[at + 1]) << 16 | UInt64(b[at + 2]) << 8 | UInt64(b[at + 3])
        }
        func u64(_ at: Int) -> UInt64? {
            guard let hi = u32(at), let lo = u32(at + 4) else { return nil }
            return hi << 32 | lo
        }
        guard let boxSize = u32(0), boxSize >= 32, Int(boxSize) <= b.count, b.count >= 8, String(bytes: b[4..<8], encoding: .ascii) == "sidx" else { return nil }
        let version = b[8]
        guard let timescale = u32(16), timescale > 0 else { return nil }
        var at = 20
        let firstOffset: UInt64
        if version == 0 {
            guard let first = u32(at + 4) else { return nil }
            firstOffset = first
            at += 8
        } else {
            guard let first = u64(at + 8) else { return nil }
            firstOffset = first
            at += 16
        }
        guard at + 4 <= b.count else { return nil }
        let count = Int(b[at + 2]) << 8 | Int(b[at + 3])
        at += 4
        guard count > 0, at + count * 12 <= Int(boxSize) else { return nil }
        // 区切りは sidx の箱のすぐ後（から first_offset 先）に並ぶ
        var offset = indexStart + Int64(boxSize) + Int64(firstOffset)
        var segments: [Segment] = []
        segments.reserveCapacity(count)
        for i in 0..<count {
            guard let ref = u32(at + i * 12), let duration = u32(at + i * 12 + 4) else { return nil }
            // 上の1ビットが立っているものは別の sidx を指す（YouTube の音声では使われない）
            guard ref & 0x8000_0000 == 0 else { return nil }
            let size = Int64(ref & 0x7FFF_FFFF)
            segments.append(Segment(offset: offset, size: size, duration: Double(duration) / Double(timescale)))
            offset += size
        }
        return SegmentIndex(segments: segments)
    }
}

/// 断片化 MP4 を1本の HLS（fMP4・バイト範囲）の再生の一覧にする。AVPlayer はこれを読むと、ファイル全体を先に読まずに
/// 区切りごとに取りながら鳴らす（MP4 のまま渡すと、長さを知るためにファイルを最後まで読んでから鳴り始める）。
public enum HlsPlaylist {
    public static func build(_ index: SegmentIndex, initEnd: Int64, mediaUrl: String) -> String {
        let target = Int((index.segments.map(\.duration).max() ?? 1).rounded(.up))
        var lines = [
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-TARGETDURATION:\(max(1, target))",
            "#EXT-X-MEDIA-SEQUENCE:0",
            "#EXT-X-PLAYLIST-TYPE:VOD",
            "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-MAP:URI=\"\(mediaUrl)\",BYTERANGE=\"\(initEnd + 1)@0\"",
        ]
        for segment in index.segments {
            lines.append(String(format: "#EXTINF:%.3f,", segment.duration))
            lines.append("#EXT-X-BYTERANGE:\(segment.size)@\(segment.offset)")
            lines.append(mediaUrl)
        }
        lines.append("#EXT-X-ENDLIST")
        return lines.joined(separator: "\n") + "\n"
    }
}
