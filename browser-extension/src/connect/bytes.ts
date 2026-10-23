// バイト列の小さな道具（base64url・UTF-8・長さ付きの連結）。Swift の B64・WaveCrypto.lengthPrefixed と同じ形。

export type Bytes = Uint8Array<ArrayBuffer>;

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: false });

/** Kotlin の encodeToByteArray と同じ UTF-8。 */
export function utf8(text: string): Bytes {
  return encoder.encode(text) as Bytes;
}

export function fromUtf8(bytes: Uint8Array): string {
  return decoder.decode(bytes);
}

export function concat(...parts: Uint8Array[]): Bytes {
  let size = 0;
  for (const p of parts) size += p.length;
  const out = new Uint8Array(size);
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

/** 長さ付きで連結する（ビッグエンディアンの4バイト長 ‖ 中身 を並べる）。 */
export function lengthPrefixed(...parts: Uint8Array[]): Bytes {
  let size = 0;
  for (const p of parts) size += 4 + p.length;
  const out = new Uint8Array(size);
  const view = new DataView(out.buffer);
  let at = 0;
  for (const p of parts) {
    view.setUint32(at, p.length);
    out.set(p, at + 4);
    at += 4 + p.length;
  }
  return out;
}

export function equalBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** 定数時間の比較。 */
export function constantTimeEquals(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i]! ^ b[i]!;
  return diff === 0;
}

/** 符号なしの辞書順で a が b より前か。 */
export function lessBytes(a: Uint8Array, b: Uint8Array): boolean {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) {
    if (a[i] !== b[i]) return a[i]! < b[i]!;
  }
  return a.length < b.length;
}

export function randomBytes(size: number): Bytes {
  const out = new Uint8Array(size);
  crypto.getRandomValues(out);
  return out;
}

const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const lookup = new Int16Array(128).fill(-1);
for (let i = 0; i < alphabet.length; i++) lookup[alphabet.charCodeAt(i)] = i;

/** base64url（パディングなし）。 */
export function b64(bytes: Uint8Array): string {
  let out = "";
  let i = 0;
  for (; i + 2 < bytes.length; i += 3) {
    const n = (bytes[i]! << 16) | (bytes[i + 1]! << 8) | bytes[i + 2]!;
    out += alphabet[n >> 18]! + alphabet[(n >> 12) & 63]! + alphabet[(n >> 6) & 63]! + alphabet[n & 63]!;
  }
  const rest = bytes.length - i;
  if (rest === 1) {
    const n = bytes[i]! << 16;
    out += alphabet[n >> 18]! + alphabet[(n >> 12) & 63]!;
  } else if (rest === 2) {
    const n = (bytes[i]! << 16) | (bytes[i + 1]! << 8);
    out += alphabet[n >> 18]! + alphabet[(n >> 12) & 63]! + alphabet[(n >> 6) & 63]!;
  }
  return out;
}

/** 読めなければ null。Java の URL デコーダと同じく、パディングの有無どちらも受ける（Swift の B64.decode と同じ規則）。 */
export function unb64(text: string | null | undefined): Bytes | null {
  if (text == null) return null;
  let end = text.length;
  let padding = 0;
  while (end > 0 && text.charCodeAt(end - 1) === 61 && padding < 3) {
    end--;
    padding++;
  }
  if (padding > 0 && (padding > 2 || text.length % 4 !== 0)) return null;
  if (end % 4 === 1) return null;
  const out = new Uint8Array(Math.floor((end * 6) / 8));
  let buffer = 0;
  let bits = 0;
  let at = 0;
  for (let i = 0; i < end; i++) {
    const c = text.charCodeAt(i);
    const v = c < 128 ? lookup[c]! : -1;
    if (v < 0) return null;
    buffer = (buffer << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[at++] = (buffer >> bits) & 0xff;
    }
  }
  // 端のビットが 0 でない形（同じバイト列に別の文字列が当たる）は読まない（どの端末も書かない形）
  if (bits > 0 && (buffer & ((1 << bits) - 1)) !== 0) return null;
  return out;
}

export function hex(bytes: Uint8Array): string {
  let out = "";
  for (const b of bytes) out += b.toString(16).padStart(2, "0");
  return out;
}

export function fromHex(text: string): Bytes {
  const out = new Uint8Array(text.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(text.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** UUID（小文字、v4）。 */
export function uuid(): string {
  return crypto.randomUUID().toLowerCase();
}
