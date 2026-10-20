using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Dispatching;
using Wave.Core.Lyrics;
using Wave.Core.Music;
using Wave.Core.Stream;
using Windows.Media;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage.Streams;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;

namespace Wave.App.Services;

public enum RepeatMode { Off, All, One }

/// <summary>
/// 再生キューと再生（Windows の MediaPlayer）。メディアキー・音量のポップアップ（SMTC）にも曲名と画像を出す。
/// 署名付きの URL は MediaPlayer に渡すだけで、ログ・画面に出さない。
/// </summary>
public sealed partial class PlayerService : ObservableObject
{
    private const long FreshMarginMs = 5 * 60 * 1000;

    private readonly MediaPlayer player = new() { AudioCategory = MediaPlayerAudioCategory.Media, AutoPlay = false };
    private readonly DispatcherQueue ui;
    private readonly DispatcherQueueTimer timer;
    private readonly Dictionary<string, ResolvedStream> prefetched = new();
    /// <summary>
    /// シャッフルを入れたときの、今の曲より後ろの並び（項目の ID → 位置）。切ると残りをこの並びに戻す（Android の QueueSession と同じ）。
    /// null ならシャッフルは切れている。シャッフル中に足した曲は、戻すときに今の順のまま最後に付く。
    /// </summary>
    private Dictionary<string, int>? shuffleOrder;
    private CancellationTokenSource? loadCancel;
    private ResolvedStream? currentStream;
    private string cpn = "";
    private bool statsSent;
    private bool retried;
    private string? radioContinuation;
    private string? radioPlaylistId;
    private bool extending;
    private int consecutiveFailures;
    private const int MaxConsecutiveFailures = 3;
    /// <summary>解決し直しても拒否された曲。2分の間は解決し直さずに飛ばす（Android と同じ。SPEC「拒否が続く曲は少しの間解決し直さない」）。</summary>
    private readonly RejectedSongs rejectedSongs;
    /// <summary>直前の StartStreamAsync が、音のサーバーに拒否されて（403 など）開けなかったか。</summary>
    private bool lastOpenRejected;
    private string? radioSeed;
    /// <summary>一覧の残りをまだ読んでいる出どころ（長いプレイリスト・アーティストを最初の分から鳴らし始めたとき）。読み終えるまでラジオへ切り替えない。</summary>
    private string? pendingSourceId;
    /// <summary>キューの最後の曲を流し終えて止まっている（あとから曲が足されたら、足した先頭から続ける）。</summary>
    private bool stoppedAtEnd;

    // 端末の連携（Connect v2）: キューの項目ごとの ID と出どころ（Queue と同じ並び）、今の曲を聴いた時間
    private readonly List<QueueItemMeta> itemMeta = [];
    private QueueOrigin nextOrigin = QueueOrigin.Source;
    private Queue<string>? adoptIds;
    private readonly System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();
    /// <summary>今の曲を聴いた時間（再生履歴を送る時と、引き取る端末へ渡す値）。</summary>
    private readonly ListenCounter listen = new();
    private string? listenedItemId;

    /// <summary>キューの並び・中身が変わった（端末の連携が書き出す）。</summary>
    public event Action? QueueChanged;

    /// <summary>位置・再生中か・曲・リピートが変わった。true は再生・一時停止・曲の遷移（すぐ書く）、false はシークなど（間引く）。</summary>
    public event Action<bool>? CursorChanged;

    /// <summary>本人が再生を始めた・曲を選んだ（true は曲を選んだ、false は再生ボタン）。</summary>
    public event Action<bool>? UserStartedPlayback;

    /// <summary>本人が一時停止した。</summary>
    public event Action? UserPaused;

    public ObservableCollection<Song> Queue { get; } = [];

    [ObservableProperty] private Song? current;
    [ObservableProperty] private int index = -1;
    [ObservableProperty] private bool isPlaying;
    [ObservableProperty] private bool isLoading;
    [ObservableProperty] private double position;
    [ObservableProperty] private double duration;
    [ObservableProperty] private bool shuffle;
    [ObservableProperty] private RepeatMode repeat;
    [ObservableProperty] private double volume;
    [ObservableProperty] private LikeStatus? like;
    /// <summary>今の曲がどれかのプレイリストに入っている（先読みで分かったとき。Spotify と同じく評価のボタンに✓を付ける）。</summary>
    [ObservableProperty] private bool inPlaylist;
    /// <summary>今の曲に重ねられる映像（MV）。無い・まだ分からなければ null。</summary>
    [ObservableProperty] private MusicVideo? video;
    /// <summary>今の曲がライブ配信（長さ・シーク・歌詞・MV が無い。下のバーと右の欄は「ライブ」の印を出す）。</summary>
    [ObservableProperty] private bool isLive;

    /// <summary>手元の音の再生位置（映像を合わせるため。ほかの端末を映している間は使わない）。</summary>
    public long LocalPositionMs => player.Source is null ? 0 : (long)player.PlaybackSession.Position.TotalMilliseconds;
    [ObservableProperty] private string? message;
    [ObservableProperty] private LyricsService.Result? lyrics;
    /// <summary>今のキューの出どころ（プレイリスト・アルバムの ID。ラジオなら null）。画面で「再生中」の印に使う。</summary>
    [ObservableProperty] private string? sourceId;

    /// <summary>ほかの端末で鳴っている（下のバー・右の欄・キューは、その端末の再生を映し、操作はその端末への命令になる）。</summary>
    [ObservableProperty] private bool isRemote;

    /// <summary>鳴らしている端末の名前（IsRemote のとき）。</summary>
    [ObservableProperty] private string? remoteDevice;

    /// <summary>手元で選んだキューを、ほかの端末で鳴らす（端末の連携が置く）。端末 ID を渡し、頼めたら true。</summary>
    public Func<string, bool>? PlayOnRemote { get; set; }

    private string? RemoteDeviceId => IsRemote ? remote?.DeviceId : null;

    /// <summary>ほかの端末への命令を送る（端末の連携が置く）。送れたら true。</summary>
    public Func<Wave.Core.Connect.ControlAction, long?, bool>? SendRemoteControl { get; set; }

    /// <summary>ほかの端末で鳴っている間の「次に再生」（true）・「キューに追加」（false）を、その端末のキューへ送る（端末の連携が置く）。送れたら true。</summary>
    public Func<IReadOnlyList<Song>, bool, bool>? SendRemoteQueueAdd { get; set; }

    /// <summary>ほかの端末で鳴っている間のキューの並べ替え（動かす曲の項目の ID・どの曲の直後か、null なら今の曲の直後）を、その端末へ送る（端末の連携が置く）。送れたら true。</summary>
    public Func<string, string?, bool>? SendRemoteQueueMove { get; set; }

    /// <summary>ほかの端末で鳴っている間のキューから外す（項目の ID）を、その端末へ送る（端末の連携が置く）。送れたら true。</summary>
    public Func<IReadOnlyList<string>, bool>? SendRemoteQueueRemove { get; set; }

    private RemoteSnapshot? remote;
    private string? remoteQueueKey;

    public PlayerService(DispatcherQueue ui)
    {
        this.ui = ui;
        rejectedSongs = new RejectedSongs(() => clock.ElapsedMilliseconds);
        var settings = AppServices.Settings;
        volume = settings.Volume;
        localVolume = volume;
        // シャッフルはキューごとの状態（Android と同じく、新しい再生では切れる）。設定には持ち越さない
        repeat = (RepeatMode)Math.Clamp(settings.Repeat, 0, 2);
        player.Volume = volume;
        remoteLiveVideo.Failed += () =>
        {
            if (IsRemote) Message = L.T("ライブの映像を読み込めませんでした", "Couldn't load the live video");
        };
        player.MediaEnded += (_, _) => ui.TryEnqueue(OnEnded);
        player.MediaOpened += (s, _) =>
        {
            var d = s.PlaybackSession.NaturalDuration.TotalSeconds;
            AppPaths.Log($"media opened duration={d:F1}");
        };
        player.MediaFailed += (_, e) => ui.TryEnqueue(() => OnFailed(e.Error));
        player.PlaybackSession.PlaybackStateChanged += (s, _) =>
        {
            var state = s.PlaybackState;
            AppPaths.Log($"state {state} pos={s.Position.TotalSeconds:F1}");
            ui.TryEnqueue(() => IsPlaying = state is MediaPlaybackState.Playing);
        };
        Queue.CollectionChanged += OnQueueCollectionChanged;
        var commands = player.CommandManager;
        // メディアキー・音量のポップアップの再生・一時停止も、本人の操作として扱う（端末の連携に知らせる）
        commands.PlayReceived += (_, e) =>
        {
            e.Handled = true;
            ui.TryEnqueue(() => { if (!IsPlaying) TogglePlay(); });
        };
        commands.PauseReceived += (_, e) =>
        {
            e.Handled = true;
            ui.TryEnqueue(() => { if (IsPlaying) TogglePlay(); });
        };
        commands.NextBehavior.EnablingRule = MediaCommandEnablingRule.Always;
        commands.PreviousBehavior.EnablingRule = MediaCommandEnablingRule.Always;
        commands.NextReceived += (_, e) =>
        {
            e.Handled = true;
            ui.TryEnqueue(Next);
        };
        commands.PreviousReceived += (_, e) =>
        {
            e.Handled = true;
            ui.TryEnqueue(Previous);
        };
        timer = ui.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(250);
        timer.Tick += (_, _) => Tick();
        timer.Start();
        // キューを次の起動に持ち越す（変わってから少し待ってまとめて書く。鳴っている間は位置のために時々書く）
        saveTimer = ui.CreateTimer();
        saveTimer.Interval = TimeSpan.FromSeconds(2);
        saveTimer.IsRepeating = false;
        saveTimer.Tick += (_, _) => SaveQueue();
        QueueChanged += ScheduleSave;
        CursorChanged += _ => ScheduleSave();
    }

    // --- キューの持ち越し ---

    private readonly QueueStore store = new();
    private readonly DispatcherQueueTimer saveTimer;
    private long lastPositionSaveMs;
    private const long PositionSaveIntervalMs = 15_000;

    private void ScheduleSave()
    {
        saveTimer.Stop();
        saveTimer.Start();
    }

    /// <summary>今のキューを保存する（ほかの端末を映している間は、手元のキューが無いので書かない）。</summary>
    public void SaveQueue()
    {
        lastPositionSaveMs = clock.ElapsedMilliseconds;
        if (IsRemote) return;
        if (ExportQueue() is not { } queue || CurrentCursor() is not { } cursor)
        {
            if (Queue.Count == 0) store.Delete();
            return;
        }
        store.Save(new SavedQueue(SavedQueue.CurrentVersion, AppServices.Account.ObtainedAtEpochMs,
            queue.Items.Select(i => new SavedQueueItem(i.Song, i.Meta.ItemId, i.Meta.Origin)).ToList(),
            cursor.CurrentItemId, cursor.PositionMs, (int)Repeat, SourceId, queue.RadioSeed, queue.Continuation, queue.RadioPlaylistId, shuffleOrder));
    }

    /// <summary>起動時に、前回のキューを再生を始めずに戻す（保存したときと今のログインが違えば消す）。</summary>
    public void RestoreSavedQueue()
    {
        if (store.Load() is not { } saved) return;
        if (saved.ObtainedAtEpochMs != AppServices.Account.ObtainedAtEpochMs)
        {
            store.Delete();
            return;
        }
        var index = saved.Items.FindIndex(i => i.ItemId == saved.CurrentItemId);
        if (index < 0 || Queue.Count > 0) return;
        SourceId = saved.SourceId;
        radioSeed = saved.RadioSeed;
        radioContinuation = saved.Continuation;
        radioPlaylistId = saved.RadioPlaylistId;
        SetShuffleOrder(saved.ShuffleOrder);
        foreach (var item in saved.Items)
        {
            adoptIds = new([item.ItemId]);
            nextOrigin = item.Origin;
            Queue.Add(item.Song);
        }
        adoptIds = null;
        nextOrigin = QueueOrigin.Source;
        Repeat = (RepeatMode)saved.Repeat;
        Index = index;
        Current = Queue[index];
        Position = saved.PositionMs / 1000.0;
        Duration = Current.DurationSeconds ?? 0;
        var song = Current;
        var cancel = new CancellationTokenSource();
        loadCancel?.Cancel();
        loadCancel = cancel;
        _ = LoadSongInfoAsync(song, cancel.Token);
        _ = LoadLyricsAsync(song, cancel.Token);
        AppPaths.Log($"queue restored items={Queue.Count} index={index}");
    }

    /// <summary>
    /// 音量のつまみ。ほかの端末を映している間はその端末の音量を映し、動かすとその端末へ送る（Spotify と同じ）。
    /// 手元の音量（localVolume）は変えない。端末そのものの音量はどちらの端末でも変えない。
    /// </summary>
    partial void OnVolumeChanged(double value)
    {
        if (IsRemote)
        {
            if (!applyingRemoteVolume) SendVolumeToRemote();
            return;
        }
        localVolume = value;
        player.Volume = value;
        AppServices.Settings.Volume = value;
        AppServices.Settings.Save();
        // 鳴らしている端末の音量は、ほかの端末のつまみに映す
        CursorChanged?.Invoke(false);
    }

    /// <summary>この端末で鳴らすときの音量（設定に残すもの）。</summary>
    private double localVolume;
    private bool applyingRemoteVolume;

    /// <summary>本人が映している端末の音量を動かした直後は、相手の古い値でつまみを戻さない。</summary>
    private long remoteVolumeHoldUntilMs;
    private DispatcherQueueTimer? remoteVolumeTimer;

    /// <summary>ほかの端末の音量（0〜100）を変えてもらう（端末の連携が置く）。送れたら true。</summary>
    public Func<int, bool>? SendRemoteVolume { get; set; }

    /// <summary>映している端末の音量を、つまみの値にしてもらう（動かしている間は 150ms に1回まで）。</summary>
    private void SendVolumeToRemote()
    {
        remoteVolumeHoldUntilMs = clock.ElapsedMilliseconds + 2_000;
        if (remoteVolumeTimer is null)
        {
            remoteVolumeTimer = ui.CreateTimer();
            remoteVolumeTimer.Interval = TimeSpan.FromMilliseconds(150);
            remoteVolumeTimer.IsRepeating = false;
            remoteVolumeTimer.Tick += (_, _) =>
            {
                if (IsRemote && SendRemoteVolume?.Invoke((int)Math.Round(Volume * 100)) != true)
                    Message = L.T("ほかの端末に送れませんでした", "Couldn’t reach the other device");
            };
        }
        remoteVolumeTimer.Stop();
        remoteVolumeTimer.Start();
    }

    /// <summary>ほかの端末から音量を変えられた（この端末が鳴らしている）。アプリの中の音量だけを変える。</summary>
    public bool ApplyVolume(int percent)
    {
        if (IsRemote) return false;
        Volume = Math.Clamp(percent, 0, 100) / 100.0;
        return true;
    }

    partial void OnRepeatChanged(RepeatMode value)
    {
        AppServices.Settings.Repeat = (int)value;
        AppServices.Settings.Save();
        CursorChanged?.Invoke(true);
    }

    partial void OnIsPlayingChanged(bool value)
    {
        if (value) stoppedAtEnd = false;
        // ほかの端末を映している間は、この端末で聴いた時間ではない。ライブの映像は鳴らしている端末に合わせて止める・流す
        if (IsRemote)
        {
            remoteLiveVideo.SetPlaying(value);
            return;
        }
        // RSS のエピソードは、止めたらその位置を覚える（ADR-0012）
        if (!value) RememberEpisode();
        if (value) listen.Start(clock.ElapsedMilliseconds);
        else listen.Stop(clock.ElapsedMilliseconds);
        CursorChanged?.Invoke(true);
    }

    partial void OnCurrentChanged(Song? value)
    {
        IsLive = value?.IsLive == true;
        // ほかの端末のライブの映像は、その曲の間だけ
        if (remoteLiveVideo.Wanted is { } wanted && wanted != value?.VideoId) remoteLiveVideo.Stop();
        CursorChanged?.Invoke(true);
    }

    partial void OnIsLiveChanged(bool value)
    {
        // ライブ配信は位置を動かせない（メディアキー・音量のポップアップのシークも止める。タイムラインも出さない）
        player.CommandManager.PositionBehavior.EnablingRule = value ? MediaCommandEnablingRule.Never : MediaCommandEnablingRule.Auto;
        OnPropertyChanged(nameof(HasVideo));
    }

    /// <summary>
    /// 映像を出せるか（曲の MV、またはライブ配信。この端末で鳴らしているライブは鳴らしている HLS に映像も入っている。
    /// ほかの端末で鳴っているライブは、この端末で同じ配信を音を出さずに読んで映す）。
    /// </summary>
    public bool HasVideo => Video is not null || IsLive;

    /// <summary>ライブの映像を出す枠に渡す再生器（この端末で鳴らしているライブは鳴らしている再生器、ほかの端末のライブは音の無い映像用の再生器）。</summary>
    public MediaPlayer? LiveVideoPlayer => !IsLive ? null : IsRemote ? remoteLiveVideo.Player : player;

    /// <summary>ほかの端末で鳴っているライブの映像（この端末で同じ配信を、音を出さずに映像の画質で読む）。</summary>
    private readonly RemoteLiveVideo remoteLiveVideo = new();

    /// <summary>ほかの端末で鳴っているライブの映像を出す・やめる（位置は合わせない。ライブは今の時点から流れる）。</summary>
    private void SetRemoteLiveVideo(bool on)
    {
        if (!on || !IsLive || Current is not { } song)
        {
            remoteLiveVideo.Stop();
            return;
        }
        _ = remoteLiveVideo.LoadAsync(song.VideoId, IsPlaying);
    }

    /// <summary>
    /// ライブで映像を出しているか（映像を見るための画質で流す。出していない間は音のための小さい画質で通信量を抑える）。
    /// 映像にした曲（liveVideoFor）でだけ効く。曲が変われば音のための画質に戻る。
    /// </summary>
    private bool LiveVideo => liveVideoFor is not null && liveVideoFor == Current?.VideoId;
    private string? liveVideoFor;

    /// <summary>ライブの映像を出す・やめる。今ライブを鳴らしていれば、その画質で読み直す（ライブは今の時点から流れるので、続きの位置は無い）。</summary>
    public async void SetLiveVideo(bool on)
    {
        if (IsRemote)
        {
            SetRemoteLiveVideo(on);
            return;
        }
        if (LiveVideo == on) return;
        liveVideoFor = on ? Current?.VideoId : null;
        if (!IsLive || IsRemote || Current is not { } song || player.Source is null) return;
        // 鳴っていたかは再生器そのものから取る（一時停止の直後は IsPlaying がまだ変わっていないことがある）
        var wasPlaying = player.PlaybackSession.PlaybackState is MediaPlaybackState.Playing or MediaPlaybackState.Buffering or MediaPlaybackState.Opening;
        // 止まっている間に映像をやめるなら読み直さない（次に鳴らすときも今の流れのまま。画質だけの違い）
        if (!on && !wasPlaying) return;
        var cancel = new CancellationTokenSource();
        loadCancel?.Cancel();
        loadCancel = cancel;
        IsLoading = true;
        if (!await StartLiveAsync(song, cancel.Token))
        {
            if (!cancel.IsCancellationRequested) await RecoverAsync(song);
            return;
        }
        if (!wasPlaying) player.Pause();
    }

    /// <summary>ライブの印の無い曲がライブ配信だった。キューの項目に印を付け直す（項目の ID と出どころは保つ）。</summary>
    private Song MarkLive(Song song)
    {
        var live = song with { IsLive = true, DurationSeconds = null };
        if (Index >= 0 && Index < Queue.Count && Queue[Index].VideoId == song.VideoId)
        {
            adoptIds = new([itemMeta[Index].ItemId]);
            nextOrigin = itemMeta[Index].Origin;
            Queue[Index] = live;
            adoptIds = null;
            nextOrigin = QueueOrigin.Source;
        }
        if (Current?.VideoId == song.VideoId) Current = live;
        return live;
    }

    // 読み込み中も「再生中」として書くので、読み込みが失敗・中断して終わったときも書き直す
    // （書き直さないと、止まっている PC が再生中のままに見え、ほかの端末の再生ボタンが引き取りになっていた）
    partial void OnIsLoadingChanged(bool value)
    {
        if (!IsRemote) CursorChanged?.Invoke(true);
    }

    /// <summary>
    /// 曲の一覧を start から再生する（プレイリスト・アルバム・検索の結果）。shuffle が null なら今の設定のまま。
    /// 流し終える前に、キューの最後の曲から始まるラジオへつなぐ（ExtendRadioIfNeeded。Android の決定 E4 と同じ）。
    /// moreComing なら一覧の残りをまだ読んでいる: AppendFrom で足し終えるまでラジオへ切り替えない。
    /// </summary>
    public void PlayList(IReadOnlyList<Song> songs, int start = 0, string? source = null, bool? shuffleOverride = null, bool moreComing = false)
    {
        if (songs.Count == 0) return;
        var target = RemoteDeviceId;
        LeaveRemote();
        radioContinuation = null;
        radioPlaylistId = null;
        SourceId = source;
        // 一覧の「シャッフル再生」のときだけシャッフルを入れて始める。それ以外は切る（Android と同じ）
        var shuffled = shuffleOverride == true;
        var order = songs.ToList();
        var first = Math.Clamp(start, 0, songs.Count - 1);
        if (shuffled)
        {
            if (shuffleOverride == true)
            {
                // 「シャッフル再生」: どの曲から始まってもよい
                Random.Shared.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(order));
            }
            else
            {
                // 曲を選んで始めたときはその曲から、残りを混ぜる
                var head = order[first];
                order.RemoveAt(first);
                Random.Shared.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(order));
                order.Insert(0, head);
            }
            first = 0;
        }
        radioSeed = null;
        pendingSourceId = moreComing ? source : null;
        ReplaceQueue(order, QueueOrigin.Source);
        // 切ったときに戻す並び: 一覧の並び（同じ曲が2回あっても項目ごとに、一覧での位置を当てる）
        SetShuffleOrder(shuffled ? RankBy(songs) : null);
        StartSelection(first, target);
    }

    /// <summary>
    /// 曲を選んだ。ほかの端末で鳴っていたら、音を出さずにキューを用意して、その端末で鳴らしてもらう（Spotify と同じ）。
    /// 頼めなければ、この PC で鳴らす。
    /// </summary>
    /// <returns>ほかの端末に頼んだか</returns>
    private bool StartSelection(int index, string? remoteTarget)
    {
        if (remoteTarget is not null && index >= 0 && index < Queue.Count)
        {
            loadCancel?.Cancel();
            player.Pause();
            player.Source = null;
            Index = index;
            Current = Queue[index];
            Position = 0;
            Duration = Current.DurationSeconds ?? 0;
            IsPlaying = false;
            IsLoading = false;
            if (PlayOnRemote?.Invoke(remoteTarget) == true) return true;
        }
        SelectAt(index);
        UserStartedPlayback?.Invoke(true);
        return false;
    }

    /// <summary>1曲から始めて、続きはラジオ（似た曲）にする（Spotify の「曲から再生」と同じ）。</summary>
    public async void PlaySong(Song song)
    {
        var target = RemoteDeviceId;
        LeaveRemote();
        SourceId = null;
        SetShuffleOrder(null);
        ReplaceQueue([song], QueueOrigin.Source);
        if (song.IsLive || Feed.IsExternal(song.VideoId))
        {
            // ライブ配信は終わらずに流れ続けるので、後ろにラジオのおすすめを足さない（Android と同じ）。
            // RSS のエピソードは YouTube のラジオが作れない（ADR-0012。Android の QueueSession と同じく続きを足さない）
            radioSeed = null;
            radioPlaylistId = null;
            radioContinuation = null;
            StartSelection(0, target);
            return;
        }
        // 曲のラジオの続きは、鳴らす端末が自分で取る（キューには種の曲だけを渡す）
        radioSeed = song.VideoId;
        radioPlaylistId = "RDAMVM" + song.VideoId;
        radioContinuation = null;
        if (StartSelection(0, target)) return;
        var page = await AppServices.Music.UpNextAsync(song.VideoId, radioPlaylistId);
        if (!page.IsOk || Queue.Count == 0 || Queue[0].VideoId != song.VideoId) return;
        radioContinuation = page.Value!.Continuation;
        radioPlaylistId = page.Value.PlaylistId ?? radioPlaylistId;
        AppendAuto(page.Value.Songs.Where(s => s.VideoId != song.VideoId).ToList(), QueueOrigin.Suggestion);
    }

    /// <summary>
    /// あとから読めた残りの曲を、まだキューに無い分だけ足す（長いプレイリストを最初のページから鳴らし始めたとき）。
    /// 読めなかったときも空で呼ぶ（読み終えた印。そこからラジオへつなげるようになる）。
    /// </summary>
    public void AppendFrom(IReadOnlyList<Song> songs)
    {
        pendingSourceId = null;
        var known = Queue.Select(s => s.VideoId).ToHashSet();
        var extra = songs.Where(s => known.Add(s.VideoId)).ToList();
        if (shuffleOrder is { } order)
        {
            // シャッフル中に一覧の残りが読めた: 混ぜて足し、戻す並びには一覧の位置で加える
            Random.Shared.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(extra));
            var start = Queue.Count;
            AddAll(extra, QueueOrigin.Source);
            var position = songs.Select((song, i) => (song.VideoId, i)).GroupBy(x => x.VideoId).ToDictionary(g => g.Key, g => g.First().i);
            for (var i = start; i < Queue.Count; i++) order[itemMeta[i].ItemId] = position.GetValueOrDefault(Queue[i].VideoId, int.MaxValue);
            ResumeAfterEnd(start);
        }
        else AppendAuto(extra, QueueOrigin.Source);
        AppPaths.Log($"source appended added={extra.Count} queue={Queue.Count}");
        // 読み終えた: 終わりが近ければラジオへつなぐ
        if (Current is not null && !IsRemote) ExtendRadioIfNeeded();
    }

    public void PlayNext(Song song)
    {
        if (IsRemote)
        {
            QueueOnRemote([song], next: true);
            return;
        }
        if (Queue.Count == 0)
        {
            PlaySong(song);
            return;
        }
        nextOrigin = QueueOrigin.UserAdded;
        Queue.Insert(Index + 1, song);
        nextOrigin = QueueOrigin.Source;
        ResumeAfterEnd(Index + 1);
        Message = L.T($"「{song.Title}」を次に再生します", $"{song.Title} will play next");
    }

    /// <summary>
    /// 一覧（プレイリスト・アルバム）を丸ごと、今の曲の次（next）かキューの最後に入れる（Android の「次に再生」「キューに追加」と同じ）。
    /// キューが空なら、その一覧を再生する。
    /// </summary>
    public void QueueAll(IReadOnlyList<Song> songs, bool next)
    {
        if (songs.Count == 0) return;
        if (IsRemote)
        {
            QueueOnRemote(songs, next);
            return;
        }
        if (Queue.Count == 0)
        {
            PlayList(songs);
            return;
        }
        if (next)
        {
            nextOrigin = QueueOrigin.UserAdded;
            for (var i = 0; i < songs.Count; i++) Queue.Insert(Index + 1 + i, songs[i]);
            nextOrigin = QueueOrigin.Source;
            ResumeAfterEnd(Index + 1);
        }
        else InsertUserItems(songs);
        Message = next ? L.T($"{songs.Count} 曲を次に再生します", $"{L.Songs(songs.Count)} will play next")
            : L.T($"{songs.Count} 曲をキューに追加しました", $"Added {L.Songs(songs.Count)} to queue");
    }

    public void AddToQueue(Song song)
    {
        if (IsRemote)
        {
            QueueOnRemote([song], next: false);
            return;
        }
        if (Queue.Count == 0)
        {
            PlaySong(song);
            return;
        }
        InsertUserItems([song]);
        Message = L.T($"「{song.Title}」をキューに追加しました", $"Added {song.Title} to queue");
    }

    /// <summary>
    /// ほかの端末で鳴っている間の追加は、鳴らしている端末のキューへ送る（手元のキューに入れても、その端末は使わない）。
    /// 映しているキューは、その端末が書き直したものが届いたときに変わる。
    /// </summary>
    private void QueueOnRemote(IReadOnlyList<Song> songs, bool next)
    {
        if (SendRemoteQueueAdd?.Invoke(songs, next) != true)
        {
            Message = L.T("ほかの端末に送れませんでした", "Couldn’t reach the other device");
            return;
        }
        var device = RemoteDevice ?? L.T("ほかの端末", "the other device");
        Message = songs.Count == 1
            ? next ? L.T($"「{songs[0].Title}」を {device} で次に再生します", $"{songs[0].Title} will play next on {device}")
                : L.T($"「{songs[0].Title}」を {device} のキューに追加しました", $"Added {songs[0].Title} to the queue on {device}")
            : next ? L.T($"{songs.Count} 曲を {device} で次に再生します", $"{L.Songs(songs.Count)} will play next on {device}")
                : L.T($"{songs.Count} 曲を {device} のキューに追加しました", $"Added {L.Songs(songs.Count)} to the queue on {device}");
    }

    /// <summary>
    /// ほかの端末で足した曲を、送り手が振った項目の ID のまま入れる（この端末が鳴らしている。Android の QueueSession.addFromRemote と同じ）。
    /// afterItemId の曲があればその直後（分けて届いた続き）、無ければ next なら今の曲の直後、そうでなければ自分で足した曲のかたまりの最後へ。
    /// </summary>
    public bool ApplyQueueAdd(IReadOnlyList<(Song Song, QueueItemMeta Meta)> entries, bool next, string? afterItemId)
    {
        if (IsRemote || Queue.Count == 0 || entries.Count == 0) return false;
        var existing = itemMeta.Select(m => m.ItemId).ToHashSet();
        if (entries.Any(e => existing.Contains(e.Meta.ItemId))) return false;
        var after = afterItemId is null ? -1 : itemMeta.FindIndex(m => m.ItemId == afterItemId);
        var at = after >= 0 ? after + 1 : next ? Index + 1 : UserQueueEnd;
        nextOrigin = QueueOrigin.UserAdded;
        for (var i = 0; i < entries.Count; i++)
        {
            adoptIds = new([entries[i].Meta.ItemId]);
            Queue.Insert(at + i, entries[i].Song);
        }
        adoptIds = null;
        nextOrigin = QueueOrigin.Source;
        ResumeAfterEnd(at);
        return true;
    }

    /// <summary>
    /// 「キューに追加」（Spotify・Android と同じ）。今の曲の直後に続く、自分で足した曲のかたまりの最後に入れる。
    /// 足した順に下へ並び、その下に元から流れる予定の曲が続く。
    /// </summary>
    private void InsertUserItems(IReadOnlyList<Song> songs)
    {
        var at = UserQueueEnd;
        nextOrigin = QueueOrigin.UserAdded;
        for (var i = 0; i < songs.Count; i++) Queue.Insert(at + i, songs[i]);
        nextOrigin = QueueOrigin.Source;
        // 最後まで行って止まっているなら、足した曲から続ける（Android の addUserItems と同じ）
        ResumeAfterEnd(at);
    }

    /// <summary>自分で足した曲（「次に再生」「キューに追加」）か。</summary>
    public bool IsUserAdded(int position) => position >= 0 && position < itemMeta.Count && itemMeta[position].Origin == QueueOrigin.UserAdded;

    /// <summary>今の曲の直後に続く、自分で足した曲のかたまりの次の位置。</summary>
    public int UserQueueEnd
    {
        get
        {
            var i = Index + 1;
            while (i < Queue.Count && IsUserAdded(i)) i++;
            return i;
        }
    }

    /// <summary>今の曲より後ろの、自分で足した曲をまとめて外す（Spotify の「キューを消去」）。</summary>
    public void ClearUserQueue()
    {
        if (IsRemote)
        {
            var ids = Enumerable.Range(Index + 1, Math.Max(0, Queue.Count - Index - 1)).Where(IsUserAdded).Select(i => itemMeta[i].ItemId).ToList();
            if (ids.Count == 0 || !RemoveOnRemote(ids)) return;
            var device = RemoteDevice ?? L.T("ほかの端末", "the other device");
            Message = L.T($"{device} のキューから {ids.Count} 曲を外しました", $"Removed {L.Songs(ids.Count)} from the queue on {device}");
            return;
        }
        var removed = 0;
        for (var i = Queue.Count - 1; i > Index; i--)
        {
            if (!IsUserAdded(i)) continue;
            Queue.RemoveAt(i);
            removed++;
        }
        if (removed > 0) Message = L.T($"キューから {removed} 曲を外しました", $"Removed {L.Songs(removed)} from the queue");
    }

    /// <summary>キューの次の曲を並べ替える（今の曲より後ろだけ）。ほかの端末で鳴っている間は、その端末へ送る。</summary>
    public void MoveUpcoming(int from, int to)
    {
        if (from <= Index || to <= Index || from >= Queue.Count || to >= Queue.Count || from == to) return;
        if (IsRemote)
        {
            // 動かした後に直前に来る曲の後ろへ、と送る（今の曲の直後なら null）
            var before = to > from ? to : to - 1;
            var after = before == Index ? null : itemMeta[before].ItemId;
            if (SendRemoteQueueMove?.Invoke(itemMeta[from].ItemId, after) != true)
            {
                Message = L.T("ほかの端末に送れませんでした", "Couldn’t reach the other device");
                return;
            }
            HoldRemoteQueue();
        }
        Queue.Move(from, to);
    }

    public void RemoveAt(int position)
    {
        if (position <= Index || position >= Queue.Count) return;
        if (IsRemote && !RemoveOnRemote([itemMeta[position].ItemId])) return;
        Queue.RemoveAt(position);
    }

    /// <summary>
    /// ほかの端末で鳴っている間の「外す」を、その端末へ送る。送れたら映しからも先に外す
    /// （映しは、その端末が書き直したキューが届いたときに置き直る）。
    /// </summary>
    private bool RemoveOnRemote(IReadOnlyList<string> itemIds)
    {
        if (SendRemoteQueueRemove?.Invoke(itemIds) != true)
        {
            Message = L.T("ほかの端末に送れませんでした", "Couldn’t reach the other device");
            return false;
        }
        HoldRemoteQueue();
        for (var i = Queue.Count - 1; i > Index; i--)
            if (itemIds.Contains(itemMeta[i].ItemId)) Queue.RemoveAt(i);
        return true;
    }

    /// <summary>
    /// 映しのキューを先に変えたとき、その端末が書き直したキューが届くまで（届かなければ期限まで）映しを置き直さない。
    /// 期限を過ぎても同じキューのままなら（その端末が従わなかった）、届いている形に戻す。
    /// </summary>
    private void HoldRemoteQueue() => remoteQueueHeldUntilMs = clock.ElapsedMilliseconds + 5_000;

    private long remoteQueueHeldUntilMs;

    /// <summary>
    /// ほかの端末のキューの画面での並べ替えに従う（この端末が鳴らしている。Android の QueueSession と同じ）。
    /// itemId の曲（今の曲より後ろ）を、afterItemId の曲の直後（null なら今の曲の直後）へ動かす。手元で並べ替えたときと同じく MoveUpcoming で動かす。
    /// </summary>
    public bool ApplyQueueMove(string itemId, string? afterItemId)
    {
        if (IsRemote) return false;
        var from = itemMeta.FindIndex(m => m.ItemId == itemId);
        var after = afterItemId is null ? Index : itemMeta.FindIndex(m => m.ItemId == afterItemId);
        if (from <= Index || after < Index || from == after) return false;
        var to = from > after ? after + 1 : after;
        if (to == from) return true;
        MoveUpcoming(from, to);
        return true;
    }

    /// <summary>ほかの端末のキューの画面での「外す」に従う（この端末が鳴らしている）。今の曲より後ろの該当する曲だけを外す。</summary>
    public bool ApplyQueueRemove(IReadOnlyList<string> itemIds)
    {
        if (IsRemote) return false;
        var removed = false;
        for (var i = Queue.Count - 1; i > Index; i--)
        {
            if (!itemIds.Contains(itemMeta[i].ItemId)) continue;
            Queue.RemoveAt(i);
            removed = true;
        }
        return removed;
    }

    /// <summary>キューの曲を選んで鳴らす（本人の選択）。</summary>
    public void PlayAt(int position)
    {
        if (position < 0 || position >= Queue.Count) return;
        var target = RemoteDeviceId;
        LeaveRemote();
        StartSelection(position, target);
    }

    private void SelectAt(int position, double seekTo = 0)
    {
        if (position < 0 || position >= Queue.Count) return;
        stoppedAtEnd = false;
        Index = position;
        Current = Queue[position];
        _ = LoadCurrentAsync(seekTo);
    }

    public void TogglePlay()
    {
        if (IsRemote)
        {
            Remote(IsPlaying ? Wave.Core.Connect.ControlAction.Pause : Wave.Core.Connect.ControlAction.Play);
            return;
        }
        if (Current is null) return;
        if (player.Source is null)
        {
            // 前回から持ち越したキューは、覚えている位置から鳴らす
            _ = LoadCurrentAsync(Position);
            UserStartedPlayback?.Invoke(false);
            return;
        }
        if (IsPlaying)
        {
            player.Pause();
            UserPaused?.Invoke();
        }
        else
        {
            stoppedAtEnd = false;
            player.Play();
            UserStartedPlayback?.Invoke(false);
        }
    }

    /// <summary>本人の「次へ」（曲を選んだのと同じ扱い）。</summary>
    public void Next()
    {
        if (IsRemote)
        {
            Remote(Wave.Core.Connect.ControlAction.Next);
            return;
        }
        if (Advance()) UserStartedPlayback?.Invoke(true);
    }

    /// <summary>次の曲へ進む（曲の終わり・失敗の飛ばしにも使う）。進んだら true。</summary>
    private bool Advance()
    {
        if (Queue.Count == 0) return false;
        if (Index + 1 < Queue.Count) SelectAt(Index + 1);
        else if (Repeat == RepeatMode.All) SelectAt(0);
        else return false;
        return true;
    }

    public void Previous()
    {
        if (IsRemote)
        {
            Remote(Wave.Core.Connect.ControlAction.Previous);
            return;
        }
        // ライブ配信には頭が無いので、前の曲へ（前が無ければ何もしない）
        if (IsLive)
        {
            if (Index <= 0) return;
            SelectAt(Index - 1);
            UserStartedPlayback?.Invoke(true);
            return;
        }
        // 3秒より先なら曲の頭へ（Spotify と同じ）
        if (Position > 3 || Index <= 0)
        {
            Seek(0);
            return;
        }
        SelectAt(Index - 1);
        UserStartedPlayback?.Invoke(true);
    }

    public void Seek(double seconds)
    {
        if (IsRemote)
        {
            // ドラッグ中は何度も呼ばれるので、動きが止まってから1回だけ送る
            var target = (long)(Math.Max(0, seconds) * 1000);
            if (remote is { } r) remote = r with { PositionMs = target, CapturedAtMs = clock.ElapsedMilliseconds };
            Position = Math.Max(0, seconds);
            pendingRemoteSeek = target;
            HoldOptimistic();
            if (remoteSeekTimer is null)
            {
                remoteSeekTimer = ui.CreateTimer();
                remoteSeekTimer.Interval = TimeSpan.FromMilliseconds(350);
                remoteSeekTimer.IsRepeating = false;
                remoteSeekTimer.Tick += (_, _) =>
                {
                    if (IsRemote && pendingRemoteSeek is { } p) Remote(Wave.Core.Connect.ControlAction.Seek, p);
                    pendingRemoteSeek = null;
                };
            }
            remoteSeekTimer.Stop();
            remoteSeekTimer.Start();
            return;
        }
        if (player.Source is null || IsLive) return;
        player.PlaybackSession.Position = TimeSpan.FromSeconds(Math.Max(0, seconds));
        Position = seconds;
        CursorChanged?.Invoke(false);
    }

    /// <summary>
    /// シャッフルの入り切り（Android と同じ）。入れると今の曲より後ろの並びを覚えて混ぜ、切ると残りを覚えた並びに戻す
    /// （シャッフル中に足した曲は今の順のまま最後に付く。曲を外しはしない）。
    /// </summary>
    public void ToggleShuffle()
    {
        // ほかの端末のキューは並べ替えられない（命令が無い）
        if (IsRemote || Queue.Count == 0 || Current is null || Index < 0) return;
        var upcoming = Enumerable.Range(Index + 1, Queue.Count - Index - 1).Select(i => (Song: Queue[i], Meta: itemMeta[i])).ToList();
        // 自分で足した曲（キュー）はシャッフルせず、そのままの順で先頭に置く（Spotify・Android と同じ）
        List<(Song Song, QueueItemMeta Meta)> rest;
        List<(Song Song, QueueItemMeta Meta)> user;
        if (shuffleOrder is not { } order)
        {
            user = upcoming.Where(u => u.Meta.Origin == QueueOrigin.UserAdded).ToList();
            rest = upcoming.Where(u => u.Meta.Origin != QueueOrigin.UserAdded).ToList();
            SetShuffleOrder(rest.Select((u, i) => (u.Meta.ItemId, i)).ToDictionary(x => x.ItemId, x => x.i));
            Random.Shared.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(rest));
        }
        else
        {
            SetShuffleOrder(null);
            user = upcoming.Where(u => u.Meta.Origin == QueueOrigin.UserAdded && !order.ContainsKey(u.Meta.ItemId)).ToList();
            rest = upcoming.Except(user).Select((u, i) => (u, i)).OrderBy(x => order.TryGetValue(x.u.Meta.ItemId, out var r) ? r : int.MaxValue).ThenBy(x => x.i).Select(x => x.u).ToList();
        }
        ReplaceUpcoming([.. user, .. rest]);
    }

    /// <summary>今の曲より後ろを並べ直す（項目の ID と出どころは保つ）。</summary>
    private void ReplaceUpcoming(List<(Song Song, QueueItemMeta Meta)> rest)
    {
        while (Queue.Count > Index + 1) Queue.RemoveAt(Queue.Count - 1);
        foreach (var (song, meta) in rest)
        {
            adoptIds = new([meta.ItemId]);
            nextOrigin = meta.Origin;
            Queue.Add(song);
        }
        adoptIds = null;
        nextOrigin = QueueOrigin.Source;
    }

    private void SetShuffleOrder(Dictionary<string, int>? order)
    {
        shuffleOrder = order;
        Shuffle = order is not null;
    }

    /// <summary>キューの項目ごとに、元の一覧での位置を当てる（同じ曲が何度も出るときは前から順に）。</summary>
    private Dictionary<string, int> RankBy(IReadOnlyList<Song> songs)
    {
        var slots = songs.Select((song, i) => (song.VideoId, i)).GroupBy(x => x.VideoId).ToDictionary(g => g.Key, g => new Queue<int>(g.Select(x => x.i)));
        var rank = new Dictionary<string, int>();
        for (var i = 0; i < Queue.Count; i++)
            rank[itemMeta[i].ItemId] = slots.TryGetValue(Queue[i].VideoId, out var q) && q.Count > 0 ? q.Dequeue() : int.MaxValue;
        return rank;
    }

    public void CycleRepeat()
    {
        if (IsRemote) return;
        Repeat = NextRepeat(Repeat);
        // 全体リピートを切った: 一覧の終わりが近ければラジオへつなぐ（Android の onRepeatModeChanged と同じ）
        if (Repeat != RepeatMode.All && Current is not null) ExtendRadioIfNeeded();
    }

    private static RepeatMode NextRepeat(RepeatMode value) => value switch
    {
        RepeatMode.Off => RepeatMode.All,
        RepeatMode.All => RepeatMode.One,
        _ => RepeatMode.Off,
    };

    /// <summary>今の曲の高評価を切り替える（ライブラリの「高く評価した曲」に入る）。</summary>
    public void ToggleLike()
    {
        if (Current is { } song) SetLiked(song, Like != LikeStatus.Like);
    }

    /// <summary>曲が高く評価されているか（再生中の曲は再生画面の印と同じ値、ほかの曲は高く評価した曲の一覧から）。</summary>
    public bool IsLiked(Song song) => Current?.VideoId == song.VideoId ? Like == LikeStatus.Like : AppServices.Library.IsLiked(song.VideoId);

    /// <summary>曲を高く評価する・評価を外す。曲のメニューから、再生中でない曲にも使う（再生中の曲なら再生画面の印も変える）。</summary>
    public async void SetLiked(Song song, bool liked)
    {
        // RSS のエピソードは YouTube の曲ではないので評価しない（ADR-0012）
        if (Feed.IsExternal(song.VideoId)) return;
        if (!AppServices.Client.IsLoggedIn)
        {
            Message = L.T("高く評価するにはログインしてください", "Log in to like songs");
            return;
        }
        if (Current?.VideoId == song.VideoId) Like = liked ? LikeStatus.Like : LikeStatus.Indifferent;
        var result = await AppServices.Music.SetLikedAsync(song.VideoId, liked);
        if (!result.IsOk)
        {
            if (Current?.VideoId == song.VideoId) Like = liked ? LikeStatus.Indifferent : LikeStatus.Like;
            Message = L.T("高評価を変更できませんでした", "Couldn't update your likes");
        }
        else
        {
            Message = liked ? L.T("高く評価した曲に追加しました", "Added to Liked Songs") : L.T("高く評価した曲から削除しました", "Removed from Liked Songs");
            AppServices.Library.LikedChanged(song.VideoId, liked);
        }
    }

    private void ReplaceQueue(List<Song> songs, QueueOrigin origin)
    {
        Queue.Clear();
        AddAll(songs, origin);
    }

    private void AddAll(IEnumerable<Song> songs, QueueOrigin origin)
    {
        nextOrigin = origin;
        foreach (var s in songs) Queue.Add(s);
        nextOrigin = QueueOrigin.Source;
    }

    /// <summary>自動で続き（一覧の残り・ラジオ）を最後に足す。最後の曲を流し終えて止まっていたら、足した先頭から続ける（Android の appendItems と同じ）。</summary>
    private void AppendAuto(IReadOnlyList<Song> songs, QueueOrigin origin)
    {
        var start = Queue.Count;
        AddAll(songs, origin);
        ResumeAfterEnd(start);
    }

    private void ResumeAfterEnd(int firstNew)
    {
        // 途中の曲で止めたもの（一時停止・失敗）は勝手に再開しない
        if (!stoppedAtEnd || IsRemote || IsPlaying || IsLoading || Index != firstNew - 1 || firstNew >= Queue.Count) return;
        AppPaths.Log($"resume after end index={firstNew}");
        SelectAt(firstNew);
    }

    private void OnQueueCollectionChanged(object? sender, System.Collections.Specialized.NotifyCollectionChangedEventArgs e)
    {
        switch (e.Action)
        {
            case System.Collections.Specialized.NotifyCollectionChangedAction.Add:
                for (var i = 0; i < e.NewItems!.Count; i++)
                {
                    var id = adoptIds is { Count: > 0 } ids ? ids.Dequeue() : Guid.NewGuid().ToString();
                    itemMeta.Insert(e.NewStartingIndex + i, new QueueItemMeta(id, nextOrigin));
                }
                break;
            case System.Collections.Specialized.NotifyCollectionChangedAction.Remove:
                itemMeta.RemoveRange(e.OldStartingIndex, e.OldItems!.Count);
                break;
            case System.Collections.Specialized.NotifyCollectionChangedAction.Move:
                var moved = itemMeta[e.OldStartingIndex];
                itemMeta.RemoveAt(e.OldStartingIndex);
                itemMeta.Insert(e.NewStartingIndex, moved);
                break;
            case System.Collections.Specialized.NotifyCollectionChangedAction.Replace:
                itemMeta[e.NewStartingIndex] = new QueueItemMeta(adoptIds is { Count: > 0 } kept ? kept.Dequeue() : Guid.NewGuid().ToString(), nextOrigin);
                break;
            case System.Collections.Specialized.NotifyCollectionChangedAction.Reset:
                itemMeta.Clear();
                break;
        }
        QueueChanged?.Invoke();
    }

    // --- 端末の連携（Connect v2）の入口。本人の操作として知らせない ---

    /// <summary>今のキューの書き出しに要る値。キューが空なら null。</summary>
    public QueueExport? ExportQueue()
    {
        if (IsRemote || Queue.Count == 0 || Index < 0 || Index >= Queue.Count) return null;
        return new QueueExport(
            Queue.Select((song, i) => (song, itemMeta[i])).ToList(),
            itemMeta[Index].ItemId,
            radioSeed is not null ? null : SourceId,
            radioSeed,
            radioContinuation,
            radioPlaylistId);
    }

    /// <summary>今の再生の位置など。キューが空なら null。</summary>
    public PlaybackCursor? CurrentCursor()
    {
        if (IsRemote || Current is null || Index < 0 || Index >= itemMeta.Count) return null;
        var position = player.Source is null ? 0 : (long)(player.PlaybackSession.Position.TotalMilliseconds);
        return new PlaybackCursor(itemMeta[Index].ItemId, Math.Max(0, position), IsPlaying || IsLoading, Repeat, ListenedNow(), statsSent, (int)Math.Round(localVolume * 100));
    }

    /// <summary>位置の見積もりに使う時計（ミリ秒、単調）。</summary>
    public long ClockMs => clock.ElapsedMilliseconds;

    private long ListenedNow() => listen.ListenedMs(clock.ElapsedMilliseconds);

    /// <summary>端末の連携が止める（本人の一時停止として知らせない）。</summary>
    public void ConnectPause()
    {
        loadCancel?.Cancel();
        IsLoading = false;
        player.Pause();
    }

    /// <summary>端末の連携が戻す。</summary>
    public void ConnectResume()
    {
        if (Current is null) return;
        if (player.Source is null) _ = LoadCurrentAsync(Position);
        else player.Play();
    }

    /// <summary>他の端末から引き取ったキューで置き換え、今の曲の positionMs から鳴らす。</summary>
    public bool ConnectAdopt(QueueExport queue, long positionMs, RepeatMode repeat, long listened, bool historySent)
    {
        var index = queue.Items.FindIndex(i => i.Meta.ItemId == queue.CurrentItemId);
        if (index < 0) return false;
        LeaveRemote();
        SourceId = queue.SourceId;
        radioSeed = queue.RadioSeed;
        radioContinuation = queue.Continuation;
        radioPlaylistId = queue.RadioPlaylistId;
        pendingSourceId = null;
        stoppedAtEnd = false;
        // ほかの端末から受け取ったキューは、シャッフルを切った状態で始める（Android と同じ）
        SetShuffleOrder(null);
        Queue.Clear();
        foreach (var (song, meta) in queue.Items)
        {
            adoptIds = new([meta.ItemId]);
            nextOrigin = meta.Origin;
            Queue.Add(song);
        }
        adoptIds = null;
        nextOrigin = QueueOrigin.Source;
        Repeat = repeat;
        seedListened = (queue.CurrentItemId, listened, historySent);
        SelectAt(index, positionMs / 1000.0);
        // 曲のラジオを種の曲だけで受け取った（送り手は続きを取る前に渡した）: 続きをこの PC で取る
        if (queue.RadioSeed is { } seed && radioContinuation is null && Queue.Count - index <= 3) _ = ExtendFromSeedAsync(seed);
        return true;
    }

    /// <summary>
    /// ほかの端末で最後に鳴ったキューと位置に、手元を合わせておく（鳴らさない）。再生ボタンを押せばそのまま続きから鳴る。
    /// ほかの端末を映している間は、映すのをやめたときに戻すキュー（退避した分）を置き換える。手元で鳴っている・読み込み中なら何もしない。
    /// </summary>
    public bool ConnectStage(QueueExport queue, long positionMs, RepeatMode repeat)
    {
        var index = queue.Items.FindIndex(i => i.Meta.ItemId == queue.CurrentItemId);
        if (index < 0) return false;
        if (IsRemote)
        {
            localBackup = new LocalQueueBackup(queue.Items, index, queue.SourceId, queue.RadioSeed, queue.Continuation, queue.RadioPlaylistId, null, positionMs / 1000.0, repeat);
            return true;
        }
        if (IsPlaying || IsLoading) return false;
        loadCancel?.Cancel();
        RememberEpisode();
        episodeUrl = null;
        player.Source = null;
        SourceId = queue.SourceId;
        radioSeed = queue.RadioSeed;
        radioContinuation = queue.Continuation;
        radioPlaylistId = queue.RadioPlaylistId;
        pendingSourceId = null;
        stoppedAtEnd = false;
        SetShuffleOrder(null);
        Queue.Clear();
        foreach (var (song, meta) in queue.Items)
        {
            adoptIds = new([meta.ItemId]);
            nextOrigin = meta.Origin;
            Queue.Add(song);
        }
        adoptIds = null;
        nextOrigin = QueueOrigin.Source;
        Repeat = repeat;
        Index = index;
        Current = Queue[index];
        Position = positionMs / 1000.0;
        Duration = Current.DurationSeconds ?? 0;
        var staged = Current;
        var cancel = new CancellationTokenSource();
        loadCancel = cancel;
        _ = LoadSongInfoAsync(staged, cancel.Token);
        _ = LoadLyricsAsync(staged, cancel.Token);
        SaveQueue();
        return true;
    }

    /// <summary>手元の同じ曲のまま、止まっている位置だけ合わせる（ほかの端末を映している間・鳴っている間は何もしない）。</summary>
    public void ConnectStagePosition(long positionMs)
    {
        if (IsRemote || IsPlaying || IsLoading || Current is null || IsLive) return;
        if (player.Source is null) Position = positionMs / 1000.0;
        else player.PlaybackSession.Position = TimeSpan.FromMilliseconds(positionMs);
    }

    /// <summary>曲のラジオの続きを、種の曲から取って足す（まだキューに無い曲だけ）。</summary>
    private async Task ExtendFromSeedAsync(string seed)
    {
        if (extending || Feed.IsExternal(seed)) return;
        extending = true;
        try
        {
            var page = await AppServices.Music.UpNextAsync(seed, radioPlaylistId ?? "RDAMVM" + seed);
            if (!page.IsOk || radioSeed != seed) return;
            radioContinuation = page.Value!.Continuation;
            radioPlaylistId = page.Value.PlaylistId ?? radioPlaylistId;
            var known = Queue.Select(s => s.VideoId).ToHashSet();
            var fresh = page.Value.Songs.Where(s => known.Add(s.VideoId)).ToList();
            AppPaths.Log($"radio from seed added={fresh.Count} more={radioContinuation is not null}");
            AppendAuto(fresh, QueueOrigin.Suggestion);
        }
        finally
        {
            extending = false;
        }
    }

    private (string ItemId, long ListenedMs, bool HistorySent)? seedListened;

    // --- ほかの端末の再生を映す ---

    private void Remote(Wave.Core.Connect.ControlAction action, long? positionMs = null)
    {
        if (SendRemoteControl?.Invoke(action, positionMs) != true)
        {
            Message = L.T("ほかの端末に送れませんでした", "Couldn’t reach the other device");
            return;
        }
        // 返事（相手の次のカーソル）を待つ間も、押した結果をすぐ見せる
        if (remote is { } r && action is Wave.Core.Connect.ControlAction.Play or Wave.Core.Connect.ControlAction.Pause)
        {
            var playing = action == Wave.Core.Connect.ControlAction.Play;
            remote = r with { PositionMs = (long)(r.PositionNow(clock.ElapsedMilliseconds) * 1000), CapturedAtMs = clock.ElapsedMilliseconds, Playing = playing };
            IsPlaying = playing;
            HoldOptimistic();
        }
    }

    private long? pendingRemoteSeek;
    private DispatcherQueueTimer? remoteSeekTimer;

    /// <summary>押した結果を見せている間（相手の新しいカーソルが届くまで、最大3秒）、届いた古いカーソルで表示を戻さない。</summary>
    private long optimisticUntilMs;
    private long? optimisticBaseVersion;

    private void HoldOptimistic()
    {
        optimisticUntilMs = clock.ElapsedMilliseconds + 3_000;
        optimisticBaseVersion = remote?.Version;
    }

    /// <summary>映し始める前の手元のキュー（映すのをやめたら戻す。Android と同じく、手元のキューは相手のキューで消さない）。</summary>
    private sealed record LocalQueueBackup(List<(Song Song, QueueItemMeta Meta)> Items, int Index, string? SourceId, string? RadioSeed, string? Continuation, string? RadioPlaylistId, Dictionary<string, int>? ShuffleOrder, double PositionSeconds = 0, RepeatMode? Repeat = null);

    private LocalQueueBackup? localBackup;

    /// <summary>ほかの端末の再生を映す（null ならやめる）。端末の連携が UI のスレッドで呼ぶ。</summary>
    public void ShowRemote(RemoteSnapshot? next)
    {
        if (next is null)
        {
            if (IsRemote) LeaveRemote(restoreLocal: true);
            return;
        }
        if (!IsRemote)
        {
            // 手元の再生は止めて、キューを退避する（この端末は鳴らしていない）
            loadCancel?.Cancel();
            RememberEpisode();
            episodeUrl = null;
            player.Pause();
            player.Source = null;
            IsLoading = false;
            localBackup = Queue.Count == 0 ? null : new LocalQueueBackup(
                Queue.Select((song, i) => (song, itemMeta[i])).ToList(), Index, SourceId, radioSeed, radioContinuation, radioPlaylistId, shuffleOrder is null ? null : new Dictionary<string, int>(shuffleOrder), Position);
            IsRemote = true;
        }
        // 押した直後は、相手の新しいカーソルが届くまで表示を保つ
        if (clock.ElapsedMilliseconds < optimisticUntilMs && next.Version == optimisticBaseVersion) return;
        optimisticUntilMs = 0;
        remote = next;
        RemoteDevice = next.DeviceLabel;
        // キューは中身が変わったときだけ置き直す
        if ((next.QueueKey != remoteQueueKey || (remoteQueueHeldUntilMs != 0 && clock.ElapsedMilliseconds >= remoteQueueHeldUntilMs)) && next.Queue is { } queue)
        {
            remoteQueueKey = next.QueueKey;
            remoteQueueHeldUntilMs = 0;
            Queue.Clear();
            foreach (var (song, meta) in queue.Items)
            {
                adoptIds = new([meta.ItemId]);
                nextOrigin = meta.Origin;
                Queue.Add(song);
            }
            adoptIds = null;
            nextOrigin = QueueOrigin.Source;
            SourceId = queue.SourceId;
        }
        var index = itemMeta.FindIndex(m => m.ItemId == next.CurrentItemId);
        if (index >= 0 && (Index != index || Current is null || !ReferenceEquals(Current, Queue[index])))
        {
            Index = index;
            Current = Queue[index];
            Like = null;
            Video = null;
            Lyrics = null;
            // 位置を先に新しい曲のものにしてから長さを替える（逆だと、シークバーの最大値が縮んだ瞬間に前の曲の終わりの位置が
            // 新しい曲の終わりに押し込まれ、本人のシークとして相手に送ってしまい、次の曲がすぐ飛ばされていた）
            Position = next.PositionNow(clock.ElapsedMilliseconds);
            Duration = Current.DurationSeconds ?? 0;
            var song = Current;
            var cancel = new CancellationTokenSource();
            loadCancel?.Cancel();
            loadCancel = cancel;
            _ = LoadSongInfoAsync(song, cancel.Token);
            _ = LoadLyricsAsync(song, cancel.Token);
        }
        IsPlaying = next.Playing;
        UpdateRemoteMediaControls();
        // 相手のリピートを映すだけ（この端末の設定には書かない）
        if (repeat != next.Repeat)
        {
            repeat = next.Repeat;
            OnPropertyChanged(nameof(Repeat));
        }
        // 相手の音量をつまみに映す（本人が動かした直後は、届いた古い値で戻さない）
        if (next.VolumePercent is { } percent && clock.ElapsedMilliseconds >= remoteVolumeHoldUntilMs)
        {
            applyingRemoteVolume = true;
            Volume = percent / 100.0;
            applyingRemoteVolume = false;
        }
        Position = next.PositionNow(clock.ElapsedMilliseconds);
    }

    /// <summary>
    /// タスクバー・音量のポップアップのメディアの表示にも、ほかの端末の曲を出す（前に手元で鳴らした曲を残さない）。
    /// 再生・一時停止のキーは CommandManager の受け口から TogglePlay に入り、その端末への命令になる。
    /// </summary>
    private void UpdateRemoteMediaControls()
    {
        var smtc = player.SystemMediaTransportControls;
        smtc.PlaybackStatus = IsPlaying ? MediaPlaybackStatus.Playing : MediaPlaybackStatus.Paused;
        if (Current is not { } song || shownOnSmtc == song) return;
        shownOnSmtc = song;
        var updater = smtc.DisplayUpdater;
        updater.ClearAll();
        updater.Type = MediaPlaybackType.Music;
        updater.MusicProperties.Title = song.Title;
        updater.MusicProperties.Artist = song.ArtistsText;
        if (song.Album?.Title is { } album) updater.MusicProperties.AlbumTitle = album;
        if (Uri.TryCreate(song.ThumbnailUrl, UriKind.Absolute, out var art)) updater.Thumbnail = RandomAccessStreamReference.CreateFromUri(art);
        updater.Update();
    }

    private Song? shownOnSmtc;

    /// <summary>
    /// 映すのをやめる。この端末で鳴らし始めたとき（曲を選んだ・引き取った）は、そのままキューを置き換える。
    /// 相手が見えなくなったときは、映す前の手元のキューに戻す。
    /// </summary>
    private void LeaveRemote(bool restoreLocal = false)
    {
        if (!IsRemote) return;
        remoteLiveVideo.Stop();
        IsRemote = false;
        RemoteDevice = null;
        shownOnSmtc = null;
        remote = null;
        remoteQueueKey = null;
        remoteQueueHeldUntilMs = 0;
        pendingRemoteSeek = null;
        remoteSeekTimer?.Stop();
        IsPlaying = false;
        Position = 0;
        // つまみを手元の音量に戻す
        remoteVolumeTimer?.Stop();
        remoteVolumeHoldUntilMs = 0;
        Volume = localVolume;
        var backup = localBackup;
        localBackup = null;
        if (!restoreLocal) return;
        // タスクバーの表示に「再生中」を残さない（手元の曲は一時停止のまま戻す）
        player.SystemMediaTransportControls.PlaybackStatus = backup is null ? MediaPlaybackStatus.Closed : MediaPlaybackStatus.Paused;
        Queue.Clear();
        Current = null;
        Index = -1;
        if (backup is null) return;
        foreach (var (song, meta) in backup.Items)
        {
            adoptIds = new([meta.ItemId]);
            nextOrigin = meta.Origin;
            Queue.Add(song);
        }
        adoptIds = null;
        nextOrigin = QueueOrigin.Source;
        SourceId = backup.SourceId;
        radioSeed = backup.RadioSeed;
        radioContinuation = backup.Continuation;
        radioPlaylistId = backup.RadioPlaylistId;
        stoppedAtEnd = false;
        SetShuffleOrder(backup.ShuffleOrder);
        if (backup.Repeat is { } repeat) Repeat = repeat;
        if (backup.Index >= 0 && backup.Index < Queue.Count)
        {
            Index = backup.Index;
            Current = Queue[backup.Index];
            Duration = Current.DurationSeconds ?? 0;
            Position = backup.PositionSeconds;
        }
        SaveQueue();
    }

    // --- ほかの端末からの命令に従う（この端末が鳴らしている） ---

    public bool ApplyControl(Wave.Core.Connect.ControlAction action, long? positionMs)
    {
        if (IsRemote || Current is null) return false;
        switch (action)
        {
            case Wave.Core.Connect.ControlAction.Play:
                ConnectResume();
                break;
            case Wave.Core.Connect.ControlAction.Pause:
                player.Pause();
                break;
            case Wave.Core.Connect.ControlAction.Next:
                Advance();
                break;
            case Wave.Core.Connect.ControlAction.Previous:
                if (IsLive) { if (Index > 0) SelectAt(Index - 1); }
                else if (Position > 3 || Index <= 0) Seek(0);
                else SelectAt(Index - 1);
                break;
            case Wave.Core.Connect.ControlAction.Seek:
                Seek((positionMs ?? 0) / 1000.0);
                break;
        }
        return true;
    }

    private async Task LoadCurrentAsync(double seekTo = 0)
    {
        loadCancel?.Cancel();
        var cancel = new CancellationTokenSource();
        loadCancel = cancel;
        if (Current is not { } song) return;
        IsLoading = true;
        Like = null;
        Video = null;
        Lyrics = null;
        Position = seekTo;
        Duration = song.DurationSeconds ?? 0;
        retried = false;
        // 聴いた時間は曲ごと（引き取った曲は相手の値から続ける。同じ曲の読み直しでは数え直さない）
        var itemId = Index >= 0 && Index < itemMeta.Count ? itemMeta[Index].ItemId : null;
        var now = clock.ElapsedMilliseconds;
        if (seedListened is { } seed && seed.ItemId == itemId)
        {
            listen.Reset(seed.ListenedMs, IsPlaying ? now : null);
            statsSent = seed.HistorySent;
            listenedItemId = itemId;
        }
        else if (itemId != listenedItemId)
        {
            listen.Reset(0, IsPlaying ? now : null);
            statsSent = false;
            listenedItemId = itemId;
        }
        seedListened = null;
        // 前に流していたエピソードの位置を書いてから替える（自然に次へ進んだときは OnEnded が「聴いた」を書いてある）
        RememberEpisode();
        episodeUrl = null;
        player.Pause();
        try
        {
            // RSS のエピソード（ext:）は YouTube の解決を通さず、音声の URL をそのまま流す（ADR-0012）
            if (Feed.IsExternal(song.VideoId))
            {
                var episode = Feed.AudioUrlOf(song.VideoId);
                if (episode is null || !await StartEpisodeAsync(song, episode, seekTo, cancel.Token))
                {
                    if (cancel.IsCancellationRequested) return;
                    await RecoverAsync(song);
                    return;
                }
                consecutiveFailures = 0;
                Like = null;
                Video = null;
                Lyrics = new LyricsService.Result.None();
                return;
            }
            ResolvedStream? stream = null;
            // 少し前に解決し直しても拒否された曲は、解決し直さずに飛ばす
            if (!song.IsLive && rejectedSongs.RecentlyRejected(song.VideoId, AppServices.Client.Generation))
            {
                AppPaths.Log("play skipped recently rejected");
                SkipFailed();
                return;
            }
            // 残してある曲: URL を調べ直さずに、残した分から鳴らし始める（続きは鳴らしながら取る）
            if (!song.IsLive && CachedStart(song.VideoId) is { } cachedFormat)
            {
                if (!await StartCachedAsync(song, cachedFormat, seekTo))
                {
                    if (cancel.IsCancellationRequested) return;
                    await RecoverAsync(song);
                    return;
                }
                consecutiveFailures = 0;
                _ = LoadSongInfoAsync(song, cancel.Token);
                _ = LoadLyricsAsync(song, cancel.Token);
                Prefetch();
                ExtendRadioIfNeeded();
                return;
            }
            if (!song.IsLive)
            {
                stream = await ResolveAsync(song.VideoId, cancel.Token);
                if (cancel.IsCancellationRequested) return;
                // ライブの印の無い曲がライブ配信だった: 印を付けて HLS で流す
                if (stream is null && lastResolveWasLive) song = MarkLive(song);
                else if (stream is null)
                {
                    // その曲だけの失敗（再生不可・曲ごとのログインの確認など）は、知らせて次の曲へ飛ばす。
                    // ログイン・通信など、どの曲でも起きる失敗では飛ばさずに止める（Android と同じ）
                    if (lastResolveFailure is { } failure && StreamFailures.IsThisSongOnly(failure))
                    {
                        SkipFailed(failure == StreamFailure.SongLoginRequired
                            ? L.T("この曲はログインの確認（年齢の確認など）が要るので、次の曲に進みます", "This song needs a sign-in check (such as age verification), skipping to the next one")
                            : L.T("この曲は再生できないので、次の曲に進みます", "This song isn't available, skipping to the next one"));
                        return;
                    }
                    IsLoading = false;
                    return;
                }
            }
            var started = song.IsLive ? await StartLiveAsync(song, cancel.Token) : await StartStreamAsync(song, stream!, seekTo);
            if (!started)
            {
                if (cancel.IsCancellationRequested) return;
                await RecoverAsync(song);
                return;
            }
            consecutiveFailures = 0;
            _ = LoadSongInfoAsync(song, cancel.Token);
            _ = LoadLyricsAsync(song, cancel.Token);
            Prefetch();
            ExtendRadioIfNeeded();
        }
        catch (OperationCanceledException)
        {
        }
    }

    /// <summary>直前の ResolveAsync が「ライブ配信だった」で終わったか。</summary>
    private bool lastResolveWasLive;
    /// <summary>直前の ResolveAsync の失敗（成功・ライブ配信なら null）。</summary>
    private StreamFailure? lastResolveFailure;

    private async Task<ResolvedStream?> ResolveAsync(string videoId, CancellationToken cancel)
    {
        if (prefetched.Remove(videoId, out var cached) && AppServices.Resolver.IsFresh(cached, FreshMarginMs) && cached.Generation == AppServices.Client.Generation)
            return cached;
        lastResolveWasLive = false;
        lastResolveFailure = null;
        var result = await AppServices.Resolver.ResolveAsync(videoId, cancel);
        if (result.Stream is { } stream) return stream;
        AppPaths.Log($"resolve failed reason={result.Failure}");
        lastResolveFailure = result.Failure;
        if (result.Failure == StreamFailure.LiveStream)
        {
            // ライブ配信だった（呼び出し側が HLS で流し直す）
            lastResolveWasLive = true;
            return null;
        }
        Message = result.Failure switch
        {
            StreamFailure.NotLoggedIn => L.T("この曲を再生するにはログインが必要です", "Log in to play this song"),
            StreamFailure.Unplayable => L.T("この曲は再生できません", "This song isn't available"),
            StreamFailure.SongLoginRequired => L.T("この曲はログインの確認（年齢の確認など）が要ります", "This song needs a sign-in check (such as age verification)"),
            StreamFailure.Network => L.T("通信できませんでした", "Couldn't connect"),
            _ => L.T($"再生の準備ができませんでした（{result.Failure}）", $"Couldn't prepare playback ({result.Failure})"),
        };
        return null;
    }

    /// <summary>鳴らし始めに残っていてほしいバイト数（256kbps で 15 秒ほど）。</summary>
    private const long CachedStartBytes = 512 * 1024;
    /// <summary>次の曲を先に取っておくバイト数（256kbps で 30 秒ほど）。</summary>
    private const long WarmBytes = 1024 * 1024;

    /// <summary>残してある曲で、鳴らし始めに足りる分（頭の区切りまで）が残っていれば、その形式。</summary>
    private static CachedFormat? CachedStart(string videoId)
    {
        if (AppServices.AudioCache.Entry(videoId) is not { } entry) return null;
        var need = Math.Max(entry.Format.Dash is { } dash ? dash.IndexEnd + 1 : 0, CachedStartBytes);
        return entry.Cached >= need || entry.Cached >= entry.Format.Length ? entry.Format : null;
    }

    private async Task<bool> StartCachedAsync(Song song, CachedFormat format, double seekTo)
    {
        currentStream = null;
        cpn = PlaybackStats.NewCpn();
        var videoId = song.VideoId;
        // 続きが要ったとき・再生履歴を送るときに、URL を調べる
        var bytes = RangeStream.OpenCached(AppServices.Http, format, AppServices.AudioCache, async () =>
        {
            var result = await AppServices.Resolver.ResolveAsync(videoId);
            if (result.Stream is not { } stream) throw new StreamRejectedException(0);
            ui.TryEnqueue(() =>
            {
                if (Current?.VideoId == videoId) currentStream = stream;
            });
            return stream;
        });
        var source = await CreateDashSourceAsync(videoId, format.Dash, format.MimeType, format.Itag, bytes, "audio");
        // 使わずに戻るときは、開いた読み手を閉じる（MusicVideoPlayer と同じ）
        if (source is null || Current?.VideoId != videoId)
        {
            bytes.Dispose();
            return false;
        }
        PlaySource(song, source, seekTo);
        AppPaths.Log($"play cached itag={format.Itag} queue={Queue.Count} index={Index}");
        _ = Task.Run(async () =>
        {
            try
            {
                await bytes.PrepareSourceAsync();
            }
            catch (Exception e) when (e is StreamRejectedException or HttpRequestException or IOException)
            {
                AppPaths.Log($"cached source failed type={e.GetType().Name}");
            }
        });
        return true;
    }

    private async Task<bool> StartStreamAsync(Song song, ResolvedStream stream, double seekTo = 0)
    {
        currentStream = stream;
        cpn = PlaybackStats.NewCpn();
        lastOpenRejected = false;
        RangeStream bytes;
        try
        {
            bytes = await RangeStream.OpenAsync(AppServices.Http, stream, cache: AppServices.AudioCache);
        }
        catch (Exception e) when (e is StreamRejectedException or HttpRequestException or IOException)
        {
            lastOpenRejected = e is StreamRejectedException;
            AppPaths.Log($"stream open failed type={e.GetType().Name} status={(e as StreamRejectedException)?.Status}");
            return false;
        }
        if (Current?.VideoId != song.VideoId) return false;
        var source = await CreateSourceAsync(song, stream, bytes);
        if (source is null || Current?.VideoId != song.VideoId) return false;
        PlaySource(song, source, seekTo);
        AppPaths.Log($"play itag={stream.Itag} queue={Queue.Count} index={Index}");
        return true;
    }

    /// <summary>再生の元を曲名・画像と一緒に MediaPlayer に渡して鳴らす（メディアキー・音量のポップアップにも出る）。</summary>
    private void PlaySource(Song song, MediaSource source, double seekTo)
    {
        var item = new MediaPlaybackItem(source);
        var props = item.GetDisplayProperties();
        props.Type = MediaPlaybackType.Music;
        props.MusicProperties.Title = song.Title;
        props.MusicProperties.Artist = song.ArtistsText;
        if (song.Album?.Title is { } album) props.MusicProperties.AlbumTitle = album;
        if (Uri.TryCreate(song.ThumbnailUrl, UriKind.Absolute, out var art)) props.Thumbnail = RandomAccessStreamReference.CreateFromUri(art);
        item.ApplyDisplayProperties(props);
        player.Source = item;
        if (seekTo > 1 && !song.IsLive) player.PlaybackSession.Position = TimeSpan.FromSeconds(seekTo);
        player.Play();
        IsLoading = false;
    }

    /// <summary>ライブ配信を流し始めた時刻（長く流れたあとの失敗は、もう一度だけ取り直してよい）。</summary>
    private long liveStartedMs;

    /// <summary>
    /// ライブ配信を HLS で流す（ANDROID の /player から選んだ1本の媒体プレイリストを、Windows の HLS の再生 AdaptiveMediaSource に渡す）。
    /// VOD の音声の取り方（RangeStream・DASH の目次）は使わない。URL は IP に結び付いた署名を含むので、ログ・画面に出さない。
    /// </summary>
    private async Task<bool> StartLiveAsync(Song song, CancellationToken cancel)
    {
        currentStream = null;
        var result = await AppServices.Live.ResolveAsync(song.VideoId, cancel, video: LiveVideo);
        if (cancel.IsCancellationRequested || Current?.VideoId != song.VideoId) return false;
        if (result.Stream is not { } live)
        {
            AppPaths.Log($"live resolve failed reason={result.Failure}");
            Message = result.Failure switch
            {
                LiveFailure.NotLive => L.T("このライブ配信は終わっているか、再生できません", "This live stream has ended or isn't available"),
                LiveFailure.Network => L.T("通信できませんでした", "Couldn't connect"),
                _ => L.T("ライブ配信を再生できませんでした", "Couldn't play this live stream"),
            };
            return false;
        }
        var source = await CreateLiveSourceAsync(live);
        if (cancel.IsCancellationRequested || Current?.VideoId != song.VideoId || source is null) return false;
        PlaySource(song, source, 0);
        liveStartedMs = clock.ElapsedMilliseconds;
        AppPaths.Log($"play live queue={Queue.Count} index={Index}");
        return true;
    }

    /// <summary>
    /// ライブの HLS の媒体プレイリストから再生の元を作る（ほかの端末のライブの映像を出す RemoteLiveVideo も使う）。
    /// 媒体プレイリストの読み直しと区切り（googlevideo）のほかは取らない。作れなければ null。
    /// </summary>
    internal static async Task<MediaSource?> CreateLiveSourceAsync(LiveStream live)
    {
        var created = await Windows.Media.Streaming.Adaptive.AdaptiveMediaSource.CreateFromUriAsync(new Uri(live.RevealUrl()));
        if (created.Status != Windows.Media.Streaming.Adaptive.AdaptiveMediaSourceCreationStatus.Success)
        {
            AppPaths.Log($"live create failed status={created.Status} http={created.HttpResponseMessage?.StatusCode}");
            return null;
        }
        var adaptive = created.MediaSource;
        AppPaths.Log($"live source live={adaptive.IsLive} bitrates={adaptive.AvailableBitrates.Count}");
        adaptive.DownloadRequested += (_, e) =>
        {
            var uri = e.ResourceUri.ToString();
            if (LiveUrls.IsAllowedManifest(uri) || LiveUrls.IsAllowedSegment(uri)) return;
            AppPaths.Log($"live download blocked host={e.ResourceUri.Host}");
            e.Result.Buffer = new Windows.Storage.Streams.Buffer(0);
            e.Result.ExtendedStatus = 403;
        };
        adaptive.DownloadFailed += (_, e) => AppPaths.Log($"live download failed type={e.ResourceType} http={e.HttpResponseMessage?.StatusCode}");
        return MediaSource.CreateFromAdaptiveMediaSource(adaptive);
    }

    /// <summary>
    /// YouTube の音声は DASH 用の断片化 MP4 で、Windows のふつうのファイル再生では時刻が読めず一瞬で終わってしまう。
    /// そこで DASH の目次（MPD）をその場で作り、Windows の DASH の再生（AdaptiveMediaSource）に渡す。
    /// 目次には本物の URL を書かず、バイトは再生の部品に求められたぶんだけアプリが取って渡す（署名付き URL は渡さない）。
    /// </summary>
    private static Task<MediaSource?> CreateSourceAsync(Song song, ResolvedStream stream, RangeStream bytes) => CreateDashSourceAsync(song.VideoId, stream, bytes, "audio");

    /// <summary>音・映像の DASH の再生の元（kind は目次に書く仮の URL の区別）。</summary>
    internal static Task<MediaSource?> CreateDashSourceAsync(string videoId, ResolvedStream stream, RangeStream bytes, string kind) =>
        CreateDashSourceAsync(videoId, stream.Dash, stream.MimeType, stream.Itag, bytes, kind);

    internal static async Task<MediaSource?> CreateDashSourceAsync(string videoId, DashInfo? dashInfo, string mimeType, int itag, RangeStream bytes, string kind)
    {
        if (dashInfo is not { } dash)
        {
            AppPaths.Log("source plain (no dash ranges)");
            return MediaSource.CreateFromStream(bytes.AsRandomAccessStream(), mimeType.Split(';')[0].Trim());
        }
        var placeholder = new Uri($"https://wave.invalid/{kind}/{videoId}/{itag}");
        var manifest = new InMemoryRandomAccessStream();
        await manifest.WriteAsync(System.Text.Encoding.UTF8.GetBytes(DashManifest.Build(dash, mimeType, placeholder.ToString())).AsBuffer());
        manifest.Seek(0);
        var created = await Windows.Media.Streaming.Adaptive.AdaptiveMediaSource.CreateFromStreamAsync(manifest, placeholder, "application/dash+xml");
        if (created.Status != Windows.Media.Streaming.Adaptive.AdaptiveMediaSourceCreationStatus.Success)
        {
            AppPaths.Log($"dash create failed status={created.Status}");
            return null;
        }
        var adaptive = created.MediaSource;
        adaptive.DownloadRequested += async (_, e) =>
        {
            if (e.ResourceUri.Host != placeholder.Host) return;
            var deferral = e.GetDeferral();
            try
            {
                var offset = (long)(e.ResourceByteRangeOffset ?? 0);
                var end = e.ResourceByteRangeLength is { } length ? offset + (long)length - 1 : bytes.Length - 1;
                var data = await bytes.ReadRangeAsync(offset, end);
                e.Result.Buffer = data.AsBuffer();
            }
            catch (Exception ex) when (ex is StreamRejectedException or HttpRequestException or IOException)
            {
                AppPaths.Log($"dash download failed type={ex.GetType().Name} status={(ex as StreamRejectedException)?.Status}");
                e.Result.ExtendedStatus = 403;
            }
            finally
            {
                deferral.Complete();
            }
        };
        return MediaSource.CreateFromAdaptiveMediaSource(adaptive);
    }

    // --- RSS のエピソード（ADR-0012） ---

    /// <summary>今流しているエピソードの音声の URL（YouTube の曲なら null）。</summary>
    private string? episodeUrl;
    private long lastEpisodeSaveMs;

    /// <summary>覚えている位置がこれより後なら、流し始めにそこへ飛ぶ（Android と同じ）。</summary>
    private const int ResumeAtLeastSeconds = 5;

    /// <summary>
    /// エピソードの音声をそのまま流す。cookie を持たない別の HttpClient で取り、リダイレクトは https の間だけ追う。
    /// 先頭の1バイトで行き先と長さを知り、Range で少しずつ取って再生の部品に渡す（Range に応じない配信元なら URL ごと渡す）。
    /// 頭から流すときは、覚えている位置（5秒より後）から始める。URL はログに書かない。
    /// </summary>
    private async Task<bool> StartEpisodeAsync(Song song, string url, double seekTo, CancellationToken cancel)
    {
        currentStream = null;
        if (seekTo < 1 && AppServices.FeedLibrary.Position(url)?.ResumeSeconds is { } resume && resume > ResumeAtLeastSeconds) seekTo = resume;
        MediaSource source;
        bool ranged;
        string mime;
        try
        {
            var (bytes, final, type) = await RangeStream.OpenEpisodeAsync(AppServices.FeedHttp, url, AppServices.Feeds.UserAgent, cancel);
            if (cancel.IsCancellationRequested || Current?.VideoId != song.VideoId) return false;
            mime = EpisodeMime(type, final);
            ranged = bytes is not null;
            source = bytes is not null ? MediaSource.CreateFromStream(bytes.AsRandomAccessStream(), mime) : MediaSource.CreateFromUri(new Uri(final));
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            return false;
        }
        catch (Exception e) when (e is StreamRejectedException or HttpRequestException or IOException or OperationCanceledException or UriFormatException)
        {
            AppPaths.Log($"episode open failed type={e.GetType().Name} status={(e as StreamRejectedException)?.Status}");
            return false;
        }
        PlaySource(song, source, seekTo);
        episodeUrl = url;
        lastEpisodeSaveMs = clock.ElapsedMilliseconds;
        AppPaths.Log($"play episode ranged={ranged} type={mime} resume={(int)seekTo} queue={Queue.Count} index={Index}");
        return true;
    }

    /// <summary>応答の種類が音声ならそれを、違えば URL の拡張子から決める。</summary>
    private static string EpisodeMime(string? contentType, string url)
    {
        if (contentType is { } type && type.StartsWith("audio/", StringComparison.OrdinalIgnoreCase)) return type;
        var path = Uri.TryCreate(url, UriKind.Absolute, out var uri) ? uri.AbsolutePath.ToLowerInvariant() : "";
        return Path.GetExtension(path) switch
        {
            ".m4a" or ".mp4" or ".m4b" => "audio/mp4",
            ".aac" => "audio/aac",
            ".ogg" or ".oga" or ".opus" => "audio/ogg",
            ".wav" => "audio/wav",
            _ => "audio/mpeg",
        };
    }

    /// <summary>今のエピソードの位置を覚える（finished なら最後まで聴いたとする）。エピソードを流していなければ何もしない。</summary>
    private void RememberEpisode(bool finished = false)
    {
        if (episodeUrl is not { } url || player.Source is null || IsRemote) return;
        var session = player.PlaybackSession;
        var natural = session.NaturalDuration.TotalSeconds;
        int? duration = natural > 0 ? (int)natural : Current?.DurationSeconds;
        var position = finished && duration is { } d ? d : (int)session.Position.TotalSeconds;
        lastEpisodeSaveMs = clock.ElapsedMilliseconds;
        AppServices.FeedLibrary.Remember(url, position, duration);
    }

    private void OnEnded()
    {
        // 曲の途中で「終わった」と来たら、読み込みの失敗とみなす（次々に飛ばさない）
        // ライブ配信が「終わった」: 流れが切れた（URL の期限・配信の中断）とみなして取り直す。だめなら次の曲へ
        if (IsLive && Current is { } live)
        {
            AppPaths.Log("live ended");
            _ = RecoverAsync(live);
            return;
        }
        // RSS のエピソードは最後まで流れた（フィードの長さは当てにならないので、途中で終わったとはみなさない）
        if (episodeUrl is not null)
        {
            RememberEpisode(finished: true);
            if (Repeat != RepeatMode.One) episodeUrl = null;
        }
        else if ((Current?.DurationSeconds ?? 0) is var known && known > 10 && Position < known - 5)
        {
            AppPaths.Log($"ended early position={Position:F1} duration={known}");
            if (Current is { } song) _ = RecoverAsync(song);
            return;
        }
        if (Repeat == RepeatMode.One)
        {
            Seek(0);
            player.Play();
            statsSent = false;
            listen.Reset(0, clock.ElapsedMilliseconds);
            cpn = PlaybackStats.NewCpn();
            return;
        }
        if (Index + 1 < Queue.Count || Repeat == RepeatMode.All) Advance();
        else
        {
            IsPlaying = false;
            stoppedAtEnd = true;
            // 最後の曲で止まった: ラジオの続きが取れていなければ取り直す（取れたら足した先頭から続ける）
            ExtendRadioIfNeeded();
        }
    }

    private async void OnFailed(MediaPlayerError error)
    {
        AppPaths.Log($"media failed error={error} retried={retried}");
        if (Current is { } song) await RecoverAsync(song);
    }

    /// <summary>
    /// 鳴らせなかったとき。1回だけ解読の準備から取り直してやり直し、だめなら次の曲へ。続けて失敗したら止める
    /// （ログインしていないと、googlevideo は先頭の一部しか返さないので、どの曲も鳴らない）。
    /// </summary>
    private async Task RecoverAsync(Song song)
    {
        // RSS のエピソードはログインが要らない。1回だけ取り直し、だめなら次へ（ADR-0012）
        if (Feed.IsExternal(song.VideoId))
        {
            if (!retried && Feed.AudioUrlOf(song.VideoId) is { } url && Current?.VideoId == song.VideoId)
            {
                retried = true;
                if (await StartEpisodeAsync(song, url, Position, loadCancel?.Token ?? default))
                {
                    consecutiveFailures = 0;
                    return;
                }
            }
            IsLoading = false;
            if (++consecutiveFailures >= MaxConsecutiveFailures)
            {
                Message = L.T("続けて再生できなかったので、止めました", "Stopped after several songs failed to play");
                consecutiveFailures = 0;
                return;
            }
            Message = L.T("エピソードを再生できなかったので、次に進みます", "Couldn't play this episode, skipping to the next one");
            if (Index + 1 < Queue.Count) Advance();
            return;
        }
        // ライブ配信は ANDROID の匿名の /player で流すので、ログインは要らない
        var live = song.IsLive || IsLive;
        if (!live && !AppServices.Client.IsLoggedIn)
        {
            IsLoading = false;
            Message = L.T("曲を再生するにはログインしてください（右上のアカウントから）", "Log in to play songs (from the account button at the top right)");
            return;
        }
        // ライブ配信は長く流れ続けるので、しばらく鳴ったあとの失敗（URL の期限・403）なら、もう一度取り直してよい
        if (live && retried && clock.ElapsedMilliseconds - liveStartedMs > 60_000) retried = false;
        if (live && !retried)
        {
            retried = true;
            if (Current?.VideoId == song.VideoId && await StartLiveAsync(Current, loadCancel?.Token ?? default))
            {
                consecutiveFailures = 0;
                return;
            }
        }
        else if (!retried)
        {
            retried = true;
            AppServices.Resolver.OnStreamRejected();
            prefetched.Remove(song.VideoId);
            var position = Position;
            var generation = AppServices.Client.Generation;
            var stream = await ResolveAsync(song.VideoId, loadCancel?.Token ?? default);
            if (stream is not null && Current?.VideoId == song.VideoId && await StartStreamAsync(song, stream, position))
            {
                consecutiveFailures = 0;
                return;
            }
            // 解決し直しても拒否された: 少しの間この曲を解決し直さない
            if (stream is not null && lastOpenRejected)
            {
                rejectedSongs.MarkRejected(song.VideoId, generation);
                AppPaths.Log("stream rejected again; cooling down");
            }
            // 取り直したらライブ配信だった（配信が始まった・印の無い曲だった）
            if (stream is null && lastResolveWasLive && Current?.VideoId == song.VideoId && await StartLiveAsync(MarkLive(song), loadCancel?.Token ?? default))
            {
                consecutiveFailures = 0;
                return;
            }
        }
        SkipFailed();
    }

    /// <summary>今の曲を鳴らせなかったので次へ進む。続けて失敗したら止める。</summary>
    private void SkipFailed(string? message = null)
    {
        IsLoading = false;
        if (++consecutiveFailures >= MaxConsecutiveFailures)
        {
            Message = L.T("続けて再生できなかったので、止めました", "Stopped after several songs failed to play");
            consecutiveFailures = 0;
            return;
        }
        // 次の曲が無ければ、進むとは知らせない（理由の知らせはそのまま残す）
        if (Index + 1 >= Queue.Count) return;
        Message = message ?? L.T("再生できなかったので、次の曲に進みます", "Couldn't play this song, skipping to the next one");
        Advance();
    }

    private void Tick()
    {
        if (IsRemote)
        {
            if (remote is { } r) Position = r.PositionNow(clock.ElapsedMilliseconds);
            return;
        }
        if (IsPlaying && clock.ElapsedMilliseconds - lastPositionSaveMs > PositionSaveIntervalMs) SaveQueue();
        if (IsPlaying && episodeUrl is not null && clock.ElapsedMilliseconds - lastEpisodeSaveMs > PositionSaveIntervalMs) RememberEpisode();
        if (player.Source is null) return;
        var session = player.PlaybackSession;
        Position = session.Position.TotalSeconds;
        if (IsLive) Duration = 0;
        else if (session.NaturalDuration.TotalSeconds > 0) Duration = session.NaturalDuration.TotalSeconds;
        // 位置ではなく実際に聴いた時間で決める（30秒、短い曲は長さの半分。シークしただけでは送らない。Android の ListenCounter と同じ）
        if (!statsSent && IsPlaying && listen.Reached(clock.ElapsedMilliseconds, Duration > 0 ? (long)(Duration * 1000) : null) && currentStream is { } stream && AppServices.Settings.SendHistory && AppServices.Client.IsLoggedIn)
        {
            statsSent = true;
            _ = SendStatsAsync(stream, cpn, Position);
        }
    }

    private static async Task SendStatsAsync(ResolvedStream stream, string cpn, double position)
    {
        var status = await AppServices.Stats.ReportAsync(stream, cpn, position);
        AppPaths.Log($"stats status={status?.ToString() ?? "skipped"}");
    }

    /// <summary>曲の評価（ログイン中だけ）と、重ねられる映像。</summary>
    private async Task LoadSongInfoAsync(Song song, CancellationToken cancel)
    {
        // RSS のエピソードには YouTube の評価も MV も無い（ADR-0012）
        if (Feed.IsExternal(song.VideoId)) return;
        var info = await AppServices.Music.SongInfoAsync(song.VideoId, cancel);
        if (!info.IsOk || Current?.VideoId != song.VideoId) return;
        if (AppServices.Client.IsLoggedIn) Like = info.Value!.Like;
        // ライブ配信には重ねる MV を出さない
        Video = song.IsLive || IsLive ? null : info.Value!.Video;
    }

    /// <summary>評価を読み直す（読めなかったときの↻）。</summary>
    public void ReloadLike()
    {
        if (Current is not { } song) return;
        _ = LoadSongInfoAsync(song, loadCancel?.Token ?? CancellationToken.None);
    }

    /// <summary>歌詞を読み直す（読み込みに失敗したときの「もう一度」。失敗は覚えていないので取り直す）。</summary>
    public void ReloadLyrics()
    {
        if (Current is not { } song) return;
        Lyrics = null;
        _ = LoadLyricsAsync(song, loadCancel?.Token ?? CancellationToken.None);
    }

    private async Task LoadLyricsAsync(Song song, CancellationToken cancel)
    {
        // ライブ配信には歌詞が無い（探さない）
        if (song.IsLive)
        {
            if (Current?.VideoId == song.VideoId) Lyrics = null;
            return;
        }
        // RSS のエピソードも探さない（ADR-0012）
        if (Feed.IsExternal(song.VideoId))
        {
            if (Current?.VideoId == song.VideoId) Lyrics = new LyricsService.Result.None();
            return;
        }
        var result = await AppServices.Lyrics.LyricsAsync(song, cancel);
        if (Current?.VideoId == song.VideoId) Lyrics = result;
    }

    /// <summary>次の曲の URL を先に解決しておく（曲の切り替えを速くする）。</summary>
    private async void Prefetch()
    {
        if (Index + 1 >= Queue.Count) return;
        var next = Queue[Index + 1];
        // ライブ配信は流すときに HLS を解決する（先読みしない）
        if (next.IsLive || Feed.IsExternal(next.VideoId) || prefetched.ContainsKey(next.VideoId)) return;
        // 頭を残してある曲は、URL も調べない（鳴らすときに残した分から始める）
        if (CachedStart(next.VideoId) is not null) return;
        var result = await AppServices.Resolver.ResolveAsync(next.VideoId);
        if (result.Stream is { } s)
        {
            prefetched[next.VideoId] = s;
            while (prefetched.Count > 4) prefetched.Remove(prefetched.Keys.First());
            // 頭の 30 秒ほどを取って残す（曲送りで、すぐ鳴らせる）
            try
            {
                var warmed = await RangeStream.OpenAsync(AppServices.Http, s, cache: AppServices.AudioCache);
                await Task.Run(() => warmed.WarmAsync(WarmBytes));
                AppPaths.Log($"prefetch warmed={AppServices.AudioCache.Entry(next.VideoId)?.Cached ?? 0}");
            }
            catch (Exception e) when (e is StreamRejectedException or HttpRequestException or IOException)
            {
                AppPaths.Log($"prefetch warm failed type={e.GetType().Name}");
            }
        }
    }

    /// <summary>
    /// 残りが少なくなったら続きを足す。一覧（プレイリスト・アルバム・アーティスト・検索の結果）なら、ここでキューの最後の曲から
    /// 始まるラジオへ切り替える（オートプレイ。Android の QueueSession の switchToRadio と同じ）。
    /// </summary>
    private async void ExtendRadioIfNeeded()
    {
        if (extending || IsRemote || !AutoRadio.NearEnd(Queue.Count, Index)) return;
        if (radioSeed is null)
        {
            var seedAtEnd = AutoRadio.SeedAtEnd(Queue, Index, hasRadio: radioContinuation is not null, Repeat == RepeatMode.All, pendingSourceId is not null && pendingSourceId == SourceId);
            if (seedAtEnd is null) return;
            // 書き出し（端末の連携）では、これ以降 SourceId ではなくこの種をラジオとして渡す（Android の source が Radio に替わるのと同じ）
            radioSeed = seedAtEnd;
            radioPlaylistId = AutoRadio.PlaylistIdOf(seedAtEnd);
            radioContinuation = null;
            AppPaths.Log($"autoplay radio seed={seedAtEnd} queue={Queue.Count} index={Index}");
        }
        // ほかの端末から引き取ったラジオ・切り替えたばかりのラジオは続きの印を持たないので、種の曲から取る
        if (radioContinuation is null)
        {
            if (radioSeed is { } seed) await ExtendFromSeedAsync(seed);
            return;
        }
        // 全体リピート中は今のキューを繰り返すので、ラジオの続きは取らない（Android の blockedByRepeat と同じ）
        if (Repeat == RepeatMode.All) return;
        extending = true;
        try
        {
            var page = await AppServices.Music.UpNextAsync(null, radioPlaylistId, radioContinuation);
            if (!page.IsOk) return;
            radioContinuation = page.Value!.Continuation;
            var known = Queue.Select(s => s.VideoId).ToHashSet();
            AppendAuto(page.Value.Songs.Where(s => known.Add(s.VideoId)).ToList(), QueueOrigin.Suggestion);
        }
        finally
        {
            extending = false;
        }
    }

    /// <summary>ログアウトのとき。再生を止めてキューを空にする（別のアカウントの URL を使い続けない）。</summary>
    public void Stop()
    {
        // ログアウト・別のアカウントでのログイン: 保存したキューも消す
        saveTimer.Stop();
        store.Delete();
        LeaveRemote();
        loadCancel?.Cancel();
        // RSS の聴いたところはアカウントに結びつかないので、ログアウトでも残す（ADR-0012）
        RememberEpisode();
        episodeUrl = null;
        player.Pause();
        player.Source = null;
        prefetched.Clear();
        // 聴いた曲の音も残さない
        AppServices.AudioCache.Clear();
        Queue.Clear();
        Current = null;
        Index = -1;
        IsPlaying = false;
        Like = null;
        Video = null;
        Lyrics = null;
    }
}

public enum QueueOrigin { Source, Suggestion, UserAdded }

/// <summary>キューの項目の ID（UUID）と出どころ。端末の連携で、どの項目が今の曲かを相手と合わせるのに使う。</summary>
public sealed record QueueItemMeta(string ItemId, QueueOrigin Origin);

/// <summary>キューの書き出し（端末の連携）。SourceId はプレイリスト・アルバムの ID、RadioSeed はラジオの元の曲。</summary>
public sealed record QueueExport(
    List<(Song Song, QueueItemMeta Meta)> Items,
    string CurrentItemId,
    string? SourceId,
    string? RadioSeed,
    string? Continuation,
    string? RadioPlaylistId);

public sealed record PlaybackCursor(string CurrentItemId, long PositionMs, bool Playing, RepeatMode Repeat, long ListenedMs, bool HistorySent, int VolumePercent);

/// <summary>ほかの端末の再生（表示と遠隔操作のため）。QueueKey はキューの中身の目印（同じなら置き直さない）。</summary>
public sealed record RemoteSnapshot(string DeviceId, string DeviceLabel, string? QueueKey, QueueExport? Queue, string CurrentItemId, long PositionMs, long CapturedAtMs, bool Playing, RepeatMode Repeat, int? DurationSeconds, long Version = 0, int? VolumePercent = null, bool IsLive = false)
{
    /// <summary>今の位置（再生中なら、取ってからの経過を足す。曲の長さで止める）。ライブ配信には位置が無いので 0（経過で伸ばさない。Android・Mac と同じ）。</summary>
    public double PositionNow(long nowMs)
    {
        if (IsLive) return 0;
        var ms = Playing ? PositionMs + Math.Max(0, nowMs - CapturedAtMs) : PositionMs;
        if (DurationSeconds is { } d && d > 0) ms = Math.Min(ms, d * 1000L);
        return ms / 1000.0;
    }
}
