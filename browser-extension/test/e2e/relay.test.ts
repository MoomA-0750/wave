// 本物の中継（relay/ の Kotlin の中継）を立ち上げ、拡張の ConnectService（B）が QR の文字で家に加わり、
// JS の端末（スマホの代わり）と wave-api（Kotlin の端末）と、状態・命令・引き取りをやり取りする。
// 先に `./gradlew :relay:installDist :api:installDist` で Kotlin の側を組んでおくこと（無ければ飛ばす）。

import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { ConnectService, type ConnectEvent, type PageCommand, type PageState } from "../../src/connect/service.ts";
import { MemoryStore } from "../../src/connect/store.ts";
import { decodeQueue } from "../../src/connect/queue.ts";
import { kotlinBuilt, Phone, startApi, startRelay, waitFor, Workspace, type Proc, type WaveApi } from "./harness.ts";

const skip = kotlinBuilt() ? false : "Kotlin の中継・wave-api が組まれていない（./gradlew :relay:installDist :api:installDist）";

let workspace: Workspace;
let relayProc: Proc;
let relay: { url: string; host: string };
let phone: Phone;
let api: WaveApi;
let ext: ConnectService;
const store = new MemoryStore();
const commands: PageCommand[] = [];
const notices: string[] = [];

const tracks = [
  { videoId: "abcdefghijA", title: "一曲目", artists: [{ name: "歌手A" }], durationMs: 200_000, thumbnailUrl: "https://i.ytimg.com/vi/abcdefghijA/hqdefault.jpg" },
  { videoId: "abcdefghijB", title: "二曲目", artists: [{ name: "歌手B" }], durationMs: 180_000 },
  { videoId: "abcdefghijC", title: "三曲目", artists: [{ name: "歌手C" }], durationMs: 240_000, origin: "suggestion" as const },
];

function state(over: Partial<PageState> = {}): PageState {
  return { queue: tracks, index: 0, positionMs: 10_000, playing: true, repeat: "off", shuffle: false, volumePercent: 70, ...over };
}

async function nextCommand<K extends PageCommand["kind"]>(kind: K, timeoutMs = 20_000): Promise<Extract<PageCommand, { kind: K }>> {
  const found = await waitFor(`ページへの命令 ${kind}`, () => commands.find((c) => c.kind === kind), timeoutMs);
  commands.splice(commands.indexOf(found), 1);
  return found as Extract<PageCommand, { kind: K }>;
}

before(async () => {
  if (skip) return;
  workspace = new Workspace();
  phone = await Phone.create();
  const started = await startRelay(workspace, phone.device.identityPub);
  relayProc = started.proc;
  relay = { url: started.url, host: started.host };
  await phone.connect(relay);
  await phone.createHousehold("account-1");
  api = await startApi(workspace, relay);
  ext = await ConnectService.open({ store, defaultDeviceName: "YouTube Music (試験)", log: () => {} });
  ext.on((e: ConnectEvent) => {
    if (e.type === "command") commands.push(e.command);
    if (e.type === "notice") notices.push(e.code);
  });
  await ext.configure({ relayUrl: relay.url });
  ext.pageConnected();
});

after(() => {
  if (skip) return;
  ext?.dispose();
  phone?.stop();
  api?.proc.stop();
  relayProc?.stop();
  workspace?.cleanup();
});

test("拡張が QR の文字と6桁で、ログインなし（wave-accountless-v1）の player として家に加わる", { skip }, async () => {
  const offer = await phone.offer("account-1");
  let shownCode = "";
  const accepted = offer.accept(async () => {
    const view = await waitFor("6桁が出る", () => {
      const p = ext.status().pairing;
      return p.state === "show_code" ? p : null;
    });
    shownCode = view.code;
    return view.code;
  });
  const joining = ext.join(offer.text);
  const view = await joining;
  assert.equal(view.state, "show_code");
  const result = await accepted;
  assert.equal(result.peerAccountless, true, "ログインを知らせる前は、ログインなしで照合する");
  assert.equal(result.peerName, "YouTube Music (試験)");
  assert.match(shownCode, /^\d{6}$/);
  await waitFor("拡張が家に入る", () => ext.status().joined && ext.status().relay === "connected", 30_000);
  assert.equal(ext.status().pairing.state, "joined");
  const member = phone.client.members.find((m) => m.deviceId === ext.deviceId);
  assert.equal(member?.role, "player");
});

test("wave-api（Kotlin）もログインなしで加わり、拡張を pc の端末として見る", { skip }, async () => {
  const offer = await phone.offer("account-1");
  const accepted = offer.accept(async () => {
    const view = await waitFor("wave-api の6桁", async () => {
      const v = await api.get<{ state: string; code?: string }>("/v1/admin/pair");
      return v.state === "show_code" ? v : null;
    });
    return view.code!;
  });
  const paired = await api.post<{ state: string; code?: string }>("/v1/admin/pair", { offer: offer.text });
  assert.equal(paired.body.state, "show_code");
  assert.equal((await accepted).peerAccountless, true);
  await waitFor("wave-api が家に入る", async () => (await api.get<{ state: string }>("/v1/admin/pair")).state === "joined", 30_000);
  // 拡張の名前と種類（pc）が wave-api に届く（拡張が封じた profile を Kotlin が開ける）
  const devices = await waitFor(
    "wave-api の端末の一覧に拡張が pc で出る",
    async () => {
      const list = await api.get<{ id: string; name: string; kind: string | null }[]>("/v1/devices");
      return list.find((d) => d.id === ext.deviceId && d.kind === "pc") ? list : null;
    },
    30_000,
  );
  assert.equal(devices.find((d) => d.id === ext.deviceId)?.name, "YouTube Music (試験)");
  // 拡張の側も wave-api を api の端末（「ここで再生」の行き先にしない）として見る
  await waitFor("拡張が wave-api を api と知る", () => ext.devices().some((d) => d.kind === "api" && !d.playable), 30_000);
  assert.ok(ext.devices().some((d) => d.self && d.playable && d.kind === "pc"));
});

test("ページの再生を家の再生にし、JS の端末と wave-api が読める", { skip }, async () => {
  await ext.setAccount("account-1||extra");
  await waitFor("自分のアカウントタグ", () => ext.status().accountTag === "own");
  await ext.publishState(state());
  assert.equal(await ext.userStartedPlayback(), true);
  await waitFor("拡張が書き手になる", () => ext.status().isWriter && ext.status().activeDeviceId === ext.deviceId, 20_000);
  // JS の端末（同じアカウント）がカーソルとキューを開ける
  const latest = await waitFor("JS の端末に再生が届く", () => {
    const c = phone.client.latestCursorBody();
    return c && c.writer === ext.deviceId && c.cursor.playing ? c : null;
  });
  assert.equal(latest.cursor.volumePercent, 70);
  assert.equal(latest.cursor.shuffle, false);
  const queue = decodeQueue(phone.client.latestQueueBody()!.queue.snapshot)!;
  assert.deepEqual(
    queue.items.map((i) => i.title),
    ["一曲目", "二曲目", "三曲目"],
  );
  assert.equal(queue.items.find((i) => i.itemId === latest.cursor.currentItemId)?.title, "一曲目");
  assert.ok(phone.events.some((e) => e.type === "remote_changed" && e.accountMatches));
  // wave-api（Kotlin）も拡張の書いた doc を開ける
  const playback = await waitFor(
    "wave-api の再生中",
    async () => {
      const p = await api.get<{ isPlaying: boolean; track: { title: string; artist: string } | null; device: { id: string; name: string } | null; volumePercent: number | null }>(
        "/v1/playback",
      );
      return p.isPlaying && p.track ? p : null;
    },
    20_000,
  );
  assert.equal(playback.track?.title, "一曲目");
  assert.equal(playback.track?.artist, "歌手A");
  assert.equal(playback.device?.id, ext.deviceId);
  assert.equal(playback.volumePercent, 70);
});

test("wave-api（Kotlin）の遠隔操作がページへの命令になる", { skip }, async () => {
  assert.equal((await api.post("/v1/control", { action: "pause" })).status, 200);
  assert.equal((await nextCommand("pause")).reason, "remote");
  await ext.publishState(state({ playing: false, positionMs: 12_000 }));
  assert.equal((await api.post("/v1/control", { action: "play" })).status, 200);
  await nextCommand("play");
  await ext.publishState(state({ positionMs: 12_000 }));
  assert.equal((await api.post("/v1/control", { action: "seek", positionMs: 90_000 })).status, 200);
  assert.equal((await nextCommand("seek")).positionMs, 90_000);
  await ext.publishState(state({ positionMs: 90_000 }));
  assert.equal((await api.post("/v1/control", { action: "volume", volumePercent: 30 })).status, 200);
  assert.equal((await nextCommand("volume")).volumePercent, 30);
  assert.equal((await api.post("/v1/control", { action: "shuffle", shuffle: true })).status, 200);
  assert.equal((await nextCommand("shuffle")).on, true);
  assert.equal((await api.post("/v1/control", { action: "repeat", repeat: "context" })).status, 200);
  assert.equal((await nextCommand("repeat")).mode, "all");
  assert.equal((await api.post("/v1/control", { action: "next" })).status, 200);
  const next = await nextCommand("next");
  // 次へは、送り手が見ていた今の曲付き
  assert.ok(next.onItemId);
});

test("JS の端末の遠隔のキュー操作（足す・消去）がページへの命令になり、ID が保たれる", { skip }, async () => {
  // ページが次の曲へ進んだことを知らせる
  await ext.publishState(state({ index: 1, positionMs: 0, volumePercent: 30, shuffle: true, repeat: "all" }));
  const remote = await waitFor("JS の端末に今の曲が届く", () => {
    const c = phone.client.latestCursorBody();
    const q = phone.client.latestQueueBody();
    if (!c || !q || c.writer !== ext.deviceId) return null;
    const snapshot = decodeQueue(q.queue.snapshot);
    const current = snapshot?.items.find((i) => i.itemId === c.cursor.currentItemId);
    return current?.title === "二曲目" && c.cursor.volumePercent === 30 ? { c, snapshot: snapshot! } : null;
  });
  const added = [
    {
      itemId: crypto.randomUUID(),
      videoId: "abcdefghijD",
      title: "足した曲",
      artists: [{ name: "歌手D", browseId: null }],
      album: null,
      durationSeconds: 100,
      thumbnailUrl: null,
      origin: "user" as const,
      isLive: false,
    },
  ];
  assert.equal(await phone.run(() => phone.client.sendQueueAdd(added, true)), true);
  const add = await nextCommand("queue_add");
  assert.equal(add.afterIndex, 1, "次に再生は今の曲の直後");
  assert.equal(add.tracks[0]!.itemId, added[0]!.itemId);
  // ページが足した並びを知らせ直すと、送り手の ID のまま家のキューに載る
  await ext.publishState(state({ queue: [tracks[0]!, tracks[1]!, { videoId: "abcdefghijD", title: "足した曲" }, tracks[2]!], index: 1, positionMs: 1_000, volumePercent: 30 }));
  await waitFor("足した曲が ID のまま家のキューに載る", () => {
    const q = phone.client.latestQueueBody();
    const snapshot = q && decodeQueue(q.queue.snapshot);
    return snapshot?.items.some((i) => i.itemId === added[0]!.itemId) ?? false;
  });
  assert.equal(await phone.run(() => phone.client.sendQueueClearFlow(remote.c.cursor.currentItemId)), true);
  const cleared = await nextCommand("queue_clear_flow");
  assert.deepEqual(cleared.indices, [3], "自分で足した曲は残し、流れの曲だけ外す");
});

test("JS の端末が「ここで再生」で引き取ると、ページは止まり、拡張は書き手でなくなる", { skip }, async () => {
  await ext.publishState(state({ queue: [tracks[0]!, tracks[1]!, { videoId: "abcdefghijD", title: "足した曲" }], index: 1, positionMs: 5_000, volumePercent: 30 }));
  // 拡張の書き込みが中継に届いてから頼む（返事を待っている間に yield が届くと、最後のカーソルを置けず、3秒待ってから取り込む）
  await waitFor("最新が届く", () => {
    const p = phone.client.latestCursorBody()?.cursor.positionMs ?? 0;
    return p >= 5_000 && p < 6_000;
  });
  assert.equal(await phone.run(() => phone.client.requestTransfer()), true);
  assert.equal((await nextCommand("pause")).reason, "taken_over");
  await waitFor("JS の端末が取り込む", () => phone.adopted.length > 0, 20_000);
  const adopted = phone.adopted[0]!;
  assert.equal(decodeQueue(adopted.snapshot)!.items.find((i) => i.itemId === adopted.cursor.currentItemId)?.title, "二曲目");
  assert.equal(adopted.cursor.playing, false, "前の端末（拡張）の最後のカーソルは一時停止");
  // 取り込んだ端末が鳴らし始める
  await phone.run(async () => {
    phone.local = {
      currentItemId: adopted.cursor.currentItemId,
      positionMs: adopted.positionMs,
      playing: true,
      repeat: adopted.cursor.repeat,
      listenedMs: 0,
      historySent: true,
      capturedAtMs: 0,
      volumePercent: null,
      shuffle: false,
    };
    await phone.client.setLocalCursor(phone.local);
  });
  await waitFor("拡張が書き手でなくなる", () => !ext.status().isWriter && ext.status().activeDeviceId === phone.device.deviceId, 20_000);
  const remote = await waitFor("拡張がほかの端末の再生を見る", () => {
    const r = ext.remote();
    return r && r.deviceId === phone.device.deviceId && r.playing ? r : null;
  });
  assert.equal(remote.accountMatches, true);
  assert.equal(remote.track?.title, "二曲目");
  assert.equal(remote.deviceName, "Pixel (試験)");
  assert.equal(remote.volumePercent, null, "スマホは音量を載せない");
});

test("拡張から遠隔操作を送ると、鳴っている JS の端末が従う", { skip }, async () => {
  assert.equal(await ext.sendControl("seek", 42_000), true);
  await waitFor("JS の端末がシークを受ける", () => phone.controls.find((c) => c.action === "seek" && c.positionMs === 42_000));
});

test("拡張の「ここで再生」で引き取ると、ページにキューと位置が届く", { skip }, async () => {
  assert.equal(await ext.takeOver(), true);
  // 鳴っていた JS の端末は止まる（yield）
  await waitFor("JS の端末が止まる", () => phone.events.some((e) => e.type === "stopped"), 20_000);
  const adopt = await nextCommand("adopt", 20_000);
  assert.equal(adopt.tracks[adopt.index]!.title, "二曲目");
  assert.equal(adopt.playing, true);
  assert.ok(adopt.positionMs >= 5_000);
  assert.equal(adopt.from, phone.device.deviceId);
  // ページが読み込んで鳴らし始めたと知らせると、取り込んだ ID のまま書き直す
  await ext.publishState({ queue: adopt.tracks, index: adopt.index, positionMs: adopt.positionMs, playing: true, volumePercent: 50, shuffle: false });
  await waitFor("拡張が書き手に戻る", () => ext.status().isWriter && ext.status().activeDeviceId === ext.deviceId, 20_000);
  await waitFor("wave-api が拡張の再生を見る", async () => {
    const p = await api.get<{ isPlaying: boolean; device: { id: string } | null; track: { title: string } | null }>("/v1/playback");
    return p.isPlaying && p.device?.id === ext.deviceId && p.track?.title === "二曲目";
  });
  assert.ok(notices.includes("transfer_completed") || notices.includes("stopped"));
});

test("wave-api（Kotlin）の「端末の切り替え」で、拡張の再生を JS の端末へ移す", { skip }, async () => {
  const sent = await api.post<{ ok: boolean }>("/v1/transfer", { deviceId: phone.device.deviceId });
  assert.equal(sent.status, 200);
  // JS の端末は「ここで再生」を頼まれる（transfer の control）→ 引き取りを頼む → 拡張は止まる
  await waitFor("JS の端末に transfer が届く", () => phone.controls.find((c) => c.action === "transfer"), 20_000);
  assert.equal((await nextCommand("pause")).reason, "taken_over");
  await waitFor("JS の端末が取り込む（2回目）", () => phone.adopted.length > 1, 20_000);
});

test("背景が止まって起き直しても、ページが鳴り続けていれば家の再生を書き続ける", { skip }, async () => {
  // もう一度この拡張で鳴らす
  await ext.publishState(state({ positionMs: 20_000 }));
  assert.equal(await ext.userStartedPlayback(), true);
  await waitFor("拡張が書き手", () => ext.status().isWriter && ext.status().activeDeviceId === ext.deviceId, 20_000);
  // 背景が止まる（保存はそのまま）
  ext.dispose();
  const restarted = await ConnectService.open({ store, defaultDeviceName: "YouTube Music (試験)", log: () => {} });
  try {
    assert.equal(restarted.deviceId, ext.deviceId, "端末 ID は保存した鍵のまま");
    assert.equal(restarted.status().joined, true);
    restarted.pageConnected();
    // ページはつなぎ直して、ログインと状態を送り直す（再生ボタンは押さない）
    await restarted.setAccount("account-1||extra");
    await restarted.publishState(state({ positionMs: 30_000 }));
    await waitFor("起き直した拡張が書き手に戻る", () => restarted.status().isWriter, 30_000);
    await waitFor("JS の端末が起き直した拡張の再生を見る", () => {
      const c = phone.client.latestCursorBody();
      return c && c.writer === ext.deviceId && c.cursor.playing && c.cursor.positionMs >= 30_000;
    });
  } finally {
    restarted.dispose();
  }
});
