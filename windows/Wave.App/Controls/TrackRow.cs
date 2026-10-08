using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Xaml.Controls;
using Wave.App.Services;
using Wave.Core.Music;

namespace Wave.App.Controls;

/// <summary>曲の一覧の1行（番号・画像・曲名・アーティスト・アルバム・長さ）。</summary>
public sealed partial class TrackRow : ObservableObject
{
    public TrackRow(int number, Song song, string? setVideoId = null)
    {
        Number = number;
        Song = song;
        SetVideoId = setVideoId;
        isLiked = AppServices.Library.IsLiked(song.VideoId);
        live.RemoveAll(r => !r.TryGetTarget(out _));
        live.Add(new WeakReference<TrackRow>(this));
    }

    public int Number { get; }
    public Song Song { get; }
    public string? SetVideoId { get; }
    public bool ShowAlbum { get; init; } = true;
    public bool ShowArtwork { get; init; } = true;
    /// <summary>この行のメニューを作る（ページごとに「このプレイリストから削除」などを足す）。</summary>
    public Func<TrackRow, MenuFlyout>? Menu { get; init; }

    [ObservableProperty] private bool isCurrent;
    /// <summary>高く評価した曲（右に✓のバッジを出す。Android・Spotify と同じ）。</summary>
    [ObservableProperty] private bool isLiked;

    /// <summary>作った行（評価が変わったら印を付け直す）。弱参照で持ち、ページが捨てた行は残さない。</summary>
    private static readonly List<WeakReference<TrackRow>> live = [];

    static TrackRow()
    {
        AppServices.Library.LikedIdsChanged += () =>
        {
            live.RemoveAll(r => !r.TryGetTarget(out _));
            foreach (var r in live)
                if (r.TryGetTarget(out var row)) row.IsLiked = AppServices.Library.IsLiked(row.Song.VideoId);
        };
    }

    public string AlbumTitle => Song.Album?.Title ?? "";

    public MenuFlyout BuildMenu() => Menu?.Invoke(this) ?? SongMenu.Build(Song);

    /// <summary>今の曲に合わせて印を付け直す。</summary>
    public static void MarkCurrent(IEnumerable<TrackRow> rows, string? videoId)
    {
        foreach (var row in rows) row.IsCurrent = row.Song.VideoId == videoId;
    }
}
