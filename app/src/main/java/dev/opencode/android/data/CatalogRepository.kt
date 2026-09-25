package dev.opencode.android.data

/**
 * `GET /provider` / `GET /agent` のREST呼び出し(Q4)。
 * **資格情報は [ConnectionRepository] が持つ**ので、呼び出し側はURLもパスワードも知らない。
 */
class CatalogRepository(
    private val connection: ConnectionRepository,
) : CatalogGateway {

    override val isConfigured: Boolean
        get() = connection.isConfigured

    override suspend fun listProviders(): ApiResult<ProvidersDto> =
        connection.withApi { it.listProviders() }

    override suspend fun listAgents(): ApiResult<List<AgentDto>> =
        connection.withApi { it.listAgents() }
}
