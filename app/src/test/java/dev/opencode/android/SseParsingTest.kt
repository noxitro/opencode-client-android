package dev.opencode.android

import dev.opencode.android.data.PermissionAskedEvent
import dev.opencode.android.data.PermissionRepliedEvent
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.parseSseEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE envelope {id, type, properties} のパース固定テスト。
 * 契約(docs/API_CONTRACT.md「SSEイベント」)のtypeのみを処理し、未知typeは破棄(Ignored)、
 * JSON不正はnull。どの場合も例外を投げてストリームを落としてはならない。
 */
class SseParsingTest {

    @Test
    fun `message-part-updatedをパースできる(未知フィールド付き)`() {
        val text = """
            {
              "id": "evt_1",
              "type": "message.part.updated",
              "properties": {
                "sessionID": "ses_a",
                "part": {
                  "id": "prt_1", "sessionID": "ses_a", "messageID": "msg_2",
                  "type": "text", "text": "こんにちは",
                  "time": {"start": 1}, "metadata": {"x": 1}
                },
                "time": {"start": 1}
              }
            }
        """.trimIndent()
        val event = parseSseEnvelope(text)
        assertTrue(event is SseEvent.PartUpdated)
        event as SseEvent.PartUpdated
        assertEquals("ses_a", event.sessionID)
        assertEquals("prt_1", event.part.id)
        assertEquals("msg_2", event.part.messageID)
        assertEquals("こんにちは", event.part.text)
    }

    @Test
    fun `session-idleとmessage-updatedとserver-connectedをパースできる`() {
        val idle = parseSseEnvelope("""{"id":"e","type":"session.idle","properties":{"sessionID":"ses_a"}}""")
        assertTrue(idle is SseEvent.SessionIdle)
        assertEquals("ses_a", (idle as SseEvent.SessionIdle).sessionID)

        val updated = parseSseEnvelope(
            """{"id":"e","type":"message.updated","properties":{"sessionID":"ses_a","info":{"id":"msg_1","role":"assistant"}}}""",
        )
        assertTrue(updated is SseEvent.MessageUpdated)

        val connected = parseSseEnvelope("""{"id":"e","type":"server.connected","properties":{}}""")
        assertEquals(SseEvent.ServerConnected, connected)
    }

    @Test
    fun `session-errorはsessionID無しでもパースできる(properties必須項目なし)`() {
        val noSid = parseSseEnvelope("""{"id":"e","type":"session.error","properties":{}}""")
        assertTrue(noSid is SseEvent.SessionError)
        assertNull((noSid as SseEvent.SessionError).sessionID)

        val withSid = parseSseEnvelope(
            """{"id":"e","type":"session.error","properties":{"sessionID":"ses_b"}}""",
        )
        assertEquals("ses_b", (withSid as SseEvent.SessionError).sessionID)
    }

    /**
     * 実物 serve 1.18.21 が実際に送った形をそのまま貼っている(実測 2026-08-25、P5)。
     * error は**オブジェクト**であり、文字列ではない。
     *
     * この形を文字列と決め打って `jsonPrimitive` を呼ぶと JsonObject に対して例外を投げ、
     * それが parseSseEnvelope を抜けて onEvent まで達し、イベントごと消える——
     * つまり「エラーを表示する」ための変更が「エラーを表示しなくする」変更になる。
     * このテストが守っているのはその一点なので、フィクスチャは実データ以外にしないこと。
     */
    @Test
    fun `session-errorのerrorはオブジェクトで届き、messageを取り出せる`() {
        val real = parseSseEnvelope(
            """{"id":"e","type":"session.error","properties":{"sessionID":"ses_c",""" +
                """"error":{"name":"APIError","data":{"message":"[Stealth] stealth/ox-alpha is """ +
                """temporarily rate-limited upstream. Please retry shortly.","statusCode":429,""" +
                """"isRetryable":true}}}}""",
        )
        assertTrue(real is SseEvent.SessionError)
        assertEquals("ses_c", (real as SseEvent.SessionError).sessionID)
        assertEquals(
            "[Stealth] stealth/ox-alpha is temporarily rate-limited upstream. Please retry shortly.",
            real.error,
        )
    }

    @Test
    fun `session-errorのerrorが文字列でも読める(契約は形を定めていない)`() {
        val asString = parseSseEnvelope(
            """{"id":"e","type":"session.error","properties":{"error":"something broke"}}""",
        )
        assertEquals("something broke", (asString as SseEvent.SessionError).error)
    }

    /** 読めない形でも**イベントは生き残る**。落とすと画面から赤帯ごと消える。 */
    @Test
    fun `session-errorのerrorが未知の形でもイベントを落とさない`() {
        for (shape in listOf("""["a","b"]""", "42", "null", """{"unexpected":true}""")) {
            val event = parseSseEnvelope(
                """{"id":"e","type":"session.error","properties":{"sessionID":"ses_d","error":$shape}}""",
            )
            assertTrue("shape=$shape", event is SseEvent.SessionError)
            assertEquals("shape=$shape", "ses_d", (event as SseEvent.SessionError).sessionID)
        }
    }

    /** name しか無ければ、せめて name を出す(無言の汎用文言より手掛かりが多い)。 */
    @Test
    fun `session-errorのerrorにdataが無ければnameを使う`() {
        val event = parseSseEnvelope(
            """{"id":"e","type":"session.error","properties":{"error":{"name":"APIError"}}}""",
        )
        assertEquals("APIError", (event as SseEvent.SessionError).error)
    }

    @Test
    fun `未知のtypeはIgnoredになり破棄できる(v2系・でっち上げ両方)`() {
        val v2 = parseSseEnvelope(
            """{"id":"e","type":"session.next.text_delta","properties":{"delta":"x"}}""",
        )
        assertTrue(v2 is SseEvent.Ignored)
        assertEquals("session.next.text_delta", (v2 as SseEvent.Ignored).type)

        val bogus = parseSseEnvelope("""{"id":"e","type":"stub.unknown_event","properties":{}}""")
        assertTrue(bogus is SseEvent.Ignored)
    }

    @Test
    fun `JSON不正やtype欠損はnull、非文字列typeはIgnored(例外を投げない)`() {
        assertNull(parseSseEnvelope("not json at all"))
        assertNull(parseSseEnvelope("""{"id":"e","properties":{}}"""))
        // typeが文字列以外は契約外のenvelope。未知typeとして破棄(Ignored)され、例外で落とされない。
        val numeric = parseSseEnvelope("""{"id":"e","type":123,"properties":{}}""")
        assertTrue(numeric is SseEvent.Ignored)
    }

    @Test
    fun `part要素が壊れていてもイベント単位で破棄される(Ignored)`() {
        val broken = parseSseEnvelope(
            """{"id":"e","type":"message.part.updated","properties":{"sessionID":"ses_a","part":"oops"}}""",
        )
        assertTrue(broken is SseEvent.Ignored)
    }

    @Test
    fun `permission-askedをパースできる(全フィールド・未知フィールド無視)`() {
        val text = """
            {
              "id": "evt_1",
              "type": "permission.asked",
              "properties": {
                "id": "per_123",
                "sessionID": "ses_abc",
                "permission": "tool:read",
                "patterns": ["**/*.kt", "**/*.java"],
                "metadata": {"command": "rm -rf /tmp/demo", "reason": "破壊的コマンド"},
                "always": ["tool:write"],
                "tool": {"messageID": "msg_1", "callID": "call_1"},
                "unknownField": "should be ignored"
              }
            }
        """.trimIndent()
        val event = parseSseEnvelope(text)
        assertTrue(event is PermissionAskedEvent)
        event as PermissionAskedEvent
        assertEquals("per_123", event.id)
        assertEquals("ses_abc", event.sessionID)
        assertEquals("tool:read", event.permission)
        assertEquals(listOf("**/*.kt", "**/*.java"), event.patterns)
        // オブジェクトのまま保持されること。String? と宣言していた版はここで
        // SerializationException になり、パースが Ignored に落ちてダイアログが出なかった。
        assertNotNull(event.metadata)
        assertTrue(event.metadata.toString().contains("rm -rf /tmp/demo"))
        assertEquals(listOf("tool:write"), event.always)
        assertNotNull(event.tool)
        assertEquals("msg_1", event.tool?.messageID)
        assertEquals("call_1", event.tool?.callID)
    }

    @Test
    fun `permission-askedをパースできる(最小フィールド・tool無し)`() {
        val text = """
            {
              "id": "evt_1",
              "type": "permission.asked",
              "properties": {
                "id": "per_456",
                "permission": "tool:exec"
              }
            }
        """.trimIndent()
        val event = parseSseEnvelope(text)
        assertTrue(event is PermissionAskedEvent)
        event as PermissionAskedEvent
        assertEquals("per_456", event.id)
        assertNull(event.sessionID)
        assertEquals("tool:exec", event.permission)
        assertTrue(event.patterns.isEmpty())
        assertNull(event.metadata)
        assertTrue(event.always.isEmpty())
        assertNull(event.tool)
    }

    @Test
    fun `permission-repliedをパースできる(同形)`() {
        val text = """
            {
              "id": "evt_2",
              "type": "permission.replied",
              "properties": {
                "id": "per_123",
                "sessionID": "ses_abc",
                "permission": "tool:read",
                "patterns": ["**/*.kt"],
                "metadata": {"command": "rm -rf /tmp/demo", "reason": "破壊的コマンド"},
                "always": [],
                "tool": null
              }
            }
        """.trimIndent()
        val event = parseSseEnvelope(text)
        assertTrue(event is PermissionRepliedEvent)
        event as PermissionRepliedEvent
        assertEquals("per_123", event.id)
        assertEquals("ses_abc", event.sessionID)
        assertEquals("tool:read", event.permission)
    }

    @Test
    fun `permission-askedのJSON不正・フィールド欠損はIgnored(例外を投げない)`() {
        // id欠損
        val missingId = parseSseEnvelope(
            """{"id":"e","type":"permission.asked","properties":{"permission":"tool:read"}}""",
        )
        assertTrue(missingId is SseEvent.Ignored)

        // propertiesが文字列
        val badProps = parseSseEnvelope(
            """{"id":"e","type":"permission.asked","properties":"oops"}""",
        )
        assertTrue(badProps is SseEvent.Ignored)

        // toolが文字列
        val badTool = parseSseEnvelope(
            """{"id":"e","type":"permission.asked","properties":{"id":"per_1","permission":"p","tool":"bad"}}""",
        )
        assertTrue(badTool is SseEvent.Ignored)
    }

    @Test
    fun `permission-repliedはidだけでも受理する`() {
        // 契約は replied を「(同系)」としか書いておらず形が確定していない。実測では
        // {id, sessionID, response} が来る。ここを Ignored にすると **ダイアログが永久に閉じない**
        // ——実機で確認済み(e2e-artifacts/P4/13-after-allow.png)。閉じる判断に要るのは id だけ。
        val minimal = parseSseEnvelope(
            """{"id":"e","type":"permission.replied","properties":{"id":"per_1"}}""",
        )
        assertTrue(minimal is PermissionRepliedEvent)
        assertEquals("per_1", (minimal as PermissionRepliedEvent).id)

        val real = parseSseEnvelope(
            """{"id":"e","type":"permission.replied","properties":{"id":"per_1","sessionID":"ses_1","response":"once"}}""",
        )
        assertTrue(real is PermissionRepliedEvent)
        assertEquals("once", (real as PermissionRepliedEvent).response)
    }

    @Test
    fun `permission-repliedにidが無ければIgnored`() {
        // id は閉じる対象を特定する唯一の手掛かりなので、これだけは必須。
        val bad = parseSseEnvelope(
            """{"id":"e","type":"permission.replied","properties":{"response":"once"}}""",
        )
        assertTrue(bad is SseEvent.Ignored)
    }
}
