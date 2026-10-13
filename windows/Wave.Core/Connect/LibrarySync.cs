namespace Wave.Core.Connect;

/// <summary>
/// ライブラリ（RSS で登録した番組・聴いたところ）を、中継の家の端末の間で合わせる（ADR-0013。Kotlin の LibrarySync と同じ動き）。
/// 中継の最新の文書を開いて手元と合わせ、手元にしか無い・手元のほうが新しいものがあれば、版の CAS で書く。
/// 書き込みは1本ずつ。食い違ったら（ほかの端末が先に書いた）、届いた最新と合わせて書き直す。
/// 呼ぶ側は ConnectClient と同じ1本の流れから呼ぶ（スレッドをまたがない）。
/// </summary>
/// <param name="chain">検証した今の一覧（無ければ書かない・開かない）。</param>
/// <param name="storeId">中継の保存の ID（ConnectClient が受け入れたもの。作り直しの確認待ちなら null）。違う保存の文書は取り込まない。</param>
/// <param name="onRemote">中継から合わせた結果が届いた（手元の状態に取り込む）。</param>
public sealed class LibrarySync(
    IDeviceSigner signer,
    IKxOpener opener,
    Func<RelayV2, bool> send,
    Func<VerifiedChain?> chain,
    Func<string?> storeId,
    Func<long> epochMs,
    Func<string> newId,
    Action<LibraryBody> onRemote)
{
    /// <summary>中継の最新の版と中身（開けたもの）と、その保存の ID。</summary>
    private long remoteVersion;
    private LibraryBody? remoteBody;
    private string? remoteHash;
    private string? remoteStore;

    /// <summary>手元の今の中身（書きたいもの）。</summary>
    private LibraryBody? local;

    /// <summary>返事を待っている書き込みと、その中身（返事が来たら中継の中身とする）。</summary>
    private string? inflight;
    private LibraryBody? pendingBody;

    /// <summary>接続して library_get の返事を受けたか（受けるまで書かない。中継の版を知らずに 0 で書くと食い違う）。</summary>
    private bool synced;

    /// <summary>開けなかった文書（一覧が古い）。一覧が届いたら開き直す。</summary>
    private LibraryState? deferred;

    /// <summary>つながった（welcome の後）。最新を頼む。古い中継は知らない命令として error を返すだけ（synced のまま false なので書かない）。</summary>
    public void OnConnected()
    {
        synced = false;
        inflight = null;
        send(RelayV2.LibraryGet.Instance);
    }

    public void OnDisconnected()
    {
        synced = false;
        inflight = null;
    }

    /// <summary>一覧が新しくなった。開けずにいた文書を開き直す。</summary>
    public void OnChainUpdated()
    {
        var waiting = deferred;
        if (waiting is null) return;
        deferred = null;
        Adopt(waiting);
        Flush();
    }

    /// <summary>手元が変わった。中継のものと合わせて、違えば書く。</summary>
    public void Publish(LibraryBody body)
    {
        local = body;
        Flush();
    }

    public void OnMessage(RelayV2 message)
    {
        switch (message)
        {
            case RelayV2.Library library:
                synced = true;
                if (library.Stored is { } state) Adopt(state);
                else ForgetRemote();
                Flush();
                break;
            case RelayV2.LibraryOk ok:
                if (ok.RequestId != inflight) return;
                inflight = null;
                remoteVersion = ok.Version;
                remoteBody = pendingBody;
                remoteHash = null;
                remoteStore = storeId();
                pendingBody = null;
                Flush();
                break;
            case RelayV2.LibraryConflict conflict:
                if (conflict.RequestId != inflight) return;
                inflight = null;
                pendingBody = null;
                if (conflict.Latest is { } latest) Adopt(latest);
                else ForgetRemote();
                Flush();
                break;
        }
    }

    /// <summary>
    /// 中継にまだ文書が無い（作り直された中継など）。覚えていた版を捨てて 0 から書く
    /// （覚えた版のまま書くと、食い違いが返り続けて書き込みが回り続ける）。
    /// </summary>
    private void ForgetRemote()
    {
        if (storeId() is null) return;
        remoteVersion = 0;
        remoteBody = null;
        remoteHash = null;
        remoteStore = null;
    }

    /// <summary>中継の文書を開いて取り込む。古い版・違う保存・開けないものは捨てる。</summary>
    private void Adopt(LibraryState state)
    {
        if (state.StoreId != storeId()) return;
        // 覚えている版が別の保存のもの（中継が作り直された）なら、版の大小では捨てない
        var sameStore = state.StoreId == remoteStore;
        if (sameStore && state.Version < remoteVersion) return;
        var hash = DocSealer.Hash(state.Doc);
        if (sameStore && state.Version == remoteVersion && hash == remoteHash) return;
        var current = chain();
        if (current is null)
        {
            deferred = state;
            return;
        }
        DocOpen.Ok opened;
        switch (DocSealer.Open(state.Doc, current, opener))
        {
            case DocOpen.Ok ok:
                opened = ok;
                break;
            case DocOpen.NeedChain:
                deferred = state;
                return;
            default:
                return;
        }
        if (opened.Header.Kind != DocKind.Library || opened.Header.Version != state.Version) return;
        if (ConnectJson.Decode<LibraryBody>(opened.Plaintext) is not { } body) return;
        remoteVersion = state.Version;
        remoteHash = hash;
        remoteBody = body;
        remoteStore = state.StoreId;
        // 手元と合わせたものを取り込んでもらう（手元のほうが新しい分は、次の Flush で書く）
        var merged = local?.Merge(body) ?? body;
        local = merged;
        onRemote(merged);
    }

    /// <summary>手元に中継より新しいものがあれば書く。</summary>
    private void Flush()
    {
        if (!synced || inflight is not null || storeId() is null) return;
        var mine = local;
        if (mine is null) return;
        var remote = remoteBody;
        // 同じ時刻なら中継の側を残す
        var merged = remote is null ? mine : mine.Merge(remote);
        if (remote is not null && merged.SameAs(remote)) return;
        if (remote is null && merged.Feeds.Count == 0 && merged.Positions.Count == 0) return;
        var current = chain();
        if (current is null || !current.Members.ContainsKey(signer.DeviceId)) return;
        var plaintext = ConnectJson.EncodeToUtf8(merged);
        if (plaintext.Length > DocSealer.MaxPlaintext) return;
        var requestId = newId();
        var doc = DocSealer.Seal(signer, current, DocKind.Library, remoteVersion + 1, null, epochMs(), plaintext);
        if (send(new RelayV2.LibraryPut { RequestId = requestId, ExpectVersion = remoteVersion, Doc = doc }))
        {
            inflight = requestId;
            pendingBody = merged;
            local = merged;
        }
    }
}
