package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.data.SessionsGateway
import dev.opencode.android.ui.SessionListController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 申し送り **Q4-1: `create` の二重POSTガードが効いていない**(RUN_PLAN)。
 *
 * 欠陥の形: `if (_state.value.creating) return` が `scope.launch` の**外**、
 * `creating = true` が**中**にあった。`launch` の本体は次のディスパッチまで走らないので、
 * **同一フレームの2連打は両方ともガードを素通りし、POST が2件飛ぶ**。
 *
 * この箇所は P1/P2 のレビューが「VM側ガードで修正済み・ランタイム連打テストは➖」と
 * 記録した場所である(TEST_REPORT 所見1-3)。**当時も連打を測っていなかった**ため、
 * ガードは形だけのまま Q1→Q2→Q3→Q4 の4段を生き延びた。
 *
 * ここで固定するのは**件数の同一性**であって「ガードがある」という説明ではない
 * (QUALITY_PLAN §4.2「テストは振る舞いの説明の再述ではなく数値と同一性を検証する」)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q5DoubleTapTest {

    /** POST の到達回数を数えるだけのゲートウェイ。**応答は保留できる**(in-flight を作るため)。 */
    private class CountingGateway(override val isConfigured: Boolean = true) : SessionsGateway {
        val creates = mutableListOf<Triple<String, String?, ModelRefDto?>>()
        val renames = mutableListOf<Pair<String, String>>()
        var listCalls = 0

        override suspend fun listSessions(limit: Int?, search: String?): ApiResult<List<SessionDto>> {
            listCalls++
            return ApiResult.Ok(emptyList())
        }

        override suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>> =
            ApiResult.Ok(emptyMap())

        override suspend fun createSession(
            title: String,
            agent: String?,
            model: ModelRefDto?,
        ): ApiResult<SessionDto> {
            creates += Triple(title, agent, model)
            return ApiResult.Ok(
                SessionDto(
                    id = "ses_${creates.size}",
                    title = title,
                    time = SessionTimeDto(created = 1000, updated = 1000),
                ),
            )
        }

        override suspend fun renameSession(sessionId: String, title: String): ApiResult<SessionDto> {
            renames += sessionId to title
            return ApiResult.Ok(
                SessionDto(id = sessionId, title = title, time = SessionTimeDto(1000, 1000)),
            )
        }

        override suspend fun deleteSession(sessionId: String): ApiResult<Unit> = ApiResult.Ok(Unit)
    }

    private fun controller(gw: SessionsGateway, scope: TestScope) =
        SessionListController(gw, scope, describeError = { "err" })

    /**
     * **同一フレームの2連打が1件しか送らないこと。**
     *
     * `runCurrent()` を挟まずに2回呼ぶ = コルーチンが一度も走らないうちに2発届いた状態で、
     * これが `adb shell input tap` では作れなかった条件そのものである(Q4 の E2E 報告)。
     */
    @Test
    fun `同一フレームの2連打でPOSTは1件だけ`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        c.create("t")
        c.create("t")
        scope.runCurrent()

        assertEquals(1, gw.creates.size)
    }

    /** 3連打・5連打でも増えない(「2回目だけ弾く」実装になっていないこと)。 */
    @Test
    fun `同一フレームの5連打でもPOSTは1件だけ`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        repeat(5) { c.create("t") }
        scope.runCurrent()

        assertEquals(1, gw.creates.size)
        assertEquals(listOf("t"), gw.creates.map { it.first })
    }

    /**
     * ガードは**同期的に立つ**。`launch` の本体が走る前の時点で `creating` が true であること。
     * これが false だと、2発目は何を見ても通ってしまう。
     */
    @Test
    fun `createはlaunchの本体を待たずにcreatingを立てる`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        c.create("t")
        // まだ1つもコルーチンを走らせていない。
        assertTrue(c.state.value.creating)
        assertEquals(0, gw.creates.size)

        scope.runCurrent()
        assertEquals(1, gw.creates.size)
    }

    /** 1件目が完了した**後**の押下は通る(ガードが永久にロックしない)。 */
    @Test
    fun `完了後の再押下は通る`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        c.create("a")
        scope.runCurrent()
        c.create("b")
        scope.runCurrent()

        assertEquals(listOf("a", "b"), gw.creates.map { it.first })
    }

    /** 失敗しても解放されること(エラーで永久に押せなくならない)。 */
    @Test
    fun `作成に失敗してもcreatingは解放される`() = runTest {
        val gw = object : SessionsGateway by CountingGateway() {
            var calls = 0
            override suspend fun createSession(
                title: String,
                agent: String?,
                model: ModelRefDto?,
            ): ApiResult<SessionDto> {
                calls++
                return ApiResult.Err(ApiError.Network("boom"))
            }
        }
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        c.create("a")
        c.create("a")
        scope.runCurrent()
        assertEquals(1, gw.calls)
        assertEquals(false, c.state.value.creating)

        c.create("a")
        scope.runCurrent()
        assertEquals(2, gw.calls)
    }

    // ---- rename も同じ形の欠陥を持っていた ----

    @Test
    fun `同一フレームの2連打で改名PATCHは1件だけ`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        c.rename("ses_x", "new")
        c.rename("ses_x", "new")
        scope.runCurrent()

        assertEquals(1, gw.renames.size)
    }

    /**
     * **拒否される要求は状態に1バイトも書かない**(Q5 レビュー major-1)。
     *
     * 1周目の修正は `getAndUpdate { it.copy(pendingActionId = sessionId, ...) }` で、
     * **拒否経路でも無条件に書いていた**。A の改名が飛んでいる最中に B を改名しようとすると、
     * B の PATCH は正しく抑止される一方で `pendingActionId` が B になり、
     * **A の行が黙って pending 表示をやめ、送られてもいない B の行がスピナーを出す**。
     * A の応答が返ると B の幽霊スピナーが無関係な瞬間に消え、その間 `delete` は
     * 存在しない要求に塞がれる。**並行性の欠陥を直すために状態破壊の欠陥を入れていた。**
     *
     * 1周目のテスト2本が見逃したのは、**どちらも同じ `sessionId` を使っていた**から。
     * 同一IDだと上書きが no-op になる。**異なるIDで試すこと。**
     */
    @Test
    fun `拒否された改名は実行中の要求の占有者を奪わない`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        c.rename("ses_A", "a")
        assertEquals("ses_A", c.state.value.pendingActionId)

        // B は抑止される。**そして A の占有を奪わない。**
        c.rename("ses_B", "b")
        assertEquals("ses_A", c.state.value.pendingActionId)

        scope.runCurrent()
        assertEquals(listOf("ses_A" to "a"), gw.renames)
        assertEquals(null, c.state.value.pendingActionId)
    }

    /** 拒否経路は `actionError` も消さない(直前の失敗表示を黙って消さないこと)。 */
    @Test
    fun `拒否された改名は表示中のエラーを消さない`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        c.rename("ses_A", "a")
        val snapshot = c.state.value
        c.rename("ses_B", "b")
        // 状態は**まるごと**変わっていないこと。
        assertSame(snapshot, c.state.value)
        scope.runCurrent()
    }

    /** `create` も対称に直す(今日は観測できないが、同じ形を残さない)。 */
    @Test
    fun `拒否された作成は状態に何も書かない`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        c.create("a")
        val snapshot = c.state.value
        c.create("b")
        assertSame(snapshot, c.state.value)
        scope.runCurrent()
        assertEquals(listOf("a"), gw.creates.map { it.first })
    }

    /** 別セッションの削除も、実行中の改名の占有を奪わない(既存の `delete` の健全性の固定)。 */
    @Test
    fun `実行中の改名がある間の削除は抑止され占有者も変わらない`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)
        c.onEvent(
            dev.opencode.android.data.SseEvent.SessionInfoChanged(
                kind = dev.opencode.android.data.SseEvent.SessionInfoChanged.Kind.CREATED,
                sessionID = "ses_B",
                info = SessionDto(id = "ses_B", title = "B", time = SessionTimeDto(1000, 1000)),
            ),
        )
        c.rename("ses_A", "a")
        assertEquals("ses_A", c.state.value.pendingActionId)
        c.delete("ses_B")
        assertEquals("ses_A", c.state.value.pendingActionId)
        scope.runCurrent()
    }

    @Test
    fun `改名は完了後にもう一度送れる`() = runTest {
        val gw = CountingGateway()
        val scope = TestScope(testScheduler)
        val c = controller(gw, scope)

        c.rename("ses_x", "a")
        scope.runCurrent()
        c.rename("ses_x", "b")
        scope.runCurrent()

        assertEquals(listOf("ses_x" to "a", "ses_x" to "b"), gw.renames)
    }
}
