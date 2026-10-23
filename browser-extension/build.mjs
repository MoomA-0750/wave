// 拡張を dist/ に組む（esbuild で、ES モジュールでないただのスクリプトにまとめる。Firefox の content script はモジュールにできない）。
// 背景（background.js）・設定の画面（options.js）と、manifest.json の content_scripts・web_accessible_resources に書いた JS を、
// src/ の同じ名前の .ts から組む（例: page/content.js ← src/page/content.ts）。ページの側（src/page/）がまだ無ければ空のものを置く。
//   node build.mjs          組む
//   node build.mjs --watch  変わるたびに組み直す

import { context } from "esbuild";
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const dist = join(root, "dist");
const watch = process.argv.includes("--watch");
const manifest = JSON.parse(readFileSync(join(root, "manifest.json"), "utf8"));

/** 組む先（dist からの道）から、もとの .ts へ。 */
const sources = new Map([
  ["background.js", "src/background/background.ts"],
  ["options.js", "src/options/options.ts"],
]);
const pageScripts = [
  ...(manifest.content_scripts ?? []).flatMap((c) => c.js ?? []),
  ...(manifest.web_accessible_resources ?? []).flatMap((w) => w.resources ?? []).filter((r) => r.endsWith(".js")),
];
for (const out of pageScripts) if (!sources.has(out)) sources.set(out, join("src", out.replace(/\.js$/, ".ts")));

rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });
cpSync(join(root, "manifest.json"), join(dist, "manifest.json"));
for (const file of ["options.html", "options.css"]) cpSync(join(root, "src/options", file), join(dist, file));
if (existsSync(join(root, "icons"))) cpSync(join(root, "icons"), join(dist, "icons"), { recursive: true });

const entryPoints = [];
for (const [out, src] of sources) {
  if (existsSync(join(root, src))) {
    entryPoints.push({ in: join(root, src), out: out.replace(/\.js$/, "") });
  } else {
    // ページの側がまだ無い: 読み込めるよう空のスクリプトを置く
    console.warn(`build: ${src} が無いので、${out} は空にします`);
    mkdirSync(dirname(join(dist, out)), { recursive: true });
    writeFileSync(join(dist, out), "// ページの側はまだありません\n");
  }
}

const ctx = await context({
  entryPoints,
  outdir: dist,
  bundle: true,
  format: "iife",
  target: ["chrome116", "firefox121"],
  sourcemap: watch ? "inline" : false,
  minify: false,
  legalComments: "none",
  logLevel: "info",
});
if (watch) {
  await ctx.watch();
} else {
  await ctx.rebuild();
  await ctx.dispose();
}
