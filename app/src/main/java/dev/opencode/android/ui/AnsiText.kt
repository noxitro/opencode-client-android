package dev.opencode.android.ui

import dev.opencode.android.ui.theme.WCAG_AA_NORMAL_TEXT
import dev.opencode.android.ui.theme.contrastRatio
import dev.opencode.android.ui.theme.relativeLuminance

/**
 * PTY 出力の**ANSI 解釈**(QUALITY_PLAN §5b Q9 スコープ2)。
 *
 * ## 何を作るのか / 何を作らないのか
 *
 * 計画書の要求は「**完全な端末エミュレータは作らない。SGR(色・太字)だけ解釈し、
 * カーソル移動・画面消去・代替画面は解釈せずに落とす**」であり、その帰結
 * (`vim` / `top` が動かないこと)を**制限としてUIに明示する**ことである。
 * ここはその「解釈する側」と「落とす側」の両方を実装する。
 *
 * ## 落とした物を**数える**(このファイルの設計上の要点)
 *
 * 落とすだけなら簡単だが、それでは画面が
 * 「そもそも制御シーケンスが来ていない」と「**来たが解釈しなかった**」を区別できない。
 * これはこのリポジトリが Q0(空バブル)以来7度閉じてきた「無い/取れなかった」そのものなので、
 * [TerminalBuffer.dropped] に**種別ごとの件数**を積む。画面はそれを注記に出す
 * ([dev.opencode.android.ui.terminalNotices])。
 *
 * ## Windows の既定シェルは起動直後から全画面制御を出す(実測)
 *
 * 実測(2026-08-30、実物 serve 1.18.21、`cmd.exe`)—— 接続直後の最初のフレーム:
 *
 * ```
 * \u001b[?9001h\u001b[?1004h\u001b[?25l\u001b[2J\u001b[m\u001b[H
 * Microsoft Windows [Version 10.0.26200.9168]\u001b]0;C:\Windows\system32\cmd.exe\u0007…
 * ```
 *
 * `[2J`(画面消去)・`[H`(原点)・`[?25l`(カーソル非表示)・OSC 0(タイトル設定)が
 * **1行目から来る**。つまり「全画面制御を検出したら警告を出す」方式は
 * **Windows では常に警告が出る**ことになり、警告として機能しない。
 * だから警告ではなく**常設の制限表示 + 落とした件数**にしてある。
 *
 * ## 途中で切れたエスケープを持ち越す
 *
 * サーバーは最大 65536 バイト単位でフレームを切るので、`ESC [ 3` までで
 * フレームが終わることがある。持ち越さないと**残りの `1m` が本文として画面に出る**。
 * [TerminalBuffer.pending] がその持ち越しで、`\r` がフレーム末尾に来た場合も同じ理由で持ち越す
 * (`\r\n` が2フレームに割れると、空行が1本増える)。
 */

/** SGR の色。**添字と RGB を混ぜない** —— 256色の添字を RGB として描くと全部灰になる。 */
sealed interface AnsiColor {
    /** `30-37` / `90-97` / `38;5;n` の 0..255。 */
    data class Indexed(val index: Int) : AnsiColor

    /** `38;2;r;g;b`。 */
    data class Rgb(val r: Int, val g: Int, val b: Int) : AnsiColor
}

/** 1つの見た目。**既定は「サーバーが何も言っていない」**であって黒でも白でもない。 */
data class AnsiStyle(
    val fg: AnsiColor? = null,
    val bg: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** `SGR 7`。前景と背景を入れ替えて描く。 */
    val inverse: Boolean = false,
) {
    companion object {
        val DEFAULT = AnsiStyle()
    }
}

/** 同じ見た目で連続する文字。 */
data class AnsiSpan(val text: String, val style: AnsiStyle)

/** 1行。 */
data class AnsiLine(val spans: List<AnsiSpan>) {
    /** 行の素のテキスト。`content-desc` とテストが使う。 */
    val text: String get() = spans.joinToString("") { it.text }
}

/**
 * 落とした制御シーケンスの種別。**「その他」に丸めない** ——
 * 画面消去だけが来ているのか、代替画面へ入ろうとしたのかは、
 * ユーザーが「今なぜ画面が壊れて見えるのか」を判断する材料が違う。
 */
enum class AnsiControlKind {
    /** カーソル移動(`H` `A` `B` `C` `D` `G` `d` ほか)。 */
    CURSOR,

    /** 画面/行の消去(`J` `K` `X`)。 */
    ERASE,

    /** 代替画面(`?1049h/l` `?47h/l` `?1047h/l`)。**`vim` / `top` はここから入る。** */
    ALT_SCREEN,

    /** その他のモード設定(`h` / `l`)。カーソル表示 `?25l` などが来る。 */
    MODE,

    /** OSC(`ESC ]` … BEL)。ウィンドウタイトル設定など。 */
    OSC,

    /** 上のどれでもないエスケープ、および解釈しない C0 制御文字。 */
    OTHER,
}

/**
 * 端末画面の状態。**これ自体が不変値**なので `runTest` も要らず素の関数で回せる。
 *
 * @param lines 描かれている行。**先頭から捨てられる**([trimmedLines])
 * @param style 次のチャンクへ持ち越す見た目(SGR はフレームをまたいで効く)
 * @param pending 途中で切れたエスケープ / 末尾の `\r`
 * @param dropped 落とした制御の種別ごとの件数
 * @param trimmedLines 行数上限で捨てた行数(**黙って捨てない**)
 */
data class TerminalBuffer(
    val lines: List<AnsiLine> = listOf(AnsiLine(emptyList())),
    val style: AnsiStyle = AnsiStyle.DEFAULT,
    val pending: String = "",
    val dropped: Map<AnsiControlKind, Int> = emptyMap(),
    val trimmedLines: Int = 0,
) {
    /** 落とした総数。0 と「1件も来ていない」は同じ意味である(こちらは数えている)。 */
    val droppedTotal: Int get() = dropped.values.sum()

    /** 画面の素のテキスト。テストと `content-desc` が使う。 */
    val text: String get() = lines.joinToString("\n") { it.text }
}

/** 1画面あたりに保持する最大行数。[FILE_MAX_LINES] と同じ値・同じ理由(描画が詰まる)。 */
const val TERMINAL_MAX_LINES = 5000

/** タブ幅。端末の既定と同じ8。 */
private const val TAB_WIDTH = 8

/** ESC。 */
private const val ESC = '\u001b'

/** BEL(OSC の終端)。 */
private const val BEL = '\u0007'

/**
 * 出力を1チャンク追記する。**この関数がこのファイルの唯一の入口である。**
 *
 * @param chunk WebSocket のフレーム1つ分の文字列([dev.opencode.android.data.PtyFrame.Output])
 * @param maxLines 保持する行数。テストは小さくして打ち切りを測る
 */
fun appendTerminalOutput(
    buffer: TerminalBuffer,
    chunk: String,
    maxLines: Int = TERMINAL_MAX_LINES,
): TerminalBuffer {
    if (chunk.isEmpty()) return buffer
    val source = buffer.pending + chunk
    val lines = buffer.lines.toMutableList()
    if (lines.isEmpty()) lines += AnsiLine(emptyList())
    val line = LineBuilder(lines.removeAt(lines.size - 1))
    var style = buffer.style
    val dropped = buffer.dropped.toMutableMap()
    fun drop(kind: AnsiControlKind) {
        dropped[kind] = (dropped[kind] ?: 0) + 1
    }

    var i = 0
    var carry = ""
    while (i < source.length) {
        val ch = source[i]
        when {
            ch == ESC -> {
                val consumed = escapeLength(source, i)
                if (consumed == null) {
                    // **フレーム境界で切れた。** 残りを次のチャンクの先頭へ持ち越す ——
                    // 落とすと `1m` のような断片が本文として画面に出る。
                    carry = source.substring(i)
                    i = source.length
                } else {
                    val sequence = source.substring(i, i + consumed)
                    val sgr = sgrParametersOf(sequence)
                    if (sgr != null) {
                        style = applySgr(style, sgr)
                    } else {
                        drop(controlKindOf(sequence))
                    }
                    i += consumed
                }
            }
            ch == '\r' -> {
                if (i == source.length - 1) {
                    // `\r` が末尾。**`\n` が次のフレームに在るかもしれない**ので判断を遅らせる。
                    carry = "\r"
                    i = source.length
                } else if (source[i + 1] == '\n') {
                    line.flushInto(lines)
                    i += 2
                } else {
                    // 復帰。**完全な上書きは実装しない**(桁位置を持たないため)ので、
                    // 「行を消してから書き直す」という近似にする。進捗表示はこれで読める。
                    line.clear()
                    i += 1
                }
            }
            ch == '\n' -> {
                line.flushInto(lines)
                i += 1
            }
            ch == '\t' -> {
                line.append(" ".repeat(TAB_WIDTH - line.length % TAB_WIDTH), style)
                i += 1
            }
            ch == '\b' -> {
                line.backspace()
                i += 1
            }
            ch.code < 0x20 || ch.code == 0x7f -> {
                drop(AnsiControlKind.OTHER)
                i += 1
            }
            else -> {
                val start = i
                while (i < source.length && isPrintable(source[i])) i++
                line.append(source.substring(start, i), style)
            }
        }
    }
    lines += line.build()

    var trimmed = buffer.trimmedLines
    if (lines.size > maxLines) {
        val cut = lines.size - maxLines
        repeat(cut) { lines.removeAt(0) }
        trimmed += cut
    }
    return TerminalBuffer(
        lines = lines,
        style = style,
        pending = carry,
        dropped = dropped,
        trimmedLines = trimmed,
    )
}

private fun isPrintable(ch: Char): Boolean = ch.code >= 0x20 && ch.code != 0x7f && ch != ESC

/**
 * `source[start]` から始まるエスケープの長さ。**まだ終わっていなければ null**
 * (呼び出し側が持ち越す)。
 */
internal fun escapeLength(source: String, start: Int): Int? {
    if (start + 1 >= source.length) return null
    return when (source[start + 1]) {
        '[' -> {
            // CSI: 終端は 0x40..0x7E。
            var i = start + 2
            while (i < source.length && source[i].code !in 0x40..0x7e) i++
            if (i >= source.length) null else i - start + 1
        }
        ']', 'P', 'X', '^', '_' -> {
            // OSC / DCS / SOS / PM / APC: BEL か ST(`ESC \`)まで。
            var i = start + 2
            while (i < source.length) {
                if (source[i] == BEL) return i - start + 1
                if (source[i] == ESC && i + 1 < source.length && source[i + 1] == '\\') return i - start + 2
                if (source[i] == ESC && i + 1 >= source.length) return null
                i++
            }
            null
        }
        '(', ')', '*', '+', '%', '#' -> if (start + 2 < source.length) 3 else null
        // `ESC 7` `ESC 8` `ESC =` `ESC >` `ESC c` `ESC M` … 2バイトで終わる。
        else -> 2
    }
}

/**
 * SGR なら**パラメータ文字列**を返す。SGR でなければ null。
 *
 * `ESC[m`(パラメータ無し)は `ESC[0m` と同じ = **全リセット**である。
 * 空文字列を返し、[applySgr] がそれを 0 として扱う。実測の `cmd.exe` は
 * 起動直後にまさに `ESC[m` を出す。
 */
internal fun sgrParametersOf(sequence: String): String? {
    if (sequence.length < 3) return null
    if (sequence[0] != ESC || sequence[1] != '[') return null
    if (sequence.last() != 'm') return null
    val params = sequence.substring(2, sequence.length - 1)
    // `ESC[?…m` のようなプライベートパラメータは SGR ではない。
    if (params.isNotEmpty() && (params[0] == '?' || params[0] == '>' || params[0] == '<')) return null
    return params
}

/** 落とすエスケープの種別。**判定はここにしかない。** */
internal fun controlKindOf(sequence: String): AnsiControlKind {
    if (sequence.length < 2) return AnsiControlKind.OTHER
    return when (sequence[1]) {
        ']' -> AnsiControlKind.OSC
        '[' -> {
            val final = sequence.last()
            val params = sequence.substring(2, sequence.length - 1)
            when (final) {
                'H', 'f', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'd' -> AnsiControlKind.CURSOR
                'J', 'K', 'X' -> AnsiControlKind.ERASE
                'h', 'l' ->
                    // **代替画面は別扱いにする。** `vim` / `top` はここから入るので、
                    // 「画面が真っ白なのはこれが原因」と言える唯一の手掛かりになる。
                    if (params in ALT_SCREEN_PARAMS) AnsiControlKind.ALT_SCREEN else AnsiControlKind.MODE
                else -> AnsiControlKind.OTHER
            }
        }
        else -> AnsiControlKind.OTHER
    }
}

/** 代替画面へ入る/出るモード番号(xterm)。 */
private val ALT_SCREEN_PARAMS = setOf("?1049", "?47", "?1047", "?1048")

/**
 * SGR を適用する。**未知のパラメータは無視して残りを適用する** ——
 * 1つ知らない番号があっただけで色指定を丸ごと捨てると、
 * 「サーバーが色を出していない」と「こちらが読めなかった」の区別が消える。
 */
internal fun applySgr(style: AnsiStyle, parameters: String): AnsiStyle {
    if (parameters.isEmpty()) return AnsiStyle.DEFAULT
    val codes = parameters.split(';').map { it.toIntOrNull() ?: 0 }
    var next = style
    var i = 0
    while (i < codes.size) {
        when (val code = codes[i]) {
            0 -> next = AnsiStyle.DEFAULT
            1 -> next = next.copy(bold = true)
            2 -> next = next.copy(dim = true)
            3 -> next = next.copy(italic = true)
            4 -> next = next.copy(underline = true)
            7 -> next = next.copy(inverse = true)
            22 -> next = next.copy(bold = false, dim = false)
            23 -> next = next.copy(italic = false)
            24 -> next = next.copy(underline = false)
            27 -> next = next.copy(inverse = false)
            39 -> next = next.copy(fg = null)
            49 -> next = next.copy(bg = null)
            in 30..37 -> next = next.copy(fg = AnsiColor.Indexed(code - 30))
            in 90..97 -> next = next.copy(fg = AnsiColor.Indexed(code - 90 + 8))
            in 40..47 -> next = next.copy(bg = AnsiColor.Indexed(code - 40))
            in 100..107 -> next = next.copy(bg = AnsiColor.Indexed(code - 100 + 8))
            38, 48 -> {
                val extended = extendedColorAt(codes, i)
                if (extended != null) {
                    next = if (code == 38) next.copy(fg = extended.first) else next.copy(bg = extended.first)
                    i += extended.second
                }
            }
            else -> Unit
        }
        i++
    }
    return next
}

/**
 * `38;5;n` / `38;2;r;g;b` を読む。**足りなければ null**(残りを色として誤読しない)。
 *
 * @return 色と、`38`/`48` の後に**追加で消費した**パラメータ数
 */
private fun extendedColorAt(codes: List<Int>, at: Int): Pair<AnsiColor, Int>? {
    if (at + 1 >= codes.size) return null
    return when (codes[at + 1]) {
        5 -> if (at + 2 < codes.size) AnsiColor.Indexed(codes[at + 2]) to 2 else null
        2 -> if (at + 4 < codes.size) {
            AnsiColor.Rgb(codes[at + 2], codes[at + 3], codes[at + 4]) to 4
        } else {
            null
        }
        else -> null
    }
}

/** 1行を組み立てる可変ヘルパ(この関数の外へ出さない)。 */
private class LineBuilder(initial: AnsiLine) {
    private val spans = initial.spans.toMutableList()
    private val run = StringBuilder()
    private var runStyle: AnsiStyle = initial.spans.lastOrNull()?.style ?: AnsiStyle.DEFAULT

    val length: Int get() = spans.sumOf { it.text.length } + run.length

    fun append(text: String, style: AnsiStyle) {
        if (text.isEmpty()) return
        if (style != runStyle && run.isNotEmpty()) {
            spans += AnsiSpan(run.toString(), runStyle)
            run.setLength(0)
        }
        runStyle = style
        run.append(text)
    }

    fun backspace() {
        if (run.isNotEmpty()) {
            run.setLength(run.length - 1)
            return
        }
        val last = spans.removeLastOrNull() ?: return
        if (last.text.length > 1) spans += last.copy(text = last.text.dropLast(1))
    }

    fun clear() {
        spans.clear()
        run.setLength(0)
    }

    fun build(): AnsiLine {
        val out = spans.toMutableList()
        if (run.isNotEmpty()) out += AnsiSpan(run.toString(), runStyle)
        return AnsiLine(out)
    }

    fun flushInto(lines: MutableList<AnsiLine>) {
        lines += build()
        clear()
    }
}

/**
 * ANSI の色 → ARGB。**Compose に依存しない**ので `runTest` も要らずに固定できる。
 *
 * 添字 0..15 は xterm の既定色をそのまま使う。16..231 は 6×6×6 の色立方体、
 * 232..255 は灰階調 —— どちらも xterm の計算式である
 * (`16 + 36r + 6g + b` / `8 + 10n`)。
 *
 * @return ARGB。**null は「この添字では色を決められない」**(範囲外)であって黒ではない。
 *   黒に潰すと、暗い背景の上で**文字が消える**。
 */
fun ansiColorToArgb(color: AnsiColor): Int? = when (color) {
    is AnsiColor.Rgb ->
        if (color.r !in 0..255 || color.g !in 0..255 || color.b !in 0..255) {
            null
        } else {
            (0xFF shl 24) or (color.r shl 16) or (color.g shl 8) or color.b
        }
    is AnsiColor.Indexed -> when (val index = color.index) {
        in 0..15 -> ANSI_BASE_PALETTE[index]
        in 16..231 -> {
            val n = index - 16
            val r = ANSI_CUBE_STEPS[n / 36]
            val g = ANSI_CUBE_STEPS[(n / 6) % 6]
            val b = ANSI_CUBE_STEPS[n % 6]
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        in 232..255 -> {
            val level = 8 + (index - 232) * 10
            (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        }
        else -> null
    }
}

/** xterm の 0..15。暗い背景で読めるよう、通常色も真っ黒/真っ白にはしない既定値である。 */
private val ANSI_BASE_PALETTE = intArrayOf(
    0xFF3B3B3B.toInt(), // 0 black(真っ黒だと暗背景で消えるので少し上げる)
    0xFFE05252.toInt(), // 1 red
    0xFF5BB55B.toInt(), // 2 green
    0xFFCFA83E.toInt(), // 3 yellow
    0xFF5B8FE0.toInt(), // 4 blue
    0xFFB569C6.toInt(), // 5 magenta
    0xFF4FB8B8.toInt(), // 6 cyan
    0xFFCACACA.toInt(), // 7 white
    0xFF6E6E6E.toInt(), // 8 bright black
    0xFFFF7B72.toInt(), // 9 bright red
    0xFF7EE787.toInt(), // 10 bright green
    0xFFF2CC60.toInt(), // 11 bright yellow
    0xFF79C0FF.toInt(), // 12 bright blue
    0xFFD2A8FF.toInt(), // 13 bright magenta
    0xFF56D4DD.toInt(), // 14 bright cyan
    0xFFFFFFFF.toInt(), // 15 bright white
)

/** 6×6×6 立方体の各段(xterm の既定)。 */
private val ANSI_CUBE_STEPS = intArrayOf(0, 95, 135, 175, 215, 255)

// ---------------------------------------------------------------------------
// テーマに合わせた読みやすさ(レビュー minor-6)
// ---------------------------------------------------------------------------

/**
 * ANSI の色を、**実際に載る面の上で読める色**にして返す。
 *
 * ## なぜ要るのか
 *
 * [ansiColorToArgb] が返すのは **xterm のパレットそのまま**で、暗い背景を前提にしている。
 * このアプリの端末はテーマの `surface` の上に描くので、**ライトテーマでは
 * 白系・淡色系(添字 7・15、灰階調の上のほう、`ESC[38;2;255;255;255m`)が
 * ほぼ見えなくなる**。逆にダークテーマでは黒系(添字 0、灰階調の下のほう)が消える。
 * どちらも**エラーは出ず、文字が無いように見えるだけ**である
 * (このリポジトリが繰り返し閉じてきた「出ていないのか、出せなかったのか」の形)。
 *
 * ## やり方
 *
 * **色相を変えず、明るさだけを面から遠ざける。** 面が明るければ黒へ、暗ければ白へ
 * 二分探索で寄せ、WCAG 2.1 のコントラスト比 [WCAG_AA_NORMAL_TEXT] を満たした時点で止める。
 * 既に満たしている色は**1ビットも動かさない**(その主張はテストで固定してある)。
 *
 * **面の色を書き写さない。** 引数で受け取るのは、テーマ側の色を変えたときに
 * テストだけが古い色で緑になるのを避けるためである(Q6 の [Contrast.kt] と同じ規則)。
 *
 * @param backgroundArgb この文字が載る面の ARGB。ANSI 側が背景色を指定していればその色、
 *   していなければテーマの `surface`
 * @return ARGB。**null は「この色は決められない」**([ansiColorToArgb] と同じ意味)
 */
fun ansiColorOn(color: AnsiColor, backgroundArgb: Int): Int? =
    ansiColorToArgb(color)?.let { readableOnBackground(it, backgroundArgb) }

/**
 * [argb] を [backgroundArgb] の上で読める明るさへ寄せる。**色相と彩度の比は保つ。**
 *
 * @param minimumRatio 要求するコントラスト比。既定は本文の 4.5:1
 */
internal fun readableOnBackground(
    argb: Int,
    backgroundArgb: Int,
    minimumRatio: Double = WCAG_AA_NORMAL_TEXT,
): Int {
    val background = backgroundArgb.toUnsignedLong()
    fun ratioOf(color: Int) = contrastRatio(color.toUnsignedLong(), background)
    if (ratioOf(argb) >= minimumRatio) return argb

    // **両方向を試す。** 「面が暗ければ白へ」だけでは足りない ——
    // 中間の明るさの面(例: 青の背景)では、白より黒のほうが遠いことがある
    // (青 `#5B8FE0` の上では 白 3.26:1 / 黒 6.45:1。数値はテストが固定している)。
    val toWhite = minimumBlend(argb, white = true, background = background, minimumRatio = minimumRatio)
    val toBlack = minimumBlend(argb, white = false, background = background, minimumRatio = minimumRatio)
    return when {
        // 両方届くなら**色を動かす量が少ないほう**。
        toWhite != null && toBlack != null -> if (toWhite.second <= toBlack.second) toWhite.first else toBlack.first
        toWhite != null -> toWhite.first
        toBlack != null -> toBlack.first
        // どちらも届かない(中間の面)。**届かないことを隠して元の色を返さない** ——
        // 行けるところまで行った色を返す。
        else -> {
            val white = blendToward(argb, white = true, t = 1.0)
            val black = blendToward(argb, white = false, t = 1.0)
            if (ratioOf(white) >= ratioOf(black)) white else black
        }
    }
}

/**
 * [minimumRatio] に届く最小の寄せ量を二分探索する。
 *
 * @return `(色, 寄せ量)`。**端まで寄せても届かなければ null**(「できなかった」を返す)
 */
private fun minimumBlend(
    argb: Int,
    white: Boolean,
    background: Long,
    minimumRatio: Double,
): Pair<Int, Double>? {
    val extreme = blendToward(argb, white, 1.0)
    if (contrastRatio(extreme.toUnsignedLong(), background) < minimumRatio) return null
    var low = 0.0
    var high = 1.0
    var best = extreme to 1.0
    repeat(20) {
        val mid = (low + high) / 2.0
        val candidate = blendToward(argb, white, mid)
        if (contrastRatio(candidate.toUnsignedLong(), background) >= minimumRatio) {
            best = candidate to mid
            high = mid
        } else {
            low = mid
        }
    }
    return best
}

/** `t=0` で元の色、`t=1` で白(または黒)。各チャネルを線形に寄せる。 */
private fun blendToward(argb: Int, white: Boolean, t: Double): Int {
    fun mix(channel: Int): Int {
        val target = if (white) 255 else 0
        return (channel + (target - channel) * t).toInt().coerceIn(0, 255)
    }
    val r = mix((argb shr 16) and 0xFF)
    val g = mix((argb shr 8) and 0xFF)
    val b = mix(argb and 0xFF)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** ARGB の Int を、符号拡張せずに `0xAARRGGBB` の Long にする。 */
internal fun Int.toUnsignedLong(): Long = this.toLong() and 0xFFFFFFFFL
