import Foundation
import Observation
import WaveCore

/// 会話の1つ分の表示（Android の AiMessage と同じ）。
enum AiMessage: Identifiable {
    case user(String)
    case assistant(String)
    case action(String)
    case songs([Song])
    case failure(String)

    var id: String {
        switch self {
        case .user(let t): "u:\(t.hashValue)"
        case .assistant(let t): "a:\(t.prefix(32).hashValue)"
        case .action(let t): "x:\(t.hashValue)"
        case .songs(let s): "s:\(s.map(\.videoId).joined())"
        case .failure(let t): "f:\(t.hashValue)"
        }
    }
}

/// Claude との会話（Android の AiChat・AiTools と同じ）。家の PC などで動く中継 tools/ai-bridge 経由で Claude Code を使う。
/// 中継は状態を持たないので、毎回それまでのやりとりと、今の曲・歌詞を送る。道具の呼び出しは手元で行う。
/// 会話はアプリが動いている間だけ持つ。YouTube の認証情報は中継へ送らない（別の通信を使う）。
@MainActor @Observable
final class AiChat {
    private(set) var messages: [AiMessage] = []
    private(set) var running = false

    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var known: [String: Song] = [:]

    // 認証情報を持たない通信（cookie・リダイレクトなし。答えを待つので時間切れにしない）
    @ObservationIgnored private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 3600
        config.timeoutIntervalForResource = 3600
        return URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }()

    private final class NoRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    }

    var configured: Bool { !(AppServices.settings.aiUrl ?? "").isEmpty && !(AppServices.settings.aiToken ?? "").isEmpty }

    func clear() {
        stop()
        messages.removeAll()
    }

    func stop() {
        task?.cancel()
        task = nil
        running = false
    }

    /// 接続の文字列（QR の中身 waveai://pair?u=&t=&n=）を読んで保存する。形が違えば false。
    static func pair(_ text: String) -> Bool {
        guard let url = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), url.scheme == "waveai", url.host == "pair" else { return false }
        let query = Dictionary((url.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        var base = query["u"] ?? ""
        while base.hasSuffix("/") { base.removeLast() }
        let token = query["t"] ?? ""
        guard !base.isEmpty, !token.isEmpty else { return false }
        // 接続先は https か、この Mac の中（127.0.0.1）だけ受ける
        guard base.hasPrefix("https://") || base.hasPrefix("http://127.0.0.1:") || base.hasPrefix("http://localhost:") else { return false }
        let settings = AppServices.settings!
        settings.aiUrl = base
        settings.aiToken = token
        settings.aiName = query["n"]
        settings.save()
        return true
    }

    static func unpair() {
        let settings = AppServices.settings!
        settings.aiUrl = nil
        settings.aiToken = nil
        settings.aiName = nil
        settings.save()
    }

    /// 頼みを送る。答えは messages に流れてくる。
    func send(_ text: String) {
        let request = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !request.isEmpty, !running else { return }
        let history: [JSON] = messages.compactMap {
            switch $0 {
            case .user(let t): .obj(["role": "user", "content": .string(t)])
            case .assistant(let t): .obj(["role": "assistant", "content": .string(t)])
            default: nil
            }
        }
        messages.append(.user(request))
        running = true
        task = Task {
            defer {
                running = false
                task = nil
            }
            guard configured else {
                messages.append(.failure(L.t("Claude につないでいません。設定の「Claude」でつないでください。", "Claude isn't connected yet. Connect it in Settings → Claude.")))
                return
            }
            do {
                try await run(request, history: history)
            } catch is CancellationError {
            } catch let error as URLError where error.code == .cancelled {
            } catch {
                messages.append(.failure(L.t("Claude の中継に届きませんでした。中継を動かしている PC が起きているか確かめてください。",
                                             "Couldn't reach the Claude bridge. Is the PC running it?")))
            }
        }
    }

    private func post(_ path: String, _ body: JSON) -> URLRequest {
        var request = URLRequest(url: URL(string: (AppServices.settings.aiUrl ?? "") + path)!)
        request.httpMethod = "POST"
        request.httpBody = body.data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer " + (AppServices.settings.aiToken ?? ""), forHTTPHeaderField: "Authorization")
        return request
    }

    private func run(_ request: String, history: [JSON]) async throws {
        var body = JSONObject([("task", .string(request)), ("messages", .array(history))])
        if let song = AppServices.player.current {
            remember(song)
            body["now_playing"] = .obj(["title": .string(song.title), "artists": .string(song.artistsText), "album": .string(song.album?.title ?? ""),
                                        "video_id": .string(song.videoId)])
            if let lyrics = await lyricsText(song) { body["lyrics"] = .string(lyrics) }
        }
        let (bytes, response) = try await session.bytes(for: post("/v1/agent", .object(body)))
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 {
            messages.append(.failure(L.t("Claude の中継にトークンを断られました。設定でつなぎ直してください。", "The Claude bridge refused the token. Connect again in Settings.")))
            return
        }
        guard (200...299).contains(status) else { throw URLError(.badServerResponse) }
        var session: String?
        var appending = false
        for try await line in bytes.lines {
            guard let ev = try? JSON.parse(line), ev.obj != nil else { continue }
            if let s = ev["session"].str {
                session = s
            } else if let text = ev["delta"].str {
                if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !appending { continue }
                if appending, case .assistant(let last)? = messages.last {
                    messages[messages.count - 1] = .assistant(last + text)
                } else {
                    messages.append(.assistant(String(text.drop(while: { $0.isWhitespace }))))
                }
                appending = text != "\n\n"
            } else if let call = ev["tool_call"].obj {
                appending = false
                let (result, error, shown) = await callTool(call["name"].str ?? "", args: call["arguments"] ?? .obj([:]))
                if let shown { messages.append(shown) }
                var reply = JSONObject([("session", .string(session ?? "")), ("id", .string(call["id"].str ?? ""))])
                if let error { reply["error"] = .string(error) } else { reply["result"] = result ?? .null }
                _ = try await self.session.data(for: post("/v1/agent/result", .object(reply)))
            } else if let e = ev["error"] {
                let text = e.str ?? e.serialized()
                messages.append(.failure(L.t("Claude が失敗しました: \(text)", "Claude failed: \(text)")))
            } else if ev["done"] != nil {
                break
            }
        }
    }

    private func lyricsText(_ song: Song) async -> String? {
        guard case .found(let lyrics)? = try? await AppServices.lyrics.lyrics(song: song) else { return nil }
        switch lyrics {
        case .synced(let s): return s.lines.map(\.text).joined(separator: "\n")
        case .plain(let p): return p.text
        }
    }

    private func remember(_ song: Song) { known[song.videoId] = song }

    private static func songJson(_ s: Song) -> JSON {
        var o = JSONObject([("video_id", .string(s.videoId)), ("title", .string(s.title)), ("artists", .string(s.artistsText))])
        if let album = s.album { o["album"] = .string(album.title) }
        if let d = s.durationSeconds { o["duration_seconds"] = JSON(d) }
        if let k = s.kind { o["kind"] = .string(k) }
        return .object(o)
    }

    private func songsOf(_ args: JSON) -> (songs: [Song], missing: [String]) {
        let ids = args["video_ids"].arr?.compactMap { $0.str ?? $0.long.map(String.init) } ?? []
        return (ids.compactMap { known[$0] }, ids.filter { known[$0] == nil })
    }

    private static func unknown(_ missing: [String]) -> (JSON?, String?, AiMessage?) {
        (nil, "unknown video_ids (use search_songs first): \(missing.joined(separator: ","))", nil)
    }

    /// 道具を手元で行う（tools/ai-bridge/wave_agent_mcp.py の道具）。この会話で出てきた曲の id だけを受ける。
    private func callTool(_ name: String, args: JSON) async -> (JSON?, String?, AiMessage?) {
        AppPaths.log("ai tool=\(name)")
        let player = AppServices.player!
        let music = AppServices.music!
        switch name {
        case "now_playing":
            guard let song = player.current else { return ("nothing is playing", nil, nil) }
            remember(song)
            var o = JSONObject([("song", AiChat.songJson(song))])
            if let lyrics = await lyricsText(song) { o["lyrics"] = .string(lyrics) }
            return (.object(o), nil, nil)
        case "get_queue":
            let songs = player.queue
            songs.forEach(remember)
            let shown = songs.dropFirst(max(0, player.index - 20)).prefix(100).map(AiChat.songJson)
            return (.obj(["current_index": JSON(player.index), "songs": .array(Array(shown))]), nil, nil)
        case "search_songs":
            let query = args["query"].str ?? ""
            let limit = min(max(args["limit"].int ?? 5, 1), 20)
            guard let page = (try? await music.searchSongs(query))?.value else { return (nil, "search failed", nil) }
            let songs = Array(page.songs.prefix(limit))
            songs.forEach(remember)
            return (.array(songs.map(AiChat.songJson)), nil, .action(L.t("「\(query)」を探しました", "Searched for “\(query)”")))
        case "liked_songs":
            let limit = min(max(args["limit"].int ?? 50, 1), 200)
            let (songs, _) = await AppServices.library.likedSongs()
            let picked = Array(songs.prefix(limit))
            picked.forEach(remember)
            return (.array(picked.map(AiChat.songJson)), nil, .action(L.t("高く評価した曲を見ました", "Looked at your liked songs")))
        case "show_songs":
            let (songs, missing) = songsOf(args)
            return songs.isEmpty ? AiChat.unknown(missing) : (.string("shown \(songs.count)"), nil, .songs(songs))
        case "play_songs":
            let (songs, missing) = songsOf(args)
            if songs.isEmpty { return AiChat.unknown(missing) }
            player.playList(songs)
            return (.string("playing \(songs.count) songs"), nil, .action(L.t("\(songs.count) 曲を再生しました", "Playing \(L.songs(songs.count))")))
        case "add_to_queue":
            let (songs, missing) = songsOf(args)
            if songs.isEmpty { return AiChat.unknown(missing) }
            player.queueAll(songs, next: args["next"].bool == true)
            return (.string("queued \(songs.count) songs"), nil, .action(L.t("\(songs.count) 曲をキューに足しました", "Added \(L.songs(songs.count)) to the queue")))
        case "list_playlists":
            return (.array(AppServices.library.editable.map { .obj(["playlist_id": .string($0.playlistId), "title": .string($0.title)]) }), nil, nil)
        case "create_playlist":
            let title = args["title"].str ?? ""
            if title.isEmpty { return (nil, "title is empty", nil) }
            let (songs, _) = songsOf(args)
            guard let id = (try? await music.createPlaylist(title))?.value else { return (nil, "create failed", nil) }
            let playlist = PlaylistSummary(playlistId: id, title: title, subtitle: L.t("非公開", "Private"), thumbnailUrl: nil, editable: true)
            AppServices.library.addCreated(playlist)
            var added = 0
            for (i, s) in songs.enumerated() {
                if (try? await music.addToPlaylist(id, videoId: s.videoId, knownEmpty: i == 0)) == .added { added += 1 }
            }
            return (.obj(["playlist_id": .string(id), "added": JSON(added)]), nil, .action(L.t("「\(title)」を作って \(added) 曲入れました", "Created “\(title)” with \(L.songs(added))")))
        case "add_to_playlist":
            let id = args["playlist_id"].str ?? ""
            guard let playlist = AppServices.library.editable.first(where: { $0.playlistId == id }) else { return (nil, "unknown playlist_id", nil) }
            let (songs, missing) = songsOf(args)
            if songs.isEmpty { return AiChat.unknown(missing) }
            var added = 0, already = 0
            for s in songs {
                let r = (try? await music.addToPlaylist(id, videoId: s.videoId)) ?? .failed
                if r == .added {
                    added += 1
                    AppServices.library.membership.noteAdded(id, s.videoId)
                } else if r == .alreadyInPlaylist {
                    already += 1
                }
            }
            return (.obj(["added": JSON(added), "already_in_playlist": JSON(already)]), nil,
                    .action(L.t("「\(playlist.title)」に \(added) 曲足しました", "Added \(L.songs(added)) to “\(playlist.title)”")))
        default:
            return (nil, "unknown tool: \(name)", nil)
        }
    }
}
