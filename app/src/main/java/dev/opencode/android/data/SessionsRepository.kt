package dev.opencode.android.data

/**
 * セッション一覧まわりのREST呼び出し。**資格情報は [ConnectionRepository] が持つ**ので、
 * 呼び出し側(ViewModel)はURLもパスワードも知らない。
 */
class SessionsRepository(
    private val connection: ConnectionRepository,
) : SessionsGateway {

    override val isConfigured: Boolean
        get() = connection.isConfigured

    /**
     * GET /session。表示順(time.updated降順)まで確定させて返す。
     *
     * [limit] を渡さないとサーバー既定の100件で切られる(R2の「直近100件の窓」の正体)。
     * ページングは **`limit` を伸ばす**ことで行う — `start` はオフセットではなく
     * `time.updated` の下限フィルタなので、古い方へは進めない(API_CONTRACT.md 実測)。
     */
    override suspend fun listSessions(limit: Int?, search: String?): ApiResult<List<SessionDto>> =
        connection.withApi { api ->
            when (val result = api.listSessions(limit = limit, search = search)) {
                is ApiResult.Ok -> ApiResult.Ok(result.value.sortedForDisplay())
                is ApiResult.Err -> result
            }
        }

    /**
     * GET /session/status。**idle のセッションはマップに現れない**(実測)ので、
     * 呼び出し側は「キーが無い = idle」として扱うこと。
     */
    override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> =
        connection.withApi { it.sessionStatus() }

    /** POST /session {title, agent?, model?}。 */
    override suspend fun createSession(
        title: String,
        agent: String?,
        model: ModelRefDto?,
    ): ApiResult<SessionDto> =
        connection.withApi { it.createSession(title, agent, model) }

    /** PATCH /session/{id} {title}。改名。 */
    override suspend fun renameSession(sessionId: String, title: String): ApiResult<SessionDto> =
        connection.withApi { it.updateSessionTitle(sessionId, title) }

    /** DELETE /session/{id}。失敗(404含む)は [ApiResult.Err] で返るので、楽観更新の巻き戻しに使える。 */
    override suspend fun deleteSession(sessionId: String): ApiResult<Unit> =
        connection.withApi { it.deleteSession(sessionId) }
}
