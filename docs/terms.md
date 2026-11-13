# 画面の文言の正の表記

wave の画面の文言（英語・日本語）を、全部のプラットフォームでそろえるための決めごと。文言を書くとき・直すときはここに合わせる。ここに無い語は、まず規則（1章）に合わせ、迷ったら表に足す。

- 対象: Android（`androidApp/src/*/res/values*/`。時計は `strings_watch.xml`、ホーム画面のウィジェットは `strings_widget.xml`）、Windows（`windows/Wave.App` の `L.T`）、Mac・iPhone（`mac/Sources` の `L.t`、ウィジェットは `mac/Resources/Widget/*.lproj` と `WidgetShared.swift`）、Linux GTK（`linux/wave-gtk`・`linux/wave-desktop` の `lang::t`）、Linux QML（`linux/wave-qt/qml` の `Style.t` と `linux/wave-qt/src` の `lang::t`）
- 決め方: YouTube Music の公式の画面にある概念は、YouTube Music の英語版・日本語版の表記に合わせる（表で「(YT)」を付けたもの）。それ以外は、使っているプラットフォームが多い表記。同数なら Android。規則（1章）に当てはまるものは、個々の多数より規則を優先する
- 表の「今の揺れ」は、正の表記と違う今の文言だけを「プラットフォーム: 英語 / 日本語」で並べる。正と同じものは書かない。「揺れなし」は全部のプラットフォームがそろっているもの
- 調べた時点: 2026-11-13（ios-app ブランチ、308a2606）

## 1. 規則

### 1.1 英語の大文字

- 題・ボタン・メニュー・タブ・設定の項目名・知らせは、文頭だけ大文字（sentence case）。今も 9 割ほどがこれ
  - 例: `Add to queue`、`Save to library`、`Liked music`、`Picture in picture`、`New release notifications`
- 固有名はそのまま: YouTube Music、YouTube、Discord、Claude、Claude Code、Premium、Tailscale、Wi-Fi、QR、RSS、PC・Mac・iPhone・Windows・macOS、Application ID（Discord の画面の語）
- wave は常に小文字（文頭でも `wave`）
- 「ラベル: 値」の値は、値の先頭を大文字にしてよい（`Repeat: Off`、`Shuffle: On`、`Premium audio: Available`）
- 例外: Mac・iPhone の App Intents（ショートカットの操作名・ウィジェットの設定の項目。`Next Track`、`Play Playlist`、`Choose What to Play` など）は Apple の決まりに合わせて Title Case のままにする
- 生放送の印だけは大文字 `LIVE`（日本語は「ライブ」）

### 1.2 英語の記号

- アポストロフィは真っすぐな `'`（`Couldn't`、`isn't`、`you're`）。今は `'` と `’` がほぼ半々で、同じ画面の中でも混ざっている。Android の多数に合わせて `'` にそろえる
- 引用符（名前を囲む）は曲がった `“ ”`（`Added to “Road trip”`）。今の多数
- 三点は 1 文字の `…`（`...` は使わない）。今も全部そう
- 区切りの中黒は前後に空白を入れた ` • `（`Playlist • Auto`、`Album • 2024`）
- 並べるときは `A, B and C`（オックスフォード・カンマは付けない。今の多数）
- ショートカットは文言の後ろに空白と半角の括弧: `Full screen (F11)`、`Ask Claude (Ctrl+J)`、Mac は `(⌘J)`

### 1.3 終わりの句読点（英語のピリオド・日本語の「。」）

- ボタン・見出し・項目名・状態・知らせ（トースト・下の帯の一言）・エラー・設定の補足には付けない。2 文になるときも、文の区切りにだけ付けて最後には付けない
  - 例: `Timed out. Please try again` / 「時間切れになりました。もう一度やり直してください」
- ダイアログの本文と、空の画面・案内の画面の説明文（読ませる文）には付ける。1 文でも付ける
  - 例: `This removes your login saved on this PC.` / 「この PC に保存したログイン情報を消します。」、`Log in to see your YouTube Music playlists, artists and albums here.`
- 英語と日本語で付け方をそろえる（片方だけ付けない）
- 問いは英語 `?`、日本語は全角の「？」（「ログアウトしますか？」）。今も全部そう

### 1.4 「…」の付け方

- 進んでいる途中の状態に付ける: `Loading…` / 「読み込んでいます…」、`Checking…` / 「確かめています…」、`Connecting to the relay…` / 「中継に接続しています…」
- ボタン・メニューの項目には付けない（押した先でもう一度入力するものでも付けない。今の多数）: `New playlist` / 「新しいプレイリスト」
- 例外: 端末のほかのアプリへ渡す共有のメニュー `Share with…` / 「ほかのアプリで共有…」は今のまま
- 英語と日本語で付け方をそろえる

### 1.5 数の書き方

- 曲数: 英語は単数・複数を分ける `1 song` / `3 songs`、日本語は数と単位のあいだに半角の空白 `3 曲`。上限を超えたら `100+ songs` / `100 曲以上`。Windows の `L.Songs`、Mac の `L.songs`、Linux の `lang::songs`・`Style.songs` と同じ形
  - Android の `%d曲`・`%1$d曲`・`残り %1$d曲`・`%2$d曲中%1$d曲` は空白が無い。`%d 曲` にする
- ほかの数（端末・件数）も同じ: `2 devices` / `2 台`、`5 results` / `5 件`
- 文の中に決まった数として入るもの（`6桁の数字`、`2分で無効`、`3曲以上のアーティスト`）は空白を入れない（今の多数）
- 進み具合: `3 of 10` / `10 曲中 3 曲`
- 割合: `80%`（空白なし）

### 1.6 時間の書き方

- 曲・エピソードの長さ（行の右）: `3:05`、`1:02:03`（言語に依らない）
- 一覧の合計の長さ: 1 時間以上は `1 hr 5 min` / `1 時間 5 分`、1 時間未満は `13 min 5 sec` / `13 分 5 秒`（Windows・Mac・Android の今の形）
- 曲数と合計の長さを並べるとき: 英語は `3 songs, 13 min 5 sec`、日本語は読点 `3 曲、13 分 5 秒`
  - 今の揺れ: Windows・Mac・Android は日本語でも半角の `, `（「3 曲, 13 分 5 秒」）。Linux は読点だが秒を出さず分を切り上げる（「3 曲、13 分」/ `3 songs, 13 min`）。GTK のアーティストの「高く評価した曲」の見出し（`linux/wave-gtk/src/ui/pages.rs` の `collection::count_and_length`）は英語の画面でも日本語が出る
- 日付: `2026/10/13`（Linux の今の形。ほかのプラットフォームは YouTube から届いた文字をそのまま出す）
- 「〜秒」の残り: `(5 s)` / `（5秒）`（Android の今の形）

### 1.7 日本語の記号と空白

- 日本語と英字・数字のあいだには半角の空白を入れる（「Claude に聞く」「YouTube Music で開く」「この PC」）。今も全部そう
- 差し込む名前（`{device}`・`%1$s` など）の後ろに助詞が続くときも空白を入れる: 「{device} で再生中」「{name} の新作」
  - 今の揺れ: 「%1$sで再生中」「{remote.DeviceLabel}で一時停止中」「{LabelOf(...)}から引き継ぎました」（Android・Windows・Mac・iPhone）
  - Mac・iPhone の App Intents（`${applicationName}で…`）は Apple の書き方のまま
- 括弧は全角の「（）」。ショートカットだけ半角（1.2）
- 引用は「」: 曲名・プレイリスト名・端末名・画面やボタンの名前を文の中で言うとき（「「Road trip」に追加しました」「設定の「Claude」でつないでください」）。アーティスト名・番組名を「〜の新作」のように名詞の頭に置くときは囲まない
  - 今の揺れ: Android の `%1$s を外しました`・`%1$s にログインを渡しました` は「」が無い（Windows・Mac は付いている）
- 並べるときは「・」（「プレイリスト・アルバム」「再生・一時停止」）。「と」「/」は使わない
- 「ラベル: 値」は半角のコロンと空白（「中継: 接続中」「歌詞の提供: LRCLIB」）
- 空白は普通の空白だけ。QML の「wave が動いている間」の改行しない空白、Android の `Wi‑Fi`（U+2011）は普通の `wave が`・`Wi-Fi` にする

### 1.8 日本語の言い回し

- 消す操作
  - 一覧（プレイリスト・キュー・高く評価した曲・履歴・ライブラリ）から項目を除く: 「〜から削除」（YouTube Music の日本語版と同じ）。英語は `Remove from …`
  - 端末を連携から除く: 「外す」（`Remove`）。端末は消えないので「削除」にしない
  - 本当に無くなるもの（ダウンロード・会話・保存したログイン情報）: 「削除」。英語は `Delete`（ダウンロードだけは YouTube Music に合わせて `Remove download`）
  - 入力欄・検索の履歴・キューを空にする: 「消去」（`Clear`）
  - 今の揺れ: Android の「このプレイリストから外す」「高く評価した曲から外す」「プレイリストから外しました」、Windows・Mac の曲の「外せませんでした」、全部の「会話を消す」、Android の「消す」「すべて消す」
- 加える操作: 曲・番組を一覧へ入れるのは「追加」（「キューに追加」「プレイリストに保存」）。「足す」は使わない。端末が連携に入るのは「加わる」、入れる側の操作の説明は「加える」、ボタンは「端末を追加」
- 作る操作: ボタン・知らせ・エラーとも「作成」（「プレイリストを作成」「「{title}」を作成しました」「プレイリストを作成できませんでした」）
- 変える操作: 「変更」（「登録を変更できませんでした」「評価を変更できませんでした」）
- 確かめる: 文の中は「確かめる」（「確かめています…」「アカウントを確かめられませんでした」）。今の多数。ボタンの `Confirm` だけ「確認」
- つなぐ: ボタンと短い状態は漢語（「接続」「接続中」「切断」「接続を外す」）、文の中は「つなぐ」（「Claude につないでいません」「つながりませんでした」）
- できなかったとき: 「〜できませんでした」「〜（ら）れませんでした」（`Couldn't …`）。「〜に失敗しました」は使わない
- 頼む文: 「〜してください」。「〜しましょう」は使わない
- 端末: 自分の機器は全部「端末」（「デバイス」は使わない）。電話は「スマホ」、時計は「時計」、パソコンは「PC」（Mac・iPhone はその名前）
- ログイン: 英語は `Log in` / `Log out`（名詞は `login`）、日本語は「ログイン」「ログアウト」。`Sign in` / `sign-in` は使わない（今は時計・Android のウィジェット・Mac の「ログインを渡す」にある）
- やめる: ダイアログの取り消しは「キャンセル」（`Cancel`）。今は Android・時計・Mac・iPhone・GTK・QML の一部に「やめる」がある

### 1.9 設定の「画面の言語」

- 項目名: `App language` / 「画面の言語」（全部そろっている）。見出しは `Language` / 「言語」
- 選び肢は 3 つ: `Use device language` / 「端末に合わせる」、`日本語`、`English`。言語名はその言語のまま書き、訳さない
- 今の揺れ: Windows `Use Windows language` / 「Windows に合わせる」、Mac `Use macOS language` / 「macOS に合わせる」、iPhone `Use iPhone language` / 「iPhone に合わせる」、GTK `System` / 「端末に合わせる」、QML `Use system language` / 「端末に合わせる」
- 補足: `Takes effect the next time you open wave` / 「次の起動から反映されます」（GTK・QML は「次の起動から変わります」）

## 2. 概念ごとの表

### 下の帯（再生の操作）

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| 再生 (YT) | Play | 再生 | 揺れなし |
| 一時停止 (YT) | Pause | 一時停止 | 揺れなし |
| 再生・一時停止（1つのボタン） | Play/Pause | 再生・一時停止 | Windows・Mac・GTK・QML: Play/Pause / 再生/一時停止 |
| 次の曲へ (YT) | Next | 次へ | Android: Next / 次の曲 |
| 前の曲へ (YT) | Previous | 前へ | Android: Previous / 前の曲 |
| シャッフル (YT) | Shuffle | シャッフル | 揺れなし |
| シャッフル再生（一覧の頭のボタン） | Shuffle play | シャッフル再生 | 揺れなし |
| リピート (YT) | Repeat | リピート | 揺れなし |
| 高く評価（ボタン） (YT) | Like | 高く評価 | 揺れなし |
| 高く評価済み（状態） | Liked | 高く評価済み | 揺れなし |
| 音量 | Volume | 音量 | 揺れなし |
| ミュート | Mute | ミュート | 揺れなし |
| 何も再生していない | Nothing playing | 何も再生していません | 時計・Android ウィジェット: Nothing playing / 再生していません |
| 再生中（見出し） | Now playing | 再生中 | 揺れなし |
| 全画面 | Full screen | 全画面 | iPhone: Full screen / 全画面で見る |
| 全画面を終える | Exit full screen | 全画面を終える | Mac・GTK・QML: Exit full screen / 全画面をやめる |
| 小窓（ピクチャー・イン・ピクチャー） | Picture in picture | 小窓で見る | Windows: Picture in picture / 小窓で見る・元に戻す |
| 小窓を閉じる | Close picture in picture | 小窓を閉じる | iPhone: Stop picture in picture / 小窓をやめる |
| 映像を大きく見る | Expand video | 映像を大きく見る | QML: Bigger video / 映像を大きく見る |
| 映像だけを窓いっぱいに | Video only | 映像だけを窓いっぱいに | QML: Fill the window with video / 映像だけを窓いっぱいに |
| 映像だけをやめる | Exit video only (Esc) | 映像だけをやめる (Esc) | Mac: Close video (Esc) / 映像だけをやめる (Esc) |
| ライブの印 | LIVE | ライブ | Android: LIVE / LIVE |

### 再生中の画面・キュー

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| キュー (YT) | Queue | キュー | Android: Queue / 再生キュー |
| キュー（曲数つき） | Queue ({n} songs) | キュー（{n} 曲） | Android: Queue (%1$d songs) / 再生キュー（%1$d曲） |
| キューを開く | Open queue | キューを開く | 揺れなし |
| 次の1曲の見出し (YT) | Up next | 次の曲 | Windows・Mac・GTK: Next up / 次の曲<br>※ YouTube Music の「次の曲」タブと同じ語 |
| キューの区切り: 自分で入れた曲 | Next in queue | キューの次の曲 | Android: Next in queue / 次の曲 |
| キューの区切り: 元の一覧・ラジオから続く曲 (YT) | Up next | 次に流れる曲 | Android・Windows・Mac・iPhone・GTK・QML: Next up / 次に流れる曲 |
| 続く曲を消す | Clear up next | 次に流れる曲を消去 | Android: Clear next up / 次に流れる曲を消去 |
| 次に流れる曲が無い | Nothing up next | 次に流れる曲はありません | Android: Nothing comes next / 次の曲はありません |
| キューを消去 | Clear queue | キューを消去 | 揺れなし |
| キューから削除 (YT) | Remove from queue | キューから削除 | 揺れなし |
| キューが空 | Your queue is empty | キューは空です | 揺れなし |
| キューが空のときの案内 | Play something and upcoming songs will show up here | 曲を再生すると、ここに次の曲が並びます | QML: Play something and upcoming songs will show up here / 曲を再生すると、次に流れる曲がここに並びます |
| キューをプレイリストとして保存 | Save queue as playlist | キューをプレイリストとして保存 | iPhone: Save queue / キューを保存 |
| 保存できる曲が無い | No songs in the queue to save | 保存できる曲がキューにありません | Mac・GTK: There are no songs in the queue to save / 保存できる曲がキューにありません |
| 履歴（キューの隣のタブ） | History | 履歴 | 揺れなし |
| 履歴が空 | Nothing played yet | まだ流れた曲はありません | QML: Nothing played yet / 履歴はありません |
| 履歴が空のときの案内 | Songs you've played will show up here | 流れた曲は、ここに新しい順に並びます | QML: Songs you've played will show up here / 曲を聴くと、ここに並びます |
| 履歴から削除 (YT) | Remove from history | 履歴から削除 | 揺れなし |
| 〜で再生中（ほかの端末） | Playing on {device} | {device} で再生中 | Mac・iPhone: Playing on \(remote.deviceLabel) / \(remote.deviceLabel)で再生中<br>Android: Playing on %1$s / %1$sで再生中<br>Windows: Playing on {remote.DeviceLabel} / {remote.DeviceLabel}で再生中 |
| 〜で一時停止中 | Paused on {device} | {device} で一時停止中 | Mac・iPhone: Paused on \(remote.deviceLabel) / \(remote.deviceLabel)で一時停止中<br>Windows: Paused on {remote.DeviceLabel} / {remote.DeviceLabel}で一時停止中 |

### 歌詞

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| 歌詞 (YT) | Lyrics | 歌詞 | 揺れなし |
| 歌詞を探している | Looking for lyrics… | 歌詞を探しています… | 揺れなし |
| 歌詞が見つからない | No lyrics found for this song | この曲の歌詞は見つかりませんでした | 揺れなし |
| 歌詞を読み込めない | Couldn't load lyrics | 歌詞を読み込めませんでした | 揺れなし |
| 曲が無いとき | Play a song to see its lyrics here | 曲を再生すると、ここに歌詞が出ます | 揺れなし |
| ライブ配信には歌詞が無い | Live streams don't have lyrics | ライブ配信には歌詞がありません | 揺れなし |
| 歌詞の提供元 | Lyrics provided by {source} | 歌詞の提供: {source} | 揺れなし |
| 読み直す | Retry | もう一度 | Windows・Mac・GTK: Try again / もう一度読み込む<br>Android・時計: Try again / もう一度<br>QML: Reload / もう一度読み込む |

### 曲のメニュー

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| メニューを開く | More | その他 | Android: More options / その他<br>Android: More options / メニュー |
| 次に再生 (YT) | Play next | 次に再生 | 揺れなし |
| キューに追加 (YT) | Add to queue | キューに追加 | 揺れなし |
| ラジオを開始 (YT) | Start radio | ラジオを開始 | Android・Windows・Mac・iPhone・GTK: Go to song radio / 曲のラジオを再生<br>QML: Start radio / ラジオを再生 |
| プレイリストに保存 (YT) | Save to playlist | プレイリストに保存 | Android・Windows・Mac・iPhone・GTK・QML: Add to playlist / プレイリストに追加<br>※ 今は全部が Add to playlist |
| このプレイリストに追加（候補の行） | Add to this playlist | このプレイリストに追加 | 揺れなし |
| このプレイリストから削除 (YT) | Remove from this playlist | このプレイリストから削除 | Android: Remove from this playlist / このプレイリストから外す |
| 高く評価した曲に追加 (YT) | Add to liked music | 高く評価した曲に追加 | Android・Windows・Mac・iPhone・GTK・QML: Add to Liked Songs / 高く評価した曲に追加 |
| 高く評価した曲から削除 (YT) | Remove from liked music | 高く評価した曲から削除 | Android・Windows・Mac・iPhone・GTK・QML: Remove from Liked Songs / 高く評価した曲から削除<br>Android: Remove from Liked Songs / 高く評価した曲から外す |
| 評価を外す | Remove like | 評価を外す | 揺れなし |
| ライブラリに保存 (YT) | Save to library | ライブラリに保存 | Android・iPhone: Save / ライブラリに保存 |
| ダウンロード (YT) | Download | ダウンロード | 揺れなし |
| ダウンロードを削除 (YT) | Remove download | ダウンロードを削除 | 揺れなし |
| アルバムへ移動 (YT) | Go to album | アルバムへ移動 | 揺れなし |
| アーティストへ移動 (YT) | Go to artist | アーティストへ移動 | Android: Go to artist / アーティストを選ぶ |
| アーティストへ移動（複数のとき） | Go to artist: {name} | アーティストへ移動: {name} | 揺れなし |
| 曲の画面を開く | Go to song | 曲の画面を開く | Android・Mac・iPhone: Open song / 曲の画面を開く |
| 共有 (YT) | Share | 共有 | 揺れなし |
| ほかのアプリで共有 | Share with… | ほかのアプリで共有… | 揺れなし |
| 曲のリンクをコピー | Copy song link | 曲のリンクをコピー | 揺れなし |
| リンクをコピーした | Link copied | リンクをコピーしました | 揺れなし |
| YouTube Music で開く | Open in YouTube Music | YouTube Music で開く | 揺れなし |
| 次に再生した（知らせ） | {title} will play next | 「{title}」を次に再生します | GTK: Will play next / 次に再生します<br>QML: Playing next / 次に再生します |
| 何曲かを次に再生した（知らせ） | {n} songs will play next | {n} 曲を次に再生します | Windows: {L.Songs(songs.Count)} will play next / {songs.Count} 曲を次に再生します |
| キューに追加した（知らせ） | Added {title} to queue | 「{title}」をキューに追加しました | GTK・QML: Added to queue / キューに追加しました |
| 何曲かをキューに追加した（知らせ） | Added {n} songs to queue | {n} 曲をキューに追加しました | Windows: Added {L.Songs(songs.Count)} to queue / {songs.Count} 曲をキューに追加しました |
| 高く評価した曲に追加した | Added to liked music | 高く評価した曲に追加しました | Android・Windows・Mac・GTK・QML: Added to Liked Songs / 高く評価した曲に追加しました |
| 高く評価した曲から削除した | Removed from liked music | 高く評価した曲から削除しました | Android・Windows・Mac・GTK・QML: Removed from Liked Songs / 高く評価した曲から削除しました |
| プレイリストに追加した | Added to {playlist} | 「{playlist}」に追加しました | 揺れなし |
| もう入っている | Already in {playlist} | 「{playlist}」にはもう入っています | 揺れなし |
| プレイリストから削除した | Removed from {playlist} | 「{playlist}」から削除しました | Android: Removed from %1$s / 「%1$s」から外しました<br>Windows: Removed from {playlist.Title} / 「{playlist.Title}」から外しました<br>Mac: Removed from \(playlist.title) / 「\(playlist.title)」から外しました |
| プレイリストから削除した（名前なし） | Removed from playlist | プレイリストから削除しました | Android: Removed from playlist / プレイリストから外しました |
| 追加できない | Couldn't add it | 追加できませんでした | Android: Couldn't add the song / 追加できませんでした |
| 削除できない（曲） | Couldn't remove it | 削除できませんでした | Windows・Mac: Couldn't remove it / 外せませんでした<br>Android: Couldn't remove the song / 外せませんでした |
| 重複を確かめられない | Couldn't check for duplicates, so it wasn't added | 重複を確かめられなかったので、追加しませんでした | GTK: Couldn't check for duplicates, so it wasn't added / プレイリストを確かめられなかったので、追加しませんでした<br>QML: Couldn't check for duplicates, so it wasn't added / プレイリストを読み切れなかったので、追加しませんでした |
| 評価を変更できない | Couldn't update your likes | 評価を変更できませんでした | Windows・Mac: Couldn't update your likes / 高評価を変更できませんでした<br>GTK・QML: Couldn't update your likes / 評価を変えられませんでした<br>Android: Couldn't update like / 評価を変更できませんでした |
| 評価を読み直す | Reload like status | 評価を読み直す | 揺れなし |

### ライブラリ

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| ライブラリ（タブ・見出し） (YT) | Library | ライブラリ | Android・Windows・Mac・GTK・QML: Your Library / マイライブラリ<br>※ 今は Your Library / マイライブラリ（Spotify の言い方） |
| ライブラリを検索 | Search library | ライブラリを検索 | Windows・GTK: Search library / マイライブラリを検索<br>Android: Search Your Library / マイライブラリを検索<br>Mac: Search in Your Library / マイライブラリを検索<br>iPhone: Search in Your Library / ライブラリを検索<br>QML: Search your library / マイライブラリを検索 |
| 高く評価した曲 (YT) | Liked music | 高く評価した曲 | Android・Windows・Mac・iPhone・GTK・QML: Liked Songs / 高く評価した曲<br>Android: Liked songs / 高く評価した曲<br>※ 今は Liked Songs（Spotify の言い方） |
| プレイリスト (YT) | Playlists | プレイリスト | 揺れなし |
| アルバム (YT) | Albums | アルバム | 揺れなし |
| アーティスト (YT) | Artists | アーティスト | 揺れなし |
| 曲 (YT) | Songs | 曲 | 揺れなし |
| 動画 (YT) | Videos | 動画 | 揺れなし |
| ポッドキャスト (YT) | Podcasts | ポッドキャスト | 揺れなし |
| 最近（並べ替え・絞り込み） | Recents | 最近 | 揺れなし |
| 最近追加した順 | Recently added | 最近追加した順 | Android: Recently added / 追加順 |
| アルファベット順 | Alphabetical | アルファベット順 | 揺れなし |
| カスタム順 | Custom order | カスタム順 | Android: Custom order / カスタム |
| 並べ替え | Sort | 並べ替え | iPhone: Sort by / 並べ替え |
| リストで表示 | List view | リストで表示 | 揺れなし |
| グリッドで表示 | Grid view | グリッドで表示 | Android: Grid view / 格子で表示 |
| プレイリスト・アルバム | Playlists and albums | プレイリスト・アルバム | Android: Playlists and albums / プレイリストとアルバム |
| 自動のプレイリストの印 | Playlist • Auto | プレイリスト • 自動 | 揺れなし |
| マイプレイリスト | My playlist | マイプレイリスト | 揺れなし |
| 再生履歴（ページ） | History | 再生履歴 | Android: Listening history / 再生履歴<br>※ Windows の再生履歴のページの題は今 Recently played / 最近再生した曲。ホームの棚とは別にする |
| 最近再生した曲（ホームの棚） | Recently played | 最近再生した曲 | 揺れなし |
| ウィジェット: 最近再生した順 | Recently played | 最近再生した順 | Mac/iPhone ウィジェット: Recently Played / 最近再生した順 |
| ログインを促す（ライブラリ） | Log in to see your YouTube Music playlists, artists and albums here. | ログインすると、YouTube Music のプレイリスト・アーティスト・アルバムがここに並びます。 | 揺れなし |
| ライブラリを変更できない | Couldn't change your library | ライブラリを変更できませんでした | Android: Couldn't update your library / ライブラリを変えられませんでした |

### プレイリスト・アルバム・アーティストのページ

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| 新しいプレイリスト (YT) | New playlist | 新しいプレイリスト | GTK・QML: New playlist… / 新しいプレイリスト…<br>Android: New playlist / 新しいプレイリストを作る |
| プレイリストを作成 | Create playlist | プレイリストを作成 | 揺れなし |
| 作成（ボタン） | Create | 作成 | 揺れなし |
| プレイリスト名 | Playlist name | プレイリスト名 | Android・QML: Playlist name / プレイリストの名前 |
| 作成できない | Couldn't create the playlist | プレイリストを作成できませんでした | Android・GTK・QML: Couldn't create the playlist / プレイリストを作れませんでした |
| 作成した | Created {title} | 「{title}」を作成しました | 揺れなし |
| 一覧の中を検索 | Search in this list | この一覧の中を検索 | Android: Search in playlist / プレイリスト内を検索<br>QML: Search in this list / 一覧の中を検索 |
| 一致する曲が無い | No matching songs | 一致する曲はありません | QML: No matching songs / 合う曲がありません |
| 曲が無い | No songs | 曲がありません | 揺れなし |
| おすすめの曲（一覧の下） | Recommended songs | おすすめの曲 | Windows・GTK: Recommended / おすすめの曲 |
| このプレイリストに合いそうな曲 | Songs that fit this playlist | このプレイリストに合いそうな曲 | 揺れなし |
| 並べ替え: タイトル | Title | タイトル | Android: Title / 曲名 |
| 並べ替え: 追加した日 | Date added | 追加した日 | 揺れなし |
| 並べ替え: リリース日 | Release date | リリース日 | Android: Release date / リリース（公開日） |
| 並べ替え: 再生時間 | Duration | 再生時間 | Android: Duration / 長さ |
| 人気 | Popular | 人気 | 揺れなし |
| すべての曲を見る | See all songs | すべての曲を見る | 揺れなし |
| MV | Music videos | MV | 揺れなし |
| その他の動画 | Other videos (covers, live…) | その他の動画（歌ってみた・ライブなど） | QML: Other videos (covers, live, etc.) / その他の動画（歌ってみた・ライブなど） |
| ライブ・お知らせ | Live & news | ライブ・お知らせ | 揺れなし |
| 登録（アーティスト・番組） (YT) | Subscribe | 登録 | QML: Subscribe / 登録する |
| 登録済み（状態） | Subscribed | 登録済み | QML: Subscribed / 登録しました |
| 登録した（知らせ） | Subscribed to the show | 番組を登録しました | QML: Subscribed / 番組を登録しました |
| 登録を解除した (YT) | Unsubscribed | 登録を解除しました | GTK・QML: Unsubscribed / 登録をやめました |
| 登録を変更できない | Couldn't change your subscription | 登録を変更できませんでした | QML: Couldn't change your subscription / 登録を変えられませんでした |

### ホーム・検索

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| ホーム (YT) | Home | ホーム | 揺れなし |
| 検索 (YT) | Search | 検索 | 揺れなし |
| 検索の入口の文 | What do you want to play? | 何を聴きたいですか？ | QML: What do you want to listen to? / 何を聴きたいですか？ |
| 最近の検索 | Recent searches | 最近の検索 | 揺れなし |
| 検索の履歴を消す | Clear all | すべて消去 | Android: Clear all / すべて消す |
| 消去（1件） | Clear | 消去 | Android: Clear / 消す |
| トップの検索結果 | Top result | トップの検索結果 | 揺れなし |
| すべて | All | すべて | 揺れなし |
| ライブ（検索のタブ） | Live | ライブ | Windows: Live / ライブ配信 |
| 見つからない | No results found | 見つかりませんでした | 揺れなし |
| 検索できない | Couldn't search | 検索できませんでした | GTK・QML: Couldn't search (check your connection) / 探せませんでした（通信を確かめてください） |
| すべてを見る（ジャンル） | Browse all | すべてをブラウズ | iPhone: Browse all / すべてを見る |
| 気分・ジャンル (YT) | Moods & genres | 気分・ジャンル | 時計: Genres & moods / ジャンル・気分 |
| すべて表示（棚） | Show all | すべて表示 | 揺れなし |
| 新作 | New releases | 新作 | 揺れなし |
| {アーティスト} の新作 | New from {artist} | {artist} の新作 | 揺れなし |
| もう一度読み込む（ホーム） | Retry | もう一度 | Windows・Mac・GTK: Try again / もう一度読み込む<br>※ 「読み直す」と同じ |

### ポッドキャスト

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| エピソード (YT) | Episodes | エピソード | 揺れなし |
| 新しいエピソード (YT) | New episodes | 新しいエピソード | 揺れなし |
| エピソードが無い | No episodes | エピソードはありません | Android・Windows: No episodes / エピソードがありません |
| 説明を広げる | Show more | すべて表示 | Android: More / もっと見る |
| 説明を縮める | Show less | 短く表示 | Android: Less / 閉じる<br>Windows: Show less / 一部のみ表示 |
| RSS の番組を探して追加 | Find and add RSS shows | RSS の番組を探して追加 | GTK: Find and add an RSS show / RSS の番組を探して足す<br>QML: Find and add RSS shows / RSS の番組を探して足す |
| RSS の番組を探す | Find RSS shows | RSS の番組を探す | GTK: Find an RSS show / RSS の番組を探す |

### ダウンロード

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| ダウンロード（ページ） (YT) | Downloads | ダウンロード | 揺れなし |
| ダウンロード済み | Downloaded | ダウンロード済み | 揺れなし |
| ダウンロード中 | Downloading | ダウンロード中 | Windows: Downloading / ダウンロードを始めました |
| ダウンロード待ち | Waiting | 待っています | Mac: Queued / 待っています |
| ダウンロードに追加した | Added to downloads | ダウンロードに追加しました | 揺れなし |
| ダウンロードを削除した | Download removed | ダウンロードを削除しました | 揺れなし |
| ダウンロードを削除しますか | Remove download? | ダウンロードを削除しますか？ | 揺れなし |
| すべて削除 | Delete all downloads | ダウンロードをすべて削除 | 揺れなし |
| すべて削除しますか | Delete all downloads? | ダウンロードをすべて削除しますか？ | 揺れなし |
| すべて削除した | All downloads deleted | ダウンロードをすべて削除しました | 揺れなし |
| スマートダウンロード (YT) | Smart downloads | スマートダウンロード | 揺れなし |
| スマートダウンロードの説明 | Automatically download songs you're likely to play | 聴きそうな曲を自動で取っておく | Mac: Automatically download songs you might like / 聴きそうな曲を自動で取っておく<br>GTK: Automatically keep songs you're likely to play / 聴きそうな曲を自動で取っておく<br>QML: Download songs you’re likely to play / 聴きそうな曲を自動で取っておく |
| スマート: いつも聴いている | Played often | いつも聴いている | Android: Listen often / いつも聴いている<br>Mac: Your favorites / いつも聴いている |
| スマート: 好きそう | You might like | 好きそう | Mac: Something new / 好きそう |
| スマート: おすすめ | Recommended | おすすめ | 揺れなし |
| ダウンロードに使う通信 | Download over | ダウンロードに使う通信 | Windows・GTK: Download using / ダウンロードに使う通信 |
| どの通信でも | Any network | どの通信でも | 揺れなし |
| Wi‑Fi などだけ | Wi-Fi and other unmetered networks only | Wi-Fi など従量制でない通信だけ | Android: Wi‑Fi and other unmetered networks only / Wi‑Fi など従量制でない通信だけ<br>QML: Unmetered networks only / 従量制でない通信だけ |
| 通信を待っている | Waiting for a network | 通信を待っています | Mac・QML: Waiting for a connection / 通信を待っています |
| 使っている容量 | Storage used: {size} | 使っている容量: {size} | Android: Storage used: %1$s / 使用中の容量: %1$s |
| 残り | {n} left | 残り {n} 曲 | Android: %1$d left / 残り %1$d曲 |
| オフライン | Offline | オフライン | 揺れなし |

### 端末の連携

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| 端末の連携（ページ） | Devices | 端末の連携 | Android: Connected devices / 端末の連携 |
| 連携している端末 | Your devices | 連携している端末 | 揺れなし |
| 端末を追加 | Add device | 端末を追加 | iPhone・QML: Add a device / 端末を加える |
| 端末を外す | Remove | 外す | 揺れなし |
| 端末を外しますか | Remove this device? | 端末を外しますか？ | 揺れなし |
| 外した | Removed {device} | 「{device}」を外しました | 揺れなし |
| 外せない | Couldn't remove the device | 外せませんでした | Windows・Mac・GTK・QML: Couldn’t remove the device / 外せませんでした |
| 加わる | Join | 加わる | 揺れなし |
| はじめる | Get started | はじめる | 揺れなし |
| 確認（6桁の数字） | Confirm | 確認 | Android: Confirm / 確定 |
| リセット | Reset | リセット | Android: Reset / 作り直す |
| 入力したら続ける | Continue after entering it | 相手に入力したら、続ける | Android: Continue after entering it / 入力したら続ける<br>QML: Continue after entering it on the other device / 相手に入力したら、続ける |
| 文字列をコピー | Copy text | 文字列をコピー | 揺れなし |
| コピーした | Copied | コピーしました | 揺れなし |
| ログインを渡す | Give login | ログインを渡す | Mac・iPhone: Share sign-in / ログインを渡す |
| ログインを渡しますか | Give your login? | ログインを渡しますか？ | Mac: Share your sign-in? / ログインを渡しますか？ |
| 渡す（ボタン） | Give | 渡す | Mac・iPhone: Share / 渡す |
| ログインを渡せない | Couldn't give your login | ログインを渡せませんでした | GTK・QML: Couldn’t send your login / ログインを渡せませんでした<br>Mac: Couldn’t share your sign-in / ログインを渡せませんでした |
| 連携のまとまり（Android の「家」） | (device) group | 連携 | Android: household / 家（19 か所。Create household / 家を作る、Household devices / 家の端末、Remove from household / 家から外す、Forget this device's household / この端末の家の記録を消す など）<br>ほか: group / 連携（Forget the group on this PC / この PC の連携の記録を消す、Start a new group on this PC / この PC で新しく始める）<br>※ Connect v2 の旧い画面の語。ほかに合わせて「連携」にする |
| 再生中の端末 | Active device | 再生中の端末 | iPhone: Now playing on / 再生中の端末 |
| 現在の端末（端末を選ぶ欄） | Current device | 現在の端末 | Android・Windows・Mac・iPhone・GTK: Current device / 現在のデバイス<br>QML: Current device / 再生中のデバイス |
| ほかの端末を選択 | Select another device | ほかの端末を選択 | Android・Windows・Mac・iPhone・GTK・QML: Select another device / ほかのデバイスを選択 |
| 端末に接続 | Connect to a device | 端末に接続 | Windows・Mac・iPhone・GTK・QML: Connect to a device / デバイスに接続 |
| 端末の連携の設定 | Device settings | 端末の連携の設定 | 揺れなし |
| 接続（ボタン） | Connect | 接続 | QML: Connect / つなぐ |
| 接続を外す | Disconnect | 接続を外す | QML: Disconnect / 切る |
| 接続中（状態） | Connected | 接続中 | 揺れなし |
| 接続している（進行） | Connecting… | 接続しています… | 揺れなし |
| 中継に接続している | Connecting to the relay… | 中継に接続しています… | Windows・Mac・GTK: Connecting to the relay… / 中継につないでいます…<br>Android: Connecting to the relay / 中継に接続しています |
| 相手に送っている | Sending to the other device… | 相手の端末に送っています… | Windows・Mac・GTK: Sending to the other device… / 相手に送っています…<br>Android: Sending to the other device / 相手の端末に送っています |
| 相手が加えるのを待っている | Waiting for the other device to add this {PC}… | 相手が加えるのを待っています… | 揺れなし |
| この端末で再生 | Play on this {PC} | この {PC} で再生 | Android: Play here / ここで再生 |
| 操作だけ（端末の印） | {device} (remote only) | {device}（操作だけ） | Android: %1$s (control only) / %1$s（操作だけ）<br>GTK: Pixel (remote only) / Pixel（操作だけ）<br>QML:  (control only) / （操作だけ） |

### 設定

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| 設定 (YT) | Settings | 設定 | 揺れなし |
| 設定を開く | Open settings | 設定を開く | iPhone: Open Settings / 設定を開く |
| アカウント | Account | アカウント | 揺れなし |
| ログイン | Log in | ログイン | 揺れなし |
| ログアウト | Log out | ログアウト | 揺れなし |
| ログアウトしますか | Log out? | ログアウトしますか？ | 揺れなし |
| ログインしていない | Not logged in | ログインしていません | Android・Windows・Mac: You're not logged in / ログインしていません |
| ログイン中（状態） | Logged in | ログイン中 | 揺れなし |
| ログインし直す | Log in again | ログインし直す | Mac・iPhone: Log in again / もう一度ログイン<br>Android: Log in again / もう一度ログインする |
| ログインし直しが要る | You need to log in again | 再ログインが必要です | Android・Mac: Please log in again / 再ログインが必要です |
| 言語（見出し） | Language | 言語 | 揺れなし |
| 画面の言語 | App language | 画面の言語 | 揺れなし |
| 画面の言語: 端末に合わせる | Use device language | 端末に合わせる | Windows: Use Windows language / Windows に合わせる<br>Mac: Use macOS language / macOS に合わせる<br>iPhone: Use iPhone language / iPhone に合わせる<br>GTK: System / 端末に合わせる<br>QML: Use system language / 端末に合わせる<br>※ 迷い。OS 名を出す案あり（未決） |
| 画面の言語: 日本語・英語 | 日本語 / English | 日本語 / English | —<br>※ 言語名はその言語のまま（全部そろっている） |
| 次の起動から | Takes effect the next time you open wave | 次の起動から反映されます | GTK・QML: Takes effect the next time you open wave / 次の起動から変わります |
| 曲名を元の表記で | Show titles as released | 曲名を元の表記で | QML: Original titles / 曲名を元の表記で |
| 新作の通知 | New release notifications | 新作の通知 | QML: New release alerts / 新作の通知 |
| 登録したアーティストの新作 | New from artists you subscribe to | 登録したアーティストの新作 | 揺れなし |
| 再生履歴に残す | Save played songs to your YouTube Music history | 再生した曲を YouTube Music の再生履歴に残す | Android: Send play history to YouTube Music / 再生履歴を YouTube Music に送る |
| Discord に再生中の曲を出す | Show what you're playing on Discord | Discord に再生中の曲を出す | Windows・Mac・GTK: Show what you’re playing on Discord / Discord に再生中の曲を出す<br>QML: Show the playing song on Discord / Discord に再生中の曲を出す |
| Discord に断られた | Discord refused the connection. Check the Application ID | Discord につなげませんでした。Application ID を確かめてください | Windows・Mac: Discord refused the connection. Check the application ID / Discord につなげませんでした。アプリケーション ID を確かめてください<br>※ Application ID は Discord の画面の語なので英語のまま |
| データの場所 | Data folder | データの場所 | 揺れなし |
| 再生中の画面（設定の項目） | Now playing view | 再生中の画面 | 揺れなし |
| このアプリについて | About | このアプリについて | 揺れなし |
| 更新 | Refresh | 更新 | 揺れなし |

### Claude

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| Claude に聞く | Ask Claude | Claude に聞く | 揺れなし |
| 新しい会話 | New chat | 新しい会話 | 揺れなし |
| これまでの会話 | Past chats | これまでの会話 | 揺れなし |
| 会話を削除 | Delete chat | 会話を削除 | Android・Windows・Mac・iPhone・GTK: Delete chat / 会話を消す<br>QML: Delete this chat from history / 今の会話を履歴から消す |
| 送る | Send | 送る | 揺れなし |
| 止める | Stop | 止める | 揺れなし |
| 接続の文字列ではない | That isn't a wave Claude bridge pairing text | wave の Claude の中継の接続の文字列ではありません | QML: That isn't a wave Claude bridge pairing text / 接続の文字列の形が違います |
| キューに追加した（Claude） | Added {n} songs to queue | {n} 曲をキューに追加しました | Android: Added %d songs to the queue / %d 曲をキューに足しました |
| 作って入れた（Claude） | Created “{title}” with {n} songs | 「{title}」を作成して {n} 曲追加しました | Android: Created “%1$s” with %2$d songs / 「%1$s」を作って %2$d 曲入れました |
| 足した（Claude） | Added {n} songs to “{title}” | 「{title}」に {n} 曲追加しました | Android: Added %2$d songs to “%1$s” / 「%1$s」に %2$d 曲足しました |

### 共通のボタン・知らせ

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| キャンセル | Cancel | キャンセル | Android・時計・Mac・iPhone・GTK・QML: Cancel / やめる |
| 閉じる | Close | 閉じる | 揺れなし |
| 完了 | Done | 完了 | 揺れなし |
| 保存 | Save | 保存 | QML: Save / 覚える |
| 削除（本当に消すもの） | Delete | 削除 | 揺れなし |
| 削除（一覧から除く） | Remove | 削除 | 揺れなし |
| すべて削除 | Delete all | すべて削除 | 揺れなし |
| 戻る | Back | 戻る | 揺れなし |
| 進む | Forward | 進む | 揺れなし |
| 開く | Open | 開く | 揺れなし |
| 確かめている | Checking… | 確かめています… | Android: Checking… / 確認中…<br>Android: Checking… / 確認しています… |
| アカウントを確かめている | Checking account… | アカウントを確かめています… | Android: Checking account… / アカウントを確認しています… |
| 使える／使えない／分からない | Available / Unavailable / Unknown | 使えます / 使えません / 分かりません | Android: Available / 利用可<br>Android: Unavailable / 利用不可<br>Android: Unknown / 未確認 |
| 通信できない | Couldn't connect | 通信できませんでした | 揺れなし |
| 応答を読み取れない | Couldn't read the response | 応答を読み取れませんでした | 揺れなし |
| 動画を読み込めない | Couldn't load the video | 動画を読み込めませんでした | 揺れなし |
| ホームを読み込めない | Couldn't load Home | ホームを読み込めませんでした | Windows・Mac・GTK: Couldn’t load Home / ホームを読み込めませんでした |
| アーティストを読み込めない | Couldn't load the artist | アーティストを読み込めませんでした | Windows・Mac: Couldn’t load the artist / アーティストを読み込めませんでした |
| ポッドキャストを読み込めない | Couldn't load the podcast | ポッドキャストを読み込めませんでした | Windows: Could not load the podcast / 番組を読み込めませんでした<br>Mac: Couldn’t load the podcast / ポッドキャストを読み込めませんでした |

## 3. 直す量の見込み（調べた時点）

正の表記と違う文言を、文言を書いている場所（Android は文字列の名前、ほかはソースの行）で数えた。同じ場所が 2 つの規則に当たっても 1 つと数える。

| プラットフォーム | 文言の数 | 直す場所 | 主なもの |
|---|---|---|---|
| Android（スマホ） | 487 | 約 138 | household / 家 → 連携（19）、確認 → 確かめる（16）、Liked Songs（12）、`%d曲` の空白（8）、次の曲・前の曲 → 次へ・前へ、外す → 削除、Try again → Retry |
| 時計 | 44 | 約 11 | Sign in → Log in（7）、やめる → キャンセル |
| Android のウィジェット | 44 | 約 4 | Sign in、Liked Songs |
| Windows | 584 | 約 110 | `’` → `'`（58）、Liked Songs（12）、Next up → Up next、Add to playlist、デバイス → 端末 |
| Mac | 585 | 約 119 | `’` → `'`（60）、Liked Songs（11）、Share sign-in → Give login、「やめる」 |
| iPhone | 402 | 約 48 | `’`（13）、デバイス → 端末、Use iPhone language |
| Mac・iPhone のウィジェット | 64 | 約 1 | （App Intents は例外にしたのでほぼ無し） |
| GTK | 589 | 約 110 | `’` → `'`（48）、Liked Songs（19）、Up next、Unsubscribed の日本語 |
| QML | 409 | 約 79 | `’`（24）、言い回しの独自の訳（Reload・Bigger video・Original titles・つなぐ・切る など） |

YouTube Music に合わせて全部で変わる語（Library・Liked music・Save to playlist・Start radio・Up next）が約 2 割、アポストロフィが約 3 割。合わせて約 620 か所。表の 268 の概念のうち 142 に揺れがある。

## 4. 迷って決めたもの（本人に確かめたい）

1. **ライブラリと高く評価した曲の英語**: 今は全部が `Your Library` / 「マイライブラリ」、`Liked Songs`（Spotify の言い方）。YouTube Music に合わせて `Library` / 「ライブラリ」、`Liked music` にした。画面の作りを Spotify に寄せている（Android の `Collection.kt` の注など）ので、Spotify の語を残すなら表の該当行を戻す
2. **プレイリストへの追加**: 今は全部が `Add to playlist` / 「プレイリストに追加」。YouTube Music の `Save to playlist` / 「プレイリストに保存」にした
3. **キューの「次に流れる曲」の英語**: 今は 6 つが `Next up`。YouTube Music の `Up next` にした。日本語は、キューの区切り（続く曲の一覧）を「次に流れる曲」、再生中の画面の「次の 1 曲」の見出しを「次の曲」と分けたまま
4. **ログインの英語**: YouTube Music は `Sign in` だが、wave は 9 割が `Log in`。多数の `Log in` にした。`Give login`（ログインを渡す）も多数のまま。英語として自然なのは `Share login` かもしれない
5. **画面の言語の「端末に合わせる」**: 英語は OS の名前を出す形（Windows・Mac・iPhone）が 3 つ、日本語は「端末に合わせる」が 3 つ（Android・GTK・QML）で同数。決め方どおり Android の `Use device language` / 「端末に合わせる」にした。Linux では「端末」がターミナルと読めること、Windows・Mac は OS の名前のほうが分かりやすいことから、`Use {OS} language` / 「{OS} に合わせる」（Linux は `Use system language` / 「システムに合わせる」）にする案もある
6. **アポストロフィ**: `'` と `’` がほぼ半々。Android に合わせて `'` にしたが、引用符は `“ ”` なので、字形をそろえるなら `’` にする案もある
7. **「デバイス」→「端末」**: 「現在のデバイス」「ほかのデバイスを選択」「デバイスに接続」は 5〜6 つのプラットフォームでそろっているが、規則（1.8）で「端末」に変えた
8. **「会話を消す」→「会話を削除」**: 5 つでそろっているが、規則（本当に無くなるものは「削除」）で変えた
9. **「再生/一時停止」→「再生・一時停止」**: 「/」が 4 つで多いが、並べる記号の規則（1.7）で「・」にした
10. **アルバム・アーティストへの移動**: YouTube Music の日本語版が「〜へ移動」か「〜に移動」か確かめられていない。今そろっている「アルバムへ移動」「アーティストへ移動」のまま
11. **「新作」**: YouTube Music の日本語版の `New releases` の訳を確かめられていない。今そろっている「新作」のまま
12. **曲数と長さ（1.6）**: 合計の長さを Windows・Mac・Android の「分 秒」の形にそろえた（Linux は分だけ）。Linux の短い形にそろえる案もある
