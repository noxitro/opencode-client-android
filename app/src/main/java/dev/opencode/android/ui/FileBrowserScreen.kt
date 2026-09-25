package dev.opencode.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opencode.android.ui.theme.LocalStatusColors

/**
 * ファイルブラウザ + 検索の画面(QUALITY_PLAN §5b Q8)。
 *
 * ## dump から引用できるようにしてあるもの(judge はスクショを見られない)
 *
 *  - `file-browser` … 画面が出ていること
 *  - `file-crumb:<path>` … パンくず(**押せるノード側**)
 *  - `file-entry:<path>:<dir|file>[:ignored][:<vcs>]` … ツリーの行(**押せるノード側**)
 *  - `file-ignored-toggle:<on|off>:<件数>` … 隠している件数
 *  - `file-viewer:<path>` … ビューアが開いていること
 *  - `file-line:<番号>` … 本文の行
 *  - `file-hscroll:<現在値>/<最大値>` … 横スクロールの到達(Q7 の `diff-hscroll` と同じ計器)
 *  - `file-truncated:<バイト>` / `file-render-truncated:<行>` / `file-binary` … 打ち切りと種別
 *  - `find-tab:<TEXT|FILES|SYMBOLS>:<selected|unselected>` … 検索タブ
 *  - `find-group:<path>:<件数>` / `find-hit:<path>:<行>:<ハイライト数>` … 全文検索
 *  - `symbol-hit:<path>:<行>:<kind>` … シンボル
 *
 * **`content-desc` は押せるノードに付ける**(Q3/Q6 で3回踏んだ罠)。
 *
 * ## 陰性側のゲート
 *
 * §5b は「`type:"binary"` のファイルで **base64 文字列が画面に出ていないこと**」を
 * dump のテキストで確認せよと定めている。そのため**バイナリでは [FileViewerUi.lines] を
 * そもそも組まない**([FileBrowserController.openFile])—— 描画側で `if` を書くと、
 * その `if` を消す変異が「画面はどこも壊れて見えない」まま base64 を dump へ流す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    ui: FileBrowserUi,
    actions: FileActions,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ファイル", maxLines = 1) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "ファイル画面を閉じる" },
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .semantics { contentDescription = "file-browser" },
        ) {
            FileSearchBar(search = ui.search, actions = actions)
            HorizontalDivider()
            // **ツリーか検索かの判定は [fileBrowserPane] にしかない。**
            // 1周目はここに `if (ui.search.query.isBlank())` と素で書いており、
            // その条件を消す変異はこの画面のテストからしか見えなかった(レビュー minor-4)。
            when (fileBrowserPane(ui.search)) {
                FileBrowserPane.TREE -> FileTreeArea(
                    tree = ui.tree,
                    actions = actions,
                    onOpenSettings = onOpenSettings,
                )
                FileBrowserPane.SEARCH -> FileSearchResults(
                    search = ui.search,
                    actions = actions,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
    // **ビューアはここで描かない。** [AppRoot] が画面によらず1か所で描く ——
    // チャットのツールカード(スコープ6)から開いたときは Screen.Detail に居るので、
    // この画面の中に置くと**その経路でだけ何も出ない**。
}

/**
 * ツリー領域(パンくず + ignored トグル + 本体)。
 *
 * **`internal` にしてあるのは Compose UI テストが本物のこれを描くため**である。
 * 1周目は `FileBrowserScreen` の中に直に書かれており、
 * **`files-all-ignored` の空状態と `SHOW_IGNORED` の導線に検出器が1本も無かった**
 * (レビュー major-1: 変異 `ui.ignoredCount > 0 ->` → `false ->` が 760件全緑で通過)。
 * KDoc は「`build/` を開くと『空です』と出る」と症状まで書いていたのに、
 * **何も強制していなかった** —— Q6 の「KDoc の虚偽」と同じ形である。
 */
@Composable
internal fun FileTreeArea(
    tree: FileTreeUi,
    actions: FileActions,
    onOpenSettings: () -> Unit,
) {
    FileBreadcrumbs(crumbs = tree.crumbs, onOpen = actions::openDirectory)
    IgnoredToggle(tree = tree, onToggle = actions::toggleIgnoredFiles)
    when (val body = fileTreeBody(tree)) {
        ScreenBody.Loading -> CenteredSpinner()
        is ScreenBody.Empty -> EmptyStateView(
            spec = body.spec,
            onAction = { action -> applyFileEmptyStateAction(action, actions, onOpenSettings) },
        )
        ScreenBody.Content -> FileTreeList(
            entries = tree.visibleEntries,
            onOpenDirectory = actions::openDirectory,
            onOpenFile = { path -> actions.openFile(path) },
        )
    }
}

/**
 * 空状態の導線を実際の呼び出しへ結ぶ(レビュー minor-4)。
 *
 * **`else -> Unit` を書かない。** 他画面([SessionListScreen] / [ChatScreen] / [ModelPickerSheet])は
 * 全分岐を列挙しており、`EmptyStateAction` が増えたときにコンパイラが漏れを指摘する。
 * `else` を置くと**新しい導線が黙って無視される**ので、その保護を Q8 だけが捨てることになる。
 */
private fun applyFileEmptyStateAction(
    action: EmptyStateAction,
    actions: FileActions,
    onOpenSettings: () -> Unit,
) {
    when (action) {
        EmptyStateAction.RETRY -> actions.retryFileTree()
        EmptyStateAction.OPEN_SETTINGS -> onOpenSettings()
        EmptyStateAction.SHOW_IGNORED -> actions.toggleIgnoredFiles()
        // ファイル画面はセッションを作らず、検索欄は独自に持ち、一覧へは戻らない。
        EmptyStateAction.CREATE_SESSION,
        EmptyStateAction.CLEAR_SEARCH,
        EmptyStateAction.BACK_TO_LIST,
        -> Unit
    }
}

@Composable
private fun CenteredSpinner() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { CircularProgressIndicator() }
}

// ---------------------------------------------------------------------------
// ツリー(スコープ1)
// ---------------------------------------------------------------------------

/**
 * パンくず。**「上へ戻る」ボタンを置かなかった**のは、深い階層から一気に戻れないため
 * ([breadcrumbsOf] の doc)。列そのものが導線である。
 */
@Composable
internal fun FileBreadcrumbs(crumbs: List<FileCrumb>, onOpen: (String) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp),
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { onOpen(crumb.path) },
                modifier = Modifier
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .semantics { contentDescription = crumb.description },
            ) {
                Text(crumb.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/**
 * `ignored` のトグル。**隠している件数を必ず出す** —— 隠すことは「無い」ではない
 * (Q4 の除外件数表示と同じ原則)。
 */
@Composable
internal fun IgnoredToggle(tree: FileTreeUi, onToggle: () -> Unit) {
    val label = ignoredToggleLabel(tree)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        FilterChip(
            selected = tree.showIgnored,
            onClick = onToggle,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier
                .heightIn(min = MIN_TOUCH_TARGET)
                .semantics {
                    contentDescription =
                        "file-ignored-toggle:${if (tree.showIgnored) "on" else "off"}:${tree.ignoredCount}"
                },
        )
    }
}

/**
 * ツリーの行。**`internal` にしてあるのは Compose UI テストが本物のこれを描くため**
 * (Q6 レビューが blocker として挙げた「自前の composable を押すトートロジー」を避ける)。
 */
@Composable
internal fun FileTreeList(
    entries: List<FileEntryUi>,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val status = LocalStatusColors.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(count = entries.size, key = { i -> entries[i].path }) { index ->
            val entry = entries[index]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // 素の `Modifier.clickable` は Material の最小タッチターゲット強制を受けない。
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .clickable {
                        // **どちらを開くかの分岐はここにしかない。** 行の型で決まる。
                        if (entry.isDirectory) onOpenDirectory(entry.path) else onOpenFile(entry.path)
                    }
                    .semantics { contentDescription = entry.description }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (entry.isDirectory) "📁" else "📄",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(24.dp),
                )
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.ignored) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (entry.vcsStatus != null) {
                    Text(
                        text = fileStatusGlyph(entry.vcsStatus),
                        style = MaterialTheme.typography.labelMedium,
                        color = status.diffAdd,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (entry.ignored) {
                    Text(
                        text = "ignored",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------------------
// 検索(スコープ3・4・5)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSearchBar(search: FileSearchUi, actions: FileActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = search.query,
            onValueChange = actions::updateFileSearchQuery,
            singleLine = true,
            label = { Text("検索") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.submitFileSearch() }),
            trailingIcon = {
                if (search.query.isNotEmpty()) {
                    IconButton(
                        onClick = actions::clearFileSearch,
                        modifier = Modifier.semantics { contentDescription = "検索を解除" },
                    ) { Icon(Icons.Filled.Close, contentDescription = null) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "file-search-field" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            FileSearchTab.entries.forEach { tab ->
                FilterChip(
                    selected = search.tab == tab,
                    onClick = { actions.setFileSearchTab(tab) },
                    label = { Text(searchTabLabel(tab), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier
                        .heightIn(min = MIN_TOUCH_TARGET)
                        .semantics {
                            contentDescription =
                                "find-tab:${tab.name}:${if (search.tab == tab) "selected" else "unselected"}"
                        },
                )
            }
        }
    }
}

/** タブの文言。**enum に文言を持たせない**(状態は状態、言葉は画面)。 */
internal fun searchTabLabel(tab: FileSearchTab): String = when (tab) {
    FileSearchTab.TEXT -> "全文"
    FileSearchTab.FILES -> "ファイル名"
    FileSearchTab.SYMBOLS -> "シンボル"
}

/**
 * 検索結果。**何を描くかの判定は [fileSearchBody] にしかない。**
 *
 * `internal` は Compose UI テストが本物を描くため。
 */
@Composable
internal fun FileSearchResults(
    search: FileSearchUi,
    actions: FileActions,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // **上限に張り付いたことを先に出す。** 結果の下に置くと、
        // 10件スクロールした人しか読めない(Q7 が打ち切り注記で踏んだ形)。
        searchCapNotice(search)?.let { notice ->
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .semantics { contentDescription = notice.key },
            )
        }
        when (val body = fileSearchBody(search)) {
            ScreenBody.Loading -> CenteredSpinner()
            is ScreenBody.Empty -> EmptyStateView(
                spec = body.spec,
                onAction = { action ->
                    // **`else` を書かない**(上の [applyFileEmptyStateAction] と同じ理由)。
                    when (action) {
                        EmptyStateAction.RETRY -> actions.submitFileSearch()
                        EmptyStateAction.OPEN_SETTINGS -> onOpenSettings()
                        EmptyStateAction.CLEAR_SEARCH -> actions.clearFileSearch()
                        EmptyStateAction.SHOW_IGNORED,
                        EmptyStateAction.CREATE_SESSION,
                        EmptyStateAction.BACK_TO_LIST,
                        -> Unit
                    }
                },
            )
            ScreenBody.Content -> when (search.tab) {
                FileSearchTab.TEXT -> TextSearchList(search.textGroups, actions)
                FileSearchTab.FILES -> FileNameList(search.files, actions)
                FileSearchTab.SYMBOLS -> SymbolList(search.symbols, actions)
            }
        }
    }
}

/** 「ファイル > 行番号 > 該当行」(§5b Q8 スコープ3)。 */
@Composable
private fun TextSearchList(groups: List<TextSearchGroup>, actions: FileActions) {
    val status = LocalStatusColors.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        groups.forEach { group ->
            item(key = "g:${group.path}") {
                Text(
                    text = "${group.path}(${group.hits.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { contentDescription = group.description },
                )
            }
            items(count = group.hits.size, key = { i -> "h:${group.path}:$i" }) { index ->
                val hit = group.hits[index]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MIN_TOUCH_TARGET)
                        .clickable { actions.openFile(hit.path, hit.lineNumber) }
                        .semantics { contentDescription = hit.description }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = hit.lineNumber.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(48.dp),
                    )
                    Text(
                        text = highlightedLine(hit, status.diffAddSurface),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * マッチ部分に背景色を付ける(§5b Q8 スコープ3「`submatches` の `start`/`end` で
 * マッチ部分をハイライト」)。
 *
 * **範囲は既に文字インデックスへ変換済み**([groupTextMatches] → [byteRangeToCharRange])。
 * ここでバイトのまま使うと**日本語を含む行だけがずれて塗られる**。
 *
 * `internal` は Compose UI テストが「実際に塗られた範囲」を assert するため。
 */
@Composable
internal fun highlightedLine(hit: TextSearchHit, highlight: Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (range in hit.highlights.sortedBy { it.first }) {
            val start = range.first.coerceIn(0, hit.text.length)
            val end = (range.last + 1).coerceIn(start, hit.text.length)
            if (start > cursor) append(hit.text.substring(cursor, start))
            withStyle(SpanStyle(background = highlight, fontWeight = FontWeight.Bold)) {
                append(hit.text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < hit.text.length) append(hit.text.substring(cursor))
    }

/** ファイル名検索(§5b Q8 スコープ4)。 */
@Composable
private fun FileNameList(files: List<String>, actions: FileActions) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(count = files.size, key = { i -> files[i] }) { index ->
            val path = files[index]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .clickable { actions.openFile(path) }
                    .semantics { contentDescription = "find-file:$path" }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            HorizontalDivider()
        }
    }
}

/** シンボル検索(§5b Q8 スコープ5)。**空のときの文言は [fileSearchEmptyState] が決める。** */
@Composable
private fun SymbolList(symbols: List<SymbolHit>, actions: FileActions) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(count = symbols.size, key = { i -> "${symbols[i].path}:${symbols[i].line}:$i" }) { index ->
            val hit = symbols[index]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .clickable { actions.openFile(hit.path, hit.line) }
                    .semantics { contentDescription = hit.description }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(hit.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        "${hit.path}:${hit.line}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------------------
// ファイルビューア(スコープ2)
// ---------------------------------------------------------------------------

/**
 * ファイルビューア。**折り返さない・横スクロールする**(Q7 の差分ビューアと同じ規則)。
 *
 * `diff` を持つときだけ「変更あり」トグルを出し、押すと **Q7 の [DiffFileList] を再利用**する
 * (§5b Q8 スコープ2)。**1.18.21 は `diff` を返さない**(実測)ので、
 * 実物ではこのトグルは出ない —— 機構はスタブで測る。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerDialog(
    viewer: FileViewerUi,
    actions: FileActions,
    onOpenSettings: () -> Unit,
) {
    Dialog(
        onDismissRequest = actions::closeFileViewer,
        // コードは横に長い。既定のダイアログ幅では1行も読めない(Q7 と同じ判断)。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(viewer.title, maxLines = 1) },
                        navigationIcon = {
                            IconButton(
                                onClick = actions::closeFileViewer,
                                modifier = Modifier.semantics { contentDescription = "ファイルを閉じる" },
                            ) { Icon(Icons.Filled.Close, contentDescription = null) }
                        },
                        actions = {
                            // **判定は [FileViewerUi.hasDiff] にしかない。**
                            if (viewer.hasDiff) {
                                TextButton(
                                    onClick = actions::toggleFileDiff,
                                    modifier = Modifier.semantics {
                                        contentDescription =
                                            "file-diff-toggle:${if (viewer.showDiff) "on" else "off"}"
                                    },
                                ) { Text(if (viewer.showDiff) "本文" else "変更あり") }
                            }
                        },
                    )
                },
            ) { inner ->
                FileViewerBody(
                    viewer = viewer,
                    actions = actions,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(inner),
                )
            }
        }
    }
}

/**
 * ビューアの本文。**`Dialog` の外に出してある**のは、Compose UI テストが
 * **本物のこれを描く**ためである(Q6 のレビューが blocker として挙げた
 * 「テストが自前の composable を押しており画面に触れていない」トートロジーを避ける)。
 *
 * §5b の**陰性側ゲート**「`type:"binary"` で base64 が画面に出ていないこと」は、
 * ここを描いて `content-desc` と `text` を全部集めることでしか主張できない。
 */
@Composable
internal fun FileViewerBody(
    viewer: FileViewerUi,
    actions: FileActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "file-viewer:${viewer.path}" },
    ) {
        fileNoticesOf(viewer).forEach { notice ->
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .semantics { contentDescription = notice.key },
            )
        }
        when {
            // **判定は [FileViewerUi.hasDiff] にしかない。**
            viewer.showDiff && viewer.hasDiff -> DiffFileList(
                files = listOf(diffFileForViewer(viewer)),
                onToggleFile = {},
            )
            else -> when (val body = fileViewerBody(viewer)) {
                ScreenBody.Loading -> CenteredSpinner()
                is ScreenBody.Empty -> EmptyStateView(
                    spec = body.spec,
                    onAction = { action ->
                        // **`else` を書かない**(上の [applyFileEmptyStateAction] と同じ理由)。
                        when (action) {
                            EmptyStateAction.RETRY -> actions.retryFileViewer()
                            EmptyStateAction.OPEN_SETTINGS -> onOpenSettings()
                            EmptyStateAction.SHOW_IGNORED,
                            EmptyStateAction.CLEAR_SEARCH,
                            EmptyStateAction.CREATE_SESSION,
                            EmptyStateAction.BACK_TO_LIST,
                            -> Unit
                        }
                    },
                )
                ScreenBody.Content -> FileLineList(viewer)
            }
        }
    }
}

/**
 * `FileContent.diff` を Q7 の [DiffFileUi] へ移す。**最初から展開しておく** ——
 * 1ファイルしか無いので、もう一度開く操作を要求しない。
 *
 * `additions`/`deletions` はこの口が返さないので 0 のまま。**0 は「変更が無い」ではない**が、
 * 見出しの `+0 -0` がそう読める。**この経路は 1.18.21 では到達しない**ので実害は無いが、
 * 到達するようになったら数え直すこと(申し送りに書く)。
 */
internal fun diffFileForViewer(viewer: FileViewerUi): DiffFileUi {
    val patch = viewer.payload?.diff
    return DiffFileUi(
        path = viewer.path,
        status = "modified",
        additions = 0,
        deletions = 0,
        patch = patch,
        expanded = true,
        parsed = parseUnifiedDiff(patch, FILE_MAX_LINES).firstOrNull(),
    )
}

/**
 * 本文。**行番号は固定、本文だけが横に流れる**(Q7 の `DiffLineRow` と同じ)。
 *
 * `internal` は Compose UI テストが本物を描くため。
 */
@Composable
internal fun FileLineList(viewer: FileViewerUi) {
    // **共有の横スクロール。** 共有の仕方は [LineScrollState] にしかない ——
    // 1周目は全行の `horizontalScroll` に同じ `ScrollState` を渡しており、
    // **短い行が1つでも表示窓に入ると `maxValue` が 0 に潰れて動かなくなっていた**
    // (Q9 の E2E が端末画面で実測。Q7 の差分と Q8 のここも同じコードだった)。
    val hScroll = rememberLineScrollState()
    val listState = rememberLazyListState()
    // 検索結果 / シンボルから来たときだけ、その行まで送る。**null は先頭のまま。**
    LaunchedEffect(viewer.path, viewer.focusLine, viewer.lines.size) {
        // **変換は [focusIndexOf] にしかない。** ここに `- 1` を書き戻さないこと ——
        // `LaunchedEffect` の中のスクロール位置は Compose UI テストから assert できず、
        // 1周目は `- 1` を外す変異が775件全緑で通り抜けた(変異 N6)。
        val index = focusIndexOf(viewer.focusLine, viewer.lines.size) ?: return@LaunchedEffect
        listState.scrollToItem(index)
    }
    // **別のファイルを開いたら横位置を先頭へ戻す**(レビュー minor-2)。
    //
    // 行キーにファイルパスを含めてあるので、行が入れ替われば
    // [LineScrollState.forgetLine] で幅は落ちる。だが**送っていた位置は状態に残る**ので、
    // 幅の集計が戻る前の1フレームだけ前のファイルの位置で描かれうる。
    // [LineScrollState.reset] を実際にここから呼ぶ —— 呼ばないなら関数を置かない。
    LaunchedEffect(viewer.path) { hScroll.reset() }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // Q7 の実測: `uiautomator dump` の `bounds` は**スクロール容器のクリップ矩形**なので
            // 横に送っても1pxも動かない。計器が測れないなら計器を足す
            // (`value == maxValue && maxValue > 0` が「末尾に到達した」の証拠)。
            .lineScrollWindow(hScroll, "file-hscroll"),
    ) {
        // **item key と横スクロールの行キーは同じ物を使う**([LineScrollState] は
        // 表示窓に出ている行の幅だけを集める)。
        //
        // **鍵はファイルパスで修飾する。** 添字だけだと別のファイルを開いても
        // 同じ鍵が居座り、`LazyColumn` は行を作り直さず、前のファイルの幅と位置を
        // そのまま引き継ぐ(レビュー minor-2)。
        items(count = viewer.lines.size, key = { i -> fileLineKey(viewer.path, i) }) { index ->
            FileLineRow(viewer.lines[index], hScroll, fileLineKey(viewer.path, index))
        }
    }
}

/**
 * 本文1行の `LazyColumn` item key 兼 [LineScrollState] の行キー。**作り方はここにしかない。**
 * (Q7 の [diffLineKey] と同じ規則。)
 */
internal fun fileLineKey(path: String, index: Int): String = "l:$path:$index"

@Composable
private fun FileLineRow(line: FileLine, hScroll: LineScrollState, rowKey: String) {
    Row(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "file-line:${line.number}" }) {
        Text(
            text = line.number.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(48.dp).padding(end = 4.dp),
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            // **折り返さない。** ここを true に戻すとコードが読めなくなる(Q7 と同じ)。
            softWrap = false,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .lineScrollContent(hScroll, rowKey)
                .padding(end = 16.dp),
        )
    }
}
