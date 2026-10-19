using Wave.Core.Music;
using Wave.Core.Stream;
using Windows.Media.Playback;

namespace Wave.App.Services;

/// <summary>
/// 曲に重ねる映像（Android の MusicVideos と同じ考え）。音は PlayerService の再生器が鳴らし続け、映像はこの音の無い再生器で出す。
/// 位置は VideoSync で音に合わせる。映像の読み込みに失敗しても、音の再生には関わらない。
/// </summary>
public sealed class MusicVideoPlayer : MutedVideo
{
    private RangeStream? bytes;
    private string? loadedVideoId;

    /// <summary>映像に当たる部分があり、映っているか（MV に無い区間では隠す）。</summary>
    public bool Showing { get; private set; }

    public event Action? ShowingChanged;

    /// <summary>映像を読み込む（同じ映像なら何もしない）。失敗したら false。</summary>
    public async Task<bool> LoadAsync(MusicVideo video)
    {
        if (loadedVideoId == video.VideoId && Player.Source is not null) return true;
        Stop();
        var mine = Begin();
        var resolved = await AppServices.Resolver.ResolveAsync(video.VideoId, kind: StreamKind.Video);
        if (resolved.Stream is not { } stream || Stale(mine))
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
        // 使わずに戻るときは、開いた読み手を閉じる（残すと通信が開いたままになる）
        if (Stale(mine))
        {
            opened.Dispose();
            return false;
        }
        var source = await PlayerService.CreateDashSourceAsync(video.VideoId, stream, opened, "video");
        if (source is null || Stale(mine))
        {
            opened.Dispose();
            return false;
        }
        bytes = opened;
        loadedVideoId = video.VideoId;
        Player.Source = source;
        AppPaths.Log($"video itag={stream.Itag}");
        return true;
    }

    /// <summary>音の位置に合わせる（画面の時計から呼ぶ）。</summary>
    public void Sync(MusicVideo video, long audioMs, bool playing)
    {
        if (Player.Source is null) return;
        var session = Player.PlaybackSession;
        var ready = session.PlaybackState is not (MediaPlaybackState.Opening or MediaPlaybackState.Buffering);
        var step = VideoSync.Decide(video.VideoPositionOf(audioMs), (long)session.Position.TotalMilliseconds, playing, ready);
        switch (step)
        {
            case VideoSync.Step.Hide:
                if (session.PlaybackState == MediaPlaybackState.Playing) Player.Pause();
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
        var state = Player.PlaybackSession.PlaybackState;
        if (play && state != MediaPlaybackState.Playing) Player.Play();
        else if (!play && state == MediaPlaybackState.Playing) Player.Pause();
    }

    private void SetShowing(bool value)
    {
        if (Showing == value) return;
        Showing = value;
        ShowingChanged?.Invoke();
    }

    /// <summary>映像を外す（曲が変わった・曲の表示に戻した）。</summary>
    public override void Stop()
    {
        base.Stop();
        bytes?.Dispose();
        bytes = null;
        loadedVideoId = null;
        SetShowing(false);
    }
}
