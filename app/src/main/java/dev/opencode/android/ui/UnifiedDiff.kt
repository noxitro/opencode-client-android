package dev.opencode.android.ui

/**
 * unified diff(git patch)のパーサ(QUALITY_PLAN §5b Q7 スコープ1)。
 *
 * ## なぜアプリ側で書くのか
 *
 * `SnapshotFileDiff.patch` / `VcsFileDiff.patch` は**unified diff の文字列**であって
 * 構造化された行の配列ではない(実機 `/doc` の schema: `patch: {type: string}`)。
 * サーバーは行番号も種別も返さないので、色分けと2列の行番号はアプリが作る。
 *
 * ## 実データで書いたこと
 *
 * このファイルの形は**実物 serve 1.18.21 の `GET /vcs/diff?mode=git` の応答**から採った
 * (採取手順と生の応答は `docs/API_CONTRACT.md` §Q7)。このプロジェクトは
 * 「フィクスチャが実データと違う形だったのでテストは全緑のまま症状が残る」を**7回**
 * 繰り返している(P3 の role / P4 の metadata / P4 の PermissionReplied / L3 の error など)。
 * 近似した文字列を流さないために、テスト([dev.opencode.android.Q7DiffParserTest])は
 * サーバーが返したバイト列をそのまま貼ってある。
 *
 * 実データから確定した形(推測ではない):
 *
 *  - `patch` は **`diff --git a/x b/x` から始まる完全な git patch** である。ハンクだけではない
 *  - 新規ファイルは `new file mode` + `--- /dev/null`、削除は `deleted file mode` + `+++ /dev/null`
 *  - バイナリは `Binary files a/x and b/x differ` の**1行だけ**でハンクが無い
 *  - 末尾改行なしは `\ No newline at end of file` が**直前の行に掛かる**
 *  - ハンクヘッダは `@@ -a,b +c,d @@` だが、**`context=0` では `@@ -2 +2 @@ def f1():` のように
 *    件数が省略され(=1)、後ろに関数名の見出しが付く**。両方とも実測で観測している
 *
 * ## 打ち切り
 *
 * 5000行を超えるファイルは [DIFF_MAX_LINES] で切り、[ParsedFileDiff.truncated] を立てる。
 * **黙って切らない**(Q0 の R1 / Q4 の除外件数表示で確立した原則)—— 画面には
 * 「大きすぎるため先頭N行のみ」と出す。切ったことが見えない省略は、
 * 「差分が無い」と「出さないことにした」の区別を消す。
 */

/** 1行の種別。 */
enum class DiffLineKind { CONTEXT, ADD, DELETE }

/**
 * 差分の1行。
 *
 * [oldNumber] / [newNumber] は**その行が存在する側にだけ入る**(追加行に旧行番号は無い)。
 * [noNewlineAtEof] は `\ No newline at end of file` が**この行に掛かっている**ことを表す。
 * マーカー自体は行として持たない —— 持つと行番号が1つずれる。
 */
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldNumber: Int?,
    val newNumber: Int?,
    val noNewlineAtEof: Boolean = false,
)

/**
 * ハンク1つ。[heading] は `@@ ... @@` の**後ろ**に git が付ける文脈(関数名など)で、
 * 無いことのほうが多い。
 */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val heading: String?,
    val lines: List<DiffLine>,
) {
    /** dump とテストから引用できる見出し文字列。`@@ -1,5 +1,6 @@` を再構成する。 */
    val header: String
        get() = buildString {
            append("@@ -")
            append(rangeText(oldStart, oldCount))
            append(" +")
            append(rangeText(newStart, newCount))
            append(" @@")
            if (!heading.isNullOrEmpty()) {
                append(' ')
                append(heading)
            }
        }

    private fun rangeText(start: Int, count: Int): String =
        if (count == 1) "$start" else "$start,$count"
}

/** ファイル1つ分の差分。 */
data class ParsedFileDiff(
    /** `--- a/x` 側のパス。新規ファイルは null(`/dev/null`)。 */
    val oldPath: String?,
    /** `+++ b/x` 側のパス。削除ファイルは null(`/dev/null`)。 */
    val newPath: String?,
    val hunks: List<DiffHunk>,
    /**
     * バイナリファイル。**ハンクは無い**が「差分が無い」ではない ——
     * 画面は「バイナリファイル(表示できません)」と出すこと。
     */
    val binary: Boolean = false,
    /** [DIFF_MAX_LINES] で打ち切ったか。 */
    val truncated: Boolean = false,
    /** 打ち切り前の総行数(打ち切っていなければ描画行数と同じ)。 */
    val totalLines: Int = 0,
) {
    /** 表示に使うパス。新規なら新パス、削除なら旧パス。 */
    val displayPath: String?
        get() = newPath ?: oldPath

    val addedLines: Int
        get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.ADD } }

    val deletedLines: Int
        get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.DELETE } }

    /** 実際に描画される行数(打ち切り後)。 */
    val renderedLines: Int
        get() = hunks.sumOf { it.lines.size }
}

/**
 * 1ファイルあたりに描く差分行の上限(§5b Q7 リスク欄)。
 *
 * 超えたぶんは捨てるのではなく **[ParsedFileDiff.truncated] と [ParsedFileDiff.totalLines] で
 * 「切った」ことを画面に出す**。数値は「数千行で LazyColumn が詰まる」という
 * 計画書のリスク記述に合わせた閾値で、**実測ではなく判断で選んだ**
 * (Q3 の `CHAT_CARD_AREA_MAX_FRACTION` と同じ性質。ここに書いておく)。
 */
const val DIFF_MAX_LINES = 5000

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?: ?(.*))?$""")

private const val NO_NEWLINE_MARKER = "\\ No newline at end of file"

/**
 * git patch を解析する。**複数ファイルを含む patch にも耐える**
 * (`GET /vcs/diff/raw` と Q8 のファイル差分で同じ関数を使えるようにするため。
 * `VcsFileDiff.patch` は1ファイルなので、その場合は要素1のリストが返る)。
 *
 * 解釈できない行は**捨てる**。`index 0000000..ce2b18a` や `new file mode 100644` は
 * 表示に要らないが、**未知の行が来たら落ちる**パーサは実サーバーの語彙が広がった瞬間に
 * 画面を空にする —— L3 の `jsonPrimitive` と同じ壊れ方なので、そうしない。
 *
 * @param maxLines 1ファイルあたりの描画行数上限。テストは小さくして打ち切りを測る。
 */
fun parseUnifiedDiff(patch: String?, maxLines: Int = DIFF_MAX_LINES): List<ParsedFileDiff> {
    if (patch.isNullOrEmpty()) return emptyList()
    val raw = patch.split('\n')
    // 末尾の '\n' が生む空要素だけを落とす。**行の途中の空行は落とさない**
    // (unified diff の空の文脈行は " " だが、末尾改行の扱いで "" になることがある)。
    val lines = if (raw.isNotEmpty() && raw.last().isEmpty()) raw.dropLast(1) else raw

    val files = mutableListOf<ParsedFileDiff>()
    var builder: FileBuilder? = null

    fun flush() {
        builder?.let { files.add(it.build()) }
        builder = null
    }

    for (line in lines) {
        if (line.startsWith("diff --git ")) {
            flush()
            builder = FileBuilder(maxLines).also { it.applyDiffGitHeader(line) }
            continue
        }
        val b = builder ?: FileBuilder(maxLines).also { builder = it }
        when {
            // **ヘッダの判定は「ハンクの外にいるとき」だけ行う。**
            // 差分そのものを差分にかけると、本文に `+++ foo` や `--- foo` が現れる
            // (`++ foo` を追加した行は `+++ foo` になる)。ハンクの中でこれをヘッダと
            // 読むと、そこから先の行が丸ごと消える —— 画面は「差分が短い」だけに見える。
            !b.inHunk && (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) ->
                // ハンクを持たないので、ここで「バイナリである」とだけ記録する。
                b.binary = true

            !b.inHunk && line.startsWith("--- ") -> b.oldPath = parsePathHeader(line.removePrefix("--- "))

            !b.inHunk && line.startsWith("+++ ") -> b.newPath = parsePathHeader(line.removePrefix("+++ "))

            // マッチしない `@@` 始まりは本文の一部ではないので捨てる。
            line.startsWith("@@") -> HUNK_HEADER.find(line)?.let { b.startHunk(it) }

            line == NO_NEWLINE_MARKER -> b.markNoNewline()

            b.inHunk && line.startsWith("+") -> b.addLine(DiffLineKind.ADD, line.substring(1))
            b.inHunk && line.startsWith("-") -> b.addLine(DiffLineKind.DELETE, line.substring(1))
            b.inHunk && line.startsWith(" ") -> b.addLine(DiffLineKind.CONTEXT, line.substring(1))
            // ハンクの中の完全な空行は「空の文脈行」。git は " " を書くが、
            // 末尾処理で "" になった patch を受け取ることがある。
            b.inHunk && line.isEmpty() -> b.addLine(DiffLineKind.CONTEXT, "")

            // `index ...` / `new file mode ...` / `similarity index ...` 等は表示に使わない。
            else -> Unit
        }
    }
    flush()
    return files.filter { it.hunks.isNotEmpty() || it.binary || it.displayPath != null }
}

/**
 * `--- a/x` / `+++ b/x` / `--- /dev/null` のパス部分を取り出す。
 *
 * git はタブ区切りでタイムスタンプを付けることがあるので、**最初のタブで切る**。
 * `a/` `b/` の接頭辞は `--no-prefix` で付かないこともあるため、**あれば剥がす**。
 */
private fun parsePathHeader(value: String): String? {
    val path = value.substringBefore('\t').trim()
    if (path == "/dev/null" || path.isEmpty()) return null
    return when {
        path.startsWith("a/") -> path.removePrefix("a/")
        path.startsWith("b/") -> path.removePrefix("b/")
        else -> path
    }
}

/** 1ファイル分の組み立て。行番号の採番だけが状態で、それ以外は素直な追記。 */
private class FileBuilder(private val maxLines: Int) {
    var oldPath: String? = null
    var newPath: String? = null
    var binary: Boolean = false
    var inHunk: Boolean = false
        private set

    private val hunks = mutableListOf<DiffHunk>()
    private var current: MutableList<DiffLine>? = null
    private var oldStart = 0
    private var oldCount = 0
    private var newStart = 0
    private var newCount = 0
    private var heading: String? = null
    private var oldNo = 0
    private var newNo = 0

    /** 打ち切り前の総行数。**切った後も本当の行数を覚えている**(画面に出すため)。 */
    private var total = 0

    fun applyDiffGitHeader(line: String) {
        // `diff --git a/x b/x`。パスに空白が含まれるとここでは決められないので、
        // 権威は `---` / `+++` に置く。ここは**両方が欠けている場合の保険**にとどめる。
        val rest = line.removePrefix("diff --git ")
        val parts = rest.split(" b/", limit = 2)
        if (parts.size == 2) {
            oldPath = parts[0].removePrefix("a/").takeIf { it.isNotEmpty() }
            newPath = parts[1].takeIf { it.isNotEmpty() }
        }
    }

    fun startHunk(match: MatchResult) {
        closeHunk()
        oldStart = match.groupValues[1].toIntOrNull() ?: 0
        oldCount = match.groupValues[2].toIntOrNull() ?: 1
        newStart = match.groupValues[3].toIntOrNull() ?: 0
        newCount = match.groupValues[4].toIntOrNull() ?: 1
        heading = match.groupValues.getOrNull(5)?.takeIf { it.isNotEmpty() }
        oldNo = oldStart
        newNo = newStart
        current = mutableListOf()
        inHunk = true
    }

    fun addLine(kind: DiffLineKind, text: String) {
        total++
        val list = current ?: return
        if (total > maxLines) return
        val line = when (kind) {
            DiffLineKind.CONTEXT -> DiffLine(kind, text, oldNo++, newNo++)
            DiffLineKind.ADD -> DiffLine(kind, text, null, newNo++)
            DiffLineKind.DELETE -> DiffLine(kind, text, oldNo++, null)
        }
        list.add(line)
    }

    /**
     * `\ No newline at end of file` は**直前の行に掛かる**。
     * マーカーを行として持たないのは、持つと行番号が1つずれるからである。
     */
    fun markNoNewline() {
        val list = current ?: return
        val last = list.lastOrNull() ?: return
        list[list.size - 1] = last.copy(noNewlineAtEof = true)
    }

    private fun closeHunk() {
        val list = current ?: return
        if (list.isNotEmpty()) {
            hunks.add(DiffHunk(oldStart, oldCount, newStart, newCount, heading, list.toList()))
        }
        current = null
        inHunk = false
    }

    fun build(): ParsedFileDiff {
        closeHunk()
        return ParsedFileDiff(
            oldPath = oldPath,
            newPath = newPath,
            hunks = hunks.toList(),
            binary = binary,
            truncated = total > maxLines,
            totalLines = total,
        )
    }
}
