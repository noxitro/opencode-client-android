package dev.opencode.android.data

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Q9 の PTY 呼び出し(REST + WebSocket)。
 * **資格情報は [ConnectionRepository] が持つ**ので、呼び出し側は URL もパスワードも知らない。
 */
class PtyRepository(
    private val connection: ConnectionRepository,
) : PtyGateway {

    override val isConfigured: Boolean
        get() = connection.isConfigured

    /** GET /pty/shells */
    override suspend fun listShells(directory: String?): ApiResult<List<PtyShellDto>> =
        connection.withApi { it.listPtyShells(directory) }

    /** GET /pty */
    override suspend fun list(directory: String?): ApiResult<List<PtyDto>> =
        connection.withApi { it.listPtys(directory) }

    /** POST /pty */
    override suspend fun create(
        command: String,
        args: List<String>,
        title: String,
        directory: String?,
    ): ApiResult<PtyDto> = connection.withApi { it.createPty(command, args, title, directory) }

    /** PUT /pty/{id} {size:{rows,cols}} */
    override suspend fun resize(
        ptyId: String,
        rows: Int,
        cols: Int,
        directory: String?,
    ): ApiResult<PtyDto> = connection.withApi { it.resizePty(ptyId, rows, cols, directory) }

    /** DELETE /pty/{id} */
    override suspend fun delete(ptyId: String, directory: String?): ApiResult<Unit> =
        connection.withApi { it.deletePty(ptyId, directory) }

    /**
     * `connect-token` → WebSocket。**2手であることをこの1メソッドに閉じる。**
     *
     * チケットを呼び出し側へ返さないのは、返すと「1本取って何度も繋ぐ」実装が書けるためで、
     * それは実測では**2本目が黙って失敗する**(チケットは単回使用)。症状は
     * 「再接続だけがなぜか繋がらない」で、1本目は正常に動いている。
     *
     * **`directory` は両方に同じ値を渡す。** 実測ではズレていても 101 になったが、
     * 揃える理由が無くなるわけではない —— サーバーが将来束縛を効かせたとき、
     * 揃えていない実装は「再接続だけが失敗する」形で壊れる。
     */
    override suspend fun connect(
        ptyId: String,
        cursor: Long?,
        directory: String?,
        listener: PtySocketListener,
    ): ApiResult<PtySocket> {
        val api = connection.api() ?: return ApiResult.Err(ApiError.NotConfigured)
        val ticket = when (val result = api.ptyConnectToken(ptyId, directory)) {
            is ApiResult.Err -> return ApiResult.Err(result.error)
            is ApiResult.Ok -> result.value.ticket
        }
        if (ticket.isBlank()) {
            return ApiResult.Err(ApiError.Network("サーバーが空のチケットを返しました"))
        }
        val url = api.ptyConnectUrl(ptyId, ticket, cursor, directory)
        val socket = api.openPtyWebSocket(url, OkHttpPtyListener(listener))
        return ApiResult.Ok(OkHttpPtySocket(socket))
    }
}

/**
 * OkHttp の [WebSocketListener] を [PtySocketListener] へ落とす。
 *
 * **ここでフレーム判別を済ませる**([classifyPtyTextFrame] / [classifyPtyBinaryFrame])——
 * 上の層に `bytes[0] == 0` を書き戻さないこと。判別を2か所に置くと、片方だけを
 * 直した変異が「メタが本文として画面に出る」形で残る。
 *
 * **`internal` にしてあるのはテストが直接駆動するためである**(レビュー major-2)。
 * `onClosing` で確定扱いにする経路と `onFailure -> code=null` の経路は、
 * 偽の [WebSocket] を渡さないと再現できない ——
 * 1周目はこのファイル全体に検出器が1本も無かった。
 */
internal class OkHttpPtyListener(private val delegate: PtySocketListener) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) = delegate.onOpen()

    override fun onMessage(webSocket: WebSocket, text: String) =
        delegate.onFrame(classifyPtyTextFrame(text))

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
        delegate.onFrame(classifyPtyBinaryFrame(bytes.toByteArray()))

    /**
     * サーバーが閉じ始めた。**ここで確定として扱う** —— `onClosed` は
     * こちらが `close()` を返すまで来ないので、`onClosing` だけで終わる経路がある。
     */
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
        delegate.onClosed(code, reason)
    }

    /**
     * **`code = null` で伝える。** ハンドシェイクにすら至らなかった失敗と、
     * サーバーが `1000` で閉じた正常終了は別のことである ——
     * ここを 0 や 1006 に潰すと、画面が「接続できなかった」と「シェルが終了した」を
     * 区別できなくなる(このリポジトリが7度閉じてきた「無い/取れなかった」の形)。
     */
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val detail = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
        delegate.onClosed(null, detail)
    }
}

internal class OkHttpPtySocket(private val socket: WebSocket) : PtySocket {
    /** テキストフレームで送る(実測: サーバーはテキスト/バイナリどちらでも受ける)。 */
    override fun send(input: String): Boolean = socket.send(input)

    /** 1000 = 正常終了。理由は送らない(サーバーは見ていない)。 */
    override fun close() {
        socket.close(1000, null)
    }
}
