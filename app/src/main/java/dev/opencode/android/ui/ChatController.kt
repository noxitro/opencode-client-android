package dev.opencode.android.ui

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ChatGateway
import dev.opencode.android.data.PermissionAskedEvent
import dev.opencode.android.data.PermissionRepliedEvent
import dev.opencode.android.data.QuestionAskedEvent
import dev.opencode.android.data.QuestionResolvedEvent
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SSE接続の状態(チャット画面の状態帯に表示)。
 *
 * **[dev.opencode.android.data.OpenCodeEvents.Status] と要素名が1対1でなければならない** —
 * 変換が `valueOf(name)` なので、片方にだけ値を足すと `IllegalArgumentException` で
 * 状態購読のコルーチンごと落ちる。UNAUTHORIZED を足したときにここを直し忘れると、
 * 「認証失敗を表示するための変更」が「接続状態が一切更新されなくなる変更」になる。
 */
enum class ChatEventStatus { CONNECTING, CONNECTED, RECONNECTING, UNAUTHORIZED }

/**
 * チャット画面の状態。メッセージ列は [ChatController] が保持するため回転で消えない
 * (プロセス死後は入室時の履歴再取得で復元する)。
 */
data class ChatUi(
    val loading: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    /** 履歴取得エラー(一覧が空の時のみ全面表示)。 */
    val error: String? = null,
    /**
     * [error] が認証失敗(401/403)由来か(Q6)。空状態の選択([chatEmptyState])が
     * 「再試行」を出すかどうかの材料にする。**文字列を見て判定しない** ——
     * 再試行の可否が文言の書き方に依存すると、文言を直した瞬間に導線が壊れる。
     */
    val errorIsAuth: Boolean = false,
    /** 送信エラー(409 SessionBusyError等)。 */
    val sendError: String? = null,
    /** session.error イベントによるエラー表示。 */
    val sessionError: String? = null,
    /** 実行中(session.idle受信まで)。送信連打防止も兼ねる。 */
    val busy: Boolean = false,
    val eventStatus: ChatEventStatus? = null,
    /** 入力下書き。保持で回転でも消さない。送信成功時のみクリアする。 */
    val draft: String = "",
    /** 承認ダイアログ表示状態。permission.asked受信でセット、permission.repliedでクリア。 */
    val permissionDialog: PermissionDialogState? = null,
    /**
     * SSEイベントの**適用**で例外が出た記録(接続の失敗ではない)。
     * null=一度も失敗していない。非nullならバナー領域に出す(EventDelivery.kt の設計注記)。
     */
    val eventDeliveryFailure: EventDeliveryFailure? = null,

    // ---- Q2 で足した分 ----

    /** `session.status(retry)` の現在値。idle/busy を受けたら null に戻す。 */
    val retry: RetryState? = null,
    /**
     * `session.deleted` をこのセッションに受けたか(申し送り Q1-1)。
     * true の間は送信できない —— 消えたセッションへ prompt を投げても 404 になるだけで、
     * 「入力欄は生きているのに何も起きない」が一番わかりにくい壊れ方だった。
     */
    val sessionDeleted: Boolean = false,
    /** abort したのに完了通知が来なかったときの注記(申し送り Q0-1)。 */
    val abortNotice: String? = null,
    /** abort を送ってから完了通知を待っている間。 */
    val aborting: Boolean = false,
    /**
     * 画面に出すタイトル。`session.updated` で追随し、`session.deleted` でも**消さない**。
     * 一覧から引くだけだと、削除されて一覧から消えた瞬間に
     * **TopAppBar が生のセッションIDへ退化する**(申し送り Q1-1 の観測された症状そのもの)。
     */
    val title: String? = null,

    // ---- Q3 で足した分 ----

    /**
     * タスクリスト(`todo.updated` / `GET /session/{id}/todo`)。
     * **`session.idle` 後も最終状態を保持する**(§5 Q3 スコープ1)——
     * 完了したタスクリストが実行終了と同時に消えると、何をやったのかが読めない。
     */
    val todos: List<TodoItem> = emptyList(),

    /**
     * 質問カード(`question.asked` / `GET /question`)。**リストである**。
     *
     * 単数だった実装を Q3 レビュー blocker-1 で差し戻された。`GET /question` は
     * "all pending question requests" を `QuestionRequest[]` で返す —— **複数 pending は
     * サーバー側の一級の概念**であり、2件目の `question.asked` が1件目を上書きすると
     * 1件目は画面からもログからも消え、**サーバーはそれを永久に待ち続ける**。
     * DTO 層は正しくリストで受けていたのに、状態層だけが1件へ潰していた。
     *
     * requestId は一意なので実質はキー付きコレクション。並び順は「届いた順」で、
     * 画面での並べ替えは [selectChatInlineCards] が持つ。
     *
     * **permission ダイアログとは独立**(§5 Q3 スコープ3)。同時に pending でも互いを消さない。
     * 応答後も**消さずに**決着状態で残す(§5 Q3 スコープ2)。
     */
    val questions: List<QuestionCardState> = emptyList(),

    // ---- Q4 で足した分 ----

    /**
     * このセッションのモデル。**権威は `GET /session/{id}`**(RUN_PLAN 決定2 の Q4 版)。
     *
     * null は「モデルが無い」ではなく「**サーバー既定に従う**」である
     * (`Session.model` は spec 上任意。API_CONTRACT.md)。表示は [modelPillLabel] が担う。
     */
    val sessionModel: dev.opencode.android.data.ModelRefDto? = null,
    /** このセッションの主エージェント。null は「サーバー既定」。 */
    val sessionAgent: String? = null,
    /**
     * `GET /session/{id}` が返した作業ディレクトリ。**モデル切替が 500 になったときの
     * 説明に使う**([modelSwitchFailureMessage])。
     *
     * 画面に常時出すためのものではない(セッションカードが既に短縮形で出している)。
     * 要るのは「500 の原因はこのパスがホストに無いことだ」と名指しするときだけである。
     */
    val sessionDirectory: String? = null,
    /** モデル切替の往復中(ピルの二度押し防止)。 */
    val switchingModel: Boolean = false,

    /**
     * 直近の `session.error` の `data.statusCode` / `data.isRetryable`(実測で載っている)。
     *
     * **`sessionError` と寿命を揃えること。** メッセージだけ消して判定材料が残ると、
     * 次のエラーで古い 402 の判定が当たる —— 「一時的な失敗なのにモデルを変えろと言う」
     * という、直したい症状の裏返しになる。
     */
    val sessionErrorStatusCode: Int? = null,
    val sessionErrorRetryable: Boolean? = null,

    // ---- Q7 で足した分(巻き戻し。§5b Q7 スコープ6)----

    /**
     * 現在の巻き戻し位置(`Session.revert.messageID`)。**null = 巻き戻していない。**
     *
     * **権威はサーバー**である(`GET /session/{id}` と revert/unrevert の応答が返す `Session`)。
     * 画面が自分で覚えると、他クライアント(TUI/CLI)の revert に追随できず、
     * 「元に戻す」が出ないまま巻き戻された状態が続く。RUN_PLAN 決定2 の Q7 における対応物で、
     * Q4 のモデルピル・Q3 の質問カードと同じ形。
     */
    val revertedMessageId: String? = null,

    /**
     * 確認ダイアログの対象。**null なら出さない。**
     *
     * revert は**ファイルシステムを書き換える**ので、確認を挟むこと自体が §5b Q7 の要求である。
     * ダイアログの有無を画面のローカル状態にしないのは、`runTest` から
     * 「確認を通らずに `revertMessage` が呼ばれない」ことを assert するため ——
     * Compose のローカル状態に置くと、その主張が実機 dump でしか立てられなくなる。
     */
    val revertConfirm: RevertConfirmState? = null,

    /** revert / unrevert の往復中(二度押し防止)。 */
    val reverting: Boolean = false,

    /** revert / unrevert の失敗(409 SessionBusyError 等)。帯に出す。 */
    val revertError: String? = null,
)

/**
 * 「ここまで戻す」の確認対象(§5b Q7 スコープ6)。
 *
 * [messageId] と [partId] はそのまま `POST /session/{id}/revert` のボディになる。
 * [preview] は確認文に出す1行で、**何が失われるかを人の言葉で**書く。
 */
data class RevertConfirmState(
    val messageId: String,
    val partId: String? = null,
    val preview: String,
)

/** abort を送ってから `session.idle` を待つ猶予(ms)。これを過ぎたら画面側で復帰させる。 */
const val ABORT_IDLE_GRACE_MS = 5_000L

/**
 * チャット画面の状態機械。**Android にも Compose にも依存しない**ので `runTest` で直接叩ける。
 *
 * 切り出した理由は [ChatGateway] の doc を参照(Q1 レビューの「検出器の穴」)。
 * ここに置いてあるのは、まさに Q1 で素通しになった種類のもの——
 * **busy の立ち下がり / バナーの選択 / abort 後の復帰 / permission ダイアログの寿命**である。
 *
 * @param describeError [ApiError] を人向けの1行にする。文言は画面側の関心なので注入する。
 * @param nowMs テストが時刻を固定するための注入口。
 * @param abortGraceMs abort 後に完了通知を待つ猶予。テストは短くする。
 * @param directory `directory` クエリに載せる作業ディレクトリ。**null = サーバーの cwd**。
 *   QUALITY_PLAN §6 が「データ層は最初から通せる形にしておくこと(UIは出さない)」と定めた口で、
 *   Q7 では常に null。**経路が無いと Q8 は口の形から作り直すことになる**(レビュー minor-5)。
 */
class ChatController(
    private val gateway: ChatGateway,
    private val scope: CoroutineScope,
    private val describeError: (ApiError) -> String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val abortGraceMs: Long = ABORT_IDLE_GRACE_MS,
    private val directory: String? = null,
) {
    private val _state = MutableStateFlow(ChatUi())
    val state: StateFlow<ChatUi> = _state.asStateFlow()

    /** 現在チャット画面で開いているセッション(null=画面外)。 */
    var activeSessionId: String? = null
        private set

    /** message.updated が part より先に届いた場合の role 保留マップ(messageId -> role)。 */
    private val pendingRoles = mutableMapOf<String, String>()

    /**
     * Q4: `message.updated` が part より先に届いた場合のメタ保留マップ
     * (messageId -> [ChatMessageMeta])。[pendingRoles] と**同じ寿命で扱う**。
     */
    private val pendingMeta = mutableMapOf<String, ChatMessageMeta>()

    /** abort 後の復帰待ち。新しい abort / idle / 退室で潰す。 */
    private var abortWatchdog: Job? = null

    /**
     * `prompt_async` の往復中か。[refreshRunState] が自分で立てた busy を消さないための札。
     * 送信直前に立て、応答(成功/失敗)で降ろす。
     */
    private var promptInFlight = false

    // ---- 入退室 ----

    /**
     * チャット画面入室: 履歴を取得し、以降のSSEイベントの宛先をこのセッションにする。
     * 同一セッションの再入室(回転など)は何もしない。
     * **SSE接続はここでは触らない** — 接続はアプリ生存中1本でデータ層が持つ(Q0 スコープ3)。
     */
    fun openChat(sessionId: String, title: String? = null) {
        if (activeSessionId == sessionId) {
            // 回転や再合成。タイトルだけは新しい方を採る(一覧が後から読めた場合)。
            if (title != null) _state.update { it.copy(title = it.title ?: title) }
            return
        }
        activeSessionId = sessionId
        pendingRoles.clear()
        pendingMeta.clear()
        abortWatchdog?.cancel()
        abortWatchdog = null
        // 接続状態と配布失敗の記録はセッションに属さないので引き継ぐ
        _state.value = ChatUi(
            loading = true,
            eventStatus = _state.value.eventStatus,
            eventDeliveryFailure = _state.value.eventDeliveryFailure,
            title = title,
        )
        loadMessages(sessionId)
        // 実行中のセッションへ入室したときに入力欄が使えるように見えてはいけない。
        // イベント列は「入室前に起きたこと」を持っていない(RUN_PLAN 決定2)。
        refreshRunState(sessionId)
        // 同じ理由で todo と未応答の質問も**サーバーの現在値**で初期化する。
        refreshTodos(sessionId)
        refreshQuestions(sessionId)
        // Q6(申し送り Q5-2): permission も**サーバーの現在値**で初期化する。
        // 入り直しで復帰しなかったのはこの1行が無かったからで、question とは違って
        // 取りこぼしを戻す口が1つも存在しなかった。
        refreshPermissions(sessionId)
        // Q4 も同じ問いに答える(RUN_PLAN 決定2)。モデル切替は `session.updated` を
        // 流さない(実測)ので、**イベント列からは復元できない**。
        refreshSessionMeta(sessionId)
    }

    /** チャット画面退室: 以降のイベントを捨てる(接続は保ったまま)。 */
    fun closeChat() {
        pendingRoles.clear()
        pendingMeta.clear()
        activeSessionId = null
        abortWatchdog?.cancel()
        abortWatchdog = null
    }

    // ---- 履歴 ----

    /** GET /session/{id}/message で履歴を取得する。 */
    fun loadMessages(sessionId: String) {
        if (!gateway.isConfigured) return
        scope.launch {
            _state.update { it.copy(loading = true, error = null, errorIsAuth = false) }
            val result = gateway.listMessages(sessionId)
            if (activeSessionId != sessionId) return@launch // すでに別画面/別セッションへ移動済み
            _state.update { state ->
                when (result) {
                    is ApiResult.Ok -> state.copy(
                        loading = false,
                        error = null,
                        errorIsAuth = false,
                        messages = initialChatMessages(result.value),
                    )
                    is ApiResult.Err -> state.copy(
                        loading = false,
                        error = describeError(result.error),
                        errorIsAuth = isAuthError(result.error),
                    )
                }
            }
        }
    }

    fun retryLoadMessages() {
        val sid = activeSessionId ?: return
        loadMessages(sid)
    }

    // ---- 接続状態 / 配布 ----

    fun setEventStatus(status: ChatEventStatus?) {
        _state.update { it.copy(eventStatus = status) }
    }

    /** [deliverTo] の `onFailure`。配布が死にかけたことを状態に出す(握り潰さない)。 */
    fun recordDeliveryFailure(t: Throwable) {
        _state.update { it.copy(eventDeliveryFailure = it.eventDeliveryFailure.plus(t)) }
    }

    /**
     * SSEが張り直された。**2つのことをする。**
     *
     * 1. **サーバーの現在値で実行状態を取り直す**(RUN_PLAN「決定2」)。
     *    切れている間に流れた `session.idle` は購読者ゼロで消えている。イベント列だけを
     *    信じると busy が永久に降りない —— 背面へ回している間に実行が終わると、
     *    復帰後は**入力欄が disabled のまま、帯は1本も出ず、画面はどこも壊れて見えない**。
     *    `sseClients` がバックグラウンドで 0 に落ちることは Q0-2 で実測済みなので、
     *    これは理屈上の話ではなく通常操作で踏む。
     *    Q1 は一覧に対してこれを実装済みで、Q2 が新設した状態機械にだけ無かった。
     * 2. 配布失敗の記録を捨てる。**前の接続で起きたこと**だから
     *    (RUN_PLAN が Q2 に設計を求めた「配布失敗帯の寿命」の片方。もう片方は「閉じる」導線)。
     */
    fun onReconnected() {
        _state.update { it.copy(eventDeliveryFailure = null) }
        val sid = activeSessionId ?: return
        refreshRunState(sid)
        // Q3 が足した状態も**同じ規則に従う**(RUN_PLAN 決定2)。とくに質問は害が大きい:
        // 切れている間に届いた `question.asked` を取り逃すと、カードが出ないまま
        // **サーバーは応答を待って止まり続ける**。todo は見た目が古くなるだけだが、
        // 「切断中に完了したタスクが pending のまま残る」のは同じ形の嘘である。
        refreshTodos(sid)
        refreshQuestions(sid)
        // 切れている間に届いた `permission.asked` は購読者ゼロで消えている。
        // question と同じ理由で、**害はこちらのほうが大きい**(モーダルなので
        // ユーザーは何を待たれているのかすら分からない)。
        refreshPermissionsOnReconnect(sid)
        // Q4: 切れている間に別クライアント(TUI/CLI)がモデルを切り替えていても、
        // `session.next.model.switched` は購読者ゼロで消えている。**しかも切替は
        // `session.updated` を流さない**(実測)ので、他の経路でも追いつけない。
        // ここで引き直さないと、ピルは古いモデルを指したまま二度と直らない。
        refreshSessionMeta(sid)
    }

    /**
     * `GET /session/{id}` を1回引いてモデル/エージェントを**置き換える**(Q4)。
     *
     * 取れなかったときは**何もしない**。空で上書きすると「既定モデル」と表示され、
     * 実際には別のモデルで動いているのに既定だと読める —— 読めなかったことを
     * 事実に化けさせる形は、このプロジェクトが繰り返し踏んできたものである。
     */
    private fun refreshSessionMeta(sessionId: String) {
        if (!gateway.isConfigured) return
        scope.launch {
            val result = gateway.getSession(sessionId)
            if (activeSessionId != sessionId) return@launch
            when (result) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        sessionModel = result.value.model,
                        sessionAgent = result.value.agent,
                        sessionDirectory = result.value.directory,
                        // タイトルも権威はここ。まだ何も分かっていなければ埋める。
                        title = it.title ?: result.value.title,
                        // Q7: 巻き戻しの現在地も**サーバーが権威**。null で上書きしてよい ——
                        // 他クライアントが unrevert したなら「元に戻す」は消えるのが正しい。
                        revertedMessageId = result.value.revert?.messageID,
                    )
                }
                is ApiResult.Err -> Unit
            }
        }
    }

    /**
     * `POST /api/session/{id}/model`(Q4 スコープ3)。**R3 の出口はここ。**
     *
     * 実測(API_CONTRACT.md 実機検証 #2): 204 が返り、`GET /session/{id}` に反映される。
     * **`session.updated` は流れない**ので、成功したら自分で引き直す ——
     * 楽観更新だけで済ませると、サーバーが別の正規化(実測: `variant` が `"default"` へ)を
     * したときに画面とサーバーが食い違う。
     *
     * 送るのは `GET /provider` から得た候補だけであること。サーバーは**値を検証せず
     * 204 で受理して保存する**(実測 #4)ので、誤った値は次の実行まで表面化しない。
     */
    fun switchModel(model: dev.opencode.android.data.ModelRefDto) {
        if (!gateway.isConfigured) return
        val sid = activeSessionId ?: return
        if (_state.value.switchingModel) return
        _state.update { it.copy(switchingModel = true, sendError = null) }
        scope.launch {
            val result = gateway.switchSessionModel(sid, model)
            if (activeSessionId != sid) return@launch
            when (result) {
                is ApiResult.Ok -> {
                    // 楽観的に当ててから引き直す。往復の間ピルが古い値のままだと
                    // 「押したのに何も起きない」に見える(P5 の教訓と同じ形)。
                    _state.update {
                        it.copy(
                            switchingModel = false,
                            sessionModel = model,
                            // モデルを変えたのだから、前のモデルの失敗はもう現在の話ではない。
                            sessionError = null,
                            sessionErrorStatusCode = null,
                            sessionErrorRetryable = null,
                        )
                    }
                    refreshSessionMeta(sid)
                }
                is ApiResult.Err -> _state.update {
                    it.copy(
                        switchingModel = false,
                        sendError = modelSwitchFailureMessage(
                            error = result.error,
                            sessionDirectory = it.sessionDirectory,
                            describe = describeError,
                        ),
                    )
                }
            }
        }
    }

    /**
     * `GET /session/{id}/todo` を1回引いてタスクリストを**置き換える**。
     *
     * 取れなかったときは**何もしない**(前の内容を残す)。空で上書きすると
     * 「タスクが無くなった」と「読めなかった」が画面上で区別できなくなる。
     */
    private fun refreshTodos(sessionId: String) {
        if (!gateway.isConfigured) return
        scope.launch {
            val result = gateway.sessionTodos(sessionId)
            if (activeSessionId != sessionId) return@launch
            when (result) {
                is ApiResult.Ok -> _state.update { it.copy(todos = result.value.toTodoItems()) }
                is ApiResult.Err -> Unit
            }
        }
    }

    /**
     * `GET /question` を1回引いて、**このセッションの**未応答質問をカードにする。
     *
     * すでに決着済みのカードは**上書きしない**。決着はローカルの操作(送信成功)でも起きるので、
     * サーバー側の反映が一拍遅れた瞬間に取り直すと、答えたばかりのカードが
     * 「回答待ち」へ巻き戻る。同一 requestId のカードを持っているときも上書きしない ——
     * 途中まで入力した選択が消えるため。
     */
    /**
     * `GET /permission` を1回引いて、**このセッションの**未応答 permission を復元する
     * (Q6 / 申し送り Q5-2)。
     *
     * ## なぜ要るのか
     *
     * `permission.asked` は SSE でしか来ない。取りこぼすと戻す口が**1つも無かった** ——
     * チャットを出て入り直しても、プロセスが死んで復帰しても、バックグラウンドから戻っても、
     * ダイアログは二度と出ない。一方**サーバーは応答を待ったまま止まり続ける**
     * (Q5 の E2E が `permissionsPending=['per_1']` を実測している)。
     *
     * Q3 は question についてこれを「**サーバーを権威にする**」で解いた。
     * permission も `GET /permission` を持っている(実測 2026-08-27、実物 serve 1.18.21 で 200 `[]`)ので、
     * **同じ手が使える**。経路ごとに例外を作らない —— 作らなかった経路が次の欠陥になる(Q3 F4)。
     *
     * 取れなかったときは**何もしない**。読めなかったことを「未応答は無い」に化けさせない。
     */
    /**
     * 再接続時の取り直し。[refreshPermissions] への**名前付きの入口**である。
     *
     * 同じ呼び出しが入室時と再接続時の2か所にあると、変異で片方だけを消したときに
     * 「どちらを消したか」がアンカーから決まらない(Q6 2周目の変異 W18 が
     * `ANCHOR count=2` で打てなかった)。**経路ごとに名前を持たせて、
     * 片方だけ落とす変異を書けるようにする** —— 書けない変異は検出できたことにならない。
     */
    private fun refreshPermissionsOnReconnect(sessionId: String) = refreshPermissions(sessionId)

    private fun refreshPermissions(sessionId: String) {
        if (!gateway.isConfigured) return
        // 取得を始める前に知っていた id(Q3 の mergePendingQuestions と同じ境界)。
        val knownBeforeFetch = setOfNotNull(_state.value.permissionDialog?.permissionId)
        scope.launch {
            val result = gateway.pendingPermissions()
            if (activeSessionId != sessionId) return@launch
            val all = (result as? ApiResult.Ok)?.value ?: return@launch
            // **セッションで絞る。** 絞らないと別セッションの承認要求がこの画面に出て、
            // 答えると別のセッションの実行が進む。
            val serverPending = all
                .filter { it.sessionID == sessionId }
                .map { permissionDialogOf(it) }
            _state.update { state ->
                state.copy(
                    permissionDialog = mergePendingPermission(
                        current = state.permissionDialog,
                        serverPending = serverPending,
                        knownBeforeFetch = knownBeforeFetch,
                    ),
                )
            }
        }
    }

    private fun refreshQuestions(sessionId: String) {
        if (!gateway.isConfigured) return
        // **取得を始める前に知っていた requestId** を控える。取得中に届いた `question.asked` を
        // 「サーバーが知らない」と誤断して畳まないための境界(mergePendingQuestions の doc)。
        val knownBeforeFetch = _state.value.questions.map { it.requestId }.toSet()
        scope.launch {
            val result = gateway.pendingQuestions()
            if (activeSessionId != sessionId) return@launch
            // 取れなかったときは**何もしない**。読めなかったことを「未応答は無い」に化けさせない。
            val all = (result as? ApiResult.Ok)?.value ?: return@launch
            // **セッションで絞る**。絞らないと別セッションの質問がこの画面に出る。
            // この行が飾りでないことは `別セッションの未応答質問は取り込まない` が見ている
            // (レビュー major-3: 以前この行を消しても284件全緑で通り抜けた)。
            val serverCards = all
                .filter { it.sessionID == sessionId }
                .mapNotNull { questionCardOf(it) }
            _state.update { state ->
                state.copy(
                    questions = mergePendingQuestions(state.questions, serverCards, knownBeforeFetch),
                )
            }
        }
    }

    /**
     * `GET /session/status` を1回引いて、このセッションの実行状態を**置き換える**。
     *
     * **キーが無い = idle**(API_CONTRACT.md の実測。`GET /session/status` は idle を返さない)。
     * 差分マージにしないのが要点で、マージすると切れている間に idle へ落ちた実行が
     * 古い busy を保ったまま残る —— まさに直したい症状そのものになる。
     *
     * 取れなかったときは**何もしない**。バッジが出ないより、勝手に idle へ倒して
     * 実行中の入力欄を開けるほうが悪い。
     */
    private fun refreshRunState(sessionId: String) {
        if (!gateway.isConfigured) return
        scope.launch {
            val result = gateway.sessionStatus()
            if (activeSessionId != sessionId) return@launch
            // 送信の往復中は当てない: prompt を投げた直後はサーバーがまだ busy を
            // 立てていないことがあり、そこで idle を当てると自分で立てた busy を自分で消す。
            if (promptInFlight) return@launch
            when (result) {
                is ApiResult.Ok ->
                    // **質問は畳まない**(expireQuestion = false)。
                    // 2026-08-27、実機で踏んだ欠陥: プロセス死の間に届いた `question.asked` を
                    // `GET /question` で復元した直後、この経路の idle が `finishRun()` を呼んで
                    // **復元したばかりのカードを EXPIRED にした**。サーバーが未応答として
                    // 返している質問は、定義上まだ答えられる。
                    // 「実行中か」を答えるこの取り直しは、「この質問の実行が終わったか」を
                    // 知らない —— 知っているのは同じ取り直しの中で走る [refreshQuestions] のほうである。
                    // 質問の寿命を決めるのは**イベント**(`session.idle` / `session.error` /
                    // `session.deleted` / abort の見張り)だけにする。
                    applyStatus(result.value[sessionId] ?: SessionStatusDto(type = "idle"), expireQuestion = false)
                is ApiResult.Err -> Unit
            }
        }
    }

    // ---- SSE ----

    /**
     * チャット画面に効くイベント。**開いていなければ何もしない。**
     *
     * この早期 return があるので、この関数は [deliverTo] から**無条件に**呼ばれること。
     * 呼び出し側に条件を書くと、Q1 レビューが打った「1行動かすだけで一覧が死ぬ」変異が復活する。
     *
     * @return このイベントをチャットが使ったか(テストと計測のため。呼び出し側は分岐しない)
     */
    fun onEvent(event: SseEvent): Boolean {
        val sid = activeSessionId ?: return false
        return when (event) {
            is SseEvent.PartUpdated -> {
                applyToMessages(event, sid)
                true
            }
            is SseEvent.MessageUpdated -> {
                applyToMessages(event, sid)
                true
            }
            is SseEvent.SessionIdle ->
                if (event.sessionID == sid) {
                    finishRun()
                    true
                } else false

            is SseEvent.SessionError ->
                if (event.sessionID == null || event.sessionID == sid) {
                    // 実行継続が不可能なケースのためbusyも解除する(idleが来ない失敗で入力欄を閉じさせない)
                    // Defect G: errorフィールドがあればそれを表示、なければ汎用メッセージ
                    val errorMsg = event.error?.ifBlank { null } ?: "セッションエラーが発生しました"
                    abortWatchdog?.cancel()
                    _state.update {
                        it.copy(
                            sessionError = errorMsg,
                            // Q4: 「そのモデルが落ちている」を機械的に判定する材料(R3)。
                            // **メッセージと同時に置く**。片方だけ更新すると、
                            // 新しいエラーに古い 402 の判定が当たる。
                            sessionErrorStatusCode = event.statusCode,
                            sessionErrorRetryable = event.retryable,
                            busy = false,
                            aborting = false,
                            retry = null,
                            // 失敗した実行の承認要求はもう応答先が無い。抱えたまま残さない。
                            // ただし**楽観的な推測**なので、直後にサーバーへ確認する(Q6)。
                            permissionDialog = null,
                            // 質問は**消さずに**押せなくする(何を聞かれたかは残す)。
                            questions = it.expireQuestions(),
                        )
                    }
                    reconcileQuestionsAfterExpiry()
                    reconcilePermissionsAfterExpiry()
                    true
                } else false

            is SseEvent.SessionStatusChanged ->
                if (event.sessionID == sid) {
                    applyStatus(event.status)
                    true
                } else false

            is SseEvent.SessionInfoChanged -> applySessionInfo(event, sid)

            // Q4: `session.next.model.switched` / `session.next.agent.switched`。
            // **表示を追随させるだけ**で、権威は `GET /session/{id}` のまま
            // (このイベントは切れている間の分が失われるので、状態の出所にはできない)。
            // 片方しか載っていないイベントで**もう片方を消さない** —— agent 切替の
            // イベントで model を null にすると、ピルが「既定モデル」へ嘘をつく。
            is SseEvent.SessionNextChanged ->
                if (event.sessionID == sid) {
                    _state.update {
                        it.copy(
                            sessionModel = event.model ?: it.sessionModel,
                            sessionAgent = event.agent ?: it.sessionAgent,
                        )
                    }
                    true
                } else false

            is SseEvent.ServerConnected -> false

            is PermissionAskedEvent ->
                if (event.sessionID == null || event.sessionID == sid) {
                    _state.update { it.copy(permissionDialog = applyPermissionEvent(event)) }
                    true
                } else false

            is PermissionRepliedEvent ->
                if (event.sessionID == null || event.sessionID == sid) {
                    _state.update { it.copy(permissionDialog = clearPermissionDialog(event)) }
                    true
                } else false

            // Q3: `todo.updated`。properties の required は {sessionID, todos} の両方。
            // **`sessionID` が一致しないものは捨てる**(質問より厳しい)。理由は
            // QuestionAskedEvent 側のコメントに書いた —— todo には ID が無いので
            // 取り違えたときに直す手掛かりが画面に無く、取りこぼしは
            // `GET /session/{id}/todo` で必ず直る。誤りの直し方が非対称なので賭け方も非対称にする。
            is SseEvent.TodoUpdated ->
                if (event.sessionID == sid) {
                    _state.update { it.copy(todos = event.todos.toTodoItems()) }
                    true
                } else false

            // Q3: `question.asked`。**permission と独立**なので permissionDialog を触らない
            // (§5 Q3 スコープ3。片方を出すときにもう片方を消す実装が、同時 pending を壊す形)。
            is QuestionAskedEvent ->
                // `sessionID` は spec 上 required だが**欠けても受ける**。todo と賭け方が逆なのは
                // **取り返しの付き方が違う**から(レビュー minor-5):
                //  - 質問を取りこぼすと**サーバーが応答を待って止まる**。一方、別セッションの質問を
                //    この画面に出しても `POST /question/{requestID}/reply` の requestID は**全体で一意**
                //    なので、応答は正しい質問へ届く。誤表示は見た目の問題に留まる
                //  - todo は逆で、リストに ID が無い。取り違えると**別セッションのタスクを
                //    このセッションのものとして表示**し、直す手掛かりが画面に無い。取りこぼしのほうは
                //    `GET /session/{id}/todo` で必ず直る
                // つまり「厳しい/緩い」を決めているのは required ではなく**誤りの直し方**である。
                if (event.sessionID == null || event.sessionID == sid) {
                    val card = questionCardOf(event)
                    if (card == null) false else {
                        _state.update { state ->
                            // 同じ質問が再送されても入力中の選択を消さない。
                            // **既存を置き換えず、知らない requestId だけ足す** ——
                            // 2件目の質問で1件目を潰したのが blocker-1 の実体だった。
                            if (state.questions.any { it.requestId == card.requestId }) state
                            else state.copy(questions = state.questions + card)
                        }
                        true
                    }
                } else false

            // Q3: `question.replied` / `question.rejected`。**`id` ではなく `requestID`**。
            is QuestionResolvedEvent ->
                if (event.sessionID == null || event.sessionID == sid) {
                    resolveQuestion(event)
                } else false

            // Q9: `pty.*`。**チャットには効かない。** ターミナルは別の状態機械が持つ
            // ([PtyController])。ここで受けているのは、`else` を書かずに
            // 「見た上で使わない」と宣言するためである(下のコメント)。
            is SseEvent.PtyLifecycle -> false

            // 未知typeは破棄(API_CONTRACT.md「未知のtypeは無視して破棄する」)。
            // `else` は書かない: SseEvent は sealed なので、実装を足したときに
            // **ここがコンパイルエラーになる**ほうがよい(黙って無視されるより)。
            is SseEvent.Ignored -> false
        }
    }

    /**
     * `question.replied` / `question.rejected` でカードを決着させる。**消さずに残す。**
     *
     * `requestID` が**欠けていても捨てない**: 現在 pending のカードを対象にフォールバックする。
     * P4 の `PermissionRepliedEvent` は「id だけの replied は Ignored であるべき」という
     * テストを持っていて、**実際に来るのがまさにその形**だった。
     * 同じ賭けをしないために、ここは「requestID が一致する」も「requestID が無い」も通す。
     * 一致しない requestID だけを弾く —— それは別の質問の決着である。
     */
    private fun resolveQuestion(event: QuestionResolvedEvent): Boolean {
        val target = targetOfResolution(event) ?: return false
        val resolution = when (event.kind) {
            QuestionResolvedEvent.Kind.REJECTED -> QuestionResolution.REJECTED
            QuestionResolvedEvent.Kind.REPLIED -> QuestionResolution.ANSWERED
        }
        updateQuestion(target.requestId) { card ->
            card.copy(
                resolution = resolution,
                submitting = false,
                // イベントが `answers` を持っていればそれを正とする(サーバーが受理した内容)。
                // 空なら自分が送った控えを残す。
                submittedAnswers = event.answers.ifEmpty { card.submittedAnswers },
            )
        }
        return true
    }

    /**
     * 決着イベントがどのカードを指しているか。
     *
     * `requestID` があればそれが答え(**`id` ではない**。spec の実測)。
     * **欠けていても捨てない**のが P4 の教訓 —— 「id だけの replied は Ignored であるべき」という
     * テストが存在したのに、実際に来るのがまさにその形だった。
     * ただし**未応答が2件以上あるときは推測しない**: どちらを畳むかを当てずっぽうで決めると、
     * 答えていない質問が「回答済み」になり、答えた質問が待ち続ける —— 取りこぼしより悪い。
     */
    private fun targetOfResolution(event: QuestionResolvedEvent): QuestionCardState? {
        val cards = _state.value.questions
        event.requestID?.let { id -> return cards.firstOrNull { it.requestId == id } }
        val pending = cards.filter { it.resolution == QuestionResolution.PENDING || it.submitting }
        return pending.singleOrNull()
    }

    /** requestId で1枚だけ差し替える。**カードを取り違えないための唯一の入口**(レビュー minor-4)。 */
    private fun updateQuestion(requestId: String, transform: (QuestionCardState) -> QuestionCardState) {
        _state.update { state ->
            state.copy(
                questions = state.questions.map { if (it.requestId == requestId) transform(it) else it },
            )
        }
    }

    // ---- Q3: 質問カードの操作 ----

    fun toggleQuestionOption(requestId: String, questionIndex: Int, optionIndex: Int) {
        updateQuestion(requestId) { card ->
            if (card.isInteractive()) card.toggleOption(questionIndex, optionIndex) else card
        }
    }

    fun updateQuestionCustomText(requestId: String, questionIndex: Int, text: String) {
        updateQuestion(requestId) { card ->
            if (card.isInteractive()) card.updateCustomText(questionIndex, text) else card
        }
    }

    /**
     * `POST /question/{requestID}/reply`。
     *
     * **カードは送信直後に閉じない。** `question.replied` の到着で決着させる(permission と同じ設計)。
     * ただし往復中は [QuestionCardState.submitting] を立てて二度押しを止める。
     * 失敗したら submitting を降ろして**既存の送信エラー帯**へ出す ——
     * §5 Q2 が「帯は1本」と決めた領域なので、**4本目を足さない**。
     *
     * 応答は必ず [updateQuestion] を通す(レビュー minor-4)。以前は `_state.value.question` を
     * 更新の**外**で1度読んで、その参照へ書き戻していた。質問が複数持てるようになると、
     * 往復の間に別の質問が届いただけで **`ANSWERED` と `submittedAnswers` が別のカードに乗る**。
     */
    fun submitQuestion(requestId: String) {
        if (!gateway.isConfigured) return
        val card = _state.value.questions.firstOrNull { it.requestId == requestId } ?: return
        if (!canSubmitQuestion(card)) return
        val answers = card.buildAnswers()
        updateQuestion(requestId) { it.copy(submitting = true, submittedAnswers = answers) }
        _state.update { it.copy(sendError = null) }
        scope.launch {
            when (val result = gateway.replyQuestion(requestId, answers)) {
                is ApiResult.Ok ->
                    // 決着は `question.replied` を待つのが本筋だが、**待つだけにしない**。
                    // 実物での発火を観測できていない(402)以上、「イベントが来なければ
                    // 永久に回答待ちのまま」という賭けはできない。200 が返った時点で
                    // サーバーは受理しているので、ローカルでも決着させる。
                    // 後から replied が来ても resolveQuestion は冪等。
                    updateQuestion(requestId) {
                        it.copy(submitting = false, resolution = QuestionResolution.ANSWERED)
                    }
                is ApiResult.Err -> {
                    updateQuestion(requestId) { it.copy(submitting = false) }
                    _state.update {
                        it.copy(sendError = "質問への回答に失敗しました: ${describeError(result.error)}")
                    }
                }
            }
        }
    }

    /** `POST /question/{requestID}/reject`。[submitQuestion] と同じ扱い。 */
    fun rejectQuestion(requestId: String) {
        if (!gateway.isConfigured) return
        val card = _state.value.questions.firstOrNull { it.requestId == requestId } ?: return
        if (!card.isInteractive()) return
        updateQuestion(requestId) { it.copy(submitting = true) }
        _state.update { it.copy(sendError = null) }
        scope.launch {
            when (val result = gateway.rejectQuestion(requestId)) {
                is ApiResult.Ok -> updateQuestion(requestId) {
                    it.copy(submitting = false, resolution = QuestionResolution.REJECTED)
                }
                is ApiResult.Err -> {
                    updateQuestion(requestId) { it.copy(submitting = false) }
                    _state.update {
                        it.copy(sendError = "質問の拒否に失敗しました: ${describeError(result.error)}")
                    }
                }
            }
        }
    }

    /**
     * 実行が終わったのに未応答の質問が残っていたら、**押せない状態にして残す**。
     *
     * 消さないのは「何を聞かれたか」が唯一の手掛かりだから。押せなくするのは、
     * 応答先を失った要求にボタンだけ生きているのが**一番わかりにくい壊れ方**だから ——
     * P4 の permission ダイアログで実際に起きた形である(abort しても
     * `permission.replied` は流れず、ダイアログが残った)。
     *
     * **これは不可逆ではない。** `GET /question` がまだ未応答と言っているなら
     * [mergePendingQuestions] が `PENDING` へ戻す —— サーバーが権威である(レビュー blocker-2)。
     * 失効させる側だけを持っていた実装では、`session.error` を1回受けたカードが
     * 再入室でも再接続でも二度と押せなかった。
     */
    private fun ChatUi.expireQuestions(): List<QuestionCardState> = expireAllPendingQuestions(questions)

    /**
     * 失効させた直後に **`GET /question` を引き直す**(E2E 所見 F4)。
     *
     * 失効は**楽観的な推測**にすぎない。「この実行が終わった」ことは
     * 「この質問はもう答えられない」ことを意味しない —— サーバーがまだ未応答として
     * 持っていることがある(`GET /question` が権威。API_CONTRACT.md)。
     *
     * 直す症状: `session.idle` で失効したカードは、SSE が繋がったままだと
     * `connectedGeneration` が動かず、取り直しの契機が**再入室かSSE再接続しか無かった**。
     * 画面は「回答しないまま実行が終了しました」と言い、サーバーは待ち続け、
     * **脱出方法が画面から発見できない**。E2E は計画書のゲート文言(「再接続または再入室」)に
     * 照らして合格としたが、失効の直前/直後に引けば済む話である。
     *
     * 楽観更新を先に当てるのは残す: 取り直しには往復があり、その間ボタンが生きていると
     * 応答先を失った要求に投げてしまう。**先に閉じ、サーバーが違うと言えば開き直す。**
     * 4つの失効経路(`session.idle` / `session.error` / `session.deleted` / abort見張り)
     * すべてで同じ扱いにする —— 経路ごとに例外を作ると、作らなかった経路が次の欠陥になる。
     */
    private fun reconcileQuestionsAfterExpiry() {
        val sid = activeSessionId ?: return
        refreshQuestions(sid)
    }

    /**
     * permission を畳んだ直後にサーバーへ確認しに行く(Q6 / 申し送り Q5-2)。
     *
     * Q3 が質問について言語化したことがそのまま当てはまる ——
     * **「失効は楽観的な推測にすぎず、『この実行が終わった』は『この承認要求はもう
     * 応答できない』を意味しない」**。permission には `GET /permission` という権威が
     * あることが Q6 で分かったので、質問と**同じ形**にする。
     * 経路ごとに例外を作らない(作らなかった経路が次の欠陥になる)。
     */
    private fun reconcilePermissionsAfterExpiry() {
        val sid = activeSessionId ?: return
        refreshPermissions(sid)
    }

    private fun applyToMessages(event: SseEvent, sid: String) {
        _state.update { state ->
            val result = applySseToMessages(state.messages, event, sid, pendingRoles.toMap(), pendingMeta.toMap())
            pendingRoles.clear()
            pendingRoles.putAll(result.pendingRoles)
            // Q4: メタの保留も role と同じ寿命で持ち回す。片方だけ捨てると、
            // part より先に届いた providerID/modelID が黙って消える。
            pendingMeta.clear()
            pendingMeta.putAll(result.pendingMeta)
            state.copy(messages = result.messages)
        }
    }

    /**
     * 実行が終わった(`session.idle`)。**abort の後始末もここに1本化する。**
     *
     * 申し送り Q0-1 の実体は2つあった:
     *  - busy が降りない → 実物 serve は abort で `session.idle` を流す(2026-08-27 実測)。
     *    降りなかったのは**スタブが流していなかった**から。実物では降りる
     *  - permission ダイアログが残る → こちらは**実物でも残る**。abort しても
     *    `permission.replied` は流れない(同実測)。実行が終わった時点で、
     *    未応答の承認要求は応答先を失っているので、ここで畳む
     */
    /**
     * 実行が終わったときの後始末。
     *
     * @param expireQuestion **イベントで終わったのか、取り直しで「実行中でない」と読めただけか**。
     *   `false` は後者(`GET /session/status` 由来)で、その取り直しは
     *   「この質問/この承認要求の実行が終わったか」を知らない。したがって
     *   **未応答のものを畳まない**。Q3 が質問について実機で踏んだ欠陥
     *   (復元した直後のカードを EXPIRED にした)と同じ形が permission にもあり、
     *   Q6 でそちらも `false` の側へ揃えた。
     */
    private fun finishRun(expireQuestion: Boolean = true) {
        abortWatchdog?.cancel()
        abortWatchdog = null
        _state.update {
            it.copy(
                busy = false,
                aborting = false,
                retry = null,
                // Q6: 取り直し由来(expireQuestion=false)では**畳まない**。
                // 質問とまったく同じ理由 —— 「実行中か」を答えるポーリングは
                // 「この承認要求がもう応答できないか」を知らない。
                permissionDialog = if (expireQuestion) null else it.permissionDialog,
                // **todos は消さない**(§5 Q3 スコープ1「session.idle 後も最終状態を保持」)。
                // 未応答のまま終わった質問は押せなくして残す。
                questions = if (expireQuestion) it.expireQuestions() else it.questions,
                // 猶予切れで出した注記は「完了通知が**届いていない**」と言っている。
                // 遅れて届いたなら、その主張はもう嘘なので消す(レビュー minor-3)。
                abortNotice = null,
            )
        }
        if (expireQuestion) {
            reconcileQuestionsAfterExpiry()
            // **畳んだあとにサーバーへ聞き直す。** `session.idle` の時点でまだ pending な
            // 承認要求はサーバーが返してくるので、消したものが実は生きていたなら戻る。
            reconcilePermissionsAfterExpiry()
        }
    }

    /**
     * `session.status`。**`type` の3値ごとに違う結末を持つ**(§5 Q2 スコープ6)。
     *
     * `idle` は [finishRun] へ寄せる —— 実物 serve は `session.status{idle}` と
     * `session.idle` を**両方**流す(2026-08-27 実測)。片方だけ効く実装にすると、
     * どちらか一方しか流さないサーバー/スタブで復帰しなくなる。
     *
     * 未知の `type` では**実行状態を動かさない**。`retry` だけは畳む ——
     * 「再試行中」と出したまま別の状態へ移るほうが害が大きい。
     */
    private fun applyStatus(status: SessionStatusDto?, expireQuestion: Boolean = true) {
        when (status?.type) {
            "retry" -> _state.update {
                it.copy(
                    retry = RetryState(
                        attempt = status.attempt,
                        message = status.message,
                        next = status.next,
                        actionTitle = status.action?.title,
                        actionLabel = status.action?.label,
                        actionMessage = status.action?.message,
                    ),
                    // retry は「まだ続いている」。busy を降ろさない
                    busy = true,
                )
            }
            "busy" -> _state.update { it.copy(retry = null, busy = true) }
            "idle" -> finishRun(expireQuestion = expireQuestion)
            else -> _state.update { it.copy(retry = null) }
        }
    }

    /**
     * `session.created` / `session.updated` / `session.deleted`(申し送り Q1-1)。
     *
     * 一覧は Q1 で正しく反応していたが、**開いているチャットは無反応**だった。
     * 遷移も通知も無く、タイトルだけが生のIDへ退化し、入力欄は生きたまま残る。
     */
    private fun applySessionInfo(event: SseEvent.SessionInfoChanged, sid: String): Boolean {
        if (event.sessionID != sid) return false
        return when (event.kind) {
            SseEvent.SessionInfoChanged.Kind.DELETED -> {
                abortWatchdog?.cancel()
                abortWatchdog = null
                _state.update {
                    it.copy(
                        sessionDeleted = true,
                        busy = false,
                        aborting = false,
                        retry = null,
                        permissionDialog = null,
                        // 消えたセッションの質問にはもう応答できない。押せなくして残す。
                        questions = it.expireQuestions(),
                        // `session.deleted` の info は**削除前**の Session(API_CONTRACT.md 実測)。
                        // 消える直前のタイトルが最後の手掛かりなので、ここで確定させる。
                        title = event.info?.displayTitle ?: it.title,
                    )
                }
                reconcileQuestionsAfterExpiry()
                true
            }
            SseEvent.SessionInfoChanged.Kind.UPDATED -> {
                val newTitle = event.info?.displayTitle ?: return false
                _state.update { it.copy(title = newTitle) }
                true
            }
            SseEvent.SessionInfoChanged.Kind.CREATED -> false
        }
    }

    // ---- 送信 ----

    fun updateDraft(value: String) {
        _state.update { it.copy(draft = value) }
    }

    /**
     * POST /session/{id}/prompt_async。busy中は再送しない(連打防止。
     * 契約上409 SessionBusyErrorがあるため二重ガード)。
     *
     * Defect E対策: ローカルエコーをAPI呼び出し**前**に挿入する。
     * 以前はAPI成功後に挿入していたが、SSEイベント(message.part.updated等)が
     * APIレスポンスより先に到着すると、マージ対象のローカルエコーが存在せず、
     * サーバー側メッセージがrole=nullで新規作成され「unknown」表示→後からローカルエコーが
     * 追加されて二重表示になる。先に挿入しておけば mergeWithLocalEchoIfNeeded が
     * 正しく動作し重複を防げる。API失敗時は挿入したローカルエコーをmessageIdで除去する。
     */
    fun sendDraft() {
        if (!gateway.isConfigured) return
        val sid = activeSessionId ?: return
        val text = _state.value.draft.trim()
        if (!canSend(_state.value) || text.isEmpty()) return

        val localEchoId = "local-echo-${nowMs()}"
        val localEcho = ChatMessage(
            messageId = localEchoId,
            role = "user",
            parts = listOf(ChatPart(partId = null, type = "text", text = text, toolLabel = null)),
            isLocalEcho = true,
        )
        _state.update { state ->
            state.copy(
                busy = true,
                draft = "",
                messages = state.messages + localEcho,
                sendError = null,
                abortNotice = null,
            )
        }

        promptInFlight = true
        scope.launch {
            val result = gateway.sendPrompt(sid, text)
            promptInFlight = false
            when (result) {
                is ApiResult.Ok -> Unit // ローカルエコーはSSEマージで実メッセージに置換される
                is ApiResult.Err -> _state.update { state ->
                    val httpCode = (result.error as? ApiError.Http)?.code
                    val messagesWithoutEcho = state.messages.filter { it.messageId != localEchoId }
                    state.copy(
                        busy = false,
                        draft = text, // 入力内容を復元
                        messages = messagesWithoutEcho,
                        sendError = if (httpCode == 409) {
                            "セッションが実行中です(409)。完了までお待ちください"
                        } else {
                            describeError(result.error)
                        },
                    )
                }
            }
        }
    }

    fun clearSendError() = _state.update { it.copy(sendError = null) }

    /**
     * `session.error` の帯を閉じる。**判定材料も一緒に捨てる**(Q4)。
     * メッセージだけ消すと、次のエラーに古い `statusCode`/`isRetryable` が当たる。
     */
    fun clearSessionError() = _state.update {
        it.copy(sessionError = null, sessionErrorStatusCode = null, sessionErrorRetryable = null)
    }

    fun clearAbortNotice() = _state.update { it.copy(abortNotice = null) }

    fun clearDeliveryFailure() = _state.update { it.copy(eventDeliveryFailure = null) }

    /**
     * バナーを閉じる。**どの帯が出ているかを UI に判断させない** ——
     * 選んだのは [selectChatBanner] なので、閉じるのも kind で受ける。
     */
    fun dismissBanner(kind: ChatBannerKind) {
        when (kind) {
            ChatBannerKind.SEND_ERROR -> clearSendError()
            ChatBannerKind.SESSION_ERROR -> clearSessionError()
            ChatBannerKind.ABORT_NOTICE -> clearAbortNotice()
            ChatBannerKind.DELIVERY_FAILURE -> clearDeliveryFailure()
            ChatBannerKind.REVERT_ERROR -> clearRevertError()
            // REVERTED は閉じられない。**閉じても巻き戻された事実は消えない**ので、
            // 閉じる導線を出すと「元に戻す」への唯一の入口が消える。
            // 消えるのは unrevert したときと、サーバーが revert を持たなくなったときだけ。
            else -> Unit // 閉じられない種類(消えた/認証/再試行/接続中/巻き戻し中)
        }
    }

    // ---- Q7: 巻き戻し(§5b Q7 スコープ6)----

    /**
     * 「ここまで戻す」の確認を出す。**ここでは何も送らない。**
     *
     * revert はファイルシステムを書き換えるので、確認を通らない経路を作らないこと。
     * `runTest` は「[requestRevert] だけでは `gateway.revertMessage` が呼ばれない」を
     * assert できる —— 確認ダイアログを Compose のローカル状態に置くと、その主張は
     * 実機 dump でしか立てられなくなる。
     */
    fun requestRevert(messageId: String, partId: String? = null, preview: String) {
        _state.update {
            it.copy(revertConfirm = RevertConfirmState(messageId, partId, preview), revertError = null)
        }
    }

    /** 確認を取り消す。 */
    fun cancelRevert() {
        _state.update { it.copy(revertConfirm = null) }
    }

    /**
     * 確認済みの巻き戻しを実行する。`POST /session/{id}/revert {messageID, partID?}`。
     *
     * **確認状態が無ければ何もしない。** 「押したときの引数」ではなく
     * 「状態に置かれた確認」を送るので、確認を飛ばす配線を書いても送信内容が作れない。
     *
     * 応答は `Session` なので、巻き戻しの現在地は**サーバーの返した値**で更新する
     * (楽観更新しない)。409 = `SessionBusyError`(実行中は巻き戻せない)は帯に出す。
     */
    fun confirmRevert() {
        val sid = activeSessionId ?: return
        val confirm = _state.value.revertConfirm ?: return
        if (_state.value.reverting) return
        if (!gateway.isConfigured) return
        _state.update { it.copy(reverting = true, revertConfirm = null, revertError = null) }
        scope.launch {
            val result = gateway.revertMessage(sid, confirm.messageId, confirm.partId, directory)
            if (activeSessionId != sid) return@launch
            _state.update { cur ->
                when (result) {
                    is ApiResult.Ok -> cur.copy(
                        reverting = false,
                        revertedMessageId = result.value.revert?.messageID,
                        revertError = null,
                    )
                    is ApiResult.Err -> cur.copy(
                        reverting = false,
                        revertError = describeError(result.error),
                    )
                }
            }
            // 巻き戻すと履歴そのものが変わる(サーバーがメッセージを落とす)。
            // **イベントで追いつけると仮定しない** —— `session.revert` の SSE は
            // 実機 spec に存在しないので、引き直さなければ画面は古い履歴を出し続ける。
            if (result is ApiResult.Ok) loadMessages(sid)
        }
    }

    /**
     * 「元に戻す」。`POST /session/{id}/unrevert` -> `Session`。
     *
     * 確認は要らない —— **巻き戻しを取り消す方向は、失われるものが無い**。
     * 危険なのは片方向だけであり、両方に確認を付けると確認が意味を失う。
     */
    fun unrevert() {
        val sid = activeSessionId ?: return
        if (_state.value.reverting) return
        if (!gateway.isConfigured) return
        _state.update { it.copy(reverting = true, revertError = null) }
        scope.launch {
            val result = gateway.unrevertSession(sid, directory)
            if (activeSessionId != sid) return@launch
            _state.update { cur ->
                when (result) {
                    is ApiResult.Ok -> cur.copy(
                        reverting = false,
                        revertedMessageId = result.value.revert?.messageID,
                        revertError = null,
                    )
                    is ApiResult.Err -> cur.copy(
                        reverting = false,
                        revertError = describeError(result.error),
                    )
                }
            }
            if (result is ApiResult.Ok) loadMessages(sid)
        }
    }

    fun clearRevertError() {
        _state.update { it.copy(revertError = null) }
    }

    // ---- permission / abort ----

    /**
     * 承認ダイアログの応答を送信する。response: "once" | "always" | "reject"。
     * 送信後はダイアログを閉じず、permission.replied イベント到着で閉じる。
     */
    fun replyPermission(permissionId: String, sessionId: String, response: String) {
        if (!gateway.isConfigured) return
        scope.launch {
            gateway.replyPermission(sessionId, permissionId, response)
            // 失敗しても閉じない: permission.replied 待ちのまま残す(P4 の設計)
        }
    }

    /**
     * 実行中セッションを中断する。POST /session/{id}/abort。
     *
     * **実測(2026-08-27、実物 serve 1.18.21)**: abort を受けると serve は
     * `session.status{idle}` と `session.idle` を流す。つまり通常経路では
     * [finishRun] が呼ばれて busy も承認ダイアログも畳まれる。
     *
     * それでも見張りを置くのは、**完了通知が来ない実装/経路が現に存在する**ため
     * (e2e-stub は abort でシーケンスを黙って止めるだけで何も流さない ——
     * 申し送り Q0-1 が観測したのはこの経路である)。
     * [abortGraceMs] 経っても busy のままなら画面側で復帰させ、
     * **黙って直すのではなく「完了通知が来なかった」と出す**。
     */
    fun abortSession() {
        if (!gateway.isConfigured) return
        val sid = activeSessionId ?: return
        if (!_state.value.busy) return
        _state.update { it.copy(aborting = true, abortNotice = null) }
        scope.launch {
            when (val result = gateway.abortSession(sid)) {
                is ApiResult.Ok -> startAbortWatchdog(sid)
                is ApiResult.Err -> _state.update {
                    it.copy(aborting = false, sendError = "中断に失敗しました: ${describeError(result.error)}")
                }
            }
        }
    }

    private fun startAbortWatchdog(sid: String) {
        abortWatchdog?.cancel()
        abortWatchdog = scope.launch {
            delay(abortGraceMs)
            if (activeSessionId != sid) return@launch
            if (!_state.value.busy) return@launch
            _state.update {
                it.copy(
                    busy = false,
                    aborting = false,
                    retry = null,
                    permissionDialog = null,
                    questions = it.expireQuestions(),
                    abortNotice = "中断しました(サーバーからの完了通知は届いていません)",
                )
            }
            reconcileQuestionsAfterExpiry()
        }
    }

    /** テスト用: 見張りが走っているか。 */
    internal val hasAbortWatchdogForTest: Boolean get() = abortWatchdog?.isActive == true
}

/**
 * 送信できる状態か。**入力欄と送信ボタンの両方がこれを見る。**
 *
 * 実行中(busy)に加えて、**削除済みセッション**でも送れない(申し送り Q1-1)。
 * 消えたセッションへ prompt を投げても 404 になるだけで、
 * 「押せるのに何も起きない」が一番わかりにくい壊れ方だった。
 */
fun canSend(state: ChatUi): Boolean = !state.busy && !state.sessionDeleted
