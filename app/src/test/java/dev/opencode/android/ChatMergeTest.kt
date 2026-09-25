package dev.opencode.android

import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.MessageInfoDto
import dev.opencode.android.data.PartDto
import dev.opencode.android.data.SseEvent
import dev.opencode.android.ui.ChatMessage
import dev.opencode.android.ui.ChatPart
import dev.opencode.android.ui.applySseToMessages
import dev.opencode.android.ui.initialChatMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * チャット表示モデルのマージ規則。SSEの message.part.updated を part.id 単位で
 * 追加/更新し、対象セッション以外を無視することを固定する。
 */
class ChatMergeTest {

    private val history = listOf(
        MessageEntryDto(
            info = MessageInfoDto(id = "msg_1", sessionID = "ses_a", role = "user"),
            parts = listOf(PartDto(id = "prt_1", messageID = "msg_1", type = "text", text = "質問です")),
        ),
        MessageEntryDto(
            info = MessageInfoDto(id = "msg_2", sessionID = "ses_a", role = "assistant"),
            parts = listOf(PartDto(id = "prt_2", messageID = "msg_2", type = "text", text = "回答")),
        ),
    )

    @Test
    fun `履歴からrole付きメッセージ列を作れる`() {
        val messages = initialChatMessages(history)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
        assertEquals("質問です", messages[0].parts[0].text)
    }

    @Test
    fun `同一part-idの更新は置換される(逐次描画の累積)`() {
        var messages = initialChatMessages(history)
        fun chunk(text: String) = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_9", messageID = "msg_9", type = "text", text = text),
        )

        messages = applySseToMessages(messages, chunk("チャンク1"), "ses_a").messages
        messages = applySseToMessages(messages, chunk("チャンク1チャンク2"), "ses_a").messages
        messages = applySseToMessages(messages, chunk("チャンク1チャンク2チャンク3"), "ses_a").messages

        // 新規メッセージが1つだけ追加され、その本文は最新のもので置換されている
        // roleはmessage.updated到着までnullのまま
        assertEquals(3, messages.size)
        val streamed = messages.last()
        assertEquals("msg_9", streamed.messageId)
        assertNull(streamed.role)
        assertEquals(1, streamed.parts.size)
        assertEquals("チャンク1チャンク2チャンク3", streamed.parts[0].text)
    }

    @Test
    fun `履歴に無いmessageIDは末尾に新規メッセージとして追加される`() {
        val messages = initialChatMessages(history)
        val event = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_9", messageID = "msg_9", type = "text", text = "追"),
        )
        val merged = applySseToMessages(messages, event, "ses_a")
        assertEquals(3, merged.messages.size)
        assertEquals("msg_9", merged.messages[2].messageId)
    }

    @Test
    fun `対象セッション以外のイベントは無視される`() {
        val messages = initialChatMessages(history)
        val event = SseEvent.PartUpdated(
            sessionID = "ses_other",
            part = PartDto(id = "prt_x", messageID = "msg_9", type = "text", text = "別セッション"),
        )
        assertEquals(messages, applySseToMessages(messages, event, "ses_a").messages)
    }

    @Test
    fun `message-updatedやidleはメッセージ列を変えない`() {
        val messages = initialChatMessages(history)
        val updated = SseEvent.MessageUpdated(sessionID = "ses_a", info = null)
        val idle = SseEvent.SessionIdle(sessionID = "ses_a")
        assertEquals(messages, applySseToMessages(messages, updated, "ses_a").messages)
        assertEquals(messages, applySseToMessages(messages, idle, "ses_a").messages)
    }

    @Test
    fun `toolパートはラベル化され未知typeはプレースホルダになる`() {
        val messages = initialChatMessages(
            listOf(
                MessageEntryDto(
                    info = MessageInfoDto(id = "msg_3", sessionID = "ses_a", role = "assistant"),
                    parts = listOf(
                        PartDto(id = "prt_t", messageID = "msg_3", type = "tool", tool = "bash", state = dev.opencode.android.data.ToolStateDto(status = "completed")),
                        PartDto(id = "prt_u", messageID = "msg_3", type = "snapshot"),
                    ),
                ),
            ),
        )
        assertEquals("bash (completed)", messages[0].parts[0].toolLabel)
        assertTrue(messages[0].parts[1].text.isEmpty())
        assertEquals("snapshot", messages[0].parts[1].type)
    }

    @Test
    fun `messageID不明のpartは合成メッセージに集約される(クラッシュしない)`() {
        val messages = initialChatMessages(emptyList())
        val e1 = SseEvent.PartUpdated(sessionID = "ses_a", part = PartDto(type = "text", text = "a"))
        val e2 = SseEvent.PartUpdated(sessionID = "ses_a", part = PartDto(type = "text", text = "b"))
        val merged = applySseToMessages(applySseToMessages(messages, e1, "ses_a").messages, e2, "ses_a")
        assertEquals(1, merged.messages.size)
        assertEquals("stream", merged.messages[0].messageId)
        assertEquals(2, merged.messages[0].parts.size)
    }

    @Test
    fun `part先着→info後着でroleがnullから確定する(回帰 ハードコードassistant除去済み)`() {
        // 履歴なしから開始
        var messages = initialChatMessages(emptyList())

        // 1. message.part.updated が先に到着 (messageID="msg_new")
        val partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_1", messageID = "msg_new", type = "text", text = "Hello"),
        )
        messages = applySseToMessages(messages, partEvent, "ses_a").messages

        // この時点でroleはnull(ハードコードassistantが除去されていること)
        assertEquals(1, messages.size)
        assertEquals("msg_new", messages[0].messageId)
        assertNull(messages[0].role)
        assertEquals("Hello", messages[0].parts[0].text)

        // 2. message.updated が後から到着 (info.role = "assistant")
        val infoEvent = SseEvent.MessageUpdated(
            sessionID = "ses_a",
            info = MessageInfoDto(id = "msg_new", sessionID = "ses_a", role = "assistant"),
        )
        messages = applySseToMessages(messages, infoEvent, "ses_a").messages

        // roleがassistantに確定
        assertEquals("assistant", messages[0].role)
    }

    @Test
    fun `userロールのエコーがuserのまま描画される(assistantに化けない)`() {
        // ユーザーが送信したメッセージのエコー: part先着→info後着でrole="user"
        var messages = initialChatMessages(emptyList())

        // 1. ユーザー入力のパートが先にストリーミングで到着
        val partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_user", messageID = "msg_user_echo", type = "text", text = "私の質問"),
        )
        messages = applySseToMessages(messages, partEvent, "ses_a").messages

        // この時点でroleはnull
        assertNull(messages[0].role)

        // 2. message.updated でinfo.role="user"が到着
        val infoEvent = SseEvent.MessageUpdated(
            sessionID = "ses_a",
            info = MessageInfoDto(id = "msg_user_echo", sessionID = "ses_a", role = "user"),
        )
        messages = applySseToMessages(messages, infoEvent, "ses_a").messages

        // roleがuserのまま確定(assistantに上書きされない)
        assertEquals("user", messages[0].role)
        assertEquals("私の質問", messages[0].parts[0].text)
    }

    @Test
    fun `messageUpdatedで存在しないmessageIDは無視される`() {
        val messages = initialChatMessages(emptyList())
        val infoEvent = SseEvent.MessageUpdated(
            sessionID = "ses_a",
            info = MessageInfoDto(id = "nonexistent", sessionID = "ses_a", role = "assistant"),
        )
        // メッセージ列は変わらない
        assertEquals(messages, applySseToMessages(messages, infoEvent, "ses_a").messages)
    }

    @Test
    fun `messageUpdatedでroleがnullのinfoはroleを変更しない`() {
        var messages = initialChatMessages(emptyList())
        val partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_1", messageID = "msg_1", type = "text", text = "Hello"),
        )
        messages = applySseToMessages(messages, partEvent, "ses_a").messages

        // info.role = null のイベント
        val infoEvent = SseEvent.MessageUpdated(
            sessionID = "ses_a",
            info = MessageInfoDto(id = "msg_1", sessionID = "ses_a", role = null),
        )
        messages = applySseToMessages(messages, infoEvent, "ses_a").messages

        // roleはnullのまま
        assertNull(messages[0].role)
    }

    @Test
    fun `messageUpdatedで既にrole確定済みなら上書きしない`() {
        var messages = initialChatMessages(emptyList())
        val partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_1", messageID = "msg_1", type = "text", text = "Hello"),
        )
        messages = applySseToMessages(messages, partEvent, "ses_a").messages

        // 最初のinfoでrole確定
        val infoEvent1 = SseEvent.MessageUpdated(
            sessionID = "ses_a",
            info = MessageInfoDto(id = "msg_1", sessionID = "ses_a", role = "user"),
        )
        messages = applySseToMessages(messages, infoEvent1, "ses_a").messages
        assertEquals("user", messages[0].role)

        // 2回目のinfoで異なるroleが来ても上書きしない
        val infoEvent2 = SseEvent.MessageUpdated(
            sessionID = "ses_a",
            info = MessageInfoDto(id = "msg_1", sessionID = "ses_a", role = "assistant"),
        )
        messages = applySseToMessages(messages, infoEvent2, "ses_a").messages
        assertEquals("user", messages[0].role)
    }

    // ===== Defect A 回帰テスト: message.updated が part より先に届くケース =====

    @Test
    fun `messageUpdatedがpartより先に届いた場合roleを保持し後からpart到着で確定する(Defect A)`() {
        // 履歴なしから開始
        var messages = initialChatMessages(emptyList())
        var pendingRoles = emptyMap<String, String>()

        // 1. message.updated が先に到着 (info.role = "assistant", messageID="msg_new")
        val infoEvent = SseEvent.MessageUpdated(
            sessionID = "ses_a",
            info = MessageInfoDto(id = "msg_new", sessionID = "ses_a", role = "assistant"),
        )
        var result = applySseToMessages(messages, infoEvent, "ses_a", pendingRoles)
        messages = result.messages
        pendingRoles = result.pendingRoles

        // この時点ではメッセージはまだ追加されず、roleが保留マップに記録される
        assertTrue(messages.isEmpty())
        assertEquals("assistant", pendingRoles["msg_new"])

        // 2. message.part.updated が後から到着
        val partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_1", messageID = "msg_new", type = "text", text = "Hello"),
        )
        result = applySseToMessages(messages, partEvent, "ses_a", pendingRoles)
        messages = result.messages
        pendingRoles = result.pendingRoles

        // メッセージが作成され、保留されていたroleが適用される
        assertEquals(1, messages.size)
        assertEquals("msg_new", messages[0].messageId)
        assertEquals("assistant", messages[0].role)
        assertEquals("Hello", messages[0].parts[0].text)
        // 保留マップからは消費済みで削除される
        assertFalse(pendingRoles.containsKey("msg_new"))
    }

    @Test
    fun `messageUpdated先着でuser役割が保持されpart到着でuser確定する(Defect A_user)`() {
        var messages = initialChatMessages(emptyList())
        var pendingRoles = emptyMap<String, String>()

        // 1. message.updated が先に到着 (info.role = "user")
        val infoEvent = SseEvent.MessageUpdated(
            sessionID = "ses_a",
            info = MessageInfoDto(id = "msg_user", sessionID = "ses_a", role = "user"),
        )
        var result = applySseToMessages(messages, infoEvent, "ses_a", pendingRoles)
        messages = result.messages
        pendingRoles = result.pendingRoles

        assertTrue(messages.isEmpty())
        assertEquals("user", pendingRoles["msg_user"])

        // 2. message.part.updated が後から到着
        val partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_1", messageID = "msg_user", type = "text", text = "私の質問"),
        )
        result = applySseToMessages(messages, partEvent, "ses_a", pendingRoles)
        messages = result.messages
        pendingRoles = result.pendingRoles

        assertEquals(1, messages.size)
        assertEquals("msg_user", messages[0].messageId)
        assertEquals("user", messages[0].role)
        assertEquals("私の質問", messages[0].parts[0].text)
        assertFalse(pendingRoles.containsKey("msg_user"))
    }

    // ===== Defect B 回帰テスト: ローカルエコーとSSEエコーの重複排除 =====

    @Test
    fun `ローカルエコー挿入後に同一テキストのSSEパート到着で重複せずマージされる(Defect B)`() {
        // 送信直後の状態を模擬: ローカルエコー(isLocalEcho=true, role="user")が既にある
        var messages = initialChatMessages(emptyList())
        val localEcho = ChatMessage(
            messageId = "local-echo-123",
            role = "user",
            parts = listOf(ChatPart(partId = null, type = "text", text = "私の質問", toolLabel = null)),
            isLocalEcho = true,
        )
        messages = messages + localEcho

        // SSE経由でユーザーメッセージのエコーが到着 (messageID="msg_server_1")
        val partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_1", messageID = "msg_server_1", type = "text", text = "私の質問"),
        )
        val result = applySseToMessages(messages, partEvent, "ses_a", emptyMap())
        messages = result.messages

        // 重複せず1つのメッセージにマージされる
        assertEquals(1, messages.size)
        val merged = messages[0]
        // messageIdはサーバー割り当てのものに更新される
        assertEquals("msg_server_1", merged.messageId)
        // roleはuserのまま
        assertEquals("user", merged.role)
        // isLocalEchoはfalseになる
        assertFalse(merged.isLocalEcho)
        // パートは1つ(ローカルエコーのプレースホルダpartId=nullが、到着partで置換される)
        assertEquals(1, merged.parts.size)
        assertEquals("prt_1", merged.parts[0].partId)
        assertEquals("私の質問", merged.parts[0].text)
    }

    @Test
    fun `ローカルエコーのテキストが部分一致でもマージされる(ストリーミング先頭チャンク)`() {
        // ローカルエコーは完全なテキストを持つ
        var messages = initialChatMessages(emptyList())
        val localEcho = ChatMessage(
            messageId = "local-echo-456",
            role = "user",
            parts = listOf(ChatPart(partId = null, type = "text", text = "長い質問文です", toolLabel = null)),
            isLocalEcho = true,
        )
        messages = messages + localEcho

        // SSE最初のチャンクが到着 (先頭部分のみ)
        val partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_1", messageID = "msg_server_2", type = "text", text = "長い質問"),
        )
        val result = applySseToMessages(messages, partEvent, "ses_a", emptyMap())
        messages = result.messages

        // マージされる (ローカルエコーのテキストが到着チャンクで始まるため)
        assertEquals(1, messages.size)
        val merged = messages[0]
        assertEquals("msg_server_2", merged.messageId)
        assertEquals("user", merged.role)
        assertFalse(merged.isLocalEcho)
        assertEquals(1, merged.parts.size)
        assertEquals("prt_1", merged.parts[0].partId)
        assertEquals("長い質問", merged.parts[0].text) // 最初のチャンクで置換される
    }

    @Test
    fun `ローカルエコーでないメッセージはマージ対象外(assistantのストリーミングは別メッセージになる)`() {
        // assistantのストリーミングメッセージ(ローカルエコーではない)が既にある
        var messages = initialChatMessages(emptyList())
        val assistantMsg = ChatMessage(
            messageId = "msg_assistant_1",
            role = "assistant",
            parts = listOf(ChatPart(partId = "prt_a", type = "text", text = "回答", toolLabel = null)),
            isLocalEcho = false,
        )
        messages = messages + assistantMsg

        // 別のmessageIDでpartが到着 -> 新規メッセージとして追加される
        val partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_b", messageID = "msg_new_2", type = "text", text = "新規"),
        )
        val result = applySseToMessages(messages, partEvent, "ses_a", emptyMap())
        messages = result.messages

        assertEquals(2, messages.size)
        assertEquals("msg_assistant_1", messages[0].messageId)
        assertEquals("msg_new_2", messages[1].messageId)
    }

    @Test
    fun `ユーザー発言がassistantに化けない(ローカルエコーuser役割が維持される)`() {
        // Defect Bの核心: ユーザー送信時のローカルエコーがuser役割のまま確定すること
        var messages = initialChatMessages(emptyList())
        val localEcho = ChatMessage(
            messageId = "local-echo-789",
            role = "user",
            parts = listOf(ChatPart(partId = null, type = "text", text = "ユーザーの発言", toolLabel = null)),
            isLocalEcho = true,
        )
        messages = messages + localEcho

        // 1. SSE part到着でマージ
        var partEvent = SseEvent.PartUpdated(
            sessionID = "ses_a",
            part = PartDto(id = "prt_1", messageID = "msg_server_3", type = "text", text = "ユーザーの発言"),
        )
        var result = applySseToMessages(messages, partEvent, "ses_a", emptyMap())
        messages = result.messages

        // 2. 後からmessage.updatedでrole="assistant"が来ても上書きされない
        val infoEvent = SseEvent.MessageUpdated(
            sessionID = "ses_a",
            info = MessageInfoDto(id = "msg_server_3", sessionID = "ses_a", role = "assistant"),
        )
        result = applySseToMessages(messages, infoEvent, "ses_a", emptyMap())
        messages = result.messages

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role) // userのまま維持
        assertEquals("ユーザーの発言", messages[0].parts[0].text)
    }
}