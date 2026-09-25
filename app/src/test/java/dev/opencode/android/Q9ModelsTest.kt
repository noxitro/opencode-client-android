package dev.opencode.android

import dev.opencode.android.ui.AnsiControlKind
import dev.opencode.android.ui.EmptyStateAction
import dev.opencode.android.ui.EmptyStateTone
import dev.opencode.android.ui.PtyConnectionState
import dev.opencode.android.ui.PtyKey
import dev.opencode.android.ui.PtyActionError
import dev.opencode.android.ui.PtyLifeState
import dev.opencode.android.ui.PtyListUi
import dev.opencode.android.ui.PtyScreenPane
import dev.opencode.android.ui.PtyTerminalUi
import dev.opencode.android.ui.PtyUi
import dev.opencode.android.ui.ScreenBody
import dev.opencode.android.ui.TERMINAL_MIN_COLS
import dev.opencode.android.ui.TERMINAL_MIN_ROWS
import dev.opencode.android.ui.TerminalBuffer
import dev.opencode.android.ui.appendTerminalOutput
import dev.opencode.android.ui.classifyClosedSocket
import dev.opencode.android.ui.ptyKeyLabel
import dev.opencode.android.ui.ptyKeySequence
import dev.opencode.android.ui.ptyListBody
import dev.opencode.android.ui.ptyListEmptyState
import dev.opencode.android.ui.ptyActionErrorMessage
import dev.opencode.android.ui.ptyRowDescription
import dev.opencode.android.ui.ptyScreenPane
import dev.opencode.android.ui.ptyShellLabel
import dev.opencode.android.ui.terminalBody
import dev.opencode.android.ui.terminalEmptyState
import dev.opencode.android.ui.terminalGridOf
import dev.opencode.android.ui.terminalNotices
import dev.opencode.android.ui.terminalScreenTitle
import dev.opencode.android.ui.terminalStatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q9 の純関数([dev.opencode.android.ui.PtyModels] / [dev.opencode.android.ui.EmptyStates])。
 *
 * ここで守るのは **`docs/HANDOFF.md` §5 の中核原則**である ——
 * 「無い」と「取れなかった」を混同しない。Q9 における具体的な形は
 * **「シェルが終わった」と「こちらの接続が切れた」と「終了コードを受け取れなかった」の3つ**である。
 */
class Q9ModelsTest {

    // ---------------------------------------------------------------------
    // 補助キー
    // ---------------------------------------------------------------------

    /** 実測: `Ctrl+C` は U+0003 の1文字で足りる。 */
    @Test
    fun `補助キーの送出バイト`() {
        assertEquals("\u0003", ptyKeySequence(PtyKey.CTRL_C))
        assertEquals("\u0004", ptyKeySequence(PtyKey.CTRL_D))
        assertEquals("\t", ptyKeySequence(PtyKey.TAB))
        assertEquals("\u001b", ptyKeySequence(PtyKey.ESC))
        assertEquals("\r", ptyKeySequence(PtyKey.ENTER))
        assertEquals("\u001b[A", ptyKeySequence(PtyKey.UP))
        assertEquals("\u001b[B", ptyKeySequence(PtyKey.DOWN))
        assertEquals("\u001b[C", ptyKeySequence(PtyKey.RIGHT))
        assertEquals("\u001b[D", ptyKeySequence(PtyKey.LEFT))
    }

    /** **全部のキーにラベルがある**(足したのにラベルを忘れる形を潰す)。 */
    @Test
    fun `補助キーのラベルは空でない`() {
        PtyKey.entries.forEach { assertTrue(ptyKeyLabel(it).isNotBlank()) }
    }

    // ---------------------------------------------------------------------
    // 切断か終了かの判定
    // ---------------------------------------------------------------------

    @Test
    fun `一覧に居れば実行中`() {
        assertEquals(PtyLifeState.Running, classifyClosedSocket(PtyLifeState.Running, true))
    }

    @Test
    fun `一覧から消えていれば終了コード不明`() {
        assertEquals(PtyLifeState.ExitedCodeUnknown, classifyClosedSocket(PtyLifeState.Running, false))
    }

    /** **引けなかったら「終わった」と言わない。** */
    @Test
    fun `一覧が引けなければ判断しない`() {
        assertEquals(PtyLifeState.Running, classifyClosedSocket(PtyLifeState.Running, null))
        assertEquals(PtyLifeState.Unknown, classifyClosedSocket(PtyLifeState.Unknown, null))
    }

    /** **終了コードを持っている状態を「不明」へ落とさない**(情報の後退)。 */
    @Test
    fun `既に終了コードを持っていれば上書きしない`() {
        assertEquals(PtyLifeState.Exited(7), classifyClosedSocket(PtyLifeState.Exited(7), false))
        assertEquals(PtyLifeState.Deleted, classifyClosedSocket(PtyLifeState.Deleted, false))
    }

    // ---------------------------------------------------------------------
    // 帯(終了と切断の区別が出る唯一の場所)
    // ---------------------------------------------------------------------

    @Test
    fun `終了コードが分かっていれば数値を出す`() {
        val line = terminalStatusLine(PtyTerminalUi(ptyId = "p", life = PtyLifeState.Exited(7)))
        assertEquals("pty-exited:7", line.key)
        assertTrue(line.text.contains("7"))
        assertEquals(EmptyStateTone.ERROR, line.tone)
    }

    @Test
    fun `終了コード 0 は異常扱いにしない`() {
        assertEquals(
            EmptyStateTone.NEUTRAL,
            terminalStatusLine(PtyTerminalUi(ptyId = "p", life = PtyLifeState.Exited(0))).tone,
        )
    }

    /**
     * **「0 だったという意味ではありません」と言い切る。**
     * これが Q9 における「無い/取れなかった」の本体である。
     */
    @Test
    fun `終了コードが取れなかったときは 0 と言わない`() {
        val line = terminalStatusLine(PtyTerminalUi(ptyId = "p", life = PtyLifeState.ExitedCodeUnknown))
        assertEquals("pty-exited-code-unknown", line.key)
        assertTrue(line.text.contains("0 だったという意味ではありません"))
    }

    @Test
    fun `削除では終了コードが分からないと言う`() {
        val line = terminalStatusLine(PtyTerminalUi(ptyId = "p", life = PtyLifeState.Deleted))
        assertEquals("pty-deleted", line.key)
        assertTrue(line.text.contains("終了コード"))
    }

    /**
     * **生死は接続より先に見る。** 逆順だと「シェルは終了しました」が
     * 「接続が切れました」で塗り潰される。
     */
    @Test
    fun `終了は接続状態より優先する`() {
        val line = terminalStatusLine(
            PtyTerminalUi(
                ptyId = "p",
                life = PtyLifeState.Exited(3),
                connection = PtyConnectionState.CLOSED,
                closeCode = 1000,
            ),
        )
        assertEquals("pty-exited:3", line.key)
    }

    /** 切断の帯には close code が載る(`null` と `1000` は別のこと)。 */
    @Test
    fun `切断の帯は close code を載せる`() {
        assertEquals(
            "pty-disconnected:1000",
            terminalStatusLine(
                PtyTerminalUi(ptyId = "p", connection = PtyConnectionState.CLOSED, closeCode = 1000),
            ).key,
        )
        assertEquals(
            "pty-disconnected:none",
            terminalStatusLine(
                PtyTerminalUi(ptyId = "p", connection = PtyConnectionState.CLOSED, closeCode = null),
            ).key,
        )
    }

    @Test
    fun `再接続中は試行回数を載せる`() {
        assertEquals(
            "pty-reconnecting:3",
            terminalStatusLine(
                PtyTerminalUi(
                    ptyId = "p",
                    connection = PtyConnectionState.RECONNECTING,
                    reconnectAttempt = 3,
                ),
            ).key,
        )
    }

    /**
     * **生死は「直前のエラー」よりも先に見る**(レビュー minor-7)。
     *
     * `error` は接続に失敗したときの1行で、生死とは別軸である。先に見ると
     * 「接続に失敗 → その後 SSE で `pty.exited` が届く」経路で、
     * **終わったのに古い接続エラーが帯に出たまま**になる。
     */
    @Test
    fun `終了はいま出ているエラーより優先する`() {
        assertEquals(
            "pty-exited:0",
            terminalStatusLine(
                PtyTerminalUi(
                    ptyId = "p",
                    life = PtyLifeState.Exited(0),
                    connection = PtyConnectionState.CLOSED,
                    error = "サーバーエラー(HTTP 404)",
                ),
            ).key,
        )
        assertEquals(
            "pty-exited-code-unknown",
            terminalStatusLine(
                PtyTerminalUi(ptyId = "p", life = PtyLifeState.ExitedCodeUnknown, error = "net:down"),
            ).key,
        )
        assertEquals(
            "pty-deleted",
            terminalStatusLine(
                PtyTerminalUi(ptyId = "p", life = PtyLifeState.Deleted, error = "HTTP 404"),
            ).key,
        )
    }

    /**
     * **「上限まで試して諦めた」と「まだ1回も試していない」は別の帯**(レビュー minor-1)。
     *
     * どちらも `CLOSED` なので、印が無ければ同じ `pty-disconnected:<code>` になる。
     * 諦めた側は**押されるまで何も起きない**ので、同じ文言だと
     * 「そのうち復帰する」と読める。
     */
    @Test
    fun `諦めた切断はまだ試していない切断と別の帯になる`() {
        val gaveUp = terminalStatusLine(
            PtyTerminalUi(
                ptyId = "p",
                connection = PtyConnectionState.CLOSED,
                closeCode = 1006,
                reconnectAttempt = 5,
                gaveUpReconnecting = true,
            ),
        )
        val neverTried = terminalStatusLine(
            PtyTerminalUi(ptyId = "p", connection = PtyConnectionState.CLOSED, closeCode = 1006),
        )
        assertEquals("pty-reconnect-gave-up:5", gaveUp.key)
        assertEquals("pty-disconnected:1006", neverTried.key)
        assertTrue(gaveUp.key != neverTried.key)
        assertTrue(gaveUp.text != neverTried.text)
        // **諦めたことを言葉でも言う**(key は judge の計器であって、人の言葉ではない)。
        assertTrue(gaveUp.text.contains("5 回"))
        assertTrue(gaveUp.text.contains("自動"))
        assertFalse(neverTried.text.contains("自動"))
    }

    @Test
    fun `エラーは認証かどうかで key が変わる`() {
        assertEquals(
            "pty-status-unauthorized",
            terminalStatusLine(PtyTerminalUi(ptyId = "p", error = "x", errorIsAuth = true)).key,
        )
        assertEquals(
            "pty-status-error",
            terminalStatusLine(PtyTerminalUi(ptyId = "p", error = "x")).key,
        )
    }

    // ---------------------------------------------------------------------
    // 注記(制限の明示 + 落とした件数)
    // ---------------------------------------------------------------------

    /**
     * §5b Q9 スコープ2 は「制限をUIに明示する(空白の画面を出して黙らない)」を求めている。
     * **常に出る**ことを固定する。
     */
    @Test
    fun `制限の説明は常に出る`() {
        val notices = terminalNotices(PtyTerminalUi())
        assertEquals("pty-ansi-limited", notices.first().key)
        assertTrue(notices.first().text.contains("vim"))
        assertTrue(notices.first().text.contains("top"))
    }

    /** 落とした件数を種別ごとに出す(「来ていない」と「解釈しなかった」の区別)。 */
    @Test
    fun `落とした件数が注記になる`() {
        val buffer = appendTerminalOutput(TerminalBuffer(), Q9Fixtures.BANNER_FRAME_1)
        val notices = terminalNotices(PtyTerminalUi(buffer = buffer))
        val dropped = notices.single { it.key.startsWith("pty-dropped:") }
        assertEquals("pty-dropped:7", dropped.key)
        assertTrue(dropped.text.contains("カーソル移動 1"))
        assertTrue(dropped.text.contains("画面消去 1"))
        assertTrue(dropped.text.contains("OSC 1"))
    }

    /** 1件も来ていなければ件数の注記は出さない(制限の説明だけ)。 */
    @Test
    fun `落とした物が無ければ件数の注記は出ない`() {
        val notices = terminalNotices(PtyTerminalUi())
        assertEquals(listOf("pty-ansi-limited"), notices.map { it.key })
    }

    /** **代替画面は別の注記**にする。`vim` / `top` を開いたときの唯一の手掛かり。 */
    @Test
    fun `代替画面は専用の注記になる`() {
        val buffer = appendTerminalOutput(TerminalBuffer(), "\u001b[?1049h")
        val notices = terminalNotices(PtyTerminalUi(buffer = buffer))
        val alt = notices.single { it.key.startsWith("pty-alt-screen:") }
        assertEquals("pty-alt-screen:1", alt.key)
        assertTrue(alt.text.contains("表示されていません"))
        assertEquals(1, buffer.dropped[AnsiControlKind.ALT_SCREEN])
    }

    @Test
    fun `行を捨てたら注記に出る`() {
        val buffer = appendTerminalOutput(TerminalBuffer(), (1..10).joinToString("\n"), maxLines = 3)
        val notices = terminalNotices(PtyTerminalUi(buffer = buffer))
        assertTrue(notices.any { it.key == "pty-trimmed:7" })
    }

    // ---------------------------------------------------------------------
    // 空状態
    // ---------------------------------------------------------------------

    @Test
    fun `取得前の空は空状態にしない`() {
        assertNull(ptyListEmptyState(PtyListUi(loaded = false)))
        assertEquals(ScreenBody.Loading, ptyListBody(PtyListUi(loading = true)))
    }

    @Test
    fun `取得後の空はターミナルが無いと言う`() {
        val spec = ptyListEmptyState(PtyListUi(loaded = true))
        assertEquals("pty-empty", spec?.key)
    }

    @Test
    fun `一覧の 401 は再試行を出さない`() {
        val spec = ptyListEmptyState(PtyListUi(loaded = true, error = "e", errorIsAuth = true))
        assertEquals("pty-unauthorized", spec?.key)
        assertEquals(
            listOf(EmptyStateAction.OPEN_SETTINGS),
            spec?.actions?.map { it.action },
        )
    }

    @Test
    fun `端末を開いていなければ その旨を出す`() {
        assertEquals("pty-none-open", terminalEmptyState(PtyTerminalUi())?.key)
    }

    /** **「まだ来ていない」と「終わったのに何も出なかった」を分ける。** */
    @Test
    fun `出力が無い理由を分ける`() {
        assertEquals(
            "pty-connected-no-output",
            terminalEmptyState(
                PtyTerminalUi(ptyId = "p", connection = PtyConnectionState.CONNECTED),
            )?.key,
        )
        assertEquals(
            "pty-no-output",
            terminalEmptyState(
                PtyTerminalUi(ptyId = "p", life = PtyLifeState.ExitedCodeUnknown),
            )?.key,
        )
    }

    /** **1文字でも受け取っていれば本文が勝つ。** 切れても読んだ分を消さない。 */
    @Test
    fun `出力があれば切断していても本文を出す`() {
        val buffer = appendTerminalOutput(TerminalBuffer(), "hello")
        val ui = PtyTerminalUi(ptyId = "p", buffer = buffer, connection = PtyConnectionState.CLOSED)
        assertEquals(ScreenBody.Content, terminalBody(ui))
        assertNull(terminalEmptyState(ui))
    }

    // ---------------------------------------------------------------------
    // 画面の分岐と寸法
    // ---------------------------------------------------------------------

    @Test
    fun `端末を開いていれば TERMINAL を描く`() {
        assertEquals(PtyScreenPane.LIST, ptyScreenPane(PtyUi()))
        assertEquals(
            PtyScreenPane.TERMINAL,
            ptyScreenPane(PtyUi(terminal = PtyTerminalUi(ptyId = "p"))),
        )
    }

    @Test
    fun `題は開いている端末を優先する`() {
        assertEquals("ターミナル", terminalScreenTitle(PtyUi()))
        assertEquals(
            "cmd",
            terminalScreenTitle(PtyUi(terminal = PtyTerminalUi(ptyId = "p", title = "cmd"))),
        )
        // 題が空なら既定へ戻る(生の ID を出さない)。
        assertEquals(
            "ターミナル",
            terminalScreenTitle(PtyUi(terminal = PtyTerminalUi(ptyId = "p", title = ""))),
        )
    }

    @Test
    fun `寸法は下限を割らない`() {
        assertEquals(TERMINAL_MIN_ROWS to TERMINAL_MIN_COLS, terminalGridOf(0f, 0f, 8f, 16f))
        assertEquals(TERMINAL_MIN_ROWS to TERMINAL_MIN_COLS, terminalGridOf(100f, 100f, 0f, 0f))
        assertEquals(30 to 100, terminalGridOf(800f, 480f, 8f, 16f))
    }


    // ---------------------------------------------------------------------
    // 表示の細部
    // ---------------------------------------------------------------------

    /** `acceptable:false` を消さず**印を付ける**(「無い」と「薦められていない」の区別)。 */
    @Test
    fun `非推奨シェルには印が付く`() {
        assertEquals("bash", ptyShellLabel("bash", acceptable = true))
        assertEquals("bash(非推奨)", ptyShellLabel("bash", acceptable = false))
    }

    @Test
    fun `一覧の行の識別子は id と状態と pid を持つ`() {
        val pty = Q9Decode.ptys(Q9Fixtures.LIST_PTY_JSON).single()
        assertEquals("pty-row:pty_04f2528ca001vnCCP68BAoLQlf:running:58076", ptyRowDescription(pty))
    }

    // ---------------------------------------------------------------------
    // アクションエラーをどの窓に出すか(レビュー minor-1)
    // ---------------------------------------------------------------------

    /**
     * **端末の窓は、いま開いている端末を対象にした失敗だけを出す。**
     */
    @Test
    fun `端末の窓は別の PTY の失敗を出さない`() {
        val mine = PtyActionError("pty_a", "HTTP 500", PtyScreenPane.TERMINAL)
        val other = PtyActionError("pty_b", "HTTP 500", PtyScreenPane.TERMINAL)
        assertEquals("HTTP 500", ptyActionErrorMessage(mine, PtyScreenPane.TERMINAL, "pty_a"))
        assertNull(ptyActionErrorMessage(other, PtyScreenPane.TERMINAL, "pty_a"))
        assertNull(ptyActionErrorMessage(null, PtyScreenPane.TERMINAL, "pty_a"))
    }

    /**
     * **一覧の窓は、一覧から起こした操作の失敗だけを出す。**
     *
     * 塞いでいる競合: 端末 A でリサイズの `PUT` が飛行中に一覧へ戻る
     * (`closeTerminal` が `actionError` を消す)→ **失敗が後から返って書き直される**
     * → もう開いていない端末のサーバーエラーが一覧の帯に出る。
     * `ptyId` は端末 A のままなので、`ptyId` だけを見る形では塞げない。
     */
    @Test
    fun `一覧の窓は端末で起こした失敗を出さない`() {
        val fromList = PtyActionError("pty_a", "HTTP 500", PtyScreenPane.LIST)
        val fromTerminal = PtyActionError("pty_a", "HTTP 404", PtyScreenPane.TERMINAL)
        assertEquals("HTTP 500", ptyActionErrorMessage(fromList, PtyScreenPane.LIST, null))
        assertNull(ptyActionErrorMessage(fromTerminal, PtyScreenPane.LIST, null))
        // 端末を閉じた後(`openPtyId == null`)でも、端末で起こした失敗は
        // **どちらの窓にも出ない** —— 出す先が無いのが正しい。
        assertNull(ptyActionErrorMessage(fromTerminal, PtyScreenPane.TERMINAL, null))
    }

    /** 終わったシェルへは打てない。**判定は1か所**([PtyTerminalUi.inputEnabled])。 */
    @Test
    fun `終わっていれば入力できない`() {
        val connected = PtyTerminalUi(ptyId = "p", connection = PtyConnectionState.CONNECTED)
        assertTrue(connected.inputEnabled)
        assertFalse(connected.copy(life = PtyLifeState.Exited(0)).inputEnabled)
        assertFalse(connected.copy(life = PtyLifeState.ExitedCodeUnknown).inputEnabled)
        assertFalse(connected.copy(life = PtyLifeState.Deleted).inputEnabled)
        assertFalse(connected.copy(connection = PtyConnectionState.RECONNECTING).inputEnabled)
    }
}
