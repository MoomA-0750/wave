using Wave.Core.Auth;
using Wave.Core.Net;

namespace Wave.Core.Tests;

public class AuthTests
{
    [Fact]
    public void CredentialsNeedSapisidAndHideValues()
    {
        Assert.Null(Credentials.FromLogin("HSID=a; SID=b", null, null, 0));
        var c = Credentials.FromLogin("SID=b; __Secure-3PAPISID=xyz", "v", "123||456", 0)!;
        Assert.Equal("123", c.RevealDataSyncId());
        Assert.DoesNotContain("xyz", c.ToString());
        Assert.Null(Credentials.FromLogin("SAPISID=a\n", null, null, 0));
        var restored = Credentials.Deserialize(c.Serialize())!;
        Assert.Equal(c.RevealCookie(), restored.RevealCookie());
        Assert.Equal("v", restored.VisitorData);
    }

    [Fact]
    public void SapisidHashMatchesTheKnownFormat()
    {
        // sha1("1700000000 abc https://music.youtube.com")
        var expected = Convert.ToHexStringLower(System.Security.Cryptography.SHA1.HashData("1700000000 abc https://music.youtube.com"u8.ToArray()));
        Assert.Equal($"SAPISIDHASH 1700000000_{expected}", InnerTubeClient.SapisidHash("abc", 1700000000));
    }

    [Fact]
    public void PrefCookieCarriesLanguageAndRegion()
    {
        var client = new InnerTubeClient(new HttpClient(), "ja", "JP");
        Assert.Equal("PREF=hl=ja&gl=JP", client.WithPref(null));
        Assert.Equal("SID=a; PREF=hl=ja&gl=JP&f6=8", client.WithPref("PREF=hl=en&f6=8; SID=a"));
    }
}
