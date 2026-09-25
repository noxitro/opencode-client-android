package dev.opencode.android.ui

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.CatalogGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * モデル/エージェントのカタログ状態(Q4)。
 *
 * [models] / [agents] は**すでに絞り込み済み**の選択肢である
 * ([selectableModels] / [selectableAgents])。生の 203プロバイダ・7,338モデルは
 * この状態に一度も入らない —— 入れてしまうと、絞り忘れた画面が出た瞬間に無意味になる。
 */
data class ModelCatalogUi(
    val loading: Boolean = false,
    val models: List<ModelChoice> = emptyList(),
    val agents: List<AgentChoice> = emptyList(),
    val error: String? = null,
    /** [error] が認証失敗(401/403)由来か(Q6)。空状態が「再試行」を出すかの材料。 */
    val errorIsAuth: Boolean = false,
    /** モデル選択シートの検索語。 */
    val query: String = "",
    /** 一度でも取得に成功したか。失敗は成功でない(次に開いたら引き直す)。 */
    val loaded: Boolean = false,
    /**
     * **今出している一覧が「取り直せなかった前回の結果」であるか**(回収項目C)。
     *
     * `models` を残したまま `error` を立てる経路が実在する(取得に一度成功したあとの再取得失敗)。
     * そのとき画面は今までどおり一覧を出すが、**それが古いことを言わなければ
     * 「取れている」と読める** —— HANDOFF 中核原則「無い」と「取れなかった」の区別が、
     * ここでは「今の」と「前回の」の区別として現れる。帯の文言は [modelCatalogNotice] が決める。
     */
    val stale: Boolean = false,
    /**
     * [canRunSession] で**出さないことにした**モデルの件数(Q4 レビュー major-2)。
     * 実測(実物 serve): connected 600件のうち **136件**。
     *
     * 画面に出すためだけに持つ。**黙って短くしたリストは「そのモデルが無い」と
     * 「出さないことにした」の区別が付かない。**
     */
    val excludedModels: Int = 0,
) {
    /** 検索語を当てた表示用の一覧。**絞り込みは1か所**([filterModelChoices])に閉じる。 */
    val visibleModels: List<ModelChoice> get() = filterModelChoices(models, query)
}

/**
 * カタログの状態機械。**Android にも Compose にも依存しない**ので `runTest` で直接叩ける
 * ([CatalogGateway] の doc に切り出した理由がある)。
 *
 * ここが持つ判断は3つだけで、どれも実測に紐づく:
 *
 *  1. **要求されるまで引かない。** `GET /provider` は実測 5.4 MiB(203プロバイダ/7,338モデル)。
 *     起動時に引くと、モデル選択を一度も開かないユーザーが毎回それを払う。
 *  2. **成功したら二度引かない。** カタログはセッション中に変わらない前提で、
 *     `loaded` が立っていれば再取得しない。明示的な [reload] だけが引き直す。
 *  3. **失敗は `loaded` にしない。** 一度失敗したきり永久に空の選択肢を出すのが
 *     一番わかりにくい壊れ方なので、次に開いたときにもう一度引く。
 *
 * 並行呼び出し(シートを素早く2回開く)は [inFlight] で1本に畳む。
 */
class ModelCatalogController(
    private val gateway: CatalogGateway,
    private val scope: CoroutineScope,
    private val describeError: (ApiError) -> String,
) {
    private val _state = MutableStateFlow(ModelCatalogUi())
    val state: StateFlow<ModelCatalogUi> = _state.asStateFlow()

    private var inFlight: Job? = null

    /**
     * まだ取れていなければ引く。**モデル選択を開く導線から呼ぶのはこちら。**
     *
     * この関数が「まだ取れていない」を判定するので、呼び出し側に条件を書かないこと ——
     * 呼び出し側の `if` は Q1/Q2 のレビューが変異で素通しを示した場所そのものである。
     */
    fun ensureLoaded() {
        if (_state.value.loaded) return
        if (inFlight?.isActive == true) return
        reload()
    }

    /** 明示的な取り直し(再試行ボタン・接続先変更)。 */
    fun reload() {
        if (!gateway.isConfigured) return
        inFlight?.cancel()
        inFlight = scope.launch {
            _state.update { it.copy(loading = true, error = null, errorIsAuth = false) }
            val providers = gateway.listProviders()
            val agents = gateway.listAgents()
            _state.update { cur ->
                when (providers) {
                    is ApiResult.Ok -> cur.copy(
                        loading = false,
                        models = selectableModels(providers.value),
                        excludedModels = excludedModelCount(providers.value),
                        // エージェントだけ失敗しても**モデル選択は成立させる**。
                        // 両方要求すると、片方の失敗が R3 の導線を丸ごと閉じる。
                        agents = (agents as? ApiResult.Ok)?.value?.let(::selectableAgents) ?: cur.agents,
                        error = (agents as? ApiResult.Err)
                            ?.let { "エージェント一覧の取得に失敗しました: ${describeError(it.error)}" },
                        errorIsAuth = isAuthError((agents as? ApiResult.Err)?.error),
                        loaded = true,
                        // 取れた一覧は古くない。
                        stale = false,
                    )
                    is ApiResult.Err -> cur.copy(
                        loading = false,
                        error = "モデル一覧の取得に失敗しました: ${describeError(providers.error)}",
                        // **失敗を loaded にしない。** 次に開いたときに引き直す。
                        errorIsAuth = isAuthError(providers.error),
                        loaded = false,
                        // **前回の一覧を残すなら、古いと言う。** 残すもの自体は変えない
                        // (消すと「モデルが1件も無いサーバー」と区別が付かなくなる)。
                        stale = cur.models.isNotEmpty(),
                    )
                }
            }
        }
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** シートを閉じたときに検索語を捨てる(次に開いたら全件から始める)。 */
    fun clearQuery() {
        _state.update { it.copy(query = "") }
    }

    /**
     * 接続先が変わった(Q4 レビュー major-1)。**キャッシュを捨てる。**
     *
     * 直す欠陥: サーバーA でカタログを取り、設定でサーバーB へ替えると、
     * B のセッションでシートを開いても **A のモデル一覧が出た**。A のモデルを選ぶと
     * **B は 204 で受理して保存する**(サーバーは値を検証しない。API_CONTRACT.md 実測 #4)。
     * 失敗は次の推論まで出ず、画面はどこも壊れて見えない。
     *
     * 契約文書は「クライアントが `GET /provider` から得た候補以外を送らないことが
     * 唯一の防波堤」と書いている。**古いカタログはその防波堤をそのまま抜ける。**
     * セッションのモデルには「サーバーが権威」を適用済み
     * ([ChatController.onReconnected] / `refreshSessionMeta`)なのに、
     * カタログにだけ未適用だった。
     *
     * **ここで取り直さない**(`reload()` を呼ばない)。5.4 MiB を接続先変更のたびに
     * 引くことになり、「要求されるまで引かない」という上の判断1と食い違う。
     * 捨てるだけにして、次に [ensureLoaded] が呼ばれたときに新しい接続先で引く。
     *
     * 同じ接続先への再保存(パスワードだけ入れ直した等)では捨てない —— [connectionKey] が
     * 変わらなければ何もしない。`null`(未設定)への遷移でも捨てる: 接続先が消えたのに
     * 前のサーバーの候補を出し続ける理由が無い。
     */
    fun onConnectionChanged(connectionKey: String?) {
        if (seenConnectionKey == connectionKey) return
        val hadSomething = seenConnectionKey != null
        seenConnectionKey = connectionKey
        // 初回(まだ何も見ていない)は捨てるものが無い。無駄な状態更新をしない。
        if (!hadSomething) return
        inFlight?.cancel()
        inFlight = null
        _state.value = ModelCatalogUi()
    }

    /** 直近に見た接続先。null = まだ一度も接続先を知らされていない。 */
    private var seenConnectionKey: String? = null

    /** テスト用: 「まだ一度も読んでいない」状態に戻す。 */
    internal fun resetLoadedForTest() {
        _state.update { it.copy(loaded = false) }
    }
}
