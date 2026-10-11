import Foundation

/// 1つの音声ファイル（YouTube の DASH 用の断片化 MP4）だけを持つ DASH の目次（MPD）を作る。
/// 実際の URL は書かず、placeholder を書く（バイトは再生の部品の求めに応じてアプリが取って渡す）。
/// （Windows で映像（MV）の再生に使っているもの。macOS の AVPlayer は DASH を読めないので、使わないかもしれない）
public enum DashManifest {
    public static func build(_ dash: DashInfo, mimeType: String, placeholderUrl: String) -> String {
        let duration = seconds(max(1, dash.durationMs))
        let mime = (mimeType.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let video = mime.hasPrefix("video/")
        let representation = video
            ? "id=\"video\" codecs=\"\(escape(dash.codecs))\" bandwidth=\"\(dash.bitrate)\" width=\"\(dash.width)\" height=\"\(dash.height)\" frameRate=\"\(max(1, dash.frameRate))\""
            : "id=\"audio\" codecs=\"\(escape(dash.codecs))\" bandwidth=\"\(dash.bitrate)\" audioSamplingRate=\"\(dash.sampleRate)\""
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT\(duration)S" minBufferTime="PT2S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
              <Period>
                <AdaptationSet mimeType="\(escape(mime))" segmentAlignment="true" startWithSAP="1">
                  <Representation \(representation)>
                    <BaseURL>\(escape(placeholderUrl))</BaseURL>
                    <SegmentBase indexRange="\(dash.indexStart)-\(dash.indexEnd)">
                      <Initialization range="\(dash.initStart)-\(dash.initEnd)" />
                    </SegmentBase>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """
    }

    /// ミリ秒を秒にして、小数は3桁まで・末尾の0を書かない（C# の ToString("0.###") と同じ）。
    static func seconds(_ ms: Int64) -> String {
        let whole = ms / 1000
        var fraction = String(format: "%03lld", ms % 1000)
        while fraction.hasSuffix("0") { fraction.removeLast() }
        return fraction.isEmpty ? String(whole) : "\(whole).\(fraction)"
    }

    /// XML の特別な文字を置き換える（C# の SecurityElement.Escape と同じ）。
    static func escape(_ text: String) -> String {
        var out = ""
        for c in text {
            switch c {
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            case "&": out += "&amp;"
            default: out.append(c)
            }
        }
        return out
    }
}
