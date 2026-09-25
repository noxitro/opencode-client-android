package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ChatGateway
import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.PermissionRequestDto
import dev.opencode.android.data.QuestionRequestDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionRevertDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.data.TodoDto
import dev.opencode.android.ui.ChatBannerKind
import dev.opencode.android.ui.ChatController
import dev.opencode.android.ui.ChatMessage
import dev.opencode.android.ui.ChatPart
import dev.opencode.android.ui.canRevertMessage
import dev.opencode.android.ui.revertPreviewText
import dev.opencode.android.ui.selectChatBanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q7 スコープ6(巻き戻し)の検出器。
 *
 * ## なぜ確認ダイアログを状態に置いたか
 *
 * revert は**ファイルシステムを書き換える**。確認を Compose のローカル状態に置くと、
 * 「**確認を通らずに revert が送られない**」という一番大事な主張が
 * `runTest` から立てられず、実機 dump でしか測れなくなる。
 * Q5 の申し送りが「Compose の中の配線は `runTest` から到達できない」と書いたのは
 * まさにこの形で、**安全に関わるものをそこに置かない**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q7RevertTest {

    private val sid = "ses_stub_0001"

    private class FakeChat(override val isConfigured: Boolean = true) : ChatGateway {
        var revertResult: ApiResult<SessionDto> = ApiResult.Ok(
            SessionDto(
                id = "ses_stub_0001", title = "t", time = SessionTimeDto(1, 1),
                revert = SessionRevertDto(messageID = "msg_9"),
            ),
        )
        var unrevertResult: ApiResult<SessionDto> = ApiResult.Ok(
            SessionDto(id = "ses_stub_0001", title = "t", time = SessionTimeDto(1, 1)),
        )
        var session: ApiResult<SessionDto> = ApiResult.Ok(
            SessionDto(id = "ses_stub_0001", title = "t", time = SessionTimeDto(1, 1)),
        )
        val reverts = mutableListOf<Triple<String, String, String?>>()
        // **`directory` は口ごとに控える**(片方にだけ渡す変異を見るため)。
        val revertDirectories = mutableListOf<String?>()
        val unrevertDirectories = mutableListOf<String?>()
        var unrevertCalls = 0
        var listMessagesCalls = 0

        override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> = ApiResult.Ok(emptyMap())
        override suspend fun listMessages(sessionId: String): ApiResult<List<MessageEntryDto>> {
            listMessagesCalls++
            return ApiResult.Ok(emptyList())
        }
        override suspend fun sendPrompt(sessionId: String, text: String): ApiResult<Unit> = ApiResult.Ok(Unit)
        override suspend fun replyPermission(sessionId: String, permissionId: String, response: String) = ApiResult.Ok(Unit)
        override suspend fun abortSession(sessionId: String): ApiResult<Unit> = ApiResult.Ok(Unit)
        override suspend fun sessionTodos(sessionId: String): ApiResult<List<TodoDto>> = ApiResult.Ok(emptyList())
        override suspend fun pendingQuestions(): ApiResult<List<QuestionRequestDto>> = ApiResult.Ok(emptyList())
        override suspend fun pendingPermissions(): ApiResult<List<PermissionRequestDto>> = ApiResult.Ok(emptyList())
        override suspend fun replyQuestion(requestId: String, answers: List<List<String>>) = ApiResult.Ok(Unit)
        override suspend fun rejectQuestion(requestId: String): ApiResult<Unit> = ApiResult.Ok(Unit)
        override suspend fun getSession(sessionId: String): ApiResult<SessionDto> = session
        override suspend fun switchSessionModel(sessionId: String, model: ModelRefDto) = ApiResult.Ok(Unit)

        override suspend fun revertMessage(
            sessionId: String,
            messageId: String,
            partId: String?,
            directory: String?,
        ): ApiResult<SessionDto> {
            reverts += Triple(sessionId, messageId, partId)
            revertDirectories += directory
            return revertResult
        }

        override suspend fun unrevertSession(sessionId: String, directory: String?): ApiResult<SessionDto> {
            unrevertCalls++
            unrevertDirectories += directory
            return unrevertResult
        }
    }

    private fun TestScope.chat(gateway: ChatGateway) =
        ChatController(gateway, this, describeError = { e ->
            when (e) {
                is ApiError.Http -> "HTTP ${e.code}"
                is ApiError.Network -> "NET ${e.message}"
                ApiError.NotConfigured -> "NOT_CONFIGURED"
            }
        })

    // ---------------- 確認を通らないと送らない ----------------

    /**
     * **[ChatController.requestRevert] だけでは1バイトも送らない。**
     * ここが送ってしまうと、長押しメニューを押した瞬間にファイルが書き換わる。
     */
    @Test
    fun `確認を出すだけでは revert を送らない`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "[user] やり直して")
        runCurrent()
        assertTrue("送っていない", g.reverts.isEmpty())
        assertEquals("msg_9", c.state.value.revertConfirm?.messageId)
        assertEquals("[user] やり直して", c.state.value.revertConfirm?.preview)
    }

    @Test
    fun `やめると確認が消えて何も送らない`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "x")
        c.cancelRevert()
        c.confirmRevert()
        runCurrent()
        assertTrue(g.reverts.isEmpty())
        assertNull(c.state.value.revertConfirm)
    }

    /** **確認状態が無ければ実行できない。** 確認を飛ばす配線を書いても送る内容が作れない。 */
    @Test
    fun `確認なしで confirmRevert を呼んでも送らない`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.confirmRevert()
        runCurrent()
        assertTrue(g.reverts.isEmpty())
    }

    // ---------------- 実行 ----------------

    @Test
    fun `確認後に messageID を送りサーバーの revert を採る`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        runCurrent()
        assertEquals(listOf(Triple(sid, "msg_9", null)), g.reverts)
        assertEquals("msg_9", c.state.value.revertedMessageId)
        assertNull(c.state.value.revertConfirm)
        assertFalse(c.state.value.reverting)
    }

    @Test
    fun `partID を指定すればそのまま送る`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", partId = "prt_3", preview = "x")
        c.confirmRevert()
        runCurrent()
        assertEquals(listOf(Triple(sid, "msg_9", "prt_3")), g.reverts)
    }

    /**
     * **巻き戻すと履歴そのものが変わる。** `session.revert` の SSE は実機 spec に無いので、
     * 引き直さないと画面は消えたはずのメッセージを出し続ける。
     */
    @Test
    fun `revert 成功で履歴を引き直す`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        val before = g.listMessagesCalls
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        runCurrent()
        assertEquals(before + 1, g.listMessagesCalls)
    }

    @Test
    fun `revert 失敗は帯に出て巻き戻し位置を動かさない`() = runTest {
        val g = FakeChat()
        g.revertResult = ApiResult.Err(ApiError.Http(409))
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        val before = g.listMessagesCalls
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        runCurrent()
        assertEquals("HTTP 409", c.state.value.revertError)
        assertNull(c.state.value.revertedMessageId)
        assertEquals("失敗したら引き直さない", before, g.listMessagesCalls)
        assertEquals(ChatBannerKind.REVERT_ERROR, selectChatBanner(c.state.value, 0L)?.kind)
    }

    // ---------------- 元に戻す ----------------

    @Test
    fun `unrevert でサーバーの revert が消える`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        runCurrent()
        assertEquals("msg_9", c.state.value.revertedMessageId)
        c.unrevert()
        runCurrent()
        assertEquals(1, g.unrevertCalls)
        assertNull(c.state.value.revertedMessageId)
    }

    /** **往復中は二度押せない**(revert も unrevert も同じ札で守る)。 */
    @Test
    fun `往復中は二度押しても1回しか送らない`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        c.unrevert()
        runCurrent()
        assertEquals(1, g.reverts.size)
        assertEquals("往復中の unrevert は撃たない", 0, g.unrevertCalls)
    }

    // ---------------- サーバーが権威 ----------------

    /**
     * **巻き戻しの現在地はサーバーが持つ。** 他クライアント(TUI/CLI)が revert していれば、
     * 入室と再接続で引き直したときに「元に戻す」が出る。
     * ここを消すと、巻き戻された状態に入室しても画面が何も言わない。
     */
    @Test
    fun `入室で Session の revert を読む`() = runTest {
        val g = FakeChat()
        g.session = ApiResult.Ok(
            SessionDto(
                id = sid, title = "t", time = SessionTimeDto(1, 1),
                revert = SessionRevertDto(messageID = "msg_other"),
            ),
        )
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        assertEquals("msg_other", c.state.value.revertedMessageId)
    }

    /** 他クライアントが unrevert したら**消えるのが正しい**(null で上書きする)。 */
    @Test
    fun `再接続で revert が消えていれば消す`() = runTest {
        val g = FakeChat()
        g.session = ApiResult.Ok(
            SessionDto(
                id = sid, title = "t", time = SessionTimeDto(1, 1),
                revert = SessionRevertDto(messageID = "msg_other"),
            ),
        )
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        assertEquals("msg_other", c.state.value.revertedMessageId)
        g.session = ApiResult.Ok(SessionDto(id = sid, title = "t", time = SessionTimeDto(1, 1)))
        c.onReconnected()
        runCurrent()
        assertNull(c.state.value.revertedMessageId)
    }

    // ---------------- 帯 ----------------

    /**
     * **帯は1本のまま。** 巻き戻し中の帯は「元に戻す」への唯一の入口なので、
     * 閉じる導線を持たない —— 閉じても巻き戻された事実は消えない。
     */
    @Test
    fun `巻き戻し中は元に戻すを持つ帯が1本出る`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        runCurrent()
        val banner = selectChatBanner(c.state.value, 0L)
        assertEquals(ChatBannerKind.REVERTED, banner?.kind)
        assertEquals("元に戻す", banner?.actionLabel)
        assertFalse("閉じられない", banner?.dismissible ?: true)
    }

    /** 失敗の帯は閉じられる(閉じたら次の操作に進める)。 */
    @Test
    fun `revert 失敗の帯は閉じられる`() = runTest {
        val g = FakeChat()
        g.revertResult = ApiResult.Err(ApiError.Http(409))
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        runCurrent()
        assertTrue(selectChatBanner(c.state.value, 0L)?.dismissible == true)
        c.dismissBanner(ChatBannerKind.REVERT_ERROR)
        assertNull(c.state.value.revertError)
    }

    /** **失敗のほうが強い。** 巻き戻し中でも、直前の操作への返事を先に出す。 */
    @Test
    fun `失敗の帯は巻き戻し中の帯より強い`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        runCurrent()
        g.revertResult = ApiResult.Err(ApiError.Http(409))
        c.requestRevert("msg_8", preview = "y")
        c.confirmRevert()
        runCurrent()
        // 巻き戻しは有効なまま(失敗しても前の revert は消えない)。
        assertEquals("msg_9", c.state.value.revertedMessageId)
        assertEquals(ChatBannerKind.REVERT_ERROR, selectChatBanner(c.state.value, 0L)?.kind)
        c.dismissBanner(ChatBannerKind.REVERT_ERROR)
        assertEquals("下の帯は生きている", ChatBannerKind.REVERTED, selectChatBanner(c.state.value, 0L)?.kind)
    }

    // ---------------- directory の経路(レビュー minor-5) ----------------

    /**
     * QUALITY_PLAN §6 が「データ層は最初から `directory` を通せる形にしておくこと(UIは出さない)」
     * と定めた口。**revert / unrevert も `directory` クエリを取る**(実機 `/doc`)。
     */
    @Test
    fun `directory は revert と unrevert の両方へ渡る`() = runTest {
        val g = FakeChat()
        val c = ChatController(
            g, this,
            describeError = { "e" },
            directory = "/work/repo",
        )
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        runCurrent()
        assertEquals(listOf<String?>("/work/repo"), g.revertDirectories)
        c.unrevert()
        runCurrent()
        assertEquals(listOf<String?>("/work/repo"), g.unrevertDirectories)
    }

    @Test
    fun `既定では directory を渡さない`() = runTest {
        val g = FakeChat()
        val c = chat(g)
        c.openChat(sid)
        runCurrent()
        c.requestRevert("msg_9", preview = "x")
        c.confirmRevert()
        runCurrent()
        assertEquals(listOf<String?>(null), g.revertDirectories)
    }

    // ---------------- 純関数 ----------------

    private fun message(id: String, text: String, role: String = "user", echo: Boolean = false) = ChatMessage(
        messageId = id,
        role = role,
        parts = listOf(ChatPart(partId = "prt", type = "text", text = text, toolLabel = null)),
        isLocalEcho = echo,
    )

    /**
     * **サーバーは `messageID` に `^msg` を課している**(実機 `/doc`)。
     * ローカルエコーと合成IDに「ここまで戻す」を出すと、押せるのに 400 が返る。
     */
    @Test
    fun `msg で始まらないメッセージは巻き戻せない`() {
        assertTrue(canRevertMessage(message("msg_1", "a")))
        assertFalse(canRevertMessage(message("local-1", "a")))
        assertFalse(canRevertMessage(message("history-0", "a")))
        assertFalse("ローカルエコーはIDが msg でも出さない", canRevertMessage(message("msg_1", "a", echo = true)))
    }

    @Test
    fun `確認文は役割と本文の先頭行を出す`() {
        assertEquals("[user] やり直して", revertPreviewText(message("msg_1", "やり直して")))
        assertEquals(
            "先頭の空行を飛ばす",
            "[assistant] 2行目が本文",
            revertPreviewText(message("msg_1", "\n\n2行目が本文\n3行目", role = "assistant")),
        )
    }

    @Test
    fun `長い本文は切る`() {
        val long = "あ".repeat(200)
        val text = revertPreviewText(message("msg_1", long))
        assertTrue(text.endsWith("…"))
        assertTrue("確認文が本文で埋まらない", text.length <= 100)
    }

    @Test
    fun `本文が無くても役割だけは出す`() {
        val empty = ChatMessage(messageId = "msg_1", role = "assistant", parts = emptyList())
        assertEquals("[assistant]", revertPreviewText(empty))
    }
}
