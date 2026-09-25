package dev.opencode.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import dev.opencode.android.data.FileContentPayload
import dev.opencode.android.ui.FileActions
import dev.opencode.android.ui.FileBreadcrumbs
import dev.opencode.android.ui.FileSearchResults
import dev.opencode.android.ui.FileSearchTab
import dev.opencode.android.ui.FileSearchUi
import dev.opencode.android.ui.FileTreeArea
import dev.opencode.android.ui.FileTreeList
import dev.opencode.android.ui.FileTreeUi
import dev.opencode.android.ui.FileViewerBody
import dev.opencode.android.ui.FileViewerUi
import dev.opencode.android.ui.MIN_TOUCH_TARGET
import dev.opencode.android.ui.breadcrumbsOf
import dev.opencode.android.ui.buildFileLines
import dev.opencode.android.ui.fileEntriesOf
import dev.opencode.android.ui.fileLineKey
import dev.opencode.android.ui.groupTextMatches
import dev.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ファイルブラウザの**描画そのもの**に検出器を置く(Q6 が入れた Compose UI テスト基盤の上)。
 *
 * ## この段の陰性側ゲート
 *
 * §5b は「`type:"binary"` のファイルで **base64 文字列が画面に出ていないこと**を
 * dump のテキストで確認」と定めている。dump が読むのは `text` と `content-desc` なので、
 * **本物の composable を描いて両方を全部集め、base64 が1つも無いこと**を assert する。
 *
 * ## 自前の composable を押さない
 *
 * ここが描くのは [FileTreeList] / [FileViewerBody] / [FileSearchResults] そのものである
 * (Q6 のレビューが blocker として挙げたトートロジーを避ける)。
 */
@RunWith(RobolectricTestRunner::class)
class Q8ComposeTest {

    @get:Rule
    val compose = createComposeRule()

    /** 呼ばれたことを控えるだけの宛先。**素通しの `Unit` を返さない**(呼ばれた事実を残す)。 */
    private class RecordingFileActions : FileActions {
        val calls = mutableListOf<String>()
        override fun openDirectory(path: String) { calls += "openDirectory:$path" }
        override fun toggleIgnoredFiles() { calls += "toggleIgnored" }
        override fun retryFileTree() { calls += "retryTree" }
        override fun openFile(path: String, focusLine: Int?) { calls += "openFile:$path:$focusLine" }
        override fun closeFileViewer() { calls += "close" }
        override fun retryFileViewer() { calls += "retryViewer" }
        override fun toggleFileDiff() { calls += "toggleDiff" }
        override fun setFileSearchTab(tab: FileSearchTab) { calls += "tab:${tab.name}" }
        override fun updateFileSearchQuery(query: String) { calls += "query:$query" }
        override fun submitFileSearch() { calls += "submit" }
        override fun clearFileSearch() { calls += "clear" }
    }

    /** 画面に出ている `text` と `content-desc` を**全部**集める(dump が読むのと同じ2つ)。 */
    private fun visibleStrings(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { walk(it) }
        }
        walk(compose.onRoot().fetchSemanticsNode())
        return out
    }

    // ---------------------------------------------------------------------
    // ツリー(スコープ1)
    // ---------------------------------------------------------------------

    private val appEntries = fileEntriesOf(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON), emptyList())

    @Test
    fun `ツリーの行は押せるノードに desc が付く`() {
        val actions = RecordingFileActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileTreeList(
                    entries = appEntries.filter { !it.ignored },
                    onOpenDirectory = actions::openDirectory,
                    onOpenFile = { actions.openFile(it) },
                )
            }
        }
        compose.onNodeWithContentDescription("file-entry:app/src:dir")
            .assertExists()
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
    }

    /** **ディレクトリとファイルで別の宛先へ行く。** 分岐は行の型だけで決まる。 */
    @Test
    fun `ディレクトリとファイルで別の宛先を呼ぶ`() {
        val actions = RecordingFileActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileTreeList(
                    entries = appEntries.filter { !it.ignored },
                    onOpenDirectory = actions::openDirectory,
                    onOpenFile = { actions.openFile(it) },
                )
            }
        }
        compose.onNodeWithContentDescription("file-entry:app/src:dir").performClick()
        compose.onNodeWithContentDescription("file-entry:app/build.gradle.kts:file").performClick()
        assertEquals(
            listOf("openDirectory:app/src", "openFile:app/build.gradle.kts:null"),
            actions.calls,
        )
    }

    /** パンくずは**列そのものが導線**。押すとその階層へ戻る。 */
    @Test
    fun `パンくずを押すとその階層へ戻る`() {
        val actions = RecordingFileActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileBreadcrumbs(crumbs = breadcrumbsOf("app/src/main"), onOpen = actions::openDirectory)
            }
        }
        compose.onNodeWithContentDescription("file-crumb:app").performClick()
        assertEquals(listOf("openDirectory:app"), actions.calls)
    }

    // ---------------------------------------------------------------------
    // ビューア(スコープ2)—— 陰性側ゲート
    // ---------------------------------------------------------------------

    @Test
    fun `テキストは行番号つきで描かれる`() {
        val (lines, cut, total) = buildFileLines("alpha\nbravo\ncharlie")
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileViewerBody(
                    viewer = FileViewerUi(
                        open = true,
                        path = "a.kt",
                        payload = FileContentPayload(type = "text", content = "x", receivedBytes = 19),
                        lines = lines,
                        renderTruncated = cut,
                        totalLines = total,
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("file-viewer:a.kt").assertExists()
        compose.onNodeWithContentDescription("file-line:1").assertExists()
        compose.onNodeWithText("bravo").assertExists()
    }

    /**
     * **短い行が混ざっていても横スクロールが死なない。**
     *
     * 1周目は全行の `horizontalScroll` が同じ `ScrollState` を共有していた。
     * `horizontalScroll` の layout は測るたびに `maxValue = 内容幅 - 表示幅` を
     * **書き込む**ので、1つの `ScrollState` を N 行が取り合い、
     * **最後に測られた短い行が 0 を書いて横スクロールが効かなくなる**。
     * 全行が長ければ症状が出ないため Q7/Q8 では露見せず、
     * 短い行が普通に混ざる Q9(端末)の E2E で初めて実測された
     * (**Q9 固有の退行ではなく Q7 から続く既存パターンの問題**)。
     */
    @Test
    fun `短い行が混ざっても横スクロールの最大値が潰れない`() {
        val (lines, cut, total) = buildFileLines("ok\n" + "x".repeat(400) + "\nok2")
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileViewerBody(
                    viewer = FileViewerUi(
                        open = true,
                        path = "a.kt",
                        payload = FileContentPayload(type = "text", content = "x", receivedBytes = 19),
                        lines = lines,
                        renderTruncated = cut,
                        totalLines = total,
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        val desc = visibleStrings().single { it.startsWith("file-hscroll:") }
        assertTrue("短い行が maxValue を 0 に潰している: $desc", desc.substringAfter("/").toInt() > 0)
    }

    /**
     * **別のファイルを開いたら横位置が先頭へ戻る**(レビュー minor-2)。
     *
     * 行の item key が添字だけ(`l:$i`)だと、`LazyColumn` は別のファイルでも
     * **同じ鍵の行を使い回す**ので、[dev.opencode.android.ui.LineScrollState] は
     * 幅も位置も引き継ぐ。長い行のファイルから長い行のファイルへ移ると、
     * **前のファイルで送っていた桁のまま新しいファイルが開く**。
     *
     * 鍵をパスで修飾し([fileLineKey])、切り替えで
     * [dev.opencode.android.ui.LineScrollState.reset] を呼ぶことで閉じている
     * (`reset()` は本番から呼ばれる —— 呼ばれない関数は検出器を持てない)。
     */
    @Test
    fun `別のファイルを開くと横位置が先頭へ戻る`() {
        fun viewerOf(path: String): FileViewerUi {
            val (lines, cut, total) = buildFileLines(List(3) { "y".repeat(400) }.joinToString("\n"))
            return FileViewerUi(
                open = true,
                path = path,
                payload = FileContentPayload(type = "text", content = "y", receivedBytes = 19),
                lines = lines,
                renderTruncated = cut,
                totalLines = total,
            )
        }
        var viewer by mutableStateOf(viewerOf("a.kt"))
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileViewerBody(viewer = viewer, actions = RecordingFileActions(), onOpenSettings = {})
            }
        }
        val window = compose.onNodeWithContentDescription("file-hscroll:", substring = true)
        window.performSemanticsAction(SemanticsActions.ScrollBy) { it(120f, 0f) }
        compose.waitForIdle()
        assertEquals(
            "送れていない(この後の assert が意味を失う)",
            120,
            visibleStrings().single { it.startsWith("file-hscroll:") }
                .substringAfter(":").substringBefore("/").toInt(),
        )

        compose.runOnUiThread { viewer = viewerOf("b.kt") }
        compose.waitForIdle()
        assertEquals(
            "前のファイルの横位置を持ち越している",
            0,
            visibleStrings().single { it.startsWith("file-hscroll:") }
                .substringAfter(":").substringBefore("/").toInt(),
        )
    }

    /**
     * **RTL でも指の向きと本文の向きが一致する**(レビュー minor-4)。
     *
     * 行の本文は `placeRelative` で置くので **RTL では配置が自動で鏡になる**。
     * `Modifier.scrollable` の `reverseDirection` を LTR 固定にすると、
     * 送る向きだけが鏡にならず**ジェスチャーと配置が食い違う**。
     * 向きは [LocalLayoutDirection] から決まる —— それを
     * `horizontalScrollAxisRange` の `reverseScrolling` で観測する
     * (同じ `rtl` の値から両方が出ている)。
     *
     * このアプリは日本語専用なので実害は無いが、**向きの根拠が
     * 「LTR だと決め打ちした」ままなのは設計と実装の食い違いである**。
     */
    @Test
    fun `LTR では横スクロールの向きが鏡にならない`() {
        val (lines, cut, total) = buildFileLines("z".repeat(400))
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OpenCodeTheme(darkTheme = true) {
                    FileViewerBody(
                        viewer = FileViewerUi(
                            open = true,
                            path = "a.kt",
                            payload = FileContentPayload(type = "text", content = "z", receivedBytes = 19),
                            lines = lines,
                            renderTruncated = cut,
                            totalLines = total,
                        ),
                        actions = RecordingFileActions(),
                        onOpenSettings = {},
                    )
                }
            }
        }
        assertFalse(
            "LTR なのに送る向きが鏡になっている",
            compose.onNodeWithContentDescription("file-hscroll:", substring = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.HorizontalScrollAxisRange]
                .reverseScrolling,
        )
    }

    /** RTL 側は別のテストで(`setContent` は1テストに1回しか呼べない)。 */
    @Test
    fun `RTL の横スクロールは反転している`() {
        val (lines, cut, total) = buildFileLines("z".repeat(400))
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                OpenCodeTheme(darkTheme = true) {
                    FileViewerBody(
                        viewer = FileViewerUi(
                            open = true,
                            path = "a.kt",
                            payload = FileContentPayload(type = "text", content = "z", receivedBytes = 19),
                            lines = lines,
                            renderTruncated = cut,
                            totalLines = total,
                        ),
                        actions = RecordingFileActions(),
                        onOpenSettings = {},
                    )
                }
            }
        }
        assertTrue(
            "RTL なのに送る向きが LTR のままである",
            compose.onNodeWithContentDescription("file-hscroll:", substring = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.HorizontalScrollAxisRange]
                .reverseScrolling,
        )
    }

    /** 行キーは**ファイルパスで修飾する**。作り方は [fileLineKey] にしかない。 */
    @Test
    fun `本文の行キーはファイルパスを含む`() {
        assertEquals("l:src/a.kt:3", fileLineKey("src/a.kt", 3))
        assertFalse(
            "別のファイルの同じ行が同じ鍵になっている",
            fileLineKey("a.kt", 3) == fileLineKey("b.kt", 3),
        )
    }

    /**
     * **§5b の陰性側ゲート**: `type:"binary"` で **base64 が画面に1文字も出ない**。
     *
     * 判定を描画側の `if` にすると、その `if` を消す変異が
     * 「画面はどこも壊れて見えない」まま base64 を dump へ流す。
     * ここは**出ていないこと**を、画面に在る文字列を全部集めて主張する。
     */
    @Test
    fun `バイナリでは base64 が画面に出ない`() {
        val base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileViewerBody(
                    viewer = FileViewerUi(
                        open = true,
                        path = "image.bin",
                        payload = FileContentPayload(
                            type = "binary",
                            content = base64,
                            encoding = "base64",
                            mimeType = "image/png",
                            receivedBytes = 116,
                        ),
                        // Controller はバイナリで行を組まない。**ここでも組まない**ことを二重に示す。
                        lines = emptyList(),
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        val strings = visibleStrings()
        assertTrue("何かは出ている", strings.isNotEmpty())
        assertFalse(
            "base64 が画面に出ている: " + strings.joinToString(" | "),
            strings.any { it.contains(base64) || it.contains("iVBORw0KGgo") },
        )
        // 代わりに mimeType とサイズが出る。
        compose.onNodeWithContentDescription("file-binary").assertExists()
        assertTrue(strings.any { it.contains("image/png") })
        assertTrue(strings.any { it.contains("116") })
    }

    @Test
    fun `受信を打ち切ったら注記が出る`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileViewerBody(
                    viewer = FileViewerUi(
                        open = true,
                        path = "big.txt",
                        payload = FileContentPayload(
                            type = "text",
                            content = "a",
                            truncated = true,
                            receivedBytes = 524288,
                        ),
                        lines = buildFileLines("a").first,
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("file-truncated:524288").assertExists()
    }

    /**
     * **空の本文は「0バイトのファイル」だと断言しない**(レビュー blocker-1)。
     *
     * 1周目のこのテストは `empty-state:file-empty` の存在だけを見ており、
     * **バグを要件として固定していた** —— 画面は
     * 「空のファイルです / **0 バイト。読み込みに失敗したわけではありません。**」と出していたが、
     * サーバーはその区別を持っていない(実測。3つの応答がバイト単位で同一):
     *
     * ```
     * GET /file/content?path=no/such/file.txt         -> 200 {"type":"text","content":""}
     * GET /file/content?path=docs/DOES_NOT_EXIST.md   -> 200 {"type":"text","content":""}
     * GET /file/content?path=<本物の0バイトファイル>   -> 200 {"type":"text","content":""}
     * ```
     *
     * したがってこのテストは**言ってはいけないことを言っていないこと**を主張する。
     * P4 の `PermissionRepliedEvent` と Q3 の質問カードに前例がある形
     * (**誤った挙動を通すテストが、修正を退行として見せる**)。
     */
    @Test
    fun `空の本文を0バイトのファイルだと断言しない`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileViewerBody(
                    viewer = FileViewerUi(
                        open = true,
                        path = "empty.txt",
                        payload = FileContentPayload(type = "text", content = "", receivedBytes = 28),
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("empty-state:file-empty-or-missing").assertExists()
        val strings = visibleStrings()
        // **断言を禁じる。** 文言を変えただけの再発を落とす。
        assertFalse(
            "「0 バイト」と断言している: " + strings.joinToString(" | "),
            strings.any { it.contains("0 バイト") },
        )
        assertFalse(
            "「失敗ではない」と断言している: " + strings.joinToString(" | "),
            strings.any { it.contains("読み込みに失敗したわけではありません") },
        )
        // **区別できないことを言っていること。**
        assertTrue(strings.any { it.contains("区別できません") })
    }

    /**
     * **打ち切りで本文が空になったとき、「空のファイル」と言わない**(レビュー blocker-1 の経路2)。
     *
     * 1周目は `fileViewerEmptyState` が `payload.truncated` を一度も見ておらず、
     * 画面に**打ち切り注記と「0 バイト。読み込みに失敗したわけではありません。」が同時に**出た。
     */
    @Test
    fun `打ち切りで本文が空でも空のファイルと言わない`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileViewerBody(
                    viewer = FileViewerUi(
                        open = true,
                        path = "huge.json",
                        payload = FileContentPayload(
                            type = "text",
                            content = "",
                            truncated = true,
                            receivedBytes = 524288,
                        ),
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("empty-state:file-truncated-empty").assertExists()
        compose.onNodeWithContentDescription("empty-state:file-empty-or-missing").assertDoesNotExist()
        // 打ち切り注記は出たままでよい(むしろ出るべき)。矛盾するのは本文側の断言のほうだった。
        compose.onNodeWithContentDescription("file-truncated:524288").assertExists()
    }

    // ---------------------------------------------------------------------
    // ツリーの空状態(レビュー major-1: 検出器が1本も無かった)
    // ---------------------------------------------------------------------

    /**
     * **`build/` のように子が全部 `ignored:true` のディレクトリで「空です」と言わない。**
     *
     * レビューの変異 N12(`ui.ignoredCount > 0 ->` を `false ->` に)は **760件全緑で通過**した ——
     * `files-all-ignored` も `SHOW_IGNORED` も**テストから一度も参照されていなかった**。
     * KDoc は症状(「実際には数千個ある」)まで書いていたのに**何も強制していなかった**。
     * Q6 の「KDoc の虚偽」と同じ形である。
     */
    @Test
    fun `子が全部 ignored のディレクトリで空ですと言わない`() {
        val actions = RecordingFileActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileTreeArea(
                    tree = FileTreeUi(
                        path = "app/build",
                        entries = appEntries.filter { it.ignored },
                        loaded = true,
                    ),
                    actions = actions,
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("empty-state:files-all-ignored").assertExists()
        compose.onNodeWithContentDescription("empty-state:files-empty").assertDoesNotExist()
        // **導線が実際に繋がっていること**(Q7 の変異 RD と同じ形の穴を塞ぐ)。
        compose.onNodeWithContentDescription("empty-state-action:files-all-ignored:SHOW_IGNORED")
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf("toggleIgnored"), actions.calls)
    }

    /** 本当に空のディレクトリは `files-empty`。**2つが同じ key になったら区別が消える。** */
    @Test
    fun `本当に空のディレクトリは別の空状態になる`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileTreeArea(
                    tree = FileTreeUi(path = "app/empty", entries = emptyList(), loaded = true),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("empty-state:files-empty").assertExists()
        compose.onNodeWithContentDescription("empty-state:files-all-ignored").assertDoesNotExist()
    }

    /** 401 では**再試行を出さない**(共通規則)。 */
    @Test
    fun `ビューアの401では再試行を出さない`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileViewerBody(
                    viewer = FileViewerUi(
                        open = true,
                        path = "x.kt",
                        error = "認証エラー",
                        errorIsAuth = true,
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("empty-state:file-unauthorized").assertExists()
        compose.onNodeWithContentDescription("empty-state-action:file-unauthorized:RETRY").assertDoesNotExist()
        compose.onNodeWithContentDescription("empty-state-action:file-unauthorized:OPEN_SETTINGS").assertExists()
    }

    /**
     * **`diff` を持つときだけ Q7 の差分ビューアを再利用する**(§5b スコープ2)。
     * 1.18.21 は `diff` を返さないので、**この経路を測れるのはここだけ**である。
     */
    @Test
    fun `diff を持つと Q7 の差分ビューアを再利用する`() {
        val diff = Q7DiffFixtures.TWO_HUNKS
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileViewerBody(
                    viewer = FileViewerUi(
                        open = true,
                        path = "big.py",
                        payload = FileContentPayload(type = "text", content = "x", diff = diff, receivedBytes = 10),
                        lines = buildFileLines("x").first,
                        showDiff = true,
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        // Q7 の計器がそのまま出る = 本当に再利用している。
        compose.onNodeWithContentDescription("diff-file:big.py:expanded").assertExists()
    }

    // ---------------------------------------------------------------------
    // 検索(スコープ3・5)
    // ---------------------------------------------------------------------

    @Test
    fun `全文検索はグループと行を描き行から開ける`() {
        val actions = RecordingFileActions()
        val groups = groupTextMatches(Q8Decode.matches(Q8Fixtures.FIND_TWO_SUBMATCHES_JSON))
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileSearchResults(
                    search = FileSearchUi(
                        tab = FileSearchTab.TEXT,
                        query = "the",
                        submittedQuery = "the",
                        textGroups = groups,
                        searched = true,
                    ),
                    actions = actions,
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("find-group:gradlew.bat:1").assertExists()
        compose.onNodeWithContentDescription("find-hit:gradlew.bat:4:2")
            .assertHasClickAction()
            .performClick()
        // **行番号を持ったまま開く。** 落とすと「検索で見つけた行へ飛べない」。
        assertEquals(listOf("openFile:gradlew.bat:4"), actions.calls)
    }

    /**
     * **シンボル0件の3分岐が画面に出ること。** これが §5b スコープ5 の要求そのもの。
     * 「見つからない」と「索引が使えない」が**違う `content-desc`** になる。
     */
    @Test
    fun `シンボルの索引なしと該当なしは別の空状態になる`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileSearchResults(
                    search = FileSearchUi(
                        tab = FileSearchTab.SYMBOLS,
                        query = "x",
                        submittedQuery = "x",
                        searched = true,
                        symbolIndex = dev.opencode.android.ui.SymbolIndexState.UNAVAILABLE,
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("empty-state:symbols-index-unavailable").assertExists()
        compose.onNodeWithContentDescription("empty-state:symbols-search-empty").assertDoesNotExist()
    }

    @Test
    fun `索引が在れば該当なしと出る`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileSearchResults(
                    search = FileSearchUi(
                        tab = FileSearchTab.SYMBOLS,
                        query = "x",
                        submittedQuery = "x",
                        searched = true,
                        symbolIndex = dev.opencode.android.ui.SymbolIndexState.AVAILABLE,
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("empty-state:symbols-search-empty").assertExists()
        compose.onNodeWithContentDescription("empty-state:symbols-index-unavailable").assertDoesNotExist()
    }

    /** **上限に張り付いたことを黙らない**(サーバーが10件で切る)。 */
    @Test
    fun `全文検索が上限に張り付いたら注記が出る`() {
        val one = groupTextMatches(Q8Decode.matches(Q8Fixtures.FIND_TWO_SUBMATCHES_JSON))[0]
        val ten = List(10) { one.hits[0] }
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                FileSearchResults(
                    search = FileSearchUi(
                        tab = FileSearchTab.TEXT,
                        query = "the",
                        submittedQuery = "the",
                        textGroups = listOf(one.copy(hits = ten)),
                        searched = true,
                    ),
                    actions = RecordingFileActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithContentDescription("find-capped:10").assertExists()
    }
}
