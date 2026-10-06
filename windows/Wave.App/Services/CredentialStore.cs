using System.Security.Cryptography;
using System.Text;
using Wave.Core.Auth;

namespace Wave.App.Services;

/// <summary>
/// 認証情報を Windows のデータ保護（DPAPI、今のユーザーだけが解ける）で暗号化して保存する。
/// 平文はファイルにもログにも出さない。
/// </summary>
public sealed class CredentialStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("xyz.mooma0750.wave/credentials/v1");
    private readonly string path = Path.Combine(AppPaths.Data, "credentials.bin");

    public enum LoadState { None, Loaded, Unreadable }

    public (LoadState State, Credentials? Credentials) Load()
    {
        if (!File.Exists(path)) return (LoadState.None, null);
        try
        {
            var plain = ProtectedData.Unprotect(File.ReadAllBytes(path), Entropy, DataProtectionScope.CurrentUser);
            var credentials = Credentials.Deserialize(Encoding.UTF8.GetString(plain));
            CryptographicOperations.ZeroMemory(plain);
            return credentials is null ? (LoadState.Unreadable, null) : (LoadState.Loaded, credentials);
        }
        catch (Exception e) when (e is CryptographicException or IOException or UnauthorizedAccessException)
        {
            return (LoadState.Unreadable, null);
        }
    }

    public bool Save(Credentials credentials)
    {
        try
        {
            var plain = Encoding.UTF8.GetBytes(credentials.Serialize());
            var sealedBytes = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);
            CryptographicOperations.ZeroMemory(plain);
            var temp = path + ".tmp";
            File.WriteAllBytes(temp, sealedBytes);
            File.Move(temp, path, overwrite: true);
            return true;
        }
        catch (Exception e) when (e is CryptographicException or IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    public void Clear()
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch (IOException)
        {
        }
    }
}

/// <summary>アプリのデータの置き場所（%LOCALAPPDATA%\wave）。</summary>
public static class AppPaths
{
    public static string Data { get; } = Directory.CreateDirectory(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "wave")).FullName;

    /// <summary>ログイン用のブラウザ（WebView2）の置き場所。ログアウトで消す。</summary>
    public static string WebView => Path.Combine(Data, "WebView2");

    /// <summary>動作の記録（件数・状態だけ。認証情報・URL は書かない）。</summary>
    public static void Log(string message)
    {
        try
        {
            File.AppendAllText(Path.Combine(Data, "wave.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}{Environment.NewLine}");
        }
        catch (IOException)
        {
        }
    }
}
