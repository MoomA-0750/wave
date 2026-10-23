// Connect v2 の端末側（Android 版の ConnectClient・Swift の ConnectClient と同じ状態機械、D-2・D-6〜D-9）。
// 暗号は WebCrypto で非同期なので、呼ぶ側は受信・操作・時間の経過（tick）を1本ずつ await すること（同時に呼ばない。ConnectEngine がそろえる）。
// 再生そのものは effects に任せる。端末の時計は順序の判断に使わない（L-10）。ライブラリ（ADR-0013）は扱わない。

import { decide as arbiterDecide, type ArbiterSession, type Evidence } from "./arbiter.ts";
import { utf8 } from "./bytes.ts";
import { verifyChain, recordHash, type DeviceSigner, type Head, type KxOpener, type VerifiedChain } from "./chain.ts";
import { docHash, openDoc, sealDoc } from "./doc.ts";
import { decodeWith, serialize } from "./json.ts";
import {
  commandToJson,
  cursorToJson,
  emptyPersisted,
  queueToJson,
  readCommand,
  readCursor,
  readQueue,
  QUEUE_EDIT_CLEAR_FLOW,
  QUEUE_EDIT_MOVE,
  QUEUE_EDIT_REMOVE,
  type ChainRecord,
  type ClientPersisted,
  type CommandBody,
  type ControlAction,
  type ControlCommand,
  type CursorBody,
  type HandoffItem,
  type HandoffRepeat,
  type JoinInfo,
  type MemberKeys,
  type QueueAddCommand,
  type QueueBody,
  type QueueEditCommand,
  type Role,
  type SealedDoc,
  type StateKind,
  type StoredState,
} from "./models.ts";
import type { RelayMessage } from "./relay-messages.ts";
import { serializePublicKey } from "./hpke.ts";
import { equalBytes } from "./bytes.ts";

export const TRANSFER_WAIT_MS = 3_000;
export const FLOOR_PERSIST_MS = 30_000;
export const MAX_EXTRAPOLATION_MS = 9 * 60 * 1000;
const MAX_HANDLED_CLAIMS = 64;
/** 遠隔操作の送り手が見ていた版と、受け手の最新の書き込みの版の差の上限。 */
const MAX_CONTROL_LAG = 20;
/** 遠隔操作の鮮度の上限（命令の見出しの時刻と受け手の時計の差）。 */
const MAX_CONTROL_AGE_MS = 5 * 60 * 1000;
const HAND_OFF_WAIT_MS = 10_000;
/** キューへの追加の命令1件の平文の上限（Android 版と同じ）。 */
export const MAX_QUEUE_ADD_PLAINTEXT = 3800;

export type ClientEvent =
  /** ほかの端末の再生で、この端末が止まった。 */
  | { type: "stopped"; by: string }
  /** ほかの端末の状態が変わった（表示）。accountMatches が false なら、別のアカウントの端末。 */
  | { type: "remote_changed"; writer: string; cursor: CursorBody; accountMatches: boolean }
  | { type: "chain_forked" }
  | { type: "chain_rolled_back" }
  | { type: "chain_invalid"; reason: string }
  /** サーバーの状態が作り直された。acceptStoreReset を呼ぶまで、その状態で止まらない・取り込まない（L-12）。 */
  | { type: "store_reset"; newStoreId: string }
  | { type: "state_equivocation" }
  | { type: "transfer_failed"; reason: string }
  | { type: "transfer_completed"; from: string }
  | { type: "server_error"; code: string }
  /** 家に入った。 */
  | { type: "joined" }
  /** 家の端末が表示名を知らせてきた。 */
  | { type: "profile"; deviceId: string; name: string; kind: string | null };

/** 手元の再生の、カーソルに入れる値。capturedAtMs は positionMs を取った時刻（nowMs と同じ単調時計）。 */
export interface LocalCursor {
  currentItemId: string;
  positionMs: number;
  playing: boolean;
  repeat: HandoffRepeat;
  listenedMs: number;
  historySent: boolean;
  capturedAtMs: number;
  /** この端末の音量（0〜100）（PC のアプリの中の音量）。 */
  volumePercent: number | null;
  /** シャッフル中か（遠隔のシャッフル・繰り返しに従う端末は必ず入れる）。 */
  shuffle: boolean | null;
}

/** ConnectClient が再生そのものと通知・保存を任せる先。どれも待たずに返す。 */
export interface ClientEffects {
  /** 一時停止し、止めた時点の手元の再生を返す。 */
  pause(): LocalCursor | null;
  /** 取り込んで positionMs から再生を始める。受け付けなければ false。 */
  adopt(snapshot: string, cursor: CursorBody, positionMs: number): boolean;
  event(event: ClientEvent): void;
  persist(state: ClientPersisted): void;
  /** 今のログインの accountTag（無ければ null）。 */
  accountTag(): string | null;
  /** 遠隔操作に従う（この端末が鳴らしている。transfer は「ここで再生」を頼まれた）。受け付けなければ false。 */
  control(action: ControlAction, positionMs: number | null): boolean;
  controlAccepted?(action: ControlAction, from: string): void;
  volume?(volumePercent: number): boolean;
  shuffle?(on: boolean): boolean;
  setRepeat?(mode: HandoffRepeat): boolean;
  queueAdd?(items: HandoffItem[], next: boolean, afterItemId: string | null): boolean;
  queueMove?(itemId: string, afterItemId: string | null): boolean;
  queueRemove?(itemIds: string[]): boolean;
  queueClearFlow?(currentItemId: string): boolean;
}

interface Opened<T> {
  stored: StoredState;
  body: T;
  hash: string;
  receivedAtLocalMs: number;
}

const writerOf = (o: Opened<unknown>): string => o.stored.doc.header.writerId;

interface Inflight {
  kind: StateKind;
  doc: SealedDoc;
  hash: string;
  cursor: CursorBody | null;
  queue: QueueBody | null;
  sessionId: number;
}

interface TransferState {
  claimId: string;
  previousWriter: string;
  startedAtMs: number;
  retried: boolean;
  adopting: boolean;
}

interface Session {
  id: number;
  arbiter: ArbiterSession;
  claimId: string | null;
  claimOrder: number | null;
}

export interface RemoteNow {
  writer: string;
  cursor: CursorBody;
  positionMs: number;
  version: number;
}

export class ConnectClient {
  private readonly signer: DeviceSigner;
  private readonly opener: KxOpener;
  private readonly role: Role;
  private readonly send: (m: RelayMessage) => boolean;
  private readonly effects: ClientEffects;
  private readonly nowMs: () => number;
  private readonly epochMs: () => number;
  private readonly newId: () => string;

  private records: ChainRecord[];
  private chain: VerifiedChain | null = null;
  private storeId: string | null;
  private pendingStoreId: string | null = null;
  private cursorFloor: number;
  private queueFloor: number;
  private retreated: boolean;
  private handledClaims: string[];
  private expectedJoin: { info: JoinInfo; inviter: string } | null;
  private creatingHousehold: boolean;
  private startWaitingForTag = false;
  private latestCursor: Opened<CursorBody> | null = null;
  private latestQueue: Opened<QueueBody> | null = null;
  private deferred: StoredState[] = [];
  private serverActive: string | null = null;
  private pendingOwner: string | null = null;
  private claimOrders = new Map<string, number>();
  /** 中継につながっている家の端末（表示と D-9 の判断だけに使い、止まる根拠にしない）。 */
  online = new Set<string>();
  private welcomed = false;
  private deferredEvidence: Evidence[] = [];
  private localQueue: string | null = null;
  private localQueueRef: string | null = null;
  private local: LocalCursor | null = null;
  private session: Session | null = null;
  private sessionCounter = 0;
  private deviceSeq = 0;
  private lastOwnCursorHash: string | null = null;
  private inflight = new Map<string, Inflight>();
  private dirtyCursor = false;
  private dirtyQueue = false;
  private transfer: TransferState | null = null;
  private resumePending = false;
  private floorDirty = false;
  private lastPersistMs: number | null = null;
  private handOff: { target: string; startedAtMs: number } | null = null;
  private seenControlDocs: string[] = [];

  private constructor(args: {
    signer: DeviceSigner;
    opener: KxOpener;
    role: Role;
    send: (m: RelayMessage) => boolean;
    effects: ClientEffects;
    nowMs: () => number;
    epochMs: () => number;
    newId: () => string;
    initial: ClientPersisted;
  }) {
    this.signer = args.signer;
    this.opener = args.opener;
    this.role = args.role;
    this.send = args.send;
    this.effects = args.effects;
    this.nowMs = args.nowMs;
    this.epochMs = args.epochMs;
    this.newId = args.newId;
    const initial = args.initial;
    this.records = initial.chain;
    this.storeId = initial.storeId;
    this.cursorFloor = initial.cursorFloor;
    this.queueFloor = initial.queueFloor;
    this.retreated = initial.retreated;
    this.handledClaims = [...initial.handledClaimIds];
    this.expectedJoin = initial.expectedJoin && initial.expectedInviter ? { info: initial.expectedJoin, inviter: initial.expectedInviter } : null;
    this.creatingHousehold = initial.creatingHousehold;
  }

  /** 保存したものから作る（鎖は genesis から検証し直す）。 */
  static async create(args: {
    signer: DeviceSigner;
    opener: KxOpener;
    role: Role;
    send: (m: RelayMessage) => boolean;
    effects: ClientEffects;
    nowMs: () => number;
    epochMs: () => number;
    newId: () => string;
    initial?: ClientPersisted | null;
  }): Promise<ConnectClient> {
    const client = new ConnectClient({ ...args, initial: args.initial ?? emptyPersisted() });
    if (client.records.length > 0) {
      const verified = await verifyChain(client.records);
      client.chain = verified.kind === "ok" ? verified.chain : null;
    }
    return client;
  }

  get isWriter(): boolean {
    return this.session !== null;
  }

  get activeDeviceId(): string | null {
    return this.serverActive;
  }

  get hasRetreated(): boolean {
    return this.retreated;
  }

  /** 検証した家の ID。家に入っていなければ null。 */
  get householdId(): string | null {
    return this.chain?.householdId ?? null;
  }

  /** 検証した端末一覧（加えた順）。 */
  get members(): MemberKeys[] {
    return this.chain?.orderedMembers.map((m) => m.keys) ?? [];
  }

  get storeResetPending(): boolean {
    return this.pendingStoreId !== null;
  }

  verifiedChain(): VerifiedChain | null {
    return this.chain;
  }

  verifiedRecords(): ChainRecord[] | null {
    return this.chain === null ? null : this.records;
  }

  latestCursorBody(): { writer: string; cursor: CursorBody; version: number } | null {
    const c = this.latestCursor;
    return c ? { writer: writerOf(c), cursor: c.body, version: c.stored.version } : null;
  }

  latestQueueBody(): { writer: string; queue: QueueBody } | null {
    const q = this.latestQueue;
    return q ? { writer: writerOf(q), queue: q.body } : null;
  }

  latestQueueHash(): string | null {
    return this.latestQueue?.hash ?? null;
  }

  /** 今の接続で最新の状態をそろえた（D-9 の判断はこれが true のときだけ行う）。 */
  get stateSettled(): boolean {
    return this.welcomed && this.deferred.length === 0;
  }

  /** 同じアカウントの他の端末が今鳴っているか（D-9）。 */
  remotePlayingNow(): boolean {
    const cursor = this.latestCursor;
    if (!cursor) return false;
    const tag = this.effects.accountTag();
    if (tag === null) return false;
    return writerOf(cursor) !== this.signer.deviceId && cursor.body.playing && cursor.body.accountTag === tag && this.online.has(writerOf(cursor));
  }

  /** A 側: Confirm に入れる床（家に入っていなければ null）。 */
  currentJoinInfo(): JoinInfo | null {
    const current = this.chain;
    if (current === null || this.storeId === null) return null;
    return {
      householdId: current.householdId,
      headSeq: current.headSeq,
      headHash: current.headHash,
      storeId: this.storeId,
      cursorFloor: this.cursorFloor,
      queueFloor: this.queueFloor,
    };
  }

  /** B 側: 加入を待つ。次に受ける鎖が、この家の ID・頭を含み、その後に inviter の署名で自分を加える記録があるときだけ受け入れる。 */
  expectJoin(info: JoinInfo, inviterDeviceId: string): void {
    if (this.chain !== null) return;
    this.expectedJoin = { info, inviter: inviterDeviceId };
    this.creatingHousehold = false;
    this.storeId = info.storeId;
    this.cursorFloor = info.cursorFloor;
    this.queueFloor = info.queueFloor;
    this.persist();
  }

  get joinPending(): boolean {
    return this.chain === null && this.expectedJoin !== null;
  }

  // --- 受信 ---

  async onMessage(message: RelayMessage): Promise<void> {
    switch (message.op) {
      case "welcome":
        return this.onWelcome(message);
      case "chain":
        return this.onChain(message.records);
      // 自分の追記の返事（chain_ok）は、他の端末への chain_changed を受けないので、同じく取り直す
      case "chain_changed":
      case "chain_ok":
        return this.onHeadAnnounced(message.headSeq, message.headHash);
      case "state":
        return this.onState(message.state);
      case "state_ok":
        return this.onStateOk(message.requestId, message.kind, message.version);
      case "state_conflict":
        return this.onStateConflict(message.requestId, message.kind, message.latest);
      case "error":
        return this.onError(message.code, message.requestId);
      case "yield":
        return this.onYield(message.claimId, message.yieldDoc);
      case "control":
        return this.onControl(message.doc);
      case "active_changed":
        return this.onActiveChanged(message);
      case "pending_expired":
        return this.onPendingExpired(message.claimId);
      case "presence":
        this.online = new Set(message.online);
        return;
      default:
        return;
    }
  }

  /** 接続が切れた。返事を待っていた書き込みは、つながり直した後に最新の版へ積み直す。 */
  onDisconnected(): void {
    this.resetConnection();
    // 取り込む前に切れた引き取りは捨てる
    if (this.transfer && !this.transfer.adopting) this.failTransfer("disconnected");
  }

  private resetConnection(): void {
    for (const sent of this.inflight.values()) this.markUnsent(sent.kind);
    this.inflight.clear();
    this.serverActive = null;
    this.pendingOwner = null;
    this.online = new Set();
    this.welcomed = false;
  }

  /** 家を作ると押した。次に受ける最初の鎖が自分の genesis なら受け入れる（試験の相手が使う）。 */
  beginCreateHousehold(): void {
    if (this.chain !== null) return;
    this.creatingHousehold = true;
    this.expectedJoin = null;
    this.persist();
  }

  /** 加入の待ちをやめた。後でつながったときに、黙って家に入らない。 */
  cancelJoin(): void {
    if (this.chain !== null || this.expectedJoin === null) return;
    this.expectedJoin = null;
    this.persist();
  }

  /** ログアウト・別のログインに変わった: 手元のキュー・再生・セッションを捨てる。 */
  clearLocal(): void {
    this.session = null;
    this.localQueue = null;
    this.localQueueRef = null;
    this.local = null;
    this.lastOwnCursorHash = null;
    this.dirtyCursor = false;
    this.dirtyQueue = false;
    this.inflight.clear();
    this.deferredEvidence = [];
    this.startWaitingForTag = false;
    this.resumePending = false;
    const waiting = this.transfer;
    this.transfer = null;
    if (waiting) this.send({ op: "release", claimId: waiting.claimId });
  }

  /** 手元の再生が無くなった。書き手なら、finalTag で一時停止のカーソルを1回だけ送ってから、手元を捨てる。 */
  async endLocal(finalTag: string | null): Promise<void> {
    const current = this.chain;
    if (current && this.session && this.local && this.localQueueRef && finalTag !== null && this.pendingStoreId === null) {
      const body = this.cursorBodyFor(null, finalTag);
      if (body) {
        body.playing = false;
        const expect = Math.max(this.latestCursor?.stored.version ?? 0, this.cursorFloor);
        const doc = await sealDoc({
          signer: this.signer,
          chain: current,
          kind: "cursor",
          version: expect + 1,
          prevHash: this.lastOwnCursorHash,
          issuedAtEpochMs: this.epochMs(),
          plaintext: utf8(serialize(cursorToJson(body))),
        });
        this.send({ op: "state_put", requestId: this.newId(), kind: "cursor", expectVersion: expect, doc });
      }
    }
    this.clearLocal();
  }

  /** アカウントタグができた。タグが無い間に始めた再生が続いていれば claim する。 */
  async onAccountTagAvailable(): Promise<void> {
    if (!this.startWaitingForTag) return;
    this.startWaitingForTag = false;
    if (this.local?.playing && this.session === null) await this.userStartedPlayback();
  }

  private onHeadAnnounced(headSeq: number, headHash: string | null): void {
    const current = this.chain;
    if (current && headSeq <= current.headSeq) {
      if (current.hashAt(headSeq) !== headHash) this.effects.event({ type: "chain_forked" });
    } else {
      this.send({ op: "chain_get", fromSeq: this.records.length });
    }
  }

  private async onWelcome(welcome: Extract<RelayMessage, { op: "welcome" }>): Promise<void> {
    // 前の接続の書き込みは届いていないかもしれない（返事を待っていたものは積み直す）。transfer は捨てない
    this.resetConnection();
    this.welcomed = true;
    this.serverActive = welcome.activeDeviceId;
    this.online = new Set(welcome.online);
    for (const [device, order] of welcome.claimOrders) this.claimOrders.set(device, order);
    if (this.storeId !== null) {
      if (this.storeId !== welcome.storeId) {
        this.pendingStoreId = welcome.storeId;
        this.effects.event({ type: "store_reset", newStoreId: welcome.storeId });
      }
    } else {
      this.storeId = welcome.storeId;
      this.persist();
    }
    this.resumePending = this.isWriter;
    if (welcome.headSeq !== null) this.onHeadAnnounced(welcome.headSeq, welcome.headHash);
    // 最新の状態を先に見る（通信を失ったまま鳴っていた端末は、ここで止まる）
    for (const state of welcome.states) await this.onState(state);
    await this.maybeResume();
  }

  /** 本人が「この状態から始める」を押した（L-12）。床を捨てて新しい storeId を受け入れる。 */
  async acceptStoreReset(): Promise<void> {
    const next = this.pendingStoreId;
    if (next === null) return;
    this.storeId = next;
    this.pendingStoreId = null;
    this.cursorFloor = 0;
    this.queueFloor = 0;
    this.latestCursor = null;
    this.latestQueue = null;
    this.persist();
    const mine = this.session;
    if (mine) {
      mine.arbiter = { base: 0, startVersion: null };
      await this.sendClaimFor(mine);
      this.markDirty();
    }
    this.send({ op: "state_get" });
    await this.flush();
  }

  private async onChain(newRecords: ChainRecord[]): Promise<void> {
    // 取得が重なって同じ記録が2回届いても、分岐にしない（seq で並べ、既に持っている分はハッシュを比べる）
    const fresh: ChainRecord[] = [];
    for (const record of newRecords) {
      const index = record.seq;
      if (index < 0) {
        this.effects.event({ type: "chain_invalid", reason: "seq" });
        return;
      }
      if (index < this.records.length) {
        if ((await recordHash(record)) !== (await recordHash(this.records[index]!))) {
          this.effects.event({ type: "chain_forked" });
          return;
        }
      } else if (index === this.records.length + fresh.length) {
        fresh.push(record);
      } else {
        this.effects.event({ type: "chain_invalid", reason: "gap" });
        return;
      }
    }
    if (fresh.length === 0) {
      await this.flush();
      return;
    }
    const candidate = [...this.records, ...fresh];
    const join = this.expectedJoin;
    let trusted: Head | null = null;
    if (this.chain) trusted = { seq: this.chain.headSeq, hash: this.chain.headHash };
    else if (join) trusted = { seq: join.info.headSeq, hash: join.info.headHash };
    const result = await verifyChain(candidate, trusted);
    switch (result.kind) {
      case "ok": {
        const verified = result.chain;
        const joining = this.chain === null;
        if (joining) {
          if (join) {
            // 加入: 家の ID が Confirm と同じで、頭の後に inviter が自分を加えた記録があること
            if (verified.householdId !== join.info.householdId) {
              this.effects.event({ type: "chain_forked" });
              return;
            }
            const existing = verified.members.get(this.signer.deviceId);
            const addedByInviter = candidate
              .slice(join.info.headSeq + 1)
              .some((r) => r.type === "add" && r.target?.deviceId === this.signer.deviceId && r.signerId === join.inviter);
            const alreadyMember =
              existing !== undefined &&
              existing.addedSeq <= join.info.headSeq &&
              equalBytes(serializePublicKey(existing.kx), serializePublicKey(this.opener.kxPub));
            // まだ加えられていない（A の追記の前に取った）: 受け入れずに次の chain_changed を待つ
            if (!(addedByInviter || alreadyMember)) return;
            this.expectedJoin = null;
          } else if (this.creatingHousehold && candidate[0]!.target?.deviceId === this.signer.deviceId && candidate[0]!.type === "genesis") {
            this.creatingHousehold = false;
          } else {
            // 加入も家を作るのも待っていない: 中継が勝手に作った家を信じない（D-12）
            this.effects.event({ type: "chain_invalid", reason: "unexpected_household" });
            return;
          }
        }
        const headChanged = verified.headHash !== this.chain?.headHash;
        this.records = candidate;
        this.chain = verified;
        this.persist();
        // 書き手は、外した端末を宛先から外すために、次の書き込みを待たずに書き直す（D-2）
        if (headChanged && this.isWriter) this.markDirty();
        if (joining) this.effects.event({ type: "joined" });
        const pending = this.deferred;
        this.deferred = [];
        for (const state of pending) await this.onState(state);
        await this.maybeResume();
        await this.flush();
        return;
      }
      case "fork":
        this.effects.event({ type: "chain_forked" });
        return;
      case "rollback":
        this.effects.event({ type: "chain_rolled_back" });
        return;
      case "invalid":
        this.effects.event({ type: "chain_invalid", reason: result.reason });
        return;
    }
  }

  private async onState(stored: StoredState): Promise<void> {
    if (this.pendingStoreId !== null && stored.storeId === this.pendingStoreId) return;
    if (stored.storeId !== this.storeId) return;
    const current = this.chain;
    if (current === null) {
      this.deferred.push(stored);
      return;
    }
    // 開く前に、開いても捨てるものを除く（鍵交換の回数を減らす）
    if (await this.alreadyHave(stored)) return;
    const opened = await openDoc(stored.doc, current, this.opener);
    if (opened.kind === "need_chain") {
      this.deferred.push(stored);
      this.send({ op: "chain_get", fromSeq: this.records.length });
      return;
    }
    if (opened.kind === "fork") {
      this.effects.event({ type: "chain_forked" });
      return;
    }
    if (opened.kind === "rejected") return;
    const header = opened.header;
    if (header.version !== stored.version || header.kind !== stored.kind) return;
    const hash = await docHash(stored.doc);
    if (stored.kind === "cursor") {
      if (stored.version < this.cursorFloor) return;
      const existing = this.latestCursor;
      if (existing && existing.stored.version === stored.version) {
        if (existing.hash !== hash && writerOf(existing) === stored.doc.header.writerId) this.effects.event({ type: "state_equivocation" });
        return;
      }
      if (existing && existing.stored.version > stored.version) return;
      const body = decodeWith(new TextDecoder().decode(opened.plaintext), readCursor);
      if (body === null) return;
      const cursor: Opened<CursorBody> = { stored, body, hash, receivedAtLocalMs: this.nowMs() };
      this.latestCursor = cursor;
      this.cursorFloor = stored.version;
      this.persistFloor();
      await this.onCursor(cursor);
    } else {
      if (stored.version < this.queueFloor) return;
      if ((this.latestQueue?.stored.version ?? -1) >= stored.version) return;
      const body = decodeWith(new TextDecoder().decode(opened.plaintext), readQueue);
      if (body === null) return;
      this.latestQueue = { stored, body, hash, receivedAtLocalMs: this.nowMs() };
      this.queueFloor = stored.version;
      this.persistFloor();
    }
  }

  /** 開いても捨てるだけの状態か（同じ版で中身が違うものは、署名を確かめて食い違いを知らせるために開く）。 */
  private async alreadyHave(stored: StoredState): Promise<boolean> {
    if (stored.kind === "cursor") {
      if (stored.version < this.cursorFloor) return true;
      const existing = this.latestCursor;
      if (!existing) return false;
      return existing.stored.version > stored.version || (existing.stored.version === stored.version && existing.hash === (await docHash(stored.doc)));
    }
    return stored.version < this.queueFloor || (this.latestQueue?.stored.version ?? -1) >= stored.version;
  }

  private async onCursor(cursor: Opened<CursorBody>): Promise<void> {
    const writer = writerOf(cursor);
    if (writer === this.signer.deviceId) return;
    const accountMatches = cursor.body.accountTag === this.effects.accountTag();
    this.effects.event({ type: "remote_changed", writer, cursor: cursor.body, accountMatches });
    const waiting = this.transfer;
    if (waiting && !waiting.adopting && writer === waiting.previousWriter && !cursor.body.playing) {
      // 前の再生中の端末が最後のカーソル（一時停止）を置いた → すぐ取り込む
      await this.completeTransfer();
      return;
    }
    if (!accountMatches || !cursor.body.playing) return;
    await this.decide({
      kind: "playing_cursor",
      from: writer,
      sessionBase: cursor.body.sessionBase,
      sessionStartVersion: cursor.body.sessionStartVersion,
    });
  }

  private async onYield(claimId: string, yieldDoc: SealedDoc): Promise<void> {
    const current = this.chain;
    if (!current) return;
    const opened = await openDoc(yieldDoc, current, this.opener);
    if (opened.kind !== "ok") return;
    const command = decodeWith(new TextDecoder().decode(opened.plaintext), readCommand);
    if (command?.type !== "yield") return;
    if (command.to !== this.signer.deviceId || command.from !== opened.header.writerId || command.claimId !== claimId) return;
    // yield を出せるのは player だけ
    if (current.members.get(command.from)?.keys.role !== "player") return;
    if (this.handledClaims.includes(command.claimId)) return;
    this.remember(command.claimId);
    // 別のアカウントの端末の再生では止まらない（L-11）
    if (command.accountTag !== this.effects.accountTag()) return;
    const wasWriter = this.isWriter;
    const stopped = await this.decide({ kind: "yield", from: command.from, sessionBase: command.sessionBase });
    // 引き取り（transfer）なら、最後のカーソルを置く（相手はそれを待って取り込む）
    if (stopped && wasWriter && this.pendingOwner === command.from) await this.sendYielded(command.claimId);
  }

  private async onActiveChanged(message: Extract<RelayMessage, { op: "active_changed" }>): Promise<void> {
    this.serverActive = message.activeDeviceId;
    this.pendingOwner = message.pendingOwner;
    const claimer = message.pendingOwner ?? message.activeDeviceId;
    if (message.claimId !== null && claimer !== null) {
      this.claimOrders.set(claimer, message.claimOrder);
      if (this.session && this.session.claimId === message.claimId) this.session.claimOrder = message.claimOrder;
    }
    // 順番が分かったので、保留していた証拠で決め直す
    if (this.deferredEvidence.length > 0) {
      const pending = this.deferredEvidence;
      this.deferredEvidence = [];
      for (const evidence of pending) {
        if (await this.decide(evidence)) break;
      }
    }
    // 自分が再生中になったら、断られていた書き込みを送る
    if (message.activeDeviceId === this.signer.deviceId || message.pendingOwner === this.signer.deviceId) await this.flush();
  }

  private async onPendingExpired(claimId: string): Promise<void> {
    const waiting = this.transfer;
    if (!waiting || waiting.claimId !== claimId) return;
    this.transfer = null;
    if (waiting.adopting && this.isWriter) {
      // 取り込んで鳴っているのに、最初のカーソルが期限に間に合わなかった: 新しく始めたものとして claim し直す
      await this.restartSession();
    } else {
      this.effects.event({ type: "transfer_failed", reason: "expired" });
    }
  }

  /** 書き手でなくなったか。 */
  private async decide(evidence: Evidence): Promise<boolean> {
    const mine = this.session;
    if (!mine) return false;
    const result = arbiterDecide(this.signer.deviceId, mine.arbiter, evidence, mine.claimOrder, this.claimOrders.get(evidence.from) ?? null);
    if (result.decision === "keep") return false;
    if (result.decision === "defer") {
      this.deferredEvidence = this.deferredEvidence.filter((e) => e.from !== evidence.from);
      this.deferredEvidence.push(evidence);
      return false;
    }
    const wasPlaying = this.local?.playing === true;
    this.endSession();
    // 止めた時点の位置を取る（引き取られるときの最後のカーソルを、最後の出来事の位置にしない）
    const paused = this.effects.pause();
    if (paused) {
      this.local = paused;
    } else if (this.local) {
      this.local = { ...this.local, positionMs: this.currentPosition(this.local), playing: false, capturedAtMs: this.nowMs() };
    } else {
      this.local = null;
    }
    // 一時停止中なら鳴らしていないので、書き手でなくなるだけ（知らせない）
    if (wasPlaying) this.effects.event({ type: "stopped", by: evidence.from });
    return true;
  }

  private endSession(): void {
    this.session = null;
    this.retreated = true;
    this.deferredEvidence = [];
    this.dirtyCursor = false;
    this.dirtyQueue = false;
    this.persist();
  }

  private async sendYielded(claimId: string): Promise<void> {
    const current = this.chain;
    if (!current) return;
    const body = this.cursorBodyFor(null);
    if (!body) return;
    const expect = Math.max(this.latestCursor?.stored.version ?? 0, this.cursorFloor);
    const doc = await sealDoc({
      signer: this.signer,
      chain: current,
      kind: "cursor",
      version: expect + 1,
      prevHash: this.lastOwnCursorHash,
      issuedAtEpochMs: this.epochMs(),
      plaintext: utf8(serialize(cursorToJson(body))),
    });
    this.send({ op: "yielded", claimId, expectVersion: expect, doc });
  }

  private async onStateOk(requestId: string, kind: StateKind, version: number): Promise<void> {
    const sent = this.inflight.get(requestId);
    if (!sent) return;
    this.inflight.delete(requestId);
    const stored: StoredState = {
      kind: sent.kind,
      version,
      doc: sent.doc,
      storeId: this.storeId ?? "",
      receivedAtServerMs: 0,
      serverNowMs: 0,
      activeDeviceId: this.serverActive,
    };
    if (kind === "cursor") {
      if (sent.cursor) this.latestCursor = { stored, body: sent.cursor, hash: sent.hash, receivedAtLocalMs: this.nowMs() };
      this.cursorFloor = Math.max(this.cursorFloor, version);
      const mine = this.session;
      if (mine && mine.id === sent.sessionId && mine.arbiter.startVersion === null) mine.arbiter.startVersion = version;
      const adopted = this.transfer;
      if (adopted?.adopting) {
        this.transfer = null;
        this.effects.event({ type: "transfer_completed", from: adopted.previousWriter });
      }
    } else {
      if (sent.queue) this.latestQueue = { stored, body: sent.queue, hash: sent.hash, receivedAtLocalMs: this.nowMs() };
      this.queueFloor = Math.max(this.queueFloor, version);
    }
    this.persist();
    await this.flush();
    await this.maybeHandOff();
  }

  private async onStateConflict(requestId: string, kind: StateKind, latest: StoredState | null): Promise<void> {
    const waiting = this.transfer;
    if (waiting && requestId === waiting.claimId) {
      if (latest) await this.onState(latest);
      this.transfer = null;
      if (!waiting.retried) await this.requestTransferInner(true);
      else this.effects.event({ type: "transfer_failed", reason: "conflict" });
      return;
    }
    const sent = this.inflight.get(requestId);
    if (!sent) return;
    // 返事が落ちて送り直した自分の書き込みと同じものがサーバーにあるなら、成功とみなす
    if (latest && latest.doc.header.writerId === this.signer.deviceId && (await docHash(latest.doc)) === sent.hash) {
      await this.onStateOk(requestId, sent.kind, latest.version);
      return;
    }
    this.inflight.delete(requestId);
    if (latest) await this.onState(latest);
    // 止まっていなければ、最新の版に積み直す
    if (this.isWriter) this.markUnsent(sent.kind);
    void kind;
    await this.flush();
  }

  private async onError(code: string, requestId: string | null): Promise<void> {
    let sent: Inflight | undefined;
    if (requestId !== null) {
      sent = this.inflight.get(requestId);
      if (sent) this.inflight.delete(requestId);
    }
    switch (code) {
      // 書き込みを断られた: 止まる理由が無く、自分の claim が一番後（または誰も再生中でない）なら、すぐ claim し直す
      case "not_active": {
        if (sent && this.isWriter) {
          this.markUnsent(sent.kind);
          let stopped = false;
          const latest = this.latestCursor;
          if (latest && writerOf(latest) !== this.signer.deviceId && latest.body.playing && latest.body.accountTag === this.effects.accountTag()) {
            stopped = await this.decide({
              kind: "playing_cursor",
              from: writerOf(latest),
              sessionBase: latest.body.sessionBase,
              sessionStartVersion: latest.body.sessionStartVersion,
            });
          }
          const mine = this.session;
          const others = [...this.claimOrders.entries()].filter(([id]) => id !== this.signer.deviceId).map(([, v]) => v);
          const latestOther = others.length > 0 ? Math.max(...others) : null;
          let mineIsLatest = false;
          if (mine?.claimOrder != null) mineIsLatest = latestOther === null || mine.claimOrder > latestOther;
          if (!stopped && this.isWriter && (this.serverActive === null || mineIsLatest) && mine?.claimOrder != null) await this.restartSession();
        }
        break;
      }
      case "stale_head":
        if (sent && this.isWriter) this.markUnsent(sent.kind);
        this.send({ op: "chain_get", fromSeq: this.records.length });
        break;
      default:
        if (requestId !== null && requestId === this.transfer?.claimId) this.failTransfer(code);
        else this.effects.event({ type: "server_error", code });
    }
  }

  // --- 手元の操作 ---

  /** 手元のキューが変わった（HandoffCodec.encode の文字列）。書き手なら置く。 */
  async setLocalQueue(snapshot: string): Promise<void> {
    if (snapshot === this.localQueue) return;
    this.localQueue = snapshot;
    if (this.isWriter) {
      this.dirtyQueue = true;
      // キューを置き直すので、カーソルも新しいキューを指し直す
      this.dirtyCursor = true;
      await this.flush();
    }
  }

  /** 手元の再生の位置・再生中か等が変わった。書き手なら置く（間引きは呼ぶ側）。 */
  async setLocalCursor(cursor: LocalCursor): Promise<void> {
    this.local = cursor;
    if (this.isWriter) {
      this.dirtyCursor = true;
      await this.flush();
    }
  }

  /** 本人が手元で新しく再生を始めた・再開した（L-3）。新しいセッションとして claim し、キューとカーソルを置く。 */
  async userStartedPlayback(): Promise<boolean> {
    if (this.role !== "player" || this.chain === null || this.storeId === null || this.pendingStoreId !== null) return false;
    const tag = this.effects.accountTag();
    if (tag === null) {
      this.startWaitingForTag = true;
      return false;
    }
    // 家の最新の再生が別のアカウントなら、claim しない
    const latest = this.latestCursor;
    if (latest && writerOf(latest) !== this.signer.deviceId && latest.body.accountTag !== tag) return false;
    this.retreated = false;
    await this.restartSession();
    return true;
  }

  private async restartSession(): Promise<void> {
    this.sessionCounter++;
    const next: Session = {
      id: this.sessionCounter,
      arbiter: { base: Math.max(this.latestCursor?.stored.version ?? 0, this.cursorFloor), startVersion: null },
      claimId: null,
      claimOrder: null,
    };
    this.session = next;
    this.deferredEvidence = [];
    // 前のセッションの書き込みの返事は待たない（新しいセッションで積み直す）
    this.inflight.clear();
    this.persist();
    await this.sendClaimFor(next);
    this.markDirty();
    await this.flush();
  }

  private async sendClaimFor(target: Session): Promise<void> {
    const current = this.chain;
    if (!current) return;
    const tag = this.effects.accountTag();
    if (tag === null) return;
    const claimId = this.newId();
    this.remember(claimId);
    target.claimId = claimId;
    target.claimOrder = null;
    const w = this.latestCursor ? writerOf(this.latestCursor) : null;
    const to = w !== null && w !== this.signer.deviceId ? w : "";
    const yieldDoc = await this.sealCommand(current, { type: "yield", claimId, from: this.signer.deviceId, to, sessionBase: target.arbiter.base, accountTag: tag });
    this.send({ op: "claim", claimId, yieldDoc });
  }

  /** つながり直した後: 最新の状態と鎖がそろっていて、まだ書き手なら、claim し直して積み直す。 */
  private async maybeResume(): Promise<void> {
    if (!this.resumePending || this.deferred.length > 0 || this.chain === null) return;
    this.resumePending = false;
    const mine = this.session;
    if (!mine) return;
    if (this.serverActive !== this.signer.deviceId) await this.sendClaimFor(mine);
    this.markDirty();
    await this.flush();
  }

  private markUnsent(kind: StateKind): void {
    if (kind === "cursor") this.dirtyCursor = true;
    else this.dirtyQueue = true;
  }

  private markDirty(): void {
    this.dirtyCursor = this.local !== null;
    this.dirtyQueue = this.localQueue !== null;
  }

  /** 「ここで再生」（D-7）。最新のカーソルとキューを開けていて、アカウントが一致するときだけ送る。 */
  async requestTransfer(): Promise<boolean> {
    return this.requestTransferInner(false);
  }

  /** 家の端末 target に「ここで再生（引き取り）」を頼む。 */
  async requestRemoteTransfer(target: string): Promise<boolean> {
    if (this.pendingStoreId !== null || target === this.signer.deviceId) return false;
    const current = this.chain;
    if (!current || current.members.get(target)?.keys.role !== "player") return false;
    const tag = this.effects.accountTag();
    const cursor = this.latestCursor;
    if (tag === null || !cursor || cursor.body.accountTag !== tag) return false;
    const command: ControlCommand = {
      type: "control",
      commandId: this.newId(),
      from: this.signer.deviceId,
      to: target,
      action: "transfer",
      positionMs: null,
      basedOnVersion: cursor.stored.version,
      accountTag: tag,
      onItemId: null,
      volumePercent: null,
      shuffle: null,
      repeat: null,
    };
    return this.send({ op: "control", doc: await this.sealCommand(current, command) });
  }

  /** 手元のキューで家の再生を引き受け（音は出さない）、それを書き終えたら target に引き取ってもらう。 */
  async playOn(target: string): Promise<boolean> {
    if (target === this.signer.deviceId) return false;
    if (!(await this.userStartedPlayback())) return false;
    this.handOff = { target, startedAtMs: this.nowMs() };
    return true;
  }

  private async maybeHandOff(): Promise<void> {
    const waiting = this.handOff;
    if (!waiting || !this.session) return;
    const cursor = this.latestCursor;
    const queue = this.latestQueue;
    if (!cursor || !queue) return;
    if (writerOf(cursor) !== this.signer.deviceId || writerOf(queue) !== this.signer.deviceId || cursor.body.queueRef !== queue.hash) return;
    this.handOff = null;
    await this.requestRemoteTransfer(waiting.target);
  }

  /** この端末の表示名を家のほかの端末に知らせる。名前は 64 文字（UTF-16 の符号単位）まで。 */
  async sendProfile(name: string, kind: string | null): Promise<boolean> {
    const current = this.chain;
    if (!current || !current.members.has(this.signer.deviceId)) return false;
    const short = name.length > 64 ? name.slice(0, 64) : name;
    return this.send({ op: "control", doc: await this.sealCommand(current, { type: "profile", from: this.signer.deviceId, name: short, kind }) });
  }

  /** 送る相手（今鳴らしている同じアカウントの端末）。送れなければ null。 */
  private remoteTarget(): { current: VerifiedChain; tag: string; cursor: Opened<CursorBody> } | null {
    if (this.isWriter || this.pendingStoreId !== null) return null;
    const current = this.chain;
    const tag = this.effects.accountTag();
    const cursor = this.latestCursor;
    if (!current || tag === null || !cursor || writerOf(cursor) === this.signer.deviceId || cursor.body.accountTag !== tag) return null;
    return { current, tag, cursor };
  }

  private controlOf(
    t: { tag: string; cursor: Opened<CursorBody> },
    action: ControlAction,
    extra: Partial<Pick<ControlCommand, "positionMs" | "onItemId" | "volumePercent" | "shuffle" | "repeat">>,
  ): ControlCommand {
    return {
      type: "control",
      commandId: this.newId(),
      from: this.signer.deviceId,
      to: writerOf(t.cursor),
      action,
      positionMs: extra.positionMs ?? null,
      basedOnVersion: t.cursor.stored.version,
      accountTag: t.tag,
      onItemId: extra.onItemId ?? null,
      volumePercent: extra.volumePercent ?? null,
      shuffle: extra.shuffle ?? null,
      repeat: extra.repeat ?? null,
    };
  }

  /** 遠隔操作を送る（鳴っていない端末から、今鳴らしている同じアカウントの端末へ）。 */
  async sendControl(action: ControlAction, positionMs: number | null = null): Promise<boolean> {
    const t = this.remoteTarget();
    if (!t) return false;
    const onItemId = action === "next" || action === "previous" || action === "seek" ? t.cursor.body.currentItemId : null;
    const command = this.controlOf(t, action, { positionMs: positionMs === null ? null : Math.max(0, positionMs), onItemId });
    return this.send({ op: "control", doc: await this.sealCommand(t.current, command) });
  }

  /** 遠隔の音量を送る（0〜100）。 */
  async sendVolume(volumePercent: number): Promise<boolean> {
    const t = this.remoteTarget();
    if (!t) return false;
    const command = this.controlOf(t, "volume", { volumePercent: Math.min(Math.max(Math.round(volumePercent), 0), 100) });
    return this.send({ op: "control", doc: await this.sealCommand(t.current, command) });
  }

  /** 遠隔のシャッフル・繰り返しを送る。相手のカーソルに shuffle が無い前の版の端末なら false。 */
  async sendShuffle(on: boolean): Promise<boolean> {
    const t = this.remoteTarget();
    if (!t || t.cursor.body.shuffle === null) return false;
    return this.send({ op: "control", doc: await this.sealCommand(t.current, this.controlOf(t, "shuffle", { shuffle: on })) });
  }

  async sendRepeat(mode: HandoffRepeat): Promise<boolean> {
    const t = this.remoteTarget();
    if (!t || t.cursor.body.shuffle === null) return false;
    return this.send({ op: "control", doc: await this.sealCommand(t.current, this.controlOf(t, "repeat", { repeat: mode })) });
  }

  /** 遠隔のキューへの追加を送る。1件の平文が詰め物の1ブロックに収まるよう分けて送る。 */
  async sendQueueAdd(items: HandoffItem[], next: boolean): Promise<boolean> {
    if (items.length === 0) return false;
    const t = this.remoteTarget();
    if (!t) return false;
    const placeholder = "00000000-0000-0000-0000-000000000000";
    const size = (list: HandoffItem[]): number =>
      utf8(
        serialize(
          commandToJson({
            type: "queue_add",
            commandId: placeholder,
            from: this.signer.deviceId,
            to: writerOf(t.cursor),
            items: list,
            next,
            afterItemId: placeholder,
            basedOnVersion: t.cursor.stored.version,
            accountTag: t.tag,
          }),
        ),
      ).length;
    const chunks: HandoffItem[][] = [];
    let chunk: HandoffItem[] = [];
    for (const item of items) {
      if (size([item]) > MAX_QUEUE_ADD_PLAINTEXT) return false;
      if (chunk.length > 0 && size([...chunk, item]) > MAX_QUEUE_ADD_PLAINTEXT) {
        chunks.push(chunk);
        chunk = [];
      }
      chunk.push(item);
    }
    chunks.push(chunk);
    let after: string | null = null;
    for (const part of chunks) {
      const command: QueueAddCommand = {
        type: "queue_add",
        commandId: this.newId(),
        from: this.signer.deviceId,
        to: writerOf(t.cursor),
        items: part,
        next,
        afterItemId: after,
        basedOnVersion: t.cursor.stored.version,
        accountTag: t.tag,
      };
      if (!this.send({ op: "control", doc: await this.sealCommand(t.current, command) })) return false;
      after = part[part.length - 1]?.itemId ?? null;
    }
    return true;
  }

  async sendQueueMove(itemId: string, afterItemId: string | null): Promise<boolean> {
    return this.sendQueueEdit(QUEUE_EDIT_MOVE, [itemId], afterItemId);
  }

  async sendQueueRemove(itemIds: string[]): Promise<boolean> {
    return this.sendQueueEdit(QUEUE_EDIT_REMOVE, itemIds, null);
  }

  /** 遠隔のキューの「次に流れる曲」の消去を送る。currentItemId は画面に出ている今の曲。 */
  async sendQueueClearFlow(currentItemId: string): Promise<boolean> {
    return this.sendQueueEdit(QUEUE_EDIT_CLEAR_FLOW, [currentItemId], null);
  }

  private async sendQueueEdit(op: string, itemIds: string[], afterItemId: string | null): Promise<boolean> {
    if (itemIds.length === 0) return false;
    const t = this.remoteTarget();
    if (!t) return false;
    const command: QueueEditCommand = {
      type: "queue_edit",
      commandId: this.newId(),
      from: this.signer.deviceId,
      to: writerOf(t.cursor),
      op,
      itemIds,
      afterItemId,
      basedOnVersion: t.cursor.stored.version,
      accountTag: t.tag,
    };
    if (utf8(serialize(commandToJson(command))).length > MAX_QUEUE_ADD_PLAINTEXT) return false;
    return this.send({ op: "control", doc: await this.sealCommand(t.current, command) });
  }

  /** 表示用: ほかの端末の最新のカーソル（別アカウントのものを含む）と、今の時刻に合わせた位置。 */
  remoteNowPosition(): RemoteNow | null {
    const cursor = this.latestCursor;
    if (!cursor || writerOf(cursor) === this.signer.deviceId) return null;
    return { writer: writerOf(cursor), cursor: cursor.body, positionMs: this.extrapolatedPosition(cursor), version: cursor.stored.version };
  }

  /** 受けた命令が、自分宛て・送り主が署名した player の端末・新しい ID・同じアカウントかを確かめる（共通の前半）。 */
  private acceptCommand(
    command: { to: string; from: string; commandId: string; accountTag: string },
    header: { writerId: string; issuedAtEpochMs: number },
    current: VerifiedChain,
  ): boolean {
    if (command.to !== this.signer.deviceId || command.from !== header.writerId || command.from === this.signer.deviceId) return false;
    // 出してから5分を超えた命令には従わない（中継が取っておいて後から届けるのを、時間でも縛る）
    if (Math.abs(this.epochMs() - header.issuedAtEpochMs) > MAX_CONTROL_AGE_MS) return false;
    // 遠隔操作を出せるのは player だけ（yield と同じ）
    if (current.members.get(command.from)?.keys.role !== "player") return false;
    if (this.handledClaims.includes(command.commandId)) return false;
    this.remember(command.commandId);
    return command.accountTag === this.effects.accountTag();
  }

  /** この端末が書き手で、送り手が見ていた版がこの端末の最新の書き込みに近いか。 */
  private basedOnOwn(basedOnVersion: number): boolean {
    const mine = this.session;
    const own = this.latestCursor;
    if (!mine || !own || writerOf(own) !== this.signer.deviceId) return false;
    if (basedOnVersion > own.stored.version || own.stored.version - basedOnVersion > MAX_CONTROL_LAG) return false;
    // 今のセッションより前の見え方に基づく命令には従わない
    return mine.arbiter.startVersion !== null && basedOnVersion >= mine.arbiter.startVersion;
  }

  /** 遠隔操作を受けた。 */
  private async onControl(doc: SealedDoc): Promise<void> {
    const current = this.chain;
    if (!current || doc.header.kind !== "command") return;
    // 中継が同じ命令を流し直しても、開く（鍵交換をする）前に捨てる
    const hash = await docHash(doc);
    if (this.seenControlDocs.includes(hash)) return;
    this.seenControlDocs.push(hash);
    while (this.seenControlDocs.length > MAX_HANDLED_CLAIMS) this.seenControlDocs.shift();
    const opened = await openDoc(doc, current, this.opener);
    if (opened.kind !== "ok") return;
    const header = opened.header;
    const body: CommandBody | null = decodeWith(new TextDecoder().decode(opened.plaintext), readCommand);
    if (body === null) return;
    switch (body.type) {
      case "profile":
        if (body.from === header.writerId && body.from !== this.signer.deviceId && current.members.has(body.from)) {
          this.effects.event({ type: "profile", deviceId: body.from, name: body.name, kind: body.kind });
        }
        return;
      case "queue_add":
        if (!this.acceptCommand(body, header, current) || body.items.length === 0) return;
        if (!this.basedOnOwn(body.basedOnVersion)) return;
        this.effects.queueAdd?.(body.items, body.next, body.afterItemId);
        return;
      case "queue_edit":
        if (!this.acceptCommand(body, header, current) || body.itemIds.length === 0) return;
        if (!this.basedOnOwn(body.basedOnVersion)) return;
        if (body.op === QUEUE_EDIT_MOVE) {
          if (body.itemIds.length === 1) this.effects.queueMove?.(body.itemIds[0]!, body.afterItemId);
        } else if (body.op === QUEUE_EDIT_REMOVE) {
          this.effects.queueRemove?.(body.itemIds);
        } else if (body.op === QUEUE_EDIT_CLEAR_FLOW) {
          if (body.itemIds.length === 1) this.effects.queueClearFlow?.(body.itemIds[0]!);
        }
        return;
      case "control":
        break;
      default:
        // login_grant など、この端末が扱わない命令は捨てる（ログインの値はどこにも置かない）
        return;
    }
    const command = body;
    if (!this.acceptCommand(command, header, current)) return;
    if (command.action === "transfer") {
      // 「ここで再生」を頼まれた: 書き手でなく、送り手が見ていた家の最新のカーソルが自分の見ているものに近いときだけ
      const latest = this.latestCursor;
      if (this.isWriter || !latest) return;
      if (command.basedOnVersion > latest.stored.version || latest.stored.version - command.basedOnVersion > MAX_CONTROL_LAG) return;
      if (this.effects.control("transfer", null)) await this.requestTransfer();
      return;
    }
    if (!this.basedOnOwn(command.basedOnVersion)) return;
    if (command.action === "seek" && command.positionMs === null) return;
    if (command.action === "volume") {
      if (command.volumePercent !== null && command.volumePercent >= 0 && command.volumePercent <= 100) this.effects.volume?.(command.volumePercent);
      return;
    }
    if (command.action === "shuffle") {
      if (command.shuffle !== null) this.effects.shuffle?.(command.shuffle);
      return;
    }
    if (command.action === "repeat") {
      if (command.repeat !== null) this.effects.setRepeat?.(command.repeat);
      return;
    }
    // 送り手が見ていた曲から、もう次の曲に変わっていたら従わない（遅れて届いた「次へ」で2曲飛ばさない）
    const own = this.latestCursor!;
    if (command.onItemId !== null && command.onItemId !== (this.local?.currentItemId ?? own.body.currentItemId)) return;
    this.effects.controlAccepted?.(command.action, command.from);
    this.effects.control(command.action, command.positionMs);
  }

  private async requestTransferInner(retried: boolean): Promise<boolean> {
    if (this.role !== "player" || this.transfer !== null || this.pendingStoreId !== null) return false;
    const current = this.chain;
    const tag = this.effects.accountTag();
    const cursor = this.latestCursor;
    const queue = this.latestQueue;
    if (!current || tag === null || !cursor || !queue) return false;
    if (writerOf(cursor) === this.signer.deviceId || cursor.body.accountTag !== tag || queue.body.accountTag !== tag || cursor.body.queueRef !== queue.hash) {
      return false;
    }
    const claimId = this.newId();
    this.remember(claimId);
    this.transfer = { claimId, previousWriter: writerOf(cursor), startedAtMs: this.nowMs(), retried, adopting: false };
    let doc: SealedDoc;
    try {
      doc = await this.sealCommand(current, {
        type: "yield",
        claimId,
        from: this.signer.deviceId,
        to: writerOf(cursor),
        sessionBase: cursor.stored.version,
        accountTag: tag,
      });
    } catch (e) {
      this.transfer = null;
      throw e;
    }
    if (!this.send({ op: "transfer", claimId, basedOnVersion: cursor.stored.version, yieldDoc: doc })) {
      this.transfer = null;
      return false;
    }
    return true;
  }

  /** 時間の経過。transfer で前の端末から返事が無ければ、最後の状態から取り込む。 */
  async tick(): Promise<void> {
    if (this.floorDirty && (this.lastPersistMs === null || this.nowMs() - this.lastPersistMs >= FLOOR_PERSIST_MS)) this.persist();
    // 引き受けたものを書けないまま10秒たったら、頼むのをやめる
    if (this.handOff && this.nowMs() - this.handOff.startedAtMs > HAND_OFF_WAIT_MS) this.handOff = null;
    const waiting = this.transfer;
    if (!waiting) return;
    if (!waiting.adopting && this.nowMs() - waiting.startedAtMs >= TRANSFER_WAIT_MS) await this.completeTransfer();
  }

  private async completeTransfer(): Promise<void> {
    const waiting = this.transfer;
    if (!waiting) return;
    const cursor = this.latestCursor;
    const queue = this.latestQueue;
    if (!cursor || !queue || cursor.body.queueRef !== queue.hash) {
      this.failTransfer("no_state", waiting.claimId);
      return;
    }
    const position = this.extrapolatedPosition(cursor);
    waiting.adopting = true;
    if (!this.effects.adopt(queue.body.snapshot, cursor.body, position)) {
      this.failTransfer("not_adopted", waiting.claimId);
      return;
    }
    this.localQueue = queue.body.snapshot;
    this.localQueueRef = queue.hash;
    this.sessionCounter++;
    const next: Session = {
      id: this.sessionCounter,
      arbiter: { base: cursor.stored.version, startVersion: null },
      claimId: waiting.claimId,
      claimOrder: this.claimOrders.get(this.signer.deviceId) ?? null,
    };
    this.session = next;
    this.deferredEvidence = [];
    this.retreated = false;
    this.local = {
      currentItemId: cursor.body.currentItemId,
      positionMs: position,
      playing: true,
      repeat: cursor.body.repeat,
      listenedMs: cursor.body.listenedMs,
      historySent: cursor.body.historySent,
      capturedAtMs: this.nowMs(),
      volumePercent: null,
      shuffle: false,
    };
    this.persist();
    // B の最初のカーソルで再生中が確定する（D-7）。キューは前の端末の doc をそのまま指す
    this.dirtyCursor = true;
    await this.flush();
  }

  /** 引き取りをやめる（取り下げるときは中継に release を送る）。 */
  private failTransfer(reason: string, release?: string): void {
    this.transfer = null;
    if (release !== undefined) this.send({ op: "release", claimId: release });
    this.effects.event({ type: "transfer_failed", reason });
  }

  /** 経過はサーバーの時計で測り、手元で受けてからの時間を足す。9分を超える経過は一時停止として扱う（D-5）。 */
  private extrapolatedPosition(cursor: Opened<CursorBody>): number {
    if (!cursor.body.playing) return cursor.body.positionMs;
    const elapsed = cursor.stored.serverNowMs - cursor.stored.receivedAtServerMs + (this.nowMs() - cursor.receivedAtLocalMs);
    if (elapsed < 0 || elapsed > MAX_EXTRAPOLATION_MS) return cursor.body.positionMs;
    return cursor.body.positionMs + elapsed;
  }

  // --- 書き込み ---

  private async flush(): Promise<void> {
    const current = this.chain;
    const mine = this.session;
    if (!current || !mine || this.resumePending) return;
    const inflight = [...this.inflight.values()];
    if (this.dirtyQueue && !inflight.some((i) => i.kind === "queue")) {
      const snapshot = this.localQueue;
      const tag = this.effects.accountTag();
      if (snapshot !== null && tag !== null) {
        const body: QueueBody = { accountTag: tag, snapshot };
        const expect = Math.max(this.latestQueue?.stored.version ?? 0, this.queueFloor);
        const doc = await sealDoc({
          signer: this.signer,
          chain: current,
          kind: "queue",
          version: expect + 1,
          prevHash: null,
          issuedAtEpochMs: this.epochMs(),
          plaintext: utf8(serialize(queueToJson(body))),
        });
        const requestId = this.newId();
        const hash = await docHash(doc);
        if (this.send({ op: "state_put", requestId, kind: "queue", expectVersion: expect, doc })) {
          this.dirtyQueue = false;
          this.localQueueRef = hash;
          this.inflight.set(requestId, { kind: "queue", doc, hash, cursor: null, queue: body, sessionId: mine.id });
        }
      }
    }
    // カーソルは、指すキューが置けてから書く（キューの送信中は待つ）
    if (this.dirtyCursor && ![...this.inflight.values()].some((i) => i.kind === "cursor" || i.kind === "queue") && !this.dirtyQueue) {
      const body = this.cursorBodyFor(mine);
      if (!body) return;
      const expect = Math.max(this.latestCursor?.stored.version ?? 0, this.cursorFloor);
      const doc = await sealDoc({
        signer: this.signer,
        chain: current,
        kind: "cursor",
        version: expect + 1,
        prevHash: this.lastOwnCursorHash,
        issuedAtEpochMs: this.epochMs(),
        plaintext: utf8(serialize(cursorToJson(body))),
      });
      const hash = await docHash(doc);
      const requestId = this.newId();
      if (this.send({ op: "state_put", requestId, kind: "cursor", expectVersion: expect, doc })) {
        this.dirtyCursor = false;
        this.lastOwnCursorHash = hash;
        this.inflight.set(requestId, { kind: "cursor", doc, hash, cursor: body, queue: null, sessionId: mine.id });
      }
    }
  }

  private cursorBodyFor(mine: Session | null, tagOverride?: string): CursorBody | null {
    const cursor = this.local;
    if (!cursor) return null;
    const tag = tagOverride ?? this.effects.accountTag();
    if (tag === null) return null;
    const queueRef = this.localQueueRef;
    if (queueRef === null) return null;
    this.deviceSeq++;
    return {
      queueRef,
      currentItemId: cursor.currentItemId,
      positionMs: this.currentPosition(cursor),
      playing: cursor.playing && mine !== null,
      repeat: cursor.repeat,
      sessionBase: mine?.arbiter.base ?? 0,
      sessionStartVersion: mine?.arbiter.startVersion ?? null,
      deviceSeq: this.deviceSeq,
      listenedMs: cursor.listenedMs,
      historySent: cursor.historySent,
      accountTag: tag,
      startedAtEpochMs: this.epochMs(),
      volumePercent: cursor.volumePercent === null ? null : Math.min(Math.max(Math.round(cursor.volumePercent), 0), 100),
      shuffle: cursor.shuffle,
    };
  }

  /** 再生中なら、位置を取ってからの経過を足す（書くのが遅れても位置を戻さない）。 */
  private currentPosition(cursor: LocalCursor): number {
    return cursor.playing ? cursor.positionMs + Math.min(Math.max(this.nowMs() - cursor.capturedAtMs, 0), MAX_EXTRAPOLATION_MS) : cursor.positionMs;
  }

  private sealCommand(current: VerifiedChain, command: CommandBody): Promise<SealedDoc> {
    return sealDoc({
      signer: this.signer,
      chain: current,
      kind: "command",
      version: 1,
      prevHash: null,
      issuedAtEpochMs: this.epochMs(),
      plaintext: utf8(serialize(commandToJson(command))),
    });
  }

  private remember(claimId: string): void {
    this.handledClaims.push(claimId);
    while (this.handledClaims.length > MAX_HANDLED_CLAIMS) this.handledClaims.shift();
  }

  private persistFloor(): void {
    if (this.lastPersistMs !== null && this.nowMs() - this.lastPersistMs < FLOOR_PERSIST_MS) {
      this.floorDirty = true;
      return;
    }
    this.persist();
  }

  private persist(): void {
    this.floorDirty = false;
    this.lastPersistMs = this.nowMs();
    this.effects.persist({
      chain: this.records,
      storeId: this.storeId,
      cursorFloor: this.cursorFloor,
      queueFloor: this.queueFloor,
      retreated: this.retreated,
      handledClaimIds: [...this.handledClaims],
      expectedJoin: this.expectedJoin?.info ?? null,
      expectedInviter: this.expectedJoin?.inviter ?? null,
      creatingHousehold: this.creatingHousehold,
    });
  }
}
