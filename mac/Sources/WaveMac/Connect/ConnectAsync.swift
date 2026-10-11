import Foundation

// 端末の連携で使う、スレッドを跨ぐ小さな道具（Windows の volatile・Interlocked・CancellationTokenSource・TaskCompletionSource・Channel・DispatcherQueueTimer に当たる）。

/// 鍵つきの値（C# の volatile・Interlocked の代わり）。
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) { self.value = value }

    var current: Value { lock.withLock { value } }

    func set(_ next: Value) { lock.withLock { value = next } }

    /// 入れ替えて、前の値を返す。
    func exchange(_ next: Value) -> Value {
        lock.withLock {
            let previous = value
            value = next
            return previous
        }
    }

    @discardableResult
    func update<R>(_ body: (inout Value) -> R) -> R { lock.withLock { body(&value) } }
}

/// 取り消しの合図（C# の CancellationTokenSource）。取り消したら登録した処理を1回ずつ呼ぶ。
final class CancelSource: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var handlers: [UUID: () -> Void] = [:]

    var isCancelled: Bool { lock.withLock { cancelled } }

    func cancel() {
        let pending: [() -> Void] = lock.withLock {
            if cancelled { return [] }
            cancelled = true
            let list = Array(handlers.values)
            handlers = [:]
            return list
        }
        pending.forEach { $0() }
    }

    /// 取り消されたら呼ぶ（もう取り消されていれば、すぐ呼ぶ）。戻り値は remove に渡す。
    @discardableResult
    func onCancel(_ handler: @escaping () -> Void) -> UUID? {
        let id = UUID()
        let now: Bool = lock.withLock {
            if cancelled { return true }
            handlers[id] = handler
            return false
        }
        if now {
            handler()
            return nil
        }
        return id
    }

    func remove(_ id: UUID?) {
        guard let id else { return }
        lock.withLock { _ = handlers.removeValue(forKey: id) }
    }

    /// これが取り消されたら一緒に取り消される子（C# の CreateLinkedTokenSource）。
    func linked() -> CancelSource {
        let child = CancelSource()
        let id = onCancel { child.cancel() }
        child.onCancel { [weak self] in self?.remove(id) }
        return child
    }

    func cancelAfter(ms: Int64) {
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(Int(ms))) { [weak self] in self?.cancel() }
    }

    func throwIfCancelled() throws {
        if isCancelled { throw CancellationError() }
    }
}

/// 一度だけ決まる結果を待つ（C# の TaskCompletionSource）。
final class Promise<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: T?
    private var waiters: [UUID: CheckedContinuation<T?, Never>] = [:]

    /// 結果を決める。もう決まっていれば何もせず false。
    @discardableResult
    func trySet(_ value: T) -> Bool {
        let waiting: [CheckedContinuation<T?, Never>]? = lock.withLock {
            if result != nil { return nil }
            result = value
            let list = Array(waiters.values)
            waiters = [:]
            return list
        }
        guard let waiting else { return false }
        waiting.forEach { $0.resume(returning: value) }
        return true
    }

    /// 結果を待つ。時間切れ・取り消しなら nil（どちらかは呼ぶ側が cancel を見て分ける）。
    func wait(timeoutMs: Int64? = nil, cancel: CancelSource? = nil) async -> T? {
        let id = UUID()
        var registration: UUID?
        let value: T? = await withCheckedContinuation { (continuation: CheckedContinuation<T?, Never>) in
            let ready: T? = lock.withLock {
                if let result { return result }
                waiters[id] = continuation
                return nil
            }
            if let ready {
                continuation.resume(returning: ready)
                return
            }
            if let timeoutMs {
                DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(Int(timeoutMs))) { [weak self] in self?.drop(id) }
            }
            if let cancel { registration = cancel.onCancel { [weak self] in self?.drop(id) } }
        }
        cancel?.remove(registration)
        return value
    }

    private func drop(_ id: UUID) {
        let waiting = lock.withLock { waiters.removeValue(forKey: id) }
        waiting?.resume(returning: nil)
    }
}

/// 上限の無い送り口（C# の Channel.CreateUnbounded）。読むのは1か所ずつ、書いた順に受ける。
final class AsyncChannel<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var buffer: [T] = []
    private var readers: [(id: UUID, continuation: CheckedContinuation<T?, Never>)] = []

    func tryWrite(_ value: T) {
        let reader: CheckedContinuation<T?, Never>? = lock.withLock {
            if readers.isEmpty {
                buffer.append(value)
                return nil
            }
            return readers.removeFirst().continuation
        }
        reader?.resume(returning: value)
    }

    func tryRead() -> T? {
        lock.withLock { buffer.isEmpty ? nil : buffer.removeFirst() }
    }

    /// 次の値を待つ。取り消されたら CancellationError。
    func read(_ cancel: CancelSource) async throws -> T {
        let id = UUID()
        var registration: UUID?
        let value: T? = await withCheckedContinuation { (continuation: CheckedContinuation<T?, Never>) in
            let ready: T? = lock.withLock {
                if !buffer.isEmpty { return buffer.removeFirst() }
                readers.append((id, continuation))
                return nil
            }
            if let ready {
                continuation.resume(returning: ready)
                return
            }
            registration = cancel.onCancel { [weak self] in self?.drop(id) }
        }
        cancel.remove(registration)
        guard let value else { throw CancellationError() }
        return value
    }

    private func drop(_ id: UUID) {
        let reader: CheckedContinuation<T?, Never>? = lock.withLock {
            guard let index = readers.firstIndex(where: { $0.id == id }) else { return nil }
            return readers.remove(at: index).continuation
        }
        reader?.resume(returning: nil)
    }
}

/// 間を空けて待つ。取り消されたら CancellationError（C# の Task.Delay(ms, token)）。
func connectDelay(_ ms: Int64, _ cancel: CancelSource? = nil) async throws {
    let timer = Promise<Bool>()
    DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(Int(ms))) { timer.trySet(true) }
    if await timer.wait(cancel: cancel) == nil { throw CancellationError() }
}

/// メインのスレッドの、1回だけ鳴る間引きのタイマー（C# の DispatcherQueueTimer。start は動いていても最初から数え直す）。
@MainActor
final class Debouncer {
    private let ms: Int
    private let action: () -> Void
    private var work: DispatchWorkItem?

    init(ms: Int, _ action: @escaping () -> Void) {
        self.ms = ms
        self.action = action
    }

    func start() {
        work?.cancel()
        let next = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.work = nil
                self.action()
            }
        }
        work = next
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(ms), execute: next)
    }

    func stop() {
        work?.cancel()
        work = nil
    }
}
