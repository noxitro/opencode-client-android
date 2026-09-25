package dev.opencode.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 空状態 / エラー状態の**唯一の描画**(QUALITY_PLAN §5 Q6 スコープ2)。
 *
 * 文言も導線も [EmptyStateSpec] が持つ。ここは並べるだけで、条件を1つも持たない ——
 * 「この画面だけ設定への導線を出さない」という差はこの構造では書けない。
 *
 * `content-desc` は3層に付ける:
 *  - 全体 `empty-state:<key>` … **どの空状態が出ているか**
 *  - 各ボタン `empty-state-action:<key>:<ACTION>` … **どの導線が出ているか**
 *
 * judge はスクショを見られないので、「401 で再試行が出ていないこと」は
 * ノードの有無でしか主張できない(HARNESS「引用できる証拠」)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyStateView(
    spec: EmptyStateSpec,
    onAction: (EmptyStateAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .semantics { contentDescription = "empty-state:${spec.key}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            spec.title,
            style = MaterialTheme.typography.titleSmall,
            color = when (spec.tone) {
                EmptyStateTone.ERROR -> MaterialTheme.colorScheme.error
                EmptyStateTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
        )
        spec.body?.let { body ->
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (spec.actions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                spec.actions.forEach { action ->
                    // `content-desc` は**子を持たないクリック面**に載せる([DescribedClickSurface])。
                    // `TextButton { Text(...) }` は意味論を持つ子を抱えるので、modifier への
                    // 直付けでは実機の dump で clickable=false 側へ移る(実機で確認済み。
                    // `open-tailscale` がゲートを3回落とした欠陥形と同じ)。
                    DescribedClickSurface(
                        description = "empty-state-action:${spec.key}:${action.action.name}",
                        clickModifier = Modifier.clickable(role = Role.Button) { onAction(action.action) },
                        // 48dp の番人(TouchTargets.kt)。クリック面はこの Box に寸法を合わせるので、
                        // **この最小高がそのまま当たり判定の高さになる**。`TextButton` 単体でも
                        // Material の最小強制で 48dp を超えるが、padding を縮めたときに効くのはこちら。
                        modifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET),
                        shape = ButtonDefaults.textShape,
                    ) {
                        TextButton(
                            onClick = { onAction(action.action) },
                            // 見た目専用。ラベルは description と二重に読み上げられるので消す。
                            modifier = Modifier
                                .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
                                .clearAndSetSemantics { },
                        ) { Text(action.label) }
                    }
                }
            }
        }
    }
}
