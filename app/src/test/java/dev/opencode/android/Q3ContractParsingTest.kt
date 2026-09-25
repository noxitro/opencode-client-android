package dev.opencode.android

import dev.opencode.android.data.QuestionAskedEvent
import dev.opencode.android.data.QuestionReplyRequest
import dev.opencode.android.data.QuestionRequestDto
import dev.opencode.android.data.QuestionResolvedEvent
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.TodoDto
import dev.opencode.android.data.contractJson
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.QuestionItem
import dev.opencode.android.ui.QuestionOption
import dev.opencode.android.ui.QuestionResolution
import dev.opencode.android.ui.QuestionSelection
import dev.opencode.android.ui.TodoStatus
import dev.opencode.android.ui.buildQuestionAnswers
import dev.opencode.android.ui.canSubmitQuestion
import dev.opencode.android.ui.expireAllPendingQuestions
import dev.opencode.android.ui.isAnswerable
import dev.opencode.android.ui.mergePendingQuestions
import dev.opencode.android.ui.questionCardOf
import dev.opencode.android.ui.questionSummaryText
import dev.opencode.android.ui.toggleOption
import dev.opencode.android.ui.todoProgressText
import dev.opencode.android.ui.todoStatusOf
import dev.opencode.android.ui.updateCustomText
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q3 の契約適合(spec の required を満たすフィクスチャ)と、`answers` 組み立ての純関数。
 *
 * **フィクスチャは spec からコピーした形**である(§4.2「フィクスチャは実物の形を貼る」)。
 * `Todo` の required は `content` `status` `priority` の3つ、
 * `QuestionOption` は `label` **と `description`**、`QuestionInfo` は `question` `header` `options`、
 * `question.replied` は `sessionID` `requestID` `answers` ——
 * **`asked` は `id`、`replied`/`rejected` は `requestID`** でキー名が違う。
 * 実物での発火は 402 のため観測できていない(API_CONTRACT.md「実物で確認できなかったこと」)。
 * ただし `Todo` だけは実物 serve の実セッションから採った形と一致している。
 */
class Q3ContractParsingTest {

    private val sid = "ses_fc0247634ffeGddA7Mw2hp2KCh"

    // ---------- todo.updated ----------

    /** 実物 serve 4097 の実セッションから採った形そのまま(2026-08-27 実測)。 */
    private val realTodoJson =
        """[{"content":"Confirm Theme.kt exists and read current content","status":"completed","priority":"high"},""" +
            """{"content":"Draft work specification JSON","status":"completed","priority":"high"}]"""

    @Test
    fun `実物の todo レスポンスがそのままパースできる`() {
        val todos = contractJson.decodeFromString<List<TodoDto>>(realTodoJson)
        assertEquals(2, todos.size)
        assertEquals("Confirm Theme.kt exists and read current content", todos[0].content)
        assertEquals("completed", todos[0].status)
        assertEquals("high", todos[0].priority)
    }

    @Test
    fun `todo_updated の envelope が TodoUpdated になる`() {
        val frame =
            """{"id":"evt_1","type":"todo.updated","properties":{"sessionID":"$sid","todos":$realTodoJson}}"""
        val event = parseSseEnvelope(frame)
        assertTrue("todo.updated が $event に落ちた", event is SseEvent.TodoUpdated)
        val todo = event as SseEvent.TodoUpdated
        assertEquals(sid, todo.sessionID)
        assertEquals(2, todo.todos.size)
    }

    @Test
    fun `todos が空配列でもイベントとして通る`() {
        // 「全部消えた」は正しい状態。ここを Ignored にすると、タスクが片付いたのに残り続ける。
        val event = parseSseEnvelope(
            """{"id":"evt_2","type":"todo.updated","properties":{"sessionID":"$sid","todos":[]}}""",
        )
        assertTrue(event is SseEvent.TodoUpdated)
        assertEquals(0, (event as SseEvent.TodoUpdated).todos.size)
    }

    @Test
    fun `status は4値ぜんぶ寄せられ、未知値でも落ちない`() {
        // spec の status は enum ではなく string。description が挙げるのは4値。
        assertEquals(TodoStatus.PENDING, todoStatusOf("pending"))
        assertEquals(TodoStatus.IN_PROGRESS, todoStatusOf("in_progress"))
        assertEquals(TodoStatus.COMPLETED, todoStatusOf("completed"))
        assertEquals(TodoStatus.CANCELLED, todoStatusOf("cancelled"))
        assertEquals(TodoStatus.UNKNOWN, todoStatusOf("blocked"))
        assertEquals(TodoStatus.UNKNOWN, todoStatusOf(null))
    }

    @Test
    fun `todos が読めない形なら空リストではなく Ignored にする`() {
        // 変異M3で判明した穴。読めなかったときに空リストへ倒すと、
        // **サーバーが持っているタスクを「全部消えた」と表示する**。
        // 「読めなかった」は「無くなった」ではない。イベントごと捨てるほうが正しい。
        val event = parseSseEnvelope(
            """{"id":"evt_bad","type":"todo.updated","properties":{"sessionID":"$sid","todos":"not-an-array"}}""",
        )
        assertTrue("読めない todos を空リストとして通している: $event", event is SseEvent.Ignored)
    }

    @Test
    fun `todos の要素の型が違っても空リストへ倒さない`() {
        val event = parseSseEnvelope(
            """{"id":"evt_bad2","type":"todo.updated","properties":{"sessionID":"$sid","todos":[{"content":42}]}}""",
        )
        assertTrue("要素が壊れた todos を空リストとして通している: $event", event is SseEvent.Ignored)
    }

    @Test
    fun `todos キーが無ければ Ignored`() {
        val event = parseSseEnvelope("""{"id":"evt_bad3","type":"todo.updated","properties":{"sessionID":"$sid"}}""")
        assertTrue(event is SseEvent.Ignored)
    }

    @Test
    fun `未知の status を含む todo でもリストごと消えない`() {
        val event = parseSseEnvelope(
            """{"id":"evt_3","type":"todo.updated","properties":{"sessionID":"$sid","todos":""" +
                """[{"content":"a","status":"blocked","priority":"low"}]}}""",
        )
        assertEquals(1, (event as SseEvent.TodoUpdated).todos.size)
    }

    // ---------- question.asked ----------

    private val askedProps =
        """{"id":"que_abc","sessionID":"$sid","questions":[""" +
            """{"question":"どちらの方式で進めますか","header":"方式選択",""" +
            """"options":[{"label":"A案","description":"速いが粗い"},{"label":"B案","description":"遅いが確実"}],""" +
            """"multiple":false,"custom":true}],"tool":{"messageID":"msg_a1","callID":"call_1"}}"""

    @Test
    fun `question_asked が QuestionAskedEvent になり id を保つ`() {
        val event = parseSseEnvelope("""{"id":"evt_4","type":"question.asked","properties":$askedProps}""")
        assertTrue("question.asked が $event に落ちた", event is QuestionAskedEvent)
        val asked = event as QuestionAskedEvent
        assertEquals("que_abc", asked.id)
        assertEquals(sid, asked.sessionID)
        assertEquals(1, asked.questions.size)
        assertEquals(2, asked.questions[0].options.size)
        // description は spec 上 required。落とすと選択肢の説明が全部消える。
        assertEquals("速いが粗い", asked.questions[0].options[0].description)
        assertEquals(true, asked.questions[0].custom)
        assertEquals("call_1", asked.tool?.callID)
    }

    @Test
    fun `question_v2_asked も同じイベントとして受ける`() {
        val event = parseSseEnvelope("""{"id":"evt_5","type":"question.v2.asked","properties":$askedProps}""")
        assertEquals("que_abc", (event as QuestionAskedEvent).id)
    }

    @Test
    fun `tool を持たない question_asked でも落ちない`() {
        // tool は spec 上任意。必須宣言すると、tool 無しの質問でカードが一度も出なくなる。
        val event = parseSseEnvelope(
            """{"id":"evt_6","type":"question.asked","properties":{"id":"que_x","sessionID":"$sid",""" +
                """"questions":[{"question":"q","header":"h","options":[{"label":"L","description":"D"}]}]}}""",
        )
        val asked = event as QuestionAskedEvent
        assertNull(asked.tool)
        // multiple / custom は任意 → 欠けたら false 扱い
        assertNull(asked.questions[0].multiple)
        assertFalse(questionCardOf(asked)!!.questions[0].multiple)
        assertFalse(questionCardOf(asked)!!.questions[0].custom)
    }

    @Test
    fun `GET question のレスポンス要素が question_asked と同形`() {
        val list = contractJson.decodeFromString<List<QuestionRequestDto>>("[$askedProps]")
        assertEquals(1, list.size)
        assertEquals("que_abc", list[0].id)
        val card = questionCardOf(list[0])
        assertNotNull(card)
        assertEquals(1, card!!.selections.size) // selections は questions と同じ長さ
    }

    // ---------- question.replied / rejected(P4 と同じ形の罠) ----------

    @Test
    fun `question_replied は requestID を読む`() {
        val event = parseSseEnvelope(
            """{"id":"evt_7","type":"question.replied","properties":""" +
                """{"sessionID":"$sid","requestID":"que_abc","answers":[["A案"],["x","y"]]}}""",
        )
        assertTrue("question.replied が $event に落ちた", event is QuestionResolvedEvent)
        val ev = event as QuestionResolvedEvent
        assertEquals(QuestionResolvedEvent.Kind.REPLIED, ev.kind)
        assertEquals("que_abc", ev.requestID)
        assertEquals(2, ev.answers.size)
        assertEquals(listOf("x", "y"), ev.answers[1])
    }

    @Test
    fun `question_rejected は answers を持たないが落ちない`() {
        val event = parseSseEnvelope(
            """{"id":"evt_8","type":"question.rejected","properties":{"sessionID":"$sid","requestID":"que_abc"}}""",
        )
        val ev = event as QuestionResolvedEvent
        assertEquals(QuestionResolvedEvent.Kind.REJECTED, ev.kind)
        assertEquals(0, ev.answers.size)
    }

    @Test
    fun `requestID を持たない replied も Ignored に落とさない`() {
        // P4 の再演を防ぐための検出器。「id だけの replied は Ignored であるべき」という
        // テストが存在したのに、実際に来るのがまさにその形だった。
        val event = parseSseEnvelope(
            """{"id":"evt_9","type":"question.replied","properties":{"sessionID":"$sid","answers":[["A案"]]}}""",
        )
        assertTrue("欠けた形で捨てている", event is QuestionResolvedEvent)
        assertNull((event as QuestionResolvedEvent).requestID)
    }

    @Test
    fun `v2 の replied と rejected も同じイベントになる`() {
        val replied = parseSseEnvelope(
            """{"id":"evt_10","type":"question.v2.replied","properties":""" +
                """{"sessionID":"$sid","requestID":"que_abc","answers":[["A案"]]}}""",
        )
        assertEquals(QuestionResolvedEvent.Kind.REPLIED, (replied as QuestionResolvedEvent).kind)
        val rejected = parseSseEnvelope(
            """{"id":"evt_11","type":"question.v2.rejected","properties":{"sessionID":"$sid","requestID":"que_abc"}}""",
        )
        assertEquals(QuestionResolvedEvent.Kind.REJECTED, (rejected as QuestionResolvedEvent).kind)
    }

    // ---------- reply のリクエストボディ ----------

    @Test
    fun `reply ボディは answers だけを持つ`() {
        // spec: `{answers}` で required は answers のみ、additionalProperties:false。
        // 余計なキーを送ると 400 になりうる。
        val json = contractJson.encodeToString(QuestionReplyRequest(listOf(listOf("A案"), listOf("x", "y"))))
        assertEquals("""{"answers":[["A案"],["x","y"]]}""", json)
    }

    // ---------- answers の組み立て(§5 Q3 のゲートが名指しした純関数) ----------

    private fun q(
        label1: String = "A案",
        label2: String = "B案",
        multiple: Boolean = false,
        custom: Boolean = false,
    ) = QuestionItem(
        question = "q",
        header = "h",
        options = listOf(QuestionOption(label1, "d1"), QuestionOption(label2, "d2")),
        multiple = multiple,
        custom = custom,
    )

    @Test
    fun `単一選択は要素1の配列になる`() {
        val answers = buildQuestionAnswers(listOf(q()), listOf(QuestionSelection(setOf(1))))
        assertEquals(1, answers.size)
        assertEquals(listOf("B案"), answers[0])
    }

    @Test
    fun `複数選択は選択肢の並び順で出る(押した順ではない)`() {
        // 押した順に依存すると、順序が非決定になりテストで数値を固定できない。
        val answers = buildQuestionAnswers(
            listOf(q(multiple = true)),
            listOf(QuestionSelection(linkedSetOf(1, 0))),
        )
        assertEquals(listOf("A案", "B案"), answers[0])
    }

    @Test
    fun `custom の自由入力は選択ラベルの後ろに1要素として足される`() {
        val answers = buildQuestionAnswers(
            listOf(q(custom = true)),
            listOf(QuestionSelection(setOf(0), customText = "  C案でお願いします  ")),
        )
        assertEquals(2, answers[0].size)
        assertEquals(listOf("A案", "C案でお願いします"), answers[0])
    }

    @Test
    fun `custom が空白だけなら足さない`() {
        val answers = buildQuestionAnswers(
            listOf(q(custom = true)),
            listOf(QuestionSelection(setOf(0), customText = "   ")),
        )
        assertEquals(listOf("A案"), answers[0])
    }

    @Test
    fun `custom が false の質問では自由入力を無視する`() {
        val answers = buildQuestionAnswers(
            listOf(q(custom = false)),
            listOf(QuestionSelection(setOf(0), customText = "無視されるべき")),
        )
        assertEquals(listOf("A案"), answers[0])
    }

    @Test
    fun `単選と複数選択と custom が混在しても質問の順序と個数が保たれる`() {
        // 計画書のゲート文言そのもの。**外側の長さは質問の数ちょうど**、詰めない。
        val questions = listOf(
            q(label1 = "単1", label2 = "単2"),
            q(label1 = "複1", label2 = "複2", multiple = true),
            q(label1 = "自1", label2 = "自2", custom = true),
        )
        val selections = listOf(
            QuestionSelection(setOf(1)),
            QuestionSelection(setOf(0, 1)),
            QuestionSelection(emptySet(), customText = "自由に書く"),
        )
        val answers = buildQuestionAnswers(questions, selections)
        assertEquals(3, answers.size)
        assertEquals(listOf("単2"), answers[0])
        assertEquals(listOf("複1", "複2"), answers[1])
        assertEquals(listOf("自由に書く"), answers[2])
        // 総要素数も数値で固定する
        assertEquals(4, answers.sumOf { it.size })
    }

    @Test
    fun `選ばれていない質問も空配列で場所を空ける`() {
        // 詰めると i 番目の対応がずれ、**別の質問への回答として解釈される**。
        val answers = buildQuestionAnswers(
            listOf(q(), q(label1 = "X", label2 = "Y")),
            listOf(QuestionSelection(emptySet()), QuestionSelection(setOf(0))),
        )
        assertEquals(2, answers.size)
        assertEquals(emptyList<String>(), answers[0])
        assertEquals(listOf("X"), answers[1])
    }

    @Test
    fun `selections が短くても質問の数だけ答えを作る`() {
        val answers = buildQuestionAnswers(listOf(q(), q()), listOf(QuestionSelection(setOf(0))))
        assertEquals(2, answers.size)
        assertEquals(emptyList<String>(), answers[1])
    }

    // ---------- カードの操作(純粋な状態遷移) ----------

    private fun card(multiple: Boolean = false, custom: Boolean = false) =
        questionCardOf(
            parseSseEnvelope(
                """{"id":"e","type":"question.asked","properties":{"id":"que_abc","sessionID":"$sid",""" +
                    """"questions":[{"question":"q","header":"h","options":""" +
                    """[{"label":"A案","description":"d1"},{"label":"B案","description":"d2"}],""" +
                    """"multiple":$multiple,"custom":$custom}]}}""",
            ) as QuestionAskedEvent,
        )!!

    @Test
    fun `単一選択は置き換えられ、同じものを押すと解除される`() {
        var c = card()
        c = c.toggleOption(0, 0)
        assertEquals(setOf(0), c.selections[0].selectedIndices)
        c = c.toggleOption(0, 1)
        assertEquals("単一選択なので置き換わる", setOf(1), c.selections[0].selectedIndices)
        c = c.toggleOption(0, 1)
        assertEquals("選び直せないカードを作らない", emptySet<Int>(), c.selections[0].selectedIndices)
    }

    @Test
    fun `複数選択は出し入れできる`() {
        var c = card(multiple = true)
        c = c.toggleOption(0, 0).toggleOption(0, 1)
        assertEquals(setOf(0, 1), c.selections[0].selectedIndices)
        c = c.toggleOption(0, 0)
        assertEquals(setOf(1), c.selections[0].selectedIndices)
    }

    @Test
    fun `範囲外の添字では状態が変わらない`() {
        val c = card()
        assertEquals(c, c.toggleOption(0, 9))
        assertEquals(c, c.toggleOption(5, 0))
    }

    @Test
    fun `custom が false の質問には自由入力を書き込めない`() {
        val c = card(custom = false)
        assertEquals(c, c.updateCustomText(0, "書けないはず"))
    }

    @Test
    fun `全部の質問に回答があるときだけ送信できる`() {
        var c = card()
        assertFalse(canSubmitQuestion(c))
        c = c.toggleOption(0, 0)
        assertTrue(canSubmitQuestion(c))
        // 決着済みは押せない
        assertFalse(canSubmitQuestion(c.copy(resolution = QuestionResolution.ANSWERED)))
        // 往復中も押せない
        assertFalse(canSubmitQuestion(c.copy(submitting = true)))
    }

    @Test
    fun `custom だけで送信できる(選択肢を選ばなくてよい)`() {
        val c = card(custom = true).updateCustomText(0, "自分で書く")
        assertTrue(canSubmitQuestion(c))
        assertEquals(listOf(listOf("自分で書く")), c.buildAnswersForTest())
    }

    private fun dev.opencode.android.ui.QuestionCardState.buildAnswersForTest() =
        buildQuestionAnswers(questions, selections)

    // ---------- サーバーを権威にするマージ(Q3 レビュー blocker-2) ----------

    private fun pendingCard(id: String, resolution: QuestionResolution = QuestionResolution.PENDING) =
        card().copy(requestId = id, resolution = resolution)

    @Test
    fun `サーバーがまだ未応答と言うなら失効を取り消し、選択は残す`() {
        val local = listOf(
            pendingCard("que_1", QuestionResolution.EXPIRED)
                .toggleOption(0, 1)
                .copy(submittedAnswers = listOf(listOf("捨てられるべき控え"))),
        )
        val merged = mergePendingQuestions(local, listOf(pendingCard("que_1")), setOf("que_1"))
        assertEquals(1, merged.size)
        assertEquals(QuestionResolution.PENDING, merged[0].resolution)
        assertEquals("選択が消えた", setOf(1), merged[0].selections[0].selectedIndices)
        assertEquals("決着表示の控えが残った", emptyList<List<String>>(), merged[0].submittedAnswers)
    }

    @Test
    fun `サーバーが載せない未応答カードは失効する`() {
        val merged = mergePendingQuestions(listOf(pendingCard("que_1")), emptyList(), setOf("que_1"))
        assertEquals(QuestionResolution.EXPIRED, merged[0].resolution)
    }

    @Test
    fun `取得を始めた後に届いたカードは失効させない`() {
        // knownBeforeFetch に入っていない = 取得中に届いた。サーバーの応答は
        // そのカードを知らなくて当然なので、「サーバーが捨てた」と読んではいけない。
        val merged = mergePendingQuestions(listOf(pendingCard("que_new")), emptyList(), emptySet())
        assertEquals(QuestionResolution.PENDING, merged[0].resolution)
    }

    @Test
    fun `往復中のカードはどちらの向きにも動かさない`() {
        val submitting = pendingCard("que_1").copy(submitting = true)
        assertEquals(submitting, mergePendingQuestions(listOf(submitting), emptyList(), setOf("que_1"))[0])
        val expiredSubmitting = pendingCard("que_2", QuestionResolution.EXPIRED).copy(submitting = true)
        assertEquals(
            expiredSubmitting,
            mergePendingQuestions(listOf(expiredSubmitting), listOf(pendingCard("que_2")), setOf("que_2"))[0],
        )
    }

    @Test
    fun `サーバーにしか無いカードは末尾に足される`() {
        val merged = mergePendingQuestions(
            listOf(pendingCard("que_1")),
            listOf(pendingCard("que_1"), pendingCard("que_2"), pendingCard("que_3")),
            setOf("que_1"),
        )
        assertEquals(listOf("que_1", "que_2", "que_3"), merged.map { it.requestId })
    }

    @Test
    fun `決着済みでサーバーにも無いカードはそのまま残る`() {
        val answered = pendingCard("que_1", QuestionResolution.ANSWERED)
            .copy(submittedAnswers = listOf(listOf("A案")))
        val merged = mergePendingQuestions(listOf(answered), emptyList(), setOf("que_1"))
        assertEquals(QuestionResolution.ANSWERED, merged[0].resolution)
        assertEquals(listOf(listOf("A案")), merged[0].submittedAnswers)
    }

    @Test
    fun `未応答を全部畳む`() {
        val cards = listOf(
            pendingCard("que_1"),
            pendingCard("que_2", QuestionResolution.ANSWERED),
            pendingCard("que_3"),
        )
        assertEquals(
            listOf(QuestionResolution.EXPIRED, QuestionResolution.ANSWERED, QuestionResolution.EXPIRED),
            expireAllPendingQuestions(cards).map { it.resolution },
        )
    }

    // ---------- 回答手段が1つも無い質問(レビュー minor-6) ----------

    @Test
    fun `選択肢も自由入力も無い質問は回答不能`() {
        val noOptions = card().copy(
            questions = listOf(QuestionItem("q", "h", emptyList(), multiple = false, custom = false)),
            selections = listOf(QuestionSelection()),
        )
        assertFalse(noOptions.isAnswerable())
        assertFalse(canSubmitQuestion(noOptions))
        // 選択肢が無くても custom があれば回答できる
        val customOnly = noOptions.copy(
            questions = listOf(QuestionItem("q", "h", emptyList(), multiple = false, custom = true)),
        )
        assertTrue(customOnly.isAnswerable())
        assertTrue(canSubmitQuestion(customOnly.updateCustomText(0, "書いた")))
    }

    @Test
    fun `複数質問のうち1つでも回答不能なら回答不能`() {
        val mixed = card().copy(
            questions = listOf(
                QuestionItem("q1", "h", listOf(QuestionOption("A", "d")), multiple = false, custom = false),
                QuestionItem("q2", "h", emptyList(), multiple = false, custom = false),
            ),
            selections = listOf(QuestionSelection(), QuestionSelection()),
        )
        assertFalse(mixed.isAnswerable())
    }

    // ---------- 表示用の1行 ----------

    @Test
    fun `決着ごとに違う1行が出る`() {
        val c = card()
        assertEquals("回答待ち", questionSummaryText(c))
        assertEquals(
            "回答済み: A案, B案 / X",
            questionSummaryText(
                c.copy(
                    resolution = QuestionResolution.ANSWERED,
                    submittedAnswers = listOf(listOf("A案", "B案"), listOf("X")),
                ),
            ),
        )
        assertEquals("拒否しました", questionSummaryText(c.copy(resolution = QuestionResolution.REJECTED)))
        assertEquals(
            "回答しないまま実行が終了しました",
            questionSummaryText(c.copy(resolution = QuestionResolution.EXPIRED)),
        )
    }

    @Test
    fun `todo の進捗は cancelled を分母から外す`() {
        val todos = listOf(
            TodoDto("a", "completed", "high"),
            TodoDto("b", "cancelled", "low"),
            TodoDto("c", "pending", "medium"),
        ).map { dev.opencode.android.ui.TodoItem(it.content, todoStatusOf(it.status)) }
        assertEquals("完了 1/2", todoProgressText(todos))
        assertEquals("完了 0/0", todoProgressText(emptyList()))
    }
}
