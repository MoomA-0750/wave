// SealedDoc（D-1）: 本文を書き込みごとの内容鍵（AES-256-GCM）で暗号化し、内容鍵を家の端末ごとに HPKE で包み、全体に書き手が署名する
// （Android 版・Swift の DocSealer と同じ）。

import { b64, lengthPrefixed, randomBytes, unb64, utf8, type Bytes } from "./bytes.ts";
import { open, seal, sha256, verify } from "./crypto.ts";
import { hpkeOpen, hpkeSeal } from "./hpke.ts";
import type { DeviceSigner, KxOpener, VerifiedChain } from "./chain.ts";
import { DOC_FORMAT_VERSION, kotlinName, type DocHeader, type DocKind, type Role, type SealedDoc } from "./models.ts";

export const DOC_LABEL = "wave-v2-doc";
const WRAP_INFO_LABEL = "wave-v2-wrap";
const PAD_BLOCK = 4096;
export const MAX_PLAINTEXT = 1024 * 1024;

export type DocOpen =
  /** staleRecipients: 自分の一覧で外された端末が宛先にいる（古い一覧で書かれた）。 */
  | { kind: "ok"; header: DocHeader; plaintext: Bytes; staleRecipients: boolean }
  /** 書き手の一覧の頭が自分より新しい。鎖を取り直してから開く。 */
  | { kind: "need_chain"; headSeq: number }
  /** 書き手の一覧の頭が、同じ seq で自分の鎖と違う（サーバーが分岐させた）。 */
  | { kind: "fork" }
  | { kind: "rejected"; reason: string };

/** 文字列を UTF-16 の符号単位で比べる（C# の StringComparer.Ordinal・Swift の ordinalLess と同じ）。JS の < と同じ。 */
export function ordinalLess(a: string, b: string): boolean {
  return a < b;
}

/** 見出しの canonical バイト列。kind は Kotlin の enum の名前（"Cursor" など）。 */
export function canonicalHeader(h: DocHeader): Bytes {
  const parts = [
    String(h.v),
    kotlinName(h.kind),
    h.householdId,
    h.writerId,
    String(h.version),
    h.prevHash ?? "",
    String(h.headSeq),
    h.headHash,
    String(h.issuedAtEpochMs),
  ];
  return lengthPrefixed(...parts.map(utf8));
}

/** 署名の対象: 見出し・nonce・暗号文・宛先ごとの包み（宛先 ID の順。同じ ID が並べば元の順）。 */
function signedBytes(doc: SealedDoc): Bytes {
  const parts: Uint8Array[] = [canonicalHeader(doc.header), utf8(doc.nonce), utf8(doc.ciphertext)];
  const ordered = doc.recipients
    .map((r, i) => ({ r, i }))
    .sort((a, b) => (a.r.deviceId < b.r.deviceId ? -1 : a.r.deviceId > b.r.deviceId ? 1 : a.i - b.i));
  for (const { r } of ordered) parts.push(utf8(r.deviceId), utf8(r.enc), utf8(r.wrappedKey));
  return lengthPrefixed(...parts);
}

/** doc のハッシュ（prevHash・食い違いの検出・キューの参照）。署名は含めない。 */
export async function docHash(doc: SealedDoc): Promise<string> {
  return b64(await sha256(signedBytes(doc)));
}

export function roleAllowed(kind: DocKind, role: Role): boolean {
  return kind === "cursor" || kind === "queue" ? role === "player" : true;
}

function wrapInfo(kind: DocKind, householdId: string): Bytes {
  return lengthPrefixed(utf8(WRAP_INFO_LABEL), utf8(kotlinName(kind)), utf8(householdId));
}

function wrapAad(headerHash: Uint8Array, recipientId: string): Bytes {
  return lengthPrefixed(headerHash, utf8(recipientId));
}

/** 長さ（4 バイト）‖ 本文 ‖ 0 埋めで、4KB 単位にそろえる。 */
function pad(plaintext: Uint8Array): Bytes {
  const total = Math.ceil((plaintext.length + 4) / PAD_BLOCK) * PAD_BLOCK;
  const out = new Uint8Array(total);
  out.set(lengthPrefixed(plaintext));
  return out;
}

function unpad(padded: Uint8Array): Bytes | null {
  if (padded.length < 4 || padded.length % PAD_BLOCK !== 0) return null;
  const size = new DataView(padded.buffer, padded.byteOffset, padded.byteLength).getInt32(0);
  if (size < 0 || size > padded.length - 4) return null;
  return padded.slice(4, 4 + size) as Bytes;
}

/** 宛先は、書き手が検証した一覧の今の全端末（only を渡せば、家の一部の端末に絞る）。 */
export async function sealDoc(args: {
  signer: DeviceSigner;
  chain: VerifiedChain;
  kind: DocKind;
  version: number;
  prevHash: string | null;
  issuedAtEpochMs: number;
  plaintext: Uint8Array;
  only?: Set<string>;
}): Promise<SealedDoc> {
  const { signer, chain, kind, only } = args;
  if (args.plaintext.length > MAX_PLAINTEXT) throw new Error("plaintext too large");
  if (!chain.members.has(signer.deviceId)) throw new Error("writer is not a member");
  if (only !== undefined && (only.size === 0 || [...only].some((id) => !chain.members.has(id)))) throw new Error("recipient is not a member");
  const header: DocHeader = {
    v: DOC_FORMAT_VERSION,
    kind,
    householdId: chain.householdId,
    writerId: signer.deviceId,
    version: args.version,
    prevHash: args.prevHash,
    headSeq: chain.headSeq,
    headHash: chain.headHash,
    issuedAtEpochMs: args.issuedAtEpochMs,
  };
  const headerHash = await sha256(canonicalHeader(header));
  const contentKey = randomBytes(32);
  const { nonce, ciphertext } = await seal(contentKey, headerHash, pad(args.plaintext));
  const members = [...chain.members.values()]
    .filter((m) => only === undefined || only.has(m.keys.deviceId))
    .sort((a, b) => (a.keys.deviceId < b.keys.deviceId ? -1 : a.keys.deviceId > b.keys.deviceId ? 1 : 0));
  const recipients = [];
  for (const member of members) {
    const sealed = await hpkeSeal(member.kx, wrapInfo(kind, chain.householdId), wrapAad(headerHash, member.keys.deviceId), contentKey);
    recipients.push({ deviceId: member.keys.deviceId, enc: b64(sealed.enc), wrappedKey: b64(sealed.ciphertext) });
  }
  const doc: SealedDoc = { header, nonce: b64(nonce), ciphertext: b64(ciphertext), recipients, signature: "" };
  doc.signature = b64(await signer.sign(DOC_LABEL, signedBytes(doc)));
  return doc;
}

/** 書き手・役割・署名・一覧の頭を確かめてから開く。 */
export async function openDoc(doc: SealedDoc, chain: VerifiedChain, me: KxOpener): Promise<DocOpen> {
  const header = doc.header;
  const rejected = (reason: string): DocOpen => ({ kind: "rejected", reason });
  if (header.v !== DOC_FORMAT_VERSION) return rejected("version");
  if (header.householdId !== chain.householdId) return rejected("household");
  if (header.headSeq > chain.headSeq) return { kind: "need_chain", headSeq: header.headSeq };
  if (chain.hashAt(header.headSeq) !== header.headHash) return { kind: "fork" };
  const writer = chain.members.get(header.writerId);
  if (writer === undefined) return rejected("writer_not_member");
  if (!roleAllowed(header.kind, writer.keys.role)) return rejected("role");
  if (new Set(doc.recipients.map((r) => r.deviceId)).size !== doc.recipients.length) return rejected("duplicate_recipient");
  const signature = unb64(doc.signature);
  if (signature === null) return rejected("malformed");
  if (!(await verify(writer.identity, DOC_LABEL, signedBytes(doc), signature))) return rejected("signature");
  const mine = doc.recipients.find((r) => r.deviceId === me.deviceId);
  if (mine === undefined) return rejected("not_for_me");
  const headerHash = await sha256(canonicalHeader(header));
  const enc = unb64(mine.enc);
  const wrapped = unb64(mine.wrappedKey);
  if (enc === null || wrapped === null) return rejected("malformed");
  const contentKey = await hpkeOpen(enc, me.kxPub, (peer) => me.dh(peer), wrapInfo(header.kind, header.householdId), wrapAad(headerHash, me.deviceId), wrapped);
  if (contentKey === null) return rejected("unwrap");
  const nonce = unb64(doc.nonce);
  const ciphertext = unb64(doc.ciphertext);
  if (nonce === null || ciphertext === null) return rejected("malformed");
  const padded = await open(contentKey, nonce, headerHash, ciphertext);
  if (padded === null) return rejected("content");
  const plaintext = unpad(padded);
  if (plaintext === null) return rejected("padding");
  const stale = doc.recipients.some((r) => chain.removed.has(r.deviceId));
  return { kind: "ok", header, plaintext, staleRecipients: stale };
}
