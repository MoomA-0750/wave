# 認証ヘッダは HTTP エンジン層で、InnerTube の宛先以外へ出さない

## 決定

`Cookie`・`Authorization`・`X-Goog-AuthUser` を持つリクエストは、`https://music.youtube.com:443/youtubei/v1/` 以下と、`https://music.youtube.com:443/api/stats/playback`（完全一致、再生履歴、ADR-0006 で追加）にしか送らない。
この検査を Ktor のプラグインではなく、`HttpClientEngine` を包む委譲エンジン（`AuthGuardEngine`）に置く。

- innertubex は、渡した `HttpClient` のエンジンだけを使って新しい `HttpClient(engine)` を作り直す経路を持つ（プレイヤー JS・STS の取得など）。プラグインはその経路に載らないが、エンジンは共有される
- 本文（`OutgoingContent`）側のヘッダも検査する。エンジンは送信時に両方を合わせて送る
- パスに `.`・`..`・`%2e` などを含むもの、ポートが 443 以外のもの、userinfo を含むものは拒否する（OkHttp が送信時にパスを解決するため）
- `HttpClient` はリダイレクトを追わない（Ktor は付けたヘッダのまま別ホストへ送り直すため）。`HttpCookies`・`Logging` プラグインは入れない
- OkHttp に `Interceptor`・`Authenticator`・`CookieJar` を足さない（ガードの後でヘッダが付く）

## Considered Options

### Ktor のクライアントプラグインで検査する — 却下

innertubex が作り直す `HttpClient(engine)` を素通りする。

### 検査しない（innertubex を信頼する）— 却下

innertubex 自身が、`www.youtube.com/watch` や再生統計の宛先に cookie を送る経路を持つ。

## Consequences

- ストリームの抽出（Phase 3）は innertubex の `InnerTubeExtractor` を使わない。Extractor は cookie を `www.youtube.com` に送り、
  匿名クライアントへフォールバックするため、このガードと ADR-0001 に合わない。`WEB_REMIX` の `/player` と `YouTubeCipherService` で1経路を組む
- 再生統計など、ほかの宛先に認証ヘッダが必要になったら、許可ルート（ホストとパスの組）に行を足す
- 音声バイトの取得は認証ヘッダを持たない別の経路で行い、解読後の URL は `https` かつ `*.googlevideo.com` の場合だけ使う
