import Foundation

/// 曲名・アーティスト名・アルバム名を、YouTube が言語に合わせて書き換える前の表記（曲の言語のまま）にする（設定の「曲名を元の表記で」。
/// Android の core の OriginalTitles を移したもの。判定の細部は同じ）。
/// YouTube Music は、日本語で頼むと洋楽をカタカナに（テイラー・スウィフト）、日本語以外で頼むと邦楽の一部をローマ字に（Kenshi Yonezu）して返す。
/// どの言語で頼んでも両方が元の表記になる頼み方は無い（2026-11 に hl・gl を変えて確かめた）ので、日本語と英語の2つの応答を、
/// 行・カード・見出しごとに ID（videoId・browseId）で突き合わせ、元の表記の方を選ぶ。
///
/// 選び方（手がかりの強い順）:
/// - 英語の応答に日本の文字が残る（「夜の踊り子 - Yoru No Odoriko」）・アーティスト名の漢字・ひらがながローマ字になる → 日本の曲（日本語の方）
/// - カタカナが英字になる → 洋楽（英語の方）。カタカナ名の邦楽（サカナクション）もあるので、日本の曲の手がかりが無いときだけ
/// - 曲名の漢字がローマ字になるだけでは決めない（洋楽にも日本語の副題がある。「シェイク・イット・オフ～気にしてなんかいられないっ！！」）
/// 日本の曲と分かったアーティストは覚え（`Result.learned`）、手がかりの無い行にも使う。YouTube が作ったプレイリスト・棚の見出し・ジャンル名は画面の言語のまま。
public enum OriginalTitles {
    /// 画面の言語（hl）に対して、もう1つ頼む言語。
    public static func otherLanguage(_ hl: String) -> String { isJapanese(hl) ? "en" : "ja" }

    public static func isJapanese(_ hl: String) -> Bool {
        (hl.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? hl).lowercased() == "ja"
    }

    public struct Result {
        public let json: JSON
        /// 新しく分かったアーティスト（browseId → 日本の曲なら `ja`、洋楽なら `en`）。
        public let learned: [String: String]
    }

    /// primary（画面の言語 primaryLanguage の応答）を、other（もう1つの言語の同じ頼みの応答）と突き合わせて元の表記にする。
    /// known は前に覚えたアーティスト（browseId → `ja`・`en`）。
    public static func merge(_ primary: JSON, _ other: JSON, primaryLanguage: String, known: [String: String] = [:]) -> Result {
        let primaryIsJa = isJapanese(primaryLanguage)
        // もう1つの言語の応答の、行・カードの題と行き先のある文字（ID ごと）。言語で並びや中身が少し変わるので、同じ位置ではなく ID で引く
        var otherTitles: [String: [JSON]] = [:]
        for (name, obj) in groups(other) {
            guard let key = groupKey(name, obj) else { continue }
            if otherTitles[key] == nil, let runs = titleRuns(name, obj) { otherTitles[key] = runs }
        }
        let otherLinked = Dictionary(uniqueKeysWithValues: linked(other, nested: true).map { ($0.id, $0.text) })

        final class Pairing {
            let name: String
            let key: String
            var artists: Set<String>
            let hints: Set<Hint>
            let artistHints: Set<Hint>
            init(name: String, key: String, artists: Set<String>, hints: Set<Hint>, artistHints: Set<Hint>) {
                self.name = name
                self.key = key
                self.artists = artists
                self.hints = hints
                self.artistHints = artistHints
            }
        }

        var pageArtists = Set<String>()
        var pairings: [Pairing] = []
        for (name, obj) in groups(primary) {
            guard let key = groupKey(name, obj), isMusicKey(key) else { continue }
            var fields: [(String, String, Bool)] = []
            if let title = titleRuns(name, obj), let otherTitle = otherTitles[key] {
                fields.append((text(title), text(otherTitle), isArtistKey(key)))
            }
            var artists = Set<String>()
            for (id, value) in linked(obj) {
                if isArtistKey(id) { artists.insert(String(id.dropFirst(2))) }
                if let o = otherLinked[id] { fields.append((value, o, isArtistKey(id))) }
            }
            if isArtistKey(key) { artists.insert(String(key.dropFirst(2))) }
            if headers.contains(name) { pageArtists.formUnion(artists) }

            func hintOf(_ field: (String, String, Bool)) -> Hint? {
                primaryIsJa ? hint(ja: field.0, en: field.1, artist: field.2) : hint(ja: field.1, en: field.0, artist: field.2)
            }
            pairings.append(Pairing(name: name, key: key, artists: artists, hints: Set(fields.compactMap(hintOf)),
                                    artistHints: Set(fields.filter(\.2).compactMap(hintOf))))
        }
        var learned = known
        var newlyLearned: [String: String] = [:]
        for p in pairings {
            // アルバムの曲の行はアーティストを書かない（見出しのアーティストの曲）
            if p.artists.isEmpty && p.name == listItem { p.artists.formUnion(pageArtists) }
            if p.hints.contains(.strongJa) {
                for a in p.artists {
                    learned[a] = ja
                    newlyLearned[a] = ja
                }
            }
        }
        // カタカナが英字になったアーティストは洋楽として覚える（キューの動画の行のように、題しか違わない行に使う）。日本の曲と分かっていれば覚えない
        for p in pairings {
            if p.hints.contains(.strongJa) || !p.artistHints.contains(.weakEn) { continue }
            for a in p.artists where learned[a] != ja {
                learned[a] = en
                newlyLearned[a] = en
            }
        }
        var replace = Set<String>()
        for p in pairings {
            let origin: String
            if p.hints.contains(.strongJa) || p.artists.contains(where: { learned[$0] == ja }) {
                origin = ja
            } else if p.artistHints.contains(.weakEn) || p.hints == [.weakEn] || p.artists.contains(where: { learned[$0] == en }) {
                origin = en
            } else {
                continue
            }
            if (origin == ja) != primaryIsJa { replace.insert(p.key) }
        }
        if replace.isEmpty { return Result(json: primary, learned: newlyLearned) }
        return Result(json: rewrite(primary, scope: nil, Replacement(keys: replace, titles: otherTitles, linked: otherLinked)), learned: newlyLearned)
    }

    private struct Replacement {
        let keys: Set<String>
        let titles: [String: [JSON]]
        let linked: [String: String]
    }

    private enum Hint { case strongJa, weakJa, weakEn }

    /// ja・en の2つの表記の違いから、元の言語の手がかり。
    private static func hint(ja: String, en: String, artist: Bool) -> Hint? {
        if ja.trimmingCharacters(in: .whitespacesAndNewlines) == en.trimmingCharacters(in: .whitespacesAndNewlines) { return nil }
        let j = Scripts(ja)
        let e = Scripts(en)
        if e.japanese || e.katakana { return .strongJa }
        if j.japanese && e.latin { return artist ? .strongJa : .weakJa }
        if j.katakana && e.latin { return .weakEn }
        return nil
    }

    /// 文字の種類（Android と同じく UTF-16 の1単位ずつ見る。サロゲートの上位は漢字の拡張 B 以降）。
    private struct Scripts {
        /// 漢字・ひらがな。
        var japanese = false
        var katakana = false
        var latin = false

        init(_ s: String) {
            for c in s.utf16 {
                switch c {
                case 0x3041...0x309F, 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF, 0xD840...0xD87F, 0x3005, 0x3006: japanese = true
                case 0x30A0...0x30FF, 0x31F0...0x31FF, 0xFF66...0xFF9F: katakana = true
                default:
                    if (c < 0x2000 || (0xFF21...0xFF5A).contains(c)), Scripts.isLetter(c) { latin = true }
                }
            }
        }

        /// Kotlin の Char.isLetter と同じ（一般区分が L のもの）。
        private static func isLetter(_ c: UInt16) -> Bool {
            guard let scalar = Unicode.Scalar(c) else { return false }
            switch scalar.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
            default: return false
            }
        }
    }

    private static let ja = "ja"
    private static let en = "en"
    private static let listItem = "musicResponsiveListItemRenderer"
    private static let items: Set<String> = [listItem, "musicTwoRowItemRenderer", "playlistPanelVideoRenderer", "musicCardShelfRenderer", "musicMultiRowListItemRenderer"]

    /// アーティストのページの見出し（アーティスト名）。
    private static let artistHeaders: Set<String> = ["musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer"]

    /// アルバム・プレイリストのページの見出し。アーティストへのつながりがあるもの（アルバム）だけを扱う。
    private static let albumHeaders: Set<String> = ["musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer"]
    private static let headers = artistHeaders.union(albumHeaders)
    private static let groupNames = items.union(headers)

    private static func groups(_ root: JSON) -> [(String, JSON)] {
        var out: [(String, JSON)] = []
        func walk(_ e: JSON) {
            switch e {
            case .object(let o):
                for (k, v) in o.pairs {
                    if groupNames.contains(k), case .object = v { out.append((k, v)) }
                    walk(v)
                }
            case .array(let a):
                a.forEach(walk)
            default:
                break
            }
        }
        walk(root)
        return out
    }

    /// 文字の続き（run）・行の行き先の ID（`v:videoId`・`b:browseId`）。
    private static func endpointId(_ o: JSON?) -> String? {
        let ne = o?["navigationEndpoint"]
        if let v = ne?["watchEndpoint"]?["videoId"]?.str, !v.isEmpty { return "v:" + v }
        if let b = ne?["browseEndpoint"]?["browseId"]?.str, !b.isEmpty { return "b:" + b }
        return nil
    }

    private static func isArtistKey(_ key: String) -> Bool { key.hasPrefix("b:UC") }

    /// 曲・動画・アルバム・アーティスト（YouTube のプレイリスト・気分の棚などは画面の言語のまま）。
    private static func isMusicKey(_ key: String) -> Bool {
        key.hasPrefix("v:") || key.hasPrefix("b:MPRE") || isArtistKey(key) || key == albumPage
    }

    private static let albumPage = "h:album"

    private static func titleRuns(_ name: String, _ g: JSON) -> [JSON]? {
        if name == listItem {
            return g["flexColumns"]?.arr?.first?["musicResponsiveListItemFlexColumnRenderer"]?["text"]?["runs"]?.arr
        }
        return g["title"]?["runs"]?.arr
    }

    private static func text(_ runs: [JSON]) -> String { runs.map { $0["text"]?.str ?? "" }.joined() }

    private static func groupKey(_ name: String, _ g: JSON) -> String? {
        if artistHeaders.contains(name) { return firstString(g, "channelId").map { "b:" + $0 } }
        if albumHeaders.contains(name) { return runsIn(g).contains { pageType($0) == "MUSIC_PAGE_TYPE_ARTIST" } ? albumPage : nil }
        if let v = g["playlistItemData"]?["videoId"]?.str ?? g["videoId"]?.str, !v.isEmpty { return "v:" + v }
        if let id = endpointId(g) { return id }
        return titleRuns(name, g)?.first.flatMap(endpointId)
    }

    private static func pageType(_ run: JSON) -> String? {
        run.path("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType")?.str
    }

    private static func firstString(_ e: JSON, _ key: String) -> String? {
        switch e {
        case .object(let o):
            if let s = o[key]?.str { return s }
            for (_, v) in o.pairs { if let s = firstString(v, key) { return s } }
            return nil
        case .array(let a):
            for v in a { if let s = firstString(v, key) { return s } }
            return nil
        default:
            return nil
        }
    }

    /// 群の中の run（nested でなければ入れ子の群の中は除く）。
    private static func runsIn(_ g: JSON, nested: Bool = false) -> [JSON] {
        var out: [JSON] = []
        func walk(_ e: JSON) {
            switch e {
            case .object(let o):
                for (k, v) in o.pairs {
                    if groupNames.contains(k) && !nested { continue }
                    if k == "runs", case .array(let runs) = v { out.append(contentsOf: runs) } else { walk(v) }
                }
            case .array(let a):
                a.forEach(walk)
            default:
                break
            }
        }
        walk(g)
        return out
    }

    /// 群の中（nested なら入れ子の群の中も）の、行き先のある run の ID と文字（同じ ID は先に出てきた方）。
    private static func linked(_ g: JSON, nested: Bool = false) -> [(id: String, text: String)] {
        var out: [(id: String, text: String)] = []
        var seen = Set<String>()
        for run in runsIn(g, nested: nested) {
            guard let id = endpointId(run), seen.insert(id).inserted else { continue }
            out.append((id, run["text"]?.str ?? ""))
        }
        return out
    }

    /// replace の群を、もう1つの言語の題・行き先のある文字に置き換える。scope は今いる置き換える群の、もう1つの言語の文字。
    private static func rewrite(_ e: JSON, scope: [String: String]?, _ replace: Replacement) -> JSON {
        switch e {
        case .object(let o):
            var replacement: String?
            if let scope, let t = o["text"], !t.isContainer, let id = endpointId(e), isMusicKey(id) { replacement = scope[id] }
            return .object(JSONObject(o.pairs.map { k, v in
                if k == "text", let replacement { return (k, .string(replacement)) }
                if groupNames.contains(k), case .object = v { return (k, group(k, v, replace)) }
                return (k, rewrite(v, scope: scope, replace))
            }))
        case .array(let a):
            return .array(a.map { rewrite($0, scope: scope, replace) })
        default:
            return e
        }
    }

    private static func group(_ name: String, _ g: JSON, _ replace: Replacement) -> JSON {
        guard let key = groupKey(name, g), replace.keys.contains(key) else { return rewrite(g, scope: nil, replace) }
        var titled = g
        if let otherTitle = replace.titles[key], titleRuns(name, g) != nil { titled = withTitle(name, g, otherTitle) }
        return rewrite(titled, scope: replace.linked, replace)
    }

    private static func withTitle(_ name: String, _ g: JSON, _ runs: [JSON]) -> JSON {
        guard var o = g.obj else { return g }
        if name != listItem {
            guard var title = o["title"]?.obj else { return g }
            title["runs"] = .array(runs)
            o["title"] = .object(title)
            return .object(o)
        }
        guard var columns = o["flexColumns"]?.arr, var first = columns.first?.obj,
            var renderer = first["musicResponsiveListItemFlexColumnRenderer"]?.obj, var text = renderer["text"]?.obj
        else { return g }
        text["runs"] = .array(runs)
        renderer["text"] = .object(text)
        first["musicResponsiveListItemFlexColumnRenderer"] = .object(renderer)
        columns[0] = .object(first)
        o["flexColumns"] = .array(columns)
        return .object(o)
    }
}

private extension JSON {
    /// オブジェクトか配列（Kotlin の JsonPrimitive でないもの）。
    var isContainer: Bool {
        switch self {
        case .object, .array: true
        default: false
        }
    }
}
