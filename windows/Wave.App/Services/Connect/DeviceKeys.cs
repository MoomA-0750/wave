using System.Security.Cryptography;
using System.Text;
using Wave.Core.Connect;

namespace Wave.App.Services.Connect;

/// <summary>
/// 端末の連携（Connect v2）の、この端末の鍵（身元の署名鍵と受け取りの鍵 kx、どちらも P-256）の保存。
/// Android の Keystore の代わりに、Windows のデータ保護（DPAPI、今のユーザーだけが解ける）で包んでファイルに置く。
/// 秘密鍵はファイル・ログに平文で出さない。
/// </summary>
public static class DeviceKeys
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("xyz.mooma0750.wave/connect-keys/v1");

    public enum LoadState { Loaded, Created, Unreadable }

    /// <summary>保存した鍵を読む。無ければ作って保存する。読めない（別のユーザー・壊れた）ときは作り直さない。</summary>
    public static (LoadState State, SoftwareDevice? Device) LoadOrCreate(string path)
    {
        if (File.Exists(path))
        {
            try
            {
                var plain = ProtectedData.Unprotect(File.ReadAllBytes(path), Entropy, DataProtectionScope.CurrentUser);
                try
                {
                    var length = BitConverter.ToInt32(plain, 0);
                    return (LoadState.Loaded, SoftwareDevice.FromPkcs8(plain.AsSpan(4, length).ToArray(), plain.AsSpan(4 + length).ToArray()));
                }
                finally
                {
                    CryptographicOperations.ZeroMemory(plain);
                }
            }
            catch (Exception e) when (e is CryptographicException or IOException or UnauthorizedAccessException or ArgumentException)
            {
                return (LoadState.Unreadable, null);
            }
        }
        var device = SoftwareDevice.Generate();
        var id = device.ExportIdentityPkcs8();
        var kx = device.ExportKxPkcs8();
        var all = new byte[4 + id.Length + kx.Length];
        BitConverter.GetBytes(id.Length).CopyTo(all, 0);
        id.CopyTo(all, 4);
        kx.CopyTo(all, 4 + id.Length);
        try
        {
            var temp = path + ".tmp";
            File.WriteAllBytes(temp, ProtectedData.Protect(all, Entropy, DataProtectionScope.CurrentUser));
            File.Move(temp, path, overwrite: true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(all);
            CryptographicOperations.ZeroMemory(id);
            CryptographicOperations.ZeroMemory(kx);
        }
        return (LoadState.Created, device);
    }
}
