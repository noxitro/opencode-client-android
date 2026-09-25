package dev.opencode.android.ui

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.MessageInfoDto
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import dev.opencode.android.data.PartDto
import dev.opencode.android.data.PermissionAskedEvent
import dev.opencode.android.data.PermissionRequestDto
import dev.opencode.android.data.PermissionRepliedEvent
import dev.opencode.android.data.SseEvent

/**
 * チャット画面の表示モデルとSSE適用(マージ)ロジック。Android非依存の純関数として
 * ユニットテスト可能にする(AppViewModelから分離)。
 */

/** 承認ダイアログの表示状態。画面回転で消えないよう rememberSaveable で保持する。 */
data class PermissionDialogState(
    val permissionId: String,
    val sessionId: String,
    val permission: String,
    val metadata: String?,
    val patterns: List<String>,
    val always: List<String>,
    val tool: String?, // tool名のみ表示用に抽出
)

/**
 * 回転を跨いで保持するための Saver。
 *
 * `rememberSaveable` の既定実装は Bundle に入る型しか扱えず、データクラスをそのまま渡すと
 * 保存時に IllegalStateException を投げて**アプリごと落ちる**。実測(2026-08-24、
 * e2e-artifacts/P4/02-dialog-rotated.png)では承認ダイアログ表示中の回転でホーム画面に戻った。
 * 全フィールドが String かその List なので、平坦な配列に落とせば既定の Bundle 保存で足りる。
 */
val PermissionDialogStateSaver: Saver<PermissionDialogState?, Any> = listSaver(
    save = { state ->
        if (state == null) emptyList()
        else listOf(
            state.permissionId,
            state.sessionId,
            state.permission,
            state.metadata ?: "",
            state.patterns,
            state.always,
            state.tool ?: "",
        )
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            @Suppress("UNCHECKED_CAST") val patterns = saved[4] as List<String>
            @Suppress("UNCHECKED_CAST") val always = saved[5] as List<String>
            PermissionDialogState(
                permissionId = saved[0] as String,
                sessionId = saved[1] as String,
                permission = saved[2] as String,
                metadata = (saved[3] as String).ifEmpty { null },
                patterns = patterns,
                always = always,
                tool = (saved[6] as String).ifEmpty { null },
            )
        }
    },
)

/** permission.asked イベントから PermissionDialogState を生成する。 */
fun applyPermissionEvent(event: PermissionAskedEvent): PermissionDialogState =
    permissionDialogOf(
        id = event.id,
        sessionID = event.sessionID,
        permission = event.permission,
        metadata = event.metadata,
        patterns = event.patterns,
        always = event.always,
    )

/**
 * `GET /permission` の要素から PermissionDialogState を生成する(Q6 / 申し送り Q5-2)。
 *
 * **`permission.asked` と同じ関数を通す。** 2か所に書くと、片方だけ直した状態が
 * 「画面は正常に見える」まま残る(このプロジェクトが何度も踏んだ形)。
 */
fun permissionDialogOf(dto: PermissionRequestDto): PermissionDialogState =
    permissionDialogOf(
        id = dto.id,
        sessionID = dto.sessionID,
        permission = dto.permission,
        metadata = dto.metadata,
        patterns = dto.patterns,
        always = dto.always,
    )

private fun permissionDialogOf(
    id: String,
    sessionID: String?,
    permission: String,
    metadata: JsonElement?,
    patterns: List<String>,
    always: List<String>,
): PermissionDialogState {
    // tool名は permission フィールドから抽出 (例: "tool:read" -> "read")
    val toolName = permission.removePrefix("tool:")
    return PermissionDialogState(
        permissionId = id,
        sessionId = sessionID.orEmpty(),
        permission = permission,
        // イベントの metadata は任意形(実測ではオブジェクト)。UI が要るのは表示用の一行なので、
        // ここで一度だけ文字列に落とす。JsonPrimitive なら引用符を外し、それ以外はそのまま出す。
        metadata = metadata?.let { meta ->
            (meta as? JsonPrimitive)?.contentOrNull ?: meta.toString()
        },
        patterns = patterns,
        always = always,
        tool = toolName,
    )
}

/**
 * サーバーの未応答 permission と、いま画面が持っているダイアログを突き合わせる
 * (Q6 / 申し送り Q5-2)。**Q3 の [mergePendingQuestions] と同じ規則**にしてある。
 *
 * 規則:
 *  - 手元のダイアログがサーバーの一覧にも居る → **そのまま残す**(作り直さない)
 *  - 手元のダイアログがサーバーに居ない:
 *      - **取得を始める前から知っていた** → サーバーが決着済みと言っている。畳んで、
 *        代わりにサーバー側の先頭を出す(別クライアントが答えた場合がこれ)
 *      - **取得中に届いた**(`knownBeforeFetch` に無い) → **畳まない**。
 *        取得が始まった時点のサーバーはそれを知らなかっただけである
 *        (Q3 が `knownBeforeFetch` を入れたのとまったく同じ理由)
 *  - 手元が空 → サーバー側の先頭を出す
 *
 * **先頭を採るのは、ダイアログが1枚しか出せないから**である。question はカードなので
 * 複数同時に描けるが、permission はモーダルで「答えるまで先へ進めない」ものなので
 * 同時に2つは出せない。残りは応答して1つ消えた次の取り直しで出る。
 * *複数 pending を1枚ずつ捌く形は Q3 の blocker-1 とは違う* —— あちらは
 * **2件目が1件目を破壊して復帰経路が無かった**が、ここは順番待ちであり
 * `GET /permission` が権威なので取りこぼしは起きない。
 */
fun mergePendingPermission(
    current: PermissionDialogState?,
    serverPending: List<PermissionDialogState>,
    knownBeforeFetch: Set<String>,
): PermissionDialogState? {
    if (current == null) return serverPending.firstOrNull()
    if (serverPending.any { it.permissionId == current.permissionId }) return current
    // サーバーが知らない。取得中に届いたものなら畳まない。
    if (current.permissionId !in knownBeforeFetch) return current
    return serverPending.firstOrNull()
}

/** permission.replied イベントでダイアログ状態をクリアする(nullを返す)。 */
fun clearPermissionDialog(event: PermissionRepliedEvent): PermissionDialogState? = null

/** チャット表示1パート。 [partId] がSSE差分更新のマージキー(nullは追記のみ)。 */
data class ChatPart(
    val partId: String?,
    /** Part.type の生値("text"/"tool"/"reasoning"/その他)。未知typeもそのまま保持しUIでプレースホルダ表示。 */
    val type: String,
    /** type=="text" / "reasoning" の本文。他typeは空。 */
    val text: String,
    /** type=="tool" の表示ラベル(tool名+status)。他typeはnull。 */
    val toolLabel: String?,
    /** type=="tool" の折り畳みカード用の詳細(Q2 スコープ2)。他typeはnull。 */
    val tool: ChatToolInfo? = null,
    /**
     * Q7: `type=="patch"` の `files`(§5b Q7 スコープ3)。他typeは空。
     *
     * **これが「N ファイル変更」のチップの材料である。** サーバーへ問い合わせずに
     * 件数が出せるのは、`PatchPart.files` がメッセージそのものに載っているから。
     * `GET /session/{id}/diff` を撃つのはチップを押したときだけにする。
     */
    val patchFiles: List<String> = emptyList(),
)

/**
 * ツール活動カード1枚分(QUALITY_PLAN §5 Q2 スコープ2)。
 *
 * 元は `ToolPart`(spec: required `id/sessionID/messageID/type/callID/tool/state`)。
 * `state` は Pending|Running|Completed|Error の anyOf で **required がバリアントごとに違う**ため、
 * ここは全部 nullable で受ける。表示は「あるものだけ出す」。
 */
data class ChatToolInfo(
    /** ツール名(`ToolPart.tool`)。欠損時は "tool"。 */
    val name: String,
    /** [ToolRunStatus] へ寄せた実行状態。未知文字列は [ToolRunStatus.UNKNOWN]。 */
    val status: ToolRunStatus,
    /** `ToolStateCompleted.title`(人向けの1行要約)。 */
    val title: String?,
    /** `state.input` を1行へ落としたもの。長い場合は [TOOL_INPUT_SUMMARY_MAX] で切る。 */
    val input: String?,
    /** `ToolStateCompleted.output`。 */
    val output: String?,
    /** `ToolStateError.error`。 */
    val error: String?,
    /**
     * Q8 スコープ6: `state.input` から拾ったファイルパス。**ここからビューアを開く。**
     * 拾えなければ null(導線を出さない)。抽出は [toolInputFilePath]。
     */
    val filePath: String? = null,
)

/**
 * ツールの `state.input` から**ファイルパスらしきもの**を1つ拾う(§5b Q8 スコープ6:
 * 「ツール活動カードのファイルパスをタップ → そのファイルをビューアで開く」)。
 *
 * ## 推測でキー名を書かない、の扱い
 *
 * `ToolState.input` は spec 上 `type: object` で**中身はツールごとに違う**。
 * つまり「どのキーにパスが入るか」は**契約に書かれていない** —— AGENTS.md が禁じている
 * 「推測でフィールド名を書く」に触れる領域である。
 *
 * したがってここは **契約の解釈ではなく、当たれば導線が1つ増える最善努力**として扱う:
 *
 *  - 候補キーを固定順で見る([TOOL_PATH_KEYS])。**最初に当たったものを採る**
 *  - 値が文字列でなければ捨てる(`JsonObject` を `String` として読むと落ちる。L3 の欠陥形)
 *  - 空文字は捨てる
 *  - **拾えなくても何も壊れない**(チップが出ないだけ)
 *
 * 拾えたパスは [normalizeServerPath] を通す —— エージェントが書く文字列は `/` 区切りで、
 * サーバーが返す `FileNode.path` は `\` 区切りである(実測)。
 */
fun toolInputFilePath(input: JsonObject?): String? {
    if (input == null) return null
    for (key in TOOL_PATH_KEYS) {
        val raw = (input[key] as? JsonPrimitive)?.contentOrNull ?: continue
        if (raw.isBlank()) continue
        return normalizeServerPath(raw)
    }
    return null
}

/**
 * `state.input` の中でファイルパスが入りうるキー。**順序に意味がある**(最初に当たったものを採る)。
 *
 * 実物のツール入力から採ったものではない —— 402 でエージェントが動かないため、
 * このリポジトリには**ツール入力の実データが1件も無い**。
 * **これは「実データで書いた」と主張できない唯一の箇所**なので、
 * 当たらないときに何も壊れない形にしてある(上の doc)。
 */
val TOOL_PATH_KEYS = listOf("filePath", "path", "file", "filename")

/** `ToolState.status`(spec の enum)。未知値でも落とさないため [UNKNOWN] を持つ。 */
enum class ToolRunStatus { PENDING, RUNNING, COMPLETED, ERROR, UNKNOWN }

/** ツールカードを展開したときに出す `state.input` の最大文字数(§5 Q2 スコープ2「長い場合は先頭N文字」)。 */
const val TOOL_INPUT_SUMMARY_MAX = 400

/**
 * `state.output` / `state.error` の最大文字数。
 *
 * 計画書スコープ2が切り詰めを要求しているのは `input` だけだが、**実データを見ると足りない**:
 * 実物 serve 4097 の実セッションから採取した `ToolStateCompleted` は `tool:"read"` で
 * `output` が**ファイル全文**だった(Q2レビュー minor-1)。切らないと、幅320dpのバブルに
 * 数十KBの単一 Text が入り、長押しコピーも同じ量を Clipboard へ渡す。
 * `input` より緩いのは、ツールの出力は「先頭数行が読めれば用が足りる」ことが多いため。
 */
const val TOOL_OUTPUT_SUMMARY_MAX = 1000

/**
 * カードに載せる長文の切り詰め。**切ったことと元の長さを本文に書く。**
 *
 * 単に `…` で終わると「ツールの出力がそこで終わった」のか「表示を切った」のかが
 * 画面から区別できない。全文が要るときに何を失っているかが分かる形にする。
 */
internal fun truncateToolText(value: String, max: Int): String =
    if (value.length <= max) value else value.take(max) + "…(全${value.length}文字)"

fun toolRunStatusOf(raw: String?): ToolRunStatus = when (raw) {
    "pending" -> ToolRunStatus.PENDING
    "running" -> ToolRunStatus.RUNNING
    "completed" -> ToolRunStatus.COMPLETED
    "error" -> ToolRunStatus.ERROR
    else -> ToolRunStatus.UNKNOWN
}

/**
 * `state.input`(spec: `type: object`、中身はツールごとに違う)を1行へ落とす。
 *
 * **文字列と決め打たない**のが要点。`input` を `String` として読もうとすると
 * `JsonObject` に対して例外になり、**ツールを見せるための変更がツールを見せなくする**
 * (L3 の `jsonPrimitive` 欠陥と同じ形)。ここは [JsonObject] のまま受け取り、
 * キー=値 を並べるだけにする。値が文字列なら引用符を外す。
 *
 * 空オブジェクト・null は null を返す(「入力なし」と「読めない」を区別しない —— どちらも出さない)。
 */
fun summarizeToolInput(input: JsonObject?, max: Int = TOOL_INPUT_SUMMARY_MAX): String? {
    if (input == null || input.isEmpty()) return null
    val body = input.entries.joinToString(", ") { (key, value) ->
        val rendered = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        "$key=$rendered"
    }
    return truncateToolText(body, max)
}

/**
 * 本文ではなく実行の区切りを表す part 種別(spec: StepStartPart / StepFinishPart / SnapshotPart)。
 * 描かない — 出せば一語の答えの周りに区切りの行が並ぶ(L3 欠陥F)。
 */
val LIFECYCLE_PART_TYPES = setOf("step-start", "step-finish", "snapshot")

/**
 * この part が**画面に何か描くか**。ChatScreen の描画分岐と対になっている
 * (片方だけ変えると「バブルは出るが中身は無い」が復活するので、判定はここ1か所)。
 *
 * - ライフサイクル part: 描かない
 * - tool: 常にラベルを描く
 * - reasoning: **本文があるときだけ**描く。空の「思考」の折り畳みだけが並ぶのを防ぐ
 *   (Q2 で足した分岐。これが無いと未知type扱いになり `(reasoning)` が出る)
 * - テキストがある: 描く
 * - テキストが無い未知type(patch等): `(patch)` と存在だけ描く
 * - type=="text" で本文が空: 描かない ← 空バブルの実体
 */
fun ChatPart.isRenderable(): Boolean = when {
    type in LIFECYCLE_PART_TYPES -> false
    type == "tool" -> true
    type == "reasoning" -> text.isNotBlank()
    text.isNotBlank() -> true
    type != "text" -> true
    else -> false
}

/** バブルの描き方。R1(QUALITY_PLAN §5 Q0 スコープ2)。 */
enum class BubbleKind {
    /** 描くべき part がある(通常表示)。 */
    CONTENT,

    /** 中身がまだ無い + セッション実行中の末尾 → 待ち表示。 */
    WAITING,

    /** 中身が無いまま実行が終わった → 「(応答なし)」。 */
    NO_RESPONSE,

    /** 描かない(ユーザー発言が空の場合。空バブルを出さない)。 */
    HIDDEN,
}

/**
 * 空バブル(R1)の判定。**ストリーミング中のプレースホルダと、完了後に空だった場合を区別する**。
 *
 * 区別に使うのは2つだけ:
 * - [busy]: `ChatUi.busy`。送信で立ち、`session.idle` / `session.error` / 送信失敗で降りる
 *   (=サーバーが「このセッションはもう動いていない」と言ったかどうか)
 * - [isLast]: 末尾のメッセージか。busyでも、末尾でない空メッセージは既に終わった応答である
 *
 * 「partを1つも持たない」だけでは足りない: 実物は `step-start` / `step-finish` だけを載せた
 * assistant メッセージを送ってくる(P5実測)。それらは描かないので、**parts が空でなくても
 * バブルは空になる**。判定は [isRenderable] を通した結果で行う。
 */
fun bubbleKindOf(message: ChatMessage, isLast: Boolean, busy: Boolean): BubbleKind = when {
    message.parts.any { it.isRenderable() } -> BubbleKind.CONTENT
    message.role == "user" -> BubbleKind.HIDDEN
    busy && isLast -> BubbleKind.WAITING
    else -> BubbleKind.NO_RESPONSE
}

/**
 * 長押し→「コピー」でクリップボードへ渡す文字列(QUALITY_PLAN §5 Q2 スコープ4)。
 *
 * **描画と同じ順序・同じ取捨選択**にする。画面に出ていないライフサイクル part を
 * コピーに混ぜると、貼り付けた側に `step-start` が並ぶ。逆に markdown は
 * **生のまま**渡す(整形後の見た目ではなく元テキストが欲しい場面のほうが多く、
 * 生テキストからは整形を再現できるが逆はできない)。
 *
 * 描くものが何も無ければ空文字を返す —— 呼び出し側はコピー導線を出さないこと。
 */
fun messageCopyText(message: ChatMessage): String =
    message.parts
        .filter { it.isRenderable() }
        .mapNotNull { part ->
            when {
                part.type == "tool" -> part.tool?.let { tool ->
                    listOfNotNull(
                        "[${tool.name}: ${tool.status.name.lowercase()}]",
                        tool.title,
                        tool.input?.let { "input: $it" },
                        tool.output?.let { "output: $it" },
                        tool.error?.let { "error: $it" },
                    ).joinToString("\n")
                } ?: part.toolLabel
                part.text.isNotBlank() -> part.text
                else -> "(${part.type})"
            }
        }
        .joinToString("\n\n")

/** チャット表示1メッセージ(info由来のrole付き)。 */
data class ChatMessage(
    val messageId: String,
    /** "user" | "assistant" | null(契約上必須だが欠損に耐える)。 */
    val role: String?,
    val parts: List<ChatPart>,
    /** ローカルエコー(送信直後にVM側で挿入)かどうか。SSEエコー到着時の重複排除に使用。 */
    val isLocalEcho: Boolean = false,
    /**
     * Q4: どのモデルがこれを書いたか(§5 Q4 スコープ4)。**常時は表示しない**。
     * null = メタが無い(ローカルエコー / 古い履歴 / user メッセージで model 未指定)。
     */
    val meta: ChatMessageMeta? = null,
)

/**
 * メッセージ1件のモデル/コストメタ(QUALITY_PLAN §5 Q4 スコープ4)。長押し「詳細」でだけ出す。
 *
 * **assistant はフラット、user はネスト**という実測(API_CONTRACT.md)をここで吸収する:
 *   assistant: `{"providerID":"mistral","modelID":"mistral-small-latest","cost":0,"tokens":{...}}`
 *   user     : `{"model":{"providerID":"mistral","modelID":"mistral-small-latest"}}`
 * 表示側が role で読み分けずに済むよう、**この型に寄せてから**画面へ渡す。
 *
 * これが R3 に効くのは、**「そのモデルが落ちている」の“その”を指させる**からである。
 * P5 では、応答が返らなかったバブルがどのモデルのものかを画面から知る方法が無かった。
 */
data class ChatMessageMeta(
    val providerId: String?,
    val modelId: String?,
    val agent: String?,
    val cost: Double?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val reasoningTokens: Long?,
    val cacheReadTokens: Long?,
    val cacheWriteTokens: Long?,
) {
    /** 何か1つでも出せるものがあるか。**空の「詳細」を開かせない**。 */
    val hasAnything: Boolean
        get() = providerId != null || modelId != null || agent != null ||
            cost != null || inputTokens != null || outputTokens != null ||
            reasoningTokens != null || cacheReadTokens != null || cacheWriteTokens != null

    /** 「mistral / mistral-small-latest」。片方しか無ければあるほうだけ。 */
    val modelLabel: String?
        get() = listOfNotNull(providerId, modelId).takeIf { it.isNotEmpty() }?.joinToString(" / ")
}

/**
 * `MessageInfo` から表示用メタを作る。**何も無ければ null を返す**
 * (空の「詳細」メニューを出さないため)。
 */
fun MessageInfoDto?.toChatMessageMeta(): ChatMessageMeta? {
    if (this == null) return null
    val meta = ChatMessageMeta(
        // assistant はフラット、user は `model` の中。**両方見る。**
        providerId = providerID ?: model?.providerID,
        modelId = modelID ?: model?.modelID,
        agent = agent,
        cost = cost,
        inputTokens = tokens?.input,
        outputTokens = tokens?.output,
        reasoningTokens = tokens?.reasoning,
        cacheReadTokens = tokens?.cache?.read,
        cacheWriteTokens = tokens?.cache?.write,
    )
    return meta.takeIf { it.hasAnything }
}

internal fun PartDto.toChatPart(): ChatPart = ChatPart(
    partId = id,
    type = type ?: "unknown",
    // Q2: reasoning も `text` を持つ(spec ReasoningPart の required に text がある)。
    // ここで拾わないと「思考」の折り畳みに出す本文が空になる。
    text = if (type == "text" || type == "reasoning") text.orEmpty() else "",
    toolLabel = if (type == "tool") {
        listOfNotNull(tool, state?.status?.let { "($it)" }).joinToString(" ").ifEmpty { "tool" }
    } else {
        null
    },
    tool = if (type == "tool") {
        ChatToolInfo(
            name = tool?.takeIf { it.isNotBlank() } ?: "tool",
            status = toolRunStatusOf(state?.status),
            title = state?.title?.takeIf { it.isNotBlank() },
            input = summarizeToolInput(state?.input),
            // 切るのは**モデル側**。ここで切っておくと、カードの表示と長押しコピーの
            // 両方が同じ上限に従う(片方だけ切ると、画面には出ないものが Clipboard に入る)。
            output = state?.output?.takeIf { it.isNotBlank() }
                ?.let { truncateToolText(it, TOOL_OUTPUT_SUMMARY_MAX) },
            error = state?.error?.takeIf { it.isNotBlank() }
                ?.let { truncateToolText(it, TOOL_OUTPUT_SUMMARY_MAX) },
            // Q8 スコープ6: 拾えたらファイルビューアへの導線を出す。**拾えなくても壊れない。**
            filePath = toolInputFilePath(state?.input),
        )
    } else {
        null
    },
    // Q7: `PatchPart.files`。**type を見て絞る** —— 他の part にも `files` が生えたときに
    // 「N ファイル変更」が別の意味の配列を数え始めないようにする。
    patchFiles = if (type == "patch") files.orEmpty() else emptyList(),
)

/**
 * このメッセージが変更したファイル(§5b Q7 スコープ3)。**重複は畳む**
 * —— 1メッセージが複数の `patch` パートを持つことがあり、同じファイルを2度触れば
 * 2回載る。チップの数字は「何ファイル」であって「何回書いたか」ではない。
 *
 * 順序は出現順を保つ(パスでソートしない。エージェントが触った順のほうが読める)。
 */
fun changedFilesOf(message: ChatMessage): List<String> =
    message.parts.flatMap { it.patchFiles }.filter { it.isNotBlank() }.distinct()

/**
 * 「N ファイル変更」チップ(§5b Q7 スコープ3)。**null なら出さない。**
 *
 * 判定をここに置くのは、画面に `if (files.isNotEmpty())` を書くと
 * その条件を消す変異が全緑で通り抜けるからである(Q1〜Q6 で6度示された形)。
 */
data class MessageDiffChip(val label: String, val description: String, val fileCount: Int)

fun messageDiffChip(message: ChatMessage): MessageDiffChip? {
    val files = changedFilesOf(message)
    if (files.isEmpty()) return null
    return MessageDiffChip(
        label = "${files.size} ファイル変更",
        description = "message-diff:${message.messageId}:${files.size}",
        fileCount = files.size,
    )
}

/**
 * このメッセージを `POST /session/{id}/revert` の対象にできるか。
 *
 * **サーバーは `messageID` に `^msg` の pattern を課している**(実機 `/doc`)。
 * ローカルエコー(送信直後にアプリが挿入したバブル)はまだサーバー側の ID を持たず、
 * 履歴が読めなかった場合の合成 ID(`history-N`)も `msg` で始まらない。
 * **押せるのに 400 が返るボタン**を出さないための判定である。
 */
fun canRevertMessage(message: ChatMessage): Boolean =
    !message.isLocalEcho && message.messageId.startsWith("msg")

/**
 * 確認ダイアログに出す1行。**何が失われるかを人の言葉で**書く。
 *
 * 本文は先頭 [REVERT_PREVIEW_MAX] 文字で切る —— 確認ダイアログが本文で埋まると、
 * 「ファイルが書き換わる」という肝心の警告が画面外へ出る。
 */
fun revertPreviewText(message: ChatMessage): String {
    val head = messageCopyText(message).trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    val role = message.role ?: "unknown"
    val body = if (head.length > REVERT_PREVIEW_MAX) head.take(REVERT_PREVIEW_MAX) + "…" else head
    return if (body.isEmpty()) "[$role]" else "[$role] $body"
}

/** 確認ダイアログのプレビュー本文の最大長。 */
const val REVERT_PREVIEW_MAX = 80

/** GET /session/{id}/message の履歴を表示モデルへ変換する。 */
fun initialChatMessages(entries: List<MessageEntryDto>): List<ChatMessage> =
    entries.mapIndexed { i, entry ->
        ChatMessage(
            messageId = entry.info?.id ?: "history-$i",
            role = entry.info?.role,
            parts = entry.parts.map { it.toChatPart() },
            isLocalEcho = false,
            // Q4: 履歴からもモデルメタを拾う。**402 で失敗した往復でも
            // providerID/modelID は載っていた**(実測)ので、失敗したバブルこそ
            // 「どのモデルが落ちたのか」を持っている。
            meta = entry.info.toChatMessageMeta(),
        )
    }

/** applySseToMessages の戻り値: 更新済みメッセージ列と、未到着messageIdへの役割保留マップ。 */
data class ApplySseResult(
    val messages: List<ChatMessage>,
    val pendingRoles: Map<String, String>,
    /**
     * Q4: `message.updated` が part より**先に**届いた場合のメタ保留マップ。
     *
     * role と同じ理由で要る。実測(2026-08-27)では1往復で `message.updated` が4件流れ、
     * **最初の1件は part より先**だった。メタをその場で捨てると、
     * 「そのモデルが落ちている」を指すための `providerID`/`modelID` が
     * **失敗した往復ほど落ちやすくなる**(part が1つも来ないのが失敗の形なので)。
     */
    val pendingMeta: Map<String, ChatMessageMeta> = emptyMap(),
)

/**
 * SSEイベントを表示モデルへ適用する。対象セッション以外のイベントは無視。
 * message.part.updated は part.id 単位で追加/更新(messageID単位でメッセージに寄せる)。
 * message.updated は対応する messageId の ChatMessage の role を info.role で更新する。
 * 履歴に無いmessageID(ストリーミング中の新規応答)は末尾に新規メッセージとして追加する(role=nullで開始)。
 *
 * [pendingRoles] は message.updated が part より先に届いた場合の role 保留マップ。
 * 戻り値の [ApplySseResult.pendingRoles] を次回呼び出しに渡すことで役割を引き継ぐ。
 */
fun applySseToMessages(
    messages: List<ChatMessage>,
    event: SseEvent,
    targetSessionId: String,
    pendingRoles: Map<String, String> = emptyMap(),
    pendingMeta: Map<String, ChatMessageMeta> = emptyMap(),
): ApplySseResult {
    var updatedPendingRoles = pendingRoles.toMutableMap()
    val updatedPendingMeta = pendingMeta.toMutableMap()
    val newMessages = when (event) {
        is SseEvent.PartUpdated -> {
            if (event.sessionID != null && event.sessionID != targetSessionId) {
                ApplySseResult(messages, updatedPendingRoles.toMap(), updatedPendingMeta.toMap())
            } else {
                val incoming = event.part.toChatPart()
                // messageID不明のpartは単一の合成メッセージ("stream")に集約する
                val key = event.part.messageID ?: "stream"
                val index = messages.indexOfFirst { it.messageId == key }
                if (index < 0) {
                    // 新規ストリーミングメッセージ: 保留中のroleがあれば適用、なければnull
                    // Defect B対策: 同一テキストのローカルエコー(isLocalEcho=true, role="user")が
                    // 既に存在する場合は、そこにマージして重複を防ぐ
                    val merged = mergeWithLocalEchoIfNeeded(messages, key, incoming)
                    if (merged != null) {
                        ApplySseResult(merged, updatedPendingRoles.toMap(), updatedPendingMeta.toMap())
                    } else {
                        val role = updatedPendingRoles.remove(key)
                        // Q4: part より先に届いていたメタをここで載せる(role と同じ扱い)。
                        val meta = updatedPendingMeta.remove(key)
                        ApplySseResult(
                            messages + ChatMessage(
                                messageId = key,
                                role = role,
                                parts = listOf(incoming),
                                isLocalEcho = false,
                                meta = meta,
                            ),
                            updatedPendingRoles.toMap(),
                            updatedPendingMeta.toMap(),
                        )
                    }
                } else {
                    val target = messages[index]
                    val mergedParts = if (incoming.partId != null && target.parts.any { it.partId == incoming.partId }) {
                        target.parts.map { existing ->
                            if (existing.partId == incoming.partId) incoming else existing
                        }
                    } else {
                        target.parts + incoming
                    }
                    ApplySseResult(
                        messages.toMutableList().apply { set(index, target.copy(parts = mergedParts)) },
                        updatedPendingRoles.toMap(),
                        updatedPendingMeta.toMap(),
                    )
                }
            }
        }
        is SseEvent.MessageUpdated -> {
            if (event.sessionID != null && event.sessionID != targetSessionId) {
                ApplySseResult(messages, updatedPendingRoles.toMap(), updatedPendingMeta.toMap())
            } else {
                val info = event.info ?: return ApplySseResult(messages, updatedPendingRoles.toMap(), updatedPendingMeta.toMap())
                // Q4: providerID/modelID/cost/tokens。**null なら触らない** ——
                // 1往復で message.updated は複数回流れ(実測4件)、初回はメタが
                // 揃っていないことがある。null で上書きすると、揃った値が消える。
                val incomingMeta = info.toChatMessageMeta()
                val index = messages.indexOfFirst { it.messageId == info.id }
                if (index < 0) {
                    // メッセージが未到着: roleを保留マップに記録(Defect A対策)
                    if (info.role != null && info.id != null) {
                        updatedPendingRoles[info.id] = info.role
                    }
                    // Q4: メタも同じ理由で保留する(part より先に届く)。
                    if (incomingMeta != null && info.id != null) {
                        updatedPendingMeta[info.id] = incomingMeta
                    }
                    ApplySseResult(messages, updatedPendingRoles.toMap(), updatedPendingMeta.toMap())
                } else {
                    val target = messages[index]
                    // roleが確定していない(null)場合のみ更新(上書きしない)
                    val nextRole = if (target.role == null) info.role ?: target.role else target.role
                    val nextMeta = incomingMeta ?: target.meta
                    if (nextRole != target.role || nextMeta != target.meta) {
                        ApplySseResult(
                            messages.toMutableList().apply {
                                set(index, target.copy(role = nextRole, meta = nextMeta))
                            },
                            updatedPendingRoles.toMap(),
                            updatedPendingMeta.toMap(),
                        )
                    } else {
                        ApplySseResult(messages, updatedPendingRoles.toMap(), updatedPendingMeta.toMap())
                    }
                }
            }
        }
        else -> ApplySseResult(messages, updatedPendingRoles.toMap(), updatedPendingMeta.toMap())
    }
    return newMessages
}

/**
 * 新規メッセージ(key)の最初のpart到着時に、同一テキストのローカルエコーがあれば
 * そこにマージして重複を防ぐ。マージした場合は更新済みメッセージ列を返し、
 * マージ不要ならnullを返す。
 */
private fun mergeWithLocalEchoIfNeeded(
    messages: List<ChatMessage>,
    newMessageId: String,
    incoming: ChatPart,
): List<ChatMessage>? {
    // ローカルエコー候補: isLocalEcho=true, role="user", 単一textパートを持つもの
    val localEchoIndex = messages.indexOfFirst { msg ->
        msg.isLocalEcho &&
        msg.role == "user" &&
        msg.parts.size == 1 &&
        msg.parts[0].type == "text" &&
        msg.parts[0].text.startsWith(incoming.text) // ストリーミング先頭チャンクがローカルエコーの先頭と一致
    }
    if (localEchoIndex < 0) return null

    val localEcho = messages[localEchoIndex]
    val localPart = localEcho.parts[0]
    // ローカルエコーのプレースホルダ(partId=null)を、到着したpartで置換する
    // 同一partIdの場合も置換、partIdが異なる(null vs 非null)場合もテキストパート同士なら置換
    val mergedParts = if (incoming.partId != null && localEcho.parts.any { it.partId == incoming.partId }) {
        // 同一partId: 通常の更新
        localEcho.parts.map { existing ->
            if (existing.partId == incoming.partId) incoming else existing
        }
    } else if (localPart.partId == null && incoming.type == "text") {
        // プレースホルダpartId=null を到着partで置換(Defect B: 重複排除)
        listOf(incoming)
    } else {
        // それ以外は追加
        localEcho.parts + incoming
    }
    // messageIdをサーバー割り当てのものに更新、isLocalEchoはfalseに(以降は通常メッセージとして扱う)
    val mergedMessage = localEcho.copy(
        messageId = newMessageId,
        parts = mergedParts,
        isLocalEcho = false,
    )
    return messages.toMutableList().apply { set(localEchoIndex, mergedMessage) }
}
/**
 * 長押し「詳細」に出す行(§5 Q4 スコープ4)。**空の項目は入れない。**
 *
 * 純関数にしてあるのは、ここが R3 の“その”を指す唯一の場所だからである。
 * P5 では、応答が返らなかったバブルがどのモデルのものかを画面から知る手段が無かった。
 * 402 で失敗した往復でも `providerID`/`modelID` は載っている(実測)ので、
 * **失敗したバブルほどこの行が要る**。
 *
 * `cost` は 0 でも出す —— 「0だった」と「分からない」は違う。
 */
fun messageDetailLines(meta: ChatMessageMeta?): List<Pair<String, String>> {
    if (meta == null) return emptyList()
    return buildList {
        meta.providerId?.let { add("プロバイダ" to it) }
        meta.modelId?.let { add("モデル" to it) }
        meta.agent?.let { add("エージェント" to it) }
        meta.cost?.let { add("コスト" to it.toString()) }
        meta.inputTokens?.let { add("入力トークン" to it.toString()) }
        meta.outputTokens?.let { add("出力トークン" to it.toString()) }
        meta.reasoningTokens?.let { add("推論トークン" to it.toString()) }
        meta.cacheReadTokens?.let { add("キャッシュ読み" to it.toString()) }
        meta.cacheWriteTokens?.let { add("キャッシュ書き" to it.toString()) }
    }
}

/**
 * モデル切替が失敗したときに画面へ出す1行(以上)。**純関数**。
 *
 * ## なぜ 500 だけ特別扱いするのか(実測に基づく)
 *
 * `POST /api/session/{id}/model` は、**セッションの `directory` がホストに実在しないと
 * モデル値によらず 500 `UnknownError` を返す**(実測。`docs/API_CONTRACT.md`
 * 「セッションの `directory` が消えていると 500 になる」)。実在すれば 204 である。
 * spec はこのパスに 500 を宣言しておらず、サーバーは `UnknownError` としか言わないので、
 * **アプリ側だけでは他の 500 と区別できない**。
 *
 * だからここは**断定しない**。「こうなる場合がある」と実測を伝え、
 * 確かめる手がかり(そのセッションの作業ディレクトリ)を並べるところまでをやる。
 * 申し送りが「未回収」として残していたのは、固定文言しか出せず**手がかりが1つも
 * 画面に出ない**ことだった。
 *
 * @param describe 一般の [ApiError] を1行にする関数。文言は画面側の関心なので注入する。
 */
fun modelSwitchFailureMessage(
    error: ApiError,
    sessionDirectory: String?,
    describe: (ApiError) -> String,
): String {
    val head = "モデルの切り替えに失敗しました: " + describe(error)
    if (!(error is ApiError.Http && error.code == 500)) return head
    val hint = if (sessionDirectory.isNullOrBlank()) {
        "このセッションの作業ディレクトリはまだ取得できていません。"
    } else {
        "このセッションの作業ディレクトリ: " + sessionDirectory
    }
    return head + "\n" +
        "サーバーは詳細を返しません(UnknownError)。" +
        "実測では、セッションの作業ディレクトリがホストに無いときにこの応答になります。" +
        "他の原因の 500 と区別する手立てはサーバー側にありません。\n" +
        hint
}
