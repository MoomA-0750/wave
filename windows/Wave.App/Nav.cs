using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Animation;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App;

/// <summary>プレイリスト・アルバムのページを開くときの値。</summary>
public sealed record CollectionRequest(bool IsAlbum, string Id, string? Title = null, string? ThumbnailUrl = null, string? Subtitle = null);

/// <summary>画面の移り変わり（中央の枠）。戻る・進むは枠の履歴を使う。</summary>
public static class Nav
{
    public static Frame Frame { get; set; } = null!;

    private static void Go(Type page, object? parameter = null) =>
        Frame.Navigate(page, parameter, new SlideNavigationTransitionInfo { Effect = SlideNavigationTransitionEffect.FromRight });

    public static void Home() => Go(typeof(Views.HomePage));

    public static void Search(string? query = null) => Go(typeof(Views.SearchPage), query);

    public static void Playlist(PlaylistSummary playlist) =>
        Go(typeof(Views.CollectionPage), new CollectionRequest(false, playlist.PlaylistId, playlist.Title, playlist.ThumbnailUrl, playlist.Subtitle));

    public static void LikedSongs() =>
        Go(typeof(Views.CollectionPage), new CollectionRequest(false, MusicRepository.LikedSongs, L.T("高く評価した曲", "Liked Songs"), AppServices.Library.LikedThumbnail));

    public static void Album(string browseId, AlbumSummary? summary = null) =>
        Go(typeof(Views.CollectionPage), new CollectionRequest(true, browseId, summary?.Title, summary?.ThumbnailUrl, summary?.Subtitle));

    public static void Artist(string browseId) => Go(typeof(Views.ArtistPage), browseId);

    public static void Mood(MoodCategory mood) => Go(typeof(Views.MoodPage), mood);

    public static void History() => Go(typeof(Views.HistoryPage));

    public static void Settings() => Go(typeof(Views.SettingsPage));

    public static void Lyrics() => Go(typeof(Views.LyricsPage));

    /// <summary>カードを押したとき。曲は再生（Spotify と同じ）、ほかはそのページへ。</summary>
    public static void Open(HomeItem item)
    {
        switch (item)
        {
            case HomeItem.SongItem s:
                AppServices.Player.PlaySong(s.Song);
                break;
            case HomeItem.AlbumItem a:
                Album(a.Album.BrowseId, a.Album);
                break;
            case HomeItem.PlaylistItem p:
                Playlist(p.Playlist);
                break;
            case HomeItem.ArtistItem a:
                Artist(a.Artist.BrowseId);
                break;
        }
    }

    /// <summary>カードの再生ボタン。ページを開かずに再生する。</summary>
    public static async void Play(HomeItem item)
    {
        var player = AppServices.Player;
        switch (item)
        {
            case HomeItem.SongItem s:
                player.PlaySong(s.Song);
                break;
            case HomeItem.AlbumItem a:
                var album = await AppServices.Music.AlbumAsync(a.Album.BrowseId);
                if (album.IsOk) player.PlayList(album.Value!.Songs, 0, a.Album.BrowseId);
                else player.Message = L.T("アルバムを読み込めませんでした", "Couldn't load the album");
                break;
            case HomeItem.PlaylistItem p:
                // 最初のページが届いたら鳴らし始め、残りはあとから足す
                var started = false;
                var full = await AppServices.Music.FullPlaylistAsync(p.Playlist.PlaylistId, page =>
                {
                    if (started || page.Songs.Count == 0) return;
                    started = true;
                    player.PlayList(page.Songs, 0, p.Playlist.PlaylistId, player.Shuffle ? true : null);
                });
                if (!full.IsOk && !started) player.Message = L.T("プレイリストを読み込めませんでした", "Couldn't load the playlist");
                else if (full.IsOk && player.SourceId == p.Playlist.PlaylistId) player.AppendFrom(full.Value!.Songs);
                break;
            case HomeItem.ArtistItem r:
                var artist = await AppServices.Music.ArtistAsync(r.Artist.BrowseId);
                if (artist.IsOk && artist.Value!.TopSongs.Count > 0) player.PlayList(artist.Value.TopSongs, 0, r.Artist.BrowseId);
                else player.Message = L.T("アーティストの曲を読み込めませんでした", "Couldn't load the artist's songs");
                break;
        }
    }
}
