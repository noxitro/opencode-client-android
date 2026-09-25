package dev.opencode.android.ui

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.DEFAULT_DIRECTORY
import dev.opencode.android.data.PtyDto
import dev.opencode.android.data.PtyFrame
import dev.opencode.android.data.PtyGateway
import dev.opencode.android.data.PtySocket
import dev.opencode.android.data.PtySocketListener
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.advancePtyCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 再接続の待ち時間(ミリ秒)。試行ごとに伸ばし、上限で頭打ちにする。 */
internal fun ptyReconnectDelayMs(attempt: Int): Long =
    when {
        attempt <= 1 -> 500L
        attempt == 2 -> 1_000L
        attempt == 3 -> 2_000L
        else -> 5_000L
    }

/** 再接続を諦める試行回数。**諦めたことは画面に出す**(黙って止まらない)。 */
const val PTY_MAX_RECONNECT_ATTEMPTS = 5

/**
 * ターミナル(PTY)の状態機械(Q9)。**Android にも Compose にも依存しない**ので
 * `runTest` から直接叩ける([FileBrowserController] / [DiffController] と同じ形)。
 *
 * ここが持つ判断は6つ。**どれも画面に書き戻さないこと:**
 *
 *  1. **接続は必ず「チケットを取り直してから」**([PtyGateway.connect])。チケットは単回使用で、
 *     使い回すと**再接続だけが黙って失敗する**(1本目は正常に動くので気付けない)
 *  2. **再開位置はメタフレームだけでは足りない。** メタは接続時の1回きりなので、
 *     以後の出力フレームのバイト数で自分で進める
 *     ([dev.opencode.android.data.advancePtyCursor])。落とすと
 *     **再接続のたびに画面の先頭からやり直す**
 *  3. **ソケットが閉じた理由を close code から決めない。** `1000` は正常終了だが、
 *     シェルの終了とサーバー側の切断の両方でありうる。`GET /pty` に**まだ居るか**で決める
 *     ([classifyClosedSocket])
 *  4. **終了コードの出所は SSE `pty.exited` だけ**(実測)。REST は終了した瞬間に 404 になる。
 *     受け取れなかったコードを 0 と書かない([PtyLifeState.ExitedCodeUnknown])
 *  5. **`DELETE` は `pty.exited` を流さない。** 自分で消した終了からは終了コードが来ないので、
 *     待ち続けない([PtyLifeState.Deleted])
 *  6. **再接続を諦めたら言う。** [PTY_MAX_RECONNECT_ATTEMPTS] 回で止め、
 *     「接続していない」ことを帯に出す(黙って止まると「繋がっているのに無反応」に見える)
 *
 * @param describeError [ApiError] を人向けの1行にする。文言は画面側の関心なので注入する。
 * @param maxLines 画面に保持する行数。テストは小さくして打ち切りを測る。
 * @param directory `directory` クエリ。**null = サーバーの cwd**(Q7 が用意した経路。UI は出さない)。
 */
class PtyController(
    private val gateway: PtyGateway,
    private val scope: CoroutineScope,
    private val describeError: (ApiError) -> String,
    private val maxLines: Int = TERMINAL_MAX_LINES,
    private val directory: String? = DEFAULT_DIRECTORY,
) {
    private val _state = MutableStateFlow(PtyUi())
    val state: StateFlow<PtyUi> = _state.asStateFlow()

    private var listJob: Job? = null
    private var shellsJob: Job? = null
    private var connectJob: Job? = null
    private var socket: PtySocket? = null

    /**
     * **今の接続の世代番号。** コールバックはこれを持ち回り、着地したときに
     * 一致しなければ捨てる。
     *
     * ## なぜ `ptyId` の比較では足りないのか(レビュー major-1、実測で再現)
     *
     * 1周目は「今つないでいる PTY のID」を比較していた。**同じPTYへ張り直す経路
     * ([reconnect] / 一覧から同じ行をもう一度開く)では、その比較が何も捨てない。**
     *
     * ```
     * disconnect()          socketPtyId = null; socket.close()   ← OkHttp は後で onClosed を投げる
     * connect()             socketPtyId = ptyId                  ← ID が元に戻る
     * (古いソケット) onClosed  socketPtyId == ptyId なので**通ってしまう**
     * ```
     *
     * 通ると `socket = null`(開いている新しいソケットの参照を落とす → 入力が送れず
     * リークする)、`connection` を `RECONNECTING` へ書き戻す、`connect` を撃って
     * **進行中の接続を `connectJob.cancel()` で殺す**。古い `onFrame` が通れば
     * 同じ出力が二重に積まれ、`cursor` も二重に進むので**次の再接続で出力が飛ぶ**。
     *
     * 世代番号は**接続のたびに新しい値**になり、[disconnect] でも進む。
     * したがって「同じPTYへの張り直し」でも古いコールバックは必ず落ちる。
     */
    private var socketGeneration: Long = 0L

    private var seenConnectionKey: String? = null

    // ---- 一覧とシェル ----

    /**
     * まだ読んでいなければ読む。**判定はここが持つ**(呼び出し側に `if` を書かない)。
     * 画面の再表示(回転・戻る)から無条件に呼ぶための口である。
     */
    fun ensureLoaded() {
        if (!_state.value.list.loaded) refreshList()
        if (!_state.value.shells.loaded) refreshShells()
    }

    /** `GET /pty`。**毎回引き直す** —— 走っているPTYは増えも減りもする。 */
    fun refreshList() {
        if (!gateway.isConfigured) return
        listJob?.cancel()
        _state.update { it.copy(list = it.list.copy(loading = true, error = null, errorIsAuth = false)) }
        listJob = scope.launch {
            applyList(gateway.list(directory))
        }
    }

    private fun applyList(result: ApiResult<List<PtyDto>>) {
        _state.update { cur ->
            cur.copy(
                list = when (result) {
                    is ApiResult.Ok -> cur.list.copy(
                        loading = false,
                        items = result.value,
                        error = null,
                        errorIsAuth = false,
                        loaded = true,
                    )
                    is ApiResult.Err -> cur.list.copy(
                        loading = false,
                        // **持っている一覧を消さない。** 消すと、通信が1回失敗しただけで
                        // 走っているターミナルへ戻る導線が画面から消える。
                        error = describeError(result.error),
                        errorIsAuth = isAuthError(result.error),
                    )
                },
            )
        }
    }

    /** `GET /pty/shells`。**一度取れれば引き直さない**(サーバーのシェル構成は変わらない)。 */
    fun refreshShells() {
        if (!gateway.isConfigured) return
        shellsJob?.cancel()
        _state.update { it.copy(shells = it.shells.copy(loading = true, error = null, errorIsAuth = false)) }
        shellsJob = scope.launch {
            val result = gateway.listShells(directory)
            _state.update { cur ->
                cur.copy(
                    shells = when (result) {
                        is ApiResult.Ok -> cur.shells.copy(
                            loading = false,
                            items = result.value,
                            error = null,
                            errorIsAuth = false,
                            loaded = true,
                        )
                        is ApiResult.Err -> cur.shells.copy(
                            loading = false,
                            error = describeError(result.error),
                            errorIsAuth = isAuthError(result.error),
                        )
                    },
                )
            }
        }
    }

    // ---- 作成 / 起動 ----

    /**
     * シェルを起動して開く(§5b Q9 スコープ4)。
     *
     * **任意コマンド実行**なので、[command] は `GET /pty/shells` が返した `path` に限る ——
     * その制約は呼び出し側(画面)が候補を出す形で守る。ここは受けた値を送る。
     */
    fun createAndOpen(command: String, title: String) {
        if (!gateway.isConfigured) return
        if (_state.value.creating) return
        _state.update { it.copy(creating = true, createError = null) }
        scope.launch {
            when (val result = gateway.create(command = command, title = title, directory = directory)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(creating = false, createError = null) }
                    // **一覧を先に更新してから開く。** 逆順だと、開いた直後の
                    // 「まだ一覧に居るか」の判定(判断3)が古い一覧を見る。
                    applyList(gateway.list(directory))
                    open(result.value)
                }
                is ApiResult.Err -> _state.update {
                    it.copy(creating = false, createError = describeError(result.error))
                }
            }
        }
    }

    /** 一覧の行から開く。 */
    fun open(pty: PtyDto) = open(pty.id, pty.title, pty.command)

    /**
     * PTY を開いて繋ぐ。**画面バッファは捨てて開き直す** ——
     * 別のPTYの出力が混ざった画面は、どちらの出力かを見分ける手段が無い。
     */
    fun open(ptyId: String, title: String, command: String) {
        if (!gateway.isConfigured) return
        disconnect()
        _state.update {
            it.copy(
                // **前の端末のアクションエラーを引き継がない。**
                // [ptyActionErrorMessage] が窓側でも弾くが、状態に残しておくと
                // 「閉じる」を押していないエラーが在庫として溜まり続ける。
                actionError = null,
                terminal = PtyTerminalUi(
                    ptyId = ptyId,
                    title = title,
                    command = command,
                    connection = PtyConnectionState.CONNECTING,
                    life = PtyLifeState.Running,
                ),
            )
        }
        connect(ptyId, attempt = 0)
    }

    /**
     * 接続する。**`cursor` は状態から取る**(呼び出し側に渡させない)——
     * 渡させると、再接続の経路が「0 を渡す」形で書かれて全部を読み直す。
     */
    private fun connect(ptyId: String, attempt: Int) {
        connectJob?.cancel()
        connectJob = scope.launch {
            if (attempt > 0) delay(ptyReconnectDelayMs(attempt))
            val cursor = _state.value.terminal.cursor
            if (cursor == null) {
                // **位置を知らないまま繋ぐと、サーバーは先頭から全部送り直す**(実測)。
                //
                // メタフレームを流さないサーバー(スタブの `STUB_PTY_NO_META`)では
                // `cursor` は最後まで null のままなので、再接続のたびに
                // **同じ出力が画面に二重・三重に積まれる**。エラーもクラッシュも出ない。
                // 全再送が来ると分かっているのだから、**受ける前に画面を捨てる**。
                _state.update { cur ->
                    if (cur.terminal.ptyId != ptyId) cur
                    else cur.copy(terminal = cur.terminal.copy(buffer = TerminalBuffer()))
                }
            }
            val generation = ++socketGeneration
            val listener = SocketCallbacks(ptyId, generation)
            when (val result = gateway.connect(ptyId, cursor, directory, listener)) {
                // **世代が進んでいたら、開いたソケットは自分の物ではない。**
                // ここで代入すると、後から開いた本物の参照を上書きして取り落とす。
                is ApiResult.Ok ->
                    if (generation == socketGeneration) socket = result.value else result.value.close()
                is ApiResult.Err -> {
                    // **自分の世代でなければ何も書かない。** 古い接続試行の失敗が
                    // 新しい接続を「切断」に塗り替えるのを防ぐ(major-1 と同じ形)。
                    if (generation != socketGeneration) return@launch
                    socket = null
                    _state.update { cur ->
                        if (cur.terminal.ptyId != ptyId) {
                            cur
                        } else {
                            cur.copy(
                                terminal = cur.terminal.copy(
                                    connection = PtyConnectionState.CLOSED,
                                    // **チケットが取れなかった**のは接続の失敗であって
                                    // シェルの終了ではない。生死は動かさない。
                                    error = describeError(result.error),
                                    errorIsAuth = isAuthError(result.error),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 入力 ----

    /**
     * 入力欄から1行送る。**改行を付けるのはここ**(画面が `+ "\r"` を書かない)。
     *
     * 空行も送る —— シェルにとって空の Enter は意味のある入力である。
     */
    fun sendLine(text: String) {
        sendRaw(text + ptyKeySequence(PtyKey.ENTER))
    }

    /** 補助キー(§5b Q9 スコープ3)。**対応表は [ptyKeySequence] にしかない。** */
    fun sendKey(key: PtyKey) {
        sendRaw(ptyKeySequence(key))
    }

    /**
     * 生の端末入力。**送れなかったことを状態に出す** ——
     * 黙って捨てると「打っても何も起きない端末」になる。
     */
    fun sendRaw(input: String) {
        val sent = socket?.send(input) ?: false
        if (!sent) {
            _state.update {
                it.copy(
                    actionError = PtyActionError(
                        ptyId = it.terminal.ptyId,
                        message = "入力を送れませんでした(接続していません)",
                        // 入力は端末の窓からしか起こせない。
                        origin = PtyScreenPane.TERMINAL,
                    ),
                )
            }
        }
    }

    fun clearActionError() {
        _state.update { it.copy(actionError = null) }
    }

    fun clearCreateError() {
        _state.update { it.copy(createError = null) }
    }

    // ---- リサイズ ----

    /**
     * 端末サイズをサーバーへ伝える(`PUT /pty/{id}`。**WS 経由ではない**)。
     *
     * **同じ寸法なら撃たない。** 撃つとサーバーが全画面を描き直すので、
     * 再コンポジションのたびに画面が流れ直す。
     */
    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        val terminal = _state.value.terminal
        val ptyId = terminal.ptyId ?: return
        // **終わった PTY はリサイズしない。**
        //
        // 実機で踏んだ形(2026-08-30): `exit` の直後に帯と注記の高さが変わって
        // 再コンポジションが走り、寸法が変わったので `PUT /pty/{id}` を撃った ——
        // サーバーからは既に消えているので **404 になり、
        // 「サーバーエラー(HTTP 404)」が終了直後の画面に出た**。
        // ユーザーは何もしておらず、失敗でもない。
        if (terminal.finished) return
        if (terminal.rows == rows && terminal.cols == cols) return
        val previousRows = terminal.rows
        val previousCols = terminal.cols
        _state.update { it.copy(terminal = it.terminal.copy(rows = rows, cols = cols)) }
        scope.launch {
            val result = gateway.resize(ptyId, rows, cols, directory)
            if (result is ApiResult.Err) {
                // **失敗したら楽観更新を取り消す。**
                //
                // 取り消さないと状態は「伝え済み」のままなので、上の同値判定が
                // **同じ寸法での再送を永久に抑止する**。症状は「一度通信が失敗しただけで
                // 端末の桁数がサーバー側の既定のまま二度と直らない」で、
                // 画面には寸法が正しく出ている(`pty-grid` も新しい値を出す)——
                // **アプリだけが伝わったと思っている**形である。
                _state.update { cur ->
                    val revert = cur.terminal.ptyId == ptyId &&
                        cur.terminal.rows == rows && cur.terminal.cols == cols
                    cur.copy(
                        // **失敗した PTY を名指しする。** 名指ししないと、切り替えた先の
                        // 健全な端末にこのエラーが出る(Q9 E2E の実測)。
                        //
                        // **窓も名指しする。** リサイズは端末の窓でしか起きないので、
                        // 一覧へ戻った後に返ってきた失敗を一覧の帯に出さない(レビュー minor-1)。
                        actionError = PtyActionError(
                            ptyId,
                            describeError(result.error),
                            PtyScreenPane.TERMINAL,
                        ),
                        terminal = if (revert) {
                            cur.terminal.copy(rows = previousRows, cols = previousCols)
                        } else {
                            cur.terminal
                        },
                    )
                }
            }
        }
    }

    // ---- 終了 / 削除 ----

    /**
     * ターミナルを削除する(`DELETE /pty/{id}`)。
     *
     * **`pty.exited` は流れない**(実測)ので、終了コードを待たない。
     * 生死は [PtyLifeState.Deleted] に落ちて、帯が「終了コードは分からない」と言う。
     */
    fun deletePty(ptyId: String) {
        if (!gateway.isConfigured) return
        // **どの窓から起こしたかを発行時に確定する**(レビュー minor-1)。
        // 削除は一覧の行の「終了」からも、端末の窓の上部からも起こせる。
        // 失敗が返った**時点**の窓を見ると、その間に画面が切り替わっていたときに
        // 他所の窓へエラーが出る —— それがこの `origin` が閉じている穴である。
        val origin = ptyScreenPane(_state.value)
        scope.launch {
            when (val result = gateway.delete(ptyId, directory)) {
                is ApiResult.Ok -> {
                    _state.update { cur ->
                        cur.copy(
                            list = cur.list.copy(items = cur.list.items.filterNot { it.id == ptyId }),
                            terminal = if (cur.terminal.ptyId == ptyId) {
                                cur.terminal.copy(
                                    life = PtyLifeState.Deleted,
                                    connection = PtyConnectionState.CLOSED,
                                )
                            } else {
                                cur.terminal
                            },
                        )
                    }
                    if (_state.value.terminal.ptyId == ptyId) disconnect()
                }
                is ApiResult.Err -> _state.update {
                    it.copy(actionError = PtyActionError(ptyId, describeError(result.error), origin))
                }
            }
        }
    }

    /**
     * 画面を閉じる。**開いているソケットも閉じる**(裏で読み続けない)。
     *
     * **アクションエラーも捨てる。** 1周目は `terminal` だけを初期化しており、
     * `actionError` は `PtyUi` 直下に居残った —— これが
     * 「サーバー停止中のリサイズ失敗が、復旧後に起動した別の端末に出たまま残る」
     * の正体だった(Q9 E2E)。
     */
    fun closeTerminal() {
        disconnect()
        _state.update { it.copy(terminal = PtyTerminalUi(), actionError = null) }
    }

    /** 手動の再接続(帯の「再接続」)。**試行回数を 0 に戻す。** */
    fun reconnect() {
        val ptyId = _state.value.terminal.ptyId ?: return
        disconnect()
        _state.update {
            it.copy(
                terminal = it.terminal.copy(
                    connection = PtyConnectionState.CONNECTING,
                    reconnectAttempt = 0,
                    // 手で押したのだから、諦めた状態からやり直す。
                    gaveUpReconnecting = false,
                    error = null,
                    errorIsAuth = false,
                ),
            )
        }
        connect(ptyId, attempt = 0)
    }

    /**
     * 今の接続を捨てる。**世代を進める**ので、この後に着地する古いコールバックは
     * すべて落ちる(同じPTYへ張り直しても、である —— major-1)。
     */
    private fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        socketGeneration++
        socket?.close()
        socket = null
    }

    // ---- SSE ----

    /**
     * SSE を受ける。**`pty.exited` が終了コードの唯一の出所**である(判断4)。
     *
     * 一覧も同時に直す —— `GET /pty` を撃ち直さずに済むぶん、
     * 「終わったのに一覧に残り続ける」窓が縮む。
     */
    fun onEvent(event: SseEvent) {
        val pty = event as? SseEvent.PtyLifecycle ?: return
        val id = pty.id ?: return
        _state.update { cur ->
            val list = when (pty.kind) {
                SseEvent.PtyLifecycle.Kind.CREATED, SseEvent.PtyLifecycle.Kind.UPDATED -> {
                    val info = pty.info
                    if (info == null) {
                        cur.list
                    } else {
                        val without = cur.list.items.filterNot { it.id == id }
                        cur.list.copy(items = without + info)
                    }
                }
                SseEvent.PtyLifecycle.Kind.EXITED, SseEvent.PtyLifecycle.Kind.DELETED ->
                    cur.list.copy(items = cur.list.items.filterNot { it.id == id })
            }
            val terminal = if (cur.terminal.ptyId != id) {
                cur.terminal
            } else {
                when (pty.kind) {
                    SseEvent.PtyLifecycle.Kind.EXITED -> cur.terminal.copy(
                        // **コードが来なかった `exited` を 0 にしない。**
                        life = pty.exitCode?.let { PtyLifeState.Exited(it) }
                            ?: PtyLifeState.ExitedCodeUnknown,
                        connection = PtyConnectionState.CLOSED,
                    )
                    SseEvent.PtyLifecycle.Kind.DELETED ->
                        if (cur.terminal.finished) {
                            // 既に終了コードを持っているなら**上書きしない**。
                            // `exited` の直後に `deleted` が来る経路がある。
                            cur.terminal
                        } else {
                            cur.terminal.copy(
                                life = PtyLifeState.Deleted,
                                connection = PtyConnectionState.CLOSED,
                            )
                        }
                    else -> cur.terminal
                }
            }
            cur.copy(list = list, terminal = terminal)
        }
        if (_state.value.terminal.ptyId == id &&
            (
                pty.kind == SseEvent.PtyLifecycle.Kind.EXITED ||
                    pty.kind == SseEvent.PtyLifecycle.Kind.DELETED
                )
        ) {
            // 終わったのだから読み続けない。**再接続もしない**(繋ぐ先が無い)。
            disconnect()
        }
    }

    /**
     * SSE が張り直された。**一覧を引き直す**(RUN_PLAN 決定2)。
     *
     * 切れている間の `pty.created` / `pty.exited` は購読者ゼロで消えている。
     * **まだ一度も読んでいなければ何もしない**(勝手に開かない)。
     */
    fun onReconnected() {
        if (!_state.value.list.loaded) return
        refreshList()
    }

    /**
     * 接続先が変わった。**捨てる**([FileBrowserController] / [DiffController] と同じ理由)。
     *
     * サーバーAのシェルの出力をサーバーBの画面に出し続けるのは、
     * Q4 レビュー major-1 がカタログで見つけたのと同じ形で、**画面はどこも壊れて見えない**。
     * ここは加えて**ソケットを閉じる**必要がある —— 閉じないと、
     * 前のサーバーのシェルへ入力を送り続ける経路が生き残る。
     */
    fun onConnectionChanged(connectionKey: String?) {
        if (seenConnectionKey == connectionKey) return
        val hadSomething = seenConnectionKey != null
        seenConnectionKey = connectionKey
        if (!hadSomething) return
        listJob?.cancel()
        shellsJob?.cancel()
        listJob = null
        shellsJob = null
        disconnect()
        _state.value = PtyUi()
    }

    // ---- WebSocket のコールバック ----

    /**
     * OkHttp のスレッドから来る通知を**自分のスコープへ移す**。
     *
     * [generation] を握っているのが要点である。**[ptyId] だけでは足りない** ——
     * 同じPTYへ張り直す経路(`reconnect` / 一覧から同じ行をもう一度開く)では
     * IDが元に戻るので、古いソケットの通知がガードを素通りする([socketGeneration])。
     */
    private inner class SocketCallbacks(
        private val ptyId: String,
        private val generation: Long,
    ) : PtySocketListener {
        /** 自分がまだ「今の接続」か。**世代で見る。ID では見ない。** */
        private val isCurrent: Boolean get() = generation == socketGeneration

        override fun onOpen() {
            scope.launch {
                if (!isCurrent) return@launch
                _state.update { cur ->
                    if (cur.terminal.ptyId != ptyId) {
                        cur
                    } else {
                        cur.copy(
                            terminal = cur.terminal.copy(
                                connection = PtyConnectionState.CONNECTED,
                                reconnectAttempt = 0,
                                // 繋がったのだから「諦めた」は取り下げる。
                                gaveUpReconnecting = false,
                                error = null,
                                errorIsAuth = false,
                                closeCode = null,
                                closeReason = "",
                            ),
                        )
                    }
                }
            }
        }

        override fun onFrame(frame: PtyFrame) {
            scope.launch {
                if (!isCurrent) return@launch
                _state.update { cur ->
                    if (cur.terminal.ptyId != ptyId) {
                        cur
                    } else {
                        val buffer = when (frame) {
                            // **メタは描かない。** 描くと `{"cursor":223}` が画面に出る。
                            is PtyFrame.Meta, is PtyFrame.UnreadableMeta -> cur.terminal.buffer
                            is PtyFrame.Output ->
                                appendTerminalOutput(cur.terminal.buffer, frame.text, maxLines)
                        }
                        cur.copy(
                            terminal = cur.terminal.copy(
                                buffer = buffer,
                                // 位置の更新規則は1か所([advancePtyCursor])。
                                cursor = advancePtyCursor(cur.terminal.cursor, frame),
                            ),
                        )
                    }
                }
            }
        }

        override fun onClosed(code: Int?, reason: String) {
            scope.launch {
                if (!isCurrent) return@launch
                socket = null
                // **まだ居るか**を聞く。close code からは決めない(判断3)。
                val stillListed = when (val listed = gateway.list(directory)) {
                    is ApiResult.Ok -> {
                        applyList(listed)
                        listed.value.any { it.id == ptyId }
                    }
                    is ApiResult.Err -> null
                }
                // **生死は `gateway.list` から戻ってきた後に読む。**
                //
                // 実機で踏んだ形(2026-08-30、スタブ 4098、`exit` 送信):
                // `pty.exited{exitCode:7}` は SSE で届いていたのに、画面には
                // `pty-exited-code-unknown`(「終了コードは受け取れていません」)が出た。
                // 原因は**この関数が `list` を待っている間に SSE が着地していた**こと ——
                // 待つ前に読んだ `life`(= Running)を使って `classifyClosedSocket` を回し、
                // 戻ってきてから **`Exited(7)` を `ExitedCodeUnknown` で上書きしていた**。
                //
                // `classifyClosedSocket` には「既に終了コードを持っていれば上書きしない」が
                // 在り、そのユニットテストも在った。**入力が古かった**のである ——
                // 純関数のテストは「関数が正しい」ことしか主張できず、
                // 「呼び出し側が今の値を渡す」は別の主張である(Q5 の R1 と同じ形)。
                val current = _state.value.terminal
                val life = classifyClosedSocket(current.life, stillListed)
                val attempt = current.reconnectAttempt + 1
                val shouldReconnect = life is PtyLifeState.Running && attempt <= PTY_MAX_RECONNECT_ATTEMPTS
                // **「上限まで試して諦めた」と「まだ1回も試していない」は別のこと。**
                //
                // 1周目はどちらも `pty-disconnected:<code>`(「接続が切れました」)になっていた。
                // 諦めた側は**もう自動では繋ぎ直さない**ので、押さないと何も起きない ——
                // 同じ文言だと「そのうち復帰する」と読める(判断6が言う「黙って止まる」の形)。
                val gaveUp = life is PtyLifeState.Running && attempt > PTY_MAX_RECONNECT_ATTEMPTS
                _state.update { cur ->
                    if (cur.terminal.ptyId != ptyId) {
                        cur
                    } else {
                        cur.copy(
                            terminal = cur.terminal.copy(
                                // **もう一度だけ「今の値」で畳む。** `_state.update` は
                                // 競合時に再実行されるので、ここで `life` を素で入れると
                                // 再実行のたびに古い値へ戻る。
                                life = classifyClosedSocket(cur.terminal.life, stillListed),
                                connection = if (shouldReconnect) {
                                    PtyConnectionState.RECONNECTING
                                } else {
                                    PtyConnectionState.CLOSED
                                },
                                closeCode = code,
                                closeReason = reason,
                                reconnectAttempt = if (shouldReconnect) attempt else attempt - 1,
                                gaveUpReconnecting = gaveUp,
                            ),
                        )
                    }
                }
                if (shouldReconnect) connect(ptyId, attempt)
            }
        }
    }
}
