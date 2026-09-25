package dev.opencode.android

import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.OpenCodeApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **アプリが実際に撃った URL** を assert する(Q7 レビュー minor-2)。
 *
 * ## この検出器が無かったせいで通り抜けた変異
 *
 * レビューの **RB**: `?messageID=` を `?messageId=` に変えても **610件全緑**。
 * 実物 serve は**未知のクエリキーを黙って無視する**(レビュー実測: 200 が返る)ので、
 * 「メッセージの差分」を開くと**セッション全体の差分が無言で出る**。
 * DTO のテストも状態機械のテストも、**アプリが何を送ったか**は見ていなかった。
 *
 * ## 何を主張しているか
 *
 * `OpenCodeApi` を**本物の HTTP サーバー**([RecordingHttpServer])へ向けて叩き、
 * 受け取った `path?query` を**文字列として**比較する。
 * キー名・値・順序・エンコード・「送らないこと」の全部がここで固定される。
 *
 * **Q8 が相続する**(`/file` `/find` で URL 組み立てはさらに増える)。
 */
class Q7HttpContractTest {

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
    // GET /session/{id}/diff — レビューの変異 RB が通り抜けた場所
    // ---------------------------------------------------------------------

    @Test
    fun `session diff は messageID を大文字Dで送る`() = runTest {
        api.sessionDiff("ses_1", "msg_9")
        assertEquals("/session/ses_1/diff?messageID=msg_9", server.single().target)
    }

    /** `messageID` が無ければ**キーごと送らない**(空の `?messageID=` を送らない)。 */
    @Test
    fun `messageID が無ければクエリを付けない`() = runTest {
        api.sessionDiff("ses_1", null)
        val rec = server.single()
        assertEquals("/session/ses_1/diff", rec.path)
        assertNull(rec.rawQuery)
    }

    @Test
    fun `session diff は directory も載せられる`() = runTest {
        api.sessionDiff("ses_1", "msg_9", directory = "/tmp/x")
        assertEquals("/session/ses_1/diff?messageID=msg_9&directory=%2Ftmp%2Fx", server.single().target)
    }

    // ---------------------------------------------------------------------
    // GET /vcs*
    // ---------------------------------------------------------------------

    @Test
    fun `vcs info はクエリ無しで撃つ`() = runTest {
        server.respond("/vcs", Q7DiffFixtures.VCS_INFO_JSON)
        val result = api.vcsInfo()
        assertEquals("/vcs", server.single().target)
        assertTrue(result is ApiResult.Ok)
        assertEquals("master", (result as ApiResult.Ok).value.branch)
    }

    @Test
    fun `vcs status もクエリ無し`() = runTest {
        server.respond("/vcs/status", Q7DiffFixtures.VCS_STATUS_JSON)
        api.vcsStatus()
        assertEquals("/vcs/status", server.single().target)
    }

    /**
     * **`mode` は required**(実物は省略すると 400)。`context` は
     * サーバー既定がファイル全体に近いので**必ず載せる**。
     */
    @Test
    fun `vcs diff は mode と context を載せる`() = runTest {
        api.vcsDiff(mode = "git", context = 3)
        assertEquals("/vcs/diff?mode=git&context=3", server.single().target)
    }

    @Test
    fun `vcs diff は directory も載せられる`() = runTest {
        api.vcsDiff(mode = "branch", context = 0, directory = "/repo a")
        assertEquals("/vcs/diff?mode=branch&context=0&directory=%2Frepo+a", server.single().target)
    }

    /** `context` を渡さなければキーごと落ちる(`context=null` と書かない)。 */
    @Test
    fun `context を渡さなければキーごと落ちる`() = runTest {
        api.vcsDiff(mode = "git", context = null)
        assertEquals("/vcs/diff?mode=git", server.single().target)
    }

    // ---------------------------------------------------------------------
    // POST /session/{id}/revert / unrevert
    // ---------------------------------------------------------------------

    @Test
    fun `revert は POST でボディに messageID を送る`() = runTest {
        server.respond("/session/ses_1/revert", """{"id":"ses_1","title":"t"}""")
        api.revertMessage("ses_1", "msg_9")
        val rec = server.single()
        assertEquals("POST", rec.method)
        assertEquals("/session/ses_1/revert", rec.target)
        // **`partID` の null をボディに書かない**(additionalProperties:false のスキーマで 400 になる形)。
        assertEquals("""{"messageID":"msg_9"}""", rec.body)
    }

    @Test
    fun `revert は partID を送れる`() = runTest {
        server.respond("/session/ses_1/revert", """{"id":"ses_1","title":"t"}""")
        api.revertMessage("ses_1", "msg_9", partId = "prt_3", directory = "/tmp/x")
        val rec = server.single()
        assertEquals("/session/ses_1/revert?directory=%2Ftmp%2Fx", rec.target)
        assertEquals("""{"messageID":"msg_9","partID":"prt_3"}""", rec.body)
    }

    @Test
    fun `unrevert は POST でボディを持たない`() = runTest {
        server.respond("/session/ses_1/unrevert", """{"id":"ses_1","title":"t"}""")
        api.unrevertSession("ses_1")
        val rec = server.single()
        assertEquals("POST", rec.method)
        assertEquals("/session/ses_1/unrevert", rec.target)
        assertEquals("", rec.body)
    }

    // ---------------------------------------------------------------------
    // 既存の口も1本ずつ押さえる(この検出器が Q7 専用ではないことを示す)
    // ---------------------------------------------------------------------

    /**
     * Q1 の実測「**`start` は渡さない**」(オフセットではないため)を URL で固定する。
     * これまでは KDoc にしか書かれておらず、送ってしまう変異を誰も見ていなかった。
     */
    @Test
    fun `session 一覧は limit と search だけを送り start を送らない`() = runTest {
        api.listSessions(limit = 50, search = "a b&c")
        val rec = server.single()
        assertEquals("/session?limit=50&search=a+b%26c", rec.target)
        assertTrue("start を送らない", rec.rawQuery?.contains("start") != true)
    }

    /** 認証は毎回 Basic ヘッダで載る(**値は assert しない**。AGENTS.md: 資格情報を記録しない)。 */
    @Test
    fun `全ての呼び出しに Basic 認証ヘッダが載る`() = runTest {
        api.vcsInfo()
        val auth = server.single().authorization
        assertTrue("Basic ヘッダが無い", auth != null && auth.startsWith("Basic "))
    }
}
