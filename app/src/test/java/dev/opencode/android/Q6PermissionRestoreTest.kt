package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ChatGateway
import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.PermissionRequestDto
import dev.opencode.android.data.QuestionRequestDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.data.TodoDto
import dev.opencode.android.ui.ChatController
import dev.opencode.android.ui.PermissionDialogState
import dev.opencode.android.ui.mergePendingPermission
import dev.opencode.android.ui.permissionDialogOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 申し送り **Q5-2**: permission がチャット再入場で復帰しない。
 *
 * Q5 の E2E が実機で観測した症状: `permission.asked` を SSE で取りこぼす(あるいは
 * 画面を出る)と、**戻す口が1つも無い**。サーバー側には残っており
 * (`permissionsPending=['per_1']`)、**応答を待ったまま止まり続ける**。
 *
 * Q3 は question について同じ問題を「`GET /question` でサーバーを権威にする」で解いた。
 * `GET /permission` は実物 serve 1.18.21 に存在する(実測 2026-08-27: 200 `[]`)ので、
 * **同じ手を当てる**。
 *
 * ## フィクスチャの根拠(§4.2「実物の形を貼る」)
 *
 * `PermissionRequest` の要素の形は spec スナップショット
 * (`docs/spec/opencode-1.18.21-openapi.json`)の required と、
 * P4 が実物 serve から採取した `permission.asked` の properties が**同一**であることによる。
 * **`GET /permission` が非空で返る様子は観測できていない**(402 Payment Required で
 * 推論が走らず、ツール実行の承認要求を実機で発生させられない)。
 * その旨は報告に「測れなかったこと」として明示してある —— **測れないと測っていないを混ぜない**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q6PermissionRestoreTest {

    private val sid = "ses_fc0247634ffeGddA7Mw2hp2KCh"
    private val other = "ses_OTHERSESSION000000000000"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * **実データの形をそのまま貼る。** `metadata` はオブジェクトである ——
     * `String?` と宣言した版が P4 で「ダイアログが一度も出ない」を起こした。
     * ここは JSON からデシリアライズして通すので、宣言を戻すとこのテストが落ちる。
     */
    private fun permissionJson(id: String, session: String) = """
        {"id":"$id","sessionID":"$session","permission":"tool:bash",
         "patterns":["ls *"],"metadata":{"command":"ls -la","description":"list files"},
         "always":["tool:read"],"tool":{"messageID":"msg_1","callID":"call_1"}}
    """.trimIndent()

    private fun dto(id: String, session: String): PermissionRequestDto =
        json.decodeFromString(PermissionRequestDto.serializer(), permissionJson(id, session))

    private class FakeGateway : ChatGateway {
        override val isConfigured = true
        var permissions: ApiResult<List<PermissionRequestDto>> = ApiResult.Ok(emptyList())
        var permissionCalls = 0

        override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> =
            ApiResult.Ok(emptyMap())

        override suspend fun listMessages(sessionId: String): ApiResult<List<MessageEntryDto>> =
            ApiResult.Ok(emptyList())

        override suspend fun sendPrompt(sessionId: String, text: String) = ApiResult.Ok(Unit)
        override suspend fun replyPermission(sessionId: String, permissionId: String, response: String) =
            ApiResult.Ok(Unit)

        override suspend fun abortSession(sessionId: String) = ApiResult.Ok(Unit)
        override suspend fun sessionTodos(sessionId: String): ApiResult<List<TodoDto>> =
            ApiResult.Ok(emptyList())

        override suspend fun pendingQuestions(): ApiResult<List<QuestionRequestDto>> =
            ApiResult.Ok(emptyList())

        override suspend fun pendingPermissions(): ApiResult<List<PermissionRequestDto>> {
            permissionCalls++
            return permissions
        }

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

    private fun TestScope.controller(gateway: ChatGateway) = ChatController(
        gateway = gateway,
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        describeError = { e ->
            when (e) {
                is ApiError.Http -> "HTTP ${e.code}"
                is ApiError.Network -> "NET ${e.message}"
                ApiError.NotConfigured -> "NOTCONF"
            }
        },
    )

    // ---- DTO -> 表示状態 ----

    @Test
    fun `PermissionRequest の metadata はオブジェクトのまま受けて文字列化する`() {
        val state = permissionDialogOf(dto("per_1", sid))
        assertEquals("per_1", state.permissionId)
        assertEquals(sid, state.sessionId)
        assertEquals("tool:bash", state.permission)
        assertEquals(listOf("ls *"), state.patterns)
        assertEquals(listOf("tool:read"), state.always)
        assertEquals("bash", state.tool)
        // オブジェクトを1行に落とす。**中身が消えないこと**が主張の本体。
        assertTrue(state.metadata!!.contains("ls -la"))
    }

    // ---- 入室での復帰(症状そのもの) ----

    @Test
    fun `入室でサーバーの未応答permissionを復元する`() = runTest {
        val gw = FakeGateway()
        gw.permissions = ApiResult.Ok(listOf(dto("per_1", sid)))
        val c = controller(gw)

        c.openChat(sid)

        assertEquals(1, gw.permissionCalls)
        val dialog = c.state.value.permissionDialog
        assertNotNull("SSE を取りこぼしても入室で戻ること(Q5-2 の症状)", dialog)
        assertEquals("per_1", dialog!!.permissionId)
    }

    @Test
    fun `再接続でも取り直す`() = runTest {
        val gw = FakeGateway()
        val c = controller(gw)
        c.openChat(sid)
        assertEquals(1, gw.permissionCalls)

        gw.permissions = ApiResult.Ok(listOf(dto("per_9", sid)))
        c.onReconnected()

        assertEquals(2, gw.permissionCalls)
        assertEquals("per_9", c.state.value.permissionDialog?.permissionId)
    }

    /** **別セッションの承認要求をこの画面に出さない。** 答えると別の実行が進んでしまう。 */
    @Test
    fun `別セッションの未応答permissionは取り込まない`() = runTest {
        val gw = FakeGateway()
        gw.permissions = ApiResult.Ok(listOf(dto("per_other", other)))
        val c = controller(gw)

        c.openChat(sid)

        assertNull(c.state.value.permissionDialog)
    }

    /** 取れなかったときは**何もしない**。読めなかったことを「未応答は無い」に化けさせない。 */
    @Test
    fun `取得に失敗しても既存のダイアログを消さない`() = runTest {
        val gw = FakeGateway()
        gw.permissions = ApiResult.Ok(listOf(dto("per_1", sid)))
        val c = controller(gw)
        c.openChat(sid)
        assertNotNull(c.state.value.permissionDialog)

        gw.permissions = ApiResult.Err(ApiError.Network("boom"))
        c.onReconnected()

        assertEquals("per_1", c.state.value.permissionDialog?.permissionId)
    }

    /** 未応答が無ければ**ダイアログを出さない**(陰性コントロール)。 */
    @Test
    fun `未応答が無ければ出さない`() = runTest {
        val gw = FakeGateway()
        val c = controller(gw)
        c.openChat(sid)
        assertEquals(1, gw.permissionCalls)
        assertNull(c.state.value.permissionDialog)
    }

    /**
     * `session.idle` は permission を**楽観的に**畳むが、直後に `GET /permission` で確認する。
     * サーバーがまだ pending だと言うなら戻す —— Q3 が質問について実証した
     * 「失効は推測にすぎない」を permission にも当てた形(Q6)。
     */
    @Test
    fun `idleで畳んでもサーバーがまだ未応答なら戻る`() = runTest {
        val gw = FakeGateway()
        gw.permissions = ApiResult.Ok(listOf(dto("per_1", sid)))
        val c = controller(gw)
        c.openChat(sid)
        assertEquals("per_1", c.state.value.permissionDialog?.permissionId)

        val before = gw.permissionCalls
        c.onEvent(
            dev.opencode.android.data.SseEvent.SessionIdle(sessionID = sid),
        )

        assertTrue("idle のあと取り直していない", gw.permissionCalls > before)
        assertEquals(
            "サーバーがまだ未応答と言っているのに消えている",
            "per_1",
            c.state.value.permissionDialog?.permissionId,
        )
    }

    /** 逆向き。サーバーが「もう無い」と言えば消える(陰性コントロール)。 */
    @Test
    fun `idleのあとサーバーが空なら消える`() = runTest {
        val gw = FakeGateway()
        gw.permissions = ApiResult.Ok(listOf(dto("per_1", sid)))
        val c = controller(gw)
        c.openChat(sid)
        assertNotNull(c.state.value.permissionDialog)

        gw.permissions = ApiResult.Ok(emptyList())
        c.onEvent(dev.opencode.android.data.SseEvent.SessionIdle(sessionID = sid))

        assertNull(c.state.value.permissionDialog)
    }

    // ---- マージ規則(Q3 の mergePendingQuestions と同じ形) ----

    private fun state(id: String) = PermissionDialogState(
        permissionId = id,
        sessionId = "ses_x",
        permission = "tool:bash",
        metadata = null,
        patterns = emptyList(),
        always = emptyList(),
        tool = "bash",
    )

    @Test
    fun `手元が空ならサーバーの先頭を出す`() {
        assertEquals(
            "per_a",
            mergePendingPermission(null, listOf(state("per_a"), state("per_b")), emptySet())?.permissionId,
        )
        assertNull(mergePendingPermission(null, emptyList(), emptySet()))
    }

    @Test
    fun `サーバーにも居るなら作り直さない`() {
        val current = state("per_a")
        val merged = mergePendingPermission(current, listOf(state("per_a")), setOf("per_a"))
        assertTrue("同一インスタンスを保つこと", merged === current)
    }

    /** 別クライアントが答えた場合。サーバーが決着済みと言っているので畳む。 */
    @Test
    fun `取得前から知っていたものがサーバーに無ければ畳む`() {
        val merged = mergePendingPermission(state("per_a"), emptyList(), setOf("per_a"))
        assertNull(merged)
    }

    @Test
    fun `畳んだあとに別の未応答があればそれを出す`() {
        val merged = mergePendingPermission(state("per_a"), listOf(state("per_b")), setOf("per_a"))
        assertEquals("per_b", merged?.permissionId)
    }

    /**
     * **取得中に届いたものは畳まない。** 取得が始まった時点のサーバーはそれを知らなかっただけで、
     * 「サーバーが知らない = 決着済み」ではない。Q3 が `knownBeforeFetch` を入れたのと同じ理由で、
     * ここを落とすと**届いたばかりの承認要求が即座に消える**。
     */
    @Test
    fun `取得中に届いたものは畳まない`() {
        val arrivedDuringFetch = state("per_new")
        val merged = mergePendingPermission(
            current = arrivedDuringFetch,
            serverPending = emptyList(),
            knownBeforeFetch = setOf("per_a"),
        )
        assertTrue(merged === arrivedDuringFetch)
    }
}
