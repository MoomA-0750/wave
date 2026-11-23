# 再生画面まわりの要望（2026-11-23）: 調べの結果と進め方

本人の要望（スクショ付き）:

1. PC・Android でも、iOS のようにキューを上にスクロールすると履歴が出る仕様にする
2. Mac: キューのリストの左右の余白が無さすぎる（右の欄の行）
3. Mac: ページの一番下が、浮いている再生バーに隠れて触れない
4. PinP: Windows は、出すとそのウィンドウだけになりリサイズできない。元のウィンドウと別のウィンドウを出し、リサイズできるようにする
5. PinP: Mac は別ウィンドウだが両方に動画が出る。PinP を出したら、元の表示には「PinP に出しています」と出す
6. PinP の窓にホバーしたとき、曲名・いいねボタン・シークバーも出す
7. ウィンドウを×（Mac は赤丸）で閉じても、アプリを終了せず裏で曲を止めない設定を足す
8. 一度ビデオに切り替えたら次の曲以降も維持する。曲が始まってから動画の読み込みが始まるので、最初の数秒を先読みしてすぐ映像を出す

## 調べの結果（読み取り。実機では動かしていない。Mac の 3 の原因は推定）

パスは `mac/` が `mac/Sources/Wave/` 省略。

### 1・2・3 キュー・余白

- iOS の仕組み: `iOS/Player/QueueView.swift` の `embeddedList`（:38-77）。履歴は `player.queue` の `index` より前の行。1 本の `List` に「履歴・目印行（id `queue-now`）・次の曲」を並べ、開いたときと曲が変わったときに `scrollTo(nowId, anchor: .top)`。下に `.contentMargins(.bottom, 480)` で次の曲が少なくても目印を上へ送れる
- 他端末は「キュー｜履歴」のタブで、上スクロールでは出ない: Mac `Mac/Views/RightPane.swift:179`（状態 `MainWindowModel.queueHistory` :45、`queueEntries()` :249）、Windows `MainWindow.xaml:333-391`・`MainWindow.xaml.cs:860-908`、Android `nowplaying/NowPlaying.kt:771`（`QueueSheet`）・`:1321`（`QueuePane`）、Linux GTK `wave-gtk/src/ui/shell.rs:1790`（Qt の `QueuePane.qml` は未調査）
- 見立て（各端末 小〜中、50〜120 行）: タブをやめ、履歴＋今＋次を 1 本の一覧にして今の曲へ scrollTo する。並べ替えの禁止の判定が履歴行にも要る
- 2 Mac の余白: `RightPane.swift:214` の `List` が `.listRowInsets` を指定していない。行（:215-218）に `.listRowInsets(…leading: s4 …)` を足すのが最小
- 3 Mac のページ下: `MainView.swift:49-51` の `NavigationSplitView` 全体に付けた `.safeAreaInset(edge: .bottom)` がバー。`ContentPane.swift:9-14` のページ側に下の余白が無い。`ContentPane` で `.safeAreaPadding(.bottom, バーの高さ)` を 1 か所に付けるのが現実的。右欄の `List` と左の `LibraryPane` も同じ。Windows・Android・Linux の帯は別の行・下段で重ならない

### 4・5・6 PinP

- Windows: `MainWindow.xaml.cs:716-750` の `SetPip`。同じ `Window` の `AppWindow.SetPresenter(CompactOverlay)` に替えて 480×270 にしている。別窓を作らず、CompactOverlay は OS 仕様でリサイズ不可。見立て（中、150〜250 行）: Mac・Linux と同じく 2 枚目の `Window`（`OverlappedPresenter`、最前面、リサイズ可）に `MediaPlayerElement` を置き、`SetMediaPlayer` を付け替える。ホバーで曲名・いいね・シークバーを出す重ね部品を新規に作る。`SPEC.md:21` に Windows の仕様が書いてあるので直す
- Mac: `Mac/Views/VideoPip.swift`（`NSPanel`、`PipView` :70-111、ホバーは `HoverTracker`）。ホバー時は前・再生・次・「wave に戻す」だけ。曲名・いいね・シークバーは無い。見立て（小、数十行）: `PipView` の `if hover` に曲名（`player.current`）、`LikeButton.view(player, size:)`（`RightPane.swift:30`）、位置スライダー（`PlayerBar` の部品）を足す。元の画面の「PinP に出しています」は、`VideoOrCover`（`PlayerBar.swift:211`）が `model.pipOpen` のとき映像でなく案内を出す（右欄・メニューバー・全画面）
- Linux GTK: `wave-gtk/src/ui/shell.rs:960-1029`（`mini_window`）。曲名・いいね・シークを足す（小）
- Android: OS の PiP（`nowplaying/VideoPip.kt`）で、アプリ側の部品は描けない。`setActions` の再生・前・次だけ可
- iOS: OS の PiP。対象外

### 7 窓を閉じても止めない設定

設定も実装も無い。Mac は `WaveApp.swift:61` の `applicationShouldTerminateAfterLastWindowClosed → true`。Windows は `MainWindow.xaml.cs:1422-1437` の `Closing`（移す作業中だけ隠し、再生も止める）。Linux GTK は主の窓の閉じる処理が無く、アプリごと終了。Android は前景サービスで鳴り続ける（対象外）。見立て（小〜中）: 設定の Bool を足し、Mac は false を返して Dock の再オープンで窓を戻す、Windows は `Closing` で隠して再生を残し戻す手段（トレイが無いので通知アイコンかメディアキー）を設計する、Linux は `app.hold()` ＋ `set_hide_on_close(true)`

### 8 ビデオの維持と先読み

- 維持: Mac `MainView.swift:74` の `showVideo = false` を外す。iOS `NowPlayingView.swift:44` も同様。Windows は `MainWindow.xaml.cs:562` の `ShowVideo(false)` と `UpdateVideoSwitch`（:780）が戻すので、「望む状態」と「出せるか」を別の変数にする。Android は `VideoPip.selected` がグローバルで、すでに維持される（MV の無い曲で戻る）。Linux `shell.rs:779-783` のリセットを外す
- 先読み: どの端末にも映像の先読みは無い（音声だけ）。次の曲の MV の有無（`songInfo`）を先に引く部分が無いので、中規模（端末ごとに 80〜150 行）。`prefetch()` に「次の曲の MV が分かり、動画の維持が ON なら、動画の URL を解決して先頭を取る」を足す

## 進め方（フェーズ。フェーズごとにセッションを替える）

1. 調べ（済み）
2. キュー: 履歴を上スクロールで（全端末）・Mac の余白・Mac のページ下
3. PinP: Windows の別窓化・Mac の案内・ホバーの曲名・いいね・シークバー（Mac・Windows・Linux）
4. 窓を閉じても止めない設定（Mac・Windows・Linux）
5. ビデオの維持と先読み（全端末）

確かめ方: Windows は to-fu（`scripts/win.sh`）、Android は Fold 7・Pixel 3、Linux は cramgent。Mac は Linux 機からは組み立てられないので、Mac の変更は「未確認」と書き、本人に Mac で確かめてもらう（`swift build`・実機）。
