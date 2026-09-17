# ログインは WebView で行い、cookie を端末の中だけに暗号化して保存する

## 決定

アプリ内の WebView で `accounts.google.com` にサインインし、`music.youtube.com` に着地したら
`CookieManager` の cookie と、ページの `yt.config_` の `VISITOR_DATA`・`DATASYNC_ID` を読み取る。
値は Android Keystore の AES-256-GCM 鍵で暗号化して DataStore に保存し、PC やログには出さない。

- JS インターフェース（`addJavascriptInterface`）は公開しない。オリジンを制限できないため。値は `evaluateJavascript` の戻り値として受け取る
- ログイン用 WebView はデータディレクトリを分け（`setDataDirectorySuffix("login")`）、アプリの起動のたびにディレクトリごと消す。取得後とログイン画面を途中で離れたときは、cookie とストレージを消す
- WebView 内で開くのは、ログインに必要な Google・YouTube のホストを列挙したものだけ。利用者がページを置ける Google のホスト（sites など）は含めない
- 鍵は利用者認証も端末のアンロックも要求しない（画面ロック中のバックグラウンド再生で使うため）
- 復号に失敗しても保存物は自動では消さない。利用者の操作で消せるようにする

## 脅威モデル

守るものは2種類で、守り方の強さが違う（2026-09-17 に分けて書いた、Phase 6 の決定 K-0d）。

**認証秘密**（cookie・SAPISID・DATASYNC_ID、Phase 6 以降は端末の鍵）:

- 他のアプリからの読み取り（アプリのサンドボックス）
- クラウドバックアップと端末間の転送（`allowBackup="false"` と `dataExtractionRules` で除外）
- ストレージを単純にコピーされた場合（鍵は Keystore から取り出せない）
- ログ・例外文・画面の保存状態（Bundle）への漏れ
- 端末の外に出さない（端末間の受け渡しでも送らない。受け取った端末は自分のログインで解決する）

**アカウント由来の視聴データ**（キュー・再生位置・非公開プレイリストの曲・評価の状態）:

- 他のアプリからの読み取り、クラウドバックアップと端末間の転送、ログへの漏れからは守る（上と同じ）
- ストレージの単純コピーからは守らない（キューの保存ファイル `no_backup/queue.json` は暗号化しない）。ログアウトとアカウントの切り替えで消す
- 端末の外に出るのは、ペアリングした自分の端末への暗号文だけ（ADR-0008）

守らないもの:

- root 化された端末、アンロックされた状態の紛失端末
- デバッガや DevTools が接続された debug ビルド。Chromium は debuggable なアプリで WebView のリモートデバッグを強制的に有効にし、`setWebContentsDebuggingEnabled(false)` は効かない
- アプリのプロセスそのものの侵害
- 改変された APK（公開ソースから同じログイン画面を持つアプリを作れる）

保存する cookie は、今は `music.youtube.com` に送られる Cookie ヘッダ全体。漏れた場合の被害は、YouTube のログインセッションに及ぶ。
必要な cookie だけに絞れるかは、実機で測ってから判断する。

## Considered Options

### OAuth（デバイスフロー）— 却下

YouTube Music の InnerTube は、Premium 音質のストリームを cookie + SAPISIDHASH のセッションで返す（ADR-0001）。
OAuth トークンでは同じ応答を得られない。

### Chrome Custom Tabs — 却下

ログインはできるが、アプリから cookie を読み取れない。

### 保存せず、起動のたびにログインする — 却下

漏れる範囲は小さくなるが、音楽アプリとして使えない。

## Consequences

- ログイン用 WebView のデータディレクトリの設定はプロセスで1回しかできず、そのプロセスのすべての WebView に効く。
  ログイン以外の用途で WebView を使う場合は、別プロセスにする
- ログアウトは端末から消すところまでで、Google 側のセッションは失効させない
