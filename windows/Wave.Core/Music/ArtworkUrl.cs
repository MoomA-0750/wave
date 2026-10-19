using System.Text.RegularExpressions;

namespace Wave.Core.Music;

/// <summary>
/// YouTube の画像の URL を、表示する大きさに合うものにする（URL で大きさを選べる）。
/// Android の largeArtwork と同じ形を扱う: googleusercontent・ggpht の「=w…-h…」「=s…」と、i.ytimg の動画のサムネイル。
/// </summary>
public static partial class ArtworkUrl
{
    /// <summary>この大きさ（物理ピクセル）より大きく出すときは、i.ytimg のサムネイルを hqdefault（480×360）にする。</summary>
    public const int YtimgHqFromPx = 1000;

    /// <param name="px">欲しい幅（物理ピクセル）。</param>
    public static string Sized(string url, int px)
    {
        // lh3.googleusercontent.com/...=w120-h120-l90-rj の形（後ろの指定は残す）
        if (SizedGoogle().IsMatch(url)) return SizedGoogle().Replace(url, $"=w{px}-h{px}$1");
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return url;
        var host = uri.Host;
        if (host.EndsWith(".googleusercontent.com", StringComparison.OrdinalIgnoreCase) || host.EndsWith(".ggpht.com", StringComparison.OrdinalIgnoreCase))
        {
            // =s120-c-k-c0x00ffffff-no-rj の形（アーティスト・チャンネルの画像）
            if (SquareGoogle().IsMatch(url)) return SquareGoogle().Replace(url, $"=s{px}$1");
            // 大きさの指定が無い（= も無い）ものには付ける
            if (string.IsNullOrEmpty(uri.Query) && !uri.AbsolutePath.Contains('=')) return url + $"=w{px}-h{px}";
            return url;
        }
        // i.ytimg.com/vi/ID/sddefault.jpg?sqp=… の形（大きく出すときだけ。署名付きの問い合わせは名前を変えると合わないので外す）
        if (px >= YtimgHqFromPx && host.Equals("i.ytimg.com", StringComparison.OrdinalIgnoreCase) && YtimgThumbnail().IsMatch(uri.AbsolutePath))
            return YtimgThumbnail().Replace(uri.GetLeftPart(UriPartial.Path), "/hqdefault.jpg");
        return url;
    }

    [GeneratedRegex(@"=w\d+-h\d+((?:-[a-z0-9]+)*)$")]
    private static partial Regex SizedGoogle();

    [GeneratedRegex(@"=s\d+((?:-[a-z0-9]+)*)$")]
    private static partial Regex SquareGoogle();

    [GeneratedRegex(@"/[a-z0-9_]+\.jpg$")]
    private static partial Regex YtimgThumbnail();
}
