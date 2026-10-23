// 設定の画面（ツールバーの小窓と、拡張の設定の両方）。中継の宛先・端末名、QR の文字での加入、家の端末の一覧を扱う。
// 開いている間は背景と Port でつながり（その間は中継につなぐ）、背景からの出来事で表示を描き直す。

import { ext } from "../shared/browser.ts";
import {
  OPTIONS_PORT,
  type BackgroundToClient,
  type ConnectEvent,
  type ConnectStatus,
  type DeviceView,
  type OptionsSnapshot,
  type OptionsToBackground,
  type PairingView,
  type RemoteView,
} from "../shared/protocol.ts";

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const port = ext.runtime.connect({ name: OPTIONS_PORT });
let nextId = 1;
const pending = new Map<number, (result: { ok: boolean; value?: unknown; error?: string }) => void>();

type Request = OptionsToBackground extends infer T ? (T extends unknown ? Omit<T, "id"> : never) : never;

function request(message: Request): Promise<{ ok: boolean; value?: unknown; error?: string }> {
  const id = nextId++;
  return new Promise((resolve) => {
    pending.set(id, resolve);
    port.postMessage({ ...message, id });
  });
}

let status: ConnectStatus | null = null;
let devices: DeviceView[] = [];
let remote: RemoteView | null = null;

const relayLabels: Record<string, string> = {
  connected: "中継につながっています",
  connecting: "中継につないでいます…",
  disconnected: "中継につないでいません",
  not_member: "中継に届きますが、この端末はまだ家に入っていません",
  failed: "中継につながりません（宛先と Tailscale を確かめてください）",
};

const pairingReasons: Record<string, string> = {
  not_configured: "先に中継の宛先を保存してください",
  already_joined: "この端末はもう家に入っています",
  not_wave_pair: "wave の連携の文字ではありません",
  relay_unreachable: "中継につながりませんでした",
  peer_not_waiting: "相手が待っていません。相手でもう一度「端末を追加」を押してください",
  bad_reply: "相手から届いた内容を読めませんでした",
  peer_unverified: "相手を確かめられませんでした",
  account_mismatch: "相手は別のアカウントでログインしています",
  peer_unsupported: "相手のアプリが古く、加われません",
  join_timed_out: "加われませんでした（時間切れ）",
  chain_unverified: "端末の一覧を確かめられませんでした",
};

function renderStatus(): void {
  if (!status) return;
  const parts = [status.configured ? relayLabels[status.relay] ?? status.relay : "中継の宛先が設定されていません"];
  if (status.joined) parts.push(status.isWriter ? "このタブで家の再生を鳴らしています" : "家に入っています");
  if (status.joined && !status.pageOpen) parts.push("music.youtube.com のタブを開くと、このブラウザで鳴らせます");
  else if (status.joined && status.accountTag === "none") parts.push("YouTube Music のログインを確かめられません");
  $("status").textContent = parts.join("。");
  $("pairing-section").hidden = status.joined && status.pairing.state !== "joined";
  $("accept-reset").hidden = !status.storeResetPending;
  renderPairing(status.pairing);
}

function renderPairing(view: PairingView): void {
  const codeBox = $("code-box");
  codeBox.hidden = view.state !== "show_code";
  if (view.state === "show_code") {
    $("code").textContent = view.code;
    $("code-peer").textContent = view.peerName;
  }
  const message =
    view.state === "working"
      ? "相手に送っています…"
      : view.state === "show_code"
        ? "相手が加えるのを待っています…"
        : view.state === "joined"
          ? `家に加わりました${view.peerName ? `（${view.peerName}）` : ""}`
          : view.state === "failed"
            ? (pairingReasons[view.reason] ?? "加われませんでした")
            : "";
  $("pairing-message").textContent = message;
  ($("join") as HTMLButtonElement).disabled = view.state === "working" || view.state === "show_code";
  $("cancel-join").hidden = !(view.state === "working" || view.state === "show_code");
}

function renderDevices(): void {
  const list = $("devices");
  list.replaceChildren();
  for (const d of devices) {
    const li = document.createElement("li");
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = d.self ? `${d.name}（この端末）` : d.name;
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = [d.kind === "api" ? "wave API" : d.kind === "pc" ? "PC" : d.kind === "phone" ? "スマホ" : "", d.online ? "オンライン" : "オフライン", d.active ? "再生中の端末" : ""]
      .filter(Boolean)
      .join("・");
    li.append(name, meta);
    if (d.playable && !d.active && status?.joined && (!d.self || status.pageOpen)) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "secondary";
      button.textContent = "ここで再生";
      button.disabled = !d.online && !d.self;
      button.onclick = () => void request({ type: "transfer_to", deviceId: d.deviceId });
      li.append(button);
    }
    list.append(li);
  }
  $("remote").textContent =
    remote && remote.track ? `${remote.deviceName}で${remote.playing ? "再生中" : "一時停止中"}: ${remote.track.title}${remote.accountMatches ? "" : "（別のアカウント）"}` : "";
}

function onEvent(event: ConnectEvent): void {
  switch (event.type) {
    case "status":
      status = event.status;
      renderStatus();
      renderDevices();
      break;
    case "devices":
      devices = event.devices;
      renderDevices();
      break;
    case "remote":
      remote = event.remote;
      renderDevices();
      break;
    case "notice":
      $("settings-message").textContent = event.text;
      break;
    case "command":
      break;
  }
}

port.onMessage.addListener((raw: unknown) => {
  const message = raw as BackgroundToClient;
  if (message.type === "event") onEvent(message.event);
  if (message.type === "result") {
    pending.get(message.id)?.(message);
    pending.delete(message.id);
  }
});

async function load(): Promise<void> {
  const result = await request({ type: "get" });
  if (!result.ok) return;
  const snapshot = result.value as OptionsSnapshot;
  ($("relay-url") as HTMLInputElement).value = snapshot.settings.relayUrl;
  ($("relay-host") as HTMLInputElement).value = snapshot.settings.relayHost;
  ($("device-name") as HTMLInputElement).value = snapshot.settings.deviceName;
  for (const event of snapshot.events) onEvent(event);
}

$("save").onclick = async () => {
  const relayUrl = ($("relay-url") as HTMLInputElement).value.trim();
  if (relayUrl && !/^wss?:\/\//.test(relayUrl)) {
    $("settings-message").textContent = "宛先は wss:// か ws:// で始めてください";
    return;
  }
  const result = await request({
    type: "configure",
    settings: {
      relayUrl,
      relayHost: ($("relay-host") as HTMLInputElement).value.trim(),
      deviceName: ($("device-name") as HTMLInputElement).value.trim(),
    },
  });
  $("settings-message").textContent = result.ok ? "保存しました" : "保存できませんでした";
};

$("join").onclick = async () => {
  const offerText = ($("offer") as HTMLTextAreaElement).value.trim();
  if (!offerText) return;
  await request({ type: "join", offerText });
};

$("cancel-join").onclick = () => void request({ type: "cancel_join" });

$("accept-reset").onclick = () => void request({ type: "accept_store_reset" });

$("forget").onclick = async () => {
  if (!confirm("この端末の家の記録を消します。もう一度加わるには、家の端末で「端末を追加」からやり直します。消しますか？")) return;
  await request({ type: "forget" });
  $("settings-message").textContent = "家の記録を消しました";
};

void load();
