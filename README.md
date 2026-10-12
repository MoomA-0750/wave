# wave

YouTube Music の自作クライアント。いまは Android（Jetpack Compose）・Windows（WinUI 3）・macOS（SwiftUI）が対象で、共有コアは Kotlin Multiplatform で書く。

InnerTube との通信には [innertubex](https://github.com/MetrolistGroup/innertubex)（GPL-3.0）を使う。

## 構成

| モジュール | 内容 |
|---|---|
| `core` | 共有コア（KMP: Android + desktop JVM）。desktop ターゲットは、ホスト上でテストを速く回すために置いている |
| `androidApp` | Android アプリ（Compose）。flavor でスマホ（`phone`、Material 3 Expressive）と時計（`wear`、Wear OS の Material 3）を組む。共通の部品は `src/main`、画面はそれぞれの flavor に置く |

設計の決定は `docs/adr/` にある。

## ビルド

必要なもの:

- JDK 21（Android Studio 同梱の JBR でよい）
- Android SDK Platform 37（innertubex が compileSdk 37 を要求する）

```sh
export JAVA_HOME=/opt/android-studio/jbr   # 環境に合わせる
./gradlew :core:desktopTest :androidApp:assembleDebug
```

依存は `gradle/verification-metadata.xml` の sha256 で検証する。依存を変えたら、次のコマンドで更新し、差分を確認してからコミットする。

```sh
# help だけではビルド時にしか取得しない依存（aapt2 など）が記録されないので、実際のタスクを流す
./gradlew --write-verification-metadata sha256 :core:desktopTest :androidApp:assembleDebug
```

commonMain に JVM・Android 専用の API が混ざっていないかは、次で確かめる（今のターゲットはどちらも JVM なので、ビルドでは気づけない）。

```sh
scripts/check-common-main.sh
```

## 時計（Wear OS）

`./gradlew :androidApp:assembleWearDebug` で時計版（`androidApp-wear-debug.apk`）を組む。スマホ版と同じ applicationId・同じ署名で配る（時計とスマホの間の Data Layer に要る）。

- ログインはスマホから受け取る（時計で「スマホに頼む」→ スマホに出た番号を見比べて「送る」。[ADR-0011](docs/adr/0011-watch-login-transfer.md)）
- スマホの再生の操作・時計とスマホの間の受け渡し・Claude への頼みは、Data Layer でスマホを通す（時計は tailnet の中継に届かない）
- 確かめは to-fu の Wear OS エミュレーターで行う（`scripts/watch.sh`）。時計でできないことは [docs/wear.md](docs/wear.md) にまとめた
- 基本操作のテスト: `python3 scripts/watch_smoke.py`（ログインした時計のエミュレーターで、検索・アーティスト・プレイリスト・再生・ベゼルの音量・曲送り・一時停止と再開・プレイリストへの追加と削除を流す。アカウントには確かめ用のプレイリストを作って消すことだけを書き込む）

## macOS

`mac/` に macOS 版（SwiftUI、macOS 15 以降）がある。画面も機能も Windows 版（`windows/`）と同じにする。`WaveCore` は Windows の Wave.Core を Swift へ移したもの（端末の連携は Android・Windows とバイト単位で互換）、`WaveMac` は画面。

組むのは Mac（`ssh mac`、Xcode）で、この機械からは `mac/scripts/mac.sh` で送って組む。

```sh
mac/scripts/mac.sh test     # WaveCore のテスト
mac/scripts/mac.sh run      # wave.app を組み、Apple Development の証明書で署名して起動する（開発用の命令を入れて組む）
mac/scripts/mac.sh cmd "search 米津玄師" "now"   # 開発用の命令を送る（Sources/WaveMac/Support/DebugCommands.swift）
mac/scripts/mac.sh log      # アプリのログ（~/Library/Application Support/wave/wave.log）
```

- 署名は Mac の画面側のターミナルで行う（`scripts/sign.command`。ssh からはログインキーチェーンが閉じていて署名できない）。Team ID の付いた署名にしておくと、組み直してもキーチェーンに保存したログイン情報をそのまま読める
- ログイン情報は macOS のキーチェーン（このユーザー・この Mac だけ）に保存する。Windows の DPAPI に当たる
- 再生は AVPlayer。署名付きの URL は渡さず、仮の URL（`wave-media://`）を渡して、求められた範囲をアプリが取って返す（Windows の DASH の DownloadRequested と同じ考え）
- 端末の連携の中継は、Windows と同じく設定ファイル（`settings.json` の `relayV2Url`・`relayV2Host`）に書いたときだけ使う

## Discord に再生中の曲を出す（Windows・macOS）

PC 版（Windows・macOS）は、同じ PC で動いている Discord のアプリ（ローカルの IPC）を通して、Discord のプロフィールに「wave を聴いています」と曲名・アーティスト・ジャケット・残り時間を出せる。既定はオフ。

1. [Discord Developer Portal](https://discord.com/developers/applications) で「New Application」を押し、名前を **wave** にして作る（この名前が「〜を聴いています」に出る）
2. 「General Information」の **Application ID** をコピーする
3. wave の設定 →「Discord」で「Discord に再生中の曲を出す」をオンにし、Application ID を貼る（`settings.json` の `DiscordPresence`・`DiscordClientId`（Windows）／`discordPresence`・`discordClientId`（macOS）に入る）

- Discord のアプリが後から起動しても、15 秒おきにつなぎ直す。一時停止すると消える
- 家のほかの端末（スマホなど）で鳴らしていて、PC の wave がそれを映しているときは、その曲も出す（画像の上の文字に「〜で再生中」と出る）。スマホの Discord には、ほかのアプリが状態を出す手段が無いため
- 送るのは曲が変わった・再生／一時停止・シークのときだけ（シークは 15 秒に 1 回まで）

## 認証情報の扱い

ログインは WebView で Google にサインインし、music.youtube.com の cookie を端末の中だけに暗号化して保存する。
何を守り、何を守らないかは [ADR-0002](docs/adr/0002-webview-login-and-threat-model.md) を参照。

debug ビルドでは、WebView のリモートデバッグ（chrome://inspect）とデバッガの接続を止められない。
実際のアカウントでログインしている間は、DevTools・デバッガ・App Inspection を接続しないこと。

## ライセンス

GPL-3.0。全文は [LICENSE](LICENSE) を参照。
