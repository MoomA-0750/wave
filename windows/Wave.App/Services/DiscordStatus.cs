using Microsoft.UI.Dispatching;
using Wave.Core.Discord;

namespace Wave.App.Services;

/// <summary>
/// Discord に「wave を聴いています」を出す（設定でオンにし、Discord のアプリの ID を入れたときだけ）。
/// ほかの端末の再生を映しているとき（スマホで鳴らしているなど）も、その曲を出す。スマホの Discord には出す手段が無いため。
/// </summary>
public sealed class DiscordStatus : IDisposable
{
    private readonly DiscordPresence presence = new(log: AppPaths.Log);
    private readonly DispatcherQueueTimer timer;

    public DiscordStatus(DispatcherQueue ui)
    {
        timer = ui.CreateTimer();
        timer.Interval = TimeSpan.FromSeconds(1);
        timer.Tick += (_, _) => Push();
        timer.Start();
    }

    /// <summary>今の状態の文（設定の画面に出す）。</summary>
    public string StatusText => presence.Status switch
    {
        "connected" => L.T("Discord につないでいます", "Connected to Discord"),
        "notFound" => L.T("Discord が見つかりません（Discord のアプリを起動すると、自動でつなぎます）", "Discord not found (wave connects when the Discord app is running)"),
        "refused" => L.T("Discord につなげませんでした。アプリケーション ID を確かめてください", "Discord refused the connection. Check the application ID"),
        _ => L.T("曲を再生すると Discord につなぎます", "wave connects to Discord when you play a song"),
    };

    /// <summary>設定が変わった・曲が変わったなどで、すぐ反映する。</summary>
    public void Push()
    {
        var settings = AppServices.Settings;
        var clientId = settings.DiscordPresence ? settings.DiscordClientId : null;
        presence.Update(clientId, clientId is null ? null : Track(AppServices.Player));
    }

    private static PresenceTrack? Track(PlayerService player)
    {
        if (player.Current is not { } song) return null;
        var album = song.Album?.Title;
        var where = player.IsRemote && player.RemoteDevice is { } device ? L.T($"{device} で再生中", $"Playing on {device}") : null;
        var large = string.Join(" · ", new[] { album, where }.Where(s => !string.IsNullOrEmpty(s)));
        var duration = player.Duration > 0 ? player.Duration : song.DurationSeconds ?? 0;
        return new PresenceTrack(song.VideoId, song.Title, song.ArtistsText, large.Length > 0 ? large : null, song.ThumbnailUrl,
            player.Position, duration, player.IsPlaying, L.T("YouTube Music で開く", "Open in YouTube Music"));
    }

    public void Dispose()
    {
        timer.Stop();
        presence.Dispose();
    }
}
