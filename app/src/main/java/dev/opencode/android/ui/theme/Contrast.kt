package dev.opencode.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG 2.x のコントラスト比を**機械計算**する(QUALITY_PLAN §5 Q6 スコープ1)。
 *
 * 計画書は「本文コントラスト比≥4.5:1 をテーマ色で機械計算(計算式をレビュアーが独立実施)」と
 * 書いている。**目で見て決めない**ことが要点なので、計算式そのものをここに置き、
 * [Theme.kt] の `DarkColors` / `LightColors` を**そのまま**入力にする。
 *
 * ここで色を再入力しない —— 数値を書き写すと、テーマを変えたときにテストだけが
 * 古い色で緑になる。このプロジェクトが7度踏んだ「フィクスチャが実物と違う形」そのものである。
 *
 * 計算式(WCAG 2.1 relative luminance):
 *  - 各チャネルを 0..1 に正規化し、`c <= 0.03928 ? c/12.92 : ((c+0.055)/1.055)^2.4`
 *  - `L = 0.2126R + 0.7152G + 0.0722B`
 *  - 比 = `(max(L1,L2) + 0.05) / (min(L1,L2) + 0.05)`
 *
 * **アルファは考慮しない。** ここで測るのは不透明な面の色同士であり、半透明の重ね合わせを
 * 混ぜると「どの背景の上か」が定まらない。半透明を含む対(scrim 等)は対象に入れない。
 */

/** WCAG の本文テキスト基準。 */
const val WCAG_AA_NORMAL_TEXT = 4.5

/** WCAG の大きい文字 / UI部品の輪郭の基準。 */
const val WCAG_AA_LARGE_TEXT = 3.0

/** 1チャネル(0..1)を線形化する。 */
internal fun linearizeChannel(channel: Double): Double =
    if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

/** 相対輝度(0..1)。 */
fun relativeLuminance(red: Double, green: Double, blue: Double): Double =
    0.2126 * linearizeChannel(red) +
        0.7152 * linearizeChannel(green) +
        0.0722 * linearizeChannel(blue)

/** 相対輝度。`0xAARRGGBB` の下位24bitだけを使う(アルファは無視)。 */
fun relativeLuminance(argb: Long): Double = relativeLuminance(
    red = ((argb shr 16) and 0xFF).toDouble() / 255.0,
    green = ((argb shr 8) and 0xFF).toDouble() / 255.0,
    blue = (argb and 0xFF).toDouble() / 255.0,
)

/** コントラスト比(1.0〜21.0)。引数の順序に依らない。 */
fun contrastRatio(argbA: Long, argbB: Long): Double {
    val la = relativeLuminance(argbA)
    val lb = relativeLuminance(argbB)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/**
 * Compose の [Color] を `0xAARRGGBB` の Long にする。
 *
 * `Color.toArgb()` は使わない —— `android.graphics` に触れるため、JVM のユニットテストで
 * 走らせると `Method not mocked` になりうる。ここは**純粋な算術だけ**にしておく。
 */
fun Color.toArgbLong(): Long {
    fun ch(v: Float): Long = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    return (ch(alpha) shl 24) or (ch(red) shl 16) or (ch(green) shl 8) or ch(blue)
}

fun contrastRatio(a: Color, b: Color): Double = contrastRatio(a.toArgbLong(), b.toArgbLong())

/**
 * 検査する1組。[minimum] は**この対に要求する基準**で、本文は 4.5、
 * 輪郭・バッジのような非本文は 3.0 を当てる。
 */
data class ContrastPair(
    val name: String,
    val foreground: Color,
    val background: Color,
    val minimum: Double = WCAG_AA_NORMAL_TEXT,
) {
    val ratio: Double get() = contrastRatio(foreground, background)
    val passes: Boolean get() = ratio >= minimum
}

/**
 * テーマが実際に描く対を列挙する。
 *
 * ## 対の選び方を「趣味」から「規則」に変えた(Q6 レビュー major-3)
 *
 * 1周目は「画面のどこかで実際に使っている組」を**手で選んで**25対にしていた。
 * レビューはそこに漏れがあることを示し(選択中のモデル行の副次行、エラー帯の補助文、
 * markdown コードブロックなど)、**「通っているのは方法ではなく運」**と評した。正しい。
 *
 * そこで対の集合を**ソースから機械的に導く規則**にした:
 *
 *  1. `onSurface` と `onSurfaceVariant` は **面トークン全部**と組む。
 *     この2つは 44 箇所で使われており、**どの面の上にも載りうる**
 *  2. 個別に色を指定している前景([error] / [primary])は、**実際にその上に描いている面**とだけ組む
 *     (`grep "color = MaterialTheme.colorScheme."` で全53箇所を数え、各サイトの
 *     囲みコンテナを読んで決めた。`error` は5箇所、`primary` は2箇所)
 *  3. `onX` / `XContainer` の対は定義上の組なので必ず入れる
 *  4. 状態色([StatusColors])は実際に載るカード面と組む
 *  5. 輪郭は**部品の境界**なので 3.0(WCAG 1.4.11)
 *
 * **全面総当たりにはしない。** 総当たりだと「誰も描いていない対」(`primary` on `errorContainer` 等)
 * まで基準に縛られ、直す理由の無い色を動かすことになる。規則2がその線引きである。
 *
 * この規則で 25対 → **38対/テーマ**になり、**手で選んでいたときには入っていなかった
 * `primary` on `surfaceContainerHigh` が 4.32:1 で落ちた** —— 質問カードの header
 * (`ChatCards.kt`)がその組み合わせで、レビューが挙げた4件のどれでもない。
 * 規則にした甲斐がここに出ている。
 */
fun themeContrastPairs(scheme: ColorScheme, status: StatusColors): List<ContrastPair> {
    // 規則1: 本文2色 × 面トークン全部。
    val surfaces = listOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceVariant" to scheme.surfaceVariant,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    )
    val pairs = mutableListOf<ContrastPair>()
    surfaces.forEach { (name, bg) ->
        pairs += ContrastPair("onSurface/$name", scheme.onSurface, bg)
        pairs += ContrastPair("onSurfaceVariant/$name", scheme.onSurfaceVariant, bg)
    }

    // 規則2: 個別指定の前景は、**実際に描いている面**とだけ組む。
    // error(5箇所): 削除の確認ダイアログ(surfaceContainerHigh)/ 作成ダイアログのエラー行(同)/
    //   質問カードの「回答できません」(surfaceContainerHigh)/ ツール状態の ✕(surfaceContainerHigh)/
    //   長押しメニューの「削除」(surfaceContainerHigh)。加えて素の面に出る経路も見る。
    listOf(
        "surface" to scheme.surface,
        "surfaceVariant" to scheme.surfaceVariant,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    ).forEach { (name, bg) -> pairs += ContrastPair("error/$name", scheme.error, bg) }

    // primary(2箇所): 設定画面のセクション見出し(surface)/ 質問カードの header(surfaceContainerHigh)。
    pairs += ContrastPair("primary/surface", scheme.primary, scheme.surface)
    pairs += ContrastPair("primary/surfaceContainerHigh", scheme.primary, scheme.surfaceContainerHigh)

    // 規則4: 状態色。一覧のカード(surfaceContainer)とチャットのカード(surfaceContainerHigh)、
    // および素の面(バッジが面の上に出る経路)。
    listOf(
        "surface" to scheme.surface,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
    ).forEach { (name, bg) ->
        pairs += ContrastPair("statusRunning/$name", status.running, bg)
        pairs += ContrastPair("statusRetry/$name", status.retry, bg)
    }

    // 規則4 の続き(Q7): 差分の行。**追加/削除の面は新しい「面トークン」である**ので、
    // 規則1 が面トークン全部に本文2色を当てているのと同じことを、この2面にもする。
    // ここを足さないと、Q6 が「ライトの面でだけ 1.67:1」を見つけたのとまったく同じ形
    // (片方のテーマでだけ読めない)を Q7 が新しく作れてしまう。
    listOf(
        "diffAddSurface" to status.diffAddSurface,
        "diffDeleteSurface" to status.diffDeleteSurface,
    ).forEach { (name, bg) ->
        pairs += ContrastPair("onSurface/$name", scheme.onSurface, bg)
        pairs += ContrastPair("onSurfaceVariant/$name", scheme.onSurfaceVariant, bg)
    }
    // 記号(`+` / `-`)と行番号は面の上にも差分行の上にも載る。
    pairs += ContrastPair("diffAdd/diffAddSurface", status.diffAdd, status.diffAddSurface)
    pairs += ContrastPair("diffDelete/diffDeleteSurface", status.diffDelete, status.diffDeleteSurface)
    pairs += ContrastPair("diffAdd/surface", status.diffAdd, scheme.surface)
    pairs += ContrastPair("diffDelete/surface", status.diffDelete, scheme.surface)

    // 規則3: 定義上の組。加えて **コンテナ面 × 抑えた前景**(直近2件の欠陥がこの形だった)。
    pairs += ContrastPair("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer)
    pairs += ContrastPair("onSurfaceVariant/errorContainer", scheme.onSurfaceVariant, scheme.errorContainer)
    pairs += ContrastPair("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
    pairs += ContrastPair("onSurfaceVariant/primaryContainer", scheme.onSurfaceVariant, scheme.primaryContainer)
    pairs += ContrastPair("onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer)
    pairs += ContrastPair("onPrimary/primary", scheme.onPrimary, scheme.primary)

    // 規則5: 輪郭は本文ではないので 3.0。`OutlinedTextField` は設定画面(surface)にも
    // ダイアログ(surfaceContainerHigh)にも、カードの上(surfaceVariant)にも出る。
    pairs += ContrastPair("outline/surface", scheme.outline, scheme.surface, WCAG_AA_LARGE_TEXT)
    pairs += ContrastPair("outline/surfaceVariant", scheme.outline, scheme.surfaceVariant, WCAG_AA_LARGE_TEXT)
    pairs += ContrastPair("outline/surfaceContainerHigh", scheme.outline, scheme.surfaceContainerHigh, WCAG_AA_LARGE_TEXT)
    return pairs
}
