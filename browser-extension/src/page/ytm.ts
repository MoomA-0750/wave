// YouTube Music の画面の中身を読む・動かす（MAIN world 専用）。
// YTM の内部（Polymer の要素・redux の store・#movie_player の API）に触るので、隔離 world からは呼べない。
// 調べた結果と、壊れやすいところは docs/ytm-page.md に書いた。

import type { Like, PageCommand, PageQueueItem, PageState, PageTrack, Playback, Repeat } from './protocol';

/* eslint-disable @typescript-eslint/no-explicit-any */
type Any = any;

const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms));

async function waitFor<T>(f: () => T | null | undefined | false, timeoutMs: number, stepMs = 100): Promise<T | null> {
  const end = Date.now() + timeoutMs;
  for (;;) {
    let v: T | null | undefined | false = null;
    try { v = f(); } catch { v = null; }
    if (v) return v;
    if (Date.now() > end) return null;
    await sleep(stepMs);
  }
}

// ---- 要素を探す ----

function app(): Any { return document.querySelector('ytmusic-app'); }
function player(): Any {
  const p: Any = document.querySelector('#movie_player');
  return p && typeof p.getPlayerState === 'function' ? p : null;
}
function bar(): Any { return document.querySelector('ytmusic-player-bar'); }
/** キューの要素（.queue に YTM のキューの係を持つ） */
function queueEl(): Any {
  for (const el of Array.from(document.querySelectorAll('ytmusic-player-queue')) as Any[]) {
    if (el.queue && typeof el.queue.getItems === 'function') return el;
  }
  return null;
}
function queueSvc(): Any { const q = queueEl(); return q ? q.queue : null; }
function store(): Any {
  const a = app();
  return a && typeof a.getState === 'function' && typeof a.dispatch === 'function' ? a : null;
}
function reduxState(): Any {
  const s = store();
  try { return s ? s.getState() : null; } catch { return null; }
}

// ---- キューの項目を読む ----

/** 項目の包み（曲と MV の両方があるときは playlistPanelVideoWrapperRenderer）から、流す方の中身を取る */
function rendererOf(it: Any, preferVideoId?: string): Any {
  if (!it) return null;
  if (it.playlistPanelVideoRenderer) return it.playlistPanelVideoRenderer;
  const w = it.playlistPanelVideoWrapperRenderer;
  if (!w) return null;
  const primary = w.primaryRenderer?.playlistPanelVideoRenderer ?? null;
  if (preferVideoId && primary?.videoId !== preferVideoId) {
    for (const c of w.counterpart ?? []) {
      const r = c?.counterpartRenderer?.playlistPanelVideoRenderer;
      if (r?.videoId === preferVideoId) return r;
    }
  }
  return primary;
}

function videoIdsOf(it: Any): string[] {
  const out: string[] = [];
  const p = rendererOf(it);
  if (p?.videoId) out.push(p.videoId);
  for (const c of it?.playlistPanelVideoWrapperRenderer?.counterpart ?? []) {
    const v = c?.counterpartRenderer?.playlistPanelVideoRenderer?.videoId;
    if (v) out.push(v);
  }
  return out;
}

function runsText(t: Any): string {
  if (!t) return '';
  if (typeof t.simpleText === 'string') return t.simpleText;
  return Array.isArray(t.runs) ? t.runs.map((r: Any) => r?.text ?? '').join('') : '';
}

function parseClock(s: string): number | null {
  if (!/^\d+(:\d+){0,2}$/.test(s)) return null;
  return s.split(':').reduce((a, x) => a * 60 + Number(x), 0) * 1000;
}

function bestThumb(t: Any): string | null {
  const list: Any[] = t?.thumbnails ?? [];
  let best: Any = null;
  for (const x of list) if (x?.url && (!best || (x.width ?? 0) > (best.width ?? 0))) best = x;
  return best?.url ?? null;
}

/** 曲の名前の下の行（「アーティスト • アルバム • 年」など）から、アーティストとアルバムを取る */
function bylineOf(r: Any): { artists: string[]; album: string | null } {
  const runs: Any[] = r?.longBylineText?.runs ?? r?.shortBylineText?.runs ?? [];
  const artists: string[] = [];
  let album: string | null = null;
  for (const run of runs) {
    const pt = run?.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    if (pt === 'MUSIC_PAGE_TYPE_ARTIST' || pt === 'MUSIC_PAGE_TYPE_USER_CHANNEL') artists.push(run.text);
    else if (pt === 'MUSIC_PAGE_TYPE_ALBUM') album = run.text;
  }
  if (artists.length === 0) {
    // リンクが無いとき（動画など）は最初の区切りの前をアーティストとみなす
    const first = runsText(r?.shortBylineText ?? r?.longBylineText).split(' • ')[0];
    if (first) artists.push(first);
  }
  return { artists, album };
}

function itemOf(it: Any, preferVideoId?: string): PageQueueItem | null {
  const r = rendererOf(it, preferVideoId);
  if (!r?.videoId) return null;
  const { artists, album } = bylineOf(r);
  const idx = r.navigationEndpoint?.watchEndpoint?.index;
  return {
    videoId: r.videoId,
    title: runsText(r.title),
    artists,
    album,
    thumbnailUrl: bestThumb(r.thumbnail),
    durationMs: parseClock(runsText(r.lengthText)),
    itemId: typeof idx === 'number' ? String(idx) : '',
  };
}

// ---- 状態を読む ----

function isAd(p: Any): boolean {
  if (!p) return false;
  const c = p.classList;
  return !!c && (c.contains('ad-showing') || c.contains('ad-interrupting'));
}

function playbackOf(p: Any, ad: boolean): Playback {
  if (!p) return 'idle';
  const v = document.querySelector('video');
  if (ad) return v && !v.paused ? 'playing' : 'paused';
  switch (p.getPlayerState()) {
    case 1: return 'playing';
    case 2: return 'paused';
    case 3: return 'buffering';
    case 0: return 'ended';
    case 5: return 'paused';
    default: return 'idle';
  }
}

function repeatOf(m: unknown): Repeat {
  return m === 'ALL' ? 'all' : m === 'ONE' ? 'one' : 'off';
}

function likeOf(): Like | null {
  const el = document.querySelector('ytmusic-player-bar ytmusic-like-button-renderer');
  const s = el?.getAttribute('like-status');
  return s === 'LIKE' ? 'like' : s === 'DISLIKE' ? 'dislike' : s === 'INDIFFERENT' ? 'none' : null;
}

export function readState(): PageState {
  const p = player();
  const s = reduxState();
  const q = s?.queue ?? {};
  const qs = queueSvc();
  const ad = isAd(p);
  const vd = p ? p.getVideoData() : null;
  const curVid: string | undefined = vd?.video_id || undefined;

  const rawItems: Any[] = Array.isArray(q.items) ? q.items : [];
  let currentIndex = -1;
  try { currentIndex = qs ? qs.getCurrentItemIndex() : -1; } catch { currentIndex = -1; }
  if (!(currentIndex >= 0 && currentIndex < rawItems.length)) currentIndex = rawItems.findIndex(it => rendererOf(it)?.selected);
  const items = rawItems.map((it, i) => itemOf(it, i === currentIndex ? curVid : undefined)).filter((x): x is PageQueueItem => !!x);
  const automix = (Array.isArray(q.automixItems) ? q.automixItems : []).map((it: Any) => itemOf(it)).filter((x: Any): x is PageQueueItem => !!x);

  let track: PageTrack | null = null;
  if (curVid) {
    const fromQueue = currentIndex >= 0 ? items[currentIndex] : undefined;
    const base: PageTrack = fromQueue && fromQueue.videoId === curVid
      ? { videoId: fromQueue.videoId, title: fromQueue.title, artists: fromQueue.artists, album: fromQueue.album, thumbnailUrl: fromQueue.thumbnailUrl, durationMs: fromQueue.durationMs }
      : { videoId: curVid, title: vd.title ?? '', artists: vd.author ? [vd.author] : [], album: null, thumbnailUrl: null, durationMs: null };
    // 広告中は mediaSession が広告の中身になるので使わない
    const ms = !ad ? navigator.mediaSession?.metadata : null;
    if (ms && ms.title === base.title) {
      if (!base.album && ms.album) base.album = ms.album;
      if (!base.thumbnailUrl && ms.artwork?.length) base.thumbnailUrl = ms.artwork[ms.artwork.length - 1].src;
    }
    const d = p.getDuration();
    if (!ad && d > 0) base.durationMs = Math.round(d * 1000);
    track = base;
  }

  const posSec = p && !ad ? p.getCurrentTime() : 0;
  return {
    ready: !!(app() && store()),
    track,
    adPlaying: ad,
    playback: track ? playbackOf(p, ad) : 'idle',
    positionMs: Math.max(0, Math.round((posSec || 0) * 1000)),
    durationMs: track?.durationMs ?? null,
    sampledAt: Date.now(),
    playbackRate: p && typeof p.getPlaybackRate === 'function' ? p.getPlaybackRate() || 1 : 1,
    volume: p ? p.getVolume() : (s?.player?.volume ?? 100),
    muted: p ? !!p.isMuted() : !!s?.player?.muted,
    shuffle: !!q.shuffleEnabled,
    repeat: repeatOf(q.repeatMode),
    like: likeOf(),
    queue: { items, currentIndex: currentIndex >= 0 && currentIndex < items.length ? currentIndex : -1, automix, autoplay: q.autoplay !== false },
  };
}

// ---- 動かす ----

function navigate(endpoint: Any): void {
  const a = app();
  if (!a) throw new Error('ytmusic-app not found');
  a.dispatchEvent(new CustomEvent('yt-navigate', { bubbles: true, composed: true, detail: { endpoint } }));
}

/**
 * YTM のメニューの項目（「次に再生」「キューから削除」）と同じ道で命令を流す。
 * ytmusic-menu-service-item-renderer は、画面に付けたときにキューや画面遷移の係を受け取る。
 */
async function runMenuCommand(command: Any): Promise<void> {
  const a = app();
  if (!a) throw new Error('ytmusic-app not found');
  const el: Any = document.createElement('ytmusic-menu-service-item-renderer');
  el.hidden = true;
  el.setAttribute('data-wave', '');
  a.appendChild(el);
  try {
    const target = typeof el.handleCommand === 'function' ? el : el.inst;
    if (!target || typeof target.handleCommand !== 'function') throw new Error('menu handleCommand not found');
    await target.handleCommand(command);
  } finally {
    el.remove();
  }
}

function itemsNow(): Any[] {
  const q = queueSvc();
  return q ? q.getItems() : (reduxState()?.queue?.items ?? []);
}

function itemIdAt(i: number): string {
  const r = rendererOf(itemsNow()[i]);
  const idx = r?.navigationEndpoint?.watchEndpoint?.index;
  if (typeof idx !== 'number') throw new Error('queue item id not found');
  return String(idx);
}

async function addToQueue(videoIds: string[], position: 'next' | 'end'): Promise<void> {
  const before = itemsNow().length;
  // 一度に送る数を抑える（YTM の画面から送られるのは多くても1枚のアルバムぶん）
  const chunks: string[][] = [];
  for (let i = 0; i < videoIds.length; i += 50) chunks.push(videoIds.slice(i, i + 50));
  // 「次に」で複数回に分けると順が逆になるので、後ろの塊から入れる
  if (position === 'next') chunks.reverse();
  let expected = before;
  for (const c of chunks) {
    await runMenuCommand({
      queueAddEndpoint: {
        queueTargets: c.map(videoId => ({ videoId })),
        queueInsertPosition: position === 'next' ? 'INSERT_AFTER_CURRENT_VIDEO' : 'INSERT_AT_END',
      },
    });
    expected += c.length;
    const ok = await waitFor(() => itemsNow().length >= expected, 10000);
    if (!ok) throw new Error('queue add timed out');
  }
}

/** キューの中の並べ替え。YTM がドラッグで並べ替えたときと同じ関数に届くよう、onDrop に偽のドラッグを渡す */
function moveItem(from: number, to: number): void {
  const qe = queueEl();
  const items = itemsNow();
  if (from < 0 || from >= items.length || to < 0 || to >= items.length) throw new Error('index out of range');
  if (from === to) return;
  const list: Any[] = Array.isArray(qe?.data) ? qe.data : items;
  const it = list[from];
  if (qe && typeof qe.onDrop === 'function' && it) {
    const data = it.playlistPanelVideoRenderer ?? it.playlistPanelVideoWrapperRenderer;
    const kids: Any[] = new Array(items.length).fill(null);
    const dragEl: Any = { data, parentElement: { children: kids } };
    kids[to] = dragEl;
    qe.onDrop({ detail: { dragEl } });
    return;
  }
  const s = store();
  if (!s) throw new Error('store not found');
  s.dispatch({ type: 'MOVE_ITEM', payload: { fromIndex: from, toIndex: to } });
}

async function removeAt(index: number, videoId?: string): Promise<void> {
  const items = itemsNow();
  if (index < 0 || index >= items.length) throw new Error('index out of range');
  if (videoId && !videoIdsOf(items[index]).includes(videoId)) throw new Error('video mismatch');
  const itemId = itemIdAt(index);
  const n = items.length;
  try {
    await runMenuCommand({ removeFromQueueEndpoint: { videoId: rendererOf(items[index])?.videoId, itemId } });
  } catch {
    queueSvc()?.removeItem(itemId);
  }
  await waitFor(() => itemsNow().length < n, 3000);
}

/**
 * 曲を開いたあとに当てる「位置」と「流す・止める」。
 * 匿名だと曲の前に広告が入り、広告の前に当てたシークは消える。なので、広告でない本編の位置が
 * 狙いに届いたのを見届けるまで当て直す。ほかの指示が来たら捨てる。
 */
let pending: { videoId: string; positionMs: number; playing: boolean; until: number; tries: number } | null = null;

function applyPending(): void {
  const pd = pending;
  const p = player();
  if (!pd || !p) return;
  if (Date.now() > pd.until || pd.tries > 20) { pending = null; return; }
  if (isAd(p) || p.getVideoData()?.video_id !== pd.videoId) return;
  const st = p.getPlayerState();
  if (st !== 1 && st !== 2 && st !== 3) {
    // 広告のあとに止まったままになることがある
    if (pd.playing) { pd.tries++; p.playVideo(); }
    return;
  }
  const pos = p.getCurrentTime() * 1000;
  const reached = pd.positionMs <= 0 || (pos >= pd.positionMs - 2000 && pos <= pd.positionMs + 15000);
  if (reached) {
    if (!pd.playing && st !== 2) p.pauseVideo();
    if (pd.playing && st === 2) p.playVideo();
    if (pd.playing ? st !== 2 : st === 2) pending = null;
    return;
  }
  pd.tries++;
  p.seekTo(pd.positionMs / 1000, true);
  if (pd.playing) p.playVideo();
}

async function loadQueue(videoIds: string[], index: number, positionMs: number, playing: boolean, autoplay?: boolean): Promise<void> {
  const target = videoIds[index];
  const beforeItems = reduxState()?.queue?.items;
  if (autoplay !== undefined) setAutoplay(autoplay);
  pending = { videoId: target, positionMs, playing, until: Date.now() + 120000, tries: 0 };
  // 1曲だけを開く。キューは「その曲 ＋ 自動再生」になる
  navigate({ watchEndpoint: { videoId: target } });
  const ok = await waitFor(() => {
    const s = reduxState();
    const items: Any[] = s?.queue?.items ?? [];
    const q = queueSvc();
    const ci = q ? q.getCurrentItemIndex() : -1;
    return items !== beforeItems && ci >= 0 && videoIdsOf(items[ci]).includes(target);
  }, 15000);
  if (!ok) throw new Error('navigation timed out');
  // 開いた曲のほかに項目があれば外す（プレイリストの文脈で開いたときなど）
  for (let i = itemsNow().length - 1; i >= 0; i--) {
    if (i !== queueSvc().getCurrentItemIndex()) await removeAt(i);
  }
  if (index + 1 < videoIds.length) await addToQueue(videoIds.slice(index + 1), 'end');
  if (index > 0) {
    // 前の曲を今の曲のすぐ後ろに入れてから、今の曲をその後ろへ動かす
    await addToQueue(videoIds.slice(0, index), 'next');
    moveItem(0, index);
    await waitFor(() => queueSvc()?.getCurrentItemIndex() === index, 3000);
  }
  if (autoplay !== undefined) setAutoplay(autoplay);
  applyPending();
}

function setAutoplay(on: boolean): void {
  const s = store();
  if (!s) throw new Error('store not found');
  // キューの「自動再生」のスイッチを押したときと同じ2つ
  s.dispatch({ type: 'SET_AUTOPLAY_ENABLED', payload: on });
  s.dispatch({ type: 'HAS_USER_CHANGED_DEFAULT_AUTOPLAY_MODE', payload: true });
}

function clickBar(method: string, selector: string): boolean {
  const b = bar();
  if (b && typeof b[method] === 'function') { b[method](); return true; }
  const el = document.querySelector<HTMLElement>(`ytmusic-player-bar ${selector}`);
  if (el) { el.click(); return true; }
  return false;
}

async function setRepeat(want: Repeat): Promise<void> {
  const map: Record<Repeat, string> = { off: 'NONE', all: 'ALL', one: 'ONE' };
  for (let i = 0; i < 3 && reduxState()?.queue?.repeatMode !== map[want]; i++) {
    if (!clickBar('onRepeatButtonClick', '.repeat')) throw new Error('repeat button not found');
    await sleep(50);
  }
  if (reduxState()?.queue?.repeatMode !== map[want]) throw new Error('repeat not applied');
}

async function setLike(want: Like): Promise<void> {
  const cur = likeOf();
  if (cur === want) return;
  const root = document.querySelector('ytmusic-player-bar ytmusic-like-button-renderer');
  if (!root) throw new Error('like button not found');
  // 外すときは、今付いている方をもう一度押す
  const which = want === 'none' ? cur : want;
  const btn = root.querySelector<HTMLElement>(which === 'dislike' ? '#button-shape-dislike button' : '#button-shape-like button');
  if (!btn) throw new Error('like button not found');
  btn.click();
  if (!(await waitFor(() => likeOf() === want, 3000))) throw new Error('like not applied (signed out?)');
}

export async function runCommand(c: PageCommand): Promise<void> {
  const p = player();
  const needPlayer = () => { if (!p) throw new Error('player not ready'); return p; };
  // 新しい再生の指示が来たら、前の loadQueue の当て直しはやめる
  if (c.type !== 'getState' && c.type !== 'loadQueue' && c.type !== 'setVolume' && c.type !== 'setMuted') pending = null;
  switch (c.type) {
    case 'getState': return;
    case 'play': {
      const pl = needPlayer();
      if (isAd(pl)) { await document.querySelector('video')?.play(); return; }
      pl.playVideo(); return;
    }
    case 'pause': {
      const pl = needPlayer();
      if (isAd(pl)) { document.querySelector('video')?.pause(); return; }
      pl.pauseVideo(); return;
    }
    case 'seek': {
      const pl = needPlayer();
      if (isAd(pl)) throw new Error('ad playing');
      pl.seekTo(c.positionMs / 1000, true); return;
    }
    case 'next':
      if (!clickBar('onNextButtonClick', '.next-button')) needPlayer().nextVideo();
      return;
    case 'previous':
      if (!clickBar('onPreviousButtonClick', '.previous-button')) needPlayer().previousVideo();
      return;
    case 'setVolume': {
      const pl = needPlayer();
      pl.setVolume(c.volume);
      if (c.volume > 0 && pl.isMuted()) pl.unMute();
      // 画面の音量のつまみは redux の値を見ている
      store()?.dispatch({ type: 'SET_VOLUME', payload: c.volume });
      store()?.dispatch({ type: 'SET_MUTED', payload: pl.isMuted() });
      return;
    }
    case 'setMuted': {
      const pl = needPlayer();
      if (c.muted) pl.mute(); else pl.unMute();
      store()?.dispatch({ type: 'SET_MUTED', payload: c.muted });
      return;
    }
    case 'setRepeat': return setRepeat(c.repeat);
    case 'setShuffle':
      if (!!reduxState()?.queue?.shuffleEnabled !== c.shuffle) {
        if (!clickBar('onShuffleButtonClick', '.shuffle')) throw new Error('shuffle button not found');
      }
      return;
    case 'setAutoplay': setAutoplay(c.autoplay); return;
    case 'setLike': return setLike(c.like);
    case 'loadQueue': return loadQueue(c.videoIds, c.index, c.positionMs, c.playing, c.autoplay);
    case 'playIndex': {
      const q = queueSvc();
      const items = itemsNow();
      if (!q || c.index >= items.length) throw new Error('index out of range');
      q.selectQueueItem(items[c.index]);
      return;
    }
    case 'addToQueue': return addToQueue(c.videoIds, c.position);
    case 'remove': return removeAt(c.index, c.videoId);
    case 'move': moveItem(c.from, c.to); return;
    case 'clearUpcoming': {
      const q = queueSvc();
      if (!q) throw new Error('queue not found');
      for (let i = itemsNow().length - 1; i > q.getCurrentItemIndex(); i--) await removeAt(i);
      return;
    }
  }
}

// ---- 変化を知らせる ----

/**
 * 状態が変わったら cb を呼ぶ。プレーヤーの出来事・<video> の出来事・redux の参照の入れ替わりを見て、
 * 取りこぼしに備えて 1 秒おきにも読む。位置だけの変化（再生中に進むこと）では呼ばない。
 */
export function watchState(cb: (s: PageState) => void): () => void {
  let last = '';
  let lastState: PageState | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let stopped = false;

  const key = (s: PageState) => JSON.stringify({ ...s, positionMs: 0, sampledAt: 0 });
  // 位置が、前に知らせた位置から見込める値とずれたら（シーク・止まっていた・遅れた）知らせる
  const drifted = (s: PageState) => {
    const l = lastState;
    if (!l) return false;
    if (s.playback === 'playing' && l.playback === 'playing') {
      const expect = l.positionMs + (s.sampledAt - l.sampledAt) * l.playbackRate;
      return Math.abs(expect - s.positionMs) > 1500;
    }
    return Math.abs(s.positionMs - l.positionMs) > 500;
  };
  const check = (force = false) => {
    if (stopped) return;
    applyPending();
    let s: PageState;
    try { s = readState(); } catch { return; }
    const k = key(s);
    if (force || k !== last || drifted(s)) {
      last = k;
      lastState = s;
      cb(s);
    }
  };
  const soon = () => {
    if (timer) return;
    timer = setTimeout(() => { timer = null; check(); }, 50);
  };

  // プレーヤーの出来事
  let hooked: Any = null;
  const hookPlayer = () => {
    const p = player();
    if (!p || p === hooked) return;
    hooked = p;
    for (const ev of ['onStateChange', 'onVideoDataChange', 'onVolumeChange', 'onAdStart', 'onAdEnd']) {
      try { p.addEventListener(ev, soon); } catch { /* 古い API */ }
    }
  };
  // <video> の出来事（捕捉段階で document から拾う。要素が作り直されても届く）
  const mediaEvents = ['play', 'pause', 'playing', 'waiting', 'seeked', 'ended', 'volumechange', 'loadedmetadata', 'durationchange', 'ratechange', 'emptied'];
  for (const ev of mediaEvents) document.addEventListener(ev, soon, true);

  // redux の参照が変わったら（キュー・プレーヤーの切片）
  let refQ: unknown = null, refP: unknown = null;
  const poll = setInterval(() => {
    hookPlayer();
    const s = reduxState();
    if (s && (s.queue !== refQ || s.player !== refP)) { refQ = s.queue; refP = s.player; soon(); }
  }, 250);
  // 取りこぼしの保険。再生中は位置のずれもここで拾う
  const slow = setInterval(() => check(), 1000);
  // 評価のボタンの属性
  const mo = new MutationObserver(soon);
  const watchLike = () => {
    const el = document.querySelector('ytmusic-player-bar ytmusic-like-button-renderer');
    if (el) mo.observe(el, { attributes: true, attributeFilter: ['like-status'] });
  };
  watchLike();
  const likeTimer = setInterval(watchLike, 5000);

  hookPlayer();
  check(true);

  return () => {
    stopped = true;
    clearInterval(poll); clearInterval(slow); clearInterval(likeTimer);
    if (timer) clearTimeout(timer);
    mo.disconnect();
    for (const ev of mediaEvents) document.removeEventListener(ev, soon, true);
    const p = hooked;
    if (p) for (const ev of ['onStateChange', 'onVideoDataChange', 'onVolumeChange', 'onAdStart', 'onAdEnd']) {
      try { p.removeEventListener(ev, soon); } catch { /* 無視 */ }
    }
  };
}

export function forceState(): PageState { return readState(); }
