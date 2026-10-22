using Microsoft.UI.Dispatching;
using Wave.App.Services.Connect;
using Wave.Core.LocalApi;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.App.Services;

/// <summary>
/// 同じ PC のほかのアプリ向けの口（docs/local-api.md）の中身。操作は画面で同じことをしたのと同じ手順で行う
/// （ほかの端末で鳴っていればその端末への命令、曲を選べばその端末で鳴らす）。どれも画面の糸で行う。
/// </summary>
public sealed class LocalApiBackend(DispatcherQueue ui) : ILocalApiBackend
{
    /// <summary>連携を使っていないときの、この PC の ID。</summary>
    private const string LocalId = "this-pc";

    /// <summary>プレイリストの曲を読むのはここまで（wave-api と同じ）。</summary>
    private const int MaxTracks = 400;

    private static PlayerService Player => AppServices.Player;

    private Task<T> OnUi<T>(Func<Task<T>> work)
    {
        var done = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!ui.TryEnqueue(async () =>
            {
                try
                {
                    done.SetResult(await work());
                }
                catch (Exception e)
                {
                    done.SetException(e);
                }
            }))
            done.SetException(new InvalidOperationException("ui closed"));
        return done.Task;
    }

    private Task<T> OnUi<T>(Func<T> work) => OnUi(() => Task.FromResult(work()));

    public Task<PlaybackView> PlaybackAsync() => OnUi(() =>
    {
        var song = Player.Current;
        var duration = Player.Duration > 0 ? Player.Duration : song?.DurationSeconds ?? 0;
        var track = song is null ? null : TrackView.Of(song, 544) with { DurationMs = song.IsLive || duration <= 0 ? null : (long)(duration * 1000) };
        return new PlaybackView(
            AppServices.Connect.Info.InHousehold,
            AppServices.Account.IsLoggedIn,
            Player.IsPlaying,
            Player.Repeat switch { RepeatMode.One => "track", RepeatMode.All => "context", _ => "off" },
            track,
            song is null || song.IsLive ? null : (long)(Player.Position * 1000),
            DateTimeOffset.UtcNow.ToString("O"),
            PlayingDevice(),
            Player.CanChangeVolume ? (int)Math.Round(Player.Volume * 100) : null);
    });

    public Task<IReadOnlyList<TrackView>> QueueAsync() => OnUi<IReadOnlyList<TrackView>>(() =>
        Player.Queue.Skip(Math.Max(0, Player.Index + 1)).Select(s => TrackView.Of(s)).ToList());

    public Task<IReadOnlyList<DeviceView>> DevicesAsync() => OnUi<IReadOnlyList<DeviceView>>(() =>
    {
        var members = AppServices.Connect.Members;
        if (members.Count == 0) return [Self()];
        var playing = PlayingDevice()?.Id;
        return members.Select(m => View(m) with { Active = m.DeviceId == playing }).ToList();
    });

    private static DeviceView View(MemberView m) =>
        new(m.DeviceId, m.Label, m.IsApi ? "api" : m.IsPc || m.IsSelf ? "pc" : "phone", m.IsSelf || m.Online, m.Active, m.IsSelf);

    private static DeviceView Self()
    {
        var self = AppServices.Connect.Members.FirstOrDefault(m => m.IsSelf);
        return self is null ? new DeviceView(LocalId, Environment.MachineName, "pc", true, !Player.IsRemote, true) : View(self) with { Active = !Player.IsRemote };
    }

    /// <summary>今鳴らしている（映している）端末。曲が無ければ null。</summary>
    private static DeviceView? PlayingDevice()
    {
        if (Player.Current is null) return null;
        if (!Player.IsRemote) return Self();
        var remote = AppServices.Connect.Remote;
        var member = AppServices.Connect.Members.FirstOrDefault(m => m.DeviceId == remote?.DeviceId);
        if (member is not null) return View(member) with { Active = true };
        return new DeviceView(remote?.DeviceId ?? "", Player.RemoteDevice ?? "", null, remote?.Online ?? false, true);
    }

    public Task<Reply> ControlAsync(string action, long? positionMs, int? volumePercent) => OnUi(() =>
    {
        if (Player.Current is null) return Reply.NotSent;
        switch (action)
        {
            case "play":
                if (!Player.IsPlaying) Player.TogglePlay();
                break;
            case "pause":
                if (Player.IsPlaying) Player.TogglePlay();
                break;
            case "next":
                Player.Next();
                break;
            case "previous":
                Player.Previous();
                break;
            case "seek":
                if (Player.IsLive) return Reply.NotSent;
                Player.Seek(positionMs!.Value / 1000.0);
                break;
            case "volume":
                // スマホで鳴らしている間は変えない（画面のつまみが灰色のときと同じ）
                if (!Player.CanChangeVolume) return Reply.NotSent;
                Player.Volume = volumePercent!.Value / 100.0;
                break;
            default:
                return Reply.BadRequest;
        }
        return Reply.Ok;
    });

    public Task<Reply> TransferAsync(string deviceId) => OnUi(() =>
    {
        var self = Self();
        if (deviceId == self.Id)
        {
            if (Player.IsRemote) AppServices.Connect.Transfer();
            return new Reply(DeviceId: deviceId);
        }
        var member = AppServices.Connect.Members.FirstOrDefault(m => m.DeviceId == deviceId);
        if (member is null) return Reply.NotFound;
        if (member.IsApi || !member.Online) return Reply.NotSent;
        AppServices.Connect.PlayOnDevice(deviceId);
        return new Reply(DeviceId: deviceId);
    });

    public Task<Reply> PlayAsync(PlayRequest request) => OnUi(async () =>
    {
        // 鳴らす先は画面で曲を選んだときと同じ（ほかの端末で鳴っていればその端末、無ければこの PC）。deviceId は見ない
        if (request.PlaylistId is { } playlistId)
        {
            var page = await AppServices.Music.FullPlaylistAsync(playlistId);
            if (!page.IsOk) return Reply.Failed(page.Failure!.Value);
            if (!Start(page.Value!.Songs.Take(MaxTracks).ToList(), playlistId)) return Reply.NotFound;
        }
        else if (request.AlbumBrowseId is { } browseId)
        {
            var album = await AppServices.Music.AlbumAsync(browseId);
            if (!album.IsOk) return Reply.Failed(album.Failure!.Value);
            if (!Start(album.Value!.Songs, browseId)) return Reply.NotFound;
        }
        else
        {
            var song = await SongAsync(request.VideoId!);
            if (!song.IsOk) return Reply.Failed(song.Failure!.Value);
            Player.PlaySong(song.Value!);
        }
        return new Reply(DeviceId: PlayingDevice()?.Id);

        bool Start(IReadOnlyList<Song> songs, string source)
        {
            if (songs.Count == 0) return false;
            var startId = request.StartVideoId ?? request.VideoId;
            var start = startId is null ? 0 : Math.Max(0, songs.ToList().FindIndex(s => s.VideoId == startId));
            Player.PlayList(songs, start, source, request.Shuffle ? true : null);
            return true;
        }
    });

    public Task<Reply> QueueAddAsync(IReadOnlyList<string> videoIds, bool next) => OnUi(async () =>
    {
        var songs = new List<Song>();
        foreach (var id in videoIds)
        {
            var song = await SongAsync(id);
            if (song.IsOk) songs.Add(song.Value!);
            else if (song.Failure != FailureKind.Parse) return Reply.Failed(song.Failure!.Value);
        }
        if (songs.Count == 0) return Reply.NotFound;
        Player.QueueAll(songs, next);
        return Reply.Ok;
    });

    /// <summary>videoId から曲の中身（曲名・アーティストなど）を読む（その曲からのラジオの先頭）。</summary>
    private static async Task<ApiResult<Song>> SongAsync(string videoId)
    {
        var page = await AppServices.Music.UpNextAsync(videoId, null);
        if (!page.IsOk) return ApiResult<Song>.Failed(page.Failure!.Value);
        return page.Value!.Songs.FirstOrDefault(s => s.VideoId == videoId) is { } song ? ApiResult<Song>.Ok(song) : ApiResult<Song>.Failed(FailureKind.Parse);
    }

    public Task<Reply> JumpAsync(int? index, string? videoId) => OnUi(() =>
    {
        var first = Player.Index + 1;
        var upcoming = Player.Queue.Skip(Math.Max(0, first)).ToList();
        int at;
        if (index is { } n)
        {
            if (n >= upcoming.Count || (videoId is not null && upcoming[n].VideoId != videoId)) return Reply.NotFound;
            at = n;
        }
        else
        {
            at = upcoming.FindIndex(s => s.VideoId == videoId);
            if (at < 0) return Reply.NotFound;
        }
        Player.PlayAt(first + at);
        return new Reply(DeviceId: PlayingDevice()?.Id);
    });

    public Task<ApiResult<IReadOnlyList<TrackView>>> SearchAsync(string query) => OnUi(async () =>
        (await AppServices.Music.SearchSongsAsync(query)).Map<IReadOnlyList<TrackView>>(p => p.Songs.Select(s => TrackView.Of(s)).ToList()));

    public Task<ApiResult<IReadOnlyList<PlaylistView>>> PlaylistsAsync() => OnUi(async () =>
        (await AppServices.Music.LibraryPlaylistsAsync()).Map<IReadOnlyList<PlaylistView>>(list => list.Select(Playlist).ToList()));

    public Task<ApiResult<IReadOnlyList<TrackView>>> PlaylistTracksAsync(string id) => OnUi(async () =>
        (await AppServices.Music.FullPlaylistAsync(id)).Map<IReadOnlyList<TrackView>>(p => p.Songs.Take(MaxTracks).Select(s => TrackView.Of(s)).ToList()));

    public Task<ApiResult<IReadOnlyList<ShelfView>>> SuggestionsAsync() => OnUi(async () =>
        (await AppServices.Music.HomeAsync()).Map<IReadOnlyList<ShelfView>>(home => home.Shelves
            .Select(shelf => new ShelfView(shelf.Title,
                shelf.Items.OfType<HomeItem.SongItem>().Select(s => TrackView.Of(s.Song)).ToList(),
                shelf.Items.OfType<HomeItem.PlaylistItem>().Select(p => Playlist(p.Playlist)).ToList(),
                shelf.Items.OfType<HomeItem.AlbumItem>().Select(a => new PlaylistView(a.Album.BrowseId, a.Album.Title, a.Album.Subtitle, a.Album.ThumbnailUrl)).ToList()))
            .Where(s => s.Tracks.Count + s.Playlists.Count + s.Albums.Count > 0)
            .ToList()));

    private static PlaylistView Playlist(PlaylistSummary p) => new(p.PlaylistId, p.Title, p.Subtitle, p.ThumbnailUrl);
}
