using System.Text.RegularExpressions;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Wave.Core.Music;

namespace Wave.App;

/// <summary>画面の x:Bind から呼ぶ小さな変換。</summary>
public static partial class Ui
{
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    /// <summary>窓の大きさを、画面の拡大率に合わせて論理ピクセルで決める（AppWindow.Resize は物理ピクセル）。</summary>
    public static void ResizeLogical(Microsoft.UI.Xaml.Window window, int width, int height)
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(window);
        var scale = Math.Max(1.0, GetDpiForWindow(hwnd) / 96.0);
        window.AppWindow.Resize(new Windows.Graphics.SizeInt32((int)(width * scale), (int)(height * scale)));
    }

    /// <summary>画像の URL を、表示する大きさに合うものにして読む（YouTube の画像は URL で大きさを選べる）。</summary>
    public static ImageSource? Img(string? url) => Img(url, 226);

    public static ImageSource? Img(string? url, int size)
    {
        if (string.IsNullOrEmpty(url) || !Uri.TryCreate(Resize(url, size), UriKind.Absolute, out var uri)) return null;
        return new BitmapImage(uri) { DecodePixelWidth = size, DecodePixelType = DecodePixelType.Logical };
    }

    public static ImageSource? Large(string? url) => Img(url, 544);

    public static ImageSource? Small(string? url) => Img(url, 96);

    // 形ごとの直し方（=w-h・=s・i.ytimg）は Core の ArtworkUrl（Android の largeArtwork と同じ形を扱う）
    internal static string Resize(string url, int size) => Wave.Core.Music.ArtworkUrl.Sized(url, Math.Max(size * 2, 120));

    public static string Duration(int? seconds) => seconds is { } s ? Time(s) : "";

    public static string Time(double seconds)
    {
        var t = TimeSpan.FromSeconds(Math.Max(0, seconds));
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes}:{t.Seconds:00}";
    }

    public static Visibility Show(bool value) => value ? Visibility.Visible : Visibility.Collapsed;

    public static Visibility ShowText(string? value) => string.IsNullOrEmpty(value) ? Visibility.Collapsed : Visibility.Visible;

    public static Visibility Hide(bool value) => value ? Visibility.Collapsed : Visibility.Visible;

    public static bool IsArtist(HomeItem item) => item is HomeItem.ArtistItem;

    /// <summary>アーティストは丸、ほかは角の少し丸い四角（Spotify と同じ）。</summary>
    public static CornerRadius CoverRadius(HomeItem? item) => Radius(item is HomeItem.ArtistItem);

    public static CornerRadius Radius(bool round) => round ? new CornerRadius(999) : new CornerRadius(6);

    public static string Kind(HomeItem item) => item switch
    {
        HomeItem.SongItem => L.T("曲", "Song"),
        HomeItem.AlbumItem => L.T("アルバム", "Album"),
        HomeItem.PlaylistItem => L.T("プレイリスト", "Playlist"),
        HomeItem.ArtistItem => L.T("アーティスト", "Artist"),
        HomeItem.PodcastItem => L.T("ポッドキャスト", "Podcast"),
        _ => "",
    };

    /// <summary>高く評価した曲のジャケット（紫から緑への斜めのグラデーション。ArtistPage.xaml の行の丸も同じ色）。</summary>
    public static Brush LikedCover() => new LinearGradientBrush(
        [new GradientStop { Color = Windows.UI.Color.FromArgb(255, 0x45, 0x0A, 0xF5), Offset = 0 }, new GradientStop { Color = Windows.UI.Color.FromArgb(255, 0xC4, 0xEF, 0xD9), Offset = 1 }], 45);

    /// <summary>高く評価した曲の一覧の、上の帯の色（ジャケットの色の代わり）。</summary>
    public static readonly Windows.UI.Color LikedTint = Windows.UI.Color.FromArgb(255, 0x50, 0x38, 0xA0);

    /// <summary>ジャケットの色を、上の濃さ（top）から下の濃さ（bottom）へ薄くしていく縦のグラデーション。</summary>
    public static Brush Fade(Windows.UI.Color color, byte top, byte bottom)
    {
        var from = color;
        from.A = top;
        var to = color;
        to.A = bottom;
        return new LinearGradientBrush([new GradientStop { Color = from, Offset = 0 }, new GradientStop { Color = to, Offset = 1 }], 90);
    }

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
