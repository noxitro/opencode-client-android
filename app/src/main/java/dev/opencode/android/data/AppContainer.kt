package dev.opencode.android.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * データ層の所有者(手動DI)。**プロセス生存中に1つ**。
 *
 * ViewModel はActivityと共に死ぬので、ここに置かないと
 * 「**フォアグラウンドの間は常時1本で、画面の出入りでは切らない**」SSE接続
 * (QUALITY_PLAN §5 Q0 スコープ3 / RUN_PLAN 決定1)が成立しない。
 * バックグラウンド化では実際に切れる(実測。[OpenCodeEventStream] の doc を参照)。
 * ライブラリ追加(Hilt等)はせず、Applicationスコープの単純なシングルトンにする。
 */
class AppContainer private constructor(context: Context) {

    /** アプリ生存中のスコープ。ViewModelのスコープと違い、画面破棄で切れない。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settings = SettingsRepository(context)

    val connection: ConnectionRepository = ConnectionRepository(settings, scope)

    /**
     * Q5: ローカル設定(カラーモード/触覚)。**ViewModel より先に値が要る** ——
     * `MainActivity` はテーマを決める前にここの最初の値を待つ(起動時のちらつき対策。
     * [PreferencesRepository] の doc)。
     */
    val preferences: PreferencesRepository =
        PreferencesRepository(DataStorePreferencesStore(context), scope)

    val sessions: SessionsRepository = SessionsRepository(connection)
    val chat: ChatRepository = ChatRepository(connection)

    /** Q4: モデル/エージェントのカタログ。**5.4 MiB を引く口**なので呼ぶ場所を絞ること。 */
    val catalog: CatalogRepository = CatalogRepository(connection)

    /**
     * Q7: 差分 / VCS。**`POST /vcs/apply` は口ごと存在しない**([DiffGateway] の doc)。
     */
    val diff: DiffRepository = DiffRepository(connection)

    /**
     * Q8: ファイルブラウザ + 検索。**`GET /file/content` は受信を
     * [FILE_CONTENT_MAX_BYTES] で打ち切る**([FilesRepository])。
     */
    val files: FilesRepository = FilesRepository(connection)

    /**
     * Q9: ターミナル(PTY)。**このフェーズだけ WebSocket を張る**([PtyChannel] の doc)。
     *
     * 接続の寿命は [dev.opencode.android.ui.PtyController] が持つ ——
     * SSE([events])と違って**画面を閉じたら切る**。走らせ続けるとサーバー側の
     * シェルへ入力を送れる経路が画面の外に残り、電池も食う。
     */
    val pty: PtyRepository = PtyRepository(connection)
    val events: OpenCodeEventStream = OpenCodeEventStream(connection, scope)

    init {
        // 接続先が未設定の間は何も張らず、設定された時点で自動的に接続する。
        events.start()
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
