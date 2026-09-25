package dev.opencode.android

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.opencode.android.ui.ChatTopBarActions
import dev.opencode.android.ui.ChatUi
import dev.opencode.android.ui.HapticEvent
import dev.opencode.android.ui.HapticGate
import dev.opencode.android.ui.InputRow
import dev.opencode.android.ui.LocalHapticGate
import dev.opencode.android.ui.NoopHapticGate
import dev.opencode.android.ui.rememberHapticGate
import dev.opencode.android.ui.selectAbortAction
import dev.opencode.android.ui.selectModelPill
import dev.opencode.android.ui.theme.DarkColors
import dev.opencode.android.ui.theme.DarkStatusColors
import dev.opencode.android.ui.theme.LightColors
import dev.opencode.android.ui.theme.LightStatusColors
import dev.opencode.android.ui.theme.LocalStatusColors
import dev.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Q6 判断事項1: **Compose 層の配線に検出器を置く**。
 *
 * RUN_PLAN の到達点はここだった —— 「Compose の composable 内部から呼ばれる配線は
 * `runTest` から到達できない。**実機 dump / logcat が唯一の検出器である。**」
 *
 * ## 1周目の誤り(レビュー blocker)
 *
 * 「クリック経路がゲートを通る」というテストを書いたが、その中身は**テスト自身が組んだ**
 * `Text{}.clickable{ gate.perform(...) }` を検査しており、**`ChatScreen` に一度も触れていなかった**。
 * `haptics.perform(HapticEvent.SEND)` の行を消す変異は当然落ちない。
 * **自分で書いた呼び出しが呼ばれることを確かめるのはトートロジーである。**
 *
 * 直し方は2つあった: (a) `ChatScreen` を丸ごと描く (b) **触覚をコントロールの持ち物にする**。
 * (a) は `AppViewModel` → `AppContainer` → DataStore/OkHttp を Robolectric 上に立てることになり、
 * 測っているものが増えすぎる。(b) を採った —— 送信の触覚は [InputRow] が、
 * 中断の触覚は [ChatTopBarActions] が自分で鳴らす。**本物のコントロールを描いて押す。**
 *
 * 実機は今も要る(実効的な当たり判定・触覚の実発火・起動ウィンドウは JVM に無い)。
 */
// SDK は `app/src/test/resources/robolectric.properties` で**1か所だけ**宣言する
// (レビュー D3: `@Config` を各クラスに書くと片方だけ古い値のまま残る)。
@RunWith(RobolectricTestRunner::class)
class Q6ComposeWiringTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * [OpenCodeTheme] が `darkTheme` の値で **ColorScheme と StatusColors の両方**を切り替えること。
     *
     * 変異: `LocalStatusColors provides statusColorsFor(darkTheme)` を定数に変えると、
     * ライトテーマでダーク用のバッジ色が配られる。**画面はどこも壊れて見えない。**
     */
    @Test
    fun `テーマは配色と状態色を同じ判定で切り替える`() {
        var darkScheme: Any? = null
        var darkStatus: Any? = null
        var lightScheme: Any? = null
        var lightStatus: Any? = null

        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                darkScheme = MaterialTheme.colorScheme
                darkStatus = LocalStatusColors.current
            }
            OpenCodeTheme(darkTheme = false) {
                lightScheme = MaterialTheme.colorScheme
                lightStatus = LocalStatusColors.current
            }
        }
        compose.waitForIdle()

        assertEquals(DarkColors, darkScheme)
        assertEquals(DarkStatusColors, darkStatus)
        assertEquals(LightColors, lightScheme)
        assertEquals(LightStatusColors, lightStatus)
        assertTrue("2つのテーマが同じ値を配っていたら何も見ていない", darkStatus != lightStatus)
    }

    /**
     * [rememberHapticGate] の `enabled` は**呼ばれた瞬間の値**を読むこと。
     * `rememberUpdatedState` を外すとゲートは最初の値のまま固まる。
     */
    @Test
    fun `触覚ゲートは最新のトグル値を読む`() {
        var toggle by mutableStateOf(false)
        var gate: HapticGate? = null

        compose.setContent { gate = rememberHapticGate(toggle) }
        compose.waitForIdle()

        val first = gate
        assertNotNull(first)
        assertTrue("初期値 false のとき ON と読んではいけない", !first!!.isEnabled)

        toggle = true
        compose.waitForIdle()
        assertTrue("rememberUpdatedState を外すと false のまま固まる", first.isEnabled)
        assertSame("ゲートが作り直されていたら別物を見ている", first, gate)

        toggle = false
        compose.waitForIdle()
        assertTrue("両方向に効くこと", !first.isEnabled)
    }

    /** 提供し忘れた画面は**黙って振動しない**(既定が Noop であること)。 */
    @Test
    fun `LocalHapticGate の既定は Noop`() {
        var seen: HapticGate? = null
        compose.setContent { seen = LocalHapticGate.current }
        compose.waitForIdle()
        assertSame(NoopHapticGate, seen)
    }

    /**
     * **本物の送信ボタン**を押すと触覚ゲートを通ること(レビュー blocker M1)。
     *
     * [InputRow] の `haptics.perform(HapticEvent.SEND)` を消す変異はここで落ちる。
     * 1周目のテストはテスト自身の composable を押していたので落ちなかった。
     */
    @Test
    fun `送信ボタンは触覚ゲートを通り onSend も呼ぶ`() {
        val events = mutableListOf<HapticEvent>()
        var sent = 0
        val gate = HapticGate(enabled = { true }, sink = { events += it })
        compose.setContent {
            OpenCodeTheme {
                CompositionLocalProvider(LocalHapticGate provides gate) {
                    InputRow(
                        draft = "hello",
                        enabled = true,
                        busy = false,
                        sessionDeleted = false,
                        onDraftChange = {},
                        onSend = { sent++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("送信").performClick()
        compose.waitForIdle()
        assertEquals(listOf(HapticEvent.SEND), events)
        assertEquals("onSend も呼ばれること(触覚だけ鳴って送らない形を弾く)", 1, sent)
    }

    /**
     * **本物の中断ボタン**を押すと触覚ゲートを通ること(レビュー blocker M1 / major-2)。
     * 併せて `content-desc` が**押せるノードに載っている**ことも押さえる。
     */
    @Test
    fun `中断ボタンは触覚ゲートを通り onAbort も呼ぶ`() {
        val events = mutableListOf<HapticEvent>()
        var aborted = 0
        val gate = HapticGate(enabled = { true }, sink = { events += it })
        val busy = ChatUi(busy = true)
        compose.setContent {
            OpenCodeTheme {
                CompositionLocalProvider(LocalHapticGate provides gate) {
                    Row {
                        ChatTopBarActions(
                            pill = selectModelPill(busy),
                            abort = selectAbortAction(busy),
                            onOpenModelSheet = {},
                            onAbort = { aborted++ },
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("chat-abort:ready").performClick()
        compose.waitForIdle()
        assertEquals(listOf(HapticEvent.ABORT), events)
        assertEquals(1, aborted)
    }

    /** OFF のときは鳴らないが**操作そのものは通る**(ゲートが操作を飲み込まないこと)。 */
    @Test
    fun `触覚OFFでも送信は通る`() {
        val events = mutableListOf<HapticEvent>()
        var sent = 0
        val gate = HapticGate(enabled = { false }, sink = { events += it })
        compose.setContent {
            OpenCodeTheme {
                CompositionLocalProvider(LocalHapticGate provides gate) {
                    InputRow("hi", true, false, false, {}, { sent++ })
                }
            }
        }
        compose.onNodeWithContentDescription("送信").performClick()
        compose.waitForIdle()
        assertTrue("OFF なのに鳴っている", events.isEmpty())
        assertEquals(1, sent)
    }
}
