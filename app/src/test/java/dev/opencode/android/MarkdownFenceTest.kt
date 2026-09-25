package dev.opencode.android

import dev.opencode.android.ui.fenceContentOf
import dev.opencode.android.ui.fenceLanguageOf
import dev.opencode.android.ui.indentedCodeContentOf
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * コードブロックの言語ラベルと本文の取り出し(QUALITY_PLAN §5 Q2 スコープ1
 * 「コードブロック(等幅+背景+**言語ラベル**)」)。
 *
 * ライブラリの既定コンポーネントは言語ラベルを描かないので、`markdownComponents(codeFence=…)`
 * で差し替えている。差し替えた側は**描画なので直接は叩けない**が、
 * AST から言語と本文を取り出す部分は純関数にしてあるので、
 * **実際のパーサ(このライブラリが使うのと同じ `org.intellij.markdown` GFM)**を通して固定する。
 *
 * ノード型名を文字列で比較する実装にすると、打ち間違えても実行時まで気づけず
 * 症状は「ラベルが出ない」だけになる。ここが検出器。
 */
class MarkdownFenceTest {

    private val flavour = GFMFlavourDescriptor()

    private fun firstFence(src: String): Pair<ASTNode, String> {
        val root = MarkdownParser(flavour).buildMarkdownTreeFromString(src)
        val fence = findFirst(root, MarkdownElementTypes.CODE_FENCE)
        assertNotNull("フィクスチャに CODE_FENCE が無い(検出器自身の校正)", fence)
        return fence!! to src
    }

    private fun findFirst(node: ASTNode, type: org.intellij.markdown.IElementType): ASTNode? {
        if (node.type == type) return node
        node.children.forEach { child -> findFirst(child, type)?.let { return it } }
        return null
    }

    @Test
    fun `言語つきフェンスから言語を取り出す`() {
        val (node, src) = firstFence("見出しの下\n\n```kotlin\nval x = 1\n```\n")
        assertEquals("kotlin", fenceLanguageOf(node, src))
    }

    @Test
    fun `言語なしフェンスは null`() {
        val (node, src) = firstFence("```\nplain text\n```\n")
        assertNull(fenceLanguageOf(node, src))
    }

    @Test
    fun `フェンス本文は複数行と空行を保つ`() {
        val (node, src) = firstFence("```kotlin\nval a = 1\n\nval b = 2\n```\n")
        // 1行ずつ連結する実装だと空行が落ちてここで差が出る。
        assertEquals("val a = 1\n\nval b = 2", fenceContentOf(node, src))
    }

    @Test
    fun `空のフェンスは空文字`() {
        val (node, src) = firstFence("```kotlin\n```\n")
        assertEquals("", fenceContentOf(node, src))
    }

    @Test
    fun `本文にバッククォートを含んでいても壊れない`() {
        val (node, src) = firstFence("````\n```\nnested\n```\n````\n")
        assertEquals("```\nnested\n```", fenceContentOf(node, src))
    }

    @Test
    fun `インデント式コードブロックは共通インデントを外す`() {
        val src = "段落\n\n    fun main() {\n        println(1)\n    }\n"
        val root = MarkdownParser(flavour).buildMarkdownTreeFromString(src)
        val block = findFirst(root, MarkdownElementTypes.CODE_BLOCK)
        assertNotNull("フィクスチャに CODE_BLOCK が無い(検出器自身の校正)", block)
        assertEquals("fun main() {\n    println(1)\n}", indentedCodeContentOf(block!!, src))
    }
}
