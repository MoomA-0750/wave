# PC の wave の口（同じ PC のほかのアプリ向け）

Windows 版の wave は、動いている間、同じ PC のほかのアプリ（visor のアプリ・Stream Deck・AutoHotkey など）向けに HTTP の口を `localhost` に開ける。
形は [wave-api](wave-api.md) と同じなので、つなぐ側はどちらにもつなげる。作りの理由は [decisions.md](decisions.md) の 2026-10-22「PC の wave に同じ PC のほかのアプリ向けの口を開ける」。

- 操作は、wave の画面で同じことをしたのと同じ手順で行う。ほかの端末で鳴っていればその端末への命令になり、曲を選べばその端末で鳴る（画面で曲を選んだときと同じ）
- ログインは PC の wave のものを使う。つなぐ側にログインや家の鍵を渡すことはない
- 設定は無い（wave が動いていれば開いている）

## つなぎ方

`%LOCALAPPDATA%\wave\local-api.json` を読む。

```json
{ "url": "http://localhost:8794", "token": "…", "pid": 1234, "exe": "C:\\…\\Wave.App.exe" }
```

- `url`・`token`: 口の場所とトークン。トークンは wave を起こすたびに作り直す。`Authorization: Bearer <token>` を付ける（`/v1/health` を除く）
- `pid`: 口を開けている wave。そのプロセスが無ければ古い（wave が落ちた）。wave を閉じると `url` は空、`token` は null、`pid` は 0 になる
- `exe`: wave の場所（閉じた後も残す）。wave が動いていなければ、これを起こすとつなげるようになる
- 番号は 8794 から、使われていれば順に 8803 まで試す
- このファイルは今のユーザーだけが読める場所にある。ブラウザのページからの呼び出し（`Origin` の付いたもの）は 403 で断る

## 口

[wave-api の口](wave-api.md#口) と同じ（`/v1/admin/*` は無い）。違いは次のとおり。

| | wave-api | PC の wave |
|---|---|---|
| `GET /v1/devices` の `self` | wave-api 自身（行き先に選べない） | この PC（行き先に選べる）。家の連携を使っていなければ、この PC だけ（ID は `this-pc`） |
| `POST /v1/transfer` | ほかの端末へ移す | この PC の ID なら、ほかの端末で鳴っている再生をこの PC に引き取る |
| `POST /v1/play` の `deviceId` | 行き先 | 見ない。行き先は画面で曲を選んだときと同じ（ほかの端末で鳴っていればその端末、無ければこの PC）。答えの `deviceId` は鳴らす前に決めた行き先 |
| `GET /v1/playback` の `joined` | wave-api が家に入っている | この PC の wave が家に入っている |
| `GET /v1/playback` の `volumePercent` | 鳴っている端末の音量 | 同じ（この PC の wave の音量か、映している PC の音量。スマホで鳴っていれば null） |
| `POST /v1/control` の `volume` | | スマホで鳴っている間は `409 not_sent`（画面の音量のつまみが灰色のときと同じ） |

### PC の wave だけにあるもの

どれも画面で同じことをしたのと同じ手順で行う。

| 口 | 中身 |
|---|---|
| `GET /v1/playback` | `track.likeStatus`（`like`・`dislike`・`indifferent`。読めていなければ null）が増える。`shuffle` は wave-api と違い null にならない（前の版の端末を映していれば false） |
| `POST /v1/control` | シャッフル・繰り返しは wave-api と同じ形。ほかの端末で鳴っていればその端末へ送る（前の版の端末なら `409 not_sent`） |
| `POST /v1/rate` | `{"likeStatus": "like"｜"indifferent", "videoId"?}`。曲を高く評価する・外す。`videoId` が無ければ今の曲。ログインが要る |
| `GET /v1/lyrics` | 今の曲の歌詞。`videoId`・`state`（`found`・`none`（無い）・`loading`・`failed`・`idle`（曲が無い））・`synced`・`source`・`lines`（`synced` のとき。`timeMs`・`text`）・`text`（時刻の無い本文） |
| `GET /v1/theme` | wave の色。`dark`（暗い表示か）・`accent`・`accentLight1`〜`3`・`accentDark1`〜`3`（Windows の強調色、`#RRGGBB`）。暗い地の文字は `accentLight2`、明るい地の文字は `accentDark1`（WinUI と同じ） |
| `GET /v1/search?q=&type=` | `type` は `song`（既定）・`live`（`track` の形）・`album`・`playlist`（`id`・`name`・`subtitle`・`artUrl`。アルバムの `id` は browseId）・`artist`（`id`・`name`・`subtitle`・`artUrl`） |
| `GET /v1/suggestions?mood=` | 棚に `items`（元の並びのまま。`type`（`track`・`playlist`・`album`・`artist`）・`id`（videoId・playlistId・browseId）・`title`・`subtitle`・`artUrl`・`isLive`）・`strapline`（見出しの上の小さな文字）・`albums` が増える。`tracks`・`playlists` は wave-api と同じ。`mood` に気分の札の `params` を渡すと、その札を選んだホーム |
| `GET /v1/moods` | ホームの気分の札（`title`・`params`） |
| `GET /v1/library/albums` | ライブラリのアルバム（`/v1/playlists` と同じ形。`id` は browseId） |
| `GET /v1/library/artists` | ライブラリのアーティスト（`id`・`name`・`subtitle`・`artUrl`） |
| `GET /v1/albums/{browseId}` | アルバム（`id`・`name`・`subtitle`・`artUrl`・`playlistId`・`tracks`）。`/tracks` を付けると曲だけ |
| `GET /v1/artists/{browseId}` | アーティスト（`id`・`name`・`artUrl`・`tracks`（人気の曲）・`allSongsPlaylistId`・`albumShelves`（`title`・`albums`）） |
| `POST /v1/play` | `artistBrowseId` も受ける（「すべての曲」のプレイリスト、無ければ人気の曲。`shuffle` も効く） |
| `GET /v1/events` | `event: queue`（`/v1/queue` と同じ形）も流す。つないだときは今の `playback` と `queue` を流す。見張りは1つで、つないでいる相手みなに配る |
| どの口でも `?hl=` | YouTube から届く文字（棚の見出し・曲名など）の言語（`ja`・`en` など）。その問い合わせだけで、wave の画面の言語は変えない。無ければ wave の表示言語 |

## 確かめ

- 自動テスト: `windows/Wave.Core.Tests` の `LocalApiTests`（トークン・ブラウザの拒否・応答の形・操作の検査・失敗の名前・SSE（キュー・見張りが1つ・閉じたときに失敗を出さない）・評価・歌詞・色・検索の種類・ライブラリ・アルバム・アーティスト・気分の札・言語・置き場のファイル）
- 手で: to-fu で wave を起こし、`local-api.json` のトークンで `/v1/playback` を読み、`/v1/control` の一時停止・再生が効く
