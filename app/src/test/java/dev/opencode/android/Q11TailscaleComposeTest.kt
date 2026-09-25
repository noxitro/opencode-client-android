package dev.opencode.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.opencode.android.ui.TailscaleSection
import dev.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Q11: 「Tailscaleを開く」の Compose 層の配線(レビュー minor-5 / minor-8)。
 *
 * **本物のコントロールを描いて押す**(Q6 のレビューが立てた規則。自分で組んだ
 * `Text{}.clickable{}` を押すのはトートロジーである)。
 *
 * ここで固定するのは2つ:
 *  - `open-tailscale` の `content-desc` が**押せるノードから読める**こと。
 *    以前は `OutlinedButton` の `modifier` に付いており、dump では押せないノードに載っていた。
 *  - 開けなかったときに**黙って終わらない**こと(`tailscale-launch:failed` が出る)。
 */
@RunWith(RobolectricTestRunner::class)
class Q11TailscaleComposeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `content-desc は押せるノードから読める`() {
        var calls = 0
        rule.setContent { OpenCodeTheme { TailscaleSection(onLaunch = { calls++; true }) } }

        rule.onNodeWithContentDescription("open-tailscale").assertHasClickAction().performClick()
        rule.waitForIdle()

        assertEquals(1, calls)
    }

    /**
     * **`content-desc` はラベルの `Text` ではなく、merge の根に載っていること。**
     *
     * 上のテストは merge 済みツリーを見るので、子 `Text` に付けた `content-desc` でも通る ——
     * 実際、実機 `uiautomator dump` では**クリック不可の子 TextView** に載っていて、
     * クリック可能な親の `content-desc` は空だった(E2E ゲートの実測)。
     * Compose の意味論ツリーと `AccessibilityNodeInfo` は同じものではないので、
     * 検出器を **unmerged ツリー**へ降ろす: `content-desc` を持つノードが `Text` を
     * 持っていたら、それは「ラベルのノードに付けた」ということである。
     */
    @Test
    fun `content-desc はラベルのノードではなく merge の根に載る`() {
        rule.setContent { OpenCodeTheme { TailscaleSection(onLaunch = { true }) } }

        rule.onNodeWithContentDescription("open-tailscale", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
    }

    /**
     * **`content-desc` と click アクションが、merge する前から同じノードに載っていること。**
     *
     * 上の2つでは足りない。1つ目は merge 済みツリーを見るので、子に付けた `content-desc` でも
     * 通ってしまう。2つ目は「ラベルのノードではない」しか言っておらず、
     * **ラベルでもコントロールでもない中間のラッパ**(`Modifier.semantics{}` だけを持つノード)は
     * 素通りする —— 実機 dump が2度続けて見せたのはまさにその形で、
     * `content-desc` を持つノードは `clickable="false"` だった。
     *
     * unmerged ツリーで `OnClick` まで要求すれば、`content-desc` が
     * **コントロールそのものの modifier に載っている**ことを固定できる。
     *
     * **これは実機の `AccessibilityNodeInfo` そのものではない。** Compose のノードと
     * `AccessibilityNodeInfo` は同じものではなく、この場で測れるのはここまでである
     * (実機での確認は E2E ゲートに委ねる)。
     */
    @Test
    fun `content-desc は click アクションと同じノードに載る`() {
        rule.setContent { OpenCodeTheme { TailscaleSection(onLaunch = { true }) } }

        rule.onNodeWithContentDescription("open-tailscale", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
    }

    /**
     * **`content-desc` を持つクリックノードは、意味論を持つ子を1つも持たないこと。**
     *
     * これが実機で3回失敗した本体である。`uiautomator dump` が読むのは
     * `AccessibilityNodeInfo` ツリーで、Compose UI (1.7 系) は
     * 「descendants を merge するノード」が「意味論を持つ子」を抱えていると、
     * その `contentDescription` を **clickable=false の fake node** へ切り出す
     * (`Modifier.clickable` は `shouldMergeDescendantSemantics = true`)。
     *
     * 上の2つのテスト(`assertHasClickAction` / unmerged で `OnClick` が同じノードにある)は
     * **3回とも通ったまま実機で落ちた**。同じノードに載っているだけでは足りず、
     * **子が居ないこと**が実機での成否を決める。ここを固定する。
     *
     * 子に `clearAndSetSemantics {}` を付けても「意味論を持つ子」としては数えられる
     * (実機で計測済み)ので、この検出器は素の子ノード数で見る。
     */
    @Test
    fun `content-desc を持つクリックノードは意味論を持つ子を持たない`() {
        rule.setContent { OpenCodeTheme { TailscaleSection(onLaunch = { true }) } }

        val node = rule.onNodeWithContentDescription("open-tailscale", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertEquals(
            "content-desc を持つノードに意味論の子が居ると、実機の dump では " +
                "content-desc が clickable=false の fake node へ移る",
            emptyList<String>(),
            node.children.map { it.id.toString() },
        )
    }

    @Test
    fun `開けたら失敗表示は出ない`() {
        rule.setContent { OpenCodeTheme { TailscaleSection(onLaunch = { true }) } }

        rule.onNodeWithContentDescription("open-tailscale").performClick()
        rule.waitForIdle()

        rule.onNodeWithContentDescription("tailscale-launch:idle").assertExists()
    }

    @Test
    fun `開けなかったら黙らずに失敗を出す`() {
        rule.setContent { OpenCodeTheme { TailscaleSection(onLaunch = { false }) } }

        rule.onNodeWithContentDescription("tailscale-launch:idle").assertExists()
        rule.onNodeWithContentDescription("open-tailscale").performClick()
        rule.waitForIdle()

        rule.onNodeWithContentDescription("tailscale-launch:failed").assertExists()
    }
}
