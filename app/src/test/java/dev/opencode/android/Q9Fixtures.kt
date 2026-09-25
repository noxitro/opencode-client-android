package dev.opencode.android

/**
 * Q9(PTY)の**実データ**。すべて実物 `opencode serve` 1.18.21 から採取した
 * (2026-08-30、Windows 11、ポート4097、`cmd.exe`)。
 *
 * QUALITY_PLAN §4.2 が4度の失敗の後に定めた規則 ——
 * 「**新規イベント/レスポンスのフィクスチャは実機レスポンスから形をコピーする。
 * 文字列で代用した近似を作らない**」——に従う。
 *
 * ここに貼ってあるのは要約ではなく、**採取したバイト列そのもの**である
 * (`\u001b` を使うのは Kotlin ソースに生の制御文字を置かないためだけで、値は同じ)。
 */
object Q9Fixtures {

    /** `GET /pty/shells` の応答。**要素はオブジェクト**である(計画書は「一覧」としか書いていない)。 */
    const val SHELLS_JSON = """
[{"path":"C:\\Program Files\\PowerShell\\7\\pwsh.EXE","name":"pwsh","acceptable":true},
 {"path":"C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.EXE","name":"powershell","acceptable":true},
 {"path":"C:\\Program Files\\Git\\bin\\bash.exe","name":"bash","acceptable":true},
 {"path":"C:\\Windows\\system32\\cmd.exe","name":"cmd","acceptable":true}]
"""

    /**
     * `POST /pty` の応答(200)。**`exitCode` が無い**ことに意味がある ——
     * spec には在るが、REST では一度も観測されていない。
     */
    const val CREATE_PTY_JSON = """
{"id":"pty_04f246e3f001Pdm7dahQta7B5e","title":"probe","command":"cmd.exe","args":[],
 "cwd":"E:\\dev\\github.com\\noxitro\\opencode-android","status":"running","pid":58556}
"""

    /** `GET /pty` の応答(走っている1件)。 */
    const val LIST_PTY_JSON = """
[{"id":"pty_04f2528ca001vnCCP68BAoLQlf","title":"v1probe","command":"cmd.exe","args":[],
  "cwd":"E:\\dev\\github.com\\noxitro\\opencode-android","status":"running","pid":58076}]
"""

    /** `POST /pty/{id}/connect-token` の応答(`x-opencode-ticket: 1` あり)。 */
    const val TICKET_JSON = """{"ticket":"65ffeda9-f427-4fd0-b10b-73c241ab6b4a","expires_in":60}"""

    /** `POST /pty/{id}/connect-token` の応答(**ヘッダ無し** → 403)。 */
    const val FORBIDDEN_JSON =
        """{"_tag":"PtyForbiddenError","message":"Invalid PTY connect token request"}"""

    /** 終了後の `GET /pty/{id}`(404)。**終了した PTY は REST から消える。** */
    const val NOT_FOUND_JSON = """
{"_tag":"PtyNotFoundError","ptyID":"pty_04f246e3f001Pdm7dahQta7B5e",
 "message":"PTY session not found: pty_04f246e3f001Pdm7dahQta7B5e"}
"""

    // ---- SSE(`GET /event`)----

    /** `pty.created`。 */
    const val EVENT_CREATED = """
{"id":"evt_04f2528d4001HNmsxXtFNCiLUm","type":"pty.created","properties":{"info":
 {"id":"pty_04f2528ca001vnCCP68BAoLQlf","title":"v1probe","command":"cmd.exe","args":[],
  "cwd":"E:\\dev\\github.com\\noxitro\\opencode-android","status":"running","pid":58076}}}
"""

    /**
     * `pty.exited`。**終了コードの唯一の出所**である。
     * `cmd.exe` に `exit 7` を送って採取した(コードが 0 でない実データを選んである ——
     * 0 だと「取れなかった値を 0 と書く」欠陥がフィクスチャから見えない)。
     */
    const val EVENT_EXITED = """
{"id":"evt_04f25bdda001kobfy4aKcsu4SM","type":"pty.exited",
 "properties":{"id":"pty_04f25b0a9001inpIll4Gaah6gp","exitCode":7}}
"""

    /** `pty.deleted`(`DELETE /pty/{id}`)。**`exitCode` が無い。** */
    const val EVENT_DELETED = """
{"id":"evt_04f2528f7001oCwGZQrhl1Nohh","type":"pty.deleted",
 "properties":{"id":"pty_04f2528ca001vnCCP68BAoLQlf"}}
"""

    /** `pty.exited` の EXITED PTY のID(上の [EVENT_EXITED] と揃える)。 */
    const val EXITED_PTY_ID = "pty_04f25b0a9001inpIll4Gaah6gp"

    // ---- WebSocket のフレーム(採取したバイト列)----

    /**
     * メタフレームの生バイト。**先頭 `0x00` + JSON**。
     * 接続直後(まだ何も出力されていない)に届いたので `cursor` は 0 である。
     */
    val META_FRAME_CURSOR_0: ByteArray = byteArrayOf(
        0x00, 0x7b, 0x22, 0x63, 0x75, 0x72, 0x73, 0x6f,
        0x72, 0x22, 0x3a, 0x30, 0x7d,
    )

    /**
     * 別接続で届いたメタ(リプレイ 223 バイトの後)。
     * `00 7b 22 63 75 72 73 6f 72 22 3a 32 32 33 7d` = `\u0000{"cursor":223}`。
     */
    val META_FRAME_CURSOR_223: ByteArray = byteArrayOf(
        0x00, 0x7b, 0x22, 0x63, 0x75, 0x72, 0x73, 0x6f,
        0x72, 0x22, 0x3a, 0x32, 0x32, 0x33, 0x7d,
    )

    /**
     * 接続直後の1フレーム目(**113 バイト**)。
     * `[?9001h` `[?1004h` `[?25l` `[2J` `[m` `[H` と OSC 0 が**バナーより先に**来る。
     */
    const val BANNER_FRAME_1 =
        "\u001b[?9001h\u001b[?1004h\u001b[?25l\u001b[2J\u001b[m\u001b[H" +
            "Microsoft Windows [Version 10.0.26200.9168]" +
            "\u001b]0;C:\\Windows\\system32\\cmd.exe\u0007" +
            "\u001b[?25h"

    /** 2フレーム目(**110 バイト**)。`ESC[4;1H` のカーソル移動が含まれる。 */
    const val BANNER_FRAME_2 =
        "\u001b[?25l\r\n(c) Microsoft Corporation. All rights reserved." +
            "\u001b[4;1HE:\\dev\\github.com\\noxitro\\opencode-android>" +
            "\u001b[?25h"

    /** `echo hello\r` を送ったときのエコー(**12 バイト**)。 */
    const val ECHO_INPUT_FRAME = "echo hello\r\n"

    /** その出力(**66 バイト**)。`hello` の後にカーソル移動が来る。 */
    const val ECHO_OUTPUT_FRAME =
        "\u001b[?25lhello\u001b[7;1HE:\\dev\\github.com\\noxitro\\opencode-android>\u001b[?25h"

    /** Ctrl+C(U+0003)を送った直後の出力(**45 バイト**)。改行して新しいプロンプトが出る。 */
    const val CTRL_C_FRAME = "\r\nE:\\dev\\github.com\\noxitro\\opencode-android>"
}
