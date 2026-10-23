// 確かめ用の MAIN world の台本（配る拡張には入れない）。
// 隔離 world の dev-content が流す状態と結果を集め、URL に #wave-dev-test(=番号) が付いていれば台本を流す。
// 指示は dev-content → Port → dev の background → Port → page-content → page-main と、本物と同じ道を通る。
// 記録は window.__dev.log に貯め、番号があれば dev-content・background を通して 127.0.0.1:番号 へ送る。
(() => {
  if (window.__dev) return;
  const KEY = 'wave-ytm-dev';
  const D = { states: [], results: {}, rid: 0, log: [], done: false };
  window.__dev = D;
  const m = /wave-dev-test(?:=(\d+))?/.exec(location.hash);
  const logPort = m && m[1] ? Number(m[1]) : null;

  window.addEventListener('message', e => {
    const d = e.data;
    if (e.source !== window || !d || typeof d !== 'object' || !d[KEY]) return;
    if (d[KEY] === 'state') { D.states.push(d.state); if (D.states.length > 400) D.states.splice(0, 100); }
    else if (d[KEY] === 'result') D.results[d.rid] = d;
  });

  const A = 'dQw4w9WgXcQ', B = 'djV11Xbc914', C = 'fJ9rUzIMcZQ', DD = 'Zi_XLOBDo_Y';
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const t0 = Date.now();
  function note(step, ok, detail) {
    const line = { t: ((Date.now() - t0) / 1000).toFixed(1), step, ok, detail };
    D.log.push(line);
    window.postMessage({ [KEY]: 'log', line, logPort }, location.origin);
  }
  const latest = () => (D.states.length ? D.states[D.states.length - 1] : null);
  async function cmd(c, timeoutMs = 60000) {
    const rid = ++D.rid;
    window.postMessage({ [KEY]: 'cmd', rid, cmd: c }, location.origin);
    const end = Date.now() + timeoutMs;
    while (!D.results[rid] && Date.now() < end) await sleep(50);
    return D.results[rid] || { result: { ok: false, error: 'no reply' } };
  }
  async function waitState(pred, timeoutMs = 20000) {
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const s = latest();
      try { if (s && pred(s)) return s; } catch { /* 続ける */ }
      await sleep(200);
    }
    return null;
  }
  const ids = s => s.queue.items.map(x => x.videoId).join(',');
  const brief = s => s && ({ v: s.track && s.track.videoId, title: s.track && s.track.title, artists: s.track && s.track.artists, album: s.track && s.track.album, ad: s.adPlaying, pb: s.playback, pos: Math.round(s.positionMs / 1000), dur: s.durationMs && Math.round(s.durationMs / 1000), vol: s.volume, muted: s.muted, rep: s.repeat, sh: s.shuffle, like: s.like, autoplay: s.queue.autoplay, items: ids(s), cur: s.queue.currentIndex, automix: s.queue.automix.length });
  async function noAd(limit = 90000) {
    const s0 = latest();
    if (s0 && s0.adPlaying) note('ad', true, 'ad playing; waiting');
    return waitState(s => !s.adPlaying, limit);
  }
  async function step(name, command, pred, timeoutMs = 10000, opt = {}) {
    const before = D.states.length;
    const r = command ? await cmd(command) : { result: { ok: true } };
    if (opt.waitAd) await noAd();
    const s = pred ? await waitState(pred, timeoutMs) : latest();
    const ok = opt.expectFail ? !r.result.ok : r.result.ok && !!s;
    note(name, ok, { result: r.result, reconnects: r.reconnects, statesSinceCmd: D.states.length - before, state: brief(s || latest()) });
    return s;
  }
  const track = s => s && s.track && s.track.videoId;

  async function scenario() {
    note('start', true, { ua: navigator.userAgent, webdriver: navigator.webdriver });
    const ready = await waitState(s => s.ready, 30000);
    note('ready (state through MAIN→isolated)', !!ready, brief(ready));

    // 1. 3曲のキューを2曲目から30秒で
    await step('loadQueue [A,B,C] index=1 at 30s → B playing from >=30s, items A,B,C cur=1',
      { type: 'loadQueue', videoIds: [A, B, C], index: 1, positionMs: 30000, playing: true },
      s => !s.adPlaying && track(s) === B && s.playback === 'playing' && s.positionMs >= 29000 && ids(s) === [A, B, C].join(',') && s.queue.currentIndex === 1,
      30000, { waitAd: true });

    // Port が静かなまま 40 秒たっても指示が通るか（Chrome の service worker の停止）
    await sleep(40000);
    await step('pause (after 40s idle)', { type: 'pause' }, s => s.playback === 'paused', 5000);
    await step('play', { type: 'play' }, s => s.playback === 'playing' || s.playback === 'buffering', 5000);
    await step('seek 90s', { type: 'seek', positionMs: 90000 }, s => s.positionMs >= 89000 && s.positionMs < 95000, 5000);
    await step('setVolume 30', { type: 'setVolume', volume: 30 }, s => s.volume === 30, 5000);
    await step('setMuted true', { type: 'setMuted', muted: true }, s => s.muted, 5000);
    await step('setMuted false', { type: 'setMuted', muted: false }, s => !s.muted, 5000);

    // 2. 次へ・前へ・番号で選ぶ（曲の変わり目を状態の流れで受け取る）
    await step('next → C', { type: 'next' }, s => !s.adPlaying && track(s) === C && s.queue.currentIndex === 2, 30000, { waitAd: true });
    await step('previous (YTM: <3s → previous item, else restart)', { type: 'previous' }, s => !s.adPlaying && (track(s) === B || s.positionMs < 3000), 30000, { waitAd: true });
    await step('playIndex 2 → C', { type: 'playIndex', index: 2 }, s => !s.adPlaying && track(s) === C, 30000, { waitAd: true });

    // 3. キューの編集
    await step('addToQueue [D] next → A,B,C,D', { type: 'addToQueue', videoIds: [DD], position: 'next' }, s => ids(s) === [A, B, C, DD].join(','));
    await step('remove index0 (A) → B,C,D', { type: 'remove', index: 0, videoId: A }, s => ids(s) === [B, C, DD].join(','));
    await step('remove with wrong videoId is refused', { type: 'remove', index: 0, videoId: DD }, null, 0, { expectFail: true });
    await step('move 2→0 → D,B,C (current stays C at 2)', { type: 'move', from: 2, to: 0 }, s => ids(s) === [DD, B, C].join(',') && track(s) === C && s.queue.currentIndex === 2);
    await step('addToQueue [A,B] end → D,B,C,A,B', { type: 'addToQueue', videoIds: [A, B], position: 'end' }, s => ids(s) === [DD, B, C, A, B].join(','));
    await step('clearUpcoming → D,B,C', { type: 'clearUpcoming' }, s => ids(s) === [DD, B, C].join(','));

    // 4. リピート・自動再生
    for (const rep of ['all', 'one', 'off']) await step(`setRepeat ${rep}`, { type: 'setRepeat', repeat: rep }, s => s.repeat === rep, 5000);
    await step('setAutoplay false', { type: 'setAutoplay', autoplay: false }, s => !s.queue.autoplay, 5000);

    // 5. 自動再生を切って最後の曲を流し終えたら止まるか
    let s = latest();
    const dur = s.durationMs || 0;
    await cmd({ type: 'seek', positionMs: Math.max(0, dur - 4000) });
    await cmd({ type: 'play' });
    const endState = await waitState(s => s.playback === 'ended' || track(s) !== C, 25000);
    await sleep(4000);
    s = latest();
    note('autoplay off: last item ends → stays ended on C', !!endState && s.playback === 'ended' && track(s) === C, { atEnd: brief(endState), after4s: brief(s) });

    // 6. 自動再生を入れて、もう一度
    await step('setAutoplay true', { type: 'setAutoplay', autoplay: true }, s => s.queue.autoplay, 5000);
    await cmd({ type: 'seek', positionMs: Math.max(0, dur - 4000) });
    await step('autoplay on: last item ends → continues with an automix track', { type: 'play' }, s => track(s) !== C && !s.adPlaying, 40000, { waitAd: true });

    // 7. 曲の終わりで次へ進むのを状態で受け取る
    s = latest();
    if (s.queue.items.length > s.queue.currentIndex + 1 || s.queue.automix.length) {
      const cur = track(s);
      await cmd({ type: 'seek', positionMs: Math.max(0, (s.durationMs || 0) - 3000) });
      await step('natural song change (track ended → next)', null, s => track(s) !== cur, 40000);
    }

    // 8. 評価（匿名ではログインを求められて付かない）
    await step('setLike like (anonymous: expected to fail)', { type: 'setLike', like: 'like' }, null, 0, { expectFail: true });

    // 9. 一番前から・止めたまま・自動再生なしで読み込む
    await step('loadQueue [C,D] index=0 at 10s paused autoplay=false → C paused at ~10s',
      { type: 'loadQueue', videoIds: [C, DD], index: 0, positionMs: 10000, playing: false, autoplay: false },
      s => !s.adPlaying && track(s) === C && s.playback === 'paused' && s.positionMs >= 9000 && s.positionMs < 13000 && ids(s) === [C, DD].join(',') && !s.queue.autoplay,
      90000, { waitAd: true });
    note('done', true, { steps: D.log.length, failed: D.log.filter(l => !l.ok).map(l => l.step) });
    D.done = true;
  }

  window.__waveDevScenario = () => scenario().catch(e => { note('exception', false, String(e && e.stack || e)); D.done = true; });
  if (m) {
    const go = () => (document.querySelector('ytmusic-app') ? window.__waveDevScenario() : setTimeout(go, 500));
    setTimeout(go, 1000);
  }
})();
