using System.Globalization;
using Wave.App.Services;

namespace Wave.App;

/// <summary>
/// 画面の言語（日本語・英語）。既定は Windows の表示言語に合わせ、日本語なら日本語、それ以外は英語。設定で固定できる（次の起動から）。
/// 文言は使う場所に日本語と英語を並べて書く（<c>L.T("日本語", "English")</c>）。XAML からも x:Bind で同じ関数を呼ぶ。
/// </summary>
public static class L
{
    public static bool Ja { get; private set; } = true;

    /// <summary>YouTube に送る表示の言語（届く文字を画面と同じ言語にする）。</summary>
    public static string Hl => Ja ? "ja" : "en";

    public static void Initialize(string? setting)
    {
        Ja = setting switch
        {
            "ja" => true,
            "en" => false,
            _ => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ja",
        };
    }

    public static string T(string ja, string en) => Ja ? ja : en;

    /// <summary>曲数（英語は単数・複数を分ける）。</summary>
    public static string Songs(int count) => Ja ? $"{count} 曲" : count == 1 ? "1 song" : $"{count} songs";

    /// <summary>合計の長さ（プレイリスト・アルバムの補足）。</summary>
    public static string Length(int seconds)
    {
        var t = TimeSpan.FromSeconds(seconds);
        if (t.TotalHours >= 1) return Ja ? $"{(int)t.TotalHours} 時間 {t.Minutes} 分" : $"{(int)t.TotalHours} hr {t.Minutes} min";
        return Ja ? $"{t.Minutes} 分 {t.Seconds} 秒" : $"{t.Minutes} min {t.Seconds} sec";
    }
}
