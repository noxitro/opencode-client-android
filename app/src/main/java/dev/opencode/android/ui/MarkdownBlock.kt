package dev.opencode.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * Markdown描画(QUALITY_PLAN §5 Q2 スコープ1)。
 *
 * パースは JetBrains の `org.intellij.markdown`(GFM フレーバ)、描画は
 * `com.mikepenz:multiplatform-markdown-renderer-m3`。選定の測定値は報告書に書いた。
 * 要点は「Compose の解決版を BOM 2024.12.01 から1つも動かさない」こと。
 *
 * **既定コンポーネントに無いものだけ差し替える**: コードフェンスの言語ラベル。
 * 差し替えは1か所で済むので、ライブラリの更新でここが壊れても影響範囲が読める。
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Markdown(
        content = text,
        colors = markdownColor(
            text = scheme.onSurface,
            codeText = scheme.onSurface,
            inlineCodeText = scheme.onSurface,
            linkText = scheme.primary,
            codeBackground = scheme.surfaceContainerHighest,
            inlineCodeBackground = scheme.surfaceContainerHighest,
            dividerColor = scheme.outlineVariant,
        ),
        typography = markdownTypography(
            text = MaterialTheme.typography.bodyMedium,
            paragraph = MaterialTheme.typography.bodyMedium,
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            h1 = MaterialTheme.typography.titleLarge,
            h2 = MaterialTheme.typography.titleMedium,
            h3 = MaterialTheme.typography.titleSmall,
            h4 = MaterialTheme.typography.labelLarge,
            h5 = MaterialTheme.typography.labelMedium,
            h6 = MaterialTheme.typography.labelSmall,
            quote = MaterialTheme.typography.bodyMedium,
            ordered = MaterialTheme.typography.bodyMedium,
            bullet = MaterialTheme.typography.bodyMedium,
            list = MaterialTheme.typography.bodyMedium,
        ),
        modifier = modifier,
        components = markdownComponents(
            codeFence = { model ->
                CodeBlock(
                    language = fenceLanguageOf(model.node, model.content),
                    code = fenceContentOf(model.node, model.content),
                )
            },
            codeBlock = { model ->
                CodeBlock(
                    language = null,
                    code = indentedCodeContentOf(model.node, model.content),
                )
            },
        ),
    )
}

/**
 * コードブロック。**等幅 + 背景 + 言語ラベル**(ゲート文言そのもの)。
 *
 * 横スクロールを内側に閉じ込める: 長い行でバブルごと横に伸びると、
 * 隣のテキストまで読めなくなる。
 */
@Composable
private fun CodeBlock(language: String?, code: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = "code-block:${language ?: "plain"}" },
    ) {
        if (language != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp)) {
                Text(
                    language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * ` ```kotlin ` の `kotlin`。無ければ null。
 *
 * ASTを直接読むのは、ライブラリが言語ラベルを渡してくれないため。
 * **ノード型は名前文字列ではなく [MarkdownTokenTypes] の定数で比較する** ——
 * 名前を打ち間違えても実行時まで気づけず、症状は「ラベルが出ない」だけになる。
 */
fun fenceLanguageOf(node: ASTNode, content: String): String? =
    node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.let { content.substring(it.startOffset, it.endOffset).trim() }
        ?.takeIf { it.isNotBlank() }

/**
 * コードフェンスの中身(``` の行と言語ラベルを除いた本文)。
 *
 * 最初と最後の `CODE_FENCE_CONTENT` の**間をそのまま切り出す**。1行ずつ連結すると
 * 空行(`EOL` だけの行)が落ちて、コードの段落が詰まる。
 * 中身が1つも無ければ空文字(空のフェンスは空のブロックとして描く)。
 */
fun fenceContentOf(node: ASTNode, content: String): String {
    val contents = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
    if (contents.isEmpty()) return ""
    return content.substring(contents.first().startOffset, contents.last().endOffset)
}

/**
 * インデント式コードブロック(4スペース)の中身。共通の先頭インデントを外す。
 *
 * 外さないと、画面の中でさらに4スペース下がって見える(元の意味は「コード」であって
 * 「4文字下げ」ではない)。
 */
fun indentedCodeContentOf(node: ASTNode, content: String): String {
    val raw = content.substring(node.startOffset, node.endOffset)
    val lines = raw.lines()
    val indent = lines.filter { it.isNotBlank() }
        .minOfOrNull { line -> line.takeWhile { it == ' ' }.length }
        ?: 0
    return lines.joinToString("\n") { line -> line.drop(minOf(indent, line.takeWhile { it == ' ' }.length)) }
        .trimEnd()
}
