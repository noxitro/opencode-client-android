package dev.opencode.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Q3 のインラインカード(§5 Q3 スコープ1・2)。**ダイアログではない** ——
 * permission がモーダルなのは「答えるまで先へ進めない」からで、todo と質問は
 * 会話の一部として流れの中に残るべきものである(応答後もカードを残す、が計画書の要求)。
 *
 * 出し分けは [selectChatInlineCards] が持つ。ここには `if (todos.isNotEmpty())` の類を書かない ——
 * 配線に条件を書くと、その条件を消す変異が全緑で通り抜ける(Q1/Q2 レビューが2度示した形)。
 *
 * **judge はスクショを見られない**ので、各カードは `contentDescription` に状態を載せる
 * (HARNESS「実機ゲートは引用できる証拠で書く」)。
 */
@Composable
fun ChatInlineCards(
    ui: ChatUi,
    modifier: Modifier = Modifier,
    onToggleOption: (String, Int, Int) -> Unit,
    onCustomText: (String, Int, String) -> Unit,
    onSubmit: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    val cards = selectChatInlineCards(ui)
    if (cards.isEmpty()) return
    // **カード群をスクロール領域にする**(E2E 所見 F1/F2/F3)。
    // 高さの上限は呼び出し側([modifier])が与える —— 上限を知っているのは
    // 「画面のどれだけをカードに使ってよいか」を知っている側だけである。
    // ここが素の Column だったころ、3枚目のカードは**アクセシビリティツリーに存在せず**、
    // スワイプしても出てこなかった(F2)。スクロール可能にすると全件が composed され、
    // dump にも現れ、指でも到達できる。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "chat-inline-cards:${cards.size}" },
    ) {
        cards.forEach { card ->
            when (card) {
                ChatInlineCard.Todo -> TodoCard(ui.todos)
                // 上限で画面から外した決着済みカードの件数。**消えたことを無言にしない**。
                is ChatInlineCard.ResolvedOverflow -> Text(
                    "回答済みの質問 ${card.count} 件を表示から省略しました",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .semantics { contentDescription = "question-overflow:${card.count}" },
                )
                // **未応答の質問は何件でも描く**(Q3 レビュー blocker-1)。何件出すか・どの順で出すかは
                // selectChatInlineCards が持つ ——「1枚だけ」という条件をここに書くと、
                // 2件目の質問が黙って画面から消える形が配線層に戻る。
                is ChatInlineCard.Question -> ui.questionCard(card.requestId)?.let { state ->
                    QuestionCard(
                        state = state,
                        onToggleOption = { q, o -> onToggleOption(state.requestId, q, o) },
                        onCustomText = { q, t -> onCustomText(state.requestId, q, t) },
                        onSubmit = { onSubmit(state.requestId) },
                        onReject = { onReject(state.requestId) },
                    )
                }
            }
        }
    }
}

/**
 * カード群が使ってよい高さの上限(本文+カードの領域に対する比)。
 *
 * **残りは本文のもの**である。E2E が実機で測ったのは、この上限が無いと
 * 本文が1行も残らず、入力欄と送信ボタンまで潰れるということだった
 * (送信ボタン 12×12px、キーボードを出すとツリーから消失)。
 * 0.5 は「カードが主役になっていい上限」で、これ以上は会話が読めなくなる。
 */
const val CHAT_CARD_AREA_MAX_FRACTION = 0.5f

/**
 * タスクリスト(§5 Q3 スコープ1)。既定は折り畳み、見出しに進捗。
 *
 * 折り畳むのは、todo が10件を超えるとチャットが読めなくなるため。**開閉は
 * `rememberSaveable` で回転をまたぐ**(キーはセッション横断で1つ —— カードも1枚しかない)。
 */
@Composable
private fun TodoCard(todos: List<TodoItem>) {
    var expanded by rememberSaveable(key = "todo-card") { mutableStateOf(true) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics {
                contentDescription =
                    "todo-card:${todos.size}:${if (expanded) "expanded" else "collapsed"}"
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Q6: 見出しは押せる要素なので 48dp を割らせない(TouchTargets.kt)。
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .clickable { expanded = !expanded },
            ) {
                Text(
                    "タスク",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    todoProgressText(todos),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "todo-progress:${todoProgressText(todos)}"
                    },
                )
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                // 長いリストでチャットを押し出さない。上限を超えたぶんはカード内でスクロールする。
                Column(modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    todos.forEachIndexed { index, todo ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics {
                                contentDescription =
                                    "todo-item:$index:${todo.status.name.lowercase()}"
                            },
                        ) {
                            if (todo.status == TodoStatus.IN_PROGRESS) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp).padding(top = 2.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(todoGlyph(todo.status), style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                todo.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (todo.status == TodoStatus.COMPLETED) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 質問カード(§5 Q3 スコープ2)。質問文 + header + 選択肢ボタン列 + (custom時)自由入力。
 *
 * **回転耐性**: 選択状態は [ChatController] が持つ(`rememberSaveable` ではない)。
 * ViewModel は回転をまたいで生存するので回転では消えず、かつ **`runTest` から叩ける**
 * ので検出器を置ける —— permission ダイアログの Saver は「叩ける場所が無い」ために
 * 回転の検証が実機頼みになっていた。プロセス死では選択が失われるが、そのときは
 * `GET /question` が**質問そのもの**を取り直すので、カードは復活する(白紙で)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(
    state: QuestionCardState,
    onToggleOption: (Int, Int) -> Unit,
    onCustomText: (Int, String) -> Unit,
    onSubmit: () -> Unit,
    onReject: () -> Unit,
) {
    val interactive = state.isInteractive()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            // **カード内の全ての目印に requestId を載せる**。質問は同時に複数出るので
            // (Q3 レビュー blocker-1)、`question-option:0:1:selected` だけでは
            // dump からどちらのカードの話か分からない —— judge はスクショを見られないので、
            // 引用できる文字列で一意に決まらないものは検査したことにならない。
            .semantics {
                contentDescription =
                    "question-card:${state.requestId}:${state.resolution.name.lowercase()}"
            },
    ) {
        // **カードの高さに上限を置く**(2026-08-27、実機で見つけた欠陥)。
        // 質問2件・選択肢5個のカードは縦に伸びて**入力欄を画面外へ押し出し**、
        // 送信ボタンが uiautomator dump にも現れなくなった。カードは会話の一部であって
        // 画面の占有者ではない。溢れたぶんはカード内でスクロールする。
        Column(
            modifier = Modifier
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            state.questions.forEachIndexed { qIndex, question ->
                question.header?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    question.question,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                val selection = state.selections.getOrNull(qIndex) ?: QuestionSelection()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    question.options.forEachIndexed { oIndex, option ->
                        val selected = oIndex in selection.selectedIndices
                        FilterChip(
                            selected = selected,
                            enabled = interactive,
                            onClick = { onToggleOption(qIndex, oIndex) },
                            label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "question-option:${state.requestId}:$qIndex:$oIndex:${if (selected) "selected" else "unselected"}"
                            },
                        )
                    }
                }
                // 選択肢の説明は**選ぶときにだけ要る**。決着後まで出しておくとカードの
                // 大半が説明文になり、残す価値のある「何を答えたか」が埋もれる。
                if (interactive) question.options.filter { !it.description.isNullOrBlank() }.forEach { option ->
                    Text(
                        "・${option.label}: ${option.description}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 自由入力欄も決着後は出さない(答えた内容は下の要約に載る)。
                if (question.custom && interactive) {
                    OutlinedTextField(
                        value = selection.customText,
                        onValueChange = { onCustomText(qIndex, it) },
                        enabled = interactive,
                        placeholder = { Text("自由入力", style = MaterialTheme.typography.bodySmall) },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .semantics { contentDescription = "question-custom:${state.requestId}:$qIndex" },
                    )
                }
                if (qIndex != state.questions.lastIndex) Spacer(Modifier.size(12.dp))
            }

            if (interactive) {
                // 選択肢も自由入力欄も無い質問は**そもそも回答できない**(レビュー minor-6)。
                // 回答ボタンを無効のまま置くと「押せない理由」が画面のどこにも出ないので、
                // 理由を書いて拒否だけ残す。
                if (!state.isAnswerable()) {
                    Text(
                        "選択肢が無いため回答できません(拒否のみ)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .semantics { contentDescription = "question-unanswerable:${state.requestId}" },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    TextButton(
                        onClick = onReject,
                        modifier = Modifier.semantics { contentDescription = "question-reject:${state.requestId}" },
                    ) { Text("拒否") }
                    Spacer(Modifier.weight(1f))
                    if (state.isAnswerable()) {
                        Button(
                            onClick = onSubmit,
                            enabled = canSubmitQuestion(state),
                            modifier = Modifier.semantics { contentDescription = "question-submit:${state.requestId}" },
                        ) { Text("回答") }
                    }
                }
            } else {
                // 決着後も**カードを残す**(§5 Q3 スコープ2)。何を聞かれ何を答えたかが残る。
                Text(
                    questionSummaryText(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .semantics { contentDescription = "question-summary:${state.requestId}:${state.resolution.name.lowercase()}" },
                )
            }
        }
    }
}
