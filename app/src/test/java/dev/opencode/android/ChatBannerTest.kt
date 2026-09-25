package dev.opencode.android

import dev.opencode.android.ui.ChatBannerKind
import dev.opencode.android.ui.ChatBannerTone
import dev.opencode.android.ui.ChatEventStatus
import dev.opencode.android.ui.ChatUi
import dev.opencode.android.ui.EventDeliveryFailure
import dev.opencode.android.ui.RetryState
import dev.opencode.android.ui.retryBannerText
import dev.opencode.android.ui.selectChatBanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帯の**一本化と優先順位**(QUALITY_PLAN §5 Q2 スコープ6 / RUN_PLAN 申し送り)。
 *
 * Q1 終了時点で帯は3本まで同時に並びえた。ここに retry が加わると4本になる。
 * 統合したので、**「どれを出すか」が新しい壊れどころ**になった ——
 * 順位を1つ入れ替えても画面は「帯が1本出ている」ままで、正常に見える。
 * だから順位そのものを固定する。
 */
class ChatBannerTest {

    private val everything = ChatUi(
        eventStatus = ChatEventStatus.RECONNECTING,
        eventDeliveryFailure = EventDeliveryFailure(count = 3, lastMessage = "IllegalStateException: boom"),
        sessionError = "セッションエラー本文",
        sendError = "送信エラー本文",
        retry = RetryState(attempt = 1, message = "retrying", next = 10_000L),
        abortNotice = "中断の注記",
        sessionDeleted = true,
    )

    /**
     * **全部立っている状態から1本ずつ剥がす**。出る順が仕様。
     * 剥がすと下が出る = 「上を出したせいで下の情報が消えた」わけではない、も同時に見ている。
     */
    @Test
    fun `優先順位は削除 認証 送信失敗 セッションエラー 再試行 中断注記 配布失敗 再接続 接続中`() {
        var state = everything
        val seen = mutableListOf<ChatBannerKind>()

        fun peel(next: (ChatUi) -> ChatUi) {
            seen += selectChatBanner(state, nowMs = 0L)!!.kind
            state = next(state)
        }

        peel { it.copy(sessionDeleted = false, eventStatus = ChatEventStatus.UNAUTHORIZED) }
        peel { it.copy(eventStatus = ChatEventStatus.RECONNECTING) }
        peel { it.copy(sendError = null) }
        peel { it.copy(sessionError = null) }
        peel { it.copy(retry = null) }
        peel { it.copy(abortNotice = null) }
        peel { it.copy(eventDeliveryFailure = null) }
        peel { it.copy(eventStatus = ChatEventStatus.CONNECTING) }
        peel { it.copy(eventStatus = ChatEventStatus.CONNECTED) }

        assertEquals(
            listOf(
                ChatBannerKind.SESSION_DELETED,
                ChatBannerKind.UNAUTHORIZED,
                ChatBannerKind.SEND_ERROR,
                ChatBannerKind.SESSION_ERROR,
                ChatBannerKind.RETRY,
                ChatBannerKind.ABORT_NOTICE,
                ChatBannerKind.DELIVERY_FAILURE,
                ChatBannerKind.RECONNECTING,
                ChatBannerKind.CONNECTING,
            ),
            seen,
        )
        // CONNECTED は帯を出さない(繋がっているのに帯が残ると、常時1本になる)
        assertNull(selectChatBanner(state, nowMs = 0L))
    }

    @Test
    fun `何も無ければ帯を出さない`() {
        assertNull(selectChatBanner(ChatUi(), nowMs = 0L))
        assertNull(selectChatBanner(ChatUi(eventStatus = ChatEventStatus.CONNECTED), nowMs = 0L))
    }

    @Test
    fun `閉じられるのはユーザーが読んで終われる種類だけ`() {
        val dismissible = mutableSetOf<ChatBannerKind>()
        listOf(
            ChatUi(sessionDeleted = true),
            ChatUi(eventStatus = ChatEventStatus.UNAUTHORIZED),
            ChatUi(sendError = "x"),
            ChatUi(sessionError = "x"),
            ChatUi(retry = RetryState(1, "m", 0L)),
            ChatUi(abortNotice = "x"),
            ChatUi(eventDeliveryFailure = EventDeliveryFailure(1, "x")),
            ChatUi(eventStatus = ChatEventStatus.RECONNECTING),
            ChatUi(eventStatus = ChatEventStatus.CONNECTING),
        ).forEach { state ->
            val banner = selectChatBanner(state, nowMs = 0L)!!
            if (banner.dismissible) dismissible += banner.kind
        }
        assertEquals(
            setOf(
                ChatBannerKind.SEND_ERROR,
                ChatBannerKind.SESSION_ERROR,
                ChatBannerKind.ABORT_NOTICE,
                ChatBannerKind.DELIVERY_FAILURE,
            ),
            dismissible,
        )
    }

    @Test
    fun `削除とエラーはERROR色 再試行と中断はWARNING`() {
        assertEquals(ChatBannerTone.ERROR, selectChatBanner(ChatUi(sessionDeleted = true), 0L)!!.tone)
        assertEquals(ChatBannerTone.ERROR, selectChatBanner(ChatUi(sessionError = "e"), 0L)!!.tone)
        assertEquals(ChatBannerTone.WARNING, selectChatBanner(ChatUi(retry = RetryState(1, "m", null)), 0L)!!.tone)
        assertEquals(ChatBannerTone.WARNING, selectChatBanner(ChatUi(abortNotice = "a"), 0L)!!.tone)
        assertEquals(
            ChatBannerTone.INFO,
            selectChatBanner(ChatUi(eventStatus = ChatEventStatus.CONNECTING), 0L)!!.tone,
        )
    }

    // ---- カウントダウン ----

    @Test
    fun `カウントダウンは切り上げ`() {
        val retry = RetryState(attempt = 2, message = "m", next = 10_000L)
        assertEquals("10秒後に再試行(2回目): m", retryBannerText(retry, nowMs = 0L))
        // 9001ms 残り → 10秒(切り上げ)。9000ms ちょうど → 9秒
        assertEquals("10秒後に再試行(2回目): m", retryBannerText(retry, nowMs = 999L))
        assertEquals("9秒後に再試行(2回目): m", retryBannerText(retry, nowMs = 1_000L))
    }

    @Test
    fun `過ぎた next で負の秒数を出さない`() {
        val retry = RetryState(attempt = 5, message = "m", next = 10_000L)
        assertEquals("まもなく再試行(5回目): m", retryBannerText(retry, nowMs = 10_000L))
        assertEquals("まもなく再試行(5回目): m", retryBannerText(retry, nowMs = 99_999L))
    }

    @Test
    fun `next が無ければ秒数を出さない`() {
        assertEquals("再試行中(1回目): m", retryBannerText(RetryState(1, "m", null), nowMs = 0L))
    }

    @Test
    fun `action_title があれば message より優先する`() {
        val retry = RetryState(
            attempt = 3,
            message = "provider retry",
            next = 5_000L,
            actionTitle = "レート制限",
            actionLabel = "設定を開く",
            actionMessage = "しばらく待つ",
        )
        assertEquals("5秒後に再試行(3回目): レート制限", retryBannerText(retry, nowMs = 0L))
    }

    @Test
    fun `attempt も message も無くても文言が壊れない`() {
        assertEquals("再試行中", retryBannerText(RetryState(null, null, null), nowMs = 0L))
        assertEquals("3秒後に再試行", retryBannerText(RetryState(null, "  ", 3_000L), nowMs = 0L))
    }

    @Test
    fun `配布失敗の帯は件数と直近の例外を出す`() {
        val banner = selectChatBanner(
            ChatUi(eventDeliveryFailure = EventDeliveryFailure(count = 7, lastMessage = "IllegalStateException: boom")),
            nowMs = 0L,
        )!!
        assertTrue(banner.text.contains("7件"))
        assertTrue(banner.text.contains("IllegalStateException: boom"))
    }
}
