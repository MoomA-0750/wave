namespace Wave.Core.Music;

/// <summary>ホームにアプリが自分で足す棚（Android の HomeShelves と同じ）。</summary>
public static class HomeShelves
{
    /// <summary>ホームの「新作」の棚に出す数（Android と同じ）。</summary>
    public const int NewReleasesCount = 20;

    /// <summary>
    /// YouTube Music の新作（アルバム・シングル）が、ホームの別の棚とアルバム3枚以上重なるか。
    /// 重なるならホームに新作の棚が来ているので、こちらの「新作」は出さない（同じものが2つ並ぶ）。
    /// </summary>
    public static bool DuplicatesNewReleases(IReadOnlyList<AlbumSummary> releases, IEnumerable<HomeShelf> shelves)
    {
        var ids = releases.Take(NewReleasesCount).Select(a => a.BrowseId).ToHashSet();
        if (ids.Count == 0) return false;
        return shelves.Any(shelf => shelf.Items.Count(i => i is HomeItem.AlbumItem a && ids.Contains(a.Album.BrowseId)) >= 3);
    }

    /// <summary>新作の棚（見出しは画面の言語で渡す）。新作が無ければ null。</summary>
    public static HomeShelf? NewReleasesShelf(string title, IReadOnlyList<AlbumSummary> releases) =>
        releases.Count == 0 ? null : new HomeShelf(title, null, releases.Take(NewReleasesCount).Select(a => (HomeItem)new HomeItem.AlbumItem(a)).ToList());
}
