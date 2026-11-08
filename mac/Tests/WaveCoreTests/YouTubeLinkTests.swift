import XCTest
@testable import WaveCore

/// ショートカットの「リンクで再生」が受けるリンク・ID の読み方。
final class YouTubeLinkTests: XCTestCase {
    func testVideoLinks() {
        let id = "dQw4w9WgXcQ"
        for text in [
            "https://music.youtube.com/watch?v=\(id)",
            "https://music.youtube.com/watch?v=\(id)&si=abc",
            "https://www.youtube.com/watch?v=\(id)&t=30s",
            "https://m.youtube.com/watch?feature=share&v=\(id)",
            "http://youtube.com/watch?v=\(id)",
            "youtube.com/watch?v=\(id)",
            "https://youtu.be/\(id)",
            "https://youtu.be/\(id)?si=xyz",
            "https://www.youtube.com/live/\(id)?feature=shared",
            "https://youtube.com/shorts/\(id)",
            "https://www.youtube.com/embed/\(id)",
            "  \(id)\n",
        ] {
            XCTAssertEqual(YouTubeLink.parse(text), .video(id), text)
        }
    }

    func testVideoWinsOverList() {
        XCTAssertEqual(YouTubeLink.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ"), .video("dQw4w9WgXcQ"))
    }

    func testPlaylistLinks() {
        XCTAssertEqual(YouTubeLink.parse("https://music.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"),
                       .playlist("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"))
        XCTAssertEqual(YouTubeLink.parse("https://www.youtube.com/playlist?list=OLAK5uy_kXg3bN6l0aQ2Rk0V1Jc9P7nX0Ue1bVvYc&si=1"),
                       .playlist("OLAK5uy_kXg3bN6l0aQ2Rk0V1Jc9P7nX0Ue1bVvYc"))
        XCTAssertEqual(YouTubeLink.parse("https://music.youtube.com/watch?list=RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"),
                       .playlist("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"))
        XCTAssertEqual(YouTubeLink.parse("https://music.youtube.com/browse/VLPLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"),
                       .playlist("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"))
        XCTAssertEqual(YouTubeLink.parse("https://music.youtube.com/playlist?list=LM"), .playlist("LM"))
    }

    func testAlbumLinks() {
        XCTAssertEqual(YouTubeLink.parse("https://music.youtube.com/browse/MPREb_4pL8gzRtw1p"), .album("MPREb_4pL8gzRtw1p"))
        XCTAssertEqual(YouTubeLink.parse("MPREb_4pL8gzRtw1p"), .album("MPREb_4pL8gzRtw1p"))
    }

    func testBareIds() {
        XCTAssertEqual(YouTubeLink.parse("jfKfPfyJRdk"), .video("jfKfPfyJRdk"))
        XCTAssertEqual(YouTubeLink.parse("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"), .playlist("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"))
        XCTAssertEqual(YouTubeLink.parse("OLAK5uy_kXg3bN6l0aQ2Rk0V1Jc9P7nX0Ue1bVvYc"), .playlist("OLAK5uy_kXg3bN6l0aQ2Rk0V1Jc9P7nX0Ue1bVvYc"))
        XCTAssertEqual(YouTubeLink.parse("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"), .playlist("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"))
        XCTAssertNil(YouTubeLink.parse("UUabcdefghijklmnopqrstuv"))
        // 11字の動画の ID は、PL で始まっていても動画
        XCTAssertEqual(YouTubeLink.parse("PLabcdefghi"), .video("PLabcdefghi"))
    }

    func testRejectsOtherText() {
        for text in [
            "", "   ", "hello world", "曲名", "abc",
            "https://example.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=short",
            "https://music.youtube.com/channel/UCxyz",
            "https://music.youtube.com/browse/UCabcdefghijklmnop",
            "https://www.youtube.com/watch",
            "https://youtu.be/",
            "ftp://youtube.com/watch?v=dQw4w9WgXcQ",
            "dQw4w9WgXc!",
            "XYabcdefghijklmnop",
        ] {
            XCTAssertNil(YouTubeLink.parse(text), text)
        }
    }
}
