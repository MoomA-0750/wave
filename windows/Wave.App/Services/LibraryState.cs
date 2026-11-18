using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.App.Services;

/// <summary>マイライブラリ（左の欄）。プレイリスト・アーティスト・アルバムを、届いたページから順に並べる。</summary>
public sealed partial class LibraryState : ObservableObject
{
    public ObservableCollection<PlaylistSummary> Playlists { get; } = [];
    public ObservableCollection<ArtistSummary> Artists { get; } = [];
    public ObservableCollection<AlbumSummary> Albums { get; } = [];
    /// <summary>保存したポッドキャストの番組（Android と同じく、ライブラリの絞り込み「ポッドキャスト」に出す）。</summary>
    public ObservableCollection<PodcastSummary> Podcasts { get; } = [];

    [ObservableProperty] private bool isLoading;
    [ObservableProperty] private string? failure;
    /// <summary>高く評価した曲（LM）のサムネイル。</summary>
    [ObservableProperty] private string? likedThumbnail;
    /// <summary>最近再生したアルバム・プレイリスト・アーティストの目印（並べ方の「最近」に使う。Android と同じ）。</summary>
    [ObservableProperty] private IReadOnlyList<string> recentKeys = [];

    public static string? KeyOf(HomeItem item) => item switch
    {
        HomeItem.PlaylistItem p => "p-" + p.Playlist.PlaylistId,
        HomeItem.ArtistItem a => "ar-" + a.Artist.BrowseId,
        HomeItem.AlbumItem a => "al-" + a.Album.BrowseId,
        HomeItem.PodcastItem c => "c-" + c.Podcast.BrowseId,
        _ => null,
    };

    private CancellationTokenSource? cancel;

    /// <summary>曲がどのプレイリストに入っているかの覚え（プレイリストに追加する画面）。</summary>
    public PlaylistMembership Membership { get; } = new();

    /// <summary>自分で編集できるプレイリスト（「プレイリストに追加」の行き先）。</summary>
    public IEnumerable<PlaylistSummary> Editable => Playlists.Where(p => p.Editable && p.PlaylistId != MusicRepository.LikedSongs);

    public event Action? LikedSongsChanged;

    /// <summary>高く評価した曲の ID（一覧の曲の行にバッジを付ける。Android と同じ）。読む前は空。</summary>
    private HashSet<string> likedIds = [];

    /// <summary>likedIds が変わった。</summary>
    public event Action? LikedIdsChanged;

    public bool IsLiked(string videoId) => likedIds.Contains(videoId);

    /// <summary>評価が変わった。曲と向きが分かれば、ID の集合をすぐ直す（全部は読み直さない）。</summary>
    public void LikedChanged(string? videoId = null, bool? liked = null)
    {
        likedSongs = null;
        if (videoId is not null && liked is { } on)
        {
            if (on) likedIds.Add(videoId);
            else likedIds.Remove(videoId);
            LikedIdsChanged?.Invoke();
        }
        LikedSongsChanged?.Invoke();
    }

    private async Task LoadLikedIdsAsync(CancellationToken token)
    {
        var (songs, _) = await LikedSongsAsync();
        if (token.IsCancellationRequested) return;
        likedIds = songs.Select(s => s.VideoId).ToHashSet();
        LikedIdsChanged?.Invoke();
    }

    /// <summary>
    /// 高く評価した曲を全部（アーティストのページで、そのアーティストの曲を数えて絞るため）。
    /// 開くたびに読み直さないよう、評価が変わるまで持っておく。Complete は最後まで読めたか。
    /// </summary>
    private Task<(List<Song> Songs, bool Complete)>? likedSongs;

    public Task<(List<Song> Songs, bool Complete)> LikedSongsAsync() => likedSongs ??= LoadLikedAsync();

    private async Task<(List<Song> Songs, bool Complete)> LoadLikedAsync()
    {
        var result = await AppServices.Music.FullPlaylistAsync(MusicRepository.LikedSongs);
        if (!result.IsOk)
        {
            likedSongs = null;
            return ([], false);
        }
        AppPaths.Log($"liked songs loaded songs={result.Value!.Songs.Count} complete={result.Value.Continuation is null}");
        return (result.Value.Songs.ToList(), result.Value.Continuation is null);
    }

    /// <summary>高く評価した曲のうち、そのアーティストのもの（ID が取れない行は名前で照らす。Android と同じ）。</summary>
    public static List<Song> ByArtist(IEnumerable<Song> songs, string? browseId, string name) =>
        songs.Where(song => song.Artists.Any(a => browseId is not null && a.BrowseId is not null ? a.BrowseId == browseId : string.Equals(a.Name, name, StringComparison.OrdinalIgnoreCase))).ToList();

    /// <summary>読めなかったときに読み直すまでの間（YouTube が続けての頼みを一時的に断ったときなど。尽きたらやめる）。</summary>
    private static readonly TimeSpan[] RetryAfter = [TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(3), TimeSpan.FromMinutes(10)];

    public Task RefreshAsync() => RefreshAsync(0);

    private async Task RefreshAsync(int retry)
    {
        cancel?.Cancel();
        var token = (cancel = new CancellationTokenSource()).Token;
        Playlists.Clear();
        Artists.Clear();
        Albums.Clear();
        Podcasts.Clear();
        Failure = null;
        likedSongs = null;
        likedIds = [];
        Membership.Clear();
        LikedIdsChanged?.Invoke();
        if (!AppServices.Client.IsLoggedIn) return;
        _ = LoadLikedIdsAsync(token);
        IsLoading = true;
        try
        {
            // 途中のページで失敗すると、そこまでの分が「読めた」として返る。そのときも読み直す
            var incomplete = false;
            void Incomplete() => incomplete = true;
            var playlists = AppServices.Music.LibraryPlaylistsAsync(page => Replace(Playlists, page.Where(p => p.PlaylistId != MusicRepository.LikedSongs || Capture(p)), token), token, Incomplete);
            var artists = AppServices.Music.LibraryArtistsAsync(page => Replace(Artists, page, token), token, Incomplete);
            var albums = AppServices.Music.LibraryAlbumsAsync(page => Replace(Albums, page, token), token, Incomplete);
            _ = LoadRecentAsync(token);
            _ = LoadPodcastsAsync(token);
            var results = await Task.WhenAll(playlists.ContinueWith(t => t.Result.Failure), artists.ContinueWith(t => t.Result.Failure), albums.ContinueWith(t => t.Result.Failure));
            if (token.IsCancellationRequested) return;
            Failure = results.FirstOrDefault(f => f is not null)?.ToString();
            AppPaths.Log($"library playlists={Playlists.Count} artists={Artists.Count} albums={Albums.Count} failure={Failure} incomplete={incomplete}");
            // 一部でも読めなかったら、少し待って読み直す（途中までの一覧のままにしない。ログインが切れたときは読み直さない）
            if ((Failure is not null || incomplete) && results.All(f => f != FailureKind.NotLoggedIn || AppServices.Client.Session.Status != SessionStatus.Expired) && retry < RetryAfter.Length)
                _ = RetryLaterAsync(retry, token);
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            if (!token.IsCancellationRequested) IsLoading = false;
        }
    }

    private async Task RetryLaterAsync(int retry, CancellationToken token)
    {
        try
        {
            await Task.Delay(RetryAfter[retry], token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        if (!token.IsCancellationRequested && AppServices.Client.IsLoggedIn) await RefreshAsync(retry + 1);
    }

    /// <summary>保存したポッドキャストを読み直す（番組のページで保存を変えたとき）。</summary>
    public Task RefreshPodcastsAsync() => AppServices.Client.IsLoggedIn ? LoadPodcastsAsync(cancel?.Token ?? CancellationToken.None) : Task.CompletedTask;

    /// <summary>保存したアルバムを読み直す（アルバムのページで保存を変えたとき）。</summary>
    public async Task RefreshAlbumsAsync()
    {
        if (!AppServices.Client.IsLoggedIn) return;
        var token = cancel?.Token ?? CancellationToken.None;
        var result = await AppServices.Music.LibraryAlbumsAsync(cancel: token);
        if (result.IsOk) Replace(Albums, result.Value!, token);
        AppPaths.Log($"library albums ok={result.IsOk} count={Albums.Count}");
    }

    private async Task LoadPodcastsAsync(CancellationToken token)
    {
        var result = await AppServices.Music.LibraryPodcastsAsync(page => Replace(Podcasts, page, token), token);
        if (result.IsOk) Replace(Podcasts, result.Value!, token);
        AppPaths.Log($"library podcasts ok={result.IsOk} count={Podcasts.Count}");
    }

    private async Task LoadRecentAsync(CancellationToken token)
    {
        var recent = await AppServices.Music.RecentActivityAsync(token);
        if (!token.IsCancellationRequested && recent.IsOk) RecentKeys = recent.Value!.Select(KeyOf).OfType<string>().ToList();
    }

    private bool Capture(PlaylistSummary liked)
    {
        LikedThumbnail = liked.ThumbnailUrl;
        return false;
    }

    /// <summary>届いたページで一覧を置き換える（同じ並びの頭はそのままにして、足りない分だけ足す）。</summary>
    private static void Replace<T>(ObservableCollection<T> target, IEnumerable<T> items, CancellationToken token)
    {
        if (token.IsCancellationRequested) return;
        var list = items.ToList();
        var same = 0;
        while (same < target.Count && same < list.Count && Equals(target[same], list[same])) same++;
        while (target.Count > same) target.RemoveAt(target.Count - 1);
        foreach (var item in list.Skip(same)) target.Add(item);
    }

    public void AddCreated(PlaylistSummary playlist)
    {
        Playlists.Insert(0, playlist);
        // 並べ方が「最近」でも先頭に出す（最近再生したものの並びに、作ったものを先頭として足す）
        RecentKeys = [KeyOf(new HomeItem.PlaylistItem(playlist))!, .. RecentKeys];
        created[playlist.PlaylistId] = (playlist, DateTime.UtcNow);
        RefreshPlaylistsSoon();
    }

    // 作ったばかりのプレイリスト。YouTube のライブラリの一覧に載るまでしばらくかかるので、読み直しで消えないよう2分は先頭に残す
    private readonly Dictionary<string, (PlaylistSummary Playlist, DateTime At)> created = [];

    private CancellationTokenSource? playlistsSoon;

    /// <summary>
    /// プレイリストに曲を足し外し・作ったあと、左の一覧の曲数（「20 曲」など）を合わせる。YouTube 側の数が追いつくまで少し待ち、
    /// プレイリストの一覧だけを読み直す（全部を読み直すと高く評価した曲や所属の記憶まで消えるため）。続けて操作したら最後の1回だけ読む。
    /// </summary>
    public void RefreshPlaylistsSoon()
    {
        playlistsSoon?.Cancel();
        var token = (playlistsSoon = new CancellationTokenSource()).Token;
        _ = ReloadPlaylistsAsync(token);
    }

    private async Task ReloadPlaylistsAsync(CancellationToken token)
    {
        try
        {
            await Task.Delay(2500, token);
            if (!AppServices.Client.IsLoggedIn) return;
            var result = await AppServices.Music.LibraryPlaylistsAsync(cancel: token);
            if (token.IsCancellationRequested || !result.IsOk || result.Value!.Count == 0) return;
            var fresh = result.Value.Where(p => p.PlaylistId != MusicRepository.LikedSongs || Capture(p)).ToList();
            var missing = false;
            foreach (var (id, entry) in created.ToList())
            {
                if (fresh.Any(p => p.PlaylistId == id) || DateTime.UtcNow - entry.At > TimeSpan.FromMinutes(2)) { created.Remove(id); continue; }
                fresh.Insert(0, entry.Playlist);
                missing = true;
            }
            Replace(Playlists, fresh, token);
            // まだ載っていなければ、もう一度少し後に読み直す
            if (missing) { await Task.Delay(8000, token); await ReloadPlaylistsAsync(token); }
        }
        catch (OperationCanceledException)
        {
        }
    }
}
