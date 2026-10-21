# wave のエージェント向けメモ

## Android の画面は Material 3 Expressive

- `androidApp/` の画面（Jetpack Compose）は Material 3 Expressive で作る。無印の Material 3 とは別物なので、無印の部品や書き方へ戻さない
- 何を何へ置き換えるかは `android-m3-expressive` スキルの置き換え表を見る
- 画面を書いたら `scripts/check_m3e.sh androidApp` が「問題なし」になること（CC では編集のたびにフックで自動検査される）
- どうしても無印・直書きが正しい行だけ、同じ行の末尾に `// m3e-allow: 理由` を書く
- 時計（`androidApp/src/wear`）は Wear の Material 3（`androidx.wear.compose.material3`。これが Wear の Expressive）で作り、スマホの部品へ置き換えない。既定のままでは Expressive にならないので、テーマに `MotionScheme.expressive()`、アイコンのボタンに `shapes = IconButtonDefaults.animatedShapes()` を渡す（検査が見る）
- Windows 版など Android 以外のコードには当てはめない

## 仕様の管理

- このリポジトリは試作しながら仕様を詰める。仕様の正本は`SPEC.md`
- 動きを変えたら、同じcommitで`SPEC.md`を直す。履歴は書かず、今の状態に書き換える
- 判断の理由、試してやめたことは`docs/decisions.md`に日付付きで追記する
- `SPEC.md`の各項目には「確定」か「仮」を付ける。「確定」の項目には、外から見た動きを確かめる自動テストを付ける。自動テストが難しいものは、確かめる手順を書けば確定にしてよい
- 迷っていることは`SPEC.md`末尾の「未決事項」に書き、本人に確認する
