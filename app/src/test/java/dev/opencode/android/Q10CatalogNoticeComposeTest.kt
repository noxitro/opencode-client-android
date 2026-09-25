package dev.opencode.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.opencode.android.ui.ModelCatalogUi
import dev.opencode.android.ui.ModelChoice
import dev.opencode.android.ui.ModelPickerSheet
import dev.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **帯が実際にシートに描かれること**(Q10/Q11 差し戻し回収・回収項目C)。
 *
 * 純関数([dev.opencode.android.ui.modelCatalogNotice])の試験は
 * `Q4ControllerTest` にある。**それだけでは足りない** —— このプロジェクトが繰り返し
 * 記録している欠陥形は「純関数は完璧なのに、UI 配線層で呼ばれていない」であり、
 * 呼び出し行を消す変異は画面が正常に見えるまま機能だけを消す。
 * ここでは**本物の `ModelPickerSheet` を描いて**、`content-desc` が出ることを固定する。
 */
@RunWith(RobolectricTestRunner::class)
class Q10CatalogNoticeComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val model = ModelChoice(
        providerId = "mistral", providerName = "Mistral",
        modelId = "m1", modelName = "M1",
    )

    private fun show(state: ModelCatalogUi) {
        rule.setContent {
            OpenCodeTheme {
                ModelPickerSheet(
                    state = state,
                    currentModel = null,
                    title = "モデル",
                    onQueryChange = {},
                    onReload = {},
                    onRetry = {},
                    onOpenSettings = {},
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `古い一覧を出しているときは帯が出る`() {
        show(
            ModelCatalogUi(
                models = listOf(model),
                error = "モデル一覧の取得に失敗しました: NET boom",
                stale = true,
            ),
        )
        rule.onNodeWithContentDescription("model-picker-catalog-stale").assertExists()
    }

    @Test
    fun `取得できているときは帯を出さない`() {
        show(ModelCatalogUi(models = listOf(model), loaded = true))
        rule.onNodeWithContentDescription("model-picker-catalog-stale").assertDoesNotExist()
        rule.onNodeWithContentDescription("model-picker-catalog-error").assertDoesNotExist()
    }
}
