package dev.opencode.android.ui

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ServerInfoGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * サーバーの素性(Q5: ドロワーのヘッダーと設定画面の「サーバーバージョン」)。
 *
 * [version] が null なら「まだ分からない」。**"不明" という文字列を状態に入れない** ——
 * 文言は画面の関心で、状態は「取れたか取れないか」だけを持つ。
 */
data class ServerInfoUi(
    val loading: Boolean = false,
    val healthy: Boolean? = null,
    val version: String? = null,
    val error: String? = null,
) {
    /** 一度でも取得に成功したか。失敗は成功でない(次に開いたら引き直す)。 */
    val loaded: Boolean get() = version != null
}

/**
 * サーバー素性の状態機械。**Android にも Compose にも依存しない**ので `runTest` で直接叩ける
 * ([ServerInfoGateway] の doc に切り出した理由がある)。
 *
 * 判断は3つで、どれも [ModelCatalogController] と同じ形に揃えてある:
 *
 *  1. **要求されるまで引かない**([ensureLoaded] をドロワー/設定画面の表示から呼ぶ)
 *  2. **成功したら二度引かない**。バージョンはセッション中に変わらない前提
 *  3. **失敗は `loaded` にしない**。次に開いたときにもう一度引く
 *
 * そして **接続先が変わったら捨てる**([onConnectionChanged])。捨てないと、サーバーAの
 * バージョンがサーバーBのヘッダーに出続ける —— Q4 レビュー major-1 がカタログで見つけたのと
 * **同じ形**で、画面はどこも壊れて見えない。
 */
class ServerInfoController(
    private val gateway: ServerInfoGateway,
    private val scope: CoroutineScope,
    private val describeError: (ApiError) -> String,
) {
    private val _state = MutableStateFlow(ServerInfoUi())
    val state: StateFlow<ServerInfoUi> = _state.asStateFlow()

    private var inFlight: Job? = null

    /** 直近に見た接続先。null = まだ一度も接続先を知らされていない。 */
    private var seenConnectionKey: String? = null

    /**
     * まだ取れていなければ引く。**表示する導線から呼ぶのはこちら。**
     *
     * 判定をここが持つので、呼び出し側に条件を書かないこと。
     */
    fun ensureLoaded() {
        if (_state.value.loaded) return
        if (inFlight?.isActive == true) return
        refresh()
    }

    /** 明示的な取り直し(接続テスト直後・再試行)。 */
    fun refresh() {
        if (!gateway.isConfigured) return
        inFlight?.cancel()
        inFlight = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // **通信は `update` の外で終わらせる**(Q5 レビュー minor-3)。
            // `MutableStateFlow.update` は CAS のリトライループなので、ラムダの中で
            // suspend 関数を呼ぶと、競合したときに `gateway.health()` が**もう一度走る**。
            // 今の競合相手は `onConnectionChanged` だけで、それは先に `inFlight` を
            // キャンセルするため潜在的だが、Q4 のレビューが既に欠陥を見つけた場所の真下にある。
            val result = gateway.health()
            _state.update { cur ->
                when (result) {
                    is ApiResult.Ok -> cur.copy(
                        loading = false,
                        healthy = result.value.healthy,
                        version = result.value.version,
                        error = null,
                    )
                    is ApiResult.Err -> cur.copy(
                        loading = false,
                        // **古い値を残さない。** 取れなかったサーバーのバージョンを出し続けるのは
                        // 「取れている」と区別が付かない。
                        healthy = null,
                        version = null,
                        error = describeError(result.error),
                    )
                }
            }
        }
    }

    /**
     * 接続先が変わった。**捨てる**([ModelCatalogController.onConnectionChanged] と同じ理由)。
     *
     * 同じ接続先への再保存(パスワードだけ入れ直した等)では捨てない。
     * 初回(まだ何も見ていない)は捨てるものが無いので状態を触らない。
     */
    fun onConnectionChanged(connectionKey: String?) {
        if (seenConnectionKey == connectionKey) return
        val hadSomething = seenConnectionKey != null
        seenConnectionKey = connectionKey
        if (!hadSomething) return
        inFlight?.cancel()
        inFlight = null
        _state.value = ServerInfoUi()
    }
}
