#!/usr/bin/env python3
"""wave の音楽の道具（MCP サーバー、標準入出力）。

`claude -p` が中継（wave_ai_bridge.py）の /v1/agent から起こされるときに、--mcp-config で渡される。
道具が呼ばれたら、中継の内側の口（/internal/agent/tool）へ送り、中継が wave に頼んで結果を待つ。
実際に曲を探したりキューに入れたりするのは wave（Android の AiTools・Windows の AiTools）。
Kite の kite_agent_mcp.py と同じ作り。

環境変数: WAVE_AGENT_URL（中継）、WAVE_AGENT_SESSION（この会話）、WAVE_AGENT_SECRET（内側の口の合言葉）。
"""

import json
import os
import sys
import urllib.request

URL = os.environ.get("WAVE_AGENT_URL", "http://127.0.0.1:8797")
SESSION = os.environ.get("WAVE_AGENT_SESSION", "")
SECRET = os.environ.get("WAVE_AGENT_SECRET", "")

VIDEO_IDS = {"type": "array", "items": {"type": "string"}, "description": "曲の video_id（search_songs などが返したもの）"}

TOOLS = [
    {
        "name": "now_playing",
        "description": "いま流れている曲（題名・アーティスト・アルバム・video_id）と、あれば歌詞を返す。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "get_queue",
        "description": "再生キュー（今の曲と、これから流れる曲）を返す。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "search_songs",
        "description": (
            "YouTube Music で曲を探す。曲名・アーティスト名・「アーティスト 曲名」などで探し、"
            "video_id・題名・アーティスト・アルバムの一覧を返す。おすすめする曲は、必ずこれで実在を確かめてから出す。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "limit": {"type": "integer", "description": "最大件数（既定 5、最大 20）"},
            },
            "required": ["query"],
        },
    },
    {
        "name": "liked_songs",
        "description": "利用者が高く評価した曲（新しい順）を返す。好みを知るのに使う。",
        "inputSchema": {
            "type": "object",
            "properties": {"limit": {"type": "integer", "description": "最大件数（既定 50、最大 200）"}},
        },
    },
    {
        "name": "show_songs",
        "description": (
            "曲を利用者の画面にカードで並べる（押すと再生できる）。おすすめや似た曲を答えるときは、"
            "文章だけでなくこれで並べる。"
        ),
        "inputSchema": {"type": "object", "properties": {"video_ids": VIDEO_IDS}, "required": ["video_ids"]},
    },
    {
        "name": "play_songs",
        "description": "曲をこの順でキューに置き換えて再生する（いま流れている曲は止まる）。",
        "inputSchema": {"type": "object", "properties": {"video_ids": VIDEO_IDS}, "required": ["video_ids"]},
    },
    {
        "name": "add_to_queue",
        "description": "曲をキューに足す。next が true なら今の曲の次に、そうでなければ自分で足した曲の最後に入れる。",
        "inputSchema": {
            "type": "object",
            "properties": {"video_ids": VIDEO_IDS, "next": {"type": "boolean"}},
            "required": ["video_ids"],
        },
    },
    {
        "name": "list_playlists",
        "description": "利用者が編集できるプレイリスト（playlist_id・名前）の一覧を返す。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "create_playlist",
        "description": "非公開のプレイリストを作って曲を入れる。作ったプレイリストの playlist_id を返す。",
        "inputSchema": {
            "type": "object",
            "properties": {"title": {"type": "string"}, "video_ids": VIDEO_IDS},
            "required": ["title", "video_ids"],
        },
    },
    {
        "name": "add_to_playlist",
        "description": "既にあるプレイリストに曲を足す（もう入っている曲は足さない）。",
        "inputSchema": {
            "type": "object",
            "properties": {"playlist_id": {"type": "string"}, "video_ids": VIDEO_IDS},
            "required": ["playlist_id", "video_ids"],
        },
    },
]


def call_bridge(name, arguments):
    body = json.dumps({"session": SESSION, "name": name, "arguments": arguments}).encode()
    req = urllib.request.Request(
        URL + "/internal/agent/tool",
        data=body,
        headers={"Content-Type": "application/json", "X-Wave-Agent-Secret": SECRET},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read())


def reply(id_, result=None, error=None):
    msg = {"jsonrpc": "2.0", "id": id_}
    if error is not None:
        msg["error"] = error
    else:
        msg["result"] = result
    sys.stdout.write(json.dumps(msg, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        method = msg.get("method")
        id_ = msg.get("id")
        if method == "initialize":
            reply(
                id_,
                {
                    "protocolVersion": msg.get("params", {}).get("protocolVersion", "2025-06-18"),
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "wave", "version": "1"},
                },
            )
        elif method == "tools/list":
            reply(id_, {"tools": TOOLS})
        elif method == "tools/call":
            p = msg.get("params") or {}
            try:
                r = call_bridge(p.get("name", ""), p.get("arguments") or {})
                text = r.get("result")
                if not isinstance(text, str):
                    text = json.dumps(text, ensure_ascii=False)
                reply(id_, {"content": [{"type": "text", "text": text}], "isError": bool(r.get("error"))})
            except Exception as e:  # 中継や wave に届かない
                reply(id_, {"content": [{"type": "text", "text": "error: %s" % e}], "isError": True})
        elif method == "ping":
            reply(id_, {})
        elif id_ is not None:
            reply(id_, error={"code": -32601, "message": "method not found"})
        # 通知（notifications/initialized など）には返さない


if __name__ == "__main__":
    main()
