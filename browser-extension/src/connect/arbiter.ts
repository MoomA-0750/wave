// 止まる規則（D-8、L-3・L-10）。再生中の端末が、ほかの player の署名を確かめた証拠を受けたときに、止まるかを決める（Android 版・Swift と同じ）。
// 端末の時計は使わない。「相手の再生を見てから始めた方が後」で決め、互いを見ずに始めたときだけ、中継が claim に振った順番（claimOrder）で決める。

export interface ArbiterSession {
  /** セッションを始めたときに見ていた最新のカーソルの版。 */
  base: number;
  /** このセッションで自分が最初に書けたカーソルの版（まだ書けていなければ null）。 */
  startVersion: number | null;
}

export type Evidence =
  /** 自分宛の yield（相手は新しくセッションを始めたところ）。 */
  | { kind: "yield"; from: string; sessionBase: number }
  /** 相手の、再生中のカーソル。一時停止のカーソルは証拠にしない。 */
  | { kind: "playing_cursor"; from: string; sessionBase: number; sessionStartVersion: number | null };

export type Decision = "stop" | "keep" | "defer";
export type Reason = "other_started_after_mine" | "mine_started_after_other" | "concurrent" | "self_evidence";

export function decide(
  myId: string,
  mine: ArbiterSession,
  evidence: Evidence,
  myClaimOrder: number | null,
  otherClaimOrder: number | null,
): { decision: Decision; reason: Reason } {
  if (evidence.from === myId) return { decision: "keep", reason: "self_evidence" };
  if (mine.startVersion !== null && evidence.sessionBase >= mine.startVersion) return { decision: "stop", reason: "other_started_after_mine" };
  if (evidence.kind === "playing_cursor" && evidence.sessionStartVersion !== null && mine.base >= evidence.sessionStartVersion) {
    return { decision: "keep", reason: "mine_started_after_other" };
  }
  if (myClaimOrder === null || otherClaimOrder === null) return { decision: "defer", reason: "concurrent" };
  return { decision: myClaimOrder < otherClaimOrder ? "stop" : "keep", reason: "concurrent" };
}
