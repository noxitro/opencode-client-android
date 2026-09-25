package dev.opencode.android.ui

import dev.opencode.android.data.ApiError

/**
 * 空状態 / エラー状態の**文言と導線を1か所に集める**(QUALITY_PLAN §5 Q6 スコープ2)。
 *
 * ## なぜ集めるのか
 *
 * Q6 の開始時点で、同じ意味の状態が画面ごとに違う言葉と違う導線を持っていた:
 *
 * | 状態 | 一覧 | チャット | モデルシート |
 * |---|---|---|---|
 * | 取得失敗 | `error` そのまま + 「再試行」+「接続設定を開く」 | `error` そのまま + 「再試行」だけ | `error` + 「再試行」 |
 * | 0件 | 「セッションがありません。右下の + で作成してください。」 | **何も出ない**(空白) | 「選べるモデルがありません…」 |
 * | 検索0件 | 「「X」に一致するセッションはありません。」 | — | 「「X」に一致するモデルはありません」 |
 * | 401 | 一覧の上に帯(`session-auth-error`) | 帯(`UNAUTHORIZED`) | 区別なし |
 *
 * **「設定を開く」が出る画面と出ない画面がある**のが一番の食い違いだった ——
 * 401 は再試行では絶対に直らないので、設定への導線が無い画面では行き止まりになる。
 * P5 が「ユーザーができることが再試行しかない」で踏んだのと同じ形である(R3)。
 *
 * ## 設計
 *
 * **どの状態を出すかの判定はこのファイルにしかない。** 画面側に
 * `if (items.isEmpty() && search.isNotBlank())` の類を書き戻さないこと ——
 * 配線に条件を置くと、その条件を消す変異が全緑で通り抜ける(Q1〜Q5 で5度示された形)。
 *
 * [EmptyStateSpec.key] は `content-desc` に載せる。judge はスクショを見られないので、
 * **どの空状態が出ているかは属性でしか主張できない**(HARNESS「引用できる証拠」)。
 */

/** 空状態が提示する導線。**画面が意味を解釈して実際の関数へ結ぶ。** */
enum class EmptyStateAction {
    /** もう一度取得する。**認証失敗では出さない**(再試行では直らない)。 */
    RETRY,

    /** 設定画面を開く。接続失敗・認証失敗で必ず出す。 */
    OPEN_SETTINGS,

    /** 新規セッションを作る。 */
    CREATE_SESSION,

    /** 検索語を捨てて全件へ戻る。 */
    CLEAR_SEARCH,

    /** 一覧へ戻る。 */
    BACK_TO_LIST,

    /** Q8: `ignored` を隠している状態を解除する。 */
    SHOW_IGNORED,
}

/** 導線1つ。ラベルもここが持つ(画面ごとに違う言葉にしないため)。 */
data class EmptyStateActionSpec(val action: EmptyStateAction, val label: String)

/**
 * 空状態1つ。
 *
 * @param key `content-desc` に載せる識別子。**画面をまたいで同じ状態は同じ key** にする
 * @param title 1行目。**何が起きたか**を書く(何をすべきかは [body] と [actions])
 * @param body 2行目。null なら出さない
 * @param tone 深刻度。色にだけ使う(判定には使わない)
 */
data class EmptyStateSpec(
    val key: String,
    val title: String,
    val body: String? = null,
    val actions: List<EmptyStateActionSpec> = emptyList(),
    val tone: EmptyStateTone = EmptyStateTone.NEUTRAL,
)

enum class EmptyStateTone { NEUTRAL, ERROR }

// ---------------------------------------------------------------------------
// 共通の部品
// ---------------------------------------------------------------------------

/**
 * 認証失敗か。**再試行を出すかどうかの分岐はこの1つの述語**にしかない。
 *
 * 401/403 は「待てば直る」種類ではない。Q1 の裁定「401/403 は一時的な切断として扱わない」
 * (RUN_PLAN 決定3)が SSE について定めたことを、**画面の導線にも同じ形で適用する**。
 */
fun isAuthError(error: ApiError?): Boolean =
    error is ApiError.Http && (error.code == 401 || error.code == 403)

private val RETRY = EmptyStateActionSpec(EmptyStateAction.RETRY, "再試行")
private val OPEN_SETTINGS = EmptyStateActionSpec(EmptyStateAction.OPEN_SETTINGS, "設定を開く")
private val CREATE_SESSION = EmptyStateActionSpec(EmptyStateAction.CREATE_SESSION, "新規セッション")
private val CLEAR_SEARCH = EmptyStateActionSpec(EmptyStateAction.CLEAR_SEARCH, "検索を解除")
private val BACK_TO_LIST = EmptyStateActionSpec(EmptyStateAction.BACK_TO_LIST, "一覧へ戻る")

/**
 * 取得に失敗したときの共通の形。**認証失敗と、それ以外を分ける。**
 *
 * @param key `load-failed` の前に付く画面名(`sessions` / `messages` / `catalog`)
 * @param message サーバー/通信から得た1行。**これを捨てない** —— P5 の欠陥Gは
 *   「理由がサーバーログにしか無い」ことだった
 */
fun loadFailureState(key: String, message: String, authFailure: Boolean): EmptyStateSpec =
    if (authFailure) {
        EmptyStateSpec(
            key = "$key-unauthorized",
            title = "認証エラーで読み込めません",
            body = "$message\n再試行では解決しません。設定画面でパスワードを確認してください。",
            actions = listOf(OPEN_SETTINGS),
            tone = EmptyStateTone.ERROR,
        )
    } else {
        EmptyStateSpec(
            key = "$key-failed",
            title = "読み込めませんでした",
            body = message,
            actions = listOf(RETRY, OPEN_SETTINGS),
            tone = EmptyStateTone.ERROR,
        )
    }

// ---------------------------------------------------------------------------
// 画面ごとの選択
// ---------------------------------------------------------------------------

/**
 * セッション一覧の空状態。**null は「一覧そのものを描く」を意味する。**
 *
 * 順序に意味がある:
 *  1. 読み込み中で1件も無い → 空状態ではない(スピナー)。呼び出し側が先に見る
 *  2. 取得に失敗して1件も無い → 失敗状態(**部分的に持っているなら一覧を出す**。
 *     持っているものを消して失敗だけを出すと、直前まで見えていた情報が消える)
 *  3. 検索中で0件 → 検索の空
 *  4. 0件 → 初回の空
 */
fun sessionListEmptyState(
    itemCount: Int,
    error: String?,
    authFailure: Boolean,
    search: String,
): EmptyStateSpec? = when {
    itemCount > 0 -> null
    error != null -> loadFailureState("sessions", error, authFailure)
    search.isNotBlank() -> EmptyStateSpec(
        key = "sessions-search-empty",
        title = "「$search」に一致するセッションはありません",
        body = "別の語で探すか、検索を解除してください。",
        actions = listOf(CLEAR_SEARCH),
    )
    else -> EmptyStateSpec(
        key = "sessions-empty",
        title = "セッションがありません",
        body = "右下の + から最初のセッションを作成してください。",
        actions = listOf(CREATE_SESSION),
    )
}

/**
 * チャット画面の空状態。
 *
 * Q6 で足したのは **`messages-empty`** である —— Q5 までは履歴が0件のとき
 * **画面が真っ白**で、「まだ何も送っていない」と「読み込みに失敗して黙っている」の
 * 区別が付かなかった。R1(空バブルを描かない)と同じ理由の欠陥が、
 * バブルではなく**画面全体**の側に残っていた。
 *
 * `sessionDeleted` を先に見るのは、削除されたセッションで「送ってください」と
 * 言うのが嘘になるため。帯([selectChatBanner])も同時に出るが、帯は上に1本、
 * ここは本文領域なので競合しない。
 */
fun chatEmptyState(
    messageCount: Int,
    error: String?,
    authFailure: Boolean,
    sessionDeleted: Boolean,
): EmptyStateSpec? = when {
    messageCount > 0 -> null
    error != null -> loadFailureState("messages", error, authFailure)
    sessionDeleted -> EmptyStateSpec(
        key = "messages-session-deleted",
        title = "このセッションは削除されました",
        body = "サーバー上に履歴が残っていません。",
        actions = listOf(BACK_TO_LIST),
        tone = EmptyStateTone.ERROR,
    )
    else -> EmptyStateSpec(
        key = "messages-empty",
        title = "まだメッセージがありません",
        body = "下の入力欄から最初のプロンプトを送ってください。",
    )
}

/**
 * モデル選択シートの空状態。
 *
 * 「接続済みプロバイダが1件も無い」と「検索に当たらない」を区別するのは Q4 からの継続。
 * Q6 で変えたのは**失敗時に一覧と同じ形になる**ことで、以前はここだけ
 * 「再試行」しか無く、401 でも同じ画面を出していた。
 */
fun modelCatalogEmptyState(
    visibleCount: Int,
    totalCount: Int,
    error: String?,
    authFailure: Boolean,
    query: String,
): EmptyStateSpec? = when {
    visibleCount > 0 -> null
    error != null -> loadFailureState("catalog", error, authFailure)
    totalCount == 0 -> EmptyStateSpec(
        key = "catalog-empty",
        title = "選べるモデルがありません",
        body = "接続済みのプロバイダが見つかりませんでした。サーバー側の設定を確認してください。",
        actions = listOf(RETRY),
    )
    else -> EmptyStateSpec(
        key = "catalog-search-empty",
        title = "「$query」に一致するモデルはありません",
        body = "検索語を短くするか、消して一覧から選んでください。",
    )
}

/**
 * モデル一覧の脚注(Q6: 申し送り Q4-2)。
 *
 * Q4 は `capabilities.toolcall && output.text && input.text` で 600→464 に絞り、
 * 除外件数を画面に出した。**それでも足りない**ことを Q4 実装が自分で申告している ——
 * mistral の `default` である Voxtral は capabilities が揃っているのでこの条件では落ちず、
 * 「capabilities だけでは音声向けを言い当てられない」。
 * その限界は KDoc とテストには書かれていたが、**画面には一言も出ていなかった**。
 *
 * 除外が0件でも出す。**「絞り込んだから安全」と読ませないための一行**なので、
 * 除外件数に紐付けると 0 件のサーバーでだけ限界が消える。
 */
fun modelCatalogFootnotes(excludedModels: Int): List<CatalogFootnote> = buildList {
    if (excludedModels > 0) {
        // **キーは Q4 の E2E 証跡と同じ文字列**(`model-picker-excluded:136`)を保つ。
        // 証跡の連続性は、後から「同じものを測っているか」を確かめる唯一の手がかりである。
        add(
            CatalogFootnote(
                key = "excluded:$excludedModels",
                text = "ツール実行に対応しないモデル ${excludedModels}件は表示していません",
            ),
        )
    }
    add(
        CatalogFootnote(
            key = "capability-limit",
            text = "残りが会話向きとは限りません。音声・画像向けのモデルも capabilities 上は同じに見えます",
        ),
    )
}

/** モデル一覧の脚注1つ。[key] は `content-desc` に載る識別子。 */
data class CatalogFootnote(val key: String, val text: String)

/** モデルシートの帯1本。[key] は `content-desc` に載る識別子。 */
data class CatalogNotice(val key: String, val text: String)

/**
 * **一覧が出ているのに取得に失敗している**ときの帯(Q10/Q11 差し戻し回収・回収項目C)。
 *
 * 直す欠陥(E2E ゲートが実機で見つけた形): 一度カタログの取得に成功したあと
 * 2回目の取得が失敗すると、[ModelCatalogController] は `error` を立てるが
 * **`models` は前回のまま残す**。空状態([modelCatalogEmptyState])は
 * `visibleCount > 0` で `null` を返すので、**失敗は画面のどこにも出ない** ——
 * ユーザーには「取れている一覧」と「取り直せなかった古い一覧」の区別が付かない。
 * このプロジェクトが繰り返してきた「**読めなかったことを事実に化けさせる**」形そのものである。
 * (`PtyActionErrorBand` が PTY で解いたのと同じ問題を、シートでは解いていなかった。)
 *
 * **空状態が同じ失敗をすでに出しているときは出さない。** 帯と空状態で二重に言わない。
 *
 * `stale` が立たない失敗(モデル一覧は取れてエージェント一覧だけ失敗した場合)でも帯は出す ——
 * こちらも今まで画面に一言も出ていなかった。文言だけ変える。
 */
fun modelCatalogNotice(ui: ModelCatalogUi): CatalogNotice? {
    if (ui.loading) return null
    val error = ui.error ?: return null
    // 空状態が同じ失敗を出しているなら、そちらに任せる。
    if (modelCatalogEmptyStateOf(ui) != null) return null
    return if (ui.stale) {
        CatalogNotice(
            key = "catalog-stale",
            text = "$error(表示しているのは前回取得した一覧です)",
        )
    } else {
        CatalogNotice(key = "catalog-error", text = error)
    }
}

// ---------------------------------------------------------------------------
// 画面の本文領域が「今どれを描くか」(Q6 レビュー blocker)
// ---------------------------------------------------------------------------

/**
 * 本文領域の3状態。**画面はこれを `when` で分岐するだけで、状態の中身を一切読まない。**
 *
 * ## なぜ状態オブジェクトごと渡す形にしたか
 *
 * 1周目は画面側がこう書いていた:
 *
 * ```
 * val emptyState = sessionListEmptyState(ui.items.size, ui.error, ui.errorIsAuth, ui.search)
 * when {
 *     ui.loading && ui.items.isEmpty() -> Spinner()
 *     emptyState != null -> EmptyStateView(emptyState, ...)
 *     else -> List()
 * }
 * ```
 *
 * 判定そのものは純関数に出してあったが、**引数の組み立てが Compose の中に残っていた**。
 * レビューはそこを狙って変異を打ち、`authFailure = false` / `messageCount = 1` /
 * `error = null` の3本が **496件全緑で通り抜けた** —— それぞれ
 * 「401 で再試行ボタンが復活する」「Q5 の白画面が戻る」「接続失敗が
 * 『セッションがありません』+作成ボタンとして描かれる」という症状になる。
 * どれも**画面はどこも壊れて見えない**。
 *
 * これは RUN_PLAN の規則「**宛先はオブジェクトで名指しし、ラムダで受け取らない**」の、
 * 引数側の同型である。`(Int, String?, Boolean, String)` を組み立てる行が Compose の中にある限り、
 * その行を書き換える変異は純関数のテストからは見えない。**状態オブジェクトごと渡す。**
 */
sealed interface ScreenBody {
    /** まだ何も無く、取得中。 */
    data object Loading : ScreenBody

    /** 出すものが無い/失敗した。 */
    data class Empty(val spec: EmptyStateSpec) : ScreenBody

    /** 本体を描く。 */
    data object Content : ScreenBody
}

/**
 * セッション一覧の本文領域。
 *
 * **読み込み中の判定を先に置く**のは、1件も無い状態での取得中に
 * 「セッションがありません」を一瞬出さないため。1件でも持っていれば一覧が勝つ
 * (持っているものを消して待たせない)。
 */
fun sessionListBody(ui: SessionsUi): ScreenBody = when {
    ui.loading && ui.items.isEmpty() -> ScreenBody.Loading
    else -> sessionListEmptyState(
        itemCount = ui.items.size,
        error = ui.error,
        authFailure = ui.errorIsAuth,
        search = ui.search,
    )?.let(ScreenBody::Empty) ?: ScreenBody.Content
}

/** チャットの本文領域。規則は [sessionListBody] と同じ。 */
fun chatBody(ui: ChatUi): ScreenBody = when {
    ui.loading && ui.messages.isEmpty() -> ScreenBody.Loading
    else -> chatEmptyState(
        messageCount = ui.messages.size,
        error = ui.error,
        authFailure = ui.errorIsAuth,
        sessionDeleted = ui.sessionDeleted,
    )?.let(ScreenBody::Empty) ?: ScreenBody.Content
}

/**
 * モデル選択シートの空状態。**シートはスピナーと一覧を同時に出す**(取得中でも
 * 前回の候補を見せる)ので [ScreenBody] ではなく空状態だけを返す。
 * 取得中に「選べるモデルがありません」と言わないための条件もここに閉じる。
 */
fun modelCatalogEmptyStateOf(ui: ModelCatalogUi): EmptyStateSpec? {
    if (ui.loading) return null
    return modelCatalogEmptyState(
        visibleCount = ui.visibleModels.size,
        totalCount = ui.models.size,
        error = ui.error,
        authFailure = ui.errorIsAuth,
        query = ui.query,
    )
}

// ---------------------------------------------------------------------------
// Q7: 差分ビューア / 作業ツリー
// ---------------------------------------------------------------------------

/**
 * 差分ビューアの空状態(§5b Q7)。
 *
 * **「差分が無い」と「サーバーが差分を返さなかった」を分ける**のがこの関数の存在理由である。
 * 実物 serve では `GET /session/{id}/diff` が**既存200セッションすべてで `[]`** を返した
 * (402 でエージェントが1度もファイルを変更していないため)。そのとき画面が
 * 「変更はありません」とだけ言うと、**メッセージには `patch` パートがあって
 * 「3 ファイル変更」のチップまで出ているのに、開くと「変更なし」**という嘘になる。
 *
 * [knownFiles] は `PatchPart.files` —— メッセージ自身が持っている情報なので、
 * サーバーが何も返さなくても**名前だけは出せる**。
 */
fun diffViewerEmptyState(
    fileCount: Int,
    error: String?,
    authFailure: Boolean,
    source: DiffSource,
    knownFiles: List<String>,
): EmptyStateSpec? = when {
    fileCount > 0 -> null
    error != null -> loadFailureState("diff", error, authFailure)
    // メッセージ側は「変更したファイル名は分かっているのに、差分本文が返らなかった」形がある。
    source == DiffSource.MESSAGE && knownFiles.isNotEmpty() -> EmptyStateSpec(
        key = "diff-patch-unavailable",
        title = "差分の本文をサーバーが返しませんでした",
        body = "このメッセージが変更したファイル:\n" + knownFiles.joinToString("\n"),
        actions = listOf(RETRY),
    )
    source == DiffSource.MESSAGE -> EmptyStateSpec(
        key = "diff-message-empty",
        title = "このメッセージによるファイル変更はありません",
        body = "エージェントがファイルを書き換えていない往復です。",
    )
    else -> EmptyStateSpec(
        key = "diff-worktree-empty",
        title = "作業ツリーに変更はありません",
        body = "コミットされていない変更が1件もない状態です。",
    )
}

/** 差分ビューアの本文領域。規則は [sessionListBody] と同じ。 */
fun diffViewerBody(ui: DiffViewerUi): ScreenBody = when {
    ui.loading && ui.files.isEmpty() -> ScreenBody.Loading
    else -> diffViewerEmptyState(
        fileCount = ui.files.size,
        error = ui.error,
        authFailure = ui.errorIsAuth,
        source = ui.source,
        knownFiles = ui.knownFiles,
    )?.let(ScreenBody::Empty) ?: ScreenBody.Content
}

/**
 * ドロワーの「変更中のファイル」(§5b Q7 スコープ5)の空状態。
 *
 * 取得前(`loaded == false`)と「取得したが0件」を分ける —— 分けないと、
 * まだ引いていないだけの状態が「変更なし」と読める。
 */
fun workingTreeEmptyState(ui: WorkingTreeUi): EmptyStateSpec? = when {
    ui.files.isNotEmpty() -> null
    ui.loading -> null
    ui.error != null -> loadFailureState("worktree", ui.error, ui.errorIsAuth)
    !ui.loaded -> null
    else -> EmptyStateSpec(
        key = "worktree-empty",
        title = "変更中のファイルはありません",
    )
}

// ---------------------------------------------------------------------------
// Q8: ファイルブラウザ / 検索
// ---------------------------------------------------------------------------

/**
 * ツリーの空状態(§5b Q8 スコープ1)。
 *
 * **「空のディレクトリ」と「全部 `ignored` で隠している」を分ける。**
 * 分けないと、`build/` のような**中身が全部 ignored のディレクトリ**を開いたとき
 * 「ファイルがありません」と出る —— 実際には数千個ある。
 * Q4 の除外件数表示と同じ形の欠陥で、**画面はどこも壊れて見えない**。
 */
fun fileTreeEmptyState(ui: FileTreeUi): EmptyStateSpec? = when {
    ui.visibleEntries.isNotEmpty() -> null
    ui.error != null -> loadFailureState("files", ui.error, ui.errorIsAuth)
    !ui.loaded -> null
    ui.ignoredCount > 0 -> EmptyStateSpec(
        key = "files-all-ignored",
        title = "表示できる項目がありません",
        body = "${ui.ignoredCount} 件すべてが ignored です。上の「ignored も表示」で出せます。",
        actions = listOf(EmptyStateActionSpec(EmptyStateAction.SHOW_IGNORED, "ignored も表示")),
    )
    else -> EmptyStateSpec(
        key = "files-empty",
        title = "このディレクトリは空です",
    )
}

/** ツリーの本文領域。規則は [sessionListBody] と同じ。 */
fun fileTreeBody(ui: FileTreeUi): ScreenBody = when {
    ui.loading && ui.entries.isEmpty() -> ScreenBody.Loading
    else -> fileTreeEmptyState(ui)?.let(ScreenBody::Empty) ?: ScreenBody.Content
}

/**
 * ファイルビューアの空状態。
 *
 * ## 1周目の欠陥(レビュー blocker-1)—— **応答が支持できない断言をしていた**
 *
 * 1周目はここで「**空のファイルです / 0 バイト。読み込みに失敗したわけではありません。**」と
 * 出していた。**サーバーはその区別を持っていない**(実測 2026-08-28、実物 serve 1.18.21):
 *
 * ```
 * GET /file/content?path=no/such/file.txt                -> 200 {"type":"text","content":""}
 * GET /file/content?path=docs/DOES_NOT_EXIST.md          -> 200 {"type":"text","content":""}
 * GET /file/content?path=<本物の0バイトファイル>          -> 200 {"type":"text","content":""}
 * ```
 *
 * **3つはバイト単位で同一**である(いずれも 28 バイト)。つまり
 * 「0バイトのファイル」と「そのパスが無い」は**この応答からは区別できない**。
 *
 * これは Q8 が `GET /file/status` について正しく診断した当のもの ——
 * 「200 が返るので『変更が無い』と『口が機能していない』が区別できない」—— を、
 * **別の口で再導入していた**。しかも黙るより悪い: **失敗を明示的に否定していた。**
 *
 * 到達経路は2つある。どちらも「本当に0バイトのファイル」ではない:
 *  1. **スコープ6**(チャットのツールカード)。[toolInputFilePath] が拾うキーは
 *     実データが1件も無い推測なので、古い/リネームされたパスがここへ来る
 *  2. **打ち切り**。[dev.opencode.android.data.salvageTruncatedFileContent] の切断点が
 *     `content` キーの手前に落ちると本文は空になる
 *
 * したがって**区別できないことを言う**。断言しない。
 *
 * バイナリと未知種別は本文を描かないが、それは空状態ではなく注記([fileNoticesOf])が担当する
 * —— 描くものが在るからである。
 */
fun fileViewerEmptyState(ui: FileViewerUi): EmptyStateSpec? = when {
    ui.error != null -> loadFailureState("file", ui.error, ui.errorIsAuth)
    ui.payload == null -> null
    fileBodyKindOf(ui) != FileBodyKind.TEXT -> null
    ui.lines.isNotEmpty() -> null
    // **打ち切りで本文が1行も救えなかった。** これは「空のファイル」ではなく
    // 「**こちらが読むのをやめた**」である。注記([fileNoticesOf])と本文が
    // 同時に「大きすぎる」「0バイト」と言う矛盾を、1周目はここで作っていた。
    ui.payload.truncated -> EmptyStateSpec(
        key = "file-truncated-empty",
        title = "本文を読み取れませんでした",
        body = "応答が大きすぎて受信を打ち切った時点で、まだ本文が始まっていませんでした。\n" +
            "ファイルが空だという意味ではありません。",
        tone = EmptyStateTone.ERROR,
    )
    // ここから3分岐。`GET /file/content` は存在しないパスにも 200 と空の本文を返すので、
    // 応答だけでは「0バイト」と「そのパスが無い」が同じ形になる。Controller が親の一覧へ
    // 1本だけ撃って [FileViewerUi.presence] を埋めており、その結果でここが割れる
    // (`GET /find/symbol` の3分岐と同じ形)。
    //
    // **画面に出る文字に markdown の記法を混ぜない。** [EmptyStateView] は素の `Text` で
    // 描くので `**` がそのまま星として出る(1周目は実機 dump に星付きで出ていた)。
    ui.presence == FilePresence.PRESENT -> EmptyStateSpec(
        key = "file-empty",
        title = "空のファイルです",
        body = "このファイルは親ディレクトリの一覧にあり、中身が0バイトです。",
    )
    ui.presence == FilePresence.ABSENT -> EmptyStateSpec(
        key = "file-missing",
        title = "このパスは見つかりませんでした",
        body = "サーバーは空の本文を返しましたが、親ディレクトリの一覧にこのパスはありません。\n" +
            "移動や削除の後にリンクをたどった可能性があります。",
        tone = EmptyStateTone.ERROR,
    )
    // 問い合わせが失敗した / 親を持たないときはここへ落ちる。「無い」と言い切らない。
    else -> EmptyStateSpec(
        key = "file-empty-or-missing",
        title = "本文が空です",
        body = "サーバーは空の本文を返しました。\n" +
            "このサーバーは存在しないパスにも同じ応答を返すため、" +
            "親ディレクトリの一覧を確かめられないかぎり、" +
            "「中身が空のファイル」なのか「そのパスが無い」のかは区別できません。",
    )
}

/** ファイルビューアの本文領域。 */
fun fileViewerBody(ui: FileViewerUi): ScreenBody = when {
    ui.loading && ui.payload == null -> ScreenBody.Loading
    else -> fileViewerEmptyState(ui)?.let(ScreenBody::Empty) ?: ScreenBody.Content
}

/**
 * 検索の空状態(§5b Q8 スコープ3・4・5)。
 *
 * ## この関数の存在理由は**シンボルの3分岐**である
 *
 * `GET /find/symbol` は LSP が動いていない環境で `200 []` を返す(実測: この環境では
 * どの語でも空)。**その応答からは「見つからない」と「索引が使えない」が区別できない。**
 * Controller が校正クエリを撃って [SymbolIndexState] を付け、ここが3つに割る:
 *
 *  - [SymbolIndexState.UNAVAILABLE] → `symbols-index-unavailable`(**「無い」と言わない**)
 *  - [SymbolIndexState.AVAILABLE] → `symbols-search-empty`(索引は在って当たらなかった)
 *  - [SymbolIndexState.UNKNOWN] → `symbols-index-unknown`(**校正そのものが測れなかった**)
 *
 * これは Q0 の R1(空バブル)、Q4 の除外件数、Q6 の打ち切り注記、Q7 の `diff-patch-missing` と
 * **同じ原則の6回目**である。「無い」と「取れなかった」を混同しない。
 */
fun fileSearchEmptyState(ui: FileSearchUi): EmptyStateSpec? {
    if (ui.error != null) return loadFailureState("search", ui.error, ui.errorIsAuth)
    if (ui.query.isBlank()) {
        return EmptyStateSpec(
            key = "search-idle",
            title = when (ui.tab) {
                FileSearchTab.TEXT -> "ファイルの中身を検索します"
                FileSearchTab.FILES -> "ファイル名を検索します"
                FileSearchTab.SYMBOLS -> "シンボル(関数・クラス)を検索します"
            },
            body = "上の欄に語を入力してください。",
        )
    }
    // **撃つ前の0件を「該当なし」と描かない。** デバウンス中はまだ何も知らない。
    if (!ui.searched) return null
    return when (ui.tab) {
        FileSearchTab.TEXT ->
            if (ui.textGroups.isNotEmpty()) {
                null
            } else {
                EmptyStateSpec(
                    key = "find-text-empty",
                    title = "「${ui.submittedQuery}」を含む行はありません",
                    body = "正規表現として解釈されます。記号を含む語は形を確かめてください。",
                )
            }
        FileSearchTab.FILES ->
            if (ui.files.isNotEmpty()) {
                null
            } else {
                EmptyStateSpec(
                    key = "find-file-empty",
                    title = "「${ui.submittedQuery}」に一致するファイル名はありません",
                )
            }
        FileSearchTab.SYMBOLS -> symbolEmptyState(ui)
    }
}

/** シンボル検索だけ3分岐する(上の doc)。**この判定を画面に書き戻さないこと。** */
private fun symbolEmptyState(ui: FileSearchUi): EmptyStateSpec? {
    if (ui.symbols.isNotEmpty()) return null
    return when (ui.symbolIndex) {
        SymbolIndexState.UNAVAILABLE -> EmptyStateSpec(
            key = "symbols-index-unavailable",
            title = "シンボル索引が使えません",
            body = "サーバー側で LSP が動いていないため、シンボル検索は常に空になります。" +
                "「このシンボルが無い」という意味ではありません。",
            tone = EmptyStateTone.ERROR,
        )
        SymbolIndexState.AVAILABLE -> EmptyStateSpec(
            key = "symbols-search-empty",
            title = "「${ui.submittedQuery}」に一致するシンボルはありません",
            body = "索引は使えています(別の語では結果が返りました)。",
        )
        SymbolIndexState.UNKNOWN -> EmptyStateSpec(
            key = "symbols-index-unknown",
            title = "シンボルが見つかりませんでした",
            body = "索引が使えるかどうかの確認に失敗しました。" +
                "「無い」のか「索引が使えない」のかは判定できていません。",
            actions = listOf(RETRY),
        )
    }
}

/** 検索結果の本文領域。 */
fun fileSearchBody(ui: FileSearchUi): ScreenBody = when {
    ui.loading -> ScreenBody.Loading
    else -> fileSearchEmptyState(ui)?.let(ScreenBody::Empty) ?: ScreenBody.Content
}

// ---------------------------------------------------------------------------
// Q9: ターミナル(PTY)
// ---------------------------------------------------------------------------

/**
 * ターミナル一覧の空状態(§5b Q9 スコープ4)。
 *
 * **「走っているものが1つも無い」と「まだ引いていない」を分ける。**
 * `GET /pty` は終了した PTY を一覧から消すので(実測)、空の一覧は
 * 「作っていない」と「全部終わった」の両方でありうる —— どちらであっても
 * ユーザーができることは「起動する」なので同じ文言でよいが、
 * **取得前の空を「ありません」と描かない**のは他の画面と同じ規則である。
 */
fun ptyListEmptyState(ui: PtyListUi): EmptyStateSpec? = when {
    ui.items.isNotEmpty() -> null
    ui.error != null -> loadFailureState("pty", ui.error, ui.errorIsAuth)
    !ui.loaded -> null
    else -> EmptyStateSpec(
        key = "pty-empty",
        title = "起動中のターミナルはありません",
        body = "下のシェルを選んで起動してください。" +
            "終了したターミナルはサーバーの一覧から消えるため、ここには残りません。",
    )
}

/** 一覧の本文領域。規則は [sessionListBody] と同じ。 */
fun ptyListBody(ui: PtyListUi): ScreenBody = when {
    ui.loading && ui.items.isEmpty() -> ScreenBody.Loading
    else -> ptyListEmptyState(ui)?.let(ScreenBody::Empty) ?: ScreenBody.Content
}

/**
 * ターミナル画面(出力領域)の空状態。
 *
 * **「まだ出力が来ていない」と「終わったのに何も出なかった」を分ける。**
 * 前者は待てば変わり、後者は変わらない —— 同じ「空」で描くと、
 * 終了済みのターミナルを開いた人が待ち続けることになる。
 *
 * 状態帯([terminalStatusLine])は接続と生死を言う。ここは**本文が空である理由**だけを言う。
 */
fun terminalEmptyState(ui: PtyTerminalUi): EmptyStateSpec? = when {
    ui.ptyId == null -> EmptyStateSpec(
        key = "pty-none-open",
        title = "ターミナルを開いていません",
        body = "上の一覧から選ぶか、シェルを起動してください。",
    )
    ui.error != null -> loadFailureState("terminal", ui.error, ui.errorIsAuth)
    ui.buffer.text.isNotEmpty() -> null
    ui.finished -> EmptyStateSpec(
        key = "pty-no-output",
        title = "出力を受け取らないまま終了しました",
        body = "接続する前に終わったか、出力が1バイトも無かったかは区別できません。",
    )
    ui.connection == PtyConnectionState.CONNECTED -> EmptyStateSpec(
        key = "pty-connected-no-output",
        title = "接続しましたが、まだ出力がありません",
        body = "シェルからの出力を待っています。",
    )
    else -> null
}

/**
 * ターミナルの本文領域。
 *
 * **1文字でも受け取っていれば本文が勝つ。** 接続が切れても、それまでに読んだ出力は消さない
 * ([sessionListBody] の「持っているものを消して失敗だけを出さない」と同じ規則)。
 */
fun terminalBody(ui: PtyTerminalUi): ScreenBody = when {
    ui.buffer.text.isNotEmpty() -> ScreenBody.Content
    ui.connection == PtyConnectionState.CONNECTING -> ScreenBody.Loading
    else -> terminalEmptyState(ui)?.let(ScreenBody::Empty) ?: ScreenBody.Content
}
