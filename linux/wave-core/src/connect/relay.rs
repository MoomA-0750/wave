//! 中継 v2 のメッセージ（/v2/connect、/v2/pair。判別子は op）。Windows の RelayV2・Android の RelayV2 と同じ JSON。
//! 中継は SealedDoc の中身を読まない。

use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::models::{ChainRecord, DocKind, SealedDoc};

/// 状態の種類（中継が版を持つもの）。
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum StateKind {
    Cursor,
    Queue,
}

impl StateKind {
    /// 状態の種類に対応する doc の種類。
    pub fn doc_kind(self) -> DocKind {
        match self {
            StateKind::Cursor => DocKind::Cursor,
            StateKind::Queue => DocKind::Queue,
        }
    }
}

/// 中継が持つ最新の状態（配るときの形）。
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredState {
    pub kind: StateKind,
    pub version: i64,
    pub doc: SealedDoc,
    pub store_id: String,
    pub received_at_server_ms: i64,
    pub server_now_ms: i64,
    #[serde(default)]
    pub active_device_id: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum RelayMessage {
    // --- 接続 ---
    Challenge {
        host: String,
        nonce: String,
    },
    Auth {
        public_key: String,
        signature: String,
    },
    Welcome {
        device_id: String,
        store_id: String,
        #[serde(default)]
        household_id: Option<String>,
        #[serde(default)]
        head_seq: Option<i64>,
        #[serde(default)]
        head_hash: Option<String>,
        #[serde(default)]
        active_device_id: Option<String>,
        #[serde(default)]
        online: Vec<String>,
        /// 最新の状態（welcome と一緒に渡し、端末が「最新を見た上で」つなぎ直しの判断をできるようにする）。
        #[serde(default)]
        states: Vec<StoredState>,
        /// 端末ごとの最新の claim の順番（同時に始めたときの判断）。
        #[serde(default)]
        claim_orders: HashMap<String, i64>,
    },

    // --- 端末一覧の鎖 ---
    HouseholdCreate {
        genesis: ChainRecord,
    },
    ChainGet {
        #[serde(default)]
        from_seq: i64,
    },
    Chain {
        records: Vec<ChainRecord>,
    },
    ChainAppend {
        record: ChainRecord,
    },
    ChainOk {
        head_seq: i64,
        head_hash: String,
    },
    ChainConflict {
        head_seq: i64,
        head_hash: String,
    },
    ChainChanged {
        head_seq: i64,
        head_hash: String,
    },

    // --- 状態 ---
    StateGet {},
    StatePut {
        request_id: String,
        kind: StateKind,
        expect_version: i64,
        doc: SealedDoc,
    },
    StateOk {
        request_id: String,
        kind: StateKind,
        version: i64,
    },
    StateConflict {
        request_id: String,
        kind: StateKind,
        #[serde(default)]
        latest: Option<StoredState>,
    },
    State {
        state: StoredState,
    },

    // --- ライブラリ（ADR-0013）。Linux 版は RSS を持たないので読まない（library_get を送らなければ届かない） ---

    // --- 再生を始める・引き取る ---
    Claim {
        claim_id: String,
        yield_doc: SealedDoc,
    },
    Transfer {
        claim_id: String,
        based_on_version: i64,
        yield_doc: SealedDoc,
    },
    Yield {
        claim_id: String,
        yield_doc: SealedDoc,
    },
    Yielded {
        claim_id: String,
        expect_version: i64,
        doc: SealedDoc,
    },
    /// 遠隔操作（命令の doc）。中継は書き手が送り主であることだけを確かめ、家のほかの端末に配る。保存しない。
    Control {
        doc: SealedDoc,
    },
    Release {
        claim_id: String,
    },
    ActiveChanged {
        #[serde(default)]
        active_device_id: Option<String>,
        #[serde(default)]
        claim_id: Option<String>,
        claim_order: i64,
        /// transfer の途中（新しい端末の最初の書き込みを待っている）。
        #[serde(default)]
        pending_owner: Option<String>,
    },
    PendingExpired {
        claim_id: String,
    },
    Presence {
        online: Vec<String>,
    },

    // --- 加入（Hello・Confirm は不透明な JSON） ---
    PairingOpen {
        token_hash: String,
    },
    PairHelloIn {
        token: String,
        hello: Value,
    },
    PairHello {
        token_hash: String,
        hello: Value,
    },
    PairConfirm {
        token_hash: String,
        confirm: Value,
    },
    PairConfirmed {
        confirm: Value,
    },
    Error {
        code: String,
        #[serde(default)]
        request_id: Option<String>,
    },
}

impl RelayMessage {
    pub fn encode(&self) -> String {
        serde_json::to_string(self).expect("relay messages are plain data")
    }

    /// 読めない・知らない op なら None。
    pub fn decode(text: &str) -> Option<Self> {
        serde_json::from_str(text).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_use_op_and_camel_case_fields() {
        let get = RelayMessage::ChainGet { from_seq: 3 }.encode();
        assert_eq!(get, r#"{"op":"chain_get","fromSeq":3}"#);
        assert_eq!(RelayMessage::StateGet {}.encode(), r#"{"op":"state_get"}"#);
        let error = RelayMessage::decode(r#"{"op":"error","code":"not_active","requestId":"r1"}"#).unwrap();
        assert_eq!(error, RelayMessage::Error { code: "not_active".into(), request_id: Some("r1".into()) });
    }

    #[test]
    fn unknown_ops_and_missing_optional_fields_are_handled() {
        assert!(RelayMessage::decode(r#"{"op":"library","state":null}"#).is_none());
        let changed = RelayMessage::decode(r#"{"op":"active_changed","claimOrder":7}"#).unwrap();
        assert_eq!(changed, RelayMessage::ActiveChanged { active_device_id: None, claim_id: None, claim_order: 7, pending_owner: None });
        let welcome = RelayMessage::decode(r#"{"op":"welcome","deviceId":"d","storeId":"s"}"#).unwrap();
        assert!(matches!(welcome, RelayMessage::Welcome { online, states, .. } if online.is_empty() && states.is_empty()));
    }
}
