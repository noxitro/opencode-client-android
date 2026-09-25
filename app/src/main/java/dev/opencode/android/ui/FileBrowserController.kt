package dev.opencode.android.ui

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.DEFAULT_DIRECTORY
import dev.opencode.android.data.DiffGateway
import dev.opencode.android.data.FILE_CONTENT_MAX_BYTES
import dev.opencode.android.data.FIND_FILE_LIMIT
import dev.opencode.android.data.FilesGateway
import dev.opencode.android.data.SYMBOL_INDEX_PROBE
import dev.opencode.android.data.VcsFileStatusDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 検索欄のデバウンス(§5b Q8 スコープ3)。1文字ごとに `GET /find` を撃たない。 */
const val FILE_SEARCH_DEBOUNCE_MS = 350L

/** ファイルブラウザまわりの状態ひとまとめ。画面はこれ1つを購読する。 */
data class FileBrowserUi(
    val tree: FileTreeUi = FileTreeUi(),
    val viewer: FileViewerUi = FileViewerUi(),
    val search: FileSearchUi = FileSearchUi(),
)

/**
 * ファイルブラウザ + 検索の状態機械(Q8)。**Android にも Compose にも依存しない**ので
 * `runTest` から直接叩ける([SessionListController] / [ChatController] /
 * [ModelCatalogController] / [DiffController] と同じ形)。
 *
 * ここが持つ判断は6つ。**どれも画面に書き戻さないこと:**
 *
 *  1. **ツリーは開くたびに引き直す。** ブランチ([DiffController] の判断1)と違って
 *     ディレクトリの中身は**変わるもの**である —— エージェントがファイルを足した後も
 *     古い一覧を出し続けるのは「一度取れたら二度引かない」を当ててはいけない側
 *  2. **変更バッジは `GET /vcs/status` から取る。** `GET /file/status` は
 *     1.18.21 では変更があっても `[]` を返す(実測)。200 が返るので
 *     **「変更が無い」と「口が機能していない」が応答から区別できない**
 *  3. **`ignored` は既定で隠すが、隠した件数は出す**([FileTreeUi.ignoredCount])
 *  4. **受信の打ち切りと行数の打ち切りは別物**([FILE_CONTENT_MAX_BYTES] と [FILE_MAX_LINES])。
 *     どちらも黙って行わない
 *  5. **検索はデバウンスして最後の1本だけ撃つ**([debounceMs])。撃った語は
 *     [FileSearchUi.submittedQuery] に残す —— 入力中の語と結果が対応しない窓ができるため
 *  6. **シンボルが0件なら校正クエリをもう1本撃つ**([SYMBOL_INDEX_PROBE])。
 *     1回の `200 []` からは「見つからない」と「索引が使えない」が区別できない(§5b スコープ5)
 *
 * @param describeError [ApiError] を人向けの1行にする。文言は画面側の関心なので注入する。
 * @param maxLines 1ファイルあたりの描画上限。テストは小さくして打ち切りを測る。
 * @param debounceMs 検索のデバウンス。テストは仮想時間で進める。
 * @param directory `directory` クエリ。**null = サーバーの cwd**(Q7 が用意した経路。UI は出さない)。
 */
class FileBrowserController(
    private val gateway: FilesGateway,
    private val vcs: DiffGateway,
    private val scope: CoroutineScope,
    private val describeError: (ApiError) -> String,
    private val maxLines: Int = FILE_MAX_LINES,
    private val debounceMs: Long = FILE_SEARCH_DEBOUNCE_MS,
    private val directory: String? = DEFAULT_DIRECTORY,
) {
    private val _state = MutableStateFlow(FileBrowserUi())
    val state: StateFlow<FileBrowserUi> = _state.asStateFlow()

    private var treeJob: Job? = null
    private var viewerJob: Job? = null
    private var searchJob: Job? = null

    /** 直近に見た接続先。null = まだ一度も接続先を知らされていない。 */
    private var seenConnectionKey: String? = null

    // ---- ツリー(スコープ1)----

    /**
     * まだ一度も読んでいなければ読む。**判定はここが持つ**(呼び出し側に `if` を書かない)。
     *
     * 画面の再表示(回転・戻る)から無条件に呼ぶための口である。
     * [openDirectory] を直に呼ぶと、**回転のたびに現在地を読み直す**。
     */
    fun ensureTreeLoaded() {
        if (_state.value.tree.loaded) return
        if (treeJob?.isActive == true) return
        openDirectory(_state.value.tree.path)
    }

    /**
     * ディレクトリを開く。**毎回引き直す**(上の判断1)。
     *
     * 変更バッジのための `GET /vcs/status` も一緒に引く。**片方が失敗しても一覧は出す** ——
     * バッジが付かないことは一覧が読めないことより軽い。
     */
    fun openDirectory(path: String) {
        if (!gateway.isConfigured) return
        val target = normalizeServerPath(path)
        treeJob?.cancel()
        _state.update {
            it.copy(tree = it.tree.copy(path = target, loading = true, error = null, errorIsAuth = false))
        }
        treeJob = scope.launch {
            val listed = gateway.listFiles(target, directory)
            val status = vcs.vcsStatus(directory)
            val statusFiles = (status as? ApiResult.Ok)?.value ?: emptyList<VcsFileStatusDto>()
            _state.update { cur ->
                cur.copy(
                    tree = when (listed) {
                        is ApiResult.Ok -> cur.tree.copy(
                            loading = false,
                            entries = fileEntriesOf(listed.value, statusFiles),
                            error = null,
                            errorIsAuth = false,
                            loaded = true,
                        )
                        // **古い一覧を残さない。** 別のディレクトリの中身を出したまま
                        // エラーだけ足すと、どのディレクトリを見ているのか分からなくなる。
                        is ApiResult.Err -> cur.tree.copy(
                            loading = false,
                            entries = emptyList(),
                            error = describeError(listed.error),
                            errorIsAuth = isAuthError(listed.error),
                        )
                    },
                )
            }
        }
    }

    /** パンくずから上へ戻る。**「戻れるか」の判定は [parentPath] が持つ。** */
    fun openParent() {
        val parent = parentPath(_state.value.tree.path) ?: return
        openDirectory(parent)
    }

    /**
     * `ignored` の表示を反転する。**通信しない** —— サーバーは `ignored` を
     * 全部返しており、隠しているのはこちらである。引き直すと隠す/出すの往復に
     * ネットワークの失敗が混ざる。
     */
    fun toggleIgnored() {
        _state.update { it.copy(tree = it.tree.copy(showIgnored = !it.tree.showIgnored)) }
    }

    /** 一覧の再取得(空状態の「再試行」)。 */
    fun retryTree() = openDirectory(_state.value.tree.path)

    // ---- ファイルビューア(スコープ2)----

    /**
     * ファイルを開く(§5b Q8 スコープ2・6)。
     *
     * [focusLine] は検索結果やシンボルから来たときの行番号(1始まり)。
     * **null は「先頭から」**であって0行目ではない。
     */
    fun openFile(path: String, focusLine: Int? = null) {
        if (!gateway.isConfigured) return
        val target = normalizeServerPath(path)
        viewerJob?.cancel()
        _state.update {
            it.copy(
                viewer = FileViewerUi(
                    open = true,
                    path = target,
                    loading = true,
                    focusLine = focusLine,
                ),
            )
        }
        viewerJob = scope.launch {
            val result = gateway.readFile(target, directory)
            _state.update { cur ->
                if (!cur.viewer.open) return@update cur
                cur.copy(
                    viewer = when (result) {
                        is ApiResult.Ok -> {
                            val payload = result.value
                            // **バイナリの本文は組まない。** 組むと base64 が
                            // `lines` に入り、`content-desc` 経由で dump に出てしまう
                            // (§5b のゲートは陰性側で「画面に出ていないこと」を要求している)。
                            val (lines, cut, total) =
                                if (payload.isText) {
                                    buildFileLines(payload.content, maxLines)
                                } else {
                                    Triple(emptyList(), false, 0)
                                }
                            cur.viewer.copy(
                                loading = false,
                                error = null,
                                errorIsAuth = false,
                                payload = payload,
                                lines = lines,
                                renderTruncated = cut,
                                totalLines = total,
                            )
                        }
                        is ApiResult.Err -> cur.viewer.copy(
                            loading = false,
                            payload = null,
                            lines = emptyList(),
                            error = describeError(result.error),
                            errorIsAuth = isAuthError(result.error),
                        )
                    },
                )
            }

            // **本文が空のときだけ、親の一覧に「在るのか」を訊きに行く。**
            //
            // `GET /file/content` は存在しないパスにも 200 と空の本文を返す(実測)ので、
            // 応答だけでは「0バイトのファイル」と「そのパスが無い」が同じ形になる。
            // 申し送りが6回目の「曖昧さを分離する」として残していた最後の1件。
            // やり方は `GET /find/symbol` の校正クエリ([SymbolIndexState])と同じで、
            // **空だったときにだけ1本追加で撃つ**(常に撃つと開くたびに往復が倍になる)。
            if (shouldProbePresence(_state.value.viewer, target)) {
                val parent = parentPath(target)
                // ルート自身は親を持たない。**そこを「無い」と読ませない**(UNKNOWN のまま)。
                if (parent != null) {
                    val listed = gateway.listFiles(parent, directory)
                    _state.update { cur ->
                        // 閉じられた / 別のファイルへ移った後に着地したら捨てる。
                        if (!cur.viewer.open || cur.viewer.path != target) return@update cur
                        cur.copy(
                            viewer = cur.viewer.copy(
                                presence = when (listed) {
                                    is ApiResult.Ok -> filePresenceIn(listed.value, target)
                                    // **問い合わせが失敗したら「無い」ではなく「分からない」。**
                                    is ApiResult.Err -> FilePresence.UNKNOWN
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 在り所の問い合わせを撃つか。**判定をここ1本に閉じ込める**(呼び出し側に `if` を散らさない)。
     *
     * 撃つのは「テキストとして受け取って、本文が1行も無くて、打ち切ってもいない」ときだけ。
     * 打ち切りで空なのは**こちらが読むのをやめた**のであって、サーバーの応答が空なのではない
     * ([fileViewerEmptyState] の `file-truncated-empty` と同じ区別)。
     */
    private fun shouldProbePresence(viewer: FileViewerUi, target: String): Boolean =
        viewer.open &&
            viewer.path == target &&
            viewer.error == null &&
            viewer.payload != null &&
            viewer.payload.isText &&
            !viewer.payload.truncated &&
            viewer.lines.isEmpty()

    /** ビューアを閉じる。**進行中の取得も捨てる**(戻ってから前の応答が着地しない)。 */
    fun closeViewer() {
        viewerJob?.cancel()
        viewerJob = null
        _state.update { it.copy(viewer = FileViewerUi()) }
    }

    /** ビューアの「再試行」。**同じファイルをもう一度**(別の要求を投げる口を作らない)。 */
    fun retryViewer() {
        val path = _state.value.viewer.path
        if (path.isEmpty()) return
        openFile(path, _state.value.viewer.focusLine)
    }

    /**
     * 「変更あり」トグル(§5b スコープ2)。**`diff` を持たないときは何もしない** ——
     * 押せないボタンを押したことにしない。
     *
     * **1.18.21 は `diff` を返さない**(実測)ので、実物ではこの経路に入らない。
     */
    fun toggleDiff() {
        _state.update {
            if (!it.viewer.hasDiff) it else it.copy(viewer = it.viewer.copy(showDiff = !it.viewer.showDiff))
        }
    }

    // ---- 検索(スコープ3・4・5)----

    /** タブを変える。**同じ語で撃ち直す**(タブごとに語を持つと、どれで探したか分からなくなる)。 */
    fun setSearchTab(tab: FileSearchTab) {
        if (_state.value.search.tab == tab) return
        _state.update {
            it.copy(
                search = it.search.copy(
                    tab = tab,
                    // **前のタブの結果を残さない。** 残すと「シンボル0件」の画面に
                    // 全文検索の結果が並ぶ。
                    textGroups = emptyList(),
                    files = emptyList(),
                    symbols = emptyList(),
                    symbolIndex = SymbolIndexState.UNKNOWN,
                    searched = false,
                    error = null,
                ),
            )
        }
        scheduleSearch(immediate = true)
    }

    /** 入力。**ここでは撃たない**(デバウンス)。 */
    fun updateSearchQuery(query: String) {
        _state.update { it.copy(search = it.search.copy(query = query)) }
        scheduleSearch(immediate = false)
    }

    /** キーボードの検索キーなどから。**デバウンスを待たずに撃つ。** */
    fun submitSearch() = scheduleSearch(immediate = true)

    /** 検索語を捨てる。**通信しない。** */
    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _state.update { it.copy(search = FileSearchUi(tab = it.search.tab)) }
    }

    private fun scheduleSearch(immediate: Boolean) {
        searchJob?.cancel()
        val query = _state.value.search.query.trim()
        if (query.isEmpty()) {
            _state.update {
                it.copy(
                    search = it.search.copy(
                        loading = false,
                        submittedQuery = "",
                        textGroups = emptyList(),
                        files = emptyList(),
                        symbols = emptyList(),
                        symbolIndex = SymbolIndexState.UNKNOWN,
                        searched = false,
                        error = null,
                    ),
                )
            }
            return
        }
        if (!gateway.isConfigured) return
        searchJob = scope.launch {
            if (!immediate) delay(debounceMs)
            runSearch(query, _state.value.search.tab)
        }
    }

    private suspend fun runSearch(query: String, tab: FileSearchTab) {
        _state.update { it.copy(search = it.search.copy(loading = true, error = null, errorIsAuth = false)) }
        when (tab) {
            FileSearchTab.TEXT -> applyTextSearch(query, gateway.findText(query, directory))
            FileSearchTab.FILES ->
                applyFileSearch(query, gateway.findFiles(query, FIND_FILE_LIMIT, directory))
            FileSearchTab.SYMBOLS -> applySymbolSearch(query)
        }
    }

    private fun applyTextSearch(query: String, result: ApiResult<List<dev.opencode.android.data.FindMatchDto>>) {
        _state.update { cur ->
            cur.copy(
                search = when (result) {
                    is ApiResult.Ok -> cur.search.copy(
                        loading = false,
                        submittedQuery = query,
                        textGroups = groupTextMatches(result.value),
                        searched = true,
                        error = null,
                    )
                    is ApiResult.Err -> cur.search.copy(
                        loading = false,
                        submittedQuery = query,
                        textGroups = emptyList(),
                        searched = true,
                        error = describeError(result.error),
                        errorIsAuth = isAuthError(result.error),
                    )
                },
            )
        }
    }

    private fun applyFileSearch(query: String, result: ApiResult<List<String>>) {
        _state.update { cur ->
            cur.copy(
                search = when (result) {
                    is ApiResult.Ok -> cur.search.copy(
                        loading = false,
                        submittedQuery = query,
                        files = result.value.map { normalizeServerPath(it) },
                        searched = true,
                        error = null,
                    )
                    is ApiResult.Err -> cur.search.copy(
                        loading = false,
                        submittedQuery = query,
                        files = emptyList(),
                        searched = true,
                        error = describeError(result.error),
                        errorIsAuth = isAuthError(result.error),
                    )
                },
            )
        }
    }

    /**
     * シンボル検索。**0件だったら校正クエリをもう1本撃つ**(§5b スコープ5)。
     *
     * `200 []` は「見つからない」と「索引が使えない」の両方でありうる。
     * 索引が在るなら [SYMBOL_INDEX_PROBE] のような1文字の語は何かに当たる。
     * 当たらなければ索引が無い —— これが**1回の応答からは作れない区別**である。
     *
     * **陽性側(索引が在る)は実物では測れない**(LSP が動く serve が無い)。
     * 陰性側は実物で測れている。機構は両側ともスタブで出す。
     */
    private suspend fun applySymbolSearch(query: String) {
        val result = gateway.findSymbols(query, directory)
        if (result is ApiResult.Err) {
            _state.update { cur ->
                cur.copy(
                    search = cur.search.copy(
                        loading = false,
                        submittedQuery = query,
                        symbols = emptyList(),
                        symbolIndex = SymbolIndexState.UNKNOWN,
                        searched = true,
                        error = describeError(result.error),
                        errorIsAuth = isAuthError(result.error),
                    ),
                )
            }
            return
        }
        val hits = symbolHitsOf((result as ApiResult.Ok).value)
        val index = if (hits.isNotEmpty()) {
            // 当たったのだから索引は在る。校正クエリを撃たない(無駄な往復をしない)。
            SymbolIndexState.AVAILABLE
        } else {
            when (val probe = gateway.findSymbols(SYMBOL_INDEX_PROBE, directory)) {
                is ApiResult.Ok ->
                    if (probe.value.isEmpty()) SymbolIndexState.UNAVAILABLE else SymbolIndexState.AVAILABLE
                // **校正が失敗したら「索引が無い」と言わない。** 測れなかったのであって、
                // 測って無かったのではない(RUN_PLAN「測れないと測っていないの区別」)。
                is ApiResult.Err -> SymbolIndexState.UNKNOWN
            }
        }
        _state.update { cur ->
            cur.copy(
                search = cur.search.copy(
                    loading = false,
                    submittedQuery = query,
                    symbols = hits,
                    symbolIndex = index,
                    searched = true,
                    error = null,
                ),
            )
        }
    }

    // ---- 接続先の変更 / 再接続 ----

    /**
     * 接続先が変わった。**捨てる**([ModelCatalogController] / [ServerInfoController] /
     * [DiffController] と同じ理由)。
     *
     * サーバーAのツリーをサーバーBの画面に出し続けるのは、Q4 レビュー major-1 が
     * カタログで見つけたのと同じ形で、**画面はどこも壊れて見えない**。
     * 同じ接続先への再保存では捨てない。初回は捨てるものが無い。
     */
    fun onConnectionChanged(connectionKey: String?) {
        if (seenConnectionKey == connectionKey) return
        val hadSomething = seenConnectionKey != null
        seenConnectionKey = connectionKey
        if (!hadSomething) return
        treeJob?.cancel()
        viewerJob?.cancel()
        searchJob?.cancel()
        treeJob = null
        viewerJob = null
        searchJob = null
        _state.value = FileBrowserUi()
    }

    /**
     * SSE が張り直された。**開いているツリーを引き直す**(RUN_PLAN 決定2)。
     *
     * `file.*` の SSE イベントは実機 spec に**存在しない** —— 切れている間に
     * エージェントが足したファイルは、イベント列から原理的に復元できない。
     * **まだ何も読んでいなければ何もしない**(勝手に開かない)。
     */
    fun onReconnected() {
        if (!_state.value.tree.loaded) return
        openDirectory(_state.value.tree.path)
    }
}
