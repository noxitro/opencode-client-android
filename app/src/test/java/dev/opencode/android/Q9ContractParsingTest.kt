package dev.opencode.android

import dev.opencode.android.data.PtyDto
import dev.opencode.android.data.PtyShellDto
import dev.opencode.android.data.PtyTicketDto
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.contractJson
import dev.opencode.android.data.parseSseEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PTY の**実データ**([Q9Fixtures])が契約どおりに読めることを固定する。
 *
 * QUALITY_PLAN §4.2:「テストは振る舞いの説明の再述ではなく**数値と同一性を検証する**」。
 */
class Q9ContractParsingTest {

    // ---------------------------------------------------------------------
    // REST
    // ---------------------------------------------------------------------

    @Test
    fun `pty shells は path name acceptable の3つを持つ`() {
        val shells: List<PtyShellDto> = contractJson.decodeFromString(Q9Fixtures.SHELLS_JSON)
        assertEquals(4, shells.size)
        assertEquals(listOf("pwsh", "powershell", "bash", "cmd"), shells.map { it.name })
        assertEquals("C:\\Windows\\system32\\cmd.exe", shells.last().path)
        assertTrue(shells.all { it.acceptable })
    }

    /**
     * **`exitCode` は応答に無い。** spec には在るが REST では観測されていない ——
     * ここが `0` にデコードされる実装だと、「取れていない」が「正常終了」に化ける。
     */
    @Test
    fun `POST pty の応答は exitCode を持たない`() {
        val pty: PtyDto = contractJson.decodeFromString(Q9Fixtures.CREATE_PTY_JSON)
        assertEquals("pty_04f246e3f001Pdm7dahQta7B5e", pty.id)
        assertEquals("cmd.exe", pty.command)
        assertEquals("running", pty.status)
        assertEquals(58556, pty.pid)
        assertEquals(emptyList<String>(), pty.args)
        assertNull(pty.exitCode)
    }

    @Test
    fun `GET pty は走っているものだけを返す`() {
        val list: List<PtyDto> = contractJson.decodeFromString(Q9Fixtures.LIST_PTY_JSON)
        assertEquals(1, list.size)
        assertEquals("running", list[0].status)
        assertEquals(58076, list[0].pid)
    }

    @Test
    fun `connect-token の応答は ticket と expires_in`() {
        val ticket: PtyTicketDto = contractJson.decodeFromString(Q9Fixtures.TICKET_JSON)
        assertEquals("65ffeda9-f427-4fd0-b10b-73c241ab6b4a", ticket.ticket)
        assertEquals(60, ticket.expiresIn)
    }

    // ---------------------------------------------------------------------
    // SSE
    // ---------------------------------------------------------------------

    @Test
    fun `pty_created は info を持つ`() {
        val event = parseSseEnvelope(Q9Fixtures.EVENT_CREATED) as SseEvent.PtyLifecycle
        assertEquals(SseEvent.PtyLifecycle.Kind.CREATED, event.kind)
        assertEquals("pty_04f2528ca001vnCCP68BAoLQlf", event.id)
        assertEquals("v1probe", event.info?.title)
        assertEquals(58076, event.info?.pid)
        assertNull(event.exitCode)
    }

    /**
     * **このテストが Q9 の中核**である。`pty.exited` を読み落とすと
     * 終了コードが二度と手に入らない(REST は終了した瞬間に 404 になる)。
     *
     * 値が **7** なのは実データがそうだから ——
     * `0` のフィクスチャだと「取れなかったものを 0 と書く」欠陥が見えない。
     */
    @Test
    fun `pty_exited は exitCode を持ち id は properties 側から取れる`() {
        val event = parseSseEnvelope(Q9Fixtures.EVENT_EXITED) as SseEvent.PtyLifecycle
        assertEquals(SseEvent.PtyLifecycle.Kind.EXITED, event.kind)
        assertEquals(Q9Fixtures.EXITED_PTY_ID, event.id)
        assertEquals(7, event.exitCode)
        assertNull(event.info)
    }

    /** `DELETE` からは終了コードが来ない。**null であることを固定する。** */
    @Test
    fun `pty_deleted は exitCode を持たない`() {
        val event = parseSseEnvelope(Q9Fixtures.EVENT_DELETED) as SseEvent.PtyLifecycle
        assertEquals(SseEvent.PtyLifecycle.Kind.DELETED, event.kind)
        assertEquals("pty_04f2528ca001vnCCP68BAoLQlf", event.id)
        assertNull(event.exitCode)
    }

    /**
     * **`exitCode` が欠けた `pty.exited` でもイベントを捨てない**(P4 で2度踏んだ形)。
     * 捨てると「終わったこと」まで失われ、画面が接続中のまま残る。
     */
    @Test
    fun `exitCode が欠けた exited も id だけで届く`() {
        val event = parseSseEnvelope(
            """{"id":"evt_x","type":"pty.exited","properties":{"id":"pty_x"}}""",
        ) as SseEvent.PtyLifecycle
        assertEquals("pty_x", event.id)
        assertNull(event.exitCode)
    }

    /** `exitCode` が**文字列**で来ても読む(`errorStatusCodeOf` と同じ賭け方)。 */
    @Test
    fun `exitCode が文字列でも読む`() {
        val event = parseSseEnvelope(
            """{"id":"evt_x","type":"pty.exited","properties":{"id":"pty_x","exitCode":"3"}}""",
        ) as SseEvent.PtyLifecycle
        assertEquals(3, event.exitCode)
    }

    /** `info` が壊れていても `id` だけで届く(イベントごと消さない)。 */
    @Test
    fun `info が壊れた created も捨てない`() {
        val event = parseSseEnvelope(
            """{"id":"evt_x","type":"pty.created","properties":{"id":"pty_x","info":42}}""",
        ) as SseEvent.PtyLifecycle
        assertEquals("pty_x", event.id)
        assertNull(event.info)
    }

    /** `pty.updated` も同じ形で受ける。 */
    @Test
    fun `pty_updated は info から id を取る`() {
        val event = parseSseEnvelope(
            """{"id":"evt_x","type":"pty.updated","properties":{"info":
               {"id":"pty_u","title":"t","command":"cmd.exe","args":[],"cwd":"c",
                "status":"running","pid":1}}}""",
        ) as SseEvent.PtyLifecycle
        assertEquals(SseEvent.PtyLifecycle.Kind.UPDATED, event.kind)
        assertEquals("pty_u", event.id)
    }
}
