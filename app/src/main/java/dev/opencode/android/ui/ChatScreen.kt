package dev.opencode.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * P3: チャット画面。履歴表示(GET /session/{id}/message)、テキスト送信(prompt_async)、
 * SSEによる逐次描画(/event)。状態は [ChatController] が保持するため回転で消えない。
 *
 * Q2 で足したもの: Markdown描画 / ツール活動カード / reasoning折り畳み / 長押しコピー /
 * 末尾追従と「↓ 最新へ」/ **バナーの一本化**(接続状態・配布失敗・session.error・retry)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    sessionId: String,
    title: String?,
    onBack: () -> Unit,
    /**
     * Q6: 401 で行き止まりにしないための導線。チャットからも設定へ抜けられる ——
     * 以前は「再試行」しか無く、認証が通らない状態では何度押しても直らなかった。
     */
    onOpenSettings: () -> Unit,
) {
    val ui by viewModel.chat.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    // Q7: ブランチ・作業ツリー・差分ビューア。**1つの状態機械**([DiffController])。
    val diffUi by viewModel.diff.collectAsStateWithLifecycle()
    // 触覚(Q5)。**無条件に perform を呼ぶ** —— トグルを見るのは HapticGate 側だけである
    // (Haptics.kt: 条件を呼び出し側に散らさない)。
    val haptics = LocalHapticGate.current

    // Q4: モデル切替シートの開閉。**回転で閉じてよい**(選択中の状態を持たないので、
    // 開き直せば同じところに戻る)。持つと Saver が要る割に得るものが無い。
    var modelSheetOpen by remember { mutableStateOf(false) }

    // 入室時に履歴取得+SSE購読を開始する。
    // 購読の停止はBackHandler(画面退出時)のみに行う。DisposableEffectのonDisposeは
    // 回転などActivity再生成でも呼ばれるため、そこで停止するとVM状態が巻き戻り
    // 「回転でメッセージが消える」バグになる(HARNESS ゲート③)。VMは回転をまたいで生存し、
    // 再合成時のopenChatは同一セッションなら早期リターンするので、ここでは再購入しない。
    LaunchedEffect(sessionId, title) { viewModel.openChat(sessionId, title) }
    // Q7 スコープ4: ブランチは**まだ取れていなければ引く**(判定は Controller 側)。
    LaunchedEffect(Unit) { viewModel.ensureBranchLoaded() }
    BackHandler(onBack = {
        viewModel.closeChat()
        onBack()
    })

    // 承認ダイアログ状態を画面回転・プロセス死で保持する(rememberSaveable)。
    // VMの permissionDialog はプロセス死で消えるため、UI側で状態を保持し、
    // VMからのイベント(permission.asked/replied)で同期する。
    // 単一の信頼源: VMの permissionDialog が権威。UIの dialogState はそれをミラーリングし、
    // rememberSaveable で回転/プロセス死をまたいで保持する。
    var dialogState by rememberSaveable(stateSaver = PermissionDialogStateSaver) { mutableStateOf<PermissionDialogState?>(null) }

    LaunchedEffect(ui.permissionDialog) {
        if (ui.permissionDialog != null) {
            dialogState = ui.permissionDialog
        }
    }
    LaunchedEffect(key1 = ui.permissionDialog, key2 = dialogState) {
        if (dialogState != null && ui.permissionDialog == null) {
            dialogState = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // 権威は ChatController が持つ。一覧から消えても(session.deleted)
                // タイトルが生のIDへ退化しない(申し送り Q1-1)。
                title = {
                    // Q7 スコープ4: ブランチは**タイトルの下**に置く。actions 側へ足すと、
                    // Q6 が決着させた「abort とモデルピルが状態遷移をまたいで
                    // 同じピクセルを共有していた」幾何をもう一度動かすことになる。
                    Column {
                        Text(ui.title ?: title ?: sessionId, maxLines = 1)
                        BranchLabel(branchChip(diffUi.branch))
                    }
                },
                navigationIcon = {
                    // レビュー指摘(major): 全退室経路でSSE購読を停止する。
                    // トップバー戻るはBackHandlerを通らないため、ここでもcloseChat()を呼ぶ。
                    // Q6: content-desc は押せるノード側(送信ボタンと同じ規則)。
                    IconButton(
                        onClick = {
                            viewModel.closeChat()
                            onBack()
                        },
                        modifier = Modifier.semantics { contentDescription = "戻る" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    ChatTopBarActions(
                        pill = selectModelPill(ui),
                        abort = selectAbortAction(ui),
                        onOpenModelSheet = { modelSheetOpen = true },
                        onAbort = viewModel::abortSession,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            if (ui.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // **本文とカード群で高さを分け合う領域**(E2E 所見 F1/F3/F2)。
            //
            // 直す欠陥: カード群がこの Column の**非weighted子**として素置きされていたため、
            // カードが積むほど本文の weight(1f) が食い潰され、実機では
            // **本文が1行も残らず、送信ボタンが 12×12px** になった。キーボードを出すと
            // 送信ボタンは**ツリーから消えた**(F3)。上限は「決着済みは2枚まで」という
            // **枚数**の側にしか無く、**縦方向**の側に無かったのが原因である。
            //
            // 対策: 帯と入力欄をこの領域の**外**に置いたまま、本文とカード群だけを
            // BoxWithConstraints の中で分ける。Column は非weighted子(帯・入力欄)を先に
            // 測るので、**入力欄はどれだけカードが積んでも縮まない**。
            // カード群はこの領域の [CHAT_CARD_AREA_MAX_FRACTION] を上限に切り、
            // 溢れた分はカード群の内側でスクロールする(F2: 3枚目に到達できる)。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val cardAreaMax = maxHeight * CHAT_CARD_AREA_MAX_FRACTION
                Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // 空状態/エラー状態の**選択も、その材料の取り出しも画面に書かない**
                // (Q6 スコープ2 + レビュー blocker)。**状態オブジェクトごと渡す**。
                // Q5 まで、履歴が0件のときこの領域は**真っ白**だった ——
                // 「まだ何も送っていない」と「黙って失敗した」が区別できない形で、
                // R1(空バブルを描かない)と同じ欠陥が**画面全体**の側に残っていた。
                // レビューが `messageCount = 1` 固定の変異を打つと、その白画面が
                // **496件全緑のまま**戻った。引数を書き換える余地を消す。
                when (val body = chatBody(ui)) {
                    ScreenBody.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is ScreenBody.Empty -> {
                        EmptyStateView(
                            spec = body.spec,
                            modifier = Modifier.align(Alignment.Center),
                            onAction = { action ->
                                when (action) {
                                    EmptyStateAction.RETRY -> viewModel.retryLoadMessages()
                                    EmptyStateAction.OPEN_SETTINGS -> onOpenSettings()
                                    EmptyStateAction.BACK_TO_LIST -> {
                                        viewModel.closeChat()
                                        onBack()
                                    }
                                    EmptyStateAction.CREATE_SESSION,
                                    EmptyStateAction.CLEAR_SEARCH,
                                    // Q8 で足した導線。チャットの空状態は ignored を持たない。
                                    EmptyStateAction.SHOW_IGNORED,
                                    -> Unit
                                }
                            },
                        )
                    }
                    ScreenBody.Content -> {
                        // **宛先はラムダではなく実体で渡す**(RUN_PLAN 設計規則1)。
                        // 1周目は `onOpenDiff = { … }` / `onRequestRevert = { … }` という
                        // ラムダ引数で、レビューの変異(中身を空にする)が610件全緑で通った。
                        MessageList(
                            messages = ui.messages,
                            busy = ui.busy,
                            modifier = Modifier.fillMaxSize(),
                            sessionId = sessionId,
                            actions = viewModel,
                        )
                    }
                }
            }

            // Q3: タスクリスト / 質問カード(§5 Q3 スコープ1・2)。**インラインカードであって
            // ダイアログではない。** どれを出すかは selectChatInlineCards が決める ——
            // ここに `if (ui.todos.isNotEmpty())` の類を書かないこと(条件をテストの外へ出さない)。
            //
            // **高さの上限はここで与える。** カード側で個々に切っても、枚数が増えれば
            // 群として縦を食い尽くす(1周目でカード内部にスクロールを入れたが、
            // 今度はカード**群**に同じ手が要る、というのが F1/F3 の指摘だった)。
                    ChatInlineCards(
                        ui = ui,
                        modifier = Modifier.heightIn(max = cardAreaMax),
                        onToggleOption = viewModel::toggleQuestionOption,
                        onCustomText = viewModel::updateQuestionCustomText,
                        onSubmit = { requestId ->
                            haptics.perform(HapticEvent.QUESTION_REPLY)
                            viewModel.submitQuestion(requestId)
                        },
                        onReject = { requestId ->
                            haptics.perform(HapticEvent.QUESTION_REPLY)
                            viewModel.rejectQuestion(requestId)
                        },
                    )
                }
            }

            // **帯は1本**(§5 Q2 スコープ6)。どれを出すかは selectChatBanner が決める。
            ChatBannerArea(
                ui = ui,
                // **宛先は実体**。`onUnrevert = { }` にする変異(レビュー RD)が
                // 610件全緑で通った経路なので、ラムダ引数を廃止した。
                actions = viewModel,
                onBackToList = {
                    viewModel.closeChat()
                    onBack()
                },
                // Q4 スコープ5: session.error から**モデル切替へ抜ける**(R3)。
                // 帯は増えていない —— 1本の帯が持つ導線が1つ増えただけである。
                onChangeModel = { modelSheetOpen = true },
            )

            InputRow(
                draft = ui.draft,
                enabled = canSend(ui),
                busy = ui.busy,
                sessionDeleted = ui.sessionDeleted,
                onDraftChange = viewModel::updateDraft,
                onSend = viewModel::sendDraft,
            )
        }
    }

    // Q7 スコープ6: 巻き戻しの確認。**状態は ChatController が持つ**ので、
    // 「確認を通らずに revert が送られないこと」を `runTest` から assert できる。
    ui.revertConfirm?.let { confirm ->
        RevertConfirmDialog(
            state = confirm,
            onConfirm = viewModel::confirmRevert,
            onDismiss = viewModel::cancelRevert,
        )
    }

    // Q7 スコープ1・2・3: 差分ビューア。
    if (diffUi.viewer.open) {
        DiffViewerDialog(
            viewer = diffUi.viewer,
            onClose = viewModel::closeDiffViewer,
            onToggleFile = viewModel::toggleDiffFile,
            onRetry = viewModel::retryDiffViewer,
            onOpenSettings = onOpenSettings,
        )
    }

    // 承認ダイアログ(モーダル) - ローカル状態で制御
    // dialogState は permission.replied 受信時に VM 側でクリアされるまで保持する
    val currentDialogState = dialogState
    if (currentDialogState != null) {
        PermissionDialog(
            state = currentDialogState,
            onAllowOnce = {
                haptics.perform(HapticEvent.PERMISSION_REPLY)
                viewModel.replyPermission(currentDialogState.permissionId, currentDialogState.sessionId, "once")
            },
            onAllowAlways = {
                haptics.perform(HapticEvent.PERMISSION_REPLY)
                viewModel.replyPermission(currentDialogState.permissionId, currentDialogState.sessionId, "always")
            },
            onReject = {
                haptics.perform(HapticEvent.PERMISSION_REPLY)
                viewModel.replyPermission(currentDialogState.permissionId, currentDialogState.sessionId, "reject")
            },
        )
    }

    // Q4: モデル切替シート。**選んだら即 POST /api/session/{id}/model**
    // (実測 204。切替は session.updated を流さないので Controller が引き直す)。
    if (modelSheetOpen) {
        ModelPickerSheet(
            state = catalog,
            currentModel = ui.sessionModel,
            title = "このセッションのモデル",
            onQueryChange = viewModel::updateCatalogQuery,
            onReload = viewModel::ensureCatalogLoaded,
            onRetry = viewModel::reloadCatalog,
            onOpenSettings = onOpenSettings,
            onPick = { choice ->
                viewModel.switchChatModel(choice.toRef())
                modelSheetOpen = false
                viewModel.clearCatalogQuery()
            },
            onDismiss = {
                modelSheetOpen = false
                viewModel.clearCatalogQuery()
            },
        )
    }
}

/**
 * 中断ボタンのために**常に予約しておく幅**(Q6 レビュー major-1)。
 *
 * ## 直す欠陥
 *
 * レビューが幾何を確定させた。**同じピクセルが、状態遷移をまたいで別のコントロールになる**:
 *
 * ```
 * busy : モデルピル [671,158][909,290]   中断 [909,158][1069,290]
 * idle : モデルピル [831,158][1069,290]  (中断は存在しない)
 * ```
 *
 * x=989 は **busy 時は中断の内側、idle 時はモデルピルの内側**。同一セッションで実証されている
 * (busy 中のタップで `abortCount` 0→1、idle 後の同じ座標で `providerCalls` 0→1)。
 *
 * 実際に踏む筋書き: **実行が終わりかけたところで「中断」を押す → ボタンが消え、
 * ピルが指の下へ広がり、モデル選択シートが開く。** 結果がまったく違う2つのコントロールが
 * 同じ座標を共有している。
 *
 * 対策は**枠を予約する**こと。中断が出ていない間もこの幅の空き箱を置くので、
 * ピルは一切動かない。「ピルの bounds が busy と idle で同一であること」は
 * Compose テストが assert している(実機の dump でも同じことを測る)。
 *
 * 幅は「中断中…」(最長の文言)が収まる値。[selectAbortAction] が返す文言を変えるなら
 * ここも見直すこと —— テストが両方の文言で幅を測っている。
 */
val ABORT_SLOT_WIDTH = 104.dp

/**
 * チャット TopAppBar の右側(モデルピル + 中断)。
 *
 * **条件を1つも持たない。** 何を出すかは [selectModelPill] と [selectAbortAction] が決め、
 * この関数は「null なら描かない / 枠は常に置く」だけを実行する。
 *
 * 触覚は**このボタンが自分で鳴らす**(Q6 レビュー blocker)。1周目は
 * `haptics.perform(HapticEvent.ABORT)` が `ChatScreen` の `actions` ラムダの中にあり、
 * **その行を消す変異を検出できるテストが1本も無かった** —— テストは自前の composable を
 * 組んで検査しており、`ChatScreen` に一度も触れていなかった(トートロジー)。
 * 触覚を**コントロールの持ち物**にすれば、コントロールを描くテストが検出器になる。
 */
@Composable
internal fun ChatTopBarActions(
    pill: ModelPill?,
    abort: AbortAction?,
    onOpenModelSheet: () -> Unit,
    onAbort: () -> Unit,
) {
    val haptics = LocalHapticGate.current
    if (pill != null) {
        TextButton(
            onClick = onOpenModelSheet,
            enabled = pill.enabled,
            modifier = Modifier.semantics { contentDescription = pill.description },
        ) {
            Text(pill.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
    // **枠は常に置く。** ここに `if (abort != null)` を掛けると major-1 が戻る。
    Box(
        modifier = Modifier.width(ABORT_SLOT_WIDTH),
        contentAlignment = Alignment.Center,
    ) {
        if (abort != null) {
            TextButton(
                onClick = {
                    haptics.perform(HapticEvent.ABORT)
                    onAbort()
                },
                enabled = abort.enabled,
                // **content-desc は押せるノード側**。1周目は中断ボタンに desc が
                // どこにも無く、**アプリで最も安全に関わるコントロールが dump から
                // 同定できなかった**(レビュー major-2)。
                modifier = Modifier.semantics { contentDescription = abort.description },
            ) {
                Text(abort.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/**
 * 帯1本ぶんの領域。
 *
 * retry のカウントダウンのために**1秒ごとに現在時刻を進める**が、進めるのは
 * retry が出ているときだけ。出ていないのに毎秒再合成すると、
 * ストリーミング中の描画と競合する。
 */
@Composable
internal fun ChatBannerArea(
    ui: ChatUi,
    /**
     * 帯の導線の宛先。**ラムダではなく実体**([ChatActions] の doc に理由)。
     * `onBackToList` / `onChangeModel` は画面遷移とシート開閉なのでここには入れない
     * —— それは ViewModel の関心ではない。
     */
    actions: ChatActions,
    onBackToList: () -> Unit,
    onChangeModel: () -> Unit,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.retry) {
        while (ui.retry != null) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }
    val banner = selectChatBanner(ui, nowMs) ?: return
    val container = when (banner.tone) {
        ChatBannerTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        ChatBannerTone.WARNING -> MaterialTheme.colorScheme.surfaceContainerHighest
        ChatBannerTone.INFO -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            // judge はスクショを見られない。どの帯が出ているかを dump から引用できるようにする。
            .semantics { contentDescription = "chat-banner:${banner.kind.name}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                banner.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )
            banner.actionLabel?.let { label ->
                TextButton(onClick = {
                    when (banner.kind) {
                        ChatBannerKind.SESSION_DELETED -> onBackToList()
                        ChatBannerKind.SESSION_ERROR -> actions.retryLoadMessages()
                        // Q7: 「元に戻す」。**この帯が unrevert への唯一の入口**である。
                        ChatBannerKind.REVERTED -> actions.unrevert()
                        else -> Unit
                    }
                }) { Text(label) }
            }
            // Q4: 「モデルを変更」。**出すかどうかは selectChatBanner が決める**
            // (modelActionLabel が null なら出ない)。ここに条件を書かない。
            banner.modelActionLabel?.let { label ->
                ChangeModelButton(label = label, onClick = onChangeModel)
            }
            if (banner.dismissible) {
                TextButton(onClick = { actions.dismissChatBanner(banner.kind) }) { Text("閉じる") }
            }
        }
    }
}

/**
 * メッセージ列。reverseLayoutで「最新が下・追記中も下端に張り付く」挙動を自前スクロール制御なしで得る。
 * **添字0が最新**。追従の判定は [isFollowingBottom] に出してある(向きを間違えても画面は正常に見える)。
 */
@Composable
internal fun MessageList(
    messages: List<ChatMessage>,
    busy: Boolean,
    modifier: Modifier = Modifier,
    /** チップと巻き戻しがどのセッションに属するか。**引数で渡し、状態から推測しない。** */
    sessionId: String,
    /**
     * Q7: 「N ファイル変更」チップと長押し「ここまで戻す」の宛先。
     * **ラムダではなく実体**([ChatActions] の doc に理由)。
     */
    actions: ChatActions,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val following by remember {
        derivedStateOf {
            isFollowingBottom(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
    // 追従中に新しいメッセージが増えたら末尾へ寄せ直す。
    // reverseLayout は添字0に貼り付くが、数px ずれたところで止まっていることがある。
    LaunchedEffect(messages.size, following) {
        if (following && messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Box(modifier = modifier) {
        LazyColumn(modifier = Modifier.fillMaxSize(), state = listState, reverseLayout = true) {
            // key は付けない。履歴の `info.id` が重複する形(スタブ再起動での ID 再利用など)を
            // 一度でも踏むと LazyColumn は例外を投げる。**表示を良くする変更でアプリを落とさない。**
            itemsIndexed(messages.asReversed()) { reversedIndex, message ->
                // R1: 空バブルを描かない。待ち(busy中の末尾)と「応答なし」を区別する。
                val kind = bubbleKindOf(
                    message = message,
                    // asReversed()なので index 0 が元リストの末尾
                    isLast = reversedIndex == 0,
                    busy = busy,
                )
                if (kind != BubbleKind.HIDDEN) {
                    MessageBubble(
                        message = message,
                        kind = kind,
                        sessionId = sessionId,
                        actions = actions,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = shouldShowJumpToLatest(following, messages.isNotEmpty()),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            AssistChip(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                label = { Text("↓ 最新へ") },
                modifier = Modifier.semantics { contentDescription = "jump-to-latest" },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    kind: BubbleKind,
    sessionId: String,
    actions: ChatActions,
) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
    // Q6 の規則(触覚は**コントロール自身の持ち物**にする)。呼び出し側から渡すと、
    // テストが自前の composable を押すトートロジーになる。
    val haptics = LocalHapticGate.current
    var menuOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    val copyText = remember(message) { messageCopyText(message) }
    // Q4: 「詳細」に出す行。**組み立ては純関数**([messageDetailLines])なので、
    // 空かどうかの判定も1か所に閉じる。空なら項目自体を出さない。
    val detailLines = remember(message) { messageDetailLines(message.meta) }
    // Q7: 「N ファイル変更」。**判定は純関数**([messageDiffChip])なので、
    // ここに `if (files.isNotEmpty())` を書かない。
    val diffChip = remember(message) { messageDiffChip(message) }
    val revertable = remember(message) { canRevertMessage(message) }

    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart),
        ) {
            Text(
                text = message.role ?: "unknown",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                Column(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        // 長押し→コピー(§5 Q2 スコープ4)/ 詳細(§5 Q4 スコープ4)。
                        // **メタだけがあるバブルでも開けること** —— 402 で本文が1文字も
                        // 来なかった往復こそ「どのモデルが落ちたか」を持っている。
                        .combinedClickable(
                            enabled = copyText.isNotBlank() || detailLines.isNotEmpty() || revertable,
                            onClick = {},
                            onLongClick = { menuOpen = true },
                        )
                        .padding(10.dp),
                ) {
                    BubbleContent(message = message, kind = kind, isUser = isUser, actions = actions)
                    // Q7 スコープ3: 変更があるときだけ出る導線。**押されるまで通信しない**
                    // (件数は `PatchPart.files` がメッセージ自身に持っている)。
                    diffChip?.let { chip ->
                        AssistChip(
                            onClick = {
                                actions.openMessageDiff(
                                    sessionId = sessionId,
                                    messageId = message.messageId,
                                    knownFiles = changedFilesOf(message),
                                )
                            },
                            label = { Text(chip.label) },
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .semantics { contentDescription = chip.description },
                        )
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (copyText.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("コピー") },
                            onClick = {
                                clipboard.setText(AnnotatedString(copyText))
                                menuOpen = false
                            },
                        )
                    }
                    // Q4 スコープ4: メタは**常時は出さない**。長押しの「詳細」でだけ出す。
                    if (detailLines.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("詳細") },
                            onClick = {
                                detailsOpen = true
                                menuOpen = false
                            },
                            modifier = Modifier.semantics { contentDescription = "message-details-item" },
                        )
                    }
                    // Q7 スコープ6: 「ここまで戻す」。**ここでは確認を出すだけ**で
                    // `POST /revert` は送らない。サーバーが `^msg` を要求するので、
                    // ローカルエコーには出さない([canRevertMessage])。
                    if (revertable) {
                        DropdownMenuItem(
                            text = { Text("ここまで戻す", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                haptics.perform(HapticEvent.PERMISSION_REPLY)
                                actions.requestRevert(
                                    messageId = message.messageId,
                                    partId = null,
                                    preview = revertPreviewText(message),
                                )
                                menuOpen = false
                            },
                            modifier = Modifier.semantics { contentDescription = "message-revert-item" },
                        )
                    }
                }
            }
        }
    }

    if (detailsOpen) {
        MessageDetailsDialog(lines = detailLines, onDismiss = { detailsOpen = false })
    }
}

/**
 * メッセージの詳細(providerID / modelID / cost / tokens)。
 * 行の組み立ては [messageDetailLines] が持つので、この関数は並べるだけ。
 */
@Composable
private fun MessageDetailsDialog(lines: List<Pair<String, String>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text("メッセージの詳細") },
        text = {
            Column(modifier = Modifier.semantics { contentDescription = "message-details" }) {
                lines.forEach { (label, value) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 110.dp),
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { contentDescription = "detail:$label=$value" },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun BubbleContent(
    message: ChatMessage,
    kind: BubbleKind,
    isUser: Boolean,
    /** Q8 スコープ6: ツールカードのパスから開く宛先。**ラムダではなく実体**([ChatActions] の doc)。 */
    actions: ChatActions,
) {
    // 実物 serve は step-start / step-finish / patch / snapshot といった part も送る
    // (実測 2026-08-25、P5)。ライフサイクルの目印は描かない——本文ではないので、
    // 出せば一語の答えの周りに三行の雑音が並ぶ。
    // 描くかどうかの判定は ChatModels.isRenderable() と同じ順序で分岐する。
    when (kind) {
        BubbleKind.WAITING -> Text(
            "応答を待っています…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "streaming-placeholder" },
        )
        BubbleKind.NO_RESPONSE -> Text(
            "(応答なし)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BubbleKind.HIDDEN -> Unit
        // キーに **メッセージIDと添字** を混ぜる(レビュー minor-6)。
        // part.id は spec 上 required だが、実物は id を持たない合成 part も送ってくる
        // (ChatModels の `partId = null`)。null が2つ同じメッセージに入ると
        // `rememberSaveable` のキーが衝突し、片方を開くともう片方も開く。
        BubbleKind.CONTENT -> message.parts.forEachIndexed { index, part ->
            val slotKey = "${message.messageId}#$index"
            when {
                !part.isRenderable() -> Unit
                part.type == "tool" -> ToolActivityCard(part, slotKey, actions)
                part.type == "reasoning" -> ReasoningBlock(part, slotKey)
                part.text.isNotBlank() ->
                    // ユーザーの発言は**素のまま**出す。自分が打った文字が勝手に
                    // 見出しや箇条書きに化けると、何を送ったのか分からなくなる。
                    if (isUser) {
                        Text(part.text, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        MarkdownText(part.text)
                    }
                else -> Text(
                    "(${part.type})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * ツール活動の1行カード(§5 Q2 スコープ2)。
 * 「ツール名 + 状態アイコン + 折り畳み」。展開で `state.input` の要約などを出す。
 *
 * `internal` にしてあるのは Compose UI テスト([dev.opencode.android.Q8WiringTest])が
 * **本物のこのカードを描いてファイルパスを押す**ため(§5b Q8 スコープ6)。
 * 自前の composable を押すとトートロジーになる(Q6 レビューの blocker)。
 */
@Composable
internal fun ToolActivityCard(part: ChatPart, slotKey: String, actions: ChatActions) {
    val tool = part.tool
    if (tool == null) {
        Text(
            "🔧 ${part.toolLabel ?: "tool"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var expanded by rememberSaveable(key = "tool-$slotKey") { mutableStateOf(false) }
    val details = listOfNotNull(
        tool.title?.let { "title: $it" },
        tool.input?.let { "input: $it" },
        tool.output?.let { "output: $it" },
        tool.error?.let { "error: $it" },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // Q6: 折り畳みは押せる要素なので 48dp を割らせない(TouchTargets.kt)。
            .heightIn(min = MIN_TOUCH_TARGET)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // 折り畳みは単純なタップ。ここに combinedClickable を置くと長押しを飲み込み、
            // バブルのコピー導線がカードの上だけ効かなくなる。
            .clickable(enabled = details.isNotEmpty()) { expanded = !expanded }
            .semantics {
                contentDescription =
                    "tool-card:${tool.name}:${tool.status.name.lowercase()}:${if (expanded) "expanded" else "collapsed"}"
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolStatusGlyph(tool.status)
            Spacer(Modifier.size(6.dp))
            Text(
                tool.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (details.isNotEmpty()) {
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Q8 スコープ6: **ツールが触ったファイルをその場で開く。**
        //
        // 折り畳みの外に出してあるのは、これが「詳細」ではなく**導線**だからである
        // (展開しないと出ないと、在ることに気付かれない)。
        // 出すかどうかの判定は [toolInputFilePath] にしかない —— ここに
        // `if (tool.input?.contains("path") == true)` の類を書き戻さないこと。
        val filePath = tool.filePath
        if (filePath != null) {
            Text(
                text = "📄 $filePath",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .padding(top = 4.dp)
                    // 素の `clickable` は Material の最小タッチターゲット強制を受けない。
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .clickable { actions.openFileFromChat(filePath) }
                    .semantics { contentDescription = "tool-file:$filePath" },
            )
        }
        if (expanded) {
            details.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** running=スピナー / completed=チェック / error=赤(§5 Q2 スコープ2の文言そのもの)。 */
@Composable
private fun ToolStatusGlyph(status: ToolRunStatus) {
    when (status) {
        ToolRunStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
        )
        // Q6: 完了の緑は**テーマから引く**。直書きの `#6FBF73` はライトの面で 2.9:1 しか無かった。
        ToolRunStatus.COMPLETED -> Text(
            "✓",
            color = LocalStatusColors.current.running,
            style = MaterialTheme.typography.labelMedium,
        )
        ToolRunStatus.ERROR -> Text("✕", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        ToolRunStatus.PENDING -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        ToolRunStatus.UNKNOWN -> Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

/** reasoning part の折り畳み(§5 Q2 スコープ3)。**既定は閉じる。** */
@Composable
private fun ReasoningBlock(part: ChatPart, slotKey: String) {
    var expanded by rememberSaveable(key = "reasoning-$slotKey") { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .heightIn(min = MIN_TOUCH_TARGET)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .semantics { contentDescription = "reasoning:${if (expanded) "expanded" else "collapsed"}" }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "💭 思考",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                part.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 入力欄+送信ボタン。**`internal` にしてあるのは Compose テストから直接叩くため**(Q6)。
 * Q3 の E2E が「送信ボタンが 12×12px」と引用した経路そのものなので、
 * 宣言サイズは実機を待たずに固定できる場所へ出す。
 *
 * **触覚はこのボタンが自分で鳴らす**(Q6 レビュー blocker)。1周目は
 * `haptics.perform(HapticEvent.SEND)` が `ChatScreen` 側のラムダにあり、
 * その行を消す変異を検出できるテストが**1本も無かった**。
 * 触覚をコントロールの持ち物にすれば、このコントロールを描くテストが検出器になる。
 */
@Composable
internal fun InputRow(
    draft: String,
    enabled: Boolean,
    busy: Boolean,
    sessionDeleted: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    when {
                        sessionDeleted -> "このセッションは削除されました"
                        busy -> "応答中…"
                        else -> "メッセージを入力"
                    },
                )
            },
            enabled = enabled,
            maxLines = 4,
        )
        Spacer(Modifier.padding(2.dp))
        // **content-desc は押せるノード(IconButton)側に置く。**
        // Icon に置くと dump に出る bounds はアイコンの 24dp であって、
        // タッチターゲットの 48dp ではない —— E2E が「送信ボタンが 12×12px」と
        // 引用したのもこのアイコンのノードだった。**測られる物と押される物を一致させる。**
        // `Q6TouchTargetTest` が **unmerged tree** で「desc の付いたノード自身が 48dp」を
        // assert している(merged tree では Icon 側に戻しても同じノードに解決してしまい、
        // 検出できなかった —— レビュー blocker M12)。
        val haptics = LocalHapticGate.current
        IconButton(
            onClick = {
                haptics.perform(HapticEvent.SEND)
                onSend()
            },
            enabled = enabled && draft.isNotBlank(),
            modifier = Modifier.semantics { contentDescription = "送信" },
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
        }
    }
}

/**
 * 承認ダイアログ。permission/metadata を表示し、3択ボタン(許可/常に許可/拒否)で応答する。
 * 画面回転で消えないよう ChatScreen 側で rememberSaveable で状態を保持する。
 * Material3 AlertDialog は2ボタン構成のため、3択対応のカスタムダイアログを実装する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionDialog(
    state: PermissionDialogState,
    onAllowOnce: () -> Unit,
    onAllowAlways: () -> Unit,
    onReject: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clip(RoundedCornerShape(28.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "ツール実行の許可",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            Column(modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 16.dp)) {
                Text("権限: ${state.permission}", style = MaterialTheme.typography.bodyMedium)
                state.metadata?.let { meta ->
                    Text("詳細: $meta", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.patterns.isNotEmpty()) {
                    Text("パターン: ${state.patterns.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.always.isNotEmpty()) {
                    Text("常に許可済み: ${state.always.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.tool?.let { tool ->
                    Text("ツール: $tool", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 拒否
                TextButton(onClick = onReject) {
                    Text("拒否", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.weight(1f))
                // 許可(今回のみ)
                Button(onClick = onAllowOnce) {
                    Text("許可", style = MaterialTheme.typography.labelLarge)
                }
                // 常に許可
                Button(onClick = onAllowAlways) {
                    Text("常に許可", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
