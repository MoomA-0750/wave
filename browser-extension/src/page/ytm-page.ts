// 隔離 world から YTM のタブを扱う窓口。MAIN world の page-main と window.postMessage で話す。
// MAIN から来るものはページが偽れるので、必ず protocol の parse* を通す。
// ここで受けた状態・結果に拡張の権限を使う判断を任せない（音を鳴らす・止める程度のことしか信じない）。

import {
  BRIDGE_NS, BRIDGE_VERSION, type BridgeMessage, type CommandResult, type PageCommand, type PageState,
  parseCommand, parsePageState, parseResult, readBridge,
} from './protocol';

export interface YtmPage {
  /** 状態が変わるたびに呼ぶ。登録した時点で知っている状態があれば、すぐ1回呼ぶ。戻り値で登録をやめる */
  onState(cb: (s: PageState) => void): () => void;
  /** 最後に受けた状態 */
  latest(): PageState | null;
  /** 指示を送り、ページでやり終えるのを待つ。形の悪い指示は送らずに失敗を返す */
  command(cmd: PageCommand, timeoutMs?: number): Promise<CommandResult>;
  dispose(): void;
}

export function createYtmPage(): YtmPage {
  let state: PageState | null = null;
  const listeners = new Set<(s: PageState) => void>();
  const waiting = new Map<number, { resolve: (r: CommandResult) => void; timer: ReturnType<typeof setTimeout> }>();
  let nextId = 1;

  const post = (m: BridgeMessage) => window.postMessage(m, location.origin);

  const onMessage = (ev: MessageEvent) => {
    if (ev.source !== window) return;
    const m = readBridge(ev.data, 'toContent');
    if (!m) return;
    if (m.kind === 'state') {
      const s = parsePageState(m.state);
      if (!s) return;
      state = s;
      for (const cb of listeners) {
        try { cb(s); } catch (e) { console.error('[wave] onState', e); }
      }
    } else if (m.kind === 'result' && typeof m.id === 'number') {
      const w = waiting.get(m.id);
      if (!w) return;
      waiting.delete(m.id);
      clearTimeout(w.timer);
      w.resolve(parseResult(m.result));
    }
  };
  window.addEventListener('message', onMessage);
  post({ ns: BRIDGE_NS, v: BRIDGE_VERSION, dir: 'toMain', kind: 'hello' });

  return {
    onState(cb) {
      listeners.add(cb);
      if (state) cb(state);
      return () => listeners.delete(cb);
    },
    latest: () => state,
    command(cmd, timeoutMs = 30000) {
      const c = parseCommand(cmd);
      if (!c) return Promise.resolve({ ok: false, error: 'bad command' });
      const id = nextId++;
      return new Promise<CommandResult>(resolve => {
        const timer = setTimeout(() => { waiting.delete(id); resolve({ ok: false, error: 'timeout' }); }, timeoutMs);
        waiting.set(id, { resolve, timer });
        post({ ns: BRIDGE_NS, v: BRIDGE_VERSION, dir: 'toMain', kind: 'command', id, command: c });
      });
    },
    dispose() {
      window.removeEventListener('message', onMessage);
      listeners.clear();
      for (const [, w] of waiting) { clearTimeout(w.timer); w.resolve({ ok: false, error: 'disposed' }); }
      waiting.clear();
    },
  };
}
