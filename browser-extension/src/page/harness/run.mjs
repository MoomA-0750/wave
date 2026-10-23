// 確かめ用の拡張（dist/）を入れたブラウザで music.youtube.com を開き、dev-main.js の台本を流す。
//   node run.mjs chromium       … puppeteer で Chromium（CHROME=... で替えられる）
//   node run.mjs firefox        … web-ext（RDP で一時的に入れる。自動操作の印が付かない）で Firefox（FIREFOX=...）
//   node run.mjs firefox-bidi   … puppeteer（WebDriver BiDi）で Firefox。YTM が 30 秒ほどで読み込みを止めるので参考
// ログインはしない（匿名の再生。広告が入ることがある）。記録は out/<名前>.json に書く。
// HEADLESS=0 で画面を出す（画面の無い所では xvfb-run を前に付ける）。
import puppeteer from 'puppeteer-core';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const which = process.argv[2] || 'chromium';
const dist = path.join(here, 'dist');
const outDir = path.join(here, 'out');
fs.mkdirSync(outDir, { recursive: true });
const headless = process.env.HEADLESS !== '0';
const sleep = ms => new Promise(r => setTimeout(r, ms));

// 台本の記録の受け口（background が POST してくる）
const log = [];
let finish;
const finished = new Promise(r => { finish = r; });
const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  let body = '';
  req.on('data', c => { body += c; });
  req.on('end', () => {
    res.end('ok');
    if (req.method !== 'POST') return;
    try {
      const line = JSON.parse(body);
      log.push(line);
      const d = typeof line.detail === 'string' ? line.detail : JSON.stringify(line.detail);
      console.log(`[${line.t}s] ${line.ok ? 'OK ' : 'NG '} ${line.step} ${d}`);
      if (line.step === 'done' || line.step === 'exception') finish();
    } catch { /* 無視 */ }
  });
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
const url = `https://music.youtube.com/#wave-dev-test=${port}`;
const timeout = setTimeout(() => { console.log('timeout'); finish(); }, 20 * 60 * 1000);

let close = async () => {};
let version = which;
if (which === 'firefox') {
  const { default: webExt } = await import('web-ext');
  const runner = await webExt.cmd.run({
    sourceDir: dist,
    firefox: process.env.FIREFOX || '/usr/bin/firefox',
    startUrl: [url],
    noReload: true,
    noInput: true,
    args: headless ? ['-headless'] : [],
    pref: { 'media.autoplay.default': 0, 'media.autoplay.blocking_policy': 0, 'intl.accept_languages': 'ja,en' },
  }, { shouldExitProgram: false });
  close = async () => { await runner.exit(); };
} else {
  let browser;
  if (which === 'firefox-bidi') {
    browser = await puppeteer.launch({
      browser: 'firefox',
      executablePath: process.env.FIREFOX || '/usr/bin/firefox',
      headless,
      extraPrefsFirefox: { 'media.autoplay.default': 0, 'media.autoplay.blocking_policy': 0, 'intl.accept_languages': 'ja,en' },
    });
  } else {
    browser = await puppeteer.launch({
      executablePath: process.env.CHROME || '/usr/bin/chromium',
      headless,
      pipe: true,
      enableExtensions: true,
      // 自動操作の印があると、YTM は途中で読み込みを止める（広告も曲も回り続ける）
      ignoreDefaultArgs: ['--enable-automation'],
      args: [
        '--disable-blink-features=AutomationControlled',
        '--autoplay-policy=no-user-gesture-required',
        '--mute-audio',
        '--lang=ja',
        // 頭なしの印（HeadlessChrome）だと YTM が「このブラウザーは非対応」を出す
        '--user-agent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36',
      ],
    });
  }
  version = await browser.version();
  const extId = await browser.installExtension(dist);
  console.log(`installed ${version} id=${extId}`);
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  close = async () => {
    try { await page.screenshot({ path: path.join(outDir, `${which}.png`) }); } catch { /* 無視 */ }
    await browser.close();
  };
}

await finished;
clearTimeout(timeout);
fs.writeFileSync(path.join(outDir, `${which}.json`), JSON.stringify({ browser: version, log }, null, 1));
await sleep(500);
await close();
server.close();
process.exit(0);
