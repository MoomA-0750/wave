#if os(iOS)
import AVFoundation
import CoreImage
import Foundation
import MediaPlayer
import UIKit
import WaveCore

/// ロック画面に出す、今歌っている歌詞の行とこの先の行（時刻付きの歌詞があるときだけ）。出す場所は設定で選ぶ:
/// 再生中の情報のアーティスト名の欄・ロック画面の再生中（Live Activity）・ロック画面の全画面のジャケット（iOS 26 から）。
/// 行が変わったら、再生中の情報と Live Activity を知らせ直す。
///
/// 見張るのは鳴っている間だけ、4 回／秒。止めている間は行が動かないので見ない。
/// 普通の Timer は画面が消えている間に間引かれて行が止まるので、間引かれない GCD のタイマー（strict）で回す。
@MainActor final class LockScreenLyrics {
    static let shared = LockScreenLyrics()

    /// 今出している行（どの場所も切ってあれば空）と、その前に出していた行（ジャケットの送りの動きの始まり）。
    private(set) var view = LockScreenLines(current: nil, upcoming: [], nextStartMs: nil)
    private var previous: LockScreenLines?
    /// 行が替わった回数（全画面のジャケットの ID に使う）と、替わったときの曲の位置。
    private var version = 0
    private var changedAtMs: Int64 = 0

    private var timer: DispatchSourceTimer?

    var current: String? { view.current }
    var next: String? { view.upcoming.first }

    private var settings: Settings { AppServices.settings }
    private var anyOn: Bool { settings.lyricsInArtist || artworkOn }
    /// 全画面のジャケットに出すか（iOS 26 から）。
    var artworkOn: Bool {
        guard settings.lyricsInArtwork else { return false }
        if #available(iOS 26.0, *) { return true }
        return false
    }

    /// アーティスト名の欄に出す行。
    var artistLine: String? { settings.lyricsInArtist ? current : nil }

    /// 再生・一時停止・曲・歌詞が変わったら呼ぶ（見張るかを決め直し、すぐ今の行を求める）。
    func refresh() {
        let playing = AppServices.player?.isPlaying ?? false
        setTicking(anyOn && syncedLines() != nil && playing)
        // 止めた・鳴らしたで、全画面のジャケットの動き（止めている間は動かさない）が変わる
        tick(force: artworkOn && playing != shownPlaying)
    }

    /// 設定が変わった（行が同じでも、出す場所が変わるので知らせ直す）。
    func settingsChanged() {
        let playing = AppServices.player?.isPlaying ?? false
        setTicking(anyOn && syncedLines() != nil && playing)
        tick(force: true)
    }

    private var shownPlaying = false

    private func syncedLines() -> [LyricLine]? {
        guard let player = AppServices.player, let song = player.current, !song.isLive,
              case .found(.synced(let s))? = player.lyrics, !s.lines.isEmpty else { return nil }
        return s.lines
    }

    private func positionMs(_ player: PlayerService) -> Int64 {
        player.isRemote ? Int64(player.position * 1000) : player.localPositionMs
    }

    private func tick(force: Bool) {
        var shown = LockScreenLines(current: nil, upcoming: [], nextStartMs: nil)
        var at: Int64 = 0
        if anyOn, let lines = syncedLines(), let player = AppServices.player {
            at = positionMs(player)
            shown = LyricsTiming.lockScreenLines(lines, positionMs: at)
        }
        let changed = shown.current != view.current || shown.upcoming != view.upcoming
        guard force || changed else { return }
        if changed {
            previous = view
            version += 1
            changedAtMs = at
        }
        view = shown
        shownPlaying = AppServices.player?.isPlaying ?? false
        AppServices.player?.lockScreenLineChanged()
    }

    /// 全画面のジャケット（3:4 の動くジャケットの欄に渡す）。出さないときは nil（いつものジャケットに戻る）。
    /// 時刻付きの歌詞が無い曲（歌詞の無い曲・時刻なしの歌詞・ライブ）も、ジャケットを全画面に出す（歌詞のある曲とない曲で
    /// ロック画面の見た目が大きく変わらないように）。
    func artwork(videoId: String, cover: UIImage?) -> AnyObject? {
        guard artworkOn, let player = AppServices.player else { return nil }
        guard #available(iOS 26.0, *) else { return nil }
        guard syncedLines() != nil else {
            guard let cover else { return nil }
            return LockScreenArtwork.shared.coverArtwork(videoId: videoId, cover: cover)
        }
        // 鳴っている間は、次の行が始まるまで（と少し）送りの動きのあと止まる動画。止めている間は動かない絵
        let playing = player.isPlaying
        var hold = 3.0
        if playing, let next = view.nextStartMs { hold = min(max(Double(next - changedAtMs) / 1000 + 4, 3), 30) }
        return LockScreenArtwork.shared.artwork(videoId: videoId, version: version, from: playing ? previous : nil, to: view,
                                                holdSeconds: hold, cover: cover)
    }

    private func setTicking(_ on: Bool) {
        guard on != (timer != nil) else { return }
        guard on else {
            timer?.cancel()
            timer = nil
            return
        }
        let t = DispatchSource.makeTimerSource(flags: .strict, queue: .main)
        t.schedule(deadline: .now() + 0.25, repeating: 0.25, leeway: .milliseconds(10))
        t.setEventHandler { MainActor.assumeIsolated { LockScreenLyrics.shared.tick(force: false) } }
        t.resume()
        timer = t
    }
}

/// ロック画面の全画面のジャケットを、歌詞の行の絵にする（iOS 26 の動くジャケット）。
/// 行が替わるたびに新しい ID のジャケットを渡す。動画は、前の行の並びから今の並びへ上に送る動き（0.55 秒）のあと、
/// 次の行が始まるころまで止まる。動画の頭は前の絵と同じなので、OS の切り替えが目立たない。
/// 絵と動画は、ロック画面が求めてきたときに初めて描く（画面が消えている間に替わった行は描かない）。
@available(iOS 26.0, *)
final class LockScreenArtwork: @unchecked Sendable {
    static let shared = LockScreenArtwork()

    private let queue = DispatchQueue(label: "wave.lock-artwork", qos: .userInitiated)
    private let lock = NSLock()
    /// 渡したジャケット（同じ ID は同じものを渡す）と、ぼかしたジャケット（曲ごと）。
    private var shown: (id: String, artwork: MPMediaItemAnimatedArtwork)?
    private var backdrops: [String: CGImage] = [:]

    static let fps: Int32 = 30
    static let moveSeconds = 0.55

    private static let folder: URL = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = caches.appendingPathComponent("LockLyrics", isDirectory: true)
        // 前に起動したときの分は消す（行ごとの短い動画で、すぐ溜まる）
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    func artwork(videoId: String, version: Int, from: LockScreenLines?, to: LockScreenLines, holdSeconds: Double, cover: UIImage?) -> MPMediaItemAnimatedArtwork {
        // ジャケットが届く前と後、動く・止まるで絵が違うので、ID にも入れる
        let id = "\(videoId)-\(version)-\(cover == nil ? 0 : 1)-\(from == nil ? "s" : "m")"
        lock.lock()
        defer { lock.unlock() }
        if let shown, shown.id == id { return shown.artwork }
        let scene = Scene(videoId: videoId, cover: cover?.cgImage, from: from, to: to, holdSeconds: holdSeconds)
        let artwork = MPMediaItemAnimatedArtwork(artworkID: id, previewImageRequestHandler: { [self] size, completion in
            queue.async {
                // 絵は動画の頭と同じ（動画が届いたときに跳ばない）
                completion(self.frame(scene, progress: scene.from == nil ? 1 : 0, size: size).map { UIImage(cgImage: $0) })
            }
        }, videoAssetFileURLRequestHandler: { [self] size, completion in
            queue.async { completion(self.video(id: id, scene: scene, size: size)) }
        })
        shown = (id, artwork)
        return artwork
    }

    /// 歌詞の無い曲の全画面のジャケット: ジャケットそのものを 3:4 いっぱいに（はみ出す左右は中央で切る）。動かない。
    func coverArtwork(videoId: String, cover: UIImage) -> MPMediaItemAnimatedArtwork {
        let id = "\(videoId)-cover"
        lock.lock()
        defer { lock.unlock() }
        if let shown, shown.id == id { return shown.artwork }
        let scene = Scene(videoId: videoId, cover: cover.cgImage, from: nil, to: LockScreenLines(current: nil, upcoming: [], nextStartMs: nil),
                          holdSeconds: 3, coverOnly: true)
        let artwork = MPMediaItemAnimatedArtwork(artworkID: id, previewImageRequestHandler: { [self] size, completion in
            queue.async { completion(self.frame(scene, progress: 1, size: size).map { UIImage(cgImage: $0) }) }
        }, videoAssetFileURLRequestHandler: { [self] size, completion in
            queue.async { completion(self.video(id: id, scene: scene, size: size)) }
        })
        shown = (id, artwork)
        return artwork
    }

    struct Scene {
        var videoId: String
        var cover: CGImage?
        var from: LockScreenLines?
        var to: LockScreenLines
        var holdSeconds: Double
        /// 歌詞を重ねず、ぼかさないジャケットだけを出す（歌詞の無い曲）。
        var coverOnly = false
    }

    // MARK: 描く（queue の上で）

    /// 描く大きさ。求められた比（3:4）のまま、幅を 1080px までにし、両辺を 16 の倍数にする（動画の符号化のため）。
    static func clipSize(_ requested: CGSize) -> CGSize {
        let width = requested.width > 0 ? min(requested.width, 1080) : 1080
        let height = requested.width > 0 ? width * requested.height / requested.width : width * 4 / 3
        func even(_ v: CGFloat) -> CGFloat { max(16, (v / 16).rounded() * 16) }
        return CGSize(width: even(width), height: even(height))
    }

    private func backdrop(videoId: String, cover: CGImage?, size: CGSize) -> CGImage? {
        let key = "\(videoId)-\(cover == nil ? 0 : 1)-\(Int(size.width))x\(Int(size.height))"
        lock.lock()
        if let cached = backdrops[key] {
            lock.unlock()
            return cached
        }
        lock.unlock()
        let made = Self.makeBackdrop(cover: cover, size: size)
        lock.lock()
        // 曲が替われば前の曲の分は要らない
        if backdrops.count > 4 { backdrops.removeAll() }
        backdrops[key] = made
        lock.unlock()
        return made
    }

    /// ジャケットをぼかさずに 3:4 の全体へ敷いたもの（縦横の大きい方に合わせ、はみ出す分は中央で切る）。
    static func makeFilledCover(cover: CGImage?, size: CGSize) -> CGImage? {
        guard let cover else { return makeBackdrop(cover: nil, size: size) }
        let rect = CGRect(origin: .zero, size: size)
        let image = CIImage(cgImage: cover)
        let scale = max(size.width / image.extent.width, size.height / image.extent.height)
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let centered = scaled.transformed(by: CGAffineTransform(translationX: (size.width - scaled.extent.width) / 2 - scaled.extent.minX,
                                                                y: (size.height - scaled.extent.height) / 2 - scaled.extent.minY))
        return CIContext().createCGImage(centered.cropped(to: rect), from: rect)
    }

    /// ジャケットを大きくぼかして暗くし、3:4 の全体に敷いたもの。ジャケットが無ければ暗い地。
    static func makeBackdrop(cover: CGImage?, size: CGSize) -> CGImage? {
        let rect = CGRect(origin: .zero, size: size)
        let context = CIContext()
        guard let cover else {
            return context.createCGImage(CIImage(color: CIColor(red: 0.08, green: 0.08, blue: 0.1)).cropped(to: rect), from: rect) // apple-ui-allow: 画像（PiP・ロック画面の絵）に描く色。システムの色は効かない
        }
        let image = CIImage(cgImage: cover)
        // 縦横の大きい方に合わせて、はみ出す分は中央で切る
        let scale = max(size.width / image.extent.width, size.height / image.extent.height) * 1.15
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let centered = scaled.transformed(by: CGAffineTransform(translationX: (size.width - scaled.extent.width) / 2 - scaled.extent.minX,
                                                                y: (size.height - scaled.extent.height) / 2 - scaled.extent.minY))
        let blurred = centered.clampedToExtent().applyingGaussianBlur(sigma: size.width * 0.06)
        // 暗くするのは掛け算（露出）で。明るさを引くと、ぼかしの薄い裾が 0 に切れて、光の縁がすっぱり切れ、赤い縞が出た
        let dimmed = blurred.applyingFilter("CIExposureAdjust", parameters: [kCIInputEVKey: -1.3])
            .applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 1.2])
        return context.createCGImage(dimmed.cropped(to: rect), from: rect)
    }

    private func frame(_ scene: Scene, progress: Double, size requested: CGSize) -> CGImage? {
        let size = Self.clipSize(requested)
        if scene.coverOnly { return Self.makeFilledCover(cover: scene.cover, size: size) }
        return Self.drawFrame(backdrop: backdrop(videoId: scene.videoId, cover: scene.cover, size: size),
                              rows: Self.rows(from: scene.from, to: scene.to), progress: progress, size: size)
    }

    /// 並べる行。from（前の並び）と to（今の並び）での位置と濃さを持つ。
    struct Row {
        var text: String
        /// その並びで、上から何番目の場所か（0 が今の行の場所。負は上へ出ていった行、nil はその並びに無い）。
        var fromSlot: Int?
        var toSlot: Int?
        var fromAlpha: Double
        var toAlpha: Double
    }

    /// 今の行・1つ先・2つ先の濃さ。今の行が無い（間奏）ときは、先の行は1段薄い。
    static func alpha(slot: Int, hasCurrent: Bool) -> Double {
        let roles = [1.0, 0.42, 0.22]
        let role = hasCurrent ? slot : slot + 1
        return role >= 0 && role < roles.count ? roles[role] : 0
    }

    static func texts(_ lines: LockScreenLines) -> [String] { (lines.current.map { [$0] } ?? []) + lines.upcoming }

    /// 前の並びから今の並びへ、1行上へ送ったか（0）・そのままか（同じ行が同じ場所）を見て、行をそろえる。そろわなければ前の並びは使わない。
    static func rows(from: LockScreenLines?, to: LockScreenLines) -> [Row] {
        let toTexts = texts(to)
        let toHas = to.current != nil
        func plain() -> [Row] {
            toTexts.enumerated().map { Row(text: $1, fromSlot: nil, toSlot: $0, fromAlpha: 0, toAlpha: alpha(slot: $0, hasCurrent: toHas)) }
        }
        guard let from else { return plain() }
        let fromTexts = texts(from)
        let fromHas = from.current != nil
        // to の i 番目が from の i + shift 番目に当たる shift を探す（重なる行がすべて同じ）
        for shift in [1, 0] {
            let overlap = min(fromTexts.count - shift, toTexts.count)
            guard overlap >= 1, (0..<overlap).allSatisfy({ fromTexts[$0 + shift] == toTexts[$0] }) else { continue }
            var rows: [Row] = []
            for (i, t) in fromTexts.enumerated() {
                let toSlot = i - shift
                rows.append(Row(text: t, fromSlot: i, toSlot: toSlot,
                                fromAlpha: alpha(slot: i, hasCurrent: fromHas), toAlpha: alpha(slot: toSlot, hasCurrent: toHas)))
            }
            for i in max(0, fromTexts.count - shift)..<toTexts.count {
                rows.append(Row(text: toTexts[i], fromSlot: i + shift, toSlot: i,
                                fromAlpha: 0, toAlpha: alpha(slot: i, hasCurrent: toHas)))
            }
            return rows
        }
        return plain()
    }

    /// 1枚の絵: ぼかしたジャケットの上に、今の行を白く、先の行を薄く、同じ大きさで並べる（Apple Music の歌詞と同じ並べ方）。
    /// 時計（上）と操作（下）に掛からない帯に置き、下の端は薄くして消す。progress は前の並び（0）から今の並び（1）への進み。
    static func drawFrame(backdrop: CGImage?, rows: [Row], progress: Double, size: CGSize) -> CGImage? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let t = 1 - pow(1 - min(max(progress, 0), 1), 3)   // 終わりにゆっくり止まる
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let cg = context.cgContext
            if let backdrop {
                // UIKit の描き場は上から下なので、CGImage は上下を返して置く
                cg.saveGState()
                cg.translateBy(x: 0, y: size.height)
                cg.scaleBy(x: 1, y: -1)
                cg.draw(backdrop, in: CGRect(origin: .zero, size: size))
                cg.restoreGState()
            } else {
                UIColor(white: 0.08, alpha: 1).setFill() // apple-ui-allow: 画像（PiP・ロック画面の絵）に描く色。システムの色は効かない
                context.fill(CGRect(origin: .zero, size: size))
            }
            let margin = size.width * 0.08
            let width = size.width - margin * 2
            let paragraph = NSMutableParagraphStyle()
            paragraph.lineBreakMode = .byWordWrapping
            paragraph.lineSpacing = size.width * 0.01
            let font = UIFont.systemFont(ofSize: size.width * 0.072, weight: .heavy) // apple-ui-allow: 画像（PiP・ロック画面の絵）に描く字。Dynamic Type は効かない
            let gap = size.width * 0.045
            let anchor = size.height * 0.17
            let fadeFrom = size.height * 0.5, fadeTo = size.height * 0.62
            func string(_ text: String, alpha: Double) -> NSAttributedString {
                NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: UIColor(white: 1, alpha: alpha), .paragraphStyle: paragraph]) // apple-ui-allow: 画像（PiP・ロック画面の絵）に描く色。システムの色は効かない
            }
            let heights = rows.map {
                ceil(string($0.text, alpha: 1).boundingRect(with: CGSize(width: width, height: .greatestFiniteMagnitude),
                                                             options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil).height)
            }
            /// 並びの中の場所 slot にある行の上端（0 番目が anchor。行の高さを積んで決める）。
            func top(_ slotOf: (Row) -> Int?) -> [CGFloat?] {
                let placed = rows.indices.compactMap { i in slotOf(rows[i]).map { (i, $0) } }.sorted { $0.1 < $1.1 }
                var tops = [CGFloat?](repeating: nil, count: rows.count)
                guard let zero = placed.firstIndex(where: { $0.1 >= 0 }) ?? placed.indices.last.map({ $0 + 1 }) else { return tops }
                var y = anchor
                for k in zero..<placed.count {
                    tops[placed[k].0] = y
                    y += heights[placed[k].0] + gap
                }
                y = anchor
                for k in stride(from: zero - 1, through: 0, by: -1) {
                    y -= heights[placed[k].0] + gap
                    tops[placed[k].0] = y
                }
                return tops
            }
            let fromTops = top { $0.fromSlot }
            let toTops = top { $0.toSlot }
            for (i, row) in rows.enumerated() {
                // 片方の並びに無い行は、もう片方の位置から1行分ずらした所から出入りする
                let toY = toTops[i] ?? ((fromTops[i] ?? anchor) - heights[i] - gap)
                let fromY = fromTops[i] ?? (toY + heights[i] * 0.6)
                let y = fromY + (toY - fromY) * t
                var alpha = row.fromAlpha + (row.toAlpha - row.fromAlpha) * t
                if y > fadeFrom { alpha *= max(0, 1 - (y - fadeFrom) / (fadeTo - fadeFrom)) }
                if y < anchor * 0.6 { alpha *= max(0, (y + heights[i]) / (anchor * 0.6 + heights[i])) }
                guard alpha > 0.01 else { continue }
                string(row.text, alpha: alpha).draw(with: CGRect(x: margin, y: y, width: width, height: heights[i]),
                                                    options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
            }
        }
        return image.cgImage
    }

    /// 送りの動き（前の並びがあるとき）のあと、今の並びのまま holdSeconds まで止まる H.264 の動画を書いて、その場所を返す。
    private func video(id: String, scene: Scene, size requested: CGSize) -> URL? {
        let size = Self.clipSize(requested)
        let file = Self.folder.appendingPathComponent("\(id)-\(Int(size.width)).mp4")
        if FileManager.default.fileExists(atPath: file.path) { return file }
        // ほかの曲の動画と、この曲の古い行の動画（新しい6つを残す）は消す
        if let old = try? FileManager.default.contentsOfDirectory(at: Self.folder, includingPropertiesForKeys: [.creationDateKey]) {
            for url in old where !url.lastPathComponent.hasPrefix(scene.videoId) { try? FileManager.default.removeItem(at: url) }
            func made(_ url: URL) -> Date { (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast }
            let mine = old.filter { $0.lastPathComponent.hasPrefix(scene.videoId) }.sorted { made($0) < made($1) }
            if mine.count > 6 { mine.prefix(mine.count - 6).forEach { try? FileManager.default.removeItem(at: $0) } }
        }
        let moving = scene.from != nil ? Int((Self.moveSeconds * Double(Self.fps)).rounded()) : 0
        var frames: [(CGImage, Int)] = []
        for k in 0...moving {
            guard let image = frame(scene, progress: moving == 0 ? 1 : Double(k) / Double(moving), size: requested) else { return nil }
            frames.append((image, k))
        }
        let total = max(Int(scene.holdSeconds * Double(Self.fps)), moving + 1)
        return Self.write(frames, lastFrame: total - 1, to: file, size: size) ? file : nil
    }

    /// frames（絵とコマの番号）を書き、最後の絵を lastFrame のコマまで続ける。
    static func write(_ frames: [(CGImage, Int)], lastFrame: Int, to file: URL, size: CGSize) -> Bool {
        try? FileManager.default.removeItem(at: file)
        guard let last = frames.last, let writer = try? AVAssetWriter(outputURL: file, fileType: .mp4) else { return false }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(size.width),
            AVVideoHeightKey: Int(size.height),
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: Int(size.width),
            kCVPixelBufferHeightKey as String: Int(size.height),
        ])
        guard writer.canAdd(input) else { return false }
        writer.add(input)
        guard writer.startWriting() else { return false }
        writer.startSession(atSourceTime: .zero)
        var all = frames
        if lastFrame > last.1 { all.append((last.0, lastFrame)) }
        for (image, index) in all {
            guard let buffer = pixelBuffer(image, size: size, pool: adaptor.pixelBufferPool) else {
                writer.cancelWriting()
                return false
            }
            while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.005) }
            guard adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(index), timescale: fps)) else {
                writer.cancelWriting()
                return false
            }
        }
        input.markAsFinished()
        writer.endSession(atSourceTime: CMTime(value: CMTimeValue(lastFrame + 1), timescale: fps))
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        return writer.status == .completed
    }

    private static func pixelBuffer(_ image: CGImage, size: CGSize, pool: CVPixelBufferPool?) -> CVPixelBuffer? {
        var buffer: CVPixelBuffer?
        if let pool {
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
        } else {
            CVPixelBufferCreate(nil, Int(size.width), Int(size.height), kCVPixelFormatType_32BGRA,
                                [kCVPixelBufferCGImageCompatibilityKey: true, kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary, &buffer)
        }
        guard let buffer else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: Int(size.width), height: Int(size.height),
                                      bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else { return nil }
        context.draw(image, in: CGRect(origin: .zero, size: size))
        return buffer
    }
}
#endif
