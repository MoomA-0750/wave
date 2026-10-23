// 隔離 world の入口。manifest の content_scripts に普通に（world を付けずに）入れる。
// YTM のタブの状態を background へ Port で送り、background からの指示をページへ渡す。
//
// Port の約束（名前は PORT_NAME = 'wave-ytm-page'）:
//   タブ → background: { type: 'state', state: PageState } … 変わるたびと、つないだ直後
//                     { type: 'result', id, result: CommandResult } … 指示の結果
//   background → タブ: { type: 'command', id, command: PageCommand }
// background が落ちて Port が切れたら（Chrome の service worker）、少し待ってつなぎ直す。
// 切れているあいだに background から送られた指示は届かない（background 側でつなぎ直しを待って送り直す）。

import { PORT_NAME, type PortToBackground, parseCommand } from './protocol';
import { createYtmPage, type YtmPage } from './ytm-page';

/* eslint-disable @typescript-eslint/no-explicit-any */
type Port = { postMessage(m: unknown): void; disconnect(): void; onMessage: { addListener(f: (m: any) => void): void }; onDisconnect: { addListener(f: () => void): void } };

function runtime(): { connect(info: { name: string }): Port } | null {
  const g = globalThis as any;
  return (g.browser?.runtime ?? g.chrome?.runtime) || null;
}

export interface PageContent {
  page: YtmPage;
  /** 今つながっている Port に送る（つながっていなければ捨てる） */
  send(m: PortToBackground): void;
  /** background から指示が来たときに呼ぶ（dev の確かめ用） */
  onPortMessage(cb: (m: unknown) => void): void;
  isConnected(): boolean;
  /** Port が切れた回数 */
  reconnects(): number;
}

export function startPageContent(): PageContent {
  const page = createYtmPage();
  let port: Port | null = null;
  let reconnects = 0;
  const extra: ((m: unknown) => void)[] = [];

  const send = (m: PortToBackground | Record<string, unknown>) => {
    try { port?.postMessage(m); } catch { /* 切れた */ }
  };

  const connect = () => {
    const rt = runtime();
    if (!rt) return;
    let p: Port;
    try { p = rt.connect({ name: PORT_NAME }); } catch { return; } // 拡張が更新・無効化された
    port = p;
    p.onMessage.addListener(async (m: any) => {
      for (const f of extra) f(m);
      if (!m || m.type !== 'command' || typeof m.id !== 'number') return;
      const cmd = parseCommand(m.command);
      const result = cmd ? await page.command(cmd) : { ok: false, error: 'bad command' };
      send({ type: 'result', id: m.id, result });
    });
    p.onDisconnect.addListener(() => {
      if (port === p) port = null;
      reconnects++;
      setTimeout(connect, 1000);
    });
    const s = page.latest();
    if (s) send({ type: 'state', state: s });
  };

  page.onState(state => send({ type: 'state', state }));
  connect();
  // 変化が無くても 20 秒おきに読み直して送る。位置の見込みを直すのと、
  // Chrome の service worker が 30 秒の無音で止まって Port が切れるのを防ぐため
  setInterval(() => { if (port) void page.command({ type: 'getState' }, 5000); }, 20000);

  return { page, send, onPortMessage: cb => { extra.push(cb); }, isConnected: () => port !== null, reconnects: () => reconnects };
}

export const pageContent = startPageContent();
