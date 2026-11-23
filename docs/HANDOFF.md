# 引き継ぎ（今の状態）

更新: 2026-11-24（iPhone の Live Activity・ミニプレーヤーの作業の後）（セッション 4033c98e、ブランチ ios-app）

## 目的

Windows の wave の「Spotify から移す」。Spotify のプレイリスト・高く評価した曲・保存したアルバム・フォローしたアーティストを YouTube Music へ移す。仕様は `SPEC.md` の「Spotify から移す」、経緯は `docs/decisions.md` の 2026-11-18〜22。

## どこまでできたか

- 突き合わせは全 7933 件が終わった。要確認と見つからないだけを新しい比べ方で突き合わせ直した結果、一致 7509・要確認 375・見つからない 49 になった。新しい比べ方は、ローマ字と漢字の名前の組を学ぶ、曲名の後ろの読みや「( + …)」を外す、の2つ。突き合わせの検索はログインしない通信で、10 秒おき・50 件ごとに 5 分休む速さで行い、断られたのは 0 回
- 見直しの画面に「Claude で探す」を足した。Claude は家の PC の ai-bridge を通して呼ぶ。試した結果:
  - 3 件: 確かな一致が 1 件、見つからないが 2 件
  - 10 件（全部 Milkoi）: 要確認が 1 件、見つからないが 9 件。ファンの再アップロードは選ばなかった
  - 見つからないと答えた件は、自動で選んでいた候補を外すようにした
- 見直しの「YouTube Music で探す」で、上位の結果（動画も含む）と曲の検索を合わせ、YouTube の並びのまま全部を出すようにした。リンクや ID（曲・アルバム・共有のアルバム・アーティスト）を貼っても選べる（11-22 15:00、実機で確かめ済み）
- 本人が手で選んだ 29 件（11-22 時点。アルバム 28・アーティスト 1。全部が「見つからない」で、候補が 0 件）を分析した: `docs/transfer-manual-analysis.md`。原因は、アニメや VTuber のシングルの一部がアルバムの検索に載らないこと。同じ語で曲の検索をすると、結果の曲のアルバムが正解だった
- YouTube Music への書き込みはしていない（本人が見直しの一覧を見て決める）

- 案 1 を入れた（`TransferMatcher.AlbumsFromSongs`、`TransferRunner.MatchOneAsync` の Album の分岐。テスト 333 件が通る）。to-fu でアルバムの見つからない・要確認 74 件（手で選んだものを除く）だけを 10 秒おきで突き合わせ直した（30.7 分、断られた回数 0）。アルバム: 一致 366→397、要確認 56→42、見つからない 18→1。全体: 一致 7514→7545、要確認 349→335、見つからない（手で選んだ 29 件を含む）70→53。曲・アーティストは変えていない。`transfer-rematch-unsure album` でアルバムだけを回せる。誤一致の確かめ（一致になった 31 件の中身を見る）はまだ

- 案 3 の一部と案 4 を入れた（2 本目の検索語 `RetryAlbumQueries`・「Various Artists」を名前として比べない・`ArtistsOf` が「、」でも分ける。テスト 336 件が通る）。アルバムの要確認 42 件＋見つからない 1 件を 10 秒おきでもう一度回した（22 分、断られた 0）: アルバム 一致 397→399、要確認 42→41、見つからない 1→0。全体: 一致 7547・要確認 334・見つからない 52（手で選んだ 29 件を含む）。効きは小さい。残る要確認 41 件は点が中くらいの別の版が 1 位にいるもの
- AI コンテンツの識別（本人の了承済みの方針）: 対象は曲とアーティスト。公式の表示（確定）と自前の手がかり（疑い）を見分けられる形で両方出す。「印・絞り込み」と「移すときの除外」は別々の機能。外部の判定サービスは既定で使わず、設定から足せる形。最初の確認として、YouTube Music の匿名の応答（Velvet Sundown の曲の next）には AI の表示が見つからなかった（AI ラベルの付いた曲を確認できていないので未確定）。Spotify の API・ZIP に AI クレジットがあるかも未確認。次は、ラベルが付いた曲を 1 件見つけて応答を調べる、ログイン済みの応答で試す

- 案 2 を入れた（手で選んだアルバム・アーティストからも名前の組を 1 回で学ぶ `NameAliases.Learn`、リンクで選んだアルバムの補足にアーティストを入れる `SubtitleWithArtists`。テスト 338 件が通る）。to-fu でアルバムの要確認を回す命令（`transfer-rematch-unsure album`）を 11-23 に送った。結果は to-fu の wave.log の「transfer match stop=」の行（`scripts/win.sh ps` で `%LOCALAPPDATA%\wave\wave.log` を読む）。件数の確かめは `scripts/win.sh ps < count.ps1` の形（保存データ spotify-transfer.json を Kind/Status で数える）。結果（11-23 06:25、断られた 0）: アルバムは一致 399・要確認 41・見つからない 0 のまま変わらず（案 2 だけでは動かない。残りは別の版が 1 位にいるもの）。全体は一致 7547・要確認 334・見つからない 52
- AI ラベルの確認（続き）: Xania Monet・Breaking Rust・Sienna Rose の曲の search・next・player（匿名、WEB_REMIX）にも AI の語は出なかった。ログイン済みの応答か、ラベルの付いた曲の特定が要る

- 曲の項目にも 2 本目の検索語を入れた（`RetryTrackQueries`。テスト 340 件が通り commit 済み）。11-23 朝に to-fu で `transfer-rematch-unsure track`（曲の要確認・見つからない 約 293 件、10 秒おき）を回し始めた。終わると to-fu の wave.log に「transfer match stop=」が出る。その件数（前: 一致 7547・要確認 334・見つからない 52）を書き足す。曲の変化の誤一致は、一致が増えた分を見直しで確かめる
- push 済み（11-23、本人の指示。ここまでの commit）

- AI ラベルの設計は `docs/ai-content-design.md`。調べの命令 `ai-label-probe <videoId ...>`（DebugCommands。ログイン済みの next・player の応答から AI らしい語のパスを記録）を書いたが、**まだビルドしていない・試していない**（ビルドすると to-fu のアプリが止まり、実行中の曲の突き合わせ直しが切れるため、終わってから）。終わったら `scripts/win.sh build` → `run` → `debug-command.txt` に `ai-label-probe 動画ID …` を書き、wave.log の「ai-label-probe」の行を読む
- 容量: Linux 側のリポジトリ 9.4GB の大半は `linux/target`（8GB、Rust のビルド作業。消してよい）。`androidApp/build` 682MB も消してよい。本人の了承の後に消す（確認一覧）

- 容量（11-23）: ビルドの作業（`linux/target`・`androidApp/build`）と `.claude/worktrees` を消した。Fold 7 の wave は、設定の「ダウンロードをすべて削除」で 7.9GB を消し（使用 0B）、端末の空きが約 27.3GiB→34.7GiB（+7.7GB）になった。ただし端末の wave のアプリデータは diskstats で 14.6GB と出ていたので、ダウンロード（7.9GB）以外の約 6.7GB の正体は未解明（diskstats の値は古いまま更新されていなかったので、あとで測り直す。release ビルドで `run-as` が使えず中身を直接は見られない）。端末の wave は古い版（容量の上限の設定が無い）で、スマートダウンロードが入っている
- スマートダウンロードの容量の上限は 5 つの実装に入れた（試験: Windows 341 件・Kotlin core・Rust core は通過、Swift は未確認）。実機で動くことは未確認

- Fold 7 に容量の上限の入った新しい版（`:androidApp:assemblePhoneRelease`、debug 鍵で署名）を `adb install -r --user 0` で上書きで入れた（ログイン・設定は残った）。設定に「Smart download storage limit 5 GB」が出ることを実機で確かめた。ダウンロード削除のあとスマートダウンロードが 73MB まで取り直している。残り約 6.7GB の可能性: Fold 7 の Secure Folder（ユーザー 150）の中の wave（adb の shell では中を見られない。本人に確かめてもらう）

## Spotify から移す: 曲の 2 本目の検索語の結果（11-23 12:23 終了）

曲の要確認・見つからない 約 293 件を回した（途中で to-fu のアプリが止まり、残り 41 件は再開して終えた。断られた 0）。曲: 一致 6809→6840、要確認 270→261、見つからない 23→1。全体: 一致 7578・要確認 325・見つからない 30（手で選んだ 29 件を含む）。案 1〜4 と曲への 2 本目の検索語まで入れ終えた。残りの要確認 325 件（曲 261・アルバム 41・アーティスト 23）は、別の版が 1 位にいるものが中心なので、見直しの画面で選ぶ。YouTube Music への書き込みはしていない（本人が見直しの一覧を見てから）

## appshelf への載せ込み（11-23、本人の依頼「全ての版を載せて」）

載せた（`scripts/shelf.sh`、commit ddb29c9e の中身）: Android（wave・build 20261123-133841-202611231331）、Windows（x86_64・20261123-133219-202611231331）、Linux（x86_64・20261123-133926-202611231331-x86_64）、時計（wave-wear・20261123-134505-202611231345）。版番号はすべて 0.1.0。Mac にログインしてもらってから macOS（build 202611231459・commit af39bc06）と iOS（build 20261123-150259-202611231502）も載せた。載せていない: Linux aarch64（cramgent に ssh が通らない。通ったら `scripts/shelf.sh linux-arm`）。載せる途中で、時計版が使う compose-bom 2 つの pom の sha256（dl.google.com の公開値と一致を確認）を `gradle/verification-metadata.xml` に足した。Mac の RightPane.swift の閉じ括弧の欠け（別セッションの変更）を直した（Mac でコンパイルが通ることは確認）

## 再生画面まわりの要望 8 件（本人の依頼 11-23。計画: `docs/player-requests-plan.md`）

フェーズ 1〜5 は済み（再生画面の要望 8 件は実装が一通り終わった。残りは未確認の端末の確認）。Mac の変更は Linux 機から組み立てられないので「未確認」と書き、本人に Mac で確かめてもらう。Windows は to-fu（`scripts/win.sh`）、Android は Fold 7・Pixel 3 で確かめる。まず `docs/player-requests-plan.md` を読む

フェーズ 2 の結果（キューを「履歴＋今＋次」の1本の一覧にし、開いたとき・曲が替わったとき今の曲を上へ合わせる。「キュー｜履歴」のタブは廃止）:
- Windows: to-fu で確認済み（履歴が上に出る・今の曲が上に来る）。`MainWindow.xaml.cs` の `RenderQueue`
- Android: Pixel 3 で確認済み（`NowPlaying.kt` の `QueueSheet`。Fold 7 は未）
- Linux GTK: 試験は通る（`shell.rs`）が、画面は未確認（Qt 版は未対応）
- Mac: **未確認**（`RightPane.swift` の QueueView・`MainWindowModel.queueEntries`。右欄の左右の余白＝`listRowInsets`、ページ下＝`ContentPane` の `safeAreaPadding(.bottom, 100)`。後者は二重に空く恐れがあるので Mac で見てもらう）
- `SPEC.md` を更新済み

フェーズ 3（PinP）の結果:
- Windows: to-fu で確認済み。`PipWindow.cs`（2 枚目の `Window`。最前面・リサイズ可。ホバーで曲名・アーティスト・評価・前/再生/次・シークバー・「wave に戻す」）。`MainWindow.xaml.cs` の `SetPip`。元の枠（右の欄・全画面）には `PaneVideoPipNote`・`FullVideoPipNote`（「PinP に出しています」）。×で閉じると元の枠へ戻る。曲が替わると「曲」の表示へ戻るので PinP も閉じる（維持はフェーズ 5）。to-fu の画面操作では PinP 窓を一度クリックして前に出さないとホバーが届かなかった
- Mac: **未確認**（`VideoPip.swift` の `PipView` に曲名・評価・シーク、`PlayerBar.swift` の `VideoOrCover` に `inPip` と案内）。Mac で `swift build` と実機の確認が要る
- Linux GTK: ビルド・試験は通る（`shell.rs` の `mini_window`）が、画面は未確認
- Android・iOS は OS の PiP で対象外

フェーズ 4（窓を閉じても再生を続ける設定 `keepPlayingWhenClosed`、既定は切）の結果:
- Windows: to-fu で確認済み（`MainWindow.xaml.cs` の `InitBackgroundTransfer` の `Closing`。窓が隠れ・プロセスが残り・再生が止まらない。戻すのは通知か wave をもう一度開く。`window-close`・`window-show` の開発用命令）。通知の見た目は未確認。設定画面のスイッチも画面では未確認
- Linux GTK: ビルドと設定の試験は通る（`main.rs` の `hide_if_keep_playing`・`HOLD`）が、画面は未確認
- Mac: **未確認**（`WaveApp.swift` の `applicationShouldTerminateAfterLastWindowClosed`・`applicationShouldHandleReopen`、`SettingsPage.swift` のスイッチ）。Mac で `swift build` と、赤丸で閉じて Dock で戻ることの確認が要る
- `SPEC.md`・`docs/decisions.md` を更新済み

フェーズ 5（ビデオの維持と先読み）の結果:
- 維持: Mac `MainView.swift`・iOS `NowPlayingView.swift`・Linux `shell.rs` は曲が変わったときのリセットを外した（ライブは Mac・iOS で次の曲に画質を合わせる）。Windows は `wantVideo`（望む）と `UpdateVideoSwitch`（出せるか）に分けた（`ApplyVideo`）。Android は `VideoPip.wanted` を足し、MV の無い曲で `selected` が外れても選びは残る
- 先読み: 次の曲の songInfo と、維持が ON のとき映像の URL を先に解決する。Windows `PlayerService.PrefetchVideo`＋`MusicVideoPlayer.PrefetchAsync`、Swift `PlayerService.prefetchVideo`＋`MusicVideoPlayer.prefetch`、Android `MusicVideos.prefetch`（`PlaybackService.prefetchVideo`）。映像の先頭のバイトは取っていない（理由は decisions.md）。Linux（Rust）は先読みを入れていない
- Windows: to-fu で確認済み（`keep=True` が MV の無い曲を挟んでも残り、曲の開始の 0.03 秒後に映像が読み込み済みになる）。ログで確認しただけで、画面で「動画」のタブが選ばれた見た目は見ていない
- Android: `compilePhoneDebugKotlin` と Pixel 3 への導入は通った。Pixel 3 が Windows の再生を映す状態だったため、手元の再生での維持・先読みは**未確認**
- Mac: Mac にログインしてもらい `mac.sh run` で起動、`mac.sh cmd` で確認済み（11-23）。`album-play`→`video on`→`next` を繰り返し、`showVideo=true` が MV の無い曲を 4 曲挟んでも残り、`video prefetch resolved` の後、MV のある曲（LOSTSCAPES）で映像が読み込まれ（`mv=ready`、1280x720）。画面の見た目は見ていない。`mac.sh test` 300 件・失敗 0。DebugCommands に `next` と、`now` に動画の状態を足した
- iOS: シミュレーター向けのビルドは通った（`ios-bundle.sh`）が、動きは未確認（シミュレーターを起動しない決まり。実機で確かめる）
- Linux: `cargo check`・試験が通る。次の曲の MV の有無だけ先に引く（`mv_prefetched`。映像の URL の先読みは無い）。画面は未確認
- `SPEC.md`・`docs/decisions.md` を更新済み

## iPhone の Live Activity・プレーヤーまわり（本人の依頼 11-23。セッション 4033c98e。設計: `docs/ios-live-activity-design.md`、経緯: `docs/decisions.md` の 2026-11-23）

appshelf（`mac.sh ios-shelf`、最新ビルド 20261124-004736）に載せ済み。iPhone へ入れるのは本人が appshelf から。実機の確認は本人の操作待ち。
- Live Activity: 再生の操作・位置の棒・歌詞を外し、評価のボタン（＋は一発で高く評価、✓は押すとアプリのプレイリスト追加画面）と次の 3 曲（押すとその曲へ）だけ。背景は透明（`.clear`）。設定「ロック画面の再生中」で入切。ロック画面の高さ・追加画面の二重表示・設定の入切は実機で確認済み。背景の透過・Dynamic Island 展開（2 曲）は未確認
- 小窓（PiP）が出ているとき、ロック画面から追加画面を開くと再生画面が重なって二重に出た問題は、`IOSShell.holdPlayerUntil` で直し、実機で確認済み
- 全画面プレイヤー: 斜めの引き下ろしで曲が替わる問題は、横が縦の 1.5 倍より大きいときだけ曲送りにして直した（実機は未確認）
- ミニプレーヤー: 左右スワイプで曲の欄が Safari のように動く（A。OS の付属部品の中）。さらに B（`MiniPlayerCard`、iOS 26 から）を足した: ガラスの枠ごと入れ替わる。枠は 2 つが欄を分け合って幅だけで入れ替わる（11-24 に 2 回作り直し。前の「縮みながら横へ動く」版は Safari と遠いと言われた。11-24 00:47 の版で、中身の動き・払い始め・横向き・位置・スナックバーの重なりを直した。実機の感想待ち）。位置は「下の安全な余白＋48pt」の見積もり、スクロールで畳む動きは無い。既定は B、設定「ミニプレーヤーの枠を自前で描く（試し）」で A に戻せる（`miniPlayerCustom`）。**本人が B を試して、元の方が良ければ A に戻す予定**（確認一覧にある）
- 関係する場所: `mac/Sources/WaveWidget/WaveWidgetBundle.swift`（`NowPlayingLiveActivity`）、`Shared/Widget/WidgetShared.swift`（`NowPlayingAttributes`）、`Shared/Services/WidgetSync.swift`（`updateActivity`）、`iOS/Player/MiniPlayer.swift`、`iOS/App/RootView.swift`（`MiniPlayerAccessory`）、`iOS/Player/NowPlayingView.swift`（ジャケットの横の払い）
- Mac は `ssh mac`（`mac/scripts/mac.sh`）。画面のセッションにログインしてもらうと `mac.sh run`・`cmd`・`log` で Mac 版も確かめられる。iPhone 実機へ入れるには Mac に iPhone をつなぐ（ロックを外す）か、appshelf を使う

## 次にやること（Spotify から移す・AI 識別。再生画面の要望と並行）

1. AI コンテンツの識別（本人の了承済みの方針は上の「どこまで」）。最初の確認: AI ラベルが付いた YouTube Music の曲の応答に表示が入っているかを調べる（本人が曲を教えるか、自分で探す。ログインしない通信と、必要ならログイン済みの応答）。Spotify の API・ZIP に AI クレジットがあるかも調べる。結果を見て `SPEC.md` に仕様（確定／仮）を書き、実装は本人の了承の後
2. 案 2 の残り: 同じ題の別項目の ID を候補に足す（一致にはせず候補だけ）
3. 案 3 の残り: 曲の項目にも 2 本目の検索語を使う（アルバムだけ入れた）。案 5: 曲の検索の上位 20 件まで見る
4. 件数の変化は `transfer-rematch-unsure`（引数 `album` でアルバムだけ）で数え、本人へ報告する。書き込みはしない

## 本人の判断待ち（確認一覧にもある）

- ミニプレーヤーを B（枠を自前で描く）で行くか A に戻すか。実機での確認（上の「iPhone の Live Activity・プレーヤーまわり」）

- YouTube Music へ書き込むか。要確認を見直してから書くか、一致だけを先に書くか
- Windows 機から中継への転送を常に張るか。to-fu の 127.0.0.1:18798 から家の PC の 8797 への `ssh -R`。11-22 17:00 に切れた（ssh が 255 で終わった）。「Claude で探す」を使う前に `ssh -N -R 18798:127.0.0.1:8797 <to-fu>` を張り直す
- Spotify のアカウントデータの ZIP が届いたら、ZIP の読み込みを試す

## 注意

- 裏で動かしている作業は無い。to-fu のアプリは起動したまま（突き合わせは終わっている）。to-fu の MCP（画面操作）は途中で切れたが、`scripts/win.sh`（ssh）は使えた
- 未 commit の変更は無い
- YouTube Music へ書き込む前に、必ず本人に見直しの一覧を見せる
- push は本人がこのセッションで直接頼んだときだけ
- Extended Streaming History（~/Downloads/spotify-extended-history/）には ip_addr が入っている。リポジトリに入れない
- 開発用の命令:
  - `transfer-rematch-unsure`: 要確認と見つからないだけを突き合わせ直す
  - `transfer-ai N`: 先頭の N 件を Claude で探す
  - `transfer-aliases`: 学んだ名前の組を出す
  - `transfer-pace 間ms 件数 休む秒`: 突き合わせの速さを変える
