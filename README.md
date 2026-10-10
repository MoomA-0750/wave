# wave

YouTube Music の自作クライアント。いまは Android（Jetpack Compose）が対象で、共有コアは Kotlin Multiplatform で書く。

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

## 認証情報の扱い

ログインは WebView で Google にサインインし、music.youtube.com の cookie を端末の中だけに暗号化して保存する。
何を守り、何を守らないかは [ADR-0002](docs/adr/0002-webview-login-and-threat-model.md) を参照。

debug ビルドでは、WebView のリモートデバッグ（chrome://inspect）とデバッガの接続を止められない。
実際のアカウントでログインしている間は、DevTools・デバッガ・App Inspection を接続しないこと。

## ライセンス

GPL-3.0。全文は [LICENSE](LICENSE) を参照。
