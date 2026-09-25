package dev.opencode.android

import dev.opencode.android.ui.ChatMessage
import dev.opencode.android.ui.ChatPart
import dev.opencode.android.ui.ChatToolInfo
import dev.opencode.android.ui.ToolRunStatus
import dev.opencode.android.ui.isRenderable
import dev.opencode.android.ui.messageCopyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 長押しコピー(QUALITY_PLAN §5 Q2 スコープ4)と reasoning の描画可否。
 *
 * コピーは**描画と同じ取捨選択**でなければならない。画面に出ていない
 * `step-start` / `step-finish` を貼り付け先に混ぜない、というのがゲートの実質。
 * エミュレータのクリップボード実測は環境依存で「ブロックしない」項目なので、
 * **渡す文字列そのもの**をここで固定する。
 */
class ChatCopyTest {

    private fun text(t: String) = ChatPart(partId = "prt_$t", type = "text", text = t, toolLabel = null)

    private fun lifecycle(type: String) = ChatPart(partId = "prt_$type", type = type, text = "", toolLabel = null)

    private fun tool() = ChatPart(
        partId = "prt_tool",
        type = "tool",
        text = "",
        toolLabel = "bash (completed)",
        tool = ChatToolInfo(
            name = "bash",
            status = ToolRunStatus.COMPLETED,
            title = "bash: ls",
            input = "command=ls",
            output = "total 8",
            error = null,
        ),
    )

    private fun reasoning(t: String) = ChatPart(partId = "prt_r", type = "reasoning", text = t, toolLabel = null)

    @Test
    fun `ライフサイクルpartはコピーに混ざらない`() {
        val message = ChatMessage(
            messageId = "msg_a",
            role = "assistant",
            parts = listOf(lifecycle("step-start"), text("答えです"), lifecycle("step-finish")),
        )
        assertEquals("答えです", messageCopyText(message))
    }

    @Test
    fun `複数のテキストは空行で連結する`() {
        val message = ChatMessage("msg_a", "assistant", listOf(text("一つ目"), text("二つ目")))
        assertEquals("一つ目\n\n二つ目", messageCopyText(message))
    }

    @Test
    fun `ツールは状態と詳細を持って出る`() {
        val message = ChatMessage("msg_a", "assistant", listOf(tool()))
        assertEquals(
            "[bash: completed]\nbash: ls\ninput: command=ls\noutput: total 8",
            messageCopyText(message),
        )
    }

    @Test
    fun `reasoningは本文としてコピーされる`() {
        val message = ChatMessage("msg_a", "assistant", listOf(reasoning("考えた内容"), text("結論")))
        assertEquals("考えた内容\n\n結論", messageCopyText(message))
    }

    @Test
    fun `markdownは整形せず生のまま渡す`() {
        val src = "# 見出し\n\n- 箇条書き\n\n```kotlin\nval x = 1\n```"
        val message = ChatMessage("msg_a", "assistant", listOf(text(src)))
        assertEquals(src, messageCopyText(message))
    }

    @Test
    fun `描くものが無いメッセージは空文字`() {
        val message = ChatMessage("msg_a", "assistant", listOf(lifecycle("step-start")))
        assertEquals("", messageCopyText(message))
    }

    // ---- 描画可否(reasoning を足したので isRenderable の分岐が増えている) ----

    @Test
    fun `本文のあるreasoningは描く`() {
        assertTrue(reasoning("考えた内容").isRenderable())
    }

    @Test
    fun `本文の無いreasoningは描かない`() {
        // 空の「思考」の折り畳みだけが並ぶのを防ぐ。
        assertFalse(reasoning("").isRenderable())
    }

    @Test
    fun `ライフサイクルpartは描かない`() {
        assertFalse(lifecycle("step-start").isRenderable())
        assertFalse(lifecycle("step-finish").isRenderable())
        assertFalse(lifecycle("snapshot").isRenderable())
    }

    @Test
    fun `テキストの無い未知typeは存在だけ描く`() {
        assertTrue(lifecycle("patch").isRenderable())
    }
}
