// 本物の music.youtube.com を開いた、拡張入りの Chromium・Firefox での通しの確かめ（手で走らせる。npm test には入れない）。
//   node test/browser/run.ts chromium|firefox
// 本物の中継（relay/）・wave-api（api/）・JS のスマホ役を手元に立て、拡張（試験用の背景）を台本の受け口につないで動かす。
// 自動操作の印（--enable-automation・WebDriver）を使わない: Chromium はそのまま起動し、Firefox は web-ext（RDP）で入れる。
// 先に `./gradlew :relay:installDist :api:installDist` と、src/page/harness で `npm i`（web-ext）をしておく。
// 本人の家の中継・端末にはつながない（手元の 127.0.0.1 だけ）。結果は test/browser/out/<ブラウザ>.json。

import { spawn, type ChildProcess } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { createServer, type IncomingMessage } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { QueueTracker } from "../../src/connect/queue.ts";
import type { ConnectStatus, DeviceView, PageState } from "../../src/connect/service.ts";
import { Phone, startApi, startRelay, waitFor, Workspace } from "../e2e/harness.ts";

const which = process.argv[2] === "firefox" ? "firefox" : "chromium";
const root = fileURLToPath(new URL("../../", import.meta.url));
const outDir = join(root, "test/browser/out");
mkdirSync(outDir, { recursive: true });
const startedAt = Date.now();
const log: { t: number; step: string; ok: boolean; detail?: unknown }[] = [];
function record(step: string, ok: boolean, detail?: unknown): void {
  const t = Math.round((Date.now() - startedAt) / 1000);
  log.push({ t, step, ok, detail });
  console.log(`[${t}s] ${ok ? "OK" : "NG"} ${step}${detail === undefined ? "" : " " + JSON.stringify(detail)}`);
}

// --- 台本の受け口（試験用の背景が 300ms ごとに来る） ---
interface Report {
  status: ConnectStatus;
  devices: DeviceView[];
  page: PageState | null;
}
let report: Report | null = null;
const bgLog: string[] = [];
let nextId = 1;
const queue: Record<string, unknown>[] = [];
const results = new Map<number, unknown>();
const body = (req: IncomingMessage): Promise<string> =>
  new Promise((resolve) => {
    let text = "";
    req.on("data", (c: Buffer) => (text += c.toString()));
    req.on("end", () => resolve(text));
  });
const server = createServer(async (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  const text = await body(req);
  if (req.url === "/next") {
    try {
      report = JSON.parse(text) as Report;
      for (const line of (report as unknown as { logs?: string[] }).logs ?? []) {
        bgLog.push(line);
        if (process.env["WAVE_BG_LOG"]) console.log("  bg:", line);
      }
    } catch {
      // 無視
    }
    res.end(JSON.stringify(queue.shift() ?? null));
  } else if (req.url === "/result") {
    const r = JSON.parse(text) as { id: number; result: unknown };
    results.set(r.id, r.result);
    res.end("ok");
  } else {
    res.end("ok");
  }
});
await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
const control = `http://127.0.0.1:${(server.address() as { port: number }).port}`;

async function instruct<T>(instruction: Record<string, unknown>, timeoutMs = 60_000): Promise<T> {
  const id = nextId++;
  queue.push({ ...instruction, id });
  return (await waitFor(`指示 ${String(instruction["type"])}`, () => (results.has(id) ? { v: results.get(id) } : null), timeoutMs)).v as T;
}

const current = (): PageState["queue"][number] | null => (report?.page ? (report.page.queue[report.page.index] ?? null) : null);

// --- 組む ---
const dist = mkdtempSync(join(tmpdir(), `wave-ext-${which}-dist-`));
await new Promise<void>((resolve, reject) => {
  const b = spawn(process.execPath, [join(root, "build.mjs"), `--test-control=${control}`, `--out=${dist}`], { stdio: "inherit" });
  b.on("exit", (code) => (code === 0 ? resolve() : reject(new Error("build"))));
});

const workspace = new Workspace();
const phone = await Phone.create();
let browser: ChildProcess | null = null;
const procs: { stop(): void }[] = [];
let ok = false;
try {
  const relay = await startRelay(workspace, phone.device.identityPub);
  procs.push(relay.proc);
  await phone.connect(relay);
  await phone.createHousehold("account-1");
  const api = await startApi(workspace, relay);
  procs.push(api.proc);
  record("中継・wave-api・スマホ役", true);

  // --- ブラウザ ---
  const startUrl = "https://music.youtube.com/";
  if (which === "chromium") {
    const profile = mkdtempSync(join(tmpdir(), "wave-ext-chromium-profile-"));
    browser = spawn(
      process.env["CHROME"] ?? "/usr/bin/chromium",
      [
        "--headless=new",
        `--user-data-dir=${profile}`,
        `--disable-extensions-except=${dist}`,
        `--load-extension=${dist}`,
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-blink-features=AutomationControlled",
        "--autoplay-policy=no-user-gesture-required",
        "--mute-audio",
        "--lang=ja",
        // 頭なしの印（HeadlessChrome）だと YouTube Music が「このブラウザーは非対応」を出す
        "--user-agent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
        startUrl,
      ],
      { stdio: "ignore" },
    );
  } else {
    browser = spawn(
      join(root, "src/page/harness/node_modules/.bin/web-ext"),
      [
        "run",
        `--source-dir=${dist}`,
        `--firefox=${process.env["FIREFOX"] ?? "/usr/bin/firefox"}`,
        `--start-url=${startUrl}`,
        "--no-reload",
        "--no-input",
        "--arg=-headless",
        "--pref=media.autoplay.default=0",
        "--pref=media.autoplay.blocking_policy=0",
        "--pref=intl.accept_languages=ja,en",
      ],
      { stdio: "ignore" },
    );
  }

  await waitFor("拡張の背景が台本へ来る", () => report, 90_000);
  const ready = await waitFor("ページがつながりログインの有無を知らせる", () => report!.status.pageOpen && report!.status.account !== "unknown", 120_000);
  record("ページがつながる（ログインの有無が届く）", ready, { account: report!.status.account });

  // --- 加入 ---
  await instruct({ type: "configure", relayUrl: relay.url });
  await waitFor("中継に届く（まだ家の外）", () => report!.status.relay === "not_member", 30_000);
  const offer = await phone.offer("account-1");
  const accepted = offer.accept(async () =>
    waitFor("6桁", () => (report!.status.pairing.state === "show_code" ? (report!.status.pairing as { code: string }).code : null), 60_000),
  );
  await instruct({ type: "join", offerText: offer.text });
  const a = await accepted;
  await waitFor("家に入る", () => report!.status.joined && report!.status.relay === "connected", 60_000);
  record("加入（QR の文字と6桁）", true, { peerAccountless: a.peerAccountless, name: a.peerName });

  // wave-api も加える
  const apiOffer = await phone.offer("account-1");
  const apiAccepted = apiOffer.accept(async () => {
    const v = await waitFor("wave-api の6桁", async () => {
      const s = await api.get<{ state: string; code?: string }>("/v1/admin/pair");
      return s.state === "show_code" ? s : null;
    });
    return v.code!;
  });
  await api.post("/v1/admin/pair", { offer: apiOffer.text });
  await apiAccepted;
  await waitFor("wave-api が家に入る", async () => (await api.get<{ state: string }>("/v1/admin/pair")).state === "joined", 60_000);
  const listed = await waitFor(
    "wave-api の端末の一覧に拡張",
    async () => (await api.get<{ id: string; name: string; kind: string | null }[]>("/v1/devices")).find((d) => d.id === report!.status.deviceId && d.kind === "pc"),
    60_000,
  ).catch(async (e: unknown) => {
    record("wave-api の端末の一覧", false, { devices: await api.get("/v1/devices"), self: report!.status.deviceId, extDevices: report!.devices });
    throw e;
  });
  record("wave-api の /v1/devices に拡張が pc で出る", true, listed);

  // --- スマホ役が鳴らしているキューを、wave-api からタブへ移す ---
  const videos = [
    { videoId: "dQw4w9WgXcQ", title: "A" },
    { videoId: "djV11Xbc914", title: "B" },
    { videoId: "fJ9rUzIMcZQ", title: "C" },
    { videoId: "Zi_XLOBDo_Y", title: "D" },
    { videoId: "kJQP7kiw5Fk", title: "E" },
    { videoId: "9bZkp7q19f0", title: "F" },
    { videoId: "OPf0YbXqDm0", title: "G" },
  ];
  const tracker = new QueueTracker();
  tracker.update(videos, 1);
  await phone.run(async () => {
    await phone.client.setLocalQueue(tracker.snapshotText()!);
    phone.local = {
      currentItemId: tracker.current!.itemId,
      positionMs: 30_000,
      playing: true,
      repeat: "off",
      listenedMs: 0,
      historySent: true,
      capturedAtMs: 0,
      volumePercent: null,
      shuffle: false,
    };
    await phone.client.setLocalCursor(phone.local);
    await phone.client.userStartedPlayback();
  });
  await waitFor("スマホ役が書き手", () => phone.client.isWriter && phone.client.latestCursorBody()?.writer === phone.device.deviceId, 30_000);
  await waitFor("拡張がスマホ役の再生を見る", () => report!.status.accountTag === "borrowed", 30_000);
  const sent = await api.post("/v1/transfer", { deviceId: report!.status.deviceId });
  record("wave-api の /v1/transfer（タブへ）", sent.status === 200, sent.body);
  const played = await waitFor(
    "YouTube Music で B が鳴る（広告の後）",
    () => {
      const c = current();
      return report!.page?.playing && c?.videoId === "djV11Xbc914" && report!.page.positionMs >= 28_000 ? report!.page : null;
    },
    240_000,
  );
  record("タブで B が 30 秒付近から鳴る", true, {
    queue: played.queue.map((t) => t.videoId),
    index: played.index,
    positionMs: played.positionMs,
    exhausted: played.exhausted,
  });
  const playback = await waitFor(
    "wave-api の /v1/playback が拡張の再生",
    async () => {
      const p = await api.get<{ isPlaying: boolean; device: { id: string } | null; track: { videoId: string; title: string } | null; progressMs: number | null }>("/v1/playback");
      return p.isPlaying && p.device?.id === report!.status.deviceId && p.track?.videoId === "djV11Xbc914" ? p : null;
    },
    60_000,
  );
  record("wave-api の /v1/playback に拡張の再生（題名は YouTube Music のもの）", true, { title: playback.track?.title, progressMs: playback.progressMs });
  const phoneStopped = await waitFor("スマホ役が止まる", () => !phone.client.isWriter, 30_000);
  record("スマホ役は止まる", phoneStopped);

  // --- wave-api からの操作が YouTube Music に効く ---
  await api.post("/v1/control", { action: "pause" });
  record("一時停止", !!(await waitFor("止まる", () => report!.page && !report!.page.playing, 30_000)));
  await api.post("/v1/control", { action: "play" });
  record("再生", !!(await waitFor("鳴る", () => report!.page?.playing, 30_000)));
  await api.post("/v1/control", { action: "seek", positionMs: 100_000 });
  const seeked = await waitFor("シーク", () => (report!.page && report!.page.positionMs >= 99_000 && report!.page.positionMs < 115_000 ? report!.page.positionMs : null), 30_000);
  record("シーク 100 秒", true, { positionMs: seeked });
  await api.post("/v1/control", { action: "volume", volumePercent: 30 });
  record("音量 30", !!(await waitFor("音量", () => report!.page?.volumePercent === 30, 30_000)));
  // シャッフル: YouTube Music のシャッフルは使わず、背景が後ろの並びを決めて動かす。切ると元の並びに戻る
  const ids = () => report!.page!.queue.map((t) => t.videoId).join(",");
  const original = ids();
  await api.post("/v1/control", { action: "shuffle", shuffle: true });
  const shuffledOrder = await waitFor("後ろが並べ替わる", () => (ids() !== original ? ids() : null), 60_000);
  const shuffleShown = await waitFor("wave-api にシャッフル", async () => (await api.get<{ shuffle: boolean | null }>("/v1/playback")).shuffle === true, 30_000);
  record("シャッフルを入れる（後ろだけ並べ替え）", shuffledOrder.split(",").slice(0, 2).join(",") === original.split(",").slice(0, 2).join(",") && shuffleShown, { before: original, after: shuffledOrder });
  await api.post("/v1/control", { action: "shuffle", shuffle: false });
  await waitFor("元の並びに戻る", () => ids() === original, 60_000);
  record("シャッフルを切る（元の並びに戻る）", true);
  await api.post("/v1/control", { action: "next" });
  const next = await waitFor("次へ → C", () => (current()?.videoId === "fJ9rUzIMcZQ" && report!.page?.playing ? current() : null), 180_000);
  record("次へ（wave-api から）", true, next?.videoId);

  // --- タブで本人が次へを押す → wave-api に映る（遠隔の命令の直後の数秒は本人の操作とみなさないので、間を置く） ---
  await new Promise((resolve) => setTimeout(resolve, 6_000));
  const userStartsBefore = bgLog.filter((l) => l.includes("user started")).length;
  await instruct({ type: "tab", action: "next" });
  await waitFor("タブで次へ → D", () => current()?.videoId === "Zi_XLOBDo_Y" && report!.page?.playing, 180_000);
  const reflected = await waitFor(
    "wave-api に D",
    async () => {
      const p = await api.get<{ track: { videoId: string } | null; device: { id: string } | null }>("/v1/playback");
      return p.track?.videoId === "Zi_XLOBDo_Y" && p.device?.id === report!.status.deviceId ? p : null;
    },
    60_000,
  );
  record("タブで次へ → wave-api の /v1/playback に反映", !!reflected, { userStartedSent: bgLog.filter((l) => l.includes("user started")).length > userStartsBefore });

  // --- スマホ役から: 次に再生で足す・「次に流れる曲」の消去 ---
  const added = {
    itemId: crypto.randomUUID(),
    videoId: "hTWKbfoikeg",
    title: "X",
    artists: [],
    album: null,
    durationSeconds: null,
    thumbnailUrl: null,
    origin: "user" as const,
    isLive: false,
  };
  await new Promise((resolve) => setTimeout(resolve, 2_000));
  record("スマホ役から次に再生を送る", await phone.run(() => phone.client.sendQueueAdd([added], true)));
  const afterAdd = await waitFor("タブのキューの今の曲の直後に X", () => (report!.page && report!.page.queue[report!.page.index + 1]?.videoId === "hTWKbfoikeg" ? ids() : null), 60_000);
  record("次に再生が YouTube Music のキューに入る", true, { queue: afterAdd });
  const currentItemId = phone.client.latestCursorBody()!.cursor.currentItemId;
  record("スマホ役から「次に流れる曲」の消去を送る", await phone.run(() => phone.client.sendQueueClearFlow(currentItemId)));
  const cleared = await waitFor(
    "足した曲だけ残り、自動再生が切れる",
    () => (report!.page && report!.page.queue.length === report!.page.index + 2 && report!.page.exhausted ? ids() : null),
    60_000,
  );
  record("「次に流れる曲」の消去（足した曲は残し、自動再生を切る）", true, { queue: cleared });

  // --- スマホ役に移すと YouTube Music が止まる ---
  const adoptedBefore = phone.adopted.length;
  const moved = await api.post("/v1/transfer", { deviceId: phone.device.deviceId });
  record("wave-api の /v1/transfer（スマホ役へ）", moved.status === 200);
  await waitFor("スマホ役が取り込む", () => phone.adopted.length > adoptedBefore, 30_000);
  await waitFor("YouTube Music が止まる", () => report!.page && !report!.page.playing, 30_000);
  const adopted = phone.adopted[phone.adopted.length - 1]!;
  record("スマホ役へ移り、YouTube Music は止まる", true, { currentOnPhone: adopted.cursor.currentItemId === tracker.items.find((i) => i.videoId === "Zi_XLOBDo_Y")?.itemId || "（ID は拡張が振り直し）" });
  // --- タブで本人が再生を押すと、家の再生がタブに戻る（スマホ役は止まる） ---
  await new Promise((resolve) => setTimeout(resolve, 6_000));
  await phone.run(async () => {
    if (phone.local) await phone.client.setLocalCursor({ ...phone.local, playing: true });
  });
  await instruct({ type: "tab", action: "play" });
  await waitFor("タブが鳴る", () => report!.page?.playing, 30_000);
  await waitFor("拡張が書き手に戻る", () => report!.status.isWriter, 30_000);
  await waitFor("スマホ役が止まる（2回目）", () => !phone.client.isWriter, 30_000);
  record("タブで再生を押す → 家の再生がタブに戻り、スマホ役は止まる", true, { userStarted: bgLog.some((l) => l.includes("user started claimed=true")) });
  ok = true;
} catch (e) {
  record("例外", false, e instanceof Error ? e.message : String(e));
} finally {
  writeFileSync(join(outDir, `${which}.json`), JSON.stringify({ browser: which, ok, log, lastReport: report, backgroundLog: bgLog }, null, 1));
  browser?.kill("SIGTERM");
  phone.stop();
  for (const proc of procs) proc.stop();
  workspace.cleanup();
  server.close();
}
console.log(ok ? "BROWSER E2E OK" : "BROWSER E2E FAILED");
process.exit(ok ? 0 : 1);
