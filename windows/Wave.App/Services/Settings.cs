using System.Text.Json;
using Wave.Core.Music;

namespace Wave.App.Services;

/// <summary>端末ごとの設定と、検索の履歴（認証情報は含まない）。</summary>
public sealed class Settings
{
    public double Volume { get; set; } = 0.7;
    public bool Shuffle { get; set; }
    public int Repeat { get; set; }
    /// <summary>再生を YouTube Music の再生履歴に送る（Android 版の既定と同じく、オン）。</summary>
    public bool SendHistory { get; set; } = true;
    public bool RightPaneOpen { get; set; } = true;
    /// <summary>登録したアーティストの新作を通知で知らせる（アプリが動いている間。Android と同じく既定はオン）。</summary>
    public bool ReleaseNotices { get; set; } = true;
    /// <summary>マイライブラリの並べ方（recent: 最近再生した順・added: 最近追加した順・alpha: アルファベット順）と、グリッドで出すか。</summary>
    public string LibrarySort { get; set; } = "recent";
    public bool LibraryGrid { get; set; }
    /// <summary>プレイリストごとに選んだ並べ方（`Added:desc` など）。Android と同じく開くたびにサーバーへ明示して送る。</summary>
    public Dictionary<string, string> PlaylistOrders { get; set; } = [];
    /// <summary>画面の言語。"system"（Windows に合わせる）・"ja"・"en"。次の起動から効く。</summary>
    public string Language { get; set; } = "system";
    public List<SearchHistoryEntry> SearchHistory { get; set; } = [];
    /// <summary>端末の連携の中継 v2 の場所（例: wss://ホスト:ポート）と、署名に入れるホスト名とポート。空なら連携を使わない。</summary>
    public string? RelayV2Url { get; set; }
    public string? RelayV2Host { get; set; }
    /// <summary>Claude の中継（tools/ai-bridge）の接続先・トークン・機械の名前。空なら使わない。</summary>
    public string? AiUrl { get; set; }
    public string? AiToken { get; set; }
    public string? AiName { get; set; }
    /// <summary>Discord に再生中の曲を出す（既定はオフ）と、そのための Discord のアプリケーション ID（discord.com/developers で作る）。</summary>
    public bool DiscordPresence { get; set; }
    public string? DiscordClientId { get; set; }

    private static readonly string FilePath = Path.Combine(AppPaths.Data, "settings.json");
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch (Exception e) when (e is JsonException or IOException)
        {
        }
        return new Settings();
    }

    public void Save()
    {
        try
        {
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Options));
        }
        catch (IOException)
        {
        }
    }

    /// <summary>検索の履歴に足す（同じものは先頭へ動かす。最大 30 件）。</summary>
    public void AddSearch(SearchHistoryEntry entry)
    {
        SearchHistory.RemoveAll(e => e.Key == entry.Key);
        SearchHistory.Insert(0, entry);
        if (SearchHistory.Count > 30) SearchHistory.RemoveRange(30, SearchHistory.Count - 30);
        Save();
    }

    public void RemoveSearch(SearchHistoryEntry entry)
    {
        SearchHistory.RemoveAll(e => e.Key == entry.Key);
        Save();
    }
}

/// <summary>検索の履歴の1件（語・曲・アルバム・アーティスト。Spotify のように画像付きで出す）。</summary>
public sealed class SearchHistoryEntry
{
    public string Kind { get; set; } = "query";
    public string Title { get; set; } = "";
    public string? Subtitle { get; set; }
    public string? ThumbnailUrl { get; set; }
    public string? Id { get; set; }
    public Song? Song { get; set; }

    [System.Text.Json.Serialization.JsonIgnore]
    public string Key => Kind + ":" + (Id ?? Title);

    [System.Text.Json.Serialization.JsonIgnore]
    public bool IsRound => Kind == "artist";

    [System.Text.Json.Serialization.JsonIgnore]
    public bool IsQuery => Kind == "query";

    public static SearchHistoryEntry Query(string q) => new() { Kind = "query", Title = q };

    public static SearchHistoryEntry Of(HomeItem item) => item switch
    {
        HomeItem.SongItem s => new() { Kind = "song", Title = s.Song.Title, Subtitle = L.T("曲 • ", "Song • ") + s.Song.ArtistsText, ThumbnailUrl = s.Song.ThumbnailUrl, Id = s.Song.VideoId, Song = s.Song },
        HomeItem.AlbumItem a => new() { Kind = "album", Title = a.Album.Title, Subtitle = a.Album.Subtitle, ThumbnailUrl = a.Album.ThumbnailUrl, Id = a.Album.BrowseId },
        HomeItem.ArtistItem a => new() { Kind = "artist", Title = a.Artist.Name, Subtitle = L.T("アーティスト", "Artist"), ThumbnailUrl = a.Artist.ThumbnailUrl, Id = a.Artist.BrowseId },
        HomeItem.PlaylistItem p => new() { Kind = "playlist", Title = p.Playlist.Title, Subtitle = p.Playlist.Subtitle, ThumbnailUrl = p.Playlist.ThumbnailUrl, Id = p.Playlist.PlaylistId },
        _ => Query(item.Title),
    };

    public HomeItem? ToItem() => Kind switch
    {
        "song" when Song is not null => new HomeItem.SongItem(Song),
        "album" when Id is not null => new HomeItem.AlbumItem(new AlbumSummary(Id, Title, Subtitle, ThumbnailUrl)),
        "artist" when Id is not null => new HomeItem.ArtistItem(new ArtistSummary(Id, Title, Subtitle, ThumbnailUrl)),
        "playlist" when Id is not null => new HomeItem.PlaylistItem(new PlaylistSummary(Id, Title, Subtitle, ThumbnailUrl)),
        _ => null,
    };
}
