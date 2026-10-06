namespace Wave.Core.Music;

/// <summary>
/// プレイリストをサーバーで並べる params（Android の PlaylistOrder と同じ。プレイリストのページの並べ替えのメニューの値）。
/// params は `{139: {1: 向き（1 = 昇順, 2 = 降順）, 2: 並べ方}}`、カスタム（Default ordering）は `{139: {}}`。
/// 自分のプレイリストではサーバーが最後に選んだ並べ方を覚えるので、開くたびに明示して送る。
/// </summary>
public static class PlaylistOrder
{
    /// <summary>並べ替えのメニューの「Default ordering」。</summary>
    public const string DefaultOrdering = "2ggA";

    /// <summary>追加した日。</summary>
    public const int Added = 3;

    /// <summary>動画の公開日（リリース順の代わり）。</summary>
    public const int Published = 1;

    public static string Params(int key, bool descending) =>
        Convert.ToBase64String([0xda, 0x08, 0x04, 0x08, (byte)(descending ? 2 : 1), 0x10, (byte)key]);
}
