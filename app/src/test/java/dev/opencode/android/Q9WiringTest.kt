package dev.opencode.android

import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.ChatController
import dev.opencode.android.ui.DiffController
import dev.opencode.android.ui.FileBrowserController
import dev.opencode.android.ui.ModelCatalogController
import dev.opencode.android.ui.PtyController
import dev.opencode.android.ui.ServerInfoController
import dev.opencode.android.ui.SessionListController
import dev.opencode.android.ui.deliverConnectionsTo
import dev.opencode.android.ui.deliverTo
import dev.opencode.android.ui.wireConnectionChangesTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q9 の**配線**(RUN_PLAN 設計規則1: 宛先はオブジェクトで名指しし、ラムダで受け取らない)。
 *
 * ## このファイルが在る理由 —— **変異校正が3本の素通しを出した**
 *
 * 1周目は Q9 の純関数・状態機械・HTTP・Compose に検出器を置き、
 * 変異 48 本のうち **45 本が DETECTED** になった。残る3本が全緑で通り抜けた:
 *
 * | 変異 | 症状 |
 * |---|---|
 * | M36 `deliverTo` から PTY の宛先を落とす | **`pty.exited` が届かず、終了コードが二度と手に入らない** |
 * | M37 `deliverConnectionsTo` から PTY の宛先を落とす | 再接続しても一覧が古いまま(終わった端末が残る) |
 * | M38 `wireConnectionChangesTo` から PTY の宛先を落とす | **サーバーA のシェルへ入力を送り続ける経路が残る** |
 *
 * どれも**画面はどこも壊れて見えない**。既存のテストは新しい宛先を**引数として渡していた**が、
 * 「配線がその宛先を実際に呼ぶ」は別の主張である —— Q5 が7本の素通しで学んだのと同じ形が、
 * 宛先を足すたびに再発する。**足した宛先には、その場で assert を足すこと。**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q9WiringTest {

    private fun ptyController(scope: TestScope, gateway: FakePtyGateway) =
        PtyController(gateway, scope, describeError = { "e" })

    // ---------------------------------------------------------------------
    // M36: SSE の配布(終了コードの唯一の経路)
    // ---------------------------------------------------------------------

    /**
     * **`pty.exited` が PTY の状態機械へ届くこと。**
     *
     * ここを落とすと、シェルが終わっても画面は「接続が切れました」と言い続ける ——
     * REST は終了した瞬間に 404 になるので、**終了コードは二度と手に入らない**。
     */
    @Test
    fun `SSE の pty イベントが PTY へ配られる`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<dev.opencode.android.data.SseEvent>()
        val gateway = FakePtyGateway()
        val pty = ptyController(this, gateway)
        pty.open(Q9Fixtures.EXITED_PTY_ID, "t", "cmd.exe")
        val job = launch {
            flow.deliverTo(
                onFailure = { throw AssertionError("配布が失敗した: $it") },
                toSessionList = { },
                toChat = { },
                toPty = pty,
            )
        }

        flow.emit(parseSseEnvelope(Q9Fixtures.EVENT_EXITED)!!)

        assertEquals(
            "pty.exited が届かなければ終了コードは二度と取れない",
            7,
            pty.state.value.terminal.exitCode,
        )
        job.cancel()
    }

    /** 一覧側の宛先も同じ列で生きている(片方だけを配る変異を捕まえる)。 */
    @Test
    fun `SSE は3つの宛先すべてへ配られる`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<dev.opencode.android.data.SseEvent>()
        val toList = mutableListOf<String>()
        val toChat = mutableListOf<String>()
        val gateway = FakePtyGateway()
        val pty = ptyController(this, gateway)
        val job = launch {
            flow.deliverTo(
                onFailure = { throw AssertionError("$it") },
                toSessionList = { toList += it::class.simpleName.orEmpty() },
                toChat = { toChat += it::class.simpleName.orEmpty() },
                toPty = pty,
            )
        }

        flow.emit(parseSseEnvelope(Q9Fixtures.EVENT_CREATED)!!)

        assertEquals(1, toList.size)
        assertEquals(1, toChat.size)
        assertEquals(1, pty.state.value.list.items.size)
        job.cancel()
    }

    /** 片方の宛先が投げても PTY への配布と後続イベントは死なない(`collectGuarded`)。 */
    @Test
    fun `先に投げる宛先があっても PTY へは届く`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<dev.opencode.android.data.SseEvent>()
        val failures = mutableListOf<Throwable>()
        val gateway = FakePtyGateway()
        val pty = ptyController(this, gateway)
        val job = launch {
            flow.deliverTo(
                onFailure = { failures += it },
                toSessionList = { error("一覧側が壊れた") },
                toChat = { },
                toPty = pty,
            )
        }

        flow.emit(parseSseEnvelope(Q9Fixtures.EVENT_CREATED)!!)

        assertEquals(1, failures.size)
        // **一覧側が投げた回でも後続は生きている。**
        flow.emit(parseSseEnvelope(Q9Fixtures.EVENT_DELETED)!!)
        assertTrue(job.isActive)
        assertEquals(2, failures.size)
        job.cancel()
    }

    // ---------------------------------------------------------------------
    // M37: 再接続の配布
    // ---------------------------------------------------------------------

    /**
     * **再接続で PTY 一覧を引き直すこと。**
     *
     * 切れている間の `pty.created` / `pty.exited` は購読者ゼロで消えているので、
     * 引き直さないと「終わった端末が一覧に残り続ける」。
     */
    @Test
    fun `再接続で PTY 一覧を引き直す`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>()
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(Q9Decode.ptys(Q9Fixtures.LIST_PTY_JSON))
        val pty = ptyController(this, gateway)
        // **まだ読んでいなければ何もしない**判定は Controller 側にあるので、先に読ませる。
        pty.refreshList()
        val before = gateway.listCalls
        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = { throw AssertionError("$it") },
                sessions = SessionListController(RecordingSessionsGateway(), this, describeError = { "e" }),
                chat = ChatController(NoopChatGateway(), this, describeError = { "e" }),
                diff = DiffController(FakeVcsGateway(), this, describeError = { "e" }),
                files = FileBrowserController(
                    FakeFilesGateway(), FakeVcsGateway(), this, describeError = { "e" },
                ),
                pty = pty,
            )
        }

        flow.emit(1)
        flow.emit(2)

        assertEquals("再接続のたびに GET /pty を引き直す", before + 2, gateway.listCalls)
        job.cancel()
    }

    /** 世代0(まだ一度も繋がっていない)では配らない。 */
    @Test
    fun `世代0では PTY へも配らない`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>()
        val gateway = FakePtyGateway()
        val pty = ptyController(this, gateway)
        pty.refreshList()
        val before = gateway.listCalls
        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = { },
                sessions = SessionListController(RecordingSessionsGateway(), this, describeError = { "e" }),
                chat = ChatController(NoopChatGateway(), this, describeError = { "e" }),
                diff = DiffController(FakeVcsGateway(), this, describeError = { "e" }),
                files = FileBrowserController(
                    FakeFilesGateway(), FakeVcsGateway(), this, describeError = { "e" },
                ),
                pty = pty,
            )
        }
        flow.emit(0)
        assertEquals(before, gateway.listCalls)
        job.cancel()
    }

    // ---------------------------------------------------------------------
    // M38: 接続先の変更(**ここだけソケットを閉じる**)
    // ---------------------------------------------------------------------

    /**
     * **接続先が変わったら PTY 側も捨て、開いている WebSocket を閉じること。**
     *
     * 閉じないと、画面は新しいサーバーの一覧を出しながら、
     * **入力は前のサーバーのシェルへ届き続ける**。Q4(カタログ)・Q7(ブランチ)・
     * Q8(ツリー)と同じ形だが、Q9 は**書き込み経路**なので害が一段重い。
     */
    @Test
    fun `接続先が変わると PTY も捨ててソケットを閉じる`() = runTest {
        val scope = TestScope(testScheduler)
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(Q9Decode.ptys(Q9Fixtures.LIST_PTY_JSON))
        val pty = PtyController(gateway, scope, describeError = { "e" })
        pty.onConnectionChanged("http://a:4097")
        pty.refreshList()
        scope.runCurrent()
        pty.open("pty_x", "t", "cmd.exe")
        scope.runCurrent()
        val socket = gateway.latest()
        assertEquals(1, pty.state.value.list.items.size)

        flowOf<String?>("http://b:4097").wireConnectionChangesTo(
            catalog = ModelCatalogController(Q9NoopCatalog, scope, describeError = { "e" }),
            serverInfo = ServerInfoController(Q9NoopServerInfo, scope, describeError = { "e" }),
            diff = DiffController(FakeVcsGateway(), scope, describeError = { "e" }),
            files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), scope, describeError = { "e" }),
            pty = pty,
            onFailure = { throw AssertionError("配布が失敗した: $it") },
        )

        assertEquals(
            "サーバーAのターミナル一覧をBの画面に出さない",
            emptyList<Any>(),
            pty.state.value.list.items,
        )
        assertNull("開いていた端末も捨てる", pty.state.value.terminal.ptyId)
        assertTrue("前のサーバーのシェルへ入力を送り続けない", socket.closed)
    }

    /** 同じ接続先の再保存では捨てない(捨てると開いている端末が毎回落ちる)。 */
    @Test
    fun `同じ接続先なら PTY を捨てない`() = runTest {
        val scope = TestScope(testScheduler)
        val gateway = FakePtyGateway()
        gateway.listResult = ApiResult.Ok(Q9Decode.ptys(Q9Fixtures.LIST_PTY_JSON))
        val pty = PtyController(gateway, scope, describeError = { "e" })
        pty.onConnectionChanged("http://a:4097")
        pty.refreshList()
        scope.runCurrent()

        flowOf<String?>("http://a:4097").wireConnectionChangesTo(
            catalog = ModelCatalogController(Q9NoopCatalog, scope, describeError = { "e" }),
            serverInfo = ServerInfoController(Q9NoopServerInfo, scope, describeError = { "e" }),
            diff = DiffController(FakeVcsGateway(), scope, describeError = { "e" }),
            files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), scope, describeError = { "e" }),
            pty = pty,
            onFailure = { throw AssertionError("$it") },
        )

        assertEquals(1, pty.state.value.list.items.size)
    }
}

/** 何も返さないカタログ(この配線テストの関心は PTY だけ)。 */
private object Q9NoopCatalog : dev.opencode.android.data.CatalogGateway {
    override val isConfigured: Boolean = true
    override suspend fun listProviders(): ApiResult<dev.opencode.android.data.ProvidersDto> =
        ApiResult.Ok(dev.opencode.android.data.ProvidersDto())
    override suspend fun listAgents(): ApiResult<List<dev.opencode.android.data.AgentDto>> =
        ApiResult.Ok(emptyList())
}

/** 何も返さないサーバー素性。 */
private object Q9NoopServerInfo : dev.opencode.android.data.ServerInfoGateway {
    override val isConfigured: Boolean = true
    override suspend fun health(): ApiResult<dev.opencode.android.data.HealthDto> =
        ApiResult.Ok(dev.opencode.android.data.HealthDto(healthy = true, version = "1.18.21"))
}
