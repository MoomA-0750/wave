// RFC 9180 HPKE の base モード、DHKEM(P-256, HKDF-SHA256)・HKDF-SHA256・AES-128-GCM（Swift の Hpke と同じ手順）。
// 付録 A.3 のテストベクタで確かめる。書き込みごとの内容鍵を、家の端末それぞれの受け取りの鍵（kx）へ包むのに使う。
// 受け手の DH は関数で受ける（受け手の秘密鍵は書き出せない CryptoKey のまま）。公開鍵の送る形は非圧縮 SEC1（65 バイト）。

import { concat, utf8, type Bytes } from "./bytes.ts";
import { aesGcmOpen, aesGcmSeal, decodePublicKey, deserializePublicKey, ecdh, generateEphemeral, Hkdf, publicKeyOf, type EcPublicKey } from "./crypto.ts";

const KEM_ID = 0x0010;
const KDF_ID = 0x0001;
const AEAD_ID = 0x0001;
const N_SECRET = 32;
const N_K = 16;
const N_N = 12;
export const N_ENC = 65;

function i2osp(value: number, length: number): Bytes {
  const out = new Uint8Array(length);
  let v = BigInt(value);
  for (let i = length - 1; i >= 0; i--) {
    out[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return out;
}

const versionLabel = utf8("HPKE-v1");
const kemSuite = concat(utf8("KEM"), i2osp(KEM_ID, 2));
const hpkeSuite = concat(utf8("HPKE"), i2osp(KEM_ID, 2), i2osp(KDF_ID, 2), i2osp(AEAD_ID, 2));

function labeledExtract(suite: Uint8Array, salt: Uint8Array, label: string, ikm: Uint8Array): Promise<Bytes> {
  return Hkdf.extract(salt, concat(versionLabel, suite, utf8(label), ikm));
}

function labeledExpand(suite: Uint8Array, prk: Uint8Array, label: string, info: Uint8Array, length: number): Promise<Bytes> {
  return Hkdf.expand(prk, concat(i2osp(length, 2), versionLabel, suite, utf8(label), info), length);
}

async function extractAndExpand(dh: Uint8Array, kemContext: Uint8Array): Promise<Bytes> {
  const prk = await labeledExtract(kemSuite, new Uint8Array(0), "eae_prk", dh);
  return labeledExpand(kemSuite, prk, "shared_secret", kemContext, N_SECRET);
}

export interface HpkeContext {
  key: Bytes;
  baseNonce: Bytes;
  exporterSecret: Bytes;
}

export async function keySchedule(sharedSecret: Uint8Array, info: Uint8Array): Promise<HpkeContext> {
  const empty = new Uint8Array(0);
  const pskIdHash = await labeledExtract(hpkeSuite, empty, "psk_id_hash", empty);
  const infoHash = await labeledExtract(hpkeSuite, empty, "info_hash", info);
  const context = concat(Uint8Array.of(0x00), pskIdHash, infoHash);
  const secret = await labeledExtract(hpkeSuite, sharedSecret, "secret", empty);
  return {
    key: await labeledExpand(hpkeSuite, secret, "key", context, N_K),
    baseNonce: await labeledExpand(hpkeSuite, secret, "base_nonce", context, N_N),
    exporterSecret: await labeledExpand(hpkeSuite, secret, "exp", context, N_SECRET),
  };
}

function nonceFor(context: HpkeContext, sequence: number): Bytes {
  const nonce = Uint8Array.from(context.baseNonce);
  const seq = i2osp(sequence, N_N);
  for (let i = 0; i < nonce.length; i++) nonce[i]! ^= seq[i]!;
  return nonce;
}

export function contextSeal(context: HpkeContext, sequence: number, aad: Uint8Array, plaintext: Uint8Array): Promise<Bytes> {
  return aesGcmSeal(context.key, nonceFor(context, sequence), aad, plaintext);
}

export function contextOpen(context: HpkeContext, sequence: number, aad: Uint8Array, ciphertext: Uint8Array): Promise<Bytes | null> {
  return aesGcmOpen(context.key, nonceFor(context, sequence), aad, ciphertext);
}

/** 非圧縮 SEC1（0x04 ‖ X ‖ Y）。 */
export function serializePublicKey(key: EcPublicKey): Bytes {
  return key.sec1;
}

export { deserializePublicKey };

/** テストにも使う: 使い捨ての鍵で包む鍵を作る。 */
export async function encap(recipient: EcPublicKey, ephemeral: CryptoKeyPair): Promise<{ sharedSecret: Bytes; enc: Bytes }> {
  const validated = await decodePublicKey(recipient.spki);
  if (validated === null) throw new Error("invalid recipient key");
  const dh = await ecdh(ephemeral.privateKey, validated);
  const enc = serializePublicKey(await publicKeyOf(ephemeral.publicKey));
  return { sharedSecret: await extractAndExpand(dh, concat(enc, serializePublicKey(validated))), enc };
}

/** 受け手の公開鍵へ、1回だけの暗号化（シーケンス番号 0）。ephemeral はテスト用に外から与えられる。 */
export async function hpkeSeal(
  recipient: EcPublicKey,
  info: Uint8Array,
  aad: Uint8Array,
  plaintext: Uint8Array,
  ephemeral?: CryptoKeyPair,
): Promise<{ enc: Bytes; ciphertext: Bytes }> {
  const { sharedSecret, enc } = await encap(recipient, ephemeral ?? (await generateEphemeral()));
  const context = await keySchedule(sharedSecret, info);
  return { enc, ciphertext: await contextSeal(context, 0, aad, plaintext) };
}

/**
 * 開く。認証に失敗したら null。
 * recipientPublic は受け手自身の公開鍵（kem_context に入る）、dh は受け手の秘密鍵で渡された公開鍵との ECDH の x 座標（32 バイト）を返す。
 */
export async function hpkeOpen(
  enc: Uint8Array,
  recipientPublic: EcPublicKey,
  dh: (peer: EcPublicKey) => Promise<Uint8Array>,
  info: Uint8Array,
  aad: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Bytes | null> {
  const ephemeral = await deserializePublicKey(enc);
  if (ephemeral === null) return null;
  let shared: Uint8Array;
  try {
    shared = await dh(ephemeral);
  } catch {
    return null;
  }
  if (shared.length !== N_SECRET) return null;
  const sharedSecret = await extractAndExpand(shared, concat(enc, serializePublicKey(recipientPublic)));
  return contextOpen(await keySchedule(sharedSecret, info), 0, aad, ciphertext);
}
