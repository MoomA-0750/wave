namespace Wave.Core.Connect;

/// <summary>端末が保存しておくもの（D-2 の頭、D-4 の storeId と版の床、D-9 の退いた印、処理した claimId）。ConnectJson で JSON にできる。</summary>
public sealed record ClientPersisted
{
    public IReadOnlyList<ChainRecord> Chain { get; init; } = [];
    public string? StoreId { get; init; }
    public long CursorFloor { get; init; }
    public long QueueFloor { get; init; }
    public bool Retreated { get; init; }
    public IReadOnlyList<string> HandledClaimIds { get; init; } = [];

    /// <summary>加入を待っている（Confirm の床と招いた端末）。プロセスが落ちても、無条件に最初の鎖を信じない。</summary>
    public ConnectClient.JoinInfo? ExpectedJoin { get; init; }

    public string? ExpectedInviter { get; init; }

    /// <summary>家を作ると押した（最初の鎖が自分の genesis なら受け入れる）。</summary>
    public bool CreatingHousehold { get; init; }
}

public abstract record ClientEvent
{
    /// <summary>ほかの端末の再生で、この端末が止まった。</summary>
    public sealed record Stopped(string By) : ClientEvent;

    /// <summary>ほかの端末の状態が変わった（表示）。AccountMatches が false なら、別のアカウントの端末（表示だけ）。</summary>
    public sealed record RemoteChanged(string Writer, CursorBody Cursor, bool AccountMatches) : ClientEvent;

    /// <summary>中継が、検証した一覧と同じ seq で別の記録を見せた。</summary>
    public sealed record ChainForked : ClientEvent;

    public sealed record ChainRolledBack : ClientEvent;

    /// <summary>受けた記録が検証を通らなかった（改ざん・不正な追記）。</summary>
    public sealed record ChainInvalid(string Reason) : ClientEvent;

    /// <summary>サーバーの状態が作り直された。AcceptStoreReset を呼ぶまで、その状態で止まらない・取り込まない（L-12）。</summary>
    public sealed record StoreReset(string NewStoreId) : ClientEvent;

    /// <summary>同じ書き手・同じ版で中身が違う状態を見た（サーバーの不正か不具合）。</summary>
    public sealed record StateEquivocation : ClientEvent;

    public sealed record TransferFailed(string Reason) : ClientEvent;

    public sealed record TransferCompleted(string From) : ClientEvent;

    public sealed record ServerError(string Code) : ClientEvent;

    /// <summary>家に入った（加入の鎖を受け入れた、または自分の genesis の鎖を受け入れた）。</summary>
    public sealed record Joined : ClientEvent;

    /// <summary>家の端末が表示名を知らせてきた（表示だけ）。</summary>
    public sealed record Profile(string DeviceId, string Name, string? Kind = null) : ClientEvent;
}

/// <summary>
/// Connect v2 の端末側（Android 版の ConnectClient と同じ状態機械、D-2・D-6〜D-9）。
/// 受信・画面の操作・時間の経過（Tick）を1本ずつ呼ぶこと（スレッドを跨いで同時に呼ばない。呼ぶ側が1本にそろえる）。
/// 暗号と署名はここで行い、再生そのものは Effects に任せる。端末の時計は順序の判断に使わない（L-10）。
/// </summary>
public sealed class ConnectClient
{
    public const long TransferWaitMs = 3_000L;
    public const long FloorPersistMs = 30_000L;
    public const long MaxExtrapolationMs = 9 * 60 * 1000L;
    private const int MaxHandledClaims = 64;

    /// <summary>遠隔操作の送り手が見ていた版と、受け手の最新の書き込みの版の差の上限（それより古い操作には従わない）。</summary>
    private const long MaxControlLag = 20;

    /// <summary>遠隔操作の鮮度の上限（命令の見出しの時刻と受け手の時計の差）。</summary>
    private const long MaxControlAgeMs = 5 * 60 * 1000;

    /// <summary>開いた遠隔操作の doc のハッシュ（同じ doc の再送を開く前に捨てる）。</summary>
    private readonly LinkedList<string> seenControlDocs = new();

    public interface IEffects
    {
        /// <summary>一時停止し、止めた時点の手元の再生を返す（最後のカーソルの位置を今に合わせる）。</summary>
        LocalCursor? Pause();

        /// <summary>取り込んで positionMs から再生を始める。受け付けなければ false（画面が前面でない・ログインが変わった等）。</summary>
        bool Adopt(string snapshot, CursorBody cursor, long positionMs);

        void Event(ClientEvent clientEvent);

        void Persist(ClientPersisted state);

        /// <summary>今のログインの accountTag（Verified でなければ null）。</summary>
        string? AccountTag();

        /// <summary>遠隔操作に従う（この端末が鳴らしている）。受け付けなければ false。</summary>
        bool Control(ControlAction action, long? positionMs) => false;

        /// <summary>
        /// 遠隔のキューへの追加に従う（この端末が鳴らしている）。afterItemId の曲があればその直後、無ければ next なら今の曲の直後、
        /// そうでなければ自分で足した曲のかたまりの最後へ、items の ID のまま入れる。受け付けなければ false。
        /// </summary>
        bool QueueAdd(IReadOnlyList<HandoffItem> items, bool next, string? afterItemId) => false;

        /// <summary>
        /// 遠隔のキューの並べ替えに従う（この端末が鳴らしている）。itemId の曲（今の曲より後ろ）を、afterItemId の曲の直後
        /// （afterItemId が無ければ今の曲の直後）へ動かす。どちらかが手元のキューに無い・今の曲より前なら何もせず false。
        /// </summary>
        bool QueueMove(string itemId, string? afterItemId) => false;

        /// <summary>遠隔のキューから外す（この端末が鳴らしている）。今の曲より後ろにある itemIds の曲だけを外す。</summary>
        bool QueueRemove(IReadOnlyList<string> itemIds) => false;

        /// <summary>遠隔の音量に従う（この端末が鳴らしている）。アプリの中の音量を volumePercent（0〜100）にする。受け付けなければ false。</summary>
        bool Volume(int volumePercent) => false;

        /// <summary>ほかの端末と合わせたライブラリが届いた（手元に取り込む、ADR-0013）。</summary>
        void LibraryChanged(LibraryBody body)
        {
        }
    }

    /// <summary>表示用: ほかの端末の最新のカーソルと、今の時刻に合わせた位置。</summary>
    public sealed record RemoteNow(string Writer, CursorBody Cursor, long PositionMs, long Version);

    /// <summary>手元の再生の、カーソルに入れる値。</summary>
    /// <param name="CapturedAtMs">PositionMs を取った時刻（nowMs と同じ単調時計）。再生中なら、書くときに経過を足す。</param>
    public sealed record LocalCursor(string CurrentItemId, long PositionMs, bool Playing, HandoffRepeat Repeat, long ListenedMs, bool HistorySent, long CapturedAtMs, int? VolumePercent = null);

    /// <summary>加入の Confirm で受け取った床（D-3）。6桁の照合で確かめた相手（inviter）から、認証された通信路で届いた値。</summary>
    public sealed record JoinInfo
    {
        public required string HouseholdId { get; init; }
        public required long HeadSeq { get; init; }
        public required string HeadHash { get; init; }
        public required string StoreId { get; init; }
        public required long CursorFloor { get; init; }
        public required long QueueFloor { get; init; }
    }

    private sealed class Opened<T>(StoredState stored, T body, string hash, long receivedAtLocalMs)
    {
        public StoredState Stored { get; } = stored;
        public T Body { get; } = body;
        public string Hash { get; } = hash;
        public long ReceivedAtLocalMs { get; } = receivedAtLocalMs;
        public string Writer => Stored.Doc.Header.WriterId;
        public long Version => Stored.Version;
    }

    /// <param name="SessionId">送ったときのセッション（前のセッションの返事で、新しいセッションの startVersion を決めない）。</param>
    private sealed record Inflight(StateKind Kind, SealedDoc Doc, string Hash, CursorBody? Cursor, QueueBody? Queue, long SessionId);

    private sealed class TransferState(string claimId, string previousWriter, long startedAtMs, bool retried)
    {
        public string ClaimId { get; } = claimId;
        public string PreviousWriter { get; } = previousWriter;
        public long StartedAtMs { get; } = startedAtMs;
        public bool Retried { get; } = retried;
        public bool Adopting { get; set; }
    }

    private sealed class Session(long id, PlaybackArbiter.Session arbiter, string? claimId)
    {
        public long Id { get; } = id;
        public PlaybackArbiter.Session Arbiter { get; set; } = arbiter;
        public string? ClaimId { get; set; } = claimId;
        public long? ClaimOrder { get; set; }
    }

    private readonly IDeviceSigner signer;
    private readonly IKxOpener opener;
    private readonly Role role;
    private readonly Func<RelayV2, bool> send;
    private readonly IEffects effects;
    private readonly Func<long> nowMs;
    private readonly Func<long> epochMs;
    private readonly Func<string> newId;

    private IReadOnlyList<ChainRecord> records;
    private VerifiedChain? chain;
    private string? storeId;
    private string? pendingStoreId;
    private long cursorFloor;
    private long queueFloor;
    private bool retreated;
    private readonly LinkedList<string> handledClaims;
    private (JoinInfo Info, string Inviter)? expectedJoin;
    private bool creatingHousehold;

    /// <summary>タグ（Verified のアカウント）が無い間に本人が再生を始めた。タグができたら claim する。</summary>
    private bool startWaitingForTag;

    private Opened<CursorBody>? latestCursor;
    private Opened<QueueBody>? latestQueue;
    private readonly List<StoredState> deferred = [];
    private string? serverActive;
    private string? pendingOwner;
    private readonly Dictionary<string, long> claimOrders = [];

    /// <summary>中継につながっている家の端末（中継の申告。表示と D-9 の判断だけに使い、止まる根拠にしない）。</summary>
    private IReadOnlySet<string> online = new HashSet<string>();

    /// <summary>中継につながっている家の端末（中継の申告。表示だけに使う）。</summary>
    public IReadOnlySet<string> Online => online;

    /// <summary>今の接続で welcome を処理した（切れたら false。メモリに残った古いカーソルを最新とみなさない）。</summary>
    private bool welcomed;

    /// <summary>同時に始めた相手の証拠で、claimOrder を待って保留しているもの（D-8）。</summary>
    private readonly List<PlaybackArbiter.Evidence> deferredEvidence = [];

    // 手元の再生
    private string? localQueue;
    private string? localQueueRef;
    private LocalCursor? local;
    private Session? session;
    private long sessionCounter;
    private long deviceSeq;
    private string? lastOwnCursorHash;
    private readonly Dictionary<string, Inflight> inflight = [];
    private bool dirtyCursor;
    private bool dirtyQueue;
    private TransferState? transfer;

    /// <summary>つながり直した後、最新の状態と鎖をそろえてから書き込みを再開する。</summary>
    private bool resumePending;

    /// <summary>版の床が上がるたびに保存しない。落ちても床が少し低いだけ（巻き戻しの窓が広がる）。</summary>
    private bool floorDirty;

    private long? lastPersistMs;

    /// <param name="send">中継へ送る。つながっていなくて送れなければ false</param>
    /// <param name="nowMs">単調時計（ミリ秒）。transfer の3秒と外挿の足し算にだけ使う</param>
    /// <param name="epochMs">表示用の時刻（startedAtEpochMs）</param>
    public ConnectClient(IDeviceSigner signer, IKxOpener opener, Role role, Func<RelayV2, bool> send, IEffects effects, Func<long> nowMs, Func<long> epochMs, Func<string> newId, ClientPersisted? initial = null)
    {
        initial ??= new ClientPersisted();
        this.signer = signer;
        this.opener = opener;
        this.role = role;
        this.send = send;
        this.effects = effects;
        this.nowMs = nowMs;
        this.epochMs = epochMs;
        this.newId = newId;
        records = initial.Chain;
        chain = records.Count == 0 ? null : (MemberChain.Verify(records) as ChainVerification.Ok)?.Chain;
        storeId = initial.StoreId;
        cursorFloor = initial.CursorFloor;
        queueFloor = initial.QueueFloor;
        retreated = initial.Retreated;
        handledClaims = new LinkedList<string>(initial.HandledClaimIds);
        expectedJoin = initial.ExpectedJoin is { } info && initial.ExpectedInviter is { } inviter ? (info, inviter) : null;
        creatingHousehold = initial.CreatingHousehold;
    }

    public bool IsWriter => session is not null;
    public string? ActiveDeviceId => serverActive;
    public bool HasRetreated => retreated;

    /// <summary>検証した家の ID（アカウントタグの計算に使う）。家に入っていなければ null。</summary>
    public string? HouseholdId => chain?.HouseholdId;

    /// <summary>検証した端末一覧（表示）。</summary>
    public IReadOnlyList<MemberKeys> Members => chain?.Members.Values.Select(m => m.Keys).ToList() ?? [];

    /// <summary>サーバーの状態が作り直されたと気づき、本人の確認を待っている（L-12）。</summary>
    public bool StoreResetPending => pendingStoreId is not null;

    /// <summary>加入の手順で、家の鎖を使う（A が B を加えるときの add の署名に使う）。</summary>
    public VerifiedChain? VerifiedChain() => chain;

    /// <summary>検証した鎖の記録（追記する前に、追記後の鎖が通るかを確かめるのに使う）。家に入っていなければ null。</summary>
    public IReadOnlyList<ChainRecord>? VerifiedRecords() => chain is null ? null : records;

    /// <summary>表示用の最新のカーソル（別アカウントのものを含む）。</summary>
    public (string Writer, CursorBody Cursor)? LatestCursorBody() => latestCursor is { } c ? (c.Writer, c.Body) : null;

    /// <summary>表示用の最新のキュー（別アカウントのものを含む）。Snapshot は HandoffCodec の文字列。カーソルの currentItemId を引くのに使う。</summary>
    public (string Writer, QueueBody Queue)? LatestQueueBody() => latestQueue is { } q ? (q.Writer, q.Body) : null;

    /// <summary>表示用: 最新のキューの doc のハッシュ（カーソルの QueueRef と照らす）。</summary>
    public string? LatestQueueHash() => latestQueue?.Hash;

    /// <summary>今の接続で最新の状態をそろえた（welcome を処理し、鎖の取り直しを待っている状態が無い）。D-9 の判断はこれが true のときだけ行う。</summary>
    public bool StateSettled => welcomed && deferred.Count == 0;

    /// <summary>
    /// 同じアカウントの他の端末が今鳴っているか（D-9 の再生ボタンの判断）。最後のカーソルが再生中で、その端末が中継につながっているとき。
    /// </summary>
    public bool RemotePlayingNow()
    {
        var cursor = latestCursor;
        if (cursor is null) return false;
        var tag = effects.AccountTag();
        if (tag is null) return false;
        return cursor.Writer != signer.DeviceId && cursor.Body.Playing && cursor.Body.AccountTag == tag && online.Contains(cursor.Writer);
    }

    /// <summary>A 側: Confirm に入れる床（家に入っていなければ null）。</summary>
    public JoinInfo? CurrentJoinInfo()
    {
        var current = chain;
        if (current is null || storeId is not { } store) return null;
        return new JoinInfo { HouseholdId = current.HouseholdId, HeadSeq = current.HeadSeq, HeadHash = current.HeadHash, StoreId = store, CursorFloor = cursorFloor, QueueFloor = queueFloor };
    }

    /// <summary>
    /// B 側: 加入を待つ。次に受ける鎖が、この家の ID・頭を含み、その後に inviter の署名で自分を加える記録があるときだけ受け入れる。
    /// storeId と版の床もここで決まる（最初に受けた welcome の storeId を無条件に信じない）。
    /// </summary>
    public void ExpectJoin(JoinInfo info, string inviterDeviceId)
    {
        if (chain is not null) return;
        expectedJoin = (info, inviterDeviceId);
        creatingHousehold = false;
        storeId = info.StoreId;
        cursorFloor = info.CursorFloor;
        queueFloor = info.QueueFloor;
        Persist();
    }

    // --- 受信 ---

    private LibrarySync? library;

    /// <summary>ライブラリの同期（ADR-0013）。手元が変わったら Library.Publish を呼ぶ。</summary>
    public LibrarySync Library => library ??= new LibrarySync(signer, opener, send, () => chain, () => pendingStoreId is null ? storeId : null, epochMs, newId, body => effects.LibraryChanged(body));

    public void OnMessage(RelayV2 message)
    {
        switch (message)
        {
            case RelayV2.Library or RelayV2.LibraryOk or RelayV2.LibraryConflict: Library.OnMessage(message); break;
            case RelayV2.Welcome welcome: OnWelcome(welcome); break;
            case RelayV2.Chain c: OnChain(c.Records); break;
            // 自分の追記の返事（chain_ok）は、他の端末への chain_changed を受けないので、同じく取り直す（宛先を書き直すため）
            case RelayV2.ChainChanged changed: OnHeadAnnounced(changed.HeadSeq, changed.HeadHash); break;
            case RelayV2.ChainOk ok: OnHeadAnnounced(ok.HeadSeq, ok.HeadHash); break;
            case RelayV2.State state: OnState(state.Stored); break;
            case RelayV2.StateOk ok: OnStateOk(ok); break;
            case RelayV2.StateConflict conflict: OnStateConflict(conflict); break;
            case RelayV2.Error error: OnError(error); break;
            case RelayV2.YieldDelivery delivery: OnYield(delivery); break;
            case RelayV2.Control control: OnControl(control); break;
            case RelayV2.ActiveChanged changed: OnActiveChanged(changed); break;
            case RelayV2.PendingExpired expired: OnPendingExpired(expired.ClaimId); break;
            case RelayV2.Presence presence: online = presence.Online.ToHashSet(); break;
        }
    }

    /// <summary>接続が切れた。返事を待っていた書き込みは、つながり直した後に最新の版へ積み直す。</summary>
    public void OnDisconnected()
    {
        ResetConnection();
        Library.OnDisconnected();
        // 取り込む前に切れた引き取りは捨てる（つながり直した後に、古い状態から突然取り込まない）
        var waiting = transfer;
        if (waiting is not null && !waiting.Adopting) FailTransfer("disconnected");
    }

    private void ResetConnection()
    {
        foreach (var sent in inflight.Values) MarkUnsent(sent.Kind);
        inflight.Clear();
        serverActive = null;
        pendingOwner = null;
        online = new HashSet<string>();
        welcomed = false;
    }

    /// <summary>家を作ると押した。次に受ける最初の鎖が自分の genesis なら受け入れる。</summary>
    public void BeginCreateHousehold()
    {
        if (chain is not null) return;
        creatingHousehold = true;
        // 途中でやめた加入の待ちを残さない
        expectedJoin = null;
        Persist();
    }

    /// <summary>加入の待ちをやめた（時間切れ・取り下げ）。後でつながったときに、黙って家に入らない。</summary>
    public void CancelJoin()
    {
        if (chain is not null || expectedJoin is null) return;
        expectedJoin = null;
        Persist();
    }

    /// <summary>ログアウト・別のログインに変わった: 手元のキュー・再生・セッションを捨てる。</summary>
    public void ClearLocal()
    {
        session = null;
        localQueue = null;
        localQueueRef = null;
        local = null;
        lastOwnCursorHash = null;
        dirtyCursor = false;
        dirtyQueue = false;
        inflight.Clear();
        deferredEvidence.Clear();
        startWaitingForTag = false;
        resumePending = false;
        var waiting = transfer;
        transfer = null;
        if (waiting is not null) send(new RelayV2.Release { ClaimId = waiting.ClaimId });
    }

    /// <summary>
    /// 手元の再生が無くなった（ログインの世代が変わった・キューが空になった）。書き手なら、finalTag（その再生のアカウントのタグ）で
    /// 一時停止のカーソルを1回だけ送ってから、手元を捨てる。返事は待たない。
    /// </summary>
    public void EndLocal(string? finalTag)
    {
        var current = chain;
        var mine = session;
        var cursor = local;
        var queueRef = localQueueRef;
        if (current is not null && mine is not null && cursor is not null && queueRef is not null && finalTag is not null && pendingStoreId is null)
        {
            var body = CursorBodyFor(null, finalTag) is { } b ? b with { Playing = false } : null;
            if (body is not null)
            {
                var expect = Math.Max(latestCursor?.Version ?? 0, cursorFloor);
                var doc = DocSealer.Seal(signer, current, DocKind.Cursor, expect + 1, lastOwnCursorHash, epochMs(), ConnectJson.EncodeToUtf8(body));
                send(new RelayV2.StatePut { RequestId = newId(), Kind = StateKind.Cursor, ExpectVersion = expect, Doc = doc });
            }
        }
        ClearLocal();
    }

    /// <summary>アカウントタグができた（Verified になった）。タグが無い間に始めた再生が続いていれば claim する。</summary>
    public void OnAccountTagAvailable()
    {
        if (!startWaitingForTag) return;
        startWaitingForTag = false;
        if (local?.Playing == true && session is null) UserStartedPlayback();
    }

    private void OnHeadAnnounced(long headSeq, string? headHash)
    {
        var current = chain;
        if (current is null || headSeq > current.HeadSeq)
        {
            send(new RelayV2.ChainGet { FromSeq = records.Count });
        }
        else if (current.HashAt(headSeq) != headHash)
        {
            effects.Event(new ClientEvent.ChainForked());
        }
    }

    private void OnWelcome(RelayV2.Welcome welcome)
    {
        // 前の接続の書き込みは届いていないかもしれない（返事を待っていたものは積み直す）。transfer は捨てない
        ResetConnection();
        welcomed = true;
        serverActive = welcome.ActiveDeviceId;
        online = welcome.Online.ToHashSet();
        foreach (var (device, order) in welcome.ClaimOrders) claimOrders[device] = order;
        var known = storeId;
        if (known is null)
        {
            storeId = welcome.StoreId;
            Persist();
        }
        else if (known != welcome.StoreId)
        {
            pendingStoreId = welcome.StoreId;
            effects.Event(new ClientEvent.StoreReset(welcome.StoreId));
        }
        resumePending = IsWriter;
        if (welcome.HeadSeq is { } headSeq) OnHeadAnnounced(headSeq, welcome.HeadHash);
        // 最新の状態を先に見る（通信を失ったまま鳴っていた端末は、ここで止まる）
        foreach (var state in welcome.States) OnState(state);
        MaybeResume();
        Library.OnConnected();
    }

    /// <summary>本人が「この状態から始める」を押した（L-12）。床を捨てて新しい storeId を受け入れる。</summary>
    public void AcceptStoreReset()
    {
        var next = pendingStoreId;
        if (next is null) return;
        storeId = next;
        pendingStoreId = null;
        cursorFloor = 0;
        queueFloor = 0;
        latestCursor = null;
        latestQueue = null;
        Persist();
        // 書き手なら、新しい保存に最初から書き直す
        if (session is { } mine)
        {
            mine.Arbiter = new PlaybackArbiter.Session(0, null);
            SendClaimFor(mine);
            MarkDirty();
        }
        send(RelayV2.StateGet.Instance);
        Flush();
        Library.OnConnected();
    }

    private void OnChain(IReadOnlyList<ChainRecord> newRecords)
    {
        // 取得が重なって同じ記録が2回届いても、分岐にしない（seq で並べ、既に持っている分はハッシュを比べる）
        var fresh = new List<ChainRecord>();
        foreach (var record in newRecords)
        {
            var index = (int)record.Seq;
            if (index < 0)
            {
                effects.Event(new ClientEvent.ChainInvalid("seq"));
                return;
            }
            if (index < records.Count)
            {
                if (MemberChain.Hash(record) != MemberChain.Hash(records[index]))
                {
                    effects.Event(new ClientEvent.ChainForked());
                    return;
                }
            }
            else if (index == records.Count + fresh.Count)
            {
                fresh.Add(record);
            }
            else
            {
                effects.Event(new ClientEvent.ChainInvalid("gap"));
                return;
            }
        }
        if (fresh.Count == 0)
        {
            Flush();
            return;
        }
        var candidate = records.Concat(fresh).ToList();
        var join = expectedJoin;
        var trusted = chain is { } c ? new MemberChain.Head(c.HeadSeq, c.HeadHash) : join is { } j ? new MemberChain.Head(j.Info.HeadSeq, j.Info.HeadHash) : null;
        switch (MemberChain.Verify(candidate, trusted))
        {
            case ChainVerification.Ok result:
            {
                var joining = chain is null;
                if (joining)
                {
                    if (join is { } expected)
                    {
                        // 加入: 家の ID が Confirm と同じで、頭の後に inviter が自分を加えた記録があること
                        if (result.Chain.HouseholdId != expected.Info.HouseholdId)
                        {
                            effects.Event(new ClientEvent.ChainForked());
                            return;
                        }
                        // 招いた端末が自分を加えた記録があるか、6桁で確かめた A の頭の時点で自分が既に同じ鍵でいる
                        result.Chain.Members.TryGetValue(signer.DeviceId, out var existing);
                        var added =
                            candidate.Skip((int)(expected.Info.HeadSeq + 1)).Any(r => r.Type == ChainType.Add && r.Target?.DeviceId == signer.DeviceId && r.SignerId == expected.Inviter) ||
                            (existing is not null && existing.AddedSeq <= expected.Info.HeadSeq && Hpke.SerializePublicKey(existing.Kx).AsSpan().SequenceEqual(Hpke.SerializePublicKey(opener.KxPub)));
                        // まだ加えられていない（A の追記の前に取った）: 受け入れずに次の chain_changed を待つ
                        if (!added) return;
                        expectedJoin = null;
                    }
                    else if (creatingHousehold && candidate[0].Target?.DeviceId == signer.DeviceId && candidate[0].Type == ChainType.Genesis)
                    {
                        // 家を作ると押した端末: 最初の記録が自分の genesis であること
                        creatingHousehold = false;
                    }
                    else
                    {
                        // 加入も家を作るのも待っていない: 中継が勝手に作った家を信じない（D-12）
                        effects.Event(new ClientEvent.ChainInvalid("unexpected_household"));
                        return;
                    }
                }
                var headChanged = result.Chain.HeadHash != chain?.HeadHash;
                records = candidate;
                chain = result.Chain;
                Persist();
                // 書き手は、外した端末を宛先から外すために、次の書き込みを待たずに書き直す（D-2）
                if (headChanged && IsWriter) MarkDirty();
                if (joining) effects.Event(new ClientEvent.Joined());
                var pending = deferred.ToList();
                deferred.Clear();
                foreach (var state in pending) OnState(state);
                MaybeResume();
                Flush();
                // 家に入ったばかりなら、つながったときの library_get は家の外として断られている。頼み直す
                if (joining) Library.OnConnected();
                else Library.OnChainUpdated();
                break;
            }
            case ChainVerification.Fork:
                effects.Event(new ClientEvent.ChainForked());
                break;
            case ChainVerification.Rollback:
                effects.Event(new ClientEvent.ChainRolledBack());
                break;
            case ChainVerification.Invalid invalid:
                effects.Event(new ClientEvent.ChainInvalid(invalid.Reason));
                break;
        }
    }

    private void OnState(StoredState stored)
    {
        if (pendingStoreId is not null && stored.StoreId == pendingStoreId) return;
        if (stored.StoreId != storeId) return;
        var current = chain;
        if (current is null)
        {
            deferred.Add(stored);
            return;
        }
        // 開く前に、開いても捨てるもの（床より古い・持っている版より古い・持っているものと同じ）を除く（鍵交換の回数を減らす）
        if (AlreadyHave(stored)) return;
        DocOpen.Ok opened;
        switch (DocSealer.Open(stored.Doc, current, opener))
        {
            case DocOpen.Ok ok:
                opened = ok;
                break;
            case DocOpen.NeedChain:
                deferred.Add(stored);
                send(new RelayV2.ChainGet { FromSeq = records.Count });
                return;
            case DocOpen.Fork:
                effects.Event(new ClientEvent.ChainForked());
                return;
            default:
                return;
        }
        if (opened.Header.Version != stored.Version || opened.Header.Kind != stored.Kind.DocKind()) return;
        var hash = DocSealer.Hash(stored.Doc);
        switch (stored.Kind)
        {
            case StateKind.Cursor:
            {
                if (stored.Version < cursorFloor) return;
                var existing = latestCursor;
                if (existing is not null && existing.Version == stored.Version)
                {
                    if (existing.Hash != hash && existing.Writer == stored.Doc.Header.WriterId) effects.Event(new ClientEvent.StateEquivocation());
                    return;
                }
                if (existing is not null && existing.Version > stored.Version) return;
                var body = ConnectJson.Decode<CursorBody>(opened.Plaintext);
                if (body is null) return;
                var cursor = new Opened<CursorBody>(stored, body, hash, nowMs());
                latestCursor = cursor;
                cursorFloor = stored.Version;
                PersistFloor();
                OnCursor(cursor);
                break;
            }
            case StateKind.Queue:
            {
                if (stored.Version < queueFloor) return;
                if ((latestQueue?.Version ?? -1) >= stored.Version) return;
                var body = ConnectJson.Decode<QueueBody>(opened.Plaintext);
                if (body is null) return;
                latestQueue = new Opened<QueueBody>(stored, body, hash, nowMs());
                queueFloor = stored.Version;
                PersistFloor();
                break;
            }
        }
    }

    /// <summary>開いても捨てるだけの状態か（同じ版で中身が違うものは、署名を確かめて食い違いを知らせるために開く）。</summary>
    private bool AlreadyHave(StoredState stored) => stored.Kind switch
    {
        StateKind.Cursor => stored.Version < cursorFloor ||
            (latestCursor is { } existing && (existing.Version > stored.Version || (existing.Version == stored.Version && existing.Hash == DocSealer.Hash(stored.Doc)))),
        _ => stored.Version < queueFloor || (latestQueue?.Version ?? -1) >= stored.Version,
    };

    private void OnCursor(Opened<CursorBody> cursor)
    {
        if (cursor.Writer == signer.DeviceId) return;
        var accountMatches = cursor.Body.AccountTag == effects.AccountTag();
        effects.Event(new ClientEvent.RemoteChanged(cursor.Writer, cursor.Body, accountMatches));
        var waiting = transfer;
        if (waiting is not null && !waiting.Adopting && cursor.Writer == waiting.PreviousWriter && !cursor.Body.Playing)
        {
            // 前の再生中の端末が最後のカーソル（一時停止）を置いた → すぐ取り込む
            CompleteTransfer();
            return;
        }
        if (!accountMatches || !cursor.Body.Playing) return;
        Decide(new PlaybackArbiter.Evidence.PlayingCursor(cursor.Writer, cursor.Body.SessionBase, cursor.Body.SessionStartVersion));
    }

    private void OnYield(RelayV2.YieldDelivery delivery)
    {
        var current = chain;
        if (current is null) return;
        if (DocSealer.Open(delivery.YieldDoc, current, opener) is not DocOpen.Ok opened) return;
        if (ConnectJson.Decode<CommandBody>(opened.Plaintext) is not CommandBody.Yield command) return;
        if (command.To != signer.DeviceId || command.From != opened.Header.WriterId || command.ClaimId != delivery.ClaimId) return;
        // yield を出せるのは player だけ
        if (!current.Members.TryGetValue(command.From, out var from) || from.Keys.Role != Role.Player) return;
        if (handledClaims.Contains(command.ClaimId)) return;
        Remember(command.ClaimId);
        // 別のアカウントの端末の再生では止まらない（L-11）
        if (command.AccountTag != effects.AccountTag()) return;
        var wasWriter = IsWriter;
        var stopped = Decide(new PlaybackArbiter.Evidence.Yield(command.From, command.SessionBase));
        // 引き取り（transfer）なら、最後のカーソルを置く（相手はそれを待って取り込む）
        if (stopped && wasWriter && pendingOwner == command.From) SendYielded(command.ClaimId);
    }

    private void OnActiveChanged(RelayV2.ActiveChanged message)
    {
        serverActive = message.ActiveDeviceId;
        pendingOwner = message.PendingOwner;
        var claimer = message.PendingOwner ?? message.ActiveDeviceId;
        if (message.ClaimId is not null && claimer is not null)
        {
            claimOrders[claimer] = message.ClaimOrder;
            if (session is { } mine && mine.ClaimId == message.ClaimId) mine.ClaimOrder = message.ClaimOrder;
        }
        // 順番が分かったので、保留していた証拠で決め直す
        if (deferredEvidence.Count > 0)
        {
            var pending = deferredEvidence.ToList();
            deferredEvidence.Clear();
            foreach (var evidence in pending)
            {
                if (Decide(evidence)) break;
            }
        }
        // 自分が再生中になったら、断られていた書き込みを送る
        if (message.ActiveDeviceId == signer.DeviceId || message.PendingOwner == signer.DeviceId) Flush();
    }

    private void OnPendingExpired(string claimId)
    {
        var waiting = transfer;
        if (waiting is null || waiting.ClaimId != claimId) return;
        transfer = null;
        if (waiting.Adopting && IsWriter)
        {
            // 取り込んで鳴っているのに、最初のカーソルが期限に間に合わなかった: 新しく始めたものとして claim し直す
            RestartSession();
        }
        else
        {
            effects.Event(new ClientEvent.TransferFailed("expired"));
        }
    }

    /// <returns>書き手でなくなったか</returns>
    private bool Decide(PlaybackArbiter.Evidence evidence)
    {
        var mine = session;
        if (mine is null) return false;
        long? otherOrder = claimOrders.TryGetValue(evidence.From, out var o) ? o : null;
        var result = PlaybackArbiter.Decide(signer.DeviceId, mine.Arbiter, evidence, mine.ClaimOrder, otherOrder);
        switch (result.Decision)
        {
            case PlaybackArbiter.Decision.Keep:
                return false;
            case PlaybackArbiter.Decision.Defer:
                deferredEvidence.RemoveAll(e => e.From == evidence.From);
                deferredEvidence.Add(evidence);
                return false;
        }
        var wasPlaying = local?.Playing == true;
        EndSession();
        // 止めた時点の位置を取る（引き取られるときの最後のカーソルを、最後の出来事の位置にしない）
        local = effects.Pause() ?? (local is { } l ? l with { Playing = false, PositionMs = CurrentPosition(l), CapturedAtMs = nowMs() } : null);
        // 一時停止中なら鳴らしていないので、書き手でなくなるだけ（知らせない）
        if (wasPlaying) effects.Event(new ClientEvent.Stopped(evidence.From));
        return true;
    }

    private void EndSession()
    {
        session = null;
        retreated = true;
        deferredEvidence.Clear();
        dirtyCursor = false;
        dirtyQueue = false;
        Persist();
    }

    private void SendYielded(string claimId)
    {
        var current = chain;
        if (current is null) return;
        var body = CursorBodyFor(null);
        if (body is null) return;
        var expect = Math.Max(latestCursor?.Version ?? 0, cursorFloor);
        var doc = DocSealer.Seal(signer, current, DocKind.Cursor, expect + 1, lastOwnCursorHash, epochMs(), ConnectJson.EncodeToUtf8(body));
        send(new RelayV2.Yielded { ClaimId = claimId, ExpectVersion = expect, Doc = doc });
    }

    private void OnStateOk(RelayV2.StateOk ok)
    {
        if (!inflight.Remove(ok.RequestId, out var sent)) return;
        var stored = new StoredState { Kind = sent.Kind, Version = ok.Version, Doc = sent.Doc, StoreId = storeId ?? "", ReceivedAtServerMs = 0, ServerNowMs = 0, ActiveDeviceId = serverActive };
        switch (ok.Kind)
        {
            case StateKind.Cursor:
                if (sent.Cursor is not null) latestCursor = new Opened<CursorBody>(stored, sent.Cursor, sent.Hash, nowMs());
                cursorFloor = Math.Max(cursorFloor, ok.Version);
                var mine = session;
                if (mine is not null && mine.Id == sent.SessionId && mine.Arbiter.StartVersion is null)
                {
                    mine.Arbiter = mine.Arbiter with { StartVersion = ok.Version };
                }
                if (transfer is { Adopting: true } adopted)
                {
                    transfer = null;
                    effects.Event(new ClientEvent.TransferCompleted(adopted.PreviousWriter));
                }
                break;
            case StateKind.Queue:
                if (sent.Queue is not null) latestQueue = new Opened<QueueBody>(stored, sent.Queue, sent.Hash, nowMs());
                queueFloor = Math.Max(queueFloor, ok.Version);
                break;
        }
        Persist();
        Flush();
        MaybeHandOff();
    }

    private void OnStateConflict(RelayV2.StateConflict conflict)
    {
        var waiting = transfer;
        if (waiting is not null && conflict.RequestId == waiting.ClaimId)
        {
            if (conflict.Latest is not null) OnState(conflict.Latest);
            transfer = null;
            if (!waiting.Retried)
            {
                RequestTransfer(retried: true);
            }
            else
            {
                effects.Event(new ClientEvent.TransferFailed("conflict"));
            }
            return;
        }
        if (!inflight.TryGetValue(conflict.RequestId, out var sent)) return;
        var latest = conflict.Latest;
        // 返事が落ちて送り直した自分の書き込みと同じものがサーバーにあるなら、成功とみなす
        if (latest is not null && latest.Doc.Header.WriterId == signer.DeviceId && DocSealer.Hash(latest.Doc) == sent.Hash)
        {
            OnStateOk(new RelayV2.StateOk { RequestId = conflict.RequestId, Kind = sent.Kind, Version = latest.Version });
            return;
        }
        inflight.Remove(conflict.RequestId);
        if (latest is not null) OnState(latest);
        // 止まっていなければ、最新の版に積み直す
        if (IsWriter) MarkUnsent(sent.Kind);
        Flush();
    }

    private void OnError(RelayV2.Error error)
    {
        Inflight? sent = null;
        if (error.RequestId is not null && inflight.Remove(error.RequestId, out var removed)) sent = removed;
        switch (error.Code)
        {
            // 書き込みを断られた: 持っている最新の証拠で止まる理由が無く、自分の claim が一番後（または誰も再生中でない）なら、すぐ claim し直す。
            // 他の端末の claim の方が後なら、その端末の証拠を待つ（断られるたびに奪い合わない）
            case "not_active":
                if (sent is not null && IsWriter)
                {
                    MarkUnsent(sent.Kind);
                    var latest = latestCursor;
                    var stopped = latest is not null && latest.Writer != signer.DeviceId && latest.Body.Playing && latest.Body.AccountTag == effects.AccountTag() &&
                        Decide(new PlaybackArbiter.Evidence.PlayingCursor(latest.Writer, latest.Body.SessionBase, latest.Body.SessionStartVersion));
                    var mine = session;
                    var others = claimOrders.Where(kv => kv.Key != signer.DeviceId).Select(kv => kv.Value).ToList();
                    long? latestOther = others.Count == 0 ? null : others.Max();
                    var mineIsLatest = mine?.ClaimOrder is { } myOrder && (latestOther is null || myOrder > latestOther);
                    if (!stopped && IsWriter && (serverActive is null || mineIsLatest) && mine?.ClaimOrder is not null) RestartSession();
                }
                break;
            case "stale_head":
                if (sent is not null && IsWriter) MarkUnsent(sent.Kind);
                send(new RelayV2.ChainGet { FromSeq = records.Count });
                break;
            default:
                if (error.RequestId is not null && error.RequestId == transfer?.ClaimId)
                {
                    FailTransfer(error.Code);
                }
                else
                {
                    effects.Event(new ClientEvent.ServerError(error.Code));
                }
                break;
        }
        // 断られた書き込みは、ここで送り直さない（次の状態・active_changed・鎖の取得で送る）
    }

    // --- 手元の操作 ---

    /// <summary>手元のキューが変わった（HandoffCodec.Encode の文字列）。書き手なら置く。</summary>
    public void SetLocalQueue(string snapshot)
    {
        // 中身が同じなら置き直さない
        if (snapshot == localQueue) return;
        localQueue = snapshot;
        if (IsWriter)
        {
            dirtyQueue = true;
            // キューを置き直すので、カーソルも新しいキューを指し直す
            dirtyCursor = true;
            Flush();
        }
    }

    /// <summary>手元の再生の位置・再生中か等が変わった。書き手なら置く（間引きは呼ぶ側）。</summary>
    public void SetLocalCursor(LocalCursor cursor)
    {
        local = cursor;
        if (IsWriter)
        {
            dirtyCursor = true;
            Flush();
        }
    }

    /// <summary>
    /// 本人が手元で新しく再生を始めた・再開した（L-3）。新しいセッションとして claim し、キューとカーソルを置く。
    /// </summary>
    /// <returns>claim を送ったか（家に入っていない・Verified でない・controller・家の再生が別のアカウントなら false。呼ぶ側は手元だけで再生する）</returns>
    public bool UserStartedPlayback()
    {
        if (role != Role.Player || chain is null || storeId is null || pendingStoreId is not null) return false;
        var tag = effects.AccountTag();
        if (tag is null)
        {
            startWaitingForTag = true;
            return false;
        }
        // 家の最新の再生が別のアカウントなら、claim しない
        var latest = latestCursor;
        if (latest is not null && latest.Writer != signer.DeviceId && latest.Body.AccountTag != tag) return false;
        retreated = false;
        RestartSession();
        return true;
    }

    /// <summary>新しいセッションを始めて claim する（手元で始めた・書き込みを断られた・取り込みが期限に遅れた）。</summary>
    private void RestartSession()
    {
        var next = new Session(++sessionCounter, new PlaybackArbiter.Session(Math.Max(latestCursor?.Version ?? 0, cursorFloor), null), null);
        session = next;
        deferredEvidence.Clear();
        // 前のセッションの書き込みの返事は待たない（新しいセッションで積み直す）
        inflight.Clear();
        Persist();
        SendClaimFor(next);
        MarkDirty();
        Flush();
    }

    private void SendClaimFor(Session target)
    {
        var current = chain;
        if (current is null) return;
        var tag = effects.AccountTag();
        if (tag is null) return;
        var claimId = newId();
        Remember(claimId);
        target.ClaimId = claimId;
        target.ClaimOrder = null;
        var to = latestCursor?.Writer is { } w && w != signer.DeviceId ? w : "";
        var command = new CommandBody.Yield { ClaimId = claimId, From = signer.DeviceId, To = to, SessionBase = target.Arbiter.Base, AccountTag = tag };
        send(new RelayV2.Claim { ClaimId = claimId, YieldDoc = SealCommand(current, command) });
    }

    /// <summary>つながり直した後: 最新の状態と鎖がそろっていて、まだ書き手なら、claim し直して積み直す。</summary>
    private void MaybeResume()
    {
        if (!resumePending || deferred.Count > 0 || chain is null) return;
        resumePending = false;
        var mine = session;
        if (mine is null) return;
        if (serverActive != signer.DeviceId)
        {
            // 切れている間に始めた再生（claim が届いていない）や、切れている間に再生中でなくなった場合
            SendClaimFor(mine);
        }
        MarkDirty();
        Flush();
    }

    /// <summary>送った書き込みが届いていない・断られた: その種類を積み直す。</summary>
    private void MarkUnsent(StateKind kind)
    {
        if (kind == StateKind.Cursor) dirtyCursor = true;
        else dirtyQueue = true;
    }

    private void MarkDirty()
    {
        dirtyCursor = local is not null;
        dirtyQueue = localQueue is not null;
    }

    /// <summary>
    /// 「ここで再生」（D-7）。最新のカーソルとキューを開けていて、アカウントが一致するときだけ送る。
    /// 取り込みは、前の端末の最後のカーソルが届いたとき、または3秒後（Tick）に行う。
    /// </summary>
    public bool RequestTransfer() => RequestTransfer(retried: false);

    /// <summary>ログインを渡す命令の平文の上限（詰め物の2ブロック、中継の 16KB に収まる。Android と同じ）。</summary>
    internal const int MaxLoginGrantPlaintext = 8000;

    /// <summary>
    /// 家の端末 target（wave-api）にログインを渡す（ADR-0015、Android の sendLoginGrant と同じ）。本人が押したときだけ呼ぶ。target の受け取りの鍵にだけ包む。
    /// </summary>
    /// <returns>送ったか（target が家の player でない・自分・大きすぎるなら false）</returns>
    public bool SendLoginGrant(string target, string cookie, string? visitorData, string? dataSyncId, long obtainedAtEpochMs)
    {
        if (pendingStoreId is not null || target == signer.DeviceId) return false;
        var current = chain;
        if (current is null || !current.Members.ContainsKey(signer.DeviceId) || !current.Members.TryGetValue(target, out var member) || member.Keys.Role != Role.Player) return false;
        var grant = new CommandBody.LoginGrant { CommandId = newId(), From = signer.DeviceId, To = target, Cookie = cookie, VisitorData = visitorData, DataSyncId = dataSyncId, ObtainedAtEpochMs = obtainedAtEpochMs };
        var plaintext = ConnectJson.EncodeToUtf8<CommandBody>(grant);
        if (plaintext.Length > MaxLoginGrantPlaintext) return false;
        return send(new RelayV2.Control { Doc = DocSealer.Seal(signer, current, DocKind.Command, 1, null, epochMs(), plaintext, new HashSet<string> { target }) });
    }

    /// <summary>家の端末 target に「ここで再生（引き取り）」を頼む（デバイスの一覧で端末を選んだ）。</summary>
    public bool RequestRemoteTransfer(string target)
    {
        if (pendingStoreId is not null || target == signer.DeviceId) return false;
        var current = chain;
        if (current is null || !current.Members.TryGetValue(target, out var member) || member.Keys.Role != Role.Player) return false;
        var tag = effects.AccountTag();
        if (tag is null || latestCursor is not { } cursor || cursor.Body.AccountTag != tag) return false;
        var command = new CommandBody.Control { CommandId = newId(), From = signer.DeviceId, To = target, Action = ControlAction.Transfer, PositionMs = null, BasedOnVersion = cursor.Version, AccountTag = tag };
        return send(new RelayV2.Control { Doc = SealCommand(current, command) });
    }

    /// <summary>
    /// 手元のキューで家の再生を引き受け（音は出さない）、それを書き終えたら target に引き取ってもらう
    /// （ほかの端末で鳴っている間に曲を選んだら、その端末で鳴らす）。呼ぶ前に SetLocalQueue・SetLocalCursor（再生中でない）を置く。
    /// </summary>
    public bool PlayOn(string target)
    {
        if (target == signer.DeviceId || !UserStartedPlayback()) return false;
        handOff = (target, nowMs());
        return true;
    }

    private (string Target, long StartedAtMs)? handOff;
    private const long HandOffWaitMs = 10_000;

    private void MaybeHandOff()
    {
        if (handOff is not { } waiting || session is null) return;
        if (latestCursor is not { } cursor || latestQueue is not { } queue) return;
        if (cursor.Writer != signer.DeviceId || queue.Writer != signer.DeviceId || cursor.Body.QueueRef != queue.Hash) return;
        handOff = null;
        RequestRemoteTransfer(waiting.Target);
    }

    /// <summary>この端末の表示名を家のほかの端末に知らせる。</summary>
    public bool SendProfile(string name, string? kind = null)
    {
        var current = chain;
        if (current is null || !current.Members.ContainsKey(signer.DeviceId)) return false;
        return send(new RelayV2.Control { Doc = SealCommand(current, new CommandBody.Profile { From = signer.DeviceId, Name = name.Length > 64 ? name[..64] : name, Kind = kind }) });
    }

    /// <summary>遠隔操作を送る（鳴っていない端末から、今鳴らしている同じアカウントの端末へ）。</summary>
    /// <returns>送ったか（家の再生が自分・別のアカウント・最新の状態をまだ見ていないなら false）</returns>
    public bool SendControl(ControlAction action, long? positionMs = null)
    {
        if (IsWriter || pendingStoreId is not null) return false;
        var current = chain;
        if (current is null) return false;
        var tag = effects.AccountTag();
        if (tag is null) return false;
        var cursor = latestCursor;
        if (cursor is null || cursor.Writer == signer.DeviceId || cursor.Body.AccountTag != tag) return false;
        var command = new CommandBody.Control
        {
            CommandId = newId(),
            From = signer.DeviceId,
            To = cursor.Writer,
            Action = action,
            PositionMs = positionMs is { } p ? Math.Max(0, p) : null,
            BasedOnVersion = cursor.Version,
            AccountTag = tag,
            OnItemId = action is ControlAction.Next or ControlAction.Previous or ControlAction.Seek ? cursor.Body.CurrentItemId : null,
        };
        return send(new RelayV2.Control { Doc = SealCommand(current, command) });
    }

    /// <summary>遠隔の音量を送る（鳴っていない端末から、今鳴らしている同じアカウントの端末へ。そのアプリの中の音量を 0〜100 にする）。</summary>
    public bool SendVolume(int volumePercent)
    {
        if (IsWriter || pendingStoreId is not null) return false;
        var current = chain;
        if (current is null) return false;
        var tag = effects.AccountTag();
        if (tag is null) return false;
        var cursor = latestCursor;
        if (cursor is null || cursor.Writer == signer.DeviceId || cursor.Body.AccountTag != tag) return false;
        var command = new CommandBody.Control
        {
            CommandId = newId(),
            From = signer.DeviceId,
            To = cursor.Writer,
            Action = ControlAction.Volume,
            BasedOnVersion = cursor.Version,
            AccountTag = tag,
            VolumePercent = Math.Clamp(volumePercent, 0, 100),
        };
        return send(new RelayV2.Control { Doc = SealCommand(current, command) });
    }

    /// <summary>キューへの追加の命令1件の平文の上限（詰め物の1ブロック 4096 から長さの欄の 4 バイトを引き、余裕を持たせる。Android と同じ）。</summary>
    internal const int MaxQueueAddPlaintext = 3800;

    /// <summary>
    /// 遠隔のキューへの追加を送る（鳴っていない端末から、今鳴らしている同じアカウントの端末へ）。
    /// 1件の平文が詰め物の1ブロックに収まるよう分けて送り、2件目からは前の件の最後の曲の後ろに入れてもらう。
    /// </summary>
    /// <returns>全部送ったか（家の再生が自分・別のアカウント・最新の状態をまだ見ていない・1曲でも収まらないなら false）</returns>
    public bool SendQueueAdd(IReadOnlyList<HandoffItem> items, bool next)
    {
        if (items.Count == 0 || IsWriter || pendingStoreId is not null) return false;
        var current = chain;
        if (current is null) return false;
        var tag = effects.AccountTag();
        if (tag is null) return false;
        var cursor = latestCursor;
        if (cursor is null || cursor.Writer == signer.DeviceId || cursor.Body.AccountTag != tag) return false;
        // 大きさは、ID の欄を UUID の長さで埋めた形で量る（実際の ID は送るときに振る）
        const string placeholder = "00000000-0000-0000-0000-000000000000";
        CommandBody Command(string id, IReadOnlyList<HandoffItem> list, string? after) => new CommandBody.QueueAdd
        {
            CommandId = id,
            From = signer.DeviceId,
            To = cursor.Writer,
            Items = list,
            Next = next,
            AfterItemId = after,
            BasedOnVersion = cursor.Version,
            AccountTag = tag,
        };
        int Size(IReadOnlyList<HandoffItem> list) => ConnectJson.EncodeToUtf8(Command(placeholder, list, placeholder)).Length;
        var chunks = new List<List<HandoffItem>>();
        var chunk = new List<HandoffItem>();
        foreach (var item in items)
        {
            if (Size([item]) > MaxQueueAddPlaintext) return false;
            if (chunk.Count > 0 && Size([.. chunk, item]) > MaxQueueAddPlaintext)
            {
                chunks.Add(chunk);
                chunk = [];
            }
            chunk.Add(item);
        }
        chunks.Add(chunk);
        string? afterId = null;
        foreach (var part in chunks)
        {
            if (!send(new RelayV2.Control { Doc = SealCommand(current, Command(newId(), part, afterId)) })) return false;
            afterId = part[^1].ItemId;
        }
        return true;
    }

    /// <summary>
    /// 遠隔のキューの並べ替えを送る（鳴っていない端末のキューの画面から、今鳴らしている同じアカウントの端末へ）。
    /// itemId の曲を afterItemId の曲の直後へ（無ければ今の曲の直後へ）動かしてもらう。
    /// </summary>
    /// <returns>送ったか（家の再生が自分・別のアカウント・最新の状態をまだ見ていないなら false）</returns>
    public bool SendQueueMove(string itemId, string? afterItemId) => SendQueueEdit(CommandBody.QueueEdit.Move, [itemId], afterItemId);

    /// <summary>遠隔のキューから外すのを送る。条件は SendQueueMove と同じ。</summary>
    public bool SendQueueRemove(IReadOnlyList<string> itemIds) => SendQueueEdit(CommandBody.QueueEdit.Remove, itemIds, null);

    private bool SendQueueEdit(string op, IReadOnlyList<string> itemIds, string? afterItemId)
    {
        if (itemIds.Count == 0 || IsWriter || pendingStoreId is not null) return false;
        var current = chain;
        if (current is null) return false;
        var tag = effects.AccountTag();
        if (tag is null) return false;
        var cursor = latestCursor;
        if (cursor is null || cursor.Writer == signer.DeviceId || cursor.Body.AccountTag != tag) return false;
        CommandBody command = new CommandBody.QueueEdit
        {
            CommandId = newId(),
            From = signer.DeviceId,
            To = cursor.Writer,
            Op = op,
            ItemIds = itemIds,
            AfterItemId = afterItemId,
            BasedOnVersion = cursor.Version,
            AccountTag = tag,
        };
        if (ConnectJson.EncodeToUtf8(command).Length > MaxQueueAddPlaintext) return false;
        return send(new RelayV2.Control { Doc = SealCommand(current, command) });
    }

    /// <summary>表示用: ほかの端末の最新のカーソル（別アカウントのものを含む）と、今の時刻に合わせた位置。</summary>
    public RemoteNow? RemoteNowPosition()
    {
        var cursor = latestCursor;
        if (cursor is null || cursor.Writer == signer.DeviceId) return null;
        return new RemoteNow(cursor.Writer, cursor.Body, ExtrapolatedPosition(cursor), cursor.Version);
    }

    /// <summary>
    /// 遠隔操作を受けた。自分宛て・送り主が署名した端末・処理していない ID・この端末が書き手・同じアカウント・
    /// 送り手が見ていた版がこの端末の最新の書き込みに近いときだけ従う（中継が取っておいた古い操作を後から流しても、版が離れていれば従わない）。
    /// </summary>
    private void OnControl(RelayV2.Control message)
    {
        var current = chain;
        if (current is null || message.Doc.Header.Kind != DocKind.Command) return;
        // 中継が同じ命令を流し直しても、開く（鍵交換をする）前に捨てる。署名の対象のハッシュなので、再送なら必ず同じ値
        var docHash = DocSealer.Hash(message.Doc);
        if (seenControlDocs.Contains(docHash)) return;
        seenControlDocs.AddLast(docHash);
        while (seenControlDocs.Count > MaxHandledClaims) seenControlDocs.RemoveFirst();
        if (DocSealer.Open(message.Doc, current, opener) is not DocOpen.Ok opened) return;
        var body = ConnectJson.Decode<CommandBody>(opened.Plaintext);
        if (body is CommandBody.Profile profile)
        {
            if (profile.From == opened.Header.WriterId && profile.From != signer.DeviceId && current.Members.ContainsKey(profile.From)) effects.Event(new ClientEvent.Profile(profile.From, profile.Name, profile.Kind));
            return;
        }
        if (body is CommandBody.QueueAdd add)
        {
            OnQueueAdd(add, opened.Header, current);
            return;
        }
        if (body is CommandBody.QueueEdit edit)
        {
            OnQueueEdit(edit, opened.Header, current);
            return;
        }
        if (body is not CommandBody.Control command) return;
        if (command.To != signer.DeviceId || command.From != opened.Header.WriterId || command.From == signer.DeviceId) return;
        // 出してから5分を超えた命令には従わない（中継が取っておいて後から届けるのを、時間でも縛る。順序の判断には時計を使わない）
        if (Math.Abs(epochMs() - opened.Header.IssuedAtEpochMs) > MaxControlAgeMs) return;
        // 遠隔操作を出せるのは player だけ（yield と同じ）
        if (!current.Members.TryGetValue(command.From, out var from) || from.Keys.Role != Role.Player) return;
        if (handledClaims.Contains(command.CommandId)) return;
        Remember(command.CommandId);
        if (command.AccountTag != effects.AccountTag()) return;
        if (command.Action == ControlAction.Transfer)
        {
            // 「ここで再生」を頼まれた: 書き手でなく、送り手が見ていた家の最新のカーソルが自分の見ているものに近いときだけ
            if (IsWriter || latestCursor is not { } latest) return;
            if (command.BasedOnVersion > latest.Version || latest.Version - command.BasedOnVersion > MaxControlLag) return;
            if (effects.Control(ControlAction.Transfer, null)) RequestTransfer();
            return;
        }
        if (session is not { } mine) return;
        if (latestCursor is not { } own || own.Writer != signer.DeviceId) return;
        if (command.BasedOnVersion > own.Version || own.Version - command.BasedOnVersion > MaxControlLag) return;
        // 今のセッションより前の見え方に基づく命令には従わない
        if (mine.Arbiter.StartVersion is not { } sessionStart || command.BasedOnVersion < sessionStart) return;
        if (command.Action == ControlAction.Seek && command.PositionMs is null) return;
        if (command.Action == ControlAction.Volume)
        {
            if (command.VolumePercent is { } percent && percent is >= 0 and <= 100) effects.Volume(percent);
            return;
        }
        // 送り手が見ていた曲から、もう次の曲に変わっていたら従わない（遅れて届いた「次へ」で2曲飛ばさない）
        // 比べるのは手元で今鳴っている曲（中継に書いた最新より先に進んでいることがある）
        if (command.OnItemId is not null && command.OnItemId != (local?.CurrentItemId ?? own.Body.CurrentItemId)) return;
        effects.Control(command.Action, command.PositionMs);
    }

    /// <summary>遠隔のキューへの追加を受けた。従う条件は再生・一時停止などの遠隔操作と同じ。</summary>
    private void OnQueueAdd(CommandBody.QueueAdd command, DocHeader header, VerifiedChain current)
    {
        if (command.To != signer.DeviceId || command.From != header.WriterId || command.From == signer.DeviceId) return;
        if (Math.Abs(epochMs() - header.IssuedAtEpochMs) > MaxControlAgeMs) return;
        if (!current.Members.TryGetValue(command.From, out var from) || from.Keys.Role != Role.Player) return;
        if (handledClaims.Contains(command.CommandId)) return;
        Remember(command.CommandId);
        if (command.AccountTag != effects.AccountTag() || command.Items.Count == 0) return;
        if (session is not { } mine) return;
        if (latestCursor is not { } own || own.Writer != signer.DeviceId) return;
        if (command.BasedOnVersion > own.Version || own.Version - command.BasedOnVersion > MaxControlLag) return;
        if (mine.Arbiter.StartVersion is not { } sessionStart || command.BasedOnVersion < sessionStart) return;
        effects.QueueAdd(command.Items, command.Next, command.AfterItemId);
    }

    /// <summary>遠隔のキューの並べ替え・外すを受けた。従う条件は遠隔のキューへの追加と同じ。</summary>
    private void OnQueueEdit(CommandBody.QueueEdit command, DocHeader header, VerifiedChain current)
    {
        if (command.To != signer.DeviceId || command.From != header.WriterId || command.From == signer.DeviceId) return;
        if (Math.Abs(epochMs() - header.IssuedAtEpochMs) > MaxControlAgeMs) return;
        if (!current.Members.TryGetValue(command.From, out var from) || from.Keys.Role != Role.Player) return;
        if (handledClaims.Contains(command.CommandId)) return;
        Remember(command.CommandId);
        if (command.AccountTag != effects.AccountTag() || command.ItemIds.Count == 0) return;
        if (session is not { } mine) return;
        if (latestCursor is not { } own || own.Writer != signer.DeviceId) return;
        if (command.BasedOnVersion > own.Version || own.Version - command.BasedOnVersion > MaxControlLag) return;
        if (mine.Arbiter.StartVersion is not { } sessionStart || command.BasedOnVersion < sessionStart) return;
        switch (command.Op)
        {
            case CommandBody.QueueEdit.Move when command.ItemIds.Count == 1:
                effects.QueueMove(command.ItemIds[0], command.AfterItemId);
                break;
            case CommandBody.QueueEdit.Remove:
                effects.QueueRemove(command.ItemIds);
                break;
        }
    }

    private bool RequestTransfer(bool retried)
    {
        if (role != Role.Player || transfer is not null || pendingStoreId is not null) return false;
        var current = chain;
        if (current is null) return false;
        var tag = effects.AccountTag();
        if (tag is null) return false;
        var cursor = latestCursor;
        var queue = latestQueue;
        if (cursor is null || queue is null) return false;
        if (cursor.Writer == signer.DeviceId || cursor.Body.AccountTag != tag || queue.Body.AccountTag != tag || cursor.Body.QueueRef != queue.Hash) return false;
        var claimId = newId();
        Remember(claimId);
        transfer = new TransferState(claimId, cursor.Writer, nowMs(), retried);
        var command = new CommandBody.Yield { ClaimId = claimId, From = signer.DeviceId, To = cursor.Writer, SessionBase = cursor.Version, AccountTag = tag };
        if (!send(new RelayV2.Transfer { ClaimId = claimId, BasedOnVersion = cursor.Version, YieldDoc = SealCommand(current, command) }))
        {
            transfer = null;
            return false;
        }
        return true;
    }

    /// <summary>時間の経過。transfer で前の端末から返事が無ければ、最後の状態から取り込む。</summary>
    public void Tick()
    {
        if (floorDirty && (lastPersistMs is not { } last || nowMs() - last >= FloorPersistMs)) Persist();
        // 引き受けたものを書けないまま10秒たったら、頼むのをやめる
        if (handOff is { } h && nowMs() - h.StartedAtMs > HandOffWaitMs) handOff = null;
        var waiting = transfer;
        if (waiting is null) return;
        if (!waiting.Adopting && nowMs() - waiting.StartedAtMs >= TransferWaitMs) CompleteTransfer();
    }

    private void CompleteTransfer()
    {
        var waiting = transfer;
        if (waiting is null) return;
        var cursor = latestCursor;
        var queue = latestQueue;
        if (cursor is null || queue is null || cursor.Body.QueueRef != queue.Hash)
        {
            FailTransfer("no_state", release: waiting.ClaimId);
            return;
        }
        var position = ExtrapolatedPosition(cursor);
        waiting.Adopting = true;
        if (!effects.Adopt(queue.Body.Snapshot, cursor.Body, position))
        {
            FailTransfer("not_adopted", release: waiting.ClaimId);
            return;
        }
        localQueue = queue.Body.Snapshot;
        localQueueRef = queue.Hash;
        session = new Session(++sessionCounter, new PlaybackArbiter.Session(cursor.Version, null), waiting.ClaimId);
        if (claimOrders.TryGetValue(signer.DeviceId, out var order)) session.ClaimOrder = order;
        deferredEvidence.Clear();
        retreated = false;
        local = new LocalCursor(cursor.Body.CurrentItemId, position, true, cursor.Body.Repeat, cursor.Body.ListenedMs, cursor.Body.HistorySent, nowMs());
        Persist();
        // B の最初のカーソルで再生中が確定する（D-7）。キューは前の端末の doc をそのまま指す
        dirtyCursor = true;
        Flush();
    }

    /// <summary>引き取りをやめる（取り下げるときは中継に Release を送る）。</summary>
    private void FailTransfer(string reason, string? release = null)
    {
        transfer = null;
        if (release is not null) send(new RelayV2.Release { ClaimId = release });
        effects.Event(new ClientEvent.TransferFailed(reason));
    }

    /// <summary>経過はサーバーの時計で測り、手元で受けてからの時間を足す。9分を超える経過は一時停止として扱う（D-5）。</summary>
    private long ExtrapolatedPosition(Opened<CursorBody> cursor)
    {
        if (!cursor.Body.Playing) return cursor.Body.PositionMs;
        var elapsed = (cursor.Stored.ServerNowMs - cursor.Stored.ReceivedAtServerMs) + (nowMs() - cursor.ReceivedAtLocalMs);
        if (elapsed < 0 || elapsed > MaxExtrapolationMs) return cursor.Body.PositionMs;
        return cursor.Body.PositionMs + elapsed;
    }

    // --- 書き込み ---

    private void Flush()
    {
        var current = chain;
        var mine = session;
        if (current is null || mine is null) return;
        if (resumePending) return;
        if (dirtyQueue && !inflight.Values.Any(i => i.Kind == StateKind.Queue))
        {
            var snapshot = localQueue;
            var tag = effects.AccountTag();
            if (snapshot is not null && tag is not null)
            {
                var body = new QueueBody { AccountTag = tag, Snapshot = snapshot };
                var expect = Math.Max(latestQueue?.Version ?? 0, queueFloor);
                var doc = DocSealer.Seal(signer, current, DocKind.Queue, expect + 1, null, epochMs(), ConnectJson.EncodeToUtf8(body));
                var requestId = newId();
                var hash = DocSealer.Hash(doc);
                if (send(new RelayV2.StatePut { RequestId = requestId, Kind = StateKind.Queue, ExpectVersion = expect, Doc = doc }))
                {
                    dirtyQueue = false;
                    localQueueRef = hash;
                    inflight[requestId] = new Inflight(StateKind.Queue, doc, hash, null, body, mine.Id);
                }
            }
        }
        // カーソルは、指すキューが置けてから書く（キューの送信中は待つ）
        if (dirtyCursor && !inflight.Values.Any(i => i.Kind is StateKind.Cursor or StateKind.Queue) && !dirtyQueue)
        {
            var body = CursorBodyFor(mine);
            if (body is null) return;
            var expect = Math.Max(latestCursor?.Version ?? 0, cursorFloor);
            var doc = DocSealer.Seal(signer, current, DocKind.Cursor, expect + 1, lastOwnCursorHash, epochMs(), ConnectJson.EncodeToUtf8(body));
            var hash = DocSealer.Hash(doc);
            var requestId = newId();
            if (send(new RelayV2.StatePut { RequestId = requestId, Kind = StateKind.Cursor, ExpectVersion = expect, Doc = doc }))
            {
                dirtyCursor = false;
                lastOwnCursorHash = hash;
                inflight[requestId] = new Inflight(StateKind.Cursor, doc, hash, body, null, mine.Id);
            }
        }
    }

    private CursorBody? CursorBodyFor(Session? mine, string? tagOverride = null)
    {
        var cursor = local;
        if (cursor is null) return null;
        var tag = tagOverride ?? effects.AccountTag();
        if (tag is null) return null;
        var queueRef = localQueueRef;
        if (queueRef is null) return null;
        return new CursorBody
        {
            QueueRef = queueRef,
            CurrentItemId = cursor.CurrentItemId,
            PositionMs = CurrentPosition(cursor),
            Playing = cursor.Playing && mine is not null,
            Repeat = cursor.Repeat,
            SessionBase = mine?.Arbiter.Base ?? 0,
            SessionStartVersion = mine?.Arbiter.StartVersion,
            DeviceSeq = ++deviceSeq,
            ListenedMs = cursor.ListenedMs,
            HistorySent = cursor.HistorySent,
            AccountTag = tag,
            StartedAtEpochMs = epochMs(),
            VolumePercent = cursor.VolumePercent is { } v ? Math.Clamp(v, 0, 100) : null,
        };
    }

    /// <summary>再生中なら、位置を取ってからの経過を足す（書くのが遅れても位置を戻さない）。</summary>
    private long CurrentPosition(LocalCursor cursor) =>
        cursor.Playing ? cursor.PositionMs + Math.Clamp(nowMs() - cursor.CapturedAtMs, 0, MaxExtrapolationMs) : cursor.PositionMs;

    private SealedDoc SealCommand(VerifiedChain current, CommandBody command) =>
        DocSealer.Seal(signer, current, DocKind.Command, 1, null, epochMs(), ConnectJson.EncodeToUtf8(command));

    private void Remember(string claimId)
    {
        handledClaims.AddLast(claimId);
        while (handledClaims.Count > MaxHandledClaims) handledClaims.RemoveFirst();
    }

    private void PersistFloor()
    {
        if (lastPersistMs is { } last && nowMs() - last < FloorPersistMs)
        {
            floorDirty = true;
            return;
        }
        Persist();
    }

    private void Persist()
    {
        floorDirty = false;
        lastPersistMs = nowMs();
        effects.Persist(new ClientPersisted
        {
            Chain = records.ToList(),
            StoreId = storeId,
            CursorFloor = cursorFloor,
            QueueFloor = queueFloor,
            Retreated = retreated,
            HandledClaimIds = handledClaims.ToList(),
            ExpectedJoin = expectedJoin?.Info,
            ExpectedInviter = expectedJoin?.Inviter,
            CreatingHousehold = creatingHousehold,
        });
    }
}
