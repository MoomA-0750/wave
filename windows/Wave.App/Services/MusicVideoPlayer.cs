using Wave.Core.Music;
using Wave.Core.Stream;
using Windows.Media.Playback;

namespace Wave.App.Services;

/// <summary>
/// 曲に重ねる映像（Android の MusicVideos と同じ考え）。音は PlayerService の再生器が鳴らし続け、映像はこの音の無い再生器で出す。
/// 位置は VideoSync で音に合わせる。映像の読み込みに失敗しても、音の再生には関わらない。
/// </summary>
public sealed class MusicVideoPlayer
{
    private readonly MediaPlayer player = new() { IsMuted = true, AutoPlay = false };
    private RangeStream? bytes;
    private string? loadedVideoId;
    private int generation;

    public MusicVideoPlayer()
    {
        // メディアキー・音量のポップアップ（SMTC）は音の再生器のものだけにする
        player.CommandManager.IsEnabled = false;
    }

    public MediaPlayer Player => player;

    /// <summary>映像に当たる部分があり、映っているか（MV に無い区間では隠す）。</summary>
    public bool Showing { get; private set; }

    public event Action? ShowingChanged;

    /// <summary>映像を読み込む（同じ映像なら何もしない）。失敗したら false。</summary>
    public async Task<bool> LoadAsync(MusicVideo video)
    {
        if (loadedVideoId == video.VideoId && player.Source is not null) return true;
        Stop();
        var mine = ++generation;
        var resolved = await AppServices.Resolver.ResolveAsync(video.VideoId, kind: StreamKind.Video);
        if (resolved.Stream is not { } stream || mine != generation)
        {
            AppPaths.Log($"video resolve failed reason={resolved.Failure}");
            return false;
        }
        RangeStream opened;
        try
        {
            opened = await RangeStream.OpenAsync(AppServices.Http, stream);
        }
        catch (Exception e) when (e is StreamRejectedException or HttpRequestException or IOException)
        {
            AppPaths.Log($"video open failed type={e.GetType().Name}");
            return false;
        }
        if (mine != generation) return false;
        var source = await PlayerService.CreateDashSourceAsync(video.VideoId, stream, opened, "video");
        if (source is null || mine != generation) return false;
        bytes = opened;
        loadedVideoId = video.VideoId;
        player.Source = source;
        AppPaths.Log($"video itag={stream.Itag}");
        return true;
    }

    /// <summary>音の位置に合わせる（画面の時計から呼ぶ）。</summary>
    public void Sync(MusicVideo video, long audioMs, bool playing)
    {
        if (player.Source is null) return;
        var session = player.PlaybackSession;
        var ready = session.PlaybackState is not (MediaPlaybackState.Opening or MediaPlaybackState.Buffering);
        var step = VideoSync.Decide(video.VideoPositionOf(audioMs), (long)session.Position.TotalMilliseconds, playing, ready);
        switch (step)
        {
            case VideoSync.Step.Hide:
                if (session.PlaybackState == MediaPlaybackState.Playing) player.Pause();
                SetShowing(false);
                return;
            case VideoSync.Step.Seek seek:
                session.Position = TimeSpan.FromMilliseconds(seek.ToMs);
                session.PlaybackRate = 1;
                Run(seek.Play);
                break;
            case VideoSync.Step.Run run:
                if (Math.Abs(session.PlaybackRate - run.Speed) > 0.005) session.PlaybackRate = run.Speed;
                Run(run.Play);
                break;
        }
        SetShowing(true);
    }

    private void Run(bool play)
    {
        var state = player.PlaybackSession.PlaybackState;
        if (play && state != MediaPlaybackState.Playing) player.Play();
        else if (!play && state == MediaPlaybackState.Playing) player.Pause();
    }

    private void SetShowing(bool value)
    {
        if (Showing == value) return;
        Showing = value;
        ShowingChanged?.Invoke();
    }

    /// <summary>映像を外す（曲が変わった・曲の表示に戻した）。</summary>
    public void Stop()
    {
        generation++;
        player.Pause();
        player.Source = null;
        bytes?.Dispose();
        bytes = null;
        loadedVideoId = null;
        SetShowing(false);
    }
}
