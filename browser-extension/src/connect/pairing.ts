// ペアリングの手順（Android 版・Swift の Pairing と同じ、ADR-0008）。通信に依らない純粋なロジック。A = QR を出す端末、B = QR を読む端末。
// 1. A は使い捨ての鍵 eA を作り、QR `{v, pkA, eA, token}` を出す
// 2. B は使い捨ての鍵 eB を作り、ECDH(eB, eA) から導いた鍵で `{pkB, sigB(transcript), 端末名, アカウントの HMAC}` を封じて、eB と一緒に A へ送る
// 3. A は開いて sigB を確かめ、`{sigA(transcript), 端末名, アカウントの HMAC}` を封じて返す
// 4. B は sigA を確かめ、確認用の6桁を表示する。A は本人がその6桁を入力し、一致したときだけペアを確定する
// JSON は Kotlin の Pairing の Json（既定値の項目は出さない）と同じ形。

import { b64, concat, constantTimeEquals, equalBytes, lengthPrefixed, lessBytes, randomBytes, unb64, utf8, type Bytes } from "./bytes.ts";
import { decodePublicKey, ecdh, generateEphemeral, Hkdf, hmacSha256, open, publicKeyOf, seal, sha256, verify } from "./crypto.ts";
import type { DeviceSigner } from "./chain.ts";
import { parse, Reader, serialize, type Json } from "./json.ts";

export const VERSION = 1;
export const TOKEN_BYTES = 16;
export const LIFETIME_MS = 2 * 60 * 1000;
export const MAX_ATTEMPTS = 3;
/** ログインをまだ持たない加わる側が、アカウントの HMAC に使う値（Kotlin の Pairing.ACCOUNTLESS_ID と同じ、ADR-0015）。 */
export const ACCOUNTLESS_ID = "wave-accountless-v1";
const MAX_EXTENSION_CHARS = 2048;
const LABEL = "wave-pair-v1";
const OFFER_PREFIX = "wave-pair:";

export interface Offer {
  v: number;
  pkA: string;
  eA: string;
  token: string;
}

/** B → A（中継の /pair 経由）。eB は平文、残りは封じる。 */
export interface Hello {
  v: number;
  eB: string;
  nonce: string;
  ciphertext: string;
}

/** A → B。 */
export interface Confirm {
  nonce: string;
  ciphertext: string;
}

/** 確定したペア。 */
export interface Established {
  peerPublicKey: Bytes;
  peerDeviceName: string;
  sendKey: Bytes;
  receiveKey: Bytes;
  accountKey: Bytes;
  /** 相手の拡張（A から見た B の Hello、B から見た A の Confirm）。署名で確かめた値。 */
  peerExtension: string | null;
  /** A から見て、B がログインをまだ持たない（ACCOUNTLESS_ID で照合した）。 */
  peerAccountless: boolean;
}

export type Failure = "malformed" | "bad_signature" | "expired" | "wrong_code" | "too_many_attempts" | "account_mismatch" | "already_used";

export type Result<T> = { ok: true; value: T } | { ok: false; failure: Failure };

const fail = <T>(failure: Failure): Result<T> => ({ ok: false, failure });
const ok = <T>(value: T): Result<T> => ({ ok: true, value });

export function offerToJson(o: Offer): Json {
  return { v: o.v, pkA: o.pkA, eA: o.eA, token: o.token };
}

export function helloToJson(h: Hello): Json {
  return { v: h.v, eB: h.eB, nonce: h.nonce, ciphertext: h.ciphertext };
}

export function confirmToJson(c: Confirm): Json {
  return { nonce: c.nonce, ciphertext: c.ciphertext };
}

export function decodeHello(json: Json): Hello | null {
  try {
    const r = new Reader(json);
    return { v: r.int("v"), eB: r.string("eB"), nonce: r.string("nonce"), ciphertext: r.string("ciphertext") };
  } catch {
    return null;
  }
}

export function decodeConfirm(json: Json): Confirm | null {
  try {
    const r = new Reader(json);
    return { nonce: r.string("nonce"), ciphertext: r.string("ciphertext") };
  } catch {
    return null;
  }
}

export function encodeOffer(offer: Offer): string {
  return OFFER_PREFIX + b64(utf8(serialize(offerToJson(offer))));
}

/** QR の文字（wave-pair:…）を読む。形と鍵を確かめる。 */
export async function decodeOffer(text: string): Promise<Offer | null> {
  if (!text.startsWith(OFFER_PREFIX) || text.length > 1024) return null;
  const bytes = unb64(text.slice(OFFER_PREFIX.length));
  if (bytes === null) return null;
  const json = parse(new TextDecoder("utf-8", { fatal: false }).decode(bytes));
  let offer: Offer;
  try {
    const r = new Reader(json ?? undefined);
    offer = { v: r.int("v"), pkA: r.string("pkA"), eA: r.string("eA"), token: r.string("token") };
  } catch {
    return null;
  }
  if (offer.v !== VERSION) return null;
  if ((await decodePublicKey(unb64(offer.pkA))) === null) return null;
  if ((await decodePublicKey(unb64(offer.eA))) === null) return null;
  if (unb64(offer.token)?.length !== TOKEN_BYTES) return null;
  return offer;
}

interface HelloBody {
  pkB: string;
  sigB: string;
  deviceName: string;
  accountHmac: string;
  /** Connect v2: B の家の鍵の記述（MemberKeys の JSON）。sigB の対象に入る。 */
  extension: string | null;
}

interface ConfirmBody {
  sigA: string;
  deviceName: string;
  accountHmac: string;
  /** Connect v2: 家の ID・一覧の頭・storeId・版の床（秘密を入れない）。sigA の対象に入る。 */
  extension: string | null;
}

function helloBodyText(b: HelloBody): string {
  const o: { [key: string]: Json } = { pkB: b.pkB, sigB: b.sigB, deviceName: b.deviceName, accountHmac: b.accountHmac };
  if (b.extension !== null) o["extension"] = b.extension;
  return serialize(o);
}

function confirmBodyText(b: ConfirmBody): string {
  const o: { [key: string]: Json } = { sigA: b.sigA, deviceName: b.deviceName, accountHmac: b.accountHmac };
  if (b.extension !== null) o["extension"] = b.extension;
  return serialize(o);
}

function readJson<T>(bytes: Uint8Array, read: (r: Reader) => T): T | null {
  const json = parse(new TextDecoder("utf-8", { fatal: false }).decode(bytes));
  if (json === null) return null;
  try {
    return read(new Reader(json));
  } catch {
    return null;
  }
}

/** 拡張があれば、署名の対象に拡張のハッシュを足す（無ければ v1 と同じ）。 */
async function signedTranscript(transcript: Uint8Array, ext: string | null): Promise<Bytes> {
  if (ext === null) return Uint8Array.from(transcript) as Bytes;
  return lengthPrefixed(transcript, await sha256(utf8(ext)));
}

async function handshakeKey(prk: Uint8Array, direction: string, transcript0: Uint8Array): Promise<Bytes> {
  return Hkdf.expand(prk, concat(utf8(`${LABEL} handshake ${direction}`), await sha256(transcript0)), 32);
}

interface Keys {
  sas: string;
  sendKey: Bytes;
  receiveKey: Bytes;
  accountKey: Bytes;
}

/** 確定したペアの鍵。方向は端末 ID（公開鍵の SHA-256）の大小（符号なし）で決める。 */
async function schedule(prk: Uint8Array, transcript: Uint8Array, selfKey: Uint8Array, peer: Uint8Array): Promise<Keys> {
  const hash = await sha256(transcript);
  const sasBytes = await Hkdf.expand(prk, concat(utf8(`${LABEL} sas`), hash), 8);
  // Long.remainderUnsigned と同じ（8 バイトを符号なしとして読む）
  let n = 0n;
  for (const b of sasBytes) n = (n << 8n) | BigInt(b);
  const sas = String(n % 1_000_000n).padStart(6, "0");
  const lowToHigh = await Hkdf.expand(prk, concat(utf8(`${LABEL} key low->high`), hash), 32);
  const highToLow = await Hkdf.expand(prk, concat(utf8(`${LABEL} key high->low`), hash), 32);
  const accountKey = await Hkdf.expand(prk, concat(utf8(`${LABEL} account`), hash), 32);
  const selfIsLow = lessBytes(await sha256(selfKey), await sha256(peer));
  return { sas, sendKey: selfIsLow ? lowToHigh : highToLow, receiveKey: selfIsLow ? highToLow : lowToHigh, accountKey };
}

/** 同じアカウントかを確かめる HMAC（誤操作の防止で、防御ではない）。 */
export function accountHmac(accountKey: Uint8Array, accountId: string): Promise<Bytes> {
  return hmacSha256(accountKey, utf8("wave-account-v1"), utf8(accountId));
}

/** 端末名から制御文字・書字方向の上書き文字を落とし、64 文字（UTF-16 の符号単位）までにする。 */
export function sanitizeName(name: string): string {
  let units = "";
  for (let i = 0; i < name.length; i++) {
    const c = name.charCodeAt(i);
    if (c <= 0x1f || (c >= 0x7f && c <= 0x9f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) || c === 0x200e || c === 0x200f) continue;
    units += name[i];
  }
  let trimmed = units.trim();
  if (trimmed.length > 64) trimmed = trimmed.slice(0, 64);
  return trimmed.length === 0 ? "端末" : trimmed;
}

/** A 側。QR を出してから、1回の B の Hello と、6桁の入力を扱う（この拡張では使わないが、相互運用の確かめと試験の相手に使う）。 */
export class Responder {
  private used = false;
  private attempts = 0;
  private pending: { sas: string; established: Established } | null = null;
  private createdAtMs = 0;
  private pkA!: Bytes;
  private eA!: Bytes;
  tokenHash!: Bytes;
  offer!: Offer;

  private readonly identity: DeviceSigner;
  private readonly deviceName: string;
  private readonly accountId: string;
  private readonly nowMs: () => number;
  private readonly confirmAccountId: string;
  private readonly confirmExtension: string | null;
  private readonly ephemeral: CryptoKeyPair;
  readonly token: Bytes;
  private readonly acceptAccountless: boolean;

  private constructor(
    identity: DeviceSigner,
    deviceName: string,
    accountId: string,
    nowMs: () => number,
    confirmAccountId: string,
    confirmExtension: string | null,
    ephemeral: CryptoKeyPair,
    token: Bytes,
    acceptAccountless: boolean,
  ) {
    this.identity = identity;
    this.deviceName = deviceName;
    this.accountId = accountId;
    this.nowMs = nowMs;
    this.confirmAccountId = confirmAccountId;
    this.confirmExtension = confirmExtension;
    this.ephemeral = ephemeral;
    this.token = token;
    this.acceptAccountless = acceptAccountless;
  }

  static async create(args: {
    identity: DeviceSigner;
    deviceName: string;
    accountId: string;
    nowMs: () => number;
    confirmExtension?: string | null;
    confirmAccountId?: string;
    ephemeral?: CryptoKeyPair;
    token?: Bytes;
    acceptAccountless?: boolean;
  }): Promise<Responder> {
    const r = new Responder(
      args.identity,
      args.deviceName,
      args.accountId,
      args.nowMs,
      args.confirmAccountId ?? args.accountId,
      args.confirmExtension ?? null,
      args.ephemeral ?? (await generateEphemeral()),
      args.token ?? randomBytes(TOKEN_BYTES),
      args.acceptAccountless ?? true,
    );
    r.pkA = args.identity.identityPub;
    r.eA = (await publicKeyOf(r.ephemeral.publicKey)).spki;
    r.createdAtMs = args.nowMs();
    r.offer = { v: VERSION, pkA: b64(r.pkA), eA: b64(r.eA), token: b64(r.token) };
    r.tokenHash = await sha256(r.token);
    return r;
  }

  private expired(): boolean {
    return this.nowMs() - this.createdAtMs > LIFETIME_MS;
  }

  /** B の Hello を受けて Confirm を返す。token は最初の正しい Hello で消費する。 */
  async onHello(hello: Hello): Promise<Result<Confirm>> {
    if (this.expired()) return fail("expired");
    if (this.used) return fail("already_used");
    if (hello.v !== VERSION) return fail("malformed");
    const eBBytes = unb64(hello.eB);
    const eB = await decodePublicKey(eBBytes);
    if (eBBytes === null || eB === null) return fail("malformed");
    let shared: Bytes;
    try {
      shared = await ecdh(this.ephemeral.privateKey, eB);
    } catch {
      return fail("malformed");
    }
    const transcript0 = lengthPrefixed(utf8(LABEL), this.pkA, this.eA, this.token, eBBytes);
    const prk = await Hkdf.extract(this.token, shared);
    const nonce = unb64(hello.nonce);
    const ciphertext = unb64(hello.ciphertext);
    if (nonce === null || ciphertext === null) return fail("malformed");
    const plain = await open(await handshakeKey(prk, "b->a", transcript0), nonce, transcript0, ciphertext);
    const body =
      plain &&
      readJson<HelloBody>(plain, (r) => ({
        pkB: r.string("pkB"),
        sigB: r.string("sigB"),
        deviceName: r.string("deviceName"),
        accountHmac: r.string("accountHmac"),
        extension: r.optString("extension"),
      }));
    if (!body) return fail("malformed");
    const pkBBytes = unb64(body.pkB);
    const pkB = await decodePublicKey(pkBBytes);
    if (pkBBytes === null || pkB === null) return fail("malformed");
    // 自分自身とのペアは受けない（方向の鍵が崩れる）
    if (equalBytes(pkBBytes, this.pkA)) return fail("malformed");
    const transcript = lengthPrefixed(transcript0, pkBBytes);
    if (body.extension !== null && body.extension.length > MAX_EXTENSION_CHARS) return fail("malformed");
    const sigB = unb64(body.sigB);
    if (sigB === null) return fail("malformed");
    if (!(await verify(pkB, `${LABEL}-b`, await signedTranscript(transcript, body.extension), sigB))) return fail("bad_signature");
    const keys = await schedule(prk, transcript, this.pkA, pkBBytes);
    const peerHmac = unb64(body.accountHmac) ?? new Uint8Array(0);
    let accountless: boolean;
    if (constantTimeEquals(await accountHmac(keys.accountKey, this.accountId), peerHmac)) {
      accountless = false;
    } else if (this.acceptAccountless && constantTimeEquals(await accountHmac(keys.accountKey, ACCOUNTLESS_ID), peerHmac)) {
      accountless = true;
    } else {
      return fail("account_mismatch");
    }
    this.used = true;
    this.pending = {
      sas: keys.sas,
      established: {
        peerPublicKey: pkBBytes,
        peerDeviceName: sanitizeName(body.deviceName),
        sendKey: keys.sendKey,
        receiveKey: keys.receiveKey,
        accountKey: keys.accountKey,
        peerExtension: body.extension,
        peerAccountless: accountless,
      },
    };
    const confirm: ConfirmBody = {
      sigA: b64(await this.identity.sign(`${LABEL}-a`, await signedTranscript(transcript, this.confirmExtension))),
      deviceName: this.deviceName,
      accountHmac: b64(await accountHmac(keys.accountKey, this.confirmAccountId)),
      extension: this.confirmExtension,
    };
    const sealed = await seal(await handshakeKey(prk, "a->b", transcript0), transcript0, utf8(confirmBodyText(confirm)));
    return ok({ nonce: b64(sealed.nonce), ciphertext: b64(sealed.ciphertext) });
  }

  /** 本人が入力した6桁（B の画面の数字）。一致したらペアを確定する。3回まで。 */
  onCodeEntered(code: string): Result<Established> {
    if (this.expired()) return fail("expired");
    if (this.attempts >= MAX_ATTEMPTS) return fail("too_many_attempts");
    const current = this.pending;
    if (current === null) return fail("malformed");
    this.attempts++;
    if (!constantTimeEquals(utf8(code.trim()), utf8(current.sas))) {
      if (this.attempts >= MAX_ATTEMPTS) this.pending = null;
      return fail(this.attempts >= MAX_ATTEMPTS ? "too_many_attempts" : "wrong_code");
    }
    this.pending = null;
    return ok(current.established);
  }
}

/** B 側。QR を読んで Hello を作り、A の Confirm で6桁とペアを得る（この拡張が加わるときに使う）。 */
export class Initiator {
  private pkB!: Bytes;
  private eB!: Bytes;
  private pkA!: Bytes;
  private transcript0!: Bytes;
  private transcript!: Bytes;
  private prk!: Bytes;
  private keys!: Keys;

  private readonly identity: DeviceSigner;
  private readonly deviceName: string;
  /** null はログインをまだ持たない（ACCOUNTLESS_ID で照合を頼み、A のアカウントは確かめない）。 */
  private readonly accountId: string | null;
  private readonly helloExtension: string | null;

  private constructor(identity: DeviceSigner, deviceName: string, accountId: string | null, helloExtension: string | null) {
    this.identity = identity;
    this.deviceName = deviceName;
    this.accountId = accountId;
    this.helloExtension = helloExtension;
  }

  /** offer は decodeOffer を通したもの（通していなければ投げる）。 */
  static async create(args: {
    offer: Offer;
    identity: DeviceSigner;
    deviceName: string;
    accountId: string | null;
    helloExtension?: string | null;
    ephemeral?: CryptoKeyPair;
  }): Promise<Initiator> {
    const i = new Initiator(args.identity, args.deviceName, args.accountId, args.helloExtension ?? null);
    const ephemeral = args.ephemeral ?? (await generateEphemeral());
    i.pkB = args.identity.identityPub;
    i.eB = (await publicKeyOf(ephemeral.publicKey)).spki;
    const a = unb64(args.offer.pkA);
    if (a === null || (await decodePublicKey(a)) === null) throw new Error("offer は decodeOffer を通すこと");
    i.pkA = a;
    const eABytes = unb64(args.offer.eA);
    const eAKey = await decodePublicKey(eABytes);
    if (eABytes === null || eAKey === null) throw new Error("offer は decodeOffer を通すこと");
    const token = unb64(args.offer.token);
    if (token === null || token.length !== TOKEN_BYTES) throw new Error("offer は decodeOffer を通すこと");
    i.transcript0 = lengthPrefixed(utf8(LABEL), i.pkA, eABytes, token, i.eB);
    i.transcript = lengthPrefixed(i.transcript0, i.pkB);
    i.prk = await Hkdf.extract(token, await ecdh(ephemeral.privateKey, eAKey));
    i.keys = await schedule(i.prk, i.transcript, i.pkB, i.pkA);
    return i;
  }

  async hello(): Promise<Hello> {
    const body: HelloBody = {
      pkB: b64(this.pkB),
      sigB: b64(await this.identity.sign(`${LABEL}-b`, await signedTranscript(this.transcript, this.helloExtension))),
      deviceName: this.deviceName,
      accountHmac: b64(await accountHmac(this.keys.accountKey, this.accountId ?? ACCOUNTLESS_ID)),
      extension: this.helloExtension,
    };
    const sealed = await seal(await handshakeKey(this.prk, "b->a", this.transcript0), this.transcript0, utf8(helloBodyText(body)));
    return { v: VERSION, eB: b64(this.eB), nonce: b64(sealed.nonce), ciphertext: b64(sealed.ciphertext) };
  }

  /** A の Confirm を確かめ、（表示する6桁, 確定したときに保存するペア）を返す。 */
  async onConfirm(confirm: Confirm): Promise<Result<{ code: string; pair: Established }>> {
    const nonce = unb64(confirm.nonce);
    const ciphertext = unb64(confirm.ciphertext);
    if (nonce === null || ciphertext === null) return fail("malformed");
    const plain = await open(await handshakeKey(this.prk, "a->b", this.transcript0), nonce, this.transcript0, ciphertext);
    const body =
      plain &&
      readJson<ConfirmBody>(plain, (r) => ({
        sigA: r.string("sigA"),
        deviceName: r.string("deviceName"),
        accountHmac: r.string("accountHmac"),
        extension: r.optString("extension"),
      }));
    if (!body) return fail("malformed");
    const pkAKey = await decodePublicKey(this.pkA);
    if (pkAKey === null) return fail("malformed");
    if (body.extension !== null && body.extension.length > MAX_EXTENSION_CHARS) return fail("malformed");
    const sigA = unb64(body.sigA);
    if (sigA === null) return fail("malformed");
    if (!(await verify(pkAKey, `${LABEL}-a`, await signedTranscript(this.transcript, body.extension), sigA))) return fail("bad_signature");
    if (this.accountId !== null && !constantTimeEquals(await accountHmac(this.keys.accountKey, this.accountId), unb64(body.accountHmac) ?? new Uint8Array(0))) {
      return fail("account_mismatch");
    }
    return ok({
      code: this.keys.sas,
      pair: {
        peerPublicKey: this.pkA,
        peerDeviceName: sanitizeName(body.deviceName),
        sendKey: this.keys.sendKey,
        receiveKey: this.keys.receiveKey,
        accountKey: this.keys.accountKey,
        peerExtension: body.extension,
        peerAccountless: false,
      },
    });
  }
}
