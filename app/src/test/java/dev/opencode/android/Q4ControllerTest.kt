package dev.opencode.android

import dev.opencode.android.data.AgentDto
import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.CatalogGateway
import dev.opencode.android.data.ChatGateway
import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.ModelCapabilitiesDto
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.ProviderDto
import dev.opencode.android.data.ProviderModelDto
import dev.opencode.android.data.ProvidersDto
import dev.opencode.android.data.PermissionRequestDto
import dev.opencode.android.data.QuestionRequestDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.ui.modelSwitchFailureMessage
import dev.opencode.android.data.TodoDto
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.ChatController
import dev.opencode.android.ui.ModelCatalogController
import dev.opencode.android.ui.modelCatalogEmptyStateOf
import dev.opencode.android.ui.modelCatalogNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q4 の**状態遷移**に検出器を置く(RUN_PLAN「検出器の穴という欠陥形」)。
 *
 * Q1 のレビューは「純関数のテストは完璧なのに、切り出せなかった部分にテストが1本も無い」を
 * 変異2本で示した —— 102件全緑のまま R2 とバッジが死に、**壊れた症状はどちらも
 * 画面が正常に見える**ものだった。Q4 で同じ位置にあるのは
 * 「入室・再接続でモデルを取り直すか」「切替後にサーバーへ問い直すか」である。
 * どちらも欠けても画面は正常に見え、**ピルだけが嘘をつく**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q4ControllerTest {

    private val sid = "ses_fbebc2a78ffeMMjWyj0mBPzBKu"

    // ---------- ChatGateway のフェイク ----------

    private class FakeChat(override val isConfigured: Boolean = true) : ChatGateway {
        var session: ApiResult<SessionDto> = ApiResult.Ok(
            SessionDto(id = "ses_x", title = "t", time = SessionTimeDto(1, 1)),
        )
        var sessionCalls = 0
        var switchResult: ApiResult<Unit> = ApiResult.Ok(Unit)
        val switched = mutableListOf<Pair<String, ModelRefDto>>()

        override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> = ApiResult.Ok(emptyMap())
        override suspend fun listMessages(sessionId: String): ApiResult<List<MessageEntryDto>> = ApiResult.Ok(emptyList())
        override suspend fun sendPrompt(sessionId: String, text: String): ApiResult<Unit> = ApiResult.Ok(Unit)
        override suspend fun replyPermission(sessionId: String, permissionId: String, response: String) = ApiResult.Ok(Unit)
        override suspend fun abortSession(sessionId: String): ApiResult<Unit> = ApiResult.Ok(Unit)
        override suspend fun sessionTodos(sessionId: String): ApiResult<List<TodoDto>> = ApiResult.Ok(emptyList())
        override suspend fun pendingQuestions(): ApiResult<List<QuestionRequestDto>> = ApiResult.Ok(emptyList())
        override suspend fun pendingPermissions(): ApiResult<List<PermissionRequestDto>> = ApiResult.Ok(emptyList())
        override suspend fun replyQuestion(requestId: String, answers: List<List<String>>) = ApiResult.Ok(Unit)
        override suspend fun rejectQuestion(requestId: String): ApiResult<Unit> = ApiResult.Ok(Unit)

        override suspend fun getSession(sessionId: String): ApiResult<SessionDto> {
            sessionCalls++
            return session
        }

        override suspend fun switchSessionModel(sessionId: String, model: ModelRefDto): ApiResult<Unit> {
            switched += sessionId to model
            return switchResult
        }
    
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

    private fun TestScope.chat(gateway: ChatGateway) =
        ChatController(gateway, this, describeError = { e ->
            when (e) {
                is ApiError.Http -> "HTTP ${e.code}"
                is ApiError.Network -> "NET ${e.message}"
                ApiError.NotConfigured -> "NOT_CONFIGURED"
            }
        })

    private fun sessionWith(model: ModelRefDto?, agent: String? = null) = ApiResult.Ok(
        SessionDto(id = "ses_x", title = "t", time = SessionTimeDto(1, 1), model = model, agent = agent),
    )

    // ---------- 入室・再接続で取り直す(RUN_PLAN 決定2 の Q4 版) ----------

    /**
     * **入室時に `GET /session/{id}` を引くこと。**
     * これを消す変異は、ピルが永久に「既定モデル」と言い続けるだけで画面は正常に見える。
     */
    @Test
    fun `入室でセッションのモデルをサーバーから取る`() = runTest {
        val g = FakeChat()
        g.session = sessionWith(ModelRefDto(id = "mistral-medium-latest", providerID = "mistral"), agent = "plan")
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        assertEquals(1, g.sessionCalls)
        assertEquals("mistral-medium-latest", c.state.value.sessionModel?.id)
        assertEquals("plan", c.state.value.sessionAgent)
    }

    /**
     * **再接続でも取り直すこと。**
     *
     * 切替は `session.updated` を流さない(実測)ので、切れている間に別クライアント
     * (TUI/CLI)が切り替えていたら、イベント列からは永久に追いつけない。
     */
    @Test
    fun `再接続でセッションのモデルを取り直す`() = runTest {
        val g = FakeChat()
        g.session = sessionWith(ModelRefDto(id = "m1", providerID = "p"))
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        assertEquals(1, g.sessionCalls)

        g.session = sessionWith(ModelRefDto(id = "m2", providerID = "p"))
        c.onReconnected()
        runCurrent()
        assertEquals(2, g.sessionCalls)
        assertEquals("m2", c.state.value.sessionModel?.id)
    }

    /** 取れなかったときは**何もしない**。読めなかったことを「既定」に化けさせない。 */
    @Test
    fun `取得失敗でモデル表示を消さない`() = runTest {
        val g = FakeChat()
        g.session = sessionWith(ModelRefDto(id = "m1", providerID = "p"))
        val c = chat(g)
        c.openChat(sid)
        runCurrent()

        g.session = ApiResult.Err(ApiError.Http(500))
        c.onReconnected()
        runCurrent()
        assertEquals("m1", c.state.value.sessionModel?.id)
    }

    /** 別セッションへ移った後の応答を当てない。 */
    @Test
    fun `退室後の応答は当てない`() = runTest {
        val g = FakeChat()
        g.session = sessionWith(ModelRefDto(id = "m1", providerID = "p"))
        val c = chat(g)
        c.openChat(sid)
        c.openChat("ses_other")
        g.session = sessionWith(ModelRefDto(id = "m-other", providerID = "p"))
        runCurrent()
        assertEquals("m-other", c.state.value.sessionModel?.id)
    }

    // ---------- 切替 ----------

    /** 送るのは `ModelRef`(**`id` キー**)であり、切替後は**サーバーへ問い直す**。 */
    @Test
    fun `モデル切替は送信して取り直す`() = runTest {
        val g = FakeChat()
        g.session = sessionWith(ModelRefDto(id = "m1", providerID = "p"))
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        val callsAfterOpen = g.sessionCalls

        g.session = sessionWith(ModelRefDto(id = "m2", providerID = "p", variant = "default"))
        c.switchModel(ModelRefDto(id = "m2", providerID = "p"))
        runCurrent()

        assertEquals(listOf(sid to ModelRefDto(id = "m2", providerID = "p")), g.switched)
        assertEquals("切替後に GET し直すこと", callsAfterOpen + 1, g.sessionCalls)
        // サーバーが正規化した値(variant="default")が最終的に効く。
        assertEquals("default", c.state.value.sessionModel?.variant)
        assertFalse(c.state.value.switchingModel)
    }

    /** モデルを変えたのだから、前のモデルの失敗はもう現在の話ではない。 */
    @Test
    fun `切替成功で session error を畳む`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.onEvent(
            parseSseEnvelope(
                """{"id":"e","type":"session.error","properties":{"sessionID":"$sid",""" +
                    """"error":{"name":"APIError","data":{"message":"Payment Required","statusCode":402,""" +
                    """"isRetryable":false}}}}""",
            )!!,
        )
        runCurrent()
        assertEquals(402, c.state.value.sessionErrorStatusCode)

        c.switchModel(ModelRefDto(id = "m2", providerID = "p"))
        runCurrent()
        assertNull(c.state.value.sessionError)
        assertNull(c.state.value.sessionErrorStatusCode)
        assertNull(c.state.value.sessionErrorRetryable)
    }

    @Test
    fun `切替失敗は送信エラー帯に出て状態を変えない`() = runTest {
        val g = FakeChat()
        g.session = sessionWith(ModelRefDto(id = "m1", providerID = "p"))
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        g.switchResult = ApiResult.Err(ApiError.Http(404))

        c.switchModel(ModelRefDto(id = "m2", providerID = "p"))
        runCurrent()
        assertEquals("m1", c.state.value.sessionModel?.id)
        assertTrue(c.state.value.sendError!!.contains("HTTP 404"))
        assertFalse(c.state.value.switchingModel)
    }

    // ---------- 500 のときに手がかりを出す(申し送りの「未回収」1件) ----------

    /**
     * **500 だけは実測の原因と作業ディレクトリを添える。**
     *
     * `POST /api/session/{id}/model` は、セッションの `directory` がホストに実在しないと
     * モデル値によらず 500 `UnknownError` を返す(実測)。サーバーは詳細を返さないので
     * アプリは断定できないが、**手がかりを1つも出さないのは別の話**である。
     * 1周目は「サーバーエラー(HTTP 500)」の固定文言だけだった。
     */
    @Test
    fun `切替が500なら作業ディレクトリを手がかりに出す`() = runTest {
        val g = FakeChat()
        g.session = ApiResult.Ok(
            SessionDto(
                id = "ses_x",
                title = "t",
                time = SessionTimeDto(1, 1),
                model = ModelRefDto(id = "m1", providerID = "p"),
                directory = "E:/github/opencode-android",
            ),
        )
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        g.switchResult = ApiResult.Err(ApiError.Http(500))

        c.switchModel(ModelRefDto(id = "m2", providerID = "p"))
        runCurrent()

        val msg = c.state.value.sendError!!
        assertTrue("実測の原因を言う: " + msg, msg.contains("作業ディレクトリ"))
        assertTrue("そのパスを名指しする: " + msg, msg.contains("E:/github/opencode-android"))
        assertTrue("断定しない: " + msg, msg.contains("区別する手立て"))
        // モデルは変わっていない(帯を足しただけ)。
        assertEquals("m1", c.state.value.sessionModel?.id)
    }

    /** **作業ディレクトリを知らないなら知らないと言う。** 空欄や嘘のパスを出さない。 */
    @Test
    fun `作業ディレクトリが分からなければその旨を出す`() {
        val msg = modelSwitchFailureMessage(
            error = ApiError.Http(500),
            sessionDirectory = null,
            describe = { "サーバーエラー(HTTP 500)" },
        )
        assertTrue(msg.contains("まだ取得できていません"))
    }

    /**
     * **500 以外は今までどおりの1行のまま。**
     *
     * ここが崩れると、404 や 409 にまで「作業ディレクトリが無いのかもしれません」と
     * 見当違いの説明が付く。
     */
    @Test
    fun `500以外には作業ディレクトリの説明を付けない`() {
        val msg = modelSwitchFailureMessage(
            error = ApiError.Http(404),
            sessionDirectory = "E:/somewhere",
            describe = { "サーバーエラー(HTTP 404)" },
        )
        assertEquals("モデルの切り替えに失敗しました: サーバーエラー(HTTP 404)", msg)
    }

    /** 画面外(入室していない)では投げない。 */
    @Test
    fun `画面外では切替を送らない`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.switchModel(ModelRefDto(id = "m2", providerID = "p"))
        runCurrent()
        assertTrue(g.switched.isEmpty())
    }

    // ---------- SSE の追随 ----------

    @Test
    fun `model switched イベントでピルが追随する`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        val used = c.onEvent(
            parseSseEnvelope(
                """{"id":"e","type":"session.next.model.switched","properties":{"sessionID":"$sid",""" +
                    """"messageID":"msg_a","timestamp":"t","model":{"id":"m9","providerID":"p9"}}}""",
            )!!,
        )
        assertTrue(used)
        assertEquals("m9", c.state.value.sessionModel?.id)
    }

    /**
     * **agent 切替のイベントで model を消さない。**
     * 片方しか載っていないイベントでもう片方を null にすると、ピルが「既定モデル」へ嘘をつく。
     */
    @Test
    fun `agent switched イベントは model を消さない`() = runTest {
        val g = FakeChat()
        g.session = sessionWith(ModelRefDto(id = "m1", providerID = "p"))
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.onEvent(
            parseSseEnvelope(
                """{"id":"e","type":"session.next.agent.switched","properties":{"sessionID":"$sid",""" +
                    """"messageID":"msg_a","timestamp":"t","agent":"build"}}""",
            )!!,
        )
        assertEquals("m1", c.state.value.sessionModel?.id)
        assertEquals("build", c.state.value.sessionAgent)
    }

    @Test
    fun `別セッションの切替イベントは無視する`() = runTest {
        val g = FakeChat()
        g.session = sessionWith(ModelRefDto(id = "m1", providerID = "p"))
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        val used = c.onEvent(
            parseSseEnvelope(
                """{"id":"e","type":"session.next.model.switched","properties":{"sessionID":"ses_other",""" +
                    """"messageID":"msg_a","timestamp":"t","model":{"id":"m9","providerID":"p9"}}}""",
            )!!,
        )
        assertFalse(used)
        assertEquals("m1", c.state.value.sessionModel?.id)
    }

    // ---------- メッセージのメタ ----------

    /**
     * `message.updated` が part より**先に**届いても `providerID`/`modelID` を落とさない。
     * 失敗した往復は part が1つも来ないので、**失敗ほどメタが落ちやすい**。
     */
    @Test
    fun `part より先に届いたメタを保留して後で載せる`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.onEvent(
            parseSseEnvelope(
                """{"id":"e1","type":"message.updated","properties":{"sessionID":"$sid","info":{""" +
                    """"id":"msg_a","sessionID":"$sid","role":"assistant","providerID":"mistral",""" +
                    """"modelID":"mistral-small-latest","cost":0,""" +
                    """"tokens":{"input":5,"output":7,"reasoning":0,"cache":{"read":0,"write":0}}}}}""",
            )!!,
        )
        assertTrue("まだメッセージは出来ていない", c.state.value.messages.isEmpty())

        c.onEvent(
            parseSseEnvelope(
                """{"id":"e2","type":"message.part.updated","properties":{"sessionID":"$sid","part":{""" +
                    """"id":"prt_a","sessionID":"$sid","messageID":"msg_a","type":"text","text":"PONG"}}}""",
            )!!,
        )
        val m = c.state.value.messages.single()
        assertEquals("assistant", m.role)
        assertEquals("mistral", m.meta?.providerId)
        assertEquals("mistral-small-latest", m.meta?.modelId)
        assertEquals(5L, m.meta?.inputTokens)
        assertEquals(7L, m.meta?.outputTokens)
    }

    /** 後から来た `message.updated` が**メタを持っていなければ上書きしない**。 */
    @Test
    fun `メタを持たない message updated で既存のメタを消さない`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.onEvent(
            parseSseEnvelope(
                """{"id":"e1","type":"message.part.updated","properties":{"sessionID":"$sid","part":{""" +
                    """"id":"prt_a","sessionID":"$sid","messageID":"msg_a","type":"text","text":"P"}}}""",
            )!!,
        )
        c.onEvent(
            parseSseEnvelope(
                """{"id":"e2","type":"message.updated","properties":{"sessionID":"$sid","info":{""" +
                    """"id":"msg_a","sessionID":"$sid","role":"assistant","providerID":"mistral",""" +
                    """"modelID":"m1"}}}""",
            )!!,
        )
        assertEquals("m1", c.state.value.messages.single().meta?.modelId)

        c.onEvent(
            parseSseEnvelope(
                """{"id":"e3","type":"message.updated","properties":{"sessionID":"$sid","info":{""" +
                    """"id":"msg_a","sessionID":"$sid","role":"assistant"}}}""",
            )!!,
        )
        assertEquals("m1", c.state.value.messages.single().meta?.modelId)
    }

    // ---------- ModelCatalogController ----------

    private class FakeCatalog(override val isConfigured: Boolean = true) : CatalogGateway {
        var providers: ApiResult<ProvidersDto> = ApiResult.Ok(
            ProvidersDto(
                all = listOf(
                    ProviderDto(
                        id = "mistral", name = "Mistral", source = "api",
                        models = mapOf("m1" to ProviderModelDto(id = "m1", name = "M1")),
                    ),
                ),
                default = mapOf("mistral" to "m1"),
                connected = listOf("mistral"),
            ),
        )
        var agents: ApiResult<List<AgentDto>> = ApiResult.Ok(listOf(AgentDto(name = "build", mode = "primary")))
        var providerCalls = 0
        var agentCalls = 0

        override suspend fun listProviders(): ApiResult<ProvidersDto> {
            providerCalls++
            return providers
        }

        override suspend fun listAgents(): ApiResult<List<AgentDto>> {
            agentCalls++
            return agents
        }
    }

    private fun TestScope.catalog(g: CatalogGateway) =
        ModelCatalogController(g, this, describeError = { e ->
            when (e) {
                is ApiError.Http -> "HTTP ${e.code}"
                is ApiError.Network -> "NET ${e.message}"
                ApiError.NotConfigured -> "NOT_CONFIGURED"
            }
        })

    /**
     * **成功したら二度引かない。** `GET /provider` は実測 5.4 MiB なので、
     * シートを開くたびに引くと端末が払う量が桁違いになる。
     */
    @Test
    fun `カタログは一度だけ引く`() = runTest {
        val g = FakeCatalog()
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, g.providerCalls)
        c.ensureLoaded()
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, g.providerCalls)
        assertEquals(1, c.state.value.models.size)
        assertEquals(1, c.state.value.agents.size)
    }

    /** **失敗は loaded にしない。** 一度失敗したきり空の選択肢を出し続けない。 */
    @Test
    fun `失敗したら次に開いたとき引き直す`() = runTest {
        val g = FakeCatalog()
        g.providers = ApiResult.Err(ApiError.Network("boom"))
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, g.providerCalls)
        assertTrue(c.state.value.error!!.contains("NET boom"))
        assertFalse(c.state.value.loaded)

        g.providers = FakeCatalog().providers
        c.ensureLoaded()
        runCurrent()
        assertEquals(2, g.providerCalls)
        assertEquals(1, c.state.value.models.size)
        assertNull(c.state.value.error)
    }

    /** エージェントだけ失敗しても**モデル選択は成立させる**(R3 の導線を閉じない)。 */
    @Test
    fun `エージェント取得の失敗はモデル選択を壊さない`() = runTest {
        val g = FakeCatalog()
        g.agents = ApiResult.Err(ApiError.Http(500))
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, c.state.value.models.size)
        assertTrue(c.state.value.agents.isEmpty())
        assertNotNull(c.state.value.error)
        assertTrue(c.state.value.loaded)
    }

    /**
     * **一度成功したあとの再取得失敗が、画面に出ること**(Q10/Q11 差し戻し回収・回収項目C)。
     *
     * E2E ゲートが実機で見つけた形: 2回目の取得が失敗すると `models` は前回のまま残るのに、
     * 空状態は `visibleCount > 0` で `null` を返すので**失敗はどこにも出ない**。
     * ユーザーには「取れている一覧」と「取り直せなかった古い一覧」の区別が付かない。
     *
     * 一覧を消す側へは倒さない(消すと「モデルが1件も無いサーバー」と区別が付かなくなる)。
     * **残したまま古いと言う**のがこの状態の意味である。
     */
    @Test
    fun `再取得に失敗したら古い一覧だと画面に出す`() = runTest {
        val g = FakeCatalog()
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, c.state.value.models.size)
        assertFalse(c.state.value.stale)
        assertNull(modelCatalogNotice(c.state.value))

        g.providers = ApiResult.Err(ApiError.Network("boom"))
        c.reload()
        runCurrent()

        assertEquals("一覧は残すこと", 1, c.state.value.models.size)
        assertTrue("古いと立てること", c.state.value.stale)
        // **空状態は出ない**(= 一覧が出ているので、帯が唯一の告知経路である)
        assertNull(modelCatalogEmptyStateOf(c.state.value))
        val notice = modelCatalogNotice(c.state.value)!!
        assertEquals("catalog-stale", notice.key)
        assertTrue(notice.text.contains("NET boom"))
        assertTrue(notice.text.contains("前回取得"))

        // 取り直せたら帯は消える。
        g.providers = FakeCatalog().providers
        c.reload()
        runCurrent()
        assertFalse(c.state.value.stale)
        assertNull(modelCatalogNotice(c.state.value))
    }

    /** 一度も取れていない状態での失敗は `stale` にしない(空状態が失敗を出す。帯は二重に言わない)。 */
    @Test
    fun `初回の失敗は stale にしない`() = runTest {
        val g = FakeCatalog()
        g.providers = ApiResult.Err(ApiError.Network("boom"))
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        assertFalse(c.state.value.stale)
        assertNotNull("空状態が失敗を出すこと", modelCatalogEmptyStateOf(c.state.value))
        assertNull("帯は出さないこと", modelCatalogNotice(c.state.value))
    }

    /** エージェントだけの失敗も帯には出る(今まで画面に一言も出ていなかった)。 */
    @Test
    fun `エージェント取得の失敗も帯で出す`() = runTest {
        val g = FakeCatalog()
        g.agents = ApiResult.Err(ApiError.Http(500))
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        val notice = modelCatalogNotice(c.state.value)!!
        assertEquals("catalog-error", notice.key)
        assertTrue(notice.text.contains("エージェント"))
    }

    @Test
    fun `検索語は visibleModels にだけ効く`() = runTest {
        val g = FakeCatalog()
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        c.updateQuery("zzz")
        assertEquals(1, c.state.value.models.size)
        assertEquals(0, c.state.value.visibleModels.size)
        c.clearQuery()
        assertEquals(1, c.state.value.visibleModels.size)
    }

    @Test
    fun `接続先未設定なら引かない`() = runTest {
        val g = FakeCatalog(isConfigured = false)
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        assertEquals(0, g.providerCalls)
    }

    // ---- 接続先変更でカタログを捨てる(レビュー major-1) ----

    /**
     * **直す欠陥**: サーバーA でカタログを取り、設定でサーバーB へ替えると
     * シートは **A のモデル一覧**を出した。A のモデルIDを選ぶと **B は 204 で
     * 受理して保存する**(サーバーは値を検証しない。API_CONTRACT.md 実測 #4)ので、
     * 失敗は次の推論まで出ず、画面はどこも壊れて見えない。
     * 契約文書が言う「唯一の防波堤」を、古いカタログはそのまま抜ける。
     */
    @Test
    fun `接続先が変わったらカタログを捨てて引き直す`() = runTest {
        val g = FakeCatalog()
        val c = catalog(g)
        c.onConnectionChanged("http://a:4097")
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, g.providerCalls)
        assertEquals(1, c.state.value.models.size)

        c.onConnectionChanged("http://b:4097")
        assertTrue("捨てること", c.state.value.models.isEmpty())
        assertFalse(c.state.value.loaded)

        c.ensureLoaded()
        runCurrent()
        assertEquals("新しい接続先で引き直すこと", 2, g.providerCalls)
    }

    /** 同じ接続先への再保存(パスワードを入れ直した等)では捨てない。5.4 MiB を無駄に引かない。 */
    @Test
    fun `同じ接続先なら捨てない`() = runTest {
        val g = FakeCatalog()
        val c = catalog(g)
        c.onConnectionChanged("http://a:4097")
        c.ensureLoaded()
        runCurrent()
        c.onConnectionChanged("http://a:4097")
        assertEquals(1, c.state.value.models.size)
        assertTrue(c.state.value.loaded)
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, g.providerCalls)
    }

    /** 初回(まだ何も知らない)は捨てるものが無いので、状態を触らない。 */
    @Test
    fun `初めての接続先通知では何も捨てない`() = runTest {
        val g = FakeCatalog()
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, c.state.value.models.size)
        c.onConnectionChanged("http://a:4097")
        assertEquals("初回通知でキャッシュを落とさない", 1, c.state.value.models.size)
    }

    /** 接続先が消えた(未設定へ戻った)ときも捨てる。前のサーバーの候補を出し続けない。 */
    @Test
    fun `接続先が未設定になったら捨てる`() = runTest {
        val g = FakeCatalog()
        val c = catalog(g)
        c.onConnectionChanged("http://a:4097")
        c.ensureLoaded()
        runCurrent()
        c.onConnectionChanged(null)
        assertTrue(c.state.value.models.isEmpty())
        assertFalse(c.state.value.loaded)
    }

    /** 除外件数も状態に載ること(シートの「N件は表示していません」の出所)。 */
    @Test
    fun `除外件数が状態に載る`() = runTest {
        val g = FakeCatalog()
        g.providers = ApiResult.Ok(
            ProvidersDto(
                all = listOf(
                    ProviderDto(
                        id = "p", name = "P", source = "api",
                        models = mapOf(
                            "ok" to ProviderModelDto(
                                id = "ok", name = "OK",
                                capabilities = ModelCapabilitiesDto(toolcall = true),
                            ),
                            "ng" to ProviderModelDto(
                                id = "ng", name = "NG",
                                capabilities = ModelCapabilitiesDto(toolcall = false),
                            ),
                        ),
                    ),
                ),
                default = emptyMap(),
                connected = listOf("p"),
            ),
        )
        val c = catalog(g)
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, c.state.value.models.size)
        assertEquals(1, c.state.value.excludedModels)
    }
}
