#!/usr/bin/env python3
"""時計版の基本操作のテスト（to-fu の Wear OS エミュレーターで、ログインしたまま流す）。

確かめること: 検索 → アーティスト → 高く評価した曲（プレイリスト）→ 再生 → 音量（ベゼル）→ 曲送り → 一時停止・再開
→ プレイリストへの追加と削除（確かめ用のプレイリストを作り、最後に消す）。

部品は画面の文字・アイコンの説明（strings.xml の英語の値）で探して押す。判定は画面・再生の状態（dumpsys media_session）・
音量（dumpsys audio）・アプリのログ（debug の DebugReceiver の結果）で行う。

使い方: scripts/watch.sh start の後に  python3 scripts/watch_smoke.py
アカウントに書き込むのは、確かめ用のプレイリスト（「wave smoke test」）を作って曲を足し・外し・消すことだけ。高く評価は押さない。
adb の命令は ssh で to-fu の adb に送る。
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "xyz.mooma0750.wave"
# Windows の adb に日本語を渡すと文字化けするので、英語の名前にする
TEST_PLAYLIST = "wave smoke test"
ROOT = Path(__file__).resolve().parent.parent

results: list[tuple[str, bool, str]] = []


def strings() -> dict[str, str]:
    """英語の文言（エミュレーターは英語）。"""
    table = {}
    for f in (ROOT / "androidApp/src/main/res/values").glob("strings*.xml"):
        for node in ET.parse(f).getroot():
            if node.tag == "string" and node.text:
                table[node.get("name")] = node.text.replace("\\'", "'")
    return table


S = strings()


def adb(*args: str, check: bool = False) -> str:
    """to-fu の adb でエミュレーターに送る（この機械の adb サーバーは、ほかの常駐ツールが作り直すと時計の認証が外れる）。"""
    quoted = " ".join("'" + a.replace("'", "''") + "'" for a in args)
    remote = f"& \"$env:LOCALAPPDATA\\Android\\Sdk\\platform-tools\\adb.exe\" -s emulator-5556 {quoted}"
    out = subprocess.run(["ssh", "tofu", remote], capture_output=True, text=True, timeout=180, encoding="utf-8", errors="replace")
    if check and out.returncode != 0:
        raise RuntimeError(out.stderr)
    return out.stdout.replace("\r\n", "\n")


def shell(cmd: str) -> str:
    return adb("shell", cmd)


def nodes() -> list[dict]:
    shell("uiautomator dump /sdcard/wave_ui.xml >/dev/null 2>&1")
    xml = adb("exec-out", "cat", "/sdcard/wave_ui.xml")
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    found = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        found.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "pkg": n.get("package", "")})
    return found


def find(pattern: str, field: str = "any") -> dict | None:
    rx = re.compile(pattern)
    for n in nodes():
        if field in ("any", "text") and n["text"] and rx.search(n["text"]):
            return n
        if field in ("any", "desc") and n["desc"] and rx.search(n["desc"]):
            return n
    return None


def wait_for(pattern: str, timeout: float = 15, field: str = "any") -> dict | None:
    end = time.time() + timeout
    while time.time() < end:
        n = find(pattern, field)
        if n:
            return n
        time.sleep(1)
    return None


def seek(pattern: str, timeout: float = 20, scroll: int = 5) -> dict | None:
    """見えるところに無ければ下へ送りながら探す（押さない）。"""
    n = wait_for(pattern, timeout)
    for _ in range(scroll):
        if n:
            return n
        shell("input swipe 227 360 227 160 300")
        time.sleep(1)
        n = find(pattern)
    return n


def to_top() -> None:
    """一覧のいちばん上へ戻す。"""
    for _ in range(5):
        shell("input swipe 227 140 227 400 200")
    time.sleep(1)


def tap(n: dict) -> None:
    shell(f"input tap {n['cx']} {n['cy']}")


def tap_text(pattern: str, timeout: float = 15, scroll: int = 4, field: str = "any") -> bool:
    """見つかるまで下へ送りながら探して押す。"""
    for _ in range(scroll + 1):
        n = wait_for(pattern, timeout if _ == 0 else 3, field)
        if n:
            tap(n)
            return True
        shell("input swipe 227 360 227 140 300")
    return False


def back() -> None:
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)


def depth() -> int:
    """アプリが記録している画面の深さ（1 がメニュー）。ログが流れて消えていることがあるので、アプリに出し直してもらう。"""
    seen = count_log(r"wear nav screen=")
    debug("nav_log")
    line = wait_log(r"wear nav screen=", 10, seen)
    m = re.search(r"depth=(\d+)", line) if line else None
    if not m:
        raise SystemExit("今の画面が分からない。止める")
    return int(m.group(1))


def to_home() -> None:
    """メニューまで戻る（メニューで戻るとアプリを閉じるので、深さを見て止める）。戻れなければテストを止める
    （違う画面のまま次の操作を押すと、意図しない行（評価の切り替えなど）を押してしまう）。"""
    for _ in range(10):
        if depth() <= 1:
            return
        before = depth()
        back()
        if depth() >= before:
            raise SystemExit("メニューへ戻れない。止める")


def media() -> dict:
    out = shell("dumpsys media_session")
    block = out.split(f"package={PKG}", 1)[-1]
    state = re.search(r"state=([A-Z_]+)\((\d+)\), position=(\d+)", block)
    desc = re.search(r"description=([^\n]*)", block)
    return {"state": state.group(1) if state else None, "position": int(state.group(3)) if state else 0, "desc": desc.group(1).strip() if desc else ""}


def volume() -> int | None:
    out = shell("dumpsys audio")
    m = re.search(r"- STREAM_MUSIC:.*?streamVolume:(\d+)", out, re.S)
    return int(m.group(1)) if m else None


def log_lines(pattern: str) -> list[str]:
    return [l for l in adb("logcat", "-d", "-s", "wave:I").splitlines() if re.search(pattern, l)]


def wait_log(pattern: str, timeout: float = 30, after: int = 0) -> str | None:
    """pattern に合う行のうち、after 件目より後に出たものを待つ（ログは途中で消さない。消すと画面の深さが分からなくなる）。"""
    end = time.time() + timeout
    while time.time() < end:
        lines = log_lines(pattern)
        if len(lines) > after:
            return lines[-1]
        time.sleep(1)
    return None


def count_log(pattern: str) -> int:
    return len(log_lines(pattern))


def debug(action: str, *extras: str) -> None:
    adb("shell", "am", "broadcast", "-n", f"{PKG}/.debug.DebugReceiver", "--es", "debug_action", action, *extras)


def edge(desc: str, timeout: float = 10) -> dict | None:
    """下の丸いボタン（一覧をいちばん下まで送ると出る）を探す。"""
    for _ in range(6):
        n = find(f"^{re.escape(desc)}$", "desc")
        if n:
            return n
        shell("input swipe 227 380 227 100 250")
        time.sleep(1)
    return wait_for(f"^{re.escape(desc)}$", timeout, "desc")


def quiet() -> None:
    """to-fu のスピーカーから大きな音が出ないよう、再生画面でベゼルを回して音量を小さくする
    （media volume の命令は効かず、音量キーはシステムの音量の画面を開いてしまう）。"""
    for _ in range(15):
        v = volume()
        if v is None or v <= 2:
            return
        shell("input rotaryencoder scroll --axis SCROLL,1")
        time.sleep(0.3)


def check(name: str, ok: bool, detail: str = "") -> bool:
    results.append((name, ok, detail))
    print(("PASS" if ok else "FAIL"), name, detail, flush=True)
    return ok


def type_text(text: str) -> bool:
    """Wear のシステムの入力（キーボード）で語を入れて送る。adb の文字は候補にだけ入るので、候補を押して確定してから送る。"""
    keyboard = wait_for(r"(?i)keyboard", 8, "desc")
    if keyboard:
        tap(keyboard)
    else:
        shell("input tap 313 257")
    time.sleep(2)
    shell(f"input text {text}")
    time.sleep(2)
    # キーボード（IME）は画面の部品の一覧に出ないので、入れた語の候補（左上）を位置で押して確定する
    chip = find(f"^{re.escape(text)}$", "text")
    if chip:
        tap(chip)
    else:
        shell("input tap 158 131")
    time.sleep(1)
    send = find(r"(?i)send|done", "desc")
    if send:
        tap(send)
    else:
        shell("input tap 376 131")
    return True


def main() -> int:
    # 画面が消えていると、アプリが前に出られない
    shell("input keyevent KEYCODE_WAKEUP")
    shell("svc power stayon true")
    # 通知・クイック設定などのシステムの画面が開いていると、アプリが前に出ない（Home・音量キーもシステムの画面を開くので使わない）
    for _ in range(4):
        if "SysUiActivity" in shell("dumpsys activity activities | grep topResumed"):
            break
        back()
    shell(f"am force-stop {PKG}")
    adb("logcat", "-c")
    adb("logcat", "-G", "16M")
    shell(f"am start -n {PKG}/.MainActivity >/dev/null")
    started = wait_log(r"wear nav screen=Home depth=1", 40) is not None
    if not check("起動してメニューが出る", started and wait_for(f"^{S['nav_search']}$", 15) is not None):
        print("ログインしていないか、起動できない。中断する")
        return 1

    # 検索 → アーティスト
    tap_text(f"^{S['nav_search']}$")
    search_button = edge(S["search_hint"])
    check("検索の画面が開く", search_button is not None)
    if search_button:
        tap(search_button)
        time.sleep(3)
        type_text("yoasobi")
        hit = wait_for(r"^YOASOBI$", 20, "text")
        check("検索の結果にアーティストが出る", hit is not None)
        check("検索の結果に曲が出る", seek(r"YOASOBI • ", 5) is not None)
        to_top()
        hit = find(r"^YOASOBI$", "text")
        if hit:
            tap(hit)
            check("アーティストのページが開く（人気の曲）", tap_text(f"^{S['artist_popular']}$", 20, 2) or find(re.escape(S["wear_shuffle_play"])) is not None)
    to_home()

    # プレイリスト（高く評価した曲）→ 再生
    tap_text(f"^{S['main_liked_songs']}$")
    time.sleep(5)
    to_top()
    check("プレイリストが開く（シャッフル再生の行）", wait_for(re.escape(S["wear_shuffle_play"]), 40) is not None)
    # 丸い画面の端の行は押しても届かないことがあるので、真ん中あたりに来た曲の行を押す
    rows = []
    for _ in range(6):
        rows = [n for n in nodes() if n["text"] and " • " in n["text"] and 120 < n["cy"] < 330]
        if rows:
            break
        shell("input swipe 227 300 227 200 300")
        time.sleep(1)
    check("プレイリストに曲が並ぶ", len(rows) > 0, f"rows={len(rows)}")
    # 曲の行を押すと、その曲から再生する
    if rows:
        tap(rows[0])
    check("再生画面へ移る", wait_for(f"^{re.escape(S['nowplaying_next'])}$", 15, "desc") is not None)
    quiet()
    time.sleep(6)
    m1 = media()
    time.sleep(5)
    m2 = media()
    check("再生が進む", m2["state"] == "PLAYING" and m2["position"] > m1["position"], f"{m1['position']}→{m2['position']} {m2['state']}")

    # 音量（ベゼル）
    v1 = volume()
    for _ in range(3):
        shell("input rotaryencoder scroll --axis SCROLL,-1")
        time.sleep(0.5)
    v2 = volume()
    for _ in range(3):
        shell("input rotaryencoder scroll --axis SCROLL,1")
        time.sleep(0.5)
    v3 = volume()
    check("ベゼルで音量が上がる・下がる", v1 is not None and v2 is not None and v3 is not None and v2 > v1 and v3 < v2, f"{v1}→{v2}→{v3}")
    quiet()

    # 曲送り
    before = media()["desc"]
    next_button = find(f"^{re.escape(S['nowplaying_next'])}$", "desc")
    if next_button:
        tap(next_button)
    end = time.time() + 15
    after = before
    while time.time() < end and after == before:
        time.sleep(1)
        after = media()["desc"]
    check("次の曲へ送れる", after != before, f"{before[:30]} → {after[:30]}")
    prev_button = find(f"^{re.escape(S['nowplaying_previous'])}$", "desc")
    if prev_button:
        tap(prev_button)
        tap(prev_button)
    time.sleep(4)
    check("前の曲へ戻れる", media()["desc"] == before, media()["desc"][:30])

    # 一時停止・再開
    pause = find(f"^{re.escape(S['nowplaying_pause'])}$", "desc")
    if pause:
        tap(pause)
    time.sleep(2)
    check("一時停止できる", media()["state"] == "PAUSED")
    resume = find(f"^{re.escape(S['nowplaying_play'])}$", "desc")
    if resume:
        tap(resume)
    time.sleep(3)
    check("再開できる", media()["state"] == "PLAYING")
    pause = find(f"^{re.escape(S['nowplaying_pause'])}$", "desc")
    if pause:
        tap(pause)

    # プレイリストへの追加と削除（確かめ用のプレイリストを作り、最後に消す）
    seen = count_log(r"debug playlist created")
    debug("playlist_create", "--es", "title", f"'{TEST_PLAYLIST}'")
    created = wait_log(r"debug playlist created", 20, seen)
    check("確かめ用のプレイリストを作る", created is not None)
    if created:
        more = find(f"^{re.escape(S['library_more_options'])}$", "desc")
        if more:
            tap(more)
        tap_text(f"^{re.escape(S['search_add_to_playlist'])}$", 10)
        target = None
        for _ in range(8):
            target = wait_for(f"^{re.escape(TEST_PLAYLIST)}$", 4, "text")
            if target:
                break
            shell("input swipe 227 360 227 140 300")
        check("追加の画面に確かめ用のプレイリストが出る", target is not None)
        if target:
            time.sleep(4)  # 入っているかの確認を待つ
            tap(target)
            time.sleep(6)
            seen = count_log(r"debug playlist songs=")
            debug("playlist_count")
            count = wait_log(r"debug playlist songs=", 20, seen)
            check("押すとプレイリストに入る", count is not None and "songs=1" in count, count or "")
            target = wait_for(f"^{re.escape(TEST_PLAYLIST)}$", 5, "text")
            if target:
                tap(target)
            time.sleep(6)
            seen = count_log(r"debug playlist songs=")
            debug("playlist_count")
            count = wait_log(r"debug playlist songs=", 20, seen)
            check("もう一度押すとプレイリストから外れる", count is not None and "songs=0" in count, count or "")
        seen = count_log(r"debug playlist delete matched=")
        debug("playlist_delete_named", "--es", "title", f"'{TEST_PLAYLIST}'")
        deleted = wait_log(r"debug playlist delete matched=", 30, seen)
        check("確かめ用のプレイリストを消す", deleted is not None and "matched=0" not in deleted and not log_lines(r"delete result=(Failed|Unknown)"), deleted or "")
    to_home()

    shell("svc power stayon false")
    crashed = [l for l in adb("logcat", "-d", "-b", "crash").splitlines() if PKG in l]
    check("落ちていない", not crashed, crashed[0] if crashed else "")
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} PASS")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
