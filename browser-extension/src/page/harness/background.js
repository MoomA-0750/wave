// 確かめ用の background。Port の行き来を通すためだけにある。
// タブから { type: 'devRelay', rid, cmd } が来たら、本物と同じ { type: 'command' } にして送り返し、
// 結果を { type: 'devResult' } でタブへ返す。届いた状態の数も数える。
const api = globalThis.browser ?? globalThis.chrome;

api.runtime.onConnect.addListener(port => {
  if (port.name !== 'wave-ytm-page') return;
  let states = 0;
  let last = null;
  port.onMessage.addListener(m => {
    if (!m || typeof m !== 'object') return;
    if (m.type === 'state') { states++; last = m.state; return; }
    if (m.type === 'devLog' && Number.isInteger(m.logPort)) {
      // 台本の記録を手元の受け口（harness/run.mjs）へ
      fetch(`http://127.0.0.1:${m.logPort}/log`, { method: 'POST', body: JSON.stringify(m.line) }).catch(() => {});
      return;
    }
    if (m.type === 'devRelay') { port.postMessage({ type: 'command', id: m.rid, command: m.cmd }); return; }
    if (m.type === 'result') {
      port.postMessage({ type: 'devResult', id: m.id, result: m.result, statesSeen: states, lastVideoId: last && last.track ? last.track.videoId : null });
    }
  });
});
