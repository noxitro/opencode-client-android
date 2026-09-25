package dev.opencode.android

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.opencode.android.ui.theme.ContrastPair
import dev.opencode.android.ui.theme.DarkColors
import dev.opencode.android.ui.theme.DarkStatusColors
import dev.opencode.android.ui.theme.LightColors
import dev.opencode.android.ui.theme.LightStatusColors
import dev.opencode.android.ui.theme.StatusColors
import dev.opencode.android.ui.theme.WCAG_AA_LARGE_TEXT
import dev.opencode.android.ui.theme.WCAG_AA_NORMAL_TEXT
import dev.opencode.android.ui.theme.contrastRatio
import dev.opencode.android.ui.theme.relativeLuminance
import dev.opencode.android.ui.theme.statusColorsFor
import dev.opencode.android.ui.theme.themeContrastPairs
import dev.opencode.android.ui.theme.toArgbLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * Q6 スコープ1: **コントラスト比を機械計算する**(QUALITY_PLAN §5 Q6)。
 *
 * ## 検出器の校正(HARNESS「常設ルール」)
 *
 * 「全部通った」という結果は、計算式が壊れていても出る。したがってこのファイルは
 * **先に計算式そのものを既知の値で固定する**:
 *  - 黒 vs 白 = 21:1(WCAG の上限)
 *  - 同じ色同士 = 1:1(下限)
 *  - 公開されている中間値(#777777 vs #FFFFFF = 4.48:1、これは **4.5 をわずかに割る**
 *    ことで有名な値)
 *
 * この3つが合っていなければ、テーマの合否は意味を持たない。
 */
class Q6ContrastTest {

    // ---- 計算式の校正 ----

    @Test
    fun `黒と白は21対1`() {
        assertEquals(21.0, contrastRatio(0xFF000000L, 0xFFFFFFFFL), 0.0001)
    }

    @Test
    fun `同じ色は1対1`() {
        assertEquals(1.0, contrastRatio(0xFF3A3A3AL, 0xFF3A3A3AL), 0.0001)
        assertEquals(1.0, contrastRatio(0xFFD97757L, 0xFFD97757L), 0.0001)
    }

    @Test
    fun `引数の順序に依らない`() {
        val a = contrastRatio(0xFF0F0F0FL, 0xFFECECECL)
        val b = contrastRatio(0xFFECECECL, 0xFF0F0F0FL)
        assertEquals(a, b, 0.0000001)
    }

    /**
     * `#777777` on white は 4.48:1(WCAG の代表的な境界例)。
     * **4.5 をわずかに割る**ので、閾値の向きを取り違えた実装はここで落ちる。
     */
    @Test
    fun `既知の境界値 777777 は白の上で4_48`() {
        val ratio = contrastRatio(0xFF777777L, 0xFFFFFFFFL)
        assertEquals(4.48, ratio, 0.01)
        assertTrue("4.5 を割ること自体が校正の主張", ratio < WCAG_AA_NORMAL_TEXT)
    }

    @Test
    fun `相対輝度は白1 黒0`() {
        assertEquals(1.0, relativeLuminance(0xFFFFFFFFL), 0.0000001)
        assertEquals(0.0, relativeLuminance(0xFF000000L), 0.0000001)
        // sRGB 原色の重み(0.2126 / 0.7152 / 0.0722)がそのまま出ること。
        assertEquals(0.2126, relativeLuminance(0xFFFF0000L), 0.0000001)
        assertEquals(0.7152, relativeLuminance(0xFF00FF00L), 0.0000001)
        assertEquals(0.0722, relativeLuminance(0xFF0000FFL), 0.0000001)
    }

    /** Compose の [Color] → ARGB Long が往復すること(`android.graphics` を経由しない)。 */
    @Test
    fun `Color から ARGB Long への変換が壊れていない`() {
        assertEquals(0xFF0F0F0FL, Color(0xFF0F0F0F).toArgbLong())
        assertEquals(0xFFFAF9F5L, Color(0xFFFAF9F5).toArgbLong())
        assertEquals(0xFFD97757L, Color(0xFFD97757).toArgbLong())
    }

    // ---- テーマの判定 ----

    @Test
    fun `ダークテーマの本文コントラストが基準を満たす`() {
        assertAllPass("dark", DarkColors, DarkStatusColors)
    }

    @Test
    fun `ライトテーマの本文コントラストが基準を満たす`() {
        assertAllPass("light", LightColors, LightStatusColors)
    }

    /**
     * **陰性コントロール**: 検査対象の集合が空でないこと、そして
     * 「基準を上げれば落ちる対がある」こと。全部が 21:1 なら検出器は何も見ていない。
     */
    @Test
    fun `検査対象が空でなく 基準を上げれば落ちる`() {
        val pairs = themeContrastPairs(DarkColors, DarkStatusColors) +
            themeContrastPairs(LightColors, LightStatusColors)
        assertTrue("検査対象が0件なら検出器の故障", pairs.size >= 76)
        val strict = pairs.map { it.copy(minimum = 21.0) }
        assertTrue("基準21:1で全部通るなら計算していない", strict.any { !it.passes })
    }

    /** [statusColorsFor] の分岐が両方向に効くこと(条件を1か所に集めた側の検出器)。 */
    @Test
    fun `statusColorsFor はダークとライトで別の色を返す`() {
        assertEquals(DarkStatusColors, statusColorsFor(darkTheme = true))
        assertEquals(LightStatusColors, statusColorsFor(darkTheme = false))
        assertTrue(DarkStatusColors.running != LightStatusColors.running)
        assertTrue(DarkStatusColors.retry != LightStatusColors.retry)
    }

    /**
     * Q6 が直した色が**元に戻ったら落ちる**ことを固定する。
     *
     * ## 数値をコメントではなく assertion で書く理由(Q6 レビュー major-4)
     *
     * 1周目はこの旧値を**コメントとして**書いており、**4つが誤っていた**
     * (`1.65`→実際 1.69 / `1.69`→1.67 / `2.45`→2.40 / **`2.13`→1.67**)。
     * assertion は正しく、変異も DETECTED だったので修正自体は健全だったが、
     * **「引用できる証拠」を方法とするプロジェクトで、記録された測定値が誤っていた**。
     * コメントは検証されないので、次に読む人はそれを実測値だと信じる。
     *
     * **したがって数値は全て `assertEquals(期待値, 実測, 誤差)` で書く。**
     * 書き間違えればテストが落ちる。コメントに数値を書かない。
     */
    @Test
    fun `Q6 が直した色は旧値では基準を満たさない`() {
        // --- 旧 InkOutline `#3A3A3A`(ダークの輪郭)---
        assertEquals(1.69, contrastRatio(0xFF3A3A3AL, 0xFF0F0F0FL), 0.005)   // on surface
        assertEquals(1.42, contrastRatio(0xFF3A3A3AL, 0xFF212121L), 0.005)   // on surfaceContainerHigh
        assertTrue(contrastRatio(0xFF3A3A3AL, 0xFF0F0F0FL) < WCAG_AA_LARGE_TEXT)

        // --- 旧 PaperOutline `#C8C3B8`(ライトの輪郭)---
        assertEquals(1.67, contrastRatio(0xFFC8C3B8L, 0xFFFAF9F5L), 0.005)
        assertEquals(1.40, contrastRatio(0xFFC8C3B8L, 0xFFE8E5DDL), 0.005)
        assertTrue(contrastRatio(0xFFC8C3B8L, 0xFFFAF9F5L) < WCAG_AA_LARGE_TEXT)

        // --- 旧 実行中バッジ `#4CAF50` ---
        assertEquals(2.40, contrastRatio(0xFF4CAF50L, 0xFFF0EEE8L), 0.005)   // light surfaceContainer
        assertEquals(2.21, contrastRatio(0xFF4CAF50L, 0xFFE8E5DDL), 0.005)   // light containerHigh
        assertEquals(6.26, contrastRatio(0xFF4CAF50L, 0xFF1A1A1AL), 0.005)   // dark surfaceContainer
        assertTrue(contrastRatio(0xFF4CAF50L, 0xFFF0EEE8L) < WCAG_AA_NORMAL_TEXT)
        // **ダークでは通っていた** —— 片方のテーマだけが壊れていたことの主張。
        assertTrue(contrastRatio(0xFF4CAF50L, 0xFF1A1A1AL) >= WCAG_AA_NORMAL_TEXT)

        // --- 旧 再試行バッジ `#FFA726` ---
        // 1周目の報告は 2.13 と書いたが**実際は 1.67**。輪郭と同じくらい悪かった。
        assertEquals(1.67, contrastRatio(0xFFFFA726L, 0xFFF0EEE8L), 0.005)
        assertEquals(1.54, contrastRatio(0xFFFFA726L, 0xFFE8E5DDL), 0.005)
        assertEquals(8.96, contrastRatio(0xFFFFA726L, 0xFF1A1A1AL), 0.005)
        assertTrue(contrastRatio(0xFFFFA726L, 0xFFF0EEE8L) < WCAG_AA_NORMAL_TEXT)

        // --- 旧 ツール完了グリフ `#6FBF73`(ChatScreen に直書きされていた)---
        assertEquals(1.93, contrastRatio(0xFF6FBF73L, 0xFFF0EEE8L), 0.005)
        assertEquals(1.78, contrastRatio(0xFF6FBF73L, 0xFFE8E5DDL), 0.005)
        assertTrue(contrastRatio(0xFF6FBF73L, 0xFFF0EEE8L) < WCAG_AA_NORMAL_TEXT)
    }

    private fun assertAllPass(label: String, scheme: ColorScheme, status: StatusColors) {
        val pairs = themeContrastPairs(scheme, status)
        assertTrue("$label: 検査対象が0件", pairs.isNotEmpty())
        val report = pairs.joinToString("\n") { p ->
            "  %-46s %6.2f:1 (min %.1f) %s".format(
                p.name, p.ratio, p.minimum, if (p.passes) "OK" else "NG",
            )
        }
        // 数値を報告に貼れるよう常に出す(合格でも出す ——「測った」ことの証跡)。
        println("[contrast:$label]\n$report")
        val failures = pairs.filter { !it.passes }
        assertTrue(
            "$label で基準を満たさない対:\n" +
                failures.joinToString("\n") { "  ${it.name} = %.2f:1 (min %.1f)".format(it.ratio, it.minimum) },
            failures.isEmpty(),
        )
        // ratio が常に 1.0 以上 21.0 以下であること(計算の健全性)。
        pairs.forEach { p ->
            assertTrue("${p.name} の比が範囲外: ${p.ratio}", p.ratio in 1.0..21.0001)
        }
    }

}
