package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ChatGateway
import dev.opencode.android.data.DiffGateway
import dev.opencode.android.data.FileContentPayload
import dev.opencode.android.data.FileNodeDto
import dev.opencode.android.data.FileStatusDto
import dev.opencode.android.data.FilesGateway
import dev.opencode.android.data.FindMatchDto
import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.PermissionRequestDto
import dev.opencode.android.data.QuestionRequestDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SnapshotFileDiffDto
import dev.opencode.android.data.SymbolDto
import dev.opencode.android.data.TodoDto
import dev.opencode.android.data.VcsFileDiffDto
import dev.opencode.android.data.VcsFileStatusDto
import dev.opencode.android.data.VcsInfoDto
import dev.opencode.android.data.contractJson

/**
 * Q8 のテストが共有する偽ゲートウェイ。
 *
 * **応答は実データから作る。** このプロジェクトが7回繰り返した欠陥形は
 * 「**フィクスチャが実データと違う形**」なので、`fromJson` 系のヘルパを通して
 * [Q8Fixtures] の**サーバーが返したバイト列そのもの**をデコードする。
 * 手で組んだ DTO を既定にすると、契約の食い違いがテストからは見えなくなる。
 */

/** [FilesGateway] の偽物。**撃たれた引数を全部控える**(URL の主張は `Q8HttpContractTest` が持つ)。 */
class FakeFilesGateway(
    override val isConfigured: Boolean = true,
    /**
     * **4宛先の呼び出し順**を1本の列に控える器(レビュー minor-7)。
     *
     * 宛先が実体になったので「配られた」は回数で測れるようになったが、
     * **順序の主張が落ちていた**。`deliverConnectionsTo` は
     * 「2つの宛先へ**順に**無条件で配る」ことがそもそもの存在理由(Q1 の変異で
     * 一覧とチャットの順序が入れ替わっても102件全緑だった)なので、順序を戻す。
     */
    private val order: MutableList<String>? = null,
) : FilesGateway {

    /** `listFiles(path)` に渡された path の列。 */
    val listedPaths = mutableListOf<String>()
    val readPaths = mutableListOf<String>()

    /**
     * `readFile` に渡された `directory`(レビュー minor-1 / 変異 N11)。
     *
     * `Q8HttpContractTest` が固定しているのは `OpenCodeApi` が組む URL であって、
     * **Controller が `directory` を渡しているか**は別の主張である ——
     * `gateway.readFile(target, null)` の変異は URL のテストからは見えない。
     */
    val readDirectories = mutableListOf<String?>()
    val textQueries = mutableListOf<String>()
    val fileQueries = mutableListOf<Pair<String, Int>>()
    val symbolQueries = mutableListOf<String>()

    var listResult: ApiResult<List<FileNodeDto>> = ApiResult.Ok(emptyList())
    var readResult: ApiResult<FileContentPayload> =
        ApiResult.Ok(FileContentPayload(type = "text", content = ""))
    var statusResult: ApiResult<List<FileStatusDto>> = ApiResult.Ok(emptyList())
    var textResult: ApiResult<List<FindMatchDto>> = ApiResult.Ok(emptyList())
    var filesResult: ApiResult<List<String>> = ApiResult.Ok(emptyList())

    /**
     * シンボル検索の応答を**語ごとに**決める。校正クエリ([dev.opencode.android.data.SYMBOL_INDEX_PROBE])と
     * ユーザーの語で別の答えを返せないと、「見つからない」と「索引が使えない」の
     * 区別そのものがテストできない。
     */
    var symbolResults: (String) -> ApiResult<List<SymbolDto>> = { ApiResult.Ok(emptyList()) }

    override suspend fun listFiles(path: String, directory: String?): ApiResult<List<FileNodeDto>> {
        listedPaths += path
        order?.add("files")
        return listResult
    }

    override suspend fun readFile(path: String, directory: String?): ApiResult<FileContentPayload> {
        readPaths += path
        readDirectories += directory
        return readResult
    }

    override suspend fun fileStatus(directory: String?): ApiResult<List<FileStatusDto>> = statusResult

    override suspend fun findText(pattern: String, directory: String?): ApiResult<List<FindMatchDto>> {
        textQueries += pattern
        return textResult
    }

    override suspend fun findFiles(query: String, limit: Int, directory: String?): ApiResult<List<String>> {
        fileQueries += query to limit
        return filesResult
    }

    override suspend fun findSymbols(query: String, directory: String?): ApiResult<List<SymbolDto>> {
        symbolQueries += query
        return symbolResults(query)
    }
}

/** `GET /vcs/status` だけを本物らしく返す [DiffGateway]。変更バッジの材料。 */
class FakeVcsGateway(
    override val isConfigured: Boolean = true,
    var status: ApiResult<List<VcsFileStatusDto>> = ApiResult.Ok(emptyList()),
    /** 4宛先の呼び出し順を控える器([FakeFilesGateway] の doc)。 */
    private val order: MutableList<String>? = null,
) : DiffGateway {
    /** `GET /vcs` が撃たれた回数(再接続の配線を測る)。 */
    var vcsInfoCalls = 0

    override suspend fun vcsInfo(directory: String?): ApiResult<VcsInfoDto> {
        vcsInfoCalls++
        order?.add("diff")
        return ApiResult.Ok(VcsInfoDto(branch = "master", defaultBranch = "master"))
    }

    override suspend fun vcsStatus(directory: String?): ApiResult<List<VcsFileStatusDto>> = status

    override suspend fun vcsDiff(mode: String, context: Int?, directory: String?): ApiResult<List<VcsFileDiffDto>> =
        ApiResult.Ok(emptyList())

    override suspend fun sessionDiff(
        sessionId: String,
        messageId: String?,
        directory: String?,
    ): ApiResult<List<SnapshotFileDiffDto>> = ApiResult.Ok(emptyList())
}

/**
 * 何も返さない [ChatGateway]。[dev.opencode.android.ui.ChatController] を
 * 配線テストで**実体として渡す**ために要る(RUN_PLAN 設計規則1: 宛先はラムダで受け取らない)。
 */
class NoopChatGateway(
    override val isConfigured: Boolean = true,
    /** 4宛先の呼び出し順を控える器([FakeFilesGateway] の doc)。 */
    private val order: MutableList<String>? = null,
) : ChatGateway {
    var sessionStatusCalls = 0

    override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> {
        sessionStatusCalls++
        return ApiResult.Ok(emptyMap())
    }

    override suspend fun listMessages(sessionId: String): ApiResult<List<MessageEntryDto>> =
        ApiResult.Ok(emptyList())

    override suspend fun sendPrompt(sessionId: String, text: String): ApiResult<Unit> = ApiResult.Ok(Unit)

    override suspend fun replyPermission(
        sessionId: String,
        permissionId: String,
        response: String,
    ): ApiResult<Unit> = ApiResult.Ok(Unit)

    override suspend fun abortSession(sessionId: String): ApiResult<Unit> = ApiResult.Ok(Unit)

    /**
     * 順序の目印は**再接続1回につき1度だけ呼ばれる口**に置く。
     * `sessionStatus()` は一覧側の口と名前が同じで紛らわしいので `sessionTodos()` を使う。
     */
    override suspend fun sessionTodos(sessionId: String): ApiResult<List<TodoDto>> {
        order?.add("chat")
        return ApiResult.Ok(emptyList())
    }

    override suspend fun pendingPermissions(): ApiResult<List<PermissionRequestDto>> = ApiResult.Ok(emptyList())

    override suspend fun pendingQuestions(): ApiResult<List<QuestionRequestDto>> = ApiResult.Ok(emptyList())

    override suspend fun replyQuestion(requestId: String, answers: List<List<String>>): ApiResult<Unit> =
        ApiResult.Ok(Unit)

    override suspend fun rejectQuestion(requestId: String): ApiResult<Unit> = ApiResult.Ok(Unit)

    override suspend fun getSession(sessionId: String): ApiResult<SessionDto> =
        ApiResult.Err(ApiError.NotConfigured)

    override suspend fun switchSessionModel(sessionId: String, model: ModelRefDto): ApiResult<Unit> =
        ApiResult.Ok(Unit)

    override suspend fun revertMessage(
        sessionId: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ): ApiResult<SessionDto> = ApiResult.Err(ApiError.NotConfigured)

    override suspend fun unrevertSession(sessionId: String, directory: String?): ApiResult<SessionDto> =
        ApiResult.Err(ApiError.NotConfigured)
}

/**
 * `GET /session/status` が撃たれた回数を控える [dev.opencode.android.data.SessionsGateway]。
 *
 * Q8 で `deliverConnectionsTo` の宛先が**ラムダから実体へ**変わったので
 * (Q7 申し送り Q7-6)、「一覧へ配られた」は**ゲートウェイが撃たれたこと**で測る。
 */
class RecordingSessionsGateway(
    override val isConfigured: Boolean = true,
    /** 4宛先の呼び出し順を控える器([FakeFilesGateway] の doc)。 */
    private val order: MutableList<String>? = null,
) : dev.opencode.android.data.SessionsGateway {
    var statusCalls = 0

    override suspend fun listSessions(limit: Int?, search: String?): ApiResult<List<SessionDto>> =
        ApiResult.Ok(emptyList())

    override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> {
        statusCalls++
        order?.add("list")
        return ApiResult.Ok(emptyMap())
    }

    override suspend fun createSession(
        title: String,
        agent: String?,
        model: ModelRefDto?,
    ): ApiResult<SessionDto> = ApiResult.Err(ApiError.NotConfigured)

    override suspend fun renameSession(sessionId: String, title: String): ApiResult<SessionDto> =
        ApiResult.Err(ApiError.NotConfigured)

    override suspend fun deleteSession(sessionId: String): ApiResult<Unit> = ApiResult.Ok(Unit)
}

/**
 * `isConfigured` を読んだ瞬間に投げる [dev.opencode.android.data.SessionsGateway]。
 *
 * `collectGuarded`(1件の適用が投げても購読を殺さない)を測るために要る。
 * `SessionListController.refreshStatus` は `if (!gateway.isConfigured) return` を
 * **同期で**通るので、ここで投げると配線の中で例外になる ——
 * `scope.launch` の中で投げると別のコルーチンの話になり、配線の主張にならない。
 */
class ThrowingSessionsGateway : dev.opencode.android.data.SessionsGateway {
    override val isConfigured: Boolean
        get() = throw IllegalStateException("一覧側が投げた")

    override suspend fun listSessions(limit: Int?, search: String?): ApiResult<List<SessionDto>> =
        ApiResult.Ok(emptyList())

    override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> = ApiResult.Ok(emptyMap())

    override suspend fun createSession(
        title: String,
        agent: String?,
        model: ModelRefDto?,
    ): ApiResult<SessionDto> = ApiResult.Err(ApiError.NotConfigured)

    override suspend fun renameSession(sessionId: String, title: String): ApiResult<SessionDto> =
        ApiResult.Err(ApiError.NotConfigured)

    override suspend fun deleteSession(sessionId: String): ApiResult<Unit> = ApiResult.Ok(Unit)
}

/** 実データ JSON をそのままデコードするヘルパ(手で組んだ DTO を使わないため)。 */
object Q8Decode {
    fun fileNodes(json: String): List<FileNodeDto> =
        contractJson.decodeFromString(json)

    fun matches(json: String): List<FindMatchDto> =
        contractJson.decodeFromString(json)

    fun symbols(json: String): List<SymbolDto> =
        contractJson.decodeFromString(json)

    fun paths(json: String): List<String> =
        contractJson.decodeFromString(json)
}
