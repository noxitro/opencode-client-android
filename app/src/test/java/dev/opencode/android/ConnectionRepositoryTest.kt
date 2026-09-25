package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ChatRepository
import dev.opencode.android.data.Connection
import dev.opencode.android.data.ConnectionRepository
import dev.opencode.android.data.SessionsRepository
import dev.opencode.android.data.SettingsRepository
import dev.opencode.android.data.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q0 スコープ3: **資格情報をリポジトリ側が保持する**ことの固定テスト。
 *
 * 差し戻しの理由になった前の実装は「呼び出しごとに `OpenCodeApi(baseUrl, password)` を
 * new して転送するだけ」だった。したがってここで検証するのは文言ではなく
 * **同一性**(同じ接続先なら同じクライアント実体)と、**引数に資格情報が無いこと**である
 * (後者はコンパイルが通ること自体が検証: `listSessions()` は引数を取らない)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRepositoryTest {

    private class FakeStore(initial: SettingsRepository.Saved? = null) : SettingsStore {
        val state = MutableStateFlow(initial ?: SettingsRepository.Saved("", ""))
        var writes = 0
        override val saved: Flow<SettingsRepository.Saved> = state
        override suspend fun save(baseUrl: String, password: String) {
            writes++
            state.value = SettingsRepository.Saved(baseUrl, password)
        }
    }

    /**
     * backgroundScope: runTest が完了を待たないスコープ。ConnectionRepository は
     * 設定Flowを collect し続けるので、テスト本体のスコープに載せると runTest が終わらない。
     */
    private fun TestScope.repo(store: FakeStore) = ConnectionRepository(store, backgroundScope)

    @Test
    fun `未設定ならNotConfiguredを返しクライアントを作らない`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val connection = repo(store)
        assertFalse(connection.isConfigured)
        assertNull(connection.api())

        val sessions = SessionsRepository(connection)
        val result = sessions.listSessions()
        assertEquals(ApiResult.Err(ApiError.NotConfigured), result)
    }

    @Test
    fun `同じ接続先ならクライアント実体を作り直さない`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(SettingsRepository.Saved("http://10.0.2.2:4098", "stub-pass"))
        val connection = repo(store)
        val first = connection.api()
        val second = connection.api()
        assertTrue(first != null)
        // 呼び出しごとにnewしていればここで別実体になる(差し戻しの再発検出)
        assertSame(first, second)
    }

    @Test
    fun `接続先が変わったらクライアントを差し替える`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(SettingsRepository.Saved("http://10.0.2.2:4098", "stub-pass"))
        val connection = repo(store)
        val before = connection.api()
        connection.save("http://10.0.2.2:4097", "other-pass")
        val after = connection.api()
        assertNotSame(before, after)
        assertEquals(Connection("http://10.0.2.2:4097", "other-pass"), connection.connection.value)
    }

    @Test
    fun `saveはDataStore書き込み前にメモリ上の接続先を切り替える`() = runTest(UnconfinedTestDispatcher()) {
        // 保存直後に health を叩く経路が、古い接続先を使わないこと。
        val store = FakeStore()
        val connection = repo(store)
        assertFalse(connection.isConfigured)
        connection.save("http://10.0.2.2:4098", "stub-pass")
        assertTrue(connection.isConfigured)
        assertEquals("http://10.0.2.2:4098", connection.connection.value?.baseUrl)
        assertEquals(1, store.writes)
    }

    @Test
    fun `空URLは未設定として扱う`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(SettingsRepository.Saved("http://10.0.2.2:4098", "stub-pass"))
        val connection = repo(store)
        assertTrue(connection.isConfigured)
        store.state.value = SettingsRepository.Saved("", "")
        assertFalse(connection.isConfigured)
        assertNull(connection.connection.value)
        val chat = ChatRepository(connection)
        assertEquals(ApiResult.Err(ApiError.NotConfigured), chat.listMessages("ses_x"))
    }

    @Test
    fun `savedは読み込み前をnullで表す`() = runTest(UnconfinedTestDispatcher()) {
        // DataStoreのFlowが最初の値を出すまで null(画面の初期遷移がこれに依存)。
        val store = object : SettingsStore {
            // 一度も値を出さないFlow(=DataStore初回読み込み中)
            override val saved: Flow<SettingsRepository.Saved> = emptyFlow()
            override suspend fun save(baseUrl: String, password: String) = Unit
        }
        val connection = ConnectionRepository(store, backgroundScope)
        assertNull(connection.saved.value)
        assertFalse(connection.isConfigured)
    }
}
