package dev.opencode.android

import dev.opencode.android.ui.LineScrollState
import dev.opencode.android.ui.lineScrollClamp
import dev.opencode.android.ui.lineScrollMaxValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 横スクロール(`diff-hscroll` / `file-hscroll` / `pty-hscroll`)の共有状態。
 *
 * ## ここが固定している症状
 *
 * Q7〜Q9 の3画面は**同じ [androidx.compose.foundation.ScrollState] を全行の
 * `horizontalScroll` に渡していた**。`horizontalScroll` の layout は測るたびに
 * `maxValue = 内容幅 - 表示幅` を**書き込む**ので、1つの `ScrollState` を N 行が
 * 取り合い、**最後に測られた行の値だけが残る**。表示窓に短い行が1つでも入れば
 * `maxValue` は 0 に潰れ、**同じ画面に長い行が出ていても横スクロールが効かない**。
 *
 * 全行が長ければ症状が出ないので、Q7(差分)と Q8(ファイル)では露見せず、
 * 短い行が普通に混ざる Q9(端末)の E2E ゲートで初めて実測された。
 *
 * [LineScrollState] は「最後に測られた行が勝つ」を「**最大幅が勝つ**」に変える。
 * 下の `短い行が混ざっても長い行の到達範囲が縮まない` が、その1点の検出器である
 * (`widths.values.maxOrNull()` を `lastOrNull()` / `minOrNull()` に変える変異、
 * および `reportLine` が幅を上書きで1つしか持たない形への退行がここで落ちる)。
 */
class LineScrollTest {

    // ---- 純関数 ----

    @Test
    fun `内容が表示窓に収まっているなら送れる量は0`() {
        assertEquals(0, lineScrollMaxValue(contentWidth = 300, viewportWidth = 800))
        assertEquals(0, lineScrollMaxValue(contentWidth = 800, viewportWidth = 800))
    }

    @Test
    fun `はみ出したぶんだけ送れる`() {
        assertEquals(1200, lineScrollMaxValue(contentWidth = 2000, viewportWidth = 800))
    }

    @Test
    fun `位置は範囲に収まる`() {
        assertEquals(0, lineScrollClamp(-5, 100))
        assertEquals(100, lineScrollClamp(999, 100))
        assertEquals(42, lineScrollClamp(42, 100))
    }

    // ---- 状態(症状そのもの) ----

    /** **これが Q9 E2E の症状の検出器である。** */
    @Test
    fun `短い行が混ざっても長い行の到達範囲が縮まない`() {
        val state = LineScrollState()
        state.reportLine("long", contentWidth = 3000, viewportWidth = 800)
        assertEquals("長い行だけなら送れる", 2200, state.maxValue)

        // 短い行が**後から**測られる。1周目はここで maxValue が 0 になっていた。
        state.reportLine("short", contentWidth = 120, viewportWidth = 800)
        assertEquals("短い行は到達範囲を縮めない", 2200, state.maxValue)

        state.reportLine("empty", contentWidth = 0, viewportWidth = 800)
        assertEquals(2200, state.maxValue)
    }

    @Test
    fun `短い行しか出ていないなら送れない`() {
        val state = LineScrollState()
        state.reportLine("a", contentWidth = 100, viewportWidth = 800)
        state.reportLine("b", contentWidth = 200, viewportWidth = 800)
        assertEquals(0, state.maxValue)
    }

    @Test
    fun `同じ行を測り直すと幅が置き換わる`() {
        val state = LineScrollState()
        state.reportLine("a", contentWidth = 3000, viewportWidth = 800)
        state.reportLine("a", contentWidth = 900, viewportWidth = 800)
        assertEquals("同じ鍵は上書き(古い幅を持ち越さない)", 100, state.maxValue)
    }

    /** 表示窓から出た行の幅を持ち越すと、居ない行が到達範囲を広げ続ける。 */
    @Test
    fun `表示窓から出た行の幅は捨てる`() {
        val state = LineScrollState()
        state.reportLine("long", contentWidth = 3000, viewportWidth = 800)
        state.reportLine("short", contentWidth = 100, viewportWidth = 800)
        state.forgetLine("long")
        assertEquals(0, state.maxValue)
    }

    @Test
    fun `送った量は範囲の中で積み上がる`() {
        val state = LineScrollState()
        state.reportLine("long", contentWidth = 3000, viewportWidth = 800)
        assertEquals("消費した量を返す", 500f, state.scrollBy(500f), 0.01f)
        assertEquals(500, state.value)
        // 端で止まる。**消費できなかったぶんは返さない**(親へ渡せるように)。
        assertEquals(1700f, state.scrollBy(9999f), 0.01f)
        assertEquals(2200, state.value)
        assertEquals("端では0しか消費しない", 0f, state.scrollBy(10f), 0.01f)
        assertEquals(-2200f, state.scrollBy(-9999f), 0.01f)
        assertEquals(0, state.value)
    }

    /** 長い行が表示窓から出たら、送り過ぎた位置も戻る(空白だけの画面を出さない)。 */
    @Test
    fun `到達範囲が縮んだら位置も縮む`() {
        val state = LineScrollState()
        state.reportLine("long", contentWidth = 3000, viewportWidth = 800)
        state.scrollBy(2200f)
        assertEquals(2200, state.value)
        state.forgetLine("long")
        state.reportLine("short", contentWidth = 200, viewportWidth = 800)
        assertEquals(0, state.maxValue)
        assertEquals(0, state.value)
    }

    @Test
    fun `別の中身に入れ替えたら先頭へ戻る`() {
        val state = LineScrollState()
        state.reportLine("long", contentWidth = 3000, viewportWidth = 800)
        state.scrollBy(1000f)
        state.reset()
        assertEquals(0, state.value)
        assertEquals(0, state.maxValue)
    }

    /** 表示窓の幅が変わったら(回転など)到達範囲も変わる。 */
    @Test
    fun `表示窓の幅は最後に測ったものが効く`() {
        val state = LineScrollState()
        state.reportLine("long", contentWidth = 3000, viewportWidth = 800)
        assertEquals(2200, state.maxValue)
        state.reportLine("long", contentWidth = 3000, viewportWidth = 1600)
        assertEquals(1400, state.maxValue)
        assertEquals(1600, state.viewportWidth)
        assertEquals(3000, state.contentWidth)
    }
}
