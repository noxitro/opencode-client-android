package dev.opencode.android.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * **行ごとに幅が違う本文を、1つのスクロール量で横に送る**ための共通部品
 * (差分 `diff-hscroll` / ファイル `file-hscroll` / 端末 `pty-hscroll` の3画面で共有する)。
 *
 * ## なぜ [androidx.compose.foundation.ScrollState] を共有する形をやめたのか
 *
 * Q7 から Q9 まで、3画面とも**同じ `ScrollState` を全行の
 * [androidx.compose.foundation.horizontalScroll] に渡していた**。折り返さない本文で
 * 行ごとに別々のスクロール量を持つと、横に送ったとき行がずれて対応が取れなくなる ——
 * その判断自体は正しい。**壊れていたのは共有の仕方である。**
 *
 * `horizontalScroll` の layout は、測るたびに
 * `scrollState.maxValue = 内容幅 - 表示幅` を**書き込む**。つまり
 * **1つの `ScrollState` を N 個のノードが取り合い、最後に測られた行の値が残る**。
 * 表示窓に短い行(内容幅 ≦ 表示幅)が1つでも入っていれば、その行が
 * `maxValue = 0` を書き、`ScrollState` は `value` も 0 へ丸める ——
 * **長い行が同じ画面に出ていても横スクロールが一切効かなくなる**。
 *
 * 全行が長いときは症状が出ない。Q7(差分)と Q8(ファイル)で露見せず、
 * 短い行が普通に混ざる Q9(端末)の E2E で初めて実測された理由がこれである。
 *
 * ## 代わりに何をしているか
 *
 * **表示窓に出ている行の幅を集約し、その最大幅から `maxValue` を1つだけ決める**
 * ([LineScrollState])。各行は自分の内容幅を [reportLine] で預けるだけで、
 * スクロール量は持たない。行は「表示窓の幅」に切り詰められ、
 * 共有の [LineScrollState.value] だけずらして置かれる。
 *
 * 「最後に測られた行が勝つ」形が「最大幅が勝つ」形に変わるので、
 * 短い行が何行混ざっても長い行の到達可能範囲は縮まない。
 *
 * ## 計器は変えない
 *
 * `<key>:<現在値>/<最大値>` の `content-desc` は Q7 が入れたまま維持する
 * (`uiautomator dump` の `bounds` はクリップ矩形で横に送っても動かないので、
 * 到達は `value == maxValue && maxValue > 0` でしか主張できない)。
 * **計器の形を変えると過去段の E2E 手順が読めなくなる。**
 */
@Stable
class LineScrollState {

    /** 表示窓に**いま出ている**行の内容幅(px)。行が composition を出たら消える。 */
    private val widths = HashMap<Any, Int>()

    private var contentWidthState by mutableIntStateOf(0)
    private var viewportWidthState by mutableIntStateOf(0)
    private var offset by mutableFloatStateOf(0f)

    /** 表示窓に出ている行の**最大**内容幅(px)。 */
    val contentWidth: Int get() = contentWidthState

    /** 表示窓そのものの幅(px)。 */
    val viewportWidth: Int get() = viewportWidthState

    /** 送れる最大量。**計器の分母**。 */
    val maxValue: Int get() = lineScrollMaxValue(contentWidthState, viewportWidthState)

    /** いま送っている量。**計器の分子**。 */
    val value: Int get() = lineScrollClamp(offset.roundToInt(), maxValue)

    /**
     * 行が自分の内容幅と表示窓の幅を預ける。**測るたびに呼ばれる。**
     *
     * `maxOrNull` であることがこの部品の全部である —— ここを「最後の値」に戻すと
     * Q9 E2E が実測した症状(短い行が1つ入ると横スクロールが死ぬ)がそのまま戻る。
     */
    fun reportLine(key: Any, contentWidth: Int, viewportWidth: Int) {
        widths[key] = contentWidth
        apply(widths.values.maxOrNull() ?: 0, viewportWidth)
    }

    /** 行が表示窓から出た。**幅を持ち越さない**(離れた行が到達範囲を広げ続けない)。 */
    fun forgetLine(key: Any) {
        if (widths.remove(key) == null) return
        apply(widths.values.maxOrNull() ?: 0, viewportWidthState)
    }

    /**
     * ジェスチャーから呼ばれる。**消費した量を返す**
     * (返さないと端まで送った後もスクロール容器が親へ delta を渡さない)。
     */
    fun scrollBy(delta: Float): Float {
        val limit = maxValue.toFloat()
        val before = offset.coerceIn(0f, limit)
        val after = (before + delta).coerceIn(0f, limit)
        offset = after
        return after - before
    }

    /** 表示窓の中身を丸ごと入れ替えたとき(別ファイル・別端末)に位置を先頭へ戻す。 */
    fun reset() {
        widths.clear()
        contentWidthState = 0
        offset = 0f
    }

    private fun apply(content: Int, viewport: Int) {
        if (contentWidthState != content) contentWidthState = content
        if (viewportWidthState != viewport) viewportWidthState = viewport
        val limit = lineScrollMaxValue(content, viewport).toFloat()
        if (offset > limit) offset = limit
    }
}

/**
 * 送れる最大量。**負にしない**(内容が表示窓に収まっているなら 0)。
 *
 * ここを `contentWidth` だけにすると、収まっている内容でも
 * 「まだ右に何かある」と計器が言い出す。
 */
fun lineScrollMaxValue(contentWidth: Int, viewportWidth: Int): Int =
    (contentWidth - viewportWidth).coerceAtLeast(0)

/** 位置を範囲に収める。 */
fun lineScrollClamp(value: Int, maxValue: Int): Int = value.coerceIn(0, maxValue)

/** 表示窓ひとつぶんの状態を覚える。 */
@Composable
fun rememberLineScrollState(): LineScrollState = remember { LineScrollState() }

/**
 * **表示窓(スクロール容器)側。** ジェスチャーと計器をここに付ける。
 *
 * ## スクロールのセマンティクスを自分で載せる理由(レビュー minor-3)
 *
 * 旧実装の [androidx.compose.foundation.horizontalScroll] は
 * `horizontalScrollAxisRange` と `ScrollBy` アクションの**両方**を行に付けていた。
 * [androidx.compose.foundation.gestures.scrollable] が自分で足すのは `ScrollBy` **だけ**で、
 * **範囲(いまどこで、どこまで送れるか)は足さない** —— 送れる量を知っているのは
 * この [LineScrollState] であって `scrollable` ではないからである。
 * 範囲が無いと TalkBack は**横スクロールできる要素だと認識せず**、
 * 3画面(差分・ファイル・端末)すべてで折り返さない本文の右側が支援技術から到達不能になる。
 *
 * したがってここが足すのは**範囲だけ**である。`ScrollBy` を重ねて書いていた版もあったが、
 * **それを外しても検出器が1つも動かなかった**(変異 Y13/Y14 が `PASS_THROUGH`)——
 * `scrollable` の物が既に効いている証拠なので、重複を残さない。
 *
 * `content-desc`(judge の計器)は**別物**であり、形を変えない。
 *
 * ## 向き(レビュー minor-4)
 *
 * [lineScrollContent] は `placeRelative` で置くので **RTL では配置が自動で鏡になる**。
 * ジェスチャーだけ LTR 固定にすると、RTL ロケールで**指の向きと本文の動く向きが逆**になる。
 * したがって `reverseDirection` は [LocalLayoutDirection] から決める。
 *
 * @param descriptionKey `diff-hscroll` / `file-hscroll` / `pty-hscroll`。
 *   **大きさ0のノードに付けないこと**(Q9 の1周目が実機 dump に1度も出なかった形)。
 */
@Composable
fun Modifier.lineScrollWindow(state: LineScrollState, descriptionKey: String): Modifier {
    val scrollable = rememberScrollableState { delta -> state.scrollBy(delta) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return this
        // LTR では `reverseDirection = true`(`Modifier.horizontalScroll` と同じ。
        // 指を左へ払うと `value` が増える)。RTL では鏡なので逆。
        .scrollable(
            state = scrollable,
            orientation = Orientation.Horizontal,
            reverseDirection = !rtl,
        )
        .semantics {
            contentDescription = "$descriptionKey:${state.value}/${state.maxValue}"
            horizontalScrollAxisRange = ScrollAxisRange(
                value = { state.value.toFloat() },
                maxValue = { state.maxValue.toFloat() },
                reverseScrolling = rtl,
            )
        }
}

/**
 * **行の本文側。** 幅を預け、共有のスクロール量ぶんだけ左へずらして置く。
 *
 * @param key 行の識別子。**表示窓の中で一意**であること(`LazyColumn` の item key と同じ物を渡す)。
 */
@Composable
fun Modifier.lineScrollContent(state: LineScrollState, key: Any): Modifier {
    // 行が表示窓から出たら幅を返す。**`Modifier.layout` には外れる契機が無い**ので
    // composition 側で持つ。
    DisposableEffect(state, key) { onDispose { state.forgetLine(key) } }
    return this
        .clipToBounds()
        .layout { measurable, constraints ->
            // **幅の制約を外して測る。** 折り返さない本文の本当の幅はこれでしか取れない。
            val placeable = measurable.measure(
                constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity),
            )
            val viewport = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
            state.reportLine(key, placeable.width, viewport)
            layout(viewport, placeable.height) {
                // 位置の読み取りを**配置**の中で行う。再コンポジションを起こさずに送れる。
                placeable.placeRelative(-state.value, 0)
            }
        }
}
