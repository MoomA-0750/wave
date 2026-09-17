# innertubex の faraday（リモートのプレイヤー設定）を有効にする

## 決定

innertubex の `RemotePlayerConfigStore` を有効にし、MetrolistGroup/faraday が公開するプレイヤー設定を使う（Phase 3 のストリーム解決で接続する）。
YouTube のプレイヤーの変更への追従を、自前の解析だけに頼らないため。

## 確認したこと（innertubex v0.6.0）

- faraday の設定の表（`player_configs.json`）から取り込むのは、関数名・定数・`sts`・`aliases` だけで、それぞれ正規表現で検証される（`cipher/RemotePlayerConfigParser.kt`）
- **ただし、表に無いプレイヤーについては、faraday のリポジトリ（jsDelivr 経由の `registry/players/<hash>.json`）から前処理済みのプレイヤー JS を取得し、同梱のソルバが QuickJS の中で実行する**（`cipher/GitHubPlayerConfigClient.kt`、`EjsChallengeSolver.kt`）。表だけを有効にする設定は無い（2026-09-17 に訂正。当初は「JS として実行されることは無い」と誤って書いていた）
- QuickJS で実行されるのは、YouTube 自身のプレイヤー（`www.youtube.com/s/player/...`）と innertubex に同梱のソルバ。QuickJS にはネットワーク・ファイル・アプリの値へ触れるホスト関数が登録されていない
- faraday の設定は github.com・jsDelivr・raw.githubusercontent.com から、cookie を付けずに取得される
- 設定の取得先は `https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json`。innertubex に既定の URL は無く、形の合わない URL を入れると例外なしで無効になる

## 受け入れたリスク（2026-09-17、本人の決定）

第三者（MetrolistGroup/faraday）が配布する JS を、cookie を持つアプリのプロセス内の QuickJS で実行しうることを受け入れる。守りは次の2つ:

- QuickJS にはネットワーク・ファイル・アプリの値へ触れるホスト関数が登録されていない。悪意ある JS でも、普通に起こせるのは誤った解読結果を返すことまで
- 解読後の URL は `https` かつ `*.googlevideo.com` の `/videoplayback` だけを使う（ADR-0003）

残るリスクは QuickJS 自体のサンドボックスの脱出（未知の脆弱性）。

## Considered Options

### 無効にする（`PlayerConfigRepository.disabled()`）— 却下

外部への依存は減るが、YouTube のプレイヤーが変わったときに解読が壊れやすくなる。

## Consequences

- 解読後の URL は、`https` かつ `*.googlevideo.com` の場合だけ使う（ADR-0003）
- innertubex を上げるときは、faraday から取り込む項目と QuickJS のホスト関数に変化が無いかを確かめる
