package dev.opencode.android.data

/**
 * ターミナル(PTY)がサーバーに対して行う操作の口(Q9)。
 *
 * **なぜインターフェイスを切るのか**([FilesGateway] / [DiffGateway] と同じ理由):
 * 「チケットを取り直して繋ぐ」「切れたら保存した cursor から読み直す」
 * 「プロセスが終わったのか接続が切れたのかを区別する」は**状態機械であって
 * 純関数に切り出せない**。Q1 の失敗(「切り出せなかった状態機械に検出器が1本も無かった」)
 * 以来、このプロジェクトは**状態遷移そのものに検出器を置く**ことを規則にしている。
 *
 * ここを差し替え可能にして [dev.opencode.android.ui.PtyController] を
 * `runTest` から直接叩く。実装は [PtyRepository]。
 */
interface PtyGateway {
    /** 接続先が設定済みか。未設定なら呼び出し側は通信を試みない。 */
    val isConfigured: Boolean

    /** `GET /pty/shells`。要素は `{path, name, acceptable}`([PtyShellDto])。 */
    suspend fun listShells(directory: String? = null): ApiResult<List<PtyShellDto>>

    /**
     * `GET /pty`。**走っているものしか載らない**(終了すると消える。実測)。
     * 「一覧に居ない」は「終わった」と「作っていない」の両方でありうる。
     */
    suspend fun list(directory: String? = null): ApiResult<List<PtyDto>>

    /** `POST /pty`。**任意コマンド実行**である(QUALITY_PLAN §5b Q9 セキュリティ注記)。 */
    suspend fun create(
        command: String,
        args: List<String> = emptyList(),
        title: String,
        directory: String? = null,
    ): ApiResult<PtyDto>

    /** `PUT /pty/{id} {size:{rows,cols}}`。**リサイズは WS 経由ではない**(実測)。 */
    suspend fun resize(ptyId: String, rows: Int, cols: Int, directory: String? = null): ApiResult<PtyDto>

    /**
     * `DELETE /pty/{id}`。**`pty.exited` は流れない**(実測)ので、
     * ここを通った終了からは終了コードが取れない。
     */
    suspend fun delete(ptyId: String, directory: String? = null): ApiResult<Unit>

    /**
     * `connect-token` を取り直してから WebSocket を開く。
     *
     * **チケットは単回使用**なので、この口は必ず2手(token → connect)である。
     * 呼び出し側にチケットを見せないのは、見せると「使い回す」実装が書けてしまうため。
     *
     * @param cursor 再開位置。**null = 渡さない(先頭から全部再送)**、
     *   [PTY_CURSOR_LIVE_ONLY] = リプレイ無し
     * @return [ApiResult.Ok] は「**WebSocket を開こうとした**」まで。実際に 101 が返ったかは
     *   [PtySocketListener.onOpen] / [PtySocketListener.onClosed] で伝わる ——
     *   OkHttp のハンドシェイクは非同期であり、ここで待つと UI スレッドを塞ぐ
     */
    suspend fun connect(
        ptyId: String,
        cursor: Long?,
        directory: String? = null,
        listener: PtySocketListener,
    ): ApiResult<PtySocket>
}

/**
 * 開いている PTY の WebSocket。**入力は別エンドポイントではなくここへ書く**(実測)。
 */
interface PtySocket {
    /**
     * 生の端末入力をそのまま送る。**Ctrl+C は `"\u0003"` の1文字**で足りる(実測)。
     *
     * @return 送れたか。false = キューに載らなかった(既に閉じている / バッファ超過)
     */
    fun send(input: String): Boolean

    /** こちらから閉じる。[PtySocketListener.onClosed] が続く。 */
    fun close()
}

/**
 * WebSocket から届くもの。**OkHttp のスレッドで呼ばれる**ので、
 * 実装は状態を触る前に自分のスコープへ移すこと(順序は保つこと)。
 */
interface PtySocketListener {
    /** 101 が返って開いた。 */
    fun onOpen()

    /** 1フレーム。判別は [classifyPtyTextFrame] / [classifyPtyBinaryFrame] が済ませてある。 */
    fun onFrame(frame: PtyFrame)

    /**
     * 閉じた。
     *
     * @param code WebSocket の close code。**null = ハンドシェイクにすら至らなかった**
     *   (接続失敗・チケット無効)。`1000` は正常終了で、実測では
     *   **プロセスが終わったときにこれが来る** —— ただし `1000` から終了コードは分からない
     * @param reason 人へ見せる1行。**空文字は「サーバーが理由を言わなかった」**
     */
    fun onClosed(code: Int?, reason: String)
}
