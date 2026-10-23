// 試験の道具: 本物の中継（relay/ の Kotlin の中継）と wave-api（api/ の Kotlin の端末）を立ち上げ、
// JS の「家を作る端末」（スマホの代わり。ConnectClient をそのまま使う）を用意する。

import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { b64, uuid } from "../../src/connect/bytes.ts";
import { addRecord, genesisRecord } from "../../src/connect/chain.ts";
import { ConnectClient, type ClientEvent, type LocalCursor } from "../../src/connect/client.ts";
import { Device } from "../../src/connect/device.ts";
import { decodeWith, serialize } from "../../src/connect/json.ts";
import { accountTagOf, joinInfoToJson, readMemberKeys, type ControlAction, type CursorBody, type HandoffItem } from "../../src/connect/models.ts";
import { confirmToJson, decodeHello, encodeOffer, Responder } from "../../src/connect/pairing.ts";
import { RelayConnection } from "../../src/connect/relay-connection.ts";
import { decodeRelay, encodeRelay, type RelayMessage } from "../../src/connect/relay-messages.ts";

export const repoRoot = fileURLToPath(new URL("../../../", import.meta.url));
const relayBin = join(repoRoot, "relay/build/install/relay/bin/relay");
const apiBin = join(repoRoot, "api/build/install/api/bin/api");

/** JDK（無ければ Android Studio の同梱のもの）。 */
export function javaHome(): string | null {
  if (process.env["JAVA_HOME"] && existsSync(join(process.env["JAVA_HOME"], "bin/java"))) return process.env["JAVA_HOME"];
  for (const candidate of ["/opt/android-studio/jbr", "/usr/lib/jvm/java-21-openjdk", "/usr/lib/jvm/default"]) {
    if (existsSync(join(candidate, "bin/java"))) return candidate;
  }
  return null;
}

export function kotlinBuilt(): boolean {
  return existsSync(relayBin) && existsSync(apiBin) && javaHome() !== null;
}

export async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      server.close(() => (typeof address === "object" && address ? resolve(address.port) : reject(new Error("port"))));
    });
  });
}

export async function waitFor<T>(what: string, check: () => T | null | undefined | false | Promise<T | null | undefined | false>, timeoutMs = 30_000): Promise<T> {
  const startedAt = Date.now();
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() - startedAt > timeoutMs) throw new Error(`待ちきれない: ${what}`);
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

export class Proc {
  readonly child: ChildProcess;
  output = "";

  constructor(command: string, args: string[], env: Record<string, string>) {
    this.child = spawn(command, args, { env: { ...process.env, ...env }, stdio: ["ignore", "pipe", "pipe"] });
    this.child.stdout?.on("data", (d: Buffer) => (this.output += d.toString()));
    this.child.stderr?.on("data", (d: Buffer) => (this.output += d.toString()));
  }

  stop(): void {
    this.child.kill("SIGTERM");
  }
}

export class Workspace {
  readonly dir = mkdtempSync(join(tmpdir(), "wave-ext-e2e-"));

  cleanup(): void {
    rmSync(this.dir, { recursive: true, force: true });
  }
}

/** 本物の中継（WAVE_RELAY_PROTOCOL=v2）。bootstrapKey は家を作れる端末の署名公開鍵。 */
export async function startRelay(workspace: Workspace, bootstrapKey: Uint8Array): Promise<{ proc: Proc; url: string; host: string }> {
  const port = await freePort();
  const host = `127.0.0.1:${port}`;
  const proc = new Proc(relayBin, [], {
    JAVA_HOME: javaHome()!,
    WAVE_RELAY_PROTOCOL: "v2",
    WAVE_RELAY_HOST: host,
    WAVE_RELAY_DATA: join(workspace.dir, "relay-household.json"),
    WAVE_RELAY_BOOTSTRAP_KEYS: b64(bootstrapKey),
    WAVE_RELAY_PORT: String(port),
    WAVE_RELAY_BIND: "127.0.0.1",
  });
  await waitFor(
    "中継の起動",
    async () => {
      try {
        return (await fetch(`http://${host}/health`)).ok;
      } catch {
        return false;
      }
    },
    60_000,
  );
  return { proc, url: `ws://${host}`, host };
}

/** wave-api（api/ の Kotlin の端末。ログインなしで加わる player）。 */
export async function startApi(workspace: Workspace, relay: { url: string; host: string }): Promise<WaveApi> {
  const port = await freePort();
  const data = join(workspace.dir, "api-data");
  const proc = new Proc(apiBin, [], {
    JAVA_HOME: javaHome()!,
    WAVE_API_DATA: data,
    WAVE_API_PORT: String(port),
    WAVE_RELAY_URL: relay.url,
    WAVE_RELAY_HOST: relay.host,
  });
  const base = `http://127.0.0.1:${port}`;
  await waitFor(
    "wave-api の起動",
    async () => {
      try {
        return (await fetch(`${base}/v1/health`)).ok;
      } catch {
        return false;
      }
    },
    60_000,
  );
  const token = readFileSync(join(data, "token"), "utf8").trim();
  return new WaveApi(proc, base, token);
}

export class WaveApi {
  readonly proc: Proc;
  readonly base: string;
  private readonly token: string;

  constructor(proc: Proc, base: string, token: string) {
    this.proc = proc;
    this.base = base;
    this.token = token;
  }

  async get<T>(path: string): Promise<T> {
    const response = await fetch(this.base + path, { headers: { Authorization: `Bearer ${this.token}` } });
    return (await response.json()) as T;
  }

  async post<T>(path: string, body: unknown): Promise<{ status: number; body: T }> {
    const response = await fetch(this.base + path, {
      method: "POST",
      headers: { Authorization: `Bearer ${this.token}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    return { status: response.status, body: (await response.json()) as T };
  }
}

/**
 * 家を作る端末（スマホの代わり）。ConnectClient をそのまま使い、QR を出して加入を受け（A の側）、再生を書き・引き取る。
 * アカウントは "account-1"。
 */
export class Phone {
  readonly device: Device;
  client!: ConnectClient;
  relay!: RelayConnection;
  tag: string | null = null;
  readonly events: ClientEvent[] = [];
  readonly adopted: { snapshot: string; cursor: CursorBody; positionMs: number }[] = [];
  readonly controls: { action: ControlAction; positionMs: number | null }[] = [];
  readonly queueAdds: HandoffItem[][] = [];
  private readonly pairHellos: Extract<RelayMessage, { op: "pair_hello" }>[] = [];
  private readonly chainReplies: RelayMessage[] = [];
  private engine: Promise<unknown> = Promise.resolve();
  private clock = performance.now();
  private ticker: ReturnType<typeof setInterval> | null = null;
  local: LocalCursor | null = null;

  private constructor(device: Device) {
    this.device = device;
  }

  static async create(): Promise<Phone> {
    return new Phone(await Device.generate());
  }

  run<T>(body: () => Promise<T> | T): Promise<T> {
    const next = this.engine.then(body, body);
    this.engine = next.catch((e: unknown) => {
      if (process.env["WAVE_E2E_DEBUG"]) console.error("phone engine failed", e);
    });
    return next;
  }

  async connect(relay: { url: string; host: string }): Promise<void> {
    this.client = await ConnectClient.create({
      signer: this.device,
      opener: this.device,
      role: "player",
      send: (m) => this.relay.send(encodeRelay(m)),
      effects: {
        pause: () => (this.local ? { ...this.local, playing: false } : null),
        adopt: (snapshot, cursor, positionMs) => {
          this.adopted.push({ snapshot, cursor, positionMs });
          return true;
        },
        event: (e) => this.events.push(e),
        persist: () => {},
        accountTag: () => this.tag,
        control: (action, positionMs) => {
          this.controls.push({ action, positionMs });
          return true;
        },
        queueAdd: (items) => {
          this.queueAdds.push(items);
          return true;
        },
      },
      nowMs: () => performance.now() - this.clock,
      epochMs: () => Date.now(),
      newId: uuid,
    });
    this.relay = new RelayConnection({
      baseUrl: relay.url,
      relayHost: relay.host,
      identityPub: this.device.identityPub,
      sign: (label, message) => this.device.sign(label, message),
      deliver: (text) => {
        const message = decodeRelay(text);
        if (message === null) return;
        if (message.op === "pair_hello") {
          this.pairHellos.push(message);
          return;
        }
        if (message.op === "chain_ok" || message.op === "chain_conflict" || (message.op === "error" && message.requestId === null)) this.chainReplies.push(message);
        void this.run(() => this.client.onMessage(message));
      },
      onConnectionLost: () => void this.run(() => this.client.onDisconnected()),
    });
    this.relay.acquire();
    // 時間の経過（引き取りの3秒の待ちなど）
    this.ticker = setInterval(() => void this.run(() => this.client.tick()), 500);
  }

  /** 家を作る（中継の起動時の設定の鍵の端末だけができる）。 */
  async createHousehold(account: string): Promise<void> {
    await waitFor("中継につながる", () => this.relay.status === "connected");
    await this.run(() => this.client.beginCreateHousehold());
    const genesis = await genesisRecord(this.device, await this.device.memberKeys("player"));
    this.relay.send(encodeRelay({ op: "household_create", genesis }));
    this.relay.send(encodeRelay({ op: "chain_get", fromSeq: 0 }));
    await waitFor("家ができる", () => this.client.householdId !== null);
    this.tag = await accountTagOf(this.client.householdId!, account);
    // 家ができた後の接続で storeId と状態を受け直す
    this.relay.restart();
    await waitFor("つなぎ直す", () => this.relay.status === "connected" && this.client.stateSettled);
  }

  /** QR を出す（A の側）。返すのは QR の文字と、Hello を受けて6桁を入れる手続き。 */
  async offer(account: string): Promise<{ text: string; accept: (code: () => Promise<string>) => Promise<{ peerAccountless: boolean; peerName: string }> }> {
    const joinInfo = this.client.currentJoinInfo();
    if (joinInfo === null) throw new Error("家に入っていない");
    const responder = await Responder.create({
      identity: this.device,
      deviceName: "Pixel (試験)",
      accountId: account,
      nowMs: () => Date.now(),
      confirmExtension: serialize(joinInfoToJson(joinInfo)),
    });
    const tokenHash = b64(responder.tokenHash);
    this.relay.send(encodeRelay({ op: "pairing_open", tokenHash }));
    const accept = async (code: () => Promise<string>) => {
      const hello = await waitFor("Hello が届く", () => this.pairHellos.find((h) => h.tokenHash === tokenHash), 60_000);
      const result = await responder.onHello(decodeHello(hello.hello)!);
      if (!result.ok) throw new Error(`Hello を受けられない: ${result.failure}`);
      this.relay.send(encodeRelay({ op: "pair_confirm", tokenHash, confirm: confirmToJson(result.value) }));
      const established = responder.onCodeEntered(await code());
      if (!established.ok) throw new Error(`6桁が合わない: ${established.failure}`);
      const member = decodeWith(established.value.peerExtension!, readMemberKeys)!;
      const chain = this.client.verifiedChain()!;
      const record = await addRecord(this.device, chain, member);
      this.chainReplies.length = 0;
      this.relay.send(encodeRelay({ op: "chain_append", record }));
      const reply = await waitFor("追記の返事", () => this.chainReplies[0]);
      if (reply.op !== "chain_ok") throw new Error(`追記できない: ${reply.op}`);
      await waitFor("鎖に加わる", () => this.client.members.some((m) => m.deviceId === member.deviceId));
      return { peerAccountless: established.value.peerAccountless, peerName: established.value.peerDeviceName };
    };
    return { text: encodeOffer(responder.offer), accept };
  }

  stop(): void {
    if (this.ticker !== null) clearInterval(this.ticker);
    this.relay.release();
  }
}
