package dev.opencode.android

import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.FIND_FILE_LIMIT
import dev.opencode.android.data.OpenCodeApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **アプリが実際に撃った URL** を assert する(Q8)。[Q7HttpContractTest] の続き。
 *
 * ## なぜ Q8 でこれが特に要るのか
 *
 * Q7 のレビューが打った変異 **RB**(`?messageID=` → `?messageId=`)は **610件全緑**で通り抜けた。
 * **実物 serve は未知のクエリキーを黙って無視する**(レビュー実測)ので、
 * 症状は「メッセージの差分を開いたのに**セッション全体の差分が無言で出る**」になる。
 *
 * Q8 は URL 組み立てが**一気に6本増える**。しかも:
 *
 *  - `path` / `pattern` / `query` は **required**(落とすと 400 で気付ける)
 *  - `directory` / `limit` は **任意**(落としても 200 が返る = **気付けない**)
 *
 * 気付けない側こそがここの守備範囲である。
 * `Q7HttpContractTest` が用意した [RecordingHttpServer] をそのまま使う。
 */
class Q8HttpContractTest {

    private lateinit var server: RecordingHttpServer
    private lateinit var api: OpenCodeApi

    @Before
    fun setUp() {
        server = RecordingHttpServer()
        api = OpenCodeApi(server.baseUrl, "test-pass")
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ---------------------------------------------------------------------
    // GET /file
    // ---------------------------------------------------------------------

    @Test
    fun `file はルートを path=ドットで撃つ`() = runTest {
        api.listFiles(".")
        assertEquals("/file?path=.", server.single().target)
    }

    /**
     * **パスは `/` 区切りで送る。** サーバーは `\` でも受けるが(実測で同一の応答)、
     * 送る側の表現を1つに決めないと `Q8ComposeTest` の主張と食い違う。
     */
    @Test
    fun `file はスラッシュ区切りで送る`() = runTest {
        api.listFiles("app/src/main")
        assertEquals("/file?path=app%2Fsrc%2Fmain", server.single().target)
    }

    /** `directory` は**落としても 200 が返る**ので、ここでしか守れない。 */
    @Test
    fun `file は directory も載せられる`() = runTest {
        api.listFiles("app", directory = "/tmp/x")
        assertEquals("/file?path=app&directory=%2Ftmp%2Fx", server.single().target)
    }

    @Test
    fun `directory が無ければキーごと送らない`() = runTest {
        api.listFiles("app")
        assertFalse(server.single().rawQuery!!.contains("directory"))
    }

    @Test
    fun `file の応答は実データでデコードされる`() = runTest {
        server.respond("/file", Q8Fixtures.FILE_LIST_APP_JSON)
        val result = api.listFiles("app")
        assertTrue(result is ApiResult.Ok)
        assertEquals(4, (result as ApiResult.Ok).value.size)
    }

    // ---------------------------------------------------------------------
    // GET /file/content
    // ---------------------------------------------------------------------

    @Test
    fun `file content は path を送る`() = runTest {
        server.respond("/file/content", Q8Fixtures.FILE_CONTENT_TEXT_JSON)
        api.readFileContent("app/x.kt")
        assertEquals("/file/content?path=app%2Fx.kt", server.single().target)
    }

    @Test
    fun `file content の応答をデコードする`() = runTest {
        server.respond("/file/content", Q8Fixtures.FILE_CONTENT_BINARY_JSON)
        val result = api.readFileContent("image.bin")
        assertTrue(result is ApiResult.Ok)
        val payload = (result as ApiResult.Ok).value
        assertTrue(payload.isBinary)
        assertEquals("application/octet-stream", payload.mimeType)
        assertFalse(payload.truncated)
    }

    /**
     * **`readFileContent` も `directory` を通すこと**(レビュー minor-1)。
     *
     * `listFiles` / `findText` / `findFiles` はここで固定されていたのに、
     * **`readFile` だけ固定されていなかった** —— レビューの変異
     * `readFile(target, directory)` → `readFile(target, null)` が **PASS_THROUGH**。
     *
     * `directory` は**落としても 200 が返る**(実測: `?directoy=zzz` のタイポでも
     * cwd の結果が返る)ので、落ちたことに気付く手段はこの assert しか無い。
     * QUALITY_PLAN §6 が「データ層は最初から `directory` を通せる形に」と名指しした当の層である。
     */
    @Test
    fun `file content も directory を載せられる`() = runTest {
        server.respond("/file/content", Q8Fixtures.FILE_CONTENT_TEXT_JSON)
        api.readFileContent("a.kt", directory = "/tmp/x")
        assertEquals("/file/content?path=a.kt&directory=%2Ftmp%2Fx", server.single().target)
    }

    /**
     * **打ち切りが本当に効くこと。** 閾値より大きい応答を返させて、
     * `truncated` が立ち、`receivedBytes` が閾値に張り付くことを見る。
     *
     * ここが無いと「閾値を書いた」だけで「切っている」の証拠にならない ——
     * Q6 が KDoc に存在しない検出を書いた形の再発を防ぐ。
     */
    @Test
    fun `閾値を超えたら受信を打ち切る`() = runTest {
        val body = """{"type":"text","content":"""" + "a".repeat(4096) + """"}"""
        server.respond("/file/content", body)
        val result = api.readFileContent("big.txt", maxBytes = 512)
        val payload = (result as ApiResult.Ok).value
        assertTrue("切ったことを伝える", payload.truncated)
        assertEquals(512, payload.receivedBytes)
        assertEquals("text", payload.type)
        assertTrue("先頭は読めている", payload.content.startsWith("aaaa"))
        assertTrue("全部は読んでいない", payload.content.length < 4096)
    }

    /** **閾値ちょうどのファイルは「切った」と言わない**(1バイト覗いて決めている)。 */
    @Test
    fun `閾値ちょうどでは打ち切ったと言わない`() = runTest {
        val body = """{"type":"text","content":"ab"}"""
        server.respond("/file/content", body)
        val payload = (api.readFileContent("x", maxBytes = body.length) as ApiResult.Ok).value
        assertFalse(payload.truncated)
        assertEquals("ab", payload.content)
    }

    // ---------------------------------------------------------------------
    // GET /file/status(**画面は使わないが口は在る**)
    // ---------------------------------------------------------------------

    @Test
    fun `file status はクエリ無しで撃つ`() = runTest {
        api.fileStatus()
        val rec = server.single()
        assertEquals("/file/status", rec.path)
        assertNull(rec.rawQuery)
    }

    // ---------------------------------------------------------------------
    // GET /find
    // ---------------------------------------------------------------------

    @Test
    fun `find は pattern というキーで送る`() = runTest {
        server.respond("/find", Q8Fixtures.FIND_TWO_SUBMATCHES_JSON)
        api.findText("the")
        assertEquals("/find?pattern=the", server.single().target)
    }

    /** 記号を含む語で URL が壊れないこと(正規表現として渡るので記号は普通に来る)。 */
    @Test
    fun `find は記号を含む語をエンコードする`() = runTest {
        api.findText("a&b=c d")
        assertEquals("/find?pattern=a%26b%3Dc+d", server.single().target)
    }

    @Test
    fun `find は日本語をエンコードする`() = runTest {
        api.findText("差分")
        assertEquals("/find?pattern=%E5%B7%AE%E5%88%86", server.single().target)
    }

    // ---------------------------------------------------------------------
    // GET /find/file
    // ---------------------------------------------------------------------

    /**
     * **`limit` を必ず送る**(§5b スコープ4)。**送らなくても 200 が返る**ので、
     * 落ちたことに気付く手段はここしか無い。
     */
    @Test
    fun `find file は query と limit を送る`() = runTest {
        server.respond("/find/file", Q8Fixtures.FIND_FILE_THEME_JSON)
        api.findFiles("Theme")
        assertEquals("/find/file?query=Theme&limit=$FIND_FILE_LIMIT", server.single().target)
    }

    /** spec の上限は 200。**超える値を既定にしていないこと**を数値で固定する。 */
    @Test
    fun `既定の limit は spec の上限以内`() {
        assertTrue(FIND_FILE_LIMIT in 1..200)
    }

    /** `dirs` / `type` は送らない(`dirs` は boolean ではなく文字列 enum なので送ると壊れうる)。 */
    @Test
    fun `find file は dirs と type を送らない`() = runTest {
        api.findFiles("x")
        val query = server.single().rawQuery!!
        assertFalse(query.contains("dirs"))
        assertFalse(query.contains("type"))
    }

    // ---------------------------------------------------------------------
    // GET /find/symbol
    // ---------------------------------------------------------------------

    @Test
    fun `find symbol は query というキーで送る`() = runTest {
        server.respond("/find/symbol", Q8Fixtures.SYMBOL_EMPTY_JSON)
        api.findSymbols("DiffController")
        assertEquals("/find/symbol?query=DiffController", server.single().target)
    }

    // ---------------------------------------------------------------------
    // 認証(全部の口に付いていること)
    // ---------------------------------------------------------------------

    /**
     * Q8 で足した6本の口が**全部 Basic 認証ヘッダを載せる**こと。
     * 1本だけ落ちると、その口だけが 401 になる —— 画面はどこも壊れて見えない。
     */
    @Test
    fun `Q8 の口はすべて認証ヘッダを載せる`() = runTest {
        server.respond("/file/content", Q8Fixtures.FILE_CONTENT_TEXT_JSON)
        api.listFiles(".")
        api.readFileContent("x")
        api.fileStatus()
        api.findText("x")
        api.findFiles("x")
        api.findSymbols("x")
        assertEquals(6, server.recorded.size)
        server.recorded.forEach { rec ->
            assertTrue(rec.target, rec.authorization?.startsWith("Basic ") == true)
        }
    }
}
