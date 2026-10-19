using Windows.Media.Playback;

namespace Wave.App.Services;

/// <summary>
/// ほかの端末で鳴っているライブ配信の映像（MusicVideoPlayer と同じく、音を出さない別の再生器）。
/// この端末で同じ videoId のライブの HLS を映像を見るための画質で読み、映像の枠に出す。
/// 位置は合わせない（ライブは今の時点から流れる）。鳴らしている端末が止まっていれば止める。
/// </summary>
public sealed class RemoteLiveVideo
{
    private readonly MediaPlayer player = new() { IsMuted = true, AutoPlay = false };
    private string? loadedVideoId;
    private int generation;
    private bool playing;
    private bool pausedSinceLoad;

    public RemoteLiveVideo()
    {
        // メディアキー・音量のポップアップ（SMTC）は音の再生器のものだけにする
        player.CommandManager.IsEnabled = false;
    }

    public MediaPlayer Player => player;

    /// <summary>映像を出そうとしている配信（読み込み中も含む）。出していなければ null。</summary>
    public string? Wanted { get; private set; }

    /// <summary>読み込めなかった（配信が終わった・通信できないなど。後から読み込みを始め直したものは含めない）。</summary>
    public event Action? Failed;

    /// <summary>配信の映像を読み込む（同じ配信なら、流す・止めるだけ合わせる）。失敗したら false。</summary>
    public async Task<bool> LoadAsync(string videoId, bool play)
    {
        playing = play;
        if (Wanted == videoId)
        {
            // 読み込み中なら、読み終えたときに今の playing で流す・止める
            SetPlaying(play);
            return true;
        }
        Stop();
        Wanted = videoId;
        var mine = ++generation;
        var result = await AppServices.Live.ResolveAsync(videoId, video: true);
        if (mine != generation) return false;
        if (result.Stream is not { } live)
        {
            AppPaths.Log($"remote live video resolve failed reason={result.Failure}");
            Wanted = null;
            Failed?.Invoke();
            return false;
        }
        var source = await PlayerService.CreateLiveSourceAsync(live);
        if (mine != generation) return false;
        if (source is null)
        {
            Wanted = null;
            Failed?.Invoke();
            return false;
        }
        loadedVideoId = videoId;
        pausedSinceLoad = false;
        player.Source = source;
        if (playing) player.Play();
        AppPaths.Log($"remote live video loaded playing={playing}");
        return true;
    }

    /// <summary>
    /// 鳴らしている端末に合わせて流す・止める。止めたあと流すときは、今の時点から読み直す
    /// （止めていた分だけ遅れた映像を出さない）。
    /// </summary>
    public void SetPlaying(bool play)
    {
        playing = play;
        if (player.Source is null || loadedVideoId is not { } videoId) return;
        if (!play)
        {
            player.Pause();
            pausedSinceLoad = true;
            return;
        }
        if (pausedSinceLoad)
        {
            Reload(videoId);
            return;
        }
        player.Play();
    }

    private async void Reload(string videoId)
    {
        Stop();
        await LoadAsync(videoId, playing);
    }

    /// <summary>映像を外す（映像を閉じた・曲が変わった・映すのをやめた）。</summary>
    public void Stop()
    {
        generation++;
        Wanted = null;
        loadedVideoId = null;
        pausedSinceLoad = false;
        player.Pause();
        player.Source = null;
    }
}
