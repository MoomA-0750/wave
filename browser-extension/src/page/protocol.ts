// YouTube Music のタブ（ページ側）と拡張のあいだで使う型と、検査。
// MAIN world（page-main）と隔離 world（page-content）の両方が読む。
// ページの JS は信用しない。MAIN から届いたものは必ず parse* を通す。

/** window.postMessage で使う印。これが無い・違うメッセージは捨てる */
export const BRIDGE_NS = 'wave-ytm-page';
export const BRIDGE_VERSION = 1;

/** 隔離 world と background のあいだの Port の名前 */
export const PORT_NAME = 'wave-ytm-page';

export type Playback = 'idle' | 'playing' | 'paused' | 'buffering' | 'ended';
export type Repeat = 'off' | 'all' | 'one';
export type Like = 'like' | 'dislike' | 'none';

export interface PageTrack {
  videoId: string;
  title: string;
  artists: string[];
  album: string | null;
  thumbnailUrl: string | null;
  durationMs: number | null;
}

export interface PageQueueItem extends PageTrack {
  /** YTM がキューの項目に振る番号（文字列）。消す・選ぶときに使う。同じ曲が2回入っても区別できる */
  itemId: string;
}

export interface PageQueue {
  /** YTM の「次に再生」の本体（流してきた曲・今の曲・この先の曲。自分で足した曲もここに混ざる） */
  items: PageQueueItem[];
  /** items の中で今の曲の位置。キューが空なら -1 */
  currentIndex: number;
  /** 自動再生（Automix・ラジオ）で後ろに足される予定の曲。items とは別の列 */
  automix: PageQueueItem[];
  /** 自動再生が入っているか（キューの「自動再生」のスイッチ） */
  autoplay: boolean;
}

export interface PageState {
  /** YTM の画面とプレーヤーが見つかったか */
  ready: boolean;
  /** 今の曲（広告中も、広告のあとに流れる曲を入れる） */
  track: PageTrack | null;
  /** 広告を流している。このあいだ playback・位置は広告ではなく曲のもの */
  adPlaying: boolean;
  playback: Playback;
  positionMs: number;
  durationMs: number | null;
  /** positionMs を読んだ時刻（Date.now()）。再生中はここから進める */
  sampledAt: number;
  playbackRate: number;
  /** 0〜100 */
  volume: number;
  muted: boolean;
  /** YTM のシャッフルのボタンの入り切り（YTM は押すとキューを作り直す） */
  shuffle: boolean;
  repeat: Repeat;
  /** 読めないとき null */
  like: Like | null;
  queue: PageQueue;
}

export type InsertPosition = 'next' | 'end';

/** ページへの指示。index は PageQueue.items の位置 */
export type PageCommand =
  | { type: 'play' }
  | { type: 'pause' }
  | { type: 'seek'; positionMs: number }
  | { type: 'next' }
  | { type: 'previous' }
  | { type: 'setVolume'; volume: number }
  | { type: 'setMuted'; muted: boolean }
  | { type: 'setRepeat'; repeat: Repeat }
  | { type: 'setShuffle'; shuffle: boolean }
  | { type: 'setAutoplay'; autoplay: boolean }
  | { type: 'setLike'; like: Like }
  /** videoIds をキューにして index の曲を positionMs から流す。autoplay を渡すとキューの自動再生も切り替える */
  | { type: 'loadQueue'; videoIds: string[]; index: number; positionMs: number; playing: boolean; autoplay?: boolean }
  | { type: 'playIndex'; index: number }
  | { type: 'addToQueue'; videoIds: string[]; position: InsertPosition }
  /** videoId を渡すと、その位置の曲が違うときは何もせず失敗にする（ずれた操作を防ぐ） */
  | { type: 'remove'; index: number; videoId?: string }
  | { type: 'move'; from: number; to: number }
  /** 今の曲より後ろの items をすべて外す（自動再生の列は autoplay で扱う） */
  | { type: 'clearUpcoming' }
  | { type: 'getState' };

export interface CommandResult {
  ok: boolean;
  error?: string;
}

// ---- MAIN world と隔離 world のあいだのメッセージ ----

export type BridgeMessage =
  | { ns: typeof BRIDGE_NS; v: number; dir: 'toMain'; kind: 'command'; id: number; command: PageCommand }
  | { ns: typeof BRIDGE_NS; v: number; dir: 'toMain'; kind: 'hello' }
  | { ns: typeof BRIDGE_NS; v: number; dir: 'toContent'; kind: 'state'; state: PageState }
  | { ns: typeof BRIDGE_NS; v: number; dir: 'toContent'; kind: 'result'; id: number; result: CommandResult }
  /** ログイン（ytcfg の LOGGED_IN と DATASYNC_ID）。dataSyncId は、ログインしていなければ null。ytcfg がまだ無ければ送らない */
  | { ns: typeof BRIDGE_NS; v: number; dir: 'toContent'; kind: 'account'; dataSyncId: string | null };

// ---- Port（隔離 world ⇔ background）のメッセージ ----

export type PortToBackground =
  | { type: 'state'; state: PageState }
  | { type: 'result'; id: number; result: CommandResult };

export type PortToPage = { type: 'command'; id: number; command: PageCommand };

// ---- 検査 ----

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const MAX_STR = 500;
const MAX_QUEUE = 500;
const MAX_IDS = 200;

export function isVideoId(x: unknown): x is string {
  return typeof x === 'string' && VIDEO_ID.test(x);
}

function str(x: unknown, max = MAX_STR): string {
  return typeof x === 'string' ? x.slice(0, max) : '';
}
function strOrNull(x: unknown, max = MAX_STR): string | null {
  return typeof x === 'string' ? x.slice(0, max) : null;
}
function num(x: unknown, d = 0): number {
  return typeof x === 'number' && Number.isFinite(x) ? x : d;
}
function numOrNull(x: unknown): number | null {
  return typeof x === 'number' && Number.isFinite(x) ? x : null;
}
function bool(x: unknown): boolean {
  return x === true;
}
function oneOf<T extends string>(x: unknown, list: readonly T[], d: T): T {
  return (list as readonly unknown[]).includes(x) ? (x as T) : d;
}
function obj(x: unknown): Record<string, unknown> | null {
  return x !== null && typeof x === 'object' && !Array.isArray(x) ? (x as Record<string, unknown>) : null;
}
function httpsUrl(x: unknown): string | null {
  const s = strOrNull(x, 2000);
  return s && /^https:\/\//.test(s) ? s : null;
}

function parseTrack(x: unknown): PageTrack | null {
  const o = obj(x);
  if (!o || !isVideoId(o.videoId)) return null;
  return {
    videoId: o.videoId,
    title: str(o.title),
    artists: Array.isArray(o.artists) ? o.artists.slice(0, 20).map(a => str(a)).filter(Boolean) : [],
    album: strOrNull(o.album),
    thumbnailUrl: httpsUrl(o.thumbnailUrl),
    durationMs: numOrNull(o.durationMs),
  };
}

function parseItem(x: unknown): PageQueueItem | null {
  const t = parseTrack(x);
  const o = obj(x);
  if (!t || !o) return null;
  return { ...t, itemId: str(o.itemId, 40) };
}

function parseItems(x: unknown): PageQueueItem[] {
  if (!Array.isArray(x)) return [];
  const out: PageQueueItem[] = [];
  for (const it of x.slice(0, MAX_QUEUE)) {
    const p = parseItem(it);
    if (p) out.push(p);
  }
  return out;
}

/** MAIN world から来た状態を、決まった形に直す。形が違えば null */
export function parsePageState(x: unknown): PageState | null {
  const o = obj(x);
  if (!o) return null;
  const q = obj(o.queue) ?? {};
  const items = parseItems(q.items);
  const ci = Math.trunc(num(q.currentIndex, -1));
  return {
    ready: bool(o.ready),
    track: o.track == null ? null : parseTrack(o.track),
    adPlaying: bool(o.adPlaying),
    playback: oneOf(o.playback, ['idle', 'playing', 'paused', 'buffering', 'ended'] as const, 'idle'),
    positionMs: Math.max(0, num(o.positionMs)),
    durationMs: numOrNull(o.durationMs),
    sampledAt: num(o.sampledAt),
    playbackRate: num(o.playbackRate, 1),
    volume: Math.min(100, Math.max(0, num(o.volume, 100))),
    muted: bool(o.muted),
    shuffle: bool(o.shuffle),
    repeat: oneOf(o.repeat, ['off', 'all', 'one'] as const, 'off'),
    like: o.like == null ? null : oneOf(o.like, ['like', 'dislike', 'none'] as const, 'none'),
    queue: {
      items,
      currentIndex: ci >= 0 && ci < items.length ? ci : -1,
      automix: parseItems(q.automix),
      autoplay: bool(q.autoplay),
    },
  };
}

function videoIds(x: unknown): string[] | null {
  if (!Array.isArray(x) || x.length === 0 || x.length > MAX_IDS) return null;
  return x.every(isVideoId) ? (x as string[]) : null;
}
function index(x: unknown): number | null {
  return typeof x === 'number' && Number.isInteger(x) && x >= 0 && x < 10000 ? x : null;
}

/** 指示を検査する。background から来たものも、ページへ渡す前に通す */
export function parseCommand(x: unknown): PageCommand | null {
  const o = obj(x);
  if (!o || typeof o.type !== 'string') return null;
  switch (o.type) {
    case 'play': case 'pause': case 'next': case 'previous': case 'clearUpcoming': case 'getState':
      return { type: o.type };
    case 'seek': {
      const p = numOrNull(o.positionMs);
      return p === null || p < 0 ? null : { type: 'seek', positionMs: p };
    }
    case 'setVolume': {
      const v = numOrNull(o.volume);
      return v === null ? null : { type: 'setVolume', volume: Math.min(100, Math.max(0, Math.round(v))) };
    }
    case 'setMuted': return typeof o.muted === 'boolean' ? { type: 'setMuted', muted: o.muted } : null;
    case 'setRepeat': return ['off', 'all', 'one'].includes(o.repeat as string) ? { type: 'setRepeat', repeat: o.repeat as Repeat } : null;
    case 'setShuffle': return typeof o.shuffle === 'boolean' ? { type: 'setShuffle', shuffle: o.shuffle } : null;
    case 'setAutoplay': return typeof o.autoplay === 'boolean' ? { type: 'setAutoplay', autoplay: o.autoplay } : null;
    case 'setLike': return ['like', 'dislike', 'none'].includes(o.like as string) ? { type: 'setLike', like: o.like as Like } : null;
    case 'loadQueue': {
      const ids = videoIds(o.videoIds);
      const i = index(o.index);
      const p = numOrNull(o.positionMs) ?? 0;
      if (!ids || i === null || i >= ids.length || p < 0) return null;
      const c: PageCommand = { type: 'loadQueue', videoIds: ids, index: i, positionMs: p, playing: o.playing !== false };
      if (typeof o.autoplay === 'boolean') c.autoplay = o.autoplay;
      return c;
    }
    case 'playIndex': {
      const i = index(o.index);
      return i === null ? null : { type: 'playIndex', index: i };
    }
    case 'addToQueue': {
      const ids = videoIds(o.videoIds);
      if (!ids || (o.position !== 'next' && o.position !== 'end')) return null;
      return { type: 'addToQueue', videoIds: ids, position: o.position };
    }
    case 'remove': {
      const i = index(o.index);
      if (i === null) return null;
      if (o.videoId !== undefined && !isVideoId(o.videoId)) return null;
      return o.videoId === undefined ? { type: 'remove', index: i } : { type: 'remove', index: i, videoId: o.videoId as string };
    }
    case 'move': {
      const f = index(o.from), t = index(o.to);
      return f === null || t === null ? null : { type: 'move', from: f, to: t };
    }
    default:
      return null;
  }
}

export function parseResult(x: unknown): CommandResult {
  const o = obj(x);
  if (!o) return { ok: false, error: 'bad result' };
  return o.ok === true ? { ok: true } : { ok: false, error: str(o.error, 300) || 'failed' };
}

/** window の message から、ページ側の印の付いたメッセージだけを取り出す */
export function readBridge(data: unknown, dir: 'toMain' | 'toContent'): Record<string, unknown> | null {
  const o = obj(data);
  if (!o || o.ns !== BRIDGE_NS || o.v !== BRIDGE_VERSION || o.dir !== dir) return null;
  return o;
}

/** MAIN から来たログインの値を検査する。形が違えば undefined（知らせが無かったのと同じに扱う）。値はログに出さない */
export function parseAccount(x: unknown): string | null | undefined {
  if (x === null) return null;
  if (typeof x !== 'string' ||x.length === 0 || x.length > 200) return undefined;
  return /^[A-Za-z0-9_|-]+$/.test(x) ? x : undefined;
}
