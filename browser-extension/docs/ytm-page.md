# YouTube Music のタブを読む・動かす（ページ側）

ブラウザー拡張で、music.youtube.com のタブを Connect の家の1台にするための、ページ側の調べと作り。
コードは `browser-extension/src/page/`。Connect の約束（`src/connect/`）とは別に作っている。
2026-10-24 に、匿名（ログインなし）の music.youtube.com（プレーヤー `cbcfbf2f`、画面 `af273e12`）で調べた。

## 作りのあらまし

```
background ──Port('wave-ytm-page')── page-content.ts（隔離 world）──window.postMessage── page-main.ts（MAIN world）── YTM
```

| ファイル | world | 役目 |
|---|---|---|
| `protocol.ts` | 両方 | `PageState`・`PageCommand`・Port とブリッジのメッセージの型、検査（`parsePageState`・`parseCommand`） |
| `ytm.ts` | MAIN | YTM の内部を読む（`readState`）・動かす（`runCommand`）・変化を見張る（`watchState`） |
| `page-main.ts` | MAIN の入口 | 指示を受けて `ytm.ts` を呼び、状態と結果を返す。拡張の API には触らない |
| `ytm-page.ts` | 隔離 | `createYtmPage()`: `onState(cb)`・`latest()`・`command(cmd)`・`dispose()` |
| `page-content.ts` | 隔離の入口 | `createYtmPage()` を Port につなぐ。切れたらつなぎ直す |
| `harness/`（リポジトリの `.gitignore` が `dev/` を除くので、この名前にした） | — | 確かめ用の拡張と台本（配る拡張には入れない） |

### 束ね方（本体のビルドへの組み込み）

- 入口は2つ。`src/page/page-main.ts`（MAIN world）と `src/page/page-content.ts`（隔離 world）。どちらも import を含むので、esbuild で `bundle: true, format: 'iife'` にして1ファイルずつにする（`harness/build.mjs` が同じことをしている）
- `page-content.ts` は読み込まれるとすぐ Port をつなぐ（`export const pageContent`）。本体の隔離 world の入口から `import { pageContent } from '../page/page-content'` してもよいし、そのまま入口にしてもよい
- manifest には次の2つを入れる（`world: "MAIN"` は Chrome 111・Firefox 128 から。Firefox 156 で動くのを確かめた。古い Firefox のために `<script>` を差し込む道は作っていない）

```json
"host_permissions": ["https://music.youtube.com/*"],
"content_scripts": [
  { "matches": ["https://music.youtube.com/*"], "js": ["page-main.js"], "world": "MAIN", "run_at": "document_idle" },
  { "matches": ["https://music.youtube.com/*"], "js": ["page-content.js"], "run_at": "document_idle" }
]
```

### Port の約束

- タブ → background: `{ type: 'state', state: PageState }`（変わるたび・つないだ直後・20 秒おき）、`{ type: 'result', id, result: { ok, error? } }`
- background → タブ: `{ type: 'command', id, command: PageCommand }`
- 20 秒おきに読み直して送るのは、位置の見込みを直すためと、Chrome の service worker が 30 秒の無音で止まって Port が切れるのを防ぐため。切れたら 1 秒後につなぎ直す。切れているあいだに送られた指示は届かないので、background は送り直すこと

### 信用の線

- ページの JS（と、ページに入り込んだもの）は、MAIN world の `page-main` と同じことができる。つまりプレーヤーを動かせるだけで、拡張の権限には届かない
- MAIN と隔離のメッセージは `ns: 'wave-ytm-page'`・`v: 1`・`dir`（`toMain`／`toContent`）の印で分け、`ev.source === window` のものだけ受ける。隔離側は、MAIN から来た状態と結果を必ず `parsePageState`・`parseResult` で形を直してから使う（文字の長さ・配列の数を切る、videoId は 11 文字の形だけ、URL は https だけ）
- ページは状態を偽れる。拡張は、タブから来た状態を「その端末の言い分」として扱い、それをもとに権限の要る動き（他のタブを開く、保存したものを渡すなど）をしない
- 指示は隔離側でも MAIN 側でも `parseCommand` を通す

## 読むところ（選んだ面と理由）

YTM は Polymer の画面と redux 風の store、`#movie_player`（YouTube の埋め込みと同じプレーヤーの API）でできている。どれも MAIN world からしか触れない（隔離 world からは要素のプロパティ・関数が見えない）。

| 読むもの | 使う面 | 理由・注意 |
|---|---|---|
| 今の曲の videoId | `#movie_player.getVideoData().video_id` | 広告中も、広告のあとに流れる曲の ID のまま。いちばん確か |
| 曲名・アーティスト・アルバム・ジャケット・長さ | キューの今の項目（`playlistPanelVideoRenderer`） | `longBylineText.runs` の `browseEndpoint` の `pageType` が `MUSIC_PAGE_TYPE_ARTIST`（複数あり）・`MUSIC_PAGE_TYPE_ALBUM` を拾う。アルバムが取れるのは「曲」（ATV）だけで、MV（OMV）は「アーティスト • 再生回数 • 高評価数」になり、アルバムは null。長さは `#movie_player.getDuration()` で上書き |
| 同上の予備 | `navigator.mediaSession.metadata` | **広告中は広告の名前になる**ので、広告でなく、曲名が一致するときだけ使う |
| 再生の様子 | `#movie_player.getPlayerState()`（-1 未開始・0 終わり・1 再生・2 一時停止・3 読み込み・5 頭出し） | 広告中は -1 のままなので、広告中だけ `<video>.paused` を見る |
| 広告 | `#movie_player` の class `ad-showing`・`ad-interrupting` | redux の `player.adPlaying` は広告中も false のことがあり、使えない。`getAdState()` も -1 のまま |
| 位置 | `#movie_player.getCurrentTime()` | 広告中は 0 として送る |
| 音量・消音 | `#movie_player.getVolume()`・`isMuted()` | redux の `player.volume` はプレーヤーと別の値（つまみの表示用） |
| シャッフル・リピート・自動再生 | redux `queue.shuffleEnabled`・`repeatMode`（`NONE`/`ALL`/`ONE`）・`autoplay` | `ytmusic-app.getState()` |
| 評価 | `ytmusic-player-bar ytmusic-like-button-renderer` の属性 `like-status`（`LIKE`/`DISLIKE`/`INDIFFERENT`） | 匿名でも読める（`INDIFFERENT`） |
| キュー | redux `queue.items`（本体）と `queue.automixItems`（自動再生で足される予定）、今の位置は `ytmusic-player-queue.queue.getCurrentItemIndex()` | redux の `selectedItemIndex` は遅れることがあるので使わない。項目の `selected` は予備 |
| 項目の ID | 項目の `navigationEndpoint.watchEndpoint.index`（文字列にして `itemId`） | YTM が足すたびに振り直す番号。消す・選ぶのに使う |

- キューの「自分で足した曲」と「元の流れ」は、YTM の中では区別が無い（どちらも `items` に入る）。区別できるのは「自動再生（Automix・ラジオ）の列」だけで、これは `automix` として別に出す。自動再生の曲は、流れる番になると `items` へ移る
- 曲と MV の両方を持つ項目は `playlistPanelVideoWrapperRenderer`（`primaryRenderer` と `counterpart`）で来ることがある。今の曲は、プレーヤーの videoId と合う方を選ぶ。匿名では一度も出てこなかったので、ログイン時の形は確かめていない

## 動かすところ

| 指示 | やり方 | 確かめ |
|---|---|---|
| `play` / `pause` | `#movie_player.playVideo()` / `pauseVideo()`。広告中は `<video>` を直接 | 両方 OK |
| `seek` | `#movie_player.seekTo(秒, true)`。広告中は断る | 両方 OK |
| `next` / `previous` | `ytmusic-player-bar` の `onNextButtonClick()` / `onPreviousButtonClick()`（無ければボタンを押す → `nextVideo()`） | 両方 OK。`previous` は YTM と同じで、曲の頭近くなら前の曲、そうでなければ頭へ戻る |
| `setVolume` | `#movie_player.setVolume()` と redux `SET_VOLUME`（つまみの表示）を両方 | 両方 OK |
| `setMuted` | `mute()`／`unMute()` と redux `SET_MUTED` | 両方 OK |
| `setRepeat` | バーの `onRepeatButtonClick()` を、狙いの値になるまで（最大3回）押す（なし→全体→1曲→なし） | 両方 OK |
| `setShuffle` | バーの `onShuffleButtonClick()`（値が違うときだけ） | 下の注意を参照。自動の確かめには入れていない |
| `setAutoplay` | redux に `SET_AUTOPLAY_ENABLED` と `HAS_USER_CHANGED_DEFAULT_AUTOPLAY_MODE`。キューの「自動再生」のスイッチを押したときと同じ（画面にも「自動再生がオフ」と出る） | 両方 OK |
| `setLike` | バーの高評価・低評価のボタンを押し、属性が変わるのを待つ | 匿名ではログインを求められて付かない（失敗を返す）。ログイン時は未確認 |
| `playIndex` | `ytmusic-player-queue.queue.selectQueueItem(項目)` | 両方 OK |
| `addToQueue` | `ytmusic-menu-service-item-renderer` を作って画面に付け、その `handleCommand({ queueAddEndpoint: { queueTargets: [{videoId}...], queueInsertPosition } })` を呼ぶ。「次に再生」は `INSERT_AFTER_CURRENT_VIDEO`、「最後に」は `INSERT_AT_END` | 両方 OK。メニューの「次に再生」と同じ道なので、曲の情報は YTM がサーバーから取ってくる。`queueTargets` に複数を並べると、その順で入る |
| `remove` | 同じ要素の `handleCommand({ removeFromQueueEndpoint: { itemId } })`（だめなら `queue.removeItem(itemId)`）。`videoId` を添えると、その位置の曲が違うときは断る | 両方 OK |
| `move` | `ytmusic-player-queue.onDrop()` に偽のドラッグ（`dragEl.data` と並びの中の位置）を渡す。ドラッグで並べ替えたときと同じ関数に届く | 両方 OK。今の曲の位置は保たれる |
| `clearUpcoming` | 今の曲より後ろの項目を後ろから `remove` | 両方 OK。自動再生の列は消さない（`setAutoplay(false)` で止める） |
| `loadQueue` | 下の手順 | 両方 OK |

### loadQueue（wave の端末からタブへ再生を移すとき）

1. `ytmusic-app` に `yt-navigate` の出来事で `{ watchEndpoint: { videoId: 流す曲 } }` を送る（画面の読み直しは無い）。キューは「その曲 ＋ 自動再生」になる
2. redux の `items` が入れ替わり、今の項目がその曲になるまで待つ（最大 15 秒）。ほかの項目があれば外す
3. 後ろの曲を `addToQueue(…, 'end')`、前の曲を `addToQueue(…, 'next')` で入れ、今の曲を `move(0, index)` で前の曲たちの後ろへ動かす
4. 位置と「流す・止める」は、広告が終わって本編が流れ出してから当てる。広告の前に当てたシークは消えるので、位置が狙い（−2〜＋15 秒）に入ったのを見届けるまで当て直す（最大 120 秒・20 回。ほかの指示が来たらやめる）
5. `autoplay` を渡したときは、キューの自動再生も切り替える

- 一度に送る曲は 50 曲ずつに分ける（200 曲まで受ける）
- 試してやめた道: `https://www.youtube.com/watch_videos?video_ids=a,b,c` が返す一時プレイリスト（`list=TLGG…`）を `watchEndpoint.playlistId` に渡すと、YTM でもその並びで流せた（`index` で途中から）。ただ、この URL は www.youtube.com にしかなく、ページからは CORS で読めない（background で `fetch` して転送先の URL を読む必要がある）。キューの中の操作だけで足りたので使っていない
- 1曲目を開いた時点で、その曲のラジオが自動再生の列に入る。wave のキューの後ろにラジオを続けたくないときは `autoplay: false` を渡す

### 自動再生（Automix・ラジオ）は止められる

- `setAutoplay(false)` のあと、最後の曲を流し終えると `playback: 'ended'` のまま止まり、次へ進まないことを両方のブラウザーで確かめた。`true` に戻すと、自動再生の列の先頭の曲へ進んだ
- 切っても `automix` の列は state に残る（YTM の画面では隠れる）。`queue.autoplay` が false のときは、その列は流れないと読む
- この設定は YTM が覚えて、次の読み込みでも残る（`hasUserChangedDefaultAutoplayMode`）

### シャッフルの注意

- YTM のシャッフルは手元での並べ替えではなく、サーバーにキューを作り直させる（`watchPlaylistEndpoint` に shuffle の印を付けて取り直す）
- 試したところ（一時プレイリスト A,B,C に 2 曲足して 6 曲にしたキュー）、入れると足した曲が消えて元の 3 曲の並べ替えになり、切ると元の並びに戻ったうえで、流れていた曲から先頭の曲（A）へ飛んで頭から流れた。wave のシャッフル（入り切りでき、切ると元の並びに戻す）とは合わない
- なので wave からタブを操作するときは、並びを wave 側で決めて `loadQueue`・`move` で送る方がよい。`setShuffle` は YTM の画面のボタンと同じ動きとして残してある

## 変化を知らせる

- `#movie_player.addEventListener` の `onStateChange`・`onVideoDataChange`・`onVolumeChange`・`onAdStart`・`onAdEnd`
- `<video>` の `play`・`pause`・`playing`・`waiting`・`seeked`・`ended`・`volumechange`・`loadedmetadata`・`durationchange`・`ratechange`・`emptied`（document で捕捉段階に拾うので、要素が作り直されても届く）
- redux の `queue`・`player` の参照が入れ替わったか（250 ms おきに比べるだけで、中身は読まない。redux は変わるたびに新しい物を作る）
- 評価のボタンの属性（MutationObserver）
- 取りこぼしの保険に 1 秒おきに読む
- 50 ms まとめてから読み、位置と時刻を除いた中身が変わったときだけ送る。位置は「前に送った位置と時刻から見込める値」と 1.5 秒以上ずれたとき（シーク）だけ送る。受け手は `positionMs + (今 − sampledAt) × playbackRate` で進める

## 確かめた結果（2026-10-24）

各ブラウザーで2回ずつ流し、どちらも 29 手順すべて OK だった。`src/page/harness/` の拡張（本物の `page-main`・`page-content` と、指示を Port で折り返す dev の background）を入れ、台本 `dev-main.js` を流した。指示は「ページ → dev-content → Port → background → Port → page-content → page-main」と本物と同じ道を通り、状態は MAIN → 隔離 world（`parsePageState` を通ったもの）を読んでいる。曲は A=dQw4w9WgXcQ・B=djV11Xbc914・C=fJ9rUzIMcZQ・D=Zi_XLOBDo_Y（どれも MV）。

| 手順 | Chromium 153（headless） | Firefox 156（web-ext・headless） |
|---|---|---|
| 拡張の読み込み・MAIN world | OK | OK（`world: "MAIN"` が効く） |
| ホームで状態が届く（曲なし） | OK | OK |
| `loadQueue [A,B,C] index=1 at 30s` → B を 30 秒から、キュー A,B,C・今 1 | OK（広告のあと。命令から 21〜96 秒、広告の長さしだい） | OK（広告のあと。9〜50 秒） |
| 40 秒なにも送らずに `pause` | OK（Port は切れない。つなぎ直し 0 回） | OK |
| `play`・`seek 90s`・`setVolume 30`・`setMuted` 入切 | OK | OK |
| `next` → C（曲の変わり目が状態で届く） | OK | OK |
| `previous`（頭近く）→ B | OK | OK |
| `playIndex 2` → C | OK | OK |
| `addToQueue [D] next` → A,B,C,D | OK | OK |
| `remove 0 (A)` → B,C,D | OK | OK |
| 違う videoId を添えた `remove` は断る | OK（`video mismatch`） | OK |
| `move 2→0` → D,B,C（今の C は 2 のまま） | OK | OK |
| `addToQueue [A,B] end` → D,B,C,A,B | OK | OK |
| `clearUpcoming` → D,B,C | OK | OK |
| `setRepeat all/one/off` | OK | OK |
| `setAutoplay false` → 最後の曲が終わると ended のまま止まる | OK | OK |
| `setAutoplay true` → 自動再生の曲へ進む | OK | OK |
| 曲が終わって次へ進むのが状態で届く | OK | OK |
| `setLike like`（匿名） | 失敗を返す（ログインを求められる。想定どおり） | 同じ |
| `loadQueue [C,D] index=0 at 10s playing=false autoplay=false` → C を 10 秒で止めて、キュー C,D | OK | OK |

- 動かし方: `cd browser-extension/src/page/harness && npm i && npm run test:chromium`（または `test:firefox`）。記録は `harness/out/<ブラウザー>.json`
- 匿名だと、曲を開くたびに広告が入ることがある（1〜2 本、長いと 90 秒）。広告中の state は `adPlaying: true`、`track` は広告のあとの曲。台本は広告が終わるのを待つ
- 同意の画面（EU の Cookie の同意）は日本からは出なかった。出る地域では、拡張では閉じずに本人に任せる（未確認）
- 言語で表記が変わる（Firefox を日本語にすると「a〜ha」「クイーン」）。名前で照合しないこと

### 自動の確かめで気をつけること（拡張そのものの問題ではない）

- 自動操作の印があると、YTM は曲の読み込みを 30 秒ほどで止める（広告も曲も「読み込み中」のまま進まない）。拡張なしでも同じ
  - Chromium: puppeteer の既定の `--enable-automation` を外し、`--disable-blink-features=AutomationControlled` を付けると止まらない
  - Firefox: WebDriver BiDi（puppeteer）で動かすと、`navigator.webdriver` を偽っても、画面を出しても止まる（最後の応答が 306 バイトになる）。web-ext（RDP で一時的に入れる。WebDriver を使わない）で動かすと止まらない。`node run.mjs firefox-bidi` と `harness/probe-ff.mjs` はこの切り分けの記録
- 頭なしの Chromium は UA に `HeadlessChrome` が入り、YTM が「このブラウザーは非対応」を出すので UA を替える

## 壊れやすいところ

YTM の内部に頼るので、YTM の更新で壊れうる。壊れたら `readState` の値が空になる・指示が `… not found` で失敗する、で気づける。

- Polymer の要素名と、テンプレートから呼ばれる関数名（`onNextButtonClick`・`onRepeatButtonClick`・`onShuffleButtonClick`・`onDrop`・`handleCommand`・`queue.getItems`・`getCurrentItemIndex`・`selectQueueItem`・`removeItem`）。テンプレートから呼ばれるので縮められていないが、作り替えで変わりうる
- redux の action の名前（`SET_AUTOPLAY_ENABLED`・`HAS_USER_CHANGED_DEFAULT_AUTOPLAY_MODE`・`SET_VOLUME`・`SET_MUTED`・`MOVE_ITEM`）と state の形（`queue.items`・`automixItems`・`repeatMode`）
- `onDrop` に渡す偽のドラッグは、`onDrop` の中身（`dragEl.data` で項目を探し、`parentElement.children` の中の位置を行き先にする）に合わせた形
- `ytmusic-menu-service-item-renderer` を画面に付けると、キューと画面遷移の係を受け取る、という作り
- 項目の ID が `watchEndpoint.index` であること
- 広告の印が `#movie_player` の class であること
- ログインした人では、キューがサーバーにも保存される（`serverQueuesEnabled`）。`move` の偽のドラッグと `remove` は YTM の関数を通すのでサーバーにも伝わるはずだが、確かめていない。redux へ直接 `MOVE_ITEM` を送る予備の道は、サーバーには伝わらない

## できないこと・確かめていないこと

- 広告は飛ばせない（飛ばさない）。広告中は `seek` を断り、`play`／`pause` は広告の `<video>` に効く
- 評価は匿名ではできない。ログイン時の評価・曲と MV の切り替え（`playlistPanelVideoWrapperRenderer`）・サーバーのキューとの同期は未確認
- 自分で足した曲と元の流れの区別は YTM に無い（自動再生の列だけは分かる）
- YTM のシャッフルは wave のシャッフルとして使えない（上の注意）
- `#movie_player` が無い間（ホームを開いただけ）は、`track: null`・`playback: 'idle'`。`loadQueue` はこの状態からでも動く
