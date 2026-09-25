package dev.opencode.android

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.opencode.android.data.PtyDto
import dev.opencode.android.ui.MIN_TOUCH_TARGET
import dev.opencode.android.ui.PtyActionError
import dev.opencode.android.ui.PtyActions
import dev.opencode.android.ui.PtyConnectionState
import dev.opencode.android.ui.PtyKey
import dev.opencode.android.ui.PtyLifeState
import dev.opencode.android.ui.PtyListUi
import dev.opencode.android.ui.PtyScreenPane
import dev.opencode.android.ui.PtyShellsUi
import dev.opencode.android.ui.PtyTerminalUi
import dev.opencode.android.ui.PtyUi
import dev.opencode.android.ui.PtyListArea
import dev.opencode.android.ui.TerminalArea
import dev.opencode.android.ui.TerminalBuffer
import dev.opencode.android.ui.appendTerminalOutput
import dev.opencode.android.ui.terminalLineKey
import dev.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ターミナル画面の**描画そのもの**に検出器を置く(Q6 が入れた Compose UI テスト基盤の上)。
 *
 * ここが描くのは [PtyListArea] / [TerminalArea] **そのもの**である
 * (Q6 のレビューが blocker として挙げたトートロジー ——
 * テスト用の composable を自分で書いて自分で押す —— を避ける)。
 *
 * ## この段の陰性側
 *
 * §5b Q9 は「**全画面TUI を開いたとき、壊れた画面ではなく制限の説明が出る**」を
 * 陰性側のゲートとして定めている。**メタフレームの生 JSON や生のエスケープが
 * 画面に出ていないこと**も同じ形の主張なので、dump が読む2つ(`text` と `content-desc`)を
 * 全部集めて assert する。
 */
@RunWith(RobolectricTestRunner::class)
class Q9ComposeTest {

    @get:Rule
    val compose = createComposeRule()

    /** 呼ばれたことを控えるだけの宛先。**素通しの `Unit` を返さない。** */
    private class RecordingPtyActions : PtyActions {
        val calls = mutableListOf<String>()
        override fun refreshPtyList() { calls += "refresh" }
        override fun startPty(command: String, title: String) { calls += "start:$command:$title" }
        override fun openPty(pty: PtyDto) { calls += "open:${pty.id}" }
        override fun deletePty(ptyId: String) { calls += "delete:$ptyId" }
        override fun closeTerminal() { calls += "close" }
        override fun sendPtyLine(text: String) { calls += "line:$text" }
        override fun sendPtyKey(key: PtyKey) { calls += "key:${key.name}" }
        override fun reconnectPty() { calls += "reconnect" }
        override fun resizePty(rows: Int, cols: Int) { calls += "resize:${rows}x$cols" }
        override fun clearPtyActionError() { calls += "clearAction" }
        override fun clearPtyCreateError() { calls += "clearCreate" }

        /**
         * リサイズ以外の呼び出し。
         *
         * 端末を描くと**必ず1回リサイズが走る**(画面が寸法を測ってサーバーへ伝える経路が
         * 生きているため)。それ自体は別のテストが主張しているので、
         * 押した結果を見るテストからは除いて数える。
         */
        val interactions: List<String> get() = calls.filterNot { it.startsWith("resize:") }
    }

    /** 画面に出ている `text` と `content-desc` を**全部**集める(dump が読むのと同じ2つ)。 */
    private fun visibleStrings(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { walk(it) }
        }
        walk(compose.onRoot().fetchSemanticsNode())
        return out
    }

    private fun setList(ui: PtyUi, actions: PtyActions) {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                Column { PtyListArea(ui = ui, actions = actions, onOpenSettings = {}) }
            }
        }
    }

    private fun setTerminal(ui: PtyUi, actions: PtyActions) {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                Column { TerminalArea(ui = ui, actions = actions, onOpenSettings = {}) }
            }
        }
    }

    private val shells = PtyShellsUi(items = Q9Decode.shells(Q9Fixtures.SHELLS_JSON), loaded = true)
    private val running = Q9Decode.ptys(Q9Fixtures.LIST_PTY_JSON)

    // ---------------------------------------------------------------------
    // 一覧
    // ---------------------------------------------------------------------

    @Test
    fun `シェルの候補を押すと起動が呼ばれる`() {
        val actions = RecordingPtyActions()
        setList(PtyUi(shells = shells, list = PtyListUi(loaded = true)), actions)
        compose.onNodeWithContentDescription("pty-shell:cmd").assertHasClickAction().performClick()
        assertEquals(
            listOf("start:C:\\Windows\\system32\\cmd.exe:cmd"),
            actions.calls,
        )
    }

    /** `acceptable:false` は**消さず印を付ける**(「無い」と「薦められていない」の区別)。 */
    @Test
    fun `非推奨のシェルも候補に出る`() {
        val marked = shells.copy(
            items = shells.items.map { if (it.name == "bash") it.copy(acceptable = false) else it },
        )
        setList(PtyUi(shells = marked, list = PtyListUi(loaded = true)), RecordingPtyActions())
        compose.onNodeWithContentDescription("pty-shell:bash").assertExists()
        compose.onNodeWithText("bash(非推奨)").assertExists()
    }

    @Test
    fun `一覧の行を押すと開く`() {
        val actions = RecordingPtyActions()
        setList(PtyUi(shells = shells, list = PtyListUi(items = running, loaded = true)), actions)
        val id = running.single().id
        compose.onNodeWithContentDescription("pty-row:$id:running:58076")
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
            .performClick()
        assertEquals(listOf("open:$id"), actions.calls)
    }

    @Test
    fun `一覧の終了ボタンは削除を呼ぶ`() {
        val actions = RecordingPtyActions()
        setList(PtyUi(shells = shells, list = PtyListUi(items = running, loaded = true)), actions)
        val id = running.single().id
        compose.onNodeWithContentDescription("pty-row-delete:$id").performClick()
        assertEquals(listOf("delete:$id"), actions.calls)
    }

    @Test
    fun `空の一覧では空状態と再試行が出る`() {
        val actions = RecordingPtyActions()
        setList(PtyUi(shells = shells, list = PtyListUi(loaded = true)), actions)
        compose.onNodeWithContentDescription("empty-state:pty-empty").assertExists()
    }

    /** **401 では再試行を出さない**(再試行では直らない。Q6 からの規則)。 */
    @Test
    fun `401 の一覧は設定への導線だけを出す`() {
        setList(
            PtyUi(shells = shells, list = PtyListUi(loaded = true, error = "認証エラー", errorIsAuth = true)),
            RecordingPtyActions(),
        )
        compose.onNodeWithContentDescription("empty-state:pty-unauthorized").assertExists()
        compose.onNodeWithContentDescription("empty-state-action:pty-unauthorized:OPEN_SETTINGS").assertExists()
        assertTrue(visibleStrings().none { it == "empty-state-action:pty-unauthorized:RETRY" })
    }

    /** §5b Q9 の「セキュリティ上の注記」は**画面にも出す**。 */
    @Test
    fun `セキュリティの注意が画面に出る`() {
        setList(PtyUi(shells = shells, list = PtyListUi(loaded = true)), RecordingPtyActions())
        compose.onNodeWithContentDescription("pty-security-note").assertExists()
        assertTrue(visibleStrings().any { it.contains("Tailscale") })
        // **desc は包んだ行に付ける**(`pty-line:` と同じ理由。レビュー minor-5)——
        // 本文の `Text` 自身に付けると、TalkBack が注意文の代わりに
        // `pty-security-note` と読み上げる。**読み上げからだけ注意が消える。**
        compose.onNodeWithContentDescription("pty-security-note")
            .assert(!hasText("Tailscale", substring = true))
    }

    // ---------------------------------------------------------------------
    // 端末
    // ---------------------------------------------------------------------

    private fun terminalUi(
        buffer: TerminalBuffer = appendTerminalOutput(TerminalBuffer(), Q9Fixtures.BANNER_FRAME_1),
        life: PtyLifeState = PtyLifeState.Running,
        connection: PtyConnectionState = PtyConnectionState.CONNECTED,
    ) = PtyUi(
        terminal = PtyTerminalUi(
            ptyId = "pty_x",
            title = "cmd",
            connection = connection,
            life = life,
            buffer = buffer,
        ),
    )

    /**
     * **陰性側**: 生のエスケープもメタの JSON も画面に出ない。
     * dump が読む2つ(`text` / `content-desc`)を全部集めて確かめる。
     */
    @Test
    fun `画面に生のエスケープが出ていない`() {
        setTerminal(terminalUi(), RecordingPtyActions())
        val strings = visibleStrings()
        assertTrue(strings.any { it.contains("Microsoft Windows") })
        assertTrue(strings.none { it.contains('\u001b') })
        assertTrue(strings.none { it.contains("[?25l") })
        assertTrue(strings.none { it.contains("\"cursor\"") })
    }

    /** 制限の説明は**常に**出る(§5b スコープ2「空白の画面を出して黙らない」)。 */
    @Test
    fun `制限の説明と落とした件数が出る`() {
        setTerminal(terminalUi(), RecordingPtyActions())
        compose.onNodeWithContentDescription("pty-notice:pty-ansi-limited").assertExists()
        compose.onNodeWithContentDescription("pty-notice:pty-dropped:7").assertExists()
    }

    /** 全画面TUI を開くと**壊れた画面ではなく制限の説明**が出る(ゲートの陰性側)。 */
    @Test
    fun `代替画面へ入ると専用の注記が出る`() {
        val buffer = appendTerminalOutput(TerminalBuffer(), "\u001b[?1049h\u001b[2J\u001b[H")
        setTerminal(terminalUi(buffer = buffer), RecordingPtyActions())
        compose.onNodeWithContentDescription("pty-notice:pty-alt-screen:1").assertExists()
        assertTrue(visibleStrings().any { it.contains("表示されていません") })
    }

    /** **終了コードが画面に出る**(§5b ゲート「`exit` で `exitCode` が表示される」)。 */
    @Test
    fun `終了コードが帯に出る`() {
        setTerminal(
            terminalUi(life = PtyLifeState.Exited(7), connection = PtyConnectionState.CLOSED),
            RecordingPtyActions(),
        )
        compose.onNodeWithContentDescription("pty-status:pty-exited:7").assertExists()
        assertTrue(visibleStrings().any { it.contains("終了コード 7") })
    }

    /** **取れなかった終了コードを 0 と書かない**(このフェーズの中核)。 */
    @Test
    fun `終了コードが取れなかったときは 0 と出さない`() {
        setTerminal(
            terminalUi(life = PtyLifeState.ExitedCodeUnknown, connection = PtyConnectionState.CLOSED),
            RecordingPtyActions(),
        )
        compose.onNodeWithContentDescription("pty-status:pty-exited-code-unknown").assertExists()
        val strings = visibleStrings()
        assertTrue(strings.any { it.contains("0 だったという意味ではありません") })
        assertTrue(strings.none { it.contains("終了コード 0") })
    }

    /** 終わったシェルには**再接続ボタンを出さない**(繋ぎ直せない)。 */
    @Test
    fun `終了後は再接続ボタンが出ない`() {
        setTerminal(
            terminalUi(life = PtyLifeState.Exited(0), connection = PtyConnectionState.CLOSED),
            RecordingPtyActions(),
        )
        assertTrue(visibleStrings().none { it == "pty-reconnect" })
    }

    @Test
    fun `切断中は再接続ボタンが押せる`() {
        val actions = RecordingPtyActions()
        setTerminal(
            terminalUi(connection = PtyConnectionState.CLOSED),
            actions,
        )
        compose.onNodeWithContentDescription("pty-reconnect").performClick()
        assertTrue(actions.calls.contains("reconnect"))
    }

    /**
     * **Ctrl+C の宛先が composable の中に在ること**を本物のノードを押して確かめる。
     * Q7 の変異 RD(`onUnrevert = { }`)と同じ形の穴がここに開くと、
     * **暴走したコマンドを止める唯一の手段が無音で死ぬ**。
     */
    @Test
    fun `Ctrl+C ボタンが宛先へ繋がっている`() {
        val actions = RecordingPtyActions()
        setTerminal(terminalUi(), actions)
        compose.onNodeWithContentDescription("pty-key:CTRL_C")
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
            .performClick()
        assertEquals(listOf("key:CTRL_C"), actions.interactions)
    }

    /** 終わったシェルには打てない(**入力欄も補助キーも無効**)。 */
    @Test
    fun `終了後は入力できない`() {
        val actions = RecordingPtyActions()
        setTerminal(
            terminalUi(life = PtyLifeState.Exited(1), connection = PtyConnectionState.CLOSED),
            actions,
        )
        compose.onNodeWithContentDescription("pty-key:CTRL_C").performClick()
        assertTrue(actions.interactions.isEmpty())
    }

    /**
     * 出力の行が1行ずつ描かれ、横スクロールの計器が付いている。
     *
     * **`reverseLayout` でも行番号は本物の番号**である。添字をそのまま載せると
     * dump の `pty-line:1` が最終行を指し、「1行目が読めた」という証拠が逆さまになる。
     */
    @Test
    fun `出力の行と横スクロールの計器が出る`() {
        val buffer = appendTerminalOutput(
            appendTerminalOutput(TerminalBuffer(), Q9Fixtures.BANNER_FRAME_1),
            Q9Fixtures.BANNER_FRAME_2,
        )
        setTerminal(terminalUi(buffer = buffer), RecordingPtyActions())
        compose.onNodeWithContentDescription("pty-line:1").assertExists()
        compose.onNodeWithContentDescription("pty-line:2").assertExists()
        // 本文は画面に出ている。
        compose.onNodeWithText("Microsoft Windows [Version 10.0.26200.9168]", substring = true)
            .assertExists()
        // **ただし desc は本文の `Text` ではなく包んだ行に付く**(Q8 の `file-line:` と同じ。
        // レビュー minor-5)。`Text` 自身に付けると TalkBack が**本文の代わりに
        // `pty-line:1` と読み上げる** —— 端末は本文しか情報が無い画面なので、
        // 読み上げから中身が消えることを意味する。
        compose.onNodeWithContentDescription("pty-line:1")
            .assert(!hasText("Microsoft Windows [Version 10.0.26200.9168]", substring = true))
        assertTrue(visibleStrings().any { it.startsWith("pty-hscroll:") })
        // **描画の向き**まで主張する。`reverseLayout` の item 0 は**画面の一番下**なので、
        // 添字をそのまま行番号にすると **1行目が一番下に出る**(読み順が逆さまになる)。
        // desc と本文の対応は保たれたままなので、対応だけを見るテストからは見えない。
        val lineDescs = visibleStrings().filter { it.startsWith("pty-line:") }
        assertEquals("最初の item は最終行(reverseLayout)", "pty-line:2", lineDescs.first())
    }

    /** 開いていない端末では**空状態**が出る(白い画面を出して黙らない)。 */
    @Test
    fun `出力が来ていない接続済みの端末は理由を出す`() {
        setTerminal(
            PtyUi(
                terminal = PtyTerminalUi(
                    ptyId = "pty_x",
                    connection = PtyConnectionState.CONNECTED,
                ),
            ),
            RecordingPtyActions(),
        )
        compose.onNodeWithContentDescription("empty-state:pty-connected-no-output").assertExists()
    }

    /** 送れなかったことを画面に出す(黙って捨てない)。 */
    @Test
    fun `入力の失敗が画面に出て閉じられる`() {
        val actions = RecordingPtyActions()
        setTerminal(
            terminalUi().copy(
                actionError = PtyActionError("pty_x", "入力を送れませんでした(接続していません)", PtyScreenPane.TERMINAL),
            ),
            actions,
        )
        compose.onNodeWithContentDescription("pty-action-error").assertExists()
        compose.onNodeWithText("閉じる").performClick()
        assertEquals(listOf("clearAction"), actions.interactions)
    }

    /**
     * **アクションエラーは端末を跨がない**(Q9 E2E が未修正のまま記録した症状の描画側)。
     *
     * 状態に残っていても、**いま開いている端末のものでなければ描かない**。
     * 非同期の `PUT /pty/{id}` が端末を切り替えた**後**に失敗して返ってくる経路は
     * Controller のクリアだけでは閉じられないので、窓側にも検出器を置く。
     */
    @Test
    fun `別の PTY のアクションエラーは端末に出さない`() {
        setTerminal(
            terminalUi().copy(
                actionError = PtyActionError("pty_other", "サーバーエラー(HTTP 404)", PtyScreenPane.TERMINAL),
            ),
            RecordingPtyActions(),
        )
        compose.onNodeWithContentDescription("pty-action-error").assertDoesNotExist()
        assertTrue(visibleStrings().none { it.contains("サーバーエラー(HTTP 404)") })
    }

    /** 一覧側の操作(行の「終了」)の失敗は**黙って捨てない**。 */
    @Test
    fun `一覧のアクションエラーは一覧に出る`() {
        val actions = RecordingPtyActions()
        setList(
            PtyUi(
                shells = shells,
                list = PtyListUi(items = running, loaded = true),
                actionError = PtyActionError(running.first().id, "HTTP 500", PtyScreenPane.LIST),
            ),
            actions,
        )
        compose.onNodeWithContentDescription("pty-action-error").assertExists()
        compose.onNodeWithText("閉じる").performClick()
        assertEquals(listOf("clearAction"), actions.interactions)
    }

    /**
     * **端末で起こした失敗は一覧に出さない**(レビュー minor-1)。
     *
     * 端末 A でリサイズの `PUT` が飛行中に一覧へ戻ると `closeTerminal` が
     * `actionError` を消すが、**失敗はその後で返ってくる**ので書き直される。
     * `ptyId` だけを見る形では、もう開いていない端末のサーバーエラーが
     * 一覧の帯に出た。端末の窓を `ptyId == openPtyId` で塞いだのと同型の穴。
     */
    @Test
    fun `端末で起こした失敗は一覧に出さない`() {
        setList(
            PtyUi(
                shells = shells,
                list = PtyListUi(items = running, loaded = true),
                // 一覧へ戻った後に返ってきた、端末 A のリサイズ失敗。
                actionError = PtyActionError(running.first().id, "HTTP 404", PtyScreenPane.TERMINAL),
            ),
            RecordingPtyActions(),
        )
        compose.onNodeWithContentDescription("pty-action-error").assertDoesNotExist()
        assertTrue(visibleStrings().none { it.contains("HTTP 404") })
    }

    /**
     * **短い行が混ざっていても横スクロールが死なない**(HANDOFF §1「未修正のまま記録された
     * 軽微な不具合2件」の2件目)。
     *
     * 1周目は全行の `horizontalScroll` が同じ `ScrollState` を共有しており、
     * `horizontalScroll` の layout が測るたびに `maxValue` を書き込むので
     * **最後に測られた短い行が 0 を書いて横スクロールが効かなくなっていた**。
     * 端末の出力は短い行だらけなので、ここが症状の出た画面である。
     */
    @Test
    fun `短い行が混ざっても横スクロールの最大値が潰れない`() {
        val long = "x".repeat(400)
        var buffer = appendTerminalOutput(TerminalBuffer(), "$long\r\n")
        buffer = appendTerminalOutput(buffer, "ok\r\n")
        setTerminal(terminalUi(buffer = buffer), RecordingPtyActions())
        val desc = visibleStrings().single { it.startsWith("pty-hscroll:") }
        val max = desc.substringAfter("/").toInt()
        assertTrue("短い行が maxValue を 0 に潰している: $desc", max > 0)
    }

    /**
     * **TalkBack から横スクロールできること**(レビュー minor-3)。
     *
     * 旧実装の `Modifier.horizontalScroll` は `HorizontalScrollAxisRange` と
     * `ScrollBy` アクションを**自分で**行に載せていた。`Modifier.scrollable` は
     * ジェスチャーしか載せないので、置き換えただけでは
     * **支援技術から「横に送れる要素」だと分からず、送る手段も消える**。
     * `content-desc`(judge の計器)が在ることは上のテストが見ているが、
     * それは人の操作性を1文字も保証しない。
     */
    @Test
    fun `横スクロールがTalkBackから操作できる`() {
        val long = "x".repeat(400)
        val buffer = appendTerminalOutput(TerminalBuffer(), "$long\r\n")
        setTerminal(terminalUi(buffer = buffer), RecordingPtyActions())
        val node = compose.onNodeWithContentDescription("pty-hscroll:", substring = true)
        node.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        node.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))

        // 実際に送れる(range が動く)。**在るだけの飾りではない**ことを示す。
        val range = node.fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("送れる量が 0 では操作の意味が無い", range.maxValue() > 0f)
        assertEquals(0f, range.value(), 0f)
        node.performSemanticsAction(SemanticsActions.ScrollBy) { it(50f, 0f) }
        compose.waitForIdle()
        assertEquals(50f, range.value(), 0f)
    }

    /**
     * 出力の行キーは**本物の行番号**から作る([terminalLineKey])。
     *
     * `reverseLayout` なので `LazyColumn` の添字と行番号は逆順である。
     * 添字から作ると、1行増えるたびに同じ鍵が別の行を指す。
     * この鍵は `LazyColumn` の item key と [LineScrollState] の行キーの**両方**に使う
     * (レビュー minor-5。[dev.opencode.android.ui.diffLineKey] と同じ規則)。
     */
    @Test
    fun `出力の行キーは行番号から作る`() {
        assertEquals("pty-line:0", terminalLineKey(0))
        assertEquals("pty-line:41", terminalLineKey(41))
    }

    // ---------------------------------------------------------------------
    // 画面全体(どちらの面を描いているか)
    // ---------------------------------------------------------------------

    /**
     * **`terminal-screen:<面>` が dump から読めること。**
     *
     * 1周目は面の識別子を `Text("")` に付けており、**実機の `uiautomator dump` に
     * 1度も現れなかった**(大きさ0のノードは出ない)。計器が測れていない形なので、
     * 大きさを持つノード(画面全体の Column)へ移した。
     */
    @Test
    fun `画面の面が識別子に載る`() {
        val actions = RecordingPtyActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                dev.opencode.android.ui.TerminalScreen(
                    ui = PtyUi(shells = shells, list = PtyListUi(loaded = true)),
                    actions = actions,
                    onBack = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("terminal-screen:LIST").assertExists()
        assertTrue(visibleStrings().none { it == "terminal-screen:TERMINAL" })
    }

    @Test
    fun `端末を開いていれば面は TERMINAL`() {
        val actions = RecordingPtyActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                dev.opencode.android.ui.TerminalScreen(
                    ui = terminalUi(),
                    actions = actions,
                    onBack = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("terminal-screen:TERMINAL").assertExists()
        // 題は開いている端末のものになる。
        compose.onNodeWithText("cmd").assertExists()
    }

    /** 端末サイズを測って**サーバーへ伝える経路が生きている**。 */
    @Test
    fun `端末サイズが計測されて resize が呼ばれる`() {
        val actions = RecordingPtyActions()
        setTerminal(terminalUi(), actions)
        compose.waitForIdle()
        assertTrue(actions.calls.any { it.startsWith("resize:") })
        assertFalse(actions.calls.contains("resize:0x0"))
    }
}
