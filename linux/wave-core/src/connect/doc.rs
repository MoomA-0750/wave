//! SealedDoc（D-1）: 本文を書き込みごとの内容鍵（AES-256-GCM）で暗号化し、内容鍵を家の端末ごとに HPKE で包み、全体に書き手が署名する
//! （Android 版・Windows 版の DocSealer と同じ）。

use std::collections::HashSet;

use super::ConnectError;
use super::chain::{DeviceIdentity, DeviceSigner, VerifiedChain};
use super::crypto::{self, EcPublicKey, b64};
use super::hpke;
use super::models::{DocHeader, DocKind, DocRecipient, Role, SealedDoc};

pub const DOC_LABEL: &str = "wave-v2-doc";
const WRAP_INFO_LABEL: &str = "wave-v2-wrap";
const PAD_BLOCK: usize = 4096;
pub const MAX_PLAINTEXT: usize = 1024 * 1024;

/// この端末の受け取りの鍵（kx）で ECDH する。
pub trait KxOpener: DeviceIdentity {
    fn kx_pub(&self) -> &EcPublicKey;

    /// ECDH の共有秘密（x 座標、32バイト）。できなければ None。
    fn dh(&self, peer: &EcPublicKey) -> Option<Vec<u8>>;
}

#[derive(Clone, PartialEq, Eq)]
pub enum DocOpen {
    /// stale_recipients: 自分の一覧で外された端末が宛先にいる（古い一覧で書かれた）。開いてよいが、書き手に書き直しを促す。
    Ok { header: DocHeader, plaintext: Vec<u8>, stale_recipients: bool },
    /// 書き手の一覧の頭が自分より新しい。鎖を取り直してから開く。
    NeedChain { head_seq: i64 },
    /// 書き手の一覧の頭が、同じ seq で自分の鎖と違う（サーバーが分岐させた）。取り込まず、止まる根拠にもしない。
    Fork,
    Rejected(&'static str),
}

/// 平文（ログインを渡す命令などを含む）は長さだけ出す。
impl std::fmt::Debug for DocOpen {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DocOpen::Ok { header, plaintext, stale_recipients } => f
                .debug_struct("Ok")
                .field("header", header)
                .field("plaintext_len", &plaintext.len())
                .field("stale_recipients", stale_recipients)
                .finish(),
            DocOpen::NeedChain { head_seq } => f.debug_struct("NeedChain").field("head_seq", head_seq).finish(),
            DocOpen::Fork => f.write_str("Fork"),
            DocOpen::Rejected(reason) => f.debug_tuple("Rejected").field(reason).finish(),
        }
    }
}

/// 宛先は、書き手が検証した一覧の今の全端末。
/// `only`: 包む宛先を家の一部の端末に絞る（ログインを渡す命令、ADR-0015）。None なら家の全端末。
#[allow(clippy::too_many_arguments)]
pub fn seal(
    signer: &dyn DeviceSigner,
    chain: &VerifiedChain,
    kind: DocKind,
    version: i64,
    prev_hash: Option<&str>,
    issued_at_epoch_ms: i64,
    plaintext: &[u8],
    only: Option<&HashSet<String>>,
) -> Result<SealedDoc, ConnectError> {
    if plaintext.len() > MAX_PLAINTEXT {
        return Err(ConnectError::InvalidArgument("plaintext too large"));
    }
    if !chain.members().contains_key(signer.device_id()) {
        return Err(ConnectError::InvalidArgument("writer is not a member"));
    }
    if only.is_some_and(|only| only.is_empty() || !only.iter().all(|id| chain.members().contains_key(id))) {
        return Err(ConnectError::InvalidArgument("recipient is not a member"));
    }
    let header = DocHeader {
        v: DocHeader::FORMAT_VERSION,
        kind,
        household_id: chain.household_id().to_string(),
        writer_id: signer.device_id().to_string(),
        version,
        prev_hash: prev_hash.map(String::from),
        head_seq: chain.head_seq(),
        head_hash: chain.head_hash().to_string(),
        issued_at_epoch_ms,
    };
    let header_hash = crypto::sha256(&[&canonical_header(&header)]);
    let content_key = crypto::random_bytes(32);
    let (nonce, ciphertext) = crypto::seal(&content_key, &header_hash, &pad(plaintext));
    let mut members: Vec<_> = chain.members().values().filter(|m| only.is_none_or(|only| only.contains(&m.keys.device_id))).collect();
    members.sort_by(|a, b| a.keys.device_id.cmp(&b.keys.device_id));
    let recipients = members
        .into_iter()
        .map(|member| {
            let sealed = hpke::seal(&member.kx, &wrap_info(kind, chain.household_id()), &wrap_aad(&header_hash, &member.keys.device_id), &content_key);
            DocRecipient { device_id: member.keys.device_id.clone(), enc: b64::encode(&sealed.enc), wrapped_key: b64::encode(&sealed.ciphertext) }
        })
        .collect();
    let mut doc = SealedDoc { header, nonce: b64::encode(&nonce), ciphertext: b64::encode(&ciphertext), recipients, signature: String::new() };
    doc.signature = b64::encode(&signer.sign(DOC_LABEL, &signed_bytes(&doc)));
    Ok(doc)
}

/// 書き手・役割・署名・一覧の頭を確かめてから開く。
pub fn open(doc: &SealedDoc, chain: &VerifiedChain, me: &dyn KxOpener) -> DocOpen {
    let header = &doc.header;
    if header.v != DocHeader::FORMAT_VERSION {
        return DocOpen::Rejected("version");
    }
    if header.household_id != chain.household_id() {
        return DocOpen::Rejected("household");
    }
    if header.head_seq > chain.head_seq() {
        return DocOpen::NeedChain { head_seq: header.head_seq };
    }
    if chain.hash_at(header.head_seq) != Some(header.head_hash.as_str()) {
        return DocOpen::Fork;
    }
    let Some(writer) = chain.members().get(&header.writer_id) else {
        return DocOpen::Rejected("writer_not_member");
    };
    if !role_allowed(header.kind, writer.keys.role) {
        return DocOpen::Rejected("role");
    }
    let distinct: HashSet<&str> = doc.recipients.iter().map(|r| r.device_id.as_str()).collect();
    if distinct.len() != doc.recipients.len() {
        return DocOpen::Rejected("duplicate_recipient");
    }
    let Some(signature) = b64::decode(&doc.signature) else {
        return DocOpen::Rejected("malformed");
    };
    if !crypto::verify(&writer.identity, DOC_LABEL, &signed_bytes(doc), &signature) {
        return DocOpen::Rejected("signature");
    }
    let Some(mine) = doc.recipients.iter().find(|r| r.device_id == me.device_id()) else {
        return DocOpen::Rejected("not_for_me");
    };
    let header_hash = crypto::sha256(&[&canonical_header(header)]);
    let (Some(enc), Some(wrapped)) = (b64::decode(&mine.enc), b64::decode(&mine.wrapped_key)) else {
        return DocOpen::Rejected("malformed");
    };
    let content_key = hpke::open(
        &enc,
        me.kx_pub(),
        |peer| me.dh(peer),
        &wrap_info(header.kind, &header.household_id),
        &wrap_aad(&header_hash, me.device_id()),
        &wrapped,
    );
    let Some(content_key) = content_key else {
        return DocOpen::Rejected("unwrap");
    };
    let (Some(nonce), Some(ciphertext)) = (b64::decode(&doc.nonce), b64::decode(&doc.ciphertext)) else {
        return DocOpen::Rejected("malformed");
    };
    let Some(padded) = crypto::open(&content_key, &nonce, &header_hash, &ciphertext) else {
        return DocOpen::Rejected("content");
    };
    let Some(plaintext) = unpad(&padded) else {
        return DocOpen::Rejected("padding");
    };
    let stale_recipients = doc.recipients.iter().any(|r| chain.removed().contains(&r.device_id));
    DocOpen::Ok { header: header.clone(), plaintext, stale_recipients }
}

/// doc のハッシュ（prevHash・食い違いの検出・キューの参照）。署名は含めない。
pub fn hash(doc: &SealedDoc) -> String {
    b64::encode(&crypto::sha256(&[&signed_bytes(doc)]))
}

pub fn role_allowed(kind: DocKind, role: Role) -> bool {
    match kind {
        DocKind::Cursor | DocKind::Queue => role == Role::Player,
        _ => true,
    }
}

fn wrap_info(kind: DocKind, household_id: &str) -> Vec<u8> {
    crypto::length_prefixed(&[WRAP_INFO_LABEL.as_bytes(), kind.name().as_bytes(), household_id.as_bytes()])
}

fn wrap_aad(header_hash: &[u8], recipient_id: &str) -> Vec<u8> {
    crypto::length_prefixed(&[header_hash, recipient_id.as_bytes()])
}

/// 見出しの canonical バイト列。kind は Kotlin の enum の名前（"Cursor" など）。
pub(crate) fn canonical_header(header: &DocHeader) -> Vec<u8> {
    let parts: [String; 9] = [
        header.v.to_string(),
        header.kind.name().to_string(),
        header.household_id.clone(),
        header.writer_id.clone(),
        header.version.to_string(),
        header.prev_hash.clone().unwrap_or_default(),
        header.head_seq.to_string(),
        header.head_hash.clone(),
        header.issued_at_epoch_ms.to_string(),
    ];
    let slices: Vec<&[u8]> = parts.iter().map(|p| p.as_bytes()).collect();
    crypto::length_prefixed(&slices)
}

/// 署名の対象: 見出し・nonce・暗号文・宛先ごとの包み（宛先 ID の順）。
fn signed_bytes(doc: &SealedDoc) -> Vec<u8> {
    let header = canonical_header(&doc.header);
    let mut parts: Vec<&[u8]> = vec![&header, doc.nonce.as_bytes(), doc.ciphertext.as_bytes()];
    let mut recipients: Vec<&DocRecipient> = doc.recipients.iter().collect();
    recipients.sort_by(|a, b| a.device_id.cmp(&b.device_id));
    for r in recipients {
        parts.push(r.device_id.as_bytes());
        parts.push(r.enc.as_bytes());
        parts.push(r.wrapped_key.as_bytes());
    }
    crypto::length_prefixed(&parts)
}

/// 長さ（4バイト）‖ 本文 ‖ 0 埋めで、4KB 単位にそろえる。
fn pad(plaintext: &[u8]) -> Vec<u8> {
    let total = (plaintext.len() + 4).div_ceil(PAD_BLOCK) * PAD_BLOCK;
    let mut output = vec![0u8; total];
    output[..4].copy_from_slice(&(plaintext.len() as u32).to_be_bytes());
    output[4..4 + plaintext.len()].copy_from_slice(plaintext);
    output
}

fn unpad(padded: &[u8]) -> Option<Vec<u8>> {
    if padded.len() < 4 || !padded.len().is_multiple_of(PAD_BLOCK) {
        return None;
    }
    let size = i32::from_be_bytes(padded[..4].try_into().ok()?);
    let size = usize::try_from(size).ok()?;
    if size > padded.len() - 4 {
        return None;
    }
    Some(padded[4..4 + size].to_vec())
}

#[cfg(test)]
mod tests {
    use super::super::chain::{self, ChainVerification};
    use super::super::models::ChainRecord;
    use super::super::test_support::{TestDevice, verified};
    use super::*;

    fn header() -> DocHeader {
        DocHeader {
            v: 1,
            kind: DocKind::Cursor,
            household_id: "house".into(),
            writer_id: "dev-a".into(),
            version: 7,
            prev_hash: Some("p".into()),
            head_seq: 3,
            head_hash: "h".into(),
            issued_at_epoch_ms: 1_700_000_000_000,
        }
    }

    /// 署名とハッシュの対象のバイト列を、Kotlin の CanonicalBytesTest と同じ既知の値で固定する。
    #[test]
    fn sealed_doc_hashes_match_kotlin() {
        let doc = SealedDoc {
            header: header(),
            nonce: "nonce".into(),
            ciphertext: "cipher".into(),
            recipients: vec![
                DocRecipient { device_id: "dev-a".into(), enc: "enc-a".into(), wrapped_key: "wrap-a".into() },
                DocRecipient { device_id: "dev-b".into(), enc: "enc-b".into(), wrapped_key: "wrap-b".into() },
            ],
            signature: "sig".into(),
        };
        assert_eq!("OIhuUfwQMOoU568qY4i4GiaVIZqPkDqmg_gOeXLnaFI", hash(&doc));
        let queue = SealedDoc { header: DocHeader { kind: DocKind::Queue, prev_hash: None, ..header() }, ..doc.clone() };
        assert_eq!("6ajZYk-MILk4axIoVv59uvEvaxL1XWV1ijvh2K9c6jQ", hash(&queue));
        assert_eq!(hash(&doc), hash(&SealedDoc { signature: "other".into(), ..doc.clone() }));
    }

    struct Fixture {
        a: TestDevice,
        b: TestDevice,
        pc: TestDevice,
        records: Vec<ChainRecord>,
        with_pc: Vec<ChainRecord>,
        chain: VerifiedChain,
    }

    const BODY: &[u8] = b"cursor-body";

    impl Fixture {
        fn new() -> Self {
            let a = TestDevice::new(Role::Player);
            let b = TestDevice::new(Role::Player);
            let pc = TestDevice::new(Role::Controller);
            let genesis = chain::genesis(&a, &a.keys).unwrap();
            let add_b = chain::add(&a, &verified(std::slice::from_ref(&genesis)), &b.keys);
            let records = vec![genesis, add_b];
            let mut with_pc = records.clone();
            with_pc.push(chain::add(&a, &verified(&records), &pc.keys));
            let chain = verified(&with_pc);
            Self { a, b, pc, records, with_pc, chain }
        }

        fn seal(&self, signer: &dyn DeviceSigner, kind: DocKind, on_chain: &VerifiedChain) -> SealedDoc {
            seal(signer, on_chain, kind, 7, None, 1, BODY, None).unwrap()
        }

        fn seal_a(&self) -> SealedDoc {
            self.seal(&self.a, DocKind::Cursor, &self.chain)
        }
    }

    fn plus(records: &[ChainRecord], record: ChainRecord) -> Vec<ChainRecord> {
        let mut all = records.to_vec();
        all.push(record);
        all
    }

    #[test]
    fn every_member_opens_and_others_cannot() {
        let f = Fixture::new();
        let doc = f.seal_a();
        for device in [&f.a, &f.b, &f.pc] {
            let DocOpen::Ok { plaintext, stale_recipients, .. } = open(&doc, &f.chain, device) else { panic!("ok") };
            assert_eq!(BODY, plaintext.as_slice());
            assert!(!stale_recipients);
        }
        assert_eq!(DocOpen::Rejected("not_for_me"), open(&doc, &f.chain, &TestDevice::new(Role::Player)));
        assert_eq!(0, (b64::decode(&doc.ciphertext).unwrap().len() - 16) % 4096);
    }

    #[test]
    fn rejects_tampering_substitution_and_wrong_roles() {
        let f = Fixture::new();
        let doc = f.seal_a();
        let mut changed = doc.clone();
        changed.header.version = 8;
        assert_eq!(DocOpen::Rejected("signature"), open(&changed, &f.chain, &f.b));
        assert_eq!(DocOpen::Rejected("signature"), open(&SealedDoc { ciphertext: f.seal_a().ciphertext, ..doc.clone() }, &f.chain, &f.b));
        let fewer: Vec<_> = doc.recipients.iter().filter(|r| r.device_id != f.pc.device_id()).cloned().collect();
        assert_eq!(DocOpen::Rejected("signature"), open(&SealedDoc { recipients: fewer, ..doc.clone() }, &f.chain, &f.b));
        let other = f.seal_a();
        let swapped: Vec<_> = doc
            .recipients
            .iter()
            .map(|r| if r.device_id == f.b.device_id() { other.recipients.iter().find(|o| o.device_id == f.b.device_id()).unwrap().clone() } else { r.clone() })
            .collect();
        assert_eq!(DocOpen::Rejected("signature"), open(&SealedDoc { recipients: swapped, ..doc.clone() }, &f.chain, &f.b));
        assert!(seal(&TestDevice::new(Role::Player), &f.chain, DocKind::Cursor, 7, None, 1, BODY, None).is_err());
        let b_signature = f.seal(&f.b, DocKind::Cursor, &f.chain).signature;
        assert_eq!(DocOpen::Rejected("signature"), open(&SealedDoc { signature: b_signature, ..doc.clone() }, &f.chain, &f.b));
        assert_eq!(DocOpen::Rejected("role"), open(&f.seal(&f.pc, DocKind::Cursor, &f.chain), &f.chain, &f.a));
        assert!(matches!(open(&f.seal(&f.pc, DocKind::Command, &f.chain), &f.chain, &f.a), DocOpen::Ok { .. }));
        let other_household = verified(&[chain::genesis(&f.b, &f.b.keys).unwrap()]);
        assert_eq!(DocOpen::Rejected("household"), open(&doc, &other_household, &f.b));
        // 宛先の重なり
        let mut duplicated = doc.clone();
        duplicated.recipients.push(doc.recipients[0].clone());
        assert_eq!(DocOpen::Rejected("duplicate_recipient"), open(&duplicated, &f.chain, &f.b));
    }

    #[test]
    fn head_of_the_writers_list_is_checked() {
        let f = Fixture::new();
        let doc = f.seal_a();
        assert_eq!(DocOpen::NeedChain { head_seq: f.chain.head_seq() }, open(&doc, &verified(&f.records), &f.b));
        let branch = verified(&plus(&f.records, chain::add(&f.b, &verified(&f.records), &TestDevice::new(Role::Player).keys)));
        assert_eq!(DocOpen::Fork, open(&doc, &branch, &f.b));
        let after_remove = verified(&plus(&f.with_pc, chain::remove(&f.b, &f.chain, f.pc.device_id())));
        let DocOpen::Ok { stale_recipients, .. } = open(&doc, &after_remove, &f.b) else { panic!("ok") };
        assert!(stale_recipients);
        let removed_writer_chain = verified(&plus(&f.with_pc, chain::remove(&f.a, &f.chain, f.b.device_id())));
        assert_eq!(DocOpen::Rejected("writer_not_member"), open(&f.seal(&f.b, DocKind::Cursor, &f.chain), &removed_writer_chain, &f.a));
        assert!(!f.seal(&f.a, DocKind::Cursor, &after_remove).recipients.iter().any(|r| r.device_id == f.pc.device_id()));
        assert!(matches!(chain::verify(&f.with_pc, None), ChainVerification::Ok(_)));
    }

    #[test]
    fn only_limits_the_recipients() {
        let f = Fixture::new();
        let only = HashSet::from([f.b.device_id().to_string()]);
        let doc = seal(&f.a, &f.chain, DocKind::Command, 1, None, 1, BODY, Some(&only)).unwrap();
        assert_eq!(vec![f.b.device_id()], doc.recipients.iter().map(|r| r.device_id.as_str()).collect::<Vec<_>>());
        assert!(matches!(open(&doc, &f.chain, &f.b), DocOpen::Ok { .. }));
        assert_eq!(DocOpen::Rejected("not_for_me"), open(&doc, &f.chain, &f.a));
        assert!(seal(&f.a, &f.chain, DocKind::Command, 1, None, 1, BODY, Some(&HashSet::new())).is_err());
        let outsider = HashSet::from([TestDevice::new(Role::Player).device_id().to_string()]);
        assert!(seal(&f.a, &f.chain, DocKind::Command, 1, None, 1, BODY, Some(&outsider)).is_err());
    }

    #[test]
    fn debug_does_not_show_the_plaintext() {
        let opened = DocOpen::Ok { header: header(), plaintext: b"SAPISID=secret".to_vec(), stale_recipients: false };
        assert!(!format!("{opened:?}").contains("secret"));
    }
}
