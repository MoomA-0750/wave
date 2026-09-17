# innertubex の faraday（リモートのプレイヤー設定）を有効にする

## 決定

innertubex の `RemotePlayerConfigStore` を有効にし、MetrolistGroup/faraday が公開するプレイヤー設定を使う（Phase 3 のストリーム解決で接続する）。
YouTube のプレイヤーの変更への追従を、自前の解析だけに頼らないため。

## 確認したこと（innertubex v0.6.0）

- faraday から取り込むのは、関数名・定数・`sts`・`aliases` だけで、それぞれ正規表現で検証される（`cipher/RemotePlayerConfigParser.kt`）。faraday の内容が JavaScript として実行されることは無い
- QuickJS で実行されるのは、YouTube 自身のプレイヤー（`www.youtube.com/s/player/...`）と innertubex に同梱のソルバ。QuickJS にはネットワーク・ファイル・アプリの値へ触れるホスト関数が登録されていない
- faraday の設定は github.com・jsDelivr・raw.githubusercontent.com から、cookie を付けずに取得される

## Considered Options

### 無効にする（`PlayerConfigRepository.disabled()`）— 却下

外部への依存は減るが、YouTube のプレイヤーが変わったときに解読が壊れやすくなる。

## Consequences

- 解読後の URL は、`https` かつ `*.googlevideo.com` の場合だけ使う（ADR-0003）
- innertubex を上げるときは、faraday から取り込む項目と QuickJS のホスト関数に変化が無いかを確かめる
