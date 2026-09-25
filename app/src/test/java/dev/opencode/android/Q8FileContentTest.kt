package dev.opencode.android

import dev.opencode.android.data.FILE_CONTENT_MAX_BYTES
import dev.opencode.android.data.FileContentPayload
import dev.opencode.android.data.decodeFileContent
import dev.opencode.android.ui.FileViewerUi
import dev.opencode.android.ui.buildFileLines
import dev.opencode.android.ui.fileBodyKindOf
import dev.opencode.android.ui.FileBodyKind
import dev.opencode.android.ui.fileNoticesOf
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **受信の打ち切りと、切れた JSON からの救出**(QUALITY_PLAN §5b Q8 のリスク欄)。
 *
 * ## 何を守っているか
 *
 * `GET /file/content` には上限が無く、`content` はファイル全文が1本の JSON 文字列で来る。
 * `resp.body.string()` は**全部をメモリに載せる**ので、数十MBのファイルで OOM する。
 *
 * 打ち切ると JSON は壊れる。**「大きすぎるので表示できません」と言うのは要求ではない** ——
 * 計画書は「**大きすぎるため先頭N KBのみ**と明示する」と書いている。
 * つまり読めるところまでは読ませたうえで、**切ったことを言う**。
 *
 * ## 「無い」と「取れなかった」(この原則の7回目)
 *
 * 実測の並びは `{"type":..,"content":..,"encoding":..,"mimeType":..}` なので、
 * **打ち切ると `content` より後ろのキーが失われる**。巨大なバイナリでは `mimeType` が読めない。
 * そのとき「不明」と書くと、**サーバーが送らなかった**のと区別が付かなくなる。
 */
class Q8FileContentTest {

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    // ---------------------------------------------------------------------
    // 打ち切っていない場合
    // ---------------------------------------------------------------------

    @Test
    fun `打ち切っていなければ普通にデコードする`() {
        val payload = decodeFileContent(bytes(Q8Fixtures.FILE_CONTENT_TEXT_JSON), truncated = false)
        assertEquals("text", payload.type)
        assertFalse(payload.truncated)
        assertNull(payload.diff)
        assertEquals(Q8Fixtures.FILE_CONTENT_TEXT_JSON.toByteArray(StandardCharsets.UTF_8).size, payload.receivedBytes)
    }

    @Test
    fun `バイナリは encoding と mimeType を持つ`() {
        val payload = decodeFileContent(bytes(Q8Fixtures.FILE_CONTENT_BINARY_JSON), truncated = false)
        assertTrue(payload.isBinary)
        assertEquals("base64", payload.encoding)
        assertEquals("application/octet-stream", payload.mimeType)
    }

    // ---------------------------------------------------------------------
    // 打ち切った場合(救出)
    // ---------------------------------------------------------------------

    @Test
    fun `切れた JSON から type と content の途中までを救い出す`() {
        val prefix = """{"type":"text","content":"line1\nline2\nlin"""
        val payload = decodeFileContent(bytes(prefix), truncated = true)
        assertEquals("text", payload.type)
        assertEquals("line1\nline2\nlin", payload.content)
        assertTrue(payload.truncated)
    }

    /** **途中で切れた `\u` エスケープは捨てる。** 中途半端に復元すると在らぬ文字が生える。 */
    @Test
    fun `途中で切れたユニコードエスケープを捨てる`() {
        val prefix = """{"type":"text","content":"ok\u30"""
        assertEquals("ok", decodeFileContent(bytes(prefix), truncated = true).content)
    }

    /** 単独の `\` で終わっても同じ。 */
    @Test
    fun `途中で切れたバックスラッシュを捨てる`() {
        val prefix = """{"type":"text","content":"ok\"""
        assertEquals("ok", decodeFileContent(bytes(prefix), truncated = true).content)
    }

    @Test
    fun `エスケープを正しく復号する`() {
        val prefix = """{"type":"text","content":"a\"b\\c\td\neあf"""
        assertEquals("a\"b\\c\td\neあf", decodeFileContent(bytes(prefix), truncated = true).content)
    }

    /**
     * **多バイト文字がバイト境界で割れたときの U+FFFD を落とす。**
     * 落とさないと「ファイルの末尾に見慣れない文字が在る」と読める。
     */
    @Test
    fun `バイト境界で割れた多バイト文字を落とす`() {
        val full = """{"type":"text","content":"あいう"""
        val raw = full.toByteArray(StandardCharsets.UTF_8)
        // 「う」の途中で切る(UTF-8 で3バイト)
        val cut = raw.copyOf(raw.size - 2)
        val payload = decodeFileContent(cut, truncated = true)
        assertEquals("あい", payload.content)
    }

    /**
     * **落とす U+FFFD は1つだけ**(レビュー minor-5)。
     *
     * 1周目は `trimEnd('\uFFFD')` で**末尾の U+FFFD を全部**落としていた。
     * 割れうるのは**切断点にまたがる1文字だけ**なので、それ以上落とすのは
     * 「本当に U+FFFD で終わるファイル」を黙って壊す —— 壊れたエンコーディングの
     * テキストや置換文字を含むログは実在する。
     */
    @Test
    fun `末尾の置換文字は1つだけ落とす`() {
        // ファイル自身が U+FFFD を3つ持っていて、さらに切断で1つ増えた形。
        val full = """{"type":"text","content":"ok\uFFFD\uFFFD\uFFFDあ"""
        val raw = full.toByteArray(StandardCharsets.UTF_8)
        val cut = raw.copyOf(raw.size - 2) // 「あ」の途中で切る
        val payload = decodeFileContent(cut, truncated = true)
        assertEquals("ok\uFFFD\uFFFD\uFFFD", payload.content)
    }

    /** 切断が絡まなくても、本文末尾の置換文字は残す。 */
    @Test
    fun `本文が置換文字で終わっても消さない`() {
        val prefix = """{"type":"text","content":"log\uFFFD"""
        // 切断点は `\uFFFD` の直後なので、割れた1文字ぶんだけが落ちる。
        assertEquals("log", decodeFileContent(prefix.toByteArray(StandardCharsets.UTF_8), truncated = true).content)
    }

    /**
     * **`content` キーへ届く前に切れた**場合。`type` は読めても本文は空。
     * 「空のファイル」ではないことは [FileContentPayload.truncated] が示す。
     */
    @Test
    fun `content キーの手前で切れても落ちない`() {
        val payload = decodeFileContent(bytes("""{"type":"binar"""), truncated = true)
        assertEquals("", payload.content)
        assertTrue(payload.truncated)
        // `type` の値そのものが切れているので **`binary` と決めつけない**。
        assertEquals(FileBodyKind.UNKNOWN, fileBodyKindOf(FileViewerUi(payload = payload)))
    }

    /**
     * **`mimeType` は `content` の後ろに在るので、打ち切ると読めない。**
     * このとき画面は「不明」ではなく「**応答が大きすぎて読み取れていません**」と言う。
     */
    @Test
    fun `打ち切ったバイナリの mimeType は不明ではなく読めていないと言う`() {
        val prefix = """{"type":"binary","content":"AAAAAAAA"""
        val payload = decodeFileContent(bytes(prefix), truncated = true)
        assertNull(payload.mimeType)
        val notices = fileNoticesOf(FileViewerUi(payload = payload))
        val binary = notices.first { it.key == "file-binary" }
        assertTrue(binary.text.contains("読み取れていません"))
        assertFalse("「不明」と書かない", binary.text.contains("不明"))
    }

    /** サーバーが `mimeType` を送らなかった(打ち切っていない)場合は**別の文言**。 */
    @Test
    fun `送られなかった mimeType は読めなかったと言わない`() {
        val payload = FileContentPayload(type = "binary", content = "AAA", receivedBytes = 3)
        val binary = fileNoticesOf(FileViewerUi(payload = payload)).first { it.key == "file-binary" }
        assertTrue(binary.text.contains("返していません"))
        assertFalse(binary.text.contains("大きすぎて"))
    }

    // ---------------------------------------------------------------------
    // 注記(黙って切らない)
    // ---------------------------------------------------------------------

    @Test
    fun `受信を打ち切ったら注記を出す`() {
        val payload = FileContentPayload(type = "text", content = "x", truncated = true, receivedBytes = 524288)
        val notices = fileNoticesOf(FileViewerUi(payload = payload))
        assertEquals("file-truncated:524288", notices[0].key)
        assertTrue(notices[0].text.contains("512 KB"))
        assertTrue(notices[0].text.contains("${FILE_CONTENT_MAX_BYTES / 1024} KB"))
    }

    @Test
    fun `打ち切っていなければ注記を出さない`() {
        val payload = FileContentPayload(type = "text", content = "x", receivedBytes = 3)
        assertEquals(emptyList<Any>(), fileNoticesOf(FileViewerUi(payload = payload)))
    }

    // ---------------------------------------------------------------------
    // 行数の打ち切り(受信の打ち切りとは別物)
    // ---------------------------------------------------------------------

    @Test
    fun `行数上限で切ったら別の注記を出す`() {
        val content = (1..10).joinToString("\n") { "line$it" }
        val (lines, cut, total) = buildFileLines(content, maxLines = 3)
        assertEquals(3, lines.size)
        assertTrue(cut)
        assertEquals(10, total)

        val ui = FileViewerUi(
            payload = FileContentPayload(type = "text", content = content, receivedBytes = 60),
            lines = lines,
            renderTruncated = cut,
            totalLines = total,
        )
        assertEquals("file-render-truncated:10", fileNoticesOf(ui)[0].key)
    }

    @Test
    fun `末尾の改行で空行を増やさない`() {
        val (lines, cut, total) = buildFileLines("a\nb\n")
        assertEquals(listOf("a", "b"), lines.map { it.text })
        assertEquals(2, total)
        assertFalse(cut)
    }

    @Test
    fun `行番号は1始まり`() {
        val (lines, _, _) = buildFileLines("a\nb\nc")
        assertEquals(listOf(1, 2, 3), lines.map { it.number })
    }

    @Test
    fun `CRLF の行末を落とす`() {
        val (lines, _, _) = buildFileLines("a\r\nb\r\n")
        assertEquals(listOf("a", "b"), lines.map { it.text })
    }

    @Test
    fun `空のファイルは0行`() {
        val (lines, cut, total) = buildFileLines("")
        assertEquals(emptyList<Any>(), lines)
        assertFalse(cut)
        assertEquals(0, total)
    }
}
