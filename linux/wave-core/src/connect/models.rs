//! Connect v2 の形式（Android 版の ConnectModels.kt・Windows 版の ConnectModels.cs と同じ JSON）。
//! 端末一覧は署名付きの記録の鎖で、状態・命令は書いた端末の署名つきの SealedDoc。バイナリは base64url（パディングなし）の文字列で運ぶ。
//! JSON の項目は Kotlin の宣言の順に並べる（kotlinx.serialization と同じ順で出す。serde は宣言の順に書く）。
//! enum の JSON の名前は SerialName（"player" など）、署名・ハッシュの対象には enum の名前（"Player" など、`name()`）を使う。
//! kotlinx の設定（ignoreUnknownKeys・encodeDefaults・null を "x":null と出す）に合わせる: 知らない項目は読み飛ばし、
//! Option は欠けていても None として読み、None は null と書く。

use indexmap::IndexMap;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use super::crypto::{self, b64};
use super::snapshot::{HandoffItem, HandoffRepeat};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Role {
    /// 再生する端末（アカウントの照合あり）。
    #[serde(rename = "player")]
    Player,
    /// 表示と操作だけ（PC）。再生しない、アカウントを持たない。
    #[serde(rename = "controller")]
    Controller,
}

impl Role {
    /// Kotlin の enum の名前（署名・ハッシュの対象）。
    pub fn name(self) -> &'static str {
        match self {
            Role::Player => "Player",
            Role::Controller => "Controller",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ChainType {
    #[serde(rename = "genesis")]
    Genesis,
    #[serde(rename = "add")]
    Add,
    #[serde(rename = "remove")]
    Remove,
}

impl ChainType {
    /// Kotlin の enum の名前（署名・ハッシュの対象）。
    pub fn name(self) -> &'static str {
        match self {
            ChainType::Genesis => "Genesis",
            ChainType::Add => "Add",
            ChainType::Remove => "Remove",
        }
    }
}

/// 家に加わる端末の鍵。kx_signature は、その端末の身元鍵での `wave-v2-kx`（deviceId・kxPub・role）の署名。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemberKeys {
    pub device_id: String,
    /// 身元の署名公開鍵（SPKI）。device_id はこの SHA-256。
    pub identity_pub: String,
    /// 受け取りの鍵（P-256、非圧縮 SEC1）。
    pub kx_pub: String,
    pub role: Role,
    pub kx_signature: String,
}

fn chain_format_version() -> i32 {
    ChainRecord::FORMAT_VERSION
}

/// 端末一覧の記録。genesis は household_id を持たない（householdId = genesis のハッシュ）。
/// add・genesis は target、remove は removed_device_id を持つ。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChainRecord {
    #[serde(default = "chain_format_version")]
    pub v: i32,
    #[serde(default)]
    pub household_id: Option<String>,
    pub seq: i64,
    #[serde(default)]
    pub prev_hash: Option<String>,
    #[serde(rename = "type")]
    pub kind: ChainType,
    #[serde(default)]
    pub target: Option<MemberKeys>,
    #[serde(default)]
    pub removed_device_id: Option<String>,
    pub signer_id: String,
    pub signature: String,
}

impl ChainRecord {
    pub const FORMAT_VERSION: i32 = 1;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum DocKind {
    #[serde(rename = "cursor")]
    Cursor,
    #[serde(rename = "queue")]
    Queue,
    #[serde(rename = "command")]
    Command,
    #[serde(rename = "profile")]
    Profile,
    /// ライブラリ（RSS で登録した番組・聴いたところ、ADR-0013）。家のどの端末も書ける。
    #[serde(rename = "library")]
    Library,
}

impl DocKind {
    /// Kotlin の enum の名前（署名・包みの info の対象）。
    pub fn name(self) -> &'static str {
        match self {
            DocKind::Cursor => "Cursor",
            DocKind::Queue => "Queue",
            DocKind::Command => "Command",
            DocKind::Profile => "Profile",
            DocKind::Library => "Library",
        }
    }
}

fn doc_format_version() -> i32 {
    DocHeader::FORMAT_VERSION
}

/// SealedDoc の見出し（平文。署名と、内容・包みの AAD に入る）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DocHeader {
    #[serde(default = "doc_format_version")]
    pub v: i32,
    pub kind: DocKind,
    pub household_id: String,
    pub writer_id: String,
    pub version: i64,
    /// 同じ書き手の、同じ kind の直前の doc のハッシュ（差し替えの検出だけに使う）。
    #[serde(default)]
    pub prev_hash: Option<String>,
    pub head_seq: i64,
    pub head_hash: String,
    pub issued_at_epoch_ms: i64,
}

impl DocHeader {
    pub const FORMAT_VERSION: i32 = 1;
}

/// 宛先ごとの包み: 内容鍵を、その端末の kx へ HPKE で包んだもの。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DocRecipient {
    pub device_id: String,
    pub enc: String,
    pub wrapped_key: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SealedDoc {
    pub header: DocHeader,
    pub nonce: String,
    pub ciphertext: String,
    pub recipients: Vec<DocRecipient>,
    pub signature: String,
}

/// カーソル（D-5）。小さく、再生・一時停止・シーク・遷移のたびに書く。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CursorBody {
    /// このカーソルの曲が載っているキューの doc のハッシュ（doc::hash）。
    pub queue_ref: String,
    pub current_item_id: String,
    pub position_ms: i64,
    pub playing: bool,
    pub repeat: HandoffRepeat,
    /// このセッションを始めたときに見ていた最新のカーソルの版（L-10）。
    pub session_base: i64,
    /// このセッションで自分が最初に書けたカーソルの版。書けるまでは None。
    #[serde(default)]
    pub session_start_version: Option<i64>,
    pub device_seq: i64,
    pub listened_ms: i64,
    pub history_sent: bool,
    pub account_tag: String,
    /// 表示だけ（順序の判断に使わない）。
    pub started_at_epoch_ms: i64,
    /// 鳴らしている端末の音量（PC のアプリの中の音量。スマホ・時計は音量を合わせないので載せない・受けない）（0〜100）。
    /// ほかの端末の音量の表示に使う。前の版の書き手は付けない（None）。
    #[serde(default)]
    pub volume_percent: Option<i32>,
    /// 鳴らしている端末のシャッフル。前の版の書き手は付けない（None）。付いている書き手は遠隔のシャッフル・繰り返し
    /// （ControlAction::Shuffle・Repeat）に従うので、ほかの端末はこれが None でないときだけその操作を出す（Android と同じ）。
    #[serde(default)]
    pub shuffle: Option<bool>,
}

/// キュー（D-5）。snapshot は snapshot::encode の文字列（取り込む側が snapshot::decode で検証する）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueBody {
    pub account_tag: String,
    pub snapshot: String,
}

/// 命令（判別子は "type"。判別子はオブジェクトの先頭に出し、読むときは位置を問わない）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum CommandBody {
    #[serde(rename = "yield")]
    Yield(YieldCommand),
    #[serde(rename = "control")]
    Control(ControlCommand),
    #[serde(rename = "queue_add")]
    QueueAdd(QueueAddCommand),
    #[serde(rename = "queue_edit")]
    QueueEdit(QueueEditCommand),
    #[serde(rename = "profile")]
    Profile(ProfileCommand),
    #[serde(rename = "login_grant")]
    LoginGrant(LoginGrantCommand),
}

/// 再生を始めた端末から、今の再生中の端末へ「止まって」（D-7）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct YieldCommand {
    pub claim_id: String,
    pub from: String,
    pub to: String,
    pub session_base: i64,
    pub account_tag: String,
}

/// 遠隔操作: 鳴っていない端末から、今の再生中の端末へ「再生・一時停止・次へ・前へ・シーク」。
/// 受け手は、自分宛て・自分が書き手・同じアカウント・処理していない ID・見ていた版が近いときだけ従う。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ControlCommand {
    pub command_id: String,
    pub from: String,
    pub to: String,
    pub action: ControlAction,
    /// シークの位置（seek のときだけ）。
    #[serde(default)]
    pub position_ms: Option<i64>,
    /// 送り手が見ていた、受け手のカーソルの版。
    pub based_on_version: i64,
    pub account_tag: String,
    /// 送り手が見ていた、受け手の今の曲（次へ・前へ・シーク）。受け手の今の曲と違えば従わない（Android と同じ）。前の版の送り手は付けない（None）。
    #[serde(default)]
    pub on_item_id: Option<String>,
    /// 音量（volume のときだけ、0〜100）。鳴らしている端末の音量（PC のアプリの中の音量。スマホ・時計は音量を合わせないので載せない・受けない）にする。
    #[serde(default)]
    pub volume_percent: Option<i32>,
    /// シャッフルを入れるか切るか（shuffle のときだけ）。
    #[serde(default)]
    pub shuffle: Option<bool>,
    /// 繰り返し（repeat のときだけ）。
    #[serde(default)]
    pub repeat: Option<HandoffRepeat>,
}

/// 遠隔のキューへの追加: 鳴っていない端末から、今の再生中の端末へ「この曲をキューに入れて」（Android の CommandBody.QueueAdd と同じ）。
/// 従う条件は Control と同じ。受け手は after_item_id の曲があればその直後へ、無ければ next なら今の曲の直後、
/// そうでなければ自分で足した曲のかたまりの最後へ、送り手が振った項目の ID のまま入れる。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueAddCommand {
    pub command_id: String,
    pub from: String,
    pub to: String,
    pub items: Vec<HandoffItem>,
    /// 「次に再生」なら true、「キューに追加」なら false。
    pub next: bool,
    #[serde(default)]
    pub after_item_id: Option<String>,
    /// 送り手が見ていた、受け手のカーソルの版。
    pub based_on_version: i64,
    pub account_tag: String,
}

/// 遠隔のキューの並べ替え・外す: 鳴っていない端末のキューの画面から、今の再生中の端末へ（Android の CommandBody.QueueEdit と同じ）。従う条件は QueueAdd と同じ。
/// op が "move" なら item_ids の1曲を after_item_id の曲の直後へ（after_item_id が無ければ今の曲の直後へ）動かす。
/// "remove" なら item_ids の曲を外す。どれも今の曲より後ろの曲だけで、受け手のキューに無い ID・今の曲・前の曲は無視する。
/// "clear_flow" なら「次に流れる曲」（元の流れの曲）をまとめて外して流れを止める（自分で足した曲は残す）。item_ids は送り手が見ていた今の曲だけで、受け手の今の曲と違えば無視する。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueEditCommand {
    pub command_id: String,
    pub from: String,
    pub to: String,
    pub op: String,
    pub item_ids: Vec<String>,
    #[serde(default)]
    pub after_item_id: Option<String>,
    /// 送り手が見ていた、受け手のカーソルの版。
    pub based_on_version: i64,
    pub account_tag: String,
}

impl QueueEditCommand {
    pub const MOVE: &'static str = "move";
    pub const REMOVE: &'static str = "remove";
    pub const CLEAR_FLOW: &'static str = "clear_flow";
}

/// ログインを渡す（ADR-0015、Android の CommandBody.LoginGrant と同じ）: 家の端末から wave-api（種類 api の端末）へ、本人が押したときだけ。
/// 宛先の端末の受け取りの鍵にだけ包む。値は WebView のログインで取った生の値（Debug に出さない）。
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoginGrantCommand {
    pub command_id: String,
    pub from: String,
    pub to: String,
    pub cookie: String,
    #[serde(default)]
    pub visitor_data: Option<String>,
    #[serde(default)]
    pub data_sync_id: Option<String>,
    pub obtained_at_epoch_ms: i64,
}

impl std::fmt::Debug for LoginGrantCommand {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "LoginGrant {{ command_id: {}, from: {}, to: {} }}", self.command_id, self.from, self.to)
    }
}

/// 端末の表示名を家のほかの端末に知らせる（名前は鎖に入れないので、加入した2台以外にも伝える）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileCommand {
    pub from: String,
    pub name: String,
    /// 端末の種類（"phone"・"pc" など。表示の絵だけに使う）。
    #[serde(default)]
    pub kind: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ControlAction {
    #[serde(rename = "play")]
    Play,
    #[serde(rename = "pause")]
    Pause,
    #[serde(rename = "next")]
    Next,
    #[serde(rename = "previous")]
    Previous,
    #[serde(rename = "seek")]
    Seek,
    /// 宛先の端末に「ここで再生（引き取り）」を頼む。宛先は書き手でない端末。
    #[serde(rename = "transfer")]
    Transfer,
    /// 鳴らしている端末の音量（PC のアプリの中の音量。スマホ・時計は音量を合わせないので載せない・受けない）を変える（volume_percent）。
    #[serde(rename = "volume")]
    Volume,
    /// 鳴らしている端末のシャッフルを入れる・切る（shuffle）。前の版の受け手は読めずに捨てる（カーソルの shuffle が None の相手には送らない）。
    #[serde(rename = "shuffle")]
    Shuffle,
    /// 鳴らしている端末の繰り返しを決める（repeat）。送る条件は Shuffle と同じ。
    #[serde(rename = "repeat")]
    Repeat,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProfileBody {
    pub name: String,
}

fn library_version() -> i32 {
    LibraryBody::CURRENT_VERSION
}

/// ライブラリの文書の中身（ADR-0013。Kotlin の LibraryBody と同じ JSON）。RSS で登録した番組と、エピソードの聴いたところ。
/// 番組・エピソードごとに、updated_at_ms の新しい方を残して合わせる（merge）。外した番組も印（removed）として残す。
/// 辞書は並びを保つ（読んで書き直すと同じ文字列になる）。== は並びを問わない（Kotlin の data class と同じ）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LibraryBody {
    #[serde(default = "library_version")]
    pub v: i32,
    /// フィードの URL ごと。
    #[serde(default)]
    pub feeds: IndexMap<String, LibraryFeed>,
    /// 音声の URL ごと。
    #[serde(default)]
    pub positions: IndexMap<String, LibraryPosition>,
}

impl Default for LibraryBody {
    fn default() -> Self {
        Self { v: Self::CURRENT_VERSION, feeds: IndexMap::new(), positions: IndexMap::new() }
    }
}

impl LibraryBody {
    pub const CURRENT_VERSION: i32 = 1;

    /// 聴いたところは新しい順にこの数まで（文書を数百 KB に収める）。
    pub const MAX_POSITIONS: usize = 500;

    /// 2つを合わせる。同じ時刻なら other を残す（自分の書き込みが後から届いた相手の書き込みを消さないよう、呼ぶ側は相手を other に渡す）。
    /// 並びは Kotlin と同じ（自分の項目の順、その後に相手だけの項目）。
    pub fn merge(&self, other: &LibraryBody) -> LibraryBody {
        LibraryBody {
            v: Self::CURRENT_VERSION,
            feeds: merge_maps(&self.feeds, &other.feeds, |f| f.updated_at_ms),
            positions: trim(merge_maps(&self.positions, &other.positions, |p| p.updated_at_ms)),
        }
    }

    /// 中身が同じか（Kotlin の data class の == と同じく、辞書は並びを問わない）。
    pub fn same_as(&self, other: &LibraryBody) -> bool {
        self == other
    }
}

fn merge_maps<T: Clone>(mine: &IndexMap<String, T>, other: &IndexMap<String, T>, at: impl Fn(&T) -> i64) -> IndexMap<String, T> {
    let mut merged = IndexMap::new();
    for key in mine.keys().chain(other.keys()) {
        if merged.contains_key(key) {
            continue;
        }
        let newer = match (mine.get(key), other.get(key)) {
            (Some(m), Some(o)) => {
                if at(m) > at(o) {
                    m
                } else {
                    o
                }
            }
            (Some(m), None) => m,
            (None, Some(o)) => o,
            (None, None) => unreachable!(),
        };
        merged.insert(key.clone(), newer.clone());
    }
    merged
}

fn trim(positions: IndexMap<String, LibraryPosition>) -> IndexMap<String, LibraryPosition> {
    if positions.len() <= LibraryBody::MAX_POSITIONS {
        return positions;
    }
    // 新しい順（同じ時刻は元の並び。Kotlin の sortedByDescending と同じく安定）
    let mut entries: Vec<_> = positions.into_iter().collect();
    entries.sort_by_key(|e| std::cmp::Reverse(e.1.updated_at_ms));
    entries.into_iter().take(LibraryBody::MAX_POSITIONS).collect()
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryFeed {
    pub title: String,
    #[serde(default)]
    pub author: Option<String>,
    #[serde(default)]
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub added_at_ms: i64,
    /// 外した（この印のほうが新しければ、ほかの端末でも外す）。
    #[serde(default)]
    pub removed: bool,
    pub updated_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryPosition {
    pub position_seconds: i32,
    #[serde(default)]
    pub duration_seconds: Option<i32>,
    #[serde(default)]
    pub finished: bool,
    pub updated_at_ms: i64,
}

/// 中継が持つ最新のライブラリの文書（ADR-0013。Windows 版では RelayV2.cs にある）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryState {
    pub version: i64,
    pub doc: SealedDoc,
    pub store_id: String,
}

/// ペアリングの Confirm に入れる拡張（家の ID・一覧の頭・storeId・版の床。秘密を入れない）。Windows 版では ConnectClient.JoinInfo。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JoinInfo {
    pub household_id: String,
    pub head_seq: i64,
    pub head_hash: String,
    pub store_id: String,
    pub cursor_floor: i64,
    pub queue_floor: i64,
}

/// kotlinx.serialization の設定に合わせた JSON の読み書き（serde_json は kotlinx と同じく `"`・`\`・U+0000〜U+001F だけを逃がす）。
pub mod connect_json {
    use super::*;

    pub fn encode<T: Serialize + ?Sized>(value: &T) -> String {
        serde_json::to_string(value).expect("Connect の型は常に JSON にできる")
    }

    pub fn encode_to_utf8<T: Serialize + ?Sized>(value: &T) -> Vec<u8> {
        encode(value).into_bytes()
    }

    /// 読めなければ None（例外を出さない）。
    pub fn decode<T: DeserializeOwned>(text: &str) -> Option<T> {
        serde_json::from_str(text).ok()
    }

    pub fn decode_utf8<T: DeserializeOwned>(utf8: &[u8]) -> Option<T> {
        serde_json::from_slice(utf8).ok()
    }
}

/// 家ごとのアカウントタグ（D-3、Android 版の AccountTag と同じ）: `SHA-256(ラベル ‖ householdId ‖ DATASYNC_ID)` を base64url で。値はログに出さない。
pub mod account_tag {
    use super::*;

    pub const LABEL: &str = "wave-v2-account-tag";

    pub fn of(household_id: &str, account_id: &str) -> String {
        b64::encode(&crypto::sha256(&[&crypto::length_prefixed(&[LABEL.as_bytes(), household_id.as_bytes(), account_id.as_bytes()])]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn escapes_like_kotlinx() {
        // 必要な文字だけを逃がす（制御文字は短い形か \u00xx の小文字）
        assert_eq!(
            r#"{"name":"a\"b\\c\n\u0001日本🎵<>&'"}"#,
            connect_json::encode(&ProfileBody { name: "a\"b\\c\n\u{1}日本🎵<>&'".into() })
        );
    }

    /// Kotlin の ModeControlJsonTest と同じ文字列。
    #[test]
    fn shuffle_and_repeat_json_matches_kotlin() {
        let shuffle = r#"{"type":"control","commandId":"c1","from":"dev-b","to":"dev-a","action":"shuffle","positionMs":null,"basedOnVersion":7,"accountTag":"tag","onItemId":null,"volumePercent":null,"shuffle":true,"repeat":null}"#;
        let repeat = r#"{"type":"control","commandId":"c2","from":"dev-b","to":"dev-a","action":"repeat","positionMs":null,"basedOnVersion":7,"accountTag":"tag","onItemId":null,"volumePercent":null,"shuffle":null,"repeat":"one"}"#;
        let Some(CommandBody::Control(s)) = connect_json::decode::<CommandBody>(shuffle) else { panic!("control") };
        assert_eq!((ControlAction::Shuffle, Some(true)), (s.action, s.shuffle));
        let Some(CommandBody::Control(r)) = connect_json::decode::<CommandBody>(repeat) else { panic!("control") };
        assert_eq!((ControlAction::Repeat, Some(HandoffRepeat::One)), (r.action, r.repeat));
        // 書く形も Kotlin と同じ
        assert_eq!(shuffle, connect_json::encode(&CommandBody::Control(s)));
        assert_eq!(repeat, connect_json::encode(&CommandBody::Control(r)));
    }

    #[test]
    fn volume_json_matches_kotlin() {
        let kotlin = r#"{"type":"control","commandId":"c1","from":"dev-b","to":"dev-a","action":"volume","positionMs":null,"basedOnVersion":7,"accountTag":"tag","onItemId":null,"volumePercent":35}"#;
        let Some(CommandBody::Control(control)) = connect_json::decode::<CommandBody>(kotlin) else { panic!("control") };
        assert_eq!(ControlAction::Volume, control.action);
        assert_eq!(Some(35), control.volume_percent);
    }

    #[test]
    fn queue_add_json_matches_kotlin() {
        // Android 版（kotlinx.serialization）が書く形
        let kotlin = r#"{"type":"queue_add","commandId":"c1","from":"dev-b","to":"dev-a","items":[{"itemId":"i1","videoId":"v1","title":"曲","artists":[{"name":"人","browseId":"UC1"}],"album":null,"durationSeconds":200,"thumbnailUrl":null,"origin":"user"}],"next":true,"afterItemId":null,"basedOnVersion":7,"accountTag":"tag"}"#;
        let Some(CommandBody::QueueAdd(add)) = connect_json::decode::<CommandBody>(kotlin) else { panic!("queue_add") };
        assert_eq!(1, add.items.len());
        assert_eq!("i1", add.items[0].item_id);
        assert_eq!(super::super::snapshot::HandoffOrigin::UserAdded, add.items[0].origin);
        assert!(add.next);
        assert_eq!(None, add.after_item_id);
        assert_eq!(7, add.based_on_version);
        // 書き戻しても同じ形（判別子が先頭）
        assert_eq!(kotlin, connect_json::encode(&CommandBody::QueueAdd(add)));
    }

    #[test]
    fn queue_edit_json_matches_kotlin() {
        let kotlin = r#"{"type":"queue_edit","commandId":"c1","from":"dev-b","to":"dev-a","op":"move","itemIds":["i2"],"afterItemId":"i5","basedOnVersion":7,"accountTag":"tag"}"#;
        let Some(CommandBody::QueueEdit(edit)) = connect_json::decode::<CommandBody>(kotlin) else { panic!("queue_edit") };
        assert_eq!(QueueEditCommand::MOVE, edit.op);
        assert_eq!(vec!["i2".to_string()], edit.item_ids);
        assert_eq!(Some("i5".to_string()), edit.after_item_id);
        assert_eq!(7, edit.based_on_version);
        // afterItemId を省いた形も読める
        let remove = r#"{"type":"queue_edit","commandId":"c2","from":"dev-b","to":"dev-a","op":"remove","itemIds":["i3","i4"],"basedOnVersion":7,"accountTag":"tag"}"#;
        let Some(CommandBody::QueueEdit(removed)) = connect_json::decode::<CommandBody>(remove) else { panic!("queue_edit") };
        assert_eq!(QueueEditCommand::REMOVE, removed.op);
        assert_eq!(None, removed.after_item_id);
        // 書き戻しても同じ形（判別子が先頭）
        assert_eq!(kotlin, connect_json::encode(&CommandBody::QueueEdit(edit)));
    }

    #[test]
    fn discriminator_may_come_anywhere_and_unknown_keys_are_ignored() {
        let text = r#"{"from":"f","name":"n","x":1,"type":"profile"}"#;
        assert_eq!(
            Some(CommandBody::Profile(ProfileCommand { from: "f".into(), name: "n".into(), kind: None })),
            connect_json::decode::<CommandBody>(text)
        );
        assert_eq!(None, connect_json::decode::<CommandBody>(r#"{"type":"nope"}"#));
        // 必須の項目が欠けていれば読めない
        assert_eq!(None, connect_json::decode::<CommandBody>(r#"{"type":"profile","from":"f"}"#));
    }

    #[test]
    fn login_grant_debug_hides_the_cookie() {
        let grant = LoginGrantCommand {
            command_id: "c".into(),
            from: "a".into(),
            to: "b".into(),
            cookie: "SAPISID=secret".into(),
            visitor_data: Some("visitor".into()),
            data_sync_id: Some("sync".into()),
            obtained_at_epoch_ms: 1,
        };
        let text = format!("{:?}", CommandBody::LoginGrant(grant));
        assert!(!text.contains("secret") && !text.contains("sync") && !text.contains("visitor"));
    }

    #[test]
    fn library_merge_keeps_newer_and_order() {
        let feed = |title: &str, at: i64| LibraryFeed { title: title.into(), author: None, thumbnail_url: None, added_at_ms: 0, removed: false, updated_at_ms: at };
        let mine = LibraryBody { feeds: [("a".to_string(), feed("mine-a", 5)), ("b".to_string(), feed("mine-b", 1))].into_iter().collect(), ..Default::default() };
        let other = LibraryBody { feeds: [("c".to_string(), feed("other-c", 1)), ("b".to_string(), feed("other-b", 1)), ("a".to_string(), feed("other-a", 4))].into_iter().collect(), ..Default::default() };
        let merged = mine.merge(&other);
        assert_eq!(vec!["a", "b", "c"], merged.feeds.keys().map(String::as_str).collect::<Vec<_>>());
        assert_eq!("mine-a", merged.feeds["a"].title);
        // 同じ時刻なら other を残す
        assert_eq!("other-b", merged.feeds["b"].title);
        // 並びを問わずに比べる
        let reordered = LibraryBody { feeds: merged.feeds.iter().rev().map(|(k, v)| (k.clone(), v.clone())).collect(), ..Default::default() };
        assert!(merged.same_as(&reordered));
    }

    #[test]
    fn library_positions_are_trimmed_to_the_newest() {
        let positions: IndexMap<String, LibraryPosition> = (0..(LibraryBody::MAX_POSITIONS as i64 + 10))
            .map(|i| (format!("u{i}"), LibraryPosition { position_seconds: 1, duration_seconds: None, finished: false, updated_at_ms: i }))
            .collect();
        let merged = LibraryBody { positions, ..Default::default() }.merge(&LibraryBody::default());
        assert_eq!(LibraryBody::MAX_POSITIONS, merged.positions.len());
        assert_eq!(Some("u509"), merged.positions.keys().next().map(String::as_str));
        assert!(!merged.positions.contains_key("u9"));
    }
}
