package dev.opencode.android.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** API呼び出しの失敗。エラーボディのshapeは未ピン留めのためステータスコードのみで分岐する(API_CONTRACT.md)。 */
sealed interface ApiError {
    /** HTTPステータス異常(401=認証失敗)。 */
    data class Http(val code: Int) : ApiError

    /** 到達不能・タイムアウト等の接続失敗。 */
    data class Network(val message: String) : ApiError

    /**
     * 接続先が未設定(DataStore未保存 or 読み込み前)。
     * リポジトリは資格情報を自分で持つため、呼び出し側が「未設定なら呼ばない」判断を
     * できない場合がある。そのときはこのエラーを返す(例外にしない)。
     */
    data object NotConfigured : ApiError
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

/**
 * opencode serve RESTクライアント。パス・フィールド名は docs/API_CONTRACT.md に従う。
 * 接続先ごとにインスタンスを生成する(設定変更時は作り直す)。
 *
 * 注意: Basic認証ヘッダをログに出さない(logging interceptorは付けない)。
 */
/** [OpenCodeApi.readFileContent] が1回に読むバイト数。**閾値ではない**(閾値は [FILE_CONTENT_MAX_BYTES])。 */
private const val READ_CHUNK_BYTES = 8 * 1024

class OpenCodeApi(
    private val baseUrl: String,
    private val basicPassword: String,
) {
    private val json = contractJson

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun requestBuilder(path: String): Request.Builder =
        Request.Builder()
            .url(baseUrl + path)
            // 認証: HTTP Basic、ユーザー名既定 "opencode"(API_CONTRACT.md 認証節)
            .header("Authorization", Credentials.basic("opencode", basicPassword, Charsets.UTF_8))

    /** GET /global/health -> {healthy, version} */
    suspend fun health(): ApiResult<HealthDto> =
        call("/global/health") { text -> json.decodeFromString<HealthDto>(text) }

    /**
     * GET /session -> Session[]。
     *
     * `limit` の既定は**サーバー側で100**(API_CONTRACT.md「Q1で使用する分」の実測)。
     * R2 の「直近100件の窓」はこの既定値そのものであり、`limit` を上げれば越えられる。
     *
     * **`start` は渡さない。** spec には query に載っているが、実測では
     * `time.updated` の下限フィルタ(エポックミリ秒・`>=`)であってオフセットではない。
     * ページング(より古い方へ進む)には使えず、渡すと**より新しい側へ絞る**だけになる。
     * 詳細と測定値は API_CONTRACT.md「`start` / `limit` / `search` の意味」。
     */
    suspend fun listSessions(limit: Int? = null, search: String? = null): ApiResult<List<SessionDto>> {
        val query = buildList {
            if (limit != null) add("limit=$limit")
            if (!search.isNullOrBlank()) add("search=" + encodeQueryValue(search))
        }
        val path = if (query.isEmpty()) "/session" else "/session?" + query.joinToString("&")
        return call(path) { text -> json.decodeFromString<List<SessionDto>>(text) }
    }

    /**
     * GET /session/status -> `{"<sessionID>": SessionStatus}`。
     *
     * 実測: 何も走っていなければ `{}`、**idle のセッションはマップに現れない**。
     * 「キーが無い = idle」として扱う(API_CONTRACT.md)。
     */
    suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> =
        call("/session/status") { text -> json.decodeFromString<Map<String, SessionStatusDto>>(text) }

    /** PATCH /session/{sessionID} {"title"} -> Session。改名に使う。 */
    suspend fun updateSessionTitle(sessionId: String, title: String): ApiResult<SessionDto> {
        val payload = json.encodeToString(UpdateSessionRequest(title))
            .toRequestBody("application/json".toMediaType())
        return call("/session/$sessionId", extra = { it.patch(payload) }) { text ->
            json.decodeFromString<SessionDto>(text)
        }
    }

    /**
     * DELETE /session/{sessionID}。成功は 200 + ボディ `true`、存在しなければ 404(実測)。
     * ボディはパースせずステータスのみで判定する(楽観更新のロールバック判定はこの結果で行う)。
     */
    suspend fun deleteSession(sessionId: String): ApiResult<Unit> =
        callNoBody("/session/$sessionId") { it.delete() }

    /**
     * POST /session {title, agent?, model?} -> Session。
     *
     * Q4 で `agent` / `model` を足した。**null は送られない**([contractJson] の
     * `explicitNulls = false`)—— 実測で `{"title":"x","agent":null}` は 400 BadRequest だった。
     * 未指定はキーごと省略され、サーバー既定が使われる。
     */
    suspend fun createSession(
        title: String,
        agent: String? = null,
        model: ModelRefDto? = null,
    ): ApiResult<SessionDto> {
        val payload = json.encodeToString(CreateSessionRequest(title, agent, model))
            .toRequestBody("application/json".toMediaType())
        return call("/session", extra = { it.post(payload) }) { text ->
            json.decodeFromString<SessionDto>(text)
        }
    }

    /**
     * GET /session/{sessionID} -> Session。Q4 で初めて使う。
     *
     * **モデル/エージェントの権威はここ**である。`POST /api/session/{id}/model` は
     * `session.updated` を流さない(実測)ので、切替の結果はイベント列から復元できない。
     * 入室時と再接続のたびに引き直す(RUN_PLAN 決定2 の Q4 における対応物)。
     */
    suspend fun getSession(sessionId: String): ApiResult<SessionDto> =
        call("/session/$sessionId") { text -> json.decodeFromString<SessionDto>(text) }

    /**
     * POST /api/session/{sessionID}/model `{model: ModelRef}` -> **204**(実測)。
     *
     * **サーバーはモデル名を検証しない**(実測 #4: 存在しないIDも 204 で受理して保存する)。
     * したがって呼び出し側は `GET /provider` から得た候補以外を渡してはならない。
     * 404 はセッションが存在しないときだけ。
     */
    suspend fun switchSessionModel(sessionId: String, model: ModelRefDto): ApiResult<Unit> =
        callNoBody("/api/session/$sessionId/model") {
            val payload = json.encodeToString(SwitchModelRequest(model))
                .toRequestBody("application/json".toMediaType())
            it.post(payload)
        }

    /** POST /api/session/{sessionID}/agent `{agent}` -> 204(実測)。検証されない点も model と同じ。 */
    suspend fun switchSessionAgent(sessionId: String, agent: String): ApiResult<Unit> =
        callNoBody("/api/session/$sessionId/agent") {
            val payload = json.encodeToString(SwitchAgentRequest(agent))
                .toRequestBody("application/json".toMediaType())
            it.post(payload)
        }

    /**
     * GET /provider -> `{all, default, connected}`。
     *
     * **応答は実測 5.4 MiB(203プロバイダ / 7,338モデル)**。起動時には引かず、
     * モデル選択を開いたときに1回だけ引くこと。DTO は表示に使うフィールドしか宣言していないので
     * (`ignoreUnknownKeys`)、materialize されるのはその一部だけになる。
     *
     * readTimeout はクラス既定の15秒。エミュレータでこれを超えるなら**分割ではなく
     * 失敗として出す** —— 途中まで読めた一覧は「選べるモデルの全部」ではない。
     */
    suspend fun listProviders(): ApiResult<ProvidersDto> =
        call("/provider") { text -> json.decodeFromString<ProvidersDto>(text) }

    /** GET /agent -> `Agent[]`(実測 17件 / 80 KB)。 */
    suspend fun listAgents(): ApiResult<List<AgentDto>> =
        call("/agent") { text -> json.decodeFromString<List<AgentDto>>(text) }

    /** GET /session/{id}/message -> {info, parts}[] */
    suspend fun listMessages(sessionId: String): ApiResult<List<MessageEntryDto>> =
        call("/session/$sessionId/message") { text ->
            json.decodeFromString<List<MessageEntryDto>>(text)
        }

    /**
     * POST /session/{id}/prompt_async body {"parts":[{"type":"text","text":"..."}]}。
     * 成功は204 No Content(ボディ無し)のためパースせずステータスのみ判定する。
     * 409=SessionBusyError(実行中セッションへの再送)。ボディshape未ピン留めにつきコードのみで分岐。
     */
    suspend fun sendPrompt(sessionId: String, text: String): ApiResult<Unit> =
        callNoBody("/session/$sessionId/prompt_async") {
            val payload = json.encodeToString(PromptInput(parts = listOf(PromptTextPartInput(text = text))))
                .toRequestBody("application/json".toMediaType())
            it.post(payload)
        }

    /**
     * POST /session/{sessionID}/permissions/{permissionID} {response: "once"|"always"|"reject"}。
     * legacy経路を主に使う(API_CONTRACT.md注記)。2xxなら成功。
     */
    suspend fun replyPermission(sessionId: String, permissionId: String, response: String): ApiResult<Unit> =
        callNoBody("/session/$sessionId/permissions/$permissionId") {
            val payload = json.encodeToString(PermissionReplyRequest(response = response))
                .toRequestBody("application/json".toMediaType())
            it.post(payload)
        }

    /**
     * POST /session/{sessionID}/abort。ボディなし。2xxなら成功。
     * 実行中にabortボタン押下時に呼ぶ。busy解除はサーバー側のsession.idle待ち。
     */
    suspend fun abortSession(sessionId: String): ApiResult<Unit> =
        callNoBody("/session/$sessionId/abort") {
            val payload = "".toRequestBody("application/json".toMediaType())
            it.post(payload)
        }

    // ---- Q3: Todo + Question ----

    /**
     * GET /session/{sessionID}/todo -> `Todo[]`。todo が無いセッションは `[]`(404 ではない。実測)。
     *
     * **入室時と再接続のたびに引く**(RUN_PLAN 決定2 と同じ問いへの答え)。
     * `todo.updated` は SSE でしか来ないので、切れている間の変化はイベント列から復元できない。
     */
    suspend fun sessionTodos(sessionId: String): ApiResult<List<TodoDto>> =
        call("/session/$sessionId/todo") { text -> json.decodeFromString<List<TodoDto>>(text) }

    /**
     * GET /permission -> `PermissionRequest[]`。**全セッション横断の未応答 permission**
     * (実測 2026-08-27、実物 serve 1.18.21: 200 `[]`)。
     *
     * Q6 で足した。Q3 が `GET /question` について解いたのと**まったく同じ問題**である ——
     * `permission.asked` は SSE でしか来ないので、切断中に届いたものを取りこぼすと
     * 画面に二度と出ず、**サーバーは応答を待ったまま止まり続ける**(申し送り Q5-2)。
     * サーバー側には残っている(Q5 の E2E が `permissionsPending=['per_1']` を観測している)。
     */
    suspend fun pendingPermissions(): ApiResult<List<PermissionRequestDto>> =
        call("/permission") { text -> json.decodeFromString<List<PermissionRequestDto>>(text) }

    /**
     * GET /question -> `QuestionRequest[]`。**全セッション横断の未応答質問**(実測: 無ければ `[]`)。
     *
     * これが「切断中に届いた `question.asked`」を埋める唯一の経路である。
     * セッションでの絞り込みはサーバー側に無いので、呼び出し側が `sessionID` で絞る。
     */
    suspend fun pendingQuestions(): ApiResult<List<QuestionRequestDto>> =
        call("/question") { text -> json.decodeFromString<List<QuestionRequestDto>>(text) }

    /**
     * POST /question/{requestID}/reply body `{answers: string[][]}`。200 + ボディ `true`。
     * 未応答の質問が無ければ 404 QuestionNotFoundError(ボディはパースせずコードで分岐)。
     */
    suspend fun replyQuestion(requestId: String, answers: List<List<String>>): ApiResult<Unit> =
        callNoBody("/question/$requestId/reply") {
            val payload = json.encodeToString(QuestionReplyRequest(answers = answers))
                .toRequestBody("application/json".toMediaType())
            it.post(payload)
        }

    /** POST /question/{requestID}/reject(ボディなし)。200 + ボディ `true` / 404。 */
    suspend fun rejectQuestion(requestId: String): ApiResult<Unit> =
        callNoBody("/question/$requestId/reject") {
            it.post("".toRequestBody("application/json".toMediaType()))
        }

    // ---- Q7: 差分 + VCS + 巻き戻し ----

    /**
     * `GET /session/{sessionID}/diff?messageID=` -> `SnapshotFileDiff[]`。
     *
     * **実測(2026-08-27、実物 serve 1.18.21、認証あり)**: `messageID` 無しで
     * 既存セッション **200件すべてが `[]`** を返した。402 Payment Required で
     * エージェントが1度もファイルを変更していないためで、**非空の応答はこの環境では
     * 観測できていない**(「測れない」であって「測っていない」ではない。RUN_PLAN)。
     *
     * spec の説明文は "the file changes that resulted from a specific **user** message" で、
     * `messageID` の pattern は `^msg`。**どのメッセージIDを渡すと非空になるかは未検証**。
     * アプリはチップが属するメッセージのIDを渡し、空が返ったらそれを空状態として出す
     * (黙って何も出さない、はしない)。
     */
    suspend fun sessionDiff(
        sessionId: String,
        messageId: String? = null,
        directory: String? = null,
    ): ApiResult<List<SnapshotFileDiffDto>> {
        val path = "/session/$sessionId/diff" + queryString(
            "messageID" to messageId,
            "directory" to directory,
        )
        return call(path) { text -> json.decodeFromString<List<SnapshotFileDiffDto>>(text) }
    }

    /**
     * `GET /vcs` -> `VcsInfo`。**推論を伴わないので 402 でも測れる。**
     * 実測: `{"branch":"master","default_branch":"master"}`(200)。
     */
    suspend fun vcsInfo(directory: String? = null): ApiResult<VcsInfoDto> =
        call("/vcs" + queryString("directory" to directory)) { text ->
            json.decodeFromString<VcsInfoDto>(text)
        }

    /**
     * `GET /vcs/status` -> `VcsFileStatus[]`。**patch を持たない軽い一覧**。
     * 実測: 変更が無ければ `[]`、**未追跡ファイルも `status:"added"` として載る**。
     */
    suspend fun vcsStatus(directory: String? = null): ApiResult<List<VcsFileStatusDto>> =
        call("/vcs/status" + queryString("directory" to directory)) { text ->
            json.decodeFromString<List<VcsFileStatusDto>>(text)
        }

    /**
     * `GET /vcs/diff?mode=git|branch&context=N` -> `VcsFileDiff[]`。
     *
     * **`mode` は required**(実測: 省略すると 400
     * `{"name":"BadRequest","data":{"message":"Missing key\n  at [\"mode\"]","kind":"Query"}}`)。
     * QUALITY_PLAN §5b は必須と書いていないが、サーバーは必須として扱う。
     *
     * **`context` の既定は「ファイル全体」に近い大きな値である**(実測: 57行のファイルが
     * `@@ -1,57 +1,57 @@` の1ハンクになった)。既定のまま引くと、1行の変更でも
     * ファイル全部を端末へ運ぶ。したがって呼び出し側は明示的に [VCS_DIFF_CONTEXT] を渡す。
     */
    suspend fun vcsDiff(
        mode: String,
        context: Int? = null,
        directory: String? = null,
    ): ApiResult<List<VcsFileDiffDto>> {
        val path = "/vcs/diff" + queryString(
            "mode" to mode,
            "context" to context?.toString(),
            "directory" to directory,
        )
        return call(path) { text -> json.decodeFromString<List<VcsFileDiffDto>>(text) }
    }

    /**
     * `POST /session/{sessionID}/revert` `{messageID, partID?}` -> `Session`。
     *
     * **ファイルシステムを書き換える。** 呼び出す前に確認ダイアログを出すこと
     * (§5b Q7 スコープ6)。409 = `SessionBusyError`(実行中は巻き戻せない)、404 = 不在。
     *
     * **本リポジトリに対しては実測していない**(§5b: 実物での revert は使い捨ての
     * 一時リポジトリで1回だけ測る)。
     */
    suspend fun revertMessage(
        sessionId: String,
        messageId: String,
        partId: String? = null,
        directory: String? = null,
    ): ApiResult<SessionDto> {
        val payload = json.encodeToString(RevertRequest(messageID = messageId, partID = partId))
            .toRequestBody("application/json".toMediaType())
        val path = "/session/$sessionId/revert" + queryString("directory" to directory)
        return call(path, extra = { it.post(payload) }) { text ->
            json.decodeFromString<SessionDto>(text)
        }
    }

    /** `POST /session/{sessionID}/unrevert`(ボディ無し)-> `Session`。巻き戻しを全部戻す。 */
    suspend fun unrevertSession(sessionId: String, directory: String? = null): ApiResult<SessionDto> {
        val payload = "".toRequestBody("application/json".toMediaType())
        val path = "/session/$sessionId/unrevert" + queryString("directory" to directory)
        return call(path, extra = { it.post(payload) }) { text ->
            json.decodeFromString<SessionDto>(text)
        }
    }

    // ---- Q8: ファイルブラウザ + 検索 ----

    /**
     * `GET /file?path=` -> `FileNode[]`。**`path` は required**(省略すると 400。実測)。
     *
     * 返る `path` は**サーバーOSの区切り**で、ディレクトリは末尾に区切りが付く
     * (実測: `app\build\`)。入力側は `/` でも `\` でも通るので、**アプリは常に `/` で送る**。
     * 正規化は [dev.opencode.android.ui.normalizeServerPath]。
     */
    suspend fun listFiles(path: String, directory: String? = null): ApiResult<List<FileNodeDto>> =
        call("/file" + queryString("path" to path, "directory" to directory)) { text ->
            json.decodeFromString<List<FileNodeDto>>(text)
        }

    /**
     * `GET /file/content?path=` -> `FileContent`。**受信を [FILE_CONTENT_MAX_BYTES] で打ち切る。**
     *
     * ここだけ [call] を使わないのは、`call` が `resp.body.string()` で**全部をメモリへ載せる**ためである。
     * `GET /file/content` には上限が無く(spec にも応答にも)、`content` はファイル全文が
     * 1本の JSON 文字列で来る —— QUALITY_PLAN §5b Q8 のリスク欄が名指ししている OOM がこれ。
     *
     * 打ち切った JSON は壊れているので [decodeFileContent] が救出する。
     * **切ったことは [FileContentPayload.truncated] で必ず上へ伝える**(黙って先頭だけ返さない)。
     */
    suspend fun readFileContent(
        path: String,
        directory: String? = null,
        maxBytes: Int = FILE_CONTENT_MAX_BYTES,
    ): ApiResult<FileContentPayload> = withContext(Dispatchers.IO) {
        val target = "/file/content" + queryString("path" to path, "directory" to directory)
        try {
            client.newCall(requestBuilder(target).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use ApiResult.Err(ApiError.Http(resp.code))
                }
                val stream = resp.body?.byteStream()
                    ?: return@use ApiResult.Err(ApiError.Network("応答の本文がありません"))
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(READ_CHUNK_BYTES)
                var total = 0
                while (total < maxBytes) {
                    val n = stream.read(chunk, 0, minOf(chunk.size, maxBytes - total))
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                    total += n
                }
                // **まだ続きがあるか**を1バイトだけ覗いて決める。`total == maxBytes` だけで
                // 判定すると、ちょうど閾値ぴったりのファイルを「切った」と嘘をつく。
                val truncated = total >= maxBytes && stream.read() >= 0
                try {
                    ApiResult.Ok(decodeFileContent(buffer.toByteArray(), truncated))
                } catch (_: SerializationException) {
                    ApiResult.Err(ApiError.Network("レスポンス形式が契約と一致しません"))
                }
            }
        } catch (e: IOException) {
            ApiResult.Err(ApiError.Network(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * `GET /file/status` -> `File[]`。
     *
     * **1.18.21 は変更があっても `[]` を返す**(実測: 同時刻の `GET /vcs/status` は5件返した)。
     * 口は残すが**画面の判断材料にはしない** —— 変更ファイルの権威は `GET /vcs/status` である
     * (API_CONTRACT.md「`GET /file/status` は変更を返さない」)。
     */
    suspend fun fileStatus(directory: String? = null): ApiResult<List<FileStatusDto>> =
        call("/file/status" + queryString("directory" to directory)) { text ->
            json.decodeFromString<List<FileStatusDto>>(text)
        }

    /**
     * `GET /find?pattern=` -> ripgrep 形式の `Match[]`。**`pattern` は required**。
     *
     * **サーバーが10件で打ち切る**([FIND_SERVER_CAP])。spec に `limit` が無いので
     * アプリからは増やせない。ちょうど10件返ったら「これ以上あるかもしれない」を画面に出すこと。
     */
    suspend fun findText(pattern: String, directory: String? = null): ApiResult<List<FindMatchDto>> =
        call("/find" + queryString("pattern" to pattern, "directory" to directory)) { text ->
            json.decodeFromString<List<FindMatchDto>>(text)
        }

    /**
     * `GET /find/file?query=&limit=` -> `string[]`。**`limit` は必ず送る**(§5b Q8 スコープ4)。
     *
     * spec の上限は 200 で、超えると 400(実測: `limit=999` で
     * `Expected a value less than or equal to 200, got 999`)。
     * `dirs` / `type` は送らない —— `dirs` は **boolean ではなく文字列 enum** で、
     * 既定(未指定)はファイルもディレクトリも返る。
     */
    suspend fun findFiles(
        query: String,
        limit: Int = FIND_FILE_LIMIT,
        directory: String? = null,
    ): ApiResult<List<String>> =
        call(
            "/find/file" + queryString(
                "query" to query,
                "limit" to limit.toString(),
                "directory" to directory,
            ),
        ) { text -> json.decodeFromString<List<String>>(text) }

    /**
     * `GET /find/symbol?query=` -> `Symbol[]`。**この環境では常に `[]`**(LSP が動いていない)。
     *
     * **200 と `[]` からは「無い」と「索引が使えない」が区別できない。**
     * 区別は [dev.opencode.android.ui.FileBrowserController] の校正クエリが付ける
     * ([SYMBOL_INDEX_PROBE])—— この口自体は区別を作らない。
     */
    suspend fun findSymbols(query: String, directory: String? = null): ApiResult<List<SymbolDto>> =
        call("/find/symbol" + queryString("query" to query, "directory" to directory)) { text ->
            json.decodeFromString<List<SymbolDto>>(text)
        }

    // ---- Q9: ターミナル(PTY)----

    /**
     * `GET /pty/shells` -> `PtyShell[]`。**要素はオブジェクト**(`{path, name, acceptable}`)で、
     * QUALITY_PLAN §5b が「利用可能なシェル一覧」としか書いていない部分の実物である。
     * 実測値は [PtyShellDto] の doc。
     */
    suspend fun listPtyShells(directory: String? = null): ApiResult<List<PtyShellDto>> =
        call("/pty/shells" + queryString("directory" to directory)) { text ->
            json.decodeFromString<List<PtyShellDto>>(text)
        }

    /**
     * `GET /pty` -> `Pty[]`。**走っているものしか載らない。**
     *
     * 終了したPTYは一覧から消える(実測: `exit 7` の直後に `[]`)。したがって
     * 「一覧に居ない」は「終わった」と「そもそも作っていない」の両方でありうる ——
     * その区別は呼び出し側が自分の記録と突き合わせて付ける
     * ([dev.opencode.android.ui.PtyController])。
     */
    suspend fun listPtys(directory: String? = null): ApiResult<List<PtyDto>> =
        call("/pty" + queryString("directory" to directory)) { text ->
            json.decodeFromString<List<PtyDto>>(text)
        }

    /**
     * `POST /pty {command, args, title}` -> `Pty`(200)。
     *
     * **`cwd` / `env` は送らない**([CreatePtyRequest] の doc)。
     *
     * **これは任意コマンド実行である。** QUALITY_PLAN §5b Q9 の「セキュリティ上の注記」の通り、
     * 無認証で起動できる serve に対しては、この口はそのまま端末上のシェルになる。
     */
    suspend fun createPty(
        command: String,
        args: List<String> = emptyList(),
        title: String,
        directory: String? = null,
    ): ApiResult<PtyDto> {
        val payload = json.encodeToString(CreatePtyRequest(command, args, title))
            .toRequestBody("application/json".toMediaType())
        return call("/pty" + queryString("directory" to directory), extra = { it.post(payload) }) { text ->
            json.decodeFromString<PtyDto>(text)
        }
    }

    /**
     * `PUT /pty/{ptyID} {size:{rows,cols}}` -> `Pty`(200)。**リサイズの経路はここ**である
     * (WebSocket 経由ではない。実測 2026-08-30 —— QUALITY_PLAN §5b の実機検証タスクの答え)。
     */
    suspend fun resizePty(
        ptyId: String,
        rows: Int,
        cols: Int,
        directory: String? = null,
    ): ApiResult<PtyDto> {
        val payload = json.encodeToString(UpdatePtyRequest(size = PtySizeDto(rows = rows, cols = cols)))
            .toRequestBody("application/json".toMediaType())
        val path = "/pty/$ptyId" + queryString("directory" to directory)
        return call(path, extra = { it.put(payload) }) { text -> json.decodeFromString<PtyDto>(text) }
    }

    /**
     * `DELETE /pty/{ptyID}` -> 200 + ボディ `true`。存在しなければ 404(実測)。
     *
     * **`pty.deleted` は流れるが `pty.exited` は流れない**(実測)。つまり
     * **明示削除からは終了コードが取れない**。取れなかったことを 0 と書かないこと。
     */
    suspend fun deletePty(ptyId: String, directory: String? = null): ApiResult<Unit> =
        callNoBody("/pty/$ptyId" + queryString("directory" to directory)) { it.delete() }

    /**
     * `POST /pty/{ptyID}/connect-token` -> `PtyTicketConnectToken`。
     *
     * **`x-opencode-ticket` ヘッダが必須である**(実測 2026-08-30):
     *
     * ```
     * POST /pty/{id}/connect-token                      -> 403 {"_tag":"PtyForbiddenError",…}
     * POST /pty/{id}/connect-token  x-opencode-ticket:1 -> 200 {"ticket":"…","expires_in":60}
     * ```
     *
     * spec にはこのヘッダが載っていない。**チケットは単回使用**なので、
     * 再接続のたびにここを叩き直すこと(実測: 同じチケットの2本目は接続に失敗する)。
     */
    suspend fun ptyConnectToken(ptyId: String, directory: String? = null): ApiResult<PtyTicketDto> {
        val payload = "".toRequestBody("application/json".toMediaType())
        val path = "/pty/$ptyId/connect-token" + queryString("directory" to directory)
        return call(
            path,
            extra = { it.post(payload).header(PTY_TICKET_HEADER, PTY_TICKET_HEADER_VALUE) },
        ) { text -> json.decodeFromString<PtyTicketDto>(text) }
    }

    /**
     * `ws://…/pty/{ptyID}/connect?ticket=…[&cursor=N]` の URL を組む。
     *
     * **URL 組み立てをここに集めるのは Q7 レビュー minor-2 と同じ理由である** ——
     * `?messageID=` を `?messageId=` に変える変異が 610件全緑で通り抜け、実物 serve は
     * 未知のクエリキーを黙って無視した。ここも同じで、`ticket` を書き間違えれば
     * 接続が黙って失敗し、`cursor` を書き間違えれば**毎回全部を再生する**(症状は
     * 「回転のたびに画面の先頭からもう一度流れる」で、クラッシュしない)。
     *
     * `Q9HttpContractTest` が本物のサーバーを立てて、撃たれたパス+クエリを assert する。
     *
     * **`cursor` は「渡さない」と「-1」で意味が違う**(実測):
     *  - 省略 / `0` … リプレイを全部再送する
     *  - `-1` … リプレイ無し(以後のライブ出力だけ)
     *  - `N` … N バイト目以降を再送する
     */
    fun ptyConnectUrl(ptyId: String, ticket: String, cursor: Long?, directory: String? = null): String =
        webSocketBaseUrl(baseUrl) + "/pty/$ptyId/connect" + queryString(
            "ticket" to ticket,
            "cursor" to cursor?.toString(),
            "directory" to directory,
        )

    /**
     * PTY の WebSocket を開く。**REST 用の [client] を使わない。**
     *
     * 理由は readTimeout である: REST 側は 15 秒で切るように作ってあり、その設定のまま
     * WebSocket を張ると、**15 秒何も打たなかっただけで接続が落ちる**。シェルは
     * 何も出力しないのが普通の状態なので、これは「放っておくと必ず切れる端末」になる。
     */
    fun openPtyWebSocket(url: String, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic("opencode", basicPassword, Charsets.UTF_8))
            .build()
        return webSocketClient.newWebSocket(request, listener)
    }

    /**
     * WebSocket 専用クライアント。**読み取りタイムアウト無し + ping 有り**。
     *
     * ping を入れるのは、モバイル網や NAT が黙って落とした接続を
     * 「まだ繋がっている」と誤認しないため —— 誤認すると再接続の契機が消え、
     * 画面は「接続中」のまま1文字も増えなくなる(EventDelivery.kt の設計注記と同じ壊れ方)。
     */
    private val webSocketClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * `?a=1&b=2` を組む。**値が null のキーは丸ごと落とす**(空文字のキーを送らない)。
     *
     * ## なぜ1か所に集めるのか(Q7 レビュー minor-2)
     *
     * レビューが `?messageID=` を `?messageId=` に変える変異を打ったところ、
     * **610件が全緑のまま通り抜けた**。しかも**実物 serve は未知のクエリキーを黙って無視する**
     * (レビュー実測: `?messageId=msg_x` -> 200)ので、症状は
     * 「**メッセージの差分を開いたのにセッション全体の差分が無言で出る**」になる。
     * URL を各メソッドが素で文字列連結していると、**撃たれた URL を見張る場所が無い**。
     *
     * ここに集めたうえで、`Q7HttpContractTest` が**本物の HTTP サーバーを立てて
     * 撃たれたクエリ文字列そのものを assert する**。Q8 は `/file` `/find` で
     * URL 組み立てがさらに増えるので、その検出器をここで用意しておく。
     *
     * キーの順序は引数の順序を保つ(テストが文字列で比較できるようにするため)。
     */
    private fun queryString(vararg params: Pair<String, String?>): String {
        val pairs = params.mapNotNull { (key, value) ->
            value?.let { key + "=" + encodeQueryValue(it) }
        }
        return if (pairs.isEmpty()) "" else "?" + pairs.joinToString("&")
    }

    /**
     * クエリ値のURLエンコード。`java.net.URLEncoder` は空白を `+` にするが、
     * これはクエリ文字列の application/x-www-form-urlencoded 規則であって
     * 検索語をそのまま渡すには合っている(サーバー側でデコードされる)。
     * `%` や `&` を含む検索語でURLが壊れないことが目的。
     */
    private fun encodeQueryValue(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** 2xxなら成功(レスポンスボディをパースしない)。 */
    private suspend fun callNoBody(
        path: String,
        extra: (Request.Builder) -> Request.Builder,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            client.newCall(extra(requestBuilder(path)).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ApiResult.Err(ApiError.Http(resp.code))
                } else {
                    ApiResult.Ok(Unit)
                }
            }
        } catch (e: IOException) {
            ApiResult.Err(ApiError.Network(e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun <T> call(
        path: String,
        extra: (Request.Builder) -> Request.Builder = { it },
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(extra(requestBuilder(path)).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    ApiResult.Err(ApiError.Http(resp.code))
                } else {
                    try {
                        ApiResult.Ok(parse(text))
                    } catch (_: SerializationException) {
                        ApiResult.Err(ApiError.Network("レスポンス形式が契約と一致しません"))
                    }
                }
            }
        } catch (e: IOException) {
            ApiResult.Err(ApiError.Network(e.message ?: e.javaClass.simpleName))
        }
    }
}
