package dev.opencode.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 接続先1つ分の資格情報。`baseUrl` は正規化済み(Urls.normalize)である前提。 */
data class Connection(
    val baseUrl: String,
    val password: String,
)

/**
 * **資格情報を持つ唯一の場所**(QUALITY_PLAN §5 Q0 スコープ3)。
 *
 * これ以前は ViewModel が `baseUrl` / `password` を全呼び出し口に引数として通しており、
 * リポジトリは受け取った資格情報で毎回 [OpenCodeApi] を new して転送するだけだった。
 * 資格情報をここに集約し、[OpenCodeApi] インスタンス(=OkHttpClient・接続プール)も
 * 接続先ごとに1つだけ持つ。接続先が変わったときだけ作り直す。
 *
 * [saved] は「DataStore読み込み前(null)」と「読み込み済みだが未設定」を区別するため
 * nullable のまま公開する(画面の初期遷移がこれに依存している)。
 *
 * [save] は **DataStore への永続化より先にメモリ状態を更新する**。逆順にすると
 * 「保存 → 直後にhealthを叩く」経路が、DataStoreのFlowが1周する前の古い接続先を使いうる。
 */
class ConnectionRepository(
    private val settings: SettingsStore,
    scope: CoroutineScope,
) : ServerInfoGateway, ConnectionSetupGateway {
    private val _saved = MutableStateFlow<SettingsRepository.Saved?>(null)
    val saved: StateFlow<SettingsRepository.Saved?> = _saved.asStateFlow()

    private val _connection = MutableStateFlow<Connection?>(null)

    /** 現在の接続先(null=未設定/読み込み前)。SSEストリームはこれを購読して張り直す。 */
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    override val isConfigured: Boolean
        get() = _connection.value != null

    /** 接続先ごとに1つだけ持つRESTクライアント。接続先が変わったときだけ差し替える。 */
    @Volatile
    private var cached: Pair<Connection, OpenCodeApi>? = null

    init {
        scope.launch {
            settings.saved.collect { apply(it) }
        }
    }

    /** 保存済み設定をメモリ状態へ反映する(同値なら何もしない)。 */
    private fun apply(value: SettingsRepository.Saved) {
        _saved.value = value
        val next = if (value.isConfigured) Connection(value.baseUrl, value.password) else null
        if (_connection.value != next) {
            _connection.value = next
        }
    }

    /** 設定保存。メモリ状態を先に更新してからDataStoreへ書く(上の注記)。 */
    override suspend fun save(baseUrl: String, password: String) {
        apply(SettingsRepository.Saved(baseUrl = baseUrl, password = password))
        settings.save(baseUrl, password)
    }

    /** 現在の接続先のRESTクライアント。未設定ならnull。 */
    fun api(): OpenCodeApi? {
        val conn = _connection.value ?: return null
        cached?.let { (c, api) -> if (c == conn) return api }
        val api = OpenCodeApi(conn.baseUrl, conn.password)
        cached = conn to api
        return api
    }

    /** 未設定なら [ApiError.NotConfigured]。呼び出し側に資格情報を要求しない。 */
    internal suspend fun <T> withApi(block: suspend (OpenCodeApi) -> ApiResult<T>): ApiResult<T> {
        val api = api() ?: return ApiResult.Err(ApiError.NotConfigured)
        return block(api)
    }

    /**
     * GET /global/health。設定保存直後の疎通確認と、Q5 の
     * 「サーバーバージョン表示」([dev.opencode.android.ui.ServerInfoController])が使う。
     */
    override suspend fun health(): ApiResult<HealthDto> = withApi { it.health() }
}
