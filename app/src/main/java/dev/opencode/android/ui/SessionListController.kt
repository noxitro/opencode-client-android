package dev.opencode.android.ui

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionsGateway
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.sortedForDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 1ページ目の件数。サーバー既定の `limit` は100なので、それより小さくないとページングを観測できない。 */
const val SESSION_PAGE_SIZE = 50

/**
 * `limit` の上限。**無制限に伸ばさない**ためのもの。
 *
 * `start` がオフセットではない以上(API_CONTRACT.md の実測)、深いページングは
 * 「`limit` を伸ばして取り直す」しか手が無く、毎回**全部**を再取得する。
 * 実測で1セッション約640バイトなので、1万件を超えるサーバーでは1回の応答が6MBを超える。
 * 上限に達したら「これ以上は追わない」ことを [SessionsUi.canLoadMore] = false で示す。
 */
const val SESSION_LIMIT_MAX = 2000

/** 検索入力のデバウンス(ms)。1文字ごとにGETを撃たない。 */
internal const val SEARCH_DEBOUNCE_MS = 300L

/**
 * 次に要求する `limit`。**線形ではなく倍々に伸ばす**。
 *
 * 50刻みだと、n件目まで到達するのに 50+100+150+… = O(n²) バイトを転送する
 * (423件のサーバーで約1.4MB。Q1レビュー minor-1)。倍々なら合計は 2n で収まる。
 * 上限は [SESSION_LIMIT_MAX]。
 */
fun nextSessionLimit(current: Int): Int = (current * 2).coerceAtMost(SESSION_LIMIT_MAX)

/**
 * まだ先があるかもしれないか。**`>=` であって `>` ではない。**
 *
 * サーバーは総件数を返さないので、「要求した `limit` ちょうど返ってきた」ことだけが
 * 手掛かりになる。`>` にすると `size == limit` のとき false になり、
 * **1ページ目から一歩も進まなくなる**(= R2 が未回収に戻る)。
 * Q1レビューがこの変異を打ち、当時のテスト102件は全緑のまま通り抜けた。
 * ぴったりで終わるときに1回だけ空振りするが、総件数が無い以上これが上限。
 */
fun canLoadMoreFrom(responseSize: Int, limit: Int): Boolean =
    responseSize >= limit && limit < SESSION_LIMIT_MAX

/**
 * セッション一覧の状態機械。**Android にも Compose にも依存しない**ので `runTest` で直接叩ける。
 *
 * [AppViewModel] から切り出した理由は [SessionsGateway] の doc を参照
 * (ページング状態機械とイベント振り分けに検出器が1本も無かった)。
 *
 * @param describeError [ApiError] を人向けの1行にする。文言は画面側の関心なので注入する。
 */
class SessionListController(
    private val gateway: SessionsGateway,
    private val scope: CoroutineScope,
    private val describeError: (ApiError) -> String,
) {
    private val _state = MutableStateFlow(SessionsUi())
    val state: StateFlow<SessionsUi> = _state.asStateFlow()

    /** 検索のデバウンス用。入力のたびに前のを潰す。 */
    private var searchJob: Job? = null

    /**
     * 一覧を一度でも取得したか。**回転や画面遷移で取り直さない**ための記録。
     *
     * これが無いと、`LaunchedEffect(Unit)` が回転のたびに走って `limit` が1ページ目へ戻り、
     * 120件を末尾まで開いていたユーザーが**48件目付近へ飛ばされる**(Q1レビュー major-1)。
     * ViewModel は構成変更をまたいで生きるので、ここが「もう読んである」を覚えられる。
     */
    private var loadedOnce = false

    /**
     * 一覧のスクロール位置(先頭に見えている行の添字とオフセット)。
     *
     * `rememberLazyListState` は内部が `rememberSaveable` なので**回転では残る**が、
     * チャットへ遷移すると一覧の Composable が構成から外れて捨てられる。
     * 122件を末尾まで開いてカードを1枚開いて戻ると、先頭へ飛ばされる
     * (Q1レビュー major-1 の2つ目)。画面より長生きする場所に置いて復元する。
     *
     * 1ページ目から取り直したときは意味を失うので [refresh] が 0 に戻す。
     */
    var savedScrollIndex: Int = 0
        private set
    var savedScrollOffset: Int = 0
        private set

    fun saveScroll(index: Int, offset: Int) {
        savedScrollIndex = index
        savedScrollOffset = offset
    }

    // ---- 取得 ----

    /**
     * まだ読んでいなければ読む。**画面の再表示(回転・チャットからの復帰)から呼ぶのはこちら。**
     * すでに読んであれば何もしない — `limit` もスクロール位置も保たれる。
     */
    fun ensureLoaded() {
        if (loadedOnce) return
        refresh()
    }

    /**
     * 接続先が変わった等で**明示的に**取り直す(再読み込みボタン・設定からの復帰)。
     *
     * [resetPaging] = true で `limit` を1ページ目へ戻す。検索語を変えたときは戻さないと、
     * 「50件しか無い検索結果」を400件分の窓で取り続けることになり、
     * 「もう先が無い」判定(応答件数 == limit)が永久に真にならない。
     */
    fun refresh(resetPaging: Boolean = true) {
        if (!gateway.isConfigured) return
        loadedOnce = true
        if (resetPaging) {
            // 1ページ目へ戻すなら、深い位置のスクロール記憶は意味を失う。
            savedScrollIndex = 0
            savedScrollOffset = 0
        }
        val limit = if (resetPaging) SESSION_PAGE_SIZE else _state.value.limit
        val search = _state.value.search
        scope.launch {
            _state.update { it.copy(loading = true, error = null, errorIsAuth = false, limit = limit) }
            val result = gateway.listSessions(limit = limit, search = search.takeIf { it.isNotBlank() })
            _state.update { s ->
                when (result) {
                    is ApiResult.Ok -> s.copy(
                        loading = false,
                        items = result.value,
                        error = null,
                        errorIsAuth = false,
                        canLoadMore = canLoadMoreFrom(result.value.size, limit),
                    )
                    is ApiResult.Err -> s.copy(
                        loading = false,
                        error = describeError(result.error),
                        errorIsAuth = isAuthError(result.error),
                    )
                }
            }
        }
        refreshStatus()
    }

    /**
     * ページング(R2)。**`limit` を倍に伸ばして取り直す**。
     *
     * `start` を使わない理由: spec には query にあるが、実サーバーで測ると
     * オフセットではなく `time.updated` の下限フィルタ(`>=`)だった。`start` を進めても
     * 同じ先頭が返り続け、末尾には**永久に到達できない**(API_CONTRACT.md に測定値)。
     */
    fun loadMore() {
        if (!gateway.isConfigured) return
        val s = _state.value
        if (s.loading || s.loadingMore || !s.canLoadMore) return
        val nextLimit = nextSessionLimit(s.limit)
        if (nextLimit <= s.limit) {
            // 上限に達している。押し続けても同じ応答が返るだけなので、先が無いことにする。
            _state.update { it.copy(canLoadMore = false) }
            return
        }
        val search = s.search
        scope.launch {
            _state.update { it.copy(loadingMore = true) }
            val result = gateway.listSessions(limit = nextLimit, search = search.takeIf { it.isNotBlank() })
            _state.update { cur ->
                when (result) {
                    is ApiResult.Ok -> cur.copy(
                        loadingMore = false,
                        limit = nextLimit,
                        items = result.value,
                        canLoadMore = canLoadMoreFrom(result.value.size, nextLimit),
                    )
                    // 伸ばした limit を確定させない: 次の押下で同じ幅をもう一度試せる。
                    is ApiResult.Err -> cur.copy(loadingMore = false, actionError = describeError(result.error))
                }
            }
        }
    }

    /**
     * `GET /session/status` を1回取得して [SessionsUi.runStates] を**置き換える**。
     *
     * 差分マージではなく置換なのが重要: マージすると、切れている間に idle へ落ちた
     * セッションが古い busy を保ったままになる。サーバーの現在値がそのまま真になる。
     */
    fun refreshStatus() {
        if (!gateway.isConfigured) return
        scope.launch {
            when (val result = gateway.sessionStatus()) {
                is ApiResult.Ok -> {
                    val states = result.value.mapValues { (_, dto) -> runStateOf(dto) }
                        .filterValues { it != SessionRunState.IDLE }
                    _state.update { it.copy(runStates = states) }
                }
                // 取得できなくてもバッジが出ないだけ。一覧そのものは壊さない。
                is ApiResult.Err -> Unit
            }
        }
    }

    // ---- 検索 ----

    /** 検索欄の開閉。閉じるときは検索語も捨てて全件へ戻す。 */
    fun setSearchOpen(open: Boolean) {
        if (_state.value.searchOpen == open) return
        if (open) {
            _state.update { it.copy(searchOpen = true) }
        } else {
            searchJob?.cancel()
            searchJob = null
            val hadQuery = _state.value.search.isNotBlank()
            _state.update { it.copy(searchOpen = false, search = "") }
            if (hadQuery) refresh()
        }
    }

    /** 検索語の変更。[SEARCH_DEBOUNCE_MS] だけ待ってから `GET /session?search=` を撃つ。 */
    fun updateSearch(query: String) {
        if (_state.value.search == query) return
        _state.update { it.copy(search = query) }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            refresh()
        }
    }

    // ---- 作成・改名・削除 ----

    /**
     * `POST /session`(Q4 で `agent` / `model` を受け取れるようにした)。
     *
     * **null は「未指定」であって「既定を明示する」ではない。** `explicitNulls = false` で
     * キーごと省略されるので、サーバー既定が使われる。明示 null を送ると 400 になる(実測)。
     */
    fun create(title: String, agent: String? = null, model: ModelRefDto? = null) {
        if (!gateway.isConfigured) return
        // ---- 二重POSTガード(申し送り Q4-1)----
        //
        // 直す欠陥: 以前は `if (_state.value.creating) return` が `scope.launch` の**外**、
        // `creating = true` が**中**にあった。`launch` の本体は次のディスパッチまで走らないので、
        // **同一フレームの2連打では両方ともガードを素通りし、POST が2件飛ぶ**。
        // P1/P2 のレビューは同じ箇所を「VM側ガードで修正済み」と記録しているが、
        // 連打を実測しておらず(TEST_REPORT 所見1-3 の「ランタイム連打テストは➖」)、
        // ガードは形だけのまま4段生き延びた。
        //
        // 読みと書きを [getAndUpdate] の1回のCASにまとめる。**判定と占有の間に
        // サスペンド点を1つも置かない**ことがガードの本体なので、
        // ここを「読んでから launch の中で立てる」形へ戻さないこと。
        //
        // **拒否される経路では1バイトも書かない。** ラムダの中で分岐して `it` をそのまま返すと、
        // `StateFlow` は同値なら購読者に流さない。素の `it.copy(...)` を渡すと、
        // 弾かれた2発目まで `createError = null` を書いてしまう —— [rename] では同じ形が
        // 「実行中の別セッションの pendingActionId を奪う」実害になった(Q5レビュー major-1)。
        // create では今日は観測できないが、**対称性のために同じ形にする**。
        val before = _state.getAndUpdate { s ->
            if (s.creating) s else s.copy(creating = true, createError = null)
        }
        if (before.creating) return
        scope.launch {
            when (val result = gateway.createSession(title, agent, model)) {
                is ApiResult.Ok -> {
                    _state.update { s ->
                        s.copy(
                            creating = false,
                            createError = null,
                            lastCreatedId = result.value.id,
                            items = (listOf(result.value) + s.items).distinctBy { it.id }.sortedForDisplay(),
                        )
                    }
                    // 返却Sessionのtimeが欠ける場合に備え、最新一覧を取り直して順序を確定させる。
                    // ページングは維持する(作成のたびに1ページ目へ戻さない)。
                    refresh(resetPaging = false)
                }
                is ApiResult.Err ->
                    _state.update { it.copy(creating = false, createError = describeError(result.error)) }
            }
        }
    }

    /**
     * 改名(PATCH /session/{id} {title})。成功したら返却された Session で置き換える。
     * `session.updated` も届くが、**それを待たない** — 待つと自分の操作が反映されたのか
     * 通信が失敗したのかを画面から区別できない。
     */
    fun rename(sessionId: String, title: String) {
        if (!gateway.isConfigured) return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        // [create] と**同じ形の欠陥**がここにもあった(読みは launch の外、占有は中)。
        // `delete` は最初から同期的に占有していたので、揃っていなかったのはここと `create` の2つ。
        //
        // **拒否される経路では書かない。** Q5レビュー major-1 が実証した欠陥:
        // 素の `it.copy(pendingActionId = sessionId, ...)` を `getAndUpdate` に渡すと、
        // 弾かれた側の `sessionId` で**実行中の要求の占有者を上書きする**。
        // A の改名が飛んでいる最中に B を改名しようとすると、B の PATCH は正しく抑止される一方で
        // `pendingActionId` が B になり、**A の行が黙って pending 表示をやめ、
        // 送られてもいない B の行がスピナーを出す**。A の応答が返ると B の幽霊スピナーが
        // 無関係な瞬間に消え、その間 `delete` は存在しない要求に塞がれる。
        // 並行性の欠陥を直すために状態破壊の欠陥を入れないこと。
        val before = _state.getAndUpdate { s ->
            if (s.pendingActionId != null) s else s.copy(pendingActionId = sessionId, actionError = null)
        }
        if (before.pendingActionId != null) return
        scope.launch {
            when (val result = gateway.renameSession(sessionId, trimmed)) {
                is ApiResult.Ok -> _state.update { s ->
                    s.copy(
                        pendingActionId = null,
                        items = applySessionInfoToItems(
                            items = s.items,
                            kind = SseEvent.SessionInfoChanged.Kind.UPDATED,
                            sessionId = sessionId,
                            info = result.value,
                            allowInsert = false,
                        ),
                    )
                }
                is ApiResult.Err -> _state.update {
                    it.copy(pendingActionId = null, actionError = "改名に失敗しました: ${describeError(result.error)}")
                }
            }
        }
    }

    /**
     * 削除。**楽観更新 + 失敗時ロールバック**(§5 Q1 スコープ7)。
     * `session.deleted` は待たない。待つと、削除したのに一覧に残っている時間が生まれる。
     *
     * 戻す位置は**添字ではなく「1つ上の行のid」**で覚える。通信中に `session.created` が
     * 届いて先頭に1件挿さると添字は1つずれ、古い添字へ戻すと**別の場所に生き返る**
     * (Q1レビュー minor-4。純関数テストは静的な列しか見ていないので検出できなかった)。
     */
    fun delete(sessionId: String) {
        if (!gateway.isConfigured) return
        val before = _state.value.items
        val index = indexOfSession(before, sessionId)
        if (index < 0) return
        if (_state.value.pendingActionId != null) return
        val removed = before[index]
        val anchorId = before.getOrNull(index - 1)?.id
        _state.update {
            it.copy(
                items = removeSessionOptimistically(it.items, sessionId),
                actionError = null,
                pendingActionId = sessionId,
            )
        }
        scope.launch {
            when (val result = gateway.deleteSession(sessionId)) {
                is ApiResult.Ok -> _state.update { it.copy(pendingActionId = null) }
                is ApiResult.Err -> _state.update { s ->
                    s.copy(
                        pendingActionId = null,
                        items = restoreSessionAfter(s.items, removed, anchorId),
                        actionError = "削除に失敗しました: ${describeError(result.error)}",
                    )
                }
            }
        }
    }

    fun clearActionError() = _state.update { it.copy(actionError = null) }

    // ---- SSE ----

    /**
     * 一覧に効くイベントを適用する。**チャットを開いているかに関係なく呼ばれること。**
     *
     * 以前は [AppViewModel.onSseEvent] の途中(`activeSessionId ?: return` の手前)で
     * 呼んでいた。その順序は**コメントでしか守られておらず**、Q1レビューが1行動かす変異を
     * 打ったところテスト102件は全緑のまま、一覧のバッジもタイトル自動更新も死んだ。
     * 現在は VM 側が「一覧へ配る」「チャットへ配る」を順に**無条件で**呼ぶ形にして、
     * 早期 return の後ろへ落ちる余地そのものを無くしてある。
     *
     * @return このイベントを一覧が使ったか(テストと計測のため。呼び出し側は分岐しない)
     */
    fun onEvent(event: SseEvent): Boolean = when (event) {
        is SseEvent.SessionStatusChanged -> {
            val id = event.sessionID
            if (id != null) {
                val runState = runStateOf(event.status)
                _state.update { ui ->
                    val next = ui.runStates.toMutableMap()
                    // idle は**キーごと消す**。`GET /session/status` が idle を返さない形と
                    // 揃えておかないと、初期化(置換)と更新(マージ)で表現が食い違う。
                    if (runState == SessionRunState.IDLE) next.remove(id) else next[id] = runState
                    ui.copy(runStates = next)
                }
            }
            true
        }
        is SseEvent.SessionInfoChanged -> {
            _state.update { ui ->
                ui.copy(
                    items = applySessionInfoToItems(
                        items = ui.items,
                        kind = event.kind,
                        sessionId = event.sessionID,
                        info = event.info,
                        // 検索中は新規セッションを差し込まない(検索結果でなくなるため)。
                        allowInsert = ui.search.isBlank(),
                    ),
                )
            }
            true
        }
        // チャット側のイベント(P3/P4の経路)は一切触らない。
        else -> false
    }

    /** テスト用: 「まだ一度も読んでいない」状態に戻す。 */
    internal fun resetLoadedForTest() {
        loadedOnce = false
    }

    /** テスト・デバッグ用の読み取り。 */
    internal val hasLoadedOnce: Boolean get() = loadedOnce

    internal fun itemsForTest(): List<SessionDto> = _state.value.items
}
