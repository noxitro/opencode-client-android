package dev.opencode.android.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.AppContainer
import dev.opencode.android.data.AppPreferences
import dev.opencode.android.data.ColorMode
import dev.opencode.android.data.HealthDto
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SettingsRepository
import dev.opencode.android.data.Urls
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 画面はNavigation Composeを使わない単純なstate切替(設定+一覧+チャットの3画面)。 */
sealed interface Screen {
    data object Settings : Screen
    data object Sessions : Screen

    /** Q8: ファイルブラウザ + 検索(§5b Q8)。ドロワーから入る。 */
    data object Files : Screen

    /** Q9: ターミナル(PTY)(§5b Q9)。ドロワーから入る。 */
    data object Terminal : Screen
    data class Detail(val sessionId: String) : Screen
}

enum class HealthFailureKind { BAD_URL, AUTH, HTTP, NETWORK }

sealed interface HealthUiState {
    data object Idle : HealthUiState
    data object Testing : HealthUiState
    data class Ok(val healthy: Boolean, val version: String) : HealthUiState
    data class Failed(val kind: HealthFailureKind, val message: String) : HealthUiState
}

data class SessionsUi(
    val loading: Boolean = false,
    val items: List<SessionDto> = emptyList(),
    val error: String? = null,
    /**
     * [error] が認証失敗(401/403)由来か(Q6)。**文字列を見て判定しない** ——
     * 「再試行を出すかどうか」は文言の書き方に依存してはならない。
     * 判定そのものは [isAuthError] にあり、ここはその結果を運ぶだけ。
     */
    val errorIsAuth: Boolean = false,
    val creating: Boolean = false,
    val createError: String? = null,
    /** 直近で作成に成功したセッションID。UIはこれを見て一覧を先頭へスクロールする。 */
    val lastCreatedId: String? = null,

    /**
     * sessionID -> 実行状態。**キーが無ければ idle**(`GET /session/status` は idle を
     * 返さないという実測に合わせる。API_CONTRACT.md)。
     */
    val runStates: Map<String, SessionRunState> = emptyMap(),

    /** 現在の検索語(空文字=検索していない)。TopAppBarの検索欄と同期する。 */
    val search: String = "",
    /** 検索欄を開いているか。回転で保つ必要があるのでVM側に持つ。 */
    val searchOpen: Boolean = false,

    /**
     * サーバーへ渡している `limit`。ページングは**これを伸ばす**方式
     * (`start` はオフセットではないため。API_CONTRACT.md の実測)。
     */
    val limit: Int = SESSION_PAGE_SIZE,
    /**
     * まだ先がありそうか。直近の応答が `limit` ちょうどだったかで判断する
     * (サーバーは総件数を返さないので、これが唯一の手掛かり)。
     */
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,

    /** 改名・削除の失敗。楽観更新をロールバックしたことをここで見せる。 */
    val actionError: String? = null,
    /** 改名/削除の送信中セッションID(ボタン二度押し防止)。 */
    val pendingActionId: String? = null,
)

// ChatEventStatus / ChatUi は Q2 で ui/ChatController.kt へ移した。
// チャットの状態機械が Android 非依存の [ChatController] になり、状態の定義と
// 遷移が同じファイルに並ぶほうが読める(Q1 の SessionListController と同じ形)。

/**
 * 画面の状態を組み立てるだけの層。**RESTもSSEもここには無い**(Q0 スコープ3)。
 * 資格情報は [dev.opencode.android.data.ConnectionRepository] が持ち、SSEは
 * [dev.opencode.android.data.OpenCodeEventStream] がアプリ生存中1本で持つ。
 */
class AppViewModel(app: Application) : AndroidViewModel(app), ChatActions, FileActions, PtyActions {

    private val container = AppContainer.get(app)
    private val connectionRepo = container.connection
    private val preferencesRepo = container.preferences

    /** null = DataStore初回読み込み中。 */
    val saved: StateFlow<SettingsRepository.Saved?> = connectionRepo.saved

    /**
     * Q5: ローカル設定(カラーモード/触覚)。**null = DataStore初回読み込み前**であって
     * 「既定」ではない([dev.opencode.android.data.PreferencesRepository] の doc)。
     * テーマを描く側は `MainActivity` が最初の値を待ってから描き始めるので、ここが null の
     * 画面は実際には出ない。
     */
    val preferences: StateFlow<AppPreferences?> = preferencesRepo.current

    private val _health = MutableStateFlow<HealthUiState>(HealthUiState.Idle)
    val health: StateFlow<HealthUiState> = _health.asStateFlow()

    /**
     * 一覧の状態機械。**Androidに依存しない素のクラス**なので `runTest` で直接叩ける
     * ([SessionListController] / [dev.opencode.android.data.SessionsGateway] の doc)。
     */
    private val sessionList = SessionListController(
        gateway = container.sessions,
        scope = viewModelScope,
        describeError = { it.describe() },
    )

    val sessions: StateFlow<SessionsUi> = sessionList.state

    /**
     * チャットの状態機械。一覧と同じく **Androidに依存しない素のクラス**なので
     * `runTest` で直接叩ける([ChatController] / [dev.opencode.android.data.ChatGateway] の doc)。
     *
     * Q1 レビューの「検出器の穴」への対応。busy の立ち下がり・バナーの選択・abort 後の復帰は
     * 純関数に切り出せないので、**叩ける場所へ出す**ことで検出器を置けるようにした。
     */
    private val chatController = ChatController(
        gateway = container.chat,
        scope = viewModelScope,
        describeError = { it.describe() },
    )

    val chat: StateFlow<ChatUi> = chatController.state

    /**
     * Q4: モデル/エージェントのカタログ。一覧の作成ダイアログとチャットの切替シートが**共有する**。
     *
     * 共有するのは `GET /provider` が実測 5.4 MiB(203プロバイダ/7,338モデル)だからである。
     * 画面ごとに持つと、作成ダイアログを開いてからチャットで切り替えるだけで2回払う。
     */
    private val modelCatalog = ModelCatalogController(
        gateway = container.catalog,
        scope = viewModelScope,
        describeError = { it.describe() },
    )

    val catalog: StateFlow<ModelCatalogUi> = modelCatalog.state

    /**
     * Q5: サーバーの素性(`GET /global/health`)。ドロワーのヘッダーと設定画面の
     * 「サーバーバージョン」が使う。**推論を伴わないので実物 serve でも測れる**。
     */
    private val serverInfoController = ServerInfoController(
        gateway = container.connection,
        scope = viewModelScope,
        describeError = { it.describe() },
    )

    val serverInfo: StateFlow<ServerInfoUi> = serverInfoController.state

    /**
     * Q7: 差分 / VCS。ブランチ表示(TopAppBar)・作業ツリー(ドロワー)・
     * メッセージの差分(チャット)が**1つの状態機械を共有する**。
     *
     * 共有するのは、3つが同じ `GET /vcs*` の族を叩き、同じ「接続先が変わったら捨てる」
     * 規則に従うからである。分けると、その規則を3か所に書くことになる。
     */
    private val diffController = DiffController(
        gateway = container.diff,
        scope = viewModelScope,
        describeError = { it.describe() },
    )

    val diff: StateFlow<DiffUi> = diffController.state

    /**
     * Q8: ファイルブラウザ + 検索。
     *
     * **`GET /vcs/status` を Q7 の口から引く**([container.diff])—— 変更バッジの材料である。
     * `GET /file/status` を使わないのは、1.18.21 が**変更があっても `[]` を返す**からで、
     * 200 が返るので応答からは「変更が無い」と「口が機能していない」が区別できない
     * (API_CONTRACT.md「`GET /file/status` は変更を返さない」)。
     */
    private val fileBrowser = FileBrowserController(
        gateway = container.files,
        vcs = container.diff,
        scope = viewModelScope,
        describeError = { it.describe() },
    )

    val files: StateFlow<FileBrowserUi> = fileBrowser.state

    /**
     * Q9: ターミナル(PTY)。**アプリで唯一 WebSocket を持つ状態機械**である。
     *
     * ViewModel スコープに置いてあるので、**プロセスが生きていても画面が死ねば接続も切れる**。
     * SSE([container.events])を [AppContainer] 側に置いたのと逆の判断で、理由は
     * 「PTY は入力を送る口である」——見ていない画面のためにシェルへの経路を開けておかない。
     */
    private val ptyController = PtyController(
        gateway = container.pty,
        scope = viewModelScope,
        describeError = { it.describe() },
    )

    val pty: StateFlow<PtyUi> = ptyController.state

    private val _eventStatus = MutableStateFlow<ChatEventStatus?>(null)

    /**
     * SSE接続の状態。**画面に依存しない**ので一覧からも参照できる。
     * 特に [ChatEventStatus.UNAUTHORIZED] は「再試行を止めた」という終端状態なので、
     * チャットに入っていなくても見えないと「なぜ更新が来ないのか」が分からない。
     */
    val eventStatus: StateFlow<ChatEventStatus?> = _eventStatus.asStateFlow()

    init {
        // SSEは常時1本(データ層が保持)。VMはそれを購読するだけで、
        // 画面の出入りで接続を張り直さない。
        viewModelScope.launch {
            // collectGuarded: 1件の適用が投げても購読を殺さない。死ぬと接続はCONNECTEDのまま
            // イベントだけが永久に届かなくなる(EventDelivery.kt の設計注記)。
            // 配線そのものは deliverTo が持つ(そこにテストがある)。
            // ここで宛先を足したり順序に条件を付けたりしないこと — その一行が
            // Q1 レビューの変異で「全緑のまま一覧が死ぬ」形だった。
            container.events.events.deliverTo(
                onFailure = chatController::recordDeliveryFailure,
                toSessionList = sessionList::onEvent,
                toChat = chatController::onEvent,
                // Q9: `pty.exited` は終了コードの唯一の出所。**実体で渡す。**
                toPty = ptyController,
            )
        }
        viewModelScope.launch {
            container.events.status.collect { status ->
                val mapped = status?.let { s -> ChatEventStatus.valueOf(s.name) }
                _eventStatus.value = mapped
                chatController.setEventStatus(mapped)
            }
        }
        // 決定2(RUN_PLAN「Q1着手条件の裁定」): 実行状態はイベント列ではなく
        // **サーバーの現在値**で初期化する。接続が確立するたびに取り直すのは、
        // 切れている間の session.status が失われているため——イベントだけを信じると
        // 「切断中に終わった実行」が永久に『実行中』のまま残る。
        // 決定2(RUN_PLAN「Q1着手条件の裁定」): 実行状態はイベント列ではなく
        // **サーバーの現在値**で初期化する。接続が確立するたびに取り直すのは、
        // 切れている間の session.status / session.idle が失われているため——
        // イベントだけを信じると「切断中に終わった実行」が永久に『実行中』のまま残る。
        // **一覧とチャットの両方**が取り直す(Q2 レビュー major-1: チャットにだけ無かった)。
        // 配線を関数に出してあるのは、宛先を1つ落とす変異を検出できるようにするため。
        // Q4 レビュー major-1: **接続先が変わったらモデルカタログを捨てる。**
        // 古いカタログは「`GET /provider` から得た候補以外を送らない」という
        // 唯一の防波堤(API_CONTRACT.md)をそのまま抜ける —— サーバーB に
        // サーバーA のモデルIDを渡すと **204 で受理して保存される**(実測 #4)。
        //
        // 鍵は **baseUrl だけ**にする。`connected` はサーバー側の環境変数で決まるので
        // 「どのサーバーか」がカタログの同一性であり、パスワードは同一性ではない。
        // パスワードを鍵に混ぜないのは AGENTS.md(資格情報を持ち回さない)にも沿う。
        // 判定(同じ接続先なら何もしない/初回は捨てるものが無い)は Controller 側にあり
        // `runTest` で叩ける。ここは値を渡すだけで条件を持たない。
        //
        // Q5: 宛先が2つになった(カタログ + サーバー素性)ので、Q4 の裸の collect を
        // `AppWiring.kt` の **条件を1つも持たない配線関数**へ出した。
        //
        // **宛先はラムダではなく controller の実体で渡す。** 1周目は
        // `toCatalog = modelCatalog::onConnectionChanged` というラムダ引数で、
        // レビューがそれを `{ }` に変える変異(R3)を打つと **431件全緑のまま
        // Q4 の blocker(サーバーAのモデルIDをBへ送り 204 で受理される)が再オープン**した。
        // 実体で渡せば、宛先を落とす変異は wireConnectionChangesTo の中にしか書けない。
        viewModelScope.launch {
            container.connection.connection.map { it?.baseUrl }.wireConnectionChangesTo(
                catalog = modelCatalog,
                serverInfo = serverInfoController,
                diff = diffController,
                files = fileBrowser,
                pty = ptyController,
                onFailure = chatController::recordDeliveryFailure,
            )
        }
        viewModelScope.launch {
            // **宛先は全部 controller の実体で渡す**(Q8 で `toSessionList` / `toChat` の
            // ラムダを実体へ揃えた。Q7 申し送り Q7-6)。ラムダのままだと、
            // 呼び出し側が `{ }` を渡す変異が `deliverConnectionsTo` のテストから見えない。
            container.events.connectedGeneration.deliverConnectionsTo(
                onFailure = chatController::recordDeliveryFailure,
                sessions = sessionList,
                chat = chatController,
                diff = diffController,
                files = fileBrowser,
                pty = ptyController,
            )
        }
    }

    /**
     * P1本体: URL+パスワードをDataStoreへ保存し、その接続先の /global/health を呼ぶ。
     * 結果(healthy/version or 401/接続失敗)は health StateFlow経由で画面に反映する。
     */
    fun saveAndTestHealth(rawUrl: String, password: String) {
        // **正規化と保存の順序は [performSaveAndTestHealth] が持つ。** ここに
        // `Urls.normalize` を書き戻さないこと —— 1周目はここに素で書かれており、
        // レビューがこの1行を外す変異(R1)を打つと **H1a のために足した UrlsTest 6本を含む
        // 431件が全緑のまま、修正が丸ごと死んだコードになった**。
        viewModelScope.launch {
            val outcome = performSaveAndTestHealth(
                rawUrl = rawUrl,
                password = password,
                gateway = connectionRepo,
                onTesting = { _health.value = HealthUiState.Testing },
            )
            _health.value = when (outcome) {
                SaveAndTestOutcome.BadUrl -> HealthUiState.Failed(
                    HealthFailureKind.BAD_URL,
                    "URLを http://host:port の形式で入力してください",
                )
                is SaveAndTestOutcome.Measured -> when (val result = outcome.result) {
                    is ApiResult.Ok -> {
                        val v: HealthDto = result.value
                        HealthUiState.Ok(healthy = v.healthy, version = v.version)
                    }
                    is ApiResult.Err -> result.error.toHealthFailure()
                }
            }
            // 接続テストで得た版はドロワー/設定の「サーバーバージョン」にも効く。
            // **接続先が変わっていれば onConnectionChanged が先に捨てている**ので、
            // ここは新しい接続先に対して引き直すことになる。
            serverInfoController.refresh()
        }
    }

    // ---- Q5: ローカル設定(カラーモード/触覚)----

    /**
     * カラーモードを保存する。**押した瞬間に色が変わる**
     * (リポジトリがメモリ状態を先に更新する。PreferencesRepository の doc)。
     */
    fun setColorMode(mode: ColorMode) {
        viewModelScope.launch { preferencesRepo.setColorMode(mode) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepo.setHapticsEnabled(enabled) }
    }

    /** まだ取れていなければ `GET /global/health` を引く(判定は Controller 側)。 */
    fun ensureServerInfoLoaded() = serverInfoController.ensureLoaded()

    // ---- 一覧(状態機械は SessionListController が持つ。ここは委譲だけ) ----

    /**
     * 画面の再表示から呼ぶ入口。**まだ読んでいなければ読む**。
     *
     * 回転やチャットからの復帰で取り直さないのが要点。取り直すと `limit` が1ページ目へ戻り、
     * 120件を末尾まで開いていたユーザーが48件目付近へ飛ばされる(Q1レビュー major-1)。
     */
    fun ensureSessionsLoaded() = onSessionListShown(sessionList)

    /** 明示的な取り直し(再読み込みボタン・接続設定からの復帰)。 */
    fun refreshSessions(resetPaging: Boolean = true) = sessionList.refresh(resetPaging)

    /**
     * 一覧へ移る導線から**無条件に**呼ぶ。取り直すかどうかは
     * [applyEnterSessionsEffect] が決める(呼び出し側に条件を書かない)。
     */
    fun onEnterSessions(from: Screen?) = applyEnterSessionsEffect(from, sessionList)

    fun refreshSessionStatus() = sessionList.refreshStatus()

    fun loadMoreSessions() = sessionList.loadMore()

    fun setSearchOpen(open: Boolean) = sessionList.setSearchOpen(open)

    fun updateSearch(query: String) = sessionList.updateSearch(query)

    fun renameSession(sessionId: String, title: String) = sessionList.rename(sessionId, title)

    fun deleteSession(sessionId: String) = sessionList.delete(sessionId)

    fun clearActionError() = sessionList.clearActionError()

    /** 一覧のスクロール位置。画面が構成から外れても保つ(チャット往復で先頭へ飛ばさない)。 */
    val sessionScrollIndex: Int get() = sessionList.savedScrollIndex
    val sessionScrollOffset: Int get() = sessionList.savedScrollOffset

    fun saveSessionScroll(index: Int, offset: Int) = sessionList.saveScroll(index, offset)

    /**
     * P2/Q4: POST /session {title, agent?, model?} -> 成功したら返却Sessionを一覧へ即時反映。
     * 未指定(null)はキーごと送らない —— 明示 null は 400 になる(API_CONTRACT.md 実測)。
     */
    fun createSession(title: String, agent: String? = null, model: ModelRefDto? = null) =
        sessionList.create(title, agent, model)

    // ---- Q4: モデル/エージェント(状態機械は ModelCatalogController が持つ。ここは委譲だけ) ----

    /** モデル選択を開く導線から呼ぶ。**まだ取れていなければ引く**(判定は Controller 側)。 */
    fun ensureCatalogLoaded() = modelCatalog.ensureLoaded()

    fun reloadCatalog() = modelCatalog.reload()

    fun updateCatalogQuery(query: String) = modelCatalog.updateQuery(query)

    fun clearCatalogQuery() = modelCatalog.clearQuery()

    /** `POST /api/session/{id}/model`。切替後は Controller がサーバーから引き直す。 */
    fun switchChatModel(model: ModelRefDto) = chatController.switchModel(model)

    // ---- P3/Q2: チャット(状態機械は ChatController が持つ。ここは委譲だけ) ----

    fun openChat(sessionId: String, title: String? = null) = chatController.openChat(sessionId, title)

    fun closeChat() = chatController.closeChat()

    override fun retryLoadMessages() = chatController.retryLoadMessages()

    fun updateDraft(value: String) = chatController.updateDraft(value)

    fun sendDraft() = chatController.sendDraft()

    fun clearSendError() = chatController.clearSendError()

    fun clearSessionError() = chatController.clearSessionError()

    override fun dismissChatBanner(kind: ChatBannerKind) = chatController.dismissBanner(kind)

    fun replyPermission(permissionId: String, sessionId: String, response: String) =
        chatController.replyPermission(permissionId, sessionId, response)

    fun abortSession() = chatController.abortSession()

    // ---- Q3: Todo + Question(状態機械は ChatController が持つ。ここは委譲だけ) ----

    // requestId を必ず通す。**「今のカード」を暗黙に指す口を残さない** ——
    // 質問は同時に複数 pending になりうる(Q3 レビュー blocker-1)ので、
    // 引数の無い口はどれに当たるかを呼び出し側から見えなくする。
    fun toggleQuestionOption(requestId: String, questionIndex: Int, optionIndex: Int) =
        chatController.toggleQuestionOption(requestId, questionIndex, optionIndex)

    fun updateQuestionCustomText(requestId: String, questionIndex: Int, text: String) =
        chatController.updateQuestionCustomText(requestId, questionIndex, text)

    fun submitQuestion(requestId: String) = chatController.submitQuestion(requestId)

    fun rejectQuestion(requestId: String) = chatController.rejectQuestion(requestId)

    // ---- Q7: 差分 / VCS / 巻き戻し(状態機械は DiffController / ChatController が持つ)----

    /** ブランチ表示の導線から呼ぶ。**まだ取れていなければ引く**(判定は Controller 側)。 */
    fun ensureBranchLoaded() = diffController.ensureBranchLoaded()

    /**
     * ドロワーの「変更中のファイル」から呼ぶ。**毎回引く** ——
     * ブランチと違って作業ツリーは変わるものなので、
     * 「一度取れたら二度引かない」を当てると古い一覧を出し続ける。
     */
    fun refreshWorkingTree() = diffController.refreshWorkingTree()

    /** 作業ツリー差分を開く。[file] を渡すとその1ファイルだけを展開済みで出す。 */
    fun openWorkingTreeDiff(file: String? = null) = diffController.openWorkingTreeDiff(file)

    /** メッセージの差分を開く(「N ファイル変更」チップ)。 */
    override fun openMessageDiff(sessionId: String, messageId: String, knownFiles: List<String>) =
        diffController.openMessageDiff(sessionId, messageId, knownFiles)

    fun toggleDiffFile(path: String) = diffController.toggleFile(path)

    fun closeDiffViewer() = diffController.closeViewer()

    /** 差分ビューアの空状態の「再試行」。**直前と同じ要求**を出す(判定は Controller 側)。 */
    fun retryDiffViewer() = diffController.retryViewer()

    /**
     * 「ここまで戻す」の**確認を出す**。ここでは何も送らない ——
     * revert はファイルシステムを書き換えるので、確認を通らない経路を作らない。
     */
    override fun requestRevert(messageId: String, partId: String?, preview: String) =
        chatController.requestRevert(messageId, partId, preview)

    fun cancelRevert() = chatController.cancelRevert()

    /** 確認済みの巻き戻しを実行する(`POST /session/{id}/revert`)。 */
    fun confirmRevert() = chatController.confirmRevert()

    /** 「元に戻す」(`POST /session/{id}/unrevert`)。 */
    override fun unrevert() = chatController.unrevert()

    // ---- Q8: ファイルブラウザ + 検索(状態機械は FileBrowserController が持つ)----

    /** ファイル画面を開く導線から呼ぶ。**まだ読んでいなければ読む**(判定は Controller 側)。 */
    fun ensureFileTreeLoaded() = fileBrowser.ensureTreeLoaded()

    override fun openDirectory(path: String) = fileBrowser.openDirectory(path)

    override fun toggleIgnoredFiles() = fileBrowser.toggleIgnored()

    override fun retryFileTree() = fileBrowser.retryTree()

    override fun openFile(path: String, focusLine: Int?) = fileBrowser.openFile(path, focusLine)

    override fun closeFileViewer() = fileBrowser.closeViewer()

    override fun retryFileViewer() = fileBrowser.retryViewer()

    override fun toggleFileDiff() = fileBrowser.toggleDiff()

    override fun setFileSearchTab(tab: FileSearchTab) = fileBrowser.setSearchTab(tab)

    override fun updateFileSearchQuery(query: String) = fileBrowser.updateSearchQuery(query)

    override fun submitFileSearch() = fileBrowser.submitSearch()

    override fun clearFileSearch() = fileBrowser.clearSearch()

    /**
     * Q8 スコープ6: チャットのツールカードのパスから開く。
     *
     * **[openFile] と同じ宛先へ落とす。** 別経路を作ると、片方だけが
     * `directory` や打ち切り閾値を落とす形の欠陥が入りうる。
     */
    override fun openFileFromChat(path: String) = fileBrowser.openFile(path)

    // ---- Q9: ターミナル(状態機械は PtyController が持つ。ここは委譲だけ)----

    /** ターミナル画面を開く導線から呼ぶ。**まだ読んでいなければ読む**(判定は Controller 側)。 */
    fun ensurePtyLoaded() = ptyController.ensureLoaded()

    override fun refreshPtyList() = ptyController.refreshList()

    override fun startPty(command: String, title: String) = ptyController.createAndOpen(command, title)

    override fun openPty(pty: dev.opencode.android.data.PtyDto) = ptyController.open(pty)

    override fun deletePty(ptyId: String) = ptyController.deletePty(ptyId)

    override fun closeTerminal() = ptyController.closeTerminal()

    override fun sendPtyLine(text: String) = ptyController.sendLine(text)

    override fun sendPtyKey(key: PtyKey) = ptyController.sendKey(key)

    override fun reconnectPty() = ptyController.reconnect()

    override fun resizePty(rows: Int, cols: Int) = ptyController.resize(rows, cols)

    override fun clearPtyActionError() = ptyController.clearActionError()

    override fun clearPtyCreateError() = ptyController.clearCreateError()

    private fun ApiError.toHealthFailure(): HealthUiState.Failed = when (this) {
        is ApiError.Http ->
            if (code == 401) {
                HealthUiState.Failed(HealthFailureKind.AUTH, "認証エラー(401): パスワードが違います")
            } else {
                HealthUiState.Failed(HealthFailureKind.HTTP, "サーバーエラー(HTTP $code)")
            }
        is ApiError.Network ->
            HealthUiState.Failed(HealthFailureKind.NETWORK, "接続失敗: $message")
        ApiError.NotConfigured ->
            HealthUiState.Failed(HealthFailureKind.BAD_URL, "接続先が未設定です")
    }

    private fun ApiError.describe(): String = when (this) {
        is ApiError.Http ->
            if (code == 401) "認証エラー(401): パスワードが違います。設定を見直してください" else "サーバーエラー(HTTP $code)"
        is ApiError.Network -> "接続失敗: $message"
        ApiError.NotConfigured -> "接続先が未設定です。設定画面でURLを保存してください"
    }
}

@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    // 初期画面は保存済み設定で決める(null=読み込み中)。回転耐性: 画面位置自体も保存する
    // (レビュー指摘2: 設定画面編集中の回転で一覧へ飛ぶ問題)。Screenはカスタム型なので
    // 文字列キーへ相互変換してrememberSaveableする。
    var screenKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(saved) {
        if (screenKey == null && saved != null) {
            screenKey = if (saved!!.isConfigured) "sessions" else "settings"
        }
    }
    val screen: Screen? = screenKey.toScreen()

    // ---- Q5: モーダルドロワー(§5 Q5 スコープ1)----
    //
    // 計画書は「SessionListScreen に ModalNavigationDrawer」と書いているが、ここに置いた。
    // スコープ3 が「TopAppBar をハンバーガー+タイトル構成に**統一**(一覧/設定/チャット)」を
    // 求めており、一覧の内側に置くと**設定画面のハンバーガーが押しても何も出ないアイコン**になる。
    // 一覧と設定で1つのドロワーを共有する形にした(チャットは詳細画面なので戻る矢印のまま。
    // 理由は報告に書いた)。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val openDrawer: () -> Unit = { drawerScope.launch { drawerState.open() } }

    // ドロワーが出す「最近の項目」は**一覧が既に持っている行**。ここで取得を起こさない
    // (起こすとページングが1ページ目へ戻る。Q1レビュー major-1 が
    // `ensureSessionsLoaded` と `refreshSessions` を分けさせた理由がこれ)。
    val sessionsUi by viewModel.sessions.collectAsStateWithLifecycle()
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()
    // Q7: ブランチ / 作業ツリー / 差分ビューア。
    val diffUi by viewModel.diff.collectAsStateWithLifecycle()
    // Q8: ファイルツリー / ビューア / 検索。
    val filesUi by viewModel.files.collectAsStateWithLifecycle()
    // Q9: ターミナル一覧 / 端末。
    val ptyUi by viewModel.pty.collectAsStateWithLifecycle()
    val hostLabel = connectionHostLabel(saved?.baseUrl)

    // ドロワーの「新規セッション」から一覧の作成ダイアログを開くための受け渡し。
    // 回転で消えないよう rememberSaveable。
    var pendingCreate by rememberSaveable { mutableStateOf(false) }

    // ドロワーを開いたときにサーバーバージョンが「取得中…」のままにならないよう、
    // **まだ取れていなければ引く**(判定は ServerInfoController が持つ)。
    LaunchedEffect(drawerState.isOpen, saved?.isConfigured) {
        if (drawerState.isOpen) viewModel.ensureServerInfoLoaded()
        // Q7 スコープ5: 作業ツリーは**開くたびに引き直す**。ブランチと違って変わるもので、
        // 「一度取れたら二度引かない」を当てるとエージェントが編集した後も古い一覧が残る。
        if (drawerState.isOpen) viewModel.refreshWorkingTree()
    }
    // Q7 スコープ4: ブランチは**まだ取れていなければ引く**(判定は Controller 側)。
    LaunchedEffect(saved?.isConfigured) { viewModel.ensureBranchLoaded() }

    val goToSettings: () -> Unit = { screenKey = "settings" }
    val goToSessions: () -> Unit = {
        // **条件は `applyEnterSessionsEffect` の中にしか無い。** ここに `if` を書き戻さないこと ——
        // 1周目はこの行が `if (screenKey == "settings") viewModel.refreshSessions()` で、
        // レビューがガードを外す変異(R2)を打つと431件全緑のまま
        // ドロワーの「セッション一覧」がページングを1ページ目へ戻すようになった。
        viewModel.onEnterSessions(screen)
        screenKey = "sessions"
    }
    // Q8: ファイル画面へ移る導線。**ここでは取得を起こさない。**
    //
    // 以前はここで `ensureFileTreeLoaded()` を呼んでいた。**プロセスが死んで復元された
    // ときはこの導線を通らない**(`screenKey` は `rememberSaveable` なので「files」のまま
    // 起き上がる)ので、一覧を一度も引かない**真っ白なファイル画面**になっていた。
    // Q9 のターミナルで直したのと同じ欠陥で、当時ファイル側は指示の範囲外として
    // 残されていた(申し送り「Q8 の Files は手を付けていない」)。
    //
    // 実機で再現済み(emulator-5554): 一覧が出ている状態で HOME → `am kill` → 再起動すると、
    // 画面は `file-browser` に戻るのに `file-entry:*` が dump に1件も出ない。
    // 取得は画面が出ている間の `LaunchedEffect` が起こす —— そちらは復元経路でも必ず走る。
    val goToFiles: () -> Unit = {
        screenKey = "files"
    }
    // Q9: ターミナル画面へ移る導線。**ここでは取得を起こさない。**
    //
    // 1周目はここで `ensurePtyLoaded()` を呼んでいた。**プロセスが死んで復元された
    // ときはこの導線を通らない**(`screenKey` は `rememberSaveable` なので
    // 「terminal」のまま起き上がる)ので、一覧を一度も引かない**真っ白な LazyColumn**
    // になっていた(レビュー minor-4)。取得は画面が出ている間の
    // `LaunchedEffect` が起こす —— そちらは復元経路でも必ず走る。
    val goToTerminal: () -> Unit = {
        screenKey = "terminal"
    }

    when (val s = screen) {
        null -> Box(modifier = Modifier.fillMaxSize()) { /* DataStore初回読み込み中 */ }

        Screen.Settings, Screen.Sessions -> {
            val destination =
                if (s == Screen.Settings) DrawerDestination.SETTINGS else DrawerDestination.SESSIONS
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    AppDrawerContent(
                        hostLabel = hostLabel,
                        serverInfo = serverInfo,
                        selected = destination,
                        sessions = sessionsUi.items,
                        onNewSession = {
                            drawerScope.launch { drawerState.close() }
                            goToSessions()
                            pendingCreate = true
                        },
                        onOpenSessions = {
                            drawerScope.launch { drawerState.close() }
                            goToSessions()
                        },
                        onOpenSettings = {
                            drawerScope.launch { drawerState.close() }
                            goToSettings()
                        },
                        onOpenSession = { id ->
                            drawerScope.launch { drawerState.close() }
                            screenKey = "detail:$id"
                        },
                        // Q8 スコープ1: ファイルブラウザへの導線。
                        onOpenFiles = {
                            drawerScope.launch { drawerState.close() }
                            goToFiles()
                        },
                        // Q9 スコープ4: ターミナルへの導線。
                        onOpenTerminal = {
                            drawerScope.launch { drawerState.close() }
                            goToTerminal()
                        },
                        workingTree = diffUi.workingTree,
                        onOpenWorkingTreeFile = { file ->
                            drawerScope.launch { drawerState.close() }
                            viewModel.openWorkingTreeDiff(file)
                        },
                    )
                },
            ) {
                if (s == Screen.Settings) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBackToSessions = goToSessions,
                        // 未設定の初回起動ではドロワーから行ける先が無いのでハンバーガーを出さない。
                        onOpenDrawer = if (saved?.isConfigured == true) openDrawer else null,
                        // ドロワーが開いている間、画面固有の戻る導線は黙る(下の BackHandler と排他)。
                        drawerOpen = drawerState.isOpen,
                    )
                } else {
                    SessionListScreen(
                        viewModel = viewModel,
                        onOpenSettings = goToSettings,
                        onOpenSession = { id -> screenKey = "detail:$id" },
                        onOpenDrawer = openDrawer,
                        requestCreate = pendingCreate,
                        onCreateRequestConsumed = { pendingCreate = false },
                    )
                }
            }

            // **ドロワーが開いていたら BACK は閉じるだけ**(E2E 所見B)。
            // `ModalNavigationDrawer` は BACK を自前で拾わないので、これが無いと
            // 一覧画面ではそのまま Activity を抜けて**アプリが終了する**。
            //
            // `ModalNavigationDrawer` の**後ろ**に置いてあるのは、BackHandler が
            // 後から登録されたものほど先に呼ばれるため。ただし順序には頼らない ——
            // 設定画面側は `screenBackEnabled` で黙るので、両方が同時に有効になることはない。
            BackHandler(enabled = drawerBackEnabled(drawerState.isOpen)) {
                drawerScope.launch { drawerState.close() }
            }

            // Q7: 一覧/設定の面から開いた差分ビューア(ドロワーの「変更中のファイル」経由)。
            // チャットの面にも同じダイアログがあるが、**開いているのは同じ1つの状態**である
            // ([DiffController] を共有している)。
            if (diffUi.viewer.open) {
                DiffViewerDialog(
                    viewer = diffUi.viewer,
                    onClose = viewModel::closeDiffViewer,
                    onToggleFile = viewModel::toggleDiffFile,
                    onRetry = viewModel::retryDiffViewer,
                    onOpenSettings = goToSettings,
                )
            }
        }

        Screen.Terminal -> {
            // **画面が出ているあいだに取得を起こす。**(判定は Controller の `ensureLoaded`)
            // 導線側ではなくここに置くのは、プロセス復元では導線を通らないためである。
            LaunchedEffect(saved?.isConfigured) { viewModel.ensurePtyLoaded() }
            TerminalScreen(
                ui = ptyUi,
                // **宛先はラムダではなく実体**([PtyActions] の doc)。
                actions = viewModel,
                onBack = goToSessions,
                onOpenSettings = goToSettings,
            )
            // **ハードウェアの戻るで一覧へ帰る。**
            //
            // 無いと BACK が Activity まで抜けて**アプリが終了する**(実機で実測 2026-08-30)。
            // これは E2E 所見B(ドロワーで同じことが起きた)と同じ形で、
            // ドロワーの無い全画面(Q8 のファイル・Q9 のターミナル)には手当てが無かった。
            //
            // 端末を開いているときは**先に端末を閉じる**(TopAppBar の戻る矢印と同じ規則)。
            // 判定は [PtyTerminalUi.open] にあり、ここには条件を書かない。
            BackHandler {
                if (ptyUi.terminal.open) viewModel.closeTerminal() else goToSessions()
            }
        }

        Screen.Files -> {
            // **画面が出ているあいだに取得を起こす。**(判定は Controller の `ensureTreeLoaded`)
            // 導線側ではなくここに置くのは、プロセス復元では導線を通らないためである
            // (`Screen.Terminal` と同じ形)。
            LaunchedEffect(saved?.isConfigured) { viewModel.ensureFileTreeLoaded() }
            FileBrowserScreen(
                ui = filesUi,
                // **宛先はラムダではなく実体**([FileActions] の doc)。
                actions = viewModel,
                onBack = goToSessions,
                onOpenSettings = goToSettings,
            )
            // Q8 から持ち越していた欠陥(上の doc と同じ)。**BACK でアプリが終了していた。**
            BackHandler { goToSessions() }
        }

        is Screen.Detail -> {
            // 一覧から引ける間はそれを初期値にする。ただし**画面の権威はここではない** ——
            // `session.deleted` で一覧から消えると null に戻り、TopAppBar が生のIDへ退化する
            // (申し送り Q1-1 の症状)。以後のタイトルは ChatController が保持する。
            val title = sessionsUi.items
                .firstOrNull { it.id == s.sessionId }?.displayTitle
            ChatScreen(
                viewModel = viewModel,
                sessionId = s.sessionId,
                title = title,
                onBack = { screenKey = "sessions" },
                onOpenSettings = goToSettings,
            )
        }
    }

    // Q8: ファイルビューアは**画面によらずここで1か所だけ描く**。
    //
    // ファイル画面の中に置くと、チャットのツールカードから開いた経路(スコープ6)で
    // **状態は open のまま何も出ない**。ダイアログなので現在の画面の上に重なる。
    if (filesUi.viewer.open) {
        FileViewerDialog(
            viewer = filesUi.viewer,
            actions = viewModel,
            onOpenSettings = goToSettings,
        )
    }
}

private fun String?.toScreen(): Screen? = when {
    this == null || this.isEmpty() -> null
    this == "settings" -> Screen.Settings
    this == "sessions" -> Screen.Sessions
    this == "files" -> Screen.Files
    this == "terminal" -> Screen.Terminal
    this.startsWith("detail:") -> Screen.Detail(this.removePrefix("detail:"))
    else -> null
}
