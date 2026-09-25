package dev.opencode.android.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 画面から使う [HapticGate]。**既定は何もしない** —— 提供し忘れた画面が黙って振動するより、
 * 黙って振動しないほうが「配線が抜けている」に気づける
 * (振動の有無は目に見えないので、既定は安全側に倒す)。
 */
val LocalHapticGate = compositionLocalOf { NoopHapticGate }

/** 実機で触覚の判定を引用可能にするための logcat タグ(HARNESS「引用できる証拠」)。 */
const val HAPTIC_LOG_TAG = "Q5Haptic"

/**
 * 現在のトグルから [HapticGate] を作る。
 *
 * [enabled] を**ラムダで包んで**渡すのが要点: 値をそのまま渡すと、設定を変えた直後の操作が
 * 再合成前の古い値で判定される。[rememberUpdatedState] で最新値を読む口だけを渡す。
 *
 * `observer` は判定の結果を**鳴らす鳴らさないに関わらず** logcat へ1行出す。
 * ゲートは「OFF のとき実際に呼ばれないこと」を要求しているが、振動そのものは
 * `uiautomator dump` にもスクショにも出ない —— この1行が実機での唯一の引用可能な証跡になる。
 */
@Composable
fun rememberHapticGate(enabled: Boolean): HapticGate {
    val haptics: HapticFeedback = LocalHapticFeedback.current
    val enabledState = rememberUpdatedState(enabled)
    return remember(haptics) {
        HapticGate(
            enabled = { enabledState.value },
            sink = { event ->
                // 種類ごとの強さを分けない: Compose が公にしているのは2種類だけで、
                // 「長押し」相当のほうが操作の手応えとして近い。
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            observer = { event, on ->
                Log.i(HAPTIC_LOG_TAG, "event=${event.name} enabled=$on fired=$on")
            },
        )
    }
}
