package dev.opencode.android

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.opencode.android.data.ColorMode
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.ui.ColorModeOptions
import dev.opencode.android.ui.EmptyStateAction
import dev.opencode.android.ui.EmptyStateView
import dev.opencode.android.ui.MIN_TOUCH_TARGET
import dev.opencode.android.ui.PasswordVisibilityToggle
import dev.opencode.android.ui.SessionCard
import dev.opencode.android.ui.SessionRunState
import dev.opencode.android.ui.sessionListEmptyState
import dev.opencode.android.ui.TailscaleSection
import dev.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

/**
 * **`content-desc` を持つクリックノードは、意味論を持つ子を1つも持たないこと** —— 横断の検出器。
 *
 * ## なぜ画面をまたいで1つのテストにしたか
 *
 * この欠陥形は `open-tailscale` で **E2Eゲートを3回連続で落とした**。当時の検出器
 * (`assertHasClickAction` / unmerged ツリーで `OnClick` が同じノードに載っている)は
 * **3回とも通ったまま実機が落ちた**。実機の `uiautomator dump` が読むのは
 * `AccessibilityNodeInfo` ツリーで、Compose UI (1.7 系) は「descendants を merge するノード」が
 * 「意味論を持つ子」を1つでも抱えていると、その `contentDescription` を
 * **clickable=false の fake node** へ切り出すためである。
 *
 * `f3b0855` で `open-tailscale` だけ直したが、**同じ形が3箇所に残っている**ことが
 * 最終ゲートの dump で確認されていた(`show-password`/`hide-password`・
 * `color-mode-option:*`・`session-card:*`)。箇所ごとに書き写すのではなく
 * [dev.opencode.android.ui.DescribedClickSurface] に機構を1つにまとめ、
 * **この1つの検出器で全箇所を同時に押さえる**。
 *
 * 子に `clearAndSetSemantics {}` を付けても「意味論を持つ子」として数えられる(実機で計測済み)ので、
 * この検出器は**素の子ノード数**で見る。
 */
@RunWith(RobolectricTestRunner::class)
class ContentDescClickNodeTest {

    @get:Rule
    val rule = createComposeRule()

    /** `desc` を持つノードが「押せる」かつ「意味論の子を持たない」ことを見る。 */
    private fun assertChildlessClickNode(desc: String) {
        rule.onNodeWithContentDescription(desc).assertHasClickAction()
        val node = rule.onNodeWithContentDescription(desc, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(
            "$desc: content-desc を持つノードに意味論の子が居ると、実機の dump では " +
                "content-desc が clickable=false の fake node へ移る",
            emptyList<String>(),
            node.children.map { it.id.toString() },
        )
    }

    @Test
    fun `open-tailscale の content-desc は子を持たないクリックノードに載る`() {
        rule.setContent { OpenCodeTheme { TailscaleSection(onLaunch = { true }) } }
        assertChildlessClickNode("open-tailscale")
    }

    @Test
    fun `show-password の content-desc は子を持たないクリックノードに載る`() {
        rule.setContent { OpenCodeTheme { PasswordVisibilityToggle(visible = false, onToggle = {}) } }
        assertChildlessClickNode("show-password")
    }

    @Test
    fun `hide-password の content-desc は子を持たないクリックノードに載る`() {
        rule.setContent { OpenCodeTheme { PasswordVisibilityToggle(visible = true, onToggle = {}) } }
        assertChildlessClickNode("hide-password")
    }

    @Test
    fun `color-mode-option の content-desc は子を持たないクリックノードに載る`() {
        rule.setContent {
            OpenCodeTheme { ColorModeOptions(current = ColorMode.SYSTEM, onSelect = {}) }
        }
        assertChildlessClickNode("color-mode-option:SYSTEM:selected")
        assertChildlessClickNode("color-mode-option:DARK:normal")
        assertChildlessClickNode("color-mode-option:LIGHT:normal")
    }

    @Test
    fun `session-card の content-desc は子を持たないクリックノードに載る`() {
        rule.setContent {
            OpenCodeTheme {
                SessionCard(
                    session = SessionDto(id = "ses_1", title = "t", time = SessionTimeDto(1, 1)),
                    runState = SessionRunState.IDLE,
                    pending = false,
                    nowMs = 1L,
                    zone = ZoneId.of("UTC"),
                    onClick = {},
                    onLongClick = {},
                )
            }
        }
        assertChildlessClickNode("session-card:ses_1:idle")
    }

    @Test
    fun `empty-state-action の content-desc は子を持たないクリックノードに載る`() {
        val spec = sessionListEmptyState(0, "NET boom", authFailure = false, search = "")!!
        rule.setContent { OpenCodeTheme { EmptyStateView(spec = spec, onAction = {}) } }

        assertEquals(2, spec.actions.size)
        spec.actions.forEach { action ->
            assertChildlessClickNode("empty-state-action:${spec.key}:${action.action.name}")
        }
    }

    /**
     * **空状態の導線は押すとハンドラへ届くこと**、かつ **48dp を割らないこと**。
     *
     * クリック面を重ねる形にしたので、当たり判定の高さを決めるのは
     * `TextButton` ではなく**外側の Box の最小高**になった。ここが崩れると
     * 「押せるが小さい」という Q3 の欠陥形に戻る。
     */
    @Test
    fun `空状態の導線は押下が届き 48dp を割らない`() {
        val spec = sessionListEmptyState(0, "NET boom", authFailure = false, search = "")!!
        val fired = mutableListOf<EmptyStateAction>()
        rule.setContent { OpenCodeTheme { EmptyStateView(spec = spec, onAction = { fired += it }) } }

        spec.actions.forEach { action ->
            val desc = "empty-state-action:${spec.key}:${action.action.name}"
            rule.onNodeWithContentDescription(desc).assertHeightIsAtLeast(MIN_TOUCH_TARGET)
            rule.onNodeWithContentDescription(desc).performClick()
        }
        rule.waitForIdle()

        assertEquals(spec.actions.map { it.action }, fired)
    }

    /**
     * **押下が本当にハンドラへ届くこと。**
     *
     * クリック面を重ねる形は「見た目のボタン」と「押せる面」が別物なので、
     * 面の側だけ意味論が整っていて配線が切れている、という壊れ方がありうる。
     */
    @Test
    fun `重ねたクリック面は押下をハンドラへ渡す`() {
        var toggles = 0
        var picked: ColorMode? = null
        rule.setContent {
            OpenCodeTheme {
                androidx.compose.foundation.layout.Column {
                    PasswordVisibilityToggle(visible = false, onToggle = { toggles++ })
                    ColorModeOptions(current = ColorMode.SYSTEM, onSelect = { picked = it })
                }
            }
        }

        rule.onNodeWithContentDescription("show-password").performClick()
        rule.onNodeWithContentDescription("color-mode-option:DARK:normal").performClick()
        rule.waitForIdle()

        assertEquals(1, toggles)
        assertEquals(ColorMode.DARK, picked)
    }

    /**
     * **セッションカードの子の意味論は消さないこと。**
     *
     * ここだけ他の3箇所と扱いが違う。`run-state:*` は E2E が dump から引用する独立した証拠
     * (Q6 レビュー blocker M3 で「画面がテーマの色を読んでいるか」を見るために置かれた)なので、
     * クリック面を重ねるついでに `clearAndSetSemantics {}` で消してしまうと**証拠が消える**。
     */
    @Test
    fun `セッションカードの run-state は content-desc として残る`() {
        rule.setContent {
            OpenCodeTheme(darkTheme = true) {
                SessionCard(
                    session = SessionDto(id = "ses_2", title = "t", time = SessionTimeDto(1, 1)),
                    runState = SessionRunState.BUSY,
                    pending = false,
                    nowMs = 1L,
                    zone = ZoneId.of("UTC"),
                    onClick = {},
                    onLongClick = {},
                )
            }
        }
        rule.onNodeWithContentDescription("run-state:busy:", substring = true, useUnmergedTree = true)
            .assertExists()
    }
}
