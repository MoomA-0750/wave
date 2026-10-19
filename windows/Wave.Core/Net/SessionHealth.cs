namespace Wave.Core.Net;

/// <summary>ログインの状態（Android の WaveSession の SessionState と同じ分け方。ADR-0004）。</summary>
public enum SessionStatus
{
    LoggedOut,
    Active,
    /// <summary>ログイン中のはずなのに、切れたのを疑う応答（403・3xx・ログアウト相当の中身）が返った。確かめている。</summary>
    Suspect,
    /// <summary>切れたと確かめられた（401、確かめの account_menu が Google のログインへ回した・アカウントのヘッダが無い）。</summary>
    Expired,
}

/// <summary>
/// ログインの状態を世代ごとに持つ（ADR-0004）。古い世代の結果は今の世代を書き換えない。
/// 疑いのときは account_menu で確かめる（同時に1本、30秒に1回まで）。疑い・失効を解くのはアカウントのヘッダが返ったときだけ。
/// </summary>
public sealed class SessionHealth(Func<long> nowMs)
{
    public const long ConfirmIntervalMs = 30_000;

    private readonly Lock gate = new();
    private long generation;
    private SessionStatus status = SessionStatus.LoggedOut;
    private bool confirming;
    private long? lastConfirmAtMs;

    public SessionStatus Status
    {
        get { lock (gate) return status; }
    }

    /// <summary>状態が変わった（通信の途中から呼ばれるので、画面は自分の糸へ移して使う）。</summary>
    public event Action<SessionStatus>? Changed;

    /// <summary>ログイン・ログアウトで世代が変わった。</summary>
    public void Reset(long newGeneration, bool loggedIn) =>
        Set(() =>
        {
            generation = newGeneration;
            confirming = false;
            lastConfirmAtMs = null;
            status = loggedIn ? SessionStatus.Active : SessionStatus.LoggedOut;
            return true;
        });

    /// <summary>401（すぐに失効）。</summary>
    public void Expire(long forGeneration) => Set(() => Matches(forGeneration) && Replace(SessionStatus.Expired));

    /// <summary>切れたのを疑う応答。確かめを始めてよければ true（その世代で今は確かめていない・前の確かめから30秒たった）。</summary>
    public bool Suspect(long forGeneration)
    {
        var start = false;
        Set(() =>
        {
            if (!Matches(forGeneration) || status == SessionStatus.Expired) return false;
            start = TryBeginConfirm();
            return Replace(SessionStatus.Suspect);
        });
        return start;
    }

    /// <summary>状態は変えずに、確かめだけを頼む（曲ごとの LOGIN_REQUIRED）。始めてよければ true。</summary>
    public bool RequestConfirm(long forGeneration)
    {
        lock (gate)
        {
            if (!Matches(forGeneration)) return false;
            return TryBeginConfirm();
        }
    }

    /// <summary>確かめが終わった（結果を書いたあと、またはどちらとも言えなかったとき）。</summary>
    public void EndConfirm(long forGeneration)
    {
        lock (gate)
        {
            if (Matches(forGeneration)) confirming = false;
        }
    }

    /// <summary>
    /// ログインが有効だと示す応答（アカウントのヘッダ）が返った。
    /// fromConfirm なら疑いだけを解く（確かめ）。そうでなければ（アカウントの読み取り）失効も解く。
    /// </summary>
    public void Proven(long forGeneration, bool fromConfirm) =>
        Set(() => Matches(forGeneration) && (!fromConfirm || status == SessionStatus.Suspect) && Replace(SessionStatus.Active));

    private bool Matches(long forGeneration) => forGeneration == generation && status != SessionStatus.LoggedOut;

    private bool TryBeginConfirm()
    {
        if (confirming) return false;
        var now = nowMs();
        if (lastConfirmAtMs is { } last && now - last < ConfirmIntervalMs) return false;
        lastConfirmAtMs = now;
        confirming = true;
        return true;
    }

    private bool Replace(SessionStatus next)
    {
        if (status == next) return false;
        status = next;
        return true;
    }

    private void Set(Func<bool> change)
    {
        SessionStatus changed;
        lock (gate)
        {
            if (!change()) return;
            changed = status;
        }
        Changed?.Invoke(changed);
    }
}
