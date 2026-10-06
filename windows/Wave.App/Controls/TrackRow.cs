using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Xaml.Controls;
using Wave.Core.Music;

namespace Wave.App.Controls;

/// <summary>曲の一覧の1行（番号・画像・曲名・アーティスト・アルバム・長さ）。</summary>
public sealed partial class TrackRow(int number, Song song, string? setVideoId = null) : ObservableObject
{
    public int Number { get; } = number;
    public Song Song { get; } = song;
    public string? SetVideoId { get; } = setVideoId;
    public bool ShowAlbum { get; init; } = true;
    public bool ShowArtwork { get; init; } = true;
    /// <summary>この行のメニューを作る（ページごとに「このプレイリストから削除」などを足す）。</summary>
    public Func<TrackRow, MenuFlyout>? Menu { get; init; }

    [ObservableProperty] private bool isCurrent;

    public string AlbumTitle => Song.Album?.Title ?? "";

    public MenuFlyout BuildMenu() => Menu?.Invoke(this) ?? SongMenu.Build(Song);

    /// <summary>今の曲に合わせて印を付け直す。</summary>
    public static void MarkCurrent(IEnumerable<TrackRow> rows, string? videoId)
    {
        foreach (var row in rows) row.IsCurrent = row.Song.VideoId == videoId;
    }
}
