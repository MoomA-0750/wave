using System.Diagnostics;
using Microsoft.UI.Dispatching;
using Wave.Core.Lyrics;
using Wave.Core.Music;
using Wave.Core.Net;
using Wave.Core.Stream;

namespace Wave.App.Services;

/// <summary>アプリ全体で1つずつ持つもの。</summary>
public static class AppServices
{
    private static readonly Stopwatch Clock = Stopwatch.StartNew();

    public static Settings Settings { get; private set; } = null!;
    /// <summary>YouTube への通信（cookie を自動で持たない）。</summary>
    public static HttpClient Http { get; private set; } = null!;
    public static InnerTubeClient Client { get; private set; } = null!;
    public static MusicRepository Music { get; private set; } = null!;
    public static StreamResolver Resolver { get; private set; } = null!;
    public static PlaybackStats Stats { get; private set; } = null!;
    public static LyricsService Lyrics { get; private set; } = null!;
    public static CredentialStore Credentials { get; private set; } = null!;
    public static PlayerService Player { get; private set; } = null!;
    public static LibraryState Library { get; private set; } = null!;
    public static AccountState Account { get; private set; } = null!;
    /// <summary>登録したアーティストの新作。</summary>
    public static Releases Releases { get; private set; } = null!;
    /// <summary>Claude との会話（アプリが動いている間だけ持つ）。</summary>
    public static AiChat Ai { get; } = new();
    /// <summary>端末の連携（中継の場所が設定に無ければ使えない）。</summary>
    public static global::Wave.App.Services.Connect.ConnectService Connect { get; private set; } = null!;

    public static void Initialize(DispatcherQueue ui)
    {
        Settings = Settings.Load();
        L.Initialize(Settings.Language);
        RangeStream.Diagnostics = AppPaths.Log;
        Http = InnerTubeClient.CreateHttpClient();
        // YouTube から届く文字（棚の見出しなど）を画面と同じ言語にする
        Client = new InnerTubeClient(Http, L.Hl);
        Music = new MusicRepository(Client);
        Resolver = new StreamResolver(Client, new PlayerCipherProvider(Http, () => Clock.ElapsedMilliseconds), () => Clock.ElapsedMilliseconds);
        Stats = new PlaybackStats(Client);
        // 歌詞の検索（LRCLIB）には YouTube と別の HttpClient を使う
        var version = typeof(AppServices).Assembly.GetName().Version?.ToString(3) ?? "0.1.0";
        Lyrics = new LyricsService(Music, new LrclibClient(InnerTubeClient.CreateHttpClient(), $"wave-windows/{version}"));
        Credentials = new CredentialStore();
        Account = new AccountState();
        Library = new LibraryState();
        Player = new PlayerService(ui);
        Account.Restore();
        // 前回のキューを、再生を始めずに戻す（同じログインのときだけ）
        Player.RestoreSavedQueue();
        Connect = new global::Wave.App.Services.Connect.ConnectService(ui, Player, Settings.RelayV2Url, Settings.RelayV2Host);
        Releases = new Releases(ui);
        Releases.Start();
    }
}
