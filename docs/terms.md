# 画面の文言の正の表記

wave の画面の文言（英語・日本語）を、全部のプラットフォームでそろえるための決めごと。文言を書くとき・直すときはここに合わせる。ここに無い語は、まず規則（1章）に合わせ、迷ったら表に足す。

- 対象: Android（`androidApp/src/*/res/values*/`。時計は `strings_watch.xml`、ホーム画面のウィジェットは `strings_widget.xml`）、Windows（`windows/Wave.App` の `L.T`）、Mac・iPhone（`mac/Sources` の `L.t`、ウィジェットは `mac/Resources/Widget/*.lproj` と `WidgetShared.swift`）、Linux GTK（`linux/wave-gtk`・`linux/wave-desktop` の `lang::t`）、Linux QML（`linux/wave-qt/qml` の `Style.t` と `linux/wave-qt/src` の `lang::t`）
- 決め方: YouTube Music の公式の画面にある概念は、YouTube Music の英語版・日本語版の表記に合わせる（表で「(YT)」を付けたもの）。それ以外は、使っているプラットフォームが多い表記。同数なら Android。規則（1章）に当てはまるものは、個々の多数より規則を優先する
- 表の「今の揺れ」は、正の表記と違う今の文言だけを「プラットフォーム: 英語 / 日本語」で並べる。正と同じものは書かない。「揺れなし」は全部のプラットフォームがそろっているもの
- 調べた時点: 2026-11-13（ios-app ブランチ。全プラットフォームを表と規則にそろえたあと）

## 1. 規則

### 1.1 英語の大文字

- 題・ボタン・メニュー・タブ・設定の項目名・知らせは、文頭だけ大文字（sentence case）。今は全部これ（App Intents の例外を除く）
  - 例: `Add to queue`、`Save to library`、`Liked music`、`Picture in picture`、`New release notifications`
- 固有名はそのまま: YouTube Music、YouTube、Discord、Claude、Claude Code、Premium、Tailscale、Wi-Fi、QR、RSS、PC・Mac・iPhone・Windows・macOS、Application ID（Discord の画面の語）
- wave は常に小文字（文頭でも `wave`）
- 「ラベル: 値」の値は、値の先頭を大文字にしてよい（`Repeat: Off`、`Shuffle: On`、`Premium audio: Available`）
- 例外: Mac・iPhone の App Intents（ショートカットの操作名・ウィジェットの設定の項目。`Next Track`、`Play Playlist`、`Choose What to Play` など）は Apple の決まりに合わせて Title Case のままにする
- 生放送の印だけは大文字 `LIVE`（日本語は「ライブ」）

### 1.2 英語の記号

- アポストロフィは真っすぐな `'`（`Couldn't`、`isn't`、`you're`）。Android の多数に合わせてそろえた。今は `’` は残っていない
- 引用符（名前を囲む）は曲がった `“ ”`（`Added to “Road trip”`）。今の多数
- 三点は 1 文字の `…`（`...` は使わない）。今も全部そう
- 区切りの中黒は前後に空白を入れた ` • `（`Playlist • Auto`、`Album • 2024`）
- 並べるときは `A, B and C`（オックスフォード・カンマは付けない。今の多数）
- ショートカットは文言の後ろに空白と半角の括弧: `Full screen (F11)`、`Ask Claude (Ctrl+J)`、Mac は `(⌘J)`

### 1.3 終わりの句読点（英語のピリオド・日本語の「。」）

- ボタン・見出し・項目名・状態・知らせ（トースト・下の帯の一言）・エラー・設定の補足には付けない。2 文になるときも、文の区切りにだけ付けて最後には付けない
  - 例: `Timed out. Please try again` / 「時間切れになりました。もう一度やり直してください」
- ダイアログの本文と、空の画面・案内の画面の説明文（読ませる文）には付ける。1 文でも付ける
  - 例: `This removes your sign-in saved on this PC.` / 「この PC に保存したログイン情報を消します。」、`Sign in to see your YouTube Music playlists, artists and albums here.`
- 英語と日本語で付け方をそろえる（片方だけ付けない）
- 問いは英語 `?`、日本語は全角の「？」（「ログアウトしますか？」）。今も全部そう

### 1.4 「…」の付け方

- 進んでいる途中の状態に付ける: `Loading…` / 「読み込んでいます…」、`Checking…` / 「確かめています…」、`Connecting to the relay…` / 「中継に接続しています…」
- ボタン・メニューの項目には付けない（押した先でもう一度入力するものでも付けない。今の多数）: `New playlist` / 「新しいプレイリスト」
- 例外: 端末のほかのアプリへ渡す共有のメニュー `Share with…` / 「ほかのアプリで共有…」は今のまま
- 英語と日本語で付け方をそろえる

### 1.5 数の書き方

- 曲数: 英語は単数・複数を分ける `1 song` / `3 songs`、日本語は数と単位のあいだに半角の空白 `3 曲`。上限を超えたら `100+ songs` / `100 曲以上`。Windows の `L.Songs`、Mac の `L.songs`、Linux の `lang::songs`・`Style.songs` と同じ形
  - Android の文字列も `%d 曲`・`残り %1$d 曲`・`%2$d 曲中 %1$d 曲` の形
- ほかの数（端末・件数）も同じ: `2 devices` / `2 台`、`5 results` / `5 件`
- 文の中に決まった数として入るもの（`6桁の数字`、`2分で無効`、`3曲以上のアーティスト`）は空白を入れない（今の多数）
- 進み具合: `3 of 10` / `10 曲中 3 曲`
- 割合: `80%`（空白なし）

### 1.6 時間の書き方

- 曲・エピソードの長さ（行の右）: `3:05`、`1:02:03`（言語に依らない）
- 一覧の合計の長さ: 1 時間以上は `1 hr 5 min` / `1 時間 5 分`、1 時間未満は `13 min 5 sec` / `13 分 5 秒`（全部のプラットフォームでこの形）
- 曲数と合計の長さを並べるとき: 英語は `3 songs, 13 min 5 sec`、日本語は読点 `3 曲、13 分 5 秒`
  - Windows の `L.SongsAndLength`、Mac の `L.songsAndLength`、Linux の `lang::count_and_length`、Android の `library_count_and_length` がこの形を作る
- 日付: `2026/10/13`（Linux の今の形。ほかのプラットフォームは YouTube から届いた文字をそのまま出す）
- 「〜秒」の残り: `(5 s)` / `（5秒）`（Android の今の形）

### 1.7 日本語の記号と空白

- 日本語と英字・数字のあいだには半角の空白を入れる（「Claude に聞く」「YouTube Music で開く」「この PC」）。今も全部そう
- 差し込む名前（`{device}`・`%1$s` など）の後ろに助詞が続くときも空白を入れる: 「{device} で再生中」「{name} の新作」
  - Mac・iPhone の App Intents（`${applicationName}で…`）は Apple の書き方のまま
- 括弧は全角の「（）」。ショートカットだけ半角（1.2）
- 引用は「」: 曲名・プレイリスト名・端末名・画面やボタンの名前を文の中で言うとき（「「Road trip」に追加しました」「設定の「Claude」でつないでください」）。アーティスト名・番組名を「〜の新作」のように名詞の頭に置くときは囲まない
  - 英語でも、文の中の曲名・プレイリスト名・端末名は “ ” で囲む（`Added “{title}” to queue`、`Removed “{device}”`、`Gave your login to “{device}”`）。日本語で「」を付けたものは英語でも囲み、片方だけにしない
- 並べるときは「・」（「プレイリスト・アルバム」「再生・一時停止」）。「と」「/」は使わない
- 「ラベル: 値」は半角のコロンと空白（「中継: 接続中」「歌詞の提供: LRCLIB」）
- 空白は普通の空白だけ。QML の「wave が動いている間」の改行しない空白、Android の `Wi‑Fi`（U+2011）は普通の `wave が`・`Wi-Fi` にする

### 1.8 日本語の言い回し

- 消す操作
  - 一覧（プレイリスト・キュー・高く評価した曲・履歴・ライブラリ）から項目を除く: 「〜から削除」（YouTube Music の日本語版と同じ）。英語は `Remove from …`
  - 端末を連携から除く: 「外す」（`Remove`）。端末は消えないので「削除」にしない
  - 本当に無くなるもの（ダウンロード・会話・保存したログイン情報）: 「削除」。英語は `Delete`（ダウンロードだけは YouTube Music に合わせて `Remove download`）
  - 入力欄・検索の履歴・キューを空にする: 「消去」（`Clear`）
  - 検索の履歴の1件を除くボタン（× ・スワイプ）は一覧から除く操作なので `Remove from history` / 「履歴から削除」。検索の履歴を全部空にするのは `Clear all` / 「すべて消去」、検索欄の入力を消す×は `Clear` / 「消去」
- 加える操作: 曲・番組を一覧へ入れるのは「追加」（「キューに追加」「プレイリストに保存」）。「足す」は使わない。端末が連携に入るのは「加わる」、入れる側の操作の説明は「加える」、ボタンは「端末を追加」
- 作る操作: ボタン・知らせ・エラーとも「作成」（「プレイリストを作成」「「{title}」を作成しました」「プレイリストを作成できませんでした」）
- 変える操作: 「変更」（「登録を変更できませんでした」「評価を変更できませんでした」）
- 確かめる: 文の中は「確かめる」（「確かめています…」「アカウントを確かめられませんでした」）。今の多数。ボタンの `Confirm` だけ「確認」
- つなぐ: ボタンと短い状態は漢語（「接続」「接続中」「切断」「接続を外す」）、文の中は「つなぐ」（「Claude につないでいません」「つながりませんでした」）
- できなかったとき: 「〜できませんでした」「〜（ら）れませんでした」（`Couldn't …`）。「〜に失敗しました」は使わない
- 頼む文: 「〜してください」。「〜しましょう」は使わない
- 端末: 自分の機器は全部「端末」（「デバイス」は使わない）。電話は「スマホ」、時計は「時計」、パソコンは「PC」（Mac・iPhone はその名前）
- ログイン: 英語は `Sign in` / `Sign out`（YouTube Music・Google と同じ。名詞・形容は `sign-in`）、日本語は「ログイン」「ログアウト」。状態は `Signed in` / 「ログイン中」、`Not signed in` / 「ログインしていません」、進行は `Signing in…` / 「ログインしています…」。`Log in` / `Log out` / `Logged in` は使わない（2026-11-13 に本人が決めた）
  - 例外: 端末の連携で wave API へログインを渡す操作だけは、名詞の `login` を使う（`Give login` / 「ログインを渡す」、`Give your login?`、`Gave your login to “{device}”`）。全部のプラットフォームでこの形
- やめる: ダイアログの取り消しは「キャンセル」（`Cancel`）。「やめる」は「映像だけをやめる」のような操作の名前にだけ使う

### 1.9 設定の「画面の言語」

- 項目名: `App language` / 「画面の言語」（全部そろっている）。見出しは `Language` / 「言語」
- 選び肢は 3 つ: `Use device language` / 「端末に合わせる」、`日本語`、`English`。言語名はその言語のまま書き、訳さない
- 全部のプラットフォームでこの 3 つにそろっている（本人の決め、2026-11-13）
- 補足: `Text from YouTube (such as shelf titles) also uses this language. Takes effect the next time you open wave` / 「YouTube から届く文字（棚の見出しなど）もこの言語になります。次の起動から反映されます」。Android は頭に今の選び肢の名前が付く

## 2. 概念ごとの表

### 下の帯（再生の操作）

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| 再生 (YT) | Play | 再生 | 揺れなし |
| 一時停止 (YT) | Pause | 一時停止 | 揺れなし |
| 再生・一時停止（1つのボタン） | Play/Pause | 再生・一時停止 | 揺れなし |
| 次の曲へ (YT) | Next | 次へ | 揺れなし |
| 前の曲へ (YT) | Previous | 前へ | 揺れなし |
| シャッフル (YT) | Shuffle | シャッフル | 揺れなし |
| シャッフル再生（一覧の頭のボタン） | Shuffle play | シャッフル再生 | 揺れなし |
| リピート (YT) | Repeat | リピート | 揺れなし |
| 高く評価（ボタン） (YT) | Like | 高く評価 | 揺れなし |
| 高く評価済み（状態） | Liked | 高く評価済み | 揺れなし |
| 音量 | Volume | 音量 | 揺れなし |
| ミュート | Mute | ミュート | 揺れなし |
| 何も再生していない | Nothing playing | 何も再生していません | 揺れなし |
| 再生中（見出し） | Now playing | 再生中 | 揺れなし |
| 全画面 | Full screen | 全画面 | 揺れなし |
| 全画面を終える | Exit full screen | 全画面を終える | 揺れなし |
| 小窓（ピクチャー・イン・ピクチャー） | Picture in picture | 小窓で見る | 揺れなし |
| 小窓を閉じる | Close picture in picture | 小窓を閉じる | 揺れなし |
| 映像を大きく見る | Expand video | 映像を大きく見る | 揺れなし |
| 映像だけを窓いっぱいに | Video only | 映像だけを窓いっぱいに | 揺れなし |
| 映像だけをやめる | Exit video only (Esc) | 映像だけをやめる (Esc) | 揺れなし |
| ライブの印 | LIVE | ライブ | 揺れなし |

### 再生中の画面・キュー

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| キュー (YT) | Queue | キュー | 揺れなし |
| キュー（曲数つき） | Queue ({n} songs) | キュー（{n} 曲） | 揺れなし |
| キューを開く | Open queue | キューを開く | 揺れなし |
| 次の1曲の見出し (YT) | Up next | 次の曲 | 揺れなし<br>※ YouTube Music の「次の曲」タブと同じ語 |
| キューの区切り: 自分で入れた曲 | Next in queue | キューの次の曲 | 揺れなし |
| キューの区切り: 元の一覧・ラジオから続く曲 (YT) | Up next | 次に流れる曲 | 揺れなし |
| 続く曲を消す | Clear up next | 次に流れる曲を消去 | 揺れなし |
| 次に流れる曲が無い | Nothing up next | 次に流れる曲はありません | 揺れなし |
| キューを消去 | Clear queue | キューを消去 | 揺れなし |
| キューから削除 (YT) | Remove from queue | キューから削除 | 揺れなし |
| キューが空 | Your queue is empty | キューは空です | 揺れなし |
| キューが空のときの案内 | Play something and upcoming songs will show up here | 曲を再生すると、ここに次の曲が並びます | 揺れなし |
| キューをプレイリストとして保存 | Save queue as playlist | キューをプレイリストとして保存 | 揺れなし |
| 保存できる曲が無い | No songs in the queue to save | 保存できる曲がキューにありません | 揺れなし |
| 履歴（キューの隣のタブ） | History | 履歴 | 揺れなし |
| 履歴が空 | Nothing played yet | まだ流れた曲はありません | 揺れなし |
| 履歴が空のときの案内 | Songs you've played will show up here | 流れた曲は、ここに新しい順に並びます | 揺れなし |
| 履歴から削除 (YT) | Remove from history | 履歴から削除 | 揺れなし<br>※ 検索の履歴の1件を除くボタンも同じ |
| 〜で再生中（ほかの端末） | Playing on {device} | {device} で再生中 | 揺れなし |
| 〜で一時停止中 | Paused on {device} | {device} で一時停止中 | 揺れなし |

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
| 読み直す | Retry | もう一度 | 揺れなし |

### 曲のメニュー

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| メニューを開く | More | その他 | 揺れなし |
| 次に再生 (YT) | Play next | 次に再生 | 揺れなし |
| キューに追加 (YT) | Add to queue | キューに追加 | 揺れなし |
| ラジオを開始 (YT) | Start radio | ラジオを開始 | 揺れなし |
| プレイリストに保存 (YT) | Save to playlist | プレイリストに保存 | 揺れなし<br>※ YouTube Music に合わせた（本人の決め、2026-11-13） |
| このプレイリストに追加（候補の行） | Add to this playlist | このプレイリストに追加 | 揺れなし |
| このプレイリストから削除 (YT) | Remove from this playlist | このプレイリストから削除 | 揺れなし |
| 高く評価した曲に追加 (YT) | Add to liked music | 高く評価した曲に追加 | 揺れなし |
| 高く評価した曲から削除 (YT) | Remove from liked music | 高く評価した曲から削除 | 揺れなし |
| 評価を外す | Remove like | 評価を外す | 揺れなし |
| ライブラリに保存 (YT) | Save to library | ライブラリに保存 | 揺れなし |
| ダウンロード (YT) | Download | ダウンロード | 揺れなし |
| ダウンロードを削除 (YT) | Remove download | ダウンロードを削除 | 揺れなし |
| アルバムへ移動 (YT) | Go to album | アルバムへ移動 | 揺れなし |
| アーティストへ移動 (YT) | Go to artist | アーティストへ移動 | 揺れなし |
| アーティストへ移動（複数のとき） | Go to artist: {name} | アーティストへ移動: {name} | 揺れなし |
| 曲の画面を開く | Go to song | 曲の画面を開く | 揺れなし |
| 共有 (YT) | Share | 共有 | 揺れなし |
| ほかのアプリで共有 | Share with… | ほかのアプリで共有… | 揺れなし |
| 曲のリンクをコピー | Copy song link | 曲のリンクをコピー | 揺れなし |
| リンクをコピーした | Link copied | リンクをコピーしました | 揺れなし |
| YouTube Music で開く | Open in YouTube Music | YouTube Music で開く | 揺れなし |
| 次に再生した（知らせ） | “{title}” will play next | 「{title}」を次に再生します | 揺れなし |
| 何曲かを次に再生した（知らせ） | {n} songs will play next | {n} 曲を次に再生します | 揺れなし |
| キューに追加した（知らせ） | Added “{title}” to queue | 「{title}」をキューに追加しました | 揺れなし |
| 何曲かをキューに追加した（知らせ） | Added {n} songs to queue | {n} 曲をキューに追加しました | 揺れなし |
| 高く評価した曲に追加した | Added to liked music | 高く評価した曲に追加しました | 揺れなし |
| 高く評価した曲から削除した | Removed from liked music | 高く評価した曲から削除しました | 揺れなし |
| プレイリストに追加した | Added to “{playlist}” | 「{playlist}」に追加しました | 揺れなし |
| もう入っている | Already in “{playlist}” | 「{playlist}」にはもう入っています | 揺れなし |
| プレイリストから削除した | Removed from “{playlist}” | 「{playlist}」から削除しました | 揺れなし |
| プレイリストから削除した（名前なし） | Removed from playlist | プレイリストから削除しました | 揺れなし |
| 追加できない | Couldn't add it | 追加できませんでした | 揺れなし |
| 削除できない（曲） | Couldn't remove it | 削除できませんでした | 揺れなし |
| 重複を確かめられない | Couldn't check for duplicates, so it wasn't added | 重複を確かめられなかったので、追加しませんでした | 揺れなし |
| 評価を変更できない | Couldn't update your likes | 評価を変更できませんでした | 揺れなし |
| 評価を読み直す | Reload like status | 評価を読み直す | 揺れなし |

### ライブラリ

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| ライブラリ（タブ・見出し） (YT) | Library | ライブラリ | 揺れなし<br>※ YouTube Music に合わせた（本人の決め、2026-11-13） |
| ライブラリを検索 | Search library | ライブラリを検索 | 揺れなし |
| 高く評価した曲 (YT) | Liked music | 高く評価した曲 | 揺れなし<br>※ プレイリストのページの題も、YouTube の「高く評価した音楽」/ Liked Music ではなくこの名前を出す |
| プレイリスト (YT) | Playlists | プレイリスト | 揺れなし |
| アルバム (YT) | Albums | アルバム | 揺れなし |
| アーティスト (YT) | Artists | アーティスト | 揺れなし |
| 曲 (YT) | Songs | 曲 | 揺れなし |
| 動画 (YT) | Videos | 動画 | 揺れなし |
| ポッドキャスト (YT) | Podcasts | ポッドキャスト | 揺れなし |
| 最近（並べ替え・絞り込み） | Recents | 最近 | 揺れなし |
| 最近追加した順 | Recently added | 最近追加した順 | 揺れなし |
| アルファベット順 | Alphabetical | アルファベット順 | 揺れなし |
| カスタム順 | Custom order | カスタム順 | 揺れなし |
| 並べ替え | Sort | 並べ替え | 揺れなし |
| リストで表示 | List view | リストで表示 | 揺れなし |
| グリッドで表示 | Grid view | グリッドで表示 | 揺れなし |
| プレイリスト・アルバム | Playlists and albums | プレイリスト・アルバム | 揺れなし |
| 自動のプレイリストの印 | Playlist • Auto | プレイリスト • 自動 | 揺れなし |
| マイプレイリスト | My playlist | マイプレイリスト | 揺れなし |
| 再生履歴（ページ） | History | 再生履歴 | 揺れなし<br>※ ホームの棚「最近再生した曲」とは別 |
| 最近再生した曲（ホームの棚） | Recently played | 最近再生した曲 | 揺れなし |
| ウィジェット: 最近再生した順 | Recently played | 最近再生した順 | Mac/iPhone ウィジェット: Recently Played / 最近再生した順<br>※ Mac・iPhone のウィジェットの設定は App Intents なので Title Case（Recently Played）のまま（1.1 の例外） |
| ログインを促す（ライブラリ） | Sign in to see your YouTube Music playlists, artists and albums here. | ログインすると、YouTube Music のプレイリスト・アーティスト・アルバムがここに並びます。 | 揺れなし |
| ライブラリを変更できない | Couldn't change your library | ライブラリを変更できませんでした | 揺れなし |

### プレイリスト・アルバム・アーティストのページ

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| 新しいプレイリスト (YT) | New playlist | 新しいプレイリスト | 揺れなし |
| プレイリストを作成 | Create playlist | プレイリストを作成 | 揺れなし |
| 作成（ボタン） | Create | 作成 | 揺れなし |
| プレイリスト名 | Playlist name | プレイリスト名 | 揺れなし |
| 作成できない | Couldn't create the playlist | プレイリストを作成できませんでした | 揺れなし |
| 作成した | Created “{title}” | 「{title}」を作成しました | 揺れなし |
| 一覧の中を検索 | Search in this list | この一覧の中を検索 | 揺れなし |
| 一致する曲が無い | No matching songs | 一致する曲はありません | 揺れなし |
| 曲が無い | No songs | 曲がありません | 揺れなし |
| おすすめの曲（一覧の下） | Recommended songs | おすすめの曲 | 揺れなし |
| このプレイリストに合いそうな曲 | Songs that fit this playlist | このプレイリストに合いそうな曲 | 揺れなし |
| 並べ替え: タイトル | Title | タイトル | 揺れなし |
| 並べ替え: 追加した日 | Date added | 追加した日 | 揺れなし |
| 並べ替え: リリース日 | Release date | リリース日 | 揺れなし |
| 並べ替え: 再生時間 | Duration | 再生時間 | 揺れなし |
| 人気 | Popular | 人気 | 揺れなし |
| すべての曲を見る | See all songs | すべての曲を見る | 揺れなし |
| MV | Music videos | MV | 揺れなし |
| その他の動画 | Other videos (covers, live…) | その他の動画（歌ってみた・ライブなど） | 揺れなし |
| ライブ・お知らせ | Live & news | ライブ・お知らせ | 揺れなし |
| 登録（アーティスト・番組） (YT) | Subscribe | 登録 | 揺れなし |
| 登録済み（状態） | Subscribed | 登録済み | 揺れなし |
| アーティストを登録した（知らせ） | Subscribed | 登録しました | 揺れなし<br>※ 知らせを出すのは QML だけ。番組は「番組を登録しました」 |
| 登録した（知らせ） | Subscribed to the show | 番組を登録しました | 揺れなし |
| 登録を解除した (YT) | Unsubscribed | 登録を解除しました | 揺れなし |
| 登録を変更できない | Couldn't change your subscription | 登録を変更できませんでした | 揺れなし |

### ホーム・検索

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| ホーム (YT) | Home | ホーム | 揺れなし |
| 検索 (YT) | Search | 検索 | 揺れなし |
| 検索の入口の文 | What do you want to play? | 何を聴きたいですか？ | 揺れなし |
| 最近の検索 | Recent searches | 最近の検索 | 揺れなし |
| 検索の履歴を消す | Clear all | すべて消去 | 揺れなし |
| 入力を消去 | Clear | 消去 | 揺れなし<br>※ 検索欄の入力を消す×。検索の履歴の1件は「履歴から削除」 |
| トップの検索結果 | Top result | トップの検索結果 | 揺れなし |
| すべて | All | すべて | 揺れなし |
| ライブ（検索のタブ） | Live | ライブ | 揺れなし |
| 見つからない | No results found | 見つかりませんでした | 揺れなし |
| 検索できない | Couldn't search | 検索できませんでした | 揺れなし |
| すべてを見る（ジャンル） | Browse all | すべてをブラウズ | 揺れなし |
| 気分・ジャンル (YT) | Moods & genres | 気分・ジャンル | 揺れなし |
| すべて表示（棚） | Show all | すべて表示 | 揺れなし |
| 新作 | New releases | 新作 | 揺れなし |
| {アーティスト} の新作 | New from {artist} | {artist} の新作 | 揺れなし |

### ポッドキャスト

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| エピソード (YT) | Episodes | エピソード | 揺れなし |
| 新しいエピソード (YT) | New episodes | 新しいエピソード | 揺れなし |
| エピソードが無い | No episodes | エピソードはありません | 揺れなし |
| 説明を広げる | Show more | すべて表示 | 揺れなし |
| 説明を縮める | Show less | 短く表示 | 揺れなし |
| RSS の番組を探して追加 | Find and add RSS shows | RSS の番組を探して追加 | 揺れなし |
| RSS の番組を探す | Find RSS shows | RSS の番組を探す | 揺れなし |

### ダウンロード

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| ダウンロード（ページ） (YT) | Downloads | ダウンロード | 揺れなし |
| ダウンロード済み | Downloaded | ダウンロード済み | 揺れなし |
| ダウンロード中 | Downloading | ダウンロード中 | 揺れなし |
| ダウンロード待ち | Waiting | 待っています | 揺れなし |
| ダウンロードに追加した | Added to downloads | ダウンロードに追加しました | 揺れなし |
| ダウンロードを削除した | Download removed | ダウンロードを削除しました | 揺れなし |
| ダウンロードを削除しますか | Remove download? | ダウンロードを削除しますか？ | 揺れなし |
| すべて削除 | Delete all downloads | ダウンロードをすべて削除 | 揺れなし |
| すべて削除しますか | Delete all downloads? | ダウンロードをすべて削除しますか？ | 揺れなし |
| すべて削除した | All downloads deleted | ダウンロードをすべて削除しました | 揺れなし |
| スマートダウンロード (YT) | Smart downloads | スマートダウンロード | 揺れなし |
| スマートダウンロードの説明（ダウンロードのページの見出し） | Automatically download songs you're likely to play | 聴きそうな曲を自動で取っておく | 揺れなし |
| スマートダウンロードの補足（ダウンロードのページ） | Picks from songs you play often, songs you might like and recommendations, refreshed daily | いつも聴いている曲・好きそうな曲・おすすめから、毎日選び直します | 揺れなし |
| スマートダウンロードの補足（設定のスイッチ） | Automatically download songs you're likely to play from songs you play often, songs you might like and recommendations, refreshed daily | いつも聴いている曲・好きそうな曲・おすすめから、聴きそうな曲を毎日選び直して取っておく | 揺れなし<br>※ Android はダウンロードのページの見出しも「Smart downloads」なので、こちらを使う |
| まだ選んだ曲が無い | No songs chosen yet | まだ選んだ曲はありません | Windows: No songs chosen yet. Songs are picked when you're online / まだ選んだ曲はありません。通信できるときに選んで取ります<br>※ Windows は後ろに「Songs are picked when you're online / 通信できるときに選んで取ります」が付く（揺れに出るのはこのため） |
| スマート: いつも聴いている | Played often | いつも聴いている | 揺れなし |
| スマート: 好きそう | You might like | 好きそう | 揺れなし |
| スマート: おすすめ | Recommended | おすすめ | 揺れなし |
| ダウンロードに使う通信 | Download over | ダウンロードに使う通信 | 揺れなし |
| どの通信でも | Any network | どの通信でも | 揺れなし |
| Wi‑Fi などだけ | Wi-Fi and other unmetered networks only | Wi-Fi など従量制でない通信だけ | 揺れなし |
| 通信を待っている | Waiting for a network | 通信を待っています | 揺れなし |
| 使っている容量 | Storage used: {size} | 使っている容量: {size} | 揺れなし |
| 残り | {n} left | 残り {n} 曲 | 揺れなし |
| オフライン | Offline | オフライン | 揺れなし |

### 端末の連携

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| 端末の連携（ページ） | Devices | 端末の連携 | 揺れなし |
| 連携している端末 | Your devices | 連携している端末 | 揺れなし |
| 端末を追加 | Add device | 端末を追加 | 揺れなし |
| 端末を外す | Remove | 外す | 揺れなし |
| 端末を外しますか | Remove this device? | 端末を外しますか？ | 揺れなし |
| 外した | Removed “{device}” | 「{device}」を外しました | 揺れなし |
| 外せない | Couldn't remove the device | 外せませんでした | 揺れなし |
| 加わる | Join | 加わる | 揺れなし |
| はじめる | Get started | はじめる | 揺れなし |
| 確認（6桁の数字） | Confirm | 確認 | 揺れなし |
| リセット | Reset | リセット | 揺れなし |
| 入力したら続ける | Continue after entering it | 相手に入力したら、続ける | 揺れなし |
| 文字列をコピー | Copy text | 文字列をコピー | 揺れなし |
| コピーした | Copied | コピーしました | 揺れなし |
| ログインを渡す | Give login | ログインを渡す | 揺れなし |
| ログインを渡しますか | Give your login? | ログインを渡しますか？ | 揺れなし |
| ログインを渡す（確かめの本文） | This gives “{device}” this device's YouTube login, so other apps (such as home-dashboard) can use your playlists, liked music and recommendations. Anyone with access to the machine running wave API can read them. | 「{device}」に、この端末の YouTube のログインを渡します。ほかのアプリ（home-dashboard など）から、あなたのプレイリスト・高く評価した曲・おすすめを使えるようになります。wave API を動かしている機械に入れる人は、それらを読めます。 | 揺れなし |
| ログインを渡した | Gave your login to “{device}” | 「{device}」にログインを渡しました | 揺れなし |
| 相手を確かめられない | Couldn't verify the other device | 相手を確かめられませんでした | 揺れなし |
| 端末の一覧を確かめられない | Couldn't verify the device list | 端末の一覧を確かめられませんでした | 揺れなし |
| 連携できない | Couldn't connect the device | 連携できませんでした | 揺れなし |
| 連携の文字列・QR ではない | That isn't a wave pairing code | wave の連携の文字列ではありません | Android: This isn't a wave pairing QR code / wave の連携の QR ではありません<br>※ QR を読んで加わる Android は QR の言い方（揺れに出るのはこのため） |
| 中継につながらない（端末の連携の状態） | Couldn't connect | つながりませんでした | 揺れなし<br>※ 通信の失敗の「通信できませんでした」とは分けている |
| 渡す（ボタン） | Give | 渡す | 揺れなし |
| ログインを渡せない | Couldn't give your login | ログインを渡せませんでした | 揺れなし |
| 連携のまとまり | (device) group | 連携 | —<br>※ Android の旧い household / 家 も、ほかと同じ group / 連携 にそろえた |
| 再生中の端末 | Active device | 再生中の端末 | 揺れなし |
| 現在の端末（端末を選ぶ欄） | Current device | 現在の端末 | 揺れなし |
| ほかの端末を選択 | Select another device | ほかの端末を選択 | 揺れなし |
| 端末に接続 | Connect to a device | 端末に接続 | 揺れなし |
| 端末の連携の設定 | Device settings | 端末の連携の設定 | 揺れなし |
| 接続（ボタン） | Connect | 接続 | 揺れなし |
| 接続を外す | Disconnect | 接続を外す | 揺れなし |
| 接続中（状態） | Connected | 接続中 | 揺れなし |
| 接続している（進行） | Connecting… | 接続しています… | 揺れなし |
| 中継に接続している | Connecting to the relay… | 中継に接続しています… | 揺れなし |
| 相手に送っている | Sending to the other device… | 相手の端末に送っています… | 揺れなし |
| 相手が加えるのを待っている | Waiting for the other device to add this {PC}… | 相手が加えるのを待っています… | 揺れなし |
| この端末で再生 | Play on this {PC} | この {PC} で再生 | 揺れなし |
| 操作だけ（端末の印） | {device} (remote only) | {device}（操作だけ） | 揺れなし |

### 設定

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| 設定 (YT) | Settings | 設定 | 揺れなし |
| 設定を開く | Open settings | 設定を開く | 揺れなし |
| アカウント | Account | アカウント | 揺れなし |
| ログイン | Sign in | ログイン | 揺れなし |
| ログアウト | Sign out | ログアウト | 揺れなし |
| ログアウトしますか | Sign out? | ログアウトしますか？ | 揺れなし |
| ログインしていない | Not signed in | ログインしていません | 揺れなし |
| ログイン中（状態） | Signed in | ログイン中 | 揺れなし |
| ログインした（知らせ） | Signed in | ログインしました | 揺れなし |
| ログインしている（進行） | Signing in… | ログインしています… | 揺れなし |
| ログインが切れた | Your sign-in has expired. Please sign in again | ログインが切れました。もう一度ログインしてください | 揺れなし |
| 相手は別のアカウント | The other device is signed in to a different account | 相手は別のアカウントでログインしています | 揺れなし |
| ログインし直す | Sign in again | ログインし直す | 揺れなし |
| ログインし直しが要る | You need to sign in again | 再ログインが必要です | 揺れなし |
| 言語（見出し） | Language | 言語 | 揺れなし |
| 画面の言語 | App language | 画面の言語 | 揺れなし |
| 画面の言語: 端末に合わせる | Use device language | 端末に合わせる | 揺れなし<br>※ 本人の決め（2026-11-13） |
| 画面の言語: 日本語・英語 | 日本語 / English | 日本語 / English | —<br>※ 言語名はその言語のまま（全部そろっている） |
| 次の起動から | Takes effect the next time you open wave | 次の起動から反映されます | 揺れなし |
| 曲名を元の表記で | Show titles as released | 曲名を元の表記で | 揺れなし |
| 新作の通知 | New release notifications | 新作の通知 | 揺れなし |
| 登録したアーティストの新作 | New from artists you subscribe to | 登録したアーティストの新作 | 揺れなし |
| 再生履歴に残す | Save played songs to your YouTube Music history | 再生した曲を YouTube Music の再生履歴に残す | 揺れなし |
| Discord に再生中の曲を出す | Show what you're playing on Discord | Discord に再生中の曲を出す | 揺れなし |
| Discord に断られた | Discord refused the connection. Check the Application ID | Discord につなげませんでした。Application ID を確かめてください | 揺れなし<br>※ Application ID は Discord の画面の語なので英語のまま |
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
| 会話を削除 | Delete chat | 会話を削除 | 揺れなし |
| 送る | Send | 送る | 揺れなし |
| 止める | Stop | 止める | 揺れなし |
| 接続の文字列ではない | That isn't a wave Claude bridge pairing text | wave の Claude の中継の接続の文字列ではありません | 揺れなし |
| キューに追加した（Claude） | Added {n} songs to queue | {n} 曲をキューに追加しました | 揺れなし |
| 作って入れた（Claude） | Created “{title}” with {n} songs | 「{title}」を作成して {n} 曲追加しました | 揺れなし |
| Claude がエラーを返した | Claude failed: {error} | Claude が失敗しました: {error} | 揺れなし<br>※ 「〜に失敗しました」の形ではないので規則（1.8）の外。全部同じ |
| Claude につないでいない | Claude isn't connected yet. Connect it in Settings → Claude | Claude につないでいません。設定の「Claude」でつないでください | 揺れなし |
| Claude の中継に届かない | Couldn't reach the Claude bridge. Is the PC running it? | Claude の中継に届きませんでした。中継を動かしている PC が起きているか確かめてください | 揺れなし |
| Claude の中継に断られた | The Claude bridge refused the token. Connect again in Settings | Claude の中継にトークンを断られました。設定でつなぎ直してください | 揺れなし |
| Claude の中継につないでいる（設定） | Connected to {name} | {name} の中継につないでいます | 揺れなし |
| 足した（Claude） | Added {n} songs to “{title}” | 「{title}」に {n} 曲追加しました | 揺れなし |

### 共通のボタン・知らせ

| 概念 | 正の英語 | 正の日本語 | 今の揺れ |
|---|---|---|---|
| キャンセル | Cancel | キャンセル | 揺れなし |
| 閉じる | Close | 閉じる | 揺れなし |
| 完了 | Done | 完了 | 揺れなし |
| 保存 | Save | 保存 | 揺れなし |
| 削除（本当に消すもの） | Delete | 削除 | 揺れなし |
| 削除（一覧から除く） | Remove | 削除 | 揺れなし |
| すべて削除 | Delete all | すべて削除 | 揺れなし |
| 戻る | Back | 戻る | 揺れなし |
| 進む | Forward | 進む | 揺れなし |
| 開く | Open | 開く | 揺れなし |
| 確かめている | Checking… | 確かめています… | 揺れなし |
| アカウントを確かめている | Checking account… | アカウントを確かめています… | 揺れなし |
| 使える／使えない／分からない | Available / Unavailable / Unknown | 使えます / 使えません / 分かりません | 揺れなし |
| 通信できない | Couldn't connect | 通信できませんでした | 揺れなし |
| 応答を読み取れない | Couldn't read the response | 応答を読み取れませんでした | 揺れなし |
| 動画を読み込めない | Couldn't load the video | 動画を読み込めませんでした | 揺れなし |
| ホームを読み込めない | Couldn't load Home | ホームを読み込めませんでした | 揺れなし |
| アーティストを読み込めない | Couldn't load the artist | アーティストを読み込めませんでした | 揺れなし |
| ポッドキャストを読み込めない | Couldn't load the podcast | ポッドキャストを読み込めませんでした | 揺れなし |

## 3. 残している違い

表と規則に合わせたあと、プラットフォームの間に残している違い。どれも理由があって残している。

- 端末の名前が入る文: `this PC` / 「この PC」、`this Mac`、`this iPhone`、Android の `this device` / 「この端末」は、それぞれの機械の呼び名のまま（同じ概念として扱う）
- Mac・iPhone のウィジェット・ショートカットの設定（App Intents）: Title Case（`Recently Played`・`Choose What to Play`）と、日本語の差し込みの前後に空白を入れない書き方（`${item}を再生`）は Apple の決まりに合わせたまま（1.1・1.7 の例外）
- QR を読んで加わる Android だけが、連携の文字列の代わりに QR の言い方（`This isn't a wave pairing QR code` / 「wave の連携の QR ではありません」）
- 機械によって動きが違うところの補足: iPhone の新作の通知（閉じている間は iPhone が決めた時に確かめる）、Windows のスマートダウンロードの「まだ選んだ曲はありません。通信できるときに選んで取ります」、Linux の「設定の「端末の連携」」（Linux は端末の連携が設定の中にある）、ダウンロードをすべて削除の本文（Windows はプレイリスト・アルバムも、Linux はエピソードも消すので、その分だけ並べる）
- `Couldn't connect` の日本語: 通信の失敗は「通信できませんでした」、端末の連携の画面の中継の状態は「つながりませんでした」（どちらも全部のプラットフォームで同じ使い分け）
- YouTube から届く文字は訳さない: プレイリストの行の補足（「（アカウントの名前） • 363 トラック」のような YouTube の書き方）、ホームの棚の見出しなど。高く評価した曲のページの題だけは、YouTube の「高く評価した音楽」/ `Liked Music` ではなく、アプリの名前「高く評価した曲」/ `Liked music` を出す

## 4. 迷って決めたもの（本人に確かめたい）

1. **ログインを渡す（`Give login`）**: 名詞の `login` を残している。英語として自然なのは `Share login` かもしれない
2. **アポストロフィ**: Android に合わせて `'` にした。引用符は `“ ”` なので、字形をそろえるなら `’` にする案もある
3. **「デバイス」→「端末」**: 「現在のデバイス」「ほかのデバイスを選択」「デバイスに接続」は 5〜6 つのプラットフォームでそろっていたが、規則（1.8）で「端末」に変えた
4. **「会話を消す」→「会話を削除」**: 5 つでそろっていたが、規則（本当に無くなるものは「削除」）で変えた
5. **「再生/一時停止」→「再生・一時停止」**: 「/」が 4 つで多かったが、並べる記号の規則（1.7）で「・」にした
6. **アルバム・アーティストへの移動**: YouTube Music の日本語版が「〜へ移動」か「〜に移動」か確かめられていない。そろっている「アルバムへ移動」「アーティストへ移動」のまま
7. **「新作」**: YouTube Music の日本語版の `New releases` の訳を確かめられていない。そろっている「新作」のまま
8. **曲数と長さ（1.6）**: 合計の長さを Windows・Mac・Android の「分 秒」の形にそろえた。Linux の短い形（分だけ）にそろえる案もある
9. **高く評価した曲のページの題**: 全部のプラットフォームが YouTube から届く題（「高く評価した音楽」/ `Liked Music`）を出していて、ライブラリの行の「高く評価した曲」と食い違っていた。アプリの名前に固定した。YouTube の題に戻すなら、ライブラリの行も「高く評価した音楽」にする
10. **再生履歴のページの名前**: Mac・GTK だけが `Recently played` / 「最近再生した曲」（ホームの棚と同じ名前）だったので、ほか 4 つの `History` / 「再生履歴」にそろえた
11. **プレイリストの行の「トラック」**: 補足の「363 トラック」/ `363 tracks` は YouTube から届く文字のまま出している（全部のプラットフォーム）。アプリの語の「曲」/ `songs` に置き換えるかどうか（置き換えるなら、5 つの読み取りの層に同じ手を入れる）
12. **Claude のエラー**: `Claude failed: {error}` / 「Claude が失敗しました: {error}」は全部そろっている。1.8 の「〜に失敗しました」の形ではないので残したが、「Claude からエラーが返りました」のような言い方にする案もある

## 本人が決めたこと（2026-11-13）

- ライブラリまわりは YouTube Music に合わせる（Library／ライブラリ、Liked music／高く評価した曲）
- 曲のメニューは YouTube Music に合わせる（Save to playlist／プレイリストに保存、Up next、Start radio／ラジオを開始）
- 画面の言語の選び肢は Use device language／端末に合わせる
- ログインの英語は Sign in／Sign out
