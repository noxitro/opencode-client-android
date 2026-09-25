package dev.opencode.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * **`content-desc` を「意味論を持つ子が1つも居ないクリックノード」に載せるための共通面。**
 *
 * ## なぜ部品にしたか(E2Eゲート3連続FAILの回収)
 *
 * 実機の `uiautomator dump` が読むのは Compose の意味論ツリーではなく
 * `AccessibilityNodeInfo` ツリーである。Compose UI (1.7 系 / compose-bom 2024.12.01) は
 * **「descendants を merge するノード」が「意味論を持つ子」を1つでも抱えていると、
 * その `contentDescription` を clickable=false の "fake node" に切り出す**
 * (`Modifier.clickable` / `selectable` / `combinedClickable` はいずれも
 * `shouldMergeDescendantSemantics = true`)。
 *
 * この欠陥形は `open-tailscale` で**3回のゲートを連続で落とし**、4周目に
 * 「見た目」と「クリック面」を2枚に分ける形でようやく通った(`f3b0855`、実機確認済み)。
 * 同じ形が `show-password`/`hide-password`・`color-mode-option:*`・`session-card:*` にも
 * 残っていたため、**箇所ごとに書き写すのをやめて機構を1つにまとめた**のがこの関数である。
 *
 * ## 使い方の約束
 *
 * - [content] は**描画専用**。クリックは常に上に重なるクリック面が受ける。
 * - [clickModifier] には `Modifier.clickable` / `selectable` / `combinedClickable` を
 *   呼び出し側で組んで渡す。`role` もそこに載せる(クリック面の意味論に合流する)。
 * - **クリック面には子を1つも置かないこと。** 置いた瞬間に fake node が生えて
 *   `content-desc` が clickable=false 側へ移る(実機で計測済み)。
 *   `clearAndSetSemantics {}` を付けた子も「意味論を持つ子」として数えられるので、
 *   「子を持たせない」以外に手は無い。
 * - [content] 側の意味論を消すかどうかは**呼び出し側の判断**である。
 *   ラベルが `description` と二重に読み上げられるだけなら `clearAndSetSemantics {}` で消す。
 *   子が独自に意味を持つ場合(セッションカードの `run-state:*` など)は**消さない**。
 *   クリック面は兄弟であって親ではないので、子を残しても `content-desc` は移らない。
 */
@Composable
internal fun DescribedClickSurface(
    description: String,
    clickModifier: Modifier,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        content()
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .semantics { contentDescription = description }
                .then(clickModifier),
        )
    }
}
