using System.Buffers.Binary;
using System.Globalization;

namespace Wave.Core.Connect;

/// <summary>この端末の受け取りの鍵（kx）で ECDH する。</summary>
public interface IKxOpener
{
    string DeviceId { get; }
    EcPublicKey KxPub { get; }

    /// <summary>ECDH の共有秘密（x 座標、32バイト）。</summary>
    byte[] Dh(EcPublicKey peer);
}

public abstract record DocOpen
{
    /// <param name="StaleRecipients">自分の一覧で外された端末が宛先にいる（古い一覧で書かれた）。開いてよいが、書き手に書き直しを促す。</param>
    public sealed record Ok(DocHeader Header, byte[] Plaintext, bool StaleRecipients) : DocOpen;

    /// <summary>書き手の一覧の頭が自分より新しい。鎖を取り直してから開く。</summary>
    public sealed record NeedChain(long HeadSeq) : DocOpen;

    /// <summary>書き手の一覧の頭が、同じ seq で自分の鎖と違う（サーバーが分岐させた）。取り込まず、止まる根拠にもしない。</summary>
    public sealed record Fork : DocOpen;

    public sealed record Rejected(string Reason) : DocOpen;
}

/// <summary>
/// SealedDoc（D-1）: 本文を書き込みごとの内容鍵（AES-256-GCM）で暗号化し、内容鍵を家の端末ごとに HPKE で包み、全体に書き手が署名する（Android 版と同じ）。
/// </summary>
public static class DocSealer
{
    public const string DocLabel = "wave-v2-doc";
    private const string WrapInfoLabel = "wave-v2-wrap";
    private const int PadBlock = 4096;
    public const int MaxPlaintext = 1024 * 1024;

    /// <summary>宛先は、書き手が検証した一覧の今の全端末。</summary>
    public static SealedDoc Seal(IDeviceSigner signer, VerifiedChain chain, DocKind kind, long version, string? prevHash, long issuedAtEpochMs, byte[] plaintext)
    {
        if (plaintext.Length > MaxPlaintext) throw new ArgumentException("plaintext too large", nameof(plaintext));
        if (!chain.Members.ContainsKey(signer.DeviceId)) throw new ArgumentException("writer is not a member", nameof(signer));
        var header = new DocHeader { Kind = kind, HouseholdId = chain.HouseholdId, WriterId = signer.DeviceId, Version = version, PrevHash = prevHash, HeadSeq = chain.HeadSeq, HeadHash = chain.HeadHash, IssuedAtEpochMs = issuedAtEpochMs };
        var headerHash = WaveCrypto.Sha256(CanonicalHeader(header));
        var contentKey = WaveCrypto.RandomBytes(32);
        var (nonce, ciphertext) = WaveCrypto.Seal(contentKey, headerHash, Pad(plaintext));
        var recipients = chain.Members.Values
            .OrderBy(m => m.Keys.DeviceId, StringComparer.Ordinal)
            .Select(member =>
            {
                var sealedKey = Hpke.Seal(member.Kx, WrapInfo(kind, chain.HouseholdId), WrapAad(headerHash, member.Keys.DeviceId), contentKey);
                return new DocRecipient { DeviceId = member.Keys.DeviceId, Enc = B64.Encode(sealedKey.Enc), WrappedKey = B64.Encode(sealedKey.Ciphertext) };
            })
            .ToList();
        var unsigned = new SealedDoc { Header = header, Nonce = B64.Encode(nonce), Ciphertext = B64.Encode(ciphertext), Recipients = recipients, Signature = "" };
        return unsigned with { Signature = B64.Encode(signer.Sign(DocLabel, SignedBytes(unsigned))) };
    }

    /// <summary>書き手・役割・署名・一覧の頭を確かめてから開く。</summary>
    public static DocOpen Open(SealedDoc doc, VerifiedChain chain, IKxOpener me)
    {
        var header = doc.Header;
        if (header.V != DocHeader.FormatVersion) return new DocOpen.Rejected("version");
        if (header.HouseholdId != chain.HouseholdId) return new DocOpen.Rejected("household");
        if (header.HeadSeq > chain.HeadSeq) return new DocOpen.NeedChain(header.HeadSeq);
        if (chain.HashAt(header.HeadSeq) != header.HeadHash) return new DocOpen.Fork();
        if (!chain.Members.TryGetValue(header.WriterId, out var writer)) return new DocOpen.Rejected("writer_not_member");
        if (!RoleAllowed(header.Kind, writer.Keys.Role)) return new DocOpen.Rejected("role");
        if (doc.Recipients.Select(r => r.DeviceId).Distinct().Count() != doc.Recipients.Count) return new DocOpen.Rejected("duplicate_recipient");
        var signature = B64.Decode(doc.Signature);
        if (signature is null) return new DocOpen.Rejected("malformed");
        if (!WaveCrypto.Verify(writer.Identity, DocLabel, SignedBytes(doc), signature)) return new DocOpen.Rejected("signature");
        var mine = doc.Recipients.FirstOrDefault(r => r.DeviceId == me.DeviceId);
        if (mine is null) return new DocOpen.Rejected("not_for_me");
        var headerHash = WaveCrypto.Sha256(CanonicalHeader(header));
        var enc = B64.Decode(mine.Enc);
        if (enc is null) return new DocOpen.Rejected("malformed");
        var wrapped = B64.Decode(mine.WrappedKey);
        if (wrapped is null) return new DocOpen.Rejected("malformed");
        var contentKey = Hpke.Open(enc, me.KxPub, me.Dh, WrapInfo(header.Kind, header.HouseholdId), WrapAad(headerHash, me.DeviceId), wrapped);
        if (contentKey is null) return new DocOpen.Rejected("unwrap");
        var nonce = B64.Decode(doc.Nonce);
        if (nonce is null) return new DocOpen.Rejected("malformed");
        var ciphertext = B64.Decode(doc.Ciphertext);
        if (ciphertext is null) return new DocOpen.Rejected("malformed");
        var padded = WaveCrypto.Open(contentKey, nonce, headerHash, ciphertext);
        if (padded is null) return new DocOpen.Rejected("content");
        var plaintext = Unpad(padded);
        if (plaintext is null) return new DocOpen.Rejected("padding");
        var stale = doc.Recipients.Any(r => chain.Removed.Contains(r.DeviceId));
        return new DocOpen.Ok(header, plaintext, stale);
    }

    /// <summary>doc のハッシュ（prevHash・食い違いの検出・キューの参照）。署名は含めない。</summary>
    public static string Hash(SealedDoc doc) => B64.Encode(WaveCrypto.Sha256(SignedBytes(doc)));

    public static bool RoleAllowed(DocKind kind, Role role) =>
        kind switch
        {
            DocKind.Cursor or DocKind.Queue => role == Role.Player,
            _ => true,
        };

    private static byte[] WrapInfo(DocKind kind, string householdId) =>
        WaveCrypto.LengthPrefixed(WaveCrypto.Utf8(WrapInfoLabel), WaveCrypto.Utf8(kind.ToString()), WaveCrypto.Utf8(householdId));

    private static byte[] WrapAad(byte[] headerHash, string recipientId) => WaveCrypto.LengthPrefixed(headerHash, WaveCrypto.Utf8(recipientId));

    /// <summary>見出しの canonical バイト列。kind は Kotlin の enum の名前（"Cursor" など）。</summary>
    internal static byte[] CanonicalHeader(DocHeader header)
    {
        string[] parts =
        [
            header.V.ToString(CultureInfo.InvariantCulture),
            header.Kind.ToString(),
            header.HouseholdId,
            header.WriterId,
            header.Version.ToString(CultureInfo.InvariantCulture),
            header.PrevHash ?? "",
            header.HeadSeq.ToString(CultureInfo.InvariantCulture),
            header.HeadHash,
            header.IssuedAtEpochMs.ToString(CultureInfo.InvariantCulture),
        ];
        return WaveCrypto.LengthPrefixed(parts.Select(WaveCrypto.Utf8).ToArray());
    }

    /// <summary>署名の対象: 見出し・nonce・暗号文・宛先ごとの包み（宛先 ID の順）。</summary>
    private static byte[] SignedBytes(SealedDoc doc)
    {
        var parts = new List<byte[]> { CanonicalHeader(doc.Header), WaveCrypto.Utf8(doc.Nonce), WaveCrypto.Utf8(doc.Ciphertext) };
        foreach (var r in doc.Recipients.OrderBy(r => r.DeviceId, StringComparer.Ordinal))
        {
            parts.Add(WaveCrypto.Utf8(r.DeviceId));
            parts.Add(WaveCrypto.Utf8(r.Enc));
            parts.Add(WaveCrypto.Utf8(r.WrappedKey));
        }
        return WaveCrypto.LengthPrefixed(parts.ToArray());
    }

    /// <summary>長さ（4バイト）‖ 本文 ‖ 0 埋めで、4KB 単位にそろえる。</summary>
    private static byte[] Pad(byte[] plaintext)
    {
        var total = (plaintext.Length + 4 + PadBlock - 1) / PadBlock * PadBlock;
        var output = new byte[total];
        BinaryPrimitives.WriteInt32BigEndian(output, plaintext.Length);
        plaintext.CopyTo(output, 4);
        return output;
    }

    private static byte[]? Unpad(byte[] padded)
    {
        if (padded.Length < 4 || padded.Length % PadBlock != 0) return null;
        var size = BinaryPrimitives.ReadInt32BigEndian(padded);
        if (size < 0 || size > padded.Length - 4) return null;
        return padded[4..(4 + size)];
    }
}
