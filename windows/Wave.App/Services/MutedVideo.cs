using Windows.Media.Playback;

namespace Wave.App.Services;

/// <summary>
/// 音を出さない映像の再生器の共通の形（MusicVideoPlayer・RemoteLiveVideo）。音は PlayerService の再生器が鳴らし、こちらは映像の枠に出すだけ。
/// 読み込みは世代番号で見張り、読み込み中に外された・別のものを読み始めたら、古い読み込みの結果は使わない。
/// </summary>
public abstract class MutedVideo
{
    private readonly MediaPlayer player = new() { IsMuted = true, AutoPlay = false };
    private int generation;

    protected MutedVideo()
    {
        // メディアキー・音量のポップアップ（SMTC）は音の再生器のものだけにする
        player.CommandManager.IsEnabled = false;
    }

    public MediaPlayer Player => player;

    /// <summary>読み込みを始める（前の読み込みは古くなる）。返した番号を Stale に渡して確かめる。</summary>
    protected int Begin() => ++generation;

    /// <summary>その読み込みの後に、外された・別のものを読み始めたか。</summary>
    protected bool Stale(int mine) => mine != generation;

    /// <summary>映像を外す（読み込み中のものも古くする）。</summary>
    public virtual void Stop()
    {
        generation++;
        player.Pause();
        player.Source = null;
    }
}
