namespace Wave.Core.Music;

/// <summary>
/// 一覧（プレイリスト・アルバム・アーティスト・検索の結果）を流し終える前に、キューの最後の曲から始まるラジオへつなぐ
/// （オートプレイ。Android の QueueSession の switchToRadio・決定 E4 と同じ）。
/// </summary>
public static class AutoRadio
{
    /// <summary>残りがこの曲数以下（今の曲を含む）になったら、続き（ラジオ）を取る。</summary>
    public const int ExtendWhenLeftAtMost = 3;

    /// <summary>ラジオの種にできる曲か。ライブ配信は終わらずに流れ続け、RSS のエピソード（ext:）は YouTube のラジオが作れない。</summary>
    public static bool CanSeed(Song song) => !song.IsLive && !Feed.IsExternal(song.VideoId);

    /// <summary>種の曲のラジオのプレイリスト ID。</summary>
    public static string PlaylistIdOf(string videoId) => "RDAMVM" + videoId;

    /// <summary>残りが少なくなって、続き（ラジオ）を取る頃か。</summary>
    public static bool NearEnd(int count, int index) => index >= 0 && index < count && count - index <= ExtendWhenLeftAtMost;

    /// <summary>
    /// 一覧を流し終える前に始めるラジオの種（キューの最後の曲）。始めないなら null。
    /// すでにラジオがある・全体リピート中（今のキューを繰り返す）・一覧の残りをまだ読んでいる・最後の曲が種にできないときは始めない。
    /// </summary>
    public static string? SeedAtEnd(IReadOnlyList<Song> queue, int index, bool hasRadio, bool repeatAll, bool sourcePending)
    {
        if (hasRadio || repeatAll || sourcePending || !NearEnd(queue.Count, index)) return null;
        var last = queue[^1];
        return CanSeed(last) ? last.VideoId : null;
    }

    /// <summary>
    /// 端末の間で受け渡すキューの出どころ（Android の handoffSnapshot と同じ形にする）。
    /// 出どころの無い一覧（検索の結果など）は、Android と同じく最後の曲から始まるラジオとして書く。
    /// Exhausted は「続きが無い」: 最後の曲が種にできず、ラジオにもつながらないときだけ true（プレイリスト・アルバムは終わりでラジオにつなぐので false）。
    /// </summary>
    public static (string? RadioSeed, string? RadioPlaylistId, bool Exhausted) ForHandoff(IReadOnlyList<Song> queue, string? sourceId, string? radioSeed, string? radioPlaylistId)
    {
        if (radioSeed is not null) return (radioSeed, radioPlaylistId ?? PlaylistIdOf(radioSeed), false);
        var seed = queue.Count > 0 && CanSeed(queue[^1]) ? queue[^1].VideoId : null;
        if (seed is null) return (null, null, true);
        return sourceId is null ? (seed, PlaylistIdOf(seed), false) : (null, null, false);
    }
}
