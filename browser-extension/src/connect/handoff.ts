// スナップショットの文字列の形（Android 版の HandoffCodec・Swift の HandoffCodec と同じ）。

import { isObject, integer, int32, parse, serialize, type Json } from "./json.ts";
import { readSnapshot, snapshotToJson, type HandoffRef, type HandoffSnapshot } from "./models.ts";

export const SCHEMA_VERSION = 1;
/** 暗号化する前のバイト数の上限。 */
export const MAX_BYTES = 512 * 1024;
export const MAX_PLAYED_ITEMS = 20;
export const MAX_UPCOMING_ITEMS = 300;
const MAX_TEXT = 500;
const MAX_CONTINUATION = 4096;
const MAX_ARTISTS = 20;
const MAX_TIME_MS = 24 * 3600 * 1000;
const THUMBNAIL_HOSTS = [".googleusercontent.com", ".ggpht.com", ".ytimg.com"];
const AUDIO_PREFIX = "ext:";

export type HandoffDecodeResult =
  | { kind: "ok"; snapshot: HandoffSnapshot }
  /** 形式のバージョンが違う（相手を更新する必要がある）。 */
  | { kind: "unsupported_version"; version: number | null }
  /** 読めない・上限を超える・矛盾している。理由は種類だけ。 */
  | { kind: "rejected"; reason: string };

export function encodeSnapshot(snapshot: HandoffSnapshot): string {
  return serialize(snapshotToJson(snapshot));
}

function utf8Length(text: string): number {
  return new TextEncoder().encode(text).length;
}

/** 受け取った文字列を読み、上限と形を検証する。サムネイルのホストが合わないものは、拒否せずに null にする。 */
export function decodeSnapshot(text: string, expectedHandoffId: string): HandoffDecodeResult {
  if (utf8Length(text) > MAX_BYTES) return { kind: "rejected", reason: "too_large" };
  const root = parse(text);
  if (!isObject(root)) return { kind: "rejected", reason: "unreadable" };
  let version: number | null = null;
  const v = root["schemaVersion"];
  if (v !== undefined && v !== null) {
    try {
      version = int32(integer(v, "schemaVersion"), "schemaVersion");
    } catch {
      return { kind: "rejected", reason: "unreadable" };
    }
  }
  if (version !== SCHEMA_VERSION) return { kind: "unsupported_version", version };
  let snapshot: HandoffSnapshot;
  try {
    snapshot = readSnapshot(root as Json);
  } catch {
    return { kind: "rejected", reason: "unreadable" };
  }
  return validateSnapshot(snapshot, expectedHandoffId);
}

function length16(text: string): number {
  return text.length;
}

function scalars(text: string): number {
  let n = 0;
  for (const _ of text) n++;
  return n;
}

export function validateSnapshot(snapshot: HandoffSnapshot, expectedHandoffId: string): HandoffDecodeResult {
  const reject = (reason: string): HandoffDecodeResult => ({ kind: "rejected", reason });
  if (snapshot.handoffId !== expectedHandoffId || !isUuid(snapshot.handoffId)) return reject("handoff_id");
  if (snapshot.items.length === 0 || snapshot.items.length > MAX_PLAYED_ITEMS + 1 + MAX_UPCOMING_ITEMS) return reject("item_count");
  const ids = snapshot.items.map((i) => i.itemId);
  if (new Set(ids).size !== ids.length || ids.some((id) => !isUuid(id))) return reject("item_id");
  const currentIndex = ids.indexOf(snapshot.currentItemId);
  if (currentIndex < 0) return reject("current_item");
  if (currentIndex > MAX_PLAYED_ITEMS || snapshot.items.length - 1 - currentIndex > MAX_UPCOMING_ITEMS) return reject("item_window");
  if (snapshot.listenedMs < 0 || snapshot.positionMs < 0) return reject("negative_time");
  for (const item of snapshot.items) {
    if (!isItemVideoId(item.videoId)) return reject("video_id");
    if (length16(item.title) === 0 || length16(item.title) > MAX_TEXT) return reject("title");
    if (item.artists.length > MAX_ARTISTS || item.artists.some((a) => !validRef(a))) return reject("artist");
    if (item.album !== null && !validRef(item.album)) return reject("album");
    if (item.durationSeconds !== null && (item.durationSeconds < 0 || item.durationSeconds > 24 * 3600)) return reject("duration");
  }
  const source = snapshot.source;
  if (source?.type === "radio" && !isVideoId(source.seedVideoId)) return reject("source");
  if (source?.type === "playlist" && !isPlaylistId(source.playlistId)) return reject("source");
  if (snapshot.radioPlaylistId !== null && !isPlaylistId(snapshot.radioPlaylistId)) return reject("radio_playlist");
  const continuation = snapshot.continuation;
  if (continuation !== null && (length16(continuation) > MAX_CONTINUATION || hasIsoControl(continuation))) return reject("continuation");
  const current = snapshot.items[currentIndex]!;
  const limit = current.durationSeconds !== null ? current.durationSeconds * 1000 : MAX_TIME_MS;
  return {
    kind: "ok",
    snapshot: {
      ...snapshot,
      items: snapshot.items.map((i) => ({ ...i, thumbnailUrl: i.thumbnailUrl !== null && allowedThumbnail(i.thumbnailUrl) ? i.thumbnailUrl : null })),
      // 位置と聴いた時間を曲の長さ以下（長さが無ければ24時間以下）に切り詰める（拒否はしない）
      positionMs: Math.min(snapshot.positionMs, limit),
      listenedMs: Math.min(snapshot.listenedMs, limit),
    },
  };
}

/** 送る側の切り詰め: 今の曲より前は MAX_PLAYED_ITEMS 件、後ろは MAX_UPCOMING_ITEMS 件まで。 */
export function windowItems<T>(items: T[], currentIndex: number): { items: T[]; currentIndex: number; droppedTail: boolean } {
  const start = Math.max(currentIndex - MAX_PLAYED_ITEMS, 0);
  const end = Math.min(currentIndex + 1 + MAX_UPCOMING_ITEMS, items.length);
  return { items: items.slice(start, end), currentIndex: currentIndex - start, droppedTail: end < items.length };
}

function validRef(ref: HandoffRef): boolean {
  return length16(ref.name) > 0 && length16(ref.name) <= MAX_TEXT && (ref.browseId === null || isBrowseId(ref.browseId));
}

/** Java の Character.isISOControl と同じ（U+0000〜U+001F・U+007F〜U+009F）。 */
function hasIsoControl(text: string): boolean {
  return /[\u0000-\u001f\u007f-\u009f]/.test(text);
}

/** Unicode の空白（Swift の properties.isWhitespace と同じ範囲のうち、ASCII と U+0085・U+00A0 を除くもの）。 */
function isUnicodeWhitespace(c: number): boolean {
  return c === 0x1680 || (c >= 0x2000 && c <= 0x200a) || c === 0x2028 || c === 0x2029 || c === 0x202f || c === 0x205f || c === 0x3000;
}

/** https・既知の画像ホストだけ（ポート・userinfo の無いもの）。 */
export function allowedThumbnail(url: string): boolean {
  if (!url.startsWith("https://") || length16(url) > 2048) return false;
  // バックスラッシュ・空白・制御文字は、URL パーサによって区切りや別のホストとして解釈されるので、どこにあっても拒否する
  for (const ch of url) {
    const c = ch.codePointAt(0)!;
    if (c === 0x5c || c <= 0x20 || (c >= 0x7f && c <= 0xa0) || isUnicodeWhitespace(c)) return false;
  }
  let authority = url.slice("https://".length);
  for (const separator of ["/", "?", "#"]) {
    const at = authority.indexOf(separator);
    if (at >= 0) authority = authority.slice(0, at);
  }
  if (!/^[a-z0-9.-]{1,253}$/.test(authority)) return false;
  return THUMBNAIL_HOSTS.some((h) => authority.endsWith(h));
}

const ID = /^[A-Za-z0-9_-]+$/;

/** `\A[A-Za-z0-9_-]{11}\z` */
export function isVideoId(s: string): boolean {
  return s.length === 11 && ID.test(s);
}

/** キューの曲の ID。YouTube の曲か、RSS のエピソード（`ext:` + https の音声の URL。ADR-0012）。 */
export function isItemVideoId(s: string): boolean {
  if (isVideoId(s)) return true;
  if (!s.startsWith(AUDIO_PREFIX)) return false;
  const url = s.slice(AUDIO_PREFIX.length);
  return url.toLowerCase().startsWith("https://") && length16(url) >= 12 && length16(url) <= 4096 && !/\s/.test(url) && !hasIsoControl(s);
}

export function isBrowseId(s: string): boolean {
  const n = scalars(s);
  return n >= 2 && n <= 64 && ID.test(s);
}

export function isPlaylistId(s: string): boolean {
  const n = scalars(s);
  return n >= 2 && n <= 128 && ID.test(s);
}

export function isUuid(s: string): boolean {
  return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(s);
}
