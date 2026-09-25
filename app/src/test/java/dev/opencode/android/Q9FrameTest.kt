package dev.opencode.android

import dev.opencode.android.data.PTY_CURSOR_LIVE_ONLY
import dev.opencode.android.data.PtyFrame
import dev.opencode.android.data.advancePtyCursor
import dev.opencode.android.data.classifyPtyBinaryFrame
import dev.opencode.android.data.classifyPtyTextFrame
import dev.opencode.android.data.webSocketBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebSocket のフレーム判別と再開位置([dev.opencode.android.data.PtyChannel])。
 *
 * ここが守っているのは実測で決まった2つの規則である:
 *
 *  1. **種別で判別する。位置で判別しない**(メタが先頭に来るとは限らない)
 *  2. **メタは位置を置き換え、出力フレームはバイト数だけ位置を進める**
 *     (メタは接続時の1回きりで、以後は更新されない)
 */
class Q9FrameTest {

    // ---------------------------------------------------------------------
    // フィクスチャがバイト単位で実物と一致していること(§4.2)
    // ---------------------------------------------------------------------

    /**
     * **採取したときのバイト数を固定する。** 実物のフレーム長を書き写した値であり、
     * これがずれたらフィクスチャが実データでなくなったということである。
     */
    @Test
    fun `フィクスチャのフレーム長は採取値と一致する`() {
        assertEquals(113, Q9Fixtures.BANNER_FRAME_1.toByteArray(Charsets.UTF_8).size)
        assertEquals(110, Q9Fixtures.BANNER_FRAME_2.toByteArray(Charsets.UTF_8).size)
        assertEquals(12, Q9Fixtures.ECHO_INPUT_FRAME.toByteArray(Charsets.UTF_8).size)
        assertEquals(66, Q9Fixtures.ECHO_OUTPUT_FRAME.toByteArray(Charsets.UTF_8).size)
        assertEquals(45, Q9Fixtures.CTRL_C_FRAME.toByteArray(Charsets.UTF_8).size)
    }

    // ---------------------------------------------------------------------
    // 判別
    // ---------------------------------------------------------------------

    @Test
    fun `テキストフレームは必ず出力`() {
        val frame = classifyPtyTextFrame(Q9Fixtures.BANNER_FRAME_1)
        assertTrue(frame is PtyFrame.Output)
        assertEquals(113, (frame as PtyFrame.Output).byteLength)
        assertEquals(Q9Fixtures.BANNER_FRAME_1, frame.text)
    }

    /** **バイト数は文字数ではない。** 日本語を含む出力で 1文字 3バイトになる。 */
    @Test
    fun `テキストフレームのバイト数は UTF-8 で数える`() {
        val frame = classifyPtyTextFrame("差分") as PtyFrame.Output
        assertEquals(2, frame.text.length)
        assertEquals(6, frame.byteLength)
    }

    @Test
    fun `先頭 0x00 のバイナリはメタ`() {
        assertEquals(
            PtyFrame.Meta(0L),
            classifyPtyBinaryFrame(Q9Fixtures.META_FRAME_CURSOR_0),
        )
        assertEquals(
            PtyFrame.Meta(223L),
            classifyPtyBinaryFrame(Q9Fixtures.META_FRAME_CURSOR_223),
        )
    }

    /** 先頭が `0x00` でないバイナリは**生の出力**である(判別規則の残り半分)。 */
    @Test
    fun `先頭が 0x00 でないバイナリは出力`() {
        val frame = classifyPtyBinaryFrame("hello".toByteArray(Charsets.UTF_8))
        assertEquals(PtyFrame.Output("hello", 5), frame)
    }

    /** 空フレームは出力(0バイト)。メタと読むと読めないメタが積み上がる。 */
    @Test
    fun `空のバイナリは出力`() {
        assertEquals(PtyFrame.Output("", 0), classifyPtyBinaryFrame(ByteArray(0)))
    }

    /** メタが壊れていても**本文として描かない**(生の JSON が画面に出る形を防ぐ)。 */
    @Test
    fun `壊れたメタは UnreadableMeta`() {
        val frame = classifyPtyBinaryFrame(byteArrayOf(0x00, '{'.code.toByte()))
        assertTrue(frame is PtyFrame.UnreadableMeta)
    }

    @Test
    fun `cursor を持たないメタも UnreadableMeta`() {
        val bytes = byteArrayOf(0x00) + """{"other":1}""".toByteArray(Charsets.UTF_8)
        assertTrue(classifyPtyBinaryFrame(bytes) is PtyFrame.UnreadableMeta)
    }

    // ---------------------------------------------------------------------
    // 再開位置(実測で一番間違えやすい所)
    // ---------------------------------------------------------------------

    /**
     * **メタだけを保存すると、接続してから今までの出力を全部もう一度読む。**
     *
     * 実測の並びをそのまま再現する: メタ(0)→ 113 バイト → 110 バイト。
     * サーバー側の累積は 223 で、別接続のメタも 223 を返した。
     */
    @Test
    fun `出力フレームのバイト数だけ位置が進む`() {
        var cursor: Long? = null
        cursor = advancePtyCursor(cursor, classifyPtyBinaryFrame(Q9Fixtures.META_FRAME_CURSOR_0))
        assertEquals(0L, cursor)
        cursor = advancePtyCursor(cursor, classifyPtyTextFrame(Q9Fixtures.BANNER_FRAME_1))
        assertEquals(113L, cursor)
        cursor = advancePtyCursor(cursor, classifyPtyTextFrame(Q9Fixtures.BANNER_FRAME_2))
        // **223 = 別接続のメタが報告した値**([Q9Fixtures.META_FRAME_CURSOR_223])。
        assertEquals(223L, cursor)
        assertEquals(
            PtyFrame.Meta(cursor!!),
            classifyPtyBinaryFrame(Q9Fixtures.META_FRAME_CURSOR_223),
        )
    }

    /** メタは**置き換える**(足さない)。 */
    @Test
    fun `メタは位置を置き換える`() {
        assertEquals(223L, advancePtyCursor(999L, PtyFrame.Meta(223L)))
    }

    /** 読めなかったメタでは位置を動かさない。 */
    @Test
    fun `読めないメタは位置を動かさない`() {
        assertEquals(50L, advancePtyCursor(50L, PtyFrame.UnreadableMeta("x")))
    }

    /**
     * **位置を知らないうちは進めない。** `null` に 0 を代入して足し始めると、
     * 「まだ何も読んでいない」が「先頭から 113 バイト読んだ」に化ける。
     */
    @Test
    fun `位置未知のまま出力が来ても位置は未知のまま`() {
        assertNull(advancePtyCursor(null, PtyFrame.Output("x", 1)))
    }

    /** `-1` は「リプレイ無し」であって位置 0 ではない。 */
    @Test
    fun `ライブのみの cursor は -1`() {
        assertEquals(-1L, PTY_CURSOR_LIVE_ONLY)
    }

    // ---------------------------------------------------------------------
    // WebSocket の URL
    // ---------------------------------------------------------------------

    @Test
    fun `http は ws に https は wss になる`() {
        assertEquals("ws://10.0.2.2:4098", webSocketBaseUrl("http://10.0.2.2:4098"))
        assertEquals("wss://example.test", webSocketBaseUrl("https://example.test"))
    }

    /** ホスト名に `http` が現れても壊さない(先頭一致でしか置換しない)。 */
    @Test
    fun `ホスト名の中の http は置換しない`() {
        assertEquals("ws://http-proxy.local:4097", webSocketBaseUrl("http://http-proxy.local:4097"))
    }

    /** 想定外のスキームは**そのまま返す**(黙って壊した URL を作らない)。 */
    @Test
    fun `未知のスキームはそのまま`() {
        assertEquals("ftp://x", webSocketBaseUrl("ftp://x"))
    }
}
