# wave の AI 中継（wave-ai-bridge）

wave の「Claude に聞く」（歌詞の意味・似た曲・プレイリスト作りなど）を受けて、`claude -p`（Claude Code のヘッドレス）を呼ぶ小さな HTTP サーバー。サブスクの範囲で動く。標準ライブラリだけで動く。作りは Kite の AI 中継（kite-browser/tools/ai-bridge）と同じ。

- Claude には wave の音楽の道具（`wave_agent_mcp.py`）だけを渡す。ほかの道具・MCP・設定ファイルは読ませない（`--tools "" --strict-mcp-config --setting-sources ""`）。空の作業場所で動かすので CLAUDE.md も拾わない
- 道具を実際に行うのは wave（曲の検索・キューに足す・再生・プレイリストを作る など）。中継は道具の呼び出しを wave に流し、wave が返した結果を Claude に渡す
- 会話は保存しない（`--no-session-persistence`）。続きの質問は、wave が毎回それまでのやりとりと、今の曲・歌詞を送る
- 既定のモデルは sonnet（`--model` か `WAVE_AI_MODEL` で変える）
- YouTube の認証情報は中継へ送らない（wave は中継とは別の HTTP クライアントで話す）

## 口

| 口 | 中身 |
|---|---|
| `GET /v1/health` | `{"ok": true, "host", "claude"}`（トークン不要） |
| `POST /v1/agent` | `{"now_playing": {"title","artists","album","video_id"}, "lyrics", "messages": [{"role","content"}], "task"}`。答えは 1 行 1 つの JSON で流す（`{"session"}` `{"delta"}` `{"tool_call": {"id","name","arguments"}}` … `{"done": true}` か `{"error"}`） |
| `POST /v1/agent/result` | `{"session", "id", "result" か "error"}`（wave が道具の結果を返す） |

`Authorization: Bearer <トークン>` が要る（health を除く）。トークンは `~/.config/wave-ai-bridge/token`（無ければ作る）。

## 道具

| 道具 | すること |
|---|---|
| `now_playing` | 今の曲と歌詞 |
| `get_queue` | 再生キュー |
| `search_songs` | YouTube Music で曲を探す（おすすめは必ずこれで実在を確かめる） |
| `liked_songs` | 高く評価した曲（好みを知る） |
| `show_songs` | 曲を wave の画面にカードで並べる（押すと再生） |
| `play_songs` / `add_to_queue` | 再生する・キューに足す |
| `list_playlists` / `create_playlist` / `add_to_playlist` | プレイリストを見る・作る・足す |

曲は video_id で受け渡す。wave はこの会話で出てきた曲（今の曲・キュー・検索・高評価）の id だけを受ける。

## 家の PC で動かす

```sh
cp wave-ai-bridge.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now wave-ai-bridge
sudo tailscale serve --bg --https=10447 http://127.0.0.1:8797   # tailnet の中だけ
```

## wave に接続する

```sh
python3 wave_ai_bridge.py --pair            # 接続先は tailscale serve から探す（無ければ 127.0.0.1）
python3 wave_ai_bridge.py --pair --url https://example.ts.net:10447
```

端末に QR と、接続先・トークンを出す（QR の中身は `waveai://pair?u=<接続先>&t=<トークン>&n=<ホスト名>`）。

- Android: 設定 →「Claude」→「QR コードで接続」で読む
- Windows: 設定 →「Claude」に、`--pair` が出す「貼り付け用」の文字列（`waveai://pair?…`）を貼る

接続先は https（tailscale serve）か、同じ端末の中（127.0.0.1）だけ受ける。トークンを知っていればこの中継で Claude を使えるので、画面を人に見せない。

## 確かめ方（開発用）

Android の debug ビルドでは、`adb reverse tcp:18797 tcp:18797` で PC の中継を端末の 127.0.0.1 に見せ、次で接続と頼みを試せる（答えは長さと道具の行だけをログに出す）。

```sh
adb shell am broadcast -n xyz.mooma0750.wave/.debug.DebugReceiver --es debug_action ai_pair --es uri "'waveai://pair?u=http://127.0.0.1:18797&t=<トークン>&n=test'"
adb shell am broadcast -n xyz.mooma0750.wave/.debug.DebugReceiver --es debug_action ai_ask --es text "'似た曲を2つ探して並べて'"
```
