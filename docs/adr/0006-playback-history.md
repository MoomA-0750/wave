# 再生履歴は playback の統計を1回だけ、music.youtube.com に向けて送る

状態: 採用（2026-09-17、Fable審査点③の指摘と本人の決定を反映）

## 背景

YouTube Music の再生履歴（と、それに基づくおすすめ）に wave で聴いた曲を載せるには、`/player` の応答の
`playbackTracking.videostatsPlaybackUrl` に、ログイン中の cookie と `SAPISIDHASH` を付けて GET を送る必要がある。

- innertubex の `registerPlaybackWithSession(client, url, cpn, playlistId, session, currentMediaTime, final, format)` は、
  URL のクエリに `ver`・`c`・`cver`・`cpn`・`cmt`・`fmt`・`rt` などを足し、`Cookie`・`X-Goog-AuthUser`・`Authorization`（SAPISIDHASH、
  origin は music.youtube.com）を付けて送る。自動の再試行はしない。`SessionSnapshot` を渡すと、送信中にセッションが変われば止まる
- innertubex 自身の検査（`validatedStatsUrl`）が許すのは https・443・ホスト `s.youtube.com`・`www.youtube.com`・`music.youtube.com`、
  パス `/api/stats/playback`・`/api/stats/watchtime` の完全一致
- 匿名の WEB_REMIX `/player` で測ると、`videostatsPlaybackUrl` のホストは `s.youtube.com`（計画レビュー、2026-09-17）
- ADR-0003 のガードは、認証ヘッダを `https://music.youtube.com:443/youtubei/v1/` 以下にしか出さない。このままでは送れない
- `cpn`（クライアントの再生ごとの ID）は `/player` の要求にも統計 URL にも無い。アプリが作る

## 決定

1. **送るのは playback の統計だけ**。watchtime は送らない（本人の決定 F-3）。1回の再生につき1回、聴いた時間の累計が
   **30秒**（曲が30秒より短ければ曲の長さの半分、ただし5秒以上）に達したときに送る。
   - 再生1回は「Player の曲の遷移で始まり、次の遷移で終わる」。同じ項目の中で先頭へシークし直しても1回のまま。1曲リピートは繰り返しごとに1回
   - **聴いた時間は位置ではなく、単調時計（`SystemClock.elapsedRealtime`）で `isPlaying` の区間だけを足す**（シークで一気に達しない）。
     `onIsPlayingChanged(true)` で残り時間の待ちを始め、`false`・曲の遷移・キューの消去で取り消す
   - 曲の長さは `player.duration`、分からなければ曲のメタデータ、それも無ければ30秒
2. **宛先はホストを `music.youtube.com` に書き換える**（本人の決定 G-4）。パスとクエリはそのまま。認証ヘッダの許可ルートに
   `music.youtube.com` + `/api/stats/playback`（**完全一致**）を足す。`AuthRoute` に完全一致のフラグを足し、既存の
   `/youtubei/v1/`（前方一致）と区別する。パスの `.`・`..`・エンコードされた区切りの拒否は今のまま
   - 判定（決定 5 の件数で見る）:
     - 2xx かつ HTML でない → 「送れた」。履歴に無い曲を30秒以上聴き、数分以内に公式アプリの履歴を見る
     - 3xx・404・2xx の HTML → 「music.youtube.com は受け付けない」と確定し、`s.youtube.com` + `/api/stats/playback`（完全一致）へ切り替える。この ADR を更新する
     - 2xx なのに履歴に載らない → 送り方（タイミング・playback だけ）の問題の可能性が高い。**s.youtube.com へは切り替えず、先に送り方を見直す**（再生の開始時に送る、watchtime も送る。本人の決定 2026-09-17）
3. **統計 URL の出どころは `StreamResolver` の `/player` 応答だけ**。解決結果（`ResolvedStream`）に、統計 URL と
   **`/player` を呼んだ WaveSession の世代**を一緒に持つ（呼ぶ前後で世代が変わっていたら統計 URL は持たない）。ログアウト・切り替えで cache ごと消す
   - 取り出すのは「曲の遷移後、最初に `isPlaying` になったとき」の1回だけ（DataSource が開いて解決が済んでいる）。cpn と一緒に「今の再生」の記録として持ち、以後 cache を見ない。
     無ければその再生は送らない。**統計のために `/player` を呼ばない**
   - 書き換えは文字列置換ではなく URL を組み立て直して行う。書き換え後の URL が https・443・`music.youtube.com`・パス完全一致でなければ送らない（応答の値を信用しない）。
     これはガード違反のログを汚さないための前段で、本命はエンジン層のガード（ADR-0003）
   - `playlistId`（`list=`）は送らない
   - **Location ヘッダ（3xx の行き先）は使わない**。共有の HttpClient（`followRedirects=false`）を通ることを、実装時に innertubex のバイトコードで確かめる
4. **cpn は再生1回ごとにアプリで作る**（16文字、`A-Za-z0-9-_`、暗号学的乱数）
5. **統計の応答は `WaveSession` の失効・疑いの分類に通さない**。統計の 401・403・3xx でセッションを疑い・失効にしない
   （失効の検知は、毎回の再生の `/player` が分類を通るので落ちない）。失敗しても送り直さない。
   結果は次の件数だけをログに出す: 形が違って送らなかった・世代が違って送らなかった・送った（status の数値と、応答が HTML だったか）・ガード違反と例外
6. **送信は WaveSession の中の、分類しない入口で行う**。`call` と同じ前段（今のログインが無い・反映前なら送らない）を持ち、
   世代のスコープで実行する（切り替え・ログアウトで取り消される）。送る直前に「統計 URL を取った世代 == 今の世代」を確かめる。
   アカウントを切り替えた直後に、前のアカウントの再生の識別子を新しいアカウントの cookie で送らないため（cache の消去と世代の切り替えは別のスレッドで進むので、消去だけでは足りない）。
   innertubex の `SessionSnapshot` は二重の保険として渡す
7. **設定で止められる**（既定は送る）。設定は認証情報とは別の DataStore に真偽1つ。設定が決めるのは送るかどうかだけで、
   統計 URL は止めている間も解決結果と一緒に持つ（同じ構造体に署名付きの音声 URL が既にあり、機微さは同等以下。本人の決定 2026-09-17）

## Considered Options

### s.youtube.com にそのまま送る — 当面は採らない

YouTube のウェブと同じ宛先で、履歴に載ることは確実に近い。ただし、認証ヘッダを出すホストが1つ増える。
cookie のドメインは `.youtube.com` なので、ブラウザでは s.youtube.com にも同じ cookie が送られているが、
wave のガードは「ホストを増やさない」を原則にしてきた（ADR-0003）。書き換えで履歴に載るなら、こちらの差分の方が小さい。

### watchtime も送る — 採らない

おすすめへの影響は playback より細かいが、送信の回数と経路が増える。履歴に載るかどうかには playback が効くと見込む
（実機で確認する。載らなければ見直す）。

### 送らない — 採らない

本人の決定（F-3）。wave で聴いた曲が履歴とおすすめに反映されないと、公式アプリとの行き来で不便になる。

## Consequences

- ADR-0003 の許可ルートが「前方一致1行」から「前方一致1行 + 完全一致1行」になる。`AuthRoute` に完全一致のフラグを足し、ADR-0003 の決定文とガードのコメントも更新する
- 既存の抜けとして記録: `X-Goog-PageId`（dataSyncId）は `AUTH_HEADERS` に無い。今回は宛先が同じホストなので影響しない
- `ResolvedStreamCache` が統計 URL（署名は無いが、`docid`・`ei`・`plid` などの再生の識別子を含む）を持つ。ログに URL・クエリを出さない
- 履歴に載るかは Pixel 3 の実アカウントで確かめる（本人の決定 F-4）
