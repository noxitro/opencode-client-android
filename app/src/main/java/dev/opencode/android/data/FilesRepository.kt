package dev.opencode.android.data

/**
 * Q8 のファイル/検索まわりの REST 呼び出し。
 * **資格情報は [ConnectionRepository] が持つ**ので、呼び出し側は URL もパスワードも知らない。
 */
class FilesRepository(
    private val connection: ConnectionRepository,
) : FilesGateway {

    override val isConfigured: Boolean
        get() = connection.isConfigured

    /** GET /file?path= */
    override suspend fun listFiles(path: String, directory: String?): ApiResult<List<FileNodeDto>> =
        connection.withApi { it.listFiles(path, directory) }

    /** GET /file/content?path=(受信は [FILE_CONTENT_MAX_BYTES] で打ち切る) */
    override suspend fun readFile(path: String, directory: String?): ApiResult<FileContentPayload> =
        connection.withApi { it.readFileContent(path, directory) }

    /** GET /file/status(**1.18.21 では常に `[]`**。[FilesGateway] の doc) */
    override suspend fun fileStatus(directory: String?): ApiResult<List<FileStatusDto>> =
        connection.withApi { it.fileStatus(directory) }

    /** GET /find?pattern= */
    override suspend fun findText(pattern: String, directory: String?): ApiResult<List<FindMatchDto>> =
        connection.withApi { it.findText(pattern, directory) }

    /** GET /find/file?query=&limit= */
    override suspend fun findFiles(query: String, limit: Int, directory: String?): ApiResult<List<String>> =
        connection.withApi { it.findFiles(query, limit, directory) }

    /** GET /find/symbol?query= */
    override suspend fun findSymbols(query: String, directory: String?): ApiResult<List<SymbolDto>> =
        connection.withApi { it.findSymbols(query, directory) }
}
