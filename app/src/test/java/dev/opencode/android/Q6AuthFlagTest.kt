package dev.opencode.android

import dev.opencode.android.data.AgentDto
import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.CatalogGateway
import dev.opencode.android.data.ChatGateway
import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.PermissionRequestDto
import dev.opencode.android.data.QuestionRequestDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.data.TodoDto
import dev.opencode.android.ui.ChatController
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.ProvidersDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SessionsGateway
import dev.opencode.android.ui.ModelCatalogController
import dev.opencode.android.ui.SessionListController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `errorIsAuth` が**通信の結果から実際に立つ**こと(Q6 レビュー blocker)。
 *
 * `isAuthError` そのものの単体テストは 1周目からあったが、**それを呼んでいる側**——
 * 各 Controller の `errorIsAuth = isAuthError(result.error)` ——には検出器が無く、
 * レビューがそこを `false` 固定にする変異を2箇所に打つと **496件全緑で通り抜けた**。
 * 症状は「**401 なのに『再試行』ボタンが出る**」= 押しても直らないボタンで行き止まりになる、
 * という Q6 がまさに直した欠陥である。
 *
 * 述語だけでなく**述語の呼び出し**に検出器を置く。RUN_PLAN
 * 「純関数のテストだけでゲートを閉じない」の Q6 版。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q6AuthFlagTest {

    private fun describe(e: ApiError): String = when (e) {
        is ApiError.Http -> "HTTP ${e.code}"
        is ApiError.Network -> "NET ${e.message}"
        ApiError.NotConfigured -> "NOTCONF"
    }

    // ---- 一覧 ----

    private class FakeSessions(var result: ApiResult<List<SessionDto>>) : SessionsGateway {
        override val isConfigured = true
        override suspend fun listSessions(limit: Int?, search: String?) = result
        override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> =
            ApiResult.Ok(emptyMap())

        override suspend fun createSession(title: String, agent: String?, model: ModelRefDto?) =
            ApiResult.Err(ApiError.NotConfigured)

        override suspend fun renameSession(sessionId: String, title: String) =
            ApiResult.Err(ApiError.NotConfigured)

        override suspend fun deleteSession(sessionId: String) = ApiResult.Err(ApiError.NotConfigured)
    }

    private fun TestScope.sessionList(gw: SessionsGateway) = SessionListController(
        gateway = gw,
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        describeError = ::describe,
    )

    @Test
    fun `一覧は401でerrorIsAuthを立てる`() = runTest {
        val gw = FakeSessions(ApiResult.Err(ApiError.Http(401)))
        val c = sessionList(gw)
        c.refresh()
        assertTrue("401 なのに errorIsAuth が立っていない", c.state.value.errorIsAuth)
        assertEquals("HTTP 401", c.state.value.error)
    }

    @Test
    fun `一覧は403でも立てる`() = runTest {
        val c = sessionList(FakeSessions(ApiResult.Err(ApiError.Http(403))))
        c.refresh()
        assertTrue(c.state.value.errorIsAuth)
    }

    /** 陰性側。認証以外では立てない —— でないと通信エラーでも再試行が消える。 */
    @Test
    fun `一覧はネットワーク失敗では立てない`() = runTest {
        val c = sessionList(FakeSessions(ApiResult.Err(ApiError.Network("boom"))))
        c.refresh()
        assertFalse(c.state.value.errorIsAuth)
        assertEquals("NET boom", c.state.value.error)
    }

    /** 成功したら**降ろす**。前回の 401 が残ると、直った後も再試行が出ない。 */
    @Test
    fun `一覧は成功でerrorIsAuthを降ろす`() = runTest {
        val gw = FakeSessions(ApiResult.Err(ApiError.Http(401)))
        val c = sessionList(gw)
        c.refresh()
        assertTrue(c.state.value.errorIsAuth)
        gw.result = ApiResult.Ok(emptyList())
        c.refresh()
        assertFalse(c.state.value.errorIsAuth)
    }

    // ---- カタログ ----

    private class FakeCatalog(
        var providers: ApiResult<ProvidersDto>,
        var agents: ApiResult<List<AgentDto>> = ApiResult.Ok(emptyList()),
    ) : CatalogGateway {
        override val isConfigured = true
        override suspend fun listProviders() = providers
        override suspend fun listAgents() = agents
    }

    private fun TestScope.catalog(gw: CatalogGateway) = ModelCatalogController(
        gateway = gw,
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        describeError = ::describe,
    )

    @Test
    fun `カタログは401でerrorIsAuthを立てる`() = runTest {
        val c = catalog(FakeCatalog(ApiResult.Err(ApiError.Http(401))))
        c.reload()
        assertTrue("401 なのに errorIsAuth が立っていない", c.state.value.errorIsAuth)
    }

    @Test
    fun `カタログはネットワーク失敗では立てない`() = runTest {
        val c = catalog(FakeCatalog(ApiResult.Err(ApiError.Network("boom"))))
        c.reload()
        assertFalse(c.state.value.errorIsAuth)
    }

    /** エージェントだけが 401 のときも立てる(モデルは取れているがカタログは不完全)。 */
    @Test
    fun `エージェント側の401でも立てる`() = runTest {
        val c = catalog(
            FakeCatalog(
                providers = ApiResult.Ok(ProvidersDto()),
                agents = ApiResult.Err(ApiError.Http(401)),
            ),
        )
        c.reload()
        assertTrue(c.state.value.errorIsAuth)
    }

    // ---- チャット ----
    //
    // 2周目の変異 W6(`errorIsAuth = false` 固定)は**ここだけ素通しした**。
    // 一覧とカタログには検出器を置いたのにチャットに置き忘れており、
    // 「同じ形の穴が3つあって2つだけ塞ぐ」という、このプロジェクトが
    // 繰り返してきた形そのものだった。

    private class FakeChat(var messages: ApiResult<List<MessageEntryDto>>) : ChatGateway {
        override val isConfigured = true
        override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> =
            ApiResult.Ok(emptyMap())
        override suspend fun listMessages(sessionId: String) = messages
        override suspend fun sendPrompt(sessionId: String, text: String) = ApiResult.Ok(Unit)
        override suspend fun replyPermission(sessionId: String, permissionId: String, response: String) =
            ApiResult.Ok(Unit)
        override suspend fun abortSession(sessionId: String) = ApiResult.Ok(Unit)
        override suspend fun sessionTodos(sessionId: String): ApiResult<List<TodoDto>> =
            ApiResult.Ok(emptyList())
        override suspend fun pendingQuestions(): ApiResult<List<QuestionRequestDto>> =
            ApiResult.Ok(emptyList())
        override suspend fun pendingPermissions(): ApiResult<List<PermissionRequestDto>> =
            ApiResult.Ok(emptyList())
        override suspend fun replyQuestion(requestId: String, answers: List<List<String>>) = ApiResult.Ok(Unit)
        override suspend fun rejectQuestion(requestId: String) = ApiResult.Ok(Unit)
        override suspend fun getSession(sessionId: String): ApiResult<SessionDto> =
            ApiResult.Ok(SessionDto(id = sessionId, title = "t", time = SessionTimeDto(1, 1)))
        override suspend fun switchSessionModel(sessionId: String, model: ModelRefDto) = ApiResult.Ok(Unit)
    
        // Q7: 巻き戻し。**この試験の関心ではない**ので、呼ばれたら失敗にする ——
        // 素通しの Ok を返すと「押していないのに送られた」変異が通り抜ける。
        override suspend fun revertMessage(
            sessionId: String,
            messageId: String,
            partId: String?,
            directory: String?,
        ): ApiResult<SessionDto> = throw AssertionError("この試験では revert を呼ばない")

        override suspend fun unrevertSession(sessionId: String, directory: String?): ApiResult<SessionDto> =
            throw AssertionError("この試験では unrevert を呼ばない")
}

    private fun TestScope.chat(gw: ChatGateway) = ChatController(
        gateway = gw,
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        describeError = ::describe,
    )

    @Test
    fun `チャットは401でerrorIsAuthを立てる`() = runTest {
        val c = chat(FakeChat(ApiResult.Err(ApiError.Http(401))))
        c.openChat("ses_1")
        assertTrue("401 なのに errorIsAuth が立っていない", c.state.value.errorIsAuth)
    }

    @Test
    fun `チャットはネットワーク失敗では立てない`() = runTest {
        val c = chat(FakeChat(ApiResult.Err(ApiError.Network("boom"))))
        c.openChat("ses_1")
        assertFalse(c.state.value.errorIsAuth)
    }

    @Test
    fun `チャットは成功でerrorIsAuthを降ろす`() = runTest {
        val gw = FakeChat(ApiResult.Err(ApiError.Http(401)))
        val c = chat(gw)
        c.openChat("ses_1")
        assertTrue(c.state.value.errorIsAuth)
        gw.messages = ApiResult.Ok(emptyList())
        c.retryLoadMessages()
        assertFalse(c.state.value.errorIsAuth)
    }
}
