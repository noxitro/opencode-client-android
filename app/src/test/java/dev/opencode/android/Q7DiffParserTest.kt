package dev.opencode.android

import dev.opencode.android.ui.DIFF_MAX_LINES
import dev.opencode.android.ui.DiffLineKind
import dev.opencode.android.ui.parseUnifiedDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * unified diff パーサの検出器(QUALITY_PLAN §5b Q7 ゲート1)。
 *
 * **フィクスチャは実物 serve が返したバイト列**([Q7DiffFixtures] の doc に採取手順)。
 * ゲートが名指ししている4ケース(ハンクヘッダ / 追加のみ・削除のみ / 末尾改行なし /
 * バイナリ)を、それぞれ**実データで**測る。
 *
 * ここに置いた assert のうち、**変異で落ちることを実際に確かめたもの**は
 * 報告(TEST_REPORT)に一覧がある。**確かめていない検出をコメントで主張しない**
 * —— Q6 で3つの KDoc が存在しない検出を約束しており、レビューはそれを
 * 「穴そのものより危険」と評した。
 */
class Q7DiffParserTest {

    // ---------------------------------------------------------------------
    // 1. ハンクヘッダ `@@ -a,b +c,d @@`
    // ---------------------------------------------------------------------

    @Test
    fun `ハンクヘッダの4つの数と見出しを読む`() {
        val file = parseUnifiedDiff(Q7DiffFixtures.TWO_HUNKS).single()
        assertEquals("big.py", file.newPath)
        assertEquals("big.py", file.oldPath)
        assertEquals(2, file.hunks.size)

        val first = file.hunks[0]
        assertEquals(1, first.oldStart)
        assertEquals(5, first.oldCount)
        assertEquals(1, first.newStart)
        assertEquals(5, first.newCount)
        assertNull("1つ目のハンクに見出しは付いていない", first.heading)

        val second = file.hunks[1]
        assertEquals(50, second.oldStart)
        assertEquals(7, second.oldCount)
        assertEquals(50, second.newStart)
        assertEquals(7, second.newCount)
        assertEquals("def f17():", second.heading)
    }

    /** ヘッダの再構成が元の文字列に戻ること(dump へ載せるのはこの文字列)。 */
    @Test
    fun `ハンクヘッダを再構成すると元に戻る`() {
        val file = parseUnifiedDiff(Q7DiffFixtures.TWO_HUNKS).single()
        assertEquals("@@ -1,5 +1,5 @@", file.hunks[0].header)
        assertEquals("@@ -50,7 +50,7 @@ def f17():", file.hunks[1].header)
    }

    /**
     * **件数の省略は「1」を意味する**(`@@ -2 +2 @@`)。
     * `context=0` の実応答でしか出ない形で、**手で書いたフィクスチャには絶対に入らない**。
     */
    @Test
    fun `件数が省略されたハンクヘッダは1行とみなす`() {
        val file = parseUnifiedDiff(Q7DiffFixtures.ZERO_CONTEXT).single()
        assertEquals(2, file.hunks.size)
        assertEquals(2, file.hunks[0].oldStart)
        assertEquals(1, file.hunks[0].oldCount)
        assertEquals(2, file.hunks[0].newStart)
        assertEquals(1, file.hunks[0].newCount)
        assertEquals("def f1():", file.hunks[0].heading)
        assertEquals("@@ -2 +2 @@ def f1():", file.hunks[0].header)
        assertEquals(53, file.hunks[1].oldStart)
        assertEquals("@@ -53 +53 @@ def f18():", file.hunks[1].header)
    }

    /**
     * **行番号の2列**。文脈は両側、追加は新側だけ、削除は旧側だけ。
     * ここが1つずれると差分は読めるのに嘘の行を指す ——
     * 「画面はどこも壊れて見えない」形の典型である。
     */
    @Test
    fun `行番号は追加と削除で別々に進む`() {
        val hunk = parseUnifiedDiff(Q7DiffFixtures.TWO_HUNKS).single().hunks[0]
        val rows = hunk.lines.map { Triple(it.kind, it.oldNumber, it.newNumber) }
        assertEquals(
            listOf(
                Triple(DiffLineKind.CONTEXT, 1, 1),
                Triple(DiffLineKind.DELETE, 2, null),
                Triple(DiffLineKind.ADD, null, 2),
                Triple(DiffLineKind.CONTEXT, 3, 3),
                Triple(DiffLineKind.CONTEXT, 4, 4),
                Triple(DiffLineKind.CONTEXT, 5, 5),
            ),
            rows,
        )
        assertEquals("def f1():", hunk.lines[0].text)
        assertEquals("    return 111", hunk.lines[1].text)
        assertEquals("    return 111222", hunk.lines[2].text)
        // 空の文脈行(`" "` の1文字)は**空文字列の CONTEXT** になる。捨てない。
        assertEquals("", hunk.lines[3].text)
    }

    /** 2つ目のハンクは 50 行目から始まる(前のハンクの番号を引きずらない)。 */
    @Test
    fun `2つ目のハンクは自分のヘッダから採番し直す`() {
        val hunk = parseUnifiedDiff(Q7DiffFixtures.TWO_HUNKS).single().hunks[1]
        assertEquals(50, hunk.lines.first().oldNumber)
        assertEquals(50, hunk.lines.first().newNumber)
        assertEquals(listOf(53), hunk.lines.filter { it.kind == DiffLineKind.DELETE }.map { it.oldNumber })
        assertEquals(listOf(53), hunk.lines.filter { it.kind == DiffLineKind.ADD }.map { it.newNumber })
    }

    // ---------------------------------------------------------------------
    // 2. 追加のみ / 削除のみ
    // ---------------------------------------------------------------------

    @Test
    fun `追加のみのファイルは旧パスを持たない`() {
        val file = parseUnifiedDiff(Q7DiffFixtures.ADDED).single()
        assertNull("`--- /dev/null` は旧パス無し", file.oldPath)
        assertEquals("added2.txt", file.newPath)
        assertEquals("added2.txt", file.displayPath)
        val hunk = file.hunks.single()
        assertEquals(0, hunk.oldStart)
        assertEquals(0, hunk.oldCount)
        assertEquals(1, hunk.newStart)
        assertEquals(2, hunk.newCount)
        assertEquals(listOf(DiffLineKind.ADD, DiffLineKind.ADD), hunk.lines.map { it.kind })
        assertEquals(listOf("brand new", "file here"), hunk.lines.map { it.text })
        assertEquals(listOf(1, 2), hunk.lines.map { it.newNumber })
        assertEquals(listOf(null, null), hunk.lines.map { it.oldNumber })
        assertEquals(2, file.addedLines)
        assertEquals(0, file.deletedLines)
    }

    @Test
    fun `削除のみのファイルは新パスを持たない`() {
        val file = parseUnifiedDiff(Q7DiffFixtures.DELETED).single()
        assertEquals("modified.txt", file.oldPath)
        assertNull("`+++ /dev/null` は新パス無し", file.newPath)
        assertEquals("modified.txt", file.displayPath)
        val hunk = file.hunks.single()
        assertEquals(6, hunk.lines.size)
        assertTrue(hunk.lines.all { it.kind == DiffLineKind.DELETE })
        assertEquals((1..6).toList(), hunk.lines.map { it.oldNumber })
        assertEquals(0, file.addedLines)
        assertEquals(6, file.deletedLines)
    }

    // ---------------------------------------------------------------------
    // 3. 末尾改行なし
    // ---------------------------------------------------------------------

    /**
     * `\ No newline at end of file` は**直前の行に掛かる**。
     * 行として持つと行番号が1つずれるので、[dev.opencode.android.ui.DiffLine] の
     * フラグにする —— **実応答ではマーカーが2回出る**(削除側と追加側の両方)。
     */
    @Test
    fun `末尾改行なしは行のフラグになり行として現れない`() {
        val hunk = parseUnifiedDiff(Q7DiffFixtures.NO_NEWLINE).single().hunks.single()
        assertEquals(
            "マーカーは行にならない: " + hunk.lines.map { it.text },
            6,
            hunk.lines.size,
        )
        assertTrue(hunk.lines.none { it.text.startsWith("\\ No newline") })

        val deleted = hunk.lines.single { it.kind == DiffLineKind.DELETE }
        assertEquals("d", deleted.text)
        assertTrue("削除側の最終行に掛かる", deleted.noNewlineAtEof)

        val added = hunk.lines.filter { it.kind == DiffLineKind.ADD }
        assertEquals(listOf("d", "e"), added.map { it.text })
        assertFalse("追加の1行目には掛からない", added[0].noNewlineAtEof)
        assertTrue("追加の最終行に掛かる", added[1].noNewlineAtEof)

        // 行番号がずれていないこと(マーカーを行として数えると全部ずれる)。
        assertEquals(listOf(1, 2, 3, 4, null, null), hunk.lines.map { it.oldNumber })
        assertEquals(listOf(1, 2, 3, null, 4, 5), hunk.lines.map { it.newNumber })
    }

    // ---------------------------------------------------------------------
    // 4. バイナリ
    // ---------------------------------------------------------------------

    /**
     * バイナリは**ハンクが0**だが「差分が無い」ではない。
     * `binary` を落とすと、画面は空欄になり
     * 「変更なし」と「表示できない」の区別が消える(R1 と同じ形)。
     */
    @Test
    fun `バイナリはハンク無しで binary が立つ`() {
        val file = parseUnifiedDiff(Q7DiffFixtures.BINARY).single()
        assertTrue(file.binary)
        assertEquals(0, file.hunks.size)
        assertEquals("image.bin", file.displayPath)
        assertFalse(file.truncated)
    }

    // ---------------------------------------------------------------------
    // 5. 打ち切り(黙って切らない)
    // ---------------------------------------------------------------------

    @Test
    fun `上限を超えたら切って本当の行数を残す`() {
        val body = (1..100).joinToString("\n") { "+line $it" }
        val patch = "diff --git a/x b/x\nnew file mode 100644\n--- /dev/null\n+++ b/x\n@@ -0,0 +1,100 @@\n$body\n"
        val file = parseUnifiedDiff(patch, maxLines = 10).single()
        assertTrue("切ったことが状態に出る", file.truncated)
        assertEquals("本当の行数を覚えている", 100, file.totalLines)
        assertEquals("描くのは上限まで", 10, file.renderedLines)
        assertEquals("line 10", file.hunks.single().lines.last().text)
    }

    @Test
    fun `上限以内なら truncated は立たない`() {
        val file = parseUnifiedDiff(Q7DiffFixtures.TWO_HUNKS).single()
        assertFalse(file.truncated)
        assertEquals(file.renderedLines, file.totalLines)
    }

    /** 既定の上限は計画書のリスク欄が定めた 5000。 */
    @Test
    fun `既定の上限は5000`() {
        assertEquals(5000, DIFF_MAX_LINES)
    }

    // ---------------------------------------------------------------------
    // 6. 壊れた入力で落ちない(未知の行を捨てる)
    // ---------------------------------------------------------------------

    @Test
    fun `空とnullは空リスト`() {
        assertEquals(emptyList<Any>(), parseUnifiedDiff(null))
        assertEquals(emptyList<Any>(), parseUnifiedDiff(""))
    }

    /**
     * **未知の行が来ても落ちない。** サーバーの語彙が広がった瞬間に画面が空になる
     * 形(L3 の `jsonPrimitive`)を作らない。
     */
    @Test
    fun `未知のヘッダ行は捨てるが本文は読める`() {
        val patch = "diff --git a/x b/x\n" +
            "similarity index 92%\n" +
            "rename from old.txt\n" +
            "rename to x\n" +
            "old mode 100644\n" +
            "new mode 100755\n" +
            "index aaa..bbb 100644\n" +
            "--- a/old.txt\n" +
            "+++ b/x\n" +
            "@@ -1,1 +1,1 @@\n" +
            "-a\n" +
            "+b\n"
        val file = parseUnifiedDiff(patch).single()
        assertEquals("old.txt", file.oldPath)
        assertEquals("x", file.newPath)
        assertEquals(2, file.hunks.single().lines.size)
    }

    /** ハンクの外に出た `-` や `+` は本文ではない(ヘッダの一部)ので数えない。 */
    @Test
    fun `ハンクの外の記号行は本文にならない`() {
        val patch = "diff --git a/x b/x\n--- a/x\n+++ b/x\n"
        val file = parseUnifiedDiff(patch).single()
        assertEquals(0, file.hunks.size)
        assertEquals(0, file.totalLines)
    }

    /**
     * **差分の差分**。本文に `+++ foo` / `--- foo` が現れても、ハンクの中では
     * ヘッダとして読まない。読むと**そこから先の行が丸ごと消える**が、
     * 画面は「差分が短い」だけに見えて壊れて見えない。
     */
    @Test
    fun `ハンクの中の3連記号は本文として扱う`() {
        val patch = "diff --git a/patch.diff b/patch.diff\n" +
            "index aaa..bbb 100644\n" +
            "--- a/patch.diff\n" +
            "+++ b/patch.diff\n" +
            "@@ -1,3 +1,3 @@\n" +
            " context\n" +
            "--- old marker\n" +
            "+++ new marker\n" +
            " tail\n"
        val file = parseUnifiedDiff(patch).single()
        assertEquals("patch.diff", file.newPath)
        val lines = file.hunks.single().lines
        assertEquals(4, lines.size)
        assertEquals("-- old marker", lines[1].text)
        assertEquals(DiffLineKind.DELETE, lines[1].kind)
        assertEquals("++ new marker", lines[2].text)
        assertEquals(DiffLineKind.ADD, lines[2].kind)
        assertEquals("tail", lines[3].text)
    }

    /** 複数ファイルを1つの patch に含む形(`/vcs/diff/raw` と Q8 での再利用)。 */
    @Test
    fun `複数ファイルの patch を分割できる`() {
        val files = parseUnifiedDiff(Q7DiffFixtures.ADDED + Q7DiffFixtures.DELETED + Q7DiffFixtures.BINARY)
        assertEquals(listOf("added2.txt", "modified.txt", "image.bin"), files.map { it.displayPath })
        assertEquals(listOf(false, false, true), files.map { it.binary })
    }
}
