//! Android（Kotlin）との相互運用。Kotlin の CSharpInteropTest が固定のテスト鍵で作った成果物（Windows 版のテストの Fixtures/connect/kotlin.json）を
//! この版で検証・開く。中継のメッセージ（RelayV2）の往復はここでは見ない（接続の層で見る）。

use std::collections::HashSet;
use std::sync::Arc;

use p256::SecretKey;
use p256::pkcs8::DecodePrivateKey;
use serde_json::Value;

use super::chain::{self, ChainVerification, DeviceIdentity, DeviceSigner};
use super::device::SoftwareDevice;
use super::doc::{self, DocOpen};
use super::hpke;
use super::models::*;
use super::pairing::{self, Failure, Initiator, Responder};
use super::snapshot::{self, HandoffDecodeResult, HandoffOrigin, HandoffRepeat, HandoffSource};
use super::test_support::verified;
use super::b64;

const HANDOFF_ID: &str = "0f8fad5b-d9cb-469f-a165-70867728950e";
const FIXTURE: &str = include_str!("../../../../windows/Wave.Core.Tests/Fixtures/connect/kotlin.json");

fn fixture() -> Value {
    serde_json::from_str(FIXTURE).unwrap()
}

fn s<'a>(node: &'a Value, key: &str) -> &'a str {
    node[key].as_str().unwrap_or_else(|| panic!("{key} が無い"))
}

fn device(fixture: &Value, name: &str) -> SoftwareDevice {
    let keys = &fixture["keys"][name];
    SoftwareDevice::from_pkcs8(&b64::decode(s(keys, "identity")).unwrap(), &b64::decode(s(keys, "kx")).unwrap()).unwrap()
}

fn decode<T: serde::de::DeserializeOwned>(node: &Value) -> T {
    serde_json::from_value(node.clone()).unwrap()
}

/// 項目の順を保つ JSON（serde_json::Value は順を並べ替えるので、Kotlin が書いた文字列を取り戻すのに使う）。
#[derive(serde::Serialize, serde::Deserialize)]
#[serde(untagged)]
enum Ordered {
    Null(()),
    Bool(bool),
    Int(i64),
    Float(f64),
    Text(String),
    List(Vec<Ordered>),
    Map(indexmap::IndexMap<String, Ordered>),
}

impl Ordered {
    fn get(&self, key: &str) -> &Ordered {
        let Ordered::Map(map) = self else { panic!("オブジェクトではない") };
        &map[key]
    }

    fn items(&self) -> &[Ordered] {
        let Ordered::List(list) = self else { panic!("配列ではない") };
        list
    }
}

fn ordered_fixture() -> Ordered {
    serde_json::from_str(FIXTURE).unwrap()
}

/// Kotlin の kotlinx が書いた文字列（fixture の中の JSON を、項目の順を保ったまま書き直したもの）。
fn text_of(node: &Ordered) -> String {
    serde_json::to_string(node).unwrap()
}

fn ephemeral(b64_text: &str) -> SecretKey {
    SecretKey::from_pkcs8_der(&b64::decode(b64_text).unwrap()).unwrap()
}

fn records(fixture: &Value) -> Vec<ChainRecord> {
    fixture["chain"].as_array().unwrap().iter().map(decode).collect()
}

/// Kotlin の CSharpInteropTest の sampleLoginGrant と同じもの（値は合成）。
fn sample_login_grant(from: &str, to: &str) -> LoginGrantCommand {
    LoginGrantCommand {
        command_id: "grant-0000000000000001".into(),
        from: from.into(),
        to: to.into(),
        cookie: "SAPISID=synthetic-sapisid; HSID=synthetic".into(),
        visitor_data: Some("synthetic-visitor".into()),
        data_sync_id: Some("synthetic-sync||x".into()),
        obtained_at_epoch_ms: 1_700_000_000_000,
    }
}

/// Kotlin の CSharpInteropTest の sampleLibrary と同じもの。
fn sample_library() -> LibraryBody {
    LibraryBody {
        v: 1,
        feeds: [
            (
                "https://feeds.example/show".to_string(),
                LibraryFeed {
                    title: "番組 \"A\" 🎙".into(),
                    author: Some("作者".into()),
                    thumbnail_url: Some("https://img.example/a.jpg".into()),
                    added_at_ms: 1_700_000_000_000,
                    removed: false,
                    updated_at_ms: 1_700_000_000_000,
                },
            ),
            (
                "https://feeds.example/old".to_string(),
                LibraryFeed { title: "".into(), author: None, thumbnail_url: None, added_at_ms: 0, removed: true, updated_at_ms: 1_700_000_000_010 },
            ),
        ]
        .into_iter()
        .collect(),
        positions: [
            (
                "https://feeds.example/1.mp3".to_string(),
                LibraryPosition { position_seconds: 60, duration_seconds: Some(600), finished: false, updated_at_ms: 1_700_000_000_020 },
            ),
            (
                "https://feeds.example/2.mp3".to_string(),
                LibraryPosition { position_seconds: 5, duration_seconds: None, finished: true, updated_at_ms: 1_700_000_000_030 },
            ),
        ]
        .into_iter()
        .collect(),
    }
}

#[test]
fn device_ids_and_member_keys_match_kotlin() {
    let f = fixture();
    let a = device(&f, "a");
    assert_eq!(s(&f["deviceIds"], "a"), a.device_id());
    assert_eq!(s(&f["deviceIds"], "pc"), device(&f, "pc").device_id());
    let keys: MemberKeys = decode(&f["memberKeys"]["a"]);
    assert_eq!(b64::encode(a.identity_pub()), keys.identity_pub);
    assert_eq!(b64::encode(&hpke::serialize_public_key(a.kx_public())), keys.kx_pub);
    // 同じ鍵で作り直した記述は、Kotlin の記述と同じ鎖で受かる（署名は乱数なので文字列は比べない）
    assert_eq!(keys.device_id, a.member_keys(keys.role).device_id);
}

#[test]
fn verifies_kotlin_chain() {
    let f = fixture();
    let records = records(&f);
    let ChainVerification::Ok(chain) = chain::verify(&records, None) else { panic!("検証できない") };
    assert_eq!(s(&f, "householdId"), chain.household_id());
    let hashes: Vec<String> = f["chainHashes"].as_array().unwrap().iter().map(|n| n.as_str().unwrap().to_string()).collect();
    assert_eq!(hashes, records.iter().map(chain::hash).collect::<Vec<_>>());
    let ids = &f["deviceIds"];
    assert_eq!(vec![s(ids, "a"), s(ids, "b"), s(ids, "pc")], chain.members().keys().map(String::as_str).collect::<Vec<_>>());
    assert_eq!(Role::Controller, chain.members()[s(ids, "pc")].keys.role);
    // JSON の形も同じ（読んで書き直すと Kotlin と同じ文字列）
    for (node, ordered) in f["chain"].as_array().unwrap().iter().zip(ordered_fixture().get("chain").items()) {
        assert_eq!(text_of(ordered), connect_json::encode(&decode::<ChainRecord>(node)));
    }
    let tag = &f["accountTag"];
    assert_eq!(s(tag, "tag"), account_tag::of(s(tag, "householdId"), s(tag, "accountId")));
}

#[test]
fn opens_kotlin_docs() {
    let f = fixture();
    let chain = verified(&records(&f));
    let devices: Vec<(&str, SoftwareDevice)> = ["a", "b", "pc"].into_iter().map(|n| (n, device(&f, n))).collect();
    let mut names = HashSet::new();
    let ordered = ordered_fixture();
    for (entry, ordered_entry) in f["docs"].as_array().unwrap().iter().zip(ordered.get("docs").items()) {
        let sealed: SealedDoc = decode(&entry["doc"]);
        assert_eq!(s(entry, "hash"), doc::hash(&sealed));
        assert_eq!(text_of(ordered_entry.get("doc")), connect_json::encode(&sealed));
        // 開ける端末（無ければ家の全端末）。宛先を絞った doc は、ほかの端末では開けない
        let openers: HashSet<&str> = match entry.get("openers") {
            Some(list) => list.as_array().unwrap().iter().map(|n| n.as_str().unwrap()).collect(),
            None => ["a", "b", "pc"].into_iter().collect(),
        };
        let plaintext = s(entry, "plaintext");
        for (name, device) in &devices {
            if !openers.contains(name) {
                assert_eq!(DocOpen::Rejected("not_for_me"), doc::open(&sealed, &chain, device));
                continue;
            }
            let DocOpen::Ok { plaintext: opened, .. } = doc::open(&sealed, &chain, device) else { panic!("{name} が開けない") };
            assert_eq!(plaintext.as_bytes(), opened.as_slice());
        }
        let name = s(entry, "name");
        names.insert(name.to_string());
        match name {
            "queue" => {
                let queue: QueueBody = connect_json::decode(plaintext).unwrap();
                assert_eq!(plaintext, connect_json::encode(&queue));
                assert!(matches!(snapshot::decode(&queue.snapshot, HANDOFF_ID), HandoffDecodeResult::Ok(_)));
            }
            "cursor" => {
                let cursor: CursorBody = connect_json::decode(plaintext).unwrap();
                assert_eq!(HandoffRepeat::One, cursor.repeat);
                assert_eq!(None, cursor.session_start_version);
                assert_eq!(plaintext, connect_json::encode(&cursor));
            }
            "command" => {
                let command: CommandBody = connect_json::decode(plaintext).unwrap();
                assert!(matches!(command, CommandBody::Yield(_)));
                assert_eq!(plaintext, connect_json::encode(&command));
            }
            "profile" => {
                assert_eq!("Windows PC", connect_json::decode::<ProfileBody>(plaintext).unwrap().name);
            }
            "login_grant" => {
                // ログインを渡す命令（ADR-0015）: 宛先（B）にだけ包まれ、書き直すと同じ文字列
                let command: CommandBody = connect_json::decode(plaintext).unwrap();
                let CommandBody::LoginGrant(grant) = &command else { panic!("login_grant") };
                assert_eq!(&sample_login_grant(devices[0].1.device_id(), devices[1].1.device_id()), grant);
                assert_eq!(plaintext, connect_json::encode(&command));
                assert_eq!(vec![devices[1].1.device_id()], sealed.recipients.iter().map(|r| r.device_id.as_str()).collect::<Vec<_>>());
            }
            "library" => {
                // 操作だけの端末（PC）が書いたライブラリ（ADR-0013）を、家のどの端末も開ける。書き直すと同じバイト
                assert_eq!(DocKind::Library, sealed.header.kind);
                let library: LibraryBody = connect_json::decode(plaintext).unwrap();
                assert!(sample_library().same_as(&library));
                assert_eq!(plaintext, connect_json::encode(&library));
            }
            other => panic!("知らない doc: {other}"),
        }
    }
    assert_eq!(6, names.len());
}

#[test]
fn handoff_codec_matches_kotlin() {
    let f = fixture();
    let text = s(&f["handoff"], "text");
    let HandoffDecodeResult::Ok(decoded) = snapshot::decode(text, HANDOFF_ID) else { panic!("読めない") };
    assert_eq!("夜に駆ける \"quoted\" 🎵", decoded.items[0].title);
    assert_eq!(Some(HandoffSource::Radio { seed_video_id: "abcdefghijA".into() }), decoded.source);
    assert_eq!(HandoffOrigin::UserAdded, decoded.items[1].origin);
    assert_eq!(text, snapshot::encode(&decoded));
    // Kotlin の sampleSnapshot と同じ値から同じ文字列ができる
    assert_eq!(text, snapshot::encode(&snapshot::tests::sample_snapshot()));
}

#[test]
fn pairs_with_kotlin_on_both_sides() {
    let f = fixture();
    let p = &f["pairing"];
    let offer = pairing::decode_offer(s(p, "offer")).unwrap();
    let hello = pairing::decode_hello(&p["hello"]).unwrap();
    let confirm = pairing::decode_confirm(&p["confirm"]).unwrap();

    // この版の Responder（Kotlin の A と同じ使い捨ての鍵・token）が Kotlin の Hello を受ける
    let a: Arc<dyn DeviceSigner + Send + Sync> = Arc::new(device(&f, "a"));
    let mut responder = Responder::with_ephemeral(
        a,
        s(p, "responderName"),
        s(p, "accountId"),
        Box::new(|| 0),
        s(p, "accountId"),
        Some(s(p, "confirmExtension").to_string()),
        ephemeral(s(p, "responderEphemeral")),
        b64::decode(&offer.token).unwrap(),
    );
    assert_eq!(&offer, responder.offer());
    assert!(responder.on_hello(&hello).is_ok());
    let wrong = if s(p, "code") == "000000" { "111111" } else { "000000" };
    assert_eq!(Failure::WrongCode, responder.on_code_entered(wrong).unwrap_err());
    let pair_on_a = responder.on_code_entered(s(p, "code")).unwrap();
    assert_eq!(s(p, "aSendKey"), b64::encode(&pair_on_a.send_key));
    assert_eq!(s(p, "aReceiveKey"), b64::encode(&pair_on_a.receive_key));
    assert_eq!(s(p, "accountKey"), b64::encode(&pair_on_a.account_key));
    assert_eq!(Some(s(p, "helloExtension")), pair_on_a.peer_extension.as_deref());
    assert_eq!("Windows test", pair_on_a.peer_device_name);
    // 拡張は Kotlin の ConnectJson の MemberKeys
    assert_eq!(s(&f["deviceIds"], "b"), connect_json::decode::<MemberKeys>(pair_on_a.peer_extension.as_deref().unwrap()).unwrap().device_id);

    // この版の Initiator（Kotlin の B と同じ使い捨ての鍵）が Kotlin の Confirm を受け、同じ6桁を出す
    let b: Arc<dyn DeviceSigner + Send + Sync> = Arc::new(device(&f, "b"));
    let initiator = Initiator::with_ephemeral(
        &offer,
        b,
        s(p, "initiatorName"),
        s(p, "accountId"),
        Some(s(p, "helloExtension").to_string()),
        ephemeral(s(p, "initiatorEphemeral")),
    )
    .unwrap();
    let confirmed = initiator.on_confirm(&confirm).unwrap();
    assert_eq!(s(p, "code"), confirmed.code);
    assert_eq!(s(p, "bSendKey"), b64::encode(&confirmed.pair.send_key));
    assert_eq!(pair_on_a.send_key, confirmed.pair.receive_key);
    assert_eq!("Pixel 3", confirmed.pair.peer_device_name);
    let extension = confirmed.pair.peer_extension.as_deref().unwrap();
    let join_info: JoinInfo = connect_json::decode(extension).unwrap();
    assert_eq!(s(&f, "householdId"), join_info.household_id);
    assert_eq!(extension, connect_json::encode(&join_info));
}

/// ログインをまだ持たない Kotlin の B（wave-api、ADR-0015）を、この版の A が印で照合して受ける。
#[test]
fn accepts_accountless_kotlin_peer() {
    let f = fixture();
    let p = &f["pairing"]["accountless"];
    let offer = pairing::decode_offer(s(p, "offer")).unwrap();
    let hello = pairing::decode_hello(&p["hello"]).unwrap();
    let a: Arc<dyn DeviceSigner + Send + Sync> = Arc::new(device(&f, "a"));
    let mut responder = Responder::with_ephemeral(
        a,
        "Pixel 3",
        "account-1",
        Box::new(|| 0),
        "account-1",
        None,
        ephemeral(s(p, "responderEphemeral")),
        b64::decode(&offer.token).unwrap(),
    );
    assert!(responder.on_hello(&hello).is_ok());
    let pair = responder.on_code_entered(s(p, "code")).unwrap();
    assert!(pair.peer_accountless);
    assert_eq!("wave API", pair.peer_device_name);
}

/// この版で作った鎖・doc を、Kotlin の固定の鍵の端末が検証・開ける（同じ版どうしの往復。Kotlin 側の確かめは C# の書き出しと同じ形）。
#[test]
fn own_artifacts_with_kotlin_keys_round_trip() {
    let f = fixture();
    let a = device(&f, "a");
    let b = device(&f, "b");
    let pc = device(&f, "pc");
    let genesis = chain::genesis(&a, &a.member_keys(Role::Player)).unwrap();
    let add_b = chain::add(&a, &verified(std::slice::from_ref(&genesis)), &b.member_keys(Role::Player));
    let add_pc = chain::add(&b, &verified(&[genesis.clone(), add_b.clone()]), &pc.member_keys(Role::Controller));
    let chain = verified(&[genesis, add_b, add_pc]);
    let grant = CommandBody::LoginGrant(sample_login_grant(a.device_id(), b.device_id()));
    let only = HashSet::from([b.device_id().to_string()]);
    let sealed = doc::seal(&a, &chain, DocKind::Command, 1, None, 1_700_000_000_007, &connect_json::encode_to_utf8(&grant), Some(&only)).unwrap();
    let DocOpen::Ok { plaintext, .. } = doc::open(&sealed, &chain, &b) else { panic!("b が開けない") };
    assert_eq!(connect_json::encode(&grant).into_bytes(), plaintext);
    assert_eq!(DocOpen::Rejected("not_for_me"), doc::open(&sealed, &chain, &pc));
}
