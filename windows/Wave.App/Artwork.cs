using System.Runtime.InteropServices.WindowsRuntime;
using Wave.App.Services;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Wave.App;

/// <summary>ジャケットの色（Spotify のページの上のグラデーションに使う）。</summary>
public static class Artwork
{
    private static readonly Dictionary<string, Windows.UI.Color?> Cache = new();

    /// <summary>小さく縮めた画像の、鮮やかさで重みを付けた平均の色。取れなければ null。</summary>
    public static async Task<Windows.UI.Color?> DominantColorAsync(string? url)
    {
        if (string.IsNullOrEmpty(url) || !Uri.TryCreate(Ui.Resize(url, 48), UriKind.Absolute, out var uri) || uri.Scheme != "https") return null;
        if (Cache.TryGetValue(url, out var cached)) return cached;
        Windows.UI.Color? color = null;
        try
        {
            var bytes = await AppServices.Http.GetByteArrayAsync(uri);
            using var stream = new InMemoryRandomAccessStream();
            await stream.WriteAsync(bytes.AsBuffer());
            stream.Seek(0);
            var decoder = await BitmapDecoder.CreateAsync(stream);
            var transform = new BitmapTransform { ScaledWidth = 24, ScaledHeight = 24 };
            var data = await decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, transform, ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage);
            var px = data.DetachPixelData();
            double r = 0, g = 0, b = 0, w = 0;
            for (var i = 0; i + 3 < px.Length; i += 4)
            {
                double pb = px[i], pg = px[i + 1], pr = px[i + 2];
                var max = Math.Max(pr, Math.Max(pg, pb));
                var min = Math.Min(pr, Math.Min(pg, pb));
                // 鮮やかで、暗すぎない色を重く見る
                var weight = (max - min) / 255.0 * (max / 255.0) + 0.02;
                r += pr * weight;
                g += pg * weight;
                b += pb * weight;
                w += weight;
            }
            if (w > 0) color = Windows.UI.Color.FromArgb(255, (byte)(r / w), (byte)(g / w), (byte)(b / w));
        }
        catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException or ArgumentException or System.Runtime.InteropServices.COMException)
        {
        }
        Cache[url] = color;
        return color;
    }
}
