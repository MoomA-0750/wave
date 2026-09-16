# ストリームは Premium セッションの WEB_REMIX から直接取り、po_token を作らない

## 決定

ログイン済み（YouTube Premium）の cookie と SAPISIDHASH 署名で `youtubei/v1/player` を
`WEB_REMIX` クライアントとして叩き、`adaptiveFormats` の `signatureCipher` を解いて
得た直URLから音声を取得する。**po_token（BotGuard）は生成しない。**
URLからは `pot` と `ump` を外す。

## Considered Options

### po_token を生成する（Metrolist と同じ）— 却下

WebView に BotGuard の interpreter を読ませてトークンを鋳造する。Metrolist は Premium の
有無に関係なくこれを常時行い、8秒でタイムアウトしたら非po_tokenクライアントへ退避している。

却下理由: **実測で不要だと確認できたため。** Premium セッションで取得したストリームURLから
`pot`（116文字）を削除しても 3.9MB が丸ごと 200 で落ちた（po_tokenなしの上限は通常 約1MB）。
WebView 依存・タイムアウト・フォールバック分岐を丸ごと持ち込む理由がない。

### 匿名クライアント（ANDROID_VR / VISIONOS）を名乗る — 却下

ログイン不要で手軽だが、2026-08 以降 CDN が 1MiB 超で 403 を返す。innertubex 側でも
ANDROID_VR系は PROBE_ONLY / DEPRECATED に落ちている。加えて匿名では明示的コンテンツが
再生できず、再生履歴もおすすめも育たない。

### SABR（serverAbrStreamingUrl）を使う — 却下（当面）

`/player` は SABR URL も返すが、UMP+protobuf のフレーミングを自前で解く必要があり、
Media3 に食わせるには独自 DataSource が要る。adaptiveFormats の直URLが生きている間は不要。
Metrolist 本体も ExoPlayer 経路では SABR を明示的に拒否している。

## Consequences

- **引き受ける代償**: ログインが必須になる。匿名では一切動かない
- Premium 契約が切れると、この方式は po_token の壁に当たって壊れる
- `signatureCipher` の解読は避けられない（全フォーマットが cipher。直URLは0件）。
  QuickJS + yt-dlp EJS を持つ innertubex に任せる
- YouTube 側が Premium セッションにも GVS po_token を要求し始めたら、この決定は覆る。
  そのときは po_token 生成を足す（innertubex に実装がある）
