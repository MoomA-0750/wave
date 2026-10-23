// ページのキューと連携のキューの行き来（ID の当て方・遠隔の操作の見込み・スナップショット）。

import assert from "node:assert/strict";
import { test } from "node:test";
import { decodeSnapshot } from "../src/connect/handoff.ts";
import { atCursor, decodeQueue, QueueTracker, SNAPSHOT_ID, type PageTrack } from "../src/connect/queue.ts";
import type { CursorBody, HandoffItem } from "../src/connect/models.ts";

const t = (videoId: string, title = videoId, origin?: PageTrack["origin"]): PageTrack => ({ videoId, title, ...(origin ? { origin } : {}) });

test("同じ曲が同じ順で残れば同じ ID を使い、新しい曲には新しい ID を振る", () => {
  const q = new QueueTracker();
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaB"), t("aaaaaaaaaaC")], 0);
  const [a, b, c] = q.items.map((i) => i.itemId);
  // 次の曲へ進み、間に1曲入り、先頭が消えた
  q.update([t("aaaaaaaaaaB"), t("aaaaaaaaaaX"), t("aaaaaaaaaaC")], 0);
  assert.deepEqual(
    q.items.map((i) => i.itemId),
    [b, q.items[1]!.itemId, c],
  );
  assert.ok(![a, b, c].includes(q.items[1]!.itemId));
  assert.equal(q.current?.videoId, "aaaaaaaaaaB");
  // 同じ曲が2回あっても ID は重ならない
  q.update([t("aaaaaaaaaaB"), t("aaaaaaaaaaB")], 1);
  assert.notEqual(q.items[0]!.itemId, q.items[1]!.itemId);
  assert.equal(q.index, 1);
});

test("形の合わない曲（ID・題名）は落とし、今の曲の位置を合わせる", () => {
  const q = new QueueTracker();
  q.update([t("bad"), t("aaaaaaaaaaA"), { videoId: "aaaaaaaaaaB", title: "" }, t("aaaaaaaaaaC")], 3);
  assert.deepEqual(
    q.items.map((i) => i.videoId),
    ["aaaaaaaaaaA", "aaaaaaaaaaC"],
  );
  assert.equal(q.current?.videoId, "aaaaaaaaaaC");
});

test("遠隔で足した曲は、届いた ID のままページの並びに当たる", () => {
  const q = new QueueTracker();
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaB", "B", "user"), t("aaaaaaaaaaC", "C", "suggestion")], 0);
  const added: HandoffItem = {
    itemId: "11111111-1111-4111-8111-111111111111",
    videoId: "aaaaaaaaaaD",
    title: "D",
    artists: [],
    album: null,
    durationSeconds: null,
    thumbnailUrl: null,
    origin: "user",
    isLive: false,
  };
  // キューに追加は、自分で足した曲のかたまりの最後（B の後ろ）
  const after = q.insertionAfter(false, null);
  assert.equal(after, 1);
  // 次に再生は今の曲の直後
  assert.equal(q.insertionAfter(true, null), 0);
  q.insert(after, [added]);
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaB"), t("aaaaaaaaaaD"), t("aaaaaaaaaaC")], 0);
  assert.equal(q.items[2]!.itemId, added.itemId);
  assert.equal(q.items[2]!.origin, "user");
  assert.equal(q.items[3]!.origin, "suggestion");
});

test("並べ替え・外す・流れの消去は、今の曲より後ろだけ", () => {
  const q = new QueueTracker();
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaB"), t("aaaaaaaaaaC", "C", "user"), t("aaaaaaaaaaD", "D", "suggestion")], 1);
  const ids = q.items.map((i) => i.itemId);
  assert.equal(q.move(ids[0]!, null), null, "前の曲は動かさない");
  assert.deepEqual(q.move(ids[3]!, null), { from: 3, after: 1 });
  assert.deepEqual(
    q.items.map((i) => i.videoId),
    ["aaaaaaaaaaA", "aaaaaaaaaaB", "aaaaaaaaaaD", "aaaaaaaaaaC"],
  );
  assert.deepEqual(q.remove([ids[0]!, ids[3]!]), [2]);
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaB"), t("aaaaaaaaaaC", "C", "user"), t("aaaaaaaaaaE", "E", "suggestion")], 1);
  assert.deepEqual(q.clearFlow(), [3]);
  assert.equal(q.meta.exhausted, true);
});

test("スナップショットは位置を入れず、中身が同じなら同じ文字列で、受け手の検査を通る", () => {
  const q = new QueueTracker();
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaB")], 1, { source: { type: "radio", seedVideoId: "aaaaaaaaaaA" } });
  const text = q.snapshotText()!;
  assert.equal(q.snapshotText(), text);
  const decoded = decodeSnapshot(text, SNAPSHOT_ID);
  assert.equal(decoded.kind, "ok");
  if (decoded.kind !== "ok") return;
  assert.equal(decoded.snapshot.positionMs, 0);
  assert.equal(decoded.snapshot.currentItemId, q.current!.itemId);
  assert.deepEqual(decoded.snapshot.source, { type: "radio", seedVideoId: "aaaaaaaaaaA" });
  assert.equal(new QueueTracker().snapshotText(), null);
});

test("届いたキューを、カーソルの曲・位置に合わせて取り込む", () => {
  const q = new QueueTracker();
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaB")], 0);
  const snapshot = decodeQueue(q.snapshotText()!)!;
  const cursor: CursorBody = {
    queueRef: "x",
    currentItemId: snapshot.items[1]!.itemId,
    positionMs: 0,
    playing: true,
    repeat: "one",
    sessionBase: 0,
    sessionStartVersion: null,
    deviceSeq: 1,
    listenedMs: 1_000,
    historySent: true,
    accountTag: "t",
    startedAtEpochMs: 0,
    volumePercent: null,
    shuffle: null,
  };
  const adjusted = atCursor(snapshot, cursor, 12_000)!;
  assert.equal(adjusted.positionMs, 12_000);
  assert.equal(adjusted.repeat, "one");
  const other = new QueueTracker();
  other.adopt(adjusted);
  assert.equal(other.current?.itemId, cursor.currentItemId);
  // ページが読み込んで知らせ直すと、同じ ID が当たる
  other.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaB")], 1);
  assert.deepEqual(
    other.items.map((i) => i.itemId),
    snapshot.items.map((i) => i.itemId),
  );
});

test("途中の知らせで一度消えた曲・並べ替えの途中でも、ID と由来を保つ", () => {
  const q = new QueueTracker();
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaB"), t("aaaaaaaaaaC"), t("aaaaaaaaaaD")], 0, {}, 0);
  const ids = new Map(q.items.map((i) => [i.videoId, i.itemId]));
  const added: HandoffItem = { ...q.items[0]!, itemId: "22222222-2222-4222-8222-222222222222", videoId: "aaaaaaaaaaX", title: "X", origin: "user" };
  q.insert(0, [added]);
  // ページの途中の知らせ（まだ X が無い・並びが動いている途中）
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaD"), t("aaaaaaaaaaB"), t("aaaaaaaaaaC")], 0, {}, 1_000);
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaX"), t("aaaaaaaaaaC"), t("aaaaaaaaaaB"), t("aaaaaaaaaaD")], 0, {}, 2_000);
  assert.equal(q.items[1]!.itemId, added.itemId);
  assert.equal(q.items[1]!.origin, "user");
  for (const item of q.items.slice(2)) assert.equal(item.itemId, ids.get(item.videoId));
  // 時間がたてば忘れる
  q.update([t("aaaaaaaaaaA")], 0, {}, 3_000);
  q.update([t("aaaaaaaaaaA"), t("aaaaaaaaaaX")], 0, {}, 100_000);
  assert.notEqual(q.items[1]!.itemId, added.itemId);
});
