// 背景の Port の振り分け（ブラウザの API に依らない部分。試験で偽の Port を当てる）。
// ページ（music.youtube.com のタブ）が1つでもつながっている間は中継につなぎ、全部離れたら閉じる。
// タブが複数あれば、命令は「今のページ」（最後に再生を始めた・最後につながったタブ）にだけ渡し、状態もそのタブのものだけを使う。

import type { ConnectService } from "../connect/service.ts";
import { PROTOCOL_VERSION, type BackgroundToClient, type OptionsSnapshot, type OptionsToBackground, type PageToBackground } from "../shared/protocol.ts";

/** chrome.runtime.Port の使う部分だけ。 */
export interface HubPort {
  postMessage(message: BackgroundToClient): void;
  onMessage: { addListener(listener: (message: unknown) => void): void };
  onDisconnect: { addListener(listener: () => void): void };
}

export class PortHub {
  readonly service: ConnectService;
  private readonly pages: HubPort[] = [];
  private readonly options = new Set<HubPort>();
  private activePage: HubPort | null = null;

  constructor(service: ConnectService) {
    this.service = service;
    service.on((event) => {
      if (event.type === "command") {
        // 命令は今のページにだけ
        this.post(this.activePage, { type: "event", event });
        return;
      }
      for (const port of [...this.pages, ...this.options]) this.post(port, { type: "event", event });
    });
  }

  private post(port: HubPort | null, message: BackgroundToClient): void {
    if (port === null) return;
    try {
      port.postMessage(message);
    } catch {
      // 閉じた Port（onDisconnect で外す）
    }
  }

  private reply(port: HubPort, id: number | undefined, ok: boolean, value?: unknown, error?: string): void {
    if (id === undefined) return;
    this.post(port, { type: "result", id, ok, ...(value !== undefined ? { value } : {}), ...(error ? { error } : {}) });
  }

  private sendSnapshot(port: HubPort): void {
    for (const event of this.service.snapshotEvents()) this.post(port, { type: "event", event });
  }

  /** ページの Port がつながった。 */
  addPage(port: HubPort): void {
    // 数を先に増やす（その知らせはほかの Port へ。このページへは下でまとめて今の値を送る）
    this.service.pageConnected();
    this.pages.push(port);
    this.activePage ??= port;
    port.onMessage.addListener((message) => void this.onPageMessage(port, message as PageToBackground));
    port.onDisconnect.addListener(() => {
      const at = this.pages.indexOf(port);
      if (at < 0) return;
      this.pages.splice(at, 1);
      if (this.activePage === port) this.activePage = this.pages[this.pages.length - 1] ?? null;
      this.service.pageDisconnected();
    });
    this.sendSnapshot(port);
  }

  /** 設定の画面の Port がつながった（開いている間は中継につなぐ）。 */
  addOptions(port: HubPort): void {
    this.options.add(port);
    this.service.acquire();
    port.onMessage.addListener((message) => void this.onOptionsMessage(port, message as OptionsToBackground));
    port.onDisconnect.addListener(() => {
      if (this.options.delete(port)) this.service.release();
    });
    this.sendSnapshot(port);
  }

  private async onPageMessage(port: HubPort, message: PageToBackground): Promise<void> {
    const s = this.service;
    try {
      switch (message.type) {
        case "hello":
          if (message.version !== PROTOCOL_VERSION) return this.reply(port, message.id, false, undefined, "version");
          return this.reply(port, message.id, true);
        case "ping":
          this.post(port, { type: "pong" });
          return this.reply(port, message.id, true);
        case "account":
          await s.setAccount(message.dataSyncId);
          return this.reply(port, message.id, true);
        case "state":
          // ほかのタブが鳴らしている間は、そのタブの状態だけを使う
          if (this.activePage !== port) {
            if (!message.state.playing) return this.reply(port, message.id, false, undefined, "not_active_page");
            this.activePage = port;
          }
          await s.publishState(message.state);
          return this.reply(port, message.id, true);
        case "user_started":
          this.activePage = port;
          return this.reply(port, message.id, await s.userStartedPlayback());
        case "ended":
          if (this.activePage !== port) return this.reply(port, message.id, false, undefined, "not_active_page");
          await s.endLocal();
          return this.reply(port, message.id, true);
        case "transfer_to":
          return this.reply(port, message.id, await s.transferTo(message.deviceId));
        case "take_over":
          this.activePage = port;
          return this.reply(port, message.id, await s.takeOver());
        case "remote_control":
          return this.reply(port, message.id, await s.sendControl(message.action, message.positionMs));
        case "remote_volume":
          return this.reply(port, message.id, await s.sendVolume(message.volumePercent));
        case "remote_shuffle":
          return this.reply(port, message.id, await s.sendShuffle(message.on));
        case "remote_repeat":
          return this.reply(port, message.id, await s.sendRepeat(message.mode));
        case "remote_queue_add":
          return this.reply(port, message.id, await s.sendQueueAdd(message.tracks, message.next));
        case "remote_queue_move":
          return this.reply(port, message.id, await s.sendQueueMove(message.itemId, message.afterItemId));
        case "remote_queue_remove":
          return this.reply(port, message.id, await s.sendQueueRemove(message.itemIds));
        case "remote_queue_clear_flow":
          return this.reply(port, message.id, await s.sendQueueClearFlow(message.currentItemId));
        default:
          return this.reply(port, (message as { id?: number }).id, false, undefined, "unknown");
      }
    } catch (e) {
      this.reply(port, message.id, false, undefined, e instanceof Error ? e.name : "failed");
    }
  }

  private async onOptionsMessage(port: HubPort, message: OptionsToBackground): Promise<void> {
    const s = this.service;
    try {
      switch (message.type) {
        case "get": {
          const value: OptionsSnapshot = { settings: s.getSettings(), events: s.snapshotEvents() };
          return this.reply(port, message.id, true, value);
        }
        case "configure":
          await s.configure(message.settings);
          return this.reply(port, message.id, true, s.getSettings());
        case "join": {
          const view = await s.join(message.offerText);
          return this.reply(port, message.id, view.state === "show_code", view);
        }
        case "cancel_join":
          await s.cancelJoin();
          return this.reply(port, message.id, true);
        case "forget":
          await s.forgetHousehold();
          return this.reply(port, message.id, true);
        case "accept_store_reset":
          await s.acceptStoreReset();
          return this.reply(port, message.id, true);
        case "transfer_to":
          return this.reply(port, message.id, await s.transferTo(message.deviceId));
        default:
          return this.reply(port, (message as { id: number }).id, false, undefined, "unknown");
      }
    } catch (e) {
      this.reply(port, message.id, false, undefined, e instanceof Error ? e.name : "failed");
    }
  }
}
