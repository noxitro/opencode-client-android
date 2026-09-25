package dev.opencode.android

import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.applySessionInfoToItems
import dev.opencode.android.ui.indexOfSession
import dev.opencode.android.ui.removeSessionOptimistically
import dev.opencode.android.ui.restoreSessionAfter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 一覧の**楽観更新とイベント適用**の固定テスト(QUALITY_PLAN §5 Q1 スコープ4・7)。
 *
 * ゲートは「削除は楽観更新+失敗時ロールバック」と定めている。**ロールバックが効くことを
 * 測れる形にする**のがここの目的なので、「元の位置へ戻ること」を位置の数値で押さえる
 * (末尾に戻しても件数だけは同じになってしまい、テストが緑のまま症状が残る)。
 */
class SessionListMutationTest {

    private fun s(id: String, title: String = "t-$id", updated: Long = 1_000L) =
        SessionDto(id = id, title = title, time = SessionTimeDto(created = updated, updated = updated))

    private val items = listOf(
        s("a", updated = 3000),
        s("b", updated = 2000),
        s("c", updated = 1000),
    )

    // ---- 楽観削除とロールバック ----

    @Test
    fun `楽観削除は該当だけを消し、順序を保つ`() {
        val after = removeSessionOptimistically(items, "b")
        assertEquals(listOf("a", "c"), after.map { it.id })
    }

    @Test
    fun `ロールバックは元の位置へ戻す(末尾ではない)`() {
        val index = indexOfSession(items, "b")
        assertEquals(1, index)
        val removed = items[index]
        val anchorId = items[index - 1].id
        val after = removeSessionOptimistically(items, "b")
        val rolledBack = restoreSessionAfter(after, removed, anchorId)
        assertEquals(listOf("a", "b", "c"), rolledBack.map { it.id })
        assertEquals(items, rolledBack)
        // 末尾に足す実装だと ["a","c","b"] になる。区別できることを明示する。
        assertNotEquals(listOf("a", "c", "b"), rolledBack.map { it.id })
    }

    @Test
    fun `先頭と末尾のロールバックも位置が保たれる`() {
        for (id in listOf("a", "c")) {
            val index = indexOfSession(items, id)
            val anchorId = items.getOrNull(index - 1)?.id
            val rolledBack = restoreSessionAfter(removeSessionOptimistically(items, id), items[index], anchorId)
            assertEquals(items.map { it.id }, rolledBack.map { it.id })
        }
    }

    @Test
    fun `既に戻っている場合は二重に足さない`() {
        val rolledBack = restoreSessionAfter(items, items[1], "a")
        assertEquals(3, rolledBack.size)
    }

    @Test
    fun `位置の基準はidなので、飛行中に前へ1件挿さってもずれない`() {
        // 削除発行時の列は [a,b,c]。b の1つ上は a。
        val afterRemoval = removeSessionOptimistically(items, "b")
        // 通信中に session.created が届いて先頭へ挿さった
        val withNew = listOf(s("new", updated = 9999)) + afterRemoval
        val rolledBack = restoreSessionAfter(withNew, items[1], "a")
        assertEquals(listOf("new", "a", "b", "c"), rolledBack.map { it.id })
    }

    @Test
    fun `基準の行自体が消えていたら updated 降順の正しい位置へ戻す`() {
        // 基準 a も消えた列
        val remaining = listOf(s("c", updated = 1000))
        val rolledBack = restoreSessionAfter(remaining, s("b", updated = 2000), "a")
        assertEquals(listOf("b", "c"), rolledBack.map { it.id })
    }

    @Test
    fun `一覧に無いセッションの位置は -1`() {
        assertEquals(-1, indexOfSession(items, "zzz"))
    }

    // ---- session.updated によるタイトル自動更新 ----

    @Test
    fun `session-updated は既存項目のタイトルを差し替える`() {
        val event = parseSseEnvelope(
            """{"id":"evt_1","type":"session.updated","properties":{"sessionID":"b",
               "info":{"id":"b","slug":"x","projectID":"p","directory":"/d","title":"自動タイトル",
                       "version":"1.18.21","time":{"created":2000,"updated":2000}}}}""",
        ) as SseEvent.SessionInfoChanged

        val after = applySessionInfoToItems(items, event.kind, event.sessionID, event.info, allowInsert = true)
        assertEquals(listOf("a", "b", "c"), after.map { it.id })
        assertEquals("自動タイトル", after.first { it.id == "b" }.title)
    }

    @Test
    fun `session-created は先頭に入り updated 降順で整列される`() {
        val created = s("new", updated = 9999)
        val after = applySessionInfoToItems(
            items,
            SseEvent.SessionInfoChanged.Kind.CREATED,
            "new",
            created,
            allowInsert = true,
        )
        assertEquals(listOf("new", "a", "b", "c"), after.map { it.id })
    }

    @Test
    fun `検索中は session-created を差し込まない`() {
        val after = applySessionInfoToItems(
            items,
            SseEvent.SessionInfoChanged.Kind.CREATED,
            "new",
            s("new", updated = 9999),
            allowInsert = false,
        )
        assertEquals(listOf("a", "b", "c"), after.map { it.id })
    }

    @Test
    fun `session-deleted は一覧から消す(info が無くても)`() {
        val after = applySessionInfoToItems(
            items,
            SseEvent.SessionInfoChanged.Kind.DELETED,
            "b",
            info = null,
            allowInsert = true,
        )
        assertEquals(listOf("a", "c"), after.map { it.id })
    }

    @Test
    fun `sessionID も info も無ければ一覧を触らない`() {
        val after = applySessionInfoToItems(
            items,
            SseEvent.SessionInfoChanged.Kind.UPDATED,
            sessionId = null,
            info = null,
            allowInsert = true,
        )
        assertEquals(items, after)
    }

    @Test
    fun `更新で time-updated が新しくなれば順序も追従する`() {
        val bumped = s("c", title = "上がってきた", updated = 5000)
        val after = applySessionInfoToItems(
            items,
            SseEvent.SessionInfoChanged.Kind.UPDATED,
            "c",
            bumped,
            allowInsert = false,
        )
        assertEquals(listOf("c", "a", "b"), after.map { it.id })
    }
}
