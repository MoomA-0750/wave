// ページのキュー（YouTube Music の再生キュー）と、連携のキュー（HandoffSnapshot）の行き来。
// YouTube Music のキューには wave の項目の ID（itemId）が無いので、ここで振って覚えておく。ページが同じ曲の並びを知らせ直したら同じ ID を使い、
// 取り込んだキュー・遠隔で足した曲は、届いた ID のままページの並びに当てる（Swift の ConnectQueue に当たる）。

import { uuid } from "./bytes.ts";
import { decodeSnapshot, encodeSnapshot, isBrowseId, isItemVideoId, isPlaylistId, validateSnapshot, windowItems } from "./handoff.ts";
import { decodeWith } from "./json.ts";
import { readSnapshot, type CursorBody, type HandoffItem, type HandoffOrigin, type HandoffRef, type HandoffSnapshot, type HandoffSource } from "./models.ts";

/** キューの doc の handoffId（Android の V2_SNAPSHOT_ID と同じ。中身の同じキューは同じ文字列になる）。 */
export const SNAPSHOT_ID = "00000000-0000-4000-8000-000000000000";

/** ページが知らせる曲（YouTube Music の再生キューの1曲）。 */
export interface PageTrack {
  videoId: string;
  title: string;
  artists?: { name: string; browseId?: string | null }[];
  album?: { name: string; browseId?: string | null } | null;
  durationMs?: number | null;
  thumbnailUrl?: string | null;
  isLive?: boolean;
  /** 自分で足した曲は "user"、自動再生（ラジオ）の曲は "suggestion"。無ければ "source"。 */
  origin?: HandoffOrigin;
}

/** ページへ渡す曲（連携の項目の ID 付き）。 */
export interface IdentifiedTrack extends PageTrack {
  itemId: string;
}

/** UTF-16 の符号単位で max までに切り詰める（サロゲートの途中では切らない）。 */
function truncated(text: string, max: number): string {
  if (text.length <= max) return text;
  let out = "";
  for (const ch of text) {
    if (out.length + ch.length > max) break;
    out += ch;
  }
  return out;
}

function refOf(ref: { name: string; browseId?: string | null }): HandoffRef | null {
  if (ref.name.length === 0 || ref.name.length > 500) return null;
  return { name: ref.name, browseId: ref.browseId && isBrowseId(ref.browseId) ? ref.browseId : null };
}

/** ページの曲を連携の項目へ（形の合わないものは null）。 */
export function itemOf(track: PageTrack, itemId: string, origin: HandoffOrigin): HandoffItem | null {
  if (!isItemVideoId(track.videoId) || track.title.length === 0) return null;
  const seconds = track.durationMs == null ? null : Math.round(track.durationMs / 1000);
  return {
    itemId,
    videoId: track.videoId,
    title: truncated(track.title, 500),
    artists: (track.artists ?? [])
      .map(refOf)
      .filter((r): r is HandoffRef => r !== null)
      .slice(0, 20),
    album: track.album ? refOf(track.album) : null,
    durationSeconds: seconds !== null && seconds >= 0 && seconds <= 24 * 3600 ? seconds : null,
    thumbnailUrl: track.thumbnailUrl ?? null,
    origin: track.origin ?? origin,
    isLive: track.isLive === true,
  };
}

export function trackOf(item: HandoffItem): IdentifiedTrack {
  return {
    itemId: item.itemId,
    videoId: item.videoId,
    title: item.title,
    artists: item.artists.map((a) => ({ name: a.name, browseId: a.browseId })),
    album: item.album ? { name: item.album.name, browseId: item.album.browseId } : null,
    durationMs: item.durationSeconds === null ? null : item.durationSeconds * 1000,
    thumbnailUrl: item.thumbnailUrl,
    isLive: item.isLive,
    origin: item.origin,
  };
}

/** 届いたキューの doc を読む（handoffId は doc 自身のもの）。 */
export function decodeQueue(text: string): HandoffSnapshot | null {
  const id = decodeWith(text, readSnapshot)?.handoffId;
  if (!id) return null;
  const decoded = decodeSnapshot(text, id);
  return decoded.kind === "ok" ? decoded.snapshot : null;
}

/** キューの doc を、カーソルの今の曲・リピート・履歴に合わせる（Android の RemoteQueue.atCursor と同じ）。 */
export function atCursor(snapshot: HandoffSnapshot, cursor: CursorBody, positionMs: number): HandoffSnapshot | null {
  const index = snapshot.items.findIndex((i) => i.itemId === cursor.currentItemId);
  if (index < 0) return null;
  const window = windowItems(snapshot.items, index);
  const moved: HandoffSnapshot = {
    ...snapshot,
    items: window.items,
    continuation: window.droppedTail ? null : snapshot.continuation,
    exhausted: !window.droppedTail && snapshot.exhausted,
    currentItemId: cursor.currentItemId,
    positionMs: Math.max(0, positionMs),
    repeat: cursor.repeat,
    listenedMs: Math.max(0, cursor.listenedMs),
    historySent: cursor.historySent,
  };
  const valid = validateSnapshot(moved, moved.handoffId);
  return valid.kind === "ok" ? valid.snapshot : null;
}

export interface QueueMeta {
  source?: HandoffSource | null;
  radioPlaylistId?: string | null;
  /** 続きが無い（自動再生を切った・「次に流れる曲」を消去した）。無ければ false（受け取った端末がラジオで続ける）。 */
  exhausted?: boolean;
}

/** ページのキューに振った ID を覚えておく。 */
export class QueueTracker {
  /** ページのキューの並び（最後に知らせてもらった形に、遠隔の操作で見込んだ変更を当てたもの）。 */
  items: HandoffItem[] = [];
  index = -1;
  meta: QueueMeta = {};

  /** ページが知らせたキューに ID を当てる。前の並びと同じ曲が同じ順で残っていれば、その ID を使う。 */
  update(tracks: PageTrack[], index: number, meta: QueueMeta = {}): void {
    const previous = this.items;
    const used = new Set<string>();
    let from = 0;
    const next: HandoffItem[] = [];
    let nextIndex = -1;
    tracks.forEach((track, i) => {
      let match = -1;
      for (let k = from; k < previous.length; k++) {
        if (previous[k]!.videoId === track.videoId && !used.has(previous[k]!.itemId)) {
          match = k;
          break;
        }
      }
      const reuse = match >= 0 ? previous[match]! : null;
      if (match >= 0) from = match + 1;
      const item = itemOf(track, reuse?.itemId ?? uuid(), reuse?.origin ?? "source");
      if (item === null) return;
      used.add(item.itemId);
      if (i === index) nextIndex = next.length;
      next.push(item);
    });
    this.items = next;
    this.index = nextIndex;
    this.meta = meta;
  }

  /** 取り込んだキューをそのまま覚える（ページがこの並びを読み込んで知らせ直すと、同じ ID が当たる）。 */
  adopt(snapshot: HandoffSnapshot): void {
    this.items = snapshot.items.map((i) => ({ ...i }));
    this.index = snapshot.items.findIndex((i) => i.itemId === snapshot.currentItemId);
    this.meta = { source: snapshot.source, radioPlaylistId: snapshot.radioPlaylistId, exhausted: snapshot.exhausted };
  }

  get current(): HandoffItem | null {
    return this.index >= 0 ? (this.items[this.index] ?? null) : null;
  }

  indexOf(itemId: string): number {
    return this.items.findIndex((i) => i.itemId === itemId);
  }

  /** 遠隔のキューへの追加を当てる位置（その位置の後ろに入れる。-1 は先頭）。Swift の applyQueueAdd と同じ決め方。 */
  insertionAfter(next: boolean, afterItemId: string | null): number {
    if (afterItemId !== null) {
      const at = this.indexOf(afterItemId);
      if (at >= 0) return at;
    }
    if (next || this.index < 0) return this.index;
    // 今の曲の後ろに続く、自分で足した曲のかたまりの最後
    let at = this.index;
    while (at + 1 < this.items.length && this.items[at + 1]!.origin === "user") at++;
    return at;
  }

  /** 見込みの変更: 曲を足す。 */
  insert(after: number, items: HandoffItem[]): void {
    const fresh = items.filter((i) => this.indexOf(i.itemId) < 0);
    this.items.splice(after + 1, 0, ...fresh);
    if (this.index > after) this.index += fresh.length;
  }

  /** 見込みの変更: 今の曲より後ろの曲を動かす。動かせなければ false。 */
  move(itemId: string, afterItemId: string | null): { from: number; after: number } | null {
    const from = this.indexOf(itemId);
    if (from <= this.index) return null;
    const anchor = afterItemId === null ? this.index : this.indexOf(afterItemId);
    if (anchor < this.index || anchor === from) return null;
    const [item] = this.items.splice(from, 1);
    const after = anchor > from ? anchor - 1 : anchor;
    this.items.splice(after + 1, 0, item!);
    return { from, after: anchor };
  }

  /** 見込みの変更: 今の曲より後ろの曲を外す。外した位置（元の並び）を返す。 */
  remove(itemIds: string[]): number[] {
    const indices = itemIds
      .map((id) => this.indexOf(id))
      .filter((i) => i > this.index)
      .sort((a, b) => a - b);
    for (let k = indices.length - 1; k >= 0; k--) this.items.splice(indices[k]!, 1);
    return indices;
  }

  /** 見込みの変更: 今の曲より後ろの、元の流れの曲（自分で足した曲でないもの）を外して流れを止める。 */
  clearFlow(): number[] {
    const indices: number[] = [];
    for (let i = this.index + 1; i < this.items.length; i++) if (this.items[i]!.origin !== "user") indices.push(i);
    for (let k = indices.length - 1; k >= 0; k--) this.items.splice(indices[k]!, 1);
    this.meta = { ...this.meta, exhausted: true };
    return indices;
  }

  /** 今のキューの doc の文字列。中身が同じなら同じ文字列にするため、位置・履歴・時刻は入れない。今の曲が無ければ null。 */
  snapshotText(): string | null {
    if (this.index < 0 || this.index >= this.items.length) return null;
    const window = windowItems(this.items, this.index);
    const source = this.meta.source ?? null;
    const snapshot: HandoffSnapshot = {
      schemaVersion: 1,
      handoffId: SNAPSHOT_ID,
      items: window.items,
      currentItemId: window.items[window.currentIndex]!.itemId,
      positionMs: 0,
      playing: false,
      repeat: "off",
      source:
        source?.type === "radio" && isItemVideoId(source.seedVideoId) && source.seedVideoId.length === 11
          ? source
          : source?.type === "playlist" && isPlaylistId(source.playlistId)
            ? source
            : null,
      continuation: null,
      radioPlaylistId: this.meta.radioPlaylistId && isPlaylistId(this.meta.radioPlaylistId) ? this.meta.radioPlaylistId : null,
      exhausted: this.meta.exhausted === true,
      listenedMs: 0,
      historySent: false,
      capturedAtEpochMs: 0,
    };
    if (validateSnapshot(snapshot, SNAPSHOT_ID).kind !== "ok") return null;
    return encodeSnapshot(snapshot);
  }
}

