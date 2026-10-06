using System.Text.Json.Nodes;

namespace Wave.Core.Tests;

internal static class Fixtures
{
    public static string Text(string name) => File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", name + ".json"));

    public static JsonNode Json(string name) => JsonNode.Parse(Text(name))!;

    public static JsonNode Parse(string text) => JsonNode.Parse(text)!;
}
