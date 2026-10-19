using System.Security.Cryptography;

namespace Wave.Core.Connect;

/// <summary>
/// 身元鍵（ECDSA）と受け取りの鍵（ECDH）をソフトウェアで持つ端末。鍵は PKCS#8 で書き出せる（保存するときは呼ぶ側が保護する。例: DPAPI）。
/// </summary>
public sealed class SoftwareDevice : IDeviceSigner, IKxOpener, IDisposable
{
    private readonly ECDsa identity;
    private readonly ECDiffieHellman kx;

    public SoftwareDevice(ECDsa identity, ECDiffieHellman kx)
    {
        this.identity = identity;
        this.kx = kx;
        IdentityPub = WaveCrypto.EncodePublicKey(identity);
        // P-256 でない鍵はここで落とす
        _ = WaveCrypto.PublicKeyOf(identity);
        DeviceId = WaveCrypto.DeviceId(IdentityPub);
        KxPub = WaveCrypto.PublicKeyOf(kx);
    }

    /// <summary>新しい鍵で作る。</summary>
    public static SoftwareDevice Generate() => new(WaveCrypto.GenerateSigningKey(), WaveCrypto.GenerateEphemeralKeyPair());

    /// <summary>PKCS#8 の秘密鍵から作る。</summary>
    public static SoftwareDevice FromPkcs8(byte[] identityPkcs8, byte[] kxPkcs8)
    {
        var id = ECDsa.Create();
        id.ImportPkcs8PrivateKey(identityPkcs8, out _);
        var k = ECDiffieHellman.Create();
        k.ImportPkcs8PrivateKey(kxPkcs8, out _);
        return new SoftwareDevice(id, k);
    }

    public string DeviceId { get; }
    public byte[] IdentityPub { get; }
    public EcPublicKey KxPub { get; }

    public byte[] Sign(string label, byte[] message) => WaveCrypto.Sign(identity, label, message);

    public byte[] Dh(EcPublicKey peer) => WaveCrypto.Ecdh(kx, peer);

    /// <summary>家に加わるときの鍵の記述。</summary>
    public MemberKeys MemberKeys(Role role) => MemberChain.MemberKeys(this, KxPub, role);

    public void Dispose()
    {
        identity.Dispose();
        kx.Dispose();
    }
}
