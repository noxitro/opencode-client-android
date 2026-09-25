package dev.opencode.android.data

/**
 * Q7 の差分/VCS まわりの REST 呼び出し。
 * **資格情報は [ConnectionRepository] が持つ**ので、呼び出し側は URL もパスワードも知らない。
 */
class DiffRepository(
    private val connection: ConnectionRepository,
) : DiffGateway {

    override val isConfigured: Boolean
        get() = connection.isConfigured

    /** GET /vcs */
    override suspend fun vcsInfo(directory: String?): ApiResult<VcsInfoDto> =
        connection.withApi { it.vcsInfo(directory) }

    /** GET /vcs/status */
    override suspend fun vcsStatus(directory: String?): ApiResult<List<VcsFileStatusDto>> =
        connection.withApi { it.vcsStatus(directory) }

    /** GET /vcs/diff?mode=…&context=… */
    override suspend fun vcsDiff(mode: String, context: Int?, directory: String?): ApiResult<List<VcsFileDiffDto>> =
        connection.withApi { it.vcsDiff(mode, context, directory) }

    /** GET /session/{sessionID}/diff?messageID= */
    override suspend fun sessionDiff(
        sessionId: String,
        messageId: String?,
        directory: String?,
    ): ApiResult<List<SnapshotFileDiffDto>> =
        connection.withApi { it.sessionDiff(sessionId, messageId, directory) }
}
