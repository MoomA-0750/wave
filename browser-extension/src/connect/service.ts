// 拡張の連携の調停役（Swift の ConnectService・ConnectWorker、wave-api の ApiHub に当たる）。
// ConnectClient を1本の作業の列（run）に閉じ込め、中継 v2・ページ（YouTube Music の再生）・保存をつなぐ。
// ページとは「状態を知らせてもらう（publishState）」「命令を渡す（command の出来事）」だけでつながり、YouTube Music の中身を知らない。
// ログは種類と件数だけ（端末 ID・本文・アカウントの識別子は出さない）。

import { uuid } from "./bytes.ts";
import { ConnectClient, type ClientEffects, type ClientEvent, type LocalCursor } from "./client.ts";
import { deviceId as deviceIdOf } from "./crypto.ts";
import { Device, type StoredKeys } from "./device.ts";
import { decodeWith, serialize } from "./json.ts";
import {
  accountTagOf,
  memberKeysToJson,
  normalizeDataSyncId,
  persistedToJson,
  readJoinInfo,
  readPersisted,
  type ClientPersisted,
  type ControlAction,
  type CursorBody,
  type HandoffItem,
  type HandoffRepeat,
  type HandoffSource,
  type Role,
} from "./models.ts";
import { decodeConfirm, decodeOffer, helloToJson, Initiator } from "./pairing.ts";
import { atCursor, decodeQueue, itemOf, QueueTracker, trackOf, type IdentifiedTrack, type PageTrack } from "./queue.ts";
import { RelayConnection, type RelayStatus } from "./relay-connection.ts";
import { decodeRelay, encodeRelay } from "./relay-messages.ts";
import { StoreKeys, type KeyValueStore } from "./store.ts";

/** ほかの端末に知らせる、この端末の種類。「pc」はほかの端末が知っている種類で、「ここで再生」の行き先に出る（未知の種類は捨てられる）。 */
export const DEVICE_KIND = "pc";
const TICK_MS = 1_000;
const JOIN_WAIT_MS = 5 * 60 * 1000;
const JOIN_RETRY_MS = 4_000;
/** 位置だけの変化は、見込みとこれ以上ずれたときだけ書く（毎秒の知らせで毎秒カーソルを書かない）。 */
const POSITION_TOLERANCE_MS = 2_000;
/** 位置だけの変化でも、これだけ間が空いたら書き直す（ほかの端末の外挿のずれを抑える）。 */
const CURSOR_REFRESH_MS = 30_000;

export interface ConnectSettings {
  /** 例: wss://<中継のホスト>:<ポート> */
  relayUrl: string;
  /** 署名の対象に入れるホスト名とポート（中継の WAVE_RELAY_HOST と同じ値）。空なら relayUrl のホストとポート。 */
  relayHost: string;
  /** ほかの端末の一覧に出す名前。 */
  deviceName: string;
}

/** ページが知らせる手元の再生（YouTube Music の再生キューと再生の様子）。 */
export interface PageState {
  /** 再生キュー（前に聴いた曲も含めた、ページの並びのまま）。 */
  queue: PageTrack[];
  /** queue の中の今の曲の位置。 */
  index: number;
  positionMs: number;
  playing: boolean;
  repeat?: HandoffRepeat;
  shuffle?: boolean;
  /** YouTube Music の音量（0〜100）。 */
  volumePercent?: number | null;
  /** キューの元（ラジオの種の曲・プレイリスト）。分かれば。 */
  source?: HandoffSource | null;
  radioPlaylistId?: string | null;
  /** 続きが無い（自動再生を切った）。無ければ false（受け取った端末がラジオで続ける）。 */
  exhausted?: boolean;
  /** 今の曲を聴いた時間。無ければ positionMs。 */
  listenedMs?: number;
  /** 今の曲の再生履歴を YouTube に送り済みか。YouTube Music は自分で送るので、無ければ true。 */
  historySent?: boolean;
}

/** ページへの命令（この端末で鳴っている間に、ほかの端末から届いた操作。と、引き取り・止まれ）。 */
export type PageCommand =
  | { kind: "play" }
  /** reason: "remote" はほかの端末からの一時停止、"taken_over" はほかの端末で再生が始まった（自動では再開しない）。 */
  | { kind: "pause"; reason: "remote" | "taken_over" }
  | { kind: "next"; onItemId: string | null }
  | { kind: "previous"; onItemId: string | null }
  | { kind: "seek"; positionMs: number }
  | { kind: "volume"; volumePercent: number }
  | { kind: "shuffle"; on: boolean }
  | { kind: "repeat"; mode: HandoffRepeat }
  /** afterIndex の曲の直後に tracks を入れる（-1 は先頭）。インデックスは最後に知らせてもらったキューの並び。 */
  | { kind: "queue_add"; tracks: IdentifiedTrack[]; afterIndex: number; next: boolean }
  /** fromIndex の曲を、afterIndex の曲の直後へ動かす（どちらも動かす前の並びのインデックス）。 */
  | { kind: "queue_move"; fromIndex: number; afterIndex: number; itemId: string }
  /** indices の曲を外す（外す前の並びのインデックス、昇順）。 */
  | { kind: "queue_remove"; indices: number[]; itemIds: string[] }
  /** 今の曲より後ろの「次に流れる曲」（自分で足した曲でないもの）を外して、自動再生を止める。 */
  | { kind: "queue_clear_flow"; indices: number[] }
  /** ほかの端末の再生を引き取った: このキューを読み込み、index の曲の positionMs から鳴らす。 */
  | {
      kind: "adopt";
      tracks: IdentifiedTrack[];
      index: number;
      positionMs: number;
      playing: boolean;
      repeat: HandoffRepeat;
      source: HandoffSource | null;
      radioPlaylistId: string | null;
      exhausted: boolean;
      from: string;
    };

export type PairingView =
  | { state: "idle" }
  | { state: "working"; step: "sending" | "waiting" }
  /** ほかの端末（peerName）でこの6桁を入れてもらう。入れてもらえたら家に加わる。 */
  | { state: "show_code"; code: string; peerName: string }
  | { state: "joined"; peerName: string | null }
  | { state: "failed"; reason: string };

export interface DeviceView {
  deviceId: string;
  name: string;
  /** "pc"・"phone"・"api"（知らなければ null）。 */
  kind: string | null;
  role: Role;
  online: boolean;
  /** 家の再生を今書いている端末（最後に鳴った端末）。 */
  active: boolean;
  self: boolean;
  /** 「ここで再生」の行き先にできる（player で、wave API でない）。 */
  playable: boolean;
}

/** ほかの端末で鳴っている再生（表示と、ページから遠隔で操作するため）。 */
export interface RemoteView {
  deviceId: string;
  deviceName: string;
  /** 同じアカウント（遠隔の操作・引き取りができる）。 */
  accountMatches: boolean;
  online: boolean;
  playing: boolean;
  /** positionAtEpochMs の時点の位置（鳴っていれば、そこからの経過を足して進める。9分を超えたら足さない）。 */
  positionMs: number;
  positionAtEpochMs: number;
  track: IdentifiedTrack | null;
  queue: IdentifiedTrack[];
  index: number;
  repeat: HandoffRepeat;
  shuffle: boolean | null;
  volumePercent: number | null;
}

export interface ConnectStatus {
  /** 中継の宛先が設定されている。 */
  configured: boolean;
  relay: RelayStatus;
  joined: boolean;
  deviceId: string;
  deviceName: string;
  isWriter: boolean;
  activeDeviceId: string | null;
  storeResetPending: boolean;
  /** music.youtube.com のタブがつながっている（引き取り・遠隔操作を受けられる）。 */
  pageOpen: boolean;
  /** アカウントタグの出どころ: own はページのログイン（DATASYNC_ID）、borrowed は家の最後の再生のものを借りている。 */
  accountTag: "own" | "borrowed" | "none";
  pairing: PairingView;
}

export type ConnectEvent =
  | { type: "status"; status: ConnectStatus }
  | { type: "devices"; devices: DeviceView[] }
  | { type: "remote"; remote: RemoteView | null }
  | { type: "command"; command: PageCommand }
  /** 本人に知らせること（code は種類、text は日本語の文）。 */
  | { type: "notice"; code: string; text: string };

export interface ServiceOptions {
  store: KeyValueStore;
  /** 設定が保存されていないときの既定の端末名（例: "YouTube Music (Chrome)"）。 */
  defaultDeviceName: string;
  nowMs?: () => number;
  epochMs?: () => number;
  webSocket?: typeof WebSocket;
  log?: (message: string) => void;
  /** 時間の経過を自分で回すか（試験では手で tick を呼ぶ）。 */
  autoTick?: boolean;
}

interface Label {
  name: string;
  kind: string | null;
}

export function relayHostOf(relayUrl: string): string | null {
  try {
    const url = new URL(relayUrl);
    if (url.protocol !== "wss:" && url.protocol !== "ws:") return null;
    return url.host;
  } catch {
    return null;
  }
}

export class ConnectService {
  private readonly store: KeyValueStore;
  private readonly nowMs: () => number;
  private readonly epochMs: () => number;
  private readonly logFn: (message: string) => void;
  private readonly webSocket: typeof WebSocket | undefined;
  private readonly listeners = new Set<(event: ConnectEvent) => void>();
  private engine: Promise<unknown> = Promise.resolve();
  private settings: ConnectSettings;
  private device!: Device;
  private client!: ConnectClient;
  private relay: RelayConnection | null = null;
  private relayUsers = 0;
  private readonly tracker = new QueueTracker();
  private labels = new Map<string, Label>();
  private pairing: PairingView = { state: "idle" };
  private joinToken = 0;
  /** ページが知らせたログイン（undefined はまだ知らせていない、null はログインしていない）。 */
  private accountId: string | null | undefined = undefined;
  private ownTag: { householdId: string; accountId: string; tag: string } | null = null;
  private lastState: { state: PageState; capturedAtMs: number } | null = null;
  private lastPushed: { key: string; positionMs: number; playing: boolean; atMs: number; snapshot: string | null } | null = null;
  private pages = 0;
  private profileSentForMembers = -1;
  private profileSeenOnline = new Set<string>();
  private lastEmitted = new Map<string, string>();
  private tickTimer: ReturnType<typeof setInterval> | null = null;
  private readonly autoTick: boolean;

  private constructor(options: ServiceOptions, settings: ConnectSettings) {
    this.store = options.store;
    this.nowMs = options.nowMs ?? (() => performance.now());
    this.epochMs = options.epochMs ?? (() => Date.now());
    this.logFn = options.log ?? (() => {});
    this.webSocket = options.webSocket;
    this.settings = settings;
    this.autoTick = options.autoTick ?? true;
  }

  /** 保存から開く（鍵が無ければ作る）。 */
  static async open(options: ServiceOptions): Promise<ConnectService> {
    const saved = await options.store.get<ConnectSettings>(StoreKeys.settings);
    const settings: ConnectSettings = {
      relayUrl: saved?.relayUrl ?? "",
      relayHost: saved?.relayHost ?? "",
      deviceName: saved?.deviceName || options.defaultDeviceName,
    };
    const service = new ConnectService(options, settings);
    await service.load();
    return service;
  }

  private log(message: string): void {
    this.logFn(`wave-connect ${message}`);
  }

  private async load(): Promise<void> {
    let keys = await this.store.get<StoredKeys>(StoreKeys.keys);
    if (!keys) {
      // 新しい鍵（端末 ID が変わる）: その端末 ID で入っていた家の記録は使えないので消す
      this.device = await Device.generate();
      await this.store.set(StoreKeys.keys, this.device.keys);
      await this.store.delete(StoreKeys.client);
      this.log("keys created");
    } else {
      this.device = await Device.fromKeys(keys);
    }
    const labels = await this.store.get<[string, Label][]>(StoreKeys.labels);
    this.labels = new Map(labels ?? []);
    const text = await this.store.get<string>(StoreKeys.client);
    const initial = text ? decodeWith(text, readPersisted) : null;
    this.client = await this.newClient(initial);
    this.createRelay();
    if (this.autoTick) this.tickTimer = setInterval(() => void this.tick(), TICK_MS);
  }

  /** 止める（試験の後始末）。 */
  dispose(): void {
    if (this.tickTimer !== null) clearInterval(this.tickTimer);
    this.tickTimer = null;
    while (this.relayUsers > 0) this.release();
    this.listeners.clear();
  }

  private newClient(initial: ClientPersisted | null): Promise<ConnectClient> {
    return ConnectClient.create({
      signer: this.device,
      opener: this.device,
      role: "player",
      send: (m) => this.relay?.send(encodeRelay(m)) === true,
      effects: this.effects,
      nowMs: this.nowMs,
      epochMs: this.epochMs,
      newId: uuid,
      initial,
    });
  }

  /** ConnectClient を触る作業を1本に並べる（暗号が非同期なので、受信と操作を重ねない）。 */
  private run<T>(body: () => Promise<T> | T): Promise<T> {
    const next = this.engine.then(body, body);
    this.engine = next.catch((e: unknown) => this.log(`engine failed type=${e instanceof Error ? e.name : typeof e}`));
    return next;
  }

  // --- 出来事 ---

  on(listener: (event: ConnectEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private emit(event: ConnectEvent): void {
    for (const listener of [...this.listeners]) {
      try {
        listener(event);
      } catch {
        // 聞き手の失敗で止めない
      }
    }
  }

  /** 前と同じ値なら出さない（ほかの端末の再生は、位置の外挿の揺れで出し直さないよう、位置の基準の時刻を秒に丸めて比べる）。 */
  private emitIfChanged(event: Extract<ConnectEvent, { type: "status" | "devices" | "remote" }>): void {
    const text =
      event.type === "remote" && event.remote
        ? JSON.stringify({ ...event.remote, positionAtEpochMs: Math.round(event.remote.positionAtEpochMs / 1000) })
        : JSON.stringify(event);
    if (this.lastEmitted.get(event.type) === text) return;
    this.lastEmitted.set(event.type, text);
    this.emit(event);
  }

  private refresh(): void {
    this.emitIfChanged({ type: "status", status: this.status() });
    this.emitIfChanged({ type: "devices", devices: this.devices() });
    this.emitIfChanged({ type: "remote", remote: this.remote() });
  }

  /** 今の値を全部出し直す（新しくつながったページ・設定の画面へ）。 */
  snapshotEvents(): ConnectEvent[] {
    return [
      { type: "status", status: this.status() },
      { type: "devices", devices: this.devices() },
      { type: "remote", remote: this.remote() },
    ];
  }

  private notice(code: string, text: string): void {
    this.emit({ type: "notice", code, text });
  }

  private command(command: PageCommand): void {
    this.emit({ type: "command", command });
  }

  // --- 中継 ---

  getSettings(): ConnectSettings {
    return { ...this.settings };
  }

  /** 中継の宛先・端末名を変える。宛先が変わればつなぎ直す。 */
  async configure(next: Partial<ConnectSettings>): Promise<void> {
    const merged: ConnectSettings = {
      relayUrl: (next.relayUrl ?? this.settings.relayUrl).trim(),
      relayHost: (next.relayHost ?? this.settings.relayHost).trim(),
      deviceName: (next.deviceName ?? this.settings.deviceName).trim() || this.settings.deviceName,
    };
    const relayChanged = merged.relayUrl !== this.settings.relayUrl || merged.relayHost !== this.settings.relayHost;
    const nameChanged = merged.deviceName !== this.settings.deviceName;
    this.settings = merged;
    await this.store.set(StoreKeys.settings, merged);
    if (relayChanged) {
      const users = this.relayUsers;
      while (this.relayUsers > 0) this.release();
      this.createRelay();
      for (let i = 0; i < users; i++) this.acquire();
    }
    if (nameChanged) this.profileSentForMembers = -1;
    await this.run(() => this.maybeSendProfile());
    this.refresh();
  }

  private createRelay(): void {
    this.relay = null;
    const url = this.settings.relayUrl;
    const host = this.settings.relayHost || relayHostOf(url);
    if (!url || !host || relayHostOf(url) === null) return;
    const relay: RelayConnection = new RelayConnection({
      baseUrl: url,
      relayHost: host,
      identityPub: this.device.identityPub,
      sign: (label, message) => this.device.sign(label, message),
      deliver: (text) => {
        if (this.relay !== relay) return;
        const message = decodeRelay(text);
        if (message === null) return;
        void this.run(async () => {
          await this.client.onMessage(message);
          await this.maybeSendProfile();
          this.refresh();
        });
      },
      onConnectionLost: () => {
        if (this.relay !== relay) return;
        void this.run(() => {
          this.client.onDisconnected();
          this.refresh();
        });
      },
      onStatus: () => {
        if (this.relay === relay) this.refresh();
      },
      log: (m) => this.logFn(m),
      ...(this.webSocket ? { webSocket: this.webSocket } : {}),
    });
    this.relay = relay;
  }

  /** 中継につないでおく（ページか設定の画面が開いている間）。数を数え、0 になったら閉じる。 */
  acquire(): void {
    this.relayUsers++;
    this.relay?.acquire();
  }

  release(): void {
    if (this.relayUsers === 0) return;
    this.relayUsers--;
    this.relay?.release();
  }

  /** ページ（music.youtube.com のタブ）がつながった・離れた。ページが無い間は引き取りを受けない。 */
  pageConnected(): void {
    this.pages++;
    this.acquire();
    this.refresh();
  }

  pageDisconnected(): void {
    if (this.pages === 0) return;
    this.pages--;
    this.release();
    this.refresh();
    if (this.pages === 0) {
      // 鳴らしていたタブが閉じた: 一時停止のカーソルを置いて手元を捨てる
      void this.run(async () => {
        await this.client.endLocal(this.effects.accountTag());
        this.lastState = null;
        this.lastPushed = null;
        this.refresh();
      });
    }
  }

  // --- 時間 ---

  async tick(): Promise<void> {
    await this.run(async () => {
      await this.client.tick();
      await this.refreshTag();
      await this.maybeSendProfile();
      this.refresh();
    });
  }

  // --- アカウント ---

  /** ページのログイン（ytcfg の DATASYNC_ID、ログインしていなければ null）。"||" の後ろは捨てる。 */
  async setAccount(rawDataSyncId: string | null): Promise<void> {
    const next = normalizeDataSyncId(rawDataSyncId);
    await this.run(async () => {
      const previous = this.accountId;
      this.accountId = next;
      if (previous !== undefined && previous !== next) {
        // ログインが変わった: 前のアカウントの再生を一時停止のカーソルで閉じ、手元を捨てる
        const finalTag = this.ownTag?.tag ?? null;
        this.ownTag = null;
        await this.client.endLocal(finalTag);
        this.log("account changed ended local");
      }
      await this.refreshTag();
      this.refresh();
    });
  }

  private async refreshTag(): Promise<void> {
    const household = this.client.householdId;
    const account = this.accountId;
    if (!household || !account) return;
    if (this.ownTag && this.ownTag.householdId === household && this.ownTag.accountId === account) return;
    this.ownTag = { householdId: household, accountId: account, tag: await accountTagOf(household, account) };
    if (!this.client.isWriter) await this.pushLocal(true);
    await this.client.onAccountTagAvailable();
  }

  private tagSource(): "own" | "borrowed" | "none" {
    const household = this.client.householdId;
    if (household && this.ownTag?.householdId === household) return "own";
    if (household && this.accountId === undefined && this.client.latestCursorBody()?.cursor.accountTag) return "borrowed";
    return "none";
  }

  // --- 効果（ConnectClient から呼ばれる。待たずに返す） ---

  private readonly effects: ClientEffects = {
    pause: (): LocalCursor | null => {
      this.command({ kind: "pause", reason: "taken_over" });
      const local = this.localCursor();
      if (local === null) return null;
      return { ...local, playing: false };
    },
    adopt: (snapshotText: string, cursor: CursorBody, positionMs: number): boolean => {
      if (this.pages === 0) return false;
      const tag = this.effects.accountTag();
      if (tag === null || cursor.accountTag !== tag) return false;
      const decoded = decodeQueue(snapshotText);
      const adjusted = decoded && atCursor(decoded, cursor, positionMs);
      if (!adjusted) return false;
      this.tracker.adopt(adjusted);
      const index = adjusted.items.findIndex((i) => i.itemId === adjusted.currentItemId);
      const from = this.client.latestCursorBody()?.writer ?? "";
      this.command({
        kind: "adopt",
        tracks: adjusted.items.map(trackOf),
        index,
        positionMs: adjusted.positionMs,
        playing: true,
        repeat: adjusted.repeat,
        source: adjusted.source,
        radioPlaylistId: adjusted.radioPlaylistId,
        exhausted: adjusted.exhausted,
        from,
      });
      // ページが読み込んで知らせ直すまでは、取り込んだものを手元の再生とみなす
      this.lastState = {
        state: {
          queue: adjusted.items.map(trackOf),
          index,
          positionMs: adjusted.positionMs,
          playing: true,
          repeat: adjusted.repeat,
          listenedMs: adjusted.listenedMs,
          historySent: adjusted.historySent,
          shuffle: false,
        },
        capturedAtMs: this.nowMs(),
      };
      this.lastPushed = null;
      return true;
    },
    event: (event: ClientEvent) => this.onClientEvent(event),
    persist: (state: ClientPersisted) => {
      void this.store.set(StoreKeys.client, serialize(persistedToJson(state))).catch(() => this.log("persist failed"));
    },
    accountTag: (): string | null => {
      const household = this.client?.householdId;
      if (!household) return null;
      if (this.ownTag?.householdId === household) return this.ownTag.tag;
      // ページがログインを知らせるまでは、家の最後の再生のアカウントタグを借りる（wave-api と同じ）
      if (this.accountId === undefined) return this.client.latestCursorBody()?.cursor.accountTag ?? null;
      return null;
    },
    control: (action: ControlAction, positionMs: number | null): boolean => {
      if (this.pages === 0) return false;
      const onItemId = this.tracker.current?.itemId ?? null;
      switch (action) {
        case "transfer":
          // 「ここで再生」を頼まれた: 引き取る（取り込みは adopt で）
          return true;
        case "play":
          this.command({ kind: "play" });
          return true;
        case "pause":
          this.command({ kind: "pause", reason: "remote" });
          return true;
        case "next":
          this.command({ kind: "next", onItemId });
          return true;
        case "previous":
          this.command({ kind: "previous", onItemId });
          return true;
        case "seek":
          if (positionMs === null) return false;
          this.command({ kind: "seek", positionMs });
          return true;
        default:
          return false;
      }
    },
    volume: (volumePercent: number): boolean => {
      if (this.pages === 0) return false;
      this.command({ kind: "volume", volumePercent });
      return true;
    },
    shuffle: (on: boolean): boolean => {
      if (this.pages === 0) return false;
      this.command({ kind: "shuffle", on });
      return true;
    },
    setRepeat: (mode: HandoffRepeat): boolean => {
      if (this.pages === 0) return false;
      this.command({ kind: "repeat", mode });
      return true;
    },
    queueAdd: (items: HandoffItem[], next: boolean, afterItemId: string | null): boolean => {
      if (this.pages === 0) return false;
      const after = this.tracker.insertionAfter(next, afterItemId);
      this.tracker.insert(after, items);
      this.command({ kind: "queue_add", tracks: items.map(trackOf), afterIndex: after, next });
      return true;
    },
    queueMove: (itemId: string, afterItemId: string | null): boolean => {
      if (this.pages === 0) return false;
      const moved = this.tracker.move(itemId, afterItemId);
      if (moved === null) return false;
      this.command({ kind: "queue_move", fromIndex: moved.from, afterIndex: moved.after, itemId });
      return true;
    },
    queueRemove: (itemIds: string[]): boolean => {
      if (this.pages === 0) return false;
      const ids = itemIds.filter((id) => this.tracker.indexOf(id) > this.tracker.index);
      const indices = this.tracker.remove(ids);
      if (indices.length === 0) return false;
      this.command({ kind: "queue_remove", indices, itemIds: ids });
      return true;
    },
    queueClearFlow: (currentItemId: string): boolean => {
      if (this.pages === 0 || this.tracker.current?.itemId !== currentItemId) return false;
      this.command({ kind: "queue_clear_flow", indices: this.tracker.clearFlow() });
      return true;
    },
  };

  private labelOf(deviceId: string): string {
    if (deviceId === this.device.deviceId) return this.settings.deviceName;
    const label = this.labels.get(deviceId);
    if (label) return label.name;
    return `端末 ${deviceId.slice(0, 6)}`;
  }

  private saveLabels(): void {
    void this.store.set(StoreKeys.labels, [...this.labels.entries()]).catch(() => this.log("labels save failed"));
  }

  private onClientEvent(event: ClientEvent): void {
    this.log(`event=${event.type}`);
    switch (event.type) {
      case "profile": {
        const kind = event.kind === "pc" || event.kind === "phone" || event.kind === "api" ? event.kind : (this.labels.get(event.deviceId)?.kind ?? null);
        const name = sanitizeLabel(event.name) || this.labels.get(event.deviceId)?.name || "";
        const next = { name: name || `端末 ${event.deviceId.slice(0, 6)}`, kind };
        const current = this.labels.get(event.deviceId);
        if (!current || current.name !== next.name || current.kind !== next.kind) {
          this.labels.set(event.deviceId, next);
          this.saveLabels();
        }
        break;
      }
      case "joined":
        this.profileSentForMembers = -1;
        if (this.pairing.state === "show_code" || this.pairing.state === "working") {
          this.setPairing({ state: "joined", peerName: this.pairing.state === "show_code" ? this.pairing.peerName : null });
        }
        break;
      case "stopped":
        this.notice("stopped", `${this.labelOf(event.by)}で再生を始めたので、ここでは止めました`);
        break;
      case "transfer_completed":
        this.notice("transfer_completed", `${this.labelOf(event.from)}から引き継ぎました`);
        break;
      case "transfer_failed":
        this.notice("transfer_failed", "引き継げませんでした");
        break;
      case "store_reset":
        this.notice("store_reset", "中継の状態が作り直されました。拡張の設定で確かめてください");
        break;
      case "chain_forked":
      case "chain_rolled_back":
      case "chain_invalid":
        this.notice(event.type, "中継から届いた端末の一覧を確かめられませんでした");
        if (this.pairing.state === "show_code") this.setPairing({ state: "failed", reason: "chain_unverified" });
        break;
      case "state_equivocation":
        this.notice(event.type, "中継から食い違った状態が届きました");
        break;
      case "remote_changed":
      case "server_error":
        break;
    }
  }

  // --- 加入（B の側。wave-api の ApiHub.pair・Swift の runAccept と同じ手順） ---

  private setPairing(view: PairingView): void {
    this.pairing = view;
    this.refresh();
  }

  /**
   * 家の端末が出した QR の文字（wave-pair:…）で加わる。6桁を返す（家の端末に入れてもらう）。その後は加入を待つ。
   * ページがログイン（DATASYNC_ID）を知らせていればそのアカウントで照合し、知らせていなければログインなし（wave-accountless-v1）で照合する。
   */
  async join(offerText: string): Promise<PairingView> {
    const fail = (reason: string): PairingView => {
      this.setPairing({ state: "failed", reason });
      return this.pairing;
    };
    const relay = this.relay;
    if (relay === null) return fail("not_configured");
    if (this.client.householdId !== null) return fail("already_joined");
    const offer = await decodeOffer(offerText.trim());
    if (offer === null) return fail("not_wave_pair");
    const token = ++this.joinToken;
    this.setPairing({ state: "working", step: "sending" });
    const memberKeys = serialize(memberKeysToJson(await this.device.memberKeys("player")));
    const initiator = await Initiator.create({
      offer,
      identity: this.device,
      deviceName: this.settings.deviceName,
      accountId: this.accountId ?? null,
      helloExtension: memberKeys,
    });
    const hello = await initiator.hello();
    const reply = await relay.pair(encodeRelay({ op: "pair_hello_in", token: offer.token, hello: helloToJson(hello) }));
    if (token !== this.joinToken) return this.pairing;
    const message = reply === null ? null : decodeRelay(reply);
    if (message?.op !== "pair_confirmed") return fail(reply === null ? "relay_unreachable" : "peer_not_waiting");
    const confirm = decodeConfirm(message.confirm);
    if (confirm === null) return fail("bad_reply");
    const confirmed = await initiator.onConfirm(confirm);
    if (!confirmed.ok) return fail(confirmed.failure === "account_mismatch" ? "account_mismatch" : "peer_unverified");
    const info = confirmed.value.pair.peerExtension === null ? null : decodeWith(confirmed.value.pair.peerExtension, readJoinInfo);
    if (info === null) return fail("peer_unsupported");
    const inviter = await deviceIdOf(confirmed.value.pair.peerPublicKey);
    const peerName = confirmed.value.pair.peerDeviceName;
    const started = await this.run(() => {
      if (this.client.householdId !== null) return false;
      this.client.expectJoin(info, inviter);
      this.labels.set(inviter, { name: peerName, kind: this.labels.get(inviter)?.kind ?? null });
      this.saveLabels();
      return true;
    });
    if (!started) return fail("already_joined");
    this.setPairing({ state: "show_code", code: confirmed.value.code, peerName });
    void this.awaitJoin(token);
    return this.pairing;
  }

  /** 加えてもらうまで、間を空けてつなぎ直す（加えてもらう前は中継が認証で断る）。 */
  private async awaitJoin(token: number): Promise<void> {
    this.acquire();
    try {
      const startedAt = this.nowMs();
      while (this.nowMs() - startedAt < JOIN_WAIT_MS) {
        if (token !== this.joinToken || this.pairing.state !== "show_code") return;
        if (this.client.householdId !== null) return;
        if (this.relay && this.relay.status !== "connected" && this.relay.status !== "connecting") this.relay.reconnect();
        await new Promise((resolve) => setTimeout(resolve, JOIN_RETRY_MS));
      }
      if (token === this.joinToken && this.pairing.state === "show_code") {
        await this.run(() => this.client.cancelJoin());
        this.setPairing({ state: "failed", reason: "join_timed_out" });
      }
    } finally {
      this.release();
    }
  }

  /** 加入をやめる。 */
  async cancelJoin(): Promise<void> {
    this.joinToken++;
    await this.run(() => this.client.cancelJoin());
    this.setPairing({ state: "idle" });
  }

  /** この端末の家の記録を消す（家から外された・作り直された後に加わり直す）。身元の鍵は残す。 */
  async forgetHousehold(): Promise<void> {
    this.joinToken++;
    await this.run(async () => {
      this.client.clearLocal();
      await this.store.delete(StoreKeys.client);
      this.labels.clear();
      this.saveLabels();
      this.ownTag = null;
      this.client = await this.newClient(null);
      this.relay?.restart();
    });
    this.setPairing({ state: "idle" });
  }

  /** 本人が「この状態から始める」を押した（L-12）。 */
  async acceptStoreReset(): Promise<void> {
    await this.run(() => this.client.acceptStoreReset());
    this.refresh();
  }

  // --- ページの再生 ---

  /** 手元の再生（ページの状態）から、カーソルに入れる値。 */
  private localCursor(): LocalCursor | null {
    const last = this.lastState;
    const current = this.tracker.current;
    if (!last || !current) return null;
    const s = last.state;
    const elapsed = s.playing ? Math.max(0, this.nowMs() - last.capturedAtMs) : 0;
    return {
      currentItemId: current.itemId,
      positionMs: Math.max(0, Math.round(s.positionMs + elapsed)),
      playing: s.playing,
      repeat: s.repeat ?? "off",
      listenedMs: Math.max(0, Math.round(s.listenedMs ?? s.positionMs)),
      historySent: s.historySent ?? true,
      capturedAtMs: this.nowMs(),
      volumePercent: s.volumePercent == null ? null : Math.min(100, Math.max(0, Math.round(s.volumePercent))),
      // 遠隔のシャッフル・繰り返しに従う端末なので、必ず入れる（ほかの端末はこれを見て操作を出す）
      shuffle: s.shuffle ?? false,
    };
  }

  /** 手元のキューとカーソルを ConnectClient に渡す（force なら変化が小さくても渡す）。 */
  private async pushLocal(force: boolean): Promise<void> {
    const cursor = this.localCursor();
    if (cursor === null) return;
    const snapshot = this.tracker.snapshotText();
    const key = [cursor.currentItemId, cursor.playing, cursor.repeat, cursor.shuffle, cursor.volumePercent].join("|");
    const now = this.nowMs();
    const pushed = this.lastPushed;
    if (!force && pushed && pushed.key === key && pushed.snapshot === snapshot) {
      const expected = pushed.playing ? pushed.positionMs + (now - pushed.atMs) : pushed.positionMs;
      if (Math.abs(expected - cursor.positionMs) < POSITION_TOLERANCE_MS && now - pushed.atMs < CURSOR_REFRESH_MS) return;
    }
    if (snapshot !== null) await this.client.setLocalQueue(snapshot);
    await this.client.setLocalCursor(cursor);
    this.lastPushed = { key, positionMs: cursor.positionMs, playing: cursor.playing, atMs: now, snapshot };
  }

  /**
   * ページの再生の様子を知らせる（曲・再生/一時停止・シーク・キュー・音量・シャッフル・繰り返しが変わったとき。定期的に送ってもよい）。
   * この端末が家の再生を書いているときだけ中継に書く。位置だけの小さな変化は書かない。
   */
  async publishState(state: PageState): Promise<void> {
    await this.run(async () => {
      this.tracker.update(state.queue, state.index, {
        source: state.source ?? null,
        radioPlaylistId: state.radioPlaylistId ?? null,
        exhausted: state.exhausted === true,
      });
      this.lastState = { state, capturedAtMs: this.nowMs() };
      if (this.tracker.current === null) {
        await this.client.endLocal(this.effects.accountTag());
        this.lastPushed = null;
      } else {
        await this.pushLocal(false);
      }
      this.refresh();
    });
  }

  /**
   * 本人がページで再生を始めた（曲を選んだ・再生ボタンを押した）。この端末を家の再生にする（ほかの端末は止まる。後から始めた端末が優先、L-3）。
   * 先に publishState で今の状態を知らせておくこと。
   * @returns 家の再生にしたか（家に入っていない・アカウントタグが無い・家の再生が別のアカウントなら false。ページは手元だけで鳴らしてよい）
   */
  async userStartedPlayback(): Promise<boolean> {
    return this.run(async () => {
      await this.pushLocal(true);
      const claimed = await this.client.userStartedPlayback();
      this.log(`user started claimed=${claimed}`);
      this.refresh();
      return claimed;
    });
  }

  /** claimPlayback = publishState + userStartedPlayback。 */
  async claimPlayback(state: PageState): Promise<boolean> {
    await this.publishState(state);
    return this.userStartedPlayback();
  }

  /** ページの再生が無くなった（キューを消した）。家の再生を書いていれば、一時停止のカーソルを置いて手元を捨てる。 */
  async endLocal(): Promise<void> {
    await this.run(async () => {
      await this.client.endLocal(this.effects.accountTag());
      this.lastState = null;
      this.lastPushed = null;
      this.refresh();
    });
  }

  // --- ほかの端末へ ---

  /** 家の端末 deviceId で再生する（今の家の再生を、その端末に引き取ってもらう）。 */
  async transferTo(deviceId: string): Promise<boolean> {
    if (deviceId === this.device.deviceId) return this.takeOver();
    return this.run(() => this.client.requestRemoteTransfer(deviceId));
  }

  /** 「ここで再生」: ほかの端末で鳴っている再生を、この端末（ページ）に引き取る。 */
  async takeOver(): Promise<boolean> {
    if (this.pages === 0) return false;
    return this.run(() => this.client.requestTransfer());
  }

  /** ページの今のキューを、家の端末 deviceId で鳴らす（ここでは鳴らさない）。 */
  async playOn(deviceId: string): Promise<boolean> {
    return this.run(async () => {
      await this.pushLocal(true);
      const local = this.localCursor();
      if (local) await this.client.setLocalCursor({ ...local, playing: false });
      return this.client.playOn(deviceId);
    });
  }

  /** ほかの端末で鳴っている再生を操作する（この端末が鳴らしていないとき）。 */
  sendControl(action: "play" | "pause" | "next" | "previous" | "seek", positionMs?: number): Promise<boolean> {
    return this.run(() => this.client.sendControl(action, positionMs ?? null));
  }

  sendVolume(volumePercent: number): Promise<boolean> {
    return this.run(() => this.client.sendVolume(volumePercent));
  }

  sendShuffle(on: boolean): Promise<boolean> {
    return this.run(() => this.client.sendShuffle(on));
  }

  sendRepeat(mode: HandoffRepeat): Promise<boolean> {
    return this.run(() => this.client.sendRepeat(mode));
  }

  /** ほかの端末のキューに曲を足す（next なら「次に再生」）。 */
  sendQueueAdd(tracks: PageTrack[], next: boolean): Promise<boolean> {
    const items = tracks.map((t) => itemOf(t, uuid(), "user")).filter((i): i is HandoffItem => i !== null);
    return this.run(() => this.client.sendQueueAdd(items, next));
  }

  sendQueueMove(itemId: string, afterItemId: string | null): Promise<boolean> {
    return this.run(() => this.client.sendQueueMove(itemId, afterItemId));
  }

  sendQueueRemove(itemIds: string[]): Promise<boolean> {
    return this.run(() => this.client.sendQueueRemove(itemIds));
  }

  sendQueueClearFlow(currentItemId: string): Promise<boolean> {
    return this.run(() => this.client.sendQueueClearFlow(currentItemId));
  }

  // --- 見せる値 ---

  get deviceId(): string {
    return this.device.deviceId;
  }

  status(): ConnectStatus {
    return {
      configured: this.relay !== null,
      relay: this.relay?.status ?? "disconnected",
      joined: this.client.householdId !== null,
      deviceId: this.device.deviceId,
      deviceName: this.settings.deviceName,
      isWriter: this.client.isWriter,
      activeDeviceId: this.client.activeDeviceId,
      storeResetPending: this.client.storeResetPending,
      pageOpen: this.pages > 0,
      accountTag: this.tagSource(),
      pairing: this.pairing,
    };
  }

  devices(): DeviceView[] {
    const chain = this.client.verifiedChain();
    if (!chain) return [];
    const online = this.client.online;
    const writer = this.client.latestCursorBody()?.writer ?? null;
    return chain.orderedMembers.map((m) => {
      const self = m.keys.deviceId === this.device.deviceId;
      const kind = self ? DEVICE_KIND : (this.labels.get(m.keys.deviceId)?.kind ?? null);
      return {
        deviceId: m.keys.deviceId,
        name: self ? this.settings.deviceName : this.labels.get(m.keys.deviceId)?.name ?? (kind === "api" ? "wave API" : `端末 ${m.keys.deviceId.slice(0, 6)}`),
        kind,
        role: m.keys.role,
        online: self ? this.relay?.status === "connected" : online.has(m.keys.deviceId),
        active: m.keys.deviceId === writer,
        self,
        playable: m.keys.role === "player" && kind !== "api",
      };
    });
  }

  remote(): RemoteView | null {
    const now = this.client.remoteNowPosition();
    if (!now) return null;
    const tag = this.effects.accountTag();
    let queue: IdentifiedTrack[] = [];
    let index = -1;
    const latestQueue = this.client.latestQueueBody();
    if (latestQueue && this.client.latestQueueHash() === now.cursor.queueRef) {
      const snapshot = decodeQueue(latestQueue.queue.snapshot);
      if (snapshot) {
        queue = snapshot.items.map(trackOf);
        index = snapshot.items.findIndex((i) => i.itemId === now.cursor.currentItemId);
      }
    }
    return {
      deviceId: now.writer,
      deviceName: this.labelOf(now.writer),
      accountMatches: tag !== null && now.cursor.accountTag === tag,
      online: this.client.online.has(now.writer),
      playing: now.cursor.playing,
      positionMs: now.cursor.positionMs,
      positionAtEpochMs: this.epochMs() - (now.positionMs - now.cursor.positionMs),
      track: index >= 0 ? queue[index]! : null,
      queue,
      index,
      repeat: now.cursor.repeat,
      shuffle: now.cursor.shuffle,
      volumePercent: now.cursor.volumePercent,
    };
  }

  // --- 名前を知らせる ---

  /** 中継につながったとき・家の端末が増えたとき・ほかの端末が新しくつながったときに、この端末の名前と種類を家に知らせる。 */
  private async maybeSendProfile(): Promise<void> {
    if (this.relay?.status !== "connected" || this.client.householdId === null) {
      if (this.relay?.status !== "connected") this.profileSentForMembers = -1;
      return;
    }
    const count = this.client.members.length;
    const online = this.client.online;
    const newcomer = [...online].some((id) => id !== this.device.deviceId && !this.profileSeenOnline.has(id));
    this.profileSeenOnline = new Set(online);
    if (count === this.profileSentForMembers && !newcomer) return;
    if (await this.client.sendProfile(this.settings.deviceName, DEVICE_KIND)) this.profileSentForMembers = count;
  }
}

/** 表示名から、制御文字と書字方向を変える文字を除く。 */
function sanitizeLabel(name: string): string {
  let out = "";
  for (const ch of name) {
    const c = ch.codePointAt(0)!;
    if (c <= 0x1f || (c >= 0x7f && c <= 0x9f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) || c === 0x200e || c === 0x200f) continue;
    out += ch;
  }
  out = out.trim();
  return out.length > 64 ? out.slice(0, 64) : out;
}
