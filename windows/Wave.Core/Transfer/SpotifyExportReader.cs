using System.IO.Compression;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Wave.Core.Transfer;

/// <summary>
/// Spotify の「データのダウンロード」（アカウントのプライバシーの画面から頼む ZIP）を読む。
/// 項目名の公式の説明は無いので、知られた形（YourLibrary.json・Playlist1.json…）を寛容に読む:
/// キーの大文字・小文字を区別せず、別名（trackName / track / name など）も試し、読めない項目は飛ばして数を残す。
/// 曲の長さは入っていない前提（突き合わせは曲名・アーティスト・アルバムだけで行う）。
/// </summary>
public static class SpotifyExportReader
{
    /// <summary>ZIP・JSON のファイル、または ZIP を展開したフォルダを読む。</summary>
    public static SpotifyLibrary ReadPath(string path)
    {
        if (Directory.Exists(path))
        {
            var files = Directory.EnumerateFiles(path, "*.json", SearchOption.AllDirectories)
                .Where(f => IsLibraryFile(Path.GetFileName(f)))
                .OrderBy(f => f, StringComparer.OrdinalIgnoreCase)
                .Select(f => (Path.GetFileName(f), (Func<System.IO.Stream>)(() => File.OpenRead(f))));
            return Read(files);
        }
        if (path.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
        {
            using var zip = ZipFile.OpenRead(path);
            var entries = zip.Entries
                .Where(e => e.Name.EndsWith(".json", StringComparison.OrdinalIgnoreCase) && IsLibraryFile(e.Name))
                .OrderBy(e => e.FullName, StringComparer.OrdinalIgnoreCase)
                .Select(e => (e.Name, (Func<System.IO.Stream>)e.Open))
                .ToList();
            return Read(entries);
        }
        // 1つの JSON を選んだときは、名前が違っても中身で見分ける
        return Read([(Path.GetFileName(path), () => File.OpenRead(path))], requireKnownName: false);
    }

    /// <summary>ライブラリ（YourLibrary）かプレイリスト（Playlist1・Playlist2…）のファイルか。再生履歴などほかのファイルは読まない。</summary>
    public static bool IsLibraryFile(string name) =>
        name.EndsWith(".json", StringComparison.OrdinalIgnoreCase)
        && (name.StartsWith("YourLibrary", StringComparison.OrdinalIgnoreCase) || name.StartsWith("Playlist", StringComparison.OrdinalIgnoreCase));

    public static SpotifyLibrary Read(IEnumerable<(string Name, Func<System.IO.Stream> Open)> files, bool requireKnownName = true)
    {
        var acc = new Accumulator();
        foreach (var (name, open) in files)
        {
            if (requireKnownName && !IsLibraryFile(name)) continue;
            JsonNode? root;
            try
            {
                using var stream = open();
                root = JsonNode.Parse(stream, documentOptions: new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
            }
            catch (Exception e) when (e is JsonException or IOException or InvalidDataException)
            {
                acc.Problems.Add(new ImportProblem(name, ImportProblemKind.Unreadable));
                continue;
            }
            if (!acc.ReadRoot(name, root)) acc.Problems.Add(new ImportProblem(name, ImportProblemKind.UnknownShape));
        }
        return acc.Build();
    }

    /// <summary>1つの JSON の文字列を読む（試験用）。</summary>
    public static SpotifyLibrary ReadJson(string name, string json) =>
        Read([(name, () => new MemoryStream(System.Text.Encoding.UTF8.GetBytes(json)))], requireKnownName: false);

    private sealed class Accumulator
    {
        public readonly List<SpotifyPlaylist> Playlists = [];
        public readonly List<SpotifyTrack> Liked = [];
        public readonly List<SpotifyAlbum> Albums = [];
        public readonly List<SpotifyArtist> Artists = [];
        public readonly List<ImportProblem> Problems = [];

        public SpotifyLibrary Build() => new(Playlists, Liked, Albums, Artists, Problems);

        /// <summary>知っている形の項目が1つでもあれば true。</summary>
        public bool ReadRoot(string file, JsonNode? root)
        {
            var found = false;
            var skipped = 0;
            var episodes = 0;
            if (root is JsonArray rootArray)
            {
                // 古い形: プレイリストの配列がそのまま
                foreach (var p in rootArray.OfType<JsonObject>())
                    if (ReadPlaylist(p, ref skipped, ref episodes)) found = true;
            }
            else if (root is JsonObject obj)
            {
                if (Get(obj, "playlists") is JsonArray playlists)
                {
                    found = true;
                    foreach (var p in playlists)
                        if (p is not JsonObject po || !ReadPlaylist(po, ref skipped, ref episodes)) skipped++;
                }
                if (Get(obj, "tracks") is JsonArray tracks)
                {
                    found = true;
                    foreach (var t in tracks)
                    {
                        if (t is JsonObject to && ReadTrack(to) is { } track) Liked.Add(track);
                        else skipped++;
                    }
                }
                if (Get(obj, "albums") is JsonArray albums)
                {
                    found = true;
                    foreach (var a in albums)
                    {
                        if (a is JsonObject ao && ReadAlbum(ao) is { } album) Albums.Add(album);
                        else skipped++;
                    }
                }
                if (Get(obj, "artists") is JsonArray artists)
                {
                    found = true;
                    foreach (var a in artists)
                    {
                        if (a is JsonObject ao && Str(ao, "name", "artistName", "artist") is { } n) Artists.Add(new SpotifyArtist(n, Str(ao, "uri", "artistUri")));
                        else if (a is JsonValue av && av.TryGetValue<string>(out var s) && s.Trim().Length > 0) Artists.Add(new SpotifyArtist(s.Trim()));
                        else skipped++;
                    }
                }
                // 1つのプレイリストだけの形（{"name", "items"}）
                if (!found && Get(obj, "items") is JsonArray && Str(obj, "name", "title", "playlistName") is not null)
                    found = ReadPlaylist(obj, ref skipped, ref episodes);
            }
            if (skipped > 0) Problems.Add(new ImportProblem(file, ImportProblemKind.SkippedItems, skipped));
            if (episodes > 0) Problems.Add(new ImportProblem(file, ImportProblemKind.SkippedEpisodes, episodes));
            return found;
        }

        private bool ReadPlaylist(JsonObject p, ref int skipped, ref int episodes)
        {
            var name = Str(p, "name", "title", "playlistName");
            var items = Get(p, "items") as JsonArray ?? Get(p, "tracks") as JsonArray;
            if (name is null || items is null) return false;
            var tracks = new List<SpotifyTrack>();
            foreach (var item in items)
            {
                if (item is not JsonObject io) { skipped++; continue; }
                // 曲は item.track。ほかの形（項目そのものが曲）も読む
                var trackNode = Get(io, "track") ?? Get(io, "item");
                if (trackNode is JsonObject to && ReadTrack(to) is { } track) { tracks.Add(track); continue; }
                if (trackNode is not JsonObject && ReadTrack(io) is { } flat) { tracks.Add(flat); continue; }
                if (Get(io, "localTrack") is { } local && ReadLocal(local) is { } localTrack) { tracks.Add(localTrack); continue; }
                if (Get(io, "episode") is JsonObject || Get(io, "audiobook") is JsonObject) { episodes++; continue; }
                skipped++;
            }
            Playlists.Add(new SpotifyPlaylist(name, tracks));
            return true;
        }

        private static SpotifyAlbum? ReadAlbum(JsonObject a)
        {
            var title = Str(a, "album", "albumName", "name", "title");
            return title is null ? null : new SpotifyAlbum(title, Names(a, "artist", "artistName", "artists"), Str(a, "uri", "albumUri"));
        }

        /// <summary>手元のファイルの曲。spotify:local:アーティスト:アルバム:曲名:秒 の URI か、曲と同じ形のもの。</summary>
        private static SpotifyTrack? ReadLocal(JsonNode local)
        {
            if (local is JsonObject lo)
            {
                if (ReadTrack(lo) is { } t) return t;
                local = Get(lo, "uri") ?? lo;
            }
            if (local is not JsonValue v || !v.TryGetValue<string>(out var uri)) return null;
            var parts = uri.Split(':');
            if (parts.Length < 6 || parts[1] != "local") return null;
            static string Unescape(string s) => Uri.UnescapeDataString(s.Replace('+', ' ')).Trim();
            var title = Unescape(parts[4]);
            if (title.Length == 0) return null;
            var artist = Unescape(parts[2]);
            var album = Unescape(parts[3]);
            int? ms = int.TryParse(parts[5], out var sec) && sec > 0 ? sec * 1000 : null;
            return new SpotifyTrack(title, artist.Length > 0 ? [artist] : [], album.Length > 0 ? album : null, ms);
        }
    }

    /// <summary>曲の1件（データのダウンロードの形と Web API の track の形の両方）。ポッドキャストのエピソードは null。</summary>
    internal static SpotifyTrack? ReadTrack(JsonObject t)
    {
        if (Str(t, "type") is "episode" or "audiobook" or "chapter") return null;
        var title = Str(t, "trackName", "track", "name", "title", "songName");
        if (title is null) return null;
        var artists = Names(t, "artistName", "artist", "artists");
        var album = Str(t, "albumName", "album") ?? (Get(t, "album") is JsonObject ao ? Str(ao, "name", "title") : null);
        var duration = Int(t, "durationMs", "duration_ms");
        var isrc = Str(t, "isrc") ?? (Get(t, "external_ids") is JsonObject ids ? Str(ids, "isrc") : null);
        return new SpotifyTrack(title, artists, album, duration, isrc, Str(t, "trackUri", "uri", "spotifyUri"));
    }

    /// <summary>キーの大文字・小文字を区別せずに引く。</summary>
    internal static JsonNode? Get(JsonObject o, string key)
    {
        if (o.TryGetPropertyValue(key, out var v)) return v;
        foreach (var (k, value) in o)
            if (string.Equals(k, key, StringComparison.OrdinalIgnoreCase)) return value;
        return null;
    }

    /// <summary>最初に見つかった空でない文字列。</summary>
    internal static string? Str(JsonObject o, params string[] keys)
    {
        foreach (var key in keys)
            if (Get(o, key) is JsonValue v && v.TryGetValue<string>(out var s) && s.Trim().Length > 0) return s.Trim();
        return null;
    }

    internal static int? Int(JsonObject o, params string[] keys)
    {
        foreach (var key in keys)
        {
            if (Get(o, key) is not JsonValue v) continue;
            if (v.TryGetValue<int>(out var i)) return i;
            if (v.TryGetValue<long>(out var l)) return (int)l;
            if (v.TryGetValue<double>(out var d)) return (int)d;
            if (v.TryGetValue<string>(out var s) && int.TryParse(s, out var p)) return p;
        }
        return null;
    }

    /// <summary>名前の一覧。文字列・文字列の配列・{name} の配列のどれでも読む。</summary>
    internal static IReadOnlyList<string> Names(JsonObject o, params string[] keys)
    {
        foreach (var key in keys)
        {
            switch (Get(o, key))
            {
                case JsonValue v when v.TryGetValue<string>(out var s) && s.Trim().Length > 0:
                    return [s.Trim()];
                case JsonArray a:
                    var names = a.Select(x => x switch
                    {
                        JsonValue xv when xv.TryGetValue<string>(out var xs) => xs.Trim(),
                        JsonObject xo => Str(xo, "name", "artistName"),
                        _ => null,
                    }).Where(n => !string.IsNullOrEmpty(n)).Select(n => n!).ToList();
                    if (names.Count > 0) return names;
                    break;
                case JsonObject single when Str(single, "name") is { } n:
                    return [n];
            }
        }
        return [];
    }
}
