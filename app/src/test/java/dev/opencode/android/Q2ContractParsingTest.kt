package dev.opencode.android

import dev.opencode.android.data.PartDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.contractJson
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.ChatMessage
import dev.opencode.android.ui.ToolRunStatus
import dev.opencode.android.ui.messageCopyText
import dev.opencode.android.ui.summarizeToolInput
import dev.opencode.android.ui.toChatPart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q2 で使う契約(`ToolPart` / `ReasoningPart` / `SessionStatus.retry.action`)の固定テスト。
 *
 * **フィクスチャは spec(`docs/spec/opencode-1.18.21-openapi.json`、2026-08-27 に実機 `/doc` と
 * SHA-256 一致を再確認済み)の required をすべて満たす形にしてある。**
 * 近似した文字列を流さない —— このプロジェクトが6回繰り返した欠陥は全部その形だった。
 *
 * required の写し(spec より):
 *  - `ToolPart`: id, sessionID, messageID, type, callID, tool, state
 *  - `ToolStateRunning`: status, input, time{start}
 *  - `ToolStateCompleted`: status, input, output, title, metadata, time{start,end}
 *  - `ToolStateError`: status, input, error, time{start,end}
 *  - `ToolStatePending`: status, input, raw
 *  - `ReasoningPart`: id, sessionID, messageID, type, text, time{start}
 *  - `SessionStatus`(retry): type, attempt, message, next(`action` は任意。
 *    `action` 自体の required は reason, provider, title, message, label)
 */
class Q2ContractParsingTest {

    private val sid = "ses_fc0247634ffeGddA7Mw2hp2KCh"
    private val mid = "msg_a2"

    // ---------- ToolPart ----------

    private fun toolRunningFrame() = """
        {"id":"evt_q2t1","type":"message.part.updated","properties":{
        "sessionID":"$sid",
        "part":{"id":"prt_tool1","sessionID":"$sid","messageID":"$mid","type":"tool",
        "callID":"call_01","tool":"bash",
        "state":{"status":"running","input":{"command":"ls -la","description":"list files"},
        "title":"bash: ls -la","time":{"start":1787785000000}}},
        "time":{"start":1787785000000}}}
    """.trimIndent()

    private fun toolCompletedFrame(output: String = "total 8\ndrwxr-xr-x  2 u u 4096 .") = """
        {"id":"evt_q2t2","type":"message.part.updated","properties":{
        "sessionID":"$sid",
        "part":{"id":"prt_tool1","sessionID":"$sid","messageID":"$mid","type":"tool",
        "callID":"call_01","tool":"bash",
        "state":{"status":"completed","input":{"command":"ls -la","description":"list files"},
        "output":"$output","title":"bash: ls -la","metadata":{},
        "time":{"start":1787785000000,"end":1787785001000}}},
        "time":{"start":1787785001000}}}
    """.trimIndent()

    private fun toolErrorFrame(error: String = "ENOENT: no such file") = """
        {"id":"evt_q2t3","type":"message.part.updated","properties":{
        "sessionID":"$sid",
        "part":{"id":"prt_tool2","sessionID":"$sid","messageID":"$mid","type":"tool",
        "callID":"call_02","tool":"read",
        "state":{"status":"error","input":{"filePath":"/nope"},"error":"$error",
        "time":{"start":1787785000000,"end":1787785000500}}},
        "time":{"start":1787785000500}}}
    """.trimIndent()

    private fun toolPendingFrame() = """
        {"id":"evt_q2t4","type":"message.part.updated","properties":{
        "sessionID":"$sid",
        "part":{"id":"prt_tool3","sessionID":"$sid","messageID":"$mid","type":"tool",
        "callID":"call_03","tool":"write",
        "state":{"status":"pending","input":{},"raw":"{\"filePath\":"}},
        "time":{"start":1787785000000}}}
    """.trimIndent()

    private fun partOf(frame: String): PartDto {
        val event = parseSseEnvelope(frame)
        assertNotNull("フィクスチャがパースできていない(検出器自身の校正)", event)
        assertTrue("Ignored に落ちた: $event", event is SseEvent.PartUpdated)
        return (event as SseEvent.PartUpdated).part
    }

    @Test
    fun `running の ToolPart が状態と入力要約を持つ`() {
        val part = partOf(toolRunningFrame()).toChatPart()
        val tool = part.tool
        assertNotNull(tool)
        assertEquals("bash", tool!!.name)
        assertEquals(ToolRunStatus.RUNNING, tool.status)
        assertEquals("bash: ls -la", tool.title)
        // `input` は object。String と決め打つと SerializationException でイベントごと消える。
        assertEquals("command=ls -la, description=list files", tool.input)
        assertNull(tool.output)
        assertNull(tool.error)
    }

    @Test
    fun `completed の ToolPart が出力を持つ`() {
        val tool = partOf(toolCompletedFrame()).toChatPart().tool!!
        assertEquals(ToolRunStatus.COMPLETED, tool.status)
        assertTrue(tool.output!!.startsWith("total 8"))
    }

    @Test
    fun `error の ToolPart が error 文字列を持つ`() {
        val tool = partOf(toolErrorFrame()).toChatPart().tool!!
        assertEquals(ToolRunStatus.ERROR, tool.status)
        assertEquals("ENOENT: no such file", tool.error)
        // ToolStateError には output も title も無い(required に無い)。無いものを作らない。
        assertNull(tool.output)
        assertNull(tool.title)
    }

    @Test
    fun `pending の ToolPart は空inputで要約を出さない`() {
        val tool = partOf(toolPendingFrame()).toChatPart().tool!!
        assertEquals(ToolRunStatus.PENDING, tool.status)
        assertNull("空オブジェクトは「入力なし」。空文字を出さない", tool.input)
    }

    @Test
    fun `同一partIdのtoolがrunningからcompletedへ差し替わる`() {
        // ゲート文言「tool part(running→completed)を演出 → 状態変化が1行カードに反映される」の
        // マージ側。part.id が同じなら**置換**されること(追加されると2枚並ぶ)。
        val running = partOf(toolRunningFrame()).toChatPart()
        val completed = partOf(toolCompletedFrame()).toChatPart()
        assertEquals("同じ part.id であること(そうでないとこのテストは何も見ていない)", running.partId, completed.partId)
        assertEquals(ToolRunStatus.RUNNING, running.tool!!.status)
        assertEquals(ToolRunStatus.COMPLETED, completed.tool!!.status)
    }

    @Test
    fun `未知のstatusでも落とさずUNKNOWNへ倒す`() {
        val frame = toolRunningFrame().replace("\"status\":\"running\"", "\"status\":\"quantum\"")
        val tool = partOf(frame).toChatPart().tool!!
        assertEquals(ToolRunStatus.UNKNOWN, tool.status)
    }

    // ---------- ReasoningPart ----------

    private fun reasoningFrame() = """
        {"id":"evt_q2r1","type":"message.part.updated","properties":{
        "sessionID":"$sid",
        "part":{"id":"prt_reason1","sessionID":"$sid","messageID":"$mid","type":"reasoning",
        "text":"まず要求を分解する。次に…","metadata":{},"time":{"start":1787785000000}},
        "time":{"start":1787785000000}}}
    """.trimIndent()

    @Test
    fun `ReasoningPart の text が拾える`() {
        val part = partOf(reasoningFrame()).toChatPart()
        assertEquals("reasoning", part.type)
        // ここを text だけ text-part 扱いしていると本文が空になり、
        // 「思考」を開いても中身が無い(=折り畳みだけが増える)。
        assertEquals("まず要求を分解する。次に…", part.text)
        assertNull(part.tool)
    }

    // ---------- SessionStatus.retry.action ----------

    private fun retryWithActionJson() = """
        {"type":"retry","attempt":2,"message":"provider retry","next":1787785030000,
        "action":{"reason":"rate_limit","provider":"anthropic","title":"レート制限",
        "message":"しばらく待ってから再試行します","label":"設定を開く","link":"https://example.invalid/x"}}
    """.trimIndent()

    @Test
    fun `retry の action が required 5項目とも読める`() {
        val dto = contractJson.decodeFromString<SessionStatusDto>(retryWithActionJson())
        assertEquals("retry", dto.type)
        assertEquals(2, dto.attempt)
        assertEquals("provider retry", dto.message)
        assertEquals(1787785030000L, dto.next)
        val action = dto.action
        assertNotNull("Q1 の SessionStatusDto には action が無かった(申し送り minor-7)", action)
        assertEquals("rate_limit", action!!.reason)
        assertEquals("anthropic", action.provider)
        assertEquals("レート制限", action.title)
        assertEquals("しばらく待ってから再試行します", action.message)
        assertEquals("設定を開く", action.label)
        assertEquals("https://example.invalid/x", action.link)
    }

    @Test
    fun `action が無い retry でも落ちない`() {
        val dto = contractJson.decodeFromString<SessionStatusDto>(
            """{"type":"retry","attempt":0,"message":"m","next":0}""",
        )
        assertEquals("retry", dto.type)
        assertEquals(0, dto.attempt)
        assertNull(dto.action)
    }

    @Test
    fun `idle と busy は type だけで読める`() {
        assertEquals("idle", contractJson.decodeFromString<SessionStatusDto>("""{"type":"idle"}""").type)
        assertEquals("busy", contractJson.decodeFromString<SessionStatusDto>("""{"type":"busy"}""").type)
    }

    @Test
    fun `session_status のenvelopeからactionまで通る`() {
        val frame = """{"id":"evt_q2s","type":"session.status","properties":{"sessionID":"$sid",""" +
            """"status":${retryWithActionJson().replace("\n", "")}}}"""
        val event = parseSseEnvelope(frame)
        assertTrue(event is SseEvent.SessionStatusChanged)
        val status = (event as SseEvent.SessionStatusChanged).status
        assertEquals("レート制限", status?.action?.title)
    }

    // ---------- 入力要約 ----------

    @Test
    fun `長い入力は先頭N文字で切り元の長さを書く`() {
        val long = contractJson.parseToJsonElement("""{"a":"${"x".repeat(500)}"}""").jsonObject
        val summary = summarizeToolInput(long, max = 20)!!
        assertTrue(summary.startsWith("a=xxxx"))
        // 「そこで終わった」のか「表示を切った」のかが画面から区別できること
        assertTrue(summary, summary.endsWith("…(全502文字)"))
    }

    /**
     * レビュー minor-1: 実物 serve 4097 の実セッションから採取した `ToolStateCompleted` は
     * `tool:"read"` で **`output` がファイル全文**だった。切らないと幅320dpのバブルに
     * 数十KBの単一 Text が入り、長押しコピーも同じ量を Clipboard へ渡す。
     */
    @Test
    fun `巨大なoutputはカードにもコピーにも全部載せない`() {
        val huge = "L".repeat(50_000)
        val part = partOf(toolCompletedFrame(output = huge)).toChatPart()
        val output = part.tool!!.output!!
        assertTrue(output.length < 1_200)
        assertTrue(output, output.endsWith("…(全50000文字)"))
        // コピーも同じ上限に従う(片方だけ切ると画面に出ないものが Clipboard に入る)
        val copied = messageCopyText(ChatMessage("msg_a", "assistant", listOf(part)))
        assertTrue(copied.length < 1_400)
        assertTrue(copied.contains("…(全50000文字)"))
    }

    @Test
    fun `巨大なerrorも切る`() {
        val huge = "E".repeat(9_000)
        val error = partOf(toolErrorFrame(error = huge)).toChatPart().tool!!.error!!
        assertTrue(error.length < 1_200)
        assertTrue(error.endsWith("…(全9000文字)"))
    }

    @Test
    fun `上限以下の出力はそのまま`() {
        val tool = partOf(toolCompletedFrame()).toChatPart().tool!!
        assertFalse(tool.output!!.contains("…(全"))
        assertTrue(tool.output.startsWith("total 8"))
    }

    /**
     * レビュー minor-2 で契約文書を直した点の裏取り: **`running` にも `title` が来うる**
     * (spec の `ToolStateRunning` は `title` / `metadata` を任意で持つ)。
     * 初版の契約文書は「`title` は completed にしか無い」と書いており、
     * このテストのフィクスチャ自身と食い違っていた。
     */
    @Test
    fun `runningにもtitleが来る`() {
        assertEquals("bash: ls -la", partOf(toolRunningFrame()).toChatPart().tool!!.title)
    }

    @Test
    fun `null と空オブジェクトは要約を出さない`() {
        assertNull(summarizeToolInput(null))
        assertNull(summarizeToolInput(JsonObject(emptyMap())))
    }

    @Test
    fun `数値やオブジェクトの値も文字列化する`() {
        val obj = contractJson.parseToJsonElement("""{"n":42,"o":{"k":"v"},"s":"plain"}""").jsonObject
        assertEquals("""n=42, o={"k":"v"}, s=plain""", summarizeToolInput(obj))
    }
}
