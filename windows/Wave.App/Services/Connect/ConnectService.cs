using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.UI.Dispatching;
using Wave.Core.Connect;
using Wave.Core.Music;

namespace Wave.App.Services.Connect;

/// <summary>
/// 端末の連携（Connect v2）の Windows 側の調停役（Android の ConnectV2Manager と同じ役割）。
/// ConnectClient を1本の作業スレッドに閉じ込め、中継 v2・再生サービス・画面をつなぐ。再生サービスの操作は UI のスレッドで行う。
/// ログは種類と件数だけ（端末 ID・本文・アカウントの識別子は出さない）。
/// </summary>
public sealed class ConnectService
{
    public const string RemotePlaying = "remote_playing";
    public const string RelayUnreachable = "relay_unreachable";
    public const string NotMemberPlay = "not_member_play";
    public const string AccountUnverifiedPlay = "account_unverified_play";
    public const string HouseholdCreateUnsent = "household_create_unsent";

    private const int TickMs = 1_000;
    private const int CursorDebounceMs = 1_000;
    private const int QueueDebounceMs = 1_000;
    private const int SettleWaitMs = 10_000;
    private const int MaxLabelChars = 64;
    /// <summary>キューの doc の handoffId（Android の V2_SNAPSHOT_ID と同じ。中身の同じキューは同じ文字列になる）。</summary>
    private const string SnapshotId = "00000000-0000-4000-8000-000000000000";
    private static readonly byte[] StorageEntropy = Encoding.UTF8.GetBytes("xyz.mooma0750.wave/connect-v2/v1");

    private readonly DispatcherQueue ui;
    private readonly PlayerService player;
    private readonly string storagePath = Path.Combine(AppPaths.Data, "connect-v2.bin");
    private readonly string labelsPath = Path.Combine(AppPaths.Data, "connect-v2-labels.json");
    private readonly Stopwatch clock = Stopwatch.StartNew();
    private readonly BlockingCollection<Action> work = new();
    private readonly SoftwareDevice? keys;
    private ConnectClient? client;

    public RelayConnection? Relay { get; }

    /// <summary>中継の場所が設定されていて、端末の鍵が読めた。</summary>
    public bool Configured => Relay is not null;

    /// <summary>端末の鍵か保存が読めない（作り直すまで使わない）。</summary>
    public bool Unreadable { get; private set; }

    // 画面に出す値（作業スレッドで作り、UI のスレッドで読む。参照ごと置き換える）
    public ConnectInfo Info { get; private set; } = new();
    public IReadOnlyList<MemberView> Members { get; private set; } = [];
    public RemoteView? Remote { get; private set; }
    public PairingView PairingState { get; private set; } = PairingView.Idle;

    /// <summary>画面の値が変わった（UI のスレッドで呼ぶ）。</summary>
    public event Action? Changed;

    /// <summary>知らせ（UI のスレッドで呼ぶ）。</summary>
    public event Action<string>? Notice;

    /// <summary>画面が前面にあるか（UI のスレッドで呼ぶ）。裏でメディアキーを押したときに、ほかの端末の再生を黙って引き取らない（D-9）。</summary>
    public Func<bool> IsForeground { get; set; } = () => true;

    private Dictionary<string, string> labels = new();

    private sealed record TagState(string HouseholdId, string Tag, long Generation);

    private volatile TagState? tagState;
    private int tagComputing;
    private long? seenGeneration;
    private long playIntent;
    private CancellationTokenSource? waitCancel;
    private (string ItemId, bool Playing, HandoffRepeat Repeat)? lastSignificant;
    private DispatcherQueueTimer? cursorTimer;
    private DispatcherQueueTimer? queueTimer;
    private volatile bool writer;

    // 加入
    private CancellationTokenSource? pairingCancel;
    private System.Threading.Channels.Channel<string>? codes;
    private Pairing.Established? pendingOnB;
    private readonly System.Threading.Channels.Channel<RelayV2.PairHello> pairHellos = System.Threading.Channels.Channel.CreateUnbounded<RelayV2.PairHello>();
    private TaskCompletionSource<RelayV2>? chainReply;
    private TaskCompletionSource<ClientEvent>? joinWaiter;

    public ConnectService(DispatcherQueue ui, PlayerService player, string? relayUrl, string? relayHost)
    {
        this.ui = ui;
        this.player = player;
        new Thread(() =>
        {
            foreach (var action in work.GetConsumingEnumerable())
            {
                try
                {
                    action();
                }
                catch (Exception e)
                {
                    AppPaths.Log($"connect-v2 failed type={e.GetType().Name}");
                }
            }
        }) { IsBackground = true, Name = "connect-v2" }.Start();
        if (string.IsNullOrWhiteSpace(relayUrl) || string.IsNullOrWhiteSpace(relayHost)) return;
        var (state, device) = DeviceKeys.LoadOrCreate();
        AppPaths.Log($"connect-v2 keys state={state}");
        if (state == DeviceKeys.LoadState.Created)
        {
            // 新しい鍵（端末 ID が変わる）: 前の取り出せる鍵のファイルと、その端末 ID で入っていた家の記録は使えないので消す（加入し直す）
            DeviceKeys.DeleteLegacyFile(Path.Combine(AppPaths.Data, "connect-keys.bin"));
            try
            {
                if (File.Exists(storagePath)) File.Delete(storagePath);
            }
            catch (IOException)
            {
            }
        }
        if (device is null)
        {
            Unreadable = true;
            return;
        }
        keys = device;
        Relay = new RelayConnection(relayUrl, relayHost, device.IdentityPub, device.Sign, Deliver, () => Post(() => client?.OnDisconnected()));
        Relay.StatusChanged += _ => Post(RefreshInfo);
        Post(Load);
        player.QueueChanged += OnQueueChanged;
        player.CursorChanged += OnCursorChanged;
        player.UserStartedPlayback += OnUserStartedPlayback;
        player.UserPaused += () => Interlocked.Increment(ref playIntent);
        player.SendRemoteControl = (action, positionMs) =>
        {
            if (client is null || Relay.Status != RelayStatus.Connected) return false;
            Post(() => AppPaths.Log($"connect-v2 control action={action} sent={client?.SendControl(action, positionMs)}"));
            return true;
        };
        // 接続に関係なく回す（transfer の期限・アカウントタグの計算）
        _ = Task.Run(async () =>
        {
            while (true)
            {
                Post(() =>
                {
                    if (client is not { } engine) return;
                    CheckLoginGeneration(engine);
                    engine.Tick();
                    RefreshInfo();
                    if (engine.HouseholdId is { } household) RefreshAccountTagIfNeeded(household);
                });
                await Task.Delay(TickMs);
            }
        });
        // PC は画面がある間ずっと中継につなぐ（家に入っていなければ、中継は認証で断る）
        Relay.Acquire();
    }

    private void Post(Action action)
    {
        if (!work.IsAddingCompleted) work.Add(action);
    }

    private Task<T> OnEngine<T>(Func<T> func)
    {
        var done = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        Post(() =>
        {
            try
            {
                done.SetResult(func());
            }
            catch (Exception e)
            {
                done.SetException(e);
            }
        });
        return done.Task;
    }

    /// <summary>UI のスレッドで走らせて待つ（作業スレッドからだけ呼ぶ。UI のスレッドは作業スレッドを待たない）。</summary>
    private T OnUi<T>(Func<T> func)
    {
        var done = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!ui.TryEnqueue(() =>
            {
                try
                {
                    done.SetResult(func());
                }
                catch (Exception e)
                {
                    done.SetException(e);
                }
            }))
            return default!;
        return done.Task.GetAwaiter().GetResult();
    }

    private void Deliver(string text)
    {
        if (RelayV2.Decode(text) is not { } message) return;
        switch (message)
        {
            case RelayV2.PairHello hello:
                pairHellos.Writer.TryWrite(hello);
                return;
            // 鎖の追記への返事（state_put への error は requestId が付くので除く）
            case RelayV2.ChainOk or RelayV2.ChainConflict or RelayV2.Error { RequestId: null }:
                chainReply?.TrySetResult(message);
                break;
        }
        Post(() => client?.OnMessage(message));
    }

    private bool Send(RelayV2 message) => Relay?.Send(RelayV2.Encode(message)) == true;

    // --- 保存 ---

    private void Load()
    {
        LoadLabels();
        ClientPersisted initial;
        if (!File.Exists(storagePath))
        {
            initial = new ClientPersisted();
        }
        else
        {
            try
            {
                var plain = ProtectedData.Unprotect(File.ReadAllBytes(storagePath), StorageEntropy, DataProtectionScope.CurrentUser);
                initial = ConnectJson.Decode<ClientPersisted>(plain) ?? throw new CryptographicException("unreadable");
            }
            catch (Exception e) when (e is CryptographicException or IOException or UnauthorizedAccessException or JsonException)
            {
                Unreadable = true;
                RefreshInfo();
                return;
            }
        }
        client = new ConnectClient(keys!, keys!, Role.Player, Send, new Effects(this), () => clock.ElapsedMilliseconds, () => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), () => Guid.NewGuid().ToString(), initial);
        seenGeneration = AppServices.Client.Generation;
        RefreshInfo();
    }

    private void Save(ClientPersisted state)
    {
        try
        {
            var temp = storagePath + ".tmp";
            File.WriteAllBytes(temp, ProtectedData.Protect(ConnectJson.EncodeToUtf8(state), StorageEntropy, DataProtectionScope.CurrentUser));
            File.Move(temp, storagePath, overwrite: true);
        }
        catch (Exception e) when (e is CryptographicException or IOException or UnauthorizedAccessException)
        {
            AppPaths.Log($"connect-v2 persist failed type={e.GetType().Name}");
        }
    }

    private void LoadLabels()
    {
        try
        {
            labels = File.Exists(labelsPath) ? JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(labelsPath)) ?? new() : new();
        }
        catch (Exception e) when (e is JsonException or IOException)
        {
            labels = new();
        }
    }

    private void RememberLabel(string deviceId, string name) => Post(() =>
    {
        labels[deviceId] = name.Length > MaxLabelChars ? name[..MaxLabelChars] : name;
        try
        {
            File.WriteAllText(labelsPath + ".tmp", JsonSerializer.Serialize(labels));
            File.Move(labelsPath + ".tmp", labelsPath, overwrite: true);
        }
        catch (IOException)
        {
        }
        RefreshInfo();
    });

    private string LabelOf(string deviceId) =>
        deviceId == keys?.DeviceId ? L.T("この PC", "This PC")
        : labels.TryGetValue(deviceId, out var name) ? name
        : L.T($"端末 {deviceId[..Math.Min(6, deviceId.Length)]}", $"Device {deviceId[..Math.Min(6, deviceId.Length)]}");

    // --- 画面の値 ---

    private void RefreshInfo()
    {
        var engine = client;
        writer = engine?.IsWriter == true;
        var online = engine?.Online ?? new HashSet<string>();
        var active = engine?.ActiveDeviceId;
        var info = new ConnectInfo(
            Configured,
            engine is not null,
            Unreadable,
            engine?.HouseholdId is not null,
            engine?.IsWriter == true,
            engine?.HasRetreated == true,
            engine?.StoreResetPending == true,
            Relay?.Status ?? RelayStatus.Disconnected);
        var members = (engine?.Members ?? []).Select(m => new MemberView(m.DeviceId, LabelOf(m.DeviceId), m.DeviceId == keys?.DeviceId, m.Role == Role.Controller, online.Contains(m.DeviceId), m.DeviceId == active)).ToList();
        RemoteView? remote = null;
        if (engine?.LatestCursorBody() is { } latest && latest.Writer != keys?.DeviceId)
        {
            var tag = tagState?.Tag;
            HandoffItem? item = null;
            if (engine.LatestQueueBody() is { } queue && queue.Writer == latest.Writer && ConnectJson.Decode<HandoffSnapshot>(queue.Queue.Snapshot) is { } snapshot)
                item = snapshot.Items.FirstOrDefault(i => i.ItemId == latest.Cursor.CurrentItemId);
            remote = new RemoteView(latest.Writer, LabelOf(latest.Writer), item?.Title, item is null ? null : string.Join(", ", item.Artists.Select(a => a.Name)), item?.ThumbnailUrl, item?.DurationSeconds,
                latest.Cursor.Playing, latest.Cursor.PositionMs, tag is not null && latest.Cursor.AccountTag == tag, online.Contains(latest.Writer));
        }
        var mirror = MirrorOf(engine, remote);
        ui.TryEnqueue(() =>
        {
            Info = info;
            Members = members;
            Remote = remote;
            player.ShowRemote(mirror);
            Changed?.Invoke();
        });
    }

    private string? mirroredSnapshot;
    private QueueExport? mirroredQueue;
    private string? mirroredKey;

    /// <summary>下のバーに映す、ほかの端末の再生（同じアカウントで、その端末が中継につながっているときだけ）。</summary>
    private RemoteSnapshot? MirrorOf(ConnectClient? engine, RemoteView? remote)
    {
        if (engine is null || engine.IsWriter || remote is not { AccountMatches: true, Online: true }) return null;
        if (engine.RemoteNowPosition() is not { } now || now.Writer != remote.DeviceId) return null;
        if (engine.LatestQueueBody() is not { } queue || queue.Writer != now.Writer) return null;
        if (!ReferenceEquals(queue.Queue.Snapshot, mirroredSnapshot))
        {
            mirroredSnapshot = queue.Queue.Snapshot;
            mirroredQueue = DecodeQueue(queue.Queue.Snapshot) is { } decoded ? ToExport(decoded) : null;
            mirroredKey = Guid.NewGuid().ToString();
        }
        if (mirroredQueue is not { } export || export.Items.All(i => i.Meta.ItemId != now.Cursor.CurrentItemId)) return null;
        var duration = export.Items.First(i => i.Meta.ItemId == now.Cursor.CurrentItemId).Song.DurationSeconds;
        return new RemoteSnapshot(remote.DeviceLabel, mirroredKey, export, now.Cursor.CurrentItemId, now.PositionMs, player.ClockMs, now.Cursor.Playing, RepeatOf(now.Cursor.Repeat), duration);
    }

    private void SetPairing(PairingView view) => ui.TryEnqueue(() =>
    {
        PairingState = view;
        Changed?.Invoke();
    });

    private void Notify(string text) => ui.TryEnqueue(() => Notice?.Invoke(text));

    // --- ConnectClient の外の作用 ---

    private sealed class Effects(ConnectService owner) : ConnectClient.IEffects
    {
        public ConnectClient.LocalCursor? Pause() => owner.OnUi(() =>
        {
            owner.player.ConnectPause();
            return owner.player.CurrentCursor() is { } cursor ? owner.ToLocal(cursor) : null;
        });

        public bool Adopt(string snapshot, CursorBody cursor, long positionMs)
        {
            var state = owner.tagState;
            if (state is null || cursor.AccountTag != state.Tag) return false;
            if (DecodeQueue(snapshot) is not { } decoded || AtCursor(decoded, cursor, positionMs) is not { } adjusted) return false;
            var export = ToExport(adjusted);
            return owner.OnUi(() =>
                AppServices.Client.Generation == state.Generation &&
                owner.player.ConnectAdopt(export, adjusted.PositionMs, RepeatOf(adjusted.Repeat), adjusted.ListenedMs, adjusted.HistorySent));
        }

        public void Event(ClientEvent clientEvent)
        {
            AppPaths.Log($"connect-v2 event={clientEvent.GetType().Name}");
            owner.OnEvent(clientEvent);
            owner.RefreshInfo();
        }

        public void Persist(ClientPersisted state)
        {
            owner.Save(state);
            owner.RefreshInfo();
        }

        public bool Control(ControlAction action, long? positionMs)
        {
            AppPaths.Log($"connect-v2 control received action={action}");
            return owner.OnUi(() => owner.player.ApplyControl(action, positionMs));
        }

        public string? AccountTag() =>
            owner.tagState is { } state && state.Generation == AppServices.Client.Generation && state.HouseholdId == owner.client?.HouseholdId ? state.Tag : null;
    }

    private void OnEvent(ClientEvent e)
    {
        if (e is ClientEvent.Joined or ClientEvent.ChainForked or ClientEvent.ChainRolledBack or ClientEvent.ChainInvalid) joinWaiter?.TrySetResult(e);
        var text = e switch
        {
            ClientEvent.Stopped stopped => L.T($"{LabelOf(stopped.By)}で再生を始めたので、ここでは止めました", $"Paused here because {LabelOf(stopped.By)} started playing"),
            ClientEvent.TransferCompleted from => L.T($"{LabelOf(from.From)}から引き継ぎました", $"Took over from {LabelOf(from.From)}"),
            ClientEvent.TransferFailed => L.T("引き継げませんでした", "Couldn’t take over playback"),
            ClientEvent.StoreReset => L.T("中継の状態が作り直されました。「端末の連携」で確かめてください", "The relay’s state was reset. Check Devices"),
            ClientEvent.ChainForked or ClientEvent.ChainRolledBack or ClientEvent.ChainInvalid => L.T("中継から届いた端末の一覧を確かめられませんでした", "Couldn’t verify the device list from the relay"),
            ClientEvent.StateEquivocation => L.T("中継から食い違った状態が届きました", "The relay sent conflicting state"),
            ClientEvent.ServerError { Code: RemotePlaying } => L.T("ほかの端末で再生中なので、ここでは鳴らしませんでした", "Another device is playing, so playback didn’t start here"),
            ClientEvent.ServerError { Code: RelayUnreachable } => L.T("中継につながらず、ほかの端末の再生を確かめられませんでした", "Couldn’t reach the relay to check your other devices"),
            ClientEvent.ServerError { Code: NotMemberPlay } => L.T("中継がこの PC を連携した端末として受け付けませんでした", "The relay didn’t accept this PC as one of your devices"),
            ClientEvent.ServerError { Code: AccountUnverifiedPlay } => L.T("アカウントを確かめられませんでした", "Couldn’t verify your account"),
            ClientEvent.ServerError { Code: HouseholdCreateUnsent } => L.T("中継に送れませんでした（この PC では新しく始められない設定かもしれません）", "Couldn’t reach the relay (this PC may not be allowed to start a new group)"),
            _ => null,
        };
        if (text is not null) Notify(text);
    }

    // --- アカウントタグ ---

    private void RefreshAccountTagIfNeeded(string householdId)
    {
        var current = tagState;
        if (current is not null && current.HouseholdId == householdId && current.Generation == AppServices.Client.Generation) return;
        if (!AppServices.Client.IsLoggedIn) return;
        if (Interlocked.Exchange(ref tagComputing, 1) == 1) return;
        _ = Task.Run(async () =>
        {
            try
            {
                if (await AppServices.Music.VerifiedAccountAsync() is not { } account) return;
                var next = new TagState(householdId, AccountTag.Of(householdId, account.Id), account.Generation);
                Post(() => OnTagComputed(next));
            }
            catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
            {
            }
            finally
            {
                Interlocked.Exchange(ref tagComputing, 0);
            }
        });
    }

    private void CheckLoginGeneration(ConnectClient engine)
    {
        var generation = AppServices.Client.Generation;
        var previous = seenGeneration;
        seenGeneration = generation;
        if (previous is null || previous == generation) return;
        var finalTag = tagState is { } state && state.Generation == previous ? state.Tag : null;
        tagState = null;
        engine.EndLocal(finalTag);
        AppPaths.Log("connect-v2 login changed ended local");
    }

    private void OnTagComputed(TagState state)
    {
        if (client is not { } engine) return;
        CheckLoginGeneration(engine);
        if (state.HouseholdId != engine.HouseholdId || state.Generation != AppServices.Client.Generation) return;
        tagState = state;
        if (!engine.IsWriter) LoadLocalFromPlayer(engine);
        engine.OnAccountTagAvailable();
        RefreshInfo();
    }

    // --- 再生サービスからの知らせ（UI のスレッド） ---

    private void OnQueueChanged()
    {
        // 書き手でなければ書き出さない（再生を始めたときに書き出す）。続けて変わるときは1秒に間引く
        if (!writer) return;
        queueTimer ??= Debounce(QueueDebounceMs, () =>
        {
            if (Snapshot() is { } text) Post(() => client?.SetLocalQueue(text));
        });
        queueTimer.Start();
    }

    private void OnCursorChanged(bool significant)
    {
        // ほかの端末を映している間は、手元の再生の変化ではない
        if (player.IsRemote) return;
        cursorTimer ??= Debounce(CursorDebounceMs, PushCursor);
        cursorTimer.Stop();
        var key = player.CurrentCursor() is { } c ? (c.CurrentItemId, c.Playing, RepeatOf(c.Repeat)) : ((string, bool, HandoffRepeat)?)null;
        if (significant && key != lastSignificant)
        {
            lastSignificant = key;
            PushCursor();
        }
        else
        {
            cursorTimer.Start();
        }
    }

    private DispatcherQueueTimer Debounce(int ms, Action action)
    {
        var timer = ui.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(ms);
        timer.IsRepeating = false;
        timer.Tick += (_, _) => action();
        return timer;
    }

    private void PushCursor()
    {
        var cursor = player.CurrentCursor() is { } c ? ToLocal(c) : null;
        Post(() =>
        {
            if (client is not { } engine) return;
            if (cursor is not null) engine.SetLocalCursor(cursor);
            else engine.EndLocal(new Effects(this).AccountTag());
            AppPaths.Log($"connect-v2 cursor playing={cursor?.Playing} writer={engine.IsWriter}");
        });
    }

    private void OnUserStartedPlayback(bool newSelection)
    {
        var intent = Interlocked.Increment(ref playIntent);
        var foreground = IsForeground();
        Post(() =>
        {
            if (client is not { } engine) return;
            waitCancel?.Cancel();
            waitCancel = null;
            var effects = new Effects(this);
            var decision = PlayButtonPolicy.OnPlay(newSelection, engine.HasRetreated, engine.HouseholdId is not null, engine.StateSettled, effects.AccountTag() is not null, engine.RemotePlayingNow(), foreground);
            if (decision == PlayButtonPolicy.Decision.Wait)
            {
                ui.TryEnqueue(player.ConnectPause);
                var cancel = new CancellationTokenSource();
                waitCancel = cancel;
                _ = DecideAfterWaitAsync(intent, foreground, cancel.Token);
                return;
            }
            Apply(engine, decision);
        });
    }

    private void Apply(ConnectClient engine, PlayButtonPolicy.Decision decision)
    {
        switch (decision)
        {
            case PlayButtonPolicy.Decision.Claim:
                ClaimLocal(engine);
                break;
            case PlayButtonPolicy.Decision.ResumeAndClaim:
                OnUi(() =>
                {
                    player.ConnectResume();
                    return true;
                });
                ClaimLocal(engine);
                break;
            case PlayButtonPolicy.Decision.TakeOver:
                ui.TryEnqueue(player.ConnectPause);
                if (engine.RequestTransfer()) AppPaths.Log("connect-v2 play button became transfer");
                else OnEvent(new ClientEvent.ServerError(RemotePlaying));
                break;
            case PlayButtonPolicy.Decision.BlockRemotePlaying:
                ui.TryEnqueue(player.ConnectPause);
                OnEvent(new ClientEvent.ServerError(RemotePlaying));
                break;
            case PlayButtonPolicy.Decision.NotifyNotMember:
                OnEvent(new ClientEvent.ServerError(NotMemberPlay));
                break;
            case PlayButtonPolicy.Decision.NotifyUnreachable:
                OnEvent(new ClientEvent.ServerError(RelayUnreachable));
                break;
            case PlayButtonPolicy.Decision.NotifyUnverified:
                OnEvent(new ClientEvent.ServerError(AccountUnverifiedPlay));
                break;
        }
    }

    /// <summary>中継の最新の状態とアカウントタグがそろうまで（最大 10 秒）待ってから、D-9 の判断をする。</summary>
    private async Task DecideAfterWaitAsync(long intent, bool foreground, CancellationToken cancel)
    {
        var relay = Relay!;
        relay.Acquire();
        try
        {
            PlayButtonPolicy.WaitOutcome? outcome = null;
            var deadline = Stopwatch.StartNew();
            while (outcome is null && deadline.ElapsedMilliseconds < SettleWaitMs)
            {
                var status = relay.Status;
                var ready = await OnEngine(() => client is { } c && c.StateSettled && new Effects(this).AccountTag() is not null);
                outcome = PlayButtonPolicy.WaitStep(status == RelayStatus.NotMember, status == RelayStatus.TailnetUnreachable, () => ready);
                if (outcome is null) await Task.Delay(100, cancel);
            }
            outcome ??= await OnEngine(() => PlayButtonPolicy.TimeoutOutcome(client?.StateSettled == true));
            if (cancel.IsCancellationRequested) return;
            var final = outcome.Value;
            Post(() =>
            {
                if (client is not { } engine) return;
                var decision = PlayButtonPolicy.AfterWait(final, Interlocked.Read(ref playIntent) == intent, engine.HasRetreated, engine.RemotePlayingNow(), foreground);
                Apply(engine, decision);
                AppPaths.Log($"connect-v2 play button waited outcome={final} decision={decision}");
            });
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            relay.Release();
        }
    }

    private void LoadLocalFromPlayer(ConnectClient engine)
    {
        var (snapshot, cursor) = OnUi(() => (Snapshot(), player.CurrentCursor() is { } c ? ToLocal(c) : null));
        if (snapshot is not null) engine.SetLocalQueue(snapshot);
        if (cursor is not null) engine.SetLocalCursor(cursor);
    }

    private void ClaimLocal(ConnectClient engine)
    {
        LoadLocalFromPlayer(engine);
        var claimed = engine.UserStartedPlayback();
        RefreshInfo();
        AppPaths.Log($"connect-v2 user started claimed={claimed}");
    }

    private ConnectClient.LocalCursor ToLocal(PlaybackCursor c) =>
        new(c.CurrentItemId, c.PositionMs, c.Playing, RepeatOf(c.Repeat), c.ListenedMs, c.HistorySent, clock.ElapsedMilliseconds);

    // --- キューの書き出し・取り込み ---

    /// <summary>今のキューの doc の文字列（UI のスレッドで呼ぶ）。中身が同じなら同じ文字列にするため、位置・履歴・時刻は入れない。</summary>
    private string? Snapshot()
    {
        if (!AppServices.Client.IsLoggedIn || player.ExportQueue() is not { } queue) return null;
        var index = queue.Items.FindIndex(i => i.Meta.ItemId == queue.CurrentItemId);
        if (index < 0) return null;
        var items = queue.Items.Where(i => ValidVideoId(i.Song.VideoId) && i.Song.Title.Length > 0).ToList();
        index = items.FindIndex(i => i.Meta.ItemId == queue.CurrentItemId);
        if (index < 0) return null;
        var (window, windowIndex, truncated) = HandoffCodec.Window(items, index);
        HandoffSource? source = queue.RadioSeed is { } seed ? new HandoffSource.Radio { SeedVideoId = seed }
            : queue.SourceId is { } id && System.Text.RegularExpressions.Regex.IsMatch(id, @"\A[A-Za-z0-9_-]{2,128}\z") ? new HandoffSource.Playlist { PlaylistId = id }
            : null;
        var snapshot = new HandoffSnapshot
        {
            SchemaVersion = HandoffCodec.SchemaVersion,
            HandoffId = SnapshotId,
            Items = window.Select(i => ItemOf(i.Song, i.Meta)).ToList(),
            CurrentItemId = window[windowIndex].Meta.ItemId,
            PositionMs = 0,
            Playing = false,
            Repeat = HandoffRepeat.Off,
            Source = source,
            Continuation = truncated ? null : queue.Continuation,
            RadioPlaylistId = queue.RadioPlaylistId,
            // プレイリスト・アルバムは全部を読み込んでから鳴らすので、続きは無い
            Exhausted = queue.RadioSeed is null && !truncated,
            ListenedMs = 0,
            HistorySent = false,
            CapturedAtEpochMs = 0,
        };
        return HandoffCodec.Validate(snapshot, SnapshotId) is HandoffDecodeResult.Ok ? HandoffCodec.Encode(snapshot) : null;
    }

    private static bool ValidVideoId(string id) => System.Text.RegularExpressions.Regex.IsMatch(id, @"\A[A-Za-z0-9_-]{11}\z");

    private static HandoffItem ItemOf(Song song, QueueItemMeta meta) => new()
    {
        ItemId = meta.ItemId,
        VideoId = song.VideoId,
        Title = song.Title.Length > 500 ? song.Title[..500] : song.Title,
        Artists = song.Artists.Where(a => a.Name.Length is > 0 and <= 500).Take(20).Select(a => new HandoffRef { Name = a.Name, BrowseId = ValidBrowseId(a.BrowseId) }).ToList(),
        Album = song.Album is { Title.Length: > 0 and <= 500 } album ? new HandoffRef { Name = album.Title, BrowseId = ValidBrowseId(album.BrowseId) } : null,
        DurationSeconds = song.DurationSeconds is >= 0 and <= 24 * 3600 ? song.DurationSeconds : null,
        ThumbnailUrl = song.ThumbnailUrl,
        Origin = meta.Origin switch
        {
            QueueOrigin.Suggestion => HandoffOrigin.Suggestion,
            QueueOrigin.UserAdded => HandoffOrigin.UserAdded,
            _ => HandoffOrigin.Source,
        },
    };

    private static string? ValidBrowseId(string? id) => id is not null && System.Text.RegularExpressions.Regex.IsMatch(id, @"\A[A-Za-z0-9_-]{2,64}\z") ? id : null;

    private static HandoffSnapshot? DecodeQueue(string text)
    {
        var id = ConnectJson.Decode<HandoffSnapshot>(text)?.HandoffId;
        return id is not null && HandoffCodec.Decode(text, id) is HandoffDecodeResult.Ok ok ? ok.Snapshot : null;
    }

    /// <summary>キューの doc を、カーソルの今の曲・リピート・履歴に合わせる（Android の RemoteQueue.atCursor と同じ）。</summary>
    private static HandoffSnapshot? AtCursor(HandoffSnapshot snapshot, CursorBody cursor, long positionMs)
    {
        var items = snapshot.Items.ToList();
        var index = items.FindIndex(i => i.ItemId == cursor.CurrentItemId);
        if (index < 0) return null;
        var (window, _, truncatedTail) = HandoffCodec.Window(items, index);
        var moved = snapshot with
        {
            Items = window.ToList(),
            Continuation = truncatedTail ? null : snapshot.Continuation,
            Exhausted = !truncatedTail && snapshot.Exhausted,
            CurrentItemId = cursor.CurrentItemId,
            PositionMs = Math.Max(0, positionMs),
            Repeat = cursor.Repeat,
            ListenedMs = Math.Max(0, cursor.ListenedMs),
            HistorySent = cursor.HistorySent,
        };
        return HandoffCodec.Validate(moved, moved.HandoffId) is HandoffDecodeResult.Ok ok ? ok.Snapshot : null;
    }

    private static QueueExport ToExport(HandoffSnapshot snapshot) => new(
        snapshot.Items.Select(i => (
            new Song(i.VideoId, i.Title, i.Artists.Select(a => new ArtistRef(a.Name, a.BrowseId)).ToList(), i.Album is { } album ? new AlbumRef(album.Name, album.BrowseId) : null, i.DurationSeconds, i.ThumbnailUrl),
            new QueueItemMeta(i.ItemId, i.Origin switch
            {
                HandoffOrigin.Suggestion => QueueOrigin.Suggestion,
                HandoffOrigin.UserAdded => QueueOrigin.UserAdded,
                _ => QueueOrigin.Source,
            }))).ToList(),
        snapshot.CurrentItemId,
        (snapshot.Source as HandoffSource.Playlist)?.PlaylistId,
        (snapshot.Source as HandoffSource.Radio)?.SeedVideoId,
        // プレイリストの続き（Android が途中まで読んだもの）は、Windows のラジオの続きとは別物なので使わない
        snapshot.Source is HandoffSource.Playlist || snapshot.Exhausted ? null : snapshot.Continuation,
        snapshot.RadioPlaylistId);

    private static HandoffRepeat RepeatOf(RepeatMode mode) => mode switch
    {
        RepeatMode.One => HandoffRepeat.One,
        RepeatMode.All => HandoffRepeat.All,
        _ => HandoffRepeat.Off,
    };

    private static RepeatMode RepeatOf(HandoffRepeat repeat) => repeat switch
    {
        HandoffRepeat.One => RepeatMode.One,
        HandoffRepeat.All => RepeatMode.All,
        _ => RepeatMode.Off,
    };

    // --- 画面からの操作 ---

    /// <summary>「この PC で再生」。</summary>
    public void Transfer() => Post(() => AppPaths.Log($"connect-v2 transfer requested={client?.RequestTransfer()}"));

    public void AcceptStoreReset() => Post(() => client?.AcceptStoreReset());

    /// <summary>起動時の設定の鍵の端末で、家を作る（家が無いときだけ中継が受ける）。</summary>
    public void CreateHousehold() => Post(() =>
    {
        if (client is not { } engine || engine.HouseholdId is not null || keys is null) return;
        engine.BeginCreateHousehold();
        var self = keys.MemberKeys(Role.Player);
        Relay!.Reconnect();
        var sent = Send(new RelayV2.HouseholdCreate { Genesis = MemberChain.Genesis(keys, self) }) && Send(new RelayV2.ChainGet { FromSeq = 0 });
        if (!sent) OnEvent(new ClientEvent.ServerError(HouseholdCreateUnsent));
        AppPaths.Log($"connect-v2 household create sent={sent}");
    });

    /// <summary>家から外された端末で、本人が押したら、この端末の家の記録を消す。</summary>
    public void ForgetHousehold() => Post(() =>
    {
        client?.ClearLocal();
        try
        {
            File.Delete(storagePath);
        }
        catch (IOException)
        {
        }
        tagState = null;
        seenGeneration = null;
        Unreadable = false;
        Load();
        Relay?.Reconnect();
        AppPaths.Log("connect-v2 household forgotten");
    });

    /// <summary>家から他の端末を外す。@return 外したか</summary>
    public async Task<bool> RemoveMemberAsync(string deviceId)
    {
        if (deviceId == keys?.DeviceId) return false;
        var record = await OnEngine(() => client?.VerifiedChain() is { } chain && chain.Members.ContainsKey(deviceId) ? MemberChain.Remove(keys!, chain, deviceId) : null);
        if (record is null) return false;
        var removed = await AppendRecordAsync(record, members => members.All(m => m.DeviceId != deviceId));
        AppPaths.Log($"connect-v2 remove member ok={removed}");
        return removed;
    }

    private async Task<bool> AppendRecordAsync(ChainRecord record, Func<IReadOnlyList<MemberKeys>, bool> done)
    {
        var reply = new TaskCompletionSource<RelayV2>(TaskCreationOptions.RunContinuationsAsynchronously);
        chainReply = reply;
        if (!Send(new RelayV2.ChainAppend { Record = record })) return false;
        var finished = await Task.WhenAny(reply.Task, Task.Delay(10_000));
        if (finished == reply.Task)
        {
            if (reply.Task.Result is RelayV2.ChainOk) return true;
            if (reply.Task.Result is not RelayV2.Error { RequestId: not null }) return false;
        }
        // 返事が来なかった: 鎖を取り直して、今の端末一覧で済んでいるかを見る（やり直すと二重の記録になる）
        Send(new RelayV2.ChainGet { FromSeq = 0 });
        for (var i = 0; i < 25; i++)
        {
            if (await OnEngine(() => client is { } c && done(c.Members))) return true;
            await Task.Delay(200);
        }
        return false;
    }

    // --- 加入（A: 家の端末が QR を出す、B: 加わる端末が文字列を貼る） ---

    private string DeviceName => Environment.MachineName.Length > 0 ? Environment.MachineName : "Windows PC";

    public void CancelPairing()
    {
        pairingCancel?.Cancel();
        pairingCancel = null;
        codes = null;
        if (pendingOnB is not null || joinWaiter is not null) Post(() => client?.CancelJoin());
        pendingOnB = null;
        SetPairing(PairingView.Idle);
    }

    public void EnterCode(string code) => codes?.Writer.TryWrite(code);

    /// <summary>A: 家の端末として、加わる端末に QR（と文字列）を出す。</summary>
    public void StartOffer()
    {
        CancelPairing();
        var cancel = new CancellationTokenSource();
        pairingCancel = cancel;
        var entered = System.Threading.Channels.Channel.CreateUnbounded<string>();
        codes = entered;
        _ = RunPairingAsync(() => RunOfferAsync(entered, cancel.Token), cancel.Token);
    }

    /// <summary>B: 家の端末が出した文字列（wave-pair:…）で加わる。</summary>
    public void Accept(string offerText)
    {
        CancelPairing();
        var cancel = new CancellationTokenSource();
        pairingCancel = cancel;
        _ = RunPairingAsync(() => RunAcceptAsync(offerText.Trim(), cancel.Token), cancel.Token);
    }

    /// <summary>B: 相手に6桁を入力してもらった後の「続ける」。</summary>
    public void ConfirmOnB()
    {
        if (pendingOnB is not { } established) return;
        if (established.PeerExtension is not { } extension)
        {
            SetPairing(PairingView.Failed(L.T("相手のアプリが古く、加われません", "The other device’s app is too old")));
            return;
        }
        pendingOnB = null;
        pairingCancel?.Cancel();
        var cancel = new CancellationTokenSource();
        pairingCancel = cancel;
        _ = RunPairingAsync(() => RunJoinOnBAsync(established, extension, cancel.Token), cancel.Token);
    }

    private async Task RunPairingAsync(Func<Task> block, CancellationToken cancel)
    {
        try
        {
            await block();
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
        }
        catch (Exception e)
        {
            AppPaths.Log($"pairing-v2 error type={e.GetType().Name}");
            Fail(L.T("連携できませんでした", "Couldn’t connect the device"));
        }
    }

    private void Fail(string message)
    {
        codes = null;
        pendingOnB = null;
        SetPairing(PairingView.Failed(message));
        AppPaths.Log("pairing-v2 failed");
    }

    private async Task RunOfferAsync(System.Threading.Channels.Channel<string> entered, CancellationToken cancel)
    {
        SetPairing(PairingView.Working(L.T("中継につないでいます…", "Connecting to the relay…")));
        var relay = Relay!;
        relay.Acquire();
        try
        {
            for (var i = 0; i < 150 && relay.Status is RelayStatus.Connecting or RelayStatus.Disconnected; i++) await Task.Delay(100, cancel);
            if (relay.Status != RelayStatus.Connected)
            {
                Fail(L.T("中継につながりませんでした（Tailscale につながっているか確かめてください）", "Couldn’t reach the relay (check that Tailscale is connected)"));
                return;
            }
            var joinInfo = await OnEngine(() => client?.CurrentJoinInfo() is { } info ? ConnectJson.Encode(info) : null);
            if (joinInfo is null)
            {
                Fail(L.T("この PC はまだ連携していません", "This PC isn’t set up yet"));
                return;
            }
            if (await AppServices.Music.VerifiedAccountAsync(cancel) is not { } account)
            {
                Fail(L.T("アカウントを確かめられませんでした", "Couldn’t verify your account"));
                return;
            }
            var responder = new Pairing.Responder(keys!, DeviceName, account.Id, () => clock.ElapsedMilliseconds, joinInfo);
            var tokenHash = B64.Encode(responder.TokenHash);
            while (pairHellos.Reader.TryRead(out _))
            {
            }
            if (!Send(new RelayV2.PairingOpen { TokenHash = tokenHash }))
            {
                Fail(L.T("中継に送れませんでした", "Couldn’t send to the relay"));
                return;
            }
            SetPairing(PairingView.ShowingOffer(Wave.Core.Connect.Pairing.EncodeOffer(responder.Offer)));
            using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancel);
            lifetime.CancelAfter(TimeSpan.FromMilliseconds(Wave.Core.Connect.Pairing.LifetimeMs));
            RelayV2.PairHello? hello = null;
            try
            {
                // 中継が切れたら待たない（Android と同じ）
                void OnStatus(RelayStatus s)
                {
                    if (s != RelayStatus.Connected) lifetime.Cancel();
                }
                relay.StatusChanged += OnStatus;
                try
                {
                    while (hello is null)
                    {
                        var next = await pairHellos.Reader.ReadAsync(lifetime.Token);
                        if (next.TokenHash == tokenHash) hello = next;
                    }
                }
                finally
                {
                    relay.StatusChanged -= OnStatus;
                }
            }
            catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
            {
                Fail(relay.Status != RelayStatus.Connected
                    ? L.T("中継との接続が切れました", "Lost the connection to the relay")
                    : L.T("時間内に読み取られませんでした", "The code wasn’t used in time"));
                return;
            }
            if (Wave.Core.Connect.Pairing.DecodeHello(hello.Hello) is not { } decoded)
            {
                Fail(L.T("相手から届いた内容を読めませんでした", "Couldn’t read the reply from the other device"));
                return;
            }
            var confirm = responder.OnHello(decoded);
            if (!confirm.IsSuccess)
            {
                Fail(confirm.Error == Wave.Core.Connect.Pairing.Failure.AccountMismatch
                    ? L.T("相手は別のアカウントでログインしています", "The other device is logged in to a different account")
                    : L.T("相手を確かめられませんでした", "Couldn’t verify the other device"));
                return;
            }
            if (!Send(new RelayV2.PairConfirm { TokenHash = tokenHash, Confirm = Wave.Core.Connect.Pairing.ToJson(confirm.Value!) }))
            {
                Fail(L.T("中継に送れませんでした", "Couldn’t send to the relay"));
                return;
            }
            SetPairing(PairingView.EnterCode(null));
            try
            {
                while (true)
                {
                    var code = await entered.Reader.ReadAsync(lifetime.Token);
                    var result = responder.OnCodeEntered(code);
                    if (!result.IsSuccess)
                    {
                        if (result.Error == Wave.Core.Connect.Pairing.Failure.WrongCode)
                        {
                            SetPairing(PairingView.EnterCode(L.T("数字が違います", "That code doesn’t match")));
                            continue;
                        }
                        Fail(L.T("連携できませんでした", "Couldn’t connect the device"));
                        return;
                    }
                    if (AppServices.Client.Generation != account.Generation)
                    {
                        Fail(L.T("途中でログインが変わりました", "Your login changed during setup"));
                        return;
                    }
                    var established = result.Value!;
                    SetPairing(PairingView.Working(L.T("端末を加えています…", "Adding the device…")));
                    if (established.PeerExtension is not { } memberKeys || !await AddMemberAsync(memberKeys, established.PeerPublicKey))
                    {
                        Fail(L.T("端末を加えられませんでした", "Couldn’t add the device"));
                        return;
                    }
                    RememberLabel(WaveCrypto.DeviceId(established.PeerPublicKey), established.PeerDeviceName);
                    AppPaths.Log("pairing-v2 established role=A");
                    SetPairing(PairingView.Done(established.PeerDeviceName));
                    return;
                }
            }
            catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
            {
                Fail(L.T("時間切れになりました。もう一度やり直してください", "Timed out. Please try again"));
            }
        }
        finally
        {
            relay.Release();
        }
    }

    /// <summary>A: 6桁の照合の後、B を家に加える。</summary>
    private async Task<bool> AddMemberAsync(string memberKeysJson, byte[] peerPublicKey)
    {
        var prepared = await OnEngine<(ChainRecord? Record, string? MemberId)?>(() =>
        {
            if (client?.VerifiedChain() is not { } chain) return null;
            if (ConnectJson.Decode<MemberKeys>(memberKeysJson) is not { Role: Role.Player } member) return null;
            // 6桁で確かめた相手の身元鍵と同じであること
            if (B64.Decode(member.IdentityPub) is not { } identity || !identity.AsSpan().SequenceEqual(peerPublicKey)) return null;
            // 保存を作り直した端末が同じ鍵で加わり直す: 既にいるので追記しない
            if (chain.Members.TryGetValue(member.DeviceId, out var existing) && existing.Keys.KxPub == member.KxPub) return (null, member.DeviceId);
            var record = MemberChain.Add(keys!, chain, member);
            // 積む前に、鎖として通ることを確かめる（相手の kx の署名が壊れていると、家の全端末がその鎖を拒んで止まる）
            if (client!.VerifiedRecords() is not { } records || MemberChain.Verify([.. records, record]) is not ChainVerification.Ok) return null;
            return (record, member.DeviceId);
        });
        if (prepared is not { } added) return false;
        if (added.Record is null) return true;
        return await AppendRecordAsync(added.Record, members => members.Any(m => m.DeviceId == added.MemberId));
    }

    private async Task RunAcceptAsync(string offerText, CancellationToken cancel)
    {
        if (Wave.Core.Connect.Pairing.DecodeOffer(offerText) is not { } offer)
        {
            Fail(L.T("wave の連携の文字列ではありません", "That isn’t a wave pairing code"));
            return;
        }
        if (await AppServices.Music.VerifiedAccountAsync(cancel) is not { } account)
        {
            Fail(L.T("アカウントを確かめられませんでした（ログインしてください）", "Couldn’t verify your account (log in first)"));
            return;
        }
        SetPairing(PairingView.Working(L.T("相手に送っています…", "Sending to the other device…")));
        var initiator = new Pairing.Initiator(offer, keys!, DeviceName, account.Id, ConnectJson.Encode(keys!.MemberKeys(Role.Player)));
        var reply = await Relay!.PairAsync(RelayV2.Encode(new RelayV2.PairHelloIn { Token = offer.Token, Hello = Wave.Core.Connect.Pairing.ToJson(initiator.Hello()) }));
        cancel.ThrowIfCancellationRequested();
        if ((reply is null ? null : RelayV2.Decode(reply)) is not RelayV2.PairConfirmed confirmed)
        {
            Fail(reply is null
                ? L.T("中継につながりませんでした（Tailscale につながっているか確かめてください）", "Couldn’t reach the relay (check that Tailscale is connected)")
                : L.T("相手が待っていません。相手でもう一度「端末を追加」を押してください", "The other device isn’t waiting. Tap “Add device” on it again"));
            return;
        }
        if (Wave.Core.Connect.Pairing.DecodeConfirm(confirmed.Confirm) is not { } confirm)
        {
            Fail(L.T("相手から届いた内容を読めませんでした", "Couldn’t read the reply from the other device"));
            return;
        }
        var result = initiator.OnConfirm(confirm);
        if (!result.IsSuccess)
        {
            Fail(result.Error == Wave.Core.Connect.Pairing.Failure.AccountMismatch
                ? L.T("相手は別のアカウントでログインしています", "The other device is logged in to a different account")
                : L.T("相手を確かめられませんでした", "Couldn’t verify the other device"));
            return;
        }
        if (AppServices.Client.Generation != account.Generation)
        {
            Fail(L.T("途中でログインが変わりました", "Your login changed during setup"));
            return;
        }
        pendingOnB = result.Value!.Pair;
        SetPairing(PairingView.ShowCode(result.Value.Code, result.Value.Pair.PeerDeviceName));
    }

    private async Task RunJoinOnBAsync(Pairing.Established established, string extension, CancellationToken cancel)
    {
        if (ConnectJson.Decode<ConnectClient.JoinInfo>(extension) is not { } info)
        {
            Fail(L.T("相手から届いた内容を読めませんでした", "Couldn’t read the reply from the other device"));
            return;
        }
        var inviter = WaveCrypto.DeviceId(established.PeerPublicKey);
        var started = await OnEngine(() =>
        {
            if (client is not { } engine || engine.HouseholdId is not null) return false;
            engine.ExpectJoin(info, inviter);
            return true;
        });
        if (!started)
        {
            Fail(L.T("この PC は今は加われません", "This PC can’t join right now"));
            return;
        }
        SetPairing(PairingView.Working(L.T("相手が加えるのを待っています…", "Waiting for the other device to add this PC…")));
        var waiter = new TaskCompletionSource<ClientEvent>(TaskCreationOptions.RunContinuationsAsynchronously);
        joinWaiter = waiter;
        var accepted = false;
        try
        {
            var relay = Relay!;
            var deadline = Stopwatch.StartNew();
            ClientEvent? outcome = null;
            foreach (var wait in new[] { 2_000, 5_000, 10_000, 13_000 })
            {
                if (await OnEngine(() => client?.HouseholdId is not null))
                {
                    outcome = new ClientEvent.Joined();
                    break;
                }
                // 家に入れてもらったら、つなぎ直して鎖を取る（認証は中継の鎖の今の端末で行う）
                relay.Reconnect();
                var finished = await Task.WhenAny(waiter.Task, Task.Delay(wait, cancel));
                cancel.ThrowIfCancellationRequested();
                if (finished == waiter.Task)
                {
                    outcome = waiter.Task.Result;
                    break;
                }
                if (deadline.ElapsedMilliseconds > 30_000) break;
            }
            accepted = outcome is ClientEvent.Joined;
            if (accepted)
            {
                RememberLabel(inviter, established.PeerDeviceName);
                AppPaths.Log("pairing-v2 established role=B");
                SetPairing(PairingView.Done(established.PeerDeviceName));
            }
            else
            {
                Fail(outcome is null ? L.T("加われませんでした（時間切れ）", "Couldn’t join (timed out)") : L.T("端末の一覧を確かめられませんでした", "Couldn’t verify the device list"));
            }
        }
        finally
        {
            joinWaiter = null;
            if (!accepted) Post(() => client?.CancelJoin());
        }
    }
}

public sealed record ConnectInfo(
    bool Configured = false,
    bool Loaded = false,
    bool Unreadable = false,
    bool InHousehold = false,
    bool IsWriter = false,
    bool Retreated = false,
    bool StoreResetPending = false,
    RelayStatus Relay = RelayStatus.Disconnected);

public sealed record MemberView(string DeviceId, string Label, bool IsSelf, bool IsController, bool Online, bool Active);

public sealed record RemoteView(string DeviceId, string DeviceLabel, string? Title, string? Artist, string? ThumbnailUrl, int? DurationSeconds, bool Playing, long PositionMs, bool AccountMatches, bool Online);

public enum PairingKind { Idle, Working, ShowingOffer, EnterCode, ShowCode, Done, Failed }

public sealed record PairingView(PairingKind Kind, string? Text = null, string? OfferText = null, string? Code = null, string? PeerName = null)
{
    public static readonly PairingView Idle = new(PairingKind.Idle);
    public static PairingView Working(string text) => new(PairingKind.Working, text);
    public static PairingView ShowingOffer(string offer) => new(PairingKind.ShowingOffer, OfferText: offer);
    public static PairingView EnterCode(string? message) => new(PairingKind.EnterCode, message);
    public static PairingView ShowCode(string code, string peer) => new(PairingKind.ShowCode, Code: code, PeerName: peer);
    public static PairingView Done(string peer) => new(PairingKind.Done, PeerName: peer);
    public static PairingView Failed(string message) => new(PairingKind.Failed, message);
}
