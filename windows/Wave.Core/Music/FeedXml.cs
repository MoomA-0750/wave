using System.Text;

namespace Wave.Core.Music;

/// <summary>
/// ポッドキャストの RSS から、要る要素だけを拾う（Android の FeedXml と同じ読み方）。
/// 木は作らず、IndexOf で目当てのタグへ飛ぶ。壊れた入力・宣言の無い接頭辞・HTML の実体参照があっても例外にしない
/// （System.Xml の読み手はそれらで止まるので使わない。Android と同じ結果にそろえるためでもある）。
/// 要素の名前は接頭辞ごと（itunes:image など）で扱う。名前空間は解決しない。
/// </summary>
public sealed class XmlRange(string source, int start, int end, IReadOnlyDictionary<string, string>? attributes = null)
{
    private const string CdataOpen = "<![CDATA[";
    private const string CdataClose = "]]>";

    public string Source { get; } = source;

    /// <summary>中身の始まり（開きタグの &gt; の次）。</summary>
    public int Start { get; } = start;

    /// <summary>中身の終わり（閉じタグの &lt;）。</summary>
    public int End { get; } = end;

    /// <summary>開きタグの属性。</summary>
    public IReadOnlyDictionary<string, string> Attributes { get; } = attributes ?? new Dictionary<string, string>();

    /// <summary>範囲の中の最初の name の要素。skip の要素の中にあるものは飛ばす（チャンネルの題と &lt;image&gt; の題を分ける）。</summary>
    public XmlRange? Child(string name, string? skip = null)
    {
        var from = Start;
        while (true)
        {
            var found = FeedXml.Element(Source, name, from, End);
            if (found is null) return null;
            var skipped = skip is null ? null : FeedXml.Element(Source, skip, from, End);
            if (skipped is not null && skipped.Start <= found.Start && found.End <= skipped.End)
            {
                from = skipped.End;
                continue;
            }
            return found;
        }
    }

    /// <summary>中身だけを切り出した範囲（中を何度も探すときに、文書の終わりまで探さないため）。</summary>
    public XmlRange Detached() => new(Source.Substring(Start, End - Start), 0, End - Start, Attributes);

    /// <summary>範囲の中の name の要素を順に。</summary>
    public List<XmlRange> Children(string name)
    {
        var output = new List<XmlRange>();
        var from = Start;
        while (true)
        {
            var found = FeedXml.Element(Source, name, from, End);
            if (found is null) return output;
            output.Add(found);
            from = found.End;
        }
    }

    /// <summary>中の文字（CDATA はそのまま、ほかは実体参照をほどく）。前後の空白は落とす。</summary>
    public string Text
    {
        get
        {
            var output = new StringBuilder();
            var from = Start;
            while (from < End)
            {
                var cdata = FeedXml.Find(Source, CdataOpen, from);
                if (cdata < from || cdata >= End)
                {
                    output.Append(FeedXml.DecodeEntities(Source[from..End]));
                    break;
                }
                output.Append(FeedXml.DecodeEntities(Source[from..cdata]));
                var close = FeedXml.Find(Source, CdataClose, cdata);
                if (close < 0 || close > End) close = End;
                output.Append(Source, cdata + CdataOpen.Length, Math.Max(0, close - cdata - CdataOpen.Length));
                from = Math.Min(close + CdataClose.Length, End);
            }
            return output.ToString().Trim();
        }
    }

    /// <summary>子要素の文字。無い・空なら null。</summary>
    public string? ChildText(string name, string? skip = null) => Child(name, skip)?.Text is { Length: > 0 } text ? text : null;
}

public static class FeedXml
{
    /// <summary>文書の最初の name の要素。</summary>
    public static XmlRange? Root(string source, string name) => Element(source, name, 0, source.Length);

    /// <summary>
    /// from〜to の中の、最初の &lt;name の要素。閉じが無ければ範囲の終わりまで、/&gt; で閉じていれば中身は空。
    /// 同じ名前の要素の入れ子は考えない（RSS の item・title などは入れ子にならない）。
    /// </summary>
    public static XmlRange? Element(string source, string name, int from, int to)
    {
        var open = "<" + name;
        var at = Find(source, open, from);
        while (at >= from && at < to)
        {
            var after = at + open.Length;
            var next = after < source.Length ? source[after] : '>';
            if (next is '>' or '/' or ' ' or '\t' or '\n' or '\r')
            {
                var tagEnd = TagEnd(source, after);
                if (tagEnd < after || tagEnd >= to) return null;
                var selfClosing = source[tagEnd - 1] == '/';
                var attributes = Attributes(source, after, selfClosing ? tagEnd - 1 : tagEnd);
                if (selfClosing) return new XmlRange(source, tagEnd + 1, tagEnd + 1, attributes);
                var close = Find(source, "</" + name, tagEnd + 1);
                if (close < 0 || close > to) close = to;
                return new XmlRange(source, tagEnd + 1, close, attributes);
            }
            at = Find(source, open, after);
        }
        return null;
    }

    /// <summary>needle の位置（無ければ -1）。</summary>
    public static int Find(string source, string needle, int from) =>
        from > source.Length ? -1 : source.IndexOf(needle, from, StringComparison.Ordinal);

    /// <summary>開きタグの &gt; の位置。属性の値の中の &gt; は飛ばす。</summary>
    private static int TagEnd(string source, int from)
    {
        var end = source.IndexOf('>', from);
        while (end >= 0)
        {
            var tag = source.AsSpan(from, end - from);
            if (tag.Count('"') % 2 == 0 && tag.Count('\'') % 2 == 0) return end;
            end = source.IndexOf('>', end + 1);
        }
        return -1;
    }

    /// <summary>key="value" の並び（タグの文字だけを切り出して読む）。</summary>
    private static Dictionary<string, string> Attributes(string document, int tagFrom, int tagTo)
    {
        var source = document[tagFrom..tagTo];
        var to = source.Length;
        var output = new Dictionary<string, string>();
        var eq = source.IndexOf('=');
        var keyFrom = 0;
        while (eq >= keyFrom && eq < to)
        {
            var key = source[keyFrom..eq].Trim();
            var v = eq + 1;
            while (v < to && char.IsWhiteSpace(source[v])) v++;
            if (v >= to) break;
            var quote = source[v];
            if (quote is '"' or '\'')
            {
                var valueEnd = source.IndexOf(quote, v + 1);
                if (valueEnd < 0 || valueEnd > to) valueEnd = to;
                output[key] = DecodeEntities(source[(v + 1)..valueEnd]);
                keyFrom = Math.Min(valueEnd + 1, to);
            }
            else
            {
                var close = v;
                while (close < to && !char.IsWhiteSpace(source[close])) close++;
                output[key] = DecodeEntities(source[v..close]);
                keyFrom = close;
            }
            eq = source.IndexOf('=', keyFrom);
        }
        return output;
    }

    public static string DecodeEntities(string text)
    {
        var amp = text.IndexOf('&');
        if (amp < 0) return text;
        var output = new StringBuilder(text.Length);
        var from = 0;
        // 1字ずつ見ずに、& から & へ飛ぶ
        while (amp >= 0)
        {
            output.Append(text, from, amp - from);
            var end = text.IndexOf(';', amp);
            string? decoded = null;
            if (end >= amp + 2 && end <= amp + 10)
            {
                var entity = text[(amp + 1)..end];
                decoded = entity switch
                {
                    "amp" => "&",
                    "lt" => "<",
                    "gt" => ">",
                    "quot" => "\"",
                    "apos" => "'",
                    "nbsp" => " ",
                    _ when entity.StartsWith("#x", StringComparison.OrdinalIgnoreCase) =>
                        int.TryParse(entity.AsSpan(2), System.Globalization.NumberStyles.AllowHexSpecifier, null, out var hex) ? CodePoint(hex) : null,
                    _ when entity.StartsWith('#') =>
                        int.TryParse(entity.AsSpan(1), System.Globalization.NumberStyles.None, null, out var dec) ? CodePoint(dec) : null,
                    _ => null,
                };
            }
            if (decoded is not null)
            {
                output.Append(decoded);
                from = end + 1;
            }
            else
            {
                output.Append('&');
                from = amp + 1;
            }
            amp = text.IndexOf('&', from);
        }
        output.Append(text, from, text.Length - from);
        return output.ToString();
    }

    private static string? CodePoint(int code) =>
        code < 0 || code > 0x10FFFF || code is >= 0xD800 and <= 0xDFFF ? null : char.ConvertFromUtf32(code);
}
