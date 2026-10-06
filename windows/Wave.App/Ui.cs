using System.Text.RegularExpressions;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Wave.Core.Music;

namespace Wave.App;

/// <summary>画面の x:Bind から呼ぶ小さな変換。</summary>
public static partial class Ui
{
    /// <summary>画像の URL を、表示する大きさに合うものにして読む（YouTube の画像は URL で大きさを選べる）。</summary>
    public static ImageSource? Img(string? url) => Img(url, 226);

    public static ImageSource? Img(string? url, int size)
    {
        if (string.IsNullOrEmpty(url) || !Uri.TryCreate(Resize(url, size), UriKind.Absolute, out var uri)) return null;
        return new BitmapImage(uri) { DecodePixelWidth = size, DecodePixelType = DecodePixelType.Logical };
    }

    public static ImageSource? Large(string? url) => Img(url, 544);

    public static ImageSource? Small(string? url) => Img(url, 96);

    internal static string Resize(string url, int size)
    {
        // lh3.googleusercontent.com/...=w120-h120-l90-rj の形
        var px = Math.Max(size * 2, 120);
        if (SizedGoogle().IsMatch(url)) return SizedGoogle().Replace(url, $"=w{px}-h{px}$1");
        return url;
    }

    [GeneratedRegex(@"=w\d+-h\d+((?:-[a-z0-9]+)*)$")]
    private static partial Regex SizedGoogle();

    public static string Duration(int? seconds) => seconds is { } s ? Time(s) : "";

    public static string Time(double seconds)
    {
        var t = TimeSpan.FromSeconds(Math.Max(0, seconds));
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes}:{t.Seconds:00}";
    }

    public static Visibility Show(bool value) => value ? Visibility.Visible : Visibility.Collapsed;

    public static Visibility ShowText(string? value) => string.IsNullOrEmpty(value) ? Visibility.Collapsed : Visibility.Visible;

    public static Visibility Hide(bool value) => value ? Visibility.Collapsed : Visibility.Visible;

    public static string Or(string? value, string fallback) => string.IsNullOrEmpty(value) ? fallback : value;

    public static bool IsArtist(HomeItem item) => item is HomeItem.ArtistItem;

    /// <summary>アーティストは丸、ほかは角の少し丸い四角（Spotify と同じ）。</summary>
    public static CornerRadius CoverRadius(HomeItem? item) => Radius(item is HomeItem.ArtistItem);

    public static CornerRadius Radius(bool round) => round ? new CornerRadius(999) : new CornerRadius(6);

    public static string Kind(HomeItem item) => item switch
    {
        HomeItem.SongItem => "曲",
        HomeItem.AlbumItem => "アルバム",
        HomeItem.PlaylistItem => "プレイリスト",
        HomeItem.ArtistItem => "アーティスト",
        _ => "",
    };

    /// <summary>白い文字が読めるよう、明るすぎる色を暗くする（YouTube の色は淡いものがある）。</summary>
    private static Windows.UI.Color Readable(byte r, byte g, byte b)
    {
        var luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
        var factor = luminance > 0.45 ? 0.45 / luminance : 1;
        return Windows.UI.Color.FromArgb(255, (byte)(r * factor), (byte)(g * factor), (byte)(b * factor));
    }

    /// <summary>ARGB の数を色に（気分・ジャンルのタイル）。</summary>
    public static Brush Tile(long? argb, int index)
    {
        if (argb is { } v) return new SolidColorBrush(Readable((byte)(v >> 16), (byte)(v >> 8), (byte)v));
        var fallback = new[] { 0xFF8D67AB, 0xFFE8115B, 0xFF1E3264, 0xFF148A08, 0xFFBA5D07, 0xFF503750, 0xFF477D95, 0xFFAF2896 };
        var f = fallback[index % fallback.Length];
        return new SolidColorBrush(Windows.UI.Color.FromArgb(255, (byte)(f >> 16), (byte)(f >> 8), (byte)f));
    }
}
