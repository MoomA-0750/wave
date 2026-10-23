// MAIN world の入口。manifest の content_scripts に "world": "MAIN" で入れる。
// 拡張の API には触らない。できるのは YTM のプレーヤーを読む・動かすことだけ。
// 隔離 world（page-content）とは window.postMessage で話す。

import { BRIDGE_NS, BRIDGE_VERSION, type BridgeMessage, type PageState, parseCommand, readBridge } from './protocol';
import { readState, runCommand, watchState } from './ytm';

declare global {
  interface Window { __waveYtmMain?: boolean }
}

/** ytcfg のログイン。ytcfg がまだ無ければ undefined、ログインしていなければ null。 */
function readAccount(): string | null | undefined {
  const cfg = (window as unknown as { ytcfg?: { get?: (k: string) => unknown } }).ytcfg;
  if (!cfg || typeof cfg.get !== 'function') return undefined;
  const loggedIn = cfg.get('LOGGED_IN');
  if (loggedIn === false) return null;
  const id = cfg.get('DATASYNC_ID');
  if (typeof id === 'string' && id.length > 0) return id;
  return loggedIn === true ? undefined : null;
}

function post(m: BridgeMessage): void {
  window.postMessage(m, location.origin);
}

function start(): void {
  // 二度読まれても一つだけ動かす
  if (window.__waveYtmMain) return;
  window.__waveYtmMain = true;

  // ログイン（DATASYNC_ID）は変わったときだけ送る。値はページの外へは隔離 world までしか出さない
  let sentAccount: string | null | undefined;
  const sendAccount = (force: boolean) => {
    const account = readAccount();
    if (account === undefined || (!force && account === sentAccount)) return;
    sentAccount = account;
    post({ ns: BRIDGE_NS, v: BRIDGE_VERSION, dir: 'toContent', kind: 'account', dataSyncId: account });
  };
  setInterval(() => sendAccount(false), 2000);

  const sendState = (state: PageState) => post({ ns: BRIDGE_NS, v: BRIDGE_VERSION, dir: 'toContent', kind: 'state', state });

  window.addEventListener('message', ev => {
    if (ev.source !== window) return;
    const m = readBridge(ev.data, 'toMain');
    if (!m) return;
    if (m.kind === 'hello') {
      sendAccount(true);
      try { sendState(readState()); } catch { /* まだ画面が無い */ }
      return;
    }
    if (m.kind !== 'command' || typeof m.id !== 'number') return;
    const id = m.id;
    const cmd = parseCommand(m.command);
    if (!cmd) {
      post({ ns: BRIDGE_NS, v: BRIDGE_VERSION, dir: 'toContent', kind: 'result', id, result: { ok: false, error: 'bad command' } });
      return;
    }
    runCommand(cmd).then(
      () => {
        post({ ns: BRIDGE_NS, v: BRIDGE_VERSION, dir: 'toContent', kind: 'result', id, result: { ok: true } });
        try { sendState(readState()); } catch { /* 無視 */ }
      },
      (e: unknown) => post({ ns: BRIDGE_NS, v: BRIDGE_VERSION, dir: 'toContent', kind: 'result', id, result: { ok: false, error: String((e as Error)?.message ?? e) } }),
    );
  });

  // ytmusic-app ができるまで待ってから見張る
  const begin = () => {
    if (!document.querySelector('ytmusic-app')) { setTimeout(begin, 200); return; }
    watchState(sendState);
  };
  begin();
}

start();
