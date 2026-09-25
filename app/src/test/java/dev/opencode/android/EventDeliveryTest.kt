package dev.opencode.android

import dev.opencode.android.ui.PtyController
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.EventDeliveryFailure
import dev.opencode.android.ui.collectGuarded
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.DiffGateway
import dev.opencode.android.data.SnapshotFileDiffDto
import dev.opencode.android.data.VcsFileDiffDto
import dev.opencode.android.data.VcsFileStatusDto
import dev.opencode.android.data.VcsInfoDto
import dev.opencode.android.ui.ChatController
import dev.opencode.android.ui.DiffController
import dev.opencode.android.ui.FileBrowserController
import dev.opencode.android.ui.SessionListController
import dev.opencode.android.ui.deliverConnectionsTo
import dev.opencode.android.ui.deliverTo
import dev.opencode.android.ui.plus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSEイベントの**配布**が1件の例外で恒久的に止まらないことの固定テスト。
 *
 * 素の `collect { apply(it) }` は apply が投げると collector ごと死に、SSE接続は生きたまま
 * (状態は CONNECTED のまま)イベントだけが二度と届かなくなる。
 * L3 の `jsonPrimitive` 欠陥と同じで、**テストは緑のまま症状だけが残る**形なので、
 * 「後続が届き続けること」と「失敗が状態に残ること」を数値で押さえる。
 *
 * フィクスチャは e2e-stub / spec と同じ envelope(`{id,type,properties}`)を
 * `parseSseEnvelope` に通して作る(生成した近似オブジェクトを使わない)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDeliveryTest {

    /**
     * Q7 で再接続の宛先が3つになった。ここは既存の2つ(一覧・チャット)を測る場所なので、
     * 差分側は素通しの実体を渡す。差分側の主張は [Q7WiringTest] が持つ。
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

    private fun diffController(scope: CoroutineScope) =
        DiffController(NoopDiffGateway(), scope, describeError = { "e" })

    private fun idleEvent(sessionId: String): SseEvent =
        parseSseEnvelope("""{"id":"evt_1","type":"session.idle","properties":{"sessionID":"$sessionId"}}""")!!

    private fun partEvent(text: String): SseEvent =
        parseSseEnvelope(
            """
            {"id":"evt_2","type":"message.part.updated","properties":{
              "sessionID":"ses_stub_0001",
              "part":{"id":"prt_a1","sessionID":"ses_stub_0001","messageID":"msg_a1",
                      "type":"text","text":"$text"},
              "time":{"start":1756000000000}}}
            """.trimIndent(),
        )!!

    private fun statusEvent(sessionId: String, type: String): SseEvent =
        parseSseEnvelope(
            """{"id":"evt_3","type":"session.status","properties":{"sessionID":"$sessionId","status":{"type":"$type"}}}""",
        )!!

    private fun sessionUpdatedEvent(sessionId: String): SseEvent =
        parseSseEnvelope(
            """{"id":"evt_4","type":"session.updated","properties":{"sessionID":"$sessionId",
               "info":{"id":"$sessionId","slug":"s","projectID":"p","directory":"/d","title":"t",
                       "version":"1.18.21","time":{"created":1,"updated":1}}}}""",
        )!!

    @Test
    fun `適用が例外を投げても後続イベントが届き続ける`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<SseEvent>(extraBufferCapacity = 16)
        val delivered = mutableListOf<SseEvent>()
        val failures = mutableListOf<Throwable>()

        val job = launch {
            flow.collectGuarded(
                onFailure = { failures += it },
                handle = { event ->
                    if (event is SseEvent.SessionIdle) error("適用中の想定外エラー")
                    delivered += event
                },
            )
        }

        flow.emit(partEvent("チャンク1"))
        flow.emit(idleEvent("ses_stub_0001")) // ここで例外
        flow.emit(partEvent("チャンク2"))
        flow.emit(partEvent("チャンク3"))

        assertTrue(job.isActive) // 購読が生きている = 恒久停止していない
        assertEquals(3, delivered.size) // 例外の1件を除く全部が届いた
        assertEquals(1, failures.size)
        job.cancel()
    }

    @Test
    fun `失敗回数は積み上がり直近の内容が残る`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<SseEvent>(extraBufferCapacity = 16)
        var state: EventDeliveryFailure? = null
        val job = launch {
            flow.collectGuarded(
                onFailure = { state = state.plus(it) },
                handle = { error("boom-${(it as? SseEvent.SessionIdle)?.sessionID}") },
            )
        }

        flow.emit(idleEvent("ses_a"))
        flow.emit(idleEvent("ses_b"))

        val captured = state
        assertNotNull(captured)
        requireNotNull(captured)
        assertEquals(2, captured.count)
        assertTrue(captured.lastMessage.contains("boom-ses_b"))
        job.cancel()
    }

    // ---- 配布先が2つある配線(Q1レビュー major-2 の変異が刺さった場所) ----

    @Test
    fun `全てのイベントが一覧とチャットの両方へ、順序どおりに届く`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<SseEvent>(extraBufferCapacity = 32)
        val toList = mutableListOf<String>()
        val toChat = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val job = launch {
            flow.deliverTo(
                onFailure = { failures += it },
                toSessionList = { toList += it::class.simpleName.orEmpty() },
                toChat = { toChat += it::class.simpleName.orEmpty() },
                // Q9: 3つ目の宛先。`pty.exited` は終了コードの唯一の出所。
                toPty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }

        // 一覧向け2件 + チャット向け2件。**どちらも両方の宛先に届かなければならない** ——
        // 「自分に関係あるか」は宛先側が判断するのであって、配線が間引いてはいけない。
        flow.emit(statusEvent("ses_a", "busy"))
        flow.emit(partEvent("チャンク1"))
        flow.emit(sessionUpdatedEvent("ses_a"))
        flow.emit(idleEvent("ses_a"))

        assertEquals(4, toList.size)
        assertEquals(4, toChat.size)
        assertEquals(toList, toChat)
        assertEquals(0, failures.size)
        job.cancel()
    }

    @Test
    fun `片方の宛先が投げても、もう片方と後続イベントは死なない`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<SseEvent>(extraBufferCapacity = 32)
        val toChat = mutableListOf<SseEvent>()
        val failures = mutableListOf<Throwable>()
        val job = launch {
            flow.deliverTo(
                onFailure = { failures += it },
                toSessionList = { error("一覧側が壊れた") },
                toChat = { toChat += it },
                // Q9: 3つ目の宛先。`pty.exited` は終了コードの唯一の出所。
                toPty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }

        flow.emit(statusEvent("ses_a", "busy"))
        flow.emit(partEvent("チャンク1"))

        assertTrue(job.isActive)
        assertEquals(2, failures.size)
        job.cancel()
    }

    @Test
    fun `CancellationExceptionは飲まずに購読を終わらせる`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<SseEvent>(extraBufferCapacity = 16)
        val failures = mutableListOf<Throwable>()
        val job = launch {
            flow.collectGuarded(
                onFailure = { failures += it },
                handle = { throw CancellationException("停止機構") },
            )
        }

        flow.emit(idleEvent("ses_a"))

        // 飲んでしまうと ViewModel 破棄後も購読が残る
        assertFalse(job.isActive)
        assertEquals(0, failures.size)
    }

    // ---- 再接続の配線(Q2レビュー W1: ViewModel に直書きした配線は素通しだった) ----

    /**
     * **Q8 で宛先がラムダから実体になった**(Q7 申し送り Q7-6)。
     *
     * それまで `toSessionList: () -> Unit` / `toChat: () -> Unit` だった2つは、
     * 呼び出し側が `{ }` を渡す変異が**この関数のテストからは見えなかった**。
     * 症状は「再接続しても一覧の実行状態バッジが二度と更新されない」で、
     * 画面はどこも壊れて見えない(RUN_PLAN 決定2 の違反がそのまま戻る)。
     *
     * 実体で受け取るようになったので、「配られた」は**ゲートウェイが撃たれたこと**で測る。
     */
    @Test
    fun `再接続は4つの宛先すべてへ配られる`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val sessionsGw = RecordingSessionsGateway()
        val filesGw = FakeFilesGateway()
        val vcsGw = FakeVcsGateway()
        val chat = ChatController(NoopChatGateway(), this, describeError = { "e" })
        val files = FileBrowserController(filesGw, vcsGw, this, describeError = { "e" })
        // ファイル側は**まだ何も読んでいなければ何もしない**ので、先に1回読ませる。
        files.openDirectory(".")
        val listedBefore = filesGw.listedPaths.size

        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = { throw AssertionError("配布が失敗した: $it") },
                sessions = SessionListController(sessionsGw, this, describeError = { "e" }),
                chat = chat,
                // **差分側も同じ実体で数える。** `NoopDiffGateway` を渡すと
                // `vcsInfoCalls` が常に0になり、assert が意味を失う。
                diff = DiffController(vcsGw, this, describeError = { "e" }),
                files = files,
                // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
                pty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }

        flow.emit(1)
        flow.emit(2)

        // 宛先を1つ落とす変異は、この4つの assert のどれかを落とす。
        assertEquals(2, sessionsGw.statusCalls)
        assertEquals(2, vcsGw.vcsInfoCalls)
        assertEquals(2, filesGw.listedPaths.size - listedBefore)
        job.cancel()
    }

    /**
     * **4宛先の順序**を戻す(レビュー minor-7)。
     *
     * 1周目は宛先をラムダから実体へ変えたときに、順序の assertion
     * (`listOf("list","chat","list","chat")`)を**回数の assertion に置き換えてしまった**。
     * `deliverConnectionsTo` は「宛先へ**順に**無条件で配る」ことがそもそもの存在理由で、
     * Q1 の変異は**順序を1行動かすだけで102件全緑のまま一覧が死んだ**。
     * 回数だけでは、その形の変異が戻ってくる。
     *
     * 各宛先の「再接続1回につき1度だけ呼ばれる口」に目印を置いて1本の列にする。
     */
    @Test
    fun `再接続は4宛先へ決まった順で配られる`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val order = mutableListOf<String>()
        val chat = ChatController(NoopChatGateway(order = order), this, describeError = { "e" })
        val vcsGw = FakeVcsGateway(order = order)
        val files = FileBrowserController(
            FakeFilesGateway(order = order), FakeVcsGateway(), this, describeError = { "e" },
        )
        // チャットは**セッションを開いている間だけ**取り直す。ファイル側は
        // **一度読んでいなければ何もしない**。どちらも先に条件を満たしてから測る。
        chat.openChat("ses_1")
        files.openDirectory(".")
        order.clear()

        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = { throw AssertionError("配布が失敗した: $it") },
                sessions = SessionListController(
                    RecordingSessionsGateway(order = order), this, describeError = { "e" },
                ),
                chat = chat,
                diff = DiffController(vcsGw, this, describeError = { "e" }),
                files = files,
                // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
                pty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }

        flow.emit(1)
        flow.emit(2)

        // 宛先を1つ落とす変異も、順序を入れ替える変異も、この1本で落ちる。
        assertEquals(
            listOf("list", "chat", "diff", "files", "list", "chat", "diff", "files"),
            order,
        )
        job.cancel()
    }

    /**
     * チャット側が配られたことは**状態で**見る。`onReconnected()` は
     * アクティブなセッションが無くても `eventDeliveryFailure` を消す。
     */
    @Test
    fun `再接続はチャットの配布失敗記録を消す`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val chat = ChatController(NoopChatGateway(), this, describeError = { "e" })
        chat.recordDeliveryFailure(IllegalStateException("配布が死にかけた"))
        assertNotNull(chat.state.value.eventDeliveryFailure)

        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = { throw AssertionError("$it") },
                sessions = SessionListController(RecordingSessionsGateway(), this, describeError = { "e" }),
                chat = chat,
                diff = diffController(this),
                files = FileBrowserController(FakeFilesGateway(), FakeVcsGateway(), this, describeError = { "e" }),
                // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
                pty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }
        flow.emit(1)
        assertNull(chat.state.value.eventDeliveryFailure)
        job.cancel()
    }

    @Test
    fun `世代0では配らない`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val sessionsGw = RecordingSessionsGateway()
        val vcsGw = FakeVcsGateway()
        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = {},
                sessions = SessionListController(sessionsGw, this, describeError = { "e" }),
                chat = ChatController(NoopChatGateway(), this, describeError = { "e" }),
                diff = DiffController(vcsGw, this, describeError = { "e" }),
                files = FileBrowserController(FakeFilesGateway(), vcsGw, this, describeError = { "e" }),
                // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
                pty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }

        // 0 は「まだ一度も繋がっていない」初期値。ここで取り直すと未設定のまま通信する。
        flow.emit(0)
        assertEquals(0, sessionsGw.statusCalls)
        assertEquals(0, vcsGw.vcsInfoCalls)
        job.cancel()
    }

    @Test
    fun `再接続の片方が投げてももう片方と後続が生きる`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val failures = mutableListOf<Throwable>()
        val vcsGw = FakeVcsGateway()
        val job = launch {
            flow.deliverConnectionsTo(
                onFailure = { failures += it },
                // `isConfigured` を読んだ瞬間に投げる(同期で配線の中に例外を起こす)。
                sessions = SessionListController(ThrowingSessionsGateway(), this, describeError = { "e" }),
                chat = ChatController(NoopChatGateway(), this, describeError = { "e" }),
                diff = DiffController(vcsGw, this, describeError = { "e" }),
                files = FileBrowserController(FakeFilesGateway(), vcsGw, this, describeError = { "e" }),
                // Q9: 5つ目の宛先。ターミナルの WebSocket も接続先ごとに閉じる。
                pty = PtyController(FakePtyGateway(), this, describeError = { "e" }),
            )
        }

        flow.emit(1)
        flow.emit(2)

        assertTrue(job.isActive)
        assertEquals(2, failures.size)
        // 一覧が先に投げるので後続の宛先は呼ばれないが、購読は死なない
        assertEquals(0, vcsGw.vcsInfoCalls)
        job.cancel()
    }
}
