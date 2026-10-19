# wave-api（ほかのアプリ向けの API）

home-dashboard などほかのアプリから、wave で再生中の曲を読み、操作し、曲やプレイリストを選んで鳴らすための HTTP の口。
作りの理由は [ADR-0015](adr/0015-wave-api.md)。

- `wave-api` は中継と同じ機械で常駐し、家の端末の1つ（「wave API」、種類 `api`）として中継につながる。音は出さない
- 家の再生（どの端末で鳴っていても）を読み、鳴っている端末へ遠隔操作を送る。鳴らすときは、キューを作って行き先の端末に引き取ってもらう
- ログインが無い間は、今の曲・キュー・端末・操作・端末の切り替えだけ。プレイリスト・検索・おすすめ・鳴らす・キューに足すは、家の端末からログインを渡すと使える

## 動かす

配布物（`./gradlew :api:installDist` の `api/build/install/api`）を Docker のコンテナで動かす（例は `relay/deploy/compose.yaml` の `wave-api`）。

| 環境変数 | 値 |
|---|---|
| `WAVE_RELAY_URL` | 中継の WebSocket（同じ compose なら `ws://wave-relay-v2:8791`） |
| `WAVE_RELAY_HOST` | 中継の `WAVE_RELAY_HOST` と同じ値（接続の署名の対象に入る） |
| `WAVE_API_DATA` | 身元の鍵・家の記録・ログイン・トークンの置き場（既定 `/data`、権限 700/600） |
| `WAVE_API_BIND` / `WAVE_API_PORT` | 待ち受け（既定 `127.0.0.1:8793`）。コンテナの外には 127.0.0.1 にだけ出す |

`WAVE_API_DATA` の中身（`keys.json`・`client.json`・`login.json`・`token`）は、家の再生の状態と YouTube のログインを読める鍵なので、ほかの人に渡さない。

## 家に加える

1. 家の端末（今は Android。Windows・Mac・iPhone も対応中）の「端末の連携」で「端末を加える」を押し、QR の文字（`wave-pair:…`）を出す
2. 中継の機械で `docker compose exec wave-api /app/bin/api pair '<wave-pair:…>'` を打つ。6桁が出る
3. 家の端末にその6桁を入れる。`api pair` が「家に加わりました」と出したら完了

`wave-api` はログインを持たないので、加入のアカウントの照合は「ログインなしの印」で通す（ADR-0015）。守りは6桁の照合。

## ログインを渡す

家の端末の「端末の連携」の一覧で「wave API」の「ログインを渡す」を押す（本人が押したときだけ送る）。ログインは `wave-api` の受け取りの鍵にだけ包んで送り、`login.json` に置く。
家の端末の一覧から「wave API」を外すと、`wave-api` は家の記録とログインを消す（身元の鍵とトークンは残すので、加え直せる）。

## 口

`Authorization: Bearer <トークン>` が要る（`/v1/health` を除く）。トークンは `docker compose exec wave-api /app/bin/api token` で出す。応答は JSON。失敗は `{"error": "<名前>"}`。

| 口 | 中身 |
|---|---|
| `GET /v1/health` | `{"ok": true}` |
| `GET /v1/playback` | `joined`・`loggedIn`・`isPlaying`・`repeat`（`off`・`track`・`context`）・`track`（`videoId`・`title`・`artist`・`album`・`artUrl`・`durationMs`・`isLive`。`artUrl` は大きく出せる大きさ（googleusercontent は `=w544-h544`、動画の絵は `hqdefault.jpg`）。ほかの口の `artUrl` は一覧用の小さいまま）・`progressMs`・`observedAt`（ISO 8601。ここから位置を進める）・`device`（`id`・`name`・`kind`・`online`・`active`）・`volumePercent` |
| `GET /v1/queue` | 今の曲の後の曲（`track` と同じ形の配列） |
| `GET /v1/devices` | 家の端末（`id`・`name`・`kind`・`online`・`active`・`self`） |
| `POST /v1/control` | `{"action": "play"｜"pause"｜"next"｜"previous"｜"seek"｜"volume", "positionMs"?, "volumePercent"?}`。シャッフル・繰り返しは連携の取り決めに無いので `400 unsupported` |
| `POST /v1/transfer` | `{"deviceId"}`。その端末に再生を移す |
| `POST /v1/play` | `{"videoId"? ｜ "playlistId"? ｜ "albumBrowseId"?, "startVideoId"?, "shuffle"?, "deviceId"?}`。曲だけならその曲からのラジオ。行き先は `deviceId`、無ければ家の最後の再生の端末（中継につながっているとき）。無ければ `409 no_device` |
| `POST /v1/queue` | `{"videoIds": [...], "next": false}`。鳴っている端末のキューに足す（`next` なら今の曲の直後） |
| `POST /v1/queue/jump` | `{"index": n, "videoId"?}`。`GET /v1/queue` の n 番目（0 が次の曲）へ飛ぶ。`videoId` も付けると、n 番目がその曲のときだけ飛ぶ（読んだ後にキューが動いていたら `404 not_found`）。`videoId` だけなら後ろの最初のその曲。鳴らしている端末に、選んだ曲からの同じキューを引き取り直してもらう（端末でほかの端末を映している間にキューの曲を選んだときと同じ）。ログインは要らない。応答は `/v1/play` と同じ |
| `GET /v1/search?q=` | 曲を探す |
| `GET /v1/playlists` | ライブラリのプレイリスト（`id`・`name`・`subtitle`・`artUrl`）。高く評価した曲は、この一覧に無くても `/v1/playlists/LM/tracks` で読める |
| `GET /v1/playlists/{id}/tracks` | プレイリストの曲（400 曲まで） |
| `GET /v1/suggestions` | ホームの棚（`title`・`tracks`・`playlists`） |
| `GET /v1/events` | SSE。家の再生が変わるたびに `event: playback`（`/v1/playback` と同じ形）を流す。15 秒ごとに空の行 |
| `POST /v1/admin/pair`・`GET /v1/admin/pair` | 加入（`api pair` が使う） |

状態の名前: `token_required`（401）・`login_required`（401、ログインを渡していない・切れた）・`not_sent`（409、鳴っている端末が無い・別のアカウント・まだ家の状態を受けていない）・`no_device`（409）・`bad_request`・`unsupported`（400）・`not_found`（404）・それ以外の通信の失敗（502）。

## 注意

- 家に加わった直後は、今の再生が読めないことがある（加わる前の状態は、加わる前の端末にだけ包まれている）。どこかの端末で再生・一時停止すると読める
- 操作は、鳴っている端末が同じアカウントで中継につながっているときだけ届く（遠隔操作と同じ条件。5分を超えて遅れた命令には従わない）
