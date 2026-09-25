package dev.opencode.android

import dev.opencode.android.data.OpenCodeEvents
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.UpdateSessionRequest
import dev.opencode.android.data.contractJson
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.SessionRunState
import dev.opencode.android.ui.runStateOf
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q1 で追加した契約(`GET /session/status` / `session.status` / `session.created|updated|deleted`)の
 * 固定テスト。
 *
 * **フィクスチャは実サーバー 1.18.21 から実際に採取したフレームをそのまま貼っている**
 * (2026-08-27、`GET /event` を開いたまま POST/PATCH/DELETE した記録と
 * `GET /session/status` の応答。docs/API_CONTRACT.md「Q1で使用する分」に同じものがある)。
 *
 * このプロジェクトは6回、**フィクスチャが実データと違う形を流していたためにテストが全緑のまま
 * 症状が残る**という同じ壊れ方をしている(P3 role / P4 metadata / P4 PermissionRepliedEvent /
 * L3 session.error / H1 URL / Q0)。近似した文字列を書かないこと。
 */
class Q1ContractParsingTest {

    // ---- 実サーバーから採取したフレーム(改行だけ入れて整形。値は無加工) ----

    private val realSessionCreated = """
        {"id":"evt_03fdb89cb002xc7lkr3G5Npixg","type":"session.created","properties":{
        "sessionID":"ses_fc0247634ffeGddA7Mw2hp2KCh",
        "info":{"id":"ses_fc0247634ffeGddA7Mw2hp2KCh","slug":"stellar-sailor","version":"1.18.21",
        "projectID":"c131653af018fbbc2b3e0a43a252c18337ffdacf","directory":"E:\\github\\opencode-android",
        "path":"","title":"Q1-sse-probe","cost":0,
        "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
        "time":{"created":1787777747403,"updated":1787777747403}}}}
    """.trimIndent()

    private val realSessionUpdated = """
        {"id":"evt_03fdb8dc1001JsSRVG0lNg089b","type":"session.updated","properties":{
        "sessionID":"ses_fc0247634ffeGddA7Mw2hp2KCh",
        "info":{"id":"ses_fc0247634ffeGddA7Mw2hp2KCh","slug":"stellar-sailor",
        "projectID":"c131653af018fbbc2b3e0a43a252c18337ffdacf","directory":"E:\\github\\opencode-android",
        "path":"","title":"Q1-sse-probe-renamed","version":"1.18.21","cost":0,
        "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
        "time":{"created":1787777747403,"updated":1787777747403}}}}
    """.trimIndent()

    private val realSessionDeleted = """
        {"id":"evt_03fdb91b9001hQPkUvbzwOzqRI","type":"session.deleted","properties":{
        "sessionID":"ses_fc0247634ffeGddA7Mw2hp2KCh",
        "info":{"id":"ses_fc0247634ffeGddA7Mw2hp2KCh","slug":"stellar-sailor",
        "projectID":"c131653af018fbbc2b3e0a43a252c18337ffdacf","directory":"E:\\github\\opencode-android",
        "path":"","title":"Q1-sse-probe-renamed","version":"1.18.21","cost":0,
        "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
        "time":{"created":1787777747403,"updated":1787777747403}}}}
    """.trimIndent()

    private val realStatusBusy = """
        {"id":"evt_03fdbf9fa0016AD24ymhZ21kCI","type":"session.status","properties":{
        "sessionID":"ses_fc024073bffe20ZIBZ6z2Ei56t","status":{"type":"busy"}}}
    """.trimIndent()

    private val realStatusIdle = """
        {"id":"evt_03fdbff5c002YzDYGyNB7srv0B","type":"session.status","properties":{
        "sessionID":"ses_fc024073bffe20ZIBZ6z2Ei56t","status":{"type":"idle"}}}
    """.trimIndent()

    // ---- SSE ----

    @Test
    fun `実サーバーの session-created をパースし info を取り出せる`() {
        val event = parseSseEnvelope(realSessionCreated)
        assertTrue(event is SseEvent.SessionInfoChanged)
        event as SseEvent.SessionInfoChanged
        assertEquals(SseEvent.SessionInfoChanged.Kind.CREATED, event.kind)
        assertEquals("ses_fc0247634ffeGddA7Mw2hp2KCh", event.sessionID)
        assertEquals("Q1-sse-probe", event.info?.title)
        // 単位の取り違え(秒/ミリ秒)は日付グループを全滅させるので値そのものを固定する
        assertEquals(1787777747403L, event.info?.time?.updated)
    }

    @Test
    fun `実サーバーの session-updated は新しいタイトルを運ぶ`() {
        val event = parseSseEnvelope(realSessionUpdated) as SseEvent.SessionInfoChanged
        assertEquals(SseEvent.SessionInfoChanged.Kind.UPDATED, event.kind)
        assertEquals("Q1-sse-probe-renamed", event.info?.title)
        // 実測: 改名では time.updated が動かない(created と同値のまま)
        assertEquals(event.info?.time?.created, event.info?.time?.updated)
    }

    @Test
    fun `実サーバーの session-deleted は削除前の info を運ぶ`() {
        val event = parseSseEnvelope(realSessionDeleted) as SseEvent.SessionInfoChanged
        assertEquals(SseEvent.SessionInfoChanged.Kind.DELETED, event.kind)
        assertEquals("ses_fc0247634ffeGddA7Mw2hp2KCh", event.info?.id)
    }

    @Test
    fun `session-status busy と idle をパースできる`() {
        val busy = parseSseEnvelope(realStatusBusy) as SseEvent.SessionStatusChanged
        assertEquals("ses_fc024073bffe20ZIBZ6z2Ei56t", busy.sessionID)
        assertEquals("busy", busy.status?.type)
        assertEquals(SessionRunState.BUSY, runStateOf(busy.status))

        val idle = parseSseEnvelope(realStatusIdle) as SseEvent.SessionStatusChanged
        assertEquals(SessionRunState.IDLE, runStateOf(idle.status))
    }

    @Test
    fun `retry は spec の required を満たす形でパースでき attempt を保つ`() {
        // spec: retry の required は type / attempt / message / next(action は任意)
        val text = """
            {"id":"evt_x","type":"session.status","properties":{
              "sessionID":"ses_a",
              "status":{"type":"retry","attempt":2,"message":"upstream unavailable","next":1787777747403,
                        "action":{"reason":"rate_limit","provider":"mistral","title":"待機中",
                                  "message":"しばらく待ちます","label":"再試行"}}}}
        """.trimIndent()
        val event = parseSseEnvelope(text) as SseEvent.SessionStatusChanged
        assertEquals("retry", event.status?.type)
        assertEquals(2, event.status?.attempt)
        assertEquals(SessionRunState.RETRY, runStateOf(event.status))
    }

    @Test
    fun `status が読めなくても sessionID は残り、状態は idle へ倒れる`() {
        val text = """{"id":"evt_x","type":"session.status","properties":{"sessionID":"ses_a","status":"busy"}}"""
        val event = parseSseEnvelope(text)
        assertTrue(event is SseEvent.SessionStatusChanged)
        event as SseEvent.SessionStatusChanged
        assertEquals("ses_a", event.sessionID)
        assertNull(event.status)
        assertEquals(SessionRunState.IDLE, runStateOf(event.status))
    }

    @Test
    fun `info が壊れていても deleted は sessionID だけで一覧から消せる`() {
        val text = """{"id":"evt_x","type":"session.deleted","properties":{"sessionID":"ses_a","info":42}}"""
        val event = parseSseEnvelope(text) as SseEvent.SessionInfoChanged
        assertEquals(SseEvent.SessionInfoChanged.Kind.DELETED, event.kind)
        assertEquals("ses_a", event.sessionID)
        assertNull(event.info)
    }

    // ---- GET /session/status ----

    @Test
    fun `session-status の応答は busy のみを載せた map`() {
        // 実測した応答そのもの
        val text = """{"ses_fc024073bffe20ZIBZ6z2Ei56t":{"type":"busy"}}"""
        val map = contractJson.decodeFromString<Map<String, SessionStatusDto>>(text)
        assertEquals(1, map.size)
        assertEquals(SessionRunState.BUSY, runStateOf(map["ses_fc024073bffe20ZIBZ6z2Ei56t"]))
        // 載っていないセッション = idle。ここを取り違えると全カードが「実行中」になる
        assertEquals(SessionRunState.IDLE, runStateOf(map["ses_other"]))
    }

    @Test
    fun `何も走っていないときの応答は空オブジェクト`() {
        val map = contractJson.decodeFromString<Map<String, SessionStatusDto>>("{}")
        assertTrue(map.isEmpty())
    }

    @Test
    fun `未知の status type は idle へ倒す(将来の種別追加で落とさない)`() {
        val map = contractJson.decodeFromString<Map<String, SessionStatusDto>>(
            """{"ses_a":{"type":"compacting","somethingNew":1}}""",
        )
        assertNotNull(map["ses_a"])
        assertEquals(SessionRunState.IDLE, runStateOf(map["ses_a"]))
    }

    // ---- PATCH のボディ ----

    @Test
    fun `PATCH のボディは title だけを含む`() {
        // spec の requestBody は additionalProperties=false。余分なキーを送らないこと。
        assertEquals("""{"title":"新しい名前"}""", contractJson.encodeToString(UpdateSessionRequest("新しい名前")))
    }

    // ---- 401/403 は再試行しない ----

    @Test
    fun `401と403だけが再試行しない扱い`() {
        assertTrue(OpenCodeEvents.isAuthRejection(401))
        assertTrue(OpenCodeEvents.isAuthRejection(403))
        assertFalse(OpenCodeEvents.isAuthRejection(500))
        assertFalse(OpenCodeEvents.isAuthRejection(404))
        assertFalse(OpenCodeEvents.isAuthRejection(200))
        assertFalse(OpenCodeEvents.isAuthRejection(429))
    }

    @Test
    fun `接続状態の enum は data層とUI層で1対1(valueOfが落ちない)`() {
        // ChatEventStatus.valueOf(Status.name) で変換しているので、片方だけ増やすと
        // IllegalArgumentException で状態購読のコルーチンごと死ぬ。
        val dataNames = OpenCodeEvents.Status.entries.map { it.name }.toSet()
        val uiNames = dev.opencode.android.ui.ChatEventStatus.entries.map { it.name }.toSet()
        assertEquals(dataNames, uiNames)
    }
}
