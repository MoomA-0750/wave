// 拡張なしの Firefox で曲を流し、止まらずに進むかだけを見る（環境の問題か切り分ける）
import puppeteer from 'puppeteer-core';
const b = await puppeteer.launch({
  browser: 'firefox', executablePath: process.env.FIREFOX || '/usr/bin/firefox', headless: process.env.HEADLESS !== '0',
  extraPrefsFirefox: { 'media.autoplay.default': 0, 'media.autoplay.blocking_policy': 0, 'dom.webdriver.enabled': false },
});
const p = await b.newPage();
if (process.env.HIDEWD) await p.evaluateOnNewDocument(() => { Object.defineProperty(Navigator.prototype, "webdriver", { get: () => false, configurable: true }); });
await p.goto('https://music.youtube.com/watch?v=djV11Xbc914', { waitUntil: 'domcontentloaded' });
for (let i = 0; i < (+process.env.N||18); i++) {
  await new Promise(r => setTimeout(r, 5000));
  const s = await p.evaluate(() => {
    const pl = document.querySelector('#movie_player'); const v = document.querySelector('video');
    if (!pl || !v) return 'no player';
    const br = []; for (let k = 0; k < v.buffered.length; k++) br.push(v.buffered.start(k).toFixed(0) + '-' + v.buffered.end(k).toFixed(0));
    return { ad: pl.classList.contains('ad-showing'), st: pl.getPlayerState(), t: v.currentTime.toFixed(1), rs: v.readyState, err: v.error && v.error.code, buf: br.join(' '), wd: navigator.webdriver, net: performance.getEntriesByType("resource").filter(e=>/googlevideo|videoplayback/.test(e.name)).slice(-4).map(e=>(e.responseStatus||"?")+" "+e.name.slice(0,60)+" "+Math.round(e.transferSize)) };
  });
  console.log(i * 5 + 5, JSON.stringify(s));
}
await b.close();
