// 暗号の部品（Swift の WaveCrypto・Hkdf と同じ形）。WebCrypto の ECDH・ECDSA・AES-GCM・HMAC・SHA-256 だけを使い、暗号そのものは書かない。
// 署名は JCA と同じ DER 形式（WebCrypto の r‖s を詰め替える）、ECDH の共有秘密は x 座標そのもの（ハッシュしない）。曲線は P-256。

import { b64, concat, equalBytes, lengthPrefixed, randomBytes, utf8, type Bytes } from "./bytes.ts";

const subtle = globalThis.crypto.subtle;
const P256 = { name: "ECDSA", namedCurve: "P-256" } as const;
const ECDH = { name: "ECDH", namedCurve: "P-256" } as const;

/** P-256 の SubjectPublicKeyInfo の頭（非圧縮の点 65 バイトが続く）。 */
const SPKI_PREFIX = Uint8Array.from([
  0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07,
  0x03, 0x42, 0x00,
]);

/** 検証済みの P-256 の公開鍵。送る形（SPKI、正規形）と非圧縮の点を持つ。作るのは decodePublicKey・deserializePublicKey だけ。 */
export class EcPublicKey {
  private verifyKey?: Promise<CryptoKey>;
  private agreeKey?: Promise<CryptoKey>;

  /** 送る形（SPKI）。 */
  readonly spki: Bytes;
  /** 非圧縮 SEC1（0x04 ‖ X ‖ Y）。 */
  readonly sec1: Bytes;

  constructor(spki: Bytes, sec1: Bytes) {
    this.spki = spki;
    this.sec1 = sec1;
  }

  equals(other: EcPublicKey): boolean {
    return equalBytes(this.spki, other.spki);
  }

  forVerify(): Promise<CryptoKey> {
    this.verifyKey ??= subtle.importKey("spki", this.spki, P256, false, ["verify"]);
    return this.verifyKey;
  }

  forAgreement(): Promise<CryptoKey> {
    this.agreeKey ??= subtle.importKey("spki", this.spki, ECDH, false, []);
    return this.agreeKey;
  }
}

export function spkiOfSec1(sec1: Uint8Array): Bytes {
  return concat(SPKI_PREFIX, sec1);
}

/**
 * 受け取った公開鍵を読む。P-256 で、正規形で、曲線上の点のときだけ（不正な曲線の点を使った攻撃を防ぐ）。
 * 曲線上の点かは WebCrypto の読み込みが確かめる。圧縮点などの別の形は、読み直したバイト列と比べて拒む。
 */
export async function decodePublicKey(encoded: Uint8Array | null): Promise<EcPublicKey | null> {
  if (encoded === null || encoded.length !== SPKI_PREFIX.length + 65) return null;
  if (!equalBytes(encoded.subarray(0, SPKI_PREFIX.length), SPKI_PREFIX) || encoded[SPKI_PREFIX.length] !== 0x04) return null;
  const spki = Uint8Array.from(encoded) as Bytes;
  try {
    const key = await subtle.importKey("spki", spki, P256, true, ["verify"]);
    const again = new Uint8Array(await subtle.exportKey("spki", key));
    if (!equalBytes(again, spki)) return null;
  } catch {
    return null;
  }
  return new EcPublicKey(spki, spki.slice(SPKI_PREFIX.length) as Bytes);
}

/** 非圧縮 SEC1（65 バイト）を読む。長さ・形・曲線上の点を確かめる。 */
export async function deserializePublicKey(encoded: Uint8Array | null): Promise<EcPublicKey | null> {
  if (encoded === null || encoded.length !== 65 || encoded[0] !== 0x04) return null;
  return decodePublicKey(spkiOfSec1(encoded));
}

export async function publicKeyOf(key: CryptoKey): Promise<EcPublicKey> {
  const spki = new Uint8Array(await subtle.exportKey("spki", key)) as Bytes;
  const decoded = await decodePublicKey(spki);
  if (decoded === null) throw new Error("not a P-256 key");
  return decoded;
}

export async function sha256(...parts: Uint8Array[]): Promise<Bytes> {
  return new Uint8Array(await subtle.digest("SHA-256", parts.length === 1 ? (parts[0] as Bytes) : concat(...parts))) as Bytes;
}

/** 端末 ID（署名公開鍵の送る形の SHA-256 を base64url で）。 */
export async function deviceId(encodedPublicKey: Uint8Array): Promise<string> {
  return b64(await sha256(encodedPublicKey));
}

export async function hmacSha256(key: Uint8Array, ...parts: Uint8Array[]): Promise<Bytes> {
  // HMAC の鍵は空にできない（WebCrypto）。HKDF の salt が空のときは呼ぶ側が 0 埋めにする
  const k = await subtle.importKey("raw", key as Bytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await subtle.sign("HMAC", k, concat(...parts))) as Bytes;
}

/** HKDF（RFC 5869）、HMAC-SHA256。 */
export const Hkdf = {
  async extract(salt: Uint8Array, ikm: Uint8Array): Promise<Bytes> {
    return hmacSha256(salt.length === 0 ? new Uint8Array(32) : salt, ikm);
  },

  async expand(prk: Uint8Array, info: Uint8Array, length: number): Promise<Bytes> {
    if (length < 1 || length > 255 * 32) throw new Error("length");
    const out = new Uint8Array(Math.ceil(length / 32) * 32);
    let previous: Uint8Array = new Uint8Array(0);
    for (let i = 0; i * 32 < length; i++) {
      previous = await hmacSha256(prk, previous, info, Uint8Array.of(i + 1));
      out.set(previous, i * 32);
    }
    return out.slice(0, length) as Bytes;
  },
};

/** ECDH の共有秘密（x 座標そのもの、32 バイト）。 */
export async function ecdh(privateKey: CryptoKey, publicKey: EcPublicKey): Promise<Bytes> {
  return new Uint8Array(await subtle.deriveBits({ name: "ECDH", public: await publicKey.forAgreement() }, privateKey, 256)) as Bytes;
}

/** 署名の対象: 用途ラベル ‖ メッセージを長さ付きで。 */
function labeled(label: string, message: Uint8Array): Bytes {
  return lengthPrefixed(utf8(label), message);
}

/** 用途ラベルを前置して署名する（DER 形式）。 */
export async function sign(privateKey: CryptoKey, label: string, message: Uint8Array): Promise<Bytes> {
  const raw = new Uint8Array(await subtle.sign({ name: "ECDSA", hash: "SHA-256" }, privateKey, labeled(label, message)));
  return rawToDer(raw);
}

export async function verify(publicKey: EcPublicKey, label: string, message: Uint8Array, signature: Uint8Array): Promise<boolean> {
  const raw = derToRaw(signature);
  if (raw === null) return false;
  try {
    return await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, await publicKey.forVerify(), raw, labeled(label, message));
  } catch {
    return false;
  }
}

/** WebCrypto の r‖s（64 バイト）を DER の SEQUENCE { INTEGER r, INTEGER s } にする。 */
export function rawToDer(raw: Uint8Array): Bytes {
  const int = (bytes: Uint8Array): Uint8Array => {
    let start = 0;
    while (start < bytes.length - 1 && bytes[start] === 0) start++;
    const body = bytes.subarray(start);
    return body[0]! & 0x80 ? concat(Uint8Array.of(0x02, body.length + 1, 0), body) : concat(Uint8Array.of(0x02, body.length), body);
  };
  const r = int(raw.subarray(0, 32));
  const s = int(raw.subarray(32, 64));
  return concat(Uint8Array.of(0x30, r.length + s.length), r, s);
}

/** DER の署名を r‖s へ。形が崩れていれば null。 */
export function derToRaw(der: Uint8Array): Bytes | null {
  if (der.length < 8 || der.length > 72 || der[0] !== 0x30 || der[1] !== der.length - 2) return null;
  const out = new Uint8Array(64);
  let at = 2;
  for (let part = 0; part < 2; part++) {
    if (der[at] !== 0x02) return null;
    const len = der[at + 1]!;
    if (len < 1 || len > 33 || at + 2 + len > der.length) return null;
    let body = der.subarray(at + 2, at + 2 + len);
    // 正規形: 余分な 0 の頭を許さない、負の数を許さない
    if (body[0]! & 0x80) return null;
    if (body.length > 1 && body[0] === 0 && !(body[1]! & 0x80)) return null;
    if (body.length === 33) {
      if (body[0] !== 0) return null;
      body = body.subarray(1);
    }
    out.set(body, part * 32 + 32 - body.length);
    at += 2 + len;
  }
  if (at !== der.length) return null;
  return out;
}

/** AES-GCM で封じる（鍵は 16 か 32 バイト）。暗号文は JCA と同じく末尾に 16 バイトのタグが付く。 */
export async function aesGcmSeal(key: Uint8Array, nonce: Uint8Array, aad: Uint8Array, plaintext: Uint8Array): Promise<Bytes> {
  const k = await subtle.importKey("raw", key as Bytes, "AES-GCM", false, ["encrypt"]);
  return new Uint8Array(
    await subtle.encrypt({ name: "AES-GCM", iv: nonce as Bytes, additionalData: aad as Bytes, tagLength: 128 }, k, plaintext as Bytes),
  ) as Bytes;
}

/** 認証に失敗したら null。 */
export async function aesGcmOpen(key: Uint8Array, nonce: Uint8Array, aad: Uint8Array, ciphertext: Uint8Array): Promise<Bytes | null> {
  if (ciphertext.length < 16) return null;
  try {
    const k = await subtle.importKey("raw", key as Bytes, "AES-GCM", false, ["decrypt"]);
    return new Uint8Array(
      await subtle.decrypt({ name: "AES-GCM", iv: nonce as Bytes, additionalData: aad as Bytes, tagLength: 128 }, k, ciphertext as Bytes),
    ) as Bytes;
  } catch {
    return null;
  }
}

/** AES-256-GCM で封じる。nonce は毎回 96 ビットの乱数。 */
export async function seal(key: Uint8Array, aad: Uint8Array, plaintext: Uint8Array): Promise<{ nonce: Bytes; ciphertext: Bytes }> {
  if (key.length !== 32) throw new Error("key は 32 バイト");
  const nonce = randomBytes(12);
  return { nonce, ciphertext: await aesGcmSeal(key, nonce, aad, plaintext) };
}

export async function open(key: Uint8Array, nonce: Uint8Array, aad: Uint8Array, ciphertext: Uint8Array): Promise<Bytes | null> {
  if (key.length !== 32 || nonce.length !== 12) return null;
  return aesGcmOpen(key, nonce, aad, ciphertext);
}

/** 使い捨ての ECDH の鍵（ペアリング・HPKE）。メモリだけに置く。 */
export async function generateEphemeral(): Promise<CryptoKeyPair> {
  return (await subtle.generateKey(ECDH, false, ["deriveBits"])) as CryptoKeyPair;
}

/** 身元の署名鍵。秘密鍵は書き出せない形で作る（IndexedDB には CryptoKey のまま置く）。 */
export async function generateSigningKey(): Promise<CryptoKeyPair> {
  return (await subtle.generateKey(P256, false, ["sign", "verify"])) as CryptoKeyPair;
}

/** 受け取りの鍵（kx）。秘密鍵は書き出せない形で作る。 */
export async function generateKxKey(): Promise<CryptoKeyPair> {
  return (await subtle.generateKey(ECDH, false, ["deriveBits"])) as CryptoKeyPair;
}

/** PKCS#8 の秘密鍵を読む（テストと、他の端末の鍵の形の確かめ用）。公開鍵が入っていない形は読めない環境がある。 */
export async function importPkcs8(pkcs8: Uint8Array, usage: "sign" | "ecdh"): Promise<CryptoKeyPair> {
  const algorithm = usage === "sign" ? P256 : ECDH;
  const usages: KeyUsage[] = usage === "sign" ? ["sign"] : ["deriveBits"];
  const privateKey = await subtle.importKey("pkcs8", pkcs8 as Bytes, algorithm, true, usages);
  const jwk = await subtle.exportKey("jwk", privateKey);
  delete jwk.d;
  jwk.key_ops = usage === "sign" ? ["verify"] : [];
  const publicKey = await subtle.importKey("jwk", jwk, algorithm, true, usage === "sign" ? ["verify"] : []);
  return { privateKey, publicKey };
}
