using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using Wave.Core.Music;

namespace Wave.App.Services;

/// <summary>マイライブラリ（左の欄）。プレイリスト・アーティスト・アルバムを、届いたページから順に並べる。</summary>
public sealed partial class LibraryState : ObservableObject
{
    public ObservableCollection<PlaylistSummary> Playlists { get; } = [];
    public ObservableCollection<ArtistSummary> Artists { get; } = [];
    public ObservableCollection<AlbumSummary> Albums { get; } = [];

    [ObservableProperty] private bool isLoading;
    [ObservableProperty] private string? failure;
    /// <summary>高く評価した曲（LM）のサムネイル。</summary>
    [ObservableProperty] private string? likedThumbnail;

    private CancellationTokenSource? cancel;

    /// <summary>自分で編集できるプレイリスト（「プレイリストに追加」の行き先）。</summary>
    public IEnumerable<PlaylistSummary> Editable => Playlists.Where(p => p.Editable && p.PlaylistId != MusicRepository.LikedSongs);

    public event Action? LikedSongsChanged;

    public void LikedChanged()
    {
        likedSongs = null;
        LikedSongsChanged?.Invoke();
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

    public async Task RefreshAsync()
    {
        cancel?.Cancel();
        var token = (cancel = new CancellationTokenSource()).Token;
        Playlists.Clear();
        Artists.Clear();
        Albums.Clear();
        Failure = null;
        if (!AppServices.Client.IsLoggedIn) return;
        IsLoading = true;
        try
        {
            var playlists = AppServices.Music.LibraryPlaylistsAsync(page => Replace(Playlists, page.Where(p => p.PlaylistId != MusicRepository.LikedSongs || Capture(p)), token), token);
            var artists = AppServices.Music.LibraryArtistsAsync(page => Replace(Artists, page, token), token);
            var albums = AppServices.Music.LibraryAlbumsAsync(page => Replace(Albums, page, token), token);
            var results = await Task.WhenAll(playlists.ContinueWith(t => t.Result.Failure), artists.ContinueWith(t => t.Result.Failure), albums.ContinueWith(t => t.Result.Failure));
            if (token.IsCancellationRequested) return;
            Failure = results.FirstOrDefault(f => f is not null)?.ToString();
            AppPaths.Log($"library playlists={Playlists.Count} artists={Artists.Count} albums={Albums.Count} failure={Failure}");
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            if (!token.IsCancellationRequested) IsLoading = false;
        }
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

    public void AddCreated(PlaylistSummary playlist) => Playlists.Insert(0, playlist);
}
