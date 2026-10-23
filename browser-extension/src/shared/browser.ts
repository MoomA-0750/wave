// chrome.* と browser.* の小さな橋（Firefox は browser.* が Promise を返し、chrome.* も同じものを指す。Chrome は chrome.* だけ）。
// 使うのは runtime の Port と getURL・id だけなので、形の同じ chrome の型で扱う。

declare const browser: typeof chrome | undefined;

export const ext: typeof chrome = typeof browser !== "undefined" && browser?.runtime ? browser : chrome;

/** ブラウザの名前（端末名の既定に使う）。 */
export function browserName(): string {
  const ua = typeof navigator !== "undefined" ? navigator.userAgent : "";
  if (/Firefox\//.test(ua)) return "Firefox";
  if (/Edg\//.test(ua)) return "Edge";
  if (/Chrome\//.test(ua)) return "Chrome";
  return "Browser";
}
