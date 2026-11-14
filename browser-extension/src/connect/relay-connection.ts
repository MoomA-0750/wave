// 中継 v2 への接続（Swift の RelayConnection・Android の RelayClientV2 と同じ規則）。使う側が参照を数え、0 になったら閉じる。切れたら間を空けてつなぎ直す。
// 受けたメッセージは JSON の文字列のまま deliver に渡す。ログは状態と種類だけ（鍵・トークンの値は出さない）。
// ブラウザの WebSocket は ping を送れないので、生きているかは中継が答えるメッセージで確かめる（RelayWatch）。

import { b64, lengthPrefixed, unb64, utf8, type Bytes } from "./bytes.ts";
import { AUTH_LABEL, MAX_FRAME_CHARS, encodeRelay } from "./relay-messages.ts";
import { PROBE, RelayWatch, WATCH_TICK_MS, reconnectDelayMs } from "./relay-watch.ts";

export type RelayStatus =
  | "disconnected"
  | "connecting"
  | "connected"
  /** 中継に届くが、この端末は家に入っていない（認証で断られた・外された）。 */
  | "not_member"
  | "failed";

const PAIR_TIMEOUT_MS = 120_000;
const MAX_PAIR_REPLY_CHARS = 64 * 1024;

export interface RelayConnectionOptions {
  /** 例: wss://<中継のホスト>:<ポート>（本人が設定する。リポジトリに書かない）。 */
  baseUrl: string;
  /** 署名の対象に入れるホスト名とポート（中継の WAVE_RELAY_HOST と同じ値）。 */
  relayHost: string;
  identityPub: Bytes;
  /** 身元鍵での、用途ラベル付きの署名（DER）。 */
  sign: (label: string, message: Uint8Array) => Promise<Bytes>;
  deliver: (text: string) => void;
  onConnectionLost: () => void;
  onStatus?: (status: RelayStatus) => void;
  log?: (message: string) => void;
  /** 試験で差し替える。 */
  webSocket?: typeof WebSocket;
  /** 単調時計（ミリ秒）。試験で差し替える。 */
  nowMs?: () => number;
}

/** 中継が送ってきた "challenge" に、設定のホストで署名して "auth" を返す（中継が名乗るホストは使わない）。 */
export async function authReply(nonceText: string, relayHost: string, identityPub: Uint8Array, sign: RelayConnectionOptions["sign"]): Promise<string | null> {
  const nonce = unb64(nonceText);
  if (nonce === null) return null;
  const signature = await sign(AUTH_LABEL, lengthPrefixed(utf8(relayHost), nonce));
  return encodeRelay({ op: "auth", publicKey: b64(identityPub), signature: b64(signature) });
}

export class RelayConnection {
  private readonly options: RelayConnectionOptions;
  private readonly baseUrl: string;
  private readonly WS: typeof WebSocket;
  private socket: WebSocket | null = null;
  private users = 0;
  private failedAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private statusValue: RelayStatus = "disconnected";
  /** 今のソケットの見張り（死んだソケットに気づく）。 */
  private readonly watch = new RelayWatch();
  private watchTimer: ReturnType<typeof setInterval> | null = null;
  private readonly now: () => number;

  constructor(options: RelayConnectionOptions) {
    this.options = options;
    this.baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.WS = options.webSocket ?? WebSocket;
    this.now = options.nowMs ?? (() => performance.now());
  }

  get status(): RelayStatus {
    return this.statusValue;
  }

  private log(message: string): void {
    this.options.log?.(message);
  }

  private setStatus(next: RelayStatus): void {
    if (this.statusValue === next) return;
    this.statusValue = next;
    this.options.onStatus?.(next);
  }

  acquire(): void {
    this.users++;
    if (this.users === 1 || (this.socket === null && this.statusValue !== "connecting")) this.open();
  }

  release(): void {
    this.users = Math.max(0, this.users - 1);
    if (this.users === 0) this.close();
  }

  get inUse(): boolean {
    return this.users > 0;
  }

  /** 断られた（家に入っていなかった）後に、つなぎ直す（加入の後）。 */
  reconnect(): void {
    if (this.users > 0 && this.socket === null) this.open();
  }

  /**
   * タブが前に出た・ネットにつながり直した: 待っているつなぎ直しをすぐ行い、つながっていれば生きているかをすぐ確かめる
   * （眠っていた間に死んだソケットや、伸びたつなぎ直しの待ちで、ほかの端末の再生が届かないままにしない）。
   */
  wake(): void {
    if (this.users === 0) return;
    this.failedAttempts = 0;
    const socket = this.socket;
    if (socket === null) {
      this.open();
      return;
    }
    if (this.statusValue === "connected" && this.watch.wake(this.now()) === "probe") this.sendRaw(socket, PROBE);
  }

  /** 見張り（1秒ごと）。返事の無いソケットは捨ててつなぎ直す。 */
  private checkLiveness(socket: WebSocket): void {
    if (this.socket !== socket) return;
    const action = this.watch.tick(this.now());
    if (action === "probe") {
      this.sendRaw(socket, PROBE);
    } else if (action === "dead") {
      this.log(`relay-v2 unresponsive status=${this.statusValue}`);
      try {
        socket.close(4000);
      } catch {
        // 閉じられなくてもよい
      }
      // 死んだソケットの onclose は遅れて来る（来ない）ので待たない
      this.onDisconnected(socket, "failed");
    }
  }

  private sendRaw(socket: WebSocket, text: string): void {
    try {
      if (socket.readyState === 1) socket.send(text);
    } catch {
      // 送れなければ次の見張りで切れる
    }
  }

  private stopWatch(): void {
    if (this.watchTimer !== null) clearInterval(this.watchTimer);
    this.watchTimer = null;
  }

  /** つないでいてもつなぎ直す。 */
  restart(): void {
    if (this.users === 0) return;
    this.close();
    this.open();
  }

  /** つながっていれば送る（呼んだ順に送る）。送れなければ false。 */
  send(json: string): boolean {
    const socket = this.socket;
    if (this.statusValue !== "connected" || socket === null || socket.readyState !== 1) return false;
    try {
      socket.send(json);
      return true;
    } catch {
      return false;
    }
  }

  /** 家に入っていない端末から、加入の第1手を送って返事を待つ（/v2/pair、認証なし）。届かなければ null。 */
  pair(pairHelloInJson: string, timeoutMs = PAIR_TIMEOUT_MS): Promise<string | null> {
    return new Promise((resolve) => {
      let done = false;
      let socket: WebSocket;
      const finish = (value: string | null): void => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        try {
          socket.close(1000);
        } catch {
          // 閉じられなくてもよい
        }
        resolve(value);
      };
      const timer = setTimeout(() => finish(null), timeoutMs);
      try {
        socket = new this.WS(this.baseUrl + "/v2/pair");
      } catch {
        this.log("relay-v2 pair failed type=url");
        clearTimeout(timer);
        resolve(null);
        return;
      }
      socket.onopen = () => socket.send(pairHelloInJson);
      socket.onmessage = (event) => {
        const text = typeof event.data === "string" ? event.data : null;
        finish(text !== null && text.length <= MAX_PAIR_REPLY_CHARS ? text : null);
      };
      socket.onerror = () => {
        this.log("relay-v2 pair failed type=socket");
        finish(null);
      };
      socket.onclose = () => finish(null);
    });
  }

  private open(): void {
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.setStatus("connecting");
    let socket: WebSocket;
    try {
      socket = new this.WS(this.baseUrl + "/v2/connect");
    } catch {
      this.log("relay-v2 failure type=url");
      this.setStatus("failed");
      return;
    }
    this.socket = socket;
    this.watch.opened(this.now());
    this.stopWatch();
    this.watchTimer = setInterval(() => this.checkLiveness(socket), WATCH_TICK_MS);
    socket.onmessage = (event) => {
      if (this.socket !== socket) return;
      const text = typeof event.data === "string" ? event.data : null;
      if (text === null) return;
      if (text.length > MAX_FRAME_CHARS) {
        socket.close(1009);
        return;
      }
      void this.onText(socket, text);
    };
    socket.onerror = () => {
      // 続く onclose で扱う
    };
    socket.onclose = (event) => {
      if (this.socket !== socket) return;
      let next: RelayStatus = "failed";
      // 中継が閉じた理由が auth・removed なら家に入っていない
      if ((this.statusValue === "connecting" && event.reason === "auth") || event.reason === "removed") next = "not_member";
      else if (event.code === 1000 || event.code === 1001) next = "disconnected";
      else this.log(`relay-v2 closed code=${event.code}`);
      this.onDisconnected(socket, next);
    };
  }

  private async onText(socket: WebSocket, text: string): Promise<void> {
    let op: unknown;
    let nonce: unknown;
    try {
      const json = JSON.parse(text) as { op?: unknown; nonce?: unknown };
      op = json.op;
      nonce = json.nonce;
    } catch {
      return;
    }
    if (this.socket === socket) this.watch.received(this.now(), op === "welcome");
    if (op === "challenge") {
      if (typeof nonce !== "string") return;
      let auth: string | null;
      try {
        auth = await authReply(nonce, this.options.relayHost, this.options.identityPub, this.options.sign);
      } catch {
        this.log("relay-v2 auth sign failed");
        return;
      }
      if (auth !== null && this.socket === socket) socket.send(auth);
      return;
    }
    if (op === "welcome") {
      if (this.socket !== socket) return;
      this.failedAttempts = 0;
      this.setStatus("connected");
      this.log("relay-v2 connected");
    }
    if (this.socket !== socket) return;
    this.options.deliver(text);
  }

  private onDisconnected(socket: WebSocket, next: RelayStatus): void {
    if (this.socket !== socket) return;
    this.socket = null;
    this.stopWatch();
    this.setStatus(next);
    if (this.users > 0 && next !== "not_member") {
      const wait = reconnectDelayMs(this.failedAttempts);
      this.failedAttempts++;
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null;
        if (this.users > 0 && this.socket === null) this.open();
      }, wait);
    }
    this.options.onConnectionLost();
  }

  private close(): void {
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    const closing = this.socket;
    this.socket = null;
    this.stopWatch();
    this.setStatus("disconnected");
    if (closing === null) return;
    try {
      closing.close(1000);
    } catch {
      // 閉じられなくてもよい
    }
    this.options.onConnectionLost();
  }
}
