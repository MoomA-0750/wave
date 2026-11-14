// 中継の接続の見張り（Android・Windows・Mac・Linux の RelayWatch と同じ場面）と、RelayConnection での使われ方。

import assert from "node:assert/strict";
import { mock, test } from "node:test";
import { RelayConnection } from "../src/connect/relay-connection.ts";
import { PROBE, RelayWatch, reconnectDelayMs } from "../src/connect/relay-watch.ts";

test("つなぎ始めて15秒のうちに welcome が来なければ切る", () => {
  const watch = new RelayWatch();
  watch.opened(0);
  assert.equal(watch.tick(14_999), "none");
  // challenge だけ届いても welcome でなければ待ち続けない
  watch.received(5_000);
  assert.equal(watch.tick(15_000), "dead");
});

test("20秒何も届かなければ確かめを送り、8秒返事が無ければ切る", () => {
  const watch = new RelayWatch();
  watch.opened(0);
  watch.received(1_000, true);
  assert.equal(watch.tick(20_999), "none");
  assert.equal(watch.tick(21_000), "probe");
  assert.equal(watch.tick(28_999), "none");
  assert.equal(watch.tick(29_000), "dead");
});

test("何か届けばソケットを残す", () => {
  const watch = new RelayWatch();
  watch.opened(0);
  watch.received(0, true);
  assert.equal(watch.tick(20_000), "probe");
  watch.received(20_300);
  assert.equal(watch.tick(29_000), "none");
  assert.equal(watch.tick(40_300), "probe");
});

test("前に出たら、つながっているときだけすぐ確かめる", () => {
  const watch = new RelayWatch();
  watch.opened(0);
  assert.equal(watch.wake(100), "none");
  watch.received(200, true);
  assert.equal(watch.wake(300), "probe");
  assert.equal(watch.wake(400), "none");
  assert.equal(watch.tick(8_300), "dead");
});

test("つなぎ直しの待ちは1秒から始まり30秒で止まる", () => {
  assert.deepEqual([0, 1, 2, 3, 4, 5, 6].map(reconnectDelayMs), [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000]);
});

/** 中継の代わり（送られたものを覚え、こちらから welcome などを渡せる）。 */
class FakeSocket {
  static all: FakeSocket[] = [];
  readyState = 0;
  sent: string[] = [];
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: ((event: { code: number; reason: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  onopen: (() => void) | null = null;
  readonly url: string;
  constructor(url: string) {
    this.url = url;
    FakeSocket.all.push(this);
  }
  send(text: string): void {
    this.sent.push(text);
  }
  close(): void {
    this.readyState = 3;
  }
  deliver(text: string): void {
    this.readyState = 1;
    this.onmessage?.({ data: text });
  }
}

test("返事の無くなった中継に気づいてつなぎ直し、前に出たときはすぐ確かめる", async () => {
  mock.timers.enable({ apis: ["setInterval", "setTimeout"] });
  try {
    FakeSocket.all = [];
    let now = 0;
    const statuses: string[] = [];
    let lost = 0;
    const relay = new RelayConnection({
      baseUrl: "wss://relay.test:8443",
      relayHost: "relay.test:8443",
      identityPub: new Uint8Array(65),
      sign: async () => new Uint8Array(8),
      deliver: () => {},
      onConnectionLost: () => lost++,
      onStatus: (s) => statuses.push(s),
      webSocket: FakeSocket as unknown as typeof WebSocket,
      nowMs: () => now,
    });
    const advance = (ms: number) => {
      for (let i = 0; i < ms / 1_000; i++) {
        now += 1_000;
        mock.timers.tick(1_000);
      }
    };
    relay.acquire();
    const first = FakeSocket.all[0]!;
    first.deliver('{"op":"welcome","deviceId":"d","storeId":"s"}');
    await Promise.resolve();
    assert.equal(relay.status, "connected");

    // 前に出た: 20 秒を待たずに確かめを送る
    relay.wake();
    assert.deepEqual(first.sent, [PROBE]);
    first.deliver('{"op":"chain","records":[]}');

    // その後、中継が黙った（スリープの間に切れた）: 20 秒で確かめ、8 秒で切ってつなぎ直す
    advance(20_000);
    assert.equal(first.sent.length, 2);
    advance(8_000);
    assert.equal(relay.status, "failed");
    assert.equal(lost, 1);
    advance(1_000);
    assert.equal(FakeSocket.all.length, 2, "1秒でつなぎ直す");
    relay.release();
  } finally {
    mock.timers.reset();
  }
});
