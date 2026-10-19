import XCTest
@testable import WaveCore

/// 画像の大きさの選び方（Android の ArtworkUrlTest と同じ形）。
final class ArtworkUrlTests: XCTestCase {
    func testGoogleImagesAreAskedForALargeSquare() {
        XCTAssertEqual("https://lh3.googleusercontent.com/abc=w544-h544", ArtworkUrl.large("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj"))
        XCTAssertEqual("https://yt3.ggpht.com/abc=w544-h544", ArtworkUrl.large("https://yt3.ggpht.com/abc=s192"))
        XCTAssertEqual("https://yt3.googleusercontent.com/abc=w544-h544", ArtworkUrl.large("https://yt3.googleusercontent.com/abc"))
        XCTAssertEqual("https://lh3.googleusercontent.com/abc=w544-h544", ArtworkUrl.large("https://lh3.googleusercontent.com/abc=w60-h60?authuser=0"))
    }

    func testVideoThumbnailsUseTheLargerFileThatAlwaysExists() {
        XCTAssertEqual("https://i.ytimg.com/vi/abc123/hqdefault.jpg", ArtworkUrl.large("https://i.ytimg.com/vi/abc123/default.jpg"))
        XCTAssertEqual("https://i.ytimg.com/vi/abc123/hqdefault.jpg", ArtworkUrl.large("https://i.ytimg.com/vi/abc123/hqdefault.jpg?sqp=xyz&rs=abc"))
        XCTAssertEqual("https://i.ytimg.com/vi/abc123/hqdefault.jpg", ArtworkUrl.large("https://i.ytimg.com/vi/abc123/hq720.jpg"))
    }

    func testOtherHostsAreLeftAlone() {
        XCTAssertEqual("https://example.com/cover.png", ArtworkUrl.large("https://example.com/cover.png"))
        XCTAssertEqual("https://example.com/cover.png", ArtworkUrl.resized("https://example.com/cover.png", px: 240))
    }

    func testResizedKeepsTheSuffixAndHandlesSquareSizes() {
        XCTAssertEqual("https://lh3.googleusercontent.com/abc=w240-h240-l90-rj", ArtworkUrl.resized("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", px: 240))
        XCTAssertEqual("https://yt3.ggpht.com/abc=w240-h240", ArtworkUrl.resized("https://yt3.ggpht.com/abc=s88-c-k-c0x00ffffff-no-rj", px: 240))
        XCTAssertEqual("https://yt3.googleusercontent.com/abc=w240-h240", ArtworkUrl.resized("https://yt3.googleusercontent.com/abc", px: 240))
    }
}
