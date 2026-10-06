using System.Text.Json;
using System.Text.Json.Nodes;

namespace Wave.Core.Music;

/// <summary>InnerTube の応答をたどるための小さな道具。途中が無い・型が違えば null や空を返し、例外を投げない。</summary>
internal static class JsonPath
{
    public static JsonNode? Path(this JsonNode? node, params string[] keys)
    {
        var current = node;
        foreach (var key in keys)
        {
            if (current is not JsonObject obj || !obj.TryGetPropertyValue(key, out current)) return null;
        }
        return current;
    }

    public static JsonObject? Obj(this JsonNode? node) => node as JsonObject;

    public static List<JsonObject> Items(this JsonNode? node) =>
        node is JsonArray array ? array.OfType<JsonObject>().ToList() : [];

    public static JsonObject? FirstItem(this JsonNode? node) => node is JsonArray array ? array.OfType<JsonObject>().FirstOrDefault() : null;

    public static string? Str(this JsonNode? node) =>
        node is JsonValue value && value.GetValueKind() == JsonValueKind.String ? value.GetValue<string>() : null;

    /// <summary>数か、数の文字列を整数として読む。</summary>
    public static long? Long(this JsonNode? node)
    {
        if (node is not JsonValue value) return null;
        return value.GetValueKind() switch
        {
            JsonValueKind.Number when value.TryGetValue<long>(out var n) => n,
            JsonValueKind.Number when value.TryGetValue<double>(out var d) => (long)d,
            JsonValueKind.String when long.TryParse(value.GetValue<string>(), out var s) => s,
            _ => null,
        };
    }

    /// <summary>key という名前のオブジェクトを、木のどこにあってもすべて集める（深さ優先、出てくる順）。</summary>
    public static List<JsonObject> FindAll(this JsonNode? root, string key)
    {
        var found = new List<JsonObject>();
        void Walk(JsonNode? node)
        {
            switch (node)
            {
                case JsonObject obj:
                    foreach (var (name, value) in obj)
                    {
                        if (name == key && value is JsonObject hit) found.Add(hit);
                        Walk(value);
                    }
                    break;
                case JsonArray array:
                    foreach (var item in array) Walk(item);
                    break;
            }
        }
        Walk(root);
        return found;
    }
}
