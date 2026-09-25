package dev.opencode.android

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.ui.AgentChoice
import dev.opencode.android.ui.AgentPickerRows
import dev.opencode.android.ui.ChatTopBarActions
import dev.opencode.android.ui.ChatUi
import dev.opencode.android.ui.EmptyStateAction
import dev.opencode.android.ui.EmptyStateView
import dev.opencode.android.ui.HapticsSettingRow
import dev.opencode.android.ui.InputRow
import dev.opencode.android.ui.MIN_TOUCH_TARGET
import dev.opencode.android.ui.ModelChoice
import dev.opencode.android.ui.ModelRow
import dev.opencode.android.ui.ModelSelectRow
import dev.opencode.android.ui.SessionCard
import dev.opencode.android.ui.SessionRunState
import dev.opencode.android.ui.selectAbortAction
import dev.opencode.android.ui.selectModelPill
import dev.opencode.android.ui.sessionListEmptyState
import dev.opencode.android.ui.theme.DarkColors
import dev.opencode.android.ui.theme.DarkStatusColors
import dev.opencode.android.ui.theme.LightStatusColors
import dev.opencode.android.ui.theme.OpenCodeTheme
import dev.opencode.android.ui.theme.toArgbLong
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Q6 スコープ1: **タッチターゲット48dp**と、**測られる物と押される物の一致**を JVM 側で押さえる。
 *
 * ## これは実機の代わりではない
 *
 * RUN_PLAN が Q3 の実測から残した警告のうち、ここで閉じられるのは
 * 「**`content-desc` が押せるノードに付いているか**」と宣言サイズだけである。
 * 「**宣言された bounds は実効的な当たり判定と一致しない**」(Q6 実測: 送信ボタンの
 * 実効は 44.4dp × 48.0dp、左端の約11pxが反応しない)は実際にタップするしかない。
 * **JVM で緑になったことを「48dp を満たした」と読まないこと。**
 */
// SDK は `app/src/test/resources/robolectric.properties` で**1か所だけ**宣言する
// (レビュー D3: `@Config` を各クラスに書くと片方だけ古い値のまま残る)。
@RunWith(RobolectricTestRunner::class)
class Q6TouchTargetTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * 送信ボタンのタッチターゲット。
     *
     * ## unmerged tree で測る理由(レビュー blocker M12)
     *
     * 1周目は `onNodeWithContentDescription("送信")` だけで測っていた。**これでは
     * `contentDescription` を `Icon` 側へ戻す変異が検出できない** —— `IconButton` は
     * 子孫のセマンティクスをマージするので、desc がどちらに付いていても
     * merged tree では**同じ 48dp のノードに解決する**。KDoc は「Icon 側へ戻す変異は
     * ここで落ちる」と書いていたが、**落ちなかった**。存在しない検出を約束していた。
     *
     * 直し方も1つ試して外した: `useUnmergedTree` で高さを測る形にしたところ、
     * **desc を `IconButton` に載せていても unmerged では 40dp** だった ——
     * `IconButton` は `minimumInteractiveComponentSize()` の**内側**の 40dp ノードに
     * 渡されたモディファイアを載せるためで、48dp を要求すると正しい実装でも落ちる。
     *
     * 主張すべきは高さではなく「**desc が載っているノードが、押せるノードでもある**」
     * という性質そのものである。`hasContentDescription(...) and hasClickAction()` を
     * unmerged tree で当てる —— Icon 側に載せれば Icon には onClick が無いので一致しない。
     * これが Q3 の「測られる物と押される物を一致させる」の機械表現である。
     *
     * **実機での意味は別途 dump で確かめる**(desc をどちらに載せたとき uiautomator が
     * 何 px を返すかは Compose のマージ規則の話であり、JVM からは決められない)。
     */
    @Test
    fun `送信ボタンは48dp かつ desc が押せるノードに載っている`() {
        compose.setContent {
            OpenCodeTheme {
                InputRow(
                    draft = "hello",
                    enabled = true,
                    busy = false,
                    sessionDeleted = false,
                    onDraftChange = {},
                    onSend = {},
                )
            }
        }
        // merged: タッチターゲットとして 48dp(Material の最小強制も含む)
        compose.onNodeWithContentDescription("送信")
            .assertTouchHeightIsEqualTo(MIN_TOUCH_TARGET)
            .assertTouchWidthIsEqualTo(MIN_TOUCH_TARGET)
        // unmerged: **desc が載っているノードが、押せるノードでもある**こと
        compose.onNode(
            hasContentDescription("送信") and hasClickAction(),
            useUnmergedTree = true,
        ).assertExists()
    }

    /**
     * 中断ボタン(レビュー major-2)。1周目は `content-desc` が**どこにも無く**、
     * **アプリで最も安全に関わるコントロールが dump から同定できなかった**。
     */
    @Test
    fun `中断ボタンは desc を持ち48dp`() {
        val busy = ChatUi(busy = true)
        compose.setContent {
            OpenCodeTheme {
                Row {
                    ChatTopBarActions(
                        pill = selectModelPill(busy),
                        abort = selectAbortAction(busy),
                        onOpenModelSheet = {},
                        onAbort = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("chat-abort:ready")
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
        // desc は押せるノードに載っていること(送信ボタンと同じ規則)。
        compose.onNode(
            hasContentDescription("chat-abort:ready") and hasClickAction(),
            useUnmergedTree = true,
        ).assertExists()
    }

    /**
     * **モデルピルの位置が busy をまたいで動かないこと**(レビュー major-1)。
     *
     * レビューが実機で確定させた幾何:
     * ```
     * busy : ピル [671,158][909,290]   中断 [909,158][1069,290]
     * idle : ピル [831,158][1069,290]  (中断は無い)
     * ```
     * **x=989 は busy では中断、idle ではピル。** 実行が終わりかけたところで
     * 「中断」を押すと、ボタンが消えてピルが指の下へ広がり、モデル選択シートが開く。
     * 中断のための枠を常に予約することで直した。ここはその不変条件そのものを見張る。
     */
    @Test
    fun `モデルピルの位置は busy をまたいで動かない`() {
        var busy by mutableStateOf(true)
        compose.setContent {
            OpenCodeTheme {
                // **TopAppBar の actions は右寄せの Row** である。左寄せで並べて測ると
                // ピルは常に x=0 に置かれ、枠の予約を外しても左端が動かない ——
                // 1周目のこのテストはその形で、`Modifier.width(ABORT_SLOT_WIDTH)` を
                // 外す変異を**通してしまった**。実物と同じ右寄せで測る。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    val ui = ChatUi(busy = busy)
                    ChatTopBarActions(
                        pill = selectModelPill(ui),
                        abort = selectAbortAction(ui),
                        onOpenModelSheet = {},
                        onAbort = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val pillDesc = selectModelPill(ChatUi())!!.description
        val whileBusy: DpRect = compose.onNodeWithContentDescription(pillDesc).getUnclippedBoundsInRoot()
        // 中断が出ていることを確かめてから消す(陰性コントロール)。
        compose.onNodeWithContentDescription("chat-abort:ready").assertHeightIsAtLeast(MIN_TOUCH_TARGET)

        busy = false
        compose.waitForIdle()
        compose.onNodeWithContentDescription("chat-abort:ready").assertDoesNotExist()
        val whileIdle: DpRect = compose.onNodeWithContentDescription(pillDesc).getUnclippedBoundsInRoot()

        assertEquals("busy をまたいでピルの左端が動いた", whileBusy.left, whileIdle.left)
        assertEquals("busy をまたいでピルの右端が動いた", whileBusy.right, whileIdle.right)
    }

    /**
     * **画面が `LocalStatusColors` を読んでいること**(レビュー blocker M3)。
     *
     * `Q6ContrastTest` は**トークン**を検査しているが、**画面がそのトークンを読んでいるか**は
     * 誰も見ていなかった。`runStateColor(runState, DarkStatusColors)` と固定する変異は
     * 496件全緑で通り抜け、ライトで `#6FBF73` on `#F0EEE8` = 1.93:1 が戻る ——
     * **Q6 が直した欠陥の現場そのもの**である。
     *
     * 色は semantics に出ないので、バッジの `content-desc` に**解決した色そのもの**を載せた
     * (実機の dump からも同じことを引用できる)。
     */
    @Test
    fun `実行中バッジの色はテーマから来る`() {
        val session = SessionDto(id = "ses_1", title = "t", time = SessionTimeDto(1, 1))
        compose.setContent {
            Column {
                OpenCodeTheme(darkTheme = true) {
                    SessionCard(session, SessionRunState.BUSY, false, 1L, ZoneId.of("UTC"), {}, {})
                }
                OpenCodeTheme(darkTheme = false) {
                    SessionCard(session, SessionRunState.RETRY, false, 1L, ZoneId.of("UTC"), {}, {})
                }
            }
        }
        compose.waitForIdle()
        val darkBusy = "run-state:busy:#%08X".format(DarkStatusColors.running.toArgbLong())
        val lightRetry = "run-state:retry:#%08X".format(LightStatusColors.retry.toArgbLong())
        compose.onNodeWithContentDescription(darkBusy, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(lightRetry, useUnmergedTree = true).assertExists()
        // 陰性コントロール: ダーク固定の変異が入ると light 側にダークの色が出る。
        val darkRetry = "run-state:retry:#%08X".format(DarkStatusColors.retry.toArgbLong())
        assertTrue("2つのテーマが同じ retry 色なら検査が成立しない", darkRetry != lightRetry)
        compose.onNodeWithContentDescription(darkRetry, useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * 空状態の導線。401 側は**再試行のノードが存在しない**ことも同時に押さえる ——
     * 「押しても直らないボタンを出さない」は文言ではなくノードの有無で主張する。
     */
    @Test
    fun `空状態の導線は48dp で 401では再試行が存在しない`() {
        val failure = sessionListEmptyState(0, "NET boom", authFailure = false, search = "")!!
        val auth = sessionListEmptyState(0, "HTTP 401", authFailure = true, search = "")!!
        compose.setContent {
            OpenCodeTheme {
                Column {
                    EmptyStateView(spec = failure, onAction = {})
                    EmptyStateView(spec = auth, onAction = {})
                }
            }
        }
        assertEquals(2, failure.actions.size)
        failure.actions.forEach { action ->
            compose.onNodeWithContentDescription("empty-state-action:${failure.key}:${action.action.name}")
                .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
        }
        compose.onNodeWithContentDescription(
            "empty-state-action:${auth.key}:${EmptyStateAction.RETRY.name}",
        ).assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "empty-state-action:${auth.key}:${EmptyStateAction.OPEN_SETTINGS.name}",
        ).assertHeightIsAtLeast(MIN_TOUCH_TARGET)
    }

    /**
     * 素の `Modifier.clickable` を当てた行(モデル選択・エージェント選択)。
     *
     * **注意(1周目の素通しの説明)**: これらの行は**自前のパディングだけで既に 48dp を超えている**ので、
     * `heightIn(min = MIN_TOUCH_TARGET)` を外しても落ちない。守っているのは行の**総高**であり、
     * パディングを縮める変異(12dp → 2dp)では実際に落ちる(実測: `Actual height is 40.0.dp`)。
     * `heightIn` は今日の値を変えない**番人**であって、この assert の対象ではない。
     */
    @Test
    fun `モデル選択行とエージェント行は48dp`() {
        compose.setContent {
            OpenCodeTheme {
                Column {
                    ModelSelectRow(
                        label = "モデル",
                        value = null,
                        description = "create-model-row:default",
                        onClick = {},
                    )
                    AgentPickerRows(
                        agents = listOf(AgentChoice(name = "build", description = null)),
                        selected = null,
                        onSelect = {},
                    )
                    ModelRow(
                        choice = ModelChoice(
                            providerId = "mistral",
                            providerName = "Mistral",
                            modelId = "mistral-medium-latest",
                            modelName = "Mistral Medium",
                        ),
                        selected = false,
                        onClick = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("create-model-row:default")
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
        compose.onNodeWithContentDescription("agent-option:default:selected")
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
        compose.onNodeWithContentDescription("agent-option:build:unselected")
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
        compose.onNodeWithContentDescription("model-option:mistral/mistral-medium-latest:unselected")
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
    }

    /**
     * 起動ウィンドウの色(申し送り Q5-3)が **`Theme.kt` の `Ink0` と同じ値**であること。
     * 3か所(リソース / Compose / `MainActivity` の定数)に散っているので、
     * 片方だけ変えた状態を検出する。**算術は `toArgbLong()` を使う**(レビュー D5:
     * 1周目は同じ計算を手書きで複製しており、複製のほうが間違っても気づけなかった)。
     */
    @Test
    fun `起動ウィンドウの背景色はダークテーマの背景と同じ`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resource = context.getColor(R.color.window_background).toLong() and 0xFFFFFFFFL
        assertEquals(0xFF0F0F0FL, resource)
        assertEquals(DarkColors.background.toArgbLong(), resource)
    }
    /**
     * 触覚トグル(Q6 レビュー2周目の走査で見つけた欠陥)。
     *
     * **押せるのはスイッチなのに desc は囲みの Row に付いていた** ——
     * 実機 dump ではスイッチ本体が `143x96px = 52 x 34.9dp` の**無名ノード**だった。
     * desc を押せるノードへ移し、48dp を保証した。
     */
    @Test
    fun `触覚トグルは押せるノードに desc を持ち48dp`() {
        var enabled by mutableStateOf(true)
        compose.setContent {
            OpenCodeTheme { HapticsSettingRow(enabled = enabled, onChange = { enabled = it }) }
        }
        compose.onNodeWithContentDescription("settings-haptics:on")
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
        // desc は押せる(トグルできる)ノードに載っていること。
        compose.onNode(
            hasContentDescription("settings-haptics:on") and hasClickAction(),
            useUnmergedTree = true,
        ).assertExists()
        // 状態が desc に出ること(dump から両方向を引用できる)。
        compose.onNodeWithContentDescription("settings-haptics:on").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("settings-haptics:off").assertExists()
    }
}
