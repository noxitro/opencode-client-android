package dev.opencode.android

import dev.opencode.android.ui.PtyController
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.CatalogGateway
import dev.opencode.android.data.DiffGateway
import dev.opencode.android.data.HealthDto
import dev.opencode.android.data.AgentDto
import dev.opencode.android.data.ProvidersDto
import dev.opencode.android.data.ServerInfoGateway
import dev.opencode.android.data.SnapshotFileDiffDto
import dev.opencode.android.data.VcsFileDiffDto
import dev.opencode.android.data.VcsFileStatusDto
import dev.opencode.android.data.VcsInfoDto
import dev.opencode.android.ui.DiffController
import dev.opencode.android.ui.ChatController
import dev.opencode.android.ui.FileBrowserController
import dev.opencode.android.ui.SessionListController
import dev.opencode.android.ui.ModelCatalogController
import dev.opencode.android.ui.ServerInfoController
import dev.opencode.android.ui.deliverConnectionsTo
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
 * Q7 の**配線層**の検出器。
 *
 * Q5 のレビューは配線層だけを狙った変異8本のうち**7本を431件全緑で通し**、
 * うち3本は過去の段の blocker を静かに再オープンするものだった。
 * RUN_PLAN が常設化した規則は「**宛先はオブジェクトで名指しし、ラムダで受け取らない**」で、
 * Q7 が足した2つの宛先([DiffController])はどちらもその形にしてある ——
 * `toDiff: () -> Unit` にすると、呼び出し側が `{ }` を渡す変異が**この関数のテストからは見えない**。
 *
 * ここが守っている症状:
 *
 *  - 接続先を変えても捨てない → **サーバーAのブランチがサーバーBの TopAppBar に出続ける**
 *  - 再接続でブランチを引き直さない → `vcs.*` の SSE は**存在しない**ので、
 *    切れている間のブランチ切替はイベント列から原理的に復元できず、**二度と直らない**
 *
 * どちらも画面はどこも壊れて見えない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q7WiringTest {

    private class CountingDiffGateway : DiffGateway {
        override val isConfigured: Boolean = true
        var infoCalls = 0
        override suspend fun vcsInfo(directory: String?): ApiResult<VcsInfoDto> {
            infoCalls++
            return ApiResult.Ok(VcsInfoDto("master", "master"))
        }
        override suspend fun vcsStatus(directory: String?): ApiResult<List<VcsFileStatusDto>> = ApiResult.Ok(emptyList())
        override suspend fun vcsDiff(mode: String, context: Int?, directory: String?): ApiResult<List<VcsFileDiffDto>> =
            ApiResult.Ok(emptyList())
        override suspend fun sessionDiff(
            sessionId: String,
            messageId: String?,
            directory: String?,
        ): ApiResult<List<SnapshotFileDiffDto>> =
            ApiResult.Ok(emptyList())
    }

    private class StubCatalog : CatalogGateway {
        override val isConfigured: Boolean = true
        override suspend fun listProviders(): ApiResult<ProvidersDto> = ApiResult.Ok(ProvidersDto())
        override suspend fun listAgents(): ApiResult<List<AgentDto>> = ApiResult.Ok(emptyList())
    }

    private class StubServerInfo : ServerInfoGateway {
        override val isConfigured: Boolean = true
        override suspend fun health(): ApiResult<HealthDto> =
            ApiResult.Ok(HealthDto(healthy = true, version = "1.18.21"))
    }

    // ---------------------------------------------------------------------
    // 接続先の変更 → 差分側も捨てる
    // ---------------------------------------------------------------------

    @Test
    fun `接続先が変わると差分側もブランチを捨てる`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = CountingDiffGateway()
        val diff = DiffController(gw, scope, describeError = { "e" })
        val catalog = ModelCatalogController(StubCatalog(), scope, describeError = { "e" })
        val info = ServerInfoController(StubServerInfo(), scope, describeError = { "e" })

        diff.onConnectionChanged("http://a:4097")
        diff.ensureBranchLoaded()
        scope.runCurrent()
        assertEquals("master", diff.state.value.branch.branch)

        flowOf<String?>("http://b:4097").wireConnectionChangesTo(
            catalog = catalog,
            serverInfo = info,
            diff = diff,
            // Q8: 4つ目の宛先。ツリーと検索結果もサーバーごとに違う。
            files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), scope, describeError = { "e" }),
            // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
            pty = PtyController(FakePtyGateway(), scope, describeError = { "e" }),
            onFailure = { throw AssertionError("配布が失敗した: $it") },
        )

        assertNull("サーバーAのブランチをBの画面に出さない", diff.state.value.branch.branch)
    }

    @Test
    fun `同じ接続先なら差分側も捨てない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = CountingDiffGateway()
        val diff = DiffController(gw, scope, describeError = { "e" })
        val catalog = ModelCatalogController(StubCatalog(), scope, describeError = { "e" })
        val info = ServerInfoController(StubServerInfo(), scope, describeError = { "e" })

        diff.onConnectionChanged("http://a:4097")
        diff.ensureBranchLoaded()
        scope.runCurrent()

        flowOf<String?>("http://a:4097").wireConnectionChangesTo(
            catalog = catalog,
            serverInfo = info,
            diff = diff,
            // Q8: 4つ目の宛先。ツリーと検索結果もサーバーごとに違う。
            files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), scope, describeError = { "e" }),
            // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
            pty = PtyController(FakePtyGateway(), scope, describeError = { "e" }),
            onFailure = { throw AssertionError("$it") },
        )
        assertEquals("master", diff.state.value.branch.branch)
    }

    // ---------------------------------------------------------------------
    // 再接続 → 差分側もブランチを取り直す
    // ---------------------------------------------------------------------

    @Test
    fun `再接続は差分側にも配られる`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val gw = CountingDiffGateway()
        val diff = DiffController(gw, this, describeError = { "e" })
        val sessionsGw = RecordingSessionsGateway()
        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = { throw AssertionError("${'$'}it") },
                // Q8: 宛先はすべて実体(Q7 申し送り Q7-6)。「配られた」は
                // **ゲートウェイが撃たれたこと**で測る。
                sessions = SessionListController(sessionsGw, this, describeError = { "e" }),
                chat = ChatController(NoopChatGateway(), this, describeError = { "e" }),
                diff = diff,
                files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), this, describeError = { "e" }),
                // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
                pty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }

        flow.emit(1)
        flow.emit(2)

        assertEquals("再接続のたびに `GET /session/status` を引き直す", 2, sessionsGw.statusCalls)
        assertEquals("再接続のたびに `GET /vcs` を引き直す", 2, gw.infoCalls)
        job.cancel()
    }

    /** 世代0(まだ一度も繋がっていない)では差分側も撃たない。 */
    @Test
    fun `世代0では差分側も撃たない`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val gw = CountingDiffGateway()
        val diff = DiffController(gw, this, describeError = { "e" })
        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = { throw AssertionError("${'$'}it") },
                sessions = SessionListController(RecordingSessionsGateway(), this, describeError = { "e" }),
                chat = ChatController(NoopChatGateway(), this, describeError = { "e" }),
                diff = diff,
                files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), this, describeError = { "e" }),
                // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
                pty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }
        flow.emit(0)
        assertEquals(0, gw.infoCalls)
        job.cancel()
    }

    /** 先に投げる宛先があっても、後続の再接続で差分側は生きている(`collectGuarded`)。 */
    @Test
    fun `前の宛先が投げても購読は死なない`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val gw = CountingDiffGateway()
        val diff = DiffController(gw, this, describeError = { "e" })
        val failures = mutableListOf<Throwable>()
        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = { failures += it },
                // `isConfigured` を読んだ瞬間に投げる。`scope.launch` の中で投げると
                // 別のコルーチンの話になり、配線の主張にならない。
                sessions = SessionListController(ThrowingSessionsGateway(), this, describeError = { "e" }),
                chat = ChatController(NoopChatGateway(), this, describeError = { "e" }),
                diff = diff,
                files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), this, describeError = { "e" }),
                // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
                pty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }
        flow.emit(1)
        flow.emit(2)
        assertTrue(job.isActive)
        assertEquals(2, failures.size)
        job.cancel()
    }
}
