using System.Globalization;

namespace Wave.Core.Connect;

/// <summary>端末の身元鍵での署名（Android では Keystore の鍵。Windows では保護して保存したソフトウェアの鍵など）。</summary>
public interface IDeviceSigner
{
    string DeviceId { get; }

    /// <summary>身元の署名公開鍵（SPKI）。</summary>
    byte[] IdentityPub { get; }

    /// <summary>用途ラベルを前置した署名（<see cref="WaveCrypto.Sign"/> と同じ形、DER）。</summary>
    byte[] Sign(string label, byte[] message);
}

public sealed record ChainMember(MemberKeys Keys, EcPublicKey Identity, EcPublicKey Kx, long AddedSeq);

/// <summary>genesis から検証した端末一覧。</summary>
public sealed class VerifiedChain(string householdId, long headSeq, string headHash, IReadOnlyDictionary<string, ChainMember> members, IReadOnlySet<string> removed, IReadOnlyList<string> hashes)
{
    public string HouseholdId { get; } = householdId;
    public long HeadSeq { get; } = headSeq;
    public string HeadHash { get; } = headHash;

    /// <summary>加えた順（Kotlin の LinkedHashMap と同じ順）。</summary>
    public IReadOnlyDictionary<string, ChainMember> Members { get; } = members;

    public IReadOnlySet<string> Removed { get; } = removed;

    public string? HashAt(long seq) => seq >= 0 && seq < hashes.Count ? hashes[(int)seq] : null;
}

public abstract record ChainVerification
{
    public sealed record Ok(VerifiedChain Chain) : ChainVerification;

    public sealed record Invalid(long Seq, string Reason) : ChainVerification;

    /// <summary>自分が検証した頭より短い（サーバーが巻き戻した）。</summary>
    public sealed record Rollback : ChainVerification;

    /// <summary>自分が検証した頭と同じ seq で、ハッシュが違う（サーバーが分岐を見せた）。</summary>
    public sealed record Fork : ChainVerification;
}

/// <summary>端末一覧の鎖（D-2）。記録の作成と、genesis からの検証（Android 版の MemberChain と同じ）。</summary>
public static class MemberChain
{
    public const string ChainLabel = "wave-v2-chain";
    public const string KxLabel = "wave-v2-kx";
    private const int MaxRecords = 1024;

    public sealed record Head(long Seq, string Hash);

    /// <summary>この端末が家に加わるときの鍵の記述（kx を身元鍵で自己署名する）。</summary>
    public static MemberKeys MemberKeys(IDeviceSigner signer, EcPublicKey kxPub, Role role)
    {
        var kx = Hpke.SerializePublicKey(kxPub);
        var signature = signer.Sign(KxLabel, KxMessage(signer.DeviceId, kx, role));
        return new MemberKeys { DeviceId = signer.DeviceId, IdentityPub = B64.Encode(signer.IdentityPub), KxPub = B64.Encode(kx), Role = role, KxSignature = B64.Encode(signature) };
    }

    public static ChainRecord Genesis(IDeviceSigner signer, MemberKeys self)
    {
        if (self.DeviceId != signer.DeviceId || self.Role != Role.Player) throw new ArgumentException("genesis は自分の player の鍵で作る");
        return Signed(signer, new ChainRecord { Seq = 0, Type = ChainType.Genesis, Target = self, SignerId = signer.DeviceId, Signature = "" });
    }

    public static ChainRecord Add(IDeviceSigner signer, VerifiedChain chain, MemberKeys target) =>
        Signed(signer, new ChainRecord { HouseholdId = chain.HouseholdId, Seq = chain.HeadSeq + 1, PrevHash = chain.HeadHash, Type = ChainType.Add, Target = target, SignerId = signer.DeviceId, Signature = "" });

    public static ChainRecord Remove(IDeviceSigner signer, VerifiedChain chain, string deviceId) =>
        Signed(signer, new ChainRecord { HouseholdId = chain.HouseholdId, Seq = chain.HeadSeq + 1, PrevHash = chain.HeadHash, Type = ChainType.Remove, RemovedDeviceId = deviceId, SignerId = signer.DeviceId, Signature = "" });

    /// <summary>記録のハッシュ（householdId・prevHash・頭）。署名は含めない。</summary>
    public static string Hash(ChainRecord record) => B64.Encode(WaveCrypto.Sha256(Canonical(record)));

    /// <summary>genesis から検証する。trusted（この端末が前に検証した頭）があれば、それより短い鎖と、その seq でハッシュが違う鎖を拒む。</summary>
    public static ChainVerification Verify(IReadOnlyList<ChainRecord> records, Head? trusted = null)
    {
        if (records.Count == 0 || records.Count > MaxRecords) return new ChainVerification.Invalid(0, "size");
        var members = new OrderedDictionary<string, ChainMember>();
        var removed = new HashSet<string>();
        var hashes = new List<string>();
        var householdId = "";
        for (var index = 0; index < records.Count; index++)
        {
            var record = records[index];
            long seq = index;
            ChainVerification Invalid(string reason) => new ChainVerification.Invalid(seq, reason);
            if (record.V != ChainRecord.FormatVersion || record.Seq != seq) return Invalid("seq");
            if (index == 0)
            {
                if (record.Type != ChainType.Genesis || record.HouseholdId is not null || record.PrevHash is not null) return Invalid("genesis");
                var target = record.Target;
                if (target is null) return Invalid("genesis_target");
                if (target.Role != Role.Player || record.SignerId != target.DeviceId || record.RemovedDeviceId is not null) return Invalid("genesis_signer");
                var member = MemberOf(target, seq);
                if (member is null) return Invalid("genesis_keys");
                if (!VerifySignature(record, member.Identity)) return Invalid("signature");
                members[target.DeviceId] = member;
                householdId = Hash(record);
            }
            else
            {
                if (record.HouseholdId != householdId || record.PrevHash != hashes[^1]) return Invalid("link");
                if (!members.TryGetValue(record.SignerId, out var signer)) return Invalid("signer_not_member");
                if (!VerifySignature(record, signer.Identity)) return Invalid("signature");
                switch (record.Type)
                {
                    case ChainType.Genesis:
                        return Invalid("second_genesis");
                    case ChainType.Add:
                    {
                        var target = record.Target;
                        if (target is null) return Invalid("add_target");
                        if (record.RemovedDeviceId is not null) return Invalid("add_shape");
                        // player を加えられるのは player だけ（L-11）。controller も当面は player だけが加える
                        if (signer.Keys.Role != Role.Player) return Invalid("add_by_controller");
                        if (members.ContainsKey(target.DeviceId) || removed.Contains(target.DeviceId)) return Invalid("add_existing");
                        var member = MemberOf(target, seq);
                        if (member is null) return Invalid("add_keys");
                        members[target.DeviceId] = member;
                        break;
                    }
                    case ChainType.Remove:
                    {
                        var targetId = record.RemovedDeviceId;
                        if (targetId is null) return Invalid("remove_target");
                        if (record.Target is not null) return Invalid("remove_shape");
                        if (!members.TryGetValue(targetId, out var target)) return Invalid("remove_not_member");
                        // controller は自分だけを外せる
                        if (signer.Keys.Role != Role.Player && targetId != signer.Keys.DeviceId) return Invalid("remove_by_controller");
                        // player がいなくなる remove は拒む
                        if (target.Keys.Role == Role.Player && members.Values.Count(m => m.Keys.Role == Role.Player) <= 1) return Invalid("last_player");
                        members.Remove(targetId);
                        removed.Add(targetId);
                        break;
                    }
                }
            }
            hashes.Add(Hash(record));
        }
        if (trusted is not null)
        {
            if (records.Count <= trusted.Seq) return new ChainVerification.Rollback();
            if (hashes[(int)trusted.Seq] != trusted.Hash) return new ChainVerification.Fork();
        }
        return new ChainVerification.Ok(new VerifiedChain(householdId, records.Count - 1L, hashes[^1], members, removed, hashes));
    }

    private static ChainMember? MemberOf(MemberKeys keys, long seq)
    {
        var identityBytes = B64.Decode(keys.IdentityPub);
        if (identityBytes is null) return null;
        var identity = WaveCrypto.DecodePublicKey(identityBytes);
        if (identity is null) return null;
        if (WaveCrypto.DeviceId(identityBytes) != keys.DeviceId) return null;
        var kxBytes = B64.Decode(keys.KxPub);
        if (kxBytes is null) return null;
        var kx = Hpke.DeserializePublicKey(kxBytes);
        if (kx is null) return null;
        var kxSignature = B64.Decode(keys.KxSignature);
        if (kxSignature is null) return null;
        if (!WaveCrypto.Verify(identity, KxLabel, KxMessage(keys.DeviceId, kxBytes, keys.Role), kxSignature)) return null;
        return new ChainMember(keys, identity, kx, seq);
    }

    private static ChainRecord Signed(IDeviceSigner signer, ChainRecord record) =>
        record with { Signature = B64.Encode(signer.Sign(ChainLabel, Canonical(record))) };

    private static bool VerifySignature(ChainRecord record, EcPublicKey identity)
    {
        var signature = B64.Decode(record.Signature);
        return signature is not null && WaveCrypto.Verify(identity, ChainLabel, Canonical(record), signature);
    }

    private static byte[] KxMessage(string deviceId, byte[] kx, Role role) =>
        WaveCrypto.LengthPrefixed(WaveCrypto.Utf8(deviceId), kx, WaveCrypto.Utf8(role.ToString()));

    /// <summary>署名とハッシュの対象（署名そのものを除く全部を長さ付きで連結）。enum は Kotlin の enum の名前（"Genesis"・"Player"）。</summary>
    internal static byte[] Canonical(ChainRecord record)
    {
        var target = record.Target;
        string[] parts =
        [
            record.V.ToString(CultureInfo.InvariantCulture),
            record.HouseholdId ?? "",
            record.Seq.ToString(CultureInfo.InvariantCulture),
            record.PrevHash ?? "",
            record.Type.ToString(),
            target?.DeviceId ?? "",
            target?.IdentityPub ?? "",
            target?.KxPub ?? "",
            target?.Role.ToString() ?? "",
            target?.KxSignature ?? "",
            record.RemovedDeviceId ?? "",
            record.SignerId,
        ];
        return WaveCrypto.LengthPrefixed(parts.Select(WaveCrypto.Utf8).ToArray());
    }
}
