#!/usr/bin/env python3
"""CI 用 E2E スモーク(GitHub Actions のエミュレータ + e2e-stub)。

adb だけで動かす。手順:
  1. APK をインストールして起動(初回起動は設定画面)
  2. サーバーURL に 10.0.2.2:4098(ホスト側の stub)、パスワードに stub-pass を入力
  3. 「保存して接続テスト」→ content-desc `settings-health-ok:true/…` が出ること
  4. セッション一覧に stub の「スタブセッション1」が出ること
  5. そのセッションを開いてメッセージを送信 → stub が SSE で流す応答
     (既定 `チャンク1|チャンク2|チャンク3` を同一 part に累積)の最終形が dump 上に現れ、
     中断ボタン(`chat-abort:*`)が消えて idle に戻ること。stub の promptCount が +1 であること
  6. logcat にアプリの FATAL EXCEPTION が無いこと

証跡(uiautomator dump / スクショ / logcat)は --out に保存し、Actions の artifact に上げる。
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

APP_ID = "io.github.noxitro.opencodeclient"
ACTIVITY = f"{APP_ID}/dev.opencode.android.MainActivity"
STUB_URL = "http://10.0.2.2:4098"
STUB_PASSWORD = "stub-pass"
# ランナー(ホスト)側から stub の検証用エンドポイントを叩くときの URL。エミュレータ内の 10.0.2.2 と同じ stub。
STUB_HOST_URL = "http://127.0.0.1:4098"
CHAT_SESSION_ID = "ses_stub_0001"
CHAT_MESSAGE = "e2e-hello"  # `adb shell input text` は非ASCIIを打てないので ASCII にする
# stub 既定の STUB_CHUNKS を累積した最終形(e2e-stub/server.mjs の CHUNKS)。
CHAT_REPLY = "チャンク1チャンク2チャンク3"


def adb(*args, check=True, capture=True):
    # logcat には他プロセス由来の非UTF-8バイトが混ざる(実例: 0xc0 で UnicodeDecodeError)。
    # 判定に必要なのは ASCII 部分だけなので、デコード不能バイトは置換して続行する。
    r = subprocess.run(["adb", *args], check=check, capture_output=capture,
                       encoding="utf-8", errors="replace")
    return r.stdout if capture else ""


def dump(out_dir, name):
    xml = subprocess.run(["adb", "exec-out", "uiautomator", "dump", "/dev/tty"],
                         capture_output=True, encoding="utf-8", errors="replace").stdout
    # `uiautomator dump /dev/tty` は XML の後ろに "UI hierchary dumped to: /dev/tty" を出す。
    # 先頭の <?xml から </hierarchy> までだけを残す(残すと ParseError で node が 0 件に見える)。
    if "<?xml" in xml:
        xml = xml[xml.find("<?xml"):]
    end = xml.rfind("</hierarchy>")
    if end != -1:
        xml = xml[: end + len("</hierarchy>")]
    with open(os.path.join(out_dir, f"{name}.xml"), "w", encoding="utf-8") as f:
        f.write(xml)
    subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=open(os.path.join(out_dir, f"{name}.png"), "wb"))
    return xml


def nodes(xml):
    try:
        return list(ET.fromstring(xml).iter("node"))
    except ET.ParseError as e:
        print(f"WARN: uiautomator dump did not parse: {e}", file=sys.stderr)
        return []


def contains(xml, needle):
    """text か content-desc に needle を含むノードがあるか。

    バブルは combinedClickable で子の Text がマージされるので、dump 上の text が
    本文そのものと一致するとは限らない。部分一致で見る。
    """
    return any(needle in (n.get("text") or "") or needle in (n.get("content-desc") or "")
               for n in nodes(xml))


def stub_stats():
    token = base64.b64encode(f"opencode:{STUB_PASSWORD}".encode()).decode()
    req = urllib.request.Request(f"{STUB_HOST_URL}/__stub/stats",
                                 headers={"Authorization": f"Basic {token}"})
    # ランナーに http_proxy が設定されていても 127.0.0.1 は直接叩く。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=10) as r:
            return json.load(r)
    except OSError as e:
        raise SystemExit(f"stub stats unreachable at {STUB_HOST_URL}: {e}")


def center(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(xml, *, text=None, desc_prefix=None, cls=None, index=0):
    hits = []
    for n in nodes(xml):
        if text is not None and n.get("text") != text:
            continue
        if desc_prefix is not None and not n.get("content-desc", "").startswith(desc_prefix):
            continue
        if cls is not None and n.get("class") != cls:
            continue
        hits.append(n)
    return hits[index] if len(hits) > index else None


def wait_for(out_dir, name, pred, timeout=60):
    deadline = time.time() + timeout
    while True:
        xml = dump(out_dir, name)
        if pred(xml):
            return xml
        if time.time() > deadline:
            raise SystemExit(f"TIMEOUT waiting for {name}; see {out_dir}/{name}.xml")
        time.sleep(2)


def tap(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)


def type_text(node, text):
    tap(node)
    adb("shell", "input", "text", text)
    time.sleep(0.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="e2e-artifacts/ci")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    adb("wait-for-device")
    adb("shell", "settings", "put", "global", "window_animation_scale", "0")
    adb("logcat", "-c")
    adb("install", "-r", "-g", a.apk)
    adb("shell", "am", "start", "-n", ACTIVITY)

    # 1. settings screen on first launch (two EditTexts: URL, password)
    xml = wait_for(a.out, "01-launch", lambda x: find(x, cls="android.widget.EditText", index=1) is not None)
    type_text(find(xml, cls="android.widget.EditText", index=0), STUB_URL)
    xml = dump(a.out, "02-url-typed")
    type_text(find(xml, cls="android.widget.EditText", index=1), STUB_PASSWORD)
    adb("shell", "input", "keyevent", "111")  # ESC: hide IME
    time.sleep(0.5)
    xml = dump(a.out, "03-password-typed")

    # 2. save + health check
    save = find(xml, text="保存して接続テスト")
    if save is None:
        raise SystemExit("save button not found")
    tap(save)
    xml = wait_for(a.out, "04-health", lambda x: find(x, desc_prefix="settings-health-ok:true/") is not None, timeout=60)
    health = find(xml, desc_prefix="settings-health-ok:true/").get("content-desc")
    print("health:", health)

    # 3. session list shows the stub session (open drawer -> sessions, or auto-navigate)
    def has_stub_session(x):
        # 一覧のカードは content-desc `session-card:<id>:<status>` で識別する(タイトル文字列は
        # カードにマージされ dump に text として出ないことがある)。stub は ses_stub_0001/0002 を返す。
        return (find(x, desc_prefix="session-card:ses_stub_") is not None
                or find(x, text="スタブセッション1") is not None)
    xml = dump(a.out, "05-after-health")
    if not has_stub_session(xml):
        # 接続OK の下に出る「セッション一覧へ」ボタンを優先。無ければドロワー経由(drawer-sessions)。
        go = find(xml, text="セッション一覧へ")
        if go is not None:
            tap(go)
        else:
            menu = find(xml, desc_prefix="メニューを開く")
            if menu is not None:
                tap(menu)
                xml = dump(a.out, "06-drawer")
                sessions = find(xml, desc_prefix="drawer-sessions")
                if sessions is not None:
                    tap(sessions)
        xml = wait_for(a.out, "07-session-list", has_stub_session, timeout=60)
    print("session list: OK")

    # 4. open the stub session and send a message; the streamed reply must land and the chat go idle
    card = find(xml, desc_prefix=f"session-card:{CHAT_SESSION_ID}:")
    if card is None:
        raise SystemExit(f"session card {CHAT_SESSION_ID} not found")
    tap(card)
    xml = wait_for(a.out, "08-chat-open",
                   lambda x: find(x, desc_prefix="送信") is not None
                   and find(x, cls="android.widget.EditText") is not None
                   and contains(x, "履歴側のアシスタント応答です"),
                   timeout=60)
    prompts_before = stub_stats()["promptCount"]
    type_text(find(xml, cls="android.widget.EditText"), CHAT_MESSAGE)

    # 送信ボタンは draft が空でなくなった後の再描画で enabled になる。1回の dump で決めない。
    def send_enabled(x):
        s = find(x, desc_prefix="送信")
        return s is not None and s.get("enabled") == "true"
    xml = wait_for(a.out, "09-chat-typed", send_enabled, timeout=30)
    tap(find(xml, desc_prefix="送信"))
    xml = wait_for(a.out, "10-chat-reply",
                   lambda x: contains(x, CHAT_REPLY)
                   and contains(x, CHAT_MESSAGE)
                   and find(x, desc_prefix="chat-abort:") is None,
                   timeout=90)
    prompts_after = stub_stats()["promptCount"]
    if prompts_after != prompts_before + 1:
        raise SystemExit(f"stub promptCount {prompts_before} -> {prompts_after}, expected +1")
    print(f"chat: sent {CHAT_MESSAGE!r}, reply {CHAT_REPLY!r}, promptCount {prompts_before}->{prompts_after}")

    # 5. no crash
    log = adb("logcat", "-d")
    with open(os.path.join(a.out, "logcat.txt"), "w", encoding="utf-8") as f:
        f.write(log)
    if re.search(rf"FATAL EXCEPTION.*\n.*{re.escape(APP_ID)}", log):
        raise SystemExit("FATAL EXCEPTION found in logcat")
    print("SMOKE PASSED")


if __name__ == "__main__":
    main()
