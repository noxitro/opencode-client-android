package dev.opencode.android

import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.PTY_TICKET_HEADER
import dev.opencode.android.data.PTY_TICKET_HEADER_VALUE
import dev.opencode.android.data.OpenCodeApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **アプリが実際に撃った HTTP** を assert する(Q9)。[Q7HttpContractTest] / [Q8HttpContractTest] の続き。
 *
 * ## なぜ Q9 でこれが特に要るのか
 *
 * Q9 には**落としても 200 が返る**ものと、**落とすと機能そのものが死ぬ**ものが両方ある:
 *
 *  - `x-opencode-ticket` を落とす → **403**。チケットが取れず**ターミナルが一度も開かない**。
 *    しかも spec にこのヘッダは載っていないので、契約文書だけを読んでも気付けない
 *  - `?cursor=` を落とす → **200**(接続は成功する)。症状は
 *    「**再接続のたびに画面の先頭からもう一度流れる**」で、クラッシュもエラーも出ない
 *  - `PUT /pty/{id}` の body の `size` を落とす → **200**。端末サイズが永久にサーバー既定のまま
 */
class Q9HttpContractTest {

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
    // 一覧・シェル
    // ---------------------------------------------------------------------

    @Test
    fun `pty shells は directory 無しなら素のパス`() = runTest {
        server.respond("/pty/shells", Q9Fixtures.SHELLS_JSON)
        val result = api.listPtyShells()
        assertEquals("/pty/shells", server.single().target)
        assertEquals(4, (result as ApiResult.Ok).value.size)
    }

    @Test
    fun `pty shells は directory を通す`() = runTest {
        server.respond("/pty/shells", Q9Fixtures.SHELLS_JSON)
        api.listPtyShells("E:/repo")
        assertEquals("/pty/shells?directory=E%3A%2Frepo", server.single().target)
    }

    @Test
    fun `GET pty は一覧を撃つ`() = runTest {
        server.respond("/pty", Q9Fixtures.LIST_PTY_JSON)
        val result = api.listPtys()
        assertEquals("GET", server.single().method)
        assertEquals("/pty", server.single().target)
        assertEquals(1, (result as ApiResult.Ok).value.size)
    }

    // ---------------------------------------------------------------------
    // 作成 / リサイズ / 削除
    // ---------------------------------------------------------------------

    /** **`cwd` も `env` も送らない**(端末から任意のパスや環境変数を入れる導線を作らない)。 */
    @Test
    fun `POST pty の body は command args title だけ`() = runTest {
        server.respond("/pty", Q9Fixtures.CREATE_PTY_JSON)
        api.createPty(command = "cmd.exe", title = "cmd")
        val recorded = server.single()
        assertEquals("POST", recorded.method)
        assertEquals("/pty", recorded.target)
        assertEquals("""{"command":"cmd.exe","args":[],"title":"cmd"}""", recorded.body)
        assertFalse(recorded.body.contains("cwd"))
        assertFalse(recorded.body.contains("env"))
    }

    /**
     * **リサイズは `PUT /pty/{id}` の body `{size:{rows,cols}}`**(実測。WS 経由ではない)。
     * `title` は送らない —— `explicitNulls = false` なのでキーごと落ちる。
     */
    @Test
    fun `PUT pty は size を送り title は送らない`() = runTest {
        server.respond("/pty/pty_x", Q9Fixtures.CREATE_PTY_JSON)
        api.resizePty("pty_x", rows = 24, cols = 100)
        val recorded = server.single()
        assertEquals("PUT", recorded.method)
        assertEquals("/pty/pty_x", recorded.target)
        assertEquals("""{"size":{"rows":24,"cols":100}}""", recorded.body)
    }

    @Test
    fun `DELETE pty はパスだけを撃つ`() = runTest {
        server.respond("/pty/pty_x", "true")
        api.deletePty("pty_x")
        assertEquals("DELETE", server.single().method)
        assertEquals("/pty/pty_x", server.single().target)
    }

    // ---------------------------------------------------------------------
    // connect-token(**spec に載っていないヘッダ**)
    // ---------------------------------------------------------------------

    /**
     * **このテストが Q9 の HTTP 側の要**である。
     *
     * 実測(2026-08-30):
     * ```
     * POST /pty/{id}/connect-token                      -> 403 PtyForbiddenError
     * POST /pty/{id}/connect-token  x-opencode-ticket:1 -> 200 {"ticket":…,"expires_in":60}
     * ```
     */
    @Test
    fun `connect-token は x-opencode-ticket ヘッダを付ける`() = runTest {
        server.respond("/pty/pty_x/connect-token", Q9Fixtures.TICKET_JSON)
        val result = api.ptyConnectToken("pty_x")
        val recorded = server.single()
        assertEquals("POST", recorded.method)
        assertEquals("/pty/pty_x/connect-token", recorded.target)
        assertEquals(PTY_TICKET_HEADER_VALUE, recorded.headers[PTY_TICKET_HEADER])
        assertEquals(
            "65ffeda9-f427-4fd0-b10b-73c241ab6b4a",
            (result as ApiResult.Ok).value.ticket,
        )
    }

    /** Basic 認証は他の口と同じく必ず付く(資格情報の経路を1本に保つ)。 */
    @Test
    fun `connect-token にも Authorization が付く`() = runTest {
        server.respond("/pty/pty_x/connect-token", Q9Fixtures.TICKET_JSON)
        api.ptyConnectToken("pty_x")
        assertNotNull(server.single().authorization)
        assertTrue(server.single().authorization!!.startsWith("Basic "))
    }

    // ---------------------------------------------------------------------
    // WebSocket の URL
    // ---------------------------------------------------------------------

    /**
     * **`cursor` を渡さない場合はキーごと落ちる**(サーバーは省略を「全部再送」と読む)。
     * `0` を送るのと**意味は同じ**だが、`-1`(リプレイ無し)とは違う。
     */
    @Test
    fun `connect の URL は ws で ticket を持つ`() {
        val url = api.ptyConnectUrl("pty_x", "tick-123", cursor = null)
        assertEquals(
            server.baseUrl.replace("http://", "ws://") + "/pty/pty_x/connect?ticket=tick-123",
            url,
        )
    }

    @Test
    fun `connect の URL は cursor を載せる`() {
        val url = api.ptyConnectUrl("pty_x", "t", cursor = 223L)
        assertTrue(url.endsWith("/pty/pty_x/connect?ticket=t&cursor=223"))
    }

    /** `-1` は「リプレイ無し」。**符号を落とすと全部を読み直す。** */
    @Test
    fun `connect の URL は -1 をそのまま載せる`() {
        assertTrue(api.ptyConnectUrl("pty_x", "t", cursor = -1L).endsWith("cursor=-1"))
    }

    @Test
    fun `connect の URL は directory も載せる`() {
        val url = api.ptyConnectUrl("pty_x", "t", cursor = 0L, directory = "E:/repo")
        assertTrue(url.endsWith("?ticket=t&cursor=0&directory=E%3A%2Frepo"))
    }

    /** チケットは URL エンコードして載せる(記号を含んでも URL が壊れない)。 */
    @Test
    fun `ticket はエンコードされる`() {
        val url = api.ptyConnectUrl("pty_x", "a b&c", cursor = null)
        assertTrue(url.contains("ticket=a+b%26c"))
    }
}
