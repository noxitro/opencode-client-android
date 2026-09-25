package dev.opencode.android

import dev.opencode.android.ui.AnsiColor
import dev.opencode.android.ui.AnsiControlKind
import dev.opencode.android.ui.AnsiStyle
import dev.opencode.android.ui.TerminalBuffer
import dev.opencode.android.ui.ansiColorToArgb
import dev.opencode.android.ui.appendTerminalOutput
import dev.opencode.android.ui.applySgr
import dev.opencode.android.ui.controlKindOf
import dev.opencode.android.ui.escapeLength
import dev.opencode.android.ui.sgrParametersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANSI 解釈([dev.opencode.android.ui.AnsiText])。
 *
 * §5b Q9 のゲート:「SGRパーサのユニットテスト: 色・太字・リセット・**未知のエスケープを落とす**こと」。
 * それに加えて、このプロジェクトの原則から要る主張が2つある:
 *
 *  - **落とした物を数えていること**(「来ていない」と「解釈しなかった」の区別)
 *  - **フレーム境界で切れたエスケープを持ち越すこと**(落とすと断片が本文に出る)
 */
class Q9AnsiTest {

    private fun render(vararg chunks: String, maxLines: Int = 5000): TerminalBuffer {
        var buffer = TerminalBuffer()
        chunks.forEach { buffer = appendTerminalOutput(buffer, it, maxLines) }
        return buffer
    }

    // ---------------------------------------------------------------------
    // SGR
    // ---------------------------------------------------------------------

    @Test
    fun `色と太字が spans に乗る`() {
        val buffer = render("plain\u001b[1;31mred bold\u001b[0mplain again")
        val spans = buffer.lines.single().spans
        assertEquals(listOf("plain", "red bold", "plain again"), spans.map { it.text })
        assertEquals(AnsiStyle.DEFAULT, spans[0].style)
        assertEquals(AnsiColor.Indexed(1), spans[1].style.fg)
        assertTrue(spans[1].style.bold)
        assertEquals(AnsiStyle.DEFAULT, spans[2].style)
    }

    /** `ESC[m`(パラメータ無し)は `ESC[0m` と同じ。**実測の `cmd.exe` が起動直後に出す。** */
    @Test
    fun `パラメータ無しの m は全リセット`() {
        assertEquals("", sgrParametersOf("\u001b[m"))
        assertEquals(AnsiStyle.DEFAULT, applySgr(AnsiStyle(bold = true, fg = AnsiColor.Indexed(2)), ""))
    }

    @Test
    fun `明るい色は添字 8 以上へ写る`() {
        assertEquals(AnsiColor.Indexed(12), applySgr(AnsiStyle.DEFAULT, "94").fg)
        assertEquals(AnsiColor.Indexed(11), applySgr(AnsiStyle.DEFAULT, "103").bg)
    }

    @Test
    fun `256色と truecolor を読む`() {
        assertEquals(AnsiColor.Indexed(208), applySgr(AnsiStyle.DEFAULT, "38;5;208").fg)
        assertEquals(AnsiColor.Rgb(10, 20, 30), applySgr(AnsiStyle.DEFAULT, "48;2;10;20;30").bg)
    }

    /** **足りない拡張色を残りのパラメータとして誤読しない。** */
    @Test
    fun `途中で切れた拡張色は色にしない`() {
        assertNull(applySgr(AnsiStyle.DEFAULT, "38;5").fg)
        assertNull(applySgr(AnsiStyle.DEFAULT, "38;2;1;2").fg)
    }

    /**
     * **未知のパラメータで色指定を丸ごと捨てない。**
     * 捨てると「サーバーが色を出していない」と「こちらが読めなかった」の区別が消える。
     */
    @Test
    fun `未知のパラメータは無視して残りを適用する`() {
        val style = applySgr(AnsiStyle.DEFAULT, "1;99;31")
        assertTrue(style.bold)
        assertEquals(AnsiColor.Indexed(1), style.fg)
    }

    @Test
    fun `39 と 49 は色だけを既定へ戻す`() {
        val start = AnsiStyle(fg = AnsiColor.Indexed(1), bg = AnsiColor.Indexed(2), bold = true)
        val after = applySgr(applySgr(start, "39"), "49")
        assertNull(after.fg)
        assertNull(after.bg)
        assertTrue(after.bold)
    }

    /** `ESC[?…m` はプライベートパラメータであって SGR ではない。 */
    @Test
    fun `プライベートパラメータ付きの m は SGR ではない`() {
        assertNull(sgrParametersOf("\u001b[?1m"))
    }

    // ---------------------------------------------------------------------
    // 落とす側(と、数える側)
    // ---------------------------------------------------------------------

    /**
     * **実データの1フレーム目**を通す。ここが Q9 の一番大事なテストで、
     * 「Windows の既定シェルは起動直後から全画面制御を出す」という実測が
     * そのまま入っている。
     */
    @Test
    fun `実データのバナーは制御が落ちて本文だけが残る`() {
        val buffer = render(Q9Fixtures.BANNER_FRAME_1)
        assertEquals("Microsoft Windows [Version 10.0.26200.9168]", buffer.text)
        // `[?9001h` `[?1004h` `[?25l` `[?25h` の4つがモード設定、`[2J` が消去、`[H` がカーソル、
        // `]0;…BEL` が OSC。`[m` は SGR なので落ちない。
        assertEquals(4, buffer.dropped[AnsiControlKind.MODE])
        assertEquals(1, buffer.dropped[AnsiControlKind.ERASE])
        assertEquals(1, buffer.dropped[AnsiControlKind.CURSOR])
        assertEquals(1, buffer.dropped[AnsiControlKind.OSC])
        assertEquals(7, buffer.droppedTotal)
    }

    @Test
    fun `実データの2フレーム目は改行してプロンプトになる`() {
        val buffer = render(Q9Fixtures.BANNER_FRAME_1, Q9Fixtures.BANNER_FRAME_2)
        assertEquals(
            listOf(
                "Microsoft Windows [Version 10.0.26200.9168]",
                "(c) Microsoft Corporation. All rights reserved.E:\\dev\\github.com\\noxitro\\opencode-android>",
            ),
            buffer.lines.map { it.text },
        )
    }

    @Test
    fun `echo hello の往復が読める`() {
        val buffer = render(
            Q9Fixtures.BANNER_FRAME_1,
            Q9Fixtures.BANNER_FRAME_2,
            Q9Fixtures.ECHO_INPUT_FRAME,
            Q9Fixtures.ECHO_OUTPUT_FRAME,
        )
        assertTrue(buffer.text.contains("echo hello"))
        assertTrue(buffer.text.contains("hello"))
        // **エスケープの断片が本文に出ていないこと**(陰性側)。
        assertTrue(buffer.text.none { it == '\u001b' })
        assertTrue(!buffer.text.contains("[?25l"))
    }

    /** 代替画面は**別枠で数える**。`vim` / `top` が入る唯一の手掛かりだから。 */
    @Test
    fun `代替画面は ALT_SCREEN として数える`() {
        val buffer = render("\u001b[?1049hfull screen\u001b[?1049l")
        assertEquals(2, buffer.dropped[AnsiControlKind.ALT_SCREEN])
        assertNull(buffer.dropped[AnsiControlKind.MODE])
    }

    @Test
    fun `制御の種別判定`() {
        assertEquals(AnsiControlKind.CURSOR, controlKindOf("\u001b[7;1H"))
        assertEquals(AnsiControlKind.ERASE, controlKindOf("\u001b[2J"))
        assertEquals(AnsiControlKind.ERASE, controlKindOf("\u001b[K"))
        assertEquals(AnsiControlKind.MODE, controlKindOf("\u001b[?25l"))
        assertEquals(AnsiControlKind.ALT_SCREEN, controlKindOf("\u001b[?1049h"))
        assertEquals(AnsiControlKind.OSC, controlKindOf("\u001b]0;title\u0007"))
        assertEquals(AnsiControlKind.OTHER, controlKindOf("\u001b7"))
    }

    /** 解釈しない C0 制御文字も**数える**(黙って消さない)。 */
    @Test
    fun `未知の制御文字は落として数える`() {
        val buffer = render("a\u0000b\u0001c")
        assertEquals("abc", buffer.text)
        assertEquals(2, buffer.dropped[AnsiControlKind.OTHER])
    }

    // ---------------------------------------------------------------------
    // フレーム境界
    // ---------------------------------------------------------------------

    /**
     * **サーバーは最大 65536 バイトでフレームを切る。**
     * 持ち越さないと、残りの `1m` が本文として画面に出る。
     */
    @Test
    fun `途中で切れた CSI を持ち越す`() {
        val first = appendTerminalOutput(TerminalBuffer(), "text\u001b[3")
        assertEquals("text", first.text)
        assertEquals("\u001b[3", first.pending)
        val second = appendTerminalOutput(first, "1mred")
        assertEquals("textred", second.text)
        assertEquals(AnsiColor.Indexed(1), second.lines.single().spans.last().style.fg)
        assertEquals("", second.pending)
    }

    /** ESC 1文字だけで切れた場合も持ち越す。 */
    @Test
    fun `ESC 単体で切れても持ち越す`() {
        val first = appendTerminalOutput(TerminalBuffer(), "a\u001b")
        assertEquals("\u001b", first.pending)
        val second = appendTerminalOutput(first, "[1mb")
        assertEquals("ab", second.text)
        assertTrue(second.lines.single().spans.last().style.bold)
    }

    /** 終端の来ない OSC も持ち越す(BEL / ST を待つ)。 */
    @Test
    fun `終端の来ない OSC は持ち越す`() {
        val first = appendTerminalOutput(TerminalBuffer(), "\u001b]0;titl")
        assertEquals("", first.text)
        assertEquals("\u001b]0;titl", first.pending)
        val second = appendTerminalOutput(first, "e\u0007after")
        assertEquals("after", second.text)
        assertEquals(1, second.dropped[AnsiControlKind.OSC])
    }

    /** `\r\n` が2フレームに割れても**空行を増やさない**。 */
    @Test
    fun `CRLF がフレーム境界で割れても行が増えない`() {
        val first = appendTerminalOutput(TerminalBuffer(), "one\r")
        assertEquals("\r", first.pending)
        val second = appendTerminalOutput(first, "\ntwo")
        assertEquals(listOf("one", "two"), second.lines.map { it.text })
    }

    /** SGR はフレームをまたいで効く(端末の状態は連続している)。 */
    @Test
    fun `SGR はフレームをまたいで持ち越す`() {
        val buffer = render("\u001b[32m", "green")
        assertEquals(AnsiColor.Indexed(2), buffer.lines.single().spans.single().style.fg)
    }

    // ---------------------------------------------------------------------
    // 行の組み立て
    // ---------------------------------------------------------------------

    /** 単独の `\r` は行の書き直し(進捗表示)。**近似であることは doc に書いてある。** */
    @Test
    fun `単独の CR は行を書き直す`() {
        assertEquals(listOf("100%"), render("  1%\r 50%\r100%").lines.map { it.text })
    }

    @Test
    fun `タブは次のタブ位置まで空白で埋める`() {
        assertEquals("ab      cd", render("ab\tcd").text)
    }

    @Test
    fun `バックスペースは1文字消す`() {
        assertEquals("ab", render("abc\b").text)
        assertEquals("ac", render("ab\bc").text)
    }

    /** 打ち切りは**黙って行わない**(捨てた行数を残す)。 */
    @Test
    fun `行数上限を超えたら先頭から捨てて件数を残す`() {
        val buffer = render((1..10).joinToString("\n") { "line$it" }, maxLines = 4)
        assertEquals(4, buffer.lines.size)
        assertEquals(6, buffer.trimmedLines)
        assertEquals("line7", buffer.lines.first().text)
        assertEquals("line10", buffer.lines.last().text)
    }

    @Test
    fun `空のチャンクは何も変えない`() {
        val buffer = render("abc")
        assertEquals(buffer, appendTerminalOutput(buffer, ""))
    }

    // ---------------------------------------------------------------------
    // エスケープ長
    // ---------------------------------------------------------------------

    @Test
    fun `escapeLength は CSI の終端を 0x40-0x7E で決める`() {
        assertEquals(5, escapeLength("\u001b[31m", 0))
        assertEquals(4, escapeLength("\u001b[2J", 0))
        assertNull(escapeLength("\u001b[31", 0))
    }

    @Test
    fun `escapeLength は OSC を BEL か ST で終える`() {
        assertEquals(8, escapeLength("\u001b]0;abc\u0007", 0))
        assertEquals(9, escapeLength("\u001b]0;abc\u001b\\", 0))
    }

    // ---------------------------------------------------------------------
    // 色 → ARGB
    // ---------------------------------------------------------------------

    @Test
    fun `256色立方体は xterm の式で計算する`() {
        // 16 = (0,0,0)、231 = (255,255,255)、`16 + 36*5 + 6*0 + 0` = 196 = (255,0,0)
        assertEquals(0xFF000000.toInt(), ansiColorToArgb(AnsiColor.Indexed(16)))
        assertEquals(0xFFFFFFFF.toInt(), ansiColorToArgb(AnsiColor.Indexed(231)))
        assertEquals(0xFFFF0000.toInt(), ansiColorToArgb(AnsiColor.Indexed(196)))
    }

    @Test
    fun `灰階調は 8 から 10 刻み`() {
        assertEquals(0xFF080808.toInt(), ansiColorToArgb(AnsiColor.Indexed(232)))
        assertEquals(0xFFEEEEEE.toInt(), ansiColorToArgb(AnsiColor.Indexed(255)))
    }

    /** **範囲外は黒に潰さない。** 潰すと暗い背景の上で文字が消える。 */
    @Test
    fun `範囲外の添字は色を決めない`() {
        assertNull(ansiColorToArgb(AnsiColor.Indexed(256)))
        assertNull(ansiColorToArgb(AnsiColor.Indexed(-1)))
        assertNull(ansiColorToArgb(AnsiColor.Rgb(300, 0, 0)))
    }

    @Test
    fun `truecolor はそのまま ARGB になる`() {
        assertEquals(0xFF0A141E.toInt(), ansiColorToArgb(AnsiColor.Rgb(10, 20, 30)))
    }
}
