package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ChatGateway
import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.MessageInfoDto
import dev.opencode.android.data.PartDto
import dev.opencode.android.data.QuestionInfoDto
import dev.opencode.android.data.QuestionOptionDto
import dev.opencode.android.data.PermissionRequestDto
import dev.opencode.android.data.QuestionRequestDto
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.TodoDto
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.ChatBannerKind
import dev.opencode.android.ui.ChatController
import dev.opencode.android.ui.ChatEventStatus
import dev.opencode.android.ui.ChatUi
import dev.opencode.android.ui.QuestionCardState
import dev.opencode.android.ui.QuestionResolution
import dev.opencode.android.ui.TodoStatus
import dev.opencode.android.ui.canSend
import dev.opencode.android.ui.canSubmitQuestion
import dev.opencode.android.ui.isAnswerable
import dev.opencode.android.ui.isInteractive
import dev.opencode.android.ui.selectChatBanner
import dev.opencode.android.ui.todoProgressText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * チャットの**状態機械**の固定テスト(Q2)。
 *
 * なぜここに置くか: RUN_PLAN「検出器の穴という欠陥形」。Q1 のレビューが打った変異2本は
 * 102件全緑のまま通り抜け、**壊れた症状はどちらも「画面は正常に見える」**だった。
 * 純関数に切り出せない部分 —— busy の立ち下がり / abort 後の復帰 / バナーの選択 /
 * permission ダイアログの寿命 —— は、[ChatGateway] を差し替えて `runTest` で直接殴る。
 *
 * **フィクスチャは spec の required を満たす実データの形**にしてある(近似した文字列を流さない)。
 * SSE は文字列の envelope から [parseSseEnvelope] を通す —— 途中でイベントが `Ignored` に
 * 落ちる欠陥(L3、P4)は、DTO を直接組み立てるテストでは絶対に見つからない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {

    private class FakeChatGateway(
        override val isConfigured: Boolean = true,
    ) : ChatGateway {
        var messages: ApiResult<List<MessageEntryDto>> = ApiResult.Ok(emptyList())
        var statusResult: ApiResult<Map<String, SessionStatusDto>> = ApiResult.Ok(emptyMap())
        var statusCalls = 0
        var promptResult: ApiResult<Unit> = ApiResult.Ok(Unit)
        var abortResult: ApiResult<Unit> = ApiResult.Ok(Unit)
        val prompts = mutableListOf<Pair<String, String>>()
        val aborts = mutableListOf<String>()
        val permissionReplies = mutableListOf<Triple<String, String, String>>()

        override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> {
            statusCalls++
            return statusResult
        }

        override suspend fun listMessages(sessionId: String) = messages

        /** 非nullなら sendPrompt はここが完了するまで待つ(往復中の状態を作るため)。 */
        var promptGate: CompletableDeferred<Unit>? = null

        override suspend fun sendPrompt(sessionId: String, text: String): ApiResult<Unit> {
            prompts += sessionId to text
            promptGate?.await()
            return promptResult
        }

        override suspend fun replyPermission(sessionId: String, permissionId: String, response: String): ApiResult<Unit> {
            permissionReplies += Triple(sessionId, permissionId, response)
            return ApiResult.Ok(Unit)
        }

        override suspend fun abortSession(sessionId: String): ApiResult<Unit> {
            aborts += sessionId
            return abortResult
        }

        // ---- Q3 ----

        var todosResult: ApiResult<List<TodoDto>> = ApiResult.Ok(emptyList())
        var todoCalls = 0
        var questionsResult: ApiResult<List<QuestionRequestDto>> = ApiResult.Ok(emptyList())
        var questionCalls = 0
        var replyQuestionResult: ApiResult<Unit> = ApiResult.Ok(Unit)
        var rejectQuestionResult: ApiResult<Unit> = ApiResult.Ok(Unit)

        /** 送られた `answers` をそのまま控える。**形の同一性を数値で主張する対象**。 */
        val questionReplies = mutableListOf<Pair<String, List<List<String>>>>()
        val questionRejects = mutableListOf<String>()

        override suspend fun sessionTodos(sessionId: String): ApiResult<List<TodoDto>> {
            todoCalls++
            return todosResult
        }

        override suspend fun pendingQuestions(): ApiResult<List<QuestionRequestDto>> {
            questionCalls++
            return questionsResult
        }

        // ---- Q6(申し送り Q5-2): permission もサーバーを権威にする ----

        var permissionsResult: ApiResult<List<PermissionRequestDto>> = ApiResult.Ok(emptyList())
        var permissionCalls = 0

        override suspend fun pendingPermissions(): ApiResult<List<PermissionRequestDto>> {
            permissionCalls++
            return permissionsResult
        }

        /** 非nullなら replyQuestion はここが完了するまで待つ(往復中の状態を作るため)。 */
        var questionReplyGate: CompletableDeferred<Unit>? = null

        override suspend fun replyQuestion(requestId: String, answers: List<List<String>>): ApiResult<Unit> {
            questionReplies += requestId to answers
            questionReplyGate?.await()
            return replyQuestionResult
        }

        override suspend fun rejectQuestion(requestId: String): ApiResult<Unit> {
            questionRejects += requestId
            return rejectQuestionResult
        }

        // ---- Q4 ----

        /** `GET /session/{id}` の応答。**モデル/エージェントの権威**。 */
        var sessionResult: ApiResult<SessionDto> =
            ApiResult.Ok(SessionDto(id = "ses_x", title = "t", time = SessionTimeDto(1, 1)))
        var sessionCalls = 0
        var switchModelResult: ApiResult<Unit> = ApiResult.Ok(Unit)

        /** 送られた `ModelRef` をそのまま控える。**キー名の取り違えを数値で主張する対象**。 */
        val switchedModels = mutableListOf<Pair<String, ModelRefDto>>()

        override suspend fun getSession(sessionId: String): ApiResult<SessionDto> {
            sessionCalls++
            return sessionResult
        }

        override suspend fun switchSessionModel(sessionId: String, model: ModelRefDto): ApiResult<Unit> {
            switchedModels += sessionId to model
            return switchModelResult
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

    private val sid = "ses_fc0247634ffeGddA7Mw2hp2KCh"

    private fun TestScope.controller(
        gateway: FakeChatGateway,
        abortGraceMs: Long = 5_000L,
    ) = ChatController(
        gateway = gateway,
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        describeError = { e ->
            when (e) {
                is ApiError.Http -> "HTTP ${e.code}"
                is ApiError.Network -> "NET ${e.message}"
                ApiError.NotConfigured -> "NOTCONF"
            }
        },
        nowMs = { 1_787_785_000_000L },
        abortGraceMs = abortGraceMs,
    )

    // ---------- SSE フィクスチャ(spec の required を満たす形) ----------

    /** `session.idle` — properties required: `{sessionID}`。 */
    private fun idleFrame(session: String = sid) =
        """{"id":"evt_q2idle","type":"session.idle","properties":{"sessionID":"$session"}}"""

    /**
     * `session.status` retry — spec `SessionStatus` の anyOf 2番目。
     * required: `type` `attempt` `message` `next`。`action` は任意だが、
     * **あるなら** `reason` `provider` `title` `message` `label` が required。
     */
    private fun retryFrame(
        session: String = sid,
        attempt: Int = 2,
        next: Long = 1_787_785_030_000L,
        withAction: Boolean = true,
    ): String {
        val action = if (withAction) {
            ""","action":{"reason":"rate_limit","provider":"anthropic",""" +
                """"title":"レート制限","message":"しばらく待ってから再試行します","label":"設定を開く","link":"https://example.invalid/x"}"""
        } else {
            ""
        }
        return """{"id":"evt_q2retry","type":"session.status","properties":{"sessionID":"$session",""" +
            """"status":{"type":"retry","attempt":$attempt,"message":"provider retry","next":$next$action}}}"""
    }

    private fun statusFrame(session: String = sid, type: String) =
        """{"id":"evt_q2st","type":"session.status","properties":{"sessionID":"$session","status":{"type":"$type"}}}"""

    /**
     * `session.deleted` — properties required: `{sessionID, info: Session}`。
     * `info` は**削除前**の Session(API_CONTRACT.md 実測)。
     */
    private fun deletedFrame(session: String = sid, title: String = "消える前のタイトル") =
        """{"id":"evt_q2del","type":"session.deleted","properties":{"sessionID":"$session",""" +
            """"info":{"id":"$session","slug":"stellar-sailor","projectID":"c1316","directory":"E:/x","path":"",""" +
            """"title":"$title","version":"1.18.21","cost":0,""" +
            """"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},""" +
            """"time":{"created":1787777747403,"updated":1787777747403}}}}"""

    private fun updatedFrame(session: String = sid, title: String) =
        """{"id":"evt_q2upd","type":"session.updated","properties":{"sessionID":"$session",""" +
            """"info":{"id":"$session","title":"$title","version":"1.18.21",""" +
            """"time":{"created":1787777747403,"updated":1787777747403}}}}"""

    /** `permission.asked` — 実測フレーム(metadata はオブジェクト)。 */
    private fun permissionAskedFrame(session: String = sid, id: String = "per_1") =
        """{"id":"evt_q2perm","type":"permission.asked","properties":{"id":"$id","sessionID":"$session",""" +
            """"permission":"bash","patterns":["rm -rf *"],"metadata":{"command":"rm -rf /tmp/x"},"always":[],""" +
            """"tool":{"messageID":"msg_a2","callID":"call_per_1"}}}"""

    /** `session.error` — 実物 serve 1.18.21 が実際に流した形(2026-08-27 の probe 記録)。 */
    private fun errorFrame(session: String = sid) =
        """{"id":"evt_q2err","type":"session.error","properties":{"sessionID":"$session",""" +
            """"error":{"name":"APIError","data":{"message":"Payment Required","statusCode":402,"isRetryable":false}}}}"""

    private fun feed(controller: ChatController, frame: String) {
        val event = parseSseEnvelope(frame)
        assertNotNull("フィクスチャがパースできていない(検出器自身の校正): $frame", event)
        // Ignored に落ちていたら、そのテストは何も検査していない。先にそれを落とす。
        assertFalse("フィクスチャが Ignored に落ちた: $event", event is SseEvent.Ignored)
        controller.onEvent(event!!)
    }

    private fun busyController(gateway: FakeChatGateway, scope: TestScope, grace: Long = 5_000L): ChatController {
        val c = scope.controller(gateway, grace)
        c.openChat(sid, title = "元のタイトル")
        scope.advanceUntilIdle()
        c.updateDraft("こんにちは")
        c.sendDraft()
        scope.advanceUntilIdle()
        return c
    }

    // ---------- busy の立ち上がり/立ち下がり ----------

    @Test
    fun `送信でbusyになり入力欄が閉じる`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        assertTrue(c.state.value.busy)
        assertFalse(canSend(c.state.value))
        assertEquals(1, gw.prompts.size)
        assertEquals(sid to "こんにちは", gw.prompts[0])
        // 送信済みの下書きは消える
        assertEquals("", c.state.value.draft)
    }

    @Test
    fun `session_idle でbusyが降りる`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, idleFrame())
        assertFalse(c.state.value.busy)
        assertTrue(canSend(c.state.value))
    }

    @Test
    fun `他セッションの session_idle ではbusyを降ろさない`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, idleFrame(session = "ses_someone_else"))
        assertTrue("別セッションのidleで復帰してはいけない", c.state.value.busy)
    }

    @Test
    fun `session_status idle でもbusyが降りる`() = runTest {
        // 実物 serve は session.status{idle} と session.idle を**両方**流す(2026-08-27 実測)。
        // 片方しか効かない実装だと、片方しか流さない経路で永久に busy が残る。
        val c = busyController(FakeChatGateway(), this)
        feed(c, statusFrame(type = "idle"))
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `session_error でbusyが降り承認ダイアログも畳まれる`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, permissionAskedFrame())
        assertNotNull(c.state.value.permissionDialog)
        feed(c, errorFrame())
        assertFalse(c.state.value.busy)
        assertNull("失敗した実行の承認要求は応答先が無い", c.state.value.permissionDialog)
        // 形が読めるので、汎用文言ではなく実際のメッセージが出ること(L3 欠陥Gの再発検出)
        assertEquals("Payment Required", c.state.value.sessionError)
    }

    // ---------- abort 後の復帰(申し送り Q0-1) ----------

    @Test
    fun `abortでidleが来れば猶予を待たずに復帰し承認ダイアログも畳む`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, permissionAskedFrame())
        c.abortSession()
        // **advanceUntilIdle は使わない**: 見張りの delay まで進めてしまい、
        // 「idle が来たから復帰した」のか「猶予切れで復帰した」のか区別できなくなる。
        runCurrent()
        assertEquals(listOf(sid), gw.aborts)
        assertTrue("idle が来るまでは中断中", c.state.value.aborting)
        assertTrue(c.state.value.busy)

        // 実物 serve は abort で session.idle を流す(2026-08-27 実測)
        feed(c, idleFrame())
        assertFalse(c.state.value.busy)
        assertFalse(c.state.value.aborting)
        assertNull("abort しても permission.replied は流れない。ここで畳む", c.state.value.permissionDialog)
        assertNull("正常に復帰したので注記は出さない", c.state.value.abortNotice)
    }

    @Test
    fun `abortしてもidleが来なければ猶予後に復帰し注記を出す`() = runTest {
        // e2e-stub は abort でシーケンスを黙って止めるだけで何も流さない。
        // 申し送り Q0-1 が観測したのはこの経路(サーバーは止まっているのにUIが抱え込む)。
        val gw = FakeChatGateway()
        val c = busyController(gw, this, grace = 1_000L)
        feed(c, permissionAskedFrame())
        c.abortSession()
        runCurrent()

        advanceTimeBy(999)
        runCurrent()
        assertTrue("猶予中はまだ抱えている", c.state.value.busy)

        advanceTimeBy(2)
        runCurrent()
        assertFalse("猶予を過ぎたら画面側で復帰させる", c.state.value.busy)
        assertNull(c.state.value.permissionDialog)
        assertEquals(
            "中断しました(サーバーからの完了通知は届いていません)",
            c.state.value.abortNotice,
        )
    }

    @Test
    fun `abort失敗ならbusyを抱えたまま失敗を出す`() = runTest {
        val gw = FakeChatGateway()
        gw.abortResult = ApiResult.Err(ApiError.Http(500))
        val c = busyController(gw, this, grace = 1_000L)
        c.abortSession()
        advanceUntilIdle()
        assertTrue("abort が届いていないのに復帰させない", c.state.value.busy)
        assertFalse(c.state.value.aborting)
        assertEquals("中断に失敗しました: HTTP 500", c.state.value.sendError)

        // 見張りは起動していない(起動していると、失敗したのに勝手に復帰する)
        advanceTimeBy(5_000)
        advanceUntilIdle()
        assertTrue(c.state.value.busy)
        assertNull(c.state.value.abortNotice)
    }

    @Test
    fun `busyでなければabortを送らない`() = runTest {
        val gw = FakeChatGateway()
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        c.abortSession()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), gw.aborts)
    }

    // ---------- retry(§5 Q2 スコープ6) ----------

    @Test
    fun `retry状態を受けるとバナーがretryになりidleで消える`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, retryFrame(attempt = 2, next = 1_787_785_030_000L))
        val retry = c.state.value.retry
        assertNotNull(retry)
        assertEquals(2, retry!!.attempt)
        assertEquals(1_787_785_030_000L, retry.next)
        // action(任意)の required 5項目が読めていること
        assertEquals("レート制限", retry.actionTitle)
        assertEquals("設定を開く", retry.actionLabel)
        assertTrue("retry の間は実行中のまま", c.state.value.busy)

        val banner = selectChatBanner(c.state.value, nowMs = 1_787_785_000_000L)
        assertEquals(ChatBannerKind.RETRY, banner?.kind)
        assertEquals("30秒後に再試行(2回目): レート制限", banner?.text)

        feed(c, idleFrame())
        assertNull(c.state.value.retry)
        assertNull(selectChatBanner(c.state.value, nowMs = 1_787_785_000_000L))
    }

    @Test
    fun `action無しのretryでも落ちずにmessageを使う`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, retryFrame(withAction = false, attempt = 1, next = 1_787_785_005_000L))
        val banner = selectChatBanner(c.state.value, nowMs = 1_787_785_000_000L)
        assertEquals(ChatBannerKind.RETRY, banner?.kind)
        assertEquals("5秒後に再試行(1回目): provider retry", banner?.text)
    }

    @Test
    fun `busy状態を受けてもretryは残さない`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, retryFrame())
        assertNotNull(c.state.value.retry)
        feed(c, statusFrame(type = "busy"))
        assertNull("busy へ移ったら「再試行中」を出し続けない", c.state.value.retry)
        assertTrue(c.state.value.busy)
    }

    @Test
    fun `他セッションのstatusはチャットに効かない`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, retryFrame(session = "ses_other"))
        assertNull(c.state.value.retry)
    }

    // ---------- session.deleted(申し送り Q1-1) ----------

    @Test
    fun `session_deleted でタイトルが残り送信が止まる`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, deletedFrame(title = "消える前のタイトル"))

        assertTrue(c.state.value.sessionDeleted)
        assertFalse("消えたセッションへ送れてはいけない", canSend(c.state.value))
        assertFalse(c.state.value.busy)
        assertEquals("生のIDへ退化させない", "消える前のタイトル", c.state.value.title)

        val banner = selectChatBanner(c.state.value, nowMs = 0L)
        assertEquals(ChatBannerKind.SESSION_DELETED, banner?.kind)
        assertEquals("一覧へ戻る", banner?.actionLabel)

        // 送信導線が生きていないこと(押しても prompt が飛ばない)
        c.updateDraft("まだ打てる?")
        c.sendDraft()
        advanceUntilIdle()
        assertEquals("削除後に prompt を投げない", 1, gw.prompts.size)
    }

    @Test
    fun `session_updated でタイトルが追随する`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, updatedFrame(title = "自動で付いたタイトル"))
        assertEquals("自動で付いたタイトル", c.state.value.title)
        assertFalse(c.state.value.sessionDeleted)
    }

    @Test
    fun `他セッションのdeletedでは反応しない`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, deletedFrame(session = "ses_other"))
        assertFalse(c.state.value.sessionDeleted)
        assertEquals("元のタイトル", c.state.value.title)
    }

    // ---------- 画面外のイベント ----------

    @Test
    fun `チャットを閉じている間はイベントを一切適用しない`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        c.closeChat()
        feed(c, deletedFrame())
        feed(c, retryFrame())
        feed(c, idleFrame())
        assertFalse(c.state.value.sessionDeleted)
        assertNull(c.state.value.retry)
        assertTrue("退室後に状態を書き換えない", c.state.value.busy)
    }

    // ---------- 逐次描画(P3の退行検出をコントローラ経由でも持つ) ----------

    @Test
    fun `同一partIdのチャンクが同じバブルへ累積する`() = runTest {
        val gw = FakeChatGateway()
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        var acc = ""
        listOf("チャンク1", "チャンク2", "チャンク3").forEach { chunk ->
            acc += chunk
            feed(
                c,
                """{"id":"evt_p","type":"message.part.updated","properties":{"sessionID":"$sid",""" +
                    """"part":{"id":"prt_a2","sessionID":"$sid","messageID":"msg_a2","type":"text","text":"$acc"},""" +
                    """"time":{"start":1}}}""",
            )
        }
        assertEquals(1, c.state.value.messages.size)
        assertEquals(1, c.state.value.messages[0].parts.size)
        assertEquals("チャンク1チャンク2チャンク3", c.state.value.messages[0].parts[0].text)
    }

    /**
     * 申し送り Q1-2 のトリアージ結果を**実行可能な形で残す**。
     *
     * 症状: 「チャットを開いたままスタブを再起動し、直後に送信するとユーザー発言が二重描画される」。
     * 原因は再接続ではなく **messageID の再利用**である。`e2e-stub` の `msgSeq` はモジュール読み込み時に
     * 0 へ戻るので、再起動後の1通目はまた `msg_u1` になる。アプリのメモリにはまだ前の `msg_u1` が
     * 残っているため、新しいユーザーエコーの part は**既存の別メッセージへ合流**し、
     * ローカルエコーはそのまま残る = 同じ発言が2か所に出る。
     *
     * **実サーバーは messageID を再利用しない**ので、この経路は実サーバーでは踏めない。
     * 実機での確認(2026-08-27、証跡 `e2e-artifacts/Q2/`):
     *  - 既定シードで再起動 → `second` が2回出る(dump-19)
     *  - `STUB_MSG_SEQ_SEED=5000` で再起動 → `third` は**1回**(dump-20)
     *  - 再起動せず `POST /__stub/drop-events` でSSEだけ切る(=実サーバーの再接続) → `fourth` は**1回**(dump-21)
     *
     * よって「アプリの欠陥ではない」。ここではその判断の根拠になっている振る舞い
     * ——「既存 messageID への part は既存メッセージへ合流する」——を固定しておく。
     * これは**正しい**振る舞いであり(ストリーミング中の追記がそれで成り立っている)、
     * 変えるとP3の逐次描画が壊れる。
     */
    @Test
    fun `既存messageIDへのpartは既存メッセージへ合流する_Q1-2の機構`() = runTest {
        val gw = FakeChatGateway()
        gw.messages = ApiResult.Ok(
            listOf(
                MessageEntryDto(
                    info = MessageInfoDto(id = "msg_u1", sessionID = sid, role = "user"),
                    parts = listOf(PartDto(id = "prt_msg_u1", sessionID = sid, messageID = "msg_u1", type = "text", text = "前の発言")),
                ),
            ),
        )
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()

        c.updateDraft("新しい発言")
        c.sendDraft()
        advanceUntilIdle()
        assertEquals(2, c.state.value.messages.size) // 履歴1件 + ローカルエコー

        // 再起動したスタブが **同じ messageID** でユーザーエコーを流す
        feed(
            c,
            """{"id":"evt_dup","type":"message.part.updated","properties":{"sessionID":"$sid",""" +
                """"part":{"id":"prt_msg_u1b","sessionID":"$sid","messageID":"msg_u1","type":"text","text":"新しい発言"},""" +
                """"time":{"start":1}}}""",
        )

        // 既存の msg_u1 へ合流し、ローカルエコーは別のまま残る = 「新しい発言」が2か所に出る
        val texts = c.state.value.messages.flatMap { m -> m.parts.map { it.text } }
        assertEquals(2, c.state.value.messages.size)
        assertEquals(listOf("前の発言", "新しい発言", "新しい発言"), texts)
    }

    @Test
    fun `messageIDが再利用されなければ二重描画しない_Q1-2の対照`() = runTest {
        val gw = FakeChatGateway()
        gw.messages = ApiResult.Ok(
            listOf(
                MessageEntryDto(
                    info = MessageInfoDto(id = "msg_u1", sessionID = sid, role = "user"),
                    parts = listOf(PartDto(id = "prt_msg_u1", sessionID = sid, messageID = "msg_u1", type = "text", text = "前の発言")),
                ),
            ),
        )
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        c.updateDraft("新しい発言")
        c.sendDraft()
        advanceUntilIdle()

        // 実サーバー相当: 新しい messageID
        feed(
            c,
            """{"id":"evt_ok","type":"message.part.updated","properties":{"sessionID":"$sid",""" +
                """"part":{"id":"prt_msg_u9","sessionID":"$sid","messageID":"msg_u9","type":"text","text":"新しい発言"},""" +
                """"time":{"start":1}}}""",
        )

        val texts = c.state.value.messages.flatMap { m -> m.parts.map { it.text } }
        assertEquals(listOf("前の発言", "新しい発言"), texts)
    }

    @Test
    fun `履歴取得の失敗は本文を消さずエラーとして出る`() = runTest {
        val gw = FakeChatGateway()
        gw.messages = ApiResult.Err(ApiError.Http(401))
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        assertEquals("HTTP 401", c.state.value.error)
        assertFalse(c.state.value.loading)
    }

    @Test
    fun `履歴は info と parts から組み立てる`() = runTest {
        val gw = FakeChatGateway()
        gw.messages = ApiResult.Ok(
            listOf(
                MessageEntryDto(
                    info = MessageInfoDto(id = "msg_h1", sessionID = sid, role = "user"),
                    parts = listOf(PartDto(id = "prt_h1", sessionID = sid, messageID = "msg_h1", type = "text", text = "質問")),
                ),
            ),
        )
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        assertEquals(1, c.state.value.messages.size)
        assertEquals("user", c.state.value.messages[0].role)
    }

    // ---------- 送信失敗 ----------

    @Test
    fun `409ならローカルエコーを撤回し下書きを戻す`() = runTest {
        val gw = FakeChatGateway()
        gw.promptResult = ApiResult.Err(ApiError.Http(409))
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        c.updateDraft("送れない文")
        c.sendDraft()
        advanceUntilIdle()
        assertFalse(c.state.value.busy)
        assertEquals("送れない文", c.state.value.draft)
        assertEquals(emptyList<Any>(), c.state.value.messages)
        assertEquals("セッションが実行中です(409)。完了までお待ちください", c.state.value.sendError)
    }

    // ---------- 再接続でサーバーの現在値を取り直す(レビュー major-1 / RUN_PLAN 決定2) ----------

    /**
     * 実際に踏むシナリオ: 送信 → ホームで背面 → `sseClients` が 0 に落ちる(Q0-2 実測)→
     * その間に実行が終わって `session.idle` が流れる(**購読者ゼロで消える**)→ 復帰。
     * イベント列だけを信じると busy が永久に降りず、**入力欄は disabled のまま帯も出ない**。
     */
    @Test
    fun `再接続でサーバーがidleならbusyが降りる`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        assertTrue(c.state.value.busy)

        // 切断中に実行が終わった = 復帰後の GET /session/status にこのセッションは現れない
        gw.statusResult = ApiResult.Ok(emptyMap())
        c.onReconnected()
        advanceUntilIdle()

        assertFalse("キーが無い = idle。取り直さないと永久に応答中のまま", c.state.value.busy)
        assertTrue(canSend(c.state.value))
    }

    @Test
    fun `再接続でサーバーがbusyならbusyのまま`() = runTest {
        // 片方向だけ直っている(「降ろすだけ」)実装を落とすための対照。
        val gw = FakeChatGateway()
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        assertFalse(c.state.value.busy)

        gw.statusResult = ApiResult.Ok(mapOf(sid to SessionStatusDto(type = "busy")))
        c.onReconnected()
        advanceUntilIdle()
        assertTrue("サーバーが実行中と言っているなら入力欄を開けない", c.state.value.busy)
    }

    @Test
    fun `再接続で居座ったretry帯が畳まれる`() = runTest {
        // retry は dismissible=false なので、居座ると閉じる手段が画面に無い。
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, retryFrame())
        assertNotNull(c.state.value.retry)

        gw.statusResult = ApiResult.Ok(emptyMap())
        c.onReconnected()
        advanceUntilIdle()
        assertNull(c.state.value.retry)
        assertNull(selectChatBanner(c.state.value, nowMs = 1_787_785_000_000L))
    }

    @Test
    fun `実行中セッションへの入室で入力欄が開かない`() = runTest {
        val gw = FakeChatGateway()
        gw.statusResult = ApiResult.Ok(mapOf(sid to SessionStatusDto(type = "busy")))
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        assertTrue("openChat は常に busy=false で作るので、取り直さないと409を踏む", c.state.value.busy)
        assertEquals(1, gw.statusCalls)
    }

    @Test
    fun `状態が取れなければ実行状態を動かさない`() = runTest {
        // 取れないときに勝手に idle へ倒すと、実行中の入力欄を開けてしまう。
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        gw.statusResult = ApiResult.Err(ApiError.Network("boom"))
        c.onReconnected()
        advanceUntilIdle()
        assertTrue(c.state.value.busy)
    }

    @Test
    fun `送信の往復中に届いた状態は当てない`() = runTest {
        // prompt を投げた直後はサーバーがまだ busy を立てていないことがある。
        // そこで idle を当てると、自分で立てた busy を自分で消す。
        val gw = FakeChatGateway()
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        gw.promptResult = ApiResult.Ok(Unit)
        gw.statusResult = ApiResult.Ok(emptyMap())
        val gate = CompletableDeferred<Unit>()
        gw.promptGate = gate
        c.updateDraft("いま送った")
        c.sendDraft()
        runCurrent()
        assertTrue("往復が終わっていないこと(そうでないとこのテストは何も見ていない)", c.state.value.busy)

        // 送信の往復が終わる前に再接続が起きる
        c.onReconnected()
        advanceUntilIdle()
        assertTrue("自分で立てた busy を自分で消さない", c.state.value.busy)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(c.state.value.busy)
    }

    @Test
    fun `退室後の再接続では状態を引かない`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        val before = gw.statusCalls
        c.closeChat()
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(before, gw.statusCalls)
    }

    @Test
    fun `再接続は状態の取り直しと配布失敗の破棄を両方やる`() = runTest {
        // 片方だけ実装した状態を落とす(レビュー W1: 配線が素通しだった箇所)。
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        c.recordDeliveryFailure(IllegalStateException("boom"))
        gw.statusResult = ApiResult.Ok(emptyMap())
        val before = gw.statusCalls

        c.onReconnected()
        advanceUntilIdle()

        assertNull(c.state.value.eventDeliveryFailure)
        assertEquals(before + 1, gw.statusCalls)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `遅れて届いたidleで中断の注記が消える`() = runTest {
        // 注記は「完了通知が**届いていない**」と言っている。届いたならその主張は嘘になる。
        val gw = FakeChatGateway()
        val c = busyController(gw, this, grace = 1_000L)
        c.abortSession()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertNotNull(c.state.value.abortNotice)

        feed(c, idleFrame())
        assertNull(c.state.value.abortNotice)
        assertNull(selectChatBanner(c.state.value, nowMs = 0L))
    }

    // ---------- 配布失敗の寿命(RUN_PLAN が Q2 に設計を求めた点) ----------

    @Test
    fun `配布失敗は積み上がり再接続で消える`() = runTest {
        val c = controller(FakeChatGateway())
        c.openChat(sid)
        advanceUntilIdle()
        c.recordDeliveryFailure(IllegalStateException("boom"))
        c.recordDeliveryFailure(IllegalStateException("boom2"))
        assertEquals(2, c.state.value.eventDeliveryFailure?.count)
        assertEquals(ChatBannerKind.DELIVERY_FAILURE, selectChatBanner(c.state.value, 0L)?.kind)

        c.onReconnected()
        assertNull("前の接続で起きたことは新しい接続へ持ち越さない", c.state.value.eventDeliveryFailure)
    }

    @Test
    fun `配布失敗は閉じられる`() = runTest {
        val c = controller(FakeChatGateway())
        c.openChat(sid)
        advanceUntilIdle()
        c.recordDeliveryFailure(IllegalStateException("boom"))
        c.dismissBanner(ChatBannerKind.DELIVERY_FAILURE)
        assertNull(c.state.value.eventDeliveryFailure)
    }

    @Test
    fun `入室しても接続状態と配布失敗は引き継ぐ`() = runTest {
        val c = controller(FakeChatGateway())
        c.openChat(sid)
        advanceUntilIdle()
        c.setEventStatus(ChatEventStatus.RECONNECTING)
        c.recordDeliveryFailure(IllegalStateException("boom"))
        c.openChat("ses_another")
        advanceUntilIdle()
        assertEquals(ChatEventStatus.RECONNECTING, c.state.value.eventStatus)
        assertEquals(1, c.state.value.eventDeliveryFailure?.count)
        assertFalse("別セッションへ移ったら削除フラグは持ち越さない", c.state.value.sessionDeleted)
    }

    // ---------- permission ----------

    @Test
    fun `permission_replied でダイアログが閉じる`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, permissionAskedFrame())
        assertEquals("per_1", c.state.value.permissionDialog?.permissionId)
        assertEquals("bash", c.state.value.permissionDialog?.permission)
        // metadata はオブジェクトで届く。String と決め打つとパースが Ignored に落ちて
        // ダイアログが一度も出なくなる(P4 の実欠陥)。文字列化できていることを見る。
        assertTrue(c.state.value.permissionDialog?.metadata.orEmpty().contains("rm -rf /tmp/x"))
        feed(
            c,
            """{"id":"evt_q2pr","type":"permission.replied","properties":{"id":"per_1","sessionID":"$sid","response":"once"}}""",
        )
        assertNull(c.state.value.permissionDialog)
    }

    @Test
    fun `承認の応答はゲートウェイへ渡る`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        c.replyPermission("per_1", sid, "always")
        advanceUntilIdle()
        assertEquals(listOf(Triple(sid, "per_1", "always")), gw.permissionReplies)
    }

    // ---------- Q3: Todo + Question(状態機械そのものへ置く検出器) ----------
    //
    // ここに置く理由は RUN_PLAN「検出器の穴」。`buildQuestionAnswers` の純関数テストだけでは
    // **質問の寿命 / todo の保持 / permission との同時 pending / 再接続での取り直し** が
    // 1本も検査されない。それらは全部「壊しても画面は正常に見える」種類である。

    /** `todo.updated` — properties required: `{sessionID, todos}` の両方。Todo required は3キー全部。 */
    private fun todoFrame(session: String = sid, vararg statuses: String): String {
        val todos = statuses.mapIndexed { i, s ->
            """{"content":"タスク${i + 1}","status":"$s","priority":"high"}"""
        }.joinToString(",")
        return """{"id":"evt_q3todo","type":"todo.updated","properties":{"sessionID":"$session","todos":[$todos]}}"""
    }

    /**
     * `question.asked` — properties: `{id(^que), sessionID, questions}`。
     * `QuestionOption` の required は **`label` と `description` の両方**。
     */
    private fun askedFrame(
        session: String = sid,
        requestId: String = "que_q3_0001",
        custom: Boolean = true,
        type: String = "question.asked",
    ): String =
        """{"id":"evt_q3ask","type":"$type","properties":{"id":"$requestId","sessionID":"$session",""" +
            """"questions":[{"question":"どちらの方式で進めますか","header":"方式選択",""" +
            """"options":[{"label":"A案","description":"速いが粗い"},{"label":"B案","description":"遅いが確実"}],""" +
            """"custom":$custom}],"tool":{"messageID":"msg_a1","callID":"call_1"}}}"""

    /**
     * `question.replied` から **`requestID` を落とした**形。
     * P4 の実欠陥(`{id, sessionID, response}` しか来なかった)と同じ形を意図的に作る。
     */
    private fun repliedFrameWithoutRequestId(session: String = sid) =
        """{"id":"evt_q3norid","type":"question.replied","properties":""" +
            """{"sessionID":"$session","answers":[["A案"]]}}"""

    /**
     * `options: []` かつ `custom` 無しの質問。spec は `options` を required にしているが
     * `minItems` を置いていないので、**この形は契約上あり得る**(レビュー minor-6)。
     */
    private fun emptyOptionsAskedFrame(session: String = sid, requestId: String = "que_noopt") =
        """{"id":"evt_q3noopt","type":"question.asked","properties":{"id":"$requestId",""" +
            """"sessionID":"$session","questions":[{"question":"入力手段がありません","header":"空","options":[]}]}}"""

    /**
     * 単数だったころの読み取りをそのまま使うための補助。
     * **カードが2枚以上あるときは落ちる** —— blocker-1(2件目の質問が1件目を潰す)を
     * 「1枚しか見ない読み取り」で覆い隠さないため。複数枚を見るテストは requestId で明示する。
     */
    private val ChatUi.soleQuestion: QuestionCardState?
        get() = if (questions.size <= 1) {
            questions.firstOrNull()
        } else {
            throw AssertionError("質問カードが${questions.size}枚ある。どれを見るか明示すること: ${questions.map { it.requestId }}")
        }

    private fun ChatUi.questionOf(requestId: String): QuestionCardState? =
        questions.firstOrNull { it.requestId == requestId }

    @Test
    fun `todo_updated でタスクリストが入り、状態が3値ぶん反映される`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, todoFrame(statuses = arrayOf("pending", "in_progress", "completed")))
        val todos = c.state.value.todos
        assertEquals(3, todos.size)
        assertEquals(
            listOf(TodoStatus.PENDING, TodoStatus.IN_PROGRESS, TodoStatus.COMPLETED),
            todos.map { it.status },
        )
        assertEquals("完了 1/3", todoProgressText(todos))
    }

    @Test
    fun `todo の status に cancelled が混ざってもリストごと消えない`() = runTest {
        // spec の status は enum ではなく string で、description は cancelled を含む4値。
        // enum で decode する実装だとここで todos が空になる(= リストが消える)。
        val c = busyController(FakeChatGateway(), this)
        feed(c, todoFrame(statuses = arrayOf("completed", "cancelled", "in_progress")))
        assertEquals(3, c.state.value.todos.size)
        assertEquals(TodoStatus.CANCELLED, c.state.value.todos[1].status)
        // 分母から cancelled を除く: 2件中1件完了
        assertEquals("完了 1/2", todoProgressText(c.state.value.todos))
    }

    @Test
    fun `session_idle が来ても todo は保持される`() = runTest {
        // §5 Q3 スコープ1「session.idle 後も最終状態を保持」。
        // finishRun で全部畳む実装にすると、完了したタスクリストが完了と同時に消える。
        val c = busyController(FakeChatGateway(), this)
        feed(c, todoFrame(statuses = arrayOf("completed", "completed")))
        feed(c, idleFrame())
        assertFalse(c.state.value.busy)
        assertEquals(2, c.state.value.todos.size)
    }

    @Test
    fun `別セッションの todo_updated は無視される`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, todoFrame(session = "ses_other", statuses = arrayOf("pending")))
        assertEquals(0, c.state.value.todos.size)
    }

    @Test
    fun `question_asked でカードが立ち、選択して回答すると answers がゲートウェイへ渡る`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        val card = c.state.value.soleQuestion
        assertNotNull("question.asked がカードにならなかった", card)
        assertEquals("que_q3_0001", card!!.requestId)
        assertEquals(1, card.questions.size)
        assertEquals(2, card.questions[0].options.size)
        assertTrue("custom:true が落ちている", card.questions[0].custom)
        assertFalse("multiple を指定していないので false", card.questions[0].multiple)
        // 何も選ばないうちは送れない
        assertFalse(canSubmitQuestion(c.state.value.soleQuestion!!))
        c.toggleQuestionOption("que_q3_0001", 0, 1)
        assertTrue(canSubmitQuestion(c.state.value.soleQuestion!!))
        c.submitQuestion("que_q3_0001")
        advanceUntilIdle()
        assertEquals(1, gw.questionReplies.size)
        assertEquals("que_q3_0001", gw.questionReplies[0].first)
        // **形の同一性を主張する**: 質問1件 → 外側1要素、選んだラベル1件 → 内側1要素
        assertEquals(listOf(listOf("B案")), gw.questionReplies[0].second)
        assertEquals(QuestionResolution.ANSWERED, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `v2 の type 名でも同じカードになる`() = runTest {
        // question.v2.asked は shape が完全に同一。どちらで届くかを実物で観測できていない以上、
        // 片方だけ受ける実装は「カードが一度も出ない」に賭けている。
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame(type = "question.v2.asked"))
        assertEquals("que_q3_0001", c.state.value.soleQuestion?.requestId)
    }

    @Test
    fun `question_replied は requestID で閉じる(id ではない)`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame())
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
        feed(
            c,
            """{"id":"evt_q3rep","type":"question.replied","properties":""" +
                """{"sessionID":"$sid","requestID":"que_q3_0001","answers":[["A案"]]}}""",
        )
        assertEquals(QuestionResolution.ANSWERED, c.state.value.soleQuestion?.resolution)
        // サーバーが受理した内容を正とする
        assertEquals(listOf(listOf("A案")), c.state.value.soleQuestion?.submittedAnswers)
        // **カードは消さない**(§5 Q3 スコープ2)
        assertNotNull(c.state.value.soleQuestion)
    }

    @Test
    fun `question_rejected でも拒否済みとして残る`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame())
        feed(
            c,
            """{"id":"evt_q3rej","type":"question.rejected","properties":""" +
                """{"sessionID":"$sid","requestID":"que_q3_0001"}}""",
        )
        assertEquals(QuestionResolution.REJECTED, c.state.value.soleQuestion?.resolution)
        assertNotNull(c.state.value.soleQuestion)
    }

    @Test
    fun `別の requestID の replied ではカードが閉じない`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame())
        feed(
            c,
            """{"id":"evt_q3rep2","type":"question.replied","properties":""" +
                """{"sessionID":"$sid","requestID":"que_other","answers":[["A案"]]}}""",
        )
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `requestID を持たない replied でも現在のカードは閉じる`() = runTest {
        // P4 の実欠陥: 「id だけの replied は Ignored であるべき」というテストがあったのに、
        // **実際に来るのがまさにその形**だった。欠けた形で捨てない側へ倒す。
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame())
        feed(
            c,
            """{"id":"evt_q3rep3","type":"question.replied","properties":{"sessionID":"$sid","answers":[["A案"]]}}""",
        )
        assertEquals(QuestionResolution.ANSWERED, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `拒否導線が reject を呼ぶ`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        c.rejectQuestion("que_q3_0001")
        advanceUntilIdle()
        assertEquals(listOf("que_q3_0001"), gw.questionRejects)
        assertEquals(QuestionResolution.REJECTED, c.state.value.soleQuestion?.resolution)
        // 拒否は reply を呼ばない(両方投げると片方が404になる)
        assertEquals(0, gw.questionReplies.size)
    }

    @Test
    fun `回答が失敗したら送信エラー帯に出て、カードは押せるまま残る`() = runTest {
        val gw = FakeChatGateway()
        gw.replyQuestionResult = ApiResult.Err(ApiError.Http(404))
        val c = busyController(gw, this)
        feed(c, askedFrame())
        c.toggleQuestionOption("que_q3_0001", 0, 0)
        c.submitQuestion("que_q3_0001")
        advanceUntilIdle()
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
        assertFalse(c.state.value.soleQuestion!!.submitting)
        assertTrue(canSubmitQuestion(c.state.value.soleQuestion!!))
        // **4本目の帯を足さない**。既存の送信エラー帯へ出す(§5 Q2 スコープ6 の一本化)。
        assertEquals(ChatBannerKind.SEND_ERROR, selectChatBanner(c.state.value, 0L)?.kind)
        assertTrue(c.state.value.sendError!!.contains("HTTP 404"))
    }

    @Test
    fun `permission と question は同時に pending でも互いを消さない`() = runTest {
        // §5 Q3 スコープ3。片方を出すときにもう片方を null にする実装は、ここで落ちる。
        val c = busyController(FakeChatGateway(), this)
        feed(c, permissionAskedFrame())
        feed(c, askedFrame())
        assertNotNull("question で permission が消えた", c.state.value.permissionDialog)
        assertNotNull("permission で question が消えた", c.state.value.soleQuestion)
        // permission だけ閉じても question は残る
        feed(
            c,
            """{"id":"evt_q3pr","type":"permission.replied","properties":{"id":"per_1","sessionID":"$sid","response":"once"}}""",
        )
        assertNull(c.state.value.permissionDialog)
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `質問が先でも permission が後から来て互いを消さない`() = runTest {
        // 変異M7で判明した穴: 「permission が来たら question を消す」変異が、
        // permission→question の順しか見ていないテストを素通りした。**両方の順序を測る。**
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame())
        feed(c, permissionAskedFrame())
        assertNotNull("permission が後から来て質問カードが消えた", c.state.value.soleQuestion)
        assertNotNull(c.state.value.permissionDialog)
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `permission の到着で入力中の選択が消えない`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame())
        c.toggleQuestionOption("que_q3_0001", 0, 1)
        feed(c, permissionAskedFrame())
        assertEquals(setOf(1), c.state.value.soleQuestion?.selections?.get(0)?.selectedIndices)
    }

    @Test
    fun `未応答のまま実行が終わったら質問は残るが押せなくなる`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame())
        feed(c, idleFrame())
        val card = c.state.value.soleQuestion
        assertNotNull("何を聞かれたかは残す", card)
        assertEquals(QuestionResolution.EXPIRED, card!!.resolution)
        assertFalse("応答先が無いのにボタンだけ生きているのが一番わかりにくい", card.isInteractive())
    }

    @Test
    fun `入室でサーバーの現在値から todo と未応答質問を取り直す`() = runTest {
        val gw = FakeChatGateway()
        gw.todosResult = ApiResult.Ok(listOf(TodoDto("復元されたタスク", "in_progress", "high")))
        gw.questionsResult = ApiResult.Ok(
            listOf(
                QuestionRequestDto(
                    id = "que_restored",
                    sessionID = sid,
                    questions = listOf(
                        QuestionInfoDto(
                            question = "続けますか",
                            header = "確認",
                            options = listOf(QuestionOptionDto("はい", "続行する")),
                        ),
                    ),
                ),
            ),
        )
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        assertEquals(1, gw.todoCalls)
        assertEquals(1, gw.questionCalls)
        assertEquals(1, c.state.value.todos.size)
        assertEquals("que_restored", c.state.value.soleQuestion?.requestId)
    }

    @Test
    fun `再接続で todo と未応答質問を取り直す`() = runTest {
        // RUN_PLAN 決定2。切れている間に届いた question.asked を取り逃すと、
        // カードが出ないまま**サーバーは応答を待って止まり続ける**。
        val gw = FakeChatGateway()
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        val todoBefore = gw.todoCalls
        val questionBefore = gw.questionCalls
        gw.questionsResult = ApiResult.Ok(
            listOf(
                QuestionRequestDto(
                    id = "que_after_reconnect",
                    sessionID = sid,
                    questions = listOf(
                        QuestionInfoDto("切断中に来た質問", "復旧", listOf(QuestionOptionDto("OK", "了解"))),
                    ),
                ),
            ),
        )
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(todoBefore + 1, gw.todoCalls)
        assertEquals(questionBefore + 1, gw.questionCalls)
        assertEquals("que_after_reconnect", c.state.value.soleQuestion?.requestId)
    }

    @Test
    fun `取り直しは入力中の選択を巻き戻さない`() = runTest {
        // **旧テスト名は「決着済みのカードも巻き戻さない」だった。それはバグの側だった** ——
        // サーバーがまだ未応答と言っているカードは PENDING へ戻すのが正しい(blocker-2)。
        // ここが固定するのは**選択が消えないこと**だけである。
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        c.toggleQuestionOption("que_q3_0001", 0, 0)
        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_q3_0001")))
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(setOf(0), c.state.value.soleQuestion?.selections?.get(0)?.selectedIndices)
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `復元した未応答質問を、同じ取り直しの idle が畳まない`() = runTest {
        // **2026-08-27、実機で踏んだ欠陥の回帰テスト。**
        // プロセス死の間に届いた question.asked を `GET /question` で復元した直後、
        // `GET /session/status` が「実行中でない」と答えた経路が finishRun() を呼び、
        // 復元したばかりのカードを EXPIRED にした(dump: `question-card:que_2:expired`)。
        // サーバーが未応答として返している質問は、定義上まだ答えられる。
        val gw = FakeChatGateway()
        gw.statusResult = ApiResult.Ok(emptyMap()) // キーが無い = idle
        gw.questionsResult = ApiResult.Ok(
            listOf(
                QuestionRequestDto(
                    id = "que_restored",
                    sessionID = sid,
                    questions = listOf(
                        QuestionInfoDto("切断中に来た質問", "復旧", listOf(QuestionOptionDto("OK", "了解"))),
                    ),
                ),
            ),
        )
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        assertEquals("que_restored", c.state.value.soleQuestion?.requestId)
        assertEquals(
            "サーバーが未応答として返した質問を idle の取り直しが畳んだ",
            QuestionResolution.PENDING,
            c.state.value.soleQuestion?.resolution,
        )
        assertTrue(c.state.value.soleQuestion!!.isInteractive())
        // 一方で busy の立ち下がりは効いていること(取り直しの本来の役目)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `再接続の取り直しでも復元した質問を畳まない`() = runTest {
        val gw = FakeChatGateway()
        gw.statusResult = ApiResult.Ok(emptyMap())
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        gw.questionsResult = ApiResult.Ok(
            listOf(
                QuestionRequestDto(
                    id = "que_after",
                    sessionID = sid,
                    questions = listOf(QuestionInfoDto("q", "h", listOf(QuestionOptionDto("OK", "d")))),
                ),
            ),
        )
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `session_idle イベントは今までどおり質問を畳む`() = runTest {
        // 上の2件で「畳まない」を足したので、**畳むほうが消えていないこと**も固定する。
        // イベントは「この実行が終わった」と言っており、取り直しの idle とは意味が違う。
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame())
        feed(c, statusFrame(type = "idle"))
        assertEquals(QuestionResolution.EXPIRED, c.state.value.soleQuestion?.resolution)
    }

    // ---------- Q3 差し戻し1周目: レビューの変異4本と blocker 2件を殺す ----------

    /** `GET /question` が返す未応答1件(spec: required は id / sessionID / questions)。 */
    private fun serverPending(
        requestId: String,
        session: String = sid,
        label: String = "A案",
    ) = QuestionRequestDto(
        id = requestId,
        sessionID = session,
        questions = listOf(
            QuestionInfoDto("どちらの方式で進めますか", "方式選択", listOf(QuestionOptionDto(label, "説明"))),
        ),
    )

    // --- blocker-1: 質問は同時に何件でも持てる ---

    @Test
    fun `2件目の question_asked が1件目を潰さない`() = runTest {
        // レビューの変異M1が殺せなかった経路。1件目が消えるとサーバーはそれを永久に待つ。
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame(requestId = "que_A"))
        c.toggleQuestionOption("que_A", 0, 1)
        feed(c, askedFrame(requestId = "que_B"))
        assertEquals(2, c.state.value.questions.size)
        assertEquals(listOf("que_A", "que_B"), c.state.value.questions.map { it.requestId })
        assertEquals(setOf(1), c.state.value.questionOf("que_A")?.selections?.get(0)?.selectedIndices)
        assertEquals(QuestionResolution.PENDING, c.state.value.questionOf("que_B")?.resolution)
    }

    @Test
    fun `2件 pending でも回答は指定した requestId のカードだけに効く`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame(requestId = "que_A"))
        feed(c, askedFrame(requestId = "que_B"))
        c.toggleQuestionOption("que_B", 0, 0)
        c.submitQuestion("que_B")
        advanceUntilIdle()
        assertEquals(listOf("que_B"), gw.questionReplies.map { it.first })
        assertEquals(QuestionResolution.ANSWERED, c.state.value.questionOf("que_B")?.resolution)
        assertEquals(
            "もう片方が巻き添えで決着した",
            QuestionResolution.PENDING,
            c.state.value.questionOf("que_A")?.resolution,
        )
        assertEquals(emptyList<List<String>>(), c.state.value.questionOf("que_A")?.submittedAnswers)
    }

    @Test
    fun `2件 pending なら requestID 無しの replied は推測しない`() = runTest {
        // どちらを畳むか当てずっぽうで決めると、答えていない質問が「回答済み」になり、
        // 答えた質問が待ち続ける —— 取りこぼしより悪い。
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame(requestId = "que_A"))
        feed(c, askedFrame(requestId = "que_B"))
        feed(c, repliedFrameWithoutRequestId())
        assertEquals(
            listOf(QuestionResolution.PENDING, QuestionResolution.PENDING),
            c.state.value.questions.map { it.resolution },
        )
    }

    @Test
    fun `実行が終わったら未応答の質問を全部畳む`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame(requestId = "que_A"))
        feed(c, askedFrame(requestId = "que_B"))
        feed(c, idleFrame())
        assertEquals(
            listOf(QuestionResolution.EXPIRED, QuestionResolution.EXPIRED),
            c.state.value.questions.map { it.resolution },
        )
    }

    @Test
    fun `切断中に増えた2件目を取り直しで回収する`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame(requestId = "que_A"))
        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_A"), serverPending("que_B")))
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(listOf("que_A", "que_B"), c.state.value.questions.map { it.requestId })
    }

    // --- blocker-2: GET /question が EXPIRED に対して権威 ---

    @Test
    fun `session_error で失効したカードを、まだ未応答ならサーバーが蘇らせる`() = runTest {
        // レビューの変異M4が殺せなかった経路の裏側。Q2 は実物の APIError/429 を
        // まさにこの面で実測している。失効させたまま二度と押せないのが元の欠陥だった。
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        c.toggleQuestionOption("que_q3_0001", 0, 1)
        feed(c, errorFrame())
        assertEquals(QuestionResolution.EXPIRED, c.state.value.soleQuestion?.resolution)

        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_q3_0001")))
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(
            "サーバーがまだ未応答と言っているのに押せないまま",
            QuestionResolution.PENDING,
            c.state.value.soleQuestion?.resolution,
        )
        assertTrue(c.state.value.soleQuestion!!.isInteractive())
        assertEquals(
            "蘇生で選択が消えた",
            setOf(1),
            c.state.value.soleQuestion?.selections?.get(0)?.selectedIndices,
        )
    }

    @Test
    fun `session_deleted で失効したカードも、蘇生の対象になる`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        feed(c, deletedFrame())
        assertEquals(QuestionResolution.EXPIRED, c.state.value.soleQuestion?.resolution)
        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_q3_0001")))
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `abort の見張りで失効したカードも、蘇生の対象になる`() = runTest {
        // レビューの変異M9が殺せなかった経路。見張りは「完了通知が来ない」ときの保険であって、
        // 「サーバーが質問を捨てた」証拠ではない。
        val gw = FakeChatGateway()
        val c = busyController(gw, this, grace = 1_000L)
        feed(c, askedFrame())
        c.abortSession()
        advanceUntilIdle()
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(QuestionResolution.EXPIRED, c.state.value.soleQuestion?.resolution)
        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_q3_0001")))
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `abort の見張りは未応答の質問を失効させる`() = runTest {
        // 蘇生を足したので、**失効させるほうが消えていないこと**も固定する(変異M9の直撃)。
        val c = busyController(FakeChatGateway(), this, grace = 1_000L)
        feed(c, askedFrame())
        c.abortSession()
        advanceUntilIdle()
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(QuestionResolution.EXPIRED, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `session_error は未応答の質問を失効させる`() = runTest {
        // 同上(変異M4の直撃)。
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame())
        feed(c, errorFrame())
        assertEquals(QuestionResolution.EXPIRED, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `サーバーが載せなくなった未応答カードは失効する`() = runTest {
        // 蘇生の逆向き。別のクライアントが答えた質問がこちらで永久に「回答待ち」にならない。
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        gw.questionsResult = ApiResult.Ok(emptyList())
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(QuestionResolution.EXPIRED, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `取得に失敗したら未応答カードを失効させない`() = runTest {
        // 「読めなかった」を「サーバーが捨てた」に化けさせない。
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        gw.questionsResult = ApiResult.Err(ApiError.Network("boom"))
        c.onReconnected()
        advanceUntilIdle()
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    // --- major-3: セッション横断フィルタ ---

    @Test
    fun `別セッションの未応答質問は取り込まない`() = runTest {
        // レビューの変異M2が殺せなかった経路。フィルタを消すと、セッションAに入ると
        // セッションBの質問が出て、回答が他所の requestID へ飛ぶ。
        // **フィクスチャに「別セッションの質問」を必ず混ぜる**のが要点で、
        // 以前の復元テストは sessionID が一致する DTO を1件流すだけだった。
        val gw = FakeChatGateway()
        gw.questionsResult = ApiResult.Ok(
            listOf(
                serverPending("que_other_1", session = "ses_someone_else"),
                serverPending("que_mine", session = sid),
                serverPending("que_other_2", session = "ses_another"),
            ),
        )
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        assertEquals(listOf("que_mine"), c.state.value.questions.map { it.requestId })
    }

    @Test
    fun `他セッションの質問しか無ければカードは1枚も出ない`() = runTest {
        val gw = FakeChatGateway()
        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_other", session = "ses_someone_else")))
        val c = controller(gw)
        c.openChat(sid)
        advanceUntilIdle()
        assertEquals(0, c.state.value.questions.size)
    }

    // --- minor-4: 往復中に別の質問が来てもカードを取り違えない ---

    @Test
    fun `回答の往復中に別の質問が届いても決着は正しいカードに乗る`() = runTest {
        val gw = FakeChatGateway()
        val gate = CompletableDeferred<Unit>()
        gw.questionReplyGate = gate
        val c = busyController(gw, this)
        feed(c, askedFrame(requestId = "que_A"))
        c.toggleQuestionOption("que_A", 0, 0)
        c.submitQuestion("que_A")
        runCurrent()
        assertTrue("往復中の札が立っていない", c.state.value.questionOf("que_A")!!.submitting)
        feed(c, askedFrame(requestId = "que_B"))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(QuestionResolution.ANSWERED, c.state.value.questionOf("que_A")?.resolution)
        assertEquals(listOf(listOf("A案")), c.state.value.questionOf("que_A")?.submittedAnswers)
        assertEquals(
            "決着が別のカードに乗った",
            QuestionResolution.PENDING,
            c.state.value.questionOf("que_B")?.resolution,
        )
        assertEquals(emptyList<List<String>>(), c.state.value.questionOf("que_B")?.submittedAnswers)
    }

    @Test
    fun `往復中のカードは取り直しで蘇生も失効もしない`() = runTest {
        val gw = FakeChatGateway()
        val gate = CompletableDeferred<Unit>()
        gw.questionReplyGate = gate
        val c = busyController(gw, this)
        feed(c, askedFrame())
        c.toggleQuestionOption("que_q3_0001", 0, 0)
        c.submitQuestion("que_q3_0001")
        runCurrent()
        gw.questionsResult = ApiResult.Ok(emptyList())
        c.onReconnected()
        runCurrent()
        assertTrue("往復中に失効させた", c.state.value.soleQuestion!!.submitting)
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(QuestionResolution.ANSWERED, c.state.value.soleQuestion?.resolution)
    }

    // --- minor-6: 回答手段が1つも無い質問 ---

    @Test
    fun `選択肢も自由入力も無い質問は回答不能として扱う`() = runTest {
        val c = busyController(FakeChatGateway(), this)
        feed(c, emptyOptionsAskedFrame())
        val card = c.state.value.soleQuestion
        assertNotNull("カードそのものは出す(何を聞かれたかは残す)", card)
        assertFalse(card!!.isAnswerable())
        assertFalse(canSubmitQuestion(card))
        assertTrue("拒否だけは通る", card.isInteractive())
    }

    // --- E2E 所見 F4: 失効の直後にサーバーへ問い直す ---

    @Test
    fun `session_idle で失効しても、サーバーがまだ持っていれば自力で戻る`() = runTest {
        // 直す症状: SSE が繋がったままだと connectedGeneration が動かず、
        // 取り直しの契機が**再入室かSSE再接続しか無かった**。画面は
        // 「回答しないまま実行が終了しました」と言い、サーバーは待ち続け、
        // 脱出方法が画面から発見できない。
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_q3_0001")))
        feed(c, idleFrame())
        advanceUntilIdle()
        assertEquals(
            "再接続も再入室もせずに戻れていない",
            QuestionResolution.PENDING,
            c.state.value.soleQuestion?.resolution,
        )
        assertTrue(c.state.value.soleQuestion!!.isInteractive())
    }

    @Test
    fun `session_error で失効しても、サーバーがまだ持っていれば自力で戻る`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_q3_0001")))
        feed(c, errorFrame())
        advanceUntilIdle()
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `abort の見張りで失効しても、サーバーがまだ持っていれば自力で戻る`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this, grace = 1_000L)
        feed(c, askedFrame())
        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_q3_0001")))
        c.abortSession()
        advanceUntilIdle()
        advanceTimeBy(1_500L)
        advanceUntilIdle()
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `session_deleted で失効しても、サーバーがまだ持っていれば自力で戻る`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        gw.questionsResult = ApiResult.Ok(listOf(serverPending("que_q3_0001")))
        feed(c, deletedFrame())
        advanceUntilIdle()
        assertEquals(QuestionResolution.PENDING, c.state.value.soleQuestion?.resolution)
    }

    @Test
    fun `サーバーが持っていなければ失効したまま`() = runTest {
        // 問い直しは**失効を無効化しない**。陰性側を固定する。
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, askedFrame())
        gw.questionsResult = ApiResult.Ok(emptyList())
        feed(c, idleFrame())
        advanceUntilIdle()
        assertEquals(QuestionResolution.EXPIRED, c.state.value.soleQuestion?.resolution)
        assertFalse(c.state.value.soleQuestion!!.isInteractive())
    }

    @Test
    fun `未応答が3件あればカードも3件とも出す`() = runTest {
        // E2E 所見 F2 の状態層側。描画層はカード群のスクロールが受け持つ。
        val c = busyController(FakeChatGateway(), this)
        feed(c, askedFrame(requestId = "que_A"))
        feed(c, askedFrame(requestId = "que_B"))
        feed(c, askedFrame(requestId = "que_C"))
        assertEquals(3, c.state.value.questions.size)
        assertEquals(
            listOf("que_A", "que_B", "que_C").map { dev.opencode.android.ui.ChatInlineCard.Question(it) },
            dev.opencode.android.ui.selectChatInlineCards(c.state.value),
        )
    }

    @Test
    fun `todo の取得に失敗しても前の内容を消さない`() = runTest {
        val gw = FakeChatGateway()
        val c = busyController(gw, this)
        feed(c, todoFrame(statuses = arrayOf("completed", "pending")))
        gw.todosResult = ApiResult.Err(ApiError.Network("boom"))
        c.onReconnected()
        advanceUntilIdle()
        assertEquals("読めなかったことを「タスクが無い」に化けさせない", 2, c.state.value.todos.size)
    }
}
