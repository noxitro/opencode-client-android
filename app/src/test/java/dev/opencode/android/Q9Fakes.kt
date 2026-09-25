package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.PtyDto
import dev.opencode.android.data.PtyFrame
import dev.opencode.android.data.PtyGateway
import dev.opencode.android.data.PtyShellDto
import dev.opencode.android.data.PtySocket
import dev.opencode.android.data.PtySocketListener
import dev.opencode.android.data.contractJson

/**
 * Q9 のテストが共有する偽ゲートウェイ。
 *
 * **応答は実データから作る**([Q9Fixtures] をデコードする)。手で組んだ DTO を既定にすると、
 * 契約の食い違いがテストからは見えなくなる(§4.2、4度の失敗)。
 *
 * WebSocket は**テストが自分で駆動する**: [connect] は開いたソケットを [sockets] に控え、
 * テストがそこから `listener` を呼んで「サーバーが何かを送ってきた」を作る。
 * こうしないと、Q9 の中核(切断と終了の区別・cursor の継続)がまったく測れない。
 */
class FakePtyGateway(
    override val isConfigured: Boolean = true,
    /** 再接続の宛先の呼び出し順を控える器([FakeFilesGateway] と同じ役割)。 */
    private val order: MutableList<String>? = null,
) : PtyGateway {

    /** テストが操作できる偽ソケット。 */
    class FakeSocket(
        val ptyId: String,
        /** 接続時に渡された `cursor`。**再開位置の主張はここで測る。** */
        val cursor: Long?,
        val directory: String?,
        val listener: PtySocketListener,
    ) {
        val sent = mutableListOf<String>()
        var closed = false

        /** `send` の戻り値。false にすると「送れなかった」を作れる。 */
        var accepts = true

        val socket: PtySocket = object : PtySocket {
            override fun send(input: String): Boolean {
                sent += input
                return accepts
            }

            override fun close() {
                closed = true
            }
        }

        fun emitText(text: String) = listener.onFrame(dev.opencode.android.data.classifyPtyTextFrame(text))
        fun emitMeta(cursor: Long) = listener.onFrame(PtyFrame.Meta(cursor))
        fun open() = listener.onOpen()
        fun close(code: Int?, reason: String = "") = listener.onClosed(code, reason)
    }

    /** 開かれたソケットを開いた順に控える。**閉じたものも残す**(再接続を数えるため)。 */
    val sockets = mutableListOf<FakeSocket>()

    /** `connect` に渡された `directory` の列(`directory` を落とす変異を捕まえる)。 */
    val connectDirectories = mutableListOf<String?>()

    val createdCommands = mutableListOf<Pair<String, String>>()
    val resizes = mutableListOf<Triple<String, Int, Int>>()
    val deleted = mutableListOf<String>()

    /** `GET /pty` が撃たれた回数。 */
    var listCalls = 0

    /**
     * `list()` を**待たせる**ための門。
     *
     * これが在るのは、実機で踏んだ競合を再現するためである(2026-08-30):
     * ソケットが閉じたときの処理は `GET /pty` を待つが、**その間に SSE の
     * `pty.exited` が着地する**。待つ前に読んだ生死を使うと、
     * 終了コードを持った状態が「コード不明」で上書きされる。
     */
    var listGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    var shellsResult: ApiResult<List<PtyShellDto>> =
        ApiResult.Ok(contractJson.decodeFromString(Q9Fixtures.SHELLS_JSON))
    var listResult: ApiResult<List<PtyDto>> = ApiResult.Ok(emptyList())
    var createResult: ApiResult<PtyDto> =
        ApiResult.Ok(contractJson.decodeFromString(Q9Fixtures.CREATE_PTY_JSON))
    var resizeResult: ApiResult<PtyDto> = createResult
    var deleteResult: ApiResult<Unit> = ApiResult.Ok(Unit)

    /** `connect` が**チケット取得の段階で**失敗する場合。 */
    var connectResult: ApiError? = null

    /**
     * `connect()` を**開いた後で**待たせる門。
     *
     * `NonCancellable` の中で待つのは**実物の形を写している**からである:
     * `PtyRepository.connect` は `api.openPtyWebSocket(...)` を呼んだ時点で
     * **ソケットは既に開いている**。その後にコルーチンが取り消されても、
     * OkHttp のソケットは開いたまま残る —— つまり「取り消されたのだから
     * 何も起きなかった」とは言えない窓がある。
     * ここを素の `await()` にすると窓ごと消えてしまい、
     * 「古い世代の接続結果を握らない」ことを測れなくなる。
     */
    var connectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun listShells(directory: String?): ApiResult<List<PtyShellDto>> = shellsResult

    override suspend fun list(directory: String?): ApiResult<List<PtyDto>> {
        listCalls++
        order?.add("pty")
        listGate?.await()
        return listResult
    }

    override suspend fun create(
        command: String,
        args: List<String>,
        title: String,
        directory: String?,
    ): ApiResult<PtyDto> {
        createdCommands += command to title
        return createResult
    }

    override suspend fun resize(
        ptyId: String,
        rows: Int,
        cols: Int,
        directory: String?,
    ): ApiResult<PtyDto> {
        resizes += Triple(ptyId, rows, cols)
        return resizeResult
    }

    override suspend fun delete(ptyId: String, directory: String?): ApiResult<Unit> {
        deleted += ptyId
        return deleteResult
    }

    override suspend fun connect(
        ptyId: String,
        cursor: Long?,
        directory: String?,
        listener: PtySocketListener,
    ): ApiResult<PtySocket> {
        connectDirectories += directory
        connectResult?.let { return ApiResult.Err(it) }
        val socket = FakeSocket(ptyId, cursor, directory, listener)
        sockets += socket
        connectGate?.let { gate ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
        }
        return ApiResult.Ok(socket.socket)
    }

    /** 直近に開いたソケット。**開いていなければ失敗**(黙って null を返さない)。 */
    fun latest(): FakeSocket = sockets.lastOrNull() ?: error("ソケットが1本も開かれていない")
}

/** 実データ JSON をそのままデコードするヘルパ。 */
object Q9Decode {
    fun ptys(json: String): List<PtyDto> = contractJson.decodeFromString(json)
    fun pty(json: String): PtyDto = contractJson.decodeFromString(json)
    fun shells(json: String): List<PtyShellDto> = contractJson.decodeFromString(json)
}
