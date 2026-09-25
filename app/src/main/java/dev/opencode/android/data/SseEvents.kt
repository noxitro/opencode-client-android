package dev.opencode.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GET /event のSSEイベント。envelope {id, type, properties} の type で判別する
 * (docs/API_CONTRACT.md「SSEイベント」)。
 *
 * 未知のtypeは [Ignored] にして破棄する。パース失敗でストリームを落とさないこと。
 */
sealed interface SseEvent {
    /** message.part.updated — properties必須: {sessionID, part, time}。チャット逐次描画の本体。 */
    data class PartUpdated(val sessionID: String?, val part: PartDto) : SseEvent

    /** message.updated — properties必須: {sessionID, info: Message}。info.roleでメッセージのroleを確定する。 */
    data class MessageUpdated(val sessionID: String?, val info: MessageInfoDto?) : SseEvent

    /** session.idle — properties必須: {sessionID}。実行完了→入力欄復帰。 */
    data class SessionIdle(val sessionID: String?) : SseEvent

    /**
     * session.error — propertiesはspec上必須項目なし(任意のsessionID/error)。
     * sessionIDはベストエフォート(null=全体エラー扱い)。errorがあれば表示用に保持する。
     */
    data class SessionError(
        val sessionID: String?,
        val error: String?,
        /**
         * Q4: `error.data.statusCode`(実測: 402 が入っていた)。読めなければ null。
         * **R3 の一次証拠**である —— 402/401/403/404 は再試行しても同じ結果になるので、
         * 「再試行」ではなく「モデルを変更」を出す根拠になる。
         */
        val statusCode: Int? = null,
        /**
         * Q4: `error.data.isRetryable`(実測: 402 のとき `false`)。読めなければ null。
         * **null は「再試行してよい」ではなく「分からない」**。判定は [errorSuggestsModelChange]。
         */
        val retryable: Boolean? = null,
    ) : SseEvent

    /** server.connected — 接続確立の目印。 */
    data object ServerConnected : SseEvent

    /**
     * session.status — properties必須: `{sessionID, status: SessionStatus}`(両方required)。
     * 一覧の実行状態バッジ。`status.type` は idle|busy|retry(API_CONTRACT.md「Q1で使用する分」)。
     */
    data class SessionStatusChanged(val sessionID: String?, val status: SessionStatusDto?) : SseEvent

    /**
     * session.created / session.updated / session.deleted — properties必須: `{sessionID, info: Session}`。
     * 3つとも同形なので [kind] で区別する(実測 2026-08-27: create/patch/delete で
     * それぞれ発火し、deleted の `info` は**削除前**のSession)。
     */
    data class SessionInfoChanged(
        val kind: Kind,
        val sessionID: String?,
        val info: SessionDto?,
    ) : SseEvent {
        enum class Kind { CREATED, UPDATED, DELETED }
    }

    /**
     * `todo.updated` — properties必須: `{sessionID, todos: Todo[]}`(両方required)。
     * タスクリストの逐次更新(API_CONTRACT.md「Q3で使用する分」)。
     */
    data class TodoUpdated(val sessionID: String?, val todos: List<TodoDto>) : SseEvent

    /**
     * `session.next.model.switched` / `session.next.agent.switched`(Q4)。
     * **両方の発火を実物 serve で実測してある**(API_CONTRACT.md「SSEイベント(追加分)」)。
     *
     * properties(spec required): model 側は `{timestamp, sessionID, messageID, model: ModelRef}`、
     * agent 側は `{timestamp, sessionID, messageID, agent: string}`。
     *
     * **これは表示の追随にしか使わない。** モデルの権威は `GET /session/{id}` である ——
     * 切替は `session.updated` を流さず、切れている間のイベントは購読者ゼロで消えるので、
     * イベント列だけを状態の出所にすると別クライアントの切替を永久に見落とす
     * (RUN_PLAN 決定2)。ここは「開いている画面をすぐ追随させる」ためだけの近道である。
     */
    data class SessionNextChanged(
        val sessionID: String?,
        /** model 側のみ。**`ModelRef`(`{id, providerID, variant?}`)**であって `modelID` ではない。 */
        val model: ModelRefDto? = null,
        /** agent 側のみ。 */
        val agent: String? = null,
    ) : SseEvent

    /**
     * `pty.created` / `pty.updated` / `pty.exited` / `pty.deleted`(Q9)。
     *
     * ## このイベントは Q9 の**唯一の終了コード源**である
     *
     * 実測(2026-08-30、実物 serve 1.18.21): `cmd.exe` に `exit 7` を送ると
     *
     * ```
     * {"id":"evt_…","type":"pty.exited","properties":{"id":"pty_…","exitCode":7}}
     * ```
     *
     * が流れる。**同じ瞬間に PTY は REST から消える** —— `GET /pty` は `[]`、
     * `GET /pty/{id}` は 404 になるので、`status:"exited"` と `exitCode` を
     * REST から読める窓が存在しない。QUALITY_PLAN §5b Q9 スコープ4 が
     * 「`GET /pty` の一覧」から取れると書いているのは**実測と食い違う**。
     *
     * `DELETE /pty/{id}` は `pty.deleted` を流し、**`exitCode` を持たない**(実測)。
     * したがって「自分で消した」と「プロセスが終わった」は別のイベントであり、
     * 前者から終了コードは取れない。**取れなかったものを 0 と書かないこと。**
     *
     * ## 形
     *
     * `created` / `updated` は `properties: {info: Pty}`、`exited` は
     * `properties: {id, exitCode}`、`deleted` は `properties: {id}`。
     * **どのフィールドも必須にしない** —— P4 で2度踏んだ形
     * (必須宣言したキーが欠けてイベントごと消える)を繰り返さない。
     */
    data class PtyLifecycle(
        val kind: Kind,
        /** `^pty` のID。`created`/`updated` では [info] から取る。 */
        val id: String?,
        val info: PtyDto? = null,
        /**
         * `pty.exited` の終了コード。**null は「0」ではなく「サーバーが言っていない」**。
         * `deleted` には原理的に付かない。
         */
        val exitCode: Int? = null,
    ) : SseEvent {
        enum class Kind { CREATED, UPDATED, EXITED, DELETED }
    }

    /** 契約に無い/未対応のtype。呼び出し側は破棄してよい。type名はデバッグ用。 */
    data class Ignored(val type: String) : SseEvent
}

/**
 * `question.asked` — properties: `{id:"que..", sessionID, questions: QuestionInfo[], tool?}`。
 * spec の required は `id` `sessionID` `questions` だが、**どれも必須宣言しない**。
 *
 * 理由は P4 で2度踏んだ形そのもの: 必須宣言したフィールドが実際には別名/欠損で届くと
 * デシリアライズが失敗して [SseEvent.Ignored] へ落ち、**カードが一度も出ない**。
 * `metadata` を `String?` と宣言して permission ダイアログが一度も出なかったのと同じ道である。
 * 実物での発火は 402 のため観測できていない(API_CONTRACT.md「実物で確認できなかったこと」)ので、
 * **観測できていないものを必須にしない**。
 */
@Serializable
data class QuestionAskedEvent(
    /** `^que` の要求ID。**`asked` は `id`、`replied`/`rejected` は `requestID`** —— キー名が違う。 */
    val id: String? = null,
    val sessionID: String? = null,
    val questions: List<QuestionInfoDto> = emptyList(),
    val tool: PermissionToolDto? = null,
) : SseEvent

/**
 * `question.replied` / `question.rejected` —— **同じ形で片付く**ので1つの型にまとめ、[kind] で分ける。
 *
 * properties(spec): replied は `{sessionID, requestID, answers}`、rejected は `{sessionID, requestID}`。
 * **`id` ではなく `requestID`** である。`asked` の `id` で作ったカードを
 * この `requestID` で閉じるので、**片方を書き間違えるとカードが永久に閉じない** ——
 * P4 の `PermissionRepliedEvent` が `{id, sessionID, response}` をパースできず
 * 「応答成功後もダイアログが閉じなかった」のと同一構造である。
 *
 * したがって `requestID` も**必須にしない**。欠けて届いても捨てず、
 * 呼び出し側は「このセッションの未応答の質問」を対象にフォールバックできる。
 * `answers` も任意(rejected には無い)。
 */
@Serializable
data class QuestionResolvedEvent(
    val sessionID: String? = null,
    val requestID: String? = null,
    val answers: List<List<String>> = emptyList(),
    /** replied か rejected か。envelope の type から決まるので @Transient ではなく後付けする。 */
    @kotlinx.serialization.Transient val kind: Kind = Kind.REPLIED,
) : SseEvent {
    enum class Kind { REPLIED, REJECTED }
}

/**
 * permission.asked — properties: {id, sessionID, permission, patterns[], metadata, always[], tool?{messageID,callID}}。
 * 承認ダイアログ表示用。未知フィールドは ignoreUnknownKeys で無視。
 */
@Serializable
data class PermissionAskedEvent(
    val id: String,
    val sessionID: String? = null,
    val permission: String,
    val patterns: List<String> = emptyList(),
    // 契約上はオブジェクト({command:...}等)。実測(e2e-stub, 実物serve)でもオブジェクトが来る。
    // String? と宣言すると SerializationException になり、パースが Ignored に落ちて
    // 承認ダイアログが一度も出ない。表示には任意の形を許して文字列化する。
    val metadata: JsonElement? = null,
    val always: List<String> = emptyList(),
    val tool: PermissionToolDto? = null,
) : SseEvent

/**
 * permission.replied — properties: 同系(permission.askedと同形)。
 * ダイアログ解消用。未知フィールドは ignoreUnknownKeys で無視。
 */
@Serializable
data class PermissionRepliedEvent(
    val id: String,
    val sessionID: String? = null,
    // asked と違い必須にしない。契約は replied を「(同系)」としか書いておらず形が確定しておらず、
    // 実測(e2e-stub)では {id, sessionID, response} だけが来る。必須にすると
    // デシリアライズに失敗して Ignored に落ち、**ダイアログが永久に閉じない**。
    // 閉じる判断に要るのは id だけなので、他は任意で受ける。
    val permission: String? = null,
    val response: String? = null,
    val patterns: List<String> = emptyList(),
    // 契約上はオブジェクト({command:...}等)。実測(e2e-stub, 実物serve)でもオブジェクトが来る。
    // String? と宣言すると SerializationException になり、パースが Ignored に落ちて
    // 承認ダイアログが一度も出ない。表示には任意の形を許して文字列化する。
    val metadata: JsonElement? = null,
    val always: List<String> = emptyList(),
    val tool: PermissionToolDto? = null,
) : SseEvent

/**
 * `session.error` の `error` から、人に見せられる一行を取り出す。形が読めなければ null。
 *
 * 実測された形はオブジェクト(`{name, data:{message, statusCode, ...}}`)だが、契約は形を定めて
 * いないため文字列でも通す。どちらでもなければ黙って null を返す——ここで例外を投げると
 * イベントそのものが消え、「エラーを出す」ためのコードが「エラーを出さなくする」コードになる。
 */
internal fun errorMessageOf(element: JsonElement?): String? {
    if (element == null) return null
    (element as? JsonPrimitive)?.let { return it.contentOrNull?.takeIf(String::isNotBlank) }
    val obj = element as? JsonObject ?: return null
    val message = (obj["data"] as? JsonObject)?.get("message") as? JsonPrimitive
    message?.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    return name
}

/**
 * `session.error` の `error.data.statusCode`(Q4)。読めなければ null。
 *
 * 実測(2026-08-27、実物 serve): `{"name":"APIError","data":{"statusCode":402,"isRetryable":false,...}}`。
 * **文字列で来ても読む** —— 数値と決め打つと、そうでない実装で黙って null に落ちる。
 */
internal fun errorStatusCodeOf(element: JsonElement?): Int? {
    val data = (element as? JsonObject)?.get("data") as? JsonObject ?: return null
    val raw = data["statusCode"] as? JsonPrimitive ?: return null
    return raw.contentOrNull?.toIntOrNull()
}

/**
 * `session.error` の `error.data.isRetryable`(Q4)。読めなければ null。
 * **null と false を混同しない**: null は「サーバーが言っていない」であって「再試行するな」ではない。
 */
internal fun errorRetryableOf(element: JsonElement?): Boolean? {
    val data = (element as? JsonObject)?.get("data") as? JsonObject ?: return null
    val raw = data["isRetryable"] as? JsonPrimitive ?: return null
    return raw.contentOrNull?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}

/** envelopeのid/type以外のフィールドは見ない。type不明・JSON不正はnull(呼び出し側で破棄)。 */
fun parseSseEnvelope(text: String): SseEvent? {
    val root = try {
        contractJson.parseToJsonElement(text).jsonObject
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    val typeName = try {
        root["type"]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    val properties = root["properties"] as? JsonObject ?: JsonObject(emptyMap())
    fun propSessionId(): String? = properties["sessionID"]?.jsonPrimitive?.contentOrNull
    return when (typeName) {
        "message.part.updated" -> {
            // part要素のshapeが崩れてもイベント単位で捨てる(ストリームは落とさない)
            val partElement = properties["part"] ?: return SseEvent.Ignored(typeName)
            val part = try {
                contractJson.decodeFromJsonElement(PartDto.serializer(), partElement)
            } catch (_: SerializationException) {
                return SseEvent.Ignored(typeName)
            } catch (_: IllegalArgumentException) {
                return SseEvent.Ignored(typeName)
            }
            SseEvent.PartUpdated(sessionID = propSessionId(), part = part)
        }
        "message.updated" -> {
            val infoElement = properties["info"]
            val info = if (infoElement != null) {
                try {
                    contractJson.decodeFromJsonElement(MessageInfoDto.serializer(), infoElement)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            } else null
            SseEvent.MessageUpdated(sessionID = propSessionId(), info = info)
        }
        "session.idle" -> SseEvent.SessionIdle(sessionID = propSessionId())
        "session.error" -> {
            // 実測 2026-08-25(実物 serve 1.18.21): error はオブジェクトで届く。
            //   "error":{"name":"APIError","data":{"message":"...","statusCode":429,...}}
            // 文字列と決め打って `jsonPrimitive` を呼ぶと JsonObject に対して例外を投げ、
            // それがこの関数を抜けて onEvent まで達し、**エラーイベントごと消える**。
            // このファイルの他の危険なアクセスが全て try で囲われているのはそのため。
            // 契約(docs/API_CONTRACT.md)は error の形を定めていないので、両方に耐える。
            val errorElement = properties["error"]
            SseEvent.SessionError(
                sessionID = propSessionId(),
                error = errorMessageOf(errorElement),
                // Q4: 「そのモデルが落ちている」を機械的に判定する材料(R3)。
                statusCode = errorStatusCodeOf(errorElement),
                retryable = errorRetryableOf(errorElement),
            )
        }
        "server.connected" -> SseEvent.ServerConnected
        "session.status" -> {
            // status が読めなくても sessionID だけは活かす(読めない=状態不明→idle扱いへ倒す)。
            val status = properties["status"]?.let { element ->
                try {
                    contractJson.decodeFromJsonElement(SessionStatusDto.serializer(), element)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            SseEvent.SessionStatusChanged(sessionID = propSessionId(), status = status)
        }
        "session.created", "session.updated", "session.deleted" -> {
            val kind = when (typeName) {
                "session.created" -> SseEvent.SessionInfoChanged.Kind.CREATED
                "session.updated" -> SseEvent.SessionInfoChanged.Kind.UPDATED
                else -> SseEvent.SessionInfoChanged.Kind.DELETED
            }
            val info = properties["info"]?.let { element ->
                try {
                    contractJson.decodeFromJsonElement(SessionDto.serializer(), element)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            // deleted は info が読めなくても sessionID だけで一覧から消せる。
            SseEvent.SessionInfoChanged(kind = kind, sessionID = propSessionId(), info = info)
        }
        "permission.asked" -> {
            val event = try {
                contractJson.decodeFromJsonElement(PermissionAskedEvent.serializer(), properties)
            } catch (_: SerializationException) {
                return SseEvent.Ignored(typeName)
            } catch (_: IllegalArgumentException) {
                return SseEvent.Ignored(typeName)
            }
            event
        }
        "permission.replied" -> {
            val event = try {
                contractJson.decodeFromJsonElement(PermissionRepliedEvent.serializer(), properties)
            } catch (_: SerializationException) {
                return SseEvent.Ignored(typeName)
            } catch (_: IllegalArgumentException) {
                return SseEvent.Ignored(typeName)
            }
            event
        }
        // ---- Q3: todo / question ----
        //
        // **v1 と v2 の両方の type 名を同じイベントとして受ける。**
        // spec 上 `question.asked` と `question.v2.asked` は shape が完全に同一
        // (`QuestionV2Info` ≡ `QuestionInfo`)で、**どちらの名前で届くかは実物で観測できていない**
        // (402 でモデルが回らない)。分岐する理由が無く、外した場合の損失
        // (カードが一度も出ない)だけが大きい。
        "todo.updated" -> {
            // todos が読めなくても sessionID は活かす —— 空リストは「タスクが無くなった」を
            // 表す正しい状態である(サーバーは全消し時に空配列を流す)。
            val todos = properties["todos"]?.let { element ->
                try {
                    contractJson.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(TodoDto.serializer()),
                        element,
                    )
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: return SseEvent.Ignored(typeName)
            SseEvent.TodoUpdated(sessionID = propSessionId(), todos = todos)
        }
        // ---- Q4: モデル / エージェント切替 ----
        //
        // **`model` が読めなくてもイベントを捨てない。** `sessionID` だけ分かれば
        // 「このセッションの何かが変わった」ことは伝わり、呼び出し側は
        // `GET /session/{id}` で取り直せる。捨てると取り直しの契機ごと失う。
        "session.next.model.switched" -> {
            val model = properties["model"]?.let { element ->
                try {
                    contractJson.decodeFromJsonElement(ModelRefDto.serializer(), element)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            SseEvent.SessionNextChanged(sessionID = propSessionId(), model = model)
        }
        "session.next.agent.switched" -> {
            val agent = try {
                properties["agent"]?.jsonPrimitive?.contentOrNull
            } catch (_: IllegalArgumentException) {
                null
            }
            SseEvent.SessionNextChanged(sessionID = propSessionId(), agent = agent)
        }
        "question.asked", "question.v2.asked" -> {
            try {
                contractJson.decodeFromJsonElement(QuestionAskedEvent.serializer(), properties)
            } catch (_: SerializationException) {
                SseEvent.Ignored(typeName)
            } catch (_: IllegalArgumentException) {
                SseEvent.Ignored(typeName)
            }
        }
        "question.replied", "question.v2.replied",
        "question.rejected", "question.v2.rejected",
        -> {
            val kind = if (typeName.endsWith("rejected")) {
                QuestionResolvedEvent.Kind.REJECTED
            } else {
                QuestionResolvedEvent.Kind.REPLIED
            }
            try {
                contractJson.decodeFromJsonElement(QuestionResolvedEvent.serializer(), properties)
                    .copy(kind = kind)
            } catch (_: SerializationException) {
                SseEvent.Ignored(typeName)
            } catch (_: IllegalArgumentException) {
                SseEvent.Ignored(typeName)
            }
        }
        // ---- Q9: PTY ----
        //
        // **`pty.exited` を落とすと終了コードが二度と手に入らない。** REST 側は
        // プロセスが終わった瞬間に 404 になるので、ここが唯一の出所である
        // ([SseEvent.PtyLifecycle] の doc)。したがって `info` が読めなくても
        // イベントを捨てない —— `id` だけでも「そのPTYは終わった」は伝わる。
        "pty.created", "pty.updated", "pty.exited", "pty.deleted" -> {
            val kind = when (typeName) {
                "pty.created" -> SseEvent.PtyLifecycle.Kind.CREATED
                "pty.updated" -> SseEvent.PtyLifecycle.Kind.UPDATED
                "pty.exited" -> SseEvent.PtyLifecycle.Kind.EXITED
                else -> SseEvent.PtyLifecycle.Kind.DELETED
            }
            val info = properties["info"]?.let { element ->
                try {
                    contractJson.decodeFromJsonElement(PtyDto.serializer(), element)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            // `exited`/`deleted` は `properties.id`、`created`/`updated` は `info.id`。
            // **両方見る** —— 片方だけを見ると、片方の type でIDが null になる。
            val id = try {
                properties["id"]?.jsonPrimitive?.contentOrNull
            } catch (_: IllegalArgumentException) {
                null
            } ?: info?.id?.takeIf { it.isNotBlank() }
            // **文字列で来ても読む**(`errorStatusCodeOf` と同じ理由)。数値と決め打つと
            // そうでない実装で黙って null に落ち、「取れなかった」が「0」に化ける。
            val exitCode = try {
                properties["exitCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            } catch (_: IllegalArgumentException) {
                null
            }
            SseEvent.PtyLifecycle(kind = kind, id = id, info = info, exitCode = exitCode)
        }
        else -> SseEvent.Ignored(typeName)
    }
}
