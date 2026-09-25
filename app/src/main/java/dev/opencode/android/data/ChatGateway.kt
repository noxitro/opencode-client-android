package dev.opencode.android.data

/**
 * チャット画面が必要とするサーバー操作の口。
 *
 * **なぜインターフェイスを切るのか**(Q1 の [SessionsGateway] と同じ理由):
 * チャットの状態機械 —— busy の立ち上がり/立ち下がり、abort 後の復帰、バナーの選択、
 * permission ダイアログの寿命 —— は純関数に切り出せない。状態を持ち、通信の結果で分岐する。
 *
 * Q1 のレビューは「純関数のテストは完璧だが、切り出せなかった部分にテストが1本も無い」ことを
 * 変異2本で示した(102件全緑のまま通り抜け、**壊れた症状はどちらも画面が正常に見える**)。
 * RUN_PLAN はこれを「7度目の欠陥、形が違う」として規則にした:
 * **純関数のテストだけでゲートを閉じない。状態遷移そのものに検出器を置く。**
 *
 * ここを差し替え可能にして [dev.opencode.android.ui.ChatController] を `runTest` で
 * 直接叩けるようにする。実装は [ChatRepository]。
 */
interface ChatGateway {
    /** 接続先が設定済みか。未設定なら呼び出し側は通信を試みない。 */
    val isConfigured: Boolean

    /**
     * `GET /session/status` -> `{"<sessionID>": SessionStatus}`。**idle のセッションは現れない**
     * (API_CONTRACT.md の実測)ので、キーが無い = idle として扱うこと。
     *
     * 一覧([SessionsGateway.sessionStatus])と同じ口をチャットにも開けているのは、
     * RUN_PLAN「決定2: 状態はイベント列ではなくサーバーの現在値で初期化する」が
     * **一覧だけの話ではない**ため。切れている間の `session.idle` は購読者ゼロで消えるので、
     * イベント列だけを信じると busy が永久に降りない。
     */
    suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>>

    suspend fun listMessages(sessionId: String): ApiResult<List<MessageEntryDto>>

    suspend fun sendPrompt(sessionId: String, text: String): ApiResult<Unit>

    suspend fun replyPermission(sessionId: String, permissionId: String, response: String): ApiResult<Unit>

    suspend fun abortSession(sessionId: String): ApiResult<Unit>

    // ---- Q3 ----

    /**
     * `GET /session/{id}/todo`。**サーバーの現在値**でタスクリストを初期化・取り直しする。
     * `todo.updated` は SSE でしか来ないので、切れている間の変化はイベント列に無い(決定2)。
     */
    suspend fun sessionTodos(sessionId: String): ApiResult<List<TodoDto>>

    /**
     * `GET /permission`。**全セッション横断**の未応答 permission。呼び出し側が `sessionID` で絞る。
     *
     * Q6(申し送り Q5-2)。`permission.asked` を取りこぼすと戻す口が無く、
     * **サーバーは待ち続ける**。question について Q3 が採った「サーバーを権威にする」を
     * permission にも同じ形で当てる —— 経路ごとに例外を作らない。
     */
    suspend fun pendingPermissions(): ApiResult<List<PermissionRequestDto>>

    /**
     * `GET /question`。**全セッション横断**の未応答質問。呼び出し側が `sessionID` で絞る。
     * 質問側の「決定2」に当たる経路 —— これが無いと、切断中に届いた `question.asked` が
     * 永久に画面へ出ず、**サーバーは応答を待ったまま止まる**。
     */
    suspend fun pendingQuestions(): ApiResult<List<QuestionRequestDto>>

    /** `POST /question/{requestID}/reply` body `{answers: string[][]}`。 */
    suspend fun replyQuestion(requestId: String, answers: List<List<String>>): ApiResult<Unit>

    /** `POST /question/{requestID}/reject`。 */
    suspend fun rejectQuestion(requestId: String): ApiResult<Unit>

    // ---- Q4 ----

    /**
     * `GET /session/{sessionID}`。**このセッションのモデル/エージェントの権威**。
     *
     * Q1 の `GET /session/status`、Q3 の `GET /question` と**同じ問いへの答え**である
     * (RUN_PLAN 決定2)。`POST /api/session/{id}/model` は `session.updated` を流さない
     * (実測)ので、切替の結果はイベント列から復元できない。切れている間に別クライアント
     * (TUI/CLI)が切り替えていても、イベントは購読者ゼロで消えている。
     * **入室時と再接続のたびに引き直す。**
     */
    suspend fun getSession(sessionId: String): ApiResult<SessionDto>

    /**
     * `POST /api/session/{sessionID}/model`。実測 204。
     *
     * **サーバーは値を検証しない**(存在しないモデルも 204 で保存される。API_CONTRACT.md 実測 #4)。
     * 呼び出し側は `GET /provider` から得た候補以外を渡してはならない。
     */
    suspend fun switchSessionModel(sessionId: String, model: ModelRefDto): ApiResult<Unit>

    // ---- Q7: 巻き戻し ----

    /**
     * `POST /session/{sessionID}/revert` `{messageID, partID?}` -> `Session`。
     *
     * **ファイルシステムを書き換える操作である。** 確認ダイアログを通っていない経路から
     * 呼ばないこと(§5b Q7 スコープ6)。判定と確認状態は
     * [dev.opencode.android.ui.ChatController] が持ち、`runTest` から叩ける。
     *
     * 409 = `SessionBusyError`。実行中のセッションは巻き戻せない —— これは
     * 「押せたのに何も起きない」ではなく**サーバーが拒否している**ので、帯に出す。
     */
    suspend fun revertMessage(
        sessionId: String,
        messageId: String,
        partId: String?,
        directory: String? = null,
    ): ApiResult<SessionDto>

    /** `POST /session/{sessionID}/unrevert` -> `Session`。巻き戻しを全部戻す(「元に戻す」)。 */
    suspend fun unrevertSession(sessionId: String, directory: String? = null): ApiResult<SessionDto>
}
