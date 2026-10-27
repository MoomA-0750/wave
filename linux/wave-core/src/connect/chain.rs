//! 端末一覧の鎖（D-2）。記録の作成と、genesis からの検証（Android 版・Windows 版の MemberChain と同じ）。

use std::collections::HashSet;

use indexmap::IndexMap;

use super::ConnectError;
use super::crypto::{self, EcPublicKey, b64};
use super::hpke;
use super::models::{ChainRecord, ChainType, MemberKeys, Role};

pub const CHAIN_LABEL: &str = "wave-v2-chain";
pub const KX_LABEL: &str = "wave-v2-kx";
const MAX_RECORDS: usize = 1024;

/// 端末 ID を持つもの（DeviceSigner と KxOpener の共通の親。両方を持つ型で名前がぶつからないように分けてある）。
pub trait DeviceIdentity {
    fn device_id(&self) -> &str;
}

/// 端末の身元鍵での署名（Android では Keystore の鍵。Linux では保護して保存したソフトウェアの鍵など）。
pub trait DeviceSigner: DeviceIdentity {
    /// 身元の署名公開鍵（SPKI）。
    fn identity_pub(&self) -> &[u8];

    /// 用途ラベルを前置した署名（crypto::sign と同じ形、DER）。
    fn sign(&self, label: &str, message: &[u8]) -> Vec<u8>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChainMember {
    pub keys: MemberKeys,
    pub identity: EcPublicKey,
    pub kx: EcPublicKey,
    pub added_seq: i64,
}

/// genesis から検証した端末一覧。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifiedChain {
    household_id: String,
    head_seq: i64,
    head_hash: String,
    members: IndexMap<String, ChainMember>,
    removed: HashSet<String>,
    hashes: Vec<String>,
}

impl VerifiedChain {
    pub fn household_id(&self) -> &str {
        &self.household_id
    }

    pub fn head_seq(&self) -> i64 {
        self.head_seq
    }

    pub fn head_hash(&self) -> &str {
        &self.head_hash
    }

    /// 加えた順（Kotlin の LinkedHashMap と同じ順）。
    pub fn members(&self) -> &IndexMap<String, ChainMember> {
        &self.members
    }

    pub fn removed(&self) -> &HashSet<String> {
        &self.removed
    }

    pub fn hash_at(&self, seq: i64) -> Option<&str> {
        usize::try_from(seq).ok().and_then(|i| self.hashes.get(i)).map(String::as_str)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ChainVerification {
    Ok(VerifiedChain),
    Invalid { seq: i64, reason: &'static str },
    /// 自分が検証した頭より短い（サーバーが巻き戻した）。
    Rollback,
    /// 自分が検証した頭と同じ seq で、ハッシュが違う（サーバーが分岐を見せた）。
    Fork,
}

/// この端末が前に検証した一覧の頭。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Head {
    pub seq: i64,
    pub hash: String,
}

/// この端末が家に加わるときの鍵の記述（kx を身元鍵で自己署名する）。
pub fn member_keys(signer: &dyn DeviceSigner, kx_pub: &EcPublicKey, role: Role) -> MemberKeys {
    let kx = hpke::serialize_public_key(kx_pub);
    let signature = signer.sign(KX_LABEL, &kx_message(signer.device_id(), &kx, role));
    MemberKeys {
        device_id: signer.device_id().to_string(),
        identity_pub: b64::encode(signer.identity_pub()),
        kx_pub: b64::encode(&kx),
        role,
        kx_signature: b64::encode(&signature),
    }
}

pub fn genesis(signer: &dyn DeviceSigner, own: &MemberKeys) -> Result<ChainRecord, ConnectError> {
    if own.device_id != signer.device_id() || own.role != Role::Player {
        return Err(ConnectError::InvalidArgument("genesis は自分の player の鍵で作る"));
    }
    Ok(signed(
        signer,
        ChainRecord {
            v: ChainRecord::FORMAT_VERSION,
            household_id: None,
            seq: 0,
            prev_hash: None,
            kind: ChainType::Genesis,
            target: Some(own.clone()),
            removed_device_id: None,
            signer_id: signer.device_id().to_string(),
            signature: String::new(),
        },
    ))
}

pub fn add(signer: &dyn DeviceSigner, chain: &VerifiedChain, target: &MemberKeys) -> ChainRecord {
    signed(
        signer,
        ChainRecord {
            v: ChainRecord::FORMAT_VERSION,
            household_id: Some(chain.household_id.clone()),
            seq: chain.head_seq + 1,
            prev_hash: Some(chain.head_hash.clone()),
            kind: ChainType::Add,
            target: Some(target.clone()),
            removed_device_id: None,
            signer_id: signer.device_id().to_string(),
            signature: String::new(),
        },
    )
}

pub fn remove(signer: &dyn DeviceSigner, chain: &VerifiedChain, device_id: &str) -> ChainRecord {
    signed(
        signer,
        ChainRecord {
            v: ChainRecord::FORMAT_VERSION,
            household_id: Some(chain.household_id.clone()),
            seq: chain.head_seq + 1,
            prev_hash: Some(chain.head_hash.clone()),
            kind: ChainType::Remove,
            target: None,
            removed_device_id: Some(device_id.to_string()),
            signer_id: signer.device_id().to_string(),
            signature: String::new(),
        },
    )
}

/// 記録のハッシュ（householdId・prevHash・頭）。署名は含めない。
pub fn hash(record: &ChainRecord) -> String {
    b64::encode(&crypto::sha256(&[&canonical(record)]))
}

/// genesis から検証する。trusted（この端末が前に検証した頭）があれば、それより短い鎖と、その seq でハッシュが違う鎖を拒む。
pub fn verify(records: &[ChainRecord], trusted: Option<&Head>) -> ChainVerification {
    if records.is_empty() || records.len() > MAX_RECORDS {
        return ChainVerification::Invalid { seq: 0, reason: "size" };
    }
    let mut members: IndexMap<String, ChainMember> = IndexMap::new();
    let mut removed: HashSet<String> = HashSet::new();
    let mut hashes: Vec<String> = Vec::new();
    let mut household_id = String::new();
    for (index, record) in records.iter().enumerate() {
        let seq = index as i64;
        let invalid = |reason: &'static str| ChainVerification::Invalid { seq, reason };
        if record.v != ChainRecord::FORMAT_VERSION || record.seq != seq {
            return invalid("seq");
        }
        if index == 0 {
            if record.kind != ChainType::Genesis || record.household_id.is_some() || record.prev_hash.is_some() {
                return invalid("genesis");
            }
            let Some(target) = &record.target else {
                return invalid("genesis_target");
            };
            if target.role != Role::Player || record.signer_id != target.device_id || record.removed_device_id.is_some() {
                return invalid("genesis_signer");
            }
            let Some(member) = member_of(target, seq) else {
                return invalid("genesis_keys");
            };
            if !verify_signature(record, &member.identity) {
                return invalid("signature");
            }
            members.insert(target.device_id.clone(), member);
            household_id = hash(record);
        } else {
            if record.household_id.as_deref() != Some(household_id.as_str()) || record.prev_hash.as_ref() != hashes.last() {
                return invalid("link");
            }
            let Some(signer) = members.get(&record.signer_id) else {
                return invalid("signer_not_member");
            };
            if !verify_signature(record, &signer.identity) {
                return invalid("signature");
            }
            let signer_role = signer.keys.role;
            let signer_id = signer.keys.device_id.clone();
            match record.kind {
                ChainType::Genesis => return invalid("second_genesis"),
                ChainType::Add => {
                    let Some(target) = &record.target else {
                        return invalid("add_target");
                    };
                    if record.removed_device_id.is_some() {
                        return invalid("add_shape");
                    }
                    // player を加えられるのは player だけ（L-11）。controller も当面は player だけが加える
                    if signer_role != Role::Player {
                        return invalid("add_by_controller");
                    }
                    if members.contains_key(&target.device_id) || removed.contains(&target.device_id) {
                        return invalid("add_existing");
                    }
                    let Some(member) = member_of(target, seq) else {
                        return invalid("add_keys");
                    };
                    members.insert(target.device_id.clone(), member);
                }
                ChainType::Remove => {
                    let Some(target_id) = &record.removed_device_id else {
                        return invalid("remove_target");
                    };
                    if record.target.is_some() {
                        return invalid("remove_shape");
                    }
                    let Some(target) = members.get(target_id) else {
                        return invalid("remove_not_member");
                    };
                    // controller は自分だけを外せる
                    if signer_role != Role::Player && *target_id != signer_id {
                        return invalid("remove_by_controller");
                    }
                    // player がいなくなる remove は拒む
                    if target.keys.role == Role::Player && members.values().filter(|m| m.keys.role == Role::Player).count() <= 1 {
                        return invalid("last_player");
                    }
                    members.shift_remove(target_id);
                    removed.insert(target_id.clone());
                }
            }
        }
        hashes.push(hash(record));
    }
    if let Some(trusted) = trusted {
        if (records.len() as i64) <= trusted.seq {
            return ChainVerification::Rollback;
        }
        if usize::try_from(trusted.seq).ok().and_then(|i| hashes.get(i)) != Some(&trusted.hash) {
            return ChainVerification::Fork;
        }
    }
    let head_hash = hashes.last().cloned().unwrap_or_default();
    ChainVerification::Ok(VerifiedChain { household_id, head_seq: records.len() as i64 - 1, head_hash, members, removed, hashes })
}

fn member_of(keys: &MemberKeys, seq: i64) -> Option<ChainMember> {
    let identity_bytes = b64::decode(&keys.identity_pub)?;
    let identity = crypto::decode_public_key(&identity_bytes)?;
    if crypto::device_id(&identity_bytes) != keys.device_id {
        return None;
    }
    let kx_bytes = b64::decode(&keys.kx_pub)?;
    let kx = hpke::deserialize_public_key(&kx_bytes)?;
    let kx_signature = b64::decode(&keys.kx_signature)?;
    if !crypto::verify(&identity, KX_LABEL, &kx_message(&keys.device_id, &kx_bytes, keys.role), &kx_signature) {
        return None;
    }
    Some(ChainMember { keys: keys.clone(), identity, kx, added_seq: seq })
}

fn signed(signer: &dyn DeviceSigner, mut record: ChainRecord) -> ChainRecord {
    record.signature = b64::encode(&signer.sign(CHAIN_LABEL, &canonical(&record)));
    record
}

fn verify_signature(record: &ChainRecord, identity: &EcPublicKey) -> bool {
    b64::decode(&record.signature).is_some_and(|signature| crypto::verify(identity, CHAIN_LABEL, &canonical(record), &signature))
}

fn kx_message(device_id: &str, kx: &[u8], role: Role) -> Vec<u8> {
    crypto::length_prefixed(&[device_id.as_bytes(), kx, role.name().as_bytes()])
}

/// 署名とハッシュの対象（署名そのものを除く全部を長さ付きで連結）。enum は Kotlin の enum の名前（"Genesis"・"Player"）。
pub(crate) fn canonical(record: &ChainRecord) -> Vec<u8> {
    let target = record.target.as_ref();
    let parts: [String; 12] = [
        record.v.to_string(),
        record.household_id.clone().unwrap_or_default(),
        record.seq.to_string(),
        record.prev_hash.clone().unwrap_or_default(),
        record.kind.name().to_string(),
        target.map(|t| t.device_id.clone()).unwrap_or_default(),
        target.map(|t| t.identity_pub.clone()).unwrap_or_default(),
        target.map(|t| t.kx_pub.clone()).unwrap_or_default(),
        target.map(|t| t.role.name().to_string()).unwrap_or_default(),
        target.map(|t| t.kx_signature.clone()).unwrap_or_default(),
        record.removed_device_id.clone().unwrap_or_default(),
        record.signer_id.clone(),
    ];
    let slices: Vec<&[u8]> = parts.iter().map(|p| p.as_bytes()).collect();
    crypto::length_prefixed(&slices)
}

#[cfg(test)]
mod tests {
    use super::super::test_support::{TestDevice, verified};
    use super::*;

    fn keys(id: &str, public: &str, kx: &str, sig: &str) -> MemberKeys {
        MemberKeys { device_id: id.into(), identity_pub: public.into(), kx_pub: kx.into(), role: Role::Player, kx_signature: sig.into() }
    }

    fn record(household: Option<&str>, seq: i64, prev: Option<&str>, kind: ChainType, target: Option<MemberKeys>, removed: Option<&str>) -> ChainRecord {
        ChainRecord {
            v: 1,
            household_id: household.map(String::from),
            seq,
            prev_hash: prev.map(String::from),
            kind,
            target,
            removed_device_id: removed.map(String::from),
            signer_id: "dev-a".into(),
            signature: "sig".into(),
        }
    }

    /// 署名とハッシュの対象のバイト列を、Kotlin の CanonicalBytesTest と同じ既知の値で固定する。
    #[test]
    fn chain_record_hashes_match_kotlin() {
        let genesis = record(None, 0, None, ChainType::Genesis, Some(keys("dev-a", "pub-a", "kx-a", "kxsig-a")), None);
        let add = record(Some("house"), 1, Some("prev"), ChainType::Add, Some(keys("dev-b", "pub-b", "kx-b", "kxsig-b")), None);
        let remove = record(Some("house"), 2, Some("prev2"), ChainType::Remove, None, Some("dev-b"));
        assert_eq!("07McxbsrW_iq4fxMp1hn5SQy6I5gpkAGc1OzMU1ZcZI", hash(&genesis));
        assert_eq!("Bc9cJdgfyeNXxPZ5HM4SisXRhTNBchPH6MbaOb57ATI", hash(&add));
        assert_eq!("A6e-7u5LQFx11vmK5Zj6n7QgeG01bctfiCnA9VnjvaU", hash(&remove));
        assert_eq!(hash(&add), hash(&ChainRecord { signature: "other".into(), ..add.clone() }));
    }

    struct Fixture {
        a: TestDevice,
        b: TestDevice,
        pc: TestDevice,
    }

    impl Fixture {
        fn new() -> Self {
            Self { a: TestDevice::new(Role::Player), b: TestDevice::new(Role::Player), pc: TestDevice::new(Role::Controller) }
        }

        fn genesis_and_b(&self) -> Vec<ChainRecord> {
            let genesis = super::genesis(&self.a, &self.a.keys).unwrap();
            let add_b = add(&self.a, &verified(std::slice::from_ref(&genesis)), &self.b.keys);
            vec![genesis, add_b]
        }
    }

    fn invalid_reason(records: &[ChainRecord]) -> &'static str {
        match verify(records, None) {
            ChainVerification::Invalid { reason, .. } => reason,
            other => panic!("Invalid ではない: {other:?}"),
        }
    }

    fn plus(records: &[ChainRecord], record: ChainRecord) -> Vec<ChainRecord> {
        let mut all = records.to_vec();
        all.push(record);
        all
    }

    #[test]
    fn genesis_add_and_remove_verify() {
        let f = Fixture::new();
        let records = f.genesis_and_b();
        let chain = verified(&records);
        assert_eq!(vec![f.a.device_id(), f.b.device_id()], chain.members().keys().map(String::as_str).collect::<Vec<_>>());
        assert_eq!(hash(&records[0]), chain.household_id());
        let with_pc = plus(&records, add(&f.b, &chain, &f.pc.keys));
        let removed = plus(&with_pc, remove(&f.a, &verified(&with_pc), f.b.device_id()));
        let final_chain = verified(&removed);
        assert_eq!(vec![f.a.device_id(), f.pc.device_id()], final_chain.members().keys().map(String::as_str).collect::<Vec<_>>());
        assert_eq!(&HashSet::from([f.b.device_id().to_string()]), final_chain.removed());
        assert_eq!(3, final_chain.head_seq());
    }

    #[test]
    fn genesis_must_be_own_player_keys() {
        let f = Fixture::new();
        assert!(super::genesis(&f.a, &f.b.keys).is_err());
        assert!(super::genesis(&f.pc, &f.pc.keys).is_err());
    }

    #[test]
    fn rejects_forged_or_unauthorized_records() {
        let f = Fixture::new();
        let records = f.genesis_and_b();
        let chain = verified(&records);
        let outsider = TestDevice::new(Role::Player);
        assert_eq!("signer_not_member", invalid_reason(&plus(&records, add(&outsider, &chain, &TestDevice::new(Role::Player).keys))));
        let forged = ChainRecord { signer_id: f.a.device_id().to_string(), ..add(&outsider, &chain, &TestDevice::new(Role::Player).keys) };
        assert_eq!("signature", invalid_reason(&plus(&records, forged)));
        let with_pc = plus(&records, add(&f.a, &chain, &f.pc.keys));
        assert_eq!("add_by_controller", invalid_reason(&plus(&with_pc, add(&f.pc, &verified(&with_pc), &TestDevice::new(Role::Player).keys))));
        assert_eq!("remove_by_controller", invalid_reason(&plus(&with_pc, remove(&f.pc, &verified(&with_pc), f.b.device_id()))));
        assert!(matches!(verify(&plus(&with_pc, remove(&f.pc, &verified(&with_pc), f.pc.device_id())), None), ChainVerification::Ok(_)));
        let only_a = plus(&records, remove(&f.a, &chain, f.b.device_id()));
        assert_eq!("last_player", invalid_reason(&plus(&only_a, remove(&f.a, &verified(&only_a), f.a.device_id()))));
        assert_eq!("signer_not_member", invalid_reason(&plus(&only_a, add(&f.b, &verified(&only_a), &TestDevice::new(Role::Player).keys))));
    }

    #[test]
    fn rejects_broken_links_and_keys() {
        let f = Fixture::new();
        let records = f.genesis_and_b();
        assert_eq!("link", invalid_reason(&[records[0].clone(), ChainRecord { prev_hash: Some("x".into()), ..records[1].clone() }]));
        assert_eq!("link", invalid_reason(&[super::genesis(&f.b, &f.b.keys).unwrap(), records[1].clone()]));
        let genesis_only = verified(&records[..1]);
        let other_kx = MemberKeys { kx_pub: TestDevice::new(Role::Player).keys.kx_pub, ..f.b.keys.clone() };
        assert_eq!("add_keys", invalid_reason(&[records[0].clone(), add(&f.a, &genesis_only, &other_kx)]));
        let other_id = MemberKeys { device_id: TestDevice::new(Role::Player).device_id().to_string(), ..f.b.keys.clone() };
        assert_eq!("add_keys", invalid_reason(&[records[0].clone(), add(&f.a, &genesis_only, &other_id)]));
        assert_eq!("add_existing", invalid_reason(&plus(&records, add(&f.a, &verified(&records), &f.b.keys))));
    }

    #[test]
    fn detects_rollback_and_fork_against_trusted_head() {
        let f = Fixture::new();
        let records = f.genesis_and_b();
        let chain = verified(&records);
        let head = Head { seq: chain.head_seq(), hash: chain.head_hash().to_string() };
        assert_eq!(ChainVerification::Rollback, verify(&records[..1], Some(&head)));
        let branch = vec![records[0].clone(), add(&f.a, &verified(&records[..1]), &TestDevice::new(Role::Player).keys)];
        assert_eq!(ChainVerification::Fork, verify(&branch, Some(&head)));
        assert!(matches!(verify(&plus(&records, add(&f.b, &chain, &f.pc.keys)), Some(&head)), ChainVerification::Ok(_)));
    }

    #[test]
    fn rejects_empty_and_second_genesis() {
        let f = Fixture::new();
        assert_eq!(ChainVerification::Invalid { seq: 0, reason: "size" }, verify(&[], None));
        let records = f.genesis_and_b();
        let chain = verified(&records);
        let second = signed(
            &f.a,
            ChainRecord { kind: ChainType::Genesis, signature: String::new(), ..add(&f.a, &chain, &TestDevice::new(Role::Player).keys) },
        );
        assert_eq!("second_genesis", invalid_reason(&plus(&records, second)));
    }
}
