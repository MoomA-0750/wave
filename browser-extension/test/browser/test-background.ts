// 試験用の背景（配らない）。本物の背景を始め、試験の台本（test/browser/run.ts の受け口）から指示を受けて答える。
// 自動操作（WebDriver・CDP）を使わずに拡張を動かすため（YouTube Music は自動操作の印があると 30 秒で読み込みを止める）。
// 台本へ送るのは状態・端末・ページの様子だけ（ログインの値は送らない。状態の account は種類だけ）。

import { startBackground } from "../../src/background/background.ts";
import { ext } from "../../src/shared/browser.ts";

declare const WAVE_TEST_CONTROL: string;

interface Instruction {
  id: number;
  type: "configure" | "join" | "tab";
  relayUrl?: string;
  offerText?: string;
  /** タブで本人が押したのと同じ操作（YouTube Music のバーの関数を呼ぶ）。 */
  action?: "next" | "previous" | "play" | "pause";
}

// 背景の記録（種類と件数だけのもの）を台本へ渡す
const logs: string[] = [];
const debug = console.debug.bind(console);
console.debug = (...args: unknown[]) => {
  logs.push(args.map(String).join(" "));
  if (logs.length > 200) logs.shift();
  debug(...args);
};

const ready = startBackground();

async function onTab(action: string): Promise<unknown> {
  const [tab] = await ext.tabs.query({ url: "https://music.youtube.com/*" });
  if (tab?.id === undefined) return { ok: false, error: "no_tab" };
  const [result] = await ext.scripting.executeScript({
    target: { tabId: tab.id },
    world: "MAIN",
    args: [action],
    func: (a: string) => {
      const bar = document.querySelector("ytmusic-player-bar") as unknown as Record<string, () => void> | null;
      const player = document.querySelector("#movie_player") as unknown as Record<string, () => void> | null;
      if (a === "next") (bar?.["onNextButtonClick"] ? bar["onNextButtonClick"]() : player?.["nextVideo"]?.());
      else if (a === "previous") (bar?.["onPreviousButtonClick"] ? bar["onPreviousButtonClick"]() : player?.["previousVideo"]?.());
      else if (a === "play") player?.["playVideo"]?.();
      else if (a === "pause") player?.["pauseVideo"]?.();
      return true;
    },
  });
  return { ok: result?.result === true };
}

async function loop(): Promise<void> {
  const hub = await ready;
  const s = hub.service;
  for (;;) {
    try {
      const report = { status: s.status(), devices: s.devices(), remote: s.remote(), page: s.lastPageState(), logs: logs.splice(0) };
      const response = await fetch(`${WAVE_TEST_CONTROL}/next`, { method: "POST", body: JSON.stringify(report) });
      const instruction = (await response.json()) as Instruction | null;
      if (instruction?.type) {
        let result: unknown;
        try {
          if (instruction.type === "configure") result = await s.configure({ relayUrl: instruction.relayUrl ?? "" });
          else if (instruction.type === "join") result = await s.join(instruction.offerText ?? "");
          else if (instruction.type === "tab") result = await onTab(instruction.action ?? "");
        } catch (e) {
          result = { error: e instanceof Error ? e.message : String(e) };
        }
        await fetch(`${WAVE_TEST_CONTROL}/result`, { method: "POST", body: JSON.stringify({ id: instruction.id, result: result ?? null }) });
      }
    } catch {
      // 台本がまだ・もう無い
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
}

void loop();
