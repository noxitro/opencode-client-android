package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.data.SessionsGateway
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.SESSION_LIMIT_MAX
import dev.opencode.android.ui.SESSION_PAGE_SIZE
import dev.opencode.android.ui.SessionListController
import dev.opencode.android.ui.SessionRunState
import dev.opencode.android.ui.canLoadMoreFrom
import dev.opencode.android.ui.nextSessionLimit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一覧の**状態機械とイベント振り分け**の固定テスト。
 *
 * なぜ後から足したか: Q1 の1周目レビューが変異を3本打ち、**2本が102件全緑のまま
 * 通り抜けた**。`canLoadMore` の `>=` を `>` にしてもページングが1ページ目で止まるだけで
 * テストは緑、一覧イベントの振り分けを早期returnの後ろへ動かしてもテストは緑。
 * どちらも**画面は正常に見えたまま** Q1 の中核が死ぬ。
 * 純関数に切り出せた部分(日付境界・ロールバック)には検出器があったのに、
 * 切り出せなかった部分(通信を伴う状態遷移)だけが素通しだった。
 *
 * [SessionsGateway] を差し替えて `runTest` で直接殴る。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListControllerTest {

    private fun session(id: String, updated: Long = 1000L) =
        SessionDto(id = id, title = "t-$id", time = SessionTimeDto(created = updated, updated = updated))

    /** 呼ばれた `limit` / `search` を全部記録するスタブ。応答は [supply] が決める。 */
    private class FakeGateway(
        override val isConfigured: Boolean = true,
        val supply: (limit: Int?, search: String?) -> ApiResult<List<SessionDto>>,
    ) : SessionsGateway {
        val listCalls = mutableListOf<Pair<Int?, String?>>()
        var statusCalls = 0
        var statusResult: ApiResult<Map<String, SessionStatusDto>> = ApiResult.Ok(emptyMap())
        var deleteResult: ApiResult<Unit> = ApiResult.Ok(Unit)
        val deleted = mutableListOf<String>()
        var renameResult: (String, String) -> ApiResult<SessionDto> = { id, title ->
            ApiResult.Ok(SessionDto(id = id, title = title, time = SessionTimeDto(1000, 1000)))
        }

        override suspend fun listSessions(limit: Int?, search: String?): ApiResult<List<SessionDto>> {
            listCalls += limit to search
            return supply(limit, search)
        }

        override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> {
            statusCalls++
            return statusResult
        }

        /** Q4: 作成時に渡された `agent` / `model` を控える。**未指定は null のまま**であること。 */
        val creates = mutableListOf<Triple<String, String?, ModelRefDto?>>()

        override suspend fun createSession(
            title: String,
            agent: String?,
            model: ModelRefDto?,
        ): ApiResult<SessionDto> {
            creates += Triple(title, agent, model)
            return ApiResult.Ok(
                SessionDto(
                    id = "ses_new",
                    title = title,
                    time = SessionTimeDto(9999, 9999),
                    agent = agent,
                    model = model,
                ),
            )
        }

        override suspend fun renameSession(sessionId: String, title: String): ApiResult<SessionDto> =
            renameResult(sessionId, title)

        override suspend fun deleteSession(sessionId: String): ApiResult<Unit> {
            deleted += sessionId
            return deleteResult
        }
    }

    /** 総件数 [total] を持つサーバーを模す(`limit` で頭から切って返す)。 */
    private fun serverWith(total: Int) = FakeGateway { limit, _ ->
        val all = (1..total).map { session("ses_%04d".format(it), updated = (total - it).toLong()) }
        ApiResult.Ok(all.take(limit ?: 100))
    }

    private fun TestScope.controller(gateway: SessionsGateway) =
        SessionListController(gateway, this, describeError = { e ->
            when (e) {
                is ApiError.Http -> "HTTP ${e.code}"
                is ApiError.Network -> "NET ${e.message}"
                ApiError.NotConfigured -> "NOT_CONFIGURED"
            }
        })

    // ---- canLoadMore の境界(変異M2: `>=` を `>` にすると R2 が未回収へ戻る) ----

    @Test
    fun `応答が limit ちょうどならまだ先があるとみなす`() {
        assertTrue(canLoadMoreFrom(responseSize = 50, limit = 50))
    }

    @Test
    fun `応答が limit 未満なら末尾`() {
        assertFalse(canLoadMoreFrom(responseSize = 49, limit = 50))
        assertFalse(canLoadMoreFrom(responseSize = 0, limit = 50))
    }

    @Test
    fun `limit 上限に達したらそれ以上は追わない`() {
        assertFalse(canLoadMoreFrom(responseSize = SESSION_LIMIT_MAX, limit = SESSION_LIMIT_MAX))
    }

    @Test
    fun `limit は倍々に伸び、上限で止まる`() {
        assertEquals(100, nextSessionLimit(50))
        assertEquals(200, nextSessionLimit(100))
        assertEquals(SESSION_LIMIT_MAX, nextSessionLimit(SESSION_LIMIT_MAX / 2))
        assertEquals(SESSION_LIMIT_MAX, nextSessionLimit(SESSION_LIMIT_MAX))
    }

    @Test
    fun `ちょうど50件のサーバーでも一度は追加取得を試みて末尾に落ち着く`() = runTest {
        // 「size == limit」で止まると1ページ目から進めない。ここが変異M2の当たり所。
        val gateway = serverWith(50)
        val c = controller(gateway)
        c.refresh()
        advanceUntilIdle()
        assertTrue("50件ちょうどでは『まだ先があるかも』でなければならない", c.state.value.canLoadMore)

        c.loadMore()
        advanceUntilIdle()
        assertEquals(50, c.state.value.items.size)
        assertFalse(c.state.value.canLoadMore)
        assertEquals(listOf(SESSION_PAGE_SIZE, 100), gateway.listCalls.map { it.first })
    }

    @Test
    fun `120件のサーバーで末尾まで到達できる(R2)`() = runTest {
        val gateway = serverWith(120)
        val c = controller(gateway)
        c.refresh()
        advanceUntilIdle()
        assertEquals(50, c.state.value.items.size)
        assertTrue(c.state.value.canLoadMore)

        c.loadMore() // limit 100
        advanceUntilIdle()
        assertEquals(100, c.state.value.items.size)
        assertTrue(c.state.value.canLoadMore)

        c.loadMore() // limit 200 -> 120件しか無い
        advanceUntilIdle()
        assertEquals(120, c.state.value.items.size)
        assertFalse(c.state.value.canLoadMore)
        assertEquals("ses_0120", c.state.value.items.last().id)
        assertEquals(listOf(50, 100, 200), gateway.listCalls.map { it.first })
    }

    @Test
    fun `末尾に到達していたら loadMore は通信しない`() = runTest {
        val gateway = serverWith(10)
        val c = controller(gateway)
        c.refresh()
        advanceUntilIdle()
        val calls = gateway.listCalls.size
        c.loadMore()
        c.loadMore()
        advanceUntilIdle()
        assertEquals(calls, gateway.listCalls.size)
    }

    // ---- 回転・再入場でページングを失わない(major-1) ----

    @Test
    fun `ensureLoaded は二度目以降は何もしない(回転で1ページ目へ戻らない)`() = runTest {
        val gateway = serverWith(120)
        val c = controller(gateway)
        c.ensureLoaded()
        advanceUntilIdle()
        c.loadMore()
        advanceUntilIdle()
        val limitAfterPaging = c.state.value.limit
        val callsAfterPaging = gateway.listCalls.size
        assertEquals(100, limitAfterPaging)

        // 回転 / チャットからの復帰 = 画面が作り直されて LaunchedEffect が再実行される
        c.ensureLoaded()
        c.ensureLoaded()
        advanceUntilIdle()

        assertEquals("再表示でGETが増えてはいけない", callsAfterPaging, gateway.listCalls.size)
        assertEquals("再表示で limit が戻ってはいけない", limitAfterPaging, c.state.value.limit)
        assertEquals(100, c.state.value.items.size)
    }

    @Test
    fun `明示的な refresh は1ページ目へ戻す`() = runTest {
        val gateway = serverWith(120)
        val c = controller(gateway)
        c.ensureLoaded(); advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        assertEquals(100, c.state.value.limit)

        c.refresh()
        advanceUntilIdle()
        assertEquals(SESSION_PAGE_SIZE, c.state.value.limit)
    }

    @Test
    fun `作成後の取り直しはページングを維持する`() = runTest {
        val gateway = serverWith(120)
        val c = controller(gateway)
        c.ensureLoaded(); advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        c.create("新規")
        advanceUntilIdle()
        assertEquals(100, c.state.value.limit)
    }

    @Test
    fun `スクロール位置は画面より長生きし、1ページ目へ戻すときだけ捨てる`() = runTest {
        val gateway = serverWith(120)
        val c = controller(gateway)
        c.ensureLoaded(); advanceUntilIdle()
        c.saveScroll(index = 118, offset = 42)

        // チャットへ遷移して戻る = 画面は作り直されるが VM は生きている
        c.ensureLoaded(); advanceUntilIdle()
        assertEquals(118, c.savedScrollIndex)
        assertEquals(42, c.savedScrollOffset)

        // 明示的な取り直しは1ページ目へ戻すので、深い位置の記憶は捨てる
        c.refresh(); advanceUntilIdle()
        assertEquals(0, c.savedScrollIndex)
        assertEquals(0, c.savedScrollOffset)
    }

    // ---- イベント振り分け(変異M3: 一覧イベントが届かなくなる) ----

    private fun event(json: String): SseEvent = requireNotNull(parseSseEnvelope(json))

    @Test
    fun `session-status は一覧が消費してバッジになる`() = runTest {
        val c = controller(serverWith(3))
        c.refresh(); advanceUntilIdle()

        val consumed = c.onEvent(
            event("""{"id":"evt_1","type":"session.status","properties":{"sessionID":"ses_0002","status":{"type":"busy"}}}"""),
        )
        assertTrue(consumed)
        assertEquals(SessionRunState.BUSY, c.state.value.runStates["ses_0002"])

        c.onEvent(
            event("""{"id":"evt_2","type":"session.status","properties":{"sessionID":"ses_0002","status":{"type":"idle"}}}"""),
        )
        // idle は「キーごと消える」。GET /session/status が idle を返さない形と揃える。
        assertNull(c.state.value.runStates["ses_0002"])
        assertEquals(SessionRunState.IDLE, c.state.value.runStates["ses_0002"] ?: SessionRunState.IDLE)
    }

    @Test
    fun `session-updated は一覧が消費してタイトルを差し替える`() = runTest {
        val c = controller(serverWith(3))
        c.refresh(); advanceUntilIdle()
        val consumed = c.onEvent(
            event(
                """{"id":"evt_1","type":"session.updated","properties":{"sessionID":"ses_0002",
                   "info":{"id":"ses_0002","slug":"s","projectID":"p","directory":"/d",
                           "title":"自動タイトル","version":"1.18.21","time":{"created":1,"updated":1}}}}""",
            ),
        )
        assertTrue(consumed)
        assertEquals("自動タイトル", c.state.value.items.first { it.id == "ses_0002" }.title)
    }

    @Test
    fun `session-deleted は一覧から消す`() = runTest {
        val c = controller(serverWith(3))
        c.refresh(); advanceUntilIdle()
        assertTrue(c.onEvent(event("""{"id":"e","type":"session.deleted","properties":{"sessionID":"ses_0002"}}""")))
        assertEquals(listOf("ses_0001", "ses_0003"), c.state.value.items.map { it.id })
    }

    @Test
    fun `チャット側のイベントは一覧が触らない(P3-P4の経路を奪わない)`() = runTest {
        val c = controller(serverWith(3))
        c.refresh(); advanceUntilIdle()
        val before = c.state.value

        val chatEvents = listOf(
            event("""{"id":"e","type":"message.part.updated","properties":{"sessionID":"ses_0001","part":{"id":"prt_1","sessionID":"ses_0001","messageID":"msg_1","type":"text","text":"x"}}}"""),
            event("""{"id":"e","type":"message.updated","properties":{"sessionID":"ses_0001","info":{"id":"msg_1","sessionID":"ses_0001","role":"assistant"}}}"""),
            event("""{"id":"e","type":"session.idle","properties":{"sessionID":"ses_0001"}}"""),
            event("""{"id":"e","type":"session.error","properties":{"sessionID":"ses_0001","error":{"name":"APIError","data":{"message":"boom"}}}}"""),
            event("""{"id":"e","type":"permission.asked","properties":{"id":"per_1","sessionID":"ses_0001","permission":"bash","patterns":[],"metadata":{"command":"ls"},"always":[]}}"""),
            event("""{"id":"e","type":"permission.replied","properties":{"id":"per_1","sessionID":"ses_0001","response":"once"}}"""),
            event("""{"id":"e","type":"server.connected","properties":{}}"""),
            event("""{"id":"e","type":"stub.unknown_event","properties":{}}"""),
        )
        for (e in chatEvents) {
            assertFalse("一覧が $e を消費してはいけない", c.onEvent(e))
        }
        assertEquals(before, c.state.value)
    }

    // ---- 削除の楽観更新とロールバック(minor-4: 飛行中のイベントで位置がずれない) ----

    @Test
    fun `削除は先に消し、成功なら戻らない`() = runTest {
        val gateway = serverWith(3)
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()
        c.delete("ses_0002")
        assertEquals("応答を待たずに消える", listOf("ses_0001", "ses_0003"), c.state.value.items.map { it.id })
        advanceUntilIdle()
        assertEquals(listOf("ses_0002"), gateway.deleted)
        assertEquals(listOf("ses_0001", "ses_0003"), c.state.value.items.map { it.id })
        assertNull(c.state.value.pendingActionId)
    }

    @Test
    fun `削除が失敗したら元の位置へ戻る`() = runTest {
        val gateway = serverWith(3)
        gateway.deleteResult = ApiResult.Err(ApiError.Http(500))
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()
        c.delete("ses_0002")
        advanceUntilIdle()
        assertEquals(listOf("ses_0001", "ses_0002", "ses_0003"), c.state.value.items.map { it.id })
        assertEquals("削除に失敗しました: HTTP 500", c.state.value.actionError)
    }

    @Test
    fun `飛行中に新規セッションが挿さってもロールバック位置がずれない`() = runTest {
        val gateway = serverWith(3)
        gateway.deleteResult = ApiResult.Err(ApiError.Http(500))
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()

        c.delete("ses_0002")
        // DELETE の応答が返る前に session.created が届いて先頭に1件挿さる。
        // 添字で戻す実装だと ses_0002 は1つ後ろ(ses_0003 の後)に生き返る。
        c.onEvent(
            event(
                """{"id":"e","type":"session.created","properties":{"sessionID":"ses_new",
                   "info":{"id":"ses_new","slug":"s","projectID":"p","directory":"/d","title":"新着",
                           "version":"1.18.21","time":{"created":99999,"updated":99999}}}}""",
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("ses_new", "ses_0001", "ses_0002", "ses_0003"),
            c.state.value.items.map { it.id },
        )
    }

    @Test
    fun `削除中は二重に削除しない`() = runTest {
        val gateway = serverWith(3)
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()
        c.delete("ses_0002")
        c.delete("ses_0003")
        advanceUntilIdle()
        assertEquals(listOf("ses_0002"), gateway.deleted)
    }

    // ---- 検索 ----

    @Test
    fun `検索はデバウンスされ、最後の1回だけ通信する`() = runTest {
        val gateway = FakeGateway { limit, search ->
            ApiResult.Ok(if (search == null) List(3) { session("ses_000${it + 1}") } else listOf(session("ses_0002")))
        }
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()
        val baseline = gateway.listCalls.size

        c.updateSearch("a")
        advanceTimeBy(100)
        c.updateSearch("ab")
        advanceTimeBy(100)
        c.updateSearch("abc")
        advanceUntilIdle()

        assertEquals(baseline + 1, gateway.listCalls.size)
        assertEquals("abc", gateway.listCalls.last().second)
        assertEquals(listOf("ses_0002"), c.state.value.items.map { it.id })
    }

    @Test
    fun `検索を閉じると全件へ戻す`() = runTest {
        val gateway = FakeGateway { _, search ->
            ApiResult.Ok(if (search == null) List(3) { session("ses_000${it + 1}") } else listOf(session("ses_0002")))
        }
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()
        c.setSearchOpen(true)
        c.updateSearch("abc")
        advanceUntilIdle()
        assertEquals(1, c.state.value.items.size)

        c.setSearchOpen(false)
        advanceUntilIdle()
        assertEquals("", c.state.value.search)
        assertEquals(3, c.state.value.items.size)
        assertNull(gateway.listCalls.last().second)
    }

    @Test
    fun `検索中は session-created を差し込まない`() = runTest {
        val gateway = FakeGateway { _, _ -> ApiResult.Ok(listOf(session("ses_0002"))) }
        val c = controller(gateway)
        c.setSearchOpen(true)
        c.updateSearch("abc")
        advanceUntilIdle()
        c.onEvent(
            event(
                """{"id":"e","type":"session.created","properties":{"sessionID":"ses_new",
                   "info":{"id":"ses_new","slug":"s","projectID":"p","directory":"/d","title":"新着",
                           "version":"1.18.21","time":{"created":99999,"updated":99999}}}}""",
            ),
        )
        assertEquals(listOf("ses_0002"), c.state.value.items.map { it.id })
    }

    // ---- 状態の初期化 ----

    @Test
    fun `refresh は GET session-status も撃つ(状態はサーバーの現在値で初期化する)`() = runTest {
        val gateway = serverWith(3)
        gateway.statusResult = ApiResult.Ok(mapOf("ses_0002" to SessionStatusDto(type = "busy")))
        val c = controller(gateway)
        c.refresh()
        advanceUntilIdle()
        assertEquals(1, gateway.statusCalls)
        assertEquals(SessionRunState.BUSY, c.state.value.runStates["ses_0002"])
    }

    @Test
    fun `status の取り直しは置換(古い busy を残さない)`() = runTest {
        val gateway = serverWith(3)
        gateway.statusResult = ApiResult.Ok(mapOf("ses_0002" to SessionStatusDto(type = "busy")))
        val c = controller(gateway)
        c.refreshStatus(); advanceUntilIdle()
        assertEquals(SessionRunState.BUSY, c.state.value.runStates["ses_0002"])

        // 切れている間に idle へ落ちた: サーバーはもうキーを返さない
        gateway.statusResult = ApiResult.Ok(emptyMap())
        c.refreshStatus(); advanceUntilIdle()
        assertTrue(c.state.value.runStates.isEmpty())
    }

    @Test
    fun `status の取得に失敗しても一覧は壊さない`() = runTest {
        val gateway = serverWith(3)
        gateway.statusResult = ApiResult.Err(ApiError.Network("down"))
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()
        assertEquals(3, c.state.value.items.size)
        assertNull(c.state.value.error)
        assertTrue(c.state.value.runStates.isEmpty())
    }

    // ---- 未設定 ----

    @Test
    fun `接続先が未設定なら何も撃たない`() = runTest {
        val gateway = FakeGateway(isConfigured = false) { _, _ -> ApiResult.Ok(emptyList()) }
        val c = controller(gateway)
        c.ensureLoaded()
        c.refresh()
        c.loadMore()
        c.refreshStatus()
        c.delete("ses_0001")
        advanceUntilIdle()
        assertEquals(0, gateway.listCalls.size)
        assertEquals(0, gateway.statusCalls)
    }

    // ---- 改名 ----

    @Test
    fun `改名は返却Sessionで置き換える(イベントを待たない)`() = runTest {
        val gateway = serverWith(3)
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()
        c.rename("ses_0002", "  新しい名前  ")
        advanceUntilIdle()
        assertEquals("新しい名前", c.state.value.items.first { it.id == "ses_0002" }.title)
        assertNull(c.state.value.pendingActionId)
    }

    @Test
    fun `改名の失敗は状態に出る`() = runTest {
        val gateway = serverWith(3)
        gateway.renameResult = { _, _ -> ApiResult.Err(ApiError.Http(404)) }
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()
        c.rename("ses_0002", "x")
        advanceUntilIdle()
        assertEquals("改名に失敗しました: HTTP 404", c.state.value.actionError)
    }

    @Test
    fun `空文字への改名は撃たない`() = runTest {
        val gateway = serverWith(3)
        var called = false
        gateway.renameResult = { id, title -> called = true; ApiResult.Ok(session(id)) }
        val c = controller(gateway)
        c.refresh(); advanceUntilIdle()
        c.rename("ses_0002", "   ")
        advanceUntilIdle()
        assertFalse(called)
    }

    // ---- Q4: 作成時のモデル/エージェント指定(レビュー major-3) ----
    //
    // **偽物ゲートウェイに `creates` を作っておきながら、それを見るテストが1本も無かった。**
    // レビューの変異 R1(`createSession(title, agent, model)` -> `(title, null, null)`)は
    // 385件全緑で通過した。症状は「選択の表示は正しいのに、送られるのは既定」——
    // 返却された Session にも既定が入るので**一覧にもピルにも矛盾が出ず**、
    // ユーザーの選択が尊重されたかを画面から判別する手段が無い。
    // Q4 スコープ2 の唯一の通し経路なので、ここに検出器を置く。

    @Test
    fun `作成はモデルとエージェントをゲートウェイまで通す`() = runTest {
        val gateway = serverWith(3)
        val c = controller(gateway)
        c.create("Q4", agent = "plan", model = ModelRefDto(id = "mistral-medium-latest", providerID = "mistral"))
        advanceUntilIdle()
        assertEquals(
            listOf(Triple("Q4", "plan", ModelRefDto(id = "mistral-medium-latest", providerID = "mistral"))),
            gateway.creates,
        )
    }

    /**
     * **未指定は null のまま通す**(`explicitNulls = false` がキーごと落とす)。
     * ここで空文字や「既定」という文字列に化けさせると、`{"agent":""}` が送られて
     * 400 BadRequest になる —— 実測で `{"agent":null}` が 400 だったのと同じ入口である。
     */
    @Test
    fun `未指定の作成は agent も model も null で通す`() = runTest {
        val gateway = serverWith(3)
        val c = controller(gateway)
        c.create("Q4-default")
        advanceUntilIdle()
        assertEquals(listOf(Triple("Q4-default", null, null)), gateway.creates)
    }

    /**
     * **観測メモ(Q4 では直さない)**: `create` の二重POSTガードは
     * `_state.value.creating` を `scope.launch` の**外**で読み、`creating = true` を
     * **中**で立てるので、同一フレームの2連打は2件とも送られる(`runTest` で実測)。
     * Q1 由来の既存挙動で、Q4 の引数追加とは独立している。ここでは主張しない ——
     * **存在しない振る舞いをテストで固定しない**ため、テストごと置かずに記録に留める。
     */
}
