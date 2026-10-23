# wave for YouTube Music（ブラウザの拡張・下書き）

music.youtube.com のタブを、wave の端末の連携（Connect v2）の1台にする拡張機能。Chrome と Firefox（MV3）、Windows・macOS・Linux。
音は YouTube Music そのものが鳴らす（ブラウザでログインしたまま）。拡張は連携の橋渡しだけをする。

- `src/connect/` … 連携の中身（YouTube Music を知らない）。Swift の `mac/Sources/WaveCore/Connect/` を移したもの。暗号は WebCrypto だけ
- `src/background/` … 背景（Chrome は service worker、Firefox は event page）。`ConnectService` を1つ持ち、ページと設定の画面の Port を受ける
- `src/options/` … ツールバーの小窓と拡張の設定（中継の宛先・端末名・加入・端末の一覧）
- `src/shared/protocol.ts` … ページ・設定の画面と背景の間の Port の取り決め（下の表と同じ）
- `src/page/` … ページの側。`page-main.ts`（MAIN world。YouTube Music の内部を読む・動かす）と、`content.ts`（隔離 world の入口。`createYtmPage()` を下の Port の取り決めにつなぐ）。調べと作りは `docs/ytm-page.md`。`page-content.ts` と `harness/` はページの側だけの確かめ用（Port 名 `wave-ytm-page`）で、配る拡張には入れない

## 組む・試す

```sh
npm install
npm run build        # dist/ に組む（背景・設定の画面・page/page-main.js（MAIN world）・page/content.js）
npm run typecheck
npm test             # 暗号・相互運用・キュー・Port の試験
npm run test:e2e     # 本物の中継と wave-api を立ち上げて確かめる（下）
npm run test:browser # 本物の music.youtube.com を開いた Chromium・Firefox で通しで確かめる（下）
```

`npm run test:e2e` は先に Kotlin の側を組んでおく（JDK 21 が要る。無ければ飛ばす）:

```sh
JAVA_HOME=<JDK 21> ./gradlew :relay:installDist :api:installDist
```

### ブラウザでの通しの確かめ（`npm run test:browser`）

`test/browser/run.ts` が、手元に中継（relay/）・wave-api（api/）・JS のスマホ役を立て、試験用に組んだ拡張（背景を `test/browser/test-background.ts` にしたもの。台本の受け口 127.0.0.1 から指示を受ける）を入れたブラウザで本物の music.youtube.com を開き、
加入 → wave-api の端末の一覧 → wave-api からタブへ移す（広告の後にキューと位置が当たって鳴る）→ 一時停止・再生・シーク・音量・シャッフルの入り切り・次へ → タブで次へ（wave-api に映る）→ スマホ役から次に再生・「次に流れる曲」の消去 → スマホ役へ移す（タブは止まる）→ タブで再生を押す（家の再生がタブに戻る）、を通しで確かめる。
ログインはしない（匿名。広告が入る）。本人の家の中継・端末にはつながない。結果は `test/browser/out/<ブラウザ>.json`。

- YouTube Music は自動操作の印があると 30 秒ほどで読み込みを止めるので、WebDriver・CDP を使わない。Chromium は `--load-extension` でそのまま起動し、Firefox は web-ext（RDP）で入れる。先に `src/page/harness` で `npm i`（web-ext）をしておく
- 試験用の組み方（`node build.mjs --test-control=… --out=…`）だけ、タブの「次へ」を押すための `scripting` と、手元の ws:// につなぐための CSP（Firefox の MV3 の既定の `upgrade-insecure-requests` を外す）を足す

### 読み込み方

- Chrome: `chrome://extensions` → デベロッパー モード → 「パッケージ化されていない拡張機能を読み込む」で `dist/`
- Firefox: `about:debugging#/runtime/this-firefox` → 「一時的なアドオンを読み込む」で `dist/manifest.json`

## 使い方

1. music.youtube.com を開いておく（ログインしていればそのアカウントで加わる）
2. ツールバーの wave を押し、「中継の宛先」に `wss://<中継のホスト>:<ポート>` を入れて保存する。署名に入れるホスト名は、中継の `WAVE_RELAY_HOST` と同じ値（空なら宛先のホストとポート）。宛先はリポジトリに書かない。Firefox は ws:// を wss:// に上げるので、wss:// にする
3. 家の端末（スマホ・PC）の「端末の連携」で「端末を追加」を押し、出た文字（`wave-pair:…`）を貼って「加わる」
4. 拡張に出た6桁を、家の端末に入れる。家の端末が加えると、拡張は家に入る
5. music.youtube.com を開いておくと、ほかの端末の一覧に「YouTube Music (Chrome)」などが PC として出て、「ここで再生」の行き先になる

## Port の取り決め（ページの側へ）

ページ（content script）は `chrome.runtime.connect({ name: "wave-page" })`（Firefox は `browser.runtime.connect`）で背景につなぎ、
`PING_INTERVAL_MS`（20 秒）ごとに `{ type: "ping" }` を送り続ける（MV3 の背景は 30 秒で止まるので、これでタブが開いている間だけ起こしておく）。
Port が切れたら（背景が止まった・拡張を入れ直した）つなぎ直して、`account` と `state` を送り直す。型は `src/shared/protocol.ts`。

### ページ → 背景

`id`（数）を付けると `{ type: "result", id, ok, value?, error? }` が返る。

| type | 中身 | いつ |
|---|---|---|
| `hello` | `version: 1` | つないだ直後 |
| `ping` | | 20 秒ごと（`pong` が返る） |
| `account` | `dataSyncId: string \| null` | ytcfg の `DATASYNC_ID`（そのまま。`\|\|` の後ろは背景が捨てる）。ログインしていなければ null。変わるたびに |
| `state` | `state: PageState` | 曲・再生/一時停止・シーク・キュー・音量・シャッフル・繰り返しが変わったとき（定期的に送ってもよい。位置だけの小さな変化は背景が間引く） |
| `user_started` | | 本人がページで再生を始めた（曲を選んだ・再生を押した）。先に `state` を送る。`ok` は家の再生にしたか |
| `ended` | | キューが無くなった |
| `transfer_to` | `deviceId` | 家の端末へ再生を移す |
| `take_over` | | ほかの端末で鳴っている再生を、このタブへ引き取る（「ここで再生」） |
| `remote_control` | `action: play\|pause\|next\|previous\|seek`, `positionMs?` | ほかの端末で鳴っている間の遠隔操作 |
| `remote_volume` / `remote_shuffle` / `remote_repeat` | `volumePercent` / `on` / `mode` | 同上 |
| `remote_queue_add` | `tracks: PageTrack[]`, `next` | 同上（ほかの端末のキューに足す） |
| `remote_queue_move` / `remote_queue_remove` / `remote_queue_clear_flow` | `itemId, afterItemId` / `itemIds` / `currentItemId` | 同上（ID は `remote` の出来事の `queue[].itemId`） |

`PageState`:

```ts
{
  queue: PageTrack[];      // 再生キュー（前に聴いた曲も含め、ページの並びのまま）
  index: number;           // 今の曲の位置
  positionMs: number; playing: boolean;
  repeat?: "off" | "one" | "all"; shuffle?: boolean;
  volumePercent?: number | null;   // YouTube Music の音量（0〜100）
  source?: { type: "radio", seedVideoId } | { type: "playlist", playlistId } | null;
  radioPlaylistId?: string | null;
  exhausted?: boolean;     // 自動再生を切っている（続きが無い）
  listenedMs?: number;     // 無ければ positionMs
  historySent?: boolean;   // 無ければ true（YouTube Music は自分で履歴を送る）
}
PageTrack = { videoId, title, artists?: {name, browseId?}[], album?: {name, browseId?}, durationMs?, thumbnailUrl?, isLive?, origin?: "source" | "suggestion" | "user" }
```

背景はキューの曲に連携の ID（`itemId`）を振って覚える。ページは ID を持たなくてよい（同じ曲が同じ順で残れば同じ ID になる）。

### 背景 → ページ

`{ type: "event", event }` で届く。`event.type` は:

- `status` … `ConnectStatus`（中継・家に入っているか・このタブが家の再生を書いているか・加入の様子）
- `devices` … `DeviceView[]`（家の端末。`playable` が「ここで再生」の行き先）
- `remote` … `RemoteView | null`（ほかの端末で鳴っている再生。曲・キュー・位置・音量・シャッフル。`accountMatches` が false なら別のアカウントで、操作できない）
- `notice` … 本人に知らせる文（`code`・`text`）
- `command` … `PageCommand`。このタブが家の再生を書いている間に、ほかの端末から届いた操作。**今のタブ（最後に `user_started` か `take_over` を送ったタブ）にだけ**届く

| command.kind | 中身 | ページがすること |
|---|---|---|
| `play` / `pause` | `pause` は `reason: "remote" \| "taken_over"` | 再生・一時停止。`taken_over` はほかの端末で再生が始まったので止める（自動では再開しない） |
| `next` / `previous` | `onItemId` | 次・前の曲へ |
| `seek` | `positionMs` | シーク |
| `volume` | `volumePercent` | YouTube Music の音量を変える（端末そのものの音量は変えない） |
| `repeat` | `mode` | 繰り返しを変える |
| `queue_order` | `fromIndices` | 今の曲より後ろを並べ替える（後ろの k 番目に、並べ替える前の `fromIndices[k]` の曲）。シャッフルの入り切りはこれで届く |
| `queue_add` | `tracks`（`itemId` 付き）, `afterIndex`, `next` | `afterIndex` の曲の直後に入れる（-1 は先頭）。インデックスは最後に送った `state` の `queue` の並び |
| `queue_move` | `fromIndex`, `afterIndex`, `itemId` | `fromIndex` の曲を `afterIndex` の曲の直後へ（どちらも動かす前の並び） |
| `queue_remove` | `indices`（昇順）, `itemIds` | その曲を外す |
| `queue_clear_flow` | `indices` | 今の曲より後ろの「次に流れる曲」（`indices`。自分で足した曲は含まない）を外し、自動再生を止める |
| `adopt` | `tracks`, `index`, `positionMs`, `playing`, `repeat`, `source`, `radioPlaylistId`, `exhausted`, `from` | ほかの端末の再生を引き取った。このキューを読み込み、`index` の曲の `positionMs` から鳴らす |

どの命令も、した後に `state` を送り直す（キューを変えたら、変えた並びを送る）。

### 本体の content script（`src/page/content.ts`）がしていること

- 様子 → `state`: キューは YouTube Music の「次に再生」の本体（自動再生の列は入れない）。`exhausted` は自動再生のスイッチが切れているか。音量は消音なら 0。題名が読めないうちは videoId を仮に入れる
- 広告の間は曲として出さない: その曲を前に鳴らしていた位置で止まって見せる。曲の前の広告（まだ位置が無い）なら知らせない
- `adopt` は `loadQueue`（前は 20 曲・全部で 200 曲まで）。**自動再生を切って読み込み**、wave の流れの続き（ラジオ）は今は足さない。読み込みの途中（キューを組み直している間・広告）は `state` を送らない
- `queue_add` は今の曲の直後なら「次に」、ほかは末尾に足してから `move`。`queue_clear_flow` は `indices` の曲を外して `setAutoplay(false)`
- `user_started`: 遠隔の命令の後の 4 秒（引き取りは鳴り出すまで）を除き、曲が変わって鳴っている（曲の終わり 5 秒以内で次へ進んだ・曲が終わったのは除く）か、同じ曲で一時停止から鳴り出したら送る。メディアキーも含めて本人の操作として扱う
- `account`: MAIN world で `ytcfg.get("LOGGED_IN")`・`ytcfg.get("DATASYNC_ID")` を読み、隔離 world で形を確かめてから送る（ログインしていなければ null。ytcfg がまだ無ければ送らない）。値は背景の中でアカウントタグにするだけで、中継にもログにも出さない（ADR-0002）

## ConnectService の口（背景の中）

`src/connect/service.ts`。Port の取り決めはこれをそのまま出したもの。

- `publishState(state)`・`userStartedPlayback()`・`claimPlayback(state)`（= 2つを続けて）・`endLocal()`・`setAccount(dataSyncId)`
- `transferTo(deviceId)`・`takeOver()`・`playOn(deviceId)`
- `sendControl`・`sendVolume`・`sendShuffle`・`sendRepeat`・`sendQueueAdd`・`sendQueueMove`・`sendQueueRemove`・`sendQueueClearFlow`
- `join(offerText)`・`cancelJoin()`・`forgetHousehold()`・`acceptStoreReset()`・`configure(settings)`
- `status()`・`devices()`・`remote()`・`on(listener)`（出来事）

## 決めたこと（下書き）

- 家には player として加わり、ほかの端末へは種類 `pc`・名前「YouTube Music (Chrome)」などとして知らせる（`profile` の命令）。ほかの端末は `pc`・`phone`・`api` しか知らないので、新しい種類（`browser` など）を使うと捨てられ、Mac などでは「wave API」のまま「ここで再生」の行き先に出ない
- 加入は、ページがログインの有無を知らせてから（タブが無い・読み込み中なら「先に music.youtube.com を開いて」と出す）。ログインしていればそのアカウントで照合し（ほかの端末で wave API 扱いにならない）、ログインしていないときだけ `wave-accountless-v1`（wave-api と同じ、ADR-0015）で照合する
- アカウントタグ（L-11）: ページの `DATASYNC_ID` から `AccountTag.of(householdId, DATASYNC_ID の "||" の前)` で計算する。ログインしていない（匿名の YouTube Music）・まだ知らせていない間は、家の最後の再生のタグを借りる（wave-api と同じ。匿名の YouTube Music は別のアカウントではなく、家のスピーカーとして鳴らす）
- 音量: PC と同じく、カーソルに YouTube Music の音量（0〜100）を載せ、遠隔の音量に従う（SPEC の「音量」の節の PC の扱い）
- シャッフル: YouTube Music のシャッフルは使わない（サーバーがキューを作り直し、足した曲が消える）。wave の決まり（Kotlin の `QueueSession.toggleShuffle`）どおり、背景が今の曲より後ろの並びを決めて（自分で足した曲は先頭にそのまま、残りを並べ替え、入れる前の並びを覚える。切ると覚えた並びに戻し、シャッフル中に足した曲は後ろに付く）`queue_order` で送る。カーソルには必ず `shuffle` を載せる（遠隔のシャッフル・繰り返しに従う端末として見える）
- キューの ID: 同じ曲には前の並びの ID を順に当て、並びから消えた曲も 60 秒は覚えておく（遠隔で足した曲・動かしている途中の知らせで ID と由来を失わない）
- 履歴: YouTube Music が自分で再生履歴を送るので、`historySent` は既定で true
- 鍵: 身元の署名鍵と受け取りの鍵（kx）は WebCrypto で書き出せない形で作り、CryptoKey のまま IndexedDB に置く
- 中継の生存確認: ブラウザの WebSocket は ping を送れないので、中継の ping（30 秒ごと）に任せる。背景が止まらないようにするのはページの Port の ping
- 背景が止まって起き直したとき、中継ではまだこの端末が再生中で（退いた印も無く）、つなぎ直したページが鳴っていると知らせてきたら、本人の操作を待たずに claim し直して書き続ける
- ページが1つも無い間は中継から離れ、引き取り（`adopt`）・遠隔操作を受けない。鳴らしていたタブが閉じたら一時停止のカーソルを置いて手元を捨てる
- 新しくつながった端末は、つながった直後は鎖を取り終えておらず名前の知らせを捨てることがあるので、3 秒後・10 秒後にもう一度知らせる（ブラウザの通しの確かめで、wave-api が拡張の名前を取りこぼすのを見つけた）
- 受け取った `login_grant`（ログインを渡す命令）は開いても使わず、どこにも置かない
- ライブラリの同期（ADR-0013）は扱わない（`library_get` を送らないので、中継も配らない）

## ほかの端末の側で要る変更（まだ入れていない）

- ログインなし（`wave-accountless-v1`）で加えると、加えた側（Android・Windows・Mac・iPhone）の完了の画面に「ログインを渡す」が出る。Mac・iPhone は加えた直後に種類を `api` と覚えるので、拡張が `pc` の `profile` を送るまで（つながるまで）「wave API」と出て「ここで再生」の行き先に出ない。拡張がページのログインで照合して加われば、どちらも起きない
- 拡張を「ブラウザ」として別の絵・名前で出したいなら、各アプリの知っている種類（`pc`・`phone`・`api`）に新しい種類を足す必要がある
