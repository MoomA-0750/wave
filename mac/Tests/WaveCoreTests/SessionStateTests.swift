import Foundation
import XCTest
@testable import WaveCore

/// ログインが切れたかの分け方（ADR-0004。Android の WaveSessionTest と同じ考え）。
/// 切れたと確かめられたときだけ expired、一時的な拒否は suspect にして account_menu で確かめ、通信の失敗では変えない。
final class SessionStateTests: XCTestCase {
    private static let header = Data(#"{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"me"}]}}}}}}}]}"#.utf8)
    private static let loggedOutMenu = Data(#"{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[]}}}}]}"#.utf8)

    private func client(_ handler: @escaping (URLRequest) -> (Int, [String: String], Data)) throws -> InnerTubeClient {
        let client = InnerTubeClient(session: StubURLProtocol.session(handler), hl: "ja", gl: "JP")
        client.setCredentials(try XCTUnwrap(Credentials.fromLogin(rawCookie: "SAPISID=abc", visitorData: nil, rawDataSyncId: nil, nowEpochMs: 0)))
        return client
    }

    private func endpoint(_ request: URLRequest) -> String { request.url?.path ?? "" }

    func testUnauthorizedExpires() async throws {
        let client = try client { _ in (401, [:], Data()) }
        XCTAssertEqual(.active, client.sessionState)
        let result = try await client.post("browse", body: .obj([:]))
        XCTAssertEqual(.expired, result.failure)
        XCTAssertEqual(.expired, client.sessionState)
    }

    func testNetworkFailureDoesNotExpire() async throws {
        let client = InnerTubeClient(session: URLSession(configuration: {
            let c = URLSessionConfiguration.ephemeral
            c.protocolClasses = [FailingURLProtocol.self]
            return c
        }()), hl: "ja", gl: "JP")
        client.setCredentials(try XCTUnwrap(Credentials.fromLogin(rawCookie: "SAPISID=abc", visitorData: nil, rawDataSyncId: nil, nowEpochMs: 0)))
        let repo = MusicRepository(client: client)
        let result = try await repo.account()
        XCTAssertEqual(.network, result.failure)
        XCTAssertEqual(.active, client.sessionState)
    }

    func testForbiddenIsSuspectAndAccountHeaderClearsIt() async throws {
        let client = try client { [self] request in
            endpoint(request).hasSuffix("account_menu") ? (200, [:], SessionStateTests.header) : (403, [:], Data())
        }
        let result = try await client.post("browse", body: .obj([:]))
        XCTAssertEqual(.suspect, result.failure)
        // 確かめは裏で走る。テストでは待つ
        await client.confirmNow()
        XCTAssertEqual(.active, client.sessionState)
    }

    func testConfirmExpiresWhenMenuHasNoAccount() async throws {
        let client = try client { [self] request in
            endpoint(request).hasSuffix("account_menu") ? (200, [:], SessionStateTests.loggedOutMenu) : (403, [:], Data())
        }
        _ = try await client.post("browse", body: .obj([:]))
        await client.confirmNow()
        XCTAssertEqual(.expired, client.sessionState)
    }

    func testConfirmExpiresWhenRedirectedToSignIn() async throws {
        let client = try client { [self] request in
            endpoint(request).hasSuffix("account_menu") ? (302, ["Location": "https://accounts.google.com/ServiceLogin"], Data()) : (403, [:], Data())
        }
        _ = try await client.post("browse", body: .obj([:]))
        await client.confirmNow()
        XCTAssertEqual(.expired, client.sessionState)
    }

    func testSongLoginRequiredDoesNotChangeStateUntilConfirmed() async throws {
        // 曲ごとの LOGIN_REQUIRED では状態を変えない。確かめてアカウントがあれば、そのまま
        let client = try client { _ in (200, [:], SessionStateTests.header) }
        client.confirmSession()
        XCTAssertEqual(.active, client.sessionState)
        await client.confirmNow()
        XCTAssertEqual(.active, client.sessionState)
    }

    func testAccountWithoutHeaderIsSuspectNotExpired() async throws {
        let client = try client { _ in (200, [:], Data(#"{}"#.utf8)) }
        let result = try await MusicRepository(client: client).account()
        XCTAssertEqual(.suspect, result.failure)
        if case .suspect = client.sessionState {} else { XCTFail("suspect のはず: \(client.sessionState)") }
        // 中身の分からない応答（actions が無い）では切れたことにしない
        await client.confirmNow()
        XCTAssertNotEqual(.expired, client.sessionState)
    }

    func testLogOutResetsState() throws {
        let client = try client { _ in (401, [:], Data()) }
        client.setCredentials(nil)
        XCTAssertEqual(.loggedOut, client.sessionState)
    }
}

/// どの要求も通信の失敗にする。
final class FailingURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet)) }
    override func stopLoading() {}
}
