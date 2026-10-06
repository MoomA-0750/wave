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
    private const double StatsAfterSeconds = 10;
    private const long FreshMarginMs = 5 * 60 * 1000;

    private readonly MediaPlayer player = new() { AudioCategory = MediaPlayerAudioCategory.Media, AutoPlay = false };
    private readonly DispatcherQueue ui;
    private readonly DispatcherQueueTimer timer;
    private readonly Dictionary<string, ResolvedStream> prefetched = new();
    private List<Song> unshuffled = [];
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
    private string? radioSeed;

    // 端末の連携（Connect v2）: キューの項目ごとの ID と出どころ（Queue と同じ並び）、今の曲を聴いた時間
    private readonly List<QueueItemMeta> itemMeta = [];
    private QueueOrigin nextOrigin = QueueOrigin.Source;
    private Queue<string>? adoptIds;
    private readonly System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();
    private long listenedMs;
    private long? playingSinceMs;
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
    [ObservableProperty] private string? message;
    [ObservableProperty] private LyricsService.Result? lyrics;
    /// <summary>今のキューの出どころ（プレイリスト・アルバムの ID。ラジオなら null）。画面で「再生中」の印に使う。</summary>
    [ObservableProperty] private string? sourceId;

    public PlayerService(DispatcherQueue ui)
    {
        this.ui = ui;
        var settings = AppServices.Settings;
        volume = settings.Volume;
        shuffle = settings.Shuffle;
        repeat = (RepeatMode)Math.Clamp(settings.Repeat, 0, 2);
        player.Volume = volume;
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
    }

    partial void OnVolumeChanged(double value)
    {
        player.Volume = value;
        AppServices.Settings.Volume = value;
        AppServices.Settings.Save();
    }

    partial void OnRepeatChanged(RepeatMode value)
    {
        AppServices.Settings.Repeat = (int)value;
        AppServices.Settings.Save();
        CursorChanged?.Invoke(true);
    }

    partial void OnIsPlayingChanged(bool value)
    {
        var now = clock.ElapsedMilliseconds;
        if (value) playingSinceMs ??= now;
        else if (playingSinceMs is { } since)
        {
            listenedMs += now - since;
            playingSinceMs = null;
        }
        CursorChanged?.Invoke(true);
    }

    partial void OnCurrentChanged(Song? value) => CursorChanged?.Invoke(true);

    /// <summary>曲の一覧を start から再生する（プレイリスト・アルバム・検索の結果）。shuffle が null なら今の設定のまま。</summary>
    public void PlayList(IReadOnlyList<Song> songs, int start = 0, string? source = null, bool? shuffleOverride = null)
    {
        if (songs.Count == 0) return;
        radioContinuation = null;
        radioPlaylistId = null;
        SourceId = source;
        if (shuffleOverride is { } s && s != Shuffle) SetShuffleField(s);
        unshuffled = songs.ToList();
        var order = songs.ToList();
        var first = Math.Clamp(start, 0, songs.Count - 1);
        if (Shuffle)
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
        ReplaceQueue(order, QueueOrigin.Source);
        SelectAt(first);
        UserStartedPlayback?.Invoke(true);
    }

    /// <summary>1曲から始めて、続きはラジオ（似た曲）にする（Spotify の「曲から再生」と同じ）。</summary>
    public async void PlaySong(Song song)
    {
        SourceId = null;
        unshuffled = [song];
        ReplaceQueue([song], QueueOrigin.Source);
        SelectAt(0);
        UserStartedPlayback?.Invoke(true);
        radioSeed = song.VideoId;
        radioPlaylistId = "RDAMVM" + song.VideoId;
        radioContinuation = null;
        var page = await AppServices.Music.UpNextAsync(song.VideoId, radioPlaylistId);
        if (!page.IsOk || Queue.Count == 0 || Queue[0].VideoId != song.VideoId) return;
        radioContinuation = page.Value!.Continuation;
        radioPlaylistId = page.Value.PlaylistId ?? radioPlaylistId;
        AddAll(page.Value.Songs.Where(s => s.VideoId != song.VideoId), QueueOrigin.Suggestion);
    }

    /// <summary>あとから読めた残りの曲を、まだキューに無い分だけ足す（長いプレイリストを最初のページから鳴らし始めたとき）。</summary>
    public void AppendFrom(IReadOnlyList<Song> songs)
    {
        var known = Queue.Select(s => s.VideoId).ToHashSet();
        var extra = songs.Where(s => known.Add(s.VideoId)).ToList();
        unshuffled = songs.ToList();
        if (Shuffle) Random.Shared.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(extra));
        AddAll(extra, QueueOrigin.Source);
    }

    public void PlayNext(Song song)
    {
        if (Queue.Count == 0)
        {
            PlaySong(song);
            return;
        }
        nextOrigin = QueueOrigin.UserAdded;
        Queue.Insert(Index + 1, song);
        nextOrigin = QueueOrigin.Source;
        Message = L.T($"「{song.Title}」を次に再生します", $"{song.Title} will play next");
    }

    public void AddToQueue(Song song)
    {
        if (Queue.Count == 0)
        {
            PlaySong(song);
            return;
        }
        AddAll([song], QueueOrigin.UserAdded);
        Message = L.T($"「{song.Title}」をキューに追加しました", $"Added {song.Title} to queue");
    }

    public void RemoveAt(int position)
    {
        if (position <= Index || position >= Queue.Count) return;
        Queue.RemoveAt(position);
    }

    /// <summary>キューの曲を選んで鳴らす（本人の選択）。</summary>
    public void PlayAt(int position)
    {
        if (position < 0 || position >= Queue.Count) return;
        SelectAt(position);
        UserStartedPlayback?.Invoke(true);
    }

    private void SelectAt(int position, double seekTo = 0)
    {
        if (position < 0 || position >= Queue.Count) return;
        Index = position;
        Current = Queue[position];
        _ = LoadCurrentAsync(seekTo);
    }

    public void TogglePlay()
    {
        if (Current is null) return;
        if (player.Source is null)
        {
            _ = LoadCurrentAsync();
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
            player.Play();
            UserStartedPlayback?.Invoke(false);
        }
    }

    /// <summary>本人の「次へ」（曲を選んだのと同じ扱い）。</summary>
    public void Next()
    {
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
        if (player.Source is null) return;
        player.PlaybackSession.Position = TimeSpan.FromSeconds(Math.Max(0, seconds));
        Position = seconds;
        CursorChanged?.Invoke(false);
    }

    public void ToggleShuffle()
    {
        SetShuffleField(!Shuffle);
        if (Queue.Count == 0 || Current is null) return;
        var current = Current;
        var upcoming = Queue.Skip(Index + 1).ToList();
        List<Song> rest;
        if (Shuffle)
        {
            rest = upcoming;
            Random.Shared.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(rest));
        }
        else
        {
            // 元の並びのうち、今の曲より後ろのもの（足した曲はそのまま後ろに残す）
            var at = unshuffled.FindIndex(s => s.VideoId == current.VideoId);
            var original = at >= 0 ? unshuffled.Skip(at + 1).ToList() : [];
            var extras = upcoming.Where(u => original.All(o => o.VideoId != u.VideoId)).ToList();
            rest = original.Where(o => upcoming.Any(u => u.VideoId == o.VideoId)).Concat(extras).ToList();
        }
        // 並べ替えでも項目の ID と出どころは保つ
        var metas = Enumerable.Range(Index + 1, Queue.Count - Index - 1).ToDictionary(i => Queue[i], i => itemMeta[i], ReferenceEqualityComparer.Instance);
        while (Queue.Count > Index + 1) Queue.RemoveAt(Queue.Count - 1);
        foreach (var s in rest)
        {
            if (metas.TryGetValue(s, out var meta))
            {
                adoptIds = new([meta.ItemId]);
                nextOrigin = meta.Origin;
            }
            Queue.Add(s);
            nextOrigin = QueueOrigin.Source;
        }
        adoptIds = null;
    }

    private void SetShuffleField(bool value)
    {
        Shuffle = value;
        AppServices.Settings.Shuffle = value;
        AppServices.Settings.Save();
    }

    public void CycleRepeat() => Repeat = Repeat switch
    {
        RepeatMode.Off => RepeatMode.All,
        RepeatMode.All => RepeatMode.One,
        _ => RepeatMode.Off,
    };

    /// <summary>今の曲の高評価を切り替える（ライブラリの「高く評価した曲」に入る）。</summary>
    public async void ToggleLike()
    {
        if (Current is not { } song) return;
        if (!AppServices.Client.IsLoggedIn)
        {
            Message = L.T("高く評価するにはログインしてください", "Log in to like songs");
            return;
        }
        var liked = Like != LikeStatus.Like;
        Like = liked ? LikeStatus.Like : LikeStatus.Indifferent;
        var result = await AppServices.Music.SetLikedAsync(song.VideoId, liked);
        if (!result.IsOk && Current?.VideoId == song.VideoId)
        {
            Like = liked ? LikeStatus.Indifferent : LikeStatus.Like;
            Message = L.T("高評価を変更できませんでした", "Couldn't update your likes");
        }
        else
        {
            Message = liked ? L.T("高く評価した曲に追加しました", "Added to Liked Songs") : L.T("高く評価した曲から削除しました", "Removed from Liked Songs");
            AppServices.Library.LikedChanged();
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
                itemMeta[e.NewStartingIndex] = new QueueItemMeta(Guid.NewGuid().ToString(), nextOrigin);
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
        if (Queue.Count == 0 || Index < 0 || Index >= Queue.Count) return null;
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
        if (Current is null || Index < 0 || Index >= itemMeta.Count) return null;
        var position = player.Source is null ? 0 : (long)(player.PlaybackSession.Position.TotalMilliseconds);
        return new PlaybackCursor(itemMeta[Index].ItemId, Math.Max(0, position), IsPlaying || IsLoading, Repeat, ListenedNow(), statsSent);
    }

    private long ListenedNow() => listenedMs + (playingSinceMs is { } since ? clock.ElapsedMilliseconds - since : 0);

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
        SourceId = queue.SourceId;
        radioSeed = queue.RadioSeed;
        radioContinuation = queue.Continuation;
        radioPlaylistId = queue.RadioPlaylistId;
        unshuffled = queue.Items.Select(i => i.Song).ToList();
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
        return true;
    }

    private (string ItemId, long ListenedMs, bool HistorySent)? seedListened;

    private async Task LoadCurrentAsync(double seekTo = 0)
    {
        loadCancel?.Cancel();
        var cancel = new CancellationTokenSource();
        loadCancel = cancel;
        if (Current is not { } song) return;
        IsLoading = true;
        Like = null;
        Lyrics = null;
        Position = seekTo;
        Duration = song.DurationSeconds ?? 0;
        retried = false;
        // 聴いた時間は曲ごと（引き取った曲は相手の値から続ける。同じ曲の読み直しでは数え直さない）
        var itemId = Index >= 0 && Index < itemMeta.Count ? itemMeta[Index].ItemId : null;
        if (seedListened is { } seed && seed.ItemId == itemId)
        {
            listenedMs = seed.ListenedMs;
            statsSent = seed.HistorySent;
            listenedItemId = itemId;
        }
        else if (itemId != listenedItemId)
        {
            listenedMs = 0;
            statsSent = false;
            listenedItemId = itemId;
        }
        seedListened = null;
        playingSinceMs = IsPlaying ? clock.ElapsedMilliseconds : null;
        player.Pause();
        try
        {
            var stream = await ResolveAsync(song.VideoId, cancel.Token);
            if (cancel.IsCancellationRequested) return;
            if (stream is null)
            {
                IsLoading = false;
                return;
            }
            if (!await StartStreamAsync(song, stream, seekTo))
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

    private async Task<ResolvedStream?> ResolveAsync(string videoId, CancellationToken cancel)
    {
        if (prefetched.Remove(videoId, out var cached) && AppServices.Resolver.IsFresh(cached, FreshMarginMs) && cached.Generation == AppServices.Client.Generation)
            return cached;
        var result = await AppServices.Resolver.ResolveAsync(videoId, cancel);
        if (result.Stream is { } stream) return stream;
        AppPaths.Log($"resolve failed reason={result.Failure}");
        Message = result.Failure switch
        {
            StreamFailure.NotLoggedIn => L.T("この曲を再生するにはログインが必要です", "Log in to play this song"),
            StreamFailure.Unplayable => L.T("この曲は再生できません", "This song isn't available"),
            StreamFailure.Network => L.T("通信できませんでした", "Couldn't connect"),
            _ => L.T($"再生の準備ができませんでした（{result.Failure}）", $"Couldn't prepare playback ({result.Failure})"),
        };
        return null;
    }

    private async Task<bool> StartStreamAsync(Song song, ResolvedStream stream, double seekTo = 0)
    {
        currentStream = stream;
        cpn = PlaybackStats.NewCpn();
        RangeStream bytes;
        try
        {
            bytes = await RangeStream.OpenAsync(AppServices.Http, stream);
        }
        catch (Exception e) when (e is StreamRejectedException or HttpRequestException or IOException)
        {
            AppPaths.Log($"stream open failed type={e.GetType().Name} status={(e as StreamRejectedException)?.Status}");
            return false;
        }
        if (Current?.VideoId != song.VideoId) return false;
        var source = await CreateSourceAsync(song, stream, bytes);
        if (source is null || Current?.VideoId != song.VideoId) return false;
        var item = new MediaPlaybackItem(source);
        var props = item.GetDisplayProperties();
        props.Type = MediaPlaybackType.Music;
        props.MusicProperties.Title = song.Title;
        props.MusicProperties.Artist = song.ArtistsText;
        if (song.Album?.Title is { } album) props.MusicProperties.AlbumTitle = album;
        if (Uri.TryCreate(song.ThumbnailUrl, UriKind.Absolute, out var art)) props.Thumbnail = RandomAccessStreamReference.CreateFromUri(art);
        item.ApplyDisplayProperties(props);
        player.Source = item;
        if (seekTo > 1) player.PlaybackSession.Position = TimeSpan.FromSeconds(seekTo);
        player.Play();
        IsLoading = false;
        AppPaths.Log($"play itag={stream.Itag} queue={Queue.Count} index={Index}");
        return true;
    }

    /// <summary>
    /// YouTube の音声は DASH 用の断片化 MP4 で、Windows のふつうのファイル再生では時刻が読めず一瞬で終わってしまう。
    /// そこで DASH の目次（MPD）をその場で作り、Windows の DASH の再生（AdaptiveMediaSource）に渡す。
    /// 目次には本物の URL を書かず、バイトは再生の部品に求められたぶんだけアプリが取って渡す（署名付き URL は渡さない）。
    /// </summary>
    private static async Task<MediaSource?> CreateSourceAsync(Song song, ResolvedStream stream, RangeStream bytes)
    {
        if (stream.Dash is not { } dash)
        {
            AppPaths.Log("source plain (no dash ranges)");
            return MediaSource.CreateFromStream(bytes.AsRandomAccessStream(), stream.MimeType.Split(';')[0].Trim());
        }
        var placeholder = new Uri($"https://wave.invalid/audio/{song.VideoId}/{stream.Itag}");
        var manifest = new InMemoryRandomAccessStream();
        await manifest.WriteAsync(System.Text.Encoding.UTF8.GetBytes(DashManifest.Build(dash, stream.MimeType, placeholder.ToString())).AsBuffer());
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

    private void OnEnded()
    {
        // 曲の途中で「終わった」と来たら、読み込みの失敗とみなす（次々に飛ばさない）
        var known = Current?.DurationSeconds ?? 0;
        if (known > 10 && Position < known - 5)
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
            listenedMs = 0;
            playingSinceMs = clock.ElapsedMilliseconds;
            cpn = PlaybackStats.NewCpn();
            return;
        }
        if (Index + 1 < Queue.Count || Repeat == RepeatMode.All) Advance();
        else IsPlaying = false;
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
        if (!AppServices.Client.IsLoggedIn)
        {
            IsLoading = false;
            Message = L.T("曲を再生するにはログインしてください（右上のアカウントから）", "Log in to play songs (from the account button at the top right)");
            return;
        }
        if (!retried)
        {
            retried = true;
            AppServices.Resolver.OnStreamRejected();
            prefetched.Remove(song.VideoId);
            var position = Position;
            var stream = await ResolveAsync(song.VideoId, loadCancel?.Token ?? default);
            if (stream is not null && Current?.VideoId == song.VideoId && await StartStreamAsync(song, stream, position))
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
        Message = L.T("再生できなかったので、次の曲に進みます", "Couldn't play this song, skipping to the next one");
        if (Index + 1 < Queue.Count) Advance();
    }

    private void Tick()
    {
        if (player.Source is null) return;
        var session = player.PlaybackSession;
        Position = session.Position.TotalSeconds;
        if (session.NaturalDuration.TotalSeconds > 0) Duration = session.NaturalDuration.TotalSeconds;
        if (!statsSent && IsPlaying && Position >= StatsAfterSeconds && currentStream is { } stream && AppServices.Settings.SendHistory && AppServices.Client.IsLoggedIn)
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

    private async Task LoadSongInfoAsync(Song song, CancellationToken cancel)
    {
        if (!AppServices.Client.IsLoggedIn) return;
        var info = await AppServices.Music.SongInfoAsync(song.VideoId, cancel);
        if (info.IsOk && Current?.VideoId == song.VideoId) Like = info.Value!.Like;
    }

    private async Task LoadLyricsAsync(Song song, CancellationToken cancel)
    {
        var result = await AppServices.Lyrics.LyricsAsync(song, cancel);
        if (Current?.VideoId == song.VideoId) Lyrics = result;
    }

    /// <summary>次の曲の URL を先に解決しておく（曲の切り替えを速くする）。</summary>
    private async void Prefetch()
    {
        if (Index + 1 >= Queue.Count) return;
        var next = Queue[Index + 1];
        if (prefetched.ContainsKey(next.VideoId)) return;
        var result = await AppServices.Resolver.ResolveAsync(next.VideoId);
        if (result.Stream is { } s)
        {
            prefetched[next.VideoId] = s;
            while (prefetched.Count > 4) prefetched.Remove(prefetched.Keys.First());
        }
    }

    /// <summary>ラジオの残りが少なくなったら続きを足す。</summary>
    private async void ExtendRadioIfNeeded()
    {
        if (extending || radioContinuation is null || Queue.Count - Index > 3) return;
        extending = true;
        try
        {
            var page = await AppServices.Music.UpNextAsync(null, radioPlaylistId, radioContinuation);
            if (!page.IsOk) return;
            radioContinuation = page.Value!.Continuation;
            var known = Queue.Select(s => s.VideoId).ToHashSet();
            AddAll(page.Value.Songs.Where(s => known.Add(s.VideoId)).ToList(), QueueOrigin.Suggestion);
        }
        finally
        {
            extending = false;
        }
    }

    /// <summary>ログアウトのとき。再生を止めてキューを空にする（別のアカウントの URL を使い続けない）。</summary>
    public void Stop()
    {
        loadCancel?.Cancel();
        player.Pause();
        player.Source = null;
        prefetched.Clear();
        Queue.Clear();
        Current = null;
        Index = -1;
        IsPlaying = false;
        Like = null;
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

public sealed record PlaybackCursor(string CurrentItemId, long PositionMs, bool Playing, RepeatMode Repeat, long ListenedMs, bool HistorySent);
