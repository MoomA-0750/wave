// 中継 v2 のメッセージ（`/v2/connect`、`/v2/pair`。判別子は `op`）。Android 版の RelayV2・Swift の RelayV2 と同じ JSON。
// 判別子はオブジェクトの先頭に出し、読むときは位置を問わない。サーバーは SealedDoc の中身を読まない。

import { DecodeError, parse, Reader, serialize, type Json } from "./json.ts";
import {
  chainRecordToJson,
  libraryStateToJson,
  readChainRecord,
  readLibraryState,
  readSealedDoc,
  readStoredState,
  sealedDocToJson,
  STATE_KINDS,
  storedStateToJson,
  type ChainRecord,
  type LibraryState,
  type SealedDoc,
  type StateKind,
  type StoredState,
} from "./models.ts";

export const AUTH_LABEL = "wave-relay-v2-auth";
export const MAX_FRAME_CHARS = 1_200_000;

export type RelayMessage =
  | { op: "challenge"; host: string; nonce: string }
  | { op: "auth"; publicKey: string; signature: string }
  | {
      op: "welcome";
      deviceId: string;
      storeId: string;
      householdId: string | null;
      headSeq: number | null;
      headHash: string | null;
      activeDeviceId: string | null;
      online: string[];
      /** 最新の状態（welcome と一緒に渡し、端末が「最新を見た上で」つなぎ直しの判断をできるようにする）。 */
      states: StoredState[];
      /** 端末ごとの最新の claim の順番（届いた順を保つ）。 */
      claimOrders: [string, number][];
    }
  | { op: "household_create"; genesis: ChainRecord }
  | { op: "chain_get"; fromSeq: number }
  | { op: "chain"; records: ChainRecord[] }
  | { op: "chain_append"; record: ChainRecord }
  | { op: "chain_ok" | "chain_conflict" | "chain_changed"; headSeq: number; headHash: string }
  | { op: "state_get" }
  | { op: "state_put"; requestId: string; kind: StateKind; expectVersion: number; doc: SealedDoc }
  | { op: "state_ok"; requestId: string; kind: StateKind; version: number }
  | { op: "state_conflict"; requestId: string; kind: StateKind; latest: StoredState | null }
  | { op: "state"; state: StoredState }
  | { op: "library_get" }
  | { op: "library_put"; requestId: string; expectVersion: number; doc: SealedDoc }
  | { op: "library_ok"; requestId: string; version: number }
  | { op: "library_conflict"; requestId: string; latest: LibraryState | null }
  | { op: "library"; state: LibraryState | null }
  | { op: "claim"; claimId: string; yieldDoc: SealedDoc }
  | { op: "transfer"; claimId: string; basedOnVersion: number; yieldDoc: SealedDoc }
  | { op: "yield"; claimId: string; yieldDoc: SealedDoc }
  | { op: "yielded"; claimId: string; expectVersion: number; doc: SealedDoc }
  /** 遠隔操作（命令の doc）。中継は書き手が送り主であることだけを確かめ、家のほかの端末に配る。 */
  | { op: "control"; doc: SealedDoc }
  | { op: "release"; claimId: string }
  | { op: "active_changed"; activeDeviceId: string | null; claimId: string | null; claimOrder: number; pendingOwner: string | null }
  | { op: "pending_expired"; claimId: string }
  | { op: "presence"; online: string[] }
  | { op: "pairing_open"; tokenHash: string }
  | { op: "pair_hello_in"; token: string; hello: Json }
  | { op: "pair_hello"; tokenHash: string; hello: Json }
  | { op: "pair_confirm"; tokenHash: string; confirm: Json }
  | { op: "pair_confirmed"; confirm: Json }
  | { op: "error"; code: string; requestId: string | null };

export function relayToJson(m: RelayMessage): Json {
  switch (m.op) {
    case "challenge":
      return { op: m.op, host: m.host, nonce: m.nonce };
    case "auth":
      return { op: m.op, publicKey: m.publicKey, signature: m.signature };
    case "welcome":
      return {
        op: m.op,
        deviceId: m.deviceId,
        storeId: m.storeId,
        householdId: m.householdId,
        headSeq: m.headSeq,
        headHash: m.headHash,
        activeDeviceId: m.activeDeviceId,
        online: m.online,
        states: m.states.map(storedStateToJson),
        claimOrders: Object.fromEntries(m.claimOrders),
      };
    case "household_create":
      return { op: m.op, genesis: chainRecordToJson(m.genesis) };
    case "chain_get":
      return { op: m.op, fromSeq: m.fromSeq };
    case "chain":
      return { op: m.op, records: m.records.map(chainRecordToJson) };
    case "chain_append":
      return { op: m.op, record: chainRecordToJson(m.record) };
    case "chain_ok":
    case "chain_conflict":
    case "chain_changed":
      return { op: m.op, headSeq: m.headSeq, headHash: m.headHash };
    case "state_get":
    case "library_get":
      return { op: m.op };
    case "state_put":
      return { op: m.op, requestId: m.requestId, kind: m.kind, expectVersion: m.expectVersion, doc: sealedDocToJson(m.doc) };
    case "state_ok":
      return { op: m.op, requestId: m.requestId, kind: m.kind, version: m.version };
    case "state_conflict":
      return { op: m.op, requestId: m.requestId, kind: m.kind, latest: m.latest === null ? null : storedStateToJson(m.latest) };
    case "state":
      return { op: m.op, state: storedStateToJson(m.state) };
    case "library_put":
      return { op: m.op, requestId: m.requestId, expectVersion: m.expectVersion, doc: sealedDocToJson(m.doc) };
    case "library_ok":
      return { op: m.op, requestId: m.requestId, version: m.version };
    case "library_conflict":
      return { op: m.op, requestId: m.requestId, latest: m.latest === null ? null : libraryStateToJson(m.latest) };
    case "library":
      return { op: m.op, state: m.state === null ? null : libraryStateToJson(m.state) };
    case "claim":
      return { op: m.op, claimId: m.claimId, yieldDoc: sealedDocToJson(m.yieldDoc) };
    case "transfer":
      return { op: m.op, claimId: m.claimId, basedOnVersion: m.basedOnVersion, yieldDoc: sealedDocToJson(m.yieldDoc) };
    case "yield":
      return { op: m.op, claimId: m.claimId, yieldDoc: sealedDocToJson(m.yieldDoc) };
    case "yielded":
      return { op: m.op, claimId: m.claimId, expectVersion: m.expectVersion, doc: sealedDocToJson(m.doc) };
    case "control":
      return { op: m.op, doc: sealedDocToJson(m.doc) };
    case "release":
      return { op: m.op, claimId: m.claimId };
    case "active_changed":
      return { op: m.op, activeDeviceId: m.activeDeviceId, claimId: m.claimId, claimOrder: m.claimOrder, pendingOwner: m.pendingOwner };
    case "pending_expired":
      return { op: m.op, claimId: m.claimId };
    case "presence":
      return { op: m.op, online: m.online };
    case "pairing_open":
      return { op: m.op, tokenHash: m.tokenHash };
    case "pair_hello_in":
      return { op: m.op, token: m.token, hello: m.hello };
    case "pair_hello":
      return { op: m.op, tokenHash: m.tokenHash, hello: m.hello };
    case "pair_confirm":
      return { op: m.op, tokenHash: m.tokenHash, confirm: m.confirm };
    case "pair_confirmed":
      return { op: m.op, confirm: m.confirm };
    case "error":
      return { op: m.op, code: m.code, requestId: m.requestId };
  }
}

export function encodeRelay(m: RelayMessage): string {
  return serialize(relayToJson(m));
}

/** 読めない・知らない op なら null。 */
export function decodeRelay(text: string): RelayMessage | null {
  const json = parse(text);
  if (json === null) return null;
  try {
    return readRelay(json);
  } catch {
    return null;
  }
}

export function readRelay(json: Json): RelayMessage {
  const r = new Reader(json);
  const op = r.string("op");
  switch (op) {
    case "challenge":
      return { op, host: r.string("host"), nonce: r.string("nonce") };
    case "auth":
      return { op, publicKey: r.string("publicKey"), signature: r.string("signature") };
    case "welcome":
      return {
        op,
        deviceId: r.string("deviceId"),
        storeId: r.string("storeId"),
        householdId: r.optString("householdId"),
        headSeq: r.optLong("headSeq"),
        headHash: r.optString("headHash"),
        activeDeviceId: r.optString("activeDeviceId"),
        online: r.strings("online", []),
        states: r.array("states", readStoredState, []),
        claimOrders: r.longMap("claimOrders", []),
      };
    case "household_create":
      return { op, genesis: r.value("genesis", readChainRecord) };
    case "chain_get":
      return { op, fromSeq: r.long("fromSeq", 0) };
    case "chain":
      return { op, records: r.array("records", readChainRecord) };
    case "chain_append":
      return { op, record: r.value("record", readChainRecord) };
    case "chain_ok":
    case "chain_conflict":
    case "chain_changed":
      return { op, headSeq: r.long("headSeq"), headHash: r.string("headHash") };
    case "state_get":
    case "library_get":
      return { op };
    case "state_put":
      return { op, requestId: r.string("requestId"), kind: r.enumValue("kind", STATE_KINDS), expectVersion: r.long("expectVersion"), doc: r.value("doc", readSealedDoc) };
    case "state_ok":
      return { op, requestId: r.string("requestId"), kind: r.enumValue("kind", STATE_KINDS), version: r.long("version") };
    case "state_conflict":
      return { op, requestId: r.string("requestId"), kind: r.enumValue("kind", STATE_KINDS), latest: r.optValue("latest", readStoredState) };
    case "state":
      return { op, state: r.value("state", readStoredState) };
    case "library_put":
      return { op, requestId: r.string("requestId"), expectVersion: r.long("expectVersion"), doc: r.value("doc", readSealedDoc) };
    case "library_ok":
      return { op, requestId: r.string("requestId"), version: r.long("version") };
    case "library_conflict":
      return { op, requestId: r.string("requestId"), latest: r.optValue("latest", readLibraryState) };
    case "library":
      return { op, state: r.optValue("state", readLibraryState) };
    case "claim":
      return { op, claimId: r.string("claimId"), yieldDoc: r.value("yieldDoc", readSealedDoc) };
    case "transfer":
      return { op, claimId: r.string("claimId"), basedOnVersion: r.long("basedOnVersion"), yieldDoc: r.value("yieldDoc", readSealedDoc) };
    case "yield":
      return { op, claimId: r.string("claimId"), yieldDoc: r.value("yieldDoc", readSealedDoc) };
    case "yielded":
      return { op, claimId: r.string("claimId"), expectVersion: r.long("expectVersion"), doc: r.value("doc", readSealedDoc) };
    case "control":
      return { op, doc: r.value("doc", readSealedDoc) };
    case "release":
      return { op, claimId: r.string("claimId") };
    case "active_changed":
      return {
        op,
        activeDeviceId: r.optString("activeDeviceId"),
        claimId: r.optString("claimId"),
        claimOrder: r.long("claimOrder"),
        pendingOwner: r.optString("pendingOwner"),
      };
    case "pending_expired":
      return { op, claimId: r.string("claimId") };
    case "presence":
      return { op, online: r.strings("online") };
    case "pairing_open":
      return { op, tokenHash: r.string("tokenHash") };
    case "pair_hello_in":
      return { op, token: r.string("token"), hello: r.raw("hello") };
    case "pair_hello":
      return { op, tokenHash: r.string("tokenHash"), hello: r.raw("hello") };
    case "pair_confirm":
      return { op, tokenHash: r.string("tokenHash"), confirm: r.raw("confirm") };
    case "pair_confirmed":
      return { op, confirm: r.raw("confirm") };
    case "error":
      return { op, code: r.string("code"), requestId: r.optString("requestId") };
    default:
      throw new DecodeError("op");
  }
}
