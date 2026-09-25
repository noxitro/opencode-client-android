package dev.opencode.android

import androidx.compose.ui.graphics.Color
import dev.opencode.android.ui.AnsiColor
import dev.opencode.android.ui.AnsiStyle
import dev.opencode.android.ui.ansiColorOn
import dev.opencode.android.ui.ansiColorToArgb
import dev.opencode.android.ui.readableOnBackground
import dev.opencode.android.ui.spanStyleOf
import dev.opencode.android.ui.theme.DarkColors
import dev.opencode.android.ui.theme.LightColors
import dev.opencode.android.ui.theme.WCAG_AA_NORMAL_TEXT
import dev.opencode.android.ui.theme.contrastRatio
import dev.opencode.android.ui.theme.toArgbLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端末の文字色が**両方のテーマで読める**こと(レビュー minor-6)。
 *
 * ## 何が壊れていたか
 *
 * [ansiColorToArgb] が返すのは **xterm のパレットそのまま**で、暗い背景を前提にしている。
 * 端末はテーマの `surface` の上に描くので、**ライトテーマでは白系・淡色系
 * (添字 7・15、灰階調の上、`ESC[38;2;255;255;255m`)がほぼ見えない**。
 * エラーも警告も出ず、**文字が来ていないように見える**だけである ——
 * このリポジトリが7度閉じてきた「無い / 取れなかった」と同じ形。
 *
 * ## 検出器の校正(HARNESS「常設ルール」)
 *
 * 「全部通った」は計算が壊れていても出る。したがってここは
 * **先に「直す前なら落ちること」を assert する**(下の `旧来の生のパレットは…`)。
 * 計算式そのものの校正は [Q6ContrastTest] にある(黒白 21:1 など)。
 *
 * **テーマの色をここに書き写さない。** [LightColors] / [DarkColors] をそのまま入力にする
 * (書き写すと、テーマを変えたときにテストだけが古い色で緑になる)。
 */
class Q9AnsiContrastTest {

    private val lightSurface = LightColors.surface.toArgbLong().toInt()
    private val darkSurface = DarkColors.surface.toArgbLong().toInt()

    private fun ratio(fg: Int, bg: Int) = contrastRatio(fg.toLong() and 0xFFFFFFFFL, bg.toLong() and 0xFFFFFFFFL)

    // ---- 陰性コントロール: 直す前は実際に落ちる ----

    /**
     * **生のパレットはライトの面で基準を割る。** ここが落ちなければ、
     * 下の「全部通った」は何も測っていない。
     */
    @Test
    fun `旧来の生のパレットはライトの面で読めない`() {
        val offenders = (0..255)
            .mapNotNull { ansiColorToArgb(AnsiColor.Indexed(it)) }
            .filter { ratio(it, lightSurface) < WCAG_AA_NORMAL_TEXT }
        assertTrue("生のパレットが1色も落ちないなら検出器の故障", offenders.size >= 100)
        // 白(添字15)は**ほぼ見えない**。数値は assertion で書く(Q6 レビュー major-4)。
        assertEquals(1.05, ratio(0xFFFFFFFF.toInt(), lightSurface), 0.01)
    }

    /** ダークの面でも同じことが起きる(黒系が消える)。**片側だけの問題ではない。** */
    @Test
    fun `旧来の生のパレットはダークの面で黒系が読めない`() {
        assertTrue(ratio(ansiColorToArgb(AnsiColor.Indexed(0))!!, darkSurface) < WCAG_AA_NORMAL_TEXT)
        assertEquals(1.71, ratio(ansiColorToArgb(AnsiColor.Indexed(0))!!, darkSurface), 0.01)
    }

    // ---- 本題: 面を渡せば 256 色すべてが基準を満たす ----

    @Test
    fun `256 色すべてがライトの面で基準を満たす`() {
        val failures = (0..255).mapNotNull { index ->
            val argb = ansiColorOn(AnsiColor.Indexed(index), lightSurface)!!
            val r = ratio(argb, lightSurface)
            if (r < WCAG_AA_NORMAL_TEXT) "$index=${"%.2f".format(r)}" else null
        }
        assertEquals("ライトの面で読めない色", emptyList<String>(), failures)
    }

    @Test
    fun `256 色すべてがダークの面で基準を満たす`() {
        val failures = (0..255).mapNotNull { index ->
            val argb = ansiColorOn(AnsiColor.Indexed(index), darkSurface)!!
            val r = ratio(argb, darkSurface)
            if (r < WCAG_AA_NORMAL_TEXT) "$index=${"%.2f".format(r)}" else null
        }
        assertEquals("ダークの面で読めない色", emptyList<String>(), failures)
    }

    /** 24bit 色(`ESC[38;2;R;G;Bm`)も同じ扱い。**サーバーは白を送ってくる。** */
    @Test
    fun `24bit の白と黒も面に合わせて寄る`() {
        val whiteOnLight = ansiColorOn(AnsiColor.Rgb(255, 255, 255), lightSurface)!!
        val blackOnDark = ansiColorOn(AnsiColor.Rgb(0, 0, 0), darkSurface)!!
        assertTrue(ratio(whiteOnLight, lightSurface) >= WCAG_AA_NORMAL_TEXT)
        assertTrue(ratio(blackOnDark, darkSurface) >= WCAG_AA_NORMAL_TEXT)
        assertNotEquals(0xFFFFFFFF.toInt(), whiteOnLight)
        assertNotEquals(0xFF000000.toInt(), blackOnDark)
    }

    // ---- 動かさないもの ----

    /** **既に読める色は1ビットも動かさない。** 動かすと端末の色が理由なく変わる。 */
    @Test
    fun `既に基準を満たす色はそのまま返す`() {
        // 明るい赤はダークの面で既に十分。
        val brightRed = ansiColorToArgb(AnsiColor.Indexed(9))!!
        assertTrue(ratio(brightRed, darkSurface) >= WCAG_AA_NORMAL_TEXT)
        assertEquals(brightRed, ansiColorOn(AnsiColor.Indexed(9), darkSurface))
        // 黒はライトの面で既に十分。
        assertEquals(0xFF000000.toInt(), readableOnBackground(0xFF000000.toInt(), lightSurface))
    }

    /** 決められない色は**決められないまま**返す(黒に潰さない)。 */
    @Test
    fun `範囲外は null のまま`() {
        assertNull(ansiColorOn(AnsiColor.Indexed(256), lightSurface))
        assertNull(ansiColorOn(AnsiColor.Rgb(300, 0, 0), darkSurface))
    }

    /**
     * **色相は保つ。** 明るさだけを面から遠ざける ——
     * 赤が灰色になったら、色を解釈している意味が無い。
     */
    @Test
    fun `寄せても赤は赤のまま`() {
        val red = ansiColorOn(AnsiColor.Rgb(255, 0, 0), lightSurface)!!
        val r = (red shr 16) and 0xFF
        val g = (red shr 8) and 0xFF
        val b = red and 0xFF
        assertTrue("赤成分が他より大きいこと: $r/$g/$b", r > g && r > b)
        assertEquals(0, g)
        assertEquals(0, b)
    }

    // ---- 配線(画面が実際にこの関数を通ること)----

    /**
     * **画面の span がこの関数を通っている**ことを固定する。
     *
     * Q5 の R1 と同じ形: 純関数が正しくても、**呼び出し側が生のパレットを使っていれば
     * 画面は読めないまま**である。ここを固定しないと、`ansiColorOn` を
     * `ansiColorToArgb` に戻す変異が「色のテストは全部緑」のまま通り抜ける。
     */
    @Test
    fun `画面の span は面に合わせた色を使う`() {
        val white = AnsiStyle.DEFAULT.copy(fg = AnsiColor.Rgb(255, 255, 255))
        val onLight = spanStyleOf(white, LightColors.surface)
        val onDark = spanStyleOf(white, DarkColors.surface)
        assertNotEquals("ライトの面で白のまま描いている", Color.White, onLight.color)
        assertTrue(ratio(onLight.color.toArgbLong().toInt(), lightSurface) >= WCAG_AA_NORMAL_TEXT)
        // ダークの面では白のままでよい(動かす理由が無い)。
        assertEquals(Color.White, onDark.color)
    }

    /** `inverse` は前景と背景を入れ替えた**後**に読みやすさを見る。 */
    @Test
    fun `inverse でも読める側を前景にする`() {
        val style = AnsiStyle.DEFAULT.copy(
            fg = AnsiColor.Rgb(255, 255, 255),
            bg = AnsiColor.Rgb(0, 0, 0),
            inverse = true,
        )
        val span = spanStyleOf(style, LightColors.surface)
        // 入れ替わって背景が白、前景が黒。
        assertEquals(Color.White, span.background)
        assertEquals(Color.Black, span.color)
    }

    /**
     * ANSI が背景色を指定していれば、**その背景**に対して寄せる(テーマの面ではない)。
     *
     * ただし**中間の明るさの背景では 4.5:1 に届かない**(白でも黒でも足りない)。
     * そのときは端まで寄せた色を返す —— **届かないことを隠して元の色を返さない**。
     * ここはその上限を数値で固定する。
     */
    @Test
    fun `背景色が指定されていればそれを基準にする`() {
        val blue = ansiColorToArgb(AnsiColor.Indexed(4))!!
        // 校正: 青の上では**白より黒のほうが遠い**。「面が暗ければ白へ」だけの実装は
        // ここで 3.26:1 に落ち着き、基準に届かない。
        assertEquals(3.26, ratio(0xFFFFFFFF.toInt(), blue), 0.01)
        assertEquals(6.45, ratio(0xFF000000.toInt(), blue), 0.01)
        val onBlue = ansiColorOn(AnsiColor.Indexed(4), blue)!!
        // 同じ色のまま(1.00:1 = 読めない)を返していないこと。
        assertNotEquals(blue, onBlue)
        assertTrue(ratio(onBlue, blue) >= WCAG_AA_NORMAL_TEXT)
    }
}
