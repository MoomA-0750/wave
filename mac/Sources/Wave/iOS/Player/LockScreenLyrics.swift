#if os(iOS)
import AVFoundation
import CoreImage
import Foundation
import MediaPlayer
import UIKit
import WaveCore

/// ロック画面に出す、今歌っている歌詞の行と次の行（時刻付きの歌詞があるときだけ）。出す場所は設定で選ぶ:
/// 再生中の情報のアーティスト名の欄・ロック画面の再生中（Live Activity）・ロック画面の全画面のジャケット（iOS 26 から）。
/// 行が変わったら、再生中の情報と Live Activity を知らせ直す。
///
/// 見張るのは鳴っている間だけ、4 回／秒。止めている間は行が動かないので見ない。
/// 普通の Timer は画面が消えている間に間引かれて行が止まるので、間引かれない GCD のタイマー（strict）で回す。
@MainActor final class LockScreenLyrics {
    static let shared = LockScreenLyrics()

    /// 今の行（間奏と、最初の行より前は nil）と次の行。どの場所も切ってあれば両方 nil。
    private(set) var current: String?
    private(set) var next: String?
    /// 今の行の位置（全画面のジャケットの ID に使う。最初の行より前は -1）。
    private var index = -1

    private var timer: DispatchSourceTimer?

    private var settings: Settings { AppServices.settings }
    private var anyOn: Bool { settings.lyricsInArtist || settings.lyricsInLiveActivity || artworkOn }
    /// 全画面のジャケットに出すか（iOS 26 から）。
    var artworkOn: Bool {
        guard settings.lyricsInArtwork else { return false }
        if #available(iOS 26.0, *) { return true }
        return false
    }

    /// アーティスト名の欄に出す行。
    var artistLine: String? { settings.lyricsInArtist ? current : nil }
    /// Live Activity に出す行と次の行。
    var activityLines: (line: String?, next: String?) { settings.lyricsInLiveActivity ? (current, next) : (nil, nil) }

    /// 再生・一時停止・曲・歌詞が変わったら呼ぶ（見張るかを決め直し、すぐ今の行を求める）。
    func refresh() {
        let playing = AppServices.player?.isPlaying ?? false
        setTicking(anyOn && syncedLines() != nil && playing)
        tick(force: false)
    }

    /// 設定が変わった（行が同じでも、出す場所が変わるので知らせ直す）。
    func settingsChanged() {
        let playing = AppServices.player?.isPlaying ?? false
        setTicking(anyOn && syncedLines() != nil && playing)
        tick(force: true)
    }

    private func syncedLines() -> [LyricLine]? {
        guard let player = AppServices.player, let song = player.current, !song.isLive,
              case .found(.synced(let s))? = player.lyrics, !s.lines.isEmpty else { return nil }
        return s.lines
    }

    private func tick(force: Bool) {
        var line: (current: String?, next: String?) = (nil, nil)
        var at = -1
        if anyOn, let lines = syncedLines(), let player = AppServices.player {
            let positionMs = player.isRemote ? Int64(player.position * 1000) : player.localPositionMs
            line = LyricsTiming.lockScreen(lines, positionMs: positionMs)
            at = LyricsTiming.currentIndex(lines, positionMs: positionMs)
        }
        guard force || line.current != current || line.next != next else { return }
        current = line.current
        next = line.next
        index = at
        AppServices.player?.lockScreenLineChanged()
        WidgetSync.shared.lockScreenLineChanged()
    }

    /// 全画面のジャケット（3:4 の動くジャケットの欄に渡す）。出さないときは nil（いつものジャケットに戻る）。
    func artwork(videoId: String, cover: UIImage?) -> AnyObject? {
        guard artworkOn, syncedLines() != nil else { return nil }
        if #available(iOS 26.0, *) {
            return LockScreenArtwork.shared.artwork(videoId: videoId, index: index, line: current, next: next, cover: cover)
        }
        return nil
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
/// 行が替わるたびに新しい ID のジャケットを渡す。絵と動画は、ロック画面が求めてきたときに初めて描く
/// （画面が消えている間に替わった行は描かない）。動画は同じ絵が2秒続くだけのもの（つなぎ目無く繰り返せる）。
@available(iOS 26.0, *)
final class LockScreenArtwork: @unchecked Sendable {
    static let shared = LockScreenArtwork()

    private let queue = DispatchQueue(label: "wave.lock-artwork", qos: .userInitiated)
    private let lock = NSLock()
    /// 渡したジャケット（同じ ID は同じものを渡す）と、ぼかしたジャケット（曲ごと）。
    private var shown: (id: String, artwork: MPMediaItemAnimatedArtwork)?
    private var backdrops: [String: CGImage] = [:]

    private static let folder: URL = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = caches.appendingPathComponent("LockLyrics", isDirectory: true)
        // 前に起動したときの分は消す（行ごとの短い動画で、すぐ溜まる）
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    func artwork(videoId: String, index: Int, line: String?, next: String?, cover: UIImage?) -> MPMediaItemAnimatedArtwork {
        // ジャケットが届く前と後で絵が違うので、ID にも入れる
        let id = "\(videoId)-\(index)-\(cover == nil ? 0 : 1)"
        lock.lock()
        defer { lock.unlock() }
        if let shown, shown.id == id { return shown.artwork }
        let coverImage = cover?.cgImage
        let artwork = MPMediaItemAnimatedArtwork(artworkID: id, previewImageRequestHandler: { [self] size, completion in
            queue.async {
                let frame = self.frame(videoId: videoId, cover: coverImage, line: line, next: next, size: size)
                completion(frame.map { UIImage(cgImage: $0) })
            }
        }, videoAssetFileURLRequestHandler: { [self] size, completion in
            queue.async {
                completion(self.video(id: id, videoId: videoId, cover: coverImage, line: line, next: next, size: size))
            }
        })
        shown = (id, artwork)
        return artwork
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

    /// ジャケットを大きくぼかして暗くし、3:4 の全体に敷いたもの。ジャケットが無ければ暗い地。
    static func makeBackdrop(cover: CGImage?, size: CGSize) -> CGImage? {
        let rect = CGRect(origin: .zero, size: size)
        let context = CIContext()
        guard let cover else {
            return context.createCGImage(CIImage(color: CIColor(red: 0.08, green: 0.08, blue: 0.1)).cropped(to: rect), from: rect)
        }
        let image = CIImage(cgImage: cover)
        // 縦横の大きい方に合わせて、はみ出す分は中央で切る
        let scale = max(size.width / image.extent.width, size.height / image.extent.height) * 1.15
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let centered = scaled.transformed(by: CGAffineTransform(translationX: (size.width - scaled.extent.width) / 2 - scaled.extent.minX,
                                                                y: (size.height - scaled.extent.height) / 2 - scaled.extent.minY))
        let blurred = centered.clampedToExtent().applyingGaussianBlur(sigma: size.width * 0.06)
        let dimmed = blurred.applyingFilter("CIColorControls", parameters: [kCIInputBrightnessKey: -0.28, kCIInputSaturationKey: 1.25])
        return context.createCGImage(dimmed.cropped(to: rect), from: rect)
    }

    private func frame(videoId: String, cover: CGImage?, line: String?, next: String?, size requested: CGSize) -> CGImage? {
        let size = Self.clipSize(requested)
        return Self.drawFrame(backdrop: backdrop(videoId: videoId, cover: cover, size: size), line: line, next: next, size: size)
    }

    /// 1枚の絵: ぼかしたジャケットの上に、今の行を大きく、次の行を薄く。時計（上）と操作（下）に掛からない真ん中の帯に置く。
    static func drawFrame(backdrop: CGImage?, line: String?, next: String?, size: CGSize) -> CGImage? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            if let backdrop {
                // UIKit の描き場は上から下なので、CGImage は上下を返して置く
                let cg = context.cgContext
                cg.saveGState()
                cg.translateBy(x: 0, y: size.height)
                cg.scaleBy(x: 1, y: -1)
                cg.draw(backdrop, in: CGRect(origin: .zero, size: size))
                cg.restoreGState()
            } else {
                UIColor(white: 0.08, alpha: 1).setFill()
                context.fill(CGRect(origin: .zero, size: size))
            }
            let margin = size.width * 0.08
            let width = size.width - margin * 2
            let paragraph = NSMutableParagraphStyle()
            paragraph.lineBreakMode = .byWordWrapping
            paragraph.lineSpacing = size.width * 0.012
            func attributed(_ text: String, size fontSize: CGFloat, alpha: CGFloat) -> NSAttributedString {
                NSAttributedString(string: text, attributes: [
                    .font: UIFont.systemFont(ofSize: fontSize, weight: .heavy),
                    .foregroundColor: UIColor(white: 1, alpha: alpha),
                    .paragraphStyle: paragraph,
                ])
            }
            var blocks: [NSAttributedString] = []
            if let line { blocks.append(attributed(line, size: size.width * 0.085, alpha: 1)) }
            if let next { blocks.append(attributed(next, size: size.width * (line == nil ? 0.085 : 0.06), alpha: 0.42)) }
            let gap = size.width * 0.05
            // 帯（真ん中の 40%）に収まるよう、行の高さを測ってから縦に中央へ
            let band = CGRect(x: margin, y: size.height * 0.32, width: width, height: size.height * 0.4)
            let heights = blocks.map {
                ceil($0.boundingRect(with: CGSize(width: width, height: band.height), options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil).height)
            }
            let total = heights.reduce(0, +) + gap * CGFloat(max(0, blocks.count - 1))
            var y = band.minY + max(0, (band.height - total) / 2)
            for (block, height) in zip(blocks, heights) {
                let fit = min(height, band.maxY - y)
                guard fit > 0 else { break }
                block.draw(with: CGRect(x: margin, y: y, width: width, height: fit), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], context: nil)
                y += height + gap
            }
        }
        return image.cgImage
    }

    /// 同じ絵が2秒続く H.264 の動画を書いて、その場所を返す（書けなければ nil。ロック画面は絵だけを出す）。
    private func video(id: String, videoId: String, cover: CGImage?, line: String?, next: String?, size requested: CGSize) -> URL? {
        let size = Self.clipSize(requested)
        let file = Self.folder.appendingPathComponent("\(id)-\(Int(size.width)).mp4")
        if FileManager.default.fileExists(atPath: file.path) { return file }
        guard let frame = frame(videoId: videoId, cover: cover, line: line, next: next, size: requested) else { return nil }
        // ほかの曲の動画と、この曲の古い行の動画（新しい6つを残す）は消す
        if let old = try? FileManager.default.contentsOfDirectory(at: Self.folder, includingPropertiesForKeys: [.creationDateKey]) {
            for url in old where !url.lastPathComponent.hasPrefix(videoId) { try? FileManager.default.removeItem(at: url) }
            let mine = old.filter { $0.lastPathComponent.hasPrefix(videoId) }
                .sorted { (try? $0.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast < (try? $1.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast }
            if mine.count > 6 { mine.prefix(mine.count - 6).forEach { try? FileManager.default.removeItem(at: $0) } }
        }
        return Self.writeStill(frame, to: file, size: size) ? file : nil
    }

    /// 同じ絵が seconds 秒続く H.264 の動画を書く。
    static func writeStill(_ image: CGImage, to file: URL, size: CGSize, seconds: Int64 = 2) -> Bool {
        try? FileManager.default.removeItem(at: file)
        guard let writer = try? AVAssetWriter(outputURL: file, fileType: .mp4) else { return false }
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
        guard let buffer = pixelBuffer(image, size: size, pool: adaptor.pixelBufferPool) else {
            writer.cancelWriting()
            return false
        }
        // 頭と終わりの近くに同じ絵を置く（その間は同じ絵のまま）
        for time in [CMTime.zero, CMTime(value: seconds * 30 - 1, timescale: 30)] {
            while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.005) }
            guard adaptor.append(buffer, withPresentationTime: time) else {
                writer.cancelWriting()
                return false
            }
        }
        input.markAsFinished()
        writer.endSession(atSourceTime: CMTime(value: seconds, timescale: 1))
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
