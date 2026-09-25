package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.ui.ChatMessage
import dev.opencode.android.ui.ChatUi
import dev.opencode.android.ui.ModelCatalogUi
import dev.opencode.android.ui.ScreenBody
import dev.opencode.android.ui.SessionsUi
import dev.opencode.android.ui.chatBody
import dev.opencode.android.ui.modelCatalogEmptyStateOf
import dev.opencode.android.ui.sessionListBody
import dev.opencode.android.ui.EmptyStateAction
import dev.opencode.android.ui.EmptyStateSpec
import dev.opencode.android.ui.EmptyStateTone
import dev.opencode.android.ui.chatEmptyState
import dev.opencode.android.ui.isAuthError
import dev.opencode.android.ui.loadFailureState
import dev.opencode.android.ui.modelCatalogEmptyState
import dev.opencode.android.ui.modelCatalogFootnotes
import dev.opencode.android.ui.sessionListEmptyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q6 スコープ2: **空状態/エラー状態の文言と導線の統一**。
 *
 * 検査するのは見た目ではなく**規則**である:
 *  - 認証失敗では「再試行」を出さない(押しても直らない)
 *  - 接続失敗ではどの画面でも「設定を開く」が出る(行き止まりを作らない)
 *  - 同じ意味の状態は画面をまたいで**同じ key**(証跡の突き合わせが効くように)
 */
class Q6EmptyStateTest {

    // ---- 述語の校正 ----

    @Test
    fun `isAuthError は401と403だけを真にする`() {
        assertTrue(isAuthError(ApiError.Http(401)))
        assertTrue(isAuthError(ApiError.Http(403)))
        assertFalse(isAuthError(ApiError.Http(402)))
        assertFalse(isAuthError(ApiError.Http(404)))
        assertFalse(isAuthError(ApiError.Http(500)))
        assertFalse(isAuthError(ApiError.Network("boom")))
        assertFalse(isAuthError(ApiError.NotConfigured))
        assertFalse(isAuthError(null))
    }

    // ---- 統一の規則 ----

    @Test
    fun `認証失敗では再試行を出さない`() {
        val specs = listOf(
            sessionListEmptyState(0, "HTTP 401", authFailure = true, search = ""),
            chatEmptyState(0, "HTTP 401", authFailure = true, sessionDeleted = false),
            modelCatalogEmptyState(0, 0, "HTTP 401", authFailure = true, query = ""),
        )
        specs.forEach { spec ->
            assertNotNull(spec)
            val actions = spec!!.actions.map { it.action }
            assertFalse("${spec.key} に再試行が出ている", EmptyStateAction.RETRY in actions)
            assertTrue("${spec.key} に設定への導線が無い", EmptyStateAction.OPEN_SETTINGS in actions)
            assertEquals(EmptyStateTone.ERROR, spec.tone)
        }
    }

    @Test
    fun `認証以外の取得失敗ではどの画面でも再試行と設定の両方を出す`() {
        val specs = listOf(
            sessionListEmptyState(0, "NET boom", authFailure = false, search = ""),
            chatEmptyState(0, "NET boom", authFailure = false, sessionDeleted = false),
            modelCatalogEmptyState(0, 0, "NET boom", authFailure = false, query = ""),
        )
        specs.forEach { spec ->
            assertNotNull(spec)
            val actions = spec!!.actions.map { it.action }
            assertEquals(listOf(EmptyStateAction.RETRY, EmptyStateAction.OPEN_SETTINGS), actions)
        }
    }

    /**
     * **同じ意味の状態は同じラベル**。1つの導線が画面ごとに違う言葉になっていたのが
     * Q6 開始時点の状態で(「接続設定を開く」/ 導線なし)、それを一本化した。
     */
    @Test
    fun `導線のラベルは画面をまたいで同じ`() {
        val labels = listOf(
            sessionListEmptyState(0, "e", false, ""),
            chatEmptyState(0, "e", false, false),
            modelCatalogEmptyState(0, 0, "e", false, ""),
        ).flatMap { it!!.actions }
            .groupBy({ it.action }, { it.label })
        labels.forEach { (action, ls) ->
            assertEquals("$action のラベルが画面ごとに違う: $ls", 1, ls.toSet().size)
        }
        assertEquals("設定を開く", labels[EmptyStateAction.OPEN_SETTINGS]!!.first())
        assertEquals("再試行", labels[EmptyStateAction.RETRY]!!.first())
    }

    /** 失敗のときも**サーバーが言ったこと**を捨てない(P5 の欠陥Gの再発防止)。 */
    @Test
    fun `失敗状態は原因の一行を本文に残す`() {
        val message = "[Stealth] stealth/ox-alpha is temporarily rate-limited upstream."
        listOf(
            loadFailureState("sessions", message, authFailure = false),
            loadFailureState("sessions", message, authFailure = true),
        ).forEach { spec ->
            assertTrue("原因が消えている: ${spec.body}", spec.body!!.contains(message))
        }
    }

    // ---- 一覧 ----

    @Test
    fun `一覧は1件でもあれば空状態を出さない`() {
        assertNull(sessionListEmptyState(1, null, false, ""))
        // **持っているものを消して失敗だけを出さない。** 部分的に取れているなら一覧が勝つ。
        assertNull(sessionListEmptyState(3, "NET boom", false, ""))
    }

    @Test
    fun `一覧の0件は検索中かどうかで別の状態`() {
        val plain = sessionListEmptyState(0, null, false, "")!!
        val searched = sessionListEmptyState(0, null, false, "abc")!!
        assertEquals("sessions-empty", plain.key)
        assertEquals("sessions-search-empty", searched.key)
        assertTrue(searched.title.contains("abc"))
        assertEquals(listOf(EmptyStateAction.CREATE_SESSION), plain.actions.map { it.action })
        assertEquals(listOf(EmptyStateAction.CLEAR_SEARCH), searched.actions.map { it.action })
    }

    /** 失敗は検索より強い(検索中に落ちたら「一致しません」ではなく「読み込めません」)。 */
    @Test
    fun `検索中に取得が失敗したら失敗を出す`() {
        assertEquals("sessions-failed", sessionListEmptyState(0, "NET boom", false, "abc")!!.key)
    }

    // ---- チャット ----

    /**
     * Q6 が足した空状態。Q5 までは**画面が真っ白**で、
     * 「まだ何も送っていない」と「黙って失敗した」が区別できなかった。
     */
    @Test
    fun `メッセージ0件に文言が出る`() {
        val spec = chatEmptyState(0, null, false, sessionDeleted = false)!!
        assertEquals("messages-empty", spec.key)
        assertTrue(spec.body!!.isNotBlank())
        // 何も押させない: この状態でできることは入力欄に打つことだけである。
        assertTrue(spec.actions.isEmpty())
    }

    @Test
    fun `削除済みセッションでは送信を促さない`() {
        val spec = chatEmptyState(0, null, false, sessionDeleted = true)!!
        assertEquals("messages-session-deleted", spec.key)
        assertEquals(listOf(EmptyStateAction.BACK_TO_LIST), spec.actions.map { it.action })
    }

    @Test
    fun `メッセージがあれば空状態を出さない`() {
        assertNull(chatEmptyState(1, null, false, false))
        assertNull(chatEmptyState(1, "NET boom", false, true))
    }

    // ---- モデルシート ----

    @Test
    fun `カタログは0件と検索0件を区別する`() {
        val none = modelCatalogEmptyState(0, 0, null, false, "")!!
        val noMatch = modelCatalogEmptyState(0, 464, null, false, "voxtral")!!
        assertEquals("catalog-empty", none.key)
        assertEquals("catalog-search-empty", noMatch.key)
        assertTrue(noMatch.title.contains("voxtral"))
        assertNull(modelCatalogEmptyState(3, 464, null, false, "gpt"))
    }

    /**
     * 申し送り **Q4-2**: capabilities が揃っていても用途が音声寄りのモデル(Voxtral 等)を
     * 選んで静かに失敗する経路。**除外が0件でも限界の一文を出す** ——
     * 除外件数に紐付けると、除外0件のサーバーでだけ限界の説明が消える。
     */
    @Test
    fun `モデル一覧の脚注は除外0件でも限界を書く`() {
        val zero = modelCatalogFootnotes(0)
        assertEquals(1, zero.size)
        assertEquals("capability-limit", zero[0].key)

        val some = modelCatalogFootnotes(136)
        assertEquals(2, some.size)
        // Q4 の E2E 証跡と同じ文字列を保つ(`model-picker-excluded:136`)。
        assertEquals("excluded:136", some[0].key)
        assertTrue(some[0].text.contains("136"))
        assertEquals("capability-limit", some[1].key)
    }

    // ---- key の一意性(証跡の突き合わせが効くこと) ----

    @Test
    fun `空状態の key は重複しない`() {
        val all: List<EmptyStateSpec> = listOfNotNull(
            sessionListEmptyState(0, null, false, ""),
            sessionListEmptyState(0, null, false, "q"),
            sessionListEmptyState(0, "e", false, ""),
            sessionListEmptyState(0, "e", true, ""),
            chatEmptyState(0, null, false, false),
            chatEmptyState(0, null, false, true),
            chatEmptyState(0, "e", false, false),
            chatEmptyState(0, "e", true, false),
            modelCatalogEmptyState(0, 0, null, false, ""),
            modelCatalogEmptyState(0, 9, null, false, "q"),
            modelCatalogEmptyState(0, 0, "e", false, ""),
            modelCatalogEmptyState(0, 0, "e", true, ""),
        )
        assertEquals(12, all.size)
        assertEquals("key が重複している: ${all.map { it.key }}", 12, all.map { it.key }.toSet().size)
    }
    // ---- 状態オブジェクトから本文領域を決める(Q6 レビュー blocker)----
    //
    // 画面が `ui.errorIsAuth` などを取り出す行を書き換える変異が496件全緑で通り抜けたので、
    // **取り出しごと純関数に入れた**。以下はその取り出しの検出器である。

    @Test
    fun `一覧の本文領域は状態から決まる`() {
        assertEquals(ScreenBody.Loading, sessionListBody(SessionsUi(loading = true)))
        // 1件でも持っていれば、取得中でも一覧が勝つ(持っているものを消して待たせない)。
        assertEquals(
            ScreenBody.Content,
            sessionListBody(SessionsUi(loading = true, items = listOf(session("s1")))),
        )
        assertEquals(ScreenBody.Content, sessionListBody(SessionsUi(items = listOf(session("s1")))))

        val empty = sessionListBody(SessionsUi()) as ScreenBody.Empty
        assertEquals("sessions-empty", empty.spec.key)

        val searched = sessionListBody(SessionsUi(search = "abc")) as ScreenBody.Empty
        assertEquals("sessions-search-empty", searched.spec.key)

        // **`errorIsAuth` が実際に効くこと。** ここが `false` 固定にされると
        // 401 で「再試行」が復活する(レビューが打った変異そのもの)。
        val failed = sessionListBody(SessionsUi(error = "HTTP 401", errorIsAuth = false)) as ScreenBody.Empty
        assertEquals("sessions-failed", failed.spec.key)
        val auth = sessionListBody(SessionsUi(error = "HTTP 401", errorIsAuth = true)) as ScreenBody.Empty
        assertEquals("sessions-unauthorized", auth.spec.key)
        assertFalse(EmptyStateAction.RETRY in auth.spec.actions.map { it.action })
    }

    @Test
    fun `チャットの本文領域は状態から決まる`() {
        assertEquals(ScreenBody.Loading, chatBody(ChatUi(loading = true)))
        // **`messageCount` を 1 に固定する変異**が Q5 の白画面を戻した。
        // 0件なら必ず空状態、1件以上なら必ず本体。
        val empty = chatBody(ChatUi()) as ScreenBody.Empty
        assertEquals("messages-empty", empty.spec.key)
        assertEquals(ScreenBody.Content, chatBody(ChatUi(messages = listOf(ChatMessage(messageId = "m1", role = "user", parts = emptyList())))))

        val deleted = chatBody(ChatUi(sessionDeleted = true)) as ScreenBody.Empty
        assertEquals("messages-session-deleted", deleted.spec.key)

        val failed = chatBody(ChatUi(error = "HTTP 401", errorIsAuth = false)) as ScreenBody.Empty
        assertEquals("messages-failed", failed.spec.key)
        val auth = chatBody(ChatUi(error = "HTTP 401", errorIsAuth = true)) as ScreenBody.Empty
        assertEquals("messages-unauthorized", auth.spec.key)
        assertFalse(EmptyStateAction.RETRY in auth.spec.actions.map { it.action })
    }

    @Test
    fun `カタログの空状態は状態から決まる`() {
        // 取得中は「選べるモデルがありません」と言わない。
        assertNull(modelCatalogEmptyStateOf(ModelCatalogUi(loading = true)))
        assertEquals("catalog-empty", modelCatalogEmptyStateOf(ModelCatalogUi())!!.key)
        val auth = modelCatalogEmptyStateOf(ModelCatalogUi(error = "HTTP 401", errorIsAuth = true))!!
        assertEquals("catalog-unauthorized", auth.key)
        assertFalse(EmptyStateAction.RETRY in auth.actions.map { it.action })
        val plain = modelCatalogEmptyStateOf(ModelCatalogUi(error = "NET boom", errorIsAuth = false))!!
        assertEquals("catalog-failed", plain.key)
    }

    private fun session(id: String) = SessionDto(id = id, title = id, time = SessionTimeDto(1, 1))
}
