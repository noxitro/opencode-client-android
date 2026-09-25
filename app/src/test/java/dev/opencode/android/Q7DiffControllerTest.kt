package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.DiffGateway
import dev.opencode.android.data.SnapshotFileDiffDto
import dev.opencode.android.data.VCS_DIFF_CONTEXT
import dev.opencode.android.data.VcsFileDiffDto
import dev.opencode.android.data.VcsFileStatusDto
import dev.opencode.android.data.VcsInfoDto
import dev.opencode.android.ui.DiffController
import dev.opencode.android.ui.DiffFileBody
import dev.opencode.android.ui.DiffFileUi
import dev.opencode.android.ui.diffFileBodyOf
import dev.opencode.android.ui.diffFileNoticeOf
import dev.opencode.android.ui.parseUnifiedDiff
import dev.opencode.android.ui.DiffSource
import dev.opencode.android.ui.ScreenBody
import dev.opencode.android.ui.VcsBranchUi
import dev.opencode.android.ui.branchChip
import dev.opencode.android.ui.diffViewerBody
import dev.opencode.android.ui.workingTreeEmptyState
import dev.opencode.android.ui.workingTreeHeading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q7 の**状態遷移**に検出器を置く(RUN_PLAN「検出器の穴という欠陥形」)。
 *
 * 純関数(パーサ)のテストは [Q7DiffParserTest] にある。**それだけでゲートを閉じない** ——
 * Q1 のレビューは「純関数のテストは完璧なのに切り出せなかった部分にテストが1本も無い」を
 * 変異2本で示し、102件全緑のまま R2 とバッジが死んだ。
 *
 * Q7 で同じ位置にあるのは:
 *  - ブランチを引き直すか(**`vcs.*` の SSE は存在しないので、取り直さなければ永久に古い**)
 *  - 接続先が変わったときに捨てるか(サーバーAのブランチをBに出し続ける)
 *  - **展開したときにだけ**ハンクを組むか(全部先に組むと大きな差分で画面が固まる)
 *  - `mode` と `context` を実際に送るか(**`mode` 無しは実物が 400**)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q7DiffControllerTest {

    private class FakeDiff(override val isConfigured: Boolean = true) : DiffGateway {
        var info: ApiResult<VcsInfoDto> = ApiResult.Ok(VcsInfoDto("master", "master"))
        var status: ApiResult<List<VcsFileStatusDto>> = ApiResult.Ok(emptyList())
        var diff: ApiResult<List<VcsFileDiffDto>> = ApiResult.Ok(emptyList())
        var snapshot: ApiResult<List<SnapshotFileDiffDto>> = ApiResult.Ok(emptyList())

        var infoCalls = 0
        var statusCalls = 0
        val diffQueries = mutableListOf<Pair<String, Int?>>()
        val snapshotQueries = mutableListOf<Pair<String, String?>>()

        // **`directory` は口ごとに別々に控える。** 1つの口にだけ渡す変異を見るため
        // (まとめて数えると「どれかに渡っていれば通る」テストになる)。
        val infoDirectories = mutableListOf<String?>()
        val statusDirectories = mutableListOf<String?>()
        val diffDirectories = mutableListOf<String?>()
        val snapshotDirectories = mutableListOf<String?>()

        override suspend fun vcsInfo(directory: String?): ApiResult<VcsInfoDto> {
            infoCalls++
            infoDirectories += directory
            return info
        }

        override suspend fun vcsStatus(directory: String?): ApiResult<List<VcsFileStatusDto>> {
            statusCalls++
            statusDirectories += directory
            return status
        }

        override suspend fun vcsDiff(mode: String, context: Int?, directory: String?): ApiResult<List<VcsFileDiffDto>> {
            diffQueries += mode to context
            diffDirectories += directory
            return diff
        }

        override suspend fun sessionDiff(
            sessionId: String,
            messageId: String?,
            directory: String?,
        ): ApiResult<List<SnapshotFileDiffDto>> {
            snapshotQueries += sessionId to messageId
            snapshotDirectories += directory
            return snapshot
        }
    }

    private fun TestScope.controller(gateway: DiffGateway, maxLines: Int = 5000) =
        DiffController(gateway, this, describeError = { e ->
            when (e) {
                is ApiError.Http -> "HTTP ${e.code}"
                is ApiError.Network -> "NET ${e.message}"
                ApiError.NotConfigured -> "NOT_CONFIGURED"
            }
        }, maxLines = maxLines)

    private fun fixtureFiles() = listOf(
        VcsFileDiffDto("added2.txt", Q7DiffFixtures.ADDED, 2, 0, "added"),
        VcsFileDiffDto("big.py", Q7DiffFixtures.TWO_HUNKS, 2, 2, "modified"),
        VcsFileDiffDto("image.bin", Q7DiffFixtures.BINARY, 0, 0, "modified"),
    )

    // ---------------- ブランチ ----------------

    @Test
    fun `ブランチはまだ取れていなければ引く`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.ensureBranchLoaded()
        runCurrent()
        assertEquals(1, g.infoCalls)
        assertEquals("master", c.state.value.branch.branch)
    }

    /** **取れたら二度引かない**([ServerInfoController] と同じ形)。 */
    @Test
    fun `取れたブランチは二度引かない`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.ensureBranchLoaded()
        runCurrent()
        c.ensureBranchLoaded()
        runCurrent()
        assertEquals(1, g.infoCalls)
    }

    /** 失敗は `loaded` にしない —— 次に開いたときにもう一度引く。 */
    @Test
    fun `失敗したブランチは次に引き直す`() = runTest {
        val g = FakeDiff()
        g.info = ApiResult.Err(ApiError.Http(500))
        val c = controller(g)
        c.ensureBranchLoaded()
        runCurrent()
        assertEquals("HTTP 500", c.state.value.branch.error)
        assertFalse(c.state.value.branch.loaded)
        c.ensureBranchLoaded()
        runCurrent()
        assertEquals(2, g.infoCalls)
    }

    /**
     * **再接続でブランチを引き直す**(RUN_PLAN 決定2 の Q7 版)。
     * `vcs.*` の SSE は実機 spec に存在しないので、ここを消すと
     * TopAppBar は古いブランチを指したまま二度と直らない。**画面は正常に見える。**
     */
    @Test
    fun `再接続でブランチを引き直す`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.ensureBranchLoaded()
        runCurrent()
        g.info = ApiResult.Ok(VcsInfoDto("feature/x", "master"))
        c.onReconnected()
        runCurrent()
        assertEquals(2, g.infoCalls)
        assertEquals("feature/x", c.state.value.branch.branch)
    }

    /**
     * **接続先が変わったら捨てる。** 捨てないとサーバーAのブランチが
     * サーバーBの TopAppBar に出続ける(Q4 レビュー major-1 と同じ形)。
     */
    @Test
    fun `接続先が変わったら状態を捨てる`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.onConnectionChanged("http://a")
        c.ensureBranchLoaded()
        runCurrent()
        assertEquals("master", c.state.value.branch.branch)
        c.onConnectionChanged("http://b")
        assertNull("捨てる", c.state.value.branch.branch)
    }

    @Test
    fun `同じ接続先への再保存では捨てない`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.onConnectionChanged("http://a")
        c.ensureBranchLoaded()
        runCurrent()
        c.onConnectionChanged("http://a")
        assertEquals("master", c.state.value.branch.branch)
    }

    // ---------------- ブランチのチップ(純関数) ----------------

    @Test
    fun `既定ブランチと同じなら強調しない`() {
        val chip = branchChip(VcsBranchUi(branch = "master", defaultBranch = "master"))
        assertEquals("master", chip?.label)
        assertEquals(false, chip?.emphasized)
        assertEquals("vcs-branch:master:default", chip?.description)
    }

    @Test
    fun `既定ブランチと違えば強調する`() {
        val chip = branchChip(VcsBranchUi(branch = "feature/q7", defaultBranch = "master"))
        assertEquals(true, chip?.emphasized)
        assertEquals("vcs-branch:feature/q7:off-default", chip?.description)
    }

    /** ブランチが取れていなければ**何も出さない**("不明" と書かない)。 */
    @Test
    fun `ブランチが無ければチップは出ない`() {
        assertNull(branchChip(VcsBranchUi()))
        assertNull(branchChip(VcsBranchUi(branch = "")))
    }

    /** `default_branch` が欠けているサーバーでは強調しない(比べる相手が無い)。 */
    @Test
    fun `既定ブランチが不明なら強調しない`() {
        assertEquals(false, branchChip(VcsBranchUi(branch = "x"))?.emphasized)
    }

    // ---------------- 作業ツリー ----------------

    /** **毎回引く。** ブランチと違って変わるものなので、キャッシュしない。 */
    @Test
    fun `作業ツリーは呼ぶたびに引く`() = runTest {
        val g = FakeDiff()
        g.status = ApiResult.Ok(listOf(VcsFileStatusDto("a.txt", 1, 2, "modified")))
        val c = controller(g)
        c.refreshWorkingTree()
        runCurrent()
        c.refreshWorkingTree()
        runCurrent()
        assertEquals(2, g.statusCalls)
        assertEquals(1, c.state.value.workingTree.files.size)
        assertTrue(c.state.value.workingTree.loaded)
    }

    @Test
    fun `作業ツリーの見出しは件数と増減を出す`() = runTest {
        val g = FakeDiff()
        g.status = ApiResult.Ok(
            listOf(
                VcsFileStatusDto("a.txt", 1, 2, "modified"),
                VcsFileStatusDto("b.txt", 3, 0, "added"),
            ),
        )
        val c = controller(g)
        c.refreshWorkingTree()
        runCurrent()
        assertEquals("変更中のファイル 2件 (+4 -2)", workingTreeHeading(c.state.value.workingTree))
    }

    /** **取得前と「取得したが0件」を分ける。** 分けないと未取得が「変更なし」と読める。 */
    @Test
    fun `取得前は空状態を出さない`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        assertNull(workingTreeEmptyState(c.state.value.workingTree))
        c.refreshWorkingTree()
        runCurrent()
        assertEquals("worktree-empty", workingTreeEmptyState(c.state.value.workingTree)?.key)
    }

    /** 401 では「再試行」を出さない(Q6 の規則をこの面にも当てる)。 */
    @Test
    fun `作業ツリーの401では再試行を出さない`() = runTest {
        val g = FakeDiff()
        g.status = ApiResult.Err(ApiError.Http(401))
        val c = controller(g)
        c.refreshWorkingTree()
        runCurrent()
        val spec = workingTreeEmptyState(c.state.value.workingTree)
        assertEquals("worktree-unauthorized", spec?.key)
        assertEquals(
            listOf(dev.opencode.android.ui.EmptyStateAction.OPEN_SETTINGS),
            spec?.actions?.map { it.action },
        )
    }

    // ---------------- ビューア ----------------

    /**
     * **`mode=git` と `context=3` を実際に送る。**
     *
     * `mode` を送らないと実物は 400(実測)。`context` を送らないと
     * サーバー既定でファイル全体が1ハンクになる(実測: 57行のファイルが `@@ -1,57 +1,57 @@`)。
     * どちらも「画面は動くが無駄に重い/そもそも失敗する」形。
     */
    @Test
    fun `作業ツリー差分は mode と context を送る`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.openWorkingTreeDiff()
        runCurrent()
        assertEquals(listOf("git" to VCS_DIFF_CONTEXT), g.diffQueries)
    }

    /** **展開するまでパースしない**(§5b Q7 スコープ2)。 */
    @Test
    fun `折り畳んだままならハンクを組まない`() = runTest {
        val g = FakeDiff()
        g.diff = ApiResult.Ok(fixtureFiles())
        val c = controller(g)
        c.openWorkingTreeDiff()
        runCurrent()
        val files = c.state.value.viewer.files
        assertEquals(3, files.size)
        assertTrue("既定は折り畳み", files.none { it.expanded })
        assertTrue("パース結果を1つも持たない", files.all { it.parsed == null })
        // 見出しに要るものはサーバーの応答から直接来る。
        assertEquals("+2 -0", files[0].countsLabel)
        assertEquals("diff-file:added2.txt:collapsed", files[0].description)
    }

    @Test
    fun `展開するとその1ファイルだけがパースされる`() = runTest {
        val g = FakeDiff()
        g.diff = ApiResult.Ok(fixtureFiles())
        val c = controller(g)
        c.openWorkingTreeDiff()
        runCurrent()
        c.toggleFile("big.py")
        val files = c.state.value.viewer.files
        val big = files.single { it.path == "big.py" }
        assertTrue(big.expanded)
        assertEquals(2, big.parsed?.hunks?.size)
        assertTrue("他のファイルは組まない", files.filter { it.path != "big.py" }.all { it.parsed == null })
        assertEquals("diff-file:big.py:expanded", big.description)
    }

    /** 折り畳んでも組んだハンクは捨てない(開き直すたびに組み直さない)。 */
    @Test
    fun `折り畳んでもパース結果は残る`() = runTest {
        val g = FakeDiff()
        g.diff = ApiResult.Ok(fixtureFiles())
        val c = controller(g)
        c.openWorkingTreeDiff()
        runCurrent()
        c.toggleFile("big.py")
        c.toggleFile("big.py")
        val big = c.state.value.viewer.files.single { it.path == "big.py" }
        assertFalse(big.expanded)
        assertNotNull(big.parsed)
    }

    /** 1ファイル指定で開いた経路は**最初から展開されている**。 */
    @Test
    fun `1ファイル指定なら最初から開く`() = runTest {
        val g = FakeDiff()
        g.diff = ApiResult.Ok(fixtureFiles())
        val c = controller(g)
        c.openWorkingTreeDiff("big.py")
        runCurrent()
        val files = c.state.value.viewer.files
        assertEquals(1, files.size)
        assertTrue(files[0].expanded)
        assertNotNull(files[0].parsed)
        assertEquals("big.py", c.state.value.viewer.title)
    }

    /** 打ち切りは Controller の [DiffController] 経由でも効く(上限を注入して測る)。 */
    @Test
    fun `上限を超えたファイルは切って本当の行数を残す`() = runTest {
        val body = (1..50).joinToString("\n") { "+line $it" }
        val patch = "diff --git a/x b/x\nnew file mode 100644\n--- /dev/null\n+++ b/x\n@@ -0,0 +1,50 @@\n$body\n"
        val g = FakeDiff()
        g.diff = ApiResult.Ok(listOf(VcsFileDiffDto("x", patch, 50, 0, "added")))
        val c = controller(g, maxLines = 5)
        c.openWorkingTreeDiff()
        runCurrent()
        c.toggleFile("x")
        val parsed = c.state.value.viewer.files.single().parsed
        assertTrue(parsed!!.truncated)
        assertEquals(50, parsed.totalLines)
        assertEquals(5, parsed.renderedLines)
    }

    // ---------------- メッセージの差分 ----------------

    @Test
    fun `メッセージ差分は messageID を送る`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.openMessageDiff("ses_1", "msg_9", listOf("a.txt"))
        runCurrent()
        assertEquals(listOf("ses_1" to "msg_9"), g.snapshotQueries)
        assertEquals(DiffSource.MESSAGE, c.state.value.viewer.source)
    }

    /**
     * **空の応答で「変更なし」と言わない。** チップが「1 ファイル変更」と言っている以上、
     * 「変更はありません」は嘘である —— サーバーが本文を返さなかっただけなので、
     * **名前は出す**。実物ではこの経路が常に `[]` を返すので、ここが既定の見え方になる。
     */
    @Test
    fun `差分が空でも変更したファイル名は出す`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.openMessageDiff("ses_1", "msg_9", listOf("a.txt", "b.txt"))
        runCurrent()
        val body = diffViewerBody(c.state.value.viewer)
        assertTrue(body is ScreenBody.Empty)
        val spec = (body as ScreenBody.Empty).spec
        assertEquals("diff-patch-unavailable", spec.key)
        val text = spec.body.orEmpty()
        assertTrue("本文にファイル名が出る: $text", text.contains("a.txt"))
        assertTrue("本文にファイル名が出る: $text", text.contains("b.txt"))
    }

    /** 変更ファイルが分かっていない場合は「変更はありません」でよい。 */
    @Test
    fun `変更ファイルも分からなければ変更なしと言う`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.openMessageDiff("ses_1", "msg_9", emptyList())
        runCurrent()
        val spec = (diffViewerBody(c.state.value.viewer) as ScreenBody.Empty).spec
        assertEquals("diff-message-empty", spec.key)
    }

    /** 作業ツリー側の空は別の文言(どちらの経路から来たかで意味が違う)。 */
    @Test
    fun `作業ツリーの空は別の文言`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.openWorkingTreeDiff()
        runCurrent()
        val spec = (diffViewerBody(c.state.value.viewer) as ScreenBody.Empty).spec
        assertEquals("diff-worktree-empty", spec.key)
    }

    // ---------------- 再試行 ----------------

    /**
     * **「再試行」は直前と同じ要求を出す。** 1周目は画面側が引数を組み立てており、
     * メッセージIDに空文字を渡す経路ができていた —— **押すと別の要求が飛ぶ**再試行は、
     * 失敗しているのかどうかすら分からなくする。
     */
    @Test
    fun `再試行は直前と同じ要求を出す`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.openMessageDiff("ses_1", "msg_9", listOf("a.txt"))
        runCurrent()
        c.retryViewer()
        runCurrent()
        assertEquals(listOf("ses_1" to "msg_9", "ses_1" to "msg_9"), g.snapshotQueries)
    }

    @Test
    fun `何も開いていなければ再試行は何もしない`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.retryViewer()
        runCurrent()
        assertTrue(g.snapshotQueries.isEmpty())
        assertTrue(g.diffQueries.isEmpty())
    }

    @Test
    fun `作業ツリーの再試行は同じファイル指定を保つ`() = runTest {
        val g = FakeDiff()
        g.diff = ApiResult.Ok(fixtureFiles())
        val c = controller(g)
        c.openWorkingTreeDiff("big.py")
        runCurrent()
        c.retryViewer()
        runCurrent()
        assertEquals(2, g.diffQueries.size)
        assertEquals(1, c.state.value.viewer.files.size)
    }

    // ---------------- 未設定 ----------------

    /** 接続先が未設定なら通信を試みない(どの入口も同じ)。 */
    @Test
    fun `未設定なら通信しない`() = runTest {
        val g = FakeDiff(isConfigured = false)
        val c = controller(g)
        c.ensureBranchLoaded()
        c.refreshWorkingTree()
        c.openWorkingTreeDiff()
        c.openMessageDiff("ses_1", "msg_1")
        runCurrent()
        assertEquals(0, g.infoCalls)
        assertEquals(0, g.statusCalls)
        assertTrue(g.diffQueries.isEmpty())
        assertTrue(g.snapshotQueries.isEmpty())
        assertFalse(c.state.value.viewer.open)
    }

    // ---------------- 「読めない」と「来なかった」を分ける(レビュー minor-1) ----------------

    private fun fileUi(patch: String?, expanded: Boolean = true) = DiffFileUi(
        path = "a.txt",
        status = "modified",
        additions = 1,
        deletions = 0,
        patch = patch,
        expanded = expanded,
        parsed = if (expanded) parseUnifiedDiff(patch).firstOrNull() else null,
    )

    /**
     * **`patch` は契約上「任意」**(実機 `/doc`)。サーバーが
     * `{"file":"a.txt","additions":1,"deletions":0}` を返すのは合法で、
     * そのとき1周目の実装は**テキストファイルを「バイナリ」と呼んでいた**。
     * 「読めない」と「来なかった」の区別を消す形は、このリポジトリが繰り返し閉じてきた欠陥である。
     */
    @Test
    fun `patch が来なかったファイルをバイナリと呼ばない`() {
        assertEquals(DiffFileBody.PATCH_MISSING, diffFileBodyOf(fileUi(null)))
        assertEquals("diff-patch-missing", diffFileNoticeOf(DiffFileBody.PATCH_MISSING)?.key)
        assertTrue(
            "バイナリと言わない",
            diffFileNoticeOf(DiffFileBody.PATCH_MISSING)!!.text.contains("バイナリとは限りません"),
        )
    }

    @Test
    fun `空文字の patch も来なかった扱い`() {
        assertEquals(DiffFileBody.PATCH_MISSING, diffFileBodyOf(fileUi("")))
    }

    @Test
    fun `バイナリはバイナリと言う`() {
        assertEquals(DiffFileBody.BINARY, diffFileBodyOf(fileUi(Q7DiffFixtures.BINARY)))
        assertEquals("diff-binary", diffFileNoticeOf(DiffFileBody.BINARY)?.key)
    }

    /** `patch` は来たが解釈できない形。**「来なかった」とは別の言葉にする。** */
    @Test
    fun `解釈できない patch は別の理由として出す`() {
        assertEquals(DiffFileBody.UNREADABLE, diffFileBodyOf(fileUi("これは diff ではありません")))
        assertEquals("diff-unreadable", diffFileNoticeOf(DiffFileBody.UNREADABLE)?.key)
    }

    /** ハンクが0(モード変更のみ等)は「差分が無い」であって「読めない」ではない。 */
    @Test
    fun `ハンクが無い patch は変更行なしと言う`() {
        val patch = "diff --git a/a.txt b/a.txt\nold mode 100644\nnew mode 100755\n"
        assertEquals(DiffFileBody.NO_HUNKS, diffFileBodyOf(fileUi(patch)))
        assertEquals("diff-no-hunks", diffFileNoticeOf(DiffFileBody.NO_HUNKS)?.key)
    }

    /** 通常のファイルには注記を出さない(ハンクだけを描く)。 */
    @Test
    fun `普通の差分には注記を出さない`() {
        assertEquals(DiffFileBody.HUNKS, diffFileBodyOf(fileUi(Q7DiffFixtures.TWO_HUNKS)))
        assertNull(diffFileNoticeOf(DiffFileBody.HUNKS))
    }

    // ---------------- directory の経路(レビュー minor-5) ----------------

    /**
     * QUALITY_PLAN §6:「**Q7〜Q9 の全エンドポイントが `directory` クエリを取るため、
     * データ層は最初から `directory` を通せる形にしておくこと(UIは出さない)**」。
     * Q7 では常に null だが、**経路が無いと Q8 は口の形から作り直すことになる**。
     */
    @Test
    fun `directory は全ての口へそのまま渡る`() = runTest {
        val g = FakeDiff()
        val c = DiffController(g, this, describeError = { "e" }, directory = "/work/repo")
        c.ensureBranchLoaded()
        c.refreshWorkingTree()
        c.openWorkingTreeDiff()
        runCurrent()
        c.openMessageDiff("ses_1", "msg_1")
        runCurrent()
        assertEquals(listOf("/work/repo"), g.infoDirectories)
        assertEquals(listOf("/work/repo"), g.statusDirectories)
        assertEquals(listOf("/work/repo"), g.diffDirectories)
        assertEquals(listOf("/work/repo"), g.snapshotDirectories)
    }

    /** 既定は null(=サーバーの cwd)。**キーごと送らない**ことは HTTP 層のテストが見る。 */
    @Test
    fun `既定では directory を渡さない`() = runTest {
        val g = FakeDiff()
        val c = controller(g)
        c.ensureBranchLoaded()
        runCurrent()
        assertEquals(listOf<String?>(null), g.infoDirectories)
    }

    // ---------------- 閉じる ----------------

    @Test
    fun `閉じると状態が消える`() = runTest {
        val g = FakeDiff()
        g.diff = ApiResult.Ok(fixtureFiles())
        val c = controller(g)
        c.openWorkingTreeDiff()
        runCurrent()
        assertTrue(c.state.value.viewer.open)
        c.closeViewer()
        assertFalse(c.state.value.viewer.open)
        assertTrue(c.state.value.viewer.files.isEmpty())
    }
}
