package dev.opencode.android.data

import java.nio.charset.StandardCharsets

/**
 * `GET /file/content` の**受信打ち切り**と、切れた JSON からの救出(QUALITY_PLAN §5b Q8 のリスク欄)。
 *
 * ## なぜ打ち切りが要るのか
 *
 * spec にも応答にもサイズの上限が無く、`content` は**ファイル全文が1本の JSON 文字列**で来る。
 * [OpenCodeApi.call] は `resp.body.string()` で全部をメモリへ載せるので、
 * 数十MBのファイルを開くと素直に OOM する。計画書のリスク欄が名指ししているのがこれで、
 * 要求は「受信サイズを閾値で打ち切り、**大きすぎるため先頭N KBのみ**と明示する」である。
 *
 * ## なぜ「切ったら諦める」ではないのか
 *
 * 打ち切ると JSON として壊れる。そこで「大きすぎるので表示できません」と言うのは簡単だが、
 * 計画書が求めているのは**先頭N KBを出すこと**である。**読めるところまでは読ませる。**
 *
 * ## 何を失うか(「無い」と「取れなかった」の区別)
 *
 * 実測の並びは `{"type":..,"content":..,"encoding":..,"mimeType":..}` なので、
 * **打ち切ると `content` より後ろのキーが失われる**。巨大なバイナリでは `mimeType` が読めない。
 * そのとき [FileContentPayload.mimeType] は null になるが、[FileContentPayload.truncated] が
 * true なので、画面は「不明」ではなく「**応答が大きすぎて読めていません**」と言える。
 * これは Q0 の R1(空バブル)以来このリポジトリが6回閉じてきた原則の7回目である。
 *
 * ## 救出は純関数にする
 *
 * [salvageTruncatedFileContent] は文字列 → 文字列で、HTTP も Android も要らない。
 * 「巨大なファイルを実際に送りつける」ことなく、**切れ方**をテストで固定できる
 * (途中の `\u` エスケープ、途中のサロゲート、`content` キーに届く前の打ち切り)。
 */

/**
 * `GET /file/content` の受信上限(バイト)。**512 KiB = 524,288。**
 *
 * ## 閾値は「ファイルサイズ」ではなく「**応答サイズ**」に効く(レビュー minor-2)
 *
 * 1周目の doc は「`docs/spec/opencode-1.18.21-openapi.json` は 478 KB なので閾値の内側
 * (切らずに読める)」と書いていた。**これは誤りである。** ディスク上のバイト数と
 * `GET /file/content` の応答バイト数は違う —— 本文が JSON 文字列へエスケープされるためで、
 * 実測(2026-08-28、実物 serve 1.18.21):
 *
 * ```
 * docs/spec/opencode-1.18.21-openapi.json   ディスク 478,747 → 応答 553,653 バイト(1.157倍)
 * ui/ChatController.kt                      ディスク  69,143 → 応答  71,771 バイト(1.038倍)
 * ui/ChatScreen.kt                          ディスク  52,909 → 応答  55,199 バイト(1.043倍)
 * ```
 *
 * したがって **`openapi.json` は 553,653 > 524,288 で、このアプリでは実際に打ち切られる。**
 * 「切らずに読める」と書いていたのは**選んだ値の根拠が間違っていた**ということである。
 *
 * ## それでも 512 KiB を選ぶ理由(正しい根拠)
 *
 * 端末で**読む**対象はソースコードである。本リポジトリで最大のソース
 * `ChatController.kt`(1,281 行)でも応答は **71,771 バイト = 閾値の約 1/7** で、
 * よくあるファイルは十分に内側へ収まる。閾値を超えるのは
 * `openapi.json` のような**機械が生成した data ファイル**で、それは端末で読む物ではない ——
 * そして**超えたら黙らず「先頭 N KB のみ」と言う**([FileContentPayload.truncated])。
 *
 * 上げる方向の代償は素直にヒープ、下げる方向の代償は「よくあるソースが切れる」。
 * **実測メモリは `e2e-artifacts/Q8/GATES.txt` の [R1] に記録した**
 * (レビュー minor-3: 1周目は「TEST_REPORT の Q8 節」と書いていたが、**そんな節は無い**)。
 */
const val FILE_CONTENT_MAX_BYTES: Int = 512 * 1024

/**
 * `GET /find` がサーバー側で返す**固定の上限件数**(実測: どんな語でも10件)。
 *
 * spec に `limit` パラメータが無いので**アプリからは増やせない**。
 * ちょうどこの件数返ったときは「これ以上あるかもしれない」を画面に出す。
 */
const val FIND_SERVER_CAP: Int = 10

/**
 * `GET /find/file?limit=` に送る値。**必ず送る**(§5b Q8 スコープ4 の要求)。
 *
 * spec の上限は 200(超えると 400。実測)。100 にしてあるのは、
 * ファイル名検索は「候補を絞るための道具」であって全件列挙の道具ではないためで、
 * 100件出て足りないなら**語を足すほうが速い**。上限に張り付いたら画面にそう出す。
 */
const val FIND_FILE_LIMIT: Int = 100

/**
 * `GET /find/symbol` が空を返したときに撃つ**校正クエリ**。
 *
 * §5b Q8 スコープ5:「空を『無い』と描かず『シンボル索引が使えない』と区別できる表示にする」。
 * 1回の応答(200 + `[]`)からは区別が付かないので、**もう1回撃って区別を作る**。
 * 索引が在るなら1文字の語は何かに当たる。当たらなければ索引が無い。
 *
 * **陽性側(索引が在る)はこの環境では測れない**(LSP が動く serve が無い)。
 * 陰性側は実物で測れている(どの語でも `[]`)。機構は両側ともスタブで出す。
 */
const val SYMBOL_INDEX_PROBE: String = "a"

/**
 * `GET /file/content` の中身。**DTO ではなく「受信の結果」**なので、
 * サーバーが送った物([FileContentDto])に加えて**こちらが切ったかどうか**を持つ。
 */
data class FileContentPayload(
    /** `"text"` | `"binary"`。打ち切りでここすら読めなければ空文字。 */
    val type: String,
    val content: String,
    /** unified diff の文字列。**1.18.21 は返さない**(実測)。 */
    val diff: String? = null,
    /** `"base64"`。 */
    val encoding: String? = null,
    val mimeType: String? = null,
    /** **こちらが受信を打ち切った。** サーバーが途中までしか送らなかった、ではない。 */
    val truncated: Boolean = false,
    /** 実際に読んだバイト数。打ち切りの注記に出す。 */
    val receivedBytes: Int = 0,
) {
    val isBinary: Boolean get() = type == "binary"
    val isText: Boolean get() = type == "text"
}

/**
 * 受信したバイト列を [FileContentPayload] にする。
 *
 * [truncated] が false なら普通に JSON をデコードする。true なら壊れているので
 * [salvageTruncatedFileContent] へ回す。**どちらの経路でも [FileContentPayload.receivedBytes] は
 * 実際に読んだバイト数**で、`content` の文字数ではない(base64 と UTF-8 で比が違う)。
 *
 * @throws kotlinx.serialization.SerializationException 打ち切っていないのに JSON として読めない場合。
 *   呼び出し側([OpenCodeApi])が `ApiError.Network` に変える。
 */
fun decodeFileContent(bytes: ByteArray, truncated: Boolean): FileContentPayload {
    val text = String(bytes, StandardCharsets.UTF_8)
    if (!truncated) {
        val dto = contractJson.decodeFromString(FileContentDto.serializer(), text)
        return FileContentPayload(
            type = dto.type,
            content = dto.content,
            diff = dto.diff,
            encoding = dto.encoding,
            mimeType = dto.mimeType,
            truncated = false,
            receivedBytes = bytes.size,
        )
    }
    return salvageTruncatedFileContent(text, bytes.size)
}

/**
 * **途中で切れた JSON から読めるところまでを救い出す。**
 *
 * 実測の並びは `{"type":"text","content":"..."}` なので、`type` は必ず読めて
 * `content` は途中まで読める。`encoding` / `mimeType` は `content` の後ろにあるため
 * **巨大なファイルでは読めない**(それが分かるように [FileContentPayload.truncated] を立てる)。
 *
 * 途中で切れた `\uXXXX` や `\` 単独は**捨てる** —— 中途半端に復元すると
 * 「ファイルにその文字が在った」ように見える。末尾の U+FFFD(UTF-8 の多バイト文字が
 * バイト境界で割れたときに [String] が置く文字)も落とす。
 *
 * キーの探索は最初の一致を採る。`content` の中に `"mimeType":"` という文字列を含む
 * ファイルを読むと誤検出しうるが、**`content` より前に在るキーしか読まない**設計なので、
 * 誤検出した値が `content` の前に現れることは無い。
 */
internal fun salvageTruncatedFileContent(text: String, receivedBytes: Int): FileContentPayload {
    val contentStart = markerEnd(text, "content")
    val content = if (contentStart < 0) "" else decodeJsonStringFrom(text, contentStart).dropTrailingReplacement()
    // `content` より前に現れたキーだけを読む。後ろのキーは失われている。
    val limit = if (contentStart < 0) text.length else contentStart
    val head = text.substring(0, limit)
    return FileContentPayload(
        type = readCompleteString(head, "type").orEmpty(),
        content = content,
        diff = readCompleteString(head, "diff"),
        encoding = readCompleteString(head, "encoding"),
        mimeType = readCompleteString(head, "mimeType"),
        truncated = true,
        receivedBytes = receivedBytes,
    )
}

/** `"key":"` の直後の位置。見つからなければ -1。 */
private fun markerEnd(text: String, key: String): Int {
    val marker = "\"" + key + "\":\""
    val at = text.indexOf(marker)
    return if (at < 0) -1 else at + marker.length
}

/** キーの値を**閉じ引用符まで読めたときだけ**返す。途中で切れていたら null。 */
private fun readCompleteString(text: String, key: String): String? {
    val start = markerEnd(text, key)
    if (start < 0) return null
    val end = findClosingQuote(text, start)
    return if (end < 0) null else decodeJsonStringFrom(text, start)
}

/** エスケープを考慮して閉じ引用符の位置を探す。見つからなければ -1。 */
private fun findClosingQuote(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            '"' -> return i
            else -> i++
        }
    }
    return -1
}

/**
 * JSON 文字列の中身を [start] から復号する。閉じ引用符か文字列の末尾で止まる。
 *
 * **途中で切れたエスケープは捨てる。** `\` で終わる / `\u12` で終わる場合、
 * そこまでに積んだ分だけを返す。
 */
private fun decodeJsonStringFrom(text: String, start: Int): String {
    val out = StringBuilder()
    var i = start
    while (i < text.length) {
        val c = text[i]
        if (c == '"') return out.toString()
        if (c != '\\') {
            out.append(c)
            i++
            continue
        }
        if (i + 1 >= text.length) return out.toString()
        when (val escape = text[i + 1]) {
            '"' -> { out.append('"'); i += 2 }
            '\\' -> { out.append('\\'); i += 2 }
            '/' -> { out.append('/'); i += 2 }
            'b' -> { out.append('\b'); i += 2 }
            'f' -> { out.append('\u000C'); i += 2 }
            'n' -> { out.append('\n'); i += 2 }
            'r' -> { out.append('\r'); i += 2 }
            't' -> { out.append('\t'); i += 2 }
            'u' -> {
                if (i + 6 > text.length) return out.toString()
                val code = text.substring(i + 2, i + 6).toIntOrNull(16) ?: return out.toString()
                out.append(code.toChar())
                i += 6
            }
            // 未知のエスケープは**その文字をそのまま**出す(JSON としては不正だが、
            // ここは救出であって検証ではない)。
            else -> { out.append(escape); i += 2 }
        }
    }
    return out.toString()
}

/**
 * 末尾の U+FFFD を**1つだけ**落とす。
 *
 * **バイト境界で割れた多バイト文字**の名残であって、ファイルに在った文字ではない。
 * 割れうるのは**切断点にまたがる1文字だけ**なので、落とすのも1つだけである。
 *
 * 1周目は `trimEnd('\uFFFD')` で**末尾の U+FFFD を全部**落としていた(レビュー minor-5)。
 * U+FFFD が並んで終わるファイル(壊れたエンコーディングのテキスト、置換文字を含むログ)は
 * **本当に在る**ので、それを黙って削るのは「読めない」と「取れなかった」の区別を消す形になる ——
 * この段が [dev.opencode.android.ui.fileViewerEmptyState] で閉じた原則の同型である。
 */
private fun String.dropTrailingReplacement(): String =
    if (endsWith('\uFFFD')) dropLast(1) else this
