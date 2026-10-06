namespace Wave.Core.Stream;

/// <summary>
/// 映像だけの再生器を、音の再生位置に合わせる判断（Android の VideoSync と同じ）。音は止めずに、映像の方を飛ばす・速さを少し変えて追いつかせる。
/// 画面にも再生の部品にも依らない。
/// </summary>
public static class VideoSync
{
    public abstract record Step
    {
        /// <summary>映像に当たる部分が無い（MV に無い区間）。映像を止めて隠す。</summary>
        public sealed record Hide : Step;

        public sealed record Seek(long ToMs, bool Play) : Step;

        public sealed record Run(double Speed, bool Play) : Step;
    }

    /// <summary>これより離れたら飛ぶ。近ければ速さで合わせる（飛ぶと映像が一瞬止まるので）。</summary>
    public const long SeekThresholdMs = 700;

    /// <summary>これ以内なら合っているとみなす。</summary>
    public const long InSyncMs = 40;

    /// <summary>止まっているときに合わせ直す差。</summary>
    public const long PausedToleranceMs = 80;

    /// <summary>速さの変え幅の上限（見た目に不自然でない範囲）。</summary>
    public const double MaxSpeedChange = 0.1;

    /// <param name="targetMs">今の音の位置に当たる映像の位置。null なら当たる部分が無い</param>
    /// <param name="videoReady">映像の再生器が読み込み中でない（読み込み中に飛び直すと、いつまでも追いつかない）</param>
    public static Step Decide(long? targetMs, long videoMs, bool playing, bool videoReady)
    {
        if (targetMs is not { } target) return new Step.Hide();
        var diff = videoMs - target;
        if (!playing) return Math.Abs(diff) > PausedToleranceMs ? new Step.Seek(target, false) : new Step.Run(1, false);
        if (!videoReady) return new Step.Run(1, true);
        if (Math.Abs(diff) > SeekThresholdMs) return new Step.Seek(target, true);
        if (Math.Abs(diff) <= InSyncMs) return new Step.Run(1, true);
        // 映像が進んでいれば遅く、遅れていれば速く。1秒で差を埋める速さ（上限あり）
        return new Step.Run(Math.Clamp(1 - diff / 1000.0, 1 - MaxSpeedChange, 1 + MaxSpeedChange), true);
    }
}
