package dev.opencode.android.ui

import dev.opencode.android.data.FILE_CONTENT_MAX_BYTES
import dev.opencode.android.data.FIND_FILE_LIMIT
import dev.opencode.android.data.FIND_SERVER_CAP
import dev.opencode.android.data.FileContentPayload
import dev.opencode.android.data.FileNodeDto
import dev.opencode.android.data.FindMatchDto
import dev.opencode.android.data.SymbolDto
import dev.opencode.android.data.VcsFileStatusDto

/**
 * ファイルブラウザ + 検索の**状態と純関数**(QUALITY_PLAN §5b Q8)。
 *
 * ここに置くものの規則は Q7 の [DiffController] と同じ:
 *
 *  - **Android にも Compose にも依存しない。** `runTest` から全部叩ける
 *  - **判定はここにしかない。** 画面に `if (entry.ignored && !showIgnored)` の類を書き戻さない ——
 *    配線に条件を置くと、その条件を消す変異が全緑で通り抜ける(Q1〜Q7 で6度示された形)
 *  - **`content-desc` に載せる文字列を状態が持つ。** judge はスクショを見られない
 */

/** ツリーの1行。 */
data class FileEntryUi(
    val name: String,
    /** 正規化済み(`/` 区切り・末尾の区切り無し)。[normalizeServerPath] を通した後の値。 */
    val path: String,
    val isDirectory: Boolean,
    /** `FileNode.ignored`。**required なので「無いから false」ではなく、サーバーがそう言った**。 */
    val ignored: Boolean,
    /**
     * 作業ツリーでの変更状態(`GET /vcs/status` 由来。`"added"`/`"modified"`/`"deleted"`)。
     *
     * **`GET /file/status` は使わない** —— 1.18.21 は変更があっても `[]` を返す(実測)。
     * 200 が返るので「変更が無い」と「口が機能していない」が区別できない。
     */
    val vcsStatus: String? = null,
) {
    /** dump から引用できる識別子。**押せるノード側**に付ける(Q3/Q6 で3回踏んだ罠)。 */
    val description: String
        get() = buildString {
            append("file-entry:")
            append(path)
            append(':')
            append(if (isDirectory) "dir" else "file")
            if (ignored) append(":ignored")
            if (vcsStatus != null) append(":$vcsStatus")
        }
}

/**
 * ツリーの状態(§5b Q8 スコープ1)。
 *
 * [showIgnored] の既定は false = **`ignored:true` は隠す**。計画書の要求そのもので、
 * 隠すこと自体は「無い」ではないので**トグルと件数を必ず出す**
 * ([ignoredCount])—— Q4 の除外件数表示と同じ原則。
 */
data class FileTreeUi(
    val path: String = FILE_ROOT_PATH,
    val loading: Boolean = false,
    val entries: List<FileEntryUi> = emptyList(),
    val error: String? = null,
    val errorIsAuth: Boolean = false,
    val showIgnored: Boolean = false,
    /** 一度でも取得に成功したか。**失敗は成功でない。** */
    val loaded: Boolean = false,
) {
    val crumbs: List<FileCrumb> get() = breadcrumbsOf(path)

    /** **隠すかどうかの判定はここにしかない。** 画面に書き戻さないこと。 */
    val visibleEntries: List<FileEntryUi>
        get() = if (showIgnored) entries else entries.filter { !it.ignored }

    /** 隠している件数。**0 でも「隠していない」と同義ではない**ので、画面は件数で分岐しない。 */
    val ignoredCount: Int get() = entries.count { it.ignored }
}

/** ツリーの行に並べ替え規則を1か所で与える。**ディレクトリが先、その中で名前順。** */
fun fileEntriesOf(nodes: List<FileNodeDto>, status: List<VcsFileStatusDto>): List<FileEntryUi> {
    val byPath = status.associateBy({ normalizeServerPath(it.file) }, { it.status })
    return nodes
        .map { node ->
            val path = normalizeServerPath(node.path)
            FileEntryUi(
                name = node.name.ifBlank { fileNameOf(path) },
                path = path,
                isDirectory = node.type == "directory",
                ignored = node.ignored,
                vcsStatus = byPath[path],
            )
        }
        .sortedWith(compareByDescending<FileEntryUi> { it.isDirectory }.thenBy { it.name.lowercase() })
}

// ---------------------------------------------------------------------------
// ファイルビューア(スコープ2)
// ---------------------------------------------------------------------------

/**
 * 1ファイルあたりに描く最大行数。Q7 の [DIFF_MAX_LINES] と同じ値・同じ理由。
 *
 * 受信の打ち切り([FILE_CONTENT_MAX_BYTES])とは**別の打ち切り**である ——
 * 512 KiB の1行だけのファイル(minify した JS など)は受信は通るが、
 * 1行を描くだけで LazyColumn が詰まる。**両方とも黙って切らない。**
 */
const val FILE_MAX_LINES = 5000

/** ビューアの1行。行番号は1始まり。 */
data class FileLine(val number: Int, val text: String)

/**
 * 展開したファイルに**何を描くか**。Q7 の [DiffFileBody] と同じ形で enum に出す。
 *
 * 順序に意味がある: 「まだ来ていない」が最初 —— それが分かった時点で
 * テキストかバイナリかは**サーバーが教えていない**(判定材料が存在しない)。
 */
enum class FileBodyKind {
    /** 応答がまだ無い。 */
    NOT_LOADED,

    /** `type:"text"`。行を描く。 */
    TEXT,

    /**
     * `type:"binary"`。**中身を描かない。** mimeType とサイズだけ出す
     * (§5b Q8 スコープ2 の「base64 を文字として描かない」)。
     */
    BINARY,

    /**
     * `type` が `text` でも `binary` でもない。**「バイナリ」と決めつけない** ——
     * 打ち切りで `type` すら読めなかった場合もここに来る。
     * Q7 の `diff-patch-missing`(「バイナリとは限りません」)と同じ形である。
     */
    UNKNOWN,
}

/**
 * 本文が空だったときに、**そのパスが在るのか無いのか**。
 *
 * `GET /file/content` は**存在しないパスにも `200` と空の本文を返す**(実測)。
 * 応答だけを見ているかぎり「0バイトのファイル」と「そのパスが無い」は同じ形をしており、
 * 1周目の画面は「区別できません」と正直に書くことしかできなかった。
 *
 * 区別は**親ディレクトリの一覧に問い合わせれば付く**(`GET /file?path=<親>`)。
 * `GET /find/symbol` が空のときに校正クエリで [SymbolIndexState] を決めたのと同じやり方で、
 * Controller が1本だけ余計に撃ってここを埋める。
 *
 * **[UNKNOWN] を「無い」の意味に使わないこと。** 問い合わせ自体が失敗したときは
 * `PRESENT` でも `ABSENT` でもなく、**分からない**のである。
 */
enum class FilePresence {
    /** まだ問い合わせていない / 問い合わせが失敗した。 */
    UNKNOWN,

    /** 親の一覧に同じパスが在った → **中身が空のファイル**。 */
    PRESENT,

    /** 親の一覧に無かった → **サーバーがそのパスを見つけられなかった**。 */
    ABSENT,
}

/**
 * 親ディレクトリの一覧から [target] の在り所を決める。**純関数**。
 *
 * 比較は [normalizeServerPath] を通した値どうしで行う —— 一覧の `path` は
 * サーバーOSの区切り(`\`)で、ディレクトリには末尾の区切りが付く([FilePaths] の doc)。
 * 生の文字列で比べると、**同じファイルなのに ABSENT と読む**。
 */
fun filePresenceIn(entries: List<FileNodeDto>, target: String): FilePresence {
    val want = normalizeServerPath(target)
    return if (entries.any { normalizeServerPath(it.path) == want }) {
        FilePresence.PRESENT
    } else {
        FilePresence.ABSENT
    }
}

/** ファイルビューアの状態。[open] が false のときは他のフィールドを見ないこと。 */
data class FileViewerUi(
    val open: Boolean = false,
    val path: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val errorIsAuth: Boolean = false,
    /** 受信結果。**null = まだ来ていない**(「空のファイル」ではない)。 */
    val payload: FileContentPayload? = null,
    /** `type:"text"` のときだけ組む。 */
    val lines: List<FileLine> = emptyList(),
    /** 行数上限([FILE_MAX_LINES])で切ったか。 */
    val renderTruncated: Boolean = false,
    /** 切る前の総行数。 */
    val totalLines: Int = 0,
    /**
     * 「変更あり」トグル(§5b スコープ2)。`FileContent.diff` を持つときだけ押せる。
     * **1.18.21 は `diff` を一度も返さない**(実測)ので、実物ではこのトグルは出ない。
     */
    val showDiff: Boolean = false,
    /**
     * 検索結果 / シンボルから来たときの行番号(**1始まり**)。null = 先頭から。
     * 画面はここへスクロールする。**0 を「先頭」の意味に使わない** ——
     * LSP の行は0始まりで、変換漏れがそのまま「常に1行ずれる」になるため
     * ([symbolHitsOf] が +1 している)。
     */
    val focusLine: Int? = null,
    /**
     * 本文が空だったときだけ意味を持つ。**空でないときは [FilePresence.UNKNOWN] のまま**で、
     * 誰も読まない(問い合わせ自体を撃たない)。
     */
    val presence: FilePresence = FilePresence.UNKNOWN,
) {
    val title: String get() = fileNameOf(path)

    /** `diff` を持っているか。**トグルを出すかどうかの判定はここにしかない。** */
    val hasDiff: Boolean get() = !payload?.diff.isNullOrEmpty()
}

/** **何を描くかの判定はこの関数にしかない。** 画面に `when (payload.type)` を書き戻さない。 */
fun fileBodyKindOf(ui: FileViewerUi): FileBodyKind {
    val payload = ui.payload ?: return FileBodyKind.NOT_LOADED
    return when (payload.type) {
        "text" -> FileBodyKind.TEXT
        "binary" -> FileBodyKind.BINARY
        else -> FileBodyKind.UNKNOWN
    }
}

/** ビューアが出す注記1行。[key] は `content-desc` に載る。 */
data class FileNotice(val key: String, val text: String)

/**
 * ビューアの注記。**0〜3本出る**(受信打ち切り / 行数打ち切り / 本文を描かない理由)。
 *
 * 「黙って切らない」を満たすのはここである。切ったことが見えない省略は、
 * 「ファイルがそこで終わっている」と「表示を切った」の区別を消す
 * —— Q6 の打ち切り注記・Q7 の 5000 行と同じ原則の3回目。
 */
fun fileNoticesOf(ui: FileViewerUi): List<FileNotice> {
    val payload = ui.payload ?: return emptyList()
    val notices = mutableListOf<FileNotice>()
    if (payload.truncated) {
        notices += FileNotice(
            "file-truncated:${payload.receivedBytes}",
            "このファイルは大きすぎるため先頭 ${payload.receivedBytes / 1024} KB のみ受信しています" +
                "(上限 ${FILE_CONTENT_MAX_BYTES / 1024} KB)",
        )
    }
    if (ui.renderTruncated) {
        notices += FileNotice(
            "file-render-truncated:${ui.totalLines}",
            "受信した分のうち先頭 ${ui.lines.size} 行だけを描いています(受信分は全 ${ui.totalLines} 行)",
        )
    }
    when (fileBodyKindOf(ui)) {
        FileBodyKind.BINARY -> notices += FileNotice(
            "file-binary",
            binaryDescription(payload),
        )
        FileBodyKind.UNKNOWN -> notices += FileNotice(
            "file-type-unknown",
            if (payload.truncated) {
                "応答が大きすぎて種別を読み取れませんでした(バイナリとは限りません)"
            } else {
                "サーバーが未知の種別(${payload.type.ifBlank { "空" }})を返しました"
            },
        )
        FileBodyKind.TEXT, FileBodyKind.NOT_LOADED -> Unit
    }
    return notices
}

/**
 * バイナリの1行。**中身は載せない。**
 *
 * `mimeType` が null のとき「不明」と書かない —— 打ち切りで**読めなかった**のか
 * サーバーが**送らなかった**のかは別のことである(実測の並びでは `mimeType` は
 * `content` の後ろに在るので、巨大なバイナリでは必ず読めない)。
 */
internal fun binaryDescription(payload: FileContentPayload): String {
    val mime = when {
        payload.mimeType != null -> payload.mimeType
        payload.truncated -> "MIME種別は応答が大きすぎて読み取れていません"
        else -> "MIME種別をサーバーが返していません"
    }
    return "バイナリファイル(中身は表示しません) / $mime / 受信 ${payload.receivedBytes} バイト"
}

/**
 * 本文から行を組む。**行数上限で切ったことを返り値に含める。**
 *
 * 末尾の改行で空行が1つ増えるのを落とす(`"a\n"` は1行)。
 */
fun buildFileLines(content: String, maxLines: Int = FILE_MAX_LINES): Triple<List<FileLine>, Boolean, Int> {
    if (content.isEmpty()) return Triple(emptyList(), false, 0)
    val raw = content.split('\n')
    val body = if (raw.size > 1 && raw.last().isEmpty()) raw.dropLast(1) else raw
    val total = body.size
    val kept = body.take(maxLines)
    return Triple(
        kept.mapIndexed { index, text -> FileLine(index + 1, text.removeSuffix("\r")) },
        total > maxLines,
        total,
    )
}

// ---------------------------------------------------------------------------
// 検索(スコープ3・4・5)
// ---------------------------------------------------------------------------

/** 検索欄のタブ。 */
enum class FileSearchTab {
    /** `GET /find?pattern=`(全文)。 */
    TEXT,

    /** `GET /find/file?query=`(ファイル名)。 */
    FILES,

    /** `GET /find/symbol?query=`(シンボル)。 */
    SYMBOLS,
}

/** 全文検索の1件。[highlights] は**文字**インデックス(バイトから変換済み)。 */
data class TextSearchHit(
    val path: String,
    val lineNumber: Int,
    val text: String,
    val highlights: List<IntRange>,
) {
    val description: String get() = "find-hit:$path:$lineNumber:${highlights.size}"
}

/** 「ファイル > 行番号 > 該当行」のグループ(§5b Q8 スコープ3)。 */
data class TextSearchGroup(val path: String, val hits: List<TextSearchHit>) {
    val description: String get() = "find-group:$path:${hits.size}"
}

/**
 * `GET /find` の応答をグループへ落とす。
 *
 * ここで3つのことをする。**どれも落とすと画面はどこも壊れて見えない:**
 *
 *  1. `path.text` を [normalizeServerPath] へ通す(`/` と `\` が混ざる)
 *  2. `lines.text` の**末尾の改行を落とす**(実測で必ず付いてくる)
 *  3. `submatches` の**バイトオフセットを文字インデックスへ変換**する
 *     ([byteRangeToCharRange])—— 落とすと**日本語を含む行だけがずれて塗られる**
 *
 * サーバーは同じファイルの複数行を連続して返すが、**順序を仮定しない**(パスでまとめ直す)。
 */
fun groupTextMatches(matches: List<FindMatchDto>): List<TextSearchGroup> {
    val order = mutableListOf<String>()
    val byPath = linkedMapOf<String, MutableList<TextSearchHit>>()
    for (match in matches) {
        val path = normalizeServerPath(match.path.text)
        val text = match.lines.text.removeSuffix("\n").removeSuffix("\r")
        val highlights = match.submatches.mapNotNull { byteRangeToCharRange(text, it.start, it.end) }
        if (path !in byPath) {
            byPath[path] = mutableListOf()
            order += path
        }
        byPath.getValue(path) += TextSearchHit(path, match.lineNumber, text, highlights)
    }
    return order.map { TextSearchGroup(it, byPath.getValue(it)) }
}

/** シンボル検索の1件。 */
data class SymbolHit(
    val name: String,
    /** LSP の `SymbolKind`(整数)。spec は enum を持たないので**名前に落とさない**。 */
    val kind: Int,
    val path: String,
    /** 1始まりの行番号(LSP の `range.start.line` は**0始まり**)。 */
    val line: Int,
) {
    val description: String get() = "symbol-hit:$path:$line:$kind"
}

/**
 * `Symbol[]` を落とす。`location.uri` は `file://` 形式(LSP の規約)。
 *
 * **`line` は +1 する。** LSP の行は0始まり、ファイルビューアの行番号は1始まりで、
 * 揃えないと**常に1行ずれた場所へ飛ぶ**。1行のずれは「だいたい合っている」ので気付きにくい。
 */
fun symbolHitsOf(symbols: List<SymbolDto>): List<SymbolHit> = symbols.map { dto ->
    SymbolHit(
        name = dto.name,
        kind = dto.kind,
        path = pathFromFileUri(dto.location.uri),
        line = dto.location.range.start.line + 1,
    )
}

/**
 * `file:///E:/x/y.kt` → `E:/x/y.kt`。**`file://` でなければそのまま返す**
 * (サーバーが別の形を返しても、そのまま出すほうが「空」より読める)。
 */
fun pathFromFileUri(uri: String): String {
    if (!uri.startsWith("file://")) return normalizeServerPath(uri)
    val rest = uri.removePrefix("file://").removePrefix("/")
    val decoded = try {
        java.net.URLDecoder.decode(rest, "UTF-8")
    } catch (_: IllegalArgumentException) {
        rest
    }
    return normalizeServerPath(decoded)
}

/**
 * シンボル索引が使えるか。**「見つからない」と「索引が無い」を分ける唯一の場所**
 * (§5b Q8 スコープ5)。
 *
 * 1回の応答(200 + `[]`)からは区別が付かないので、Controller が
 * **校正クエリ**をもう1本撃った結果をここへ入れる。
 */
enum class SymbolIndexState {
    /** まだ判定していない(検索していない/結果が非空だったので判定不要)。 */
    UNKNOWN,

    /** 校正クエリが非0を返した = 索引は在る。ユーザーの語が当たらなかっただけ。 */
    AVAILABLE,

    /** 校正クエリも0だった = 索引が使えない。**実物 serve はここに落ちる**(LSP 無し)。 */
    UNAVAILABLE,
}

/** 検索欄の状態。3タブぶんを1つに持つ(タブを跨いでも語を保つ)。 */
data class FileSearchUi(
    val tab: FileSearchTab = FileSearchTab.TEXT,
    /** 入力中の語。**これで通信しない**(デバウンス後の [submittedQuery] で撃つ)。 */
    val query: String = "",
    /** 実際に撃った語。結果はこれに対応する。 */
    val submittedQuery: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val errorIsAuth: Boolean = false,
    val textGroups: List<TextSearchGroup> = emptyList(),
    val files: List<String> = emptyList(),
    val symbols: List<SymbolHit> = emptyList(),
    val symbolIndex: SymbolIndexState = SymbolIndexState.UNKNOWN,
    /** 一度でも撃ったか。**撃つ前の0件を「該当なし」と描かない**ため。 */
    val searched: Boolean = false,
) {
    val textHitCount: Int get() = textGroups.sumOf { it.hits.size }

    /**
     * サーバーの上限に張り付いたか。**張り付いたら「これ以上あるかもしれない」を出す。**
     * `GET /find` は10件で固定([FIND_SERVER_CAP])、`GET /find/file` は送った `limit`。
     */
    val textCapped: Boolean get() = textHitCount >= FIND_SERVER_CAP
    val filesCapped: Boolean get() = files.size >= FIND_FILE_LIMIT
}

/**
 * `ignored` トグルの**見えるラベル**。
 *
 * ## なぜ純関数に出したか(変異 N4)
 *
 * [FileTreeUi] の KDoc は「隠すこと自体は『無い』ではないので**トグルと件数を必ず出す**」と
 * 主張していたが、1周目はラベルが `IgnoredToggle` の中に素で組まれており、
 * **件数を落とす変異が775件全緑で通り抜けた**。`content-desc`
 * (`file-ignored-toggle:off:1`)には件数が載っていたので dump では見えたが、
 * **人が読む文字からは消えていた** —— desc は judge のための計器であって、
 * 画面に出ている言葉の代わりではない。
 */
fun ignoredToggleLabel(tree: FileTreeUi): String =
    if (tree.showIgnored) {
        "ignored を表示中(${tree.ignoredCount} 件)"
    } else {
        "ignored ${tree.ignoredCount} 件を隠しています"
    }

/**
 * 「この行へ送る」の**行番号 → LazyColumn の添字**(0始まり)。
 *
 * ## なぜ純関数に出したか(変異 N6)
 *
 * 1周目はこの変換が `FileLineList` の `LaunchedEffect` の中に
 * `listState.scrollToItem((line - 1).coerceIn(0, size - 1))` と素で書かれていた。
 * [FileViewerUi.focusLine] の KDoc は「**1始まり。0 を『先頭』の意味に使わない**」と
 * 主張していたのに、`- 1` を外す変異は **775件全緑で通り抜けた** ——
 * `LaunchedEffect` の中のスクロール位置は Compose UI テストから assert できない。
 *
 * **KDoc が主張する区別には検出器を置く**(Q6 の「KDoc の虚偽」)。
 * 変換をここへ出せば、`runTest` も要らない素の関数として固定できる。
 *
 * @return 送る先の添字。**null は「送らない」**(先頭のまま)。
 */
fun focusIndexOf(focusLine: Int?, lineCount: Int): Int? {
    if (focusLine == null || lineCount <= 0) return null
    return (focusLine - 1).coerceIn(0, lineCount - 1)
}

/**
 * 画面の本体が**ツリーか検索結果か**(レビュー minor-4)。
 *
 * 1周目はこの判定が `FileBrowserScreen` の中に `if (ui.search.query.isBlank())` として
 * 素で書かれていた。**このファイルの冒頭が禁じている形そのもの**である ——
 * 配線に条件を置くと、その条件を消す変異が全緑で通り抜ける
 * (Q1〜Q7 で6度示され、Q6 レビューの blocker がまさに「引数の組み立てが Compose の中に残っていた」だった)。
 *
 * **状態オブジェクトごと受け取る**([sessionListBody] / [chatBody] と同じ形)。
 * `(String)` を受け取ると、呼び出し側が `ui.submittedQuery` を渡す変異が
 * この関数のテストからは見えない。
 */
enum class FileBrowserPane {
    /** ツリー(パンくず + 一覧)。 */
    TREE,

    /** 検索結果(3タブ)。 */
    SEARCH,
}

/** **どちらを描くかの判定はここにしかない。** 画面に `if` を書き戻さないこと。 */
fun fileBrowserPane(search: FileSearchUi): FileBrowserPane =
    if (search.query.isBlank()) FileBrowserPane.TREE else FileBrowserPane.SEARCH

/** 上限に張り付いたときの1行(タブごとに件数が違うので関数にする)。 */
fun searchCapNotice(ui: FileSearchUi): FileNotice? = when {
    ui.tab == FileSearchTab.TEXT && ui.textCapped -> FileNotice(
        "find-capped:$FIND_SERVER_CAP",
        "サーバーが $FIND_SERVER_CAP 件で打ち切ります(件数を増やす口がありません)。" +
            "語を足して絞り込んでください",
    )
    ui.tab == FileSearchTab.FILES && ui.filesCapped -> FileNotice(
        "find-file-capped:$FIND_FILE_LIMIT",
        "上限 $FIND_FILE_LIMIT 件まで表示しています。語を足して絞り込んでください",
    )
    else -> null
}
