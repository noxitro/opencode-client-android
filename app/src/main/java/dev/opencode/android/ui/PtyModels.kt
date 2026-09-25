package dev.opencode.android.ui

import dev.opencode.android.data.PtyDto
import dev.opencode.android.data.PtyShellDto

/**
 * ターミナル(PTY)の**状態と純関数**(QUALITY_PLAN §5b Q9)。
 *
 * 規則は Q7 の [DiffController] / Q8 の [FileBrowserModels][FileBrowserPane] と同じ:
 *
 *  - **Android にも Compose にも依存しない。** `runTest` すら要らない素の関数として叩ける
 *  - **判定はここにしかない。** 画面に `if (ui.exitCode != null)` の類を書き戻さない
 *  - **`content-desc` に載せる文字列を状態が持つ。** judge はスクショを見られない
 *
 * ## このファイルが背負っている中核の区別
 *
 * `docs/HANDOFF.md` §5 の一覧に Q9 が足す行は
 * **「シェルが終わったのか、こちらの接続が切れたのか」**である。
 * WebSocket の close code は両方で `1000` になりうるし、`GET /pty` は
 * 終了した PTY を**一覧から消す**ので、「居ない」も両義である。
 * [PtyLifeState] がその区別を4つに割り、[classifyClosedSocket] がどれに落ちるかを決める。
 */

/** シェルの入力に送るバイト列を持つ補助キー(§5b Q9 スコープ3)。 */
enum class PtyKey { CTRL_C, CTRL_D, TAB, ESC, ENTER, UP, DOWN, LEFT, RIGHT }

/**
 * 補助キー → 実際に送る文字列。**表と実装を分けない**(表があると片方だけ直る)。
 *
 * `Ctrl+C` が `\u0003` の1文字で足りることは実測してある(2026-08-30、`cmd.exe`)。
 * 矢印は xterm 形式の CSI で、ConPTY が受ける。
 */
fun ptyKeySequence(key: PtyKey): String = when (key) {
    PtyKey.CTRL_C -> "\u0003"
    PtyKey.CTRL_D -> "\u0004"
    PtyKey.TAB -> "\t"
    PtyKey.ESC -> "\u001b"
    PtyKey.ENTER -> "\r"
    PtyKey.UP -> "\u001b[A"
    PtyKey.DOWN -> "\u001b[B"
    PtyKey.RIGHT -> "\u001b[C"
    PtyKey.LEFT -> "\u001b[D"
}

/** 補助キーの見えるラベル。**画面に文字を書き戻さない**(ラベルもここが持つ)。 */
fun ptyKeyLabel(key: PtyKey): String = when (key) {
    PtyKey.CTRL_C -> "Ctrl+C"
    PtyKey.CTRL_D -> "Ctrl+D"
    PtyKey.TAB -> "Tab"
    PtyKey.ESC -> "Esc"
    PtyKey.ENTER -> "Enter"
    PtyKey.UP -> "↑"
    PtyKey.DOWN -> "↓"
    PtyKey.LEFT -> "←"
    PtyKey.RIGHT -> "→"
}

/** 補助キー行に出す順序。 */
val PTY_KEY_ROW: List<PtyKey> = listOf(
    PtyKey.CTRL_C,
    PtyKey.TAB,
    PtyKey.UP,
    PtyKey.DOWN,
    PtyKey.LEFT,
    PtyKey.RIGHT,
    PtyKey.ESC,
    PtyKey.CTRL_D,
)

/** WebSocket の状態。**プロセスの生死とは別軸**である([PtyLifeState])。 */
enum class PtyConnectionState {
    /** まだ開こうとしていない。 */
    IDLE,

    /** チケット取得 〜 101 待ち。 */
    CONNECTING,

    /** 開いている。 */
    CONNECTED,

    /** 落ちたので開き直している。 */
    RECONNECTING,

    /** 閉じている(理由は [PtyTerminalUi.closeCode] と [PtyLifeState])。 */
    CLOSED,
}

/**
 * シェルのプロセスがどうなったか。**4つに割るのがこの型の存在理由である。**
 *
 * 実測(2026-08-30、実物 serve 1.18.21)が作った制約:
 *
 *  - プロセスが終わると PTY は `GET /pty` から消え、`GET /pty/{id}` は 404 になる。
 *    **`status:"exited"` を REST で読める窓が無い**
 *  - 終了コードは SSE `pty.exited` の1回きり。**SSE が切れている間に終わったら永久に取れない**
 *  - `DELETE /pty/{id}` は `pty.deleted` を流し、**`exitCode` を持たない**
 *
 * したがって「終了コードを持たない終了」が2種類あり、どちらも
 * **0 と書いてはならない**。0 は「正常終了した」という別の主張である。
 */
sealed interface PtyLifeState {
    /** まだ何も分かっていない(作った直後・一覧を引く前)。 */
    data object Unknown : PtyLifeState

    /** サーバーの一覧に居る。 */
    data object Running : PtyLifeState

    /** `pty.exited` を受け取った。**終了コードが分かっている唯一の状態**。 */
    data class Exited(val exitCode: Int) : PtyLifeState

    /**
     * 終わったことは分かるが**終了コードを受け取っていない**
     * (SSE が切れている間に終わった / `pty.exited` が届く前に一覧から消えた)。
     */
    data object ExitedCodeUnknown : PtyLifeState

    /** こちらが `DELETE` した。**この経路では終了コードは原理的に来ない**。 */
    data object Deleted : PtyLifeState
}

/**
 * ソケットが閉じたときに、プロセスがどうなったと判断するか。
 *
 * **この判断を画面にも Controller の分岐にも書き戻さないこと。**
 * 1回の close code からは何も分からない —— `1000` は正常終了だが、
 * 「シェルが終わった」ことも「サーバーが接続だけ畳んだ」ことも同じ `1000` になりうる。
 * 決め手は**サーバーの一覧にまだ居るか**である。
 *
 * @param known これまでに確定している生死。**既に [PtyLifeState.Exited] なら上書きしない** ——
 *   終了コードを持っている状態を「コード不明」へ落とすのは情報の後退である
 * @param stillListed 直後に引いた `GET /pty` にこのIDが載っていたか。
 *   **null は「引けなかった」**(通信失敗)であって「載っていない」ではない
 */
fun classifyClosedSocket(known: PtyLifeState, stillListed: Boolean?): PtyLifeState = when {
    known is PtyLifeState.Exited -> known
    known is PtyLifeState.Deleted -> known
    // 引けなかった。**「終わった」と言わない。** 分かっているのは接続が切れたことだけ。
    stillListed == null -> known
    stillListed -> PtyLifeState.Running
    else -> PtyLifeState.ExitedCodeUnknown
}

/** ターミナル画面の状態。 */
data class PtyTerminalUi(
    /** null = まだどのPTYも開いていない。 */
    val ptyId: String? = null,
    val title: String = "",
    val command: String = "",
    val connection: PtyConnectionState = PtyConnectionState.IDLE,
    val life: PtyLifeState = PtyLifeState.Unknown,
    val buffer: TerminalBuffer = TerminalBuffer(),
    /**
     * 再開位置(出力ストリーム先頭からの累積バイト数)。
     * **null は「まだ位置を知らない」**であって 0 ではない
     * ([dev.opencode.android.data.advancePtyCursor])。
     */
    val cursor: Long? = null,
    /** 直近の close code。**null = ハンドシェイクにすら至らなかった**、または未切断。 */
    val closeCode: Int? = null,
    val closeReason: String = "",
    /** 再接続の試行回数(0 = まだ落ちていない)。 */
    val reconnectAttempt: Int = 0,
    /**
     * **上限まで試して諦めたか。** [reconnectAttempt] だけでは足りない ——
     * 「5回試して諦めた」と「まだ1回も試していない」は、どちらも
     * 「繋がっていない」だが**この先の振る舞いが逆**である(前者はもう自動で繋ぎ直さない)。
     */
    val gaveUpReconnecting: Boolean = false,
    val error: String? = null,
    val errorIsAuth: Boolean = false,
    /** 直近にサーバーへ伝えた端末サイズ。**0 = まだ伝えていない**。 */
    val rows: Int = 0,
    val cols: Int = 0,
) {
    val open: Boolean get() = ptyId != null

    /** 終了コード。**分かっているときだけ非 null**(「取れなかった」は null)。 */
    val exitCode: Int? get() = (life as? PtyLifeState.Exited)?.exitCode

    /** プロセスはもう動いていないか。 */
    val finished: Boolean
        get() = life is PtyLifeState.Exited ||
            life is PtyLifeState.ExitedCodeUnknown ||
            life is PtyLifeState.Deleted

    /**
     * 入力を受け付けるか。**判定はここにしかない。**
     * 終わったシェルへ打った文字は行き先が無いので、入力欄を黙って死なせない。
     */
    val inputEnabled: Boolean
        get() = connection == PtyConnectionState.CONNECTED && !finished
}

/** `GET /pty` の一覧。 */
data class PtyListUi(
    val loading: Boolean = false,
    val items: List<PtyDto> = emptyList(),
    val error: String? = null,
    val errorIsAuth: Boolean = false,
    /** 一度でも取得に**成功**したか。失敗は成功ではない。 */
    val loaded: Boolean = false,
)

/** `GET /pty/shells` の一覧。 */
data class PtyShellsUi(
    val loading: Boolean = false,
    val items: List<PtyShellDto> = emptyList(),
    val error: String? = null,
    val errorIsAuth: Boolean = false,
    val loaded: Boolean = false,
) {
    /**
     * 起動候補。**`acceptable:false` を消さない** —— サーバーが薦めていないだけで
     * 存在はしている。消すと「無い」と「薦められていない」が区別できなくなる。
     */
    val startable: List<PtyShellDto> get() = items
}

/**
 * アクション(入力送信・リサイズ・削除)の失敗。**どの PTY に対する失敗かを持つ。**
 *
 * `String?` だった1周目は、失敗した PTY が分からなかった。症状:
 * サーバー停止中に `PUT /pty/{id}` が失敗して帯が立ち、**一覧へ戻って新しい PTY を
 * 起動しても、健全な端末に古いサーバーエラーが貼り付いたまま残る**
 * (Q9 の E2E ゲートが 5 端末 × サーバー再起動3回を跨いで実測)。
 *
 * `ptyId` は「その操作が対象にした PTY」。`null` は特定の端末に紐づかない失敗。
 * 出す/出さないの判定は [ptyActionErrorMessage] にしかない。
 *
 * ## なぜ [origin] が要るのか(レビュー minor-1)
 *
 * `ptyId` だけでは**一覧の窓が塞げない**。端末 A でリサイズの `PUT` が飛んでいる最中に
 * 一覧へ戻ると [PtyController.closeTerminal] が `actionError` を消すが、
 * **失敗はその後で返ってくる**ので `actionError` が書き直される ——
 * すると「もう開いていない端末のサーバーエラー」が一覧の帯に出る。
 * 端末の窓で `ptyId == openPtyId` を要求して塞いだのと**同型の穴**である。
 *
 * [origin] は「**その操作をどの窓から起こしたか**」。一覧の窓は一覧から起こした失敗だけを出す。
 * 削除は両方の窓から起こせるので、`PtyController` が**発行時**の窓を載せる。
 */
data class PtyActionError(
    val ptyId: String?,
    val message: String,
    /** その操作を起こした窓。**発行時に決まる**(失敗が返った時点の窓ではない)。 */
    val origin: PtyScreenPane,
)

/** ターミナル画面ひとまとめ。画面はこれ1つを購読する。 */
data class PtyUi(
    val list: PtyListUi = PtyListUi(),
    val shells: PtyShellsUi = PtyShellsUi(),
    val terminal: PtyTerminalUi = PtyTerminalUi(),
    val creating: Boolean = false,
    val createError: String? = null,
    val actionError: PtyActionError? = null,
)

/**
 * その窓に出してよいアクションエラーの本文。**判定はここにしかない。**
 *
 * - 端末の窓では、**いま開いている端末を対象にした失敗だけ**を出す。
 *   別の PTY の失敗を出すと、健全な端末に他所のサーバーエラーが貼り付く
 *   (Q9 E2E が実測した症状そのもの)。非同期の `PUT` が端末を切り替えた**後**に
 *   失敗して返ってくる経路も、ここで閉じる —— 切り替え時のクリアだけでは間に合わない。
 * - 一覧の窓は特定の端末を見ていない。**一覧から起こした操作(行の「終了」)の失敗だけ**を
 *   出す —— 黙って捨てないが、他所の窓の失敗も出さない。
 *   端末で撃った `PUT` が一覧へ戻った**後**に失敗して返る経路をここで閉じる
 *   (レビュー minor-1。[PtyController.closeTerminal] のクリアだけでは間に合わない)。
 */
fun ptyActionErrorMessage(error: PtyActionError?, pane: PtyScreenPane, openPtyId: String?): String? {
    if (error == null) return null
    return when (pane) {
        PtyScreenPane.TERMINAL -> if (error.ptyId == openPtyId) error.message else null
        PtyScreenPane.LIST -> if (error.origin == PtyScreenPane.LIST) error.message else null
    }
}

/** 一覧の1行が `content-desc` に載せる識別子。 */
fun ptyRowDescription(pty: PtyDto): String = "pty-row:${pty.id}:${pty.status}:${pty.pid}"

/** 注記1つ。[key] は `content-desc` に載る。 */
data class PtyNotice(val key: String, val text: String)

/**
 * ターミナルの注記(§5b Q9 スコープ2 の「制限をUIに明示する」)。
 *
 * ## なぜ「常に出す」のか
 *
 * 「全画面制御を検出したら警告」にすると、**Windows では起動直後から必ず出る**
 * (`cmd.exe` は接続直後に `ESC[2J` `ESC[H` `ESC[?25l` を吐く。実測)。
 * 常に出る警告は警告として働かないので、**制限は常設の説明**にし、
 * その横に**落とした件数**を出す。件数のほうが状況で変わる。
 *
 * ## 件数を出す理由
 *
 * 0件と「1件も来ていない」は同じことだが、**数えていること自体が
 * 「解釈しなかった物が在る」を可視にする**。数えないと、壊れた表示を見た人が
 * 「サーバーが何も出していない」と読む余地が残る(Q0 の空バブル以来の原則)。
 */
fun terminalNotices(ui: PtyTerminalUi): List<PtyNotice> {
    val notices = mutableListOf<PtyNotice>()
    notices += PtyNotice(
        "pty-ansi-limited",
        "この端末は色(SGR)だけを解釈します。カーソル移動・画面消去・代替画面は解釈しません。" +
            "vim や top のような全画面TUIは正しく描けません。",
    )
    val dropped = ui.buffer.dropped
    val alt = dropped[AnsiControlKind.ALT_SCREEN] ?: 0
    if (alt > 0) {
        notices += PtyNotice(
            "pty-alt-screen:$alt",
            "全画面(代替画面)への切り替えを $alt 回受け取りましたが、この端末は切り替えません。" +
                "実行中のプログラムの画面は表示されていません。Ctrl+C や q で戻してください。",
        )
    }
    if (ui.buffer.droppedTotal > 0) {
        notices += PtyNotice(
            "pty-dropped:${ui.buffer.droppedTotal}",
            "解釈しなかった制御シーケンス ${ui.buffer.droppedTotal} 件" +
                "(カーソル移動 ${dropped[AnsiControlKind.CURSOR] ?: 0} / " +
                "画面消去 ${dropped[AnsiControlKind.ERASE] ?: 0} / " +
                "代替画面 $alt / " +
                "モード設定 ${dropped[AnsiControlKind.MODE] ?: 0} / " +
                "OSC ${dropped[AnsiControlKind.OSC] ?: 0} / " +
                "その他 ${dropped[AnsiControlKind.OTHER] ?: 0})",
        )
    }
    if (ui.buffer.trimmedLines > 0) {
        notices += PtyNotice(
            "pty-trimmed:${ui.buffer.trimmedLines}",
            "古い ${ui.buffer.trimmedLines} 行は画面から捨てました(保持は $TERMINAL_MAX_LINES 行まで)",
        )
    }
    return notices
}

/** 帯1本。[key] は `content-desc` に載る。 */
data class PtyStatusLine(val key: String, val text: String, val tone: EmptyStateTone)

/**
 * ターミナル上部の帯。**「シェルが終わった」と「接続が切れた」を分ける唯一の場所**である。
 *
 * 順序に意味がある: **生死が接続よりも、そして直近のエラーよりも先**。
 * 接続が `CLOSED` なのは終了しても切断でも同じで、
 * 先に接続を見ると「シェルが終了しました」を「接続が切れました」で塗り潰す。
 *
 * ## エラーより先に見る理由(レビュー minor-7)
 *
 * 1周目は `error != null` が先頭だった。[PtyTerminalUi.error] は**接続に失敗したときの
 * 1行**で、生死とは別軸である。したがって「接続できずエラーが立つ → その後 SSE で
 * `pty.exited{exitCode:0}` が届く」経路では、**終了したのに古い接続エラーが
 * 帯に出たままになる**。エラーは既に起きたことの記録で、生死は今の事実である。
 */
fun terminalStatusLine(ui: PtyTerminalUi): PtyStatusLine = when {
    ui.life is PtyLifeState.Exited -> PtyStatusLine(
        "pty-exited:${ui.life.exitCode}",
        "シェルは終了しました(終了コード ${ui.life.exitCode})",
        if (ui.life.exitCode == 0) EmptyStateTone.NEUTRAL else EmptyStateTone.ERROR,
    )
    ui.life is PtyLifeState.ExitedCodeUnknown -> PtyStatusLine(
        "pty-exited-code-unknown",
        "シェルは終了しました。終了コードは受け取れていません" +
            "(終了通知はイベント接続にしか流れず、切れている間の分は残りません)。" +
            "0 だったという意味ではありません。",
        EmptyStateTone.ERROR,
    )
    ui.life is PtyLifeState.Deleted -> PtyStatusLine(
        "pty-deleted",
        "このターミナルを削除しました。削除では終了通知が流れないため、終了コードは分かりません。",
        EmptyStateTone.NEUTRAL,
    )
    ui.error != null -> PtyStatusLine(
        if (ui.errorIsAuth) "pty-status-unauthorized" else "pty-status-error",
        ui.error,
        EmptyStateTone.ERROR,
    )
    // **諦めたことを言う。** 「接続が切れました」とだけ書くと、自動で復帰する途中に見える
    // (判断6)。ここに落ちたら**押されるまで何も起きない**。
    ui.gaveUpReconnecting -> PtyStatusLine(
        "pty-reconnect-gave-up:${ui.reconnectAttempt}",
        "接続が切れました。${ui.reconnectAttempt} 回試しましたが繋がらないので、自動での再接続をやめました。" +
            "これ以上は自動で試しません。「再接続」を押してください。",
        EmptyStateTone.ERROR,
    )
    ui.connection == PtyConnectionState.CONNECTING -> PtyStatusLine(
        "pty-connecting",
        "接続中…",
        EmptyStateTone.NEUTRAL,
    )
    ui.connection == PtyConnectionState.RECONNECTING -> PtyStatusLine(
        "pty-reconnecting:${ui.reconnectAttempt}",
        "接続が切れました。再接続しています(${ui.reconnectAttempt} 回目)",
        EmptyStateTone.ERROR,
    )
    ui.connection == PtyConnectionState.CONNECTED -> PtyStatusLine(
        "pty-connected",
        "接続中",
        EmptyStateTone.NEUTRAL,
    )
    ui.connection == PtyConnectionState.CLOSED -> PtyStatusLine(
        // **close code を key に載せる。** `null` は「ハンドシェイクにすら至らなかった」で、
        // `1000` の正常終了とは別のことである。
        //
        // ここは**上限まで試して諦めた状態ではない**(それは上の `pty-reconnect-gave-up`)。
        "pty-disconnected:${ui.closeCode ?: "none"}",
        "接続が切れました" +
            (ui.closeCode?.let { "(コード $it)" } ?: "(接続できませんでした)") +
            "。シェルはサーバー上で動いている可能性があります。",
        EmptyStateTone.ERROR,
    )
    else -> PtyStatusLine("pty-idle", "未接続", EmptyStateTone.NEUTRAL)
}

// ---------------------------------------------------------------------------
// 画面が使う純関数(Compose に依存させない)
// ---------------------------------------------------------------------------

/** TopAppBar の題。**開いている端末の題を優先する**(どれを見ているか分かるように)。 */
fun terminalScreenTitle(ui: PtyUi): String =
    ui.terminal.takeIf { it.open }?.title?.takeIf { it.isNotBlank() } ?: "ターミナル"

/** 画面の本体が一覧か端末か。 */
enum class PtyScreenPane { LIST, TERMINAL }

/**
 * **どちらを描くかの判定はここにしかない。** 状態オブジェクトごと受け取る
 * ([fileBrowserPane] と同じ形)—— `(Boolean)` を受け取ると、
 * 呼び出し側が別の値を渡す変異がこの関数のテストからは見えない。
 */
fun ptyScreenPane(ui: PtyUi): PtyScreenPane =
    if (ui.terminal.open) PtyScreenPane.TERMINAL else PtyScreenPane.LIST

/**
 * 起動候補のラベル。**`acceptable:false` を隠さず印を付ける** ——
 * 一覧から消すと「無い」と「薦められていない」が区別できなくなる。
 */
fun ptyShellLabel(name: String, acceptable: Boolean): String =
    if (acceptable) name else "$name(非推奨)"

/** 端末の最小の行数・桁数。**0 を送らないための下限**(下の doc)。 */
const val TERMINAL_MIN_ROWS = 5
const val TERMINAL_MIN_COLS = 20

/**
 * 画面の寸法 → 端末の行数・桁数。**純関数にしてあるのは Compose の外で固定するため。**
 *
 * 下限を置くのは、寸法が 0 の一瞬(初回コンポジション)に `rows=0, cols=0` を
 * サーバーへ送らないためである。実測では `PUT /pty/{id}` は 0 を 200 で受理するので、
 * **サーバーは弾いてくれない**。
 *
 * @return `(rows, cols)`
 */
fun terminalGridOf(
    widthPx: Float,
    heightPx: Float,
    charWidthPx: Float,
    lineHeightPx: Float,
): Pair<Int, Int> {
    if (charWidthPx <= 0f || lineHeightPx <= 0f) return TERMINAL_MIN_ROWS to TERMINAL_MIN_COLS
    val cols = (widthPx / charWidthPx).toInt().coerceAtLeast(TERMINAL_MIN_COLS)
    val rows = (heightPx / lineHeightPx).toInt().coerceAtLeast(TERMINAL_MIN_ROWS)
    return rows to cols
}

