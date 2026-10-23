// ページ（music.youtube.com の content script）・設定の画面と、背景（ConnectService を持つ）の間の Port の取り決め。
// ページの側（src/page/）はこのファイルの型だけを使えばよい。README の「Port の取り決め」と同じもの。

import type { ConnectEvent, ConnectSettings, PageState } from "../connect/service.ts";
import type { PageTrack } from "../connect/queue.ts";
import type { HandoffRepeat } from "../connect/models.ts";

export type {
  ConnectEvent,
  ConnectSettings,
  ConnectStatus,
  DeviceView,
  PageCommand,
  PageState,
  PairingView,
  RemoteView,
} from "../connect/service.ts";
export type { IdentifiedTrack, PageTrack } from "../connect/queue.ts";
export type { HandoffRepeat, HandoffSource } from "../connect/models.ts";

/** ページが開く Port の名前（chrome.runtime.connect({ name })）。 */
export const PAGE_PORT = "wave-page";
/** 設定の画面が開く Port の名前。 */
export const OPTIONS_PORT = "wave-options";
/** 取り決めの版（hello で送る。合わなければ背景が error を返す）。 */
export const PROTOCOL_VERSION = 1;
/** ページが背景を起こしておくために ping を送る間隔（MV3 の背景は 30 秒で止まる）。 */
export const PING_INTERVAL_MS = 20_000;

/** ページ → 背景。id を付けたものには result が返る。 */
export type PageToBackground = { id?: number } & (
  | { type: "hello"; version: number }
  | { type: "ping" }
  /** ytcfg の DATASYNC_ID（ログインしていなければ null）。ログインが変わるたびに送る。 */
  | { type: "account"; dataSyncId: string | null }
  /** 再生の様子（曲・再生/一時停止・シーク・キュー・音量・シャッフル・繰り返しが変わったとき）。 */
  | { type: "state"; state: PageState }
  /** 本人がページで再生を始めた（曲を選んだ・再生を押した）。先に state を送っておく。result の ok は家の再生にしたか。 */
  | { type: "user_started" }
  /** ページの再生が無くなった（キューを消した）。 */
  | { type: "ended" }
  /** 家の端末 deviceId へ再生を移す。 */
  | { type: "transfer_to"; deviceId: string }
  /** ほかの端末で鳴っている再生を、このページへ引き取る（「ここで再生」）。 */
  | { type: "take_over" }
  /** ほかの端末で鳴っている再生を操作する（このページが鳴らしていないとき）。 */
  | { type: "remote_control"; action: "play" | "pause" | "next" | "previous" | "seek"; positionMs?: number }
  | { type: "remote_volume"; volumePercent: number }
  | { type: "remote_shuffle"; on: boolean }
  | { type: "remote_repeat"; mode: HandoffRepeat }
  | { type: "remote_queue_add"; tracks: PageTrack[]; next: boolean }
  | { type: "remote_queue_move"; itemId: string; afterItemId: string | null }
  | { type: "remote_queue_remove"; itemIds: string[] }
  | { type: "remote_queue_clear_flow"; currentItemId: string }
);

/** 設定の画面 → 背景。どれも result が返る（get の value は OptionsSnapshot）。 */
export type OptionsToBackground = { id: number } & (
  | { type: "get" }
  | { type: "configure"; settings: Partial<ConnectSettings> }
  | { type: "join"; offerText: string }
  | { type: "cancel_join" }
  | { type: "forget" }
  | { type: "accept_store_reset" }
  | { type: "transfer_to"; deviceId: string }
);

export interface OptionsSnapshot {
  settings: ConnectSettings;
  events: ConnectEvent[];
}

/** 背景 → ページ・設定の画面。 */
export type BackgroundToClient =
  | { type: "event"; event: ConnectEvent }
  | { type: "result"; id: number; ok: boolean; value?: unknown; error?: string }
  | { type: "pong" };
