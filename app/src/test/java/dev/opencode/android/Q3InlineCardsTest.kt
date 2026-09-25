package dev.opencode.android

import dev.opencode.android.ui.ChatInlineCard
import dev.opencode.android.ui.ChatUi
import dev.opencode.android.ui.PermissionDialogState
import dev.opencode.android.ui.QuestionCardState
import dev.opencode.android.ui.QuestionItem
import dev.opencode.android.ui.QuestionResolution
import dev.opencode.android.ui.QuestionOption
import dev.opencode.android.ui.QuestionSelection
import dev.opencode.android.ui.TodoItem
import dev.opencode.android.ui.TodoStatus
import dev.opencode.android.ui.selectChatBanner
import dev.opencode.android.ui.selectChatInlineCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * インラインカードの**出し分け**(`selectChatInlineCards`)。
 *
 * なぜ関数に出してテストするのか: Q1/Q2 のレビューが2度示したのは
 * 「**配線に条件を書くと、その条件はテストの外に出る**」ことだった。
 * `ChatScreen.kt` に `if (ui.todos.isNotEmpty())` と書けば、それを消す変異は全緑で通り抜け、
 * **画面は正常に見えたままカードだけが出なくなる**。ここが唯一の出し分けの所在である。
 */
class Q3InlineCardsTest {

    private fun card(id: String = "que_abc", resolution: QuestionResolution = QuestionResolution.PENDING) = QuestionCardState(
        requestId = id,
        sessionId = "ses_1",
        questions = listOf(
            QuestionItem("q", "h", listOf(QuestionOption("A", "d")), multiple = false, custom = false),
        ),
        selections = listOf(QuestionSelection()),
        resolution = resolution,
    )

    private val todos = listOf(TodoItem("t", TodoStatus.PENDING))

    @Test
    fun `何も無ければカードを出さない`() {
        assertEquals(emptyList<ChatInlineCard>(), selectChatInlineCards(ChatUi()))
    }

    @Test
    fun `todo だけ、質問だけ、両方の3通り`() {
        assertEquals(listOf(ChatInlineCard.Todo), selectChatInlineCards(ChatUi(todos = todos)))
        assertEquals(
            listOf(ChatInlineCard.Question("que_abc")),
            selectChatInlineCards(ChatUi(questions = listOf(card()))),
        )
        assertEquals(
            listOf(ChatInlineCard.Todo, ChatInlineCard.Question("que_abc")),
            selectChatInlineCards(ChatUi(todos = todos, questions = listOf(card()))),
        )
    }

    @Test
    fun `空の todo リストではカードを出さない`() {
        // 「タスクが無い」ときに空カードが残ると、実行前から枠だけが見える。
        assertEquals(emptyList<ChatInlineCard>(), selectChatInlineCards(ChatUi(todos = emptyList())))
    }

    @Test
    fun `未応答の質問は何件でも全部描く`() {
        // Q3 レビュー blocker-1。1枚しか描かない実装はここで落ちる。
        val ui = ChatUi(questions = listOf(card("que_A"), card("que_B"), card("que_C")))
        assertEquals(
            listOf(
                ChatInlineCard.Question("que_A"),
                ChatInlineCard.Question("que_B"),
                ChatInlineCard.Question("que_C"),
            ),
            selectChatInlineCards(ui),
        )
    }

    @Test
    fun `未応答は3件以上でも1件も落とさない`() {
        // E2E 所見 F2 の状態層側の固定。描画層は ChatInlineCards のスクロールが受け持つ。
        val ui = ChatUi(questions = (1..5).map { card("que_$it") })
        assertEquals(
            (1..5).map { ChatInlineCard.Question("que_$it") },
            selectChatInlineCards(ui),
        )
    }

    @Test
    fun `上限で省いた決着済みの件数を画面に出す`() {
        // E2E 所見: 上限で落とすのはよいが**無言だった**。件数だけでも出す。
        val ui = ChatUi(
            questions = listOf(
                card("que_1", QuestionResolution.ANSWERED),
                card("que_2", QuestionResolution.ANSWERED),
                card("que_3", QuestionResolution.ANSWERED),
                card("que_4", QuestionResolution.REJECTED),
                card("que_5"),
            ),
        )
        assertEquals(
            listOf(
                ChatInlineCard.ResolvedOverflow(2),
                ChatInlineCard.Question("que_3"),
                ChatInlineCard.Question("que_4"),
                ChatInlineCard.Question("que_5"),
            ),
            selectChatInlineCards(ui),
        )
    }

    @Test
    fun `省いた件数が0なら注記を出さない`() {
        val ui = ChatUi(
            questions = listOf(
                card("que_1", QuestionResolution.ANSWERED),
                card("que_2", QuestionResolution.ANSWERED),
            ),
        )
        assertEquals(
            listOf(ChatInlineCard.Question("que_1"), ChatInlineCard.Question("que_2")),
            selectChatInlineCards(ui),
        )
    }

    @Test
    fun `決着済みは直近2件だけ残し、未応答は入力欄に近い側へ寄せる`() {
        // 決着済みを無制限に積むと、長いセッションで入力欄が画面外へ出る
        // (2026-08-27 に実機で1枚でも起きた形)。数値で固定する。
        val ui = ChatUi(
            questions = listOf(
                card("que_old1", QuestionResolution.ANSWERED),
                card("que_old2", QuestionResolution.REJECTED),
                card("que_old3", QuestionResolution.EXPIRED),
                card("que_live", QuestionResolution.PENDING),
            ),
        )
        assertEquals(
            listOf(
                // 省いた1件は無言にしない
                ChatInlineCard.ResolvedOverflow(1),
                ChatInlineCard.Question("que_old2"),
                ChatInlineCard.Question("que_old3"),
                ChatInlineCard.Question("que_live"),
            ),
            selectChatInlineCards(ui),
        )
    }

    @Test
    fun `未応答が複数あれば決着済みより後ろに全部並ぶ`() {
        val ui = ChatUi(
            questions = listOf(
                card("que_done", QuestionResolution.ANSWERED),
                card("que_a"),
                card("que_b"),
            ),
        )
        assertEquals(
            listOf(
                ChatInlineCard.Question("que_done"),
                ChatInlineCard.Question("que_a"),
                ChatInlineCard.Question("que_b"),
            ),
            selectChatInlineCards(ui),
        )
    }

    @Test
    fun `質問カードは帯を1本も増やさない`() {
        // §5 Q2 スコープ6 / RUN_PLAN「帯は1本」。Q3 は4本目を足さない。
        val ui = ChatUi(
            todos = todos,
            questions = listOf(card()),
            permissionDialog = PermissionDialogState(
                permissionId = "per_1",
                sessionId = "ses_1",
                permission = "bash",
                metadata = null,
                patterns = emptyList(),
                always = emptyList(),
                tool = "bash",
            ),
        )
        assertNull("todo/question/permission だけでは帯は出ない", selectChatBanner(ui, 0L))
    }

    @Test
    fun `permission と同時でもインラインカードは変わらない`() {
        // permission はモーダルで別枠(§5 Q3 スコープ3)。ここには入らない。
        val ui = ChatUi(
            questions = listOf(card()),
            permissionDialog = PermissionDialogState(
                permissionId = "per_1",
                sessionId = "ses_1",
                permission = "bash",
                metadata = null,
                patterns = emptyList(),
                always = emptyList(),
                tool = "bash",
            ),
        )
        assertEquals(listOf(ChatInlineCard.Question("que_abc")), selectChatInlineCards(ui))
    }
}
