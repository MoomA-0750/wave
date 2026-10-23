// Connect v2 の形式（Android 版の ConnectModels.kt・Swift の ConnectModels と同じ JSON）。
// 端末一覧は署名付きの記録の鎖で、状態・命令は書いた端末の署名つきの SealedDoc。バイナリは base64url（パディングなし）の文字列で運ぶ。
// JSON のキーは Kotlin の宣言の順に並べる（kotlinx.serialization と同じ順で出す）。
// enum の JSON の名前は SerialName（"player" など）、署名・ハッシュの対象には Kotlin の enum の名前（"Player" など）を使う。

import { b64, lengthPrefixed, utf8 } from "./bytes.ts";
import { sha256 } from "./crypto.ts";
import { DecodeError, Reader, type Json } from "./json.ts";

export type Role = "player" | "controller";
export const ROLES = ["player", "controller"] as const;
export type ChainType = "genesis" | "add" | "remove";
export const CHAIN_TYPES = ["genesis", "add", "remove"] as const;
export type DocKind = "cursor" | "queue" | "command" | "profile" | "library";
export const DOC_KINDS = ["cursor", "queue", "command", "profile", "library"] as const;
export type HandoffRepeat = "off" | "one" | "all";
export const REPEATS = ["off", "one", "all"] as const;
export type HandoffOrigin = "source" | "suggestion" | "user";
export const ORIGINS = ["source", "suggestion", "user"] as const;
export type ControlAction = "play" | "pause" | "next" | "previous" | "seek" | "transfer" | "volume" | "shuffle" | "repeat";
export const CONTROL_ACTIONS = ["play", "pause", "next", "previous", "seek", "transfer", "volume", "shuffle", "repeat"] as const;
export type StateKind = "cursor" | "queue";
export const STATE_KINDS = ["cursor", "queue"] as const;

/** Kotlin の enum の名前（署名・ハッシュの対象）。 */
export function kotlinName(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

// --- 鎖 ---

/** 家に加わる端末の鍵。kxSignature は、その端末の身元鍵での `wave-v2-kx`（deviceId・kxPub・role）の署名。 */
export interface MemberKeys {
  deviceId: string;
  /** 身元の署名公開鍵（SPKI）。deviceId はこの SHA-256。 */
  identityPub: string;
  /** 受け取りの鍵（P-256、非圧縮 SEC1）。 */
  kxPub: string;
  role: Role;
  kxSignature: string;
}

export function memberKeysToJson(k: MemberKeys): Json {
  return { deviceId: k.deviceId, identityPub: k.identityPub, kxPub: k.kxPub, role: k.role, kxSignature: k.kxSignature };
}

export function readMemberKeys(json: Json): MemberKeys {
  const r = new Reader(json);
  return {
    deviceId: r.string("deviceId"),
    identityPub: r.string("identityPub"),
    kxPub: r.string("kxPub"),
    role: r.enumValue("role", ROLES),
    kxSignature: r.string("kxSignature"),
  };
}

/** 端末一覧の記録。genesis は householdId を持たない（householdId = genesis のハッシュ）。 */
export interface ChainRecord {
  v: number;
  householdId: string | null;
  seq: number;
  prevHash: string | null;
  type: ChainType;
  target: MemberKeys | null;
  removedDeviceId: string | null;
  signerId: string;
  signature: string;
}

export const CHAIN_FORMAT_VERSION = 1;

export function chainRecordToJson(c: ChainRecord): Json {
  return {
    v: c.v,
    householdId: c.householdId,
    seq: c.seq,
    prevHash: c.prevHash,
    type: c.type,
    target: c.target === null ? null : memberKeysToJson(c.target),
    removedDeviceId: c.removedDeviceId,
    signerId: c.signerId,
    signature: c.signature,
  };
}

export function readChainRecord(json: Json): ChainRecord {
  const r = new Reader(json);
  return {
    v: r.int("v", CHAIN_FORMAT_VERSION),
    householdId: r.optString("householdId"),
    seq: r.long("seq"),
    prevHash: r.optString("prevHash"),
    type: r.enumValue("type", CHAIN_TYPES),
    target: r.optValue("target", readMemberKeys),
    removedDeviceId: r.optString("removedDeviceId"),
    signerId: r.string("signerId"),
    signature: r.string("signature"),
  };
}

// --- SealedDoc ---

export const DOC_FORMAT_VERSION = 1;

/** SealedDoc の見出し（平文。署名と、内容・包みの AAD に入る）。 */
export interface DocHeader {
  v: number;
  kind: DocKind;
  householdId: string;
  writerId: string;
  version: number;
  /** 同じ書き手の、同じ kind の直前の doc のハッシュ（差し替えの検出だけに使う）。 */
  prevHash: string | null;
  headSeq: number;
  headHash: string;
  issuedAtEpochMs: number;
}

export function docHeaderToJson(h: DocHeader): Json {
  return {
    v: h.v,
    kind: h.kind,
    householdId: h.householdId,
    writerId: h.writerId,
    version: h.version,
    prevHash: h.prevHash,
    headSeq: h.headSeq,
    headHash: h.headHash,
    issuedAtEpochMs: h.issuedAtEpochMs,
  };
}

export function readDocHeader(json: Json): DocHeader {
  const r = new Reader(json);
  return {
    v: r.int("v", DOC_FORMAT_VERSION),
    kind: r.enumValue("kind", DOC_KINDS),
    householdId: r.string("householdId"),
    writerId: r.string("writerId"),
    version: r.long("version"),
    prevHash: r.optString("prevHash"),
    headSeq: r.long("headSeq"),
    headHash: r.string("headHash"),
    issuedAtEpochMs: r.long("issuedAtEpochMs"),
  };
}

/** 宛先ごとの包み: 内容鍵を、その端末の kx へ HPKE で包んだもの。 */
export interface DocRecipient {
  deviceId: string;
  enc: string;
  wrappedKey: string;
}

export interface SealedDoc {
  header: DocHeader;
  nonce: string;
  ciphertext: string;
  recipients: DocRecipient[];
  signature: string;
}

export function sealedDocToJson(d: SealedDoc): Json {
  return {
    header: docHeaderToJson(d.header),
    nonce: d.nonce,
    ciphertext: d.ciphertext,
    recipients: d.recipients.map((x) => ({ deviceId: x.deviceId, enc: x.enc, wrappedKey: x.wrappedKey })),
    signature: d.signature,
  };
}

export function readSealedDoc(json: Json): SealedDoc {
  const r = new Reader(json);
  return {
    header: r.value("header", readDocHeader),
    nonce: r.string("nonce"),
    ciphertext: r.string("ciphertext"),
    recipients: r.array("recipients", (item) => {
      const x = new Reader(item);
      return { deviceId: x.string("deviceId"), enc: x.string("enc"), wrappedKey: x.string("wrappedKey") };
    }),
    signature: r.string("signature"),
  };
}

// --- 状態の本文 ---

/** カーソル（D-5）。小さく、再生・一時停止・シーク・遷移のたびに書く。 */
export interface CursorBody {
  /** このカーソルの曲が載っているキューの doc のハッシュ。 */
  queueRef: string;
  currentItemId: string;
  positionMs: number;
  playing: boolean;
  repeat: HandoffRepeat;
  /** このセッションを始めたときに見ていた最新のカーソルの版（L-10）。 */
  sessionBase: number;
  /** このセッションで自分が最初に書けたカーソルの版。書けるまでは null。 */
  sessionStartVersion: number | null;
  deviceSeq: number;
  listenedMs: number;
  historySent: boolean;
  accountTag: string;
  /** 表示だけ（順序の判断に使わない）。 */
  startedAtEpochMs: number;
  /** 鳴らしている端末の音量（PC のアプリの中の音量、0〜100）。スマホ・時計は載せない。 */
  volumePercent: number | null;
  /** 鳴らしている端末のシャッフル。付いている書き手は遠隔のシャッフル・繰り返しに従う。 */
  shuffle: boolean | null;
}

export function cursorToJson(c: CursorBody): Json {
  return {
    queueRef: c.queueRef,
    currentItemId: c.currentItemId,
    positionMs: c.positionMs,
    playing: c.playing,
    repeat: c.repeat,
    sessionBase: c.sessionBase,
    sessionStartVersion: c.sessionStartVersion,
    deviceSeq: c.deviceSeq,
    listenedMs: c.listenedMs,
    historySent: c.historySent,
    accountTag: c.accountTag,
    startedAtEpochMs: c.startedAtEpochMs,
    volumePercent: c.volumePercent,
    shuffle: c.shuffle,
  };
}

export function readCursor(json: Json): CursorBody {
  const r = new Reader(json);
  const volume = r.optLong("volumePercent");
  return {
    queueRef: r.string("queueRef"),
    currentItemId: r.string("currentItemId"),
    positionMs: r.long("positionMs"),
    playing: r.bool("playing"),
    repeat: r.enumValue("repeat", REPEATS),
    sessionBase: r.long("sessionBase"),
    sessionStartVersion: r.optLong("sessionStartVersion"),
    deviceSeq: r.long("deviceSeq"),
    listenedMs: r.long("listenedMs"),
    historySent: r.bool("historySent"),
    accountTag: r.string("accountTag"),
    startedAtEpochMs: r.long("startedAtEpochMs"),
    volumePercent: volume === null ? null : Math.max(-2147483648, Math.min(2147483647, volume)),
    shuffle: r.optBool("shuffle"),
  };
}

/** キュー（D-5）。snapshot は HandoffCodec.encode の文字列。 */
export interface QueueBody {
  accountTag: string;
  snapshot: string;
}

export function queueToJson(q: QueueBody): Json {
  return { accountTag: q.accountTag, snapshot: q.snapshot };
}

export function readQueue(json: Json): QueueBody {
  const r = new Reader(json);
  return { accountTag: r.string("accountTag"), snapshot: r.string("snapshot") };
}

export interface ProfileBody {
  name: string;
}

export function readProfileBody(json: Json): ProfileBody {
  return { name: new Reader(json).string("name") };
}

// --- 命令（判別子は "type"） ---

/** 再生を始めた端末から、今の再生中の端末へ「止まって」（D-7）。 */
export interface YieldCommand {
  type: "yield";
  claimId: string;
  from: string;
  to: string;
  sessionBase: number;
  accountTag: string;
}

/** 遠隔操作: 鳴っていない端末から、今の再生中の端末へ。 */
export interface ControlCommand {
  type: "control";
  commandId: string;
  from: string;
  to: string;
  action: ControlAction;
  /** シークの位置（seek のときだけ）。 */
  positionMs: number | null;
  /** 送り手が見ていた、受け手のカーソルの版。 */
  basedOnVersion: number;
  accountTag: string;
  /** 送り手が見ていた、受け手の今の曲（次へ・前へ・シーク）。 */
  onItemId: string | null;
  /** 音量（volume のときだけ、0〜100）。 */
  volumePercent: number | null;
  /** シャッフルを入れるか切るか（shuffle のときだけ）。 */
  shuffle: boolean | null;
  /** 繰り返し（repeat のときだけ）。 */
  repeat: HandoffRepeat | null;
}

/** 遠隔のキューへの追加。 */
export interface QueueAddCommand {
  type: "queue_add";
  commandId: string;
  from: string;
  to: string;
  items: HandoffItem[];
  /** 「次に再生」なら true、「キューに追加」なら false。 */
  next: boolean;
  afterItemId: string | null;
  basedOnVersion: number;
  accountTag: string;
}

/** 遠隔のキューの並べ替え・外す・「次に流れる曲」の消去（op は move・remove・clear_flow）。 */
export interface QueueEditCommand {
  type: "queue_edit";
  commandId: string;
  from: string;
  to: string;
  op: string;
  itemIds: string[];
  afterItemId: string | null;
  basedOnVersion: number;
  accountTag: string;
}

/** 端末の表示名を家のほかの端末に知らせる。kind は "phone"・"pc"・"api"（表示の絵と、「ここで再生」の行き先にするかに使う）。 */
export interface ProfileCommand {
  type: "profile";
  from: string;
  name: string;
  kind: string | null;
}

/** ログインを渡す（ADR-0015）。この拡張は受け取っても使わない（値をどこにも置かない）。 */
export interface LoginGrantCommand {
  type: "login_grant";
  commandId: string;
  from: string;
  to: string;
  cookie: string;
  visitorData: string | null;
  dataSyncId: string | null;
  obtainedAtEpochMs: number;
}

export type CommandBody = YieldCommand | ControlCommand | QueueAddCommand | QueueEditCommand | ProfileCommand | LoginGrantCommand;

export const QUEUE_EDIT_MOVE = "move";
export const QUEUE_EDIT_REMOVE = "remove";
export const QUEUE_EDIT_CLEAR_FLOW = "clear_flow";

export function commandToJson(c: CommandBody): Json {
  switch (c.type) {
    case "yield":
      return { type: "yield", claimId: c.claimId, from: c.from, to: c.to, sessionBase: c.sessionBase, accountTag: c.accountTag };
    case "control":
      return {
        type: "control",
        commandId: c.commandId,
        from: c.from,
        to: c.to,
        action: c.action,
        positionMs: c.positionMs,
        basedOnVersion: c.basedOnVersion,
        accountTag: c.accountTag,
        onItemId: c.onItemId,
        volumePercent: c.volumePercent,
        shuffle: c.shuffle,
        repeat: c.repeat,
      };
    case "queue_add":
      return {
        type: "queue_add",
        commandId: c.commandId,
        from: c.from,
        to: c.to,
        items: c.items.map(itemToJson),
        next: c.next,
        afterItemId: c.afterItemId,
        basedOnVersion: c.basedOnVersion,
        accountTag: c.accountTag,
      };
    case "queue_edit":
      return {
        type: "queue_edit",
        commandId: c.commandId,
        from: c.from,
        to: c.to,
        op: c.op,
        itemIds: c.itemIds,
        afterItemId: c.afterItemId,
        basedOnVersion: c.basedOnVersion,
        accountTag: c.accountTag,
      };
    case "profile":
      return { type: "profile", from: c.from, name: c.name, kind: c.kind };
    case "login_grant":
      return {
        type: "login_grant",
        commandId: c.commandId,
        from: c.from,
        to: c.to,
        cookie: c.cookie,
        visitorData: c.visitorData,
        dataSyncId: c.dataSyncId,
        obtainedAtEpochMs: c.obtainedAtEpochMs,
      };
  }
}

export function readCommand(json: Json): CommandBody {
  const r = new Reader(json);
  switch (r.string("type")) {
    case "yield":
      return {
        type: "yield",
        claimId: r.string("claimId"),
        from: r.string("from"),
        to: r.string("to"),
        sessionBase: r.long("sessionBase"),
        accountTag: r.string("accountTag"),
      };
    case "control": {
      const volume = r.optLong("volumePercent");
      return {
        type: "control",
        commandId: r.string("commandId"),
        from: r.string("from"),
        to: r.string("to"),
        action: r.enumValue("action", CONTROL_ACTIONS),
        positionMs: r.optLong("positionMs"),
        basedOnVersion: r.long("basedOnVersion"),
        accountTag: r.string("accountTag"),
        onItemId: r.optString("onItemId"),
        volumePercent: volume === null ? null : Math.max(-2147483648, Math.min(2147483647, volume)),
        shuffle: r.optBool("shuffle"),
        repeat: r.optEnumValue("repeat", REPEATS),
      };
    }
    case "queue_add":
      return {
        type: "queue_add",
        commandId: r.string("commandId"),
        from: r.string("from"),
        to: r.string("to"),
        items: r.array("items", readItem),
        next: r.bool("next"),
        afterItemId: r.optString("afterItemId"),
        basedOnVersion: r.long("basedOnVersion"),
        accountTag: r.string("accountTag"),
      };
    case "queue_edit":
      return {
        type: "queue_edit",
        commandId: r.string("commandId"),
        from: r.string("from"),
        to: r.string("to"),
        op: r.string("op"),
        itemIds: r.strings("itemIds"),
        afterItemId: r.optString("afterItemId"),
        basedOnVersion: r.long("basedOnVersion"),
        accountTag: r.string("accountTag"),
      };
    case "profile":
      return { type: "profile", from: r.string("from"), name: r.string("name"), kind: r.optString("kind") };
    case "login_grant":
      return {
        type: "login_grant",
        commandId: r.string("commandId"),
        from: r.string("from"),
        to: r.string("to"),
        cookie: r.string("cookie"),
        visitorData: r.optString("visitorData"),
        dataSyncId: r.optString("dataSyncId"),
        obtainedAtEpochMs: r.long("obtainedAtEpochMs"),
      };
    default:
      throw new DecodeError("type");
  }
}

// --- キューのスナップショット（Android 版の HandoffSnapshot と同じ JSON） ---

export interface HandoffRef {
  name: string;
  browseId: string | null;
}

export interface HandoffItem {
  /** 項目ごとの ID（UUID）。 */
  itemId: string;
  videoId: string;
  title: string;
  artists: HandoffRef[];
  album: HandoffRef | null;
  durationSeconds: number | null;
  thumbnailUrl: string | null;
  origin: HandoffOrigin;
  /** ライブ配信か。false のときは JSON に書かない。 */
  isLive: boolean;
}

export type HandoffSource = { type: "radio"; seedVideoId: string } | { type: "playlist"; playlistId: string };

export interface HandoffSnapshot {
  schemaVersion: number;
  handoffId: string;
  items: HandoffItem[];
  currentItemId: string;
  positionMs: number;
  playing: boolean;
  repeat: HandoffRepeat;
  source: HandoffSource | null;
  continuation: string | null;
  radioPlaylistId: string | null;
  exhausted: boolean;
  listenedMs: number;
  historySent: boolean;
  capturedAtEpochMs: number;
}

function refToJson(r: HandoffRef): Json {
  return { name: r.name, browseId: r.browseId };
}

function readRef(json: Json): HandoffRef {
  const r = new Reader(json);
  return { name: r.string("name"), browseId: r.optString("browseId") };
}

export function itemToJson(i: HandoffItem): Json {
  const out: { [key: string]: Json } = {
    itemId: i.itemId,
    videoId: i.videoId,
    title: i.title,
    artists: i.artists.map(refToJson),
    album: i.album === null ? null : refToJson(i.album),
    durationSeconds: i.durationSeconds,
    thumbnailUrl: i.thumbnailUrl,
    origin: i.origin,
  };
  // ライブのときだけ印を足す（false は書かない）
  if (i.isLive) out["isLive"] = true;
  return out;
}

export function readItem(json: Json): HandoffItem {
  const r = new Reader(json);
  return {
    itemId: r.string("itemId"),
    videoId: r.string("videoId"),
    title: r.string("title"),
    artists: r.array("artists", readRef),
    album: r.optValue("album", readRef),
    durationSeconds: r.optInt("durationSeconds"),
    thumbnailUrl: r.optString("thumbnailUrl"),
    origin: r.enumValue("origin", ORIGINS),
    isLive: r.bool("isLive", false),
  };
}

function sourceToJson(s: HandoffSource): Json {
  return s.type === "radio" ? { type: "radio", seedVideoId: s.seedVideoId } : { type: "playlist", playlistId: s.playlistId };
}

function readSource(json: Json): HandoffSource {
  const r = new Reader(json);
  switch (r.string("type")) {
    case "radio":
      return { type: "radio", seedVideoId: r.string("seedVideoId") };
    case "playlist":
      return { type: "playlist", playlistId: r.string("playlistId") };
    default:
      throw new DecodeError("type");
  }
}

export function snapshotToJson(s: HandoffSnapshot): Json {
  return {
    schemaVersion: s.schemaVersion,
    handoffId: s.handoffId,
    items: s.items.map(itemToJson),
    currentItemId: s.currentItemId,
    positionMs: s.positionMs,
    playing: s.playing,
    repeat: s.repeat,
    source: s.source === null ? null : sourceToJson(s.source),
    continuation: s.continuation,
    radioPlaylistId: s.radioPlaylistId,
    exhausted: s.exhausted,
    listenedMs: s.listenedMs,
    historySent: s.historySent,
    capturedAtEpochMs: s.capturedAtEpochMs,
  };
}

export function readSnapshot(json: Json): HandoffSnapshot {
  const r = new Reader(json);
  return {
    schemaVersion: r.int("schemaVersion"),
    handoffId: r.string("handoffId"),
    items: r.array("items", readItem),
    currentItemId: r.string("currentItemId"),
    positionMs: r.long("positionMs"),
    playing: r.bool("playing"),
    repeat: r.enumValue("repeat", REPEATS),
    source: r.optValue("source", readSource),
    continuation: r.optString("continuation"),
    radioPlaylistId: r.optString("radioPlaylistId"),
    exhausted: r.bool("exhausted"),
    listenedMs: r.long("listenedMs"),
    historySent: r.bool("historySent"),
    capturedAtEpochMs: r.long("capturedAtEpochMs"),
  };
}

// --- 中継が持つ状態 ---

/** サーバーが持つ最新の状態（配るときの形）。 */
export interface StoredState {
  kind: StateKind;
  version: number;
  doc: SealedDoc;
  storeId: string;
  receivedAtServerMs: number;
  serverNowMs: number;
  activeDeviceId: string | null;
}

export function storedStateToJson(s: StoredState): Json {
  return {
    kind: s.kind,
    version: s.version,
    doc: sealedDocToJson(s.doc),
    storeId: s.storeId,
    receivedAtServerMs: s.receivedAtServerMs,
    serverNowMs: s.serverNowMs,
    activeDeviceId: s.activeDeviceId,
  };
}

export function readStoredState(json: Json): StoredState {
  const r = new Reader(json);
  return {
    kind: r.enumValue("kind", STATE_KINDS),
    version: r.long("version"),
    doc: r.value("doc", readSealedDoc),
    storeId: r.string("storeId"),
    receivedAtServerMs: r.long("receivedAtServerMs"),
    serverNowMs: r.long("serverNowMs"),
    activeDeviceId: r.optString("activeDeviceId"),
  };
}

/** ライブラリの最新（ADR-0013）。この拡張はライブラリを扱わないが、中継の形として読み書きできるようにしておく。 */
export interface LibraryState {
  version: number;
  doc: SealedDoc;
  storeId: string;
}

export function libraryStateToJson(s: LibraryState): Json {
  return { version: s.version, doc: sealedDocToJson(s.doc), storeId: s.storeId };
}

export function readLibraryState(json: Json): LibraryState {
  const r = new Reader(json);
  return { version: r.long("version"), doc: r.value("doc", readSealedDoc), storeId: r.string("storeId") };
}

/** 加入の Confirm で受け取った床（D-3）。 */
export interface JoinInfo {
  householdId: string;
  headSeq: number;
  headHash: string;
  storeId: string;
  cursorFloor: number;
  queueFloor: number;
}

export function joinInfoToJson(j: JoinInfo): Json {
  return {
    householdId: j.householdId,
    headSeq: j.headSeq,
    headHash: j.headHash,
    storeId: j.storeId,
    cursorFloor: j.cursorFloor,
    queueFloor: j.queueFloor,
  };
}

export function readJoinInfo(json: Json): JoinInfo {
  const r = new Reader(json);
  return {
    householdId: r.string("householdId"),
    headSeq: r.long("headSeq"),
    headHash: r.string("headHash"),
    storeId: r.string("storeId"),
    cursorFloor: r.long("cursorFloor"),
    queueFloor: r.long("queueFloor"),
  };
}

/** 端末が保存しておくもの（D-2 の頭、D-4 の storeId と版の床、D-9 の退いた印、処理した claimId）。 */
export interface ClientPersisted {
  chain: ChainRecord[];
  storeId: string | null;
  cursorFloor: number;
  queueFloor: number;
  retreated: boolean;
  handledClaimIds: string[];
  expectedJoin: JoinInfo | null;
  expectedInviter: string | null;
  creatingHousehold: boolean;
}

export function emptyPersisted(): ClientPersisted {
  return {
    chain: [],
    storeId: null,
    cursorFloor: 0,
    queueFloor: 0,
    retreated: false,
    handledClaimIds: [],
    expectedJoin: null,
    expectedInviter: null,
    creatingHousehold: false,
  };
}

export function persistedToJson(p: ClientPersisted): Json {
  return {
    chain: p.chain.map(chainRecordToJson),
    storeId: p.storeId,
    cursorFloor: p.cursorFloor,
    queueFloor: p.queueFloor,
    retreated: p.retreated,
    handledClaimIds: p.handledClaimIds,
    expectedJoin: p.expectedJoin === null ? null : joinInfoToJson(p.expectedJoin),
    expectedInviter: p.expectedInviter,
    creatingHousehold: p.creatingHousehold,
  };
}

export function readPersisted(json: Json): ClientPersisted {
  const r = new Reader(json);
  return {
    chain: r.array("chain", readChainRecord, []),
    storeId: r.optString("storeId"),
    cursorFloor: r.long("cursorFloor", 0),
    queueFloor: r.long("queueFloor", 0),
    retreated: r.bool("retreated", false),
    handledClaimIds: r.strings("handledClaimIds", []),
    expectedJoin: r.optValue("expectedJoin", readJoinInfo),
    expectedInviter: r.optString("expectedInviter"),
    creatingHousehold: r.bool("creatingHousehold", false),
  };
}

// --- アカウントタグ ---

export const ACCOUNT_TAG_LABEL = "wave-v2-account-tag";

/** 家ごとのアカウントタグ（D-3）: `SHA-256(ラベル ‖ householdId ‖ DATASYNC_ID)` を base64url で。値はログに出さない。 */
export async function accountTagOf(householdId: string, accountId: string): Promise<string> {
  return b64(await sha256(lengthPrefixed(utf8(ACCOUNT_TAG_LABEL), utf8(householdId), utf8(accountId))));
}

/** DATASYNC_ID は "||" の前だけを使う（core の normalizeDataSyncId と同じ）。空になったら null。 */
export function normalizeDataSyncId(raw: string | null | undefined): string | null {
  if (raw == null) return null;
  const at = raw.indexOf("||");
  const head = (at >= 0 ? raw.slice(0, at) : raw).trim();
  return head.length > 0 ? head : null;
}
