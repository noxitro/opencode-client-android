package dev.opencode.android

import dev.opencode.android.data.VcsFileStatusDto
import dev.opencode.android.ui.FILE_ROOT_PATH
import dev.opencode.android.ui.breadcrumbsOf
import dev.opencode.android.ui.byteRangeToCharRange
import dev.opencode.android.ui.childPath
import dev.opencode.android.ui.fileEntriesOf
import dev.opencode.android.ui.fileNameOf
import dev.opencode.android.ui.groupTextMatches
import dev.opencode.android.ui.normalizeServerPath
import dev.opencode.android.ui.parentPath
import dev.opencode.android.ui.pathFromFileUri
import dev.opencode.android.ui.symbolHitsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * パス正規化・パンくず・**バイトオフセット→文字インデックス**の純関数テスト(Q8)。
 *
 * ## なぜ日本語のケースが要るのか(実測)
 *
 * `GET /find` の `submatches[].start`/`end` は **UTF-8 のバイトオフセット**である。
 * `差分ビューア`(6文字/18バイト)が `start=5,end=23` で返り、
 * **文字インデックスとして切ると `**: unified ` が出る**(実測)。
 *
 * ASCII だけの行では両者が一致するので、**英語のテストしか書かないと絶対に気付かない**。
 * このプロジェクトが7回繰り返した「フィクスチャが実データと違う形」の変種である ——
 * 形は合っているのに**中身が英語しか無い**フィクスチャは、同じ穴を作る。
 */
class Q8PathTest {

    // ---------------------------------------------------------------------
    // 正規化
    // ---------------------------------------------------------------------

    @Test
    fun `円マーク区切りをスラッシュへ寄せる`() {
        assertEquals("app/build", normalizeServerPath("app\\build\\"))
        assertEquals("app/src/main", normalizeServerPath("app\\src\\main"))
        assertEquals("app/build.gradle.kts", normalizeServerPath("app\\build.gradle.kts"))
    }

    @Test
    fun `末尾の区切りと先頭のドットスラッシュを落とす`() {
        assertEquals("app/src", normalizeServerPath("app/src/"))
        assertEquals("app/src", normalizeServerPath("./app/src"))
        assertEquals("app", normalizeServerPath("app/"))
    }

    @Test
    fun `空とドットはルートになる`() {
        assertEquals(FILE_ROOT_PATH, normalizeServerPath(null))
        assertEquals(FILE_ROOT_PATH, normalizeServerPath(""))
        assertEquals(FILE_ROOT_PATH, normalizeServerPath("."))
        assertEquals(FILE_ROOT_PATH, normalizeServerPath("./"))
        assertEquals(FILE_ROOT_PATH, normalizeServerPath("/"))
    }

    @Test
    fun `子と親の組み立て`() {
        assertEquals("app", childPath(FILE_ROOT_PATH, "app"))
        assertEquals("app/src", childPath("app", "src"))
        assertEquals("app", parentPath("app/src"))
        assertEquals(FILE_ROOT_PATH, parentPath("app"))
    }

    /** **ルートの親は null。** 「これ以上戻れない」の判定を呼び出し側に書かせない。 */
    @Test
    fun `ルートの親は null`() {
        assertNull(parentPath(FILE_ROOT_PATH))
        assertNull(parentPath("."))
    }

    @Test
    fun `ファイル名`() {
        assertEquals("Theme.kt", fileNameOf("app/src/main/java/x/Theme.kt"))
        assertEquals("app", fileNameOf("app"))
        assertEquals("/", fileNameOf(FILE_ROOT_PATH))
    }

    // ---------------------------------------------------------------------
    // パンくず
    // ---------------------------------------------------------------------

    @Test
    fun `パンくずは先頭がルートで末尾が現在地`() {
        val crumbs = breadcrumbsOf("app/src/main")
        assertEquals(listOf("/", "app", "src", "main"), crumbs.map { it.label })
        assertEquals(listOf(".", "app", "app/src", "app/src/main"), crumbs.map { it.path })
    }

    @Test
    fun `ルートのパンくずは1つだけ`() {
        assertEquals(1, breadcrumbsOf(FILE_ROOT_PATH).size)
        assertEquals(".", breadcrumbsOf(FILE_ROOT_PATH)[0].path)
    }

    /** サーバー由来の `\` 区切りをそのまま渡しても同じ列になる。 */
    @Test
    fun `パンくずはサーバー由来の区切りでも同じ`() {
        assertEquals(
            breadcrumbsOf("app/src/main").map { it.path },
            breadcrumbsOf("app\\src\\main\\").map { it.path },
        )
    }

    // ---------------------------------------------------------------------
    // バイトオフセット → 文字インデックス
    // ---------------------------------------------------------------------

    /** ASCII は一致する。**ここだけ緑でも何も守れない**(下の日本語のケースが本体)。 */
    @Test
    fun `ASCII ではバイトと文字が一致する`() {
        val text = "@rem Licensed under the Apache License"
        assertEquals(20 until 23, byteRangeToCharRange(text, 20, 23))
        assertEquals("the", text.substring(20, 23))
    }

    /**
     * **実データそのもの。** `差分ビューア` は 6文字 / 18バイトで `start=5,end=23`。
     * バイトのまま切ると `**: unified ` が出る(実測)。
     */
    @Test
    fun `日本語ではバイトと文字がずれる`() {
        val m = Q8Decode.matches(Q8Fixtures.FIND_JAPANESE_JSON)[0]
        val text = m.lines.text.removeSuffix("\n")
        val sub = m.submatches[0]
        assertEquals(5, sub.start)
        assertEquals(23, sub.end)

        val range = byteRangeToCharRange(text, sub.start, sub.end)!!
        assertEquals("差分ビューア", text.substring(range.first, range.last + 1))
        // 変換を落とすと**別の場所を塗る**ことを、同じ入力で示しておく。
        assertTrue(
            "バイトのまま切ると違う文字列になる",
            text.substring(sub.start, sub.end) != "差分ビューア",
        )
    }

    /**
     * **2バイト文字(キリル・アクセント付きラテン・ギリシャ)でもずれない。**
     *
     * ここは**変異校正が空けた穴を塞いだ**テストである。`utf8Length` の
     * `c.code < 0x800 -> 2` を `-> 1` に変える変異(M4)は、日本語(3バイト)と
     * サロゲート(4バイト)のテストがあっても**全緑で通り抜けた** ——
     * **2バイトの帯だけ誰も測っていなかった**。
     *
     * 症状は「ロシア語やドイツ語のコメントを含む行だけがずれて塗られる」で、
     * 日本語でも英語でも再現しない。**言語を1つ足すだけで見える穴だった。**
     */
    @Test
    fun `2バイト文字でもずれない`() {
        // "Привет" はキリル6文字 = 12バイト。その後ろの "target" を切る。
        val text = "Привет target"
        val startByte = 12 + 1 // キリル6文字(12バイト) + 半角スペース(1)
        val range = byteRangeToCharRange(text, startByte, startByte + 6)!!
        assertEquals("target", text.substring(range.first, range.last + 1))
    }

    @Test
    fun `サロゲートペアを含む行でもずれない`() {
        // 𠮷(U+20BB7) は UTF-8 で4バイト / UTF-16 で2単位。
        val text = "a𠮷b_target"
        val startByte = 1 + 4 + 1 + 1 // a(1) + 𠮷(4) + b(1) + _(1)
        val range = byteRangeToCharRange(text, startByte, startByte + 6)!!
        assertEquals("target", text.substring(range.first, range.last + 1))
    }

    @Test
    fun `範囲外や逆転した範囲は塗らない`() {
        assertNull(byteRangeToCharRange("abc", -1, 2))
        assertNull(byteRangeToCharRange("abc", 3, 1))
        assertNull(byteRangeToCharRange("abc", 0, 99))
    }

    // ---------------------------------------------------------------------
    // グループ化
    // ---------------------------------------------------------------------

    @Test
    fun `全文検索は末尾の改行を落として2つのマッチを両方塗る`() {
        val groups = groupTextMatches(Q8Decode.matches(Q8Fixtures.FIND_TWO_SUBMATCHES_JSON))
        assertEquals(1, groups.size)
        val hit = groups[0].hits[0]
        assertEquals("gradlew.bat", hit.path)
        assertTrue("末尾の改行を落とす", !hit.text.endsWith("\n"))
        assertEquals(2, hit.highlights.size)
        assertEquals("the", hit.text.substring(hit.highlights[0].first, hit.highlights[0].last + 1))
        assertEquals("the", hit.text.substring(hit.highlights[1].first, hit.highlights[1].last + 1))
    }

    @Test
    fun `1行4マッチも全部塗る`() {
        val groups = groupTextMatches(Q8Decode.matches(Q8Fixtures.FIND_FOUR_SUBMATCHES_JSON))
        val hit = groups[0].hits[0]
        assertEquals(4, hit.highlights.size)
        hit.highlights.forEach {
            assertEquals("path", hit.text.substring(it.first, it.last + 1))
        }
    }

    /** グループはパスでまとめる。**サーバーの順序を仮定しない。** */
    @Test
    fun `同じファイルの複数行が1つのグループになる`() {
        val json = """
            [{"path":{"text":"a\\b.kt"},"lines":{"text":"x\n"},"line_number":1,"absolute_offset":0,"submatches":[]},
             {"path":{"text":"c.kt"},"lines":{"text":"y\n"},"line_number":2,"absolute_offset":1,"submatches":[]},
             {"path":{"text":"a\\b.kt"},"lines":{"text":"z\n"},"line_number":3,"absolute_offset":2,"submatches":[]}]
        """.trimIndent()
        val groups = groupTextMatches(Q8Decode.matches(json))
        assertEquals(listOf("a/b.kt", "c.kt"), groups.map { it.path })
        assertEquals(2, groups[0].hits.size)
    }

    // ---------------------------------------------------------------------
    // ツリーの行
    // ---------------------------------------------------------------------

    @Test
    fun `ツリーの行はディレクトリが先で名前順`() {
        val entries = fileEntriesOf(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON), emptyList())
        assertEquals(listOf("build", "src", "build.gradle.kts", "proguard-rules.pro"), entries.map { it.name })
        assertEquals(listOf("app/build", "app/src", "app/build.gradle.kts", "app/proguard-rules.pro"), entries.map { it.path })
    }

    /** 変更バッジは `GET /vcs/status`(**`GET /file/status` ではない**)から付ける。 */
    @Test
    fun `変更バッジは vcs status から付く`() {
        val entries = fileEntriesOf(
            Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON),
            listOf(VcsFileStatusDto(file = "app/build.gradle.kts", additions = 1, deletions = 0, status = "modified")),
        )
        assertEquals("modified", entries.first { it.name == "build.gradle.kts" }.vcsStatus)
        assertNull(entries.first { it.name == "src" }.vcsStatus)
    }

    /** `content-desc` の形。**judge はこれしか引用できない。** */
    @Test
    fun `行の説明文字列`() {
        val entries = fileEntriesOf(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON), emptyList())
        assertEquals("file-entry:app/build:dir:ignored", entries.first { it.name == "build" }.description)
        assertEquals("file-entry:app/src:dir", entries.first { it.name == "src" }.description)
    }

    // ---------------------------------------------------------------------
    // シンボル
    // ---------------------------------------------------------------------

    /**
     * **LSP の行は0始まり、ビューアの行番号は1始まり。**
     * 揃えないと**常に1行ずれた場所へ飛ぶ** —— 1行のずれは「だいたい合っている」ので気付きにくい。
     */
    @Test
    fun `シンボルの行番号は1始まりへ直す`() {
        val json = """
            [{"name":"f","kind":12,
              "location":{"uri":"file:///E:/x/y.kt",
                          "range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}}]
        """.trimIndent()
        val hit = symbolHitsOf(Q8Decode.symbols(json))[0]
        assertEquals(1, hit.line)
        assertEquals("E:/x/y.kt", hit.path)
    }

    @Test
    fun `file URI をパスへ落とす`() {
        assertEquals("E:/x/y.kt", pathFromFileUri("file:///E:/x/y.kt"))
        assertEquals("a/b c.kt", pathFromFileUri("file:///a/b%20c.kt"))
        // `file://` でなければそのまま(空にするより読める)
        assertEquals("a/b.kt", pathFromFileUri("a\\b.kt"))
    }
}
