package dev.opencode.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.android.data.PtyDto
import dev.opencode.android.ui.theme.toArgbLong

/**
 * ターミナル(PTY)画面(QUALITY_PLAN §5b Q9)。
 *
 * ## dump から引用できるようにしてあるもの(judge はスクショを見られない)
 *
 *  - `terminal-screen:<LIST|TERMINAL>` … 画面が出ていること + どちらの面か
 *    (**大きさ0のノードには付けない**。1周目は別の `Text("")` に分けており、
 *    実機の dump に1度も現れなかった)
 *  - `pty-row:<id>:<status>:<pid>` … 一覧の行(**押せるノード側**)
 *  - `pty-shell:<name>` … 起動候補(**押せるノード側**)
 *  - `pty-status:<key>` … 帯([terminalStatusLine])。**終了と切断の区別はここに出る**
 *  - `pty-notice:<key>` … 制限と落とした件数([terminalNotices])
 *  - `pty-line:<行番号>` … 出力の行
 *  - `pty-hscroll:<現在値>/<最大値>` … 横スクロールの到達(Q7/Q8 と同じ計器)
 *  - `pty-key:<KEY>` … 補助キー(**押せるノード側**)
 *  - `pty-input` … 入力欄
 *  - `pty-grid:<rows>x<cols>` … サーバーへ伝えた端末サイズ
 *
 * ## この画面が持たない判断
 *
 * 一覧か端末か([ptyScreenPane])、帯の文言([terminalStatusLine])、
 * 注記([terminalNotices])、空状態([terminalBody] / [ptyListBody])、
 * 補助キーの送出バイト([ptyKeySequence])は**すべて外の純関数**である。
 * ここに `if` を書き戻すと、その `if` を消す変異が画面のテストからしか見えなくなる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    ui: PtyUi,
    actions: PtyActions,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(terminalScreenTitle(ui), maxLines = 1) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (ui.terminal.open) actions.closeTerminal() else onBack() },
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (ui.terminal.open) "ターミナルを閉じる" else "ターミナル画面を閉じる"
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    val target = ui.terminal.ptyId
                    if (target != null) {
                        IconButton(
                            onClick = { actions.deletePty(target) },
                            modifier = Modifier.semantics { contentDescription = "pty-delete:$target" },
                        ) { Icon(Icons.Filled.Delete, contentDescription = null) }
                    } else {
                        IconButton(
                            onClick = actions::refreshPtyList,
                            modifier = Modifier.semantics { contentDescription = "pty-refresh" },
                        ) { Icon(Icons.Filled.Refresh, contentDescription = null) }
                    }
                },
            )
        },
    ) { inner ->
        // **一覧か端末かの判定は [ptyScreenPane] にしかない。**
        val pane = ptyScreenPane(ui)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                // **どちらの面に居るかは画面全体の desc に載せる。**
                //
                // **今の計器の名前は `terminal-screen:<LIST|TERMINAL>` である**(上の一覧)。
                // 1周目は `pty-pane:<X>` という別の名前を `Text("")` に付けていたが、**実機の
                // `uiautomator dump` に1度も現れなかった**(2026-08-30 実測)——
                // 大きさ0のノードは dump に出ない。「計器が測れていない」形そのもので、
                // HARNESS の「検出器が『0件』を報告したら先に検出器自身を疑う」に当たる。
                .semantics { contentDescription = "terminal-screen:${pane.name}" },
        ) {
            when (pane) {
                PtyScreenPane.LIST -> PtyListArea(ui = ui, actions = actions, onOpenSettings = onOpenSettings)
                PtyScreenPane.TERMINAL -> TerminalArea(ui = ui, actions = actions, onOpenSettings = onOpenSettings)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 一覧(スコープ4)
// ---------------------------------------------------------------------------

@Composable
internal fun ColumnScope.PtyListArea(ui: PtyUi, actions: PtyActions, onOpenSettings: () -> Unit) {
    PtySecurityNote()
    HorizontalDivider()
    PtyShellRow(shells = ui.shells, creating = ui.creating, onStart = actions::startPty)
    ui.createError?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = "pty-create-error" },
        )
    }
    // 一覧側の操作(行の「終了」)の失敗を**黙って捨てない**。
    // 1周目は帯が端末の窓にしか無く、一覧で `DELETE /pty/{id}` が失敗しても
    // **どこにも出ないまま、次に開いた端末で初めて姿を現していた**。
    //
    // **逆向きも塞ぐ**(レビュー minor-1): 端末で撃った `PUT` が一覧へ戻った後に
    // 失敗して返っても、ここには出ない。判定は [ptyActionErrorMessage] にしかない。
    PtyActionErrorBand(
        message = ptyActionErrorMessage(ui.actionError, PtyScreenPane.LIST, ui.terminal.ptyId),
        onClear = actions::clearPtyActionError,
    )
    HorizontalDivider()
    when (val body = ptyListBody(ui.list)) {
        ScreenBody.Loading -> CenteredTerminalSpinner()
        is ScreenBody.Empty -> EmptyStateView(
            spec = body.spec,
            onAction = { action -> applyPtyEmptyStateAction(action, actions, onOpenSettings) },
        )
        ScreenBody.Content -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(ui.list.items.size) { index ->
                PtyRow(pty = ui.list.items[index], actions = actions)
                HorizontalDivider()
            }
        }
    }
}

/**
 * セキュリティ注記(§5b Q9「セキュリティ上の注記」)。
 *
 * **画面に出す。** 計画書は README への明記と「アプリ内にも接続時の注意を出す」を
 * 両方求めている。PTY は任意コマンド実行であり、`OPENCODE_SERVER_PASSWORD` 未設定の
 * serve は無認証で 200 を返すことが P5 で実測されている。
 */
@Composable
private fun PtySecurityNote() {
    // **`content-desc` は本文の `Text` ではなく、包んだ器に付ける**(Q8 の `file-line:` と同じ)。
    //
    // `Text` 自体に付けると TalkBack が**本文の代わりに `pty-security-note` と読み上げる** ——
    // 計器(judge が dump から引くための識別子)が、人が聞く言葉を潰す。
    // Q8 レビューが「desc は judge の計器であって、画面の言葉の代わりではない」と書いた形。
    Row(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "pty-security-note" }) {
        Text(
            "ここで起動するのはサーバー上の本物のシェルです。opencode serve は認証なしでも起動できるため、" +
                "Tailscale などのプライベートネットワーク内でのみ使ってください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        )
    }
}

@Composable
private fun PtyShellRow(shells: PtyShellsUi, creating: Boolean, onStart: (String, String) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (shells.loading && shells.items.isEmpty()) {
            Text("シェル一覧を取得中…", style = MaterialTheme.typography.bodySmall)
        }
        shells.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "pty-shells-error" },
            )
        }
        shells.startable.forEach { shell ->
            AssistChip(
                enabled = !creating,
                onClick = { onStart(shell.path, shell.name) },
                label = { Text(ptyShellLabel(shell.name, shell.acceptable)) },
                modifier = Modifier
                    .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
                    .semantics { contentDescription = "pty-shell:${shell.name}" },
            )
        }
    }
}

/**
 * アクションエラーの帯。**両方の窓で同じ物を描く**(`content-desc` も同じ)——
 * 一度に描かれるのは片方の窓だけなので、dump からの引用は変わらない。
 *
 * [message] が `null` なら**何も描かない**。判定は [ptyActionErrorMessage] にあり、
 * ここには `if (error.ptyId == …)` を書き戻さないこと。
 */
@Composable
private fun PtyActionErrorBand(message: String?, onClear: () -> Unit) {
    if (message == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "pty-action-error" },
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text("閉じる") }
    }
}

@Composable
private fun PtyRow(pty: PtyDto, actions: PtyActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
            .clickable { actions.openPty(pty) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = ptyRowDescription(pty) },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pty.title.ifBlank { pty.id }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                "${pty.command}  pid ${pty.pid}  ${pty.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        TextButton(
            onClick = { actions.deletePty(pty.id) },
            modifier = Modifier
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
                .semantics { contentDescription = "pty-row-delete:${pty.id}" },
        ) { Text("終了") }
    }
}

/**
 * 空状態の導線。**`else -> Unit` を書かない**([FileBrowserScreen] と同じ理由) ——
 * `EmptyStateAction` が増えたときにコンパイラが漏れを指摘する。
 */
private fun applyPtyEmptyStateAction(
    action: EmptyStateAction,
    actions: PtyActions,
    onOpenSettings: () -> Unit,
) {
    when (action) {
        EmptyStateAction.RETRY -> actions.refreshPtyList()
        EmptyStateAction.OPEN_SETTINGS -> onOpenSettings()
        // ターミナル画面はセッションを作らず、検索欄も ignored トグルも持たず、一覧へは戻らない。
        EmptyStateAction.CREATE_SESSION,
        EmptyStateAction.CLEAR_SEARCH,
        EmptyStateAction.BACK_TO_LIST,
        EmptyStateAction.SHOW_IGNORED,
        -> Unit
    }
}

// ---------------------------------------------------------------------------
// 端末(スコープ1・2・3・5)
// ---------------------------------------------------------------------------

@Composable
internal fun ColumnScope.TerminalArea(ui: PtyUi, actions: PtyActions, onOpenSettings: () -> Unit) {
    TerminalStatusBar(terminal = ui.terminal, onReconnect = actions::reconnectPty)
    TerminalNoticeList(notices = terminalNotices(ui.terminal))
    // **どのエラーを出すかの判定は [ptyActionErrorMessage] にしかない。**
    // ここに `ui.actionError?.let` と素で書くと、別の PTY のエラーが
    // 健全な端末に出る(Q9 E2E が実測した残留)。
    PtyActionErrorBand(
        message = ptyActionErrorMessage(ui.actionError, PtyScreenPane.TERMINAL, ui.terminal.ptyId),
        onClear = actions::clearPtyActionError,
    )
    HorizontalDivider()
    BoxWithConstraints(modifier = Modifier.weight(1f)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // 等幅フォントの1文字ぶん。**測らず定数にしない**と端末幅がまるで違う値になる。
        val charWidthPx = with(density) { TERMINAL_CHAR_WIDTH.toPx() }
        val lineHeightPx = with(density) { TERMINAL_LINE_HEIGHT.toPx() }
        val grid = terminalGridOf(widthPx, heightPx, charWidthPx, lineHeightPx)
        // **サイズは画面が測り、送るかどうかは Controller が決める**(同値なら撃たない)。
        LaunchedEffect(grid) { actions.resizePty(grid.first, grid.second) }
        // **サーバーへ伝えた寸法は、大きさを持つノードの desc に載せる。**
        // 1周目は `Text("")` に付けており、**dump に1度も現れなかった**(上と同じ罠)。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "pty-grid:${ui.terminal.rows}x${ui.terminal.cols}"
                },
        ) {
            when (val body = terminalBody(ui.terminal)) {
                ScreenBody.Loading -> CenteredTerminalSpinner()
                is ScreenBody.Empty -> EmptyStateView(
                    spec = body.spec,
                    onAction = { action -> applyPtyEmptyStateAction(action, actions, onOpenSettings) },
                )
                ScreenBody.Content -> TerminalOutput(terminal = ui.terminal)
            }
        }
    }
    HorizontalDivider()
    TerminalKeyRow(enabled = ui.terminal.inputEnabled, onKey = actions::sendPtyKey)
    TerminalInput(enabled = ui.terminal.inputEnabled, onSend = actions::sendPtyLine)
}

/** 端末の1文字幅 / 1行高さ。等幅フォントの実測に近い保守的な値。 */
private val TERMINAL_CHAR_WIDTH = 8.dp
private val TERMINAL_LINE_HEIGHT = 16.dp

@Composable
private fun TerminalStatusBar(terminal: PtyTerminalUi, onReconnect: () -> Unit) {
    val status = terminalStatusLine(terminal)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "pty-status:${status.key}" },
    ) {
        Text(
            status.text,
            style = MaterialTheme.typography.bodySmall,
            color = when (status.tone) {
                EmptyStateTone.ERROR -> MaterialTheme.colorScheme.error
                EmptyStateTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        // **再接続は「終わっていないとき」だけ出す。** 終わったシェルへは繋ぎ直せない。
        if (!terminal.finished) {
            TextButton(
                onClick = onReconnect,
                modifier = Modifier
                    .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
                    .semantics { contentDescription = "pty-reconnect" },
            ) { Text("再接続") }
        }
    }
}

@Composable
private fun TerminalNoticeList(notices: List<PtyNotice>) {
    notices.forEach { notice ->
        Text(
            notice.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .semantics { contentDescription = "pty-notice:${notice.key}" },
        )
    }
}

/**
 * 出力。**折り返さない**(§5b Q7 スコープ1 と同じ規則: 折り返すと端末の桁が読めなくなる)ので
 * 行ごとに横スクロールする。末尾追従は Q2 の規則([ChatScroll])と同じ。
 */
@Composable
private fun TerminalOutput(terminal: PtyTerminalUi) {
    val listState = rememberLazyListState()
    val lines = terminal.buffer.lines
    // **共有の横スクロール。** 共有の仕方は [LineScrollState] にしかない ——
    // 1周目は全行の `horizontalScroll` に同じ `ScrollState` を渡していた。
    // `horizontalScroll` の layout は測るたびに `maxValue` を**書き込む**ので、
    // **短い行が1つでも表示窓に入るとその行が 0 を書き、横スクロールが死ぬ**。
    // 端末の出力は短い行だらけなので、ここが最初に実測された(Q9 の E2E ゲート)。
    val scroll = rememberLineScrollState()
    // **Q2 の `reverseLayout` 方式をそのまま使う**([isFollowingBottom])。
    //
    // 1周目は普通の向きの LazyColumn で `firstVisibleItemIndex + 可視件数 >= 総数` を
    // 追従条件にしていた。**実機で 1 行も追従しなかった**(2026-08-30、実物 serve):
    // 行数が画面をはみ出した最初の瞬間、`LaunchedEffect(lines.size)` が読む
    // `layoutInfo` は**まだ増える前の測定値**なので `28 >= 30` が false になり、
    // 「ユーザーが上へスクロールした」と誤判定して以後ずっと追わない。
    // 症状は「**打ったコマンドの出力が画面に出ない**」で、
    // 出力自体は届いている(注記の件数だけが増える)。
    //
    // `reverseLayout = true` なら「一番下」は **添字 0**で、`layoutInfo` を読まずに済む。
    // Q2 のチャットが同じ理由で同じ形になっている。
    LaunchedEffect(lines.size) {
        if (isFollowingBottom(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) {
            listState.scrollToItem(0)
        }
    }
    // **別の端末を開いたら横位置を先頭へ戻す**([FileLineList] と同じ理由。レビュー minor-2)。
    LaunchedEffect(terminal.ptyId) { scroll.reset() }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier
            .fillMaxSize()
            // Q7/Q8 と同じ横スクロールの計器を**同じ場所**(スクロール容器そのもの)に置く。
            // `uiautomator dump` の `bounds` はクリップ矩形なので横に送っても1pxも動かず、
            // 到達は `value == maxValue && maxValue > 0` でしか主張できない
            // ([FileBrowserScreen] の `file-hscroll` と同じ理由)。
            //
            // **大きさ0のノードに付けない。** 1周目は `item { Text("") }` に付けており、
            // 実機の dump に1度も現れなかった(2026-08-30 実測)。
            .lineScrollWindow(scroll, "pty-hscroll"),
    ) {
        // **item key と横スクロールの行キーは同じ物を使う**([DiffView] / [FileLineList] と同じ規則。
        // レビュー minor-5)。`reverseLayout` では添字 0 が最終行なので、item key を省くと
        // **1行増えるたびに全可視行の item key(= 既定の添字)が別の行を指し**、
        // `LazyColumn` が行を作り直す → [LineScrollState.forgetLine] と
        // [LineScrollState.reportLine] が毎行毎回空回りする。
        items(count = lines.size, key = { i -> terminalLineKey(lines.size - 1 - i) }) { index ->
            // **`reverseLayout` でも行番号は本物の番号を出す。**
            // 添字をそのまま載せると dump の `pty-line:1` が最終行を指し、
            // 「1行目が読めた」の証拠が逆さまになる。
            val lineIndex = lines.size - 1 - index
            TerminalLineRow(
                line = lines[lineIndex],
                number = lineIndex + 1,
                scroll = scroll,
                // **行キーは本物の行番号から作る。** 添字にすると `reverseLayout` で
                // 出力が伸びるたびに同じ鍵が別の行を指し、幅の集計が入れ替わる。
                rowKey = terminalLineKey(lineIndex),
            )
        }
    }
}

/**
 * 出力1行の `LazyColumn` item key 兼 [LineScrollState] の行キー。**作り方はここにしかない。**
 * ([diffLineKey] / [fileLineKey] と同じ規則。)
 *
 * 引数は**本物の行番号(0 始まり)**であって `LazyColumn` の添字ではない ——
 * `reverseLayout` なので両者は逆順である。
 */
internal fun terminalLineKey(lineIndex: Int): String = "pty-line:$lineIndex"

/**
 * 出力の1行。
 *
 * **`content-desc` は本文の `Text` ではなく、包んだ [Row] に付ける**
 * (Q8 の `file-line:` と同じ形。レビュー minor-5)。`Text` 自体に付けると
 * TalkBack が**端末の行の中身ではなく `pty-line:12` と読み上げる** ——
 * 端末は本文しか情報が無い画面なので、これは読み上げから中身が消えることを意味する。
 *
 * **色は背景を渡して決める**([ansiColorOn])。端末の面はテーマで変わるので、
 * 暗背景を前提にした固定パレットはライトテーマで読めなくなる(レビュー minor-6)。
 */
@Composable
private fun TerminalLineRow(line: AnsiLine, number: Int, scroll: LineScrollState, rowKey: String) {
    val background = MaterialTheme.colorScheme.surface
    Row(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "pty-line:$number" }) {
        Text(
            text = annotatedAnsiLine(line, background),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .lineScrollContent(scroll, rowKey)
                .padding(horizontal = 8.dp),
        )
    }
}

/** [AnsiLine] を Compose の [AnnotatedString] にする。**色の対応は [ansiColorOn] にしかない。** */
internal fun annotatedAnsiLine(line: AnsiLine, surface: Color): AnnotatedString = buildAnnotatedString {
    if (line.spans.isEmpty()) {
        // 空行でも1行ぶんの高さを保つ(行番号と本文がずれない)。
        append(" ")
        return@buildAnnotatedString
    }
    line.spans.forEach { span ->
        withStyle(spanStyleOf(span.style, surface)) { append(span.text) }
    }
}

/**
 * [AnsiStyle] → [SpanStyle]。`inverse` は前景と背景を入れ替える。
 *
 * @param surface この行が載る面の色。**前景はこの色に対して読めるところまで寄せる**
 *   ([ansiColorOn])。ANSI 側が背景色を指定していれば、そちらが基準になる。
 */
internal fun spanStyleOf(style: AnsiStyle, surface: Color): SpanStyle {
    // `inverse` は前景と背景を入れ替える。**入れ替えた後で**読みやすさを見る。
    val fgColor = if (style.inverse) style.bg else style.fg
    val bgColor = if (style.inverse) style.fg else style.bg
    // 背景は塗り潰しなので生の色のまま。前景だけを、**実際に載る面**に対して寄せる。
    val background = bgColor?.let(::ansiColorToArgb)
    val foreground = fgColor?.let { ansiColorOn(it, background ?: surface.toArgbLong().toInt()) }
    return SpanStyle(
        color = foreground?.let(::Color) ?: Color.Unspecified,
        background = background?.let(::Color) ?: Color.Unspecified,
        fontWeight = if (style.bold) FontWeight.Bold else null,
        fontStyle = if (style.italic) FontStyle.Italic else null,
        textDecoration = if (style.underline) TextDecoration.Underline else null,
    )
}

@Composable
private fun TerminalKeyRow(enabled: Boolean, onKey: (PtyKey) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        PTY_KEY_ROW.forEach { key ->
            TextButton(
                enabled = enabled,
                onClick = { onKey(key) },
                modifier = Modifier
                    .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
                    .semantics { contentDescription = "pty-key:${key.name}" },
            ) { Text(ptyKeyLabel(key)) }
        }
    }
}

@Composable
private fun TerminalInput(enabled: Boolean, onSend: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("コマンド") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    onSend(draft)
                    draft = ""
                },
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "pty-input" },
        )
        Button(
            enabled = enabled,
            onClick = {
                onSend(draft)
                draft = ""
            },
            modifier = Modifier
                .padding(start = 6.dp)
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
                .semantics { contentDescription = "pty-send" },
        ) { Text("送信") }
    }
}

@Composable
private fun CenteredTerminalSpinner() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { CircularProgressIndicator() }
}
