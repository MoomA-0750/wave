# wave

YouTube Music の自作クライアント。いまは Android（Jetpack Compose）が対象で、共有コアは Kotlin Multiplatform で書く。

InnerTube との通信には [innertubex](https://github.com/MetrolistGroup/innertubex)（GPL-3.0）を使う。

## 構成

| モジュール | 内容 |
|---|---|
| `core` | 共有コア（KMP: Android + desktop JVM）。desktop ターゲットは、ホスト上でテストを速く回すために置いている |
| `androidApp` | Android アプリ（Compose + Material3） |

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
./gradlew --write-verification-metadata sha256 help
```

## ライセンス

GPL-3.0。全文は [LICENSE](LICENSE) を参照。
