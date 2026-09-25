package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.PTY_MAX_RECONNECT_ATTEMPTS
import dev.opencode.android.ui.PtyConnectionState
import dev.opencode.android.ui.PtyController
import dev.opencode.android.ui.PtyKey
import dev.opencode.android.ui.PtyLifeState
import dev.opencode.android.ui.PtyScreenPane
import dev.opencode.android.ui.ptyActionErrorMessage
import dev.opencode.android.ui.terminalStatusLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PtyController] の状態遷移(Q9)。
 *
 * ## なぜ状態機械にテストを置くのか
 *
 * Q1 の失敗(「純関数に切り出せなかった部分に検出器が1本も無かった」)以来の規則。
 * Q9 で切り出せないのは**このフェーズの中核そのもの**である:
 *
 *  - **接続のたびにチケットを取り直す**(単回使用。使い回すと再接続だけが黙って失敗する)
 *  - **保存した cursor から読み直す**(落とすと再接続のたびに先頭からやり直す)
 *  - **切断と終了を区別する**(close code からは決まらない。一覧に居るかで決める)
 *  - **終了コードは SSE からしか来ない**(REST は終了した瞬間に 404)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q9ControllerTest {

    private fun controller(
        scope: TestScope,
        gateway: FakePtyGateway = FakePtyGateway(),
        directory: String? = null,
    ) = PtyController(
        gateway = gateway,
        scope = scope,
        describeError = { error ->
            when (error) {
                is ApiError.Http -> "HTTP ${error.code}"
                is ApiError.Network -> "net:${error.message}"
                ApiError.NotConfigured -> "未設定"
            }
        },
        maxLines = 100,
        directory = directory,
    )

    private val running = Q9Decode.ptys(Q9Fixtures.LIST_PTY_JSON)
    private val runningId = running.single().id

    // ---------------------------------------------------------------------
    // 一覧とシェル
    // ---------------------------------------------------------------------

    @Test
    fun `ensureLoaded は一覧とシェルを1度ずつ引く`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, gateway.listCalls)
        assertEquals(running, c.state.value.list.items)
        assertEquals(4, c.state.value.shells.items.size)
        // 2度目は引かない(回転のたびに撃たない)。
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, gateway.listCalls)
    }

    /** **失敗は「読んだ」ではない。** 失敗のままだと `ensureLoaded` が撃ち直せる。 */
    @Test
    fun `一覧の取得に失敗したら loaded にならない`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Err(ApiError.Http(500))
        val c = controller(this, gateway)
        c.ensureLoaded()
        advanceUntilIdle()
        assertFalse(c.state.value.list.loaded)
        assertEquals("HTTP 500", c.state.value.list.error)
        assertFalse(c.state.value.list.errorIsAuth)
    }

    @Test
    fun `401 は認証失敗として立つ`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Err(ApiError.Http(401))
        val c = controller(this, gateway)
        c.refreshList()
        advanceUntilIdle()
        assertTrue(c.state.value.list.errorIsAuth)
    }

    /** 通信が1回失敗しただけで、走っているターミナルへ戻る導線を消さない。 */
    @Test
    fun `取得に失敗しても持っている一覧は残る`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.refreshList()
        advanceUntilIdle()
        gateway.listResult = ApiResult.Err(ApiError.Network("down"))
        c.refreshList()
        advanceUntilIdle()
        assertEquals(running, c.state.value.list.items)
        assertEquals("net:down", c.state.value.list.error)
    }

    // ---------------------------------------------------------------------
    // 接続
    // ---------------------------------------------------------------------

    @Test
    fun `開くと接続してソケットが1本になる`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        assertEquals(PtyConnectionState.CONNECTING, c.state.value.terminal.connection)
        advanceUntilIdle()
        assertEquals(1, gateway.sockets.size)
        // **初回は cursor を渡さない**(位置を知らない = null)。
        assertNull(gateway.latest().cursor)
        gateway.latest().open()
        advanceUntilIdle()
        assertEquals(PtyConnectionState.CONNECTED, c.state.value.terminal.connection)
        assertTrue(c.state.value.terminal.inputEnabled)
    }

    /** `directory` を落とす変異を捕まえる(URL のテストからは見えない主張)。 */
    @Test
    fun `connect に directory がそのまま渡る`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway, directory = "E:/repo")
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        assertEquals(listOf<String?>("E:/repo"), gateway.connectDirectories)
    }

    @Test
    fun `チケットが取れなければ接続失敗として出る`() = runTest {
        val gateway = FakePtyGateway()
        gateway.connectResult = ApiError.Http(403)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        assertEquals(PtyConnectionState.CLOSED, c.state.value.terminal.connection)
        assertEquals("HTTP 403", c.state.value.terminal.error)
        // **接続の失敗はシェルの終了ではない。** 生死を動かさない。
        assertEquals(PtyLifeState.Running, c.state.value.terminal.life)
    }

    // ---------------------------------------------------------------------
    // 出力とメタ
    // ---------------------------------------------------------------------

    @Test
    fun `出力は画面に積まれ メタは描かれない`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val socket = gateway.latest()
        socket.open()
        socket.emitMeta(0L)
        socket.emitText(Q9Fixtures.BANNER_FRAME_1)
        advanceUntilIdle()
        assertEquals("Microsoft Windows [Version 10.0.26200.9168]", c.state.value.terminal.buffer.text)
        // **`{"cursor":0}` が画面に出ていないこと**(陰性側)。
        assertFalse(c.state.value.terminal.buffer.text.contains("cursor"))
        assertEquals(113L, c.state.value.terminal.cursor)
    }

    /**
     * **再接続は保存した位置から読む。**
     *
     * ここを落とすと「回転のたびに画面の先頭からやり直す」になり、
     * クラッシュもエラーも出ない。
     */
    @Test
    fun `再接続は保存した cursor を渡す`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val first = gateway.latest()
        first.open()
        first.emitMeta(0L)
        first.emitText(Q9Fixtures.BANNER_FRAME_1)
        advanceUntilIdle()
        first.close(1006, "dropped")
        advanceUntilIdle()
        assertEquals(2, gateway.sockets.size)
        assertEquals(113L, gateway.latest().cursor)
    }

    // ---------------------------------------------------------------------
    // 切断と終了の区別(このフェーズの中核)
    // ---------------------------------------------------------------------

    /** **一覧にまだ居る = 切断。** 再接続する。 */
    @Test
    fun `一覧に残っていれば切断として再接続する`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()
        gateway.latest().close(1000, "")
        runCurrent()
        assertEquals(PtyConnectionState.RECONNECTING, c.state.value.terminal.connection)
        assertEquals(PtyLifeState.Running, c.state.value.terminal.life)
        advanceUntilIdle()
        assertEquals(2, gateway.sockets.size)
    }

    /**
     * **一覧から消えている = 終わった。ただし終了コードは分からない。**
     *
     * close code は同じ `1000` である。ここを `Exited(0)` に落とす実装は
     * 「取れなかった値」を「正常終了」と偽る。
     */
    @Test
    fun `一覧から消えていれば終了だが終了コードは不明`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(emptyList())
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()
        gateway.latest().close(1000, "")
        advanceUntilIdle()
        assertEquals(PtyLifeState.ExitedCodeUnknown, c.state.value.terminal.life)
        assertEquals(PtyConnectionState.CLOSED, c.state.value.terminal.connection)
        assertNull(c.state.value.terminal.exitCode)
        // **終わったなら繋ぎ直さない。**
        assertEquals(1, gateway.sockets.size)
        assertFalse(c.state.value.terminal.inputEnabled)
    }

    /**
     * **一覧が引けなかったら「終わった」と言わない。**
     * 分かっているのは接続が切れたことだけである。
     */
    @Test
    fun `一覧が引けなければ生死を動かさない`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Err(ApiError.Network("down"))
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()
        gateway.latest().close(1000, "")
        runCurrent()
        assertEquals(PtyLifeState.Running, c.state.value.terminal.life)
    }

    /** ハンドシェイクに至らなかった失敗は `code = null` で伝わる。 */
    @Test
    fun `接続できなかった場合の close code は null`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().close(null, "Connection refused")
        runCurrent()
        assertNull(c.state.value.terminal.closeCode)
        assertEquals("Connection refused", c.state.value.terminal.closeReason)
    }

    /** 再接続は無限に繰り返さない。**諦めたことが状態に出る。** */
    @Test
    fun `再接続は上限で止まる`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        repeat(PTY_MAX_RECONNECT_ATTEMPTS + 2) {
            gateway.latest().close(1006, "")
            advanceUntilIdle()
        }
        assertEquals(PtyConnectionState.CLOSED, c.state.value.terminal.connection)
        assertEquals(PTY_MAX_RECONNECT_ATTEMPTS + 1, gateway.sockets.size)
    }

    // ---------------------------------------------------------------------
    // SSE(終了コードの唯一の出所)
    // ---------------------------------------------------------------------

    @Test
    fun `pty_exited を受けると終了コードが立つ`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(Q9Fixtures.EXITED_PTY_ID, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()
        c.onEvent(parseSseEnvelope(Q9Fixtures.EVENT_EXITED)!!)
        advanceUntilIdle()
        assertEquals(PtyLifeState.Exited(7), c.state.value.terminal.life)
        assertEquals(7, c.state.value.terminal.exitCode)
        assertEquals(PtyConnectionState.CLOSED, c.state.value.terminal.connection)
        assertTrue(gateway.latest().closed)
    }

    /**
     * **実機で踏んだ競合の再現**(2026-08-30、スタブ 4098 に `exit` を送った)。
     *
     * 症状: `pty.exited{exitCode:7}` は SSE で届いていたのに、画面には
     * `pty-exited-code-unknown`(「終了コードは受け取れていません」)が出た。
     *
     * 原因: ソケットが閉じたときの処理が `GET /pty` を待っている**間に**
     * SSE が着地し、待つ前に読んだ生死(Running)で `classifyClosedSocket` を回して
     * **`Exited(7)` を `ExitedCodeUnknown` で上書きしていた**。
     *
     * `classifyClosedSocket` 自体は「終了コードを持っていれば上書きしない」を守っており、
     * そのユニットテストも通っていた —— **入力が古かった**のである。
     * 純関数のテストは「関数が正しい」しか主張できない(Q5 の R1 と同じ形)。
     */
    @Test
    fun `一覧を待っている間に届いた exited を上書きしない`() = runTest {
        val gateway = FakePtyGateway()
        // 終了しているので一覧からは消えている。
        gateway.listResult = ApiResult.Ok(emptyList())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val c = controller(this, gateway)
        c.open(Q9Fixtures.EXITED_PTY_ID, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()

        // ここから `GET /pty` を待たせる。
        gateway.listGate = gate
        gateway.latest().close(1000, "")
        runCurrent()

        // **待っている間に SSE が着地する。**
        c.onEvent(parseSseEnvelope(Q9Fixtures.EVENT_EXITED)!!)
        runCurrent()
        assertEquals(PtyLifeState.Exited(7), c.state.value.terminal.life)

        // 一覧が返ってくる。**ここで上書きしてはいけない。**
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PtyLifeState.Exited(7), c.state.value.terminal.life)
        assertEquals(7, c.state.value.terminal.exitCode)
        assertEquals(PtyConnectionState.CLOSED, c.state.value.terminal.connection)
    }

    /** `exitCode` の無い `exited` を **0 にしない**。 */
    @Test
    fun `exitCode の無い exited は コード不明になる`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open("pty_x", "t", "cmd.exe")
        advanceUntilIdle()
        c.onEvent(parseSseEnvelope("""{"id":"e","type":"pty.exited","properties":{"id":"pty_x"}}""")!!)
        advanceUntilIdle()
        assertEquals(PtyLifeState.ExitedCodeUnknown, c.state.value.terminal.life)
        assertNull(c.state.value.terminal.exitCode)
    }

    /** `deleted` は**終了コードを持たない別の終わり方**である。 */
    @Test
    fun `pty_deleted は削除として立つ`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        c.onEvent(parseSseEnvelope(Q9Fixtures.EVENT_DELETED)!!)
        advanceUntilIdle()
        assertEquals(PtyLifeState.Deleted, c.state.value.terminal.life)
        assertNull(c.state.value.terminal.exitCode)
    }

    /** `exited` の後に `deleted` が来ても**終了コードを失わない**。 */
    @Test
    fun `exited の後の deleted で終了コードが消えない`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(Q9Fixtures.EXITED_PTY_ID, "t", "cmd.exe")
        advanceUntilIdle()
        c.onEvent(parseSseEnvelope(Q9Fixtures.EVENT_EXITED)!!)
        c.onEvent(
            parseSseEnvelope(
                """{"id":"e","type":"pty.deleted","properties":{"id":"${Q9Fixtures.EXITED_PTY_ID}"}}""",
            )!!,
        )
        advanceUntilIdle()
        assertEquals(PtyLifeState.Exited(7), c.state.value.terminal.life)
    }

    @Test
    fun `pty_created は一覧に足され exited は一覧から消す`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.onEvent(parseSseEnvelope(Q9Fixtures.EVENT_CREATED)!!)
        assertEquals(1, c.state.value.list.items.size)
        c.onEvent(parseSseEnvelope(Q9Fixtures.EVENT_DELETED)!!)
        assertEquals(0, c.state.value.list.items.size)
    }

    /** 別の PTY のイベントで開いている端末を触らない。 */
    @Test
    fun `他の PTY の exited は開いている端末を終わらせない`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open("pty_mine", "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()
        c.onEvent(parseSseEnvelope(Q9Fixtures.EVENT_EXITED)!!)
        advanceUntilIdle()
        assertEquals(PtyLifeState.Running, c.state.value.terminal.life)
        assertEquals(PtyConnectionState.CONNECTED, c.state.value.terminal.connection)
    }

    /** PTY 以外のイベントは黙って無視する(投げない)。 */
    @Test
    fun `PTY 以外のイベントは何も変えない`() = runTest {
        val c = controller(this)
        val before = c.state.value
        c.onEvent(SseEvent.ServerConnected)
        assertEquals(before, c.state.value)
    }

    // ---------------------------------------------------------------------
    // 入力
    // ---------------------------------------------------------------------

    @Test
    fun `sendLine は改行を付けて送る`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()
        c.sendLine("echo hello")
        assertEquals(listOf("echo hello\r"), gateway.latest().sent)
    }

    /** **Ctrl+C は U+0003 の1文字**(実測)。ここが暴走を止める唯一の手段である。 */
    @Test
    fun `sendKey は対応表どおりのバイトを送る`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()
        c.sendKey(PtyKey.CTRL_C)
        c.sendKey(PtyKey.UP)
        assertEquals(listOf("\u0003", "\u001b[A"), gateway.latest().sent)
    }

    /** **送れなかったことを黙らない。** 黙ると「打っても何も起きない端末」になる。 */
    @Test
    fun `接続していないときの入力は失敗として出る`() = runTest {
        val c = controller(this)
        c.sendLine("ls")
        assertNotNull(c.state.value.actionError)
        c.clearActionError()
        assertNull(c.state.value.actionError)
    }

    // ---------------------------------------------------------------------
    // リサイズ
    // ---------------------------------------------------------------------

    @Test
    fun `リサイズは同じ寸法なら撃たない`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        c.resize(24, 80)
        advanceUntilIdle()
        c.resize(24, 80)
        advanceUntilIdle()
        assertEquals(listOf(Triple(runningId, 24, 80)), gateway.resizes)
        c.resize(30, 80)
        advanceUntilIdle()
        assertEquals(2, gateway.resizes.size)
    }

    /**
     * **終わった PTY はリサイズしない**(実機で踏んだ形。2026-08-30)。
     *
     * `exit` の直後に帯と注記の高さが変わって再コンポジションが走り、
     * 寸法が変わったので `PUT /pty/{id}` を撃った —— サーバーからは既に消えているので
     * **404 になり、「サーバーエラー(HTTP 404)」が終了直後の画面に出た**。
     * ユーザーは何もしておらず、失敗でもない。
     */
    @Test
    fun `終了後はリサイズを撃たない`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(Q9Fixtures.EXITED_PTY_ID, "t", "cmd.exe")
        advanceUntilIdle()
        c.resize(24, 80)
        advanceUntilIdle()
        assertEquals(1, gateway.resizes.size)

        c.onEvent(parseSseEnvelope(Q9Fixtures.EVENT_EXITED)!!)
        advanceUntilIdle()
        c.resize(30, 90)
        advanceUntilIdle()

        assertEquals("終了後の PUT /pty は撃たない", 1, gateway.resizes.size)
        assertNull("ユーザーが何もしていないのにエラーを出さない", c.state.value.actionError)
    }

    /**
     * **アクションエラーは端末を跨がない**(Q9 の E2E ゲートが未修正のまま記録した症状)。
     *
     * 再現手順(実測): サーバーを止める → リサイズが `PUT /pty/{id}` で失敗して帯が立つ
     * → サーバーを復旧 → 一覧へ戻って**新しい PTY を起動する** →
     * **健全な端末に古いサーバーエラーが出たまま残る**。5端末・サーバー再起動3回を跨いだ。
     *
     * 原因は [PtyController.closeTerminal] / [PtyController.open] が `terminal` だけを
     * 初期化し、`actionError` が [PtyUi] 直下の別プロパティとして居残ったこと。
     */
    @Test
    fun `別の端末を開くと前の端末のアクションエラーは消える`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.resizeResult = ApiResult.Err(ApiError.Network("boom"))
        c.resize(24, 80)
        advanceUntilIdle()
        assertNotNull(c.state.value.actionError)

        gateway.resizeResult = ApiResult.Ok(Q9Decode.pty(Q9Fixtures.CREATE_PTY_JSON))
        c.open("pty_new", "t2", "cmd.exe")
        advanceUntilIdle()
        assertNull("新しい端末に前のサーバーエラーを持ち込まない", c.state.value.actionError)
    }

    /** 一覧へ戻る経路でも同じ。**`terminal` だけを初期化しない。** */
    @Test
    fun `端末を閉じるとアクションエラーも消える`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.resizeResult = ApiResult.Err(ApiError.Network("boom"))
        c.resize(24, 80)
        advanceUntilIdle()
        assertNotNull(c.state.value.actionError)
        c.closeTerminal()
        assertNull(c.state.value.actionError)
    }

    /**
     * **削除の失敗も対象の PTY を名指しする。** 名指ししないと、一覧から消せなかった
     * PTY のエラーが、次に開いた無関係な端末に出る。
     */
    @Test
    fun `削除の失敗は対象の PTY を名指しする`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        gateway.deleteResult = ApiResult.Err(ApiError.Http(500))
        c.deletePty(runningId)
        advanceUntilIdle()
        assertEquals(runningId, c.state.value.actionError?.ptyId)
        assertEquals("HTTP 500", c.state.value.actionError?.message)
    }

    /**
     * **失敗はどの窓から起こしたかも持つ**(レビュー minor-1)。
     *
     * 削除は一覧の行からも端末の窓の上部からも起こせる。`origin` は**発行時**に
     * 決まるので、失敗が返るまでに画面が切り替わっていても他所の窓へ出ない。
     */
    @Test
    fun `削除の失敗は起こした窓を名指しする`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        gateway.deleteResult = ApiResult.Err(ApiError.Http(500))

        // 一覧の行から。
        c.deletePty(runningId)
        advanceUntilIdle()
        assertEquals(PtyScreenPane.LIST, c.state.value.actionError?.origin)

        // 端末の窓の上部から。
        c.clearActionError()
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        c.deletePty(runningId)
        advanceUntilIdle()
        assertEquals(PtyScreenPane.TERMINAL, c.state.value.actionError?.origin)
    }

    /**
     * **一覧へ戻った後に返ってきたリサイズの失敗が、一覧の帯に出ない。**
     *
     * `closeTerminal` のクリアは飛行中の `PUT` には間に合わない。
     * 状態に残ること自体は正しい(記録である)が、**出す窓が無い**ことを
     * [ptyActionErrorMessage] が決める。
     */
    @Test
    fun `一覧へ戻った後のリサイズ失敗は一覧に出さない`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.resizeResult = ApiResult.Err(ApiError.Http(404))
        c.resize(24, 80)
        // まだ失敗は返っていない。ここで一覧へ戻る。
        c.closeTerminal()
        advanceUntilIdle()

        val error = c.state.value.actionError
        assertNotNull("失敗は状態には残る(記録である)", error)
        assertEquals(PtyScreenPane.TERMINAL, error?.origin)
        assertNull(
            "もう開いていない端末のサーバーエラーが一覧の帯に出ている",
            ptyActionErrorMessage(error, PtyScreenPane.LIST, c.state.value.terminal.ptyId),
        )
    }

    /** 送信の失敗も、いま開いている端末を名指しする。 */
    @Test
    fun `入力の失敗は開いている端末を名指しする`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().accepts = false
        c.sendLine("ls")
        assertEquals(runningId, c.state.value.actionError?.ptyId)
        // **窓も名指しする。** 入力は端末の窓からしか起こせないので、
        // 一覧の帯には出ない(レビュー minor-1)。
        assertEquals(PtyScreenPane.TERMINAL, c.state.value.actionError?.origin)
        assertNull(
            ptyActionErrorMessage(c.state.value.actionError, PtyScreenPane.LIST, null),
        )
    }

    /** 0 は送らない(サーバーは弾かない)。 */
    @Test
    fun `0 の寸法は送らない`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        c.resize(0, 0)
        advanceUntilIdle()
        assertTrue(gateway.resizes.isEmpty())
    }

    // ---------------------------------------------------------------------
    // 削除 / 作成 / 接続先変更
    // ---------------------------------------------------------------------

    @Test
    fun `削除は一覧から消して端末を削除状態にする`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.refreshList()
        advanceUntilIdle()
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        c.deletePty(runningId)
        advanceUntilIdle()
        assertEquals(listOf(runningId), gateway.deleted)
        assertTrue(c.state.value.list.items.isEmpty())
        assertEquals(PtyLifeState.Deleted, c.state.value.terminal.life)
    }

    @Test
    fun `作成に成功したら一覧を引き直してから開く`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.createAndOpen("cmd.exe", "cmd")
        advanceUntilIdle()
        assertEquals(listOf("cmd.exe" to "cmd"), gateway.createdCommands)
        assertEquals("pty_04f246e3f001Pdm7dahQta7B5e", c.state.value.terminal.ptyId)
        assertEquals(1, gateway.sockets.size)
        assertFalse(c.state.value.creating)
    }

    @Test
    fun `作成に失敗したら理由が残る`() = runTest {
        val gateway = FakePtyGateway()
        gateway.createResult = ApiResult.Err(ApiError.Http(500))
        val c = controller(this, gateway)
        c.createAndOpen("cmd.exe", "cmd")
        advanceUntilIdle()
        assertEquals("HTTP 500", c.state.value.createError)
        assertTrue(gateway.sockets.isEmpty())
        c.clearCreateError()
        assertNull(c.state.value.createError)
    }

    /**
     * **接続先が変わったらソケットを閉じる。**
     * 閉じないと、サーバーA のシェルへ入力を送り続ける経路が生き残る。
     */
    @Test
    fun `接続先が変わったら捨ててソケットを閉じる`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.onConnectionChanged("http://a")
        c.refreshList()
        advanceUntilIdle()
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val socket = gateway.latest()
        c.onConnectionChanged("http://b")
        assertTrue(socket.closed)
        assertTrue(c.state.value.list.items.isEmpty())
        assertNull(c.state.value.terminal.ptyId)
    }

    /** 同じ接続先の再保存では捨てない。初回は捨てるものが無い。 */
    @Test
    fun `同じ接続先なら捨てない`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.onConnectionChanged("http://a")
        c.refreshList()
        advanceUntilIdle()
        c.onConnectionChanged("http://a")
        assertEquals(running, c.state.value.list.items)
    }

    /** 再接続では**まだ読んでいなければ何もしない**(勝手に開かない)。 */
    @Test
    fun `onReconnected は読んでいなければ何もしない`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(0, gateway.listCalls)
        c.refreshList()
        advanceUntilIdle()
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(2, gateway.listCalls)
    }

    @Test
    fun `closeTerminal はソケットを閉じて画面を捨てる`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val socket = gateway.latest()
        c.closeTerminal()
        assertTrue(socket.closed)
        assertNull(c.state.value.terminal.ptyId)
    }

    // ---------------------------------------------------------------------
    // 同一PTYへの張り直し(レビュー major-1)
    //
    // `close()` は同期しない —— OkHttp は後から `onClosing` / `onClosed` を投げる。
    // その間に**同じPTYへ**繋ぎ直すと、`ptyId` の比較だけのガードは何も捨てない。
    // ---------------------------------------------------------------------

    /**
     * **古いソケットの `onClosed` が新しい接続を壊さない。**
     *
     * ガードが `ptyId` の比較だけだと、ここで:
     *  - `socket = null` になり、**開いている本物の WebSocket 参照を落とす**(入力が送れない)
     *  - `connection` が `RECONNECTING` へ書き戻る
     *  - `connect()` が撃たれ、`connectJob.cancel()` が**進行中の接続を殺す**
     */
    @Test
    fun `同じ PTY へ張り直すと古いソケットの onClosed は捨てられる`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val old = gateway.latest()
        old.open()
        advanceUntilIdle()

        // 手で再接続する(帯の「再接続」)。**PTY のIDは同じ**である。
        c.reconnect()
        advanceUntilIdle()
        val fresh = gateway.latest()
        assertTrue("別のソケットが開かれていること", fresh !== old)
        fresh.open()
        advanceUntilIdle()
        val socketsAfterReconnect = gateway.sockets.size

        // OkHttp が遅れて古いソケットの close を届ける。
        old.close(1000, "old socket")
        advanceUntilIdle()

        assertEquals(PtyConnectionState.CONNECTED, c.state.value.terminal.connection)
        assertEquals("古い close で繋ぎ直さない", socketsAfterReconnect, gateway.sockets.size)
        // **新しいソケットの参照が生きている**(落とすと入力が送れない)。
        c.sendLine("echo hi")
        assertEquals(listOf("echo hi\r"), fresh.sent)
        assertNull(c.state.value.actionError)
    }

    /** 古いソケットの出力を**画面にも位置にも足さない**(二重描画と cursor の二重進行)。 */
    @Test
    fun `同じ PTY へ張り直すと古いソケットの出力は捨てられる`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val old = gateway.latest()
        old.open()
        old.emitMeta(0L)
        old.emitText("LIVE")
        advanceUntilIdle()
        assertEquals(4L, c.state.value.terminal.cursor)

        c.reconnect()
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()

        old.emitText("STALE")
        advanceUntilIdle()

        assertFalse(
            "古いソケットの出力が画面に出ている",
            c.state.value.terminal.buffer.text.contains("STALE"),
        )
        assertEquals("古いソケットの出力で位置が進んでいる", 4L, c.state.value.terminal.cursor)
    }

    /** 一覧から**同じ行をもう一度開く**経路も同じ形である([open] は `disconnect` を挟む)。 */
    @Test
    fun `同じ行をもう一度開いても古いソケットの onOpen は効かない`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val old = gateway.latest()

        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        assertEquals(2, gateway.sockets.size)

        // 古いソケットが遅れて「開いた」と言ってくる。
        old.open()
        advanceUntilIdle()
        assertEquals(PtyConnectionState.CONNECTING, c.state.value.terminal.connection)

        old.close(null, "late failure")
        advanceUntilIdle()
        assertEquals("古い失敗が新しい接続を切断にしない", PtyConnectionState.CONNECTING, c.state.value.terminal.connection)
        assertNull(c.state.value.terminal.closeReason.ifEmpty { null })
    }

    /**
     * **閉じた端末のソケットが後から `onClosed` を投げても繋ぎ直さない。**
     *
     * `closeTerminal()` は `disconnect()` を通るだけで、`connect()` を呼ばない ——
     * つまり**世代を進めるのは `disconnect()` の責務**である。進めないと、
     * ユーザーが閉じた端末へ**勝手に繋ぎ直す**(サーバー上のシェルはまだ走っているので
     * `GET /pty` には居る = 「切断」と判定される)。
     */
    @Test
    fun `閉じた端末のソケットが後から閉じても繋ぎ直さない`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val socket = gateway.latest()
        socket.open()
        advanceUntilIdle()

        c.closeTerminal()
        socket.close(1000, "closed by us")
        advanceUntilIdle()

        assertEquals("閉じた端末へ繋ぎ直している", 1, gateway.sockets.size)
        assertNull(c.state.value.terminal.ptyId)
    }

    /**
     * **取り消された接続が開いてしまったソケットを握らない。**
     *
     * `PtyRepository.connect` は `openPtyWebSocket` を呼んだ時点でソケットを開く。
     * その後にコルーチンが取り消されても**ソケットは開いたまま**なので、
     * 「取り消したのだから何も起きなかった」とは言えない。
     * 握ってしまうと、後から開いた本物の参照を上書きして取り落とす
     * (入力が古いソケットへ流れる / 誰も閉じない)。
     */
    @Test
    fun `古い世代で開いてしまったソケットは握らずに閉じる`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.connectGate = gate
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val stale = gateway.latest()

        // まだ開き終わっていないうちに張り直す。
        gateway.connectGate = null
        c.reconnect()
        advanceUntilIdle()
        val fresh = gateway.latest()
        assertTrue(fresh !== stale)
        fresh.open()
        advanceUntilIdle()

        // 遅れて古い接続が「開けた」と返ってくる。
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("誰も閉じないソケットが残っている", stale.closed)
        c.sendLine("x")
        assertEquals("入力が古いソケットへ流れている", listOf("x\r"), fresh.sent)
        assertTrue(stale.sent.isEmpty())
    }

    // ---------------------------------------------------------------------
    // cursor を知らないままの再接続(レビュー minor-3)
    // ---------------------------------------------------------------------

    /**
     * **メタが来ないサーバーでは `cursor` が null のままになる**
     * (スタブの `STUB_PTY_NO_META`)。その状態で繋ぎ直すと、サーバーは
     * **先頭から全部送り直す** —— 既存のバッファに足すと同じ出力が二重になる。
     */
    @Test
    fun `cursor を知らないまま再接続したら画面を捨ててから受ける`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val first = gateway.latest()
        first.open()
        // **メタは来ない。** 出力だけが流れる。
        first.emitText("banner line\n")
        advanceUntilIdle()
        assertNull("メタが無ければ位置は分からないまま", c.state.value.terminal.cursor)
        assertEquals(1, c.state.value.terminal.buffer.text.split("banner line").size - 1)

        first.close(1006, "dropped")
        advanceUntilIdle()
        val second = gateway.latest()
        assertNull("位置を知らないので cursor は渡さない", second.cursor)
        second.open()
        // サーバーは先頭から全部送り直す。
        second.emitText("banner line\n")
        advanceUntilIdle()

        assertEquals(
            "全再送が二重に積まれている",
            1,
            c.state.value.terminal.buffer.text.split("banner line").size - 1,
        )
    }

    /** 位置が分かっているときは**捨てない**(再送されるのは続きだけである)。 */
    @Test
    fun `cursor が分かっていれば再接続で画面を捨てない`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        val first = gateway.latest()
        first.open()
        first.emitMeta(0L)
        first.emitText("kept\n")
        advanceUntilIdle()
        first.close(1006, "dropped")
        advanceUntilIdle()
        gateway.latest().open()
        advanceUntilIdle()
        assertTrue(c.state.value.terminal.buffer.text.contains("kept"))
    }

    // ---------------------------------------------------------------------
    // 再接続を諦めたこと(レビュー minor-1)
    // ---------------------------------------------------------------------

    /**
     * **「上限まで試して諦めた」と「まだ1回も試していない」を帯で区別する。**
     * どちらも `CLOSED` なので、状態に印が無ければ同じ文言になる。
     */
    @Test
    fun `上限まで試して諦めたことが状態に出る`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(running)
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        assertFalse(c.state.value.terminal.gaveUpReconnecting)
        repeat(PTY_MAX_RECONNECT_ATTEMPTS + 1) {
            gateway.latest().close(1006, "")
            advanceUntilIdle()
        }
        assertEquals(PtyConnectionState.CLOSED, c.state.value.terminal.connection)
        assertTrue(c.state.value.terminal.gaveUpReconnecting)
        assertEquals(
            "pty-reconnect-gave-up:$PTY_MAX_RECONNECT_ATTEMPTS",
            terminalStatusLine(c.state.value.terminal).key,
        )
        // 手で押したらやり直す。
        c.reconnect()
        advanceUntilIdle()
        assertFalse(c.state.value.terminal.gaveUpReconnecting)
    }

    /** 一度も再接続していない切断は「諦めた」ではない。 */
    @Test
    fun `終了による切断は諦めた扱いにしない`() = runTest {
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(emptyList())
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.latest().close(1000, "")
        advanceUntilIdle()
        assertFalse(c.state.value.terminal.gaveUpReconnecting)
    }

    // ---------------------------------------------------------------------
    // リサイズの楽観更新(レビュー minor-8)
    // ---------------------------------------------------------------------

    /**
     * **`PUT` が失敗したら楽観更新を取り消す。**
     *
     * 取り消さないと、同値判定が同じ寸法での再送を**永久に抑止する** ——
     * 通信が一度失敗しただけで端末の桁がサーバー既定のまま二度と直らない。
     */
    @Test
    fun `リサイズが失敗したら寸法を戻して次の同じ寸法を撃てる`() = runTest {
        val gateway = FakePtyGateway()
        val c = controller(this, gateway)
        c.open(runningId, "t", "cmd.exe")
        advanceUntilIdle()
        gateway.resizeResult = ApiResult.Err(ApiError.Network("boom"))
        c.resize(24, 80)
        advanceUntilIdle()
        assertEquals(1, gateway.resizes.size)
        assertEquals("net:boom", c.state.value.actionError?.message)
        assertEquals("失敗した PTY を名指しする", runningId, c.state.value.actionError?.ptyId)
        assertEquals("失敗したのに伝え済みの寸法として残っている", 0, c.state.value.terminal.rows)
        assertEquals(0, c.state.value.terminal.cols)

        gateway.resizeResult = ApiResult.Ok(Q9Decode.pty(Q9Fixtures.CREATE_PTY_JSON))
        c.resize(24, 80)
        advanceUntilIdle()
        assertEquals("同じ寸法をもう一度撃てない", 2, gateway.resizes.size)
        assertEquals(24, c.state.value.terminal.rows)
        assertEquals(80, c.state.value.terminal.cols)
    }
}
