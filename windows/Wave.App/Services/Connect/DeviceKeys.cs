using System.Security.Cryptography;
using Wave.Core.Connect;

namespace Wave.App.Services.Connect;

/// <summary>
/// 端末の連携（Connect v2）の、この端末の鍵（身元の署名鍵と受け取りの鍵 kx、どちらも P-256）。
/// Windows の鍵の保管庫（CNG のソフトウェアの保管庫、今のユーザー）に、書き出しできない鍵として作る。
/// 同じユーザーのほかのプログラムでも鍵そのものは取り出せない（Android の Keystore の鍵と同じく、使えるのはこの PC の上だけ）。
/// </summary>
public static class DeviceKeys
{
    private const string IdentityName = "xyz.mooma0750.wave/connect-identity/v2";
    private const string KxName = "xyz.mooma0750.wave/connect-kx/v2";

    public enum LoadState { Loaded, Created, Unreadable }

    /// <summary>保管庫の鍵を開く。無ければ作る。開けない（壊れた・片方だけ残った）ときは作り直さない。</summary>
    public static (LoadState State, SoftwareDevice? Device) LoadOrCreate()
    {
        var provider = CngProvider.MicrosoftSoftwareKeyStorageProvider;
        var hasIdentity = CngKey.Exists(IdentityName, provider);
        var hasKx = CngKey.Exists(KxName, provider);
        try
        {
            if (hasIdentity && hasKx)
            {
                return (LoadState.Loaded, Device(CngKey.Open(IdentityName, provider), CngKey.Open(KxName, provider)));
            }
            // 片方だけ残っているのは、作る途中で落ちたか壊れた。勝手に作り直すと端末 ID が変わるので、本人に任せる
            if (hasIdentity || hasKx) return (LoadState.Unreadable, null);
            var identity = Create(IdentityName, CngAlgorithm.ECDsaP256, CngKeyUsages.Signing);
            var kx = Create(KxName, CngAlgorithm.ECDiffieHellmanP256, CngKeyUsages.KeyAgreement);
            return (LoadState.Created, Device(identity, kx));
        }
        catch (CryptographicException e)
        {
            AppPaths.Log($"connect-v2 keys failed type={e.GetType().Name} code={e.HResult:X8}");
            return (LoadState.Unreadable, null);
        }
    }

    /// <summary>保管庫の鍵を消す（連携の保存を作り直すとき）。</summary>
    public static void Delete()
    {
        var provider = CngProvider.MicrosoftSoftwareKeyStorageProvider;
        foreach (var name in new[] { IdentityName, KxName })
        {
            try
            {
                if (CngKey.Exists(name, provider)) CngKey.Open(name, provider).Delete();
            }
            catch (CryptographicException)
            {
            }
        }
    }

    private static CngKey Create(string name, CngAlgorithm algorithm, CngKeyUsages usage) =>
        CngKey.Create(algorithm, name, new CngKeyCreationParameters
        {
            Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider,
            ExportPolicy = CngExportPolicies.None,
            KeyUsage = usage,
            KeyCreationOptions = CngKeyCreationOptions.None,
        });

    private static SoftwareDevice Device(CngKey identity, CngKey kx)
    {
        if (identity.ExportPolicy != CngExportPolicies.None || kx.ExportPolicy != CngExportPolicies.None) throw new CryptographicException("exportable key");
        return new SoftwareDevice(new ECDsaCng(identity), new ECDiffieHellmanCng(kx));
    }

    /// <summary>DPAPI で包んでいた前の鍵のファイル（取り出せる鍵）。読まずに消す。</summary>
    public static void DeleteLegacyFile(string path)
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
