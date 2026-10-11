import CryptoKit
import Foundation
import XCTest
@testable import WaveCore

final class AuthTests: XCTestCase {
    func testCredentialsNeedSapisidAndHideValues() throws {
        XCTAssertNil(Credentials.fromLogin(rawCookie: "HSID=a; SID=b", visitorData: nil, rawDataSyncId: nil, nowEpochMs: 0))
        let c = try XCTUnwrap(Credentials.fromLogin(rawCookie: "SID=b; __Secure-3PAPISID=xyz", visitorData: "v", rawDataSyncId: "123||456", nowEpochMs: 0))
        XCTAssertEqual("123", c.revealDataSyncId())
        XCTAssertFalse(c.description.contains("xyz"))
        XCTAssertFalse(String(describing: c).contains("xyz"))
        XCTAssertNil(Credentials.fromLogin(rawCookie: "SAPISID=a\n", visitorData: nil, rawDataSyncId: nil, nowEpochMs: 0))
        let restored = try XCTUnwrap(Credentials.deserialize(c.serialize()))
        XCTAssertEqual(c.revealCookie(), restored.revealCookie())
        XCTAssertEqual("v", restored.visitorData)
    }

    func testSapisidHashMatchesTheKnownFormat() {
        // sha1("1700000000 abc https://music.youtube.com")
        let expected = Insecure.SHA1.hash(data: Data("1700000000 abc https://music.youtube.com".utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual("SAPISIDHASH 1700000000_\(expected)", InnerTubeClient.sapisidHash("abc", unixSeconds: 1_700_000_000))
    }

    func testPrefCookieCarriesLanguageAndRegion() {
        let client = InnerTubeClient(session: URLSession(configuration: .ephemeral), hl: "ja", gl: "JP")
        XCTAssertEqual("PREF=hl=ja&gl=JP", client.withPref(nil))
        XCTAssertEqual("SID=a; PREF=hl=ja&gl=JP&f6=8", client.withPref("PREF=hl=en&f6=8; SID=a"))
    }

    func testAccountTokensReadTheSelectedAccount() {
        // MusicRepository.AccountTokens（Windows では internal）: 選択中のアカウントの datasyncIdToken の "||" より前
        let root = Fixtures.parse("""
            {"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[{"accountSectionListRenderer":{"contents":[
            {"accountItem":{"isSelected":true,"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"datasyncIdToken":{"datasyncIdToken":"111||"}}]}}}},
            {"accountItem":{"isSelected":false,"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"datasyncIdToken":{"datasyncIdToken":"222||9"}}]}}}}
            ]}}]}}}}]}
            """)
        // datasyncIdToken がオブジェクトのときは中の文字列を読む（同じ名前のキーが入れ子）
        let tokens = MusicRepository.accountTokens(root)
        XCTAssertEqual(tokens.map(\.id), ["111", "222"])
        XCTAssertEqual(tokens.map(\.selected), [true, false])
    }
}
