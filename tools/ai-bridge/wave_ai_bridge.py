#!/usr/bin/env python3
"""wave の AI 中継。wave からの頼み（歌詞の意味・似た曲・プレイリスト作りなど）を受けて `claude -p` を呼ぶ。

Kite の AI 中継（kite-browser/tools/ai-bridge/kite_ai_bridge.py）と同じ作り。Claude Code のヘッドレス（サブスクの範囲で動く）を
そのまま使い、道具は wave の音楽の道具（wave_agent_mcp.py）だけを渡す。設定ファイルは読まず、会話は保存しない
（--no-session-persistence）。続きの質問は、wave が毎回それまでのやりとりを送ってくる（中継は状態を持たない）。
標準ライブラリだけで動く。

口:
  GET  /v1/health        {"ok": true, "host": ..., "claude": "2.1.x"}
  POST /v1/agent         {"now_playing": {...}, "lyrics": "...", "messages": [{"role","content"}], "task": "..."}
                         1 行 1 つの JSON で流す（{"session"} {"delta"} {"tool_call": {"id","name","arguments"}} … {"done"}）。
                         道具の結果は wave が /v1/agent/result へ返す
  POST /v1/agent/result  {"session", "id", "result" か "error"}

どれも Authorization: Bearer <トークン> が要る（health を除く）。トークンは ~/.config/wave-ai-bridge/token（無ければ作る）。
"""

import argparse
import json
import os
import queue
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG_DIR = os.path.expanduser(os.environ.get("WAVE_AI_CONFIG", "~/.config/wave-ai-bridge"))
MAX_PAGE_CHARS = 60_000
MAX_BODY = 4 * 1024 * 1024
TIMEOUT_S = 300

AGENT_SYSTEM = (
    "あなたは音楽プレイヤー wave に組み込まれたアシスタントです。利用者がいま聴いている曲が <now_playing> に、"
    "あれば歌詞が <lyrics> に入っています。利用者と同じ言語で、簡潔に答えてください。"
    "歌詞の意味を聞かれたら、歌詞に基づいて解釈し、確かでないことは推測だと断ってください。"
    "似た曲やおすすめを聞かれたら、search_songs で実在を確かめた曲だけを挙げ、show_songs で画面に並べてください。"
    "利用者の好みが要るときは liked_songs を見てください。"
    "プレイリストを作る・キューに足す・再生するように頼まれたら、wave の道具で実際に行い、何をしたかを短く報告してください。"
    "頼まれていないのに再生を始めたり、キューを置き換えたりしないでください。"
)
MCP_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wave_agent_mcp.py")
AGENT_TOOL_TIMEOUT_S = 300

def load_token():
    os.makedirs(CONFIG_DIR, exist_ok=True)
    path = os.path.join(CONFIG_DIR, "token")
    if not os.path.exists(path):
        with open(path, "w") as f:
            f.write(secrets.token_urlsafe(24))
        os.chmod(path, 0o600)
    with open(path) as f:
        return f.read().strip()


def find_claude():
    return (
        os.environ.get("WAVE_AI_CLAUDE")
        or shutil.which("claude")
        or next(
            (p for p in [os.path.expanduser("~/.local/bin/claude")] if os.path.exists(p)), None
        )
    )


class Bridge:
    def __init__(self, claude, model, token):
        self.claude = claude
        self.model = model
        self.token = token
        self.port = 0
        # 道具を呼ぶ MCP サーバーだけが知る合言葉（内側の口 /internal/agent/tool 用）。
        self.secret = secrets.token_urlsafe(24)
        self.sessions = {}
        self.lock = threading.Lock()
        # 道具も設定も読まない空の作業場所（CLAUDE.md を拾わないため）。
        self.workdir = tempfile.mkdtemp(prefix="wave-ai-")
        self.version = self._version()

    def _version(self):
        try:
            out = subprocess.run(
                [self.claude, "--version"], capture_output=True, text=True, timeout=30
            ).stdout
            return out.split()[0] if out else "?"
        except Exception:
            return "?"

    def base_args(self, system):
        args = [
            self.claude,
            "-p",
            "--tools",
            "",
            "--strict-mcp-config",
            "--setting-sources",
            "",
            "--no-session-persistence",
            "--system-prompt",
            system,
        ]
        if self.model:
            args += ["--model", self.model]
        return args

    def spawn(self, args, stdin_text):
        env = dict(os.environ)
        # Termux の非公式の包みは -p のとき標準入力を捨てるので、継がせる（Camp と同じ対処）。
        env.setdefault("CLAUDE_TERMUX_STDIN", "inherit")
        p = subprocess.Popen(
            args,
            cwd=self.workdir,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
        )
        p.stdin.write(stdin_text)
        p.stdin.close()
        return p


class AgentSession:
    """1 回の頼み。wave へ流すもの（文字・道具の呼び出し）を 1 本の列に並べ、道具の結果を待つ。"""

    def __init__(self, sid):
        self.sid = sid
        self.events = queue.Queue()
        self.pending = {}  # 道具の呼び出しの id → [Event, 結果]
        self.closed = False
        self.counter = 0

    def call_tool(self, name, arguments):
        if self.closed:
            return {"error": True, "result": "the user stopped the task"}
        self.counter += 1
        cid = "%s-%d" % (self.sid[:6], self.counter)
        ev = threading.Event()
        slot = [ev, None]
        self.pending[cid] = slot
        self.events.put({"tool_call": {"id": cid, "name": name, "arguments": arguments}})
        if not ev.wait(AGENT_TOOL_TIMEOUT_S) or slot[1] is None:
            self.pending.pop(cid, None)
            return {"error": True, "result": "wave did not answer (timeout)"}
        return slot[1]

    def resolve(self, cid, result):
        slot = self.pending.pop(cid, None)
        if slot is None:
            return False
        slot[1] = result
        slot[0].set()
        return True

    def close(self):
        self.closed = True
        for slot in list(self.pending.values()):
            slot[1] = {"error": True, "result": "the user stopped the task"}
            slot[0].set()
        self.pending.clear()


MAX_LYRICS_CHARS = 20_000


def agent_prompt(req):
    song = req.get("now_playing") or {}
    parts = []
    if song:
        parts += [
            "<now_playing>",
            "題名: %s" % song.get("title", ""),
            "アーティスト: %s" % song.get("artists", ""),
            "アルバム: %s" % song.get("album", ""),
            "video_id: %s" % song.get("video_id", ""),
            "</now_playing>",
            "",
        ]
    lyrics = (req.get("lyrics") or "")[:MAX_LYRICS_CHARS]
    if lyrics:
        parts += ["<lyrics>", lyrics, "</lyrics>", ""]
    history = req.get("messages") or []
    if history:
        parts.append("<conversation>")
        for m in history:
            who = "利用者" if m.get("role") == "user" else "アシスタント"
            parts.append("%s: %s" % (who, m.get("content", "")))
        parts.append("</conversation>")
        parts.append("")
    parts.append(req.get("task") or "")
    return "\n".join(parts)


class Handler(BaseHTTPRequestHandler):
    bridge: Bridge = None  # type: ignore
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        got = self.headers.get("Authorization", "")
        return secrets.compare_digest(got, "Bearer " + self.bridge.token)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_BODY:
            raise ValueError("bad length")
        return json.loads(self.rfile.read(n))

    def do_GET(self):
        if self.path == "/v1/health":
            return self._json(
                200, {"ok": True, "host": socket.gethostname(), "claude": self.bridge.version}
            )
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/internal/agent/tool":
            return self._agent_tool()
        if not self._authorized():
            return self._json(401, {"error": "unauthorized"})
        try:
            req = self._body()
        except Exception:
            return self._json(400, {"error": "bad request"})
        if self.path == "/v1/agent":
            return self._agent(req)
        if self.path == "/v1/agent/result":
            return self._agent_result(req)
        self._json(404, {"error": "not found"})

    # ---- 頼み（道具つき） ----

    def _agent(self, req):
        b = self.bridge
        sid = secrets.token_urlsafe(12)
        session = AgentSession(sid)
        with b.lock:
            b.sessions[sid] = session
        mcp = {
            "mcpServers": {
                "wave": {
                    "command": sys.executable,
                    "args": [MCP_SCRIPT],
                    "env": {
                        "WAVE_AGENT_URL": "http://127.0.0.1:%d" % b.port,
                        "WAVE_AGENT_SESSION": sid,
                        "WAVE_AGENT_SECRET": b.secret,
                    },
                }
            }
        }
        args = b.base_args(AGENT_SYSTEM) + [
            "--mcp-config",
            json.dumps(mcp),
            "--allowedTools",
            "mcp__wave",
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
        ]
        p = b.spawn(args, agent_prompt(req))

        def pump():
            for line in p.stdout:
                try:
                    d = json.loads(line)
                except ValueError:
                    continue
                ev = d.get("event") or {}
                if d.get("type") == "stream_event" and ev.get("type") == "content_block_delta":
                    delta = ev.get("delta") or {}
                    if delta.get("type") == "text_delta" and delta.get("text"):
                        session.events.put({"delta": delta["text"]})
                elif d.get("type") == "stream_event" and ev.get("type") == "message_stop":
                    session.events.put({"delta": "\n\n"})
                elif d.get("type") == "result":
                    if d.get("is_error"):
                        session.events.put({"error": str(d.get("result") or "claude failed")})
            p.wait()
            if p.returncode and not session.closed:
                err = (p.stderr.read() or "")[-500:]
                if err:
                    session.events.put({"error": err})
            session.events.put(None)

        threading.Thread(target=pump, daemon=True).start()
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def emit(obj):
            data = (json.dumps(obj, ensure_ascii=False) + "\n").encode()
            self.wfile.write(b"%x\r\n%s\r\n" % (len(data), data))
            self.wfile.flush()

        try:
            emit({"session": sid})
            while True:
                try:
                    item = session.events.get(timeout=15)
                except queue.Empty:
                    emit({"ping": True})  # つながっているかを確かめる（切れていれば例外）
                    continue
                if item is None:
                    break
                emit(item)
            emit({"done": True})
            self.wfile.write(b"0\r\n\r\n")
        except (BrokenPipeError, ConnectionResetError):
            pass  # wave が止めた・画面を閉じた
        finally:
            session.close()
            if p.poll() is None:
                p.kill()
            with b.lock:
                b.sessions.pop(sid, None)

    def _agent_result(self, req):
        b = self.bridge
        with b.lock:
            session = b.sessions.get(req.get("session", ""))
        if session is None:
            return self._json(404, {"error": "no such session"})
        if "error" in req:
            result = {"error": True, "result": str(req.get("error"))}
        else:
            result = {"error": False, "result": req.get("result")}
        ok = session.resolve(req.get("id", ""), result)
        self._json(200 if ok else 404, {"ok": ok})

    def _agent_tool(self):
        b = self.bridge
        if not secrets.compare_digest(self.headers.get("X-Wave-Agent-Secret", ""), b.secret):
            return self._json(401, {"error": "unauthorized"})
        try:
            req = self._body()
        except Exception:
            return self._json(400, {"error": "bad request"})
        with b.lock:
            session = b.sessions.get(req.get("session", ""))
        if session is None:
            return self._json(200, {"error": True, "result": "the task has ended"})
        self._json(200, session.call_tool(req.get("name", ""), req.get("arguments") or {}))


def public_url(port):
    """wave から見たこの中継の URL。tailscale serve でこのポートを出していればその HTTPS、無ければ手元。"""
    try:
        out = subprocess.run(
            ["tailscale", "serve", "status", "--json"], capture_output=True, text=True, timeout=10
        ).stdout
        web = (json.loads(out) if out.strip() else {}).get("Web") or {}
        for hostport, conf in web.items():
            for handler in (conf.get("Handlers") or {}).values():
                proxy = handler.get("Proxy", "")
                if proxy.rstrip("/") in ("http://127.0.0.1:%d" % port, "http://localhost:%d" % port):
                    return "https://" + (hostport[:-4] if hostport.endswith(":443") else hostport)
    except Exception:
        pass
    return "http://127.0.0.1:%d" % port


def print_pairing(url, token, png=None):
    """接続の QR と、接続先・トークンを表示する（wave の設定 → Claude → QR コードで接続 で読む）。"""
    import urllib.parse

    import wave_qr

    name = socket.gethostname()
    payload = "waveai://pair?" + urllib.parse.urlencode({"u": url, "t": token, "n": name})
    modules = wave_qr.encode(payload)
    print()
    print(wave_qr.render_terminal(modules))
    if png:
        with open(os.path.expanduser(png), "wb") as f:
            f.write(wave_qr.to_png(modules))
        os.chmod(os.path.expanduser(png), 0o600)
    print()
    local = urllib.parse.urlparse(url).hostname in ("127.0.0.1", "localhost")
    print("  wave の AI 中継（%s）" % name)
    print("  接続先  : %s" % url)
    print("  トークン: %s" % token)
    print("  貼り付け用: %s" % payload)
    print()
    print("  Android: wave の設定 →「Claude」→「QR コードで接続」で読み取る。")
    print("  Windows: wave の設定 →「Claude」に上の「貼り付け用」の文字列を貼る。")
    if png:
        print("  QR の画像: %s（読ませたら消すこと）" % png)
    print("  トークンを知っている人はこの中継で Claude を使えるので、画面を人に見せないこと。")
    print()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8797)
    ap.add_argument("--model", default=os.environ.get("WAVE_AI_MODEL", "sonnet"))
    ap.add_argument("--print-token", action="store_true", help="トークンを表示して終わる")
    ap.add_argument("--pair", action="store_true",
                    help="wave に読ませる QR と、接続先・トークンを表示して終わる")
    ap.add_argument("--url", help="--pair で使う接続先（省略すると tailscale serve から探す）")
    ap.add_argument("--png", help="--pair の QR を PNG にも書く（端末の表示で読めないとき）")
    a = ap.parse_args()
    token = load_token()
    if a.print_token:
        print(token)
        return
    if a.pair:
        print_pairing(a.url or public_url(a.port), token, a.png)
        return
    claude = find_claude()
    if not claude:
        sys.exit("claude が見つからない（WAVE_AI_CLAUDE で場所を指定できる）")
    Handler.bridge = Bridge(claude, a.model, token)
    Handler.bridge.port = a.port
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f"wave-ai-bridge: http://{a.host}:{a.port} claude={Handler.bridge.version} model={a.model}",
          flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
