# wave のエージェント向けメモ

## Android の画面は Material 3 Expressive

- `androidApp/` の画面（Jetpack Compose）は Material 3 Expressive で作る。無印の Material 3 とは別物なので、無印の部品や書き方へ戻さない
- 何を何へ置き換えるかは `android-m3-expressive` スキルの置き換え表を見る
- 画面を書いたら `scripts/check_m3e.sh androidApp` が「問題なし」になること（CC では編集のたびにフックで自動検査される）
- どうしても無印・直書きが正しい行だけ、同じ行の末尾に `// m3e-allow: 理由` を書く
- Windows 版など Android 以外のコードには当てはめない
