import Foundation

/// 同時に1つだけを通す門（C# の SemaphoreSlim(1, 1) の WaitAsync・Release に当たる）。待っている間に取り消されたら CancellationError。
final class AsyncGate: @unchecked Sendable {
    private let lock = NSLock()
    private var busy = false
    private var waiters: [(id: UUID, continuation: CheckedContinuation<Void, Error>)] = []

    func wait() async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                lock.lock()
                if Task.isCancelled {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                } else if !busy {
                    busy = true
                    lock.unlock()
                    continuation.resume()
                } else {
                    waiters.append((id, continuation))
                    lock.unlock()
                }
            }
        } onCancel: {
            lock.lock()
            if let i = waiters.firstIndex(where: { $0.id == id }) {
                let waiter = waiters.remove(at: i)
                lock.unlock()
                waiter.continuation.resume(throwing: CancellationError())
            } else {
                lock.unlock()
            }
        }
    }

    func signal() {
        lock.lock()
        if waiters.isEmpty {
            busy = false
            lock.unlock()
        } else {
            let next = waiters.removeFirst()
            lock.unlock()
            next.continuation.resume()
        }
    }

    /// 門を通ってから body を動かし、終わったら次へ譲る。
    func withGate<T>(_ body: () async throws -> T) async throws -> T {
        try await wait()
        defer { signal() }
        return try await body()
    }
}

/// 取り消しによる失敗なら CancellationError として投げ直す（C# の `catch (OperationCanceledException) when (cancel.IsCancellationRequested) { throw; }`）。
func rethrowIfCancelled(_ error: Error) throws {
    if error is CancellationError { throw error }
    if let urlError = error as? URLError, urlError.code == .cancelled, Task.isCancelled { throw CancellationError() }
}

/// URL の検査に使う、C# の Uri に近い読み方（https・既定のポート・userinfo なし）。
struct CheckedURL {
    let host: String
    let path: String
    /// 先頭の ? を除いた、符号化されたままのクエリ。
    let query: String

    init?(_ text: String) {
        guard let c = URLComponents(string: text), let scheme = c.scheme?.lowercased(), scheme == "https",
            let host = c.host, !host.isEmpty
        else { return nil }
        if let port = c.port, port != 443 { return nil }
        if c.user != nil || c.password != nil { return nil }
        self.host = host.lowercased()
        self.path = c.percentEncodedPath.isEmpty ? "/" : c.percentEncodedPath
        self.query = c.percentEncodedQuery ?? ""
    }
}

/// C# の double.ToString(CultureInfo.InvariantCulture) と同じ形（整数なら小数点を書かない）。
func invariantString(_ value: Double) -> String {
    if value.isFinite, value.rounded() == value, abs(value) < 1e15 { return String(Int64(value)) }
    return String(value)
}

extension Array {
    /// 同じ値の並びを保つ並べ替え（C# の OrderBy と同じ）。
    func stableSorted<K: Comparable>(by key: (Element) -> K, descending: Bool = false) -> [Element] {
        enumerated().sorted { a, b in
            let ka = key(a.element)
            let kb = key(b.element)
            if ka != kb { return descending ? ka > kb : ka < kb }
            return a.offset < b.offset
        }.map(\.element)
    }
}
