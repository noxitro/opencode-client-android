package dev.opencode.android.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 最小タッチターゲット(QUALITY_PLAN §5 Q6 スコープ1「タッチターゲット48dp」)。
 *
 * ## Q3 が残した2つの実測(RUN_PLAN「a11y ゲートは bounds だけで閉じない」)
 *
 * 1. **`content-desc` が押せるノードに付いていないと、dump は押せない子ノードの寸法を返す。**
 *    Q3 の送信ボタンは 48dp を満たしていたのに **12×12px** と読めた
 * 2. **宣言された bounds は実効的な当たり判定と一致しない。** Q6 の実測では送信ボタンの
 *    実効ターゲットが **44.4dp × 48.0dp**(左端の約11pxが反応しない)。**原因は未特定**
 *
 * したがってこの定数は「宣言を揃えるため」のものであって、**合格の証拠ではない**。
 * 主要な操作要素は実際にタップして反応を確かめること。
 *
 * ## この定数を使った `heightIn(min = ...)` は「今日の値を変えない番人」である
 *
 * **正直に書いておく(Q6 レビュー D-M2/W9)**: モデル行・エージェント行・ツールカード・
 * todo 見出しは、**自前のパディングだけで既に 48dp を超えている**。したがって
 * `heightIn(min = MIN_TOUCH_TARGET)` を外しても**テストは落ちない**(変異 W9 は
 * 2周とも PASS-THROUGH)。守っているのは行の**総高**であり、パディングを縮める変異
 * (12dp → 2dp)では実際に落ちる(実測: `Actual height is 40.0.dp`)。
 *
 * **この行そのものを検出する手段は無い。** 将来 padding を減らしたときに 48dp を割らせない
 * ための保険として置いてある —— **「守られている」と読まないこと。**
 *
 * ## Material の最小タッチターゲット強制との関係
 *
 * `IconButton` / `Button` / `FilterChip` のような Material3 の部品は
 * `minimumInteractiveComponentSize()` を内部で当てるので、視覚が 32dp でも
 * **タッチ領域**は 48dp になる(実測: 質問チップは desc ノード 146×88px に対し
 * clickable な祖先が 146×132px)。一方、素の `Modifier.clickable` を当てた `Row` / `Column`
 * は**何の強制も受けない**。揃えるのはそちら側である。
 */
val MIN_TOUCH_TARGET: Dp = 48.dp

/** dp の生値。E2E 側の px 換算(`dp × density`)と突き合わせるために公開する。 */
const val MIN_TOUCH_TARGET_DP: Int = 48
