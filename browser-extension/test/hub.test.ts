// 背景の Port の取り決め（偽の Port で、ページ・設定の画面とのやり取りを確かめる。中継にはつながない）。

import assert from "node:assert/strict";
import { test } from "node:test";
import { PortHub, type HubPort } from "../src/background/hub.ts";
import { ConnectService } from "../src/connect/service.ts";
import { MemoryStore } from "../src/connect/store.ts";
import { PROTOCOL_VERSION, type BackgroundToClient, type ConnectStatus } from "../src/shared/protocol.ts";

class FakePort implements HubPort {
  readonly received: BackgroundToClient[] = [];
  private messageListener: ((m: unknown) => void) | null = null;
  private disconnectListener: (() => void) | null = null;
  postMessage(message: BackgroundToClient): void {
    this.received.push(JSON.parse(JSON.stringify(message)) as BackgroundToClient);
  }
  onMessage = { addListener: (l: (m: unknown) => void) => void (this.messageListener = l) };
  onDisconnect = { addListener: (l: () => void) => void (this.disconnectListener = l) };
  send(message: unknown): void {
    this.messageListener?.(message);
  }
  disconnect(): void {
    this.disconnectListener?.();
  }
  async result(id: number): Promise<Extract<BackgroundToClient, { type: "result" }>> {
    for (let i = 0; i < 200; i++) {
      const found = this.received.find((m) => m.type === "result" && m.id === id);
      if (found) return found as Extract<BackgroundToClient, { type: "result" }>;
      await new Promise((r) => setTimeout(r, 5));
    }
    throw new Error(`result ${id} が来ない`);
  }
  lastStatus(): ConnectStatus | null {
    const events = this.received.filter((m) => m.type === "event" && m.event.type === "status");
    const last = events[events.length - 1];
    return last && last.type === "event" && last.event.type === "status" ? last.event.status : null;
  }
}

async function setup(): Promise<{ service: ConnectService; hub: PortHub }> {
  const service = await ConnectService.open({ store: new MemoryStore(), defaultDeviceName: "YouTube Music (試験)", autoTick: false });
  return { service, hub: new PortHub(service) };
}

test("ページがつながると今の値が届き、hello の版と ping を扱う", async () => {
  const { service, hub } = await setup();
  const page = new FakePort();
  hub.addPage(page);
  assert.deepEqual(
    page.received.filter((m) => m.type === "event").map((m) => (m.type === "event" ? m.event.type : "")),
    ["status", "devices", "remote"],
  );
  assert.equal(page.lastStatus()?.pageOpen, true);
  assert.equal(page.lastStatus()?.deviceName, "YouTube Music (試験)");
  page.send({ type: "hello", version: PROTOCOL_VERSION, id: 1 });
  assert.equal((await page.result(1)).ok, true);
  page.send({ type: "hello", version: 999, id: 2 });
  assert.equal((await page.result(2)).error, "version");
  page.send({ type: "ping", id: 3 });
  await page.result(3);
  assert.ok(page.received.some((m) => m.type === "pong"));
  service.dispose();
});

test("家に入っていなくても、状態・再生の開始・アカウントは受けて、家の再生にはしない", async () => {
  const { service, hub } = await setup();
  const page = new FakePort();
  hub.addPage(page);
  page.send({ type: "account", dataSyncId: "abc||def", id: 1 });
  assert.equal((await page.result(1)).ok, true);
  page.send({ type: "state", id: 2, state: { queue: [{ videoId: "aaaaaaaaaaA", title: "A" }], index: 0, positionMs: 0, playing: true } });
  assert.equal((await page.result(2)).ok, true);
  page.send({ type: "user_started", id: 3 });
  assert.equal((await page.result(3)).ok, false, "家に入っていないので家の再生にしない");
  page.send({ type: "take_over", id: 4 });
  assert.equal((await page.result(4)).ok, false);
  page.send({ type: "remote_control", action: "pause", id: 5 });
  assert.equal((await page.result(5)).ok, false);
  page.send({ type: "nope", id: 6 });
  assert.equal((await page.result(6)).error, "unknown");
  service.dispose();
});

test("タブが2つあれば、再生を始めたタブの状態だけを使う", async () => {
  const { service, hub } = await setup();
  const first = new FakePort();
  const second = new FakePort();
  hub.addPage(first);
  hub.addPage(second);
  const paused = { queue: [{ videoId: "aaaaaaaaaaA", title: "A" }], index: 0, positionMs: 0, playing: false };
  second.send({ type: "state", id: 1, state: paused });
  assert.equal((await second.result(1)).error, "not_active_page");
  second.send({ type: "user_started", id: 2 });
  await second.result(2);
  first.send({ type: "state", id: 3, state: paused });
  assert.equal((await first.result(3)).error, "not_active_page");
  // 今のタブが閉じると、残ったタブが今のタブになる
  second.disconnect();
  first.send({ type: "state", id: 4, state: paused });
  assert.equal((await first.result(4)).ok, true);
  first.disconnect();
  assert.equal(service.status().pageOpen, false);
  service.dispose();
});

test("設定の画面: 値を読み、宛先を保存し、QR でない文字を断る", async () => {
  const { service, hub } = await setup();
  const options = new FakePort();
  hub.addOptions(options);
  options.send({ type: "get", id: 1 });
  const got = await options.result(1);
  assert.equal((got.value as { settings: { deviceName: string } }).settings.deviceName, "YouTube Music (試験)");
  options.send({ type: "join", id: 2, offerText: "wave-pair:xxx" });
  assert.equal(((await options.result(2)).value as { reason: string }).reason, "not_configured");
  options.send({ type: "configure", id: 3, settings: { relayUrl: "ws://127.0.0.1:9", deviceName: "居間の Chrome" } });
  assert.equal((await options.result(3)).ok, true);
  assert.equal(service.status().configured, true);
  assert.equal(service.status().deviceName, "居間の Chrome");
  options.send({ type: "join", id: 4, offerText: "hello" });
  assert.equal(((await options.result(4)).value as { reason: string }).reason, "not_wave_pair");
  options.disconnect();
  service.dispose();
});
