package dev.opencode.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import dev.opencode.android.ui.DiffFileList
import dev.opencode.android.ui.DiffFileUi
import dev.opencode.android.ui.MIN_TOUCH_TARGET
import dev.opencode.android.ui.parseUnifiedDiff
import dev.opencode.android.ui.theme.DarkStatusColors
import dev.opencode.android.ui.theme.LightStatusColors
import dev.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 差分ビューアの**描画そのもの**に検出器を置く(Q6 が入れた Compose UI テスト基盤の上)。
 *
 * ## なぜこれが要るのか
 *
 * Q6 のレビューが挙げた3つの「存在しない検出」のうち1つは
 * 「**テストはトークンを見ており、画面が CompositionLocal を読んでいるかは誰も見ていない**」
 * だった。差分の色分けはまさにその形の危険がある —— [dev.opencode.android.ui.theme.StatusColors]
 * の値をテストしても、`DiffLineRow` が `DarkStatusColors` を直に読んでいたら
 * **ライトテーマでだけ読めない差分**になり、画面はどこも壊れて見えない。
 *
 * したがって**解決済みの色を `content-desc` に載せ**、本物の composable を両テーマで描いて
 * 出てきた文字列を比べる。これは dump からも同じ形で引用できる(judge はスクショを見られない)。
 *
 * **自前の composable を押さない。** テストが描くのは `DiffFileList` そのものである。
 */
@RunWith(RobolectricTestRunner::class)
class Q7ComposeDiffTest {

    @get:Rule
    val compose = createComposeRule()

    private fun fileOf(path: String, patch: String, expanded: Boolean, maxLines: Int = 5000) = DiffFileUi(
        path = path,
        status = "modified",
        additions = 1,
        deletions = 1,
        patch = patch,
        expanded = expanded,
        parsed = if (expanded) parseUnifiedDiff(patch, maxLines).firstOrNull() else null,
    )

    // ---------------------------------------------------------------------
    // 折り畳み(スコープ2)
    // ---------------------------------------------------------------------

    /** **既定は折り畳み。** 折り畳んだままなら差分の行は1つも描かれない。 */
    @Test
    fun `折り畳んだファイルは行を描かない`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                DiffFileList(
                    files = listOf(fileOf("big.py", Q7DiffFixtures.TWO_HUNKS, expanded = false)),
                    onToggleFile = {},
                )
            }
        }
        compose.onNodeWithContentDescription("diff-file:big.py:collapsed").assertExists()
        assertEquals(0, compose.onAllNodesWithContentDescription("diff-hunk:@@ -1,5 +1,5 @@").fetchSemanticsNodes().size)
    }

    @Test
    fun `展開するとハンクヘッダと行が出る`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                DiffFileList(
                    files = listOf(fileOf("big.py", Q7DiffFixtures.TWO_HUNKS, expanded = true)),
                    onToggleFile = {},
                )
            }
        }
        compose.onNodeWithContentDescription("diff-file:big.py:expanded").assertExists()
        compose.onNodeWithContentDescription("diff-hunk:@@ -1,5 +1,5 @@").assertExists()
        compose.onNodeWithContentDescription("diff-hunk:@@ -50,7 +50,7 @@ def f17():").assertExists()
    }

    /**
     * **見出しは押せるノードに `content-desc` が付いている**こと。
     *
     * Q3 と Q6 で3回踏んだ罠がこれ —— 子の Icon 側に付けると
     * dump は押せない 24dp のノードを返し、48dp を満たしているのに満たしていないと読める。
     */
    @Test
    fun `ファイル見出しは押せてタッチターゲットを満たす`() {
        var toggled: String? = null
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                DiffFileList(
                    files = listOf(fileOf("big.py", Q7DiffFixtures.TWO_HUNKS, expanded = false)),
                    onToggleFile = { toggled = it },
                )
            }
        }
        val node = compose.onNodeWithContentDescription("diff-file:big.py:collapsed")
        node.assertHasClickAction()
        node.assertHeightIsAtLeast(MIN_TOUCH_TARGET)
        node.performClick()
        assertEquals("big.py", toggled)
    }

    // ---------------------------------------------------------------------
    // 色分け(スコープ1)
    // ---------------------------------------------------------------------

    /**
     * **追加行と削除行の面の色は `LocalStatusColors` から来る。**
     *
     * ここを `DarkStatusColors` に固定する変異は、ダークでは通り
     * **ライトでだけ読めない差分**になる —— Q6 が実行中バッジで見つけたのと同じ形。
     * 解決済みの色を desc に載せているので、両テーマで**違う文字列**が出ることを主張できる。
     */
    @Test
    fun `ダークの差分の行はダークの面色で描かれる`() {
        val descs = renderLineDescriptions(darkTheme = true)
        assertEquals(
            setOf("diff-line:ADD:-:2:${DarkStatusColors.diffAddSurface.hex()}"),
            descs.filter { it.startsWith("diff-line:ADD:-:2:") }.toSet(),
        )
        assertEquals(
            setOf("diff-line:DELETE:2:-:${DarkStatusColors.diffDeleteSurface.hex()}"),
            descs.filter { it.startsWith("diff-line:DELETE:2:-:") }.toSet(),
        )
    }

    /**
     * **ライト側がこの組の要である。** `DarkStatusColors` を直に読む変異は
     * ダークのテストでは通り、ここでだけ落ちる —— Q6 が実行中バッジで見つけた
     * 「ライトの面でだけ 1.67:1」とまったく同じ形を、Q7 が新しく作れないようにする。
     */
    @Test
    fun `ライトの差分の行はライトの面色で描かれる`() {
        val descs = renderLineDescriptions(darkTheme = false)
        assertEquals(
            setOf("diff-line:ADD:-:2:${LightStatusColors.diffAddSurface.hex()}"),
            descs.filter { it.startsWith("diff-line:ADD:-:2:") }.toSet(),
        )
        assertEquals(
            setOf("diff-line:DELETE:2:-:${LightStatusColors.diffDeleteSurface.hex()}"),
            descs.filter { it.startsWith("diff-line:DELETE:2:-:") }.toSet(),
        )
    }

    /** 文脈行は面を塗らない(`none`)。塗ると差分の緑/赤が意味を失う。 */
    @Test
    fun `文脈行は面を塗らない`() {
        val context = renderLineDescriptions(darkTheme = true).filter { it.startsWith("diff-line:CONTEXT:") }
        assertTrue("文脈行が1つも描かれていない", context.isNotEmpty())
        assertEquals("文脈行に面色を塗らない", emptyList<String>(), context.filterNot { it.endsWith(":none") })
        assertTrue(context.contains("diff-line:CONTEXT:1:1:none"))
    }

    /** 末尾改行なしは行の desc に出る(dump から引用できる)。 */
    @Test
    fun `末尾改行なしは行のdescに出る`() {
        val descs = renderLineDescriptions(darkTheme = true, patch = Q7DiffFixtures.NO_NEWLINE)
        assertEquals(2, descs.count { it.endsWith(":no-eol") })
    }

    // ---------------------------------------------------------------------
    // バイナリと打ち切り(黙って切らない)
    // ---------------------------------------------------------------------

    /**
     * **横スクロールの位置が dump から引用できる**こと。
     *
     * 実機で測ったところ `uiautomator dump` の `bounds` は**スクロール容器で切り取られた矩形**で、
     * 横に送っても1pxも動かない —— §5b Q7 のゲート「横スクロールで長い行の末尾に到達できる
     * (dump の bounds で確認)」は bounds では閉じられない。
     * ここは「計器が居ること」だけを主張する(**到達したかどうかは実機でしか測れない**)。
     */
    @Test
    fun `横スクロールの位置がdescに出る`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                DiffFileList(
                    files = listOf(fileOf("big.py", Q7DiffFixtures.TWO_HUNKS, expanded = true)),
                    onToggleFile = {},
                )
            }
        }
        compose.onNodeWithContentDescription("diff-hscroll:", substring = true).assertExists()
    }

    /**
     * **短い行が混ざっていても横スクロールが死なない。**
     *
     * 1周目は全行の `horizontalScroll` が同じ
     * [androidx.compose.foundation.ScrollState] を共有していた。その layout は
     * 測るたびに `maxValue = 内容幅 - 表示幅` を**書き込む**ので、1つの `ScrollState` を
     * N 行が取り合い、**最後に測られた短い行が 0 を書いて横スクロールが効かなくなる**。
     * 全行が長ければ症状が出ないので Q7 では露見せず、短い行が普通に混ざる Q9(端末)の
     * E2E ゲートで初めて実測された(**Q7 から続く既存パターンの問題**)。
     *
     * 入力は**短い行のあとに長い行、そのあともう一度短い行**。1周目の形では
     * 最後の短い行が `maxValue` を 0 で上書きする。
     */
    @Test
    fun `短い行が混ざっても横スクロールの最大値が潰れない`() {
        val patch = buildString {
            append("--- a/wide.py\n+++ b/wide.py\n@@ -1,3 +1,3 @@\n")
            append(" ok\n")
            append("+" + "x".repeat(400) + "\n")
            append(" ok2\n")
        }
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                DiffFileList(
                    files = listOf(fileOf("wide.py", patch, expanded = true)),
                    onToggleFile = {},
                )
            }
        }
        val desc = compose.onNodeWithContentDescription("diff-hscroll:", substring = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()
        assertTrue("短い行が maxValue を 0 に潰している: $desc", desc.substringAfter("/").toInt() > 0)
    }

    /**
     * **`patch` が来なかったファイルを「バイナリ」と呼ばない**(レビュー minor-1)。
     *
     * `patch` は契約上「任意」なので、これは**契約が許す入力**である。
     * 1周目はこの経路で「バイナリファイル(差分を表示できません)」と表示していた ——
     * 「読めない」と「来なかった」の区別を消す形で、Q0 の R1 / Q4 の除外件数 /
     * Q6 の打ち切り注記で繰り返し閉じてきた原則そのものだった。
     */
    @Test
    fun `patch が無いファイルはバイナリと表示しない`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                DiffFileList(
                    files = listOf(
                        DiffFileUi(
                            path = "a.txt",
                            status = "modified",
                            additions = 1,
                            deletions = 0,
                            patch = null,
                            expanded = true,
                            parsed = null,
                        ),
                    ),
                    onToggleFile = {},
                )
            }
        }
        compose.onNodeWithContentDescription("diff-patch-missing").assertExists()
        assertEquals(
            "バイナリと言わない",
            0,
            compose.onAllNodesWithContentDescription("diff-binary").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `バイナリは表示できないと明示する`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                DiffFileList(
                    files = listOf(fileOf("image.bin", Q7DiffFixtures.BINARY, expanded = true)),
                    onToggleFile = {},
                )
            }
        }
        compose.onNodeWithContentDescription("diff-binary").assertExists()
    }

    /**
     * **打ち切りを黙って行わない**(§5b Q7 リスク欄 / Q0 の R1 と同じ原則)。
     * 本当の行数が desc に載るので、「切った」ことと「どれだけ切ったか」が引用できる。
     */
    @Test
    fun `打ち切ったら本当の行数を出す`() {
        val body = (1..40).joinToString("\n") { "+line $it" }
        val patch = "diff --git a/x b/x\nnew file mode 100644\n--- /dev/null\n+++ b/x\n@@ -0,0 +1,40 @@\n$body\n"
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                DiffFileList(
                    files = listOf(fileOf("x", patch, expanded = true, maxLines = 5)),
                    onToggleFile = {},
                )
            }
        }
        compose.onNodeWithContentDescription("diff-truncated:40").assertExists()
    }

    // ---------------------------------------------------------------------

    private fun renderLineDescriptions(
        darkTheme: Boolean,
        patch: String = Q7DiffFixtures.TWO_HUNKS,
    ): List<String> {
        compose.setContent {
            OpenCodeTheme(darkTheme = darkTheme) {
                DiffFileList(files = listOf(fileOf("f", patch, expanded = true)), onToggleFile = {})
            }
        }
        compose.waitForIdle()
        return compose.onAllNodesWithContentDescription("diff-line:", substring = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            }
    }

    private fun androidx.compose.ui.graphics.Color.hex(): String {
        fun ch(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(ch(red), ch(green), ch(blue))
    }
}
