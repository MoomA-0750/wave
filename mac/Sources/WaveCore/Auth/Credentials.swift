import Foundation

/// ログインで得た認証情報。値は description・ログ・例外文に出さない。値を取り出す場所は reveal で探せる。
public final class Credentials: CustomStringConvertible, @unchecked Sendable {
    private static let sapisidCookieNames = ["SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"]

    private let cookie: String
    private let dataSyncId: String?

    /// 追跡用の ID で、認証情報ではない。
    public let visitorData: String?
    public let obtainedAtEpochMs: Int64

    private init(cookie: String, dataSyncId: String?, visitorData: String?, obtainedAtEpochMs: Int64) {
        self.cookie = cookie
        self.dataSyncId = dataSyncId
        self.visitorData = visitorData
        self.obtainedAtEpochMs = obtainedAtEpochMs
    }

    public func revealCookie() -> String { cookie }

    public func revealDataSyncId() -> String? { dataSyncId }

    /// SAPISIDHASH に使う値。無ければ nil。
    func revealSapisid() -> String? {
        let map = Credentials.parseCookie(cookie)
        for name in Credentials.sapisidCookieNames {
            if let v = map[name], !v.isEmpty { return v }
        }
        return nil
    }

    public var description: String {
        "Credentials(cookie=present, dataSyncId=\(dataSyncId != nil), visitorData=\(visitorData != nil))"
    }

    /// ブラウザから取った生の値で作る。制御文字を含むときと、SAPISID 系の cookie が無いときは nil。
    public static func fromLogin(rawCookie: String, visitorData: String?, rawDataSyncId: String?, nowEpochMs: Int64) -> Credentials? {
        for v in [rawCookie, visitorData, rawDataSyncId] {
            if let v, v.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) { return nil }
        }
        let cookie = rawCookie.trimmingCharacters(in: .whitespaces)
        let map = parseCookie(cookie)
        guard sapisidCookieNames.contains(where: { !(map[$0] ?? "").isEmpty }) else { return nil }
        let visitor = (visitorData?.trimmingCharacters(in: .whitespaces).isEmpty ?? true) ? nil : visitorData
        return Credentials(cookie: cookie, dataSyncId: normalizeDataSyncId(rawDataSyncId), visitorData: visitor, obtainedAtEpochMs: nowEpochMs)
    }

    /// DATASYNC_ID は "||" の前だけを onBehalfOfUser に使う（丸ごと渡すと 500 になる）。
    static func normalizeDataSyncId(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let head = raw.range(of: "||").map { String(raw[..<$0.lowerBound]) } ?? raw
        let value = head.trimmingCharacters(in: .whitespaces)
        return value.isEmpty ? nil : value
    }

    static func parseCookie(_ cookie: String) -> [String: String] {
        var map: [String: String] = [:]
        for part in cookie.split(separator: ";", omittingEmptySubsequences: false) {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { continue }
            map[part[..<eq].trimmingCharacters(in: .whitespaces)] = part[part.index(after: eq)...].trimmingCharacters(in: .whitespaces)
        }
        return map
    }

    /// 保存用の形（暗号化してから保存する）。
    public func serialize() -> String {
        JSON.obj([
            "cookie": .string(cookie),
            "dataSyncId": JSON(dataSyncId),
            "visitorData": JSON(visitorData),
            "obtainedAt": JSON(obtainedAtEpochMs),
        ]).serialized()
    }

    public static func deserialize(_ text: String) -> Credentials? {
        guard let root = try? JSON.parse(text), let cookie = root["cookie"].str else { return nil }
        return fromLogin(rawCookie: cookie, visitorData: root["visitorData"].str, rawDataSyncId: root["dataSyncId"].str,
                         nowEpochMs: root["obtainedAt"].long ?? 0)
    }
}
