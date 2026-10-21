# wave の AI 中継（移した）

「Claude に聞く」の中継は、Kite と共通の汎用の中継 **ai-bridge**（別のリポジトリ。家の PC では `~/Documents/git-cloned/ai-bridge`）に移した（2026-10-21）。

- 家の PC では `ai-bridge@wave`（127.0.0.1:8797。トークンは前と同じ `~/.config/wave-ai-bridge/token`）として動く
- 接続の QR は `python3 ai_bridge.py --name wave --pair`（ai-bridge のリポジトリで）。形は前と同じ `waveai://pair?u=&t=&n=`
- 中継はアプリのことを知らない。システムプロンプトと道具の定義は wave の `ai/agent.json`（Android・Mac/iOS・Windows が同じファイルをアプリに入れる）で、wave が毎回 `/v1/run` へ送る
- 道具を実際に行うのは前と同じく各 OS の AiTools。曲は、この会話で出てきたもの（今の曲・キュー・検索・高評価）の video_id だけを受ける

## 確かめ方（開発用）

Android の debug ビルドでは、`adb reverse tcp:18797 tcp:8797` で PC の中継を端末の 127.0.0.1 に見せ、次で接続と頼みを試せる（答えは長さと道具の行だけをログに出す）。

```sh
adb shell am broadcast -n xyz.mooma0750.wave/.debug.DebugReceiver --es debug_action ai_pair --es uri "'waveai://pair?u=http://127.0.0.1:18797&t=<トークン>&n=test'"
adb shell am broadcast -n xyz.mooma0750.wave/.debug.DebugReceiver --es debug_action ai_ask --es text "'似た曲を2つ探して並べて'"
```
