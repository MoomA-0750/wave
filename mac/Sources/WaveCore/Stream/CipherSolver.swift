import Foundation
import JavaScriptCore

/// faraday の表の1行（プレイヤーのハッシュごと）。
public struct PlayerCipherConfig: Hashable, Sendable {
    public var sigExpression: String
    public var nClass: String
    public var signatureTimestamp: Int
    public init(sigExpression: String, nClass: String, signatureTimestamp: Int) {
        self.sigExpression = sigExpression
        self.nClass = nClass
        self.signatureTimestamp = signatureTimestamp
    }
}

/// faraday の表（プレイヤーごとの署名の式と n のクラス名）を使い、YouTube のプレイヤー JS を JavaScriptCore の中で動かして
/// signatureCipher と n を解く（Android 版の innertubex の ZemerCipherSolver・Windows 版の Jint と同じやり方）。
/// JS の文脈にはネットワーク・ファイル・アプリの値へ触れる手段を渡さない（Swift の関数・オブジェクトを1つも登録しない）。
public final class CipherSolver: @unchecked Sendable {
    private static let playerIifeTrailer = "})(_yt_player);"
    private static let nProbeInput = "KdrqFlzJXl9EcCwlmEy"
    private static let maxInputLength = 64 * 1024

    public struct ScriptError: Error, CustomStringConvertible {
        public let stage: String
        public var description: String { "cipher script failed stage=\(stage)" }
    }

    private let context: JSContext
    private let lock = NSLock()

    private init(context: JSContext) { self.context = context }

    /// プレイヤー JS を読み込み、n の変換が働くことを確かめてから返す。働かなければ例外。
    public static func create(playerCode: String, config: PlayerCipherConfig) throws -> CipherSolver {
        // 文脈ごとに別の仮想機械にする（ほかの JS と値を共有しない）
        guard let context = JSContext(virtualMachine: JSVirtualMachine()) else { throw ScriptError(stage: "context") }
        try execute(context, globalsPreamble, stage: "preamble")
        try execute(context, "globalThis._yt_player = globalThis._yt_player || {};", stage: "globals")
        try execute(context, buildModifiedPlayerScript(playerCode, config: config), stage: "player")
        let solver = CipherSolver(context: context)
        let probe = solver.call("_nTransformFunc", nProbeInput)
        guard let probe, probe != nProbeInput, probe.utf16.count >= 5, probe.wholeMatch(of: #/[A-Za-z0-9_-]+/#) != nil else {
            throw ScriptError(stage: "n-transform probe")
        }
        return solver
    }

    /// 文を評価する。投げられた例外は Swift の例外にする（Jint の Execute と同じ）。
    private static func execute(_ context: JSContext, _ script: String, stage: String) throws {
        context.exception = nil
        context.evaluateScript(script)
        if context.exception != nil {
            context.exception = nil
            throw ScriptError(stage: stage)
        }
    }

    public func solveSignature(_ input: String) -> String? {
        guard let s = call("_cipherSigFunc", input), !s.isEmpty, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }

    public func solveN(_ input: String) -> String? {
        guard let s = call("_nTransformFunc", input), !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, s != input else { return nil }
        return s
    }

    private func call(_ function: String, _ input: String) -> String? {
        if input.utf16.count > CipherSolver.maxInputLength { return nil }
        return lock.withLock {
            context.exception = nil
            guard let f = context.objectForKeyedSubscript(function), f.isObject else { return nil }
            let result = f.call(withArguments: [input])
            if context.exception != nil {
                context.exception = nil
                return nil
            }
            guard let result, result.isString else { return nil }
            return result.toString()
        }
    }

    static func buildModifiedPlayerScript(_ playerCode: String, config: PlayerCipherConfig) -> String {
        let sigExpression = config.sigExpression.replacingOccurrences(of: "INPUT", with: "sig")
        let nExpression = nExpression(config.nClass).replacingOccurrences(of: "INPUT", with: "n")
        let exports =
            "; window._cipherSigFunc=function(sig){try{return \(sigExpression);}catch(e){return null;}};" +
            "window._nTransformFunc=function(n){try{return \(nExpression);}catch(e){return n;}}; "
        // プレイヤーの即時関数の終わりの直前に入れる（中の変数 g などに届くように）
        if let at = playerCode.range(of: playerIifeTrailer, options: [.literal, .backwards]) {
            return String(playerCode[..<at.lowerBound]) + exports + " " + String(playerCode[at.lowerBound...])
        }
        return playerCode + "\n" + exports
    }

    private static func nExpression(_ nClass: String) -> String {
        "(function(n){try{var u=new g." + nClass + "('https://x.googlevideo.com/videoplayback?n='+n,true);" +
            "var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)"
    }

    /// プレイヤー JS がブラウザの外で動くための最小の置き換え（innertubex の setupYoutubeGlobals と同じ）。
    private static let globalsPreamble = """
        if (typeof globalThis.XMLHttpRequest === "undefined") { globalThis.XMLHttpRequest = { prototype: {} }; }
        globalThis.location = { hash: "", host: "www.youtube.com", hostname: "www.youtube.com", href: "https://www.youtube.com/watch?v=yt-dlp-wins",
            origin: "https://www.youtube.com", password: "", pathname: "/watch", port: "", protocol: "https:", search: "?v=yt-dlp-wins", username: "" };
        if (typeof globalThis.document === "undefined") { globalThis.document = Object.create(null); }
        if (typeof globalThis.navigator === "undefined") { globalThis.navigator = Object.create(null); }
        if (typeof globalThis.self === "undefined") { globalThis.self = globalThis; }
        if (typeof globalThis.window === "undefined") { globalThis.window = globalThis; }
        if (typeof globalThis.Intl === "undefined") {
            const NumberFormat = function(locale, options) { this.options = options || {}; };
            NumberFormat.supportedLocalesOf = function(locales) { return Array.isArray(locales) ? locales : [locales]; };
            NumberFormat.prototype.format = function(value) { let f = String(value); const m = this.options.minimumIntegerDigits || 0; while (f.length < m) f = "0" + f; return f; };
            const DateTimeFormat = function() {};
            DateTimeFormat.prototype.resolvedOptions = function() { return { timeZone: "UTC" }; };
            DateTimeFormat.prototype.format = function(value) { return String(value); };
            globalThis.Intl = { NumberFormat, DateTimeFormat };
        }
        """
}
