package dev.opencode.android.ui

import dev.opencode.android.data.QuestionAskedEvent
import dev.opencode.android.data.QuestionInfoDto
import dev.opencode.android.data.QuestionRequestDto
import dev.opencode.android.data.TodoDto

/**
 * Q3 の表示モデルと**純粋な組み立てロジック**(QUALITY_PLAN §5 Q3)。
 *
 * ここに置くのは Android にも Compose にも依存しない部分だけ。状態機械(質問の寿命・
 * todo の保持・permission との同時 pending)は [ChatController] にあり、そちらにも検出器を置く ——
 * RUN_PLAN「検出器の穴」が言うとおり、**純関数のテストだけではゲートは閉じない**。
 */

// ---- Todo ----

/**
 * `Todo.status` を表示側の語彙へ寄せる。**未知値は [UNKNOWN]**。
 *
 * spec の `status` は enum ではなく `type: string` で、description が挙げるのは
 * `pending, in_progress, completed, cancelled` の**4値**である
 * (計画書 §5 Q3 スコープ1 は3値と書いていて spec より狭い。実測は spec を採った)。
 */
enum class TodoStatus { PENDING, IN_PROGRESS, COMPLETED, CANCELLED, UNKNOWN }

fun todoStatusOf(raw: String?): TodoStatus = when (raw) {
    "pending" -> TodoStatus.PENDING
    "in_progress" -> TodoStatus.IN_PROGRESS
    "completed" -> TodoStatus.COMPLETED
    "cancelled" -> TodoStatus.CANCELLED
    else -> TodoStatus.UNKNOWN
}

/** タスクリスト1行。`priority` は spec 上 required だが表示には使っていない。 */
data class TodoItem(
    val content: String,
    val status: TodoStatus,
)

fun TodoDto.toTodoItem(): TodoItem = TodoItem(
    content = content,
    status = todoStatusOf(status),
)

fun List<TodoDto>.toTodoItems(): List<TodoItem> = map { it.toTodoItem() }

/**
 * カードの見出しに出す進捗。「完了 N/M」。
 *
 * **分母から `cancelled` を除く**。取り消したタスクを分母に残すと、全部片付いても
 * 「2/3」で止まって見え、**終わったのに終わっていないように見える**。
 * 数値そのものをテストで固定する(§4.2「数値と同一性を検証する」)。
 */
fun todoProgressText(todos: List<TodoItem>): String {
    val counted = todos.filter { it.status != TodoStatus.CANCELLED }
    val done = counted.count { it.status == TodoStatus.COMPLETED }
    return "完了 $done/${counted.size}"
}

/** 行頭のグリフ。3値(+cancelled/unknown)を1文字で見分ける。dump からも引ける。 */
fun todoGlyph(status: TodoStatus): String = when (status) {
    TodoStatus.PENDING -> "☐"
    TodoStatus.IN_PROGRESS -> "▶"
    TodoStatus.COMPLETED -> "☑"
    TodoStatus.CANCELLED -> "✕"
    TodoStatus.UNKNOWN -> "・"
}

// ---- Question ----

/** 選択肢1つ。`label` が `answers` に載る値そのもの。`description` は spec 上 required。 */
data class QuestionOption(
    val label: String,
    val description: String?,
)

/** 質問1件。`multiple` / `custom` は spec 上任意なので**欠けたら false**。 */
data class QuestionItem(
    val question: String,
    val header: String?,
    val options: List<QuestionOption>,
    val multiple: Boolean,
    val custom: Boolean,
)

fun QuestionInfoDto.toQuestionItem(): QuestionItem = QuestionItem(
    question = question,
    header = header?.takeIf { it.isNotBlank() },
    options = options.map { QuestionOption(label = it.label, description = it.description?.takeIf(String::isNotBlank)) },
    multiple = multiple == true,
    custom = custom == true,
)

/**
 * 1つの質問に対するユーザーの入力。**選択は「ラベルの集合」ではなく `options` の添字**で持つ。
 *
 * ラベルで持つと、同じ label を持つ選択肢が2つある質問(spec は一意性を要求していない)で
 * 片方を押すともう片方も選択済みに見える。組み立ての最後に添字→ラベルへ落とす。
 */
data class QuestionSelection(
    val selectedIndices: Set<Int> = emptySet(),
    val customText: String = "",
)

/**
 * 質問カードの決着。**カードは消さない**(計画書 §5 Q3 スコープ2「応答後はカードを
 * 選択済み状態で残す」)ので、どう決着したかを持つ。
 */
enum class QuestionResolution {
    /** まだ応答していない(ボタンが押せる唯一の状態)。 */
    PENDING,

    /** 応答した(自分が送った / `question.replied` が届いた)。 */
    ANSWERED,

    /** 拒否した(`question.rejected`)。 */
    REJECTED,

    /**
     * 応答しないまま実行が終わった(`session.idle` / `session.error` / セッション削除)。
     * **消さずに残す**が、もう押せない —— 応答先が無いのにボタンだけ生きているのが
     * 一番わかりにくい壊れ方だった(P4 の permission ダイアログで実際に起きた形)。
     */
    EXPIRED,
}

/**
 * 質問カード1枚。**permission ダイアログとは独立**なので同時に pending でも壊れない
 * (§5 Q3 スコープ3。UI 上も別コンポーネント)。
 *
 * [selections] は [questions] と**同じ長さ**を保つ。長さがずれると「i番目の質問への回答」が
 * ずれ、`answers` の並びが崩れる(spec: "in order of questions")。
 * 生成は [questionCardOf] だけが行い、長さを揃える責任もそこにある。
 */
data class QuestionCardState(
    /** `^que` の要求ID。`asked` の `id` で作り、`replied`/`rejected` の `requestID` で閉じる。 */
    val requestId: String,
    val sessionId: String,
    val questions: List<QuestionItem>,
    val selections: List<QuestionSelection>,
    val resolution: QuestionResolution = QuestionResolution.PENDING,
    /** 応答の往復中(二度押し防止)。 */
    val submitting: Boolean = false,
    /** 決着後に画面へ残す「送った内容」。`answers` そのもの。 */
    val submittedAnswers: List<List<String>> = emptyList(),
)

/** 押せるのは PENDING かつ往復中でないときだけ。 */
fun QuestionCardState.isInteractive(): Boolean =
    resolution == QuestionResolution.PENDING && !submitting

/**
 * **そもそも回答できる質問か**(レビュー minor-6)。
 *
 * spec は `options` を required にしているが `minItems` を置いていないので、
 * `options: []` かつ `custom` 無しの質問が来うる。その質問には**入力手段が1つも無い**。
 * 黙って「回答ボタンが永久に無効」にすると、押せない理由が画面のどこにも出ない ——
 * これはこのプロジェクトが繰り返した「押せるのに何も起きない」の裏返しで、同じくらい悪い。
 * 回答不能だと分かったら**そう書いて、拒否だけを残す**。
 */
fun QuestionCardState.isAnswerable(): Boolean =
    questions.isNotEmpty() && questions.all { it.options.isNotEmpty() || it.custom }

/**
 * `question.asked` / `GET /question` の要素からカードを作る。
 *
 * `id` が無ければ**カードを作らない**(null を返す)。requestID が無いと reply も reject も
 * 送れず、押せるのに何も起きないカードになる。「出ない」ほうがまだ診断できる。
 */
fun questionCardOf(requestId: String?, sessionId: String?, questions: List<QuestionInfoDto>): QuestionCardState? {
    val id = requestId?.takeIf { it.isNotBlank() } ?: return null
    if (questions.isEmpty()) return null
    val items = questions.map { it.toQuestionItem() }
    return QuestionCardState(
        requestId = id,
        sessionId = sessionId.orEmpty(),
        questions = items,
        // 長さを questions に揃える。ここが唯一の生成点。
        selections = List(items.size) { QuestionSelection() },
    )
}

fun questionCardOf(event: QuestionAskedEvent): QuestionCardState? =
    questionCardOf(event.id, event.sessionID, event.questions)

fun questionCardOf(dto: QuestionRequestDto): QuestionCardState? =
    questionCardOf(dto.id, dto.sessionID, dto.questions)

/**
 * 選択肢のトグル。**単一選択は置き換え、`multiple` は集合への出し入れ。**
 *
 * 範囲外の添字と長さのずれは黙って無視する(状態を壊すより何もしないほうがよい)。
 */
fun QuestionCardState.toggleOption(questionIndex: Int, optionIndex: Int): QuestionCardState {
    val q = questions.getOrNull(questionIndex) ?: return this
    if (optionIndex !in q.options.indices) return this
    if (questionIndex !in selections.indices) return this
    val current = selections[questionIndex]
    val next = if (q.multiple) {
        if (optionIndex in current.selectedIndices) {
            current.copy(selectedIndices = current.selectedIndices - optionIndex)
        } else {
            current.copy(selectedIndices = current.selectedIndices + optionIndex)
        }
    } else {
        // 単一選択: 同じものをもう一度押したら解除(選び直せないカードを作らない)
        if (current.selectedIndices == setOf(optionIndex)) {
            current.copy(selectedIndices = emptySet())
        } else {
            current.copy(selectedIndices = setOf(optionIndex))
        }
    }
    return copy(selections = selections.toMutableList().apply { set(questionIndex, next) })
}

/** 自由入力欄の更新。`custom` が false の質問では何もしない。 */
fun QuestionCardState.updateCustomText(questionIndex: Int, text: String): QuestionCardState {
    val q = questions.getOrNull(questionIndex) ?: return this
    if (!q.custom) return this
    if (questionIndex !in selections.indices) return this
    val next = selections[questionIndex].copy(customText = text)
    return copy(selections = selections.toMutableList().apply { set(questionIndex, next) })
}

/**
 * **`answers` の組み立て(§5 Q3 のゲートが名指しした純関数)。**
 *
 * 契約(API_CONTRACT.md「`answers` の形」):
 * `answers[i]` は **i番目の質問への回答**であり、中身は**選んだラベルの配列**。
 * 根拠は spec の3か所の一致 —— requestBody の description
 * "User answers in order of questions (each answer is an array of selected labels)"、
 * `QuestionAnswer = string[]`、v2 の `QuestionV2Reply` も同一形。
 *
 * 規則:
 * - 外側の長さは**質問の数ちょうど**。選んでいない質問も空配列で場所を空ける ——
 *   詰めると i 番目の対応がずれ、**別の質問への回答として解釈される**。
 * - ラベルは**選択肢の並び順**で出す。ユーザーが押した順ではない
 *   (押し順に意味は無く、順序が非決定だとテストで数値を固定できない)。
 * - `custom: true` の自由入力は**選択ラベルの後ろ**に1要素として足す。空白のみなら足さない。
 *   `answers` の型が `string[]` である以上ほかに置き場が無いという消去法の結論であり、
 *   **spec が明示していない唯一の点**である(実物未確認。API_CONTRACT.md に明記した)。
 */
fun buildQuestionAnswers(
    questions: List<QuestionItem>,
    selections: List<QuestionSelection>,
): List<List<String>> = questions.mapIndexed { index, question ->
    val selection = selections.getOrNull(index) ?: QuestionSelection()
    val chosen = question.options
        .filterIndexed { optionIndex, _ -> optionIndex in selection.selectedIndices }
        .map { it.label }
    val custom = selection.customText.trim().takeIf { question.custom && it.isNotBlank() }
    if (custom == null) chosen else chosen + custom
}

fun QuestionCardState.buildAnswers(): List<List<String>> = buildQuestionAnswers(questions, selections)

/**
 * 送信できるか。**すべての質問に1つ以上の回答があること。**
 *
 * spec は「in order of questions」としか言わないので空配列を許すかは不明だが、
 * 空の回答を送るのは「答えずに答えたことにする」であり、拒否(`/reject`)と区別が付かない。
 * 答えないなら拒否導線を使う。
 */
fun canSubmitQuestion(state: QuestionCardState): Boolean =
    state.isInteractive() && state.isAnswerable() && state.buildAnswers().all { it.isNotEmpty() }

/**
 * 入力欄の上に出す**インラインカード**1枚(§5 Q3 スコープ1・2「ダイアログではなくインラインカード」)。
 *
 * [selectChatInlineCards] が唯一の出し分けの所在である —— [selectChatBanner] と同じ形にした理由:
 * Q1/Q2 のレビューが繰り返し示したのは「**配線に条件を書くと、その条件はテストの外に出る**」
 * ことだった。`if (ui.todos.isNotEmpty())` を `ChatScreen.kt` に書くと、それを消す変異が
 * 全緑で通り抜け、**画面は正常に見えたままカードだけが出なくなる**。
 *
 * 質問が [Question] として **requestId ごとに1枚**返るのは Q3 レビュー blocker-1 の帰結である。
 * 以前ここは `enum { TODO, QUESTION }` で、質問は何件 pending でも1枚しか描けなかった。
 */
sealed interface ChatInlineCard {
    data object Todo : ChatInlineCard

    data class Question(val requestId: String) : ChatInlineCard

    /**
     * 上限([RESOLVED_QUESTION_CARDS_KEPT])を超えて画面から外した決着済みカードの件数。
     *
     * E2E の指摘: 上限で落とすこと自体はよいが、**落ちたことが無言だった** ——
     * 「送った内容を後から確認する手段が画面に無い」。件数だけでも出しておけば、
     * 消えたのか元から無かったのかが区別できる。
     */
    data class ResolvedOverflow(val count: Int) : ChatInlineCard
}

/**
 * 決着済みカードを画面に残す上限(§5 Q3 スコープ2「応答後はカードを選択済み状態で残す」)。
 *
 * 無制限に積むと、長いセッションで決着済みカードが縦に伸びて**入力欄を画面外へ押し出す**
 * (2026-08-27 に実機で1枚のカードでも起きた形)。残すのは直近ぶんだけにする。
 */
const val RESOLVED_QUESTION_CARDS_KEPT = 2

/**
 * 出すカードを順に返す。**帯と違って1本ではない** —— todo と質問は同時に、
 * さらに**質問どうしも同時に**出てよい(`GET /question` は `QuestionRequest[]` を返し、
 * 複数 pending はサーバー側の一級の概念である。Q3 レビュー blocker-1)。
 *
 * 並び: todo → 決着済み(直近 [RESOLVED_QUESTION_CARDS_KEPT] 件)→ 未応答。
 * **未応答を最後に置く**のは、押す物を入力欄に近い側へ寄せるため。
 * permission ダイアログはモーダルで別枠なので、ここには入らない(§5 Q3 スコープ3)。
 */
fun selectChatInlineCards(state: ChatUi): List<ChatInlineCard> = buildList {
    if (state.todos.isNotEmpty()) add(ChatInlineCard.Todo)
    val (pending, resolved) = state.questions.partition { it.resolution == QuestionResolution.PENDING }
    val hidden = (resolved.size - RESOLVED_QUESTION_CARDS_KEPT).coerceAtLeast(0)
    if (hidden > 0) add(ChatInlineCard.ResolvedOverflow(hidden))
    resolved.takeLast(RESOLVED_QUESTION_CARDS_KEPT).forEach { add(ChatInlineCard.Question(it.requestId)) }
    // **未応答は1件も落とさない。** 決着済みは上限で削るが、未応答を削ると
    // サーバーが待っている質問が画面のどこにも無くなる(blocker-1 と同じ性質)。
    pending.forEach { add(ChatInlineCard.Question(it.requestId)) }
}

/** requestId でカードを引く。UI は [selectChatInlineCards] が返した id でこれを呼ぶ。 */
fun ChatUi.questionCard(requestId: String): QuestionCardState? =
    questions.firstOrNull { it.requestId == requestId }

/**
 * **`GET /question` の応答をカード列へ反映する。サーバーが権威**(Q3 レビュー blocker-2)。
 *
 * 直す欠陥: `session.error` / `session.deleted` / abort の見張りは未応答カードを [QuestionResolution.EXPIRED]
 * にするが、**サーバーはまだ同じ質問を未応答として持っていることがある**。以前の実装は
 * 「同じ requestId のカードがあるなら何もしない」だったので、いちど EXPIRED になったカードは
 * 再入室しても再接続しても二度と押せず、**reply も reject も永久に送れなかった**。
 * 観測した1つのトリガ(取り直しの idle)だけを塞いだのが誤りで、**復元を権威にする**のが答えである。
 *
 * 規則:
 * - サーバーが未応答と言っている → **[QuestionResolution.PENDING] へ戻す。選択は保持する**
 *   (答えかけていた内容を消さない)。決着表示に使っていた控えだけ捨てる
 * - サーバーが載せていない → 決着している。**ただし取得を始める前から知っていたカードだけ**を
 *   [QuestionResolution.EXPIRED] にする。[knownBeforeFetch] を渡すのはそのためで、
 *   取得中に届いた `question.asked` を「サーバーが知らない」と誤断して畳まないようにする
 * - 往復中([QuestionCardState.submitting])のカードは**どちらの向きにも触らない**。
 *   応答が確定していないものにサーバーの一瞬前の見解を当てない
 * - サーバーにあってローカルに無い → 追加する(切断中に届いた `question.asked` の回収)
 */
fun mergePendingQuestions(
    current: List<QuestionCardState>,
    serverPending: List<QuestionCardState>,
    knownBeforeFetch: Set<String>,
): List<QuestionCardState> {
    val serverIds = serverPending.map { it.requestId }.toSet()
    val updated = current.map { card ->
        when {
            card.submitting -> card
            card.requestId in serverIds ->
                if (card.resolution == QuestionResolution.PENDING) {
                    card
                } else {
                    card.copy(resolution = QuestionResolution.PENDING, submittedAnswers = emptyList())
                }
            card.requestId in knownBeforeFetch && card.resolution == QuestionResolution.PENDING ->
                card.copy(resolution = QuestionResolution.EXPIRED)
            else -> card
        }
    }
    val known = updated.map { it.requestId }.toSet()
    return updated + serverPending.filter { it.requestId !in known }
}

/**
 * 未応答のカードを**すべて**押せなくして残す(`session.idle` / `session.error` /
 * `session.deleted` / abort の見張りから呼ぶ)。
 *
 * **すべて**なのが要点。以前は単数の `question` を1件畳むだけで、2件目以降は
 * 実行が終わっても「回答待ち」のまま画面に残った(blocker-1 の副作用)。
 */
fun expireAllPendingQuestions(cards: List<QuestionCardState>): List<QuestionCardState> =
    cards.map {
        if (it.resolution == QuestionResolution.PENDING) {
            it.copy(resolution = QuestionResolution.EXPIRED, submitting = false)
        } else {
            it
        }
    }

/** 決着後に画面へ残す1行(「送った内容」)。dump からも引ける形にする。 */
fun questionSummaryText(state: QuestionCardState): String = when (state.resolution) {
    QuestionResolution.PENDING -> "回答待ち"
    QuestionResolution.ANSWERED ->
        "回答済み: " + state.submittedAnswers.joinToString(" / ") { it.joinToString(", ") }
    QuestionResolution.REJECTED -> "拒否しました"
    QuestionResolution.EXPIRED -> "回答しないまま実行が終了しました"
}
