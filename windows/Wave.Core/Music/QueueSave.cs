namespace Wave.Core.Music;

/// <summary>キューをプレイリストとして保存するときの決まり（全端末で同じ）。</summary>
public static class QueueSave
{
    /// <summary>
    /// 保存する曲: キューの全部（聴き終えた曲・今の曲・先の曲）を並びの順に。エピソード（YouTube のポッドキャスト・RSS）とライブは除き、
    /// 同じ曲は最初の1つだけ。
    /// </summary>
    public static List<Song> Songs(IEnumerable<Song> queue) =>
        queue.Where(s => !s.IsLive && s.Kind != "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" && !Feed.IsExternal(s.VideoId) && !string.IsNullOrEmpty(s.VideoId))
            .DistinctBy(s => s.VideoId).ToList();

    /// <summary>名前の既定: 「〈キューの先頭の曲名〉のラジオ」。</summary>
    public static string DefaultTitle(IReadOnlyList<Song> songs, bool japanese) =>
        songs.Count == 0 ? (japanese ? "ラジオ" : "Radio") : japanese ? $"{songs[0].Title}のラジオ" : $"{songs[0].Title} radio";
}
