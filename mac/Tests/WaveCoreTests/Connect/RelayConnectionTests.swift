import Foundation
import Network
import XCTest

@testable import WaveCore

/// この Mac の中だけで動く WebSocket の中継の代わり（本物の中継にはつながない）。
private final class LocalWebSocketServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "local-ws")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var received: [String] = []

    /// 受けたテキストへの返事（返さなければ nil）。
    var reply: (String) -> String? = { _ in nil }
    /// つながったときに送るもの。
    var greeting: String?

    init() throws {
        let parameters = NWParameters.tcp
        let options = NWProtocolWebSocket.Options()
        options.autoReplyPing = true
        parameters.defaultProtocolStack.applicationProtocols.insert(options, at: 0)
        listener = try NWListener(using: parameters, on: .any)
    }

    func start() async throws -> UInt16 {
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.start(queue: queue)
        for _ in 0..<200 {
            if let port = listener.port, port.rawValue != 0, case .ready = listener.state { return port.rawValue }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        throw URLError(.cannotConnectToHost)
    }

    var messages: [String] { lock.withLock { received } }

    private func accept(_ connection: NWConnection) {
        lock.withLock { connections.append(connection) }
        connection.start(queue: queue)
        if let greeting { send(connection, greeting) }
        receive(connection)
    }

    private func receive(_ connection: NWConnection) {
        connection.receiveMessage { [weak self] data, _, _, error in
            guard let self, error == nil else { return }
            if let data, !data.isEmpty {
                let text = String(decoding: data, as: UTF8.self)
                self.lock.withLock { self.received.append(text) }
                if let answer = self.reply(text) { self.send(connection, answer) }
            }
            self.receive(connection)
        }
    }

    private func send(_ connection: NWConnection, _ text: String) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
        connection.send(content: Data(text.utf8), contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
    }

    /// 理由つきで閉じる（中継が「外された」と知らせるとき）。
    func closeAll(reason: String) {
        for connection in lock.withLock({ connections }) {
            let metadata = NWProtocolWebSocket.Metadata(opcode: .close)
            metadata.closeCode = .protocolCode(.normalClosure)
            let context = NWConnection.ContentContext(identifier: "close", metadata: [metadata])
            connection.send(content: Data(reason.utf8), contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
        }
    }

    func stop() {
        listener.cancel()
        for connection in lock.withLock({ connections }) { connection.cancel() }
    }
}

private final class Box<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: T
    init(_ value: T) { self.value = value }
    func get() -> T { lock.withLock { value } }
    func update(_ change: (inout T) -> Void) { lock.withLock { change(&value) } }
}

final class RelayConnectionTests: XCTestCase {
    private func until(_ seconds: Double = 5, _ condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return condition()
    }

    func testAuthenticatesDeliversSendsAndStopsWhenRemoved() async throws {
        let device = SoftwareDevice.generate()
        let relayHost = "relay.test:8443"
        let server = try LocalWebSocketServer()
        server.greeting = #"{"op":"challenge","host":"evil.example","nonce":"bm9uY2U"}"#
        let verified = Box(false)
        server.reply = { text in
            guard case .auth(let auth)? = RelayV2.decode(text) else { return nil }
            // 中継が名乗るホストではなく、設定のホストで署名している
            let ok =
                WaveCrypto.decodePublicKey(B64.decode(auth.publicKey) ?? Data()).map {
                    WaveCrypto.verify(
                        $0, label: RelayV2.authLabel, message: WaveCrypto.lengthPrefixed(Data(relayHost.utf8), Data("nonce".utf8)),
                        signature: B64.decode(auth.signature) ?? Data())
                } ?? false
            verified.update { $0 = ok }
            return ok ? RelayV2.encode(.welcome(.init(deviceId: device.deviceId, storeId: "store-1"))) : nil
        }
        let port = try await server.start()
        defer { server.stop() }

        let delivered = Box<[String]>([])
        let lost = Box(0)
        let statuses = Box<[RelayStatus]>([])
        let relay = RelayConnection(
            baseUrl: "ws://127.0.0.1:\(port)/", relayHost: relayHost, identityPub: device.identityPub,
            sign: { label, message in try device.sign(label: label, message: message) },
            deliver: { text in delivered.update { $0.append(text) } }, onConnectionLost: { lost.update { $0 += 1 } })
        relay.onStatusChanged = { status in statuses.update { $0.append(status) } }
        XCTAssertFalse(relay.send(#"{"op":"state_get"}"#), "つながる前は送れない")
        relay.acquire()
        let connected = await until { relay.status == .connected }
        XCTAssertTrue(connected)
        XCTAssertTrue(verified.get())
        // challenge は渡さず、welcome から渡す
        XCTAssertEqual(1, delivered.get().count)
        guard case .welcome? = RelayV2.decode(delivered.get().first ?? "") else { return XCTFail("welcome ではない") }

        XCTAssertTrue(relay.send(#"{"op":"state_get"}"#))
        let sent = await until { server.messages.contains(#"{"op":"state_get"}"#) }
        XCTAssertTrue(sent)

        // 中継が「外された」で閉じる → 家に入っていない。つなぎ直さない
        server.closeAll(reason: "removed")
        let removed = await until { relay.status == .notMember }
        XCTAssertTrue(removed, "状態: \(relay.status)")
        XCTAssertEqual(1, lost.get())
        let settled = await until { statuses.get().contains(.notMember) }
        XCTAssertTrue(settled)
        XCTAssertFalse(relay.send(#"{"op":"state_get"}"#))
        relay.release()
        XCTAssertEqual(.disconnected, relay.status)
    }

    func testPairSendsTheFirstMoveAndReturnsTheReply() async throws {
        let server = try LocalWebSocketServer()
        server.reply = { text in RelayV2.decode(text).map { _ in #"{"op":"pair_confirmed","confirm":{"nonce":"n","ciphertext":"c"}}"# } }
        let port = try await server.start()
        defer { server.stop() }
        let device = SoftwareDevice.generate()
        let relay = RelayConnection(
            baseUrl: "ws://127.0.0.1:\(port)", relayHost: "relay.test", identityPub: device.identityPub, sign: { _, _ in Data() }, deliver: { _ in },
            onConnectionLost: {})
        let hello = RelayV2.encode(.pairHelloIn(.init(token: "t", hello: .obj(["v": 1]))))
        let reply = await relay.pair(hello)
        XCTAssertEqual(#"{"op":"pair_confirmed","confirm":{"nonce":"n","ciphertext":"c"}}"#, reply)
        XCTAssertEqual([hello], server.messages)
    }

    func testUnreachableHostIsReportedAndRetried() async throws {
        let relay = RelayConnection(
            baseUrl: "ws://127.0.0.1:9", relayHost: "relay.test", identityPub: Data(), sign: { _, _ in Data() }, deliver: { _ in }, onConnectionLost: {})
        relay.acquire()
        defer { relay.release() }
        // つながらなければ失敗にし、間を空けてつなぎ直す（すぐには connecting に戻らない）
        let failed = await until { relay.status == .failed }
        XCTAssertTrue(failed, "状態: \(relay.status)")
    }
}
