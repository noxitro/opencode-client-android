package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ConnectionRepository
import dev.opencode.android.data.OkHttpPtyListener
import dev.opencode.android.data.OkHttpPtySocket
import dev.opencode.android.data.PTY_TICKET_HEADER
import dev.opencode.android.data.PtyFrame
import dev.opencode.android.data.PtyRepository
import dev.opencode.android.data.PtySocketListener
import dev.opencode.android.data.SettingsRepository
import dev.opencode.android.data.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PtyRepository] **そのもの**を駆動する(レビュー major-2)。
 *
 * ## なぜ要るのか
 *
 * このフェーズの第一規則 —— **「チケットは単回使用。接続のたびに取り直す」** ——
 * を実装している唯一の場所が [PtyRepository.connect] の2手(token → connect)である。
 * 1周目はここを**どのテストも駆動していなかった**: [Q9ControllerTest] は
 * [FakePtyGateway] に差し替えており、[Q9HttpContractTest] は `OpenCodeApi` の口を
 * 1つずつ叩くだけで、**2手が2手であること**は誰も主張していなかった。
 *
 * 症状は「1本目は正常に動き、**再接続だけが黙って失敗する**」で、
 * 画面はエラーも出さずに「接続中…」のままになる。
 *
 * ## 何を Fake にしないか
 *
 *  - **REST は本物の HTTP** で測る([RecordingHttpServer])。撃たれた順序と本数を数える
 *  - **WebSocket のリスナは本物の [OkHttpPtyListener]** を、偽の [WebSocket] で駆動する。
 *    `onClosing` で確定扱いにする経路は、OkHttp が閉じ始めた側にならないと再現できない
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q9RepositoryTest {

    private lateinit var server: RecordingHttpServer

    @Before
    fun setUp() {
        server = RecordingHttpServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private class FakeStore(baseUrl: String) : SettingsStore {
        val state = MutableStateFlow(SettingsRepository.Saved(baseUrl, "pw"))
        override val saved: Flow<SettingsRepository.Saved> = state
        override suspend fun save(baseUrl: String, password: String) {
            state.value = SettingsRepository.Saved(baseUrl, password)
        }
    }

    private fun TestScope.repository(): PtyRepository =
        PtyRepository(ConnectionRepository(FakeStore(server.baseUrl), backgroundScope))

    /** 通知を控えるだけの宛先。**素通しの `Unit` を返さない。** */
    private class RecordingListener : PtySocketListener {
        val events = mutableListOf<String>()
        val frames = mutableListOf<PtyFrame>()
        var closeCode: Int? = null
        var sawClose = false
        var closeReason: String = ""
        override fun onOpen() { events += "open" }
        override fun onFrame(frame: PtyFrame) {
            events += "frame"
            frames += frame
        }
        override fun onClosed(code: Int?, reason: String) {
            events += "closed"
            sawClose = true
            closeCode = code
            closeReason = reason
        }
    }

    /**
     * WebSocket のハンドシェイクは非同期なので、**記録が増えるまで実時間で待つ**。
     * 待たずに数えると「まだ来ていない」を「来なかった」と読む(このリポジトリが
     * 繰り返し閉じてきた形)。
     */
    private fun awaitRecorded(count: Int, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (server.recorded.size >= count) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun awaitClose(listener: RecordingListener, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (listener.sawClose) return true
            Thread.sleep(20)
        }
        return false
    }

    // ---------------------------------------------------------------------
    // 2手であること(このフェーズの第一規則)
    // ---------------------------------------------------------------------

    /**
     * **接続のたびに `connect-token` を取り直す。**
     *
     * チケットは単回使用(実測 2026-08-30)。1本取って使い回す実装は
     * **1本目だけ動く** —— 症状は「再接続だけがなぜか繋がらない」である。
     */
    @Test
    fun `connect は毎回チケットを取り直してから WebSocket を開く`() = runTest(UnconfinedTestDispatcher()) {
        server.respond("/pty/pty_x/connect-token", Q9Fixtures.TICKET_JSON)
        val repo = repository()

        val first = repo.connect("pty_x", cursor = null, directory = null, listener = RecordingListener())
        assertTrue(first is ApiResult.Ok)
        assertTrue("ws のハンドシェイクが撃たれていない", awaitRecorded(2))

        val second = repo.connect("pty_x", cursor = 223L, directory = null, listener = RecordingListener())
        assertTrue(second is ApiResult.Ok)
        assertTrue(awaitRecorded(4))

        val targets = server.recorded.map { "${it.method} ${it.path}" }
        assertEquals(
            listOf(
                "POST /pty/pty_x/connect-token",
                "GET /pty/pty_x/connect",
                "POST /pty/pty_x/connect-token",
                "GET /pty/pty_x/connect",
            ),
            targets,
        )
        // **チケットは URL に載る**(載らなければサーバーは 403 を返す)。
        assertTrue(server.recorded[1].target.contains("ticket=65ffeda9"))
        // 2本目は保存した位置から読み直す。
        assertTrue(server.recorded[3].target.contains("cursor=223"))
        // **spec に載っていないヘッダ**を毎回付ける(落とすと 403 でチケットが取れない)。
        assertEquals("1", server.recorded[0].headers[PTY_TICKET_HEADER])
        assertEquals("1", server.recorded[2].headers[PTY_TICKET_HEADER])
    }

    /**
     * **空のチケットで繋ぎに行かない。**
     *
     * 空で開くと WebSocket 側は 403 で落ち、画面には
     * 「接続が切れました(接続できませんでした)」だけが出る ——
     * 原因(サーバーがチケットを出さなかった)が画面から消える。
     */
    @Test
    fun `空のチケットは拒否して WebSocket を開かない`() = runTest(UnconfinedTestDispatcher()) {
        server.respond("/pty/pty_x/connect-token", """{"ticket":"","expires_in":60}""")
        val repo = repository()
        val result = repo.connect("pty_x", cursor = null, directory = null, listener = RecordingListener())
        assertTrue(result is ApiResult.Err)
        assertTrue((result as ApiResult.Err).error is ApiError.Network)
        // **撃たれたのは token だけ。** ws のハンドシェイクは無い。
        assertFalse(awaitRecorded(2, timeoutMs = 500))
        assertEquals(listOf("/pty/pty_x/connect-token"), server.recorded.map { it.path })
    }

    /** チケットが取れなければ、そこで止まる(WebSocket は開かない)。 */
    @Test
    fun `チケットが 403 なら WebSocket を開かない`() = runTest(UnconfinedTestDispatcher()) {
        server.respond("/pty/pty_x/connect-token", """{"data":{"message":"forbidden"}}""", status = 403)
        val repo = repository()
        val result = repo.connect("pty_x", cursor = null, directory = null, listener = RecordingListener())
        assertEquals(ApiError.Http(403), (result as ApiResult.Err).error)
        assertFalse(awaitRecorded(2, timeoutMs = 500))
    }

    /** 未設定なら通信そのものをしない。 */
    @Test
    fun `未設定なら NotConfigured`() = runTest(UnconfinedTestDispatcher()) {
        val repo = PtyRepository(ConnectionRepository(FakeStore(""), backgroundScope))
        val result = repo.connect("pty_x", cursor = null, directory = null, listener = RecordingListener())
        assertEquals(ApiError.NotConfigured, (result as ApiResult.Err).error)
        assertTrue(server.recorded.isEmpty())
    }

    /** `directory` は **token と connect の両方に同じ値**を載せる。 */
    @Test
    fun `directory は 2手の両方に載る`() = runTest(UnconfinedTestDispatcher()) {
        server.respond("/pty/pty_x/connect-token", Q9Fixtures.TICKET_JSON)
        val repo = repository()
        repo.connect("pty_x", cursor = null, directory = "E:/repo", listener = RecordingListener())
        assertTrue(awaitRecorded(2))
        assertTrue(server.recorded[0].target.contains("directory=E%3A%2Frepo"))
        assertTrue(server.recorded[1].target.contains("directory=E%3A%2Frepo"))
    }

    // ---------------------------------------------------------------------
    // 「繋がらなかった」の伝わり方
    // ---------------------------------------------------------------------

    /**
     * **ハンドシェイクに至らなかった失敗は `code = null`。**
     *
     * ここを `0` や `1006` に潰すと、画面が「接続できなかった」と
     * 「シェルが終了した(`1000`)」を区別できなくなる。
     *
     * この検出器は**本物のサーバー相手**である: [RecordingHttpServer] は
     * 101 を返さないので、OkHttp は `onFailure` を呼ぶ。
     */
    @Test
    fun `101 が返らなければ code は null で伝わる`() = runTest(UnconfinedTestDispatcher()) {
        server.respond("/pty/pty_x/connect-token", Q9Fixtures.TICKET_JSON)
        val listener = RecordingListener()
        val repo = repository()
        // **開こうとしたところまでは成功**である(101 を待たない。待つと UI が塞がる)。
        assertTrue(repo.connect("pty_x", cursor = null, directory = null, listener = listener) is ApiResult.Ok)
        assertTrue("onClosed が来ない", awaitClose(listener))
        assertNull("ハンドシェイク不達を close code として扱っている", listener.closeCode)
        assertTrue("理由が空だと原因が画面から消える", listener.closeReason.isNotBlank())
        assertFalse(listener.events.contains("open"))
    }

    // ---------------------------------------------------------------------
    // OkHttp のリスナ(偽の WebSocket で駆動する)
    // ---------------------------------------------------------------------

    /** `close(code, reason)` を控えるだけの偽 [WebSocket]。 */
    private class FakeWebSocket : WebSocket {
        val closes = mutableListOf<Pair<Int, String?>>()
        var cancelled = false
        val sent = mutableListOf<String>()
        override fun cancel() { cancelled = true }
        override fun close(code: Int, reason: String?): Boolean {
            closes += code to reason
            return true
        }
        override fun queueSize(): Long = 0
        override fun request(): Request = Request.Builder().url("http://127.0.0.1/").build()
        override fun send(text: String): Boolean {
            sent += text
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            sent += bytes.utf8()
            return true
        }
    }

    /**
     * **`onClosing` で確定として扱う。**
     *
     * `onClosed` は**こちらが `close()` を返すまで来ない**ので、
     * `onClosing` だけで終わる経路がある。ここを落とすと、サーバーが閉じ始めても
     * 画面は「接続中」のまま1文字も増えない(再接続の契機も生まれない)。
     */
    @Test
    fun `onClosing は自分から閉じ返して確定として伝える`() {
        val delegate = RecordingListener()
        val socket = FakeWebSocket()
        OkHttpPtyListener(delegate).onClosing(socket, 1000, "bye")
        assertEquals(listOf(1000 to null), socket.closes)
        assertTrue(delegate.sawClose)
        assertEquals(1000, delegate.closeCode)
        assertEquals("bye", delegate.closeReason)
    }

    /** `onFailure` は **`code = null`**。応答があれば HTTP の番号を理由に載せる。 */
    @Test
    fun `onFailure は code null で応答があれば HTTP 番号を載せる`() {
        val delegate = RecordingListener()
        val response = Response.Builder()
            .request(Request.Builder().url("http://127.0.0.1/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .build()
        OkHttpPtyListener(delegate).onFailure(FakeWebSocket(), RuntimeException("boom"), response)
        assertNull(delegate.closeCode)
        assertEquals("HTTP 403", delegate.closeReason)
    }

    /** 応答が無ければ例外の理由を載せる(**空文字にしない**)。 */
    @Test
    fun `onFailure は応答が無ければ例外の理由を載せる`() {
        val delegate = RecordingListener()
        OkHttpPtyListener(delegate).onFailure(FakeWebSocket(), java.net.ConnectException("refused"), null)
        assertNull(delegate.closeCode)
        assertEquals("refused", delegate.closeReason)
    }

    /**
     * **フレーム判別はこの層で済ませる。** 上へ生バイトを渡さない ——
     * 判別を2か所に置くと、片方だけを直した変異が
     * 「メタの JSON が端末画面に出る」形で残る。
     */
    @Test
    fun `テキストは出力 先頭 0x00 のバイナリはメタとして上げる`() {
        val delegate = RecordingListener()
        val listener = OkHttpPtyListener(delegate)
        listener.onMessage(FakeWebSocket(), "こんにちは")
        listener.onMessage(FakeWebSocket(), (byteArrayOf(0x00) + """{"cursor":223}""".toByteArray()).toByteString())
        listener.onMessage(FakeWebSocket(), "plain".toByteArray().toByteString())

        val output = delegate.frames[0] as PtyFrame.Output
        assertEquals("こんにちは", output.text)
        // **バイト数であって文字数ではない**(日本語で 1文字 3バイト)。
        assertEquals(15, output.byteLength)
        assertNotEquals(output.text.length, output.byteLength)
        assertEquals(PtyFrame.Meta(223), delegate.frames[1])
        assertEquals(PtyFrame.Output("plain", 5), delegate.frames[2])
    }

    /** `onOpen` は素通しで上がる(101 が返ったことの唯一の合図)。 */
    @Test
    fun `onOpen が上がる`() {
        val delegate = RecordingListener()
        val response = Response.Builder()
            .request(Request.Builder().url("http://127.0.0.1/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        OkHttpPtyListener(delegate).onOpen(FakeWebSocket(), response)
        assertEquals(listOf("open"), delegate.events)
    }

    /** こちらから閉じるときは **1000 / 理由なし**。入力はテキストフレームで送る。 */
    @Test
    fun `送信と切断は WebSocket へそのまま渡る`() {
        val socket = FakeWebSocket()
        val pty = OkHttpPtySocket(socket)
        assertTrue(pty.send("\u0003"))
        pty.close()
        assertEquals(listOf("\u0003"), socket.sent)
        assertEquals(listOf(1000 to null), socket.closes)
    }
}
