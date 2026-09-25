package dev.opencode.android

import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.contractJson
import dev.opencode.android.ui.BubbleKind
import dev.opencode.android.ui.bubbleKindOf
import dev.opencode.android.ui.initialChatMessages
import dev.opencode.android.ui.isRenderable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1(QUALITY_PLAN §5 Q0 スコープ2): part を1つも「描かない」assistantメッセージを
 * 空バブルとして出さず、**ストリーミング中(busy)と完了後に空だった場合を区別する**。
 *
 * フィクスチャは `docs/spec/opencode-1.18.21-openapi.json` の shape をそのまま貼る
 * (TextPart / StepStartPart / StepFinishPart / PatchPart / ToolPart の required を満たす形)。
 * 「近似した文字列」を作らない — P3 role / P4 metadata / P4 PermissionRepliedEvent /
 * L3 session.error はすべて、フィクスチャが実データと違う形だったためにテストが緑のまま
 * 症状が残った。
 */
class EmptyBubbleTest {

    private fun entries(json: String): List<MessageEntryDto> =
        contractJson.decodeFromString(ListSerializer(MessageEntryDto.serializer()), json)

    /** GET /session/{id}/message の1要素。assistant で parts が空(R1の元の症状)。 */
    private val emptyAssistantHistory = """
        [
          {
            "info": {"id":"msg_e1","sessionID":"ses_stub_0001","role":"assistant"},
            "parts": []
          }
        ]
    """.trimIndent()

    /**
     * 実物 serve が返した形(P5実測、TEST_REPORT「実物の part 語彙のほうがアプリのモデルより広い」)。
     * **parts は空ではない**が、中身はライフサイクルの目印だけなので描くものが無い。
     * `parts.isEmpty()` で判定すると、この形は素通りして空バブルが残る。
     */
    private val lifecycleOnlyHistory = """
        [
          {
            "info": {"id":"msg_e2","sessionID":"ses_stub_0001","role":"assistant"},
            "parts": [
              {"id":"prt_s1","sessionID":"ses_stub_0001","messageID":"msg_e2","type":"step-start"},
              {"id":"prt_s2","sessionID":"ses_stub_0001","messageID":"msg_e2","type":"step-finish",
               "reason":"stop","cost":0.0,"tokens":{"input":11,"output":0,"reasoning":0,
               "cache":{"read":0,"write":0}}}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun `partが空のassistantは完了後なら応答なし表示になる`() {
        val message = initialChatMessages(entries(emptyAssistantHistory)).single()
        assertEquals(0, message.parts.size)
        assertEquals(
            BubbleKind.NO_RESPONSE,
            bubbleKindOf(message, isLast = true, busy = false),
        )
    }

    @Test
    fun `partが空のassistantはbusy中の末尾なら待ち表示になる`() {
        val message = initialChatMessages(entries(emptyAssistantHistory)).single()
        assertEquals(
            BubbleKind.WAITING,
            bubbleKindOf(message, isLast = true, busy = true),
        )
    }

    @Test
    fun `busy中でも末尾でない空assistantは応答なし扱い`() {
        val message = initialChatMessages(entries(emptyAssistantHistory)).single()
        assertEquals(
            BubbleKind.NO_RESPONSE,
            bubbleKindOf(message, isLast = false, busy = true),
        )
    }

    @Test
    fun `step-startとstep-finishだけのassistantも空として扱う`() {
        val message = initialChatMessages(entries(lifecycleOnlyHistory)).single()
        // partsは2件ある(=「partが1つも無い」では判定できない)
        assertEquals(2, message.parts.size)
        assertFalse(message.parts.any { it.isRenderable() })
        assertEquals(BubbleKind.NO_RESPONSE, bubbleKindOf(message, isLast = true, busy = false))
        assertEquals(BubbleKind.WAITING, bubbleKindOf(message, isLast = true, busy = true))
    }

    @Test
    fun `本文のあるtext partはbusyでも通常表示のまま`() {
        val history = """
            [
              {
                "info": {"id":"msg_t1","sessionID":"ses_stub_0001","role":"assistant"},
                "parts": [
                  {"id":"prt_t1","sessionID":"ses_stub_0001","messageID":"msg_t1",
                   "type":"text","text":"履歴側のアシスタント応答です"}
                ]
              }
            ]
        """.trimIndent()
        val message = initialChatMessages(entries(history)).single()
        assertEquals(BubbleKind.CONTENT, bubbleKindOf(message, isLast = true, busy = true))
        assertEquals(BubbleKind.CONTENT, bubbleKindOf(message, isLast = true, busy = false))
    }

    @Test
    fun `本文が空文字のtext partは描画対象ではない`() {
        val history = """
            [
              {
                "info": {"id":"msg_t2","sessionID":"ses_stub_0001","role":"assistant"},
                "parts": [
                  {"id":"prt_t2","sessionID":"ses_stub_0001","messageID":"msg_t2",
                   "type":"text","text":""}
                ]
              }
            ]
        """.trimIndent()
        val message = initialChatMessages(entries(history)).single()
        assertEquals(1, message.parts.size)
        assertFalse(message.parts.single().isRenderable())
        assertEquals(BubbleKind.NO_RESPONSE, bubbleKindOf(message, isLast = true, busy = false))
    }

    @Test
    fun `本文を持たないpatch partは存在を示すので空ではない`() {
        // PatchPart required: id, sessionID, messageID, type, hash, files
        val history = """
            [
              {
                "info": {"id":"msg_p1","sessionID":"ses_stub_0001","role":"assistant"},
                "parts": [
                  {"id":"prt_p1","sessionID":"ses_stub_0001","messageID":"msg_p1",
                   "type":"patch","hash":"abc123","files":["app/build.gradle.kts"]}
                ]
              }
            ]
        """.trimIndent()
        val message = initialChatMessages(entries(history)).single()
        assertTrue(message.parts.single().isRenderable())
        assertEquals(BubbleKind.CONTENT, bubbleKindOf(message, isLast = true, busy = false))
    }

    @Test
    fun `tool partだけのメッセージは空ではない`() {
        val history = """
            [
              {
                "info": {"id":"msg_o1","sessionID":"ses_stub_0001","role":"assistant"},
                "parts": [
                  {"id":"prt_o1","sessionID":"ses_stub_0001","messageID":"msg_o1",
                   "type":"tool","callID":"call_1","tool":"read",
                   "state":{"status":"running","input":{"filePath":"README.md"}}}
                ]
              }
            ]
        """.trimIndent()
        val message = initialChatMessages(entries(history)).single()
        assertTrue(message.parts.single().isRenderable())
        assertEquals(BubbleKind.CONTENT, bubbleKindOf(message, isLast = true, busy = true))
    }

    @Test
    fun `中身の無いuserメッセージはバブルごと描かない`() {
        val history = """
            [
              {
                "info": {"id":"msg_u1","sessionID":"ses_stub_0001","role":"user"},
                "parts": []
              }
            ]
        """.trimIndent()
        val message = initialChatMessages(entries(history)).single()
        assertEquals(BubbleKind.HIDDEN, bubbleKindOf(message, isLast = true, busy = false))
        assertEquals(BubbleKind.HIDDEN, bubbleKindOf(message, isLast = true, busy = true))
    }

    @Test
    fun `roleが欠けた空メッセージも完了後は応答なし表示`() {
        // ストリーミング途中は role が確定しない(message.updated 未着)ことがある。
        val history = """
            [
              { "info": {"id":"msg_n1","sessionID":"ses_stub_0001"}, "parts": [] }
            ]
        """.trimIndent()
        val message = initialChatMessages(entries(history)).single()
        assertEquals(null, message.role)
        assertEquals(BubbleKind.WAITING, bubbleKindOf(message, isLast = true, busy = true))
        assertEquals(BubbleKind.NO_RESPONSE, bubbleKindOf(message, isLast = true, busy = false))
    }
}
