"""Q5 変異校正。

RUN_PLAN「常設ルール(追加)— 変異が『全部検出された』ときも検出器を疑う」の3点セット:
  1. 無変異キャリブレーション(M0)が GREEN になること
  2. 変異が**実際にファイルを変えた**ことを assert する
  3. **テストランナーが実際に走った**ことを assert する(gradle 出力に BUILD 行)

復元は**バイトコピー**(`git checkout --` は使わない。HARNESS)。
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main", "java", "dev", "opencode", "android")


def p(*parts):
    return os.path.join(MAIN, *parts)


URLS = p("data", "Urls.kt")
CTRL = p("ui", "SessionListController.kt")
HAPTIC = p("ui", "Haptics.kt")
APPEAR = p("ui", "AppearanceModels.kt")
DELIVERY = p("ui", "EventDelivery.kt")
SERVERINFO = p("ui", "ServerInfoController.kt")
PREFS = p("data", "PreferencesRepository.kt")
WIRING = p("ui", "AppWiring.kt")
DRAWER = p("ui", "AppDrawer.kt")

MUTATIONS = [
    ("M0", None, None, None, "無変異キャリブレーション(GREEN であること)"),
    ("M1", URLS, '        else -> ":$DEFAULT_OPENCODE_PORT"',
     '        else -> ""', "H1a: 4097 の自動補填を消す(:80 へ落ちる元の症状)"),
    ("M2", URLS, "        port != -1 -> \":$port\"",
     '        port != -1 -> ""', "H1a: 明示ポートを捨てる"),
    ("M3", URLS, '        scheme == "https" -> ""',
     '        scheme == "https" -> ":$DEFAULT_OPENCODE_PORT"',
     "H1a: https にも 4097 を補う(443 が正しいのに)"),
    ("M4", CTRL,
     """        val before = _state.getAndUpdate { s ->
            if (s.creating) s else s.copy(creating = true, createError = null)
        }
        if (before.creating) return
        scope.launch {""",
     """        if (_state.value.creating) return
        scope.launch {
            _state.update { it.copy(creating = true, createError = null) }""",
     "Q4-1: create のガードを『読みは外・占有は中』へ戻す(欠陥の元の形)"),
    ("M5", CTRL,
     """        val before = _state.getAndUpdate { s ->
            if (s.pendingActionId != null) s else s.copy(pendingActionId = sessionId, actionError = null)
        }
        if (before.pendingActionId != null) return
        scope.launch {""",
     """        if (_state.value.pendingActionId != null) return
        scope.launch {
            _state.update { it.copy(pendingActionId = sessionId, actionError = null) }""",
     "Q4-1: rename のガードを元の形へ戻す"),
    ("M6", HAPTIC, "        if (!on) return\n        sink(event)",
     "        sink(event)", "触覚: OFF でも鳴らす(ゲートを外す)"),
    ("M7", APPEAR, "    ColorMode.SYSTEM -> systemInDarkMode",
     "    ColorMode.SYSTEM -> true", "カラーモード: SYSTEM が端末設定に従わない"),
    ("M8", APPEAR, "    ColorMode.LIGHT -> false", "    ColorMode.LIGHT -> true",
     "カラーモード: LIGHT がダークになる"),
    # M9 は削除。ラムダ引数版の配布関数ごと `wireConnectionChangesTo` に置き換えたので、
    # 同じ狙いは R3 / R8 が担う(そちらは呼び出し口からの変異も検出する)。
    ("M10", SERVERINFO, "        _state.value = ServerInfoUi()\n    }",
     "    }", "接続先が変わってもバージョンを捨てない(Q4 major-1 と同じ形)"),
    ("M11", SERVERINFO,
     """                        healthy = null,
                        version = null,""",
     """                        healthy = cur.healthy,
                        version = cur.version,""",
     "取得失敗でも古いバージョンを残す"),
    ("M12", PREFS, "            entries.firstOrNull { it.name == value } ?: DARK",
     "            entries.firstOrNull { it.name == value } ?: SYSTEM",
     "未保存の既定をダークからシステムへ変える(Q0 の既定を壊す)"),
    ("M13", APPEAR, "    items.take(count)", "    items",
     "ドロワーの最近の項目が上位N件に切られない"),

    # ------------------------------------------------------------------
    # 2周目: **配線層**の変異(Q5 レビュー major-2)。
    # 1周目の M1〜M13 は全て関数の**中身**を狙っており、レビューが呼び出し口を狙った
    # 8本のうち7本が431件全緑で通り抜けた。以下は R1〜R8 を新しい構造へ写したもので、
    # 「アプリがその関数を通る」ことを主張できているかを測る。
    # ------------------------------------------------------------------
    ("R1", WIRING, "    val normalized = Urls.normalize(rawUrl) ?: return SaveAndTestOutcome.BadUrl",
     "    val normalized = rawUrl",
     "R1: Urls.normalize をバイパス(H1a の修正が死んだコードになる)"),
    ("R2", WIRING,
     """    if (from != Screen.Settings) return false
    sessions.refresh()""",
     """    sessions.refresh()""",
     "R2: 設定から出るときだけ、のガードを外す(ドロワー移動でページングが戻る)"),
    ("R3", WIRING,
     """        catalog.onConnectionChanged(baseUrl)
        serverInfo.onConnectionChanged(baseUrl)""",
     """        serverInfo.onConnectionChanged(baseUrl)""",
     "R3: 接続先変更でカタログを捨てない(Q4 の blocker が静かに再オープン)"),
    ("R7", WIRING, "    sessions.ensureLoaded()", "    sessions.refresh()",
     "R7: 画面表示で取り直す(Q1 major-1 のページング退行そのもの)"),
    ("R8", WIRING,
     """        catalog.onConnectionChanged(baseUrl)
        serverInfo.onConnectionChanged(baseUrl)""",
     """        catalog.onConnectionChanged(baseUrl)""",
     "R8: 接続先変更でサーバー素性を捨てない"),
    ("G1", CTRL,
     """        val before = _state.getAndUpdate { s ->
            if (s.pendingActionId != null) s else s.copy(pendingActionId = sessionId, actionError = null)
        }""",
     """        val before = _state.getAndUpdate { it.copy(pendingActionId = sessionId, actionError = null) }""",
     "major-1: rename の拒否経路が実行中要求の占有者を上書きする(1周目が持ち込んだ欠陥)"),
    ("G2", CTRL,
     """        val before = _state.getAndUpdate { s ->
            if (s.creating) s else s.copy(creating = true, createError = null)
        }""",
     """        val before = _state.getAndUpdate { it.copy(creating = true, createError = null) }""",
     "major-1[等価]: create の拒否経路が状態に書き込む(対称性のため直したが検出不能)",
     "EQUIVALENT"),
    ("G3", APPEAR, '''    info.loading -> "取得中…"
    info.error != null -> "取得できません"''',
     '''    info.error != null -> "取得中…"
    info.loading -> "取得中…"''',
     "minor-2: 取得失敗を「取得中…」と言い続ける(ドロワーが嘘をついていた形)"),
    ("B1", APPEAR, "fun drawerBackEnabled(drawerOpen: Boolean): Boolean = drawerOpen",
     "fun drawerBackEnabled(drawerOpen: Boolean): Boolean = false",
     "所見B: ドロワーが開いていても BACK を受けない(アプリが終了する元の症状)"),
    ("B2", APPEAR,
     "fun screenBackEnabled(canGoBack: Boolean, drawerOpen: Boolean): Boolean = canGoBack && !drawerOpen",
     "fun screenBackEnabled(canGoBack: Boolean, drawerOpen: Boolean): Boolean = canGoBack",
     "所見B: 画面側の BACK がドロワー中も有効(2つのハンドラが登録順で競合する)"),
    ("G4", WIRING,
     """    gateway.save(normalized, password)
    return SaveAndTestOutcome.Measured(normalized, gateway.health())""",
     """    val measured = SaveAndTestOutcome.Measured(normalized, gateway.health())
    gateway.save(normalized, password)
    return measured""",
     "保存より先に測る(古い接続先を測ってしまう)"),
]

GRADLE = os.path.join(ROOT, "gradlew.bat")


RESULT_DIR = os.path.join(ROOT, "app", "build", "test-results", "testDebugUnitTest")


def _clear_results():
    """前回の XML を消す。残っていると『今回落ちた』と取り違える。"""
    if os.path.isdir(RESULT_DIR):
        for fn in os.listdir(RESULT_DIR):
            if fn.endswith(".xml"):
                os.remove(os.path.join(RESULT_DIR, fn))


def _failing_tests():
    """XML から落ちたテスト名を拾う(**テスト失敗であること**の実証)。"""
    names = []
    if not os.path.isdir(RESULT_DIR):
        return names
    for fn in os.listdir(RESULT_DIR):
        if not fn.endswith(".xml"):
            continue
        with open(os.path.join(RESULT_DIR, fn), encoding="utf-8", errors="replace") as f:
            body = f.read()
        for m in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<(failure|error)', body):
            names.append(m.group(1))
    return names


def run_tests():
    """テストを回す。戻り: (green, ran, compile_error, failing, tail)。"""
    _clear_results()
    proc = subprocess.run(
        [GRADLE, ":app:testDebugUnitTest"],
        cwd=ROOT, capture_output=True, text=True, errors="replace",
    )
    out = proc.stdout + proc.stderr
    ran = ("BUILD SUCCESSFUL" in out) or ("BUILD FAILED" in out)
    green = "BUILD SUCCESSFUL" in out
    # **コンパイル失敗を「検出」と数えない。** BUILD FAILED はテストが落ちても
    # コンパイルが落ちても出る。区別しないと「変異が検出された」が嘘になりうる
    # (RUN_PLAN の第3の形と同じで、結論が良い方へ転ぶ校正漏れ)。
    compile_error = ("\ne: " in out) or out.startswith("e: ")
    failing = _failing_tests()
    tail = "\n".join(out.strip().splitlines()[-6:])
    return green, ran, compile_error, failing, tail


def main():
    results = []
    for entry in MUTATIONS:
        name, path, old, new, desc = entry[:5]
        expected = entry[5] if len(entry) > 5 else "DETECTED"
        backup = None
        changed = None
        if path is not None:
            with open(path, "rb") as f:
                backup = f.read()
            text = backup.decode("utf-8")
            # 改行が CRLF のファイルにも当たるよう、両方の形を試す。
            needle = old
            repl = new
            if needle not in text:
                needle = old.replace("\n", "\r\n")
                repl = new.replace("\n", "\r\n")
            if needle not in text:
                print(f"{name}: SKIP-NOMATCH  {desc}")
                results.append((name, "NOMATCH", desc))
                continue
            mutated = text.replace(needle, repl, 1)
            # (2) 変異が実際にファイルを変えたことを assert
            assert mutated != text, f"{name}: 変異がファイルを変えていない"
            with open(path, "wb") as f:
                f.write(mutated.encode("utf-8"))
            with open(path, "rb") as f:
                changed = f.read() != backup
            assert changed, f"{name}: 書き戻し後もバイト列が同じ"
        try:
            green, ran, compile_error, failing, tail = run_tests()
        finally:
            if path is not None:
                # 復元は**バイトコピー**。git checkout -- は使わない(HARNESS)。
                with open(path, "wb") as f:
                    f.write(backup)
                with open(path, "rb") as f:
                    assert f.read() == backup, f"{name}: 復元に失敗"
        # (3) テストランナーが実際に走ったことを assert
        if not ran:
            verdict = "RUNNER-DID-NOT-RUN"
        elif name == "M0":
            verdict = "CALIBRATION-GREEN" if green else "CALIBRATION-RED(異常)"
        elif compile_error:
            # コンパイルが落ちただけ = 検出器はこの変異を見ていない。
            verdict = "COMPILE-ERROR(検出ではない)"
        elif green and expected == "EQUIVALENT":
            # **等価変異**。`create` の拒否経路が書く値は既存の値と `equals` になるので
            # (`creating` は既に true、`createError` は既に null)、`MutableStateFlow` は
            # 置き換えず、公開状態からは区別できない。レビューの「今日は観測できない」と一致する。
            # **検出器の穴ではなく、変異の側に観測可能な差が無い。** 対称性のため修正は入れてある。
            verdict = "EQUIVALENT(観測可能な差が無い/想定どおり)"
        elif green:
            verdict = "SURVIVED(素通し)"
        elif not failing:
            verdict = "FAILED-BUT-NO-TEST-FAILURE(要調査)"
        else:
            verdict = "DETECTED"
        detail = ("  <- " + ", ".join(sorted(set(failing))[:3])) if failing else ""
        print(f"{name}: {verdict}  {desc}{detail}")
        if verdict.startswith("SURVIVED") or verdict.startswith("RUNNER") or verdict.endswith("(異常)"):
            print("  ---- gradle 末尾 ----")
            print("  " + tail.replace("\n", "\n  "))
        results.append((name, verdict, desc))

    print("\n==== まとめ ====")
    for name, verdict, desc in results:
        print(f"{name}\t{verdict}\t{desc}")
    ok = ("DETECTED", "CALIBRATION-GREEN", "EQUIVALENT(観測可能な差が無い/想定どおり)")
    bad = [r for r in results if r[1] not in ok]
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
