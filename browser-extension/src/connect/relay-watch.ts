// 中継の接続の見張り（Android・Windows・Mac・Linux の RelayWatch と同じ規則。docs/decisions.md 2026-11-14）。
// 死んだソケットを「つながっている」と思い込んだまま、ほかの端末の再生が届かないのを防ぐ。
// つなぎ始めてから 15 秒のうちに welcome が来なければ切る。つながっている間、20 秒何も届かなければ確かめを送り、
// 8 秒のうちに何も届かなければ切る。タブが前に出た・ネットにつながり直したら（wake）、すぐ確かめを送る。
// ブラウザの WebSocket は ping を送れないので、確かめは中継が答えるメッセージ（空の鎖を頼む）で行う。

export type WatchAction = "none" | "probe" | "dead";

export const WELCOME_TIMEOUT_MS = 15_000;
export const IDLE_MS = 20_000;
export const PROBE_TIMEOUT_MS = 8_000;
export const WATCH_TICK_MS = 1_000;
/** 確かめに送るもの。鎖のずっと先を頼む（中継は空の鎖を返すだけで、どの版の中継も答える）。 */
export const PROBE = '{"op":"chain_get","fromSeq":2147483647}';

export class RelayWatch {
  private openedAt = 0;
  private welcomed = false;
  private lastReceivedAt = 0;
  private probeSentAt: number | null = null;

  /** 新しいソケットでつなぎ始めた。 */
  opened(nowMs: number): void {
    this.openedAt = nowMs;
    this.welcomed = false;
    this.lastReceivedAt = nowMs;
    this.probeSentAt = null;
  }

  /** 中継から何か届いた。 */
  received(nowMs: number, welcome = false): void {
    if (welcome) this.welcomed = true;
    this.lastReceivedAt = nowMs;
    this.probeSentAt = null;
  }

  /** タブが前に出た・ネットにつながり直した。つながっていれば、すぐ確かめる。 */
  wake(nowMs: number): WatchAction {
    if (!this.welcomed || this.probeSentAt !== null) return "none";
    this.probeSentAt = nowMs;
    return "probe";
  }

  /** 1秒ごとに呼ぶ。 */
  tick(nowMs: number): WatchAction {
    if (!this.welcomed) return nowMs - this.openedAt >= WELCOME_TIMEOUT_MS ? "dead" : "none";
    if (this.probeSentAt !== null) return nowMs - this.probeSentAt >= PROBE_TIMEOUT_MS ? "dead" : "none";
    if (nowMs - this.lastReceivedAt >= IDLE_MS) {
      this.probeSentAt = nowMs;
      return "probe";
    }
    return "none";
  }
}

/**
 * つなぎ直すまでの待ち（ミリ秒）。failedAttempts は welcome を受けてから続けて失敗した数（つながっていたソケットが切れた直後は 0）。
 * 1秒、2秒、4秒、8秒、16秒、以後30秒。
 */
export function reconnectDelayMs(failedAttempts: number): number {
  return failedAttempts <= 0 ? 1_000 : Math.min(2_000 * 2 ** Math.min(failedAttempts - 1, 4), 30_000);
}
