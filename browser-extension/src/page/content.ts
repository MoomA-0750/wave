// 本体の隔離 world の入口（manifest の content_scripts、world なし）。
// MAIN world の page-main が読む YouTube Music の様子（createYtmPage）を、背景の Port の取り決め（src/shared/protocol.ts、Port 名 "wave-page"）へつなぐ。
// - 様子 → state（キュー・今の曲・位置・再生中か・音量・繰り返し・自動再生）。広告の間は曲として出さず、直前の位置で止まって見せる
// - 背景の command → YouTube Music の操作（loadQueue・addToQueue・move・remove・setAutoplay など）
// - 本人がタブで曲を選んだ・再生を押した（遠隔の命令によらない変化）→ user_started
// - ログイン（DATASYNC_ID、MAIN world で読んで検査したもの）→ account。値はログに出さない

import type { PageCommand as WaveCommand, PageState as WaveState, PageTrack as WaveTrack } from "../shared/protocol.ts";
import { PAGE_PORT, PING_INTERVAL_MS, PROTOCOL_VERSION, type BackgroundToClient, type PageToBackground } from "../shared/protocol.ts";
import type { PageCommand as YtmCommand, PageQueueItem, PageState as YtmState } from "./protocol.ts";
import { createYtmPage } from "./ytm-page.ts";

/* eslint-disable @typescript-eslint/no-explicit-any */
type Port = {
  postMessage(m: unknown): void;
  disconnect(): void;
  onMessage: { addListener(f: (m: any) => void): void };
  onDisconnect: { addListener(f: () => void): void };
};

/** 遠隔の命令の後、これだけの間に起きた変化は本人の操作とみなさない。 */
const REMOTE_GRACE_MS = 4_000;
/** 引き取った曲（adopt）が広告の後に流れ出すまで待つ上限。 */
const ADOPT_GRACE_MS = 150_000;
/** 曲の終わりのこれだけ手前より後で次の曲へ変わったら、自然に進んだとみなす。 */
const NATURAL_END_MS = 5_000;
/** loadQueue に一度に渡せる曲の数（page の上限）。前は 20 曲まで残す。 */
const MAX_LOAD = 200;

const page = createYtmPage();
let port: Port | null = null;
let nextId = 1;
let account: string | null | undefined;

// --- 遠隔の命令と本人の操作の見分け ---
let remoteUntil = 0;
let adoptTarget: { videoId: string; until: number } | null = null;
let previous: { videoId: string | null; playing: boolean; positionMs: number; durationMs: number | null; ended: boolean } | null = null;
/** 曲ごとの最後の本編の位置（広告の間はこれで止まって見せる）。 */
const lastPosition = new Map<string, number>();

function inRemoteWindow(now: number): boolean {
  if (adoptTarget && now > adoptTarget.until) adoptTarget = null;
  return adoptTarget !== null || now < remoteUntil;
}

function runtime(): { connect(info: { name: string }): Port } | null {
  const g = globalThis as any;
  return (g.browser?.runtime ?? g.chrome?.runtime) || null;
}

function send(message: PageToBackground): void {
  try {
    port?.postMessage(message);
  } catch {
    // 切れた（onDisconnect でつなぎ直す）
  }
}

// --- 様子の写し ---

function trackOf(item: { videoId: string; title: string; artists: string[]; album: string | null; thumbnailUrl: string | null; durationMs: number | null }): WaveTrack {
  return {
    videoId: item.videoId,
    // 題名が読めないうちは ID を仮に入れる（連携のキューは題名が空の曲を受けない）
    title: item.title || item.videoId,
    artists: item.artists.map((name) => ({ name })),
    album: item.album ? { name: item.album } : null,
    durationMs: item.durationMs,
    thumbnailUrl: item.thumbnailUrl,
  };
}

/** YouTube Music の様子を、背景に知らせる state へ。曲が無ければ null。 */
function toWaveState(s: YtmState): WaveState | null {
  if (!s.ready || s.track === null) return null;
  let items: (PageQueueItem | NonNullable<YtmState["track"]>)[] = s.queue.items;
  let index = s.queue.currentIndex;
  if (index < 0 || items[index]?.videoId !== s.track.videoId) {
    // キューに今の曲が見つからない（読み込みの途中など）: 今の曲だけのキューにする
    items = [s.track];
    index = 0;
  }
  const queue = items.map((item, i) => {
    const t = trackOf(i === index ? { ...item, durationMs: s.durationMs ?? item.durationMs } : item);
    return t;
  });
  const now = Date.now();
  const elapsed = s.playback === "playing" ? Math.max(0, now - s.sampledAt) * (s.playbackRate || 1) : 0;
  let positionMs = Math.round(s.positionMs + elapsed);
  let playing = s.playback === "playing" || (s.playback === "buffering" && previous?.playing === true);
  if (s.adPlaying) {
    // 広告の間は曲として出さない: その曲の直前の位置で止まって見せる。曲の前の広告（まだ位置が無い）なら知らせない
    const last = lastPosition.get(s.track.videoId);
    if (last === undefined) return null;
    positionMs = last;
    playing = false;
  } else {
    lastPosition.set(s.track.videoId, positionMs);
  }
  return {
    queue,
    index,
    positionMs,
    playing,
    repeat: s.repeat,
    volumePercent: s.muted ? 0 : Math.round(s.volume),
    // 自動再生（ラジオ）を切っていれば、続きは無い
    exhausted: !s.queue.autoplay,
    listenedMs: positionMs,
    historySent: true,
  };
}

function onPageState(s: YtmState): void {
  const state = toWaveState(s);
  const now = Date.now();
  if (state === null && s.adPlaying) return;
  if (state === null) {
    if (previous?.videoId) send({ type: "ended" });
    previous = null;
    return;
  }
  const current = state.queue[state.index]!;
  // 引き取った曲が広告の後に流れ出したら、そこからは本人の操作を見る
  if (adoptTarget && current.videoId === adoptTarget.videoId && state.playing && !s.adPlaying) {
    adoptTarget = null;
    remoteUntil = now + REMOTE_GRACE_MS;
  }
  // 変わるたびに送る（位置だけの小さな変化は背景が間引く）。引き取った曲を読み込んでいる間（キューを組み直している途中）は送らない
  if (adoptTarget === null || inRemoteWindow(now) === false) send({ type: "state", state });
  // 本人の操作か（遠隔の命令の後でなく、広告の切り替わりでもない）
  if (!s.adPlaying && previous !== null && !inRemoteWindow(now)) {
    const changedTrack = previous.videoId !== current.videoId;
    const naturalEnd =
      previous.ended || (previous.durationMs !== null && previous.durationMs > 0 && previous.positionMs >= previous.durationMs - NATURAL_END_MS);
    const startedPlaying = !previous.playing && state.playing;
    // 曲を選んだ・次へ/前へを押した（曲の終わりで自然に進んだのでない）、または同じ曲で再生を押した
    if ((changedTrack && !naturalEnd && state.playing) || (!changedTrack && startedPlaying)) send({ type: "user_started" });
  }
  if (!s.adPlaying) {
    previous = {
      videoId: current.videoId,
      playing: state.playing,
      positionMs: state.positionMs,
      durationMs: current.durationMs ?? null,
      ended: s.playback === "ended",
    };
  }
}

// --- 命令 ---

let chain: Promise<unknown> = Promise.resolve();

async function ytm(command: YtmCommand): Promise<boolean> {
  const result = await page.command(command);
  if (!result.ok) console.debug("[wave] command failed", command.type, result.error);
  return result.ok;
}

/** 今の YouTube Music のキューの並び（最後に読んだもの）。 */
function currentItems(): PageQueueItem[] {
  return page.latest()?.queue.items ?? [];
}

async function runCommand(command: WaveCommand): Promise<void> {
  remoteUntil = Date.now() + ADOPT_GRACE_MS;
  try {
    switch (command.kind) {
      case "play":
        await ytm({ type: "play" });
        break;
      case "pause":
        await ytm({ type: "pause" });
        break;
      case "next":
        await ytm({ type: "next" });
        break;
      case "previous":
        await ytm({ type: "previous" });
        break;
      case "seek":
        await ytm({ type: "seek", positionMs: command.positionMs });
        break;
      case "volume":
        await ytm({ type: "setVolume", volume: command.volumePercent });
        break;
      case "repeat":
        await ytm({ type: "setRepeat", repeat: command.mode });
        break;
      case "queue_add": {
        const ids = command.tracks.map((t) => t.videoId).filter((id) => /^[A-Za-z0-9_-]{11}$/.test(id));
        if (ids.length === 0) break;
        const s = page.latest();
        const current = s?.queue.currentIndex ?? -1;
        if (command.afterIndex === current) {
          await ytm({ type: "addToQueue", videoIds: ids, position: "next" });
          break;
        }
        const before = currentItems().length;
        if (!(await ytm({ type: "addToQueue", videoIds: ids, position: "end" }))) break;
        // 末尾に入れてから、afterIndex の曲の直後へ順に動かす
        if (command.afterIndex < before - 1) {
          for (let k = 0; k < ids.length; k++) await ytm({ type: "move", from: before + k, to: command.afterIndex + 1 + k });
        }
        break;
      }
      case "queue_move": {
        const to = command.afterIndex < command.fromIndex ? command.afterIndex + 1 : command.afterIndex;
        await ytm({ type: "move", from: command.fromIndex, to });
        break;
      }
      case "queue_order": {
        const current = page.latest()?.queue.currentIndex ?? -1;
        if (current < 0) break;
        const order = currentItems().map((_, i) => i);
        for (let k = 0; k < command.fromIndices.length; k++) {
          const at = order.indexOf(command.fromIndices[k]!);
          const to = current + 1 + k;
          if (at < 0 || at === to) continue;
          if (!(await ytm({ type: "move", from: at, to }))) break;
          order.splice(to, 0, ...order.splice(at, 1));
        }
        break;
      }
      case "queue_remove": {
        const items = currentItems();
        const ids = command.itemIds;
        for (let k = command.indices.length - 1; k >= 0; k--) {
          const index = command.indices[k]!;
          const videoId = items[index]?.videoId;
          if (videoId === undefined) continue;
          await ytm({ type: "remove", index, videoId });
        }
        void ids;
        break;
      }
      case "queue_clear_flow": {
        // 自分で足した曲は残し、流れの曲を外して自動再生を止める
        const items = currentItems();
        for (let k = command.indices.length - 1; k >= 0; k--) {
          const index = command.indices[k]!;
          const videoId = items[index]?.videoId;
          if (videoId !== undefined) await ytm({ type: "remove", index, videoId });
        }
        await ytm({ type: "setAutoplay", autoplay: false });
        break;
      }
      case "adopt": {
        // 前は 20 曲まで、後ろは残りを MAX_LOAD まで
        const start = Math.max(0, command.index - 20);
        const tracks = command.tracks.slice(start, start + MAX_LOAD).filter((t) => /^[A-Za-z0-9_-]{11}$/.test(t.videoId));
        const index = tracks.findIndex((t) => t.itemId === command.tracks[command.index]?.itemId);
        if (index < 0) break;
        adoptTarget = { videoId: tracks[index]!.videoId, until: Date.now() + ADOPT_GRACE_MS };
        // wave から移したキューは自動再生を切って読み込む（wave の流れの続きは今は足さない）
        await ytm({
          type: "loadQueue",
          videoIds: tracks.map((t) => t.videoId),
          index,
          positionMs: command.positionMs,
          playing: command.playing,
          autoplay: false,
        });
        if (command.repeat !== "off") await ytm({ type: "setRepeat", repeat: command.repeat });
        break;
      }
    }
  } finally {
    remoteUntil = Date.now() + REMOTE_GRACE_MS;
    const s = page.latest();
    if (s) onPageState(s);
  }
}

// --- Port ---

function connect(): void {
  const rt = runtime();
  if (!rt) return;
  let p: Port;
  try {
    p = rt.connect({ name: PAGE_PORT });
  } catch {
    return; // 拡張が更新・無効化された
  }
  port = p;
  p.onMessage.addListener((m: BackgroundToClient) => {
    if (m?.type !== "event" || m.event.type !== "command") return;
    const command = m.event.command;
    chain = chain.then(() => runCommand(command)).catch((e: unknown) => console.debug("[wave] command error", e));
  });
  p.onDisconnect.addListener(() => {
    if (port === p) port = null;
    setTimeout(connect, 1_000);
  });
  send({ type: "hello", version: PROTOCOL_VERSION, id: nextId++ });
  if (account !== undefined) send({ type: "account", dataSyncId: account });
  const s = page.latest();
  if (s) onPageState(s);
}

page.onAccount((dataSyncId) => {
  account = dataSyncId;
  send({ type: "account", dataSyncId });
});
page.onState(onPageState);
connect();
setInterval(() => send({ type: "ping" }), PING_INTERVAL_MS);
