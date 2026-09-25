package dev.opencode.android

import dev.opencode.android.data.FileContentDto
import dev.opencode.android.data.FileNodeDto
import dev.opencode.android.data.FindMatchDto
import dev.opencode.android.data.SymbolDto
import dev.opencode.android.data.contractJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q8 の DTO が**実物 serve の応答そのもの**をデコードできることの固定
 * ([Q8Fixtures] は `curl` の出力である)。
 *
 * ## この段で契約が計画書を覆した2件
 *
 * 1. **`FileContent.patch` は文字列ではない。** spec では
 *    `{oldFileName,newFileName,hunks:[...]}` の構造化オブジェクトで、`type: string` なのは
 *    `diff` だけ。§5b は「`diff`/`patch` を持つ場合は Q7 の差分ビューアを再利用」と書くが、
 *    Q7 のパーサが食うのは unified diff の**文字列**である。
 *    `patch: String?` と宣言すると `SerializationException` になり、
 *    **差分を出すための宣言がファイルの中身ごと表示できなくする**。
 *    → DTO は `patch` を宣言しない。**そのことをここで固定する。**
 * 2. **`diff` も `patch` も 1.18.21 は返さない**(変更済みファイルでも)。
 */
class Q8ContractParsingTest {

    // ---------------------------------------------------------------------
    // FileNode
    // ---------------------------------------------------------------------

    @Test
    fun `FileNode の実データをデコードできる`() {
        val nodes = Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON)
        assertEquals(4, nodes.size)
        val build = nodes.first { it.name == "build" }
        assertEquals("directory", build.type)
        assertTrue("build は ignored:true で返る(実測)", build.ignored)
        val src = nodes.first { it.name == "src" }
        assertFalse(src.ignored)
    }

    /**
     * **`path` はサーバーOSの区切りで、ディレクトリは末尾に区切りが付く。**
     *
     * ここを assert しておかないと、`normalizeServerPath` が何のために在るのかが
     * テストからは分からなくなる —— 正規化のテストだけが在っても
     * 「サーバーが本当にこの形で返す」は別の主張である(Q5 の R1 と同じ形)。
     */
    @Test
    fun `FileNode の path は円マーク区切りでディレクトリは末尾に区切りが付く`() {
        val nodes = Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON)
        assertEquals("app\\build\\", nodes.first { it.name == "build" }.path)
        assertEquals("app\\build.gradle.kts", nodes.first { it.name == "build.gradle.kts" }.path)
        assertTrue(nodes.first { it.name == "build" }.absolute.startsWith("E:\\"))
    }

    @Test
    fun `ネスト2階層目もデコードできる`() {
        val nodes = Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_SRC_JSON)
        assertEquals(listOf("main", "test"), nodes.map { it.name })
        assertTrue(nodes.all { it.type == "directory" })
    }

    /**
     * **required を必須宣言しない**(Q7 と同じ判断)。
     * サーバーが1つ落としただけで**一覧が丸ごと消える**形を作らない
     * (P4 の `PermissionRepliedEvent` が実際にそれで壊れた)。
     */
    @Test
    fun `FileNode はキーが欠けても落ちない`() {
        val nodes = contractJson.decodeFromString<List<FileNodeDto>>("""[{"name":"x"}]""")
        assertEquals("x", nodes[0].name)
        assertEquals("", nodes[0].type)
        assertFalse(nodes[0].ignored)
    }

    // ---------------------------------------------------------------------
    // FileContent
    // ---------------------------------------------------------------------

    @Test
    fun `FileContent のテキストは type と content の2つだけで来る`() {
        val dto = contractJson.decodeFromString<FileContentDto>(Q8Fixtures.FILE_CONTENT_TEXT_JSON)
        assertEquals("text", dto.type)
        assertNull("1.18.21 は diff を返さない(実測)", dto.diff)
        assertNull(dto.encoding)
        assertNull(dto.mimeType)
    }

    /** **変更済みファイルでも `diff` は来ない**(`git status` が `M` と言うファイルで実測)。 */
    @Test
    fun `変更済みファイルでも diff は来ない`() {
        val dto = contractJson.decodeFromString<FileContentDto>(Q8Fixtures.FILE_CONTENT_MODIFIED_JSON)
        assertEquals("text", dto.type)
        assertNull(dto.diff)
    }

    @Test
    fun `FileContent のバイナリは encoding と mimeType を持つ`() {
        val dto = contractJson.decodeFromString<FileContentDto>(Q8Fixtures.FILE_CONTENT_BINARY_JSON)
        assertEquals("binary", dto.type)
        assertEquals("base64", dto.encoding)
        assertEquals("application/octet-stream", dto.mimeType)
    }

    /**
     * **`patch` が構造化オブジェクトで来ても落ちない。**
     *
     * 宣言していないので `ignoreUnknownKeys` が捨てる。ここを `patch: String?` にすると
     * この JSON で `SerializationException` になり、**ファイルの中身ごと出せなくなる**。
     * spec の形をそのまま貼って固定しておく。
     */
    @Test
    fun `FileContent の patch がオブジェクトでも落ちない`() {
        val json = """
            {"type":"text","content":"a\n",
             "patch":{"oldFileName":"a","newFileName":"b",
                      "hunks":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-a","+b"]}]}}
        """.trimIndent()
        val dto = contractJson.decodeFromString<FileContentDto>(json)
        assertEquals("text", dto.type)
        assertEquals("a\n", dto.content)
    }

    // ---------------------------------------------------------------------
    // Match(/find)
    // ---------------------------------------------------------------------

    @Test
    fun `Match の実データをデコードできる`() {
        val matches = Q8Decode.matches(Q8Fixtures.FIND_TWO_SUBMATCHES_JSON)
        assertEquals(1, matches.size)
        val m = matches[0]
        assertEquals("gradlew.bat", m.path.text)
        assertEquals(4, m.lineNumber)
        assertEquals(62L, m.absoluteOffset)
        assertEquals(2, m.submatches.size)
        assertTrue("lines.text は末尾に改行を含む(実測)", m.lines.text.endsWith("\n"))
    }

    @Test
    fun `1行に4回のマッチもデコードできる`() {
        val m = Q8Decode.matches(Q8Fixtures.FIND_FOUR_SUBMATCHES_JSON)[0]
        assertEquals(4, m.submatches.size)
        assertEquals(listOf(10, 26, 42, 50), m.submatches.map { it.start })
    }

    /** `line_number` / `absolute_offset` のスネークケースを [FindMatchDto] が受けていること。 */
    @Test
    fun `スネークケースのキーを受ける`() {
        val m = contractJson.decodeFromString<List<FindMatchDto>>(
            """[{"path":{"text":"a"},"lines":{"text":"b"},"line_number":7,"absolute_offset":99,"submatches":[]}]""",
        )[0]
        assertEquals(7, m.lineNumber)
        assertEquals(99L, m.absoluteOffset)
    }

    // ---------------------------------------------------------------------
    // Symbol
    // ---------------------------------------------------------------------

    /** **実物は常に `[]`**(LSP が動いていない)。空配列がデコードできることを固定する。 */
    @Test
    fun `Symbol の空配列をデコードできる`() {
        assertEquals(emptyList<SymbolDto>(), Q8Decode.symbols(Q8Fixtures.SYMBOL_EMPTY_JSON))
    }

    /**
     * 非空のシンボルは**実物では観測できていない**ので、**spec の required をそのまま組んだ**形で固定する。
     * これは「実データを貼った」とは言えない唯一のシンボル側フィクスチャで、
     * そのことを doc に書いておく(§5b が求める「測れないと測っていないの区別」)。
     */
    @Test
    fun `Symbol の非空応答は spec の required 通りにデコードできる`() {
        val json = """
            [{"name":"DiffController","kind":5,
              "location":{"uri":"file:///E:/github/opencode-android/app/src/main/java/x.kt",
                          "range":{"start":{"line":433,"character":6},"end":{"line":433,"character":20}}}}]
        """.trimIndent()
        val s = Q8Decode.symbols(json)[0]
        assertEquals("DiffController", s.name)
        assertEquals(5, s.kind)
        assertEquals(433, s.location.range.start.line)
    }

    // ---------------------------------------------------------------------
    // find/file
    // ---------------------------------------------------------------------

    /** **セパレータが混在する**ことを実データで固定する。 */
    @Test
    fun `find file の応答はセパレータが混在する`() {
        val paths = Q8Decode.paths(Q8Fixtures.FIND_FILE_THEME_JSON)
        assertTrue(paths.any { it == "app/src/main/res/values/themes.xml" })
        assertTrue("ディレクトリは末尾に円マークが付く", paths.any { it.endsWith("\\") })
    }
}
