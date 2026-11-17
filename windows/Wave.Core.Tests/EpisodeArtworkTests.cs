using System.Text.RegularExpressions;
using Wave.Core.Music;

namespace Wave.Core.Tests;

/// <summary>
/// エピソードの行の絵に左右の黒い帯が出ないこと。
/// エピソードの絵は動画のサムネイル（i.ytimg の hq720 などの 16:9）で、音声の番組（Rebuild など）は正方形の番組の絵の左右に黒い帯が焼き込まれている。
/// 行の枠を正方形にして UniformToFill で真ん中を切れば、帯は枠の外へ落ちる（Android の行と同じ 64 の正方形）。
/// WinUI の Image の UniformToFill は、はみ出た分を右・下から切る（左の帯が残り右が切れる）ので、ImageBrush（AlignmentX・Y が Center）で切る。
/// </summary>
public class EpisodeArtworkTests
{
    [Fact]
    public void EpisodeThumbnailsAreWideVideoThumbnails()
    {
        // 前提: パーサーが選ぶエピソードの絵は、動画のサムネイル（16:9。正方形の番組の絵は左右に帯が入った形で来る）
        var page = MusicParsers.PodcastPage(Fixtures.Json("browse_podcast"), "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv")!;
        Assert.NotEmpty(page.Episodes);
        Assert.All(page.Episodes, e => Assert.Matches(@"^https://i\.ytimg\.com/vi/[^/]+/hq720\.jpg", e.Song.ThumbnailUrl));
    }

    [Theory]
    [InlineData("PodcastPage.xaml")]
    [InlineData("SearchPage.xaml")]
    public void EpisodeRowArtworkIsSquareAndCropped(string page)
    {
        var xaml = File.ReadAllText(Path.Combine(AppViews(), page));
        var template = Regex.Match(xaml, @"<DataTemplate x:DataType=""music:PodcastEpisode"">(.*?)</DataTemplate>", RegexOptions.Singleline);
        Assert.True(template.Success, page + " にエピソードの行が無い");
        var frame = Regex.Match(template.Groups[1].Value, @"<Grid Width=""(\d+)"" Height=""(\d+)""[^>]*>\s*<Border [^>]*>\s*<Border\.Background>\s*<ImageBrush [^>]*Stretch=""(\w+)""[^>]*/>");
        Assert.True(frame.Success, page + " のエピソードの行に絵の枠が無い");
        Assert.Equal(frame.Groups[1].Value, frame.Groups[2].Value);
        Assert.Equal("UniformToFill", frame.Groups[3].Value);
        Assert.Contains(@"AlignmentX=""Center""", frame.Value);
        Assert.Contains(@"AlignmentY=""Center""", frame.Value);
        Assert.DoesNotContain("<Image ", template.Groups[1].Value);
    }

    private static string AppViews()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var views = Path.Combine(dir.FullName, "Wave.App", "Views");
            if (Directory.Exists(views)) return views;
        }
        throw new DirectoryNotFoundException("windows/Wave.App/Views が見つからない");
    }
}
