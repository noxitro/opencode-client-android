package dev.opencode.android

import dev.opencode.android.ui.PtyController
import dev.opencode.android.data.AgentDto
import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.CatalogGateway
import dev.opencode.android.data.DiffGateway
import dev.opencode.android.data.SnapshotFileDiffDto
import dev.opencode.android.data.VcsFileDiffDto
import dev.opencode.android.data.VcsFileStatusDto
import dev.opencode.android.data.VcsInfoDto
import dev.opencode.android.data.ConnectionSetupGateway
import dev.opencode.android.data.DEFAULT_OPENCODE_PORT
import dev.opencode.android.data.HealthDto
import dev.opencode.android.data.ModelCapabilitiesDto
import dev.opencode.android.data.ModelModalityDto
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.ProviderDto
import dev.opencode.android.data.ProviderModelDto
import dev.opencode.android.data.ProvidersDto
import dev.opencode.android.data.ServerInfoGateway
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.data.SessionsGateway
import dev.opencode.android.ui.DiffController
import dev.opencode.android.ui.FileBrowserController
import dev.opencode.android.ui.ModelCatalogController
import dev.opencode.android.ui.SaveAndTestOutcome
import dev.opencode.android.ui.SESSION_PAGE_SIZE
import dev.opencode.android.ui.Screen
import dev.opencode.android.ui.ServerInfoController
import dev.opencode.android.ui.ServerInfoUi
import dev.opencode.android.ui.SessionListController
import dev.opencode.android.ui.applyEnterSessionsEffect
import dev.opencode.android.ui.drawerServerLine
import dev.opencode.android.ui.onSessionListShown
import dev.opencode.android.ui.performSaveAndTestHealth
import dev.opencode.android.ui.serverVersionLabel
import dev.opencode.android.ui.wireConnectionChangesTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
 * **配線層**の検出器(Q5 レビュー major-2)。
 *
 * Q5 の1周目は状態機械と純関数に検出器を置き、自分で打った変異13本を全て検出した。
 * それでもレビューが**呼び出し口**を狙った変異8本のうち**7本が431件全緑で通り抜けた**:
 * `Urls.normalize` の呼び出しを外す(R1)/ 接続先変更の宛先を空にする(R3・Q4 の blocker が再オープン)/
 * `ensureLoaded()` を `refresh()` に変える(R7・Q1 major-1 の退行)/
 * 設定から戻るときのガードを外す(R2)。**どれも画面はどこも壊れて見えない。**
 *
 * 教訓は「関数のテストは『関数が正しい』しか主張しない。**アプリがその関数を通る**は別の主張」
 * ということ。ここは後者を主張する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q5WiringTest {

    /**
     * Q7 で `wireConnectionChangesTo` の宛先が3つになった。ここは Q5 の主張
     * (カタログとサーバー素性を捨てる)を測る場所なので、差分側は**素通しの実体**で足す。
     * 差分側そのものの検出器は [Q7WiringTest] にある。
     */
    private class NoopDiffGateway : DiffGateway {
        override val isConfigured: Boolean = true
        override suspend fun vcsInfo(directory: String?): ApiResult<VcsInfoDto> = ApiResult.Ok(VcsInfoDto())
        override suspend fun vcsStatus(directory: String?): ApiResult<List<VcsFileStatusDto>> = ApiResult.Ok(emptyList())
        override suspend fun vcsDiff(mode: String, context: Int?, directory: String?): ApiResult<List<VcsFileDiffDto>> =
            ApiResult.Ok(emptyList())
        override suspend fun sessionDiff(
            sessionId: String,
            messageId: String?,
            directory: String?,
        ): ApiResult<List<SnapshotFileDiffDto>> =
            ApiResult.Ok(emptyList())
    }

    private fun diffController(scope: TestScope) =
        DiffController(NoopDiffGateway(), scope, describeError = { "e" })

    // ---------------------------------------------------------------------
    // R1: Urls.normalize を通る経路
    // ---------------------------------------------------------------------

    /** 保存された文字列を**そのまま控える**ゲートウェイ。正規化されたかはここでしか見えない。 */
    private class RecordingSetupGateway : ConnectionSetupGateway {
        val saved = mutableListOf<String>()
        var savedPasswordLength = -1
        var healthResult: ApiResult<HealthDto> = ApiResult.Ok(HealthDto(healthy = true, version = "1.18.21"))
        var healthCalls = 0

        override suspend fun save(baseUrl: String, password: String) {
            saved += baseUrl
            savedPasswordLength = password.length
        }

        override suspend fun health(): ApiResult<HealthDto> {
            healthCalls++
            return healthResult
        }
    }

    /**
     * **H1a の修正が実際に効く経路を通ること。**
     *
     * レビューの変異 R1 は `AppViewModel` の `Urls.normalize(rawUrl)` を `rawUrl` に変えるもので、
     * H1a のために足した `UrlsTest` 6本を含む431件が全緑のまま通り抜けた。
     * `UrlsTest` は関数を試すが、**保存される値**は試していなかった。
     */
    @Test
    fun `保存される接続先は正規化済みである`() = runTest {
        val gw = RecordingSetupGateway()
        val outcome = performSaveAndTestHealth("10.0.2.2", "pw", gw, onTesting = {})
        assertEquals(listOf("http://10.0.2.2:$DEFAULT_OPENCODE_PORT"), gw.saved)
        assertTrue(outcome is SaveAndTestOutcome.Measured)
        assertEquals("http://10.0.2.2:$DEFAULT_OPENCODE_PORT", (outcome as SaveAndTestOutcome.Measured).normalized)
    }

    /** 報告された入力そのもの(スキーム付き・ポート無し)が :80 のまま保存されないこと。 */
    @Test
    fun `スキーム付きポート無しも4097を補って保存される`() = runTest {
        val gw = RecordingSetupGateway()
        performSaveAndTestHealth("http://100.64.0.1", "pw", gw, onTesting = {})
        assertEquals(listOf("http://100.64.0.1:$DEFAULT_OPENCODE_PORT"), gw.saved)
        assertFalse(gw.saved.single().endsWith(":80"))
    }

    @Test
    fun `末尾スラッシュと空白も保存前に落ちる`() = runTest {
        val gw = RecordingSetupGateway()
        performSaveAndTestHealth("  http://10.0.2.2:4098/  ", "pw", gw, onTesting = {})
        assertEquals(listOf("http://10.0.2.2:4098"), gw.saved)
    }

    /** 解釈できない入力は**保存もテストもしない**(壊れた接続先で上書きしない)。 */
    @Test
    fun `不正なURLは保存もhealthもしない`() = runTest {
        val gw = RecordingSetupGateway()
        val outcome = performSaveAndTestHealth("ftp://example.com", "pw", gw, onTesting = {
            throw AssertionError("測る前に Testing へ入ってはいけない")
        })
        assertEquals(SaveAndTestOutcome.BadUrl, outcome)
        assertEquals(emptyList<String>(), gw.saved)
        assertEquals(0, gw.healthCalls)
    }

    /** 保存が health より**先**であること(逆順だと古い接続先を測る)。 */
    @Test
    fun `保存してから測る`() = runTest {
        val order = mutableListOf<String>()
        val gw = object : ConnectionSetupGateway {
            override suspend fun save(baseUrl: String, password: String) { order += "save" }
            override suspend fun health(): ApiResult<HealthDto> {
                order += "health"
                return ApiResult.Ok(HealthDto(healthy = true, version = "v"))
            }
        }
        val testing = mutableListOf<String>()
        performSaveAndTestHealth("10.0.2.2", "pw", gw, onTesting = { testing += "testing" })
        assertEquals(listOf("save", "health"), order)
        assertEquals(listOf("testing"), testing)
    }

    @Test
    fun `健全でない応答もそのまま返る`() = runTest {
        val gw = RecordingSetupGateway()
        gw.healthResult = ApiResult.Err(ApiError.Http(401))
        val outcome = performSaveAndTestHealth("10.0.2.2", "pw", gw, onTesting = {})
        val measured = outcome as SaveAndTestOutcome.Measured
        assertTrue(measured.result is ApiResult.Err)
        // 401 でも**保存はされている**(直しに戻れるように)。
        assertEquals(1, gw.saved.size)
    }

    // ---------------------------------------------------------------------
    // R3 / R8: 接続先変更が2つの宛先へ届く
    // ---------------------------------------------------------------------

    private class FakeCatalog(override val isConfigured: Boolean = true) : CatalogGateway {
        var providerCalls = 0
        override suspend fun listProviders(): ApiResult<ProvidersDto> {
            providerCalls++
            // フィクスチャは**実データの形**に(QUALITY_PLAN §4.2)。`capabilities` を
            // 満たさないモデルは `canRunSession` に弾かれ、models が空になって
            // 「捨てた」と「元から無い」の区別が付かなくなる。
            return ApiResult.Ok(
                ProvidersDto(
                    all = listOf(
                        ProviderDto(
                            id = "opencode",
                            name = "opencode",
                            source = "api",
                            models = mapOf(
                                "grok-code" to ProviderModelDto(
                                    id = "grok-code",
                                    name = "Grok Code",
                                    status = "active",
                                    capabilities = ModelCapabilitiesDto(
                                        toolcall = true,
                                        reasoning = false,
                                        input = ModelModalityDto(text = true, audio = false, image = false, video = false, pdf = false),
                                        output = ModelModalityDto(text = true, audio = false, image = false, video = false, pdf = false),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    default = mapOf("opencode" to "grok-code"),
                    connected = listOf("opencode"),
                ),
            )
        }
        override suspend fun listAgents(): ApiResult<List<AgentDto>> = ApiResult.Ok(emptyList())
    }

    private class FakeServerInfo(override val isConfigured: Boolean = true) : ServerInfoGateway {
        var calls = 0
        var result: ApiResult<HealthDto> = ApiResult.Ok(HealthDto(healthy = true, version = "1.18.21"))
        override suspend fun health(): ApiResult<HealthDto> {
            calls++
            return result
        }
    }

    /**
     * **宛先をラムダではなく controller の実体で受け取る**ので、片方を落とす変異は
     * `wireConnectionChangesTo` の中にしか書けない。ここはその中身を試す。
     *
     * カタログを捨てないと Q4 の blocker が戻る: 古いカタログは
     * 「`GET /provider` から得た候補以外を送らない」防波堤を抜け、サーバーB は
     * サーバーA のモデルIDを **204 で受理して保存する**(API_CONTRACT.md 実測 #4)。
     */
    @Test
    fun `接続先が変わると両方の controller がキャッシュを捨てる`() = runTest {
        val scope = TestScope(testScheduler)
        val catalogGw = FakeCatalog()
        val infoGw = FakeServerInfo()
        val catalog = ModelCatalogController(catalogGw, scope, describeError = { "e" })
        val info = ServerInfoController(infoGw, scope, describeError = { "e" })

        // サーバーA を見て、両方を温める。
        catalog.onConnectionChanged("http://a:4097")
        info.onConnectionChanged("http://a:4097")
        catalog.ensureLoaded()
        info.ensureLoaded()
        scope.runCurrent()
        assertEquals(1, catalog.state.value.models.size)
        assertEquals("1.18.21", info.state.value.version)

        // サーバーB へ切り替える配線を通す。
        flowOf<String?>("http://b:4097").wireConnectionChangesTo(
            catalog = catalog,
            serverInfo = info,
            // Q7: 3つ目の宛先。ブランチと作業ツリーもサーバーごとに違う。
            diff = diffController(scope),
            // Q8: 4つ目の宛先。ツリーと検索結果もサーバーごとに違う。
            files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), scope, describeError = { "e" }),
            // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
            pty = PtyController(FakePtyGateway(), scope, describeError = { "e" }),
            onFailure = { throw AssertionError("配布が失敗した: $it") },
        )

        assertEquals(emptyList<Any>(), catalog.state.value.models)
        assertFalse(catalog.state.value.loaded)
        assertNull(info.state.value.version)
    }

    /** 同じ接続先の再保存(パスワードだけ入れ直した等)では捨てない。 */
    @Test
    fun `同じ接続先が流れてもキャッシュは残る`() = runTest {
        val scope = TestScope(testScheduler)
        val catalog = ModelCatalogController(FakeCatalog(), scope, describeError = { "e" })
        val info = ServerInfoController(FakeServerInfo(), scope, describeError = { "e" })
        catalog.onConnectionChanged("http://a:4097")
        info.onConnectionChanged("http://a:4097")
        catalog.ensureLoaded()
        info.ensureLoaded()
        scope.runCurrent()

        flowOf<String?>("http://a:4097", "http://a:4097").wireConnectionChangesTo(
            catalog = catalog,
            serverInfo = info,
            // Q7: 3つ目の宛先。ブランチと作業ツリーもサーバーごとに違う。
            diff = diffController(scope),
            // Q8: 4つ目の宛先。ツリーと検索結果もサーバーごとに違う。
            files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), scope, describeError = { "e" }),
            // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
            pty = PtyController(FakePtyGateway(), scope, describeError = { "e" }),
            onFailure = { throw AssertionError("$it") },
        )
        assertEquals(1, catalog.state.value.models.size)
        assertEquals("1.18.21", info.state.value.version)
    }

    /** 未設定(null)への遷移でも捨てる。接続先が消えたのに前のサーバーの値を出さない。 */
    @Test
    fun `接続先がnullになってもキャッシュを捨てる`() = runTest {
        val scope = TestScope(testScheduler)
        val catalog = ModelCatalogController(FakeCatalog(), scope, describeError = { "e" })
        val info = ServerInfoController(FakeServerInfo(), scope, describeError = { "e" })
        catalog.onConnectionChanged("http://a:4097")
        info.onConnectionChanged("http://a:4097")
        catalog.ensureLoaded()
        info.ensureLoaded()
        scope.runCurrent()

        flowOf<String?>(null).wireConnectionChangesTo(
            catalog = catalog,
            serverInfo = info,
            // Q7: 3つ目の宛先。ブランチと作業ツリーもサーバーごとに違う。
            diff = diffController(scope),
            // Q8: 4つ目の宛先。ツリーと検索結果もサーバーごとに違う。
            files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), scope, describeError = { "e" }),
            // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
            pty = PtyController(FakePtyGateway(), scope, describeError = { "e" }),
            onFailure = { throw AssertionError("$it") },
        )
        assertFalse(catalog.state.value.loaded)
        assertNull(info.state.value.version)
    }

    // ---------------------------------------------------------------------
    // R7 / R2: 画面遷移の副作用
    // ---------------------------------------------------------------------

    private class PagingGateway(override val isConfigured: Boolean = true) : SessionsGateway {
        val listCalls = mutableListOf<Int?>()
        var supply: (Int?) -> List<SessionDto> = { limit ->
            (1..(limit ?: 0)).map {
                SessionDto(id = "ses_$it", title = "t$it", time = SessionTimeDto(1000L, 1000L))
            }
        }
        override suspend fun listSessions(limit: Int?, search: String?): ApiResult<List<SessionDto>> {
            listCalls += limit
            return ApiResult.Ok(supply(limit))
        }
        override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> = ApiResult.Ok(emptyMap())
        override suspend fun createSession(title: String, agent: String?, model: ModelRefDto?) =
            ApiResult.Ok(SessionDto(id = "new", title = title, time = SessionTimeDto(1, 1)))
        override suspend fun renameSession(sessionId: String, title: String) =
            ApiResult.Ok(SessionDto(id = sessionId, title = title, time = SessionTimeDto(1, 1)))
        override suspend fun deleteSession(sessionId: String): ApiResult<Unit> = ApiResult.Ok(Unit)
    }

    /**
     * **R7 の検出器**: 画面の再表示は `limit` を1ページ目へ戻さない。
     *
     * `onSessionListShown` を `refresh()` にする変異は、ここで `listCalls` の末尾が
     * `SESSION_PAGE_SIZE` に戻ることで見える。1周目はこの主張がどこにも無かった。
     */
    @Test
    fun `一覧画面の再表示はページングを1ページ目へ戻さない`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = PagingGateway()
        val sessions = SessionListController(gw, scope, describeError = { "e" })

        onSessionListShown(sessions)
        scope.runCurrent()
        assertEquals(listOf<Int?>(SESSION_PAGE_SIZE), gw.listCalls)

        // 末尾まで開く(limit を倍に伸ばす)。
        sessions.loadMore()
        scope.runCurrent()
        assertEquals(SESSION_PAGE_SIZE * 2, sessions.state.value.limit)

        // 回転・チャットからの復帰・ドロワーからの移動 = 何度表示しても撃たない。
        repeat(3) { onSessionListShown(sessions) }
        scope.runCurrent()
        assertEquals(listOf<Int?>(SESSION_PAGE_SIZE, SESSION_PAGE_SIZE * 2), gw.listCalls)
        assertEquals(SESSION_PAGE_SIZE * 2, sessions.state.value.limit)
    }

    /**
     * **R2 の検出器**: 設定から一覧へ戻るときだけ取り直す。
     * ドロワーの「セッション一覧」(一覧→一覧)では撃たない。
     */
    @Test
    fun `設定から一覧へ移るときだけ取り直す`() = runTest {
        val scope = TestScope(testScheduler)
        val gw = PagingGateway()
        val sessions = SessionListController(gw, scope, describeError = { "e" })
        onSessionListShown(sessions)
        scope.runCurrent()
        sessions.loadMore()
        scope.runCurrent()
        val callsBefore = gw.listCalls.size

        assertFalse(applyEnterSessionsEffect(Screen.Sessions, sessions))
        assertFalse(applyEnterSessionsEffect(Screen.Detail("ses_1"), sessions))
        assertFalse(applyEnterSessionsEffect(null, sessions))
        scope.runCurrent()
        assertEquals(callsBefore, gw.listCalls.size)
        assertEquals(SESSION_PAGE_SIZE * 2, sessions.state.value.limit)

        assertTrue(applyEnterSessionsEffect(Screen.Settings, sessions))
        scope.runCurrent()
        assertEquals(callsBefore + 1, gw.listCalls.size)
        // 設定から出るときは**1ページ目へ戻す**のが正しい(接続先が変わったかもしれない)。
        assertEquals(SESSION_PAGE_SIZE, gw.listCalls.last())
        assertEquals(SESSION_PAGE_SIZE, sessions.state.value.limit)
    }

    // ---------------------------------------------------------------------
    // minor-2: バージョン文言はドロワーと設定で同じ
    // ---------------------------------------------------------------------

    @Test
    fun `取得できなかったことを取得中と言わない`() {
        val failed = ServerInfoUi(loading = false, error = "接続失敗: boom")
        assertEquals("取得できません", serverVersionLabel(failed))
        assertEquals("バージョン: 取得できません", drawerServerLine(failed))
    }

    @Test
    fun `バージョン文言は4状態を区別する`() {
        assertEquals("未取得", serverVersionLabel(ServerInfoUi()))
        assertEquals("取得中…", serverVersionLabel(ServerInfoUi(loading = true)))
        assertEquals("取得できません", serverVersionLabel(ServerInfoUi(error = "e")))
        assertEquals("1.18.21", serverVersionLabel(ServerInfoUi(version = "1.18.21")))
        assertEquals(4, listOf(ServerInfoUi(), ServerInfoUi(loading = true), ServerInfoUi(error = "e"), ServerInfoUi(version = "v"))
            .map { serverVersionLabel(it) }.toSet().size)
    }

    @Test
    fun `serveの冠はバージョンが取れているときだけ`() {
        assertEquals("serve 1.18.21", drawerServerLine(ServerInfoUi(version = "1.18.21")))
        assertEquals("バージョン: 取得中…", drawerServerLine(ServerInfoUi(loading = true)))
        assertNotNull(drawerServerLine(ServerInfoUi()))
    }
}
