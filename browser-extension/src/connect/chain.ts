// 端末一覧の鎖（D-2）。記録の作成と、genesis からの検証（Android 版・Swift の MemberChain と同じ）。

import { b64, lengthPrefixed, unb64, utf8, type Bytes } from "./bytes.ts";
import { decodePublicKey, deviceId as deviceIdOf, sha256, verify, type EcPublicKey } from "./crypto.ts";
import { deserializePublicKey, serializePublicKey } from "./hpke.ts";
import { CHAIN_FORMAT_VERSION, kotlinName, type ChainRecord, type MemberKeys, type Role } from "./models.ts";

export const CHAIN_LABEL = "wave-v2-chain";
export const KX_LABEL = "wave-v2-kx";
const MAX_RECORDS = 1024;

/** 端末の身元鍵での署名。 */
export interface DeviceSigner {
  readonly deviceId: string;
  /** 身元の署名公開鍵（SPKI）。 */
  readonly identityPub: Bytes;
  /** 用途ラベルを前置した署名（DER）。 */
  sign(label: string, message: Uint8Array): Promise<Bytes>;
}

/** この端末の受け取りの鍵（kx）で ECDH する。 */
export interface KxOpener {
  readonly deviceId: string;
  readonly kxPub: EcPublicKey;
  /** ECDH の共有秘密（x 座標、32 バイト）。 */
  dh(peer: EcPublicKey): Promise<Bytes>;
}

export interface ChainMember {
  keys: MemberKeys;
  identity: EcPublicKey;
  kx: EcPublicKey;
  addedSeq: number;
}

/** genesis から検証した端末一覧。 */
export class VerifiedChain {
  readonly householdId: string;
  readonly headSeq: number;
  readonly headHash: string;
  /** 端末 ID から端末。 */
  readonly members: Map<string, ChainMember>;
  /** 加えた順の端末 ID。 */
  readonly memberIds: string[];
  readonly removed: Set<string>;
  private readonly hashes: string[];

  constructor(
    householdId: string,
    headSeq: number,
    headHash: string,
    members: Map<string, ChainMember>,
    memberIds: string[],
    removed: Set<string>,
    hashes: string[],
  ) {
    this.householdId = householdId;
    this.headSeq = headSeq;
    this.headHash = headHash;
    this.members = members;
    this.memberIds = memberIds;
    this.removed = removed;
    this.hashes = hashes;
  }

  /** 加えた順の端末。 */
  get orderedMembers(): ChainMember[] {
    return this.memberIds.map((id) => this.members.get(id)).filter((m): m is ChainMember => m !== undefined);
  }

  hashAt(seq: number): string | null {
    return seq >= 0 && seq < this.hashes.length ? this.hashes[seq]! : null;
  }
}

export interface Head {
  seq: number;
  hash: string;
}

export type ChainVerification =
  | { kind: "ok"; chain: VerifiedChain }
  | { kind: "invalid"; seq: number; reason: string }
  /** 自分が検証した頭より短い（サーバーが巻き戻した）。 */
  | { kind: "rollback" }
  /** 自分が検証した頭と同じ seq で、ハッシュが違う（サーバーが分岐を見せた）。 */
  | { kind: "fork" };

function kxMessage(deviceId: string, kx: Uint8Array, role: Role): Bytes {
  return lengthPrefixed(utf8(deviceId), kx, utf8(kotlinName(role)));
}

/** 署名とハッシュの対象（署名そのものを除く全部を長さ付きで連結）。enum は Kotlin の enum の名前（"Genesis"・"Player"）。 */
export function canonicalRecord(record: ChainRecord): Bytes {
  const t = record.target;
  const parts = [
    String(record.v),
    record.householdId ?? "",
    String(record.seq),
    record.prevHash ?? "",
    kotlinName(record.type),
    t?.deviceId ?? "",
    t?.identityPub ?? "",
    t?.kxPub ?? "",
    t ? kotlinName(t.role) : "",
    t?.kxSignature ?? "",
    record.removedDeviceId ?? "",
    record.signerId,
  ];
  return lengthPrefixed(...parts.map(utf8));
}

/** 記録のハッシュ（householdId・prevHash・頭）。署名は含めない。 */
export async function recordHash(record: ChainRecord): Promise<string> {
  return b64(await sha256(canonicalRecord(record)));
}

/** この端末が家に加わるときの鍵の記述（kx を身元鍵で自己署名する）。 */
export async function memberKeysOf(signer: DeviceSigner, kxPub: EcPublicKey, role: Role): Promise<MemberKeys> {
  const kx = serializePublicKey(kxPub);
  const signature = await signer.sign(KX_LABEL, kxMessage(signer.deviceId, kx, role));
  return { deviceId: signer.deviceId, identityPub: b64(signer.identityPub), kxPub: b64(kx), role, kxSignature: b64(signature) };
}

async function signed(signer: DeviceSigner, record: ChainRecord): Promise<ChainRecord> {
  return { ...record, signature: b64(await signer.sign(CHAIN_LABEL, canonicalRecord(record))) };
}

export async function genesisRecord(signer: DeviceSigner, keys: MemberKeys): Promise<ChainRecord> {
  if (keys.deviceId !== signer.deviceId || keys.role !== "player") throw new Error("genesis は自分の player の鍵で作る");
  return signed(signer, {
    v: CHAIN_FORMAT_VERSION,
    householdId: null,
    seq: 0,
    prevHash: null,
    type: "genesis",
    target: keys,
    removedDeviceId: null,
    signerId: signer.deviceId,
    signature: "",
  });
}

export async function addRecord(signer: DeviceSigner, chain: VerifiedChain, target: MemberKeys): Promise<ChainRecord> {
  return signed(signer, {
    v: CHAIN_FORMAT_VERSION,
    householdId: chain.householdId,
    seq: chain.headSeq + 1,
    prevHash: chain.headHash,
    type: "add",
    target,
    removedDeviceId: null,
    signerId: signer.deviceId,
    signature: "",
  });
}

export async function removeRecord(signer: DeviceSigner, chain: VerifiedChain, deviceId: string): Promise<ChainRecord> {
  return signed(signer, {
    v: CHAIN_FORMAT_VERSION,
    householdId: chain.householdId,
    seq: chain.headSeq + 1,
    prevHash: chain.headHash,
    type: "remove",
    target: null,
    removedDeviceId: deviceId,
    signerId: signer.deviceId,
    signature: "",
  });
}

async function memberOf(keys: MemberKeys, seq: number): Promise<ChainMember | null> {
  const identityBytes = unb64(keys.identityPub);
  if (identityBytes === null) return null;
  const identity = await decodePublicKey(identityBytes);
  if (identity === null) return null;
  if ((await deviceIdOf(identityBytes)) !== keys.deviceId) return null;
  const kxBytes = unb64(keys.kxPub);
  if (kxBytes === null) return null;
  const kx = await deserializePublicKey(kxBytes);
  if (kx === null) return null;
  const kxSignature = unb64(keys.kxSignature);
  if (kxSignature === null) return null;
  if (!(await verify(identity, KX_LABEL, kxMessage(keys.deviceId, kxBytes, keys.role), kxSignature))) return null;
  return { keys, identity, kx, addedSeq: seq };
}

async function verifySignature(record: ChainRecord, identity: EcPublicKey): Promise<boolean> {
  const signature = unb64(record.signature);
  if (signature === null) return false;
  return verify(identity, CHAIN_LABEL, canonicalRecord(record), signature);
}

/** genesis から検証する。trusted（この端末が前に検証した頭）があれば、それより短い鎖と、その seq でハッシュが違う鎖を拒む。 */
export async function verifyChain(records: ChainRecord[], trusted?: Head | null): Promise<ChainVerification> {
  if (records.length === 0 || records.length > MAX_RECORDS) return { kind: "invalid", seq: 0, reason: "size" };
  const members = new Map<string, ChainMember>();
  let memberIds: string[] = [];
  const removed = new Set<string>();
  const hashes: string[] = [];
  let householdId = "";
  for (let index = 0; index < records.length; index++) {
    const record = records[index]!;
    const invalid = (reason: string): ChainVerification => ({ kind: "invalid", seq: index, reason });
    if (record.v !== CHAIN_FORMAT_VERSION || record.seq !== index) return invalid("seq");
    if (index === 0) {
      if (record.type !== "genesis" || record.householdId !== null || record.prevHash !== null) return invalid("genesis");
      const target = record.target;
      if (target === null) return invalid("genesis_target");
      if (target.role !== "player" || record.signerId !== target.deviceId || record.removedDeviceId !== null) return invalid("genesis_signer");
      const member = await memberOf(target, index);
      if (member === null) return invalid("genesis_keys");
      if (!(await verifySignature(record, member.identity))) return invalid("signature");
      members.set(target.deviceId, member);
      memberIds.push(target.deviceId);
      householdId = await recordHash(record);
    } else {
      if (record.householdId !== householdId || record.prevHash !== hashes[hashes.length - 1]) return invalid("link");
      const signer = members.get(record.signerId);
      if (signer === undefined) return invalid("signer_not_member");
      if (!(await verifySignature(record, signer.identity))) return invalid("signature");
      switch (record.type) {
        case "genesis":
          return invalid("second_genesis");
        case "add": {
          const target = record.target;
          if (target === null) return invalid("add_target");
          if (record.removedDeviceId !== null) return invalid("add_shape");
          // player を加えられるのは player だけ（L-11）。controller も当面は player だけが加える
          if (signer.keys.role !== "player") return invalid("add_by_controller");
          if (members.has(target.deviceId) || removed.has(target.deviceId)) return invalid("add_existing");
          const member = await memberOf(target, index);
          if (member === null) return invalid("add_keys");
          members.set(target.deviceId, member);
          memberIds.push(target.deviceId);
          break;
        }
        case "remove": {
          const targetId = record.removedDeviceId;
          if (targetId === null) return invalid("remove_target");
          if (record.target !== null) return invalid("remove_shape");
          const target = members.get(targetId);
          if (target === undefined) return invalid("remove_not_member");
          // controller は自分だけを外せる
          if (signer.keys.role !== "player" && targetId !== signer.keys.deviceId) return invalid("remove_by_controller");
          // player がいなくなる remove は拒む
          if (target.keys.role === "player" && [...members.values()].filter((m) => m.keys.role === "player").length <= 1) {
            return invalid("last_player");
          }
          members.delete(targetId);
          memberIds = memberIds.filter((id) => id !== targetId);
          removed.add(targetId);
          break;
        }
      }
    }
    hashes.push(await recordHash(record));
  }
  if (trusted) {
    if (records.length <= trusted.seq) return { kind: "rollback" };
    if (trusted.seq < 0 || hashes[trusted.seq] !== trusted.hash) return { kind: "fork" };
  }
  return {
    kind: "ok",
    chain: new VerifiedChain(householdId, records.length - 1, hashes[hashes.length - 1]!, members, memberIds, removed, hashes),
  };
}
