using System.Globalization;
using System.Security;

namespace Wave.Core.Stream;

/// <summary>
/// 1つの音声ファイル（YouTube の DASH 用の断片化 MP4）だけを持つ DASH の目次（MPD）を作る。
/// 実際の URL は書かず、placeholder を書く（バイトは再生の部品の求めに応じてアプリが取って渡す）。
/// </summary>
public static class DashManifest
{
    public static string Build(DashInfo dash, string mimeType, string placeholderUrl)
    {
        var duration = TimeSpan.FromMilliseconds(Math.Max(1, dash.DurationMs)).TotalSeconds.ToString("0.###", CultureInfo.InvariantCulture);
        var mime = mimeType.Split(';')[0].Trim();
        var video = mime.StartsWith("video/", StringComparison.Ordinal);
        var representation = video
            ? $"""id="video" codecs="{SecurityElement.Escape(dash.Codecs)}" bandwidth="{dash.Bitrate}" width="{dash.Width}" height="{dash.Height}" frameRate="{Math.Max(1, dash.FrameRate)}" """
            : $"""id="audio" codecs="{SecurityElement.Escape(dash.Codecs)}" bandwidth="{dash.Bitrate}" audioSamplingRate="{dash.SampleRate}" """;
        return $"""
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT{duration}S" minBufferTime="PT2S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
              <Period>
                <AdaptationSet mimeType="{SecurityElement.Escape(mime)}" segmentAlignment="true" startWithSAP="1">
                  <Representation {representation.Trim()}>
                    <BaseURL>{SecurityElement.Escape(placeholderUrl)}</BaseURL>
                    <SegmentBase indexRange="{dash.IndexStart}-{dash.IndexEnd}">
                      <Initialization range="{dash.InitStart}-{dash.InitEnd}" />
                    </SegmentBase>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """;
    }
}
