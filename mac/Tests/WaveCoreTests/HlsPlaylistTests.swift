import XCTest
@testable import WaveCore

final class HlsPlaylistTests: XCTestCase {
    /// sidx（version 0）の箱を作る。区切りは (大きさ, 長さ（timescale の単位）)。
    private func sidx(timescale: UInt32, firstOffset: UInt32, refs: [(UInt32, UInt32)]) -> Data {
        var body = Data()
        func put(_ v: UInt32) { withUnsafeBytes(of: v.bigEndian) { body.append(contentsOf: $0) } }
        body.append(contentsOf: [0, 0, 0, 0]) // version 0・flags
        put(1) // reference_ID
        put(timescale)
        put(0) // earliest_presentation_time
        put(firstOffset)
        body.append(contentsOf: [0, 0, UInt8(refs.count >> 8), UInt8(refs.count & 0xFF)])
        for (size, duration) in refs {
            put(size)
            put(duration)
            put(0x9000_0000) // starts_with_SAP
        }
        var box = Data()
        withUnsafeBytes(of: UInt32(body.count + 8).bigEndian) { box.append(contentsOf: $0) }
        box.append(contentsOf: Array("sidx".utf8))
        box.append(body)
        return box
    }

    func testSegmentIndexIsReadFromTheSidxBox() {
        let box = sidx(timescale: 44100, firstOffset: 0, refs: [(1000, 441000), (2000, 220500)])
        let index = SegmentIndex.parse(box, indexStart: 759)
        // 区切りは sidx の箱のすぐ後から並ぶ
        let first = 759 + Int64(box.count)
        XCTAssertEqual([SegmentIndex.Segment(offset: first, size: 1000, duration: 10), SegmentIndex.Segment(offset: first + 1000, size: 2000, duration: 5)],
                       index?.segments)
    }

    func testSegmentIndexRejectsOtherBoxesAndNestedIndexes() {
        XCTAssertNil(SegmentIndex.parse(Data(repeating: 0, count: 64), indexStart: 0))
        XCTAssertNil(SegmentIndex.parse(sidx(timescale: 1000, firstOffset: 0, refs: [(0x8000_0010, 1000)]), indexStart: 0))
        let box = sidx(timescale: 1000, firstOffset: 0, refs: [(10, 1000)])
        XCTAssertNil(SegmentIndex.parse(box.prefix(box.count - 4), indexStart: 0))
    }

    func testPlaylistUsesByteRangesOfOneFile() {
        let index = SegmentIndex(segments: [.init(offset: 1043, size: 321_408, duration: 10.03), .init(offset: 322_451, size: 1000, duration: 2.5)])
        let text = HlsPlaylist.build(index, initEnd: 758, mediaUrl: "media.mp4")
        XCTAssertEqual("""
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:11
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MAP:URI="media.mp4",BYTERANGE="759@0"
            #EXTINF:10.030,
            #EXT-X-BYTERANGE:321408@1043
            media.mp4
            #EXTINF:2.500,
            #EXT-X-BYTERANGE:1000@322451
            media.mp4
            #EXT-X-ENDLIST

            """, text)
    }
}
