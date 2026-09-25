package dev.opencode.android

import dev.opencode.android.ui.PtyController
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.contractJson
import dev.opencode.android.ui.ChatActions
import dev.opencode.android.ui.ChatBannerKind
import dev.opencode.android.ui.ChatPart
import dev.opencode.android.ui.FileBrowserController
import dev.opencode.android.ui.ModelCatalogController
import dev.opencode.android.ui.ServerInfoController
import dev.opencode.android.ui.DiffController
import dev.opencode.android.ui.ToolActivityCard
import dev.opencode.android.ui.theme.OpenCodeTheme
import dev.opencode.android.ui.toolInputFilePath
import dev.opencode.android.ui.wireConnectionChangesTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Q8 の**配線**(RUN_PLAN 設計規則1: 宛先はオブジェクトで名指しし、ラムダで受け取らない)。
 *
 * ## 何を守っているか
 *
 * 1. **接続先が変わったらツリーと検索結果も捨てる。** 捨てないと、サーバーAの
 *    ディレクトリ一覧がサーバーBの画面に出続ける —— Q4 レビュー major-1(カタログ)・
 *    Q7(ブランチ)と同じ形で、**画面はどこも壊れて見えない**
 * 2. **チャットのツールカードのパスが `openFileFromChat` に繋がっている**(§5b スコープ6)。
 *    Q7 のレビューが打った変異 RD(`onUnrevert = {}`)と同じ形の穴がここに開きうる ——
 *    導線が無音で死んでも、カードは今までどおり出る
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Q8WiringTest {

    @get:Rule
    val compose = createComposeRule()

    // ---------------------------------------------------------------------
    // 接続先の変更
    // ---------------------------------------------------------------------

    @Test
    fun `接続先が変わるとファイル側も捨てる`() = runTest {
        val scope = TestScope(testScheduler)
        val filesGw = FakeFilesGateway()
        filesGw.listResult = ApiResult.Ok(Q8Decode.fileNodes(Q8Fixtures.FILE_LIST_APP_JSON))
        val files = FileBrowserController(filesGw, FakeVcsGateway(), scope, describeError = { "e" })
        files.onConnectionChanged("http://a:4097")
        files.openDirectory("app")
        scope.runCurrent()
        assertEquals(4, files.state.value.tree.entries.size)

        flowOf<String?>("http://b:4097").wireConnectionChangesTo(
            catalog = ModelCatalogController(Q8NoopCatalog, scope, describeError = { "e" }),
            serverInfo = ServerInfoController(Q8NoopServerInfo, scope, describeError = { "e" }),
            diff = DiffController(FakeVcsGateway(), scope, describeError = { "e" }),
            files = files,
            // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
            pty = PtyController(FakePtyGateway(), scope, describeError = { "e" }),
            onFailure = { throw AssertionError("配布が失敗した: $it") },
        )

        assertEquals(
            "サーバーAのツリーをBの画面に出さない",
            emptyList<Any>(),
            files.state.value.tree.entries,
        )
        assertFalse(files.state.value.tree.loaded)
    }

    // ---------------------------------------------------------------------
    // スコープ6: チャットのツールカード → ファイルビューア
    // ---------------------------------------------------------------------

    /** 呼ばれたことを控えるだけの宛先。 */
    private class RecordingChatActions : ChatActions {
        val calls = mutableListOf<String>()
        override fun unrevert() { calls += "unrevert" }
        override fun retryLoadMessages() { calls += "retry" }
        override fun dismissChatBanner(kind: ChatBannerKind) { calls += "dismiss" }
        override fun openMessageDiff(sessionId: String, messageId: String, knownFiles: List<String>) {
            calls += "diff"
        }
        override fun requestRevert(messageId: String, partId: String?, preview: String) { calls += "revert" }
        override fun openFileFromChat(path: String) { calls += "openFileFromChat:$path" }
    }

    private fun toolPart(inputJson: String): ChatPart {
        val entry = contractJson.decodeFromString<List<dev.opencode.android.data.MessageEntryDto>>(
            """
            [{"info":{"id":"msg_1","sessionID":"ses_1","role":"assistant"},
              "parts":[{"id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"tool",
                        "callID":"call_1","tool":"read",
                        "state":{"status":"completed","input":$inputJson,"title":"read"}}]}]
            """.trimIndent(),
        )
        return dev.opencode.android.ui.initialChatMessages(entry).first().parts.first()
    }

    @Test
    fun `ツールカードのファイルパスがファイルビューアへ繋がる`() {
        val actions = RecordingChatActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                ToolActivityCard(
                    part = toolPart("""{"filePath":"app/src/main/java/x/Theme.kt"}"""),
                    slotKey = "msg_1#0",
                    actions = actions,
                )
            }
        }
        compose.onNodeWithContentDescription("tool-file:app/src/main/java/x/Theme.kt")
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf("openFileFromChat:app/src/main/java/x/Theme.kt"), actions.calls)
    }

    /** **拾えなければ導線を出さない**(押せないチップを置かない)。 */
    @Test
    fun `パスを拾えないツールにはチップを出さない`() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                ToolActivityCard(
                    part = toolPart("""{"pattern":"foo","glob":"*.kt"}"""),
                    slotKey = "msg_1#0",
                    actions = RecordingChatActions(),
                )
            }
        }
        compose.onNodeWithContentDescription("tool-file:foo").assertDoesNotExist()
    }

    // ---------------------------------------------------------------------
    // パス抽出(純関数)
    // ---------------------------------------------------------------------

    @Test
    fun `候補キーを固定順で見る`() {
        assertEquals("a/b.kt", toolInputFilePath(json("""{"filePath":"a/b.kt"}""")))
        assertEquals("a/b.kt", toolInputFilePath(json("""{"path":"a\\b.kt"}""")))
        assertEquals("a/b.kt", toolInputFilePath(json("""{"file":"a/b.kt"}""")))
        // filePath が先(順序に意味がある)
        assertEquals("first.kt", toolInputFilePath(json("""{"path":"second.kt","filePath":"first.kt"}""")))
    }

    /** **文字列でない値は捨てる。** `JsonObject` を `String` として読むと落ちる(L3 の欠陥形)。 */
    @Test
    fun `文字列でない値や空文字は拾わない`() {
        assertNull(toolInputFilePath(json("""{"path":{"nested":"x"}}""")))
        assertNull(toolInputFilePath(json("""{"path":""}""")))
        assertNull(toolInputFilePath(json("""{"other":"x"}""")))
        assertNull(toolInputFilePath(null))
    }

    private fun json(text: String): JsonObject = contractJson.decodeFromString(text)
}

/** 何も返さないカタログ(この配線テストの関心はファイル側だけ)。 */
private object Q8NoopCatalog : dev.opencode.android.data.CatalogGateway {
    override val isConfigured: Boolean = true
    override suspend fun listProviders(): ApiResult<dev.opencode.android.data.ProvidersDto> =
        ApiResult.Ok(dev.opencode.android.data.ProvidersDto())
    override suspend fun listAgents(): ApiResult<List<dev.opencode.android.data.AgentDto>> =
        ApiResult.Ok(emptyList())
}

/** 何も返さないサーバー素性。 */
private object Q8NoopServerInfo : dev.opencode.android.data.ServerInfoGateway {
    override val isConfigured: Boolean = true
    override suspend fun health(): ApiResult<dev.opencode.android.data.HealthDto> =
        ApiResult.Ok(dev.opencode.android.data.HealthDto(healthy = true, version = "1.18.21"))
}
