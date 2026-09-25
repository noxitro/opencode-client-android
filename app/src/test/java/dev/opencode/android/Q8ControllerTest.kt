package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.FileContentPayload
import dev.opencode.android.data.FileNodeDto
import dev.opencode.android.data.SYMBOL_INDEX_PROBE
import dev.opencode.android.data.SymbolDto
import dev.opencode.android.data.SymbolLocationDto
import dev.opencode.android.data.VcsFileStatusDto
import dev.opencode.android.ui.FILE_SEARCH_DEBOUNCE_MS
import dev.opencode.android.ui.EmptyStateAction
import dev.opencode.android.ui.FileBrowserController
import dev.opencode.android.ui.FileBrowserPane
import dev.opencode.android.ui.FilePresence
import dev.opencode.android.ui.filePresenceIn
import dev.opencode.android.ui.FileSearchUi
import dev.opencode.android.ui.FileTreeUi
import dev.opencode.android.ui.FileViewerUi
import dev.opencode.android.ui.FileBodyKind
import dev.opencode.android.ui.fileBrowserPane
import dev.opencode.android.ui.fileNoticesOf
import dev.opencode.android.ui.focusIndexOf
import dev.opencode.android.ui.ignoredToggleLabel
import dev.opencode.android.ui.fileEntriesOf
import dev.opencode.android.ui.fileTreeEmptyState
import dev.opencode.android.ui.fileViewerEmptyState
import dev.opencode.android.ui.FileSearchTab
import dev.opencode.android.ui.SymbolIndexState
import dev.opencode.android.ui.fileSearchEmptyState
import dev.opencode.android.ui.fileTreeEmptyState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileBrowserController] の状態遷移(Q8)。
 *
 * ## なぜ状態機械にテストを置くのか
 *
 * Q1 のレビューが「**純関数に切り出せなかった部分にテストが1本も無い**」を変異2本で示して以来、
 * このプロジェクトは**状態遷移そのものに検出器を置く**ことを規則にしている
 * (RUN_PLAN「検出器の穴という欠陥形」)。Q8 で切り出せないのは:
 *
 *  - 「開くたびに引き直す」(ブランチと違ってツリーは**変わるもの**)
 *  - 「デバウンスして最後の1本だけ撃つ」
 *  - **「0件だったら校正クエリをもう1本撃つ」** ← §5b スコープ5 の中核
 *  - 「接続先が変わったら捨てる」
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q8ControllerTest {

    private fun controller(
        scope: TestScope,
        files: FakeFilesGateway = FakeFilesGateway(),
        vcs: FakeVcsGateway = FakeVcsGateway(),
        maxLines: Int = 5000,
    ) = FileBrowserController(files, vcs, scope, describeError = { "e:$it" }, maxLines = maxLines)

    // ---------------------------------------------------------------------
    // ツリー(スコープ1)
    // ---------------------------------------------------------------------

    @Test
    fun `ディレクトリを開くと実データの行が並ぶ`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.listResult = ApiResult.Ok(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON))
        val c = controller(scope, gw)

        c.openDirectory("app")
        scope.runCurrent()

        assertEquals(listOf("app"), gw.listedPaths)
        assertEquals(4, c.state.value.tree.entries.size)
        assertTrue(c.state.value.tree.loaded)
    }

    /**
     * **`ignored` は既定で隠すが、件数は出す。** 隠すことは「無い」ではない
     * (Q4 の除外件数表示と同じ原則)。
     */
    @Test
    fun `ignored は既定で隠れるが件数は残る`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.listResult = ApiResult.Ok(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON))
        val c = controller(scope, gw)
        c.openDirectory("app")
        scope.runCurrent()

        assertEquals(3, c.state.value.tree.visibleEntries.size)
        assertEquals(1, c.state.value.tree.ignoredCount)

        c.toggleIgnored()
        assertEquals(4, c.state.value.tree.visibleEntries.size)
        // **通信しない。** 隠す/出すの往復にネットワークの失敗を混ぜない。
        assertEquals(1, gw.listedPaths.size)
    }

    /** **開くたびに引き直す**(判断1)。ブランチと違ってディレクトリの中身は変わる。 */
    @Test
    fun `同じディレクトリを開き直すと引き直す`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = controller(scope, gw)
        c.openDirectory("app")
        scope.runCurrent()
        c.openDirectory("app")
        scope.runCurrent()
        assertEquals(listOf("app", "app"), gw.listedPaths)
    }

    /** **`ensureTreeLoaded` は一度読んだら引き直さない**(回転・戻るから無条件に呼ぶ口)。 */
    @Test
    fun `ensureTreeLoaded は一度読んだら引かない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = controller(scope, gw)
        c.ensureTreeLoaded()
        scope.runCurrent()
        c.ensureTreeLoaded()
        scope.runCurrent()
        assertEquals(1, gw.listedPaths.size)
    }

    @Test
    fun `変更バッジは vcs status から付く`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.listResult = ApiResult.Ok(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON))
        val vcs = FakeVcsGateway(
            status = ApiResult.Ok(
                listOf(VcsFileStatusDto(file = "app/build.gradle.kts", additions = 2, deletions = 1, status = "modified")),
            ),
        )
        val c = controller(scope, gw, vcs)
        c.openDirectory("app")
        scope.runCurrent()
        assertEquals(
            "modified",
            c.state.value.tree.entries.first { it.name == "build.gradle.kts" }.vcsStatus,
        )
    }

    /** **`vcs/status` が失敗しても一覧は出す。** バッジが付かないほうが軽い。 */
    @Test
    fun `変更バッジの取得に失敗しても一覧は出る`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.listResult = ApiResult.Ok(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON))
        val vcs = FakeVcsGateway(status = ApiResult.Err(ApiError.Http(500)))
        val c = controller(scope, gw, vcs)
        c.openDirectory("app")
        scope.runCurrent()
        assertEquals(4, c.state.value.tree.entries.size)
        assertNull(c.state.value.tree.error)
    }

    /** **失敗したら古い一覧を残さない。** どのディレクトリを見ているのか分からなくなる。 */
    @Test
    fun `一覧の取得に失敗したら前の中身を消す`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.listResult = ApiResult.Ok(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON))
        val c = controller(scope, gw)
        c.openDirectory("app")
        scope.runCurrent()

        gw.listResult = ApiResult.Err(ApiError.Http(500))
        c.openDirectory("app/src")
        scope.runCurrent()

        assertEquals(emptyList<Any>(), c.state.value.tree.entries)
        assertEquals("app/src", c.state.value.tree.path)
    }

    /** 401 は**再試行を出さない**(EmptyStates の共通規則)。 */
    @Test
    fun `401 では再試行を出さない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.listResult = ApiResult.Err(ApiError.Http(401))
        val c = controller(scope, gw)
        c.openDirectory(".")
        scope.runCurrent()

        val spec = fileTreeEmptyState(c.state.value.tree)!!
        assertEquals("files-unauthorized", spec.key)
        assertTrue(spec.actions.none { it.action.name == "RETRY" })
    }

    /** 親へ戻る。ルートでは何も起きない。 */
    @Test
    fun `親へ戻る`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = controller(scope, gw)
        c.openDirectory("app/src/main")
        scope.runCurrent()
        c.openParent()
        scope.runCurrent()
        assertEquals("app/src", c.state.value.tree.path)

        c.openDirectory(".")
        scope.runCurrent()
        val before = gw.listedPaths.size
        c.openParent()
        scope.runCurrent()
        assertEquals("ルートの親は無い", before, gw.listedPaths.size)
    }

    // ---------------------------------------------------------------------
    // ツリーの空状態(レビュー major-1: 変異 N12 が760件全緑で通過した場所)
    // ---------------------------------------------------------------------

    /**
     * **子が全部 `ignored:true` のディレクトリを「空です」と言わない。**
     *
     * `fileTreeEmptyState` の KDoc は「`build/` を開くと『空です』と出る。実際には数千個ある」と
     * 症状まで書いていたのに、**その区別を強制するテストが1本も無かった**
     * (レビューの変異 `ui.ignoredCount > 0 ->` → `false ->` が PASS_THROUGH)。
     * KDoc が主張する区別には検出器を置く —— Q6 の「KDoc の虚偽」と同じ形だった。
     */
    @Test
    fun `子が全部 ignored なら空ではなく隠していると言う`() {
        val all = fileEntriesOf(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON), emptyList())
        val ui = FileTreeUi(path = "app", entries = all.filter { it.ignored }, loaded = true)
        val spec = fileTreeEmptyState(ui)!!
        assertEquals("files-all-ignored", spec.key)
        assertTrue("隠している件数を出す", spec.body!!.contains("1 件"))
        assertEquals(
            listOf(EmptyStateAction.SHOW_IGNORED),
            spec.actions.map { it.action },
        )
    }

    /** 本当に0件のディレクトリは別の key。**同じ key になったら区別が消える。** */
    @Test
    fun `本当に空のディレクトリは files-empty`() {
        val spec = fileTreeEmptyState(FileTreeUi(path = "x", entries = emptyList(), loaded = true))!!
        assertEquals("files-empty", spec.key)
        assertTrue("隠していないので導線を出さない", spec.actions.isEmpty())
    }

    /** **取得前は空状態を出さない**(まだ何も知らないのに「空です」と言わない)。 */
    @Test
    fun `取得前は空状態を出さない`() {
        assertNull(fileTreeEmptyState(FileTreeUi(path = "x", loaded = false)))
    }

    /** ignored を表示中なら、見えている行があるので空状態は出ない。 */
    @Test
    fun `ignored を表示すれば空状態は消える`() {
        val all = fileEntriesOf(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON), emptyList())
        val ui = FileTreeUi(path = "app", entries = all.filter { it.ignored }, loaded = true, showIgnored = true)
        assertNull(fileTreeEmptyState(ui))
    }

    // ---------------------------------------------------------------------
    // ビューアの空状態(レビュー blocker-1: 応答が支持できない断言をしていた)
    // ---------------------------------------------------------------------

    /**
     * **空の本文を「0バイトのファイル」だと断言しない。**
     *
     * 実測(実物 serve 1.18.21)—— 3つはバイト単位で同一(いずれも28バイト):
     * ```
     * GET /file/content?path=no/such/file.txt         -> 200 {"type":"text","content":""}
     * GET /file/content?path=docs/DOES_NOT_EXIST.md   -> 200 {"type":"text","content":""}
     * GET /file/content?path=<本物の0バイトファイル>   -> 200 {"type":"text","content":""}
     * ```
     * サーバーが持っていない区別を、アプリが持っているふりをしていた。
     */
    // ---------------------------------------------------------------------
    // 「0バイト」なのか「そのパスが無い」のか(申し送り6件目の回収)
    // ---------------------------------------------------------------------

    /**
     * **親の一覧との突き合わせは区切りを正規化してから行う。**
     *
     * 一覧の `path` はサーバーOSの区切り(`\`)で来て、ディレクトリには末尾の区切りが付く
     * ([FilePaths] の doc)。生の文字列で比べると**同じファイルなのに ABSENT と読む** ——
     * この関数の失敗の仕方は「見つからない」と嘘をつくことなので、そこを固定する。
     */
    @Test
    fun `在り所は区切りを正規化して突き合わせる`() {
        val entries = listOf(
            FileNodeDto(name = "empty.txt", path = "app\\empty.txt", type = "file"),
            FileNodeDto(name = "src", path = "app\\src\\", type = "directory"),
        )
        assertEquals(FilePresence.PRESENT, filePresenceIn(entries, "app/empty.txt"))
        assertEquals(FilePresence.PRESENT, filePresenceIn(entries, "app/src"))
        assertEquals(FilePresence.ABSENT, filePresenceIn(entries, "app/gone.txt"))
        assertEquals(FilePresence.ABSENT, filePresenceIn(emptyList(), "app/empty.txt"))
    }

    /** 本文が空 かつ 親の一覧に在る → **0バイトだと言い切ってよい**。 */
    @Test
    fun `本文が空で親の一覧に在れば0バイトと分かる`() = runTest {
        val scope = TestScope(testScheduler)
        val files = FakeFilesGateway()
        files.readResult = ApiResult.Ok(FileContentPayload(type = "text", content = ""))
        files.listResult = ApiResult.Ok(
            listOf(FileNodeDto(name = "empty.txt", path = "app\\empty.txt", type = "file")),
        )
        val c = controller(scope, files)

        c.openFile("app/empty.txt")
        scope.runCurrent()

        assertEquals(FilePresence.PRESENT, c.state.value.viewer.presence)
        // **親を訊いている**(ファイル自身ではない)。
        assertEquals(listOf("app"), files.listedPaths)
        assertEquals("file-empty", fileViewerEmptyState(c.state.value.viewer)!!.key)
    }

    /** 本文が空 かつ 親の一覧に無い → **そのパスが無い**。 */
    @Test
    fun `本文が空で親の一覧に無ければ見つからないと分かる`() = runTest {
        val scope = TestScope(testScheduler)
        val files = FakeFilesGateway()
        files.readResult = ApiResult.Ok(FileContentPayload(type = "text", content = ""))
        files.listResult = ApiResult.Ok(
            listOf(FileNodeDto(name = "other.txt", path = "app\\other.txt", type = "file")),
        )
        val c = controller(scope, files)

        c.openFile("app/gone.txt")
        scope.runCurrent()

        assertEquals(FilePresence.ABSENT, c.state.value.viewer.presence)
        assertEquals("file-missing", fileViewerEmptyState(c.state.value.viewer)!!.key)
    }

    /**
     * **問い合わせが失敗したら「無い」と言わない。**
     *
     * ここが `ABSENT` に倒れると、ネットワークが切れているだけのときに
     * 「このパスは見つかりませんでした」と嘘をつく。1周目の文言へ戻るのが正しい。
     */
    @Test
    fun `親の一覧が引けなければ区別できないままにする`() = runTest {
        val scope = TestScope(testScheduler)
        val files = FakeFilesGateway()
        files.readResult = ApiResult.Ok(FileContentPayload(type = "text", content = ""))
        files.listResult = ApiResult.Err(ApiError.Network("boom"))
        val c = controller(scope, files)

        c.openFile("app/empty.txt")
        scope.runCurrent()

        assertEquals(FilePresence.UNKNOWN, c.state.value.viewer.presence)
        val spec = fileViewerEmptyState(c.state.value.viewer)!!
        assertEquals("file-empty-or-missing", spec.key)
        assertTrue("区別できないことを言う", spec.body.orEmpty().contains("区別できません"))
    }

    /**
     * **本文があるときは余計な1本を撃たない。**
     *
     * 常に撃つ実装にすると、ファイルを開くたびに往復が倍になる。
     * 「空だったときだけ」という条件そのものを固定する。
     */
    @Test
    fun `本文があるときは親の一覧を訊かない`() = runTest {
        val scope = TestScope(testScheduler)
        val files = FakeFilesGateway()
        files.readResult = ApiResult.Ok(FileContentPayload(type = "text", content = "hello"))
        val c = controller(scope, files)

        c.openFile("app/a.txt")
        scope.runCurrent()

        assertEquals(emptyList<String>(), files.listedPaths)
        assertEquals(FilePresence.UNKNOWN, c.state.value.viewer.presence)
    }

    /**
     * **打ち切りで空になったときも撃たない。**
     *
     * 打ち切りは「こちらが読むのをやめた」であって、サーバーの応答が空なのではない。
     * ここで親を訊くと、実在するファイルに対して `file-empty` を出しかねない。
     */
    @Test
    fun `打ち切りで空になったときは親の一覧を訊かない`() = runTest {
        val scope = TestScope(testScheduler)
        val files = FakeFilesGateway()
        files.readResult = ApiResult.Ok(
            FileContentPayload(type = "text", content = "", truncated = true, receivedBytes = 524288),
        )
        val c = controller(scope, files)

        c.openFile("app/big.txt")
        scope.runCurrent()

        assertEquals(emptyList<String>(), files.listedPaths)
        assertEquals("file-truncated-empty", fileViewerEmptyState(c.state.value.viewer)!!.key)
    }

    @Test
    fun `空の本文は0バイトだと断言しない`() {
        val ui = FileViewerUi(
            open = true,
            path = "x.txt",
            payload = FileContentPayload(type = "text", content = "", receivedBytes = 28),
        )
        val spec = fileViewerEmptyState(ui)!!
        assertEquals("file-empty-or-missing", spec.key)
        val text = spec.title + "\n" + spec.body.orEmpty()
        assertFalse("「0 バイト」と断言しない", text.contains("0 バイト"))
        assertFalse("失敗を否定しない", text.contains("読み込みに失敗したわけではありません"))
        assertTrue("区別できないことを言う", text.contains("区別できません"))
    }

    /**
     * **打ち切りで本文が空になったときは別の状態。**
     *
     * 1周目は `fileViewerEmptyState` が `payload.truncated` を一度も見ておらず、
     * 画面に**打ち切り注記と「0 バイト」が同時に**出た。
     */
    @Test
    fun `打ち切りで本文が空なら空のファイルと言わない`() {
        val ui = FileViewerUi(
            open = true,
            path = "huge.json",
            payload = FileContentPayload(type = "text", content = "", truncated = true, receivedBytes = 524288),
        )
        val spec = fileViewerEmptyState(ui)!!
        assertEquals("file-truncated-empty", spec.key)
        assertTrue(spec.body!!.contains("ファイルが空だという意味ではありません"))
    }

    /** バイナリと未知種別は空状態ではない(注記が担当する)。 */
    @Test
    fun `バイナリは空状態にしない`() {
        val ui = FileViewerUi(
            open = true,
            path = "a.bin",
            payload = FileContentPayload(type = "binary", content = "AAA", receivedBytes = 20),
        )
        assertNull(fileViewerEmptyState(ui))
    }

    // ---------------------------------------------------------------------
    // KDoc が主張していたのに検出器が無かった区別(変異 N4 / N5 / N6 / N11)
    // ---------------------------------------------------------------------

    /**
     * **トグルのラベルに件数が載ること**(変異 N4)。
     *
     * [FileTreeUi] の KDoc は「トグルと件数を**必ず**出す」と主張していたが、
     * 1周目はラベルが composable の中に素で組まれており、件数を落とす変異が
     * **775件全緑で通り抜けた**。`content-desc` には件数が載っていたので dump では見えたが、
     * **人が読む文字からは消えていた** —— desc は judge のための計器であって、
     * 画面に出ている言葉の代わりではない。
     */
    @Test
    fun `ignored トグルのラベルは件数を必ず含む`() {
        val all = fileEntriesOf(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON), emptyList())
        val hidden = FileTreeUi(path = "app", entries = all, loaded = true)
        assertTrue(ignoredToggleLabel(hidden).contains("${hidden.ignoredCount} 件"))
        val shown = hidden.copy(showIgnored = true)
        assertTrue(ignoredToggleLabel(shown).contains("${shown.ignoredCount} 件"))
        // 0件でも件数を出す(「隠していない」と「0件隠している」を言葉で混ぜない)。
        val none = FileTreeUi(path = "x", entries = all.filter { !it.ignored }, loaded = true)
        assertTrue(ignoredToggleLabel(none).contains("0 件"))
    }

    /**
     * **未知の種別を「バイナリ」と言い切らない**(変異 N5)。
     *
     * [FileBodyKind.UNKNOWN] の KDoc は「**『バイナリ』と決めつけない**」と主張していたが、
     * 注記の文言そのものを「バイナリファイル」に変える変異は通り抜けた。
     * Q7 の `diff-patch-missing`(「バイナリとは限りません」)と同じ形の主張である。
     */
    @Test
    fun `未知の種別はバイナリと言い切らない`() {
        val ui = FileViewerUi(
            open = true,
            path = "x",
            payload = FileContentPayload(type = "", content = "", truncated = true, receivedBytes = 8),
        )
        val notice = fileNoticesOf(ui).first { it.key == "file-type-unknown" }
        assertTrue("「バイナリとは限りません」と言う", notice.text.contains("バイナリとは限りません"))
        assertFalse(
            "バイナリだと言い切っている: " + notice.text,
            notice.text.startsWith("バイナリファイル"),
        )
    }

    /**
     * **行番号(1始まり)→ 添字(0始まり)**(変異 N6)。
     *
     * [FileViewerUi.focusLine] の KDoc は「**1始まり。0 を『先頭』の意味に使わない**」と
     * 主張していたが、変換は `LaunchedEffect` の中に在り、
     * `- 1` を外す変異は **775件全緑で通り抜けた**(スクロール位置は
     * Compose UI テストから assert できない)。純関数へ出して固定する。
     */
    @Test
    fun `行番号は1始まり添字は0始まり`() {
        assertEquals(0, focusIndexOf(1, 10))
        assertEquals(2, focusIndexOf(3, 10))
        // 範囲外は端に寄せる(飛ばない、ではなく端へ)
        assertEquals(9, focusIndexOf(999, 10))
        assertEquals(0, focusIndexOf(0, 10))
        // **null は「送らない」**。0 ではない。
        assertNull(focusIndexOf(null, 10))
        assertNull(focusIndexOf(3, 0))
    }

    /**
     * **`readFile` にも `directory` が渡ること**(レビュー minor-1 / 変異 N11)。
     *
     * `Q8HttpContractTest` が固定しているのは `OpenCodeApi` が組む URL であって、
     * **Controller が `directory` を渡しているか**は別の主張である ——
     * `gateway.readFile(target, null)` の変異は URL のテストからは見えず、
     * レビューの実測でも通り抜けた。`directory` は**落としても 200 が返る**
     * (`?directoy=zzz` のタイポでも cwd の結果が返る)ので、気付く手段がここしか無い。
     */
    @Test
    fun `ファイルを開くときも directory を通す`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = FileBrowserController(
            gw, FakeVcsGateway(), scope,
            describeError = { "e" },
            directory = "/tmp/other-repo",
        )
        c.openFile("a.kt")
        scope.runCurrent()
        assertEquals(listOf("/tmp/other-repo"), gw.readDirectories)
    }

    /** ツリーの一覧も同じ(こちらは1周目から通っていたが、対で固定しておく)。 */
    @Test
    fun `ツリーの一覧も directory を通す`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = FileBrowserController(
            gw, FakeVcsGateway(), scope,
            describeError = { "e" },
            directory = "/tmp/other-repo",
        )
        c.openDirectory("app")
        scope.runCurrent()
        assertEquals(listOf("app"), gw.listedPaths)
    }

    // ---------------------------------------------------------------------
    // 画面の本体がツリーか検索か(レビュー minor-4)
    // ---------------------------------------------------------------------

    /**
     * **判定を画面に書き戻さない。** 1周目は `FileBrowserScreen` の中に
     * `if (ui.search.query.isBlank())` と素で書かれており、その条件を消す変異は
     * 画面のテストからしか見えなかった。
     */
    @Test
    fun `検索語が空ならツリー、あれば検索結果`() {
        assertEquals(FileBrowserPane.TREE, fileBrowserPane(FileSearchUi()))
        assertEquals(FileBrowserPane.TREE, fileBrowserPane(FileSearchUi(query = "   ")))
        assertEquals(FileBrowserPane.SEARCH, fileBrowserPane(FileSearchUi(query = "a")))
        // **入力中の語で切り替える**(撃った語 `submittedQuery` ではない)——
        // 撃つ前でも検索面へ移らないと、1文字目で画面が固まって見える。
        assertEquals(
            FileBrowserPane.SEARCH,
            fileBrowserPane(FileSearchUi(query = "abc", submittedQuery = "")),
        )
    }

    // ---------------------------------------------------------------------
    // ビューア(スコープ2)
    // ---------------------------------------------------------------------

    @Test
    fun `テキストを開くと行が組まれる`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.readResult = ApiResult.Ok(FileContentPayload(type = "text", content = "a\nb\nc", receivedBytes = 5))
        val c = controller(scope, gw)
        c.openFile("a.kt")
        scope.runCurrent()
        assertEquals(listOf("a", "b", "c"), c.state.value.viewer.lines.map { it.text })
        assertEquals(listOf("a.kt"), gw.readPaths)
    }

    /**
     * **バイナリでは行を組まない**(§5b の陰性側ゲート: base64 を画面に出さない)。
     *
     * 描画側で `if` を書くと、その `if` を消す変異が
     * 「画面はどこも壊れて見えない」まま base64 を dump へ流す。
     */
    @Test
    fun `バイナリでは行を組まない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.readResult = ApiResult.Ok(
            FileContentPayload(
                type = "binary",
                content = "AAAAAAAAAAAAAAAAAAAA",
                encoding = "base64",
                mimeType = "application/octet-stream",
                receivedBytes = 116,
            ),
        )
        val c = controller(scope, gw)
        c.openFile("image.bin")
        scope.runCurrent()
        assertEquals(emptyList<Any>(), c.state.value.viewer.lines)
    }

    @Test
    fun `行数上限で切ると注記の材料が入る`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.readResult = ApiResult.Ok(
            FileContentPayload(type = "text", content = (1..10).joinToString("\n"), receivedBytes = 20),
        )
        val c = controller(scope, gw, maxLines = 3)
        c.openFile("a.kt")
        scope.runCurrent()
        assertTrue(c.state.value.viewer.renderTruncated)
        assertEquals(10, c.state.value.viewer.totalLines)
        assertEquals(3, c.state.value.viewer.lines.size)
    }

    /** **`diff` が無ければトグルは押せない。** 押せないものを押したことにしない。 */
    @Test
    fun `diff を持たないとき変更ありトグルは効かない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.readResult = ApiResult.Ok(FileContentPayload(type = "text", content = "a", receivedBytes = 1))
        val c = controller(scope, gw)
        c.openFile("a.kt")
        scope.runCurrent()
        assertFalse(c.state.value.viewer.hasDiff)
        c.toggleDiff()
        assertFalse(c.state.value.viewer.showDiff)
    }

    @Test
    fun `diff を持てば変更ありトグルが効く`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.readResult = ApiResult.Ok(
            FileContentPayload(type = "text", content = "a", diff = "diff --git a/a b/a\n", receivedBytes = 1),
        )
        val c = controller(scope, gw)
        c.openFile("a.kt")
        scope.runCurrent()
        assertTrue(c.state.value.viewer.hasDiff)
        c.toggleDiff()
        assertTrue(c.state.value.viewer.showDiff)
    }

    /** 「再試行」は**同じファイル**をもう一度。別の要求を投げる口を作らない。 */
    @Test
    fun `ビューアの再試行は同じファイルを引き直す`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = controller(scope, gw)
        c.openFile("a.kt", focusLine = 12)
        scope.runCurrent()
        c.retryViewer()
        scope.runCurrent()
        assertEquals(listOf("a.kt", "a.kt"), gw.readPaths)
        assertEquals(12, c.state.value.viewer.focusLine)
    }

    @Test
    fun `閉じると状態が消える`() = runTest {
        val scope = TestScope(testScheduler)
        val c = controller(scope)
        c.openFile("a.kt")
        scope.runCurrent()
        c.closeViewer()
        assertFalse(c.state.value.viewer.open)
        assertEquals("", c.state.value.viewer.path)
    }

    // ---------------------------------------------------------------------
    // 検索(スコープ3・4)
    // ---------------------------------------------------------------------

    /** **1文字ごとに撃たない。** デバウンスの窓が閉じるまでは何も飛ばない。 */
    @Test
    fun `検索はデバウンスして最後の1本だけ撃つ`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = controller(scope, gw)

        c.updateSearchQuery("d")
        scope.advanceTimeBy(FILE_SEARCH_DEBOUNCE_MS / 2)
        c.updateSearchQuery("di")
        scope.advanceTimeBy(FILE_SEARCH_DEBOUNCE_MS / 2)
        c.updateSearchQuery("dif")
        assertEquals("窓が閉じるまでは1本も撃たない", 0, gw.textQueries.size)

        scope.advanceTimeBy(FILE_SEARCH_DEBOUNCE_MS + 1)
        scope.runCurrent()
        assertEquals(listOf("dif"), gw.textQueries)
    }

    @Test
    fun `検索キーはデバウンスを待たない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = controller(scope, gw)
        c.updateSearchQuery("diff")
        c.submitSearch()
        scope.runCurrent()
        assertEquals(listOf("diff"), gw.textQueries)
    }

    @Test
    fun `空の語では撃たない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = controller(scope, gw)
        c.updateSearchQuery("   ")
        scope.advanceTimeBy(FILE_SEARCH_DEBOUNCE_MS * 2)
        scope.runCurrent()
        assertEquals(0, gw.textQueries.size)
        assertFalse(c.state.value.search.searched)
    }

    @Test
    fun `全文検索の結果がグループになる`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.textResult = ApiResult.Ok(Q8Decode.matches(Q8Fixtures.FIND_TWO_SUBMATCHES_JSON))
        val c = controller(scope, gw)
        c.updateSearchQuery("the")
        c.submitSearch()
        scope.runCurrent()
        assertEquals(1, c.state.value.search.textGroups.size)
        assertEquals(1, c.state.value.search.textHitCount)
        assertEquals("the", c.state.value.search.submittedQuery)
    }

    /** **`limit` を必ず送る**(§5b スコープ4)。値は spec の上限 200 以内。 */
    @Test
    fun `ファイル名検索は limit を必ず送る`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.filesResult = ApiResult.Ok(Q8Decode.paths(Q8Fixtures.FIND_FILE_THEME_JSON))
        val c = controller(scope, gw)
        c.setSearchTab(FileSearchTab.FILES)
        c.updateSearchQuery("Theme")
        c.submitSearch()
        scope.runCurrent()
        assertEquals(1, gw.fileQueries.size)
        assertEquals("Theme", gw.fileQueries[0].first)
        assertTrue("spec の上限は200", gw.fileQueries[0].second in 1..200)
        // セパレータを寄せてから並べる。
        assertTrue(c.state.value.search.files.none { it.endsWith("\\") })
    }

    /** タブを変えたら**前のタブの結果を残さない**(シンボル0件の画面に全文の結果が並ばない)。 */
    @Test
    fun `タブを変えると前の結果を捨てる`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.textResult = ApiResult.Ok(Q8Decode.matches(Q8Fixtures.FIND_TWO_SUBMATCHES_JSON))
        val c = controller(scope, gw)
        c.updateSearchQuery("the")
        c.submitSearch()
        scope.runCurrent()
        assertEquals(1, c.state.value.search.textGroups.size)

        c.setSearchTab(FileSearchTab.FILES)
        scope.runCurrent()
        assertEquals(emptyList<Any>(), c.state.value.search.textGroups)
    }

    // ---------------------------------------------------------------------
    // シンボル(スコープ5)—— この段の中核
    // ---------------------------------------------------------------------

    /**
     * **索引が使えないとき**: ユーザーの語も校正クエリも0件 →
     * 「見つからない」ではなく「**索引が使えない**」。
     *
     * これが実物 serve の挙動である(どの語でも `[]`)。
     */
    @Test
    fun `校正クエリも0件なら索引が使えないと判定する`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.symbolResults = { ApiResult.Ok(emptyList()) }
        val c = controller(scope, gw)
        c.setSearchTab(FileSearchTab.SYMBOLS)
        c.updateSearchQuery("DiffController")
        c.submitSearch()
        scope.runCurrent()

        assertEquals(listOf("DiffController", SYMBOL_INDEX_PROBE), gw.symbolQueries)
        assertEquals(SymbolIndexState.UNAVAILABLE, c.state.value.search.symbolIndex)
        assertEquals("symbols-index-unavailable", fileSearchEmptyState(c.state.value.search)!!.key)
    }

    /**
     * **索引は在るが当たらないとき**: 校正クエリが非0 → 「該当なし」。
     *
     * **陽性側はこの環境では測れない**(LSP が動く serve が無い)ので、
     * ここが機構を測る唯一の場所である。
     */
    @Test
    fun `校正クエリが非0なら該当なしと判定する`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.symbolResults = { q ->
            if (q == SYMBOL_INDEX_PROBE) {
                ApiResult.Ok(listOf(SymbolDto(name = "anything", kind = 12, location = SymbolLocationDto())))
            } else {
                ApiResult.Ok(emptyList())
            }
        }
        val c = controller(scope, gw)
        c.setSearchTab(FileSearchTab.SYMBOLS)
        c.updateSearchQuery("NoSuchSymbol")
        c.submitSearch()
        scope.runCurrent()

        assertEquals(SymbolIndexState.AVAILABLE, c.state.value.search.symbolIndex)
        assertEquals("symbols-search-empty", fileSearchEmptyState(c.state.value.search)!!.key)
    }

    /** 当たったら**校正クエリを撃たない**(無駄な往復をしない)。 */
    @Test
    fun `当たったら校正クエリを撃たない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.symbolResults = { ApiResult.Ok(listOf(SymbolDto(name = "x", kind = 12, location = SymbolLocationDto()))) }
        val c = controller(scope, gw)
        c.setSearchTab(FileSearchTab.SYMBOLS)
        c.updateSearchQuery("x")
        c.submitSearch()
        scope.runCurrent()
        assertEquals(listOf("x"), gw.symbolQueries)
        assertEquals(SymbolIndexState.AVAILABLE, c.state.value.search.symbolIndex)
        assertNull(fileSearchEmptyState(c.state.value.search))
    }

    /**
     * **校正そのものが失敗したら「索引が無い」と言わない。**
     * 測れなかったのであって、測って無かったのではない(RUN_PLAN の区別)。
     */
    @Test
    fun `校正が失敗したら判定を保留する`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.symbolResults = { q ->
            if (q == SYMBOL_INDEX_PROBE) ApiResult.Err(ApiError.Network("timeout")) else ApiResult.Ok(emptyList())
        }
        val c = controller(scope, gw)
        c.setSearchTab(FileSearchTab.SYMBOLS)
        c.updateSearchQuery("x")
        c.submitSearch()
        scope.runCurrent()
        assertEquals(SymbolIndexState.UNKNOWN, c.state.value.search.symbolIndex)
        assertEquals("symbols-index-unknown", fileSearchEmptyState(c.state.value.search)!!.key)
    }

    // ---------------------------------------------------------------------
    // 接続先の変更 / 再接続
    // ---------------------------------------------------------------------

    @Test
    fun `接続先が変わったらツリーを捨てる`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.listResult = ApiResult.Ok(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON))
        val c = controller(scope, gw)
        c.onConnectionChanged("http://a:4097")
        c.openDirectory("app")
        scope.runCurrent()
        assertEquals(4, c.state.value.tree.entries.size)

        c.onConnectionChanged("http://b:4097")
        assertEquals("サーバーAのツリーをBの画面に出さない", emptyList<Any>(), c.state.value.tree.entries)
        assertFalse(c.state.value.tree.loaded)
    }

    @Test
    fun `同じ接続先では捨てない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        gw.listResult = ApiResult.Ok(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON))
        val c = controller(scope, gw)
        c.onConnectionChanged("http://a:4097")
        c.openDirectory("app")
        scope.runCurrent()
        c.onConnectionChanged("http://a:4097")
        assertEquals(4, c.state.value.tree.entries.size)
    }

    /** **まだ読んでいなければ再接続で勝手に開かない。** */
    @Test
    fun `再接続は読んでいなければ何もしない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = controller(scope, gw)
        c.onReconnected()
        scope.runCurrent()
        assertEquals(0, gw.listedPaths.size)
    }

    /** 読んでいれば**引き直す**(`file.*` の SSE イベントは存在しない)。 */
    @Test
    fun `再接続は開いているツリーを引き直す`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway()
        val c = controller(scope, gw)
        c.openDirectory("app")
        scope.runCurrent()
        c.onReconnected()
        scope.runCurrent()
        assertEquals(listOf("app", "app"), gw.listedPaths)
    }

    /** 接続先が未設定なら通信しない。 */
    @Test
    fun `未設定では撃たない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = FakeFilesGateway(isConfigured = false)
        val c = controller(scope, gw)
        c.openDirectory("app")
        c.openFile("a.kt")
        c.updateSearchQuery("x")
        scope.advanceTimeBy(FILE_SEARCH_DEBOUNCE_MS * 2)
        scope.runCurrent()
        assertEquals(0, gw.listedPaths.size)
        assertEquals(0, gw.readPaths.size)
        assertEquals(0, gw.textQueries.size)
    }
}
