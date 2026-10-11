import SwiftUI
#if os(macOS)
    import AppKit
    /// その OS の画像（Mac は NSImage、iPhone は UIImage）。
    typealias PlatformImage = NSImage
#else
    import UIKit
    typealias PlatformImage = UIImage
#endif

extension Image {
    init(platformImage: PlatformImage) {
        #if os(macOS)
            self.init(nsImage: platformImage)
        #else
            self.init(uiImage: platformImage)
        #endif
    }
}

extension PlatformImage {
    /// 色を読むための CGImage。
    var cgImageForReading: CGImage? {
        #if os(macOS)
            cgImage(forProposedRect: nil, context: nil, hints: nil)
        #else
            cgImage
        #endif
    }
}

/// 画像の取得（認証情報を持たない通信で、URL ごとに覚えておく）。
enum ImageLoader {
    static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.httpCookieStorage = nil
        config.urlCache = URLCache(memoryCapacity: 64 * 1024 * 1024, diskCapacity: 256 * 1024 * 1024)
        config.timeoutIntervalForRequest = 15
        return URLSession(configuration: config)
    }()

    private static let cache = NSCache<NSURL, PlatformImage>()

    static func cached(_ url: URL) -> PlatformImage? { cache.object(forKey: url as NSURL) }

    static func load(_ url: URL) async -> PlatformImage? {
        if let hit = cache.object(forKey: url as NSURL) { return hit }
        guard url.scheme == "https", let (data, _) = try? await session.data(from: url), let image = PlatformImage(data: data) else { return nil }
        cache.setObject(image, forKey: url as NSURL)
        return image
    }
}

/// URL の画像（読み込み中は地の色）。枠いっぱいに切り抜いて出す。
struct RemoteImage: View {
    let url: URL?
    var contentMode: ContentMode = .fill

    @State private var image: PlatformImage?

    var body: some View {
        // 地（Color.clear）は渡された大きさをそのまま取るので、画像が大きくても枠からはみ出さない
        Color.clear
            .overlay {
                if let image {
                    Image(platformImage: image).resizable().aspectRatio(contentMode: contentMode)
                }
            }
            .clipped()
            .task(id: url) {
            guard let url else {
                image = nil
                return
            }
            if let hit = ImageLoader.cached(url) {
                image = hit
                return
            }
            image = nil
            image = await ImageLoader.load(url)
        }
    }
}

/// ジャケットの色（Spotify のページの上のグラデーションに使う）。Windows の Artwork と同じ求め方。
enum Artwork {
    @MainActor private static var colors: [String: Color?] = [:]

    /// 小さく縮めた画像の、鮮やかさで重みを付けた平均の色。取れなければ nil。
    @MainActor
    static func dominantColor(_ url: String?) async -> Color? {
        guard let url, let small = Ui.resize(url, 48), small.scheme == "https" else { return nil }
        if let hit = colors[url] { return hit }
        var color: Color?
        if let image = await ImageLoader.load(small), let cg = image.cgImageForReading {
            let size = 24
            var px = [UInt8](repeating: 0, count: size * size * 4)
            if let ctx = CGContext(data: &px, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                                   space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
            {
                ctx.draw(cg, in: CGRect(x: 0, y: 0, width: size, height: size))
                var r = 0.0, g = 0.0, b = 0.0, w = 0.0
                for i in stride(from: 0, to: px.count, by: 4) {
                    let pr = Double(px[i]), pg = Double(px[i + 1]), pb = Double(px[i + 2])
                    let mx = max(pr, pg, pb), mn = min(pr, pg, pb)
                    // 鮮やかで、暗すぎない色を重く見る
                    let weight = (mx - mn) / 255 * (mx / 255) + 0.02
                    r += pr * weight
                    g += pg * weight
                    b += pb * weight
                    w += weight
                }
                if w > 0 { color = Color(red: r / w / 255, green: g / w / 255, blue: b / w / 255) }
            }
        }
        colors[url] = color
        return color
    }
}
