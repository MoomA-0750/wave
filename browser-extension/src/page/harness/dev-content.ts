// 確かめ用の隔離 world の入口。本物の page-content をそのまま動かし、
// ページから（自動の確かめの道具から）指示を受けて Port 経由で流す。配る拡張には入れない。

import { pageContent } from '../page-content';

/* eslint-disable @typescript-eslint/no-explicit-any */
const DEV = 'wave-ytm-dev';

pageContent.page.onState(state => window.postMessage({ [DEV]: 'state', state }, location.origin));

pageContent.onPortMessage((m: any) => {
  if (m && m.type === 'devResult') {
    window.postMessage({ [DEV]: 'result', rid: m.id, result: m.result, statesSeen: m.statesSeen, lastVideoId: m.lastVideoId, reconnects: pageContent.reconnects() }, location.origin);
  }
});

window.addEventListener('message', ev => {
  const d: any = ev.data;
  if (ev.source !== window || !d) return;
  if (d[DEV] === 'log' && typeof d.logPort === 'number') {
    pageContent.send({ type: 'devLog', logPort: d.logPort, line: d.line } as any);
    return;
  }
  if (d[DEV] !== 'cmd' || typeof d.rid !== 'number') return;
  if (!pageContent.isConnected()) {
    window.postMessage({ [DEV]: 'result', rid: d.rid, result: { ok: false, error: 'port not connected' }, reconnects: pageContent.reconnects() }, location.origin);
    return;
  }
  pageContent.send({ type: 'devRelay', rid: d.rid, cmd: d.cmd } as any);
});

window.postMessage({ [DEV]: 'ready' }, location.origin);
