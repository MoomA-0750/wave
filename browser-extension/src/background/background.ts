// 拡張の背景（Chrome は service worker、Firefox は event page）。ConnectService を1つ持ち、ページと設定の画面の Port を受ける。
// MV3 の背景は 30 秒で止まるので、ページの Port が ping を送り続けている間（music.youtube.com のタブが開いている間）だけ起きている。
// 止まって起き直したら、保存（IndexedDB）から開き直す。Port の受け口はすぐに登録し、開き終わるまでに届いたものは取っておいて後で渡す。

import { ConnectService } from "../connect/service.ts";
import { IndexedDbStore } from "../connect/store.ts";
import { browserName, ext } from "../shared/browser.ts";
import { OPTIONS_PORT, PAGE_PORT, type BackgroundToClient } from "../shared/protocol.ts";
import { PortHub, type HubPort } from "./hub.ts";

const PAGE_ORIGIN = "https://music.youtube.com/";

/** 背景を始める（試験用の背景もこれを呼ぶ）。開き終わった Port の振り分けを返す。 */
export function startBackground(): Promise<PortHub> {
  const ready: Promise<PortHub> = ConnectService.open({
    store: new IndexedDbStore(),
    defaultDeviceName: `YouTube Music (${browserName()})`,
    log: (message) => console.debug(message),
  }).then((service) => new PortHub(service));
  listen(ready);
  return ready;
}

/** 背景が開き終わるまでに届いたメッセージと切断を取っておく Port。 */
function buffered(port: chrome.runtime.Port): HubPort {
  const queued: unknown[] = [];
  let disconnected = false;
  let onMessage: ((message: unknown) => void) | null = null;
  let onDisconnect: (() => void) | null = null;
  port.onMessage.addListener((message: unknown) => (onMessage ? onMessage(message) : queued.push(message)));
  port.onDisconnect.addListener(() => {
    disconnected = true;
    onDisconnect?.();
  });
  return {
    postMessage: (message: BackgroundToClient) => {
      if (!disconnected) port.postMessage(message);
    },
    onMessage: {
      addListener: (listener) => {
        onMessage = listener;
        for (const message of queued.splice(0)) listener(message);
      },
    },
    onDisconnect: {
      addListener: (listener) => {
        onDisconnect = listener;
        if (disconnected) listener();
      },
    },
  };
}

function listen(ready: Promise<PortHub>): void {
  ext.runtime.onConnect.addListener((port) => {
    const senderUrl = port.sender?.url ?? "";
    const fromExtension = port.sender?.id === ext.runtime.id;
    if (port.name === PAGE_PORT && fromExtension && senderUrl.startsWith(PAGE_ORIGIN)) {
      const hubPort = buffered(port);
      void ready.then((hub) => hub.addPage(hubPort));
    } else if (port.name === OPTIONS_PORT && fromExtension && senderUrl.startsWith(ext.runtime.getURL(""))) {
      const hubPort = buffered(port);
      void ready.then((hub) => hub.addOptions(hubPort));
    } else {
      port.disconnect();
    }
  });
}
