package dev.opencode.android.data

/**
 * チャット1セッション分のREST呼び出し。**資格情報は [ConnectionRepository] が持つ**ので、
 * 呼び出し側(ViewModel)はURLもパスワードも知らない。
 *
 * SSEはここではなく [OpenCodeEventStream](アプリ生存中に1本)が持つ。
 */
class ChatRepository(
    private val connection: ConnectionRepository,
) : ChatGateway {

    override val isConfigured: Boolean
        get() = connection.isConfigured

    /**
     * GET /session/status。**idle のセッションはマップに現れない**(実測)ので、
     * 呼び出し側は「キーが無い = idle」として扱うこと。
     */
    override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> =
        connection.withApi { it.sessionStatus() }

    /** GET /session/{id}/message */
    override suspend fun listMessages(sessionId: String): ApiResult<List<MessageEntryDto>> =
        connection.withApi { it.listMessages(sessionId) }

    /** POST /session/{id}/prompt_async */
    override suspend fun sendPrompt(sessionId: String, text: String): ApiResult<Unit> =
        connection.withApi { it.sendPrompt(sessionId, text) }

    /** POST /session/{sessionID}/permissions/{permissionID} */
    override suspend fun replyPermission(sessionId: String, permissionId: String, response: String): ApiResult<Unit> =
        connection.withApi { it.replyPermission(sessionId, permissionId, response) }

    /** POST /session/{sessionID}/abort */
    override suspend fun abortSession(sessionId: String): ApiResult<Unit> =
        connection.withApi { it.abortSession(sessionId) }

    /** GET /session/{sessionID}/todo */
    override suspend fun sessionTodos(sessionId: String): ApiResult<List<TodoDto>> =
        connection.withApi { it.sessionTodos(sessionId) }

    /** GET /permission(全セッション横断の未応答 permission。Q6 / 申し送り Q5-2) */
    override suspend fun pendingPermissions(): ApiResult<List<PermissionRequestDto>> =
        connection.withApi { it.pendingPermissions() }

    /** GET /question(全セッション横断の未応答質問) */
    override suspend fun pendingQuestions(): ApiResult<List<QuestionRequestDto>> =
        connection.withApi { it.pendingQuestions() }

    /** POST /question/{requestID}/reply */
    override suspend fun replyQuestion(requestId: String, answers: List<List<String>>): ApiResult<Unit> =
        connection.withApi { it.replyQuestion(requestId, answers) }

    /** POST /question/{requestID}/reject */
    override suspend fun rejectQuestion(requestId: String): ApiResult<Unit> =
        connection.withApi { it.rejectQuestion(requestId) }

    /** GET /session/{sessionID}(Q4: モデル/エージェントの権威) */
    override suspend fun getSession(sessionId: String): ApiResult<SessionDto> =
        connection.withApi { it.getSession(sessionId) }

    /** POST /api/session/{sessionID}/model {model} -> 204 */
    override suspend fun switchSessionModel(sessionId: String, model: ModelRefDto): ApiResult<Unit> =
        connection.withApi { it.switchSessionModel(sessionId, model) }

    /** POST /session/{sessionID}/revert {messageID, partID?}(Q7。**ファイルを書き換える**) */
    override suspend fun revertMessage(
        sessionId: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ): ApiResult<SessionDto> =
        connection.withApi { it.revertMessage(sessionId, messageId, partId, directory) }

    /** POST /session/{sessionID}/unrevert(Q7) */
    override suspend fun unrevertSession(sessionId: String, directory: String?): ApiResult<SessionDto> =
        connection.withApi { it.unrevertSession(sessionId, directory) }
}
