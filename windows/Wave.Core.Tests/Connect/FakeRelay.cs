using Wave.Core.Connect;

namespace Wave.Core.Tests.Connect;

/// <summary>
/// 端末なしの多端末テストの中継（relay の HouseholdStore・HouseholdRouter を最小に写したもの。家は1つ、メモリだけ）。
/// 届けるのは Run() のときだけ（決まった順）。送受信はどちらも JSON の文字列を通す（ワイヤの形も確かめる）。
/// </summary>
internal sealed class FakeRelay(Func<long> nowMs)
{
    public const long PendingMs = 10_000;

    private sealed record StoredDoc(long Version, SealedDoc Doc, long ReceivedAtServerMs);

    private sealed record Pending(string ClaimId, string Owner, string? PreviousActive, long UntilMs);

    public string StoreId { get; } = B64.Encode(WaveCrypto.RandomBytes(16));
    private readonly List<ChainRecord> records = [];
    private VerifiedChain? chain;
    private StoredDoc? cursor;
    private StoredDoc? queue;
    private string? active;
    private long claimOrder;
    private readonly Dictionary<string, long> claimOrders = [];
    private Pending? pending;

    /// <summary>ライブラリの文書（ADR-0013）と、library_get を送った端末（配るのはこの端末だけ）。</summary>
    private StoredDoc? library;
    private readonly HashSet<string> librarySubscribers = [];

    /// <summary>false なら、ライブラリを知らない古い中継として振る舞う（library_* に unsupported を返す）。</summary>
    public bool SupportsLibrary { get; set; } = true;

    public long? LibraryVersion => library?.Version;

    /// <summary>中継がライブラリを失った（作り直しなど）。</summary>
    public void DropLibrary() => library = null;

    private readonly Dictionary<string, Action<RelayV2>> connected = [];
    private readonly Queue<(string To, string Text)> outbox = new();
    private readonly List<(string To, string Text)> held = [];

    /// <summary>true を返すと、release まで届けない。</summary>
    public Func<string, RelayV2, bool> Hold { get; set; } = (_, _) => false;

    public string? ActiveDeviceId => active;

    public void Connect(string deviceId, Action<RelayV2> deliver)
    {
        connected[deviceId] = deliver;
        Post(deviceId, Welcome(deviceId));
        Broadcast(new RelayV2.Presence { Online = connected.Keys.ToList() });
    }

    public void Disconnect(string deviceId)
    {
        connected.Remove(deviceId);
        librarySubscribers.Remove(deviceId);
        BroadcastExpired(ExpirePending(deviceId));
        Broadcast(new RelayV2.Presence { Online = connected.Keys.ToList() });
    }

    public void Tick() => BroadcastExpired(ExpirePending(null));

    public void ReleaseHeld()
    {
        foreach (var item in held) outbox.Enqueue(item);
        held.Clear();
    }

    public void Run()
    {
        var guard = 0;
        while (outbox.Count > 0)
        {
            if (guard++ > 10_000) throw new InvalidOperationException("メッセージが止まらない");
            var (to, text) = outbox.Dequeue();
            if (!connected.TryGetValue(to, out var deliver)) continue;
            var message = RelayV2.Decode(text) ?? throw new InvalidOperationException("読めない: " + text);
            if (Hold(to, message))
            {
                held.Add((to, text));
                continue;
            }
            deliver(message);
        }
    }

    /// <summary>端末から届いたものを覗く（テストで、敵対的な中継が取っておく命令を得る）。</summary>
    public Action<string, RelayV2>? OnFromClient { get; set; }

    /// <summary>敵対的な中継: 端末へ任意のメッセージを差し込む。</summary>
    public void Inject(string to, RelayV2 message) => Post(to, message);

    /// <returns>中継に届いたか（つながっていなければ false）</returns>
    public bool FromClient(string deviceId, RelayV2 message)
    {
        if (!connected.ContainsKey(deviceId)) return false;
        OnFromClient?.Invoke(deviceId, message);
        var text = RelayV2.Encode(message);
        if (text.Length > RelayV2.MaxFrameChars) throw new InvalidOperationException("大きすぎる");
        Route(deviceId, RelayV2.Decode(text) ?? throw new InvalidOperationException("読めない: " + text));
        return true;
    }

    private void Post(string to, RelayV2 message) => outbox.Enqueue((to, RelayV2.Encode(message)));

    private void Broadcast(RelayV2 message, string? except = null)
    {
        foreach (var id in connected.Keys.Where(id => id != except).ToList()) Post(id, message);
    }

    private RelayV2.Welcome Welcome(string deviceId) => new()
    {
        DeviceId = deviceId,
        StoreId = StoreId,
        HouseholdId = chain?.HouseholdId,
        HeadSeq = chain?.HeadSeq,
        HeadHash = chain?.HeadHash,
        ActiveDeviceId = active,
        Online = connected.Keys.ToList(),
        States = Latest(),
        ClaimOrders = new Dictionary<string, long>(claimOrders),
    };

    private List<StoredState> Latest() => new[] { StateOf(StateKind.Cursor), StateOf(StateKind.Queue) }.OfType<StoredState>().ToList();

    private StoredState? StateOf(StateKind kind)
    {
        var stored = kind == StateKind.Cursor ? cursor : queue;
        return stored is null ? null : new StoredState { Kind = kind, Version = stored.Version, Doc = stored.Doc, StoreId = StoreId, ReceivedAtServerMs = stored.ReceivedAtServerMs, ServerNowMs = nowMs(), ActiveDeviceId = active };
    }

    private void Route(string sender, RelayV2 message)
    {
        void Reply(RelayV2 m) => Post(sender, m);
        switch (message)
        {
            case RelayV2.HouseholdCreate create:
                if (chain is not null || create.Genesis.SignerId != sender || MemberChain.Verify([create.Genesis]) is not ChainVerification.Ok created)
                {
                    Reply(new RelayV2.Error { Code = "household_exists" });
                    return;
                }
                records.Add(create.Genesis);
                chain = created.Chain;
                Reply(new RelayV2.ChainOk { HeadSeq = chain.HeadSeq, HeadHash = chain.HeadHash });
                break;
            case RelayV2.ChainGet get:
                Reply(new RelayV2.Chain { Records = records.Skip((int)Math.Clamp(get.FromSeq, 0, int.MaxValue)).ToList() });
                break;
            case RelayV2.ChainAppend append:
            {
                var current = chain;
                if (current is null || !current.Members.ContainsKey(sender) || append.Record.SignerId != sender)
                {
                    Reply(new RelayV2.Error { Code = "not_member" });
                    return;
                }
                if (append.Record.Seq != current.HeadSeq + 1 || append.Record.PrevHash != current.HeadHash)
                {
                    Reply(new RelayV2.ChainConflict { HeadSeq = current.HeadSeq, HeadHash = current.HeadHash });
                    return;
                }
                if (MemberChain.Verify([.. records, append.Record], new MemberChain.Head(current.HeadSeq, current.HeadHash)) is not ChainVerification.Ok ok)
                {
                    Reply(new RelayV2.Error { Code = "invalid_record" });
                    return;
                }
                records.Add(append.Record);
                chain = ok.Chain;
                Reply(new RelayV2.ChainOk { HeadSeq = chain.HeadSeq, HeadHash = chain.HeadHash });
                Broadcast(new RelayV2.ChainChanged { HeadSeq = chain.HeadSeq, HeadHash = chain.HeadHash }, except: sender);
                break;
            }
            case RelayV2.StateGet:
                foreach (var state in Latest()) Reply(new RelayV2.State { Stored = state });
                break;
            case RelayV2.StatePut put:
                OnPut(sender, put);
                break;
            case RelayV2.Claim claim:
                OnClaim(sender, claim.ClaimId, claim.YieldDoc, transferBasedOn: null);
                break;
            case RelayV2.Transfer transfer:
                OnClaim(sender, transfer.ClaimId, transfer.YieldDoc, transfer.BasedOnVersion);
                break;
            case RelayV2.Yielded yielded:
                OnYielded(sender, yielded);
                break;
            case RelayV2.Control control:
                // 書き手が送り主で、命令の doc であるときだけ配る（中継の HouseholdRouter と同じ）
                if (control.Doc.Header.WriterId == sender && control.Doc.Header.Kind == DocKind.Command) Broadcast(control, except: sender);
                else Reply(new RelayV2.Error { Code = "bad_control" });
                break;
            case RelayV2.LibraryGet when SupportsLibrary:
                if (chain is null || !chain.Members.ContainsKey(sender))
                {
                    Reply(new RelayV2.Error { Code = "not_member" });
                    return;
                }
                librarySubscribers.Add(sender);
                Reply(new RelayV2.Library { Stored = LibraryOf() });
                break;
            case RelayV2.LibraryPut put when SupportsLibrary:
                OnLibraryPut(sender, put);
                break;
            case RelayV2.Release release:
                if (pending is { } waiting && waiting.ClaimId == release.ClaimId && waiting.Owner == sender) Broadcast(DropPending());
                break;
            default:
                Reply(new RelayV2.Error { Code = "unsupported" });
                break;
        }
    }

    private string? HeaderProblem(string sender, VerifiedChain current, StateKind kind, long expectVersion, SealedDoc doc)
    {
        var header = doc.Header;
        if (header.WriterId != sender) return "writer_mismatch";
        if (header.HouseholdId != current.HouseholdId) return "household";
        if (header.Kind != kind.DocKind()) return "kind";
        if (header.Version != expectVersion + 1) return "version";
        if (header.HeadSeq > current.HeadSeq) return "future_head";
        if (header.HeadSeq < current.HeadSeq) return "stale_head";
        return null;
    }

    private void OnPut(string sender, RelayV2.StatePut put)
    {
        var current = chain;
        string? problem = null;
        if (current is null) problem = "no_household";
        else if (!current.Members.TryGetValue(sender, out var member) || member.Keys.Role != Role.Player) problem = "not_player";
        else if (active != sender && pending?.Owner != sender) problem = "not_active";
        else problem = HeaderProblem(sender, current, put.Kind, put.ExpectVersion, put.Doc);
        if (problem is not null)
        {
            Post(sender, new RelayV2.Error { Code = problem, RequestId = put.RequestId });
            return;
        }
        var stored = put.Kind == StateKind.Cursor ? cursor : queue;
        if ((stored?.Version ?? 0) != put.ExpectVersion)
        {
            Post(sender, new RelayV2.StateConflict { RequestId = put.RequestId, Kind = put.Kind, Latest = StateOf(put.Kind) });
            return;
        }
        var doc = new StoredDoc(put.ExpectVersion + 1, put.Doc, nowMs());
        if (put.Kind == StateKind.Cursor) cursor = doc;
        else queue = doc;
        RelayV2.ActiveChanged? activeChanged = null;
        if (put.Kind == StateKind.Cursor && pending is { } waiting && waiting.Owner == sender)
        {
            active = sender;
            pending = null;
            activeChanged = new RelayV2.ActiveChanged { ActiveDeviceId = sender, ClaimId = waiting.ClaimId, ClaimOrder = claimOrder };
        }
        Post(sender, new RelayV2.StateOk { RequestId = put.RequestId, Kind = put.Kind, Version = doc.Version });
        Broadcast(new RelayV2.State { Stored = StateOf(put.Kind)! }, except: sender);
        if (activeChanged is not null) Broadcast(activeChanged);
    }

    private LibraryState? LibraryOf() => library is null ? null : new LibraryState { Version = library.Version, Doc = library.Doc, StoreId = StoreId };

    /// <summary>中継の HouseholdStore.putLibrary と同じ: 家のどの端末も書ける（再生中でなくてよい）。版の CAS。</summary>
    private void OnLibraryPut(string sender, RelayV2.LibraryPut put)
    {
        var current = chain;
        string? problem;
        if (current is null) problem = "no_household";
        else if (!current.Members.ContainsKey(sender)) problem = "not_member";
        else
        {
            var header = put.Doc.Header;
            problem = header.WriterId != sender ? "writer_mismatch"
                : header.HouseholdId != current.HouseholdId ? "household"
                : header.Kind != DocKind.Library ? "kind"
                : header.Version != put.ExpectVersion + 1 ? "version"
                : header.HeadSeq > current.HeadSeq ? "future_head"
                : header.HeadSeq < current.HeadSeq ? "stale_head"
                : null;
        }
        if (problem is not null)
        {
            Post(sender, new RelayV2.Error { Code = problem, RequestId = put.RequestId });
            return;
        }
        if ((library?.Version ?? 0) != put.ExpectVersion)
        {
            Post(sender, new RelayV2.LibraryConflict { RequestId = put.RequestId, Latest = LibraryOf() });
            return;
        }
        library = new StoredDoc(put.ExpectVersion + 1, put.Doc, nowMs());
        Post(sender, new RelayV2.LibraryOk { RequestId = put.RequestId, Version = library.Version });
        foreach (var id in connected.Keys.Where(id => id != sender && librarySubscribers.Contains(id)).ToList()) Post(id, new RelayV2.Library { Stored = LibraryOf() });
    }

    private void OnClaim(string sender, string claimId, SealedDoc yieldDoc, long? transferBasedOn)
    {
        var current = chain;
        string? problem = null;
        if (current is null) problem = "no_household";
        else if (!current.Members.TryGetValue(sender, out var member) || member.Keys.Role != Role.Player) problem = "not_player";
        else if (yieldDoc.Header.WriterId != sender || yieldDoc.Header.Kind != DocKind.Command || yieldDoc.Header.HouseholdId != current.HouseholdId || claimId.Length is < 16 or > 64) problem = "bad_command";
        else if (transferBasedOn is not null && cursor is null) problem = "no_state";
        if (problem is not null)
        {
            Post(sender, new RelayV2.Error { Code = problem, RequestId = claimId });
            return;
        }
        if (transferBasedOn is { } based && cursor!.Version != based)
        {
            Post(sender, new RelayV2.StateConflict { RequestId = claimId, Kind = StateKind.Cursor, Latest = StateOf(StateKind.Cursor) });
            return;
        }
        var dropped = pending?.ClaimId;
        claimOrder++;
        claimOrders[sender] = claimOrder;
        RelayV2.ActiveChanged changed;
        if (transferBasedOn is null)
        {
            pending = null;
            active = sender;
            changed = new RelayV2.ActiveChanged { ActiveDeviceId = sender, ClaimId = claimId, ClaimOrder = claimOrder };
        }
        else
        {
            pending = new Pending(claimId, sender, active, nowMs() + PendingMs);
            changed = new RelayV2.ActiveChanged { ActiveDeviceId = active, ClaimId = claimId, ClaimOrder = claimOrder, PendingOwner = sender };
        }
        if (dropped is not null) Broadcast(new RelayV2.PendingExpired { ClaimId = dropped });
        Broadcast(changed);
        // yield は家の全端末に配る（各端末は宛先で判断する）
        Broadcast(new RelayV2.YieldDelivery { ClaimId = claimId, YieldDoc = yieldDoc }, except: sender);
    }

    private void OnYielded(string sender, RelayV2.Yielded message)
    {
        var current = chain;
        string? problem = null;
        if (current is null) problem = "no_household";
        else if (pending is not { } waiting) problem = "no_pending";
        else if (waiting.ClaimId != message.ClaimId || waiting.PreviousActive != sender) problem = "not_pending_previous";
        else problem = HeaderProblem(sender, current, StateKind.Cursor, message.ExpectVersion, message.Doc);
        if (problem is not null)
        {
            Post(sender, new RelayV2.Error { Code = problem, RequestId = message.ClaimId });
            return;
        }
        if ((cursor?.Version ?? 0) != message.ExpectVersion)
        {
            Post(sender, new RelayV2.StateConflict { RequestId = message.ClaimId, Kind = StateKind.Cursor, Latest = StateOf(StateKind.Cursor) });
            return;
        }
        cursor = new StoredDoc(message.ExpectVersion + 1, message.Doc, nowMs());
        Post(sender, new RelayV2.StateOk { RequestId = message.ClaimId, Kind = StateKind.Cursor, Version = cursor.Version });
        Broadcast(new RelayV2.State { Stored = StateOf(StateKind.Cursor)! }, except: sender);
    }

    private (string ClaimId, RelayV2.ActiveChanged Active)? ExpirePending(string? disconnected)
    {
        if (pending is not { } waiting) return null;
        if (waiting.UntilMs >= nowMs() && waiting.Owner != disconnected) return null;
        return (waiting.ClaimId, DropPending());
    }

    /// <summary>新しい端末が引き取れなかった: 前の再生中の端末に戻す。</summary>
    private RelayV2.ActiveChanged DropPending()
    {
        var previous = pending?.PreviousActive;
        pending = null;
        active = previous;
        return new RelayV2.ActiveChanged { ActiveDeviceId = previous, ClaimOrder = claimOrder };
    }

    private void BroadcastExpired((string ClaimId, RelayV2.ActiveChanged Active)? expired)
    {
        if (expired is not { } e) return;
        Broadcast(new RelayV2.PendingExpired { ClaimId = e.ClaimId });
        Broadcast(e.Active);
    }
}

/// <summary>シミュレーションの端末: 鍵・ConnectClient・偽の再生。</summary>
internal sealed class SimDevice : ConnectClient.IEffects
{
    private readonly Func<FakeRelay> relay;
    private readonly Func<long> clock;
    private readonly SoftwareDevice keys = SoftwareDevice.Generate();
    private bool playing;
    private long positionAnchor;
    private long anchorAt;

    public SimDevice(string name, Func<FakeRelay> relay, Func<long> clock, Role role = Role.Player)
    {
        Name = name;
        this.relay = relay;
        this.clock = clock;
        Role = role;
        Client = NewClient(new ClientPersisted());
    }

    public string Name { get; }
    public Role Role { get; }
    public string DeviceId => keys.DeviceId;
    public SoftwareDevice Keys => keys;
    public MemberKeys MemberKeys => keys.MemberKeys(Role);
    public string? Tag { get; set; } = "tag-account-1";
    public string? Queue { get; set; }
    public string CurrentItemId { get; set; } = "item-0";
    public List<ClientEvent> Events { get; } = [];
    public ClientPersisted Persisted { get; private set; } = new();
    public ConnectClient Client { get; private set; }

    public bool Playing
    {
        get => playing;
        set
        {
            var current = PositionMs;
            playing = value;
            positionAnchor = current;
            anchorAt = clock();
        }
    }

    public long PositionMs
    {
        get => playing ? positionAnchor + (clock() - anchorAt) : positionAnchor;
        set
        {
            positionAnchor = value;
            anchorAt = clock();
        }
    }

    /// <summary>プロセスが落ちて起き直した（保存から読み直す。JSON を通す）。</summary>
    public void Restart() => Client = NewClient(ConnectJson.Decode<ClientPersisted>(ConnectJson.Encode(Persisted))!);

    private ConnectClient NewClient(ClientPersisted initial) =>
        new(keys, keys, Role, message => relay().FromClient(DeviceId, message), this, clock, clock, () => Guid.NewGuid().ToString(), initial);

    public void Connect() => relay().Connect(DeviceId, m => Client.OnMessage(m));

    public void Disconnect()
    {
        relay().Disconnect(DeviceId);
        Client.OnDisconnected();
    }

    public void StartPlaying(string snapshot, string item = "item-0", long position = 0)
    {
        Queue = snapshot;
        CurrentItemId = item;
        PositionMs = position;
        Playing = true;
        Client.SetLocalQueue(snapshot);
        PushCursor();
        Client.UserStartedPlayback();
    }

    public void PushCursor() => Client.SetLocalCursor(LocalCursor());

    public ConnectClient.LocalCursor LocalCursor() => new(CurrentItemId, PositionMs, Playing, HandoffRepeat.Off, 0, false, clock());

    public bool StoppedBy(SimDevice other) => Events.Any(e => e is ClientEvent.Stopped s && s.By == other.DeviceId);

    // --- Effects ---

    ConnectClient.LocalCursor? ConnectClient.IEffects.Pause()
    {
        Playing = false;
        return LocalCursor();
    }

    bool ConnectClient.IEffects.Adopt(string snapshot, CursorBody cursor, long positionMs)
    {
        Queue = snapshot;
        CurrentItemId = cursor.CurrentItemId;
        PositionMs = positionMs;
        Playing = true;
        return true;
    }

    void ConnectClient.IEffects.Event(ClientEvent clientEvent) => Events.Add(clientEvent);

    /// <summary>中継から届いたライブラリ（合わせた後のもの）。</summary>
    public LibraryBody? Library { get; private set; }

    void ConnectClient.IEffects.LibraryChanged(LibraryBody body) => Library = body;

    void ConnectClient.IEffects.Persist(ClientPersisted state) => Persisted = state;

    string? ConnectClient.IEffects.AccountTag() => Tag;

    /// <summary>従った遠隔操作。</summary>
    public List<(ControlAction Action, long? PositionMs)> Controls { get; } = [];

    bool ConnectClient.IEffects.Control(ControlAction action, long? positionMs)
    {
        Controls.Add((action, positionMs));
        if (action == ControlAction.Play) Playing = true;
        else if (action == ControlAction.Pause) Playing = false;
        else if (action == ControlAction.Seek) PositionMs = positionMs ?? 0;
        return true;
    }
}
