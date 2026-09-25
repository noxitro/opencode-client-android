package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.AppPreferences
import dev.opencode.android.data.ColorMode
import dev.opencode.android.data.HealthDto
import dev.opencode.android.data.PreferencesRepository
import dev.opencode.android.data.PreferencesStore
import dev.opencode.android.data.ServerInfoGateway
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.ui.DRAWER_RECENT_COUNT
import dev.opencode.android.ui.HapticEvent
import dev.opencode.android.ui.HapticGate
import dev.opencode.android.ui.ServerInfoController
import dev.opencode.android.ui.colorModeLabel
import dev.opencode.android.ui.connectionHostLabel
import dev.opencode.android.ui.drawerBackEnabled
import dev.opencode.android.ui.screenBackEnabled
import dev.opencode.android.ui.isDarkTheme
import dev.opencode.android.ui.recentSessions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q5(ドロワー+設定画面)の**状態遷移と判定**に検出器を置く。
 *
 * 純関数のテストだけでゲートを閉じない(RUN_PLAN「検出器の穴という欠陥形」)。
 * ここで固定するのは、どれも**欠けても画面が正常に見える**ものである:
 *  - カラーモードの判定(1か所に集めた `isDarkTheme`)
 *  - 触覚のゲート(**OFF のとき呼ばれないこと**が§5 Q5 のゲート)
 *  - 保存値の復元(未知の値をどう扱うか)
 *  - 接続先変更の**2つの宛先への配布**(1つ落としても画面は正常に見える)
 *  - サーバー素性の取得(接続先が変わったら捨てる)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Q5SettingsTest {

    // ---------- カラーモードの判定 ----------

    @Test
    fun `DARKとLIGHTは端末のダークモードに関係なく固定される`() {
        assertTrue(isDarkTheme(ColorMode.DARK, systemInDarkMode = false))
        assertTrue(isDarkTheme(ColorMode.DARK, systemInDarkMode = true))
        assertFalse(isDarkTheme(ColorMode.LIGHT, systemInDarkMode = false))
        assertFalse(isDarkTheme(ColorMode.LIGHT, systemInDarkMode = true))
    }

    @Test
    fun `SYSTEMだけが端末のダークモードに従う`() {
        assertTrue(isDarkTheme(ColorMode.SYSTEM, systemInDarkMode = true))
        assertFalse(isDarkTheme(ColorMode.SYSTEM, systemInDarkMode = false))
    }

    @Test
    fun `カラーモードのラベルは3つとも別の文字列`() {
        val labels = ColorMode.entries.map { colorModeLabel(it) }
        assertEquals(listOf("システム", "ダーク", "ライト"), labels)
        assertEquals(3, labels.toSet().size)
    }

    // ---------- 保存値の復元 ----------

    /**
     * **既定はダーク。** Q0 が決めた既定を Q5 が変えないことを固定する。
     * 未保存(null)と、将来 enum から消えた値・壊れた値は同じ扱いにする。
     */
    @Test
    fun `未保存と未知の保存値はダークに落ちる`() {
        assertEquals(ColorMode.DARK, ColorMode.fromStored(null))
        assertEquals(ColorMode.DARK, ColorMode.fromStored(""))
        assertEquals(ColorMode.DARK, ColorMode.fromStored("SEPIA"))
        assertEquals(ColorMode.DARK, ColorMode.fromStored("dark"))
    }

    @Test
    fun `保存された名前はそのまま復元される`() {
        ColorMode.entries.forEach { mode ->
            assertEquals(mode, ColorMode.fromStored(mode.name))
        }
    }

    // ---------- 永続化(プロセス再起動をまたぐ経路)----------

    private class FakePrefsStore(initial: AppPreferences = AppPreferences()) : PreferencesStore {
        val flow = MutableStateFlow(initial)
        override val preferences: Flow<AppPreferences> = flow
        var writes = 0
        override suspend fun setColorMode(mode: ColorMode) {
            writes++
            flow.value = flow.value.copy(colorMode = mode)
        }
        override suspend fun setHapticsEnabled(enabled: Boolean) {
            writes++
            flow.value = flow.value.copy(hapticsEnabled = enabled)
        }
    }

    /**
     * **書いた値がストアに残る**こと。プロセス再起動後に維持されるかは
     * 「新しいリポジトリが同じストアから同じ値を読むか」で表せる(実機でも別途測る)。
     */
    @Test
    fun `カラーモードはストアへ書かれ 新しいリポジトリが同じ値を読む`() = runTest {
        val store = FakePrefsStore()
        val scope = TestScope(testScheduler)
        val repo = PreferencesRepository(store, scope)
        scope.runCurrent()

        repo.setColorMode(ColorMode.LIGHT)
        repo.setHapticsEnabled(false)
        scope.runCurrent()

        assertEquals(2, store.writes)

        // プロセス再起動に相当: 同じストアから作り直した別インスタンス。
        val reborn = PreferencesRepository(store, TestScope(testScheduler))
        val loaded = reborn.awaitLoaded()
        assertEquals(ColorMode.LIGHT, loaded.colorMode)
        assertFalse(loaded.hapticsEnabled)
    }

    /** 押した瞬間に色が変わること = DataStore の Flow が一周する前にメモリ状態が動く。 */
    @Test
    fun `setColorModeはストアのFlowを待たずに現在値を更新する`() = runTest {
        // 書いても Flow を更新しないストア(= DataStore がまだ一周していない状態)。
        val store = object : PreferencesStore {
            override val preferences: Flow<AppPreferences> = flowOf(AppPreferences())
            override suspend fun setColorMode(mode: ColorMode) = Unit
            override suspend fun setHapticsEnabled(enabled: Boolean) = Unit
        }
        val scope = TestScope(testScheduler)
        val repo = PreferencesRepository(store, scope)
        scope.runCurrent()

        repo.setColorMode(ColorMode.LIGHT)
        assertEquals(ColorMode.LIGHT, repo.current.value?.colorMode)
    }

    @Test
    fun `awaitLoadedは最初の値を返す`() = runTest {
        val store = FakePrefsStore(AppPreferences(colorMode = ColorMode.SYSTEM, hapticsEnabled = false))
        val repo = PreferencesRepository(store, TestScope(testScheduler))
        val loaded = repo.awaitLoaded()
        assertEquals(ColorMode.SYSTEM, loaded.colorMode)
        assertFalse(loaded.hapticsEnabled)
    }

    // ---------- 触覚のゲート(§5 Q5 のゲート「OFF のとき実際に呼ばれないこと」) ----------

    @Test
    fun `触覚OFFのときsinkは一度も呼ばれない`() {
        val fired = mutableListOf<HapticEvent>()
        val gate = HapticGate(enabled = { false }, sink = { fired += it })
        HapticEvent.entries.forEach { gate.perform(it) }
        assertEquals(emptyList<HapticEvent>(), fired)
    }

    @Test
    fun `触覚ONのときsinkは呼ばれた種類をそのまま受け取る`() {
        val fired = mutableListOf<HapticEvent>()
        val gate = HapticGate(enabled = { true }, sink = { fired += it })
        gate.perform(HapticEvent.SEND)
        gate.perform(HapticEvent.PERMISSION_REPLY)
        assertEquals(listOf(HapticEvent.SEND, HapticEvent.PERMISSION_REPLY), fired)
    }

    /**
     * トグルを切り替えた**直後**の操作が古い値で判定されないこと。
     * `enabled` を値ではなく関数で受けているのがこの性質の本体である。
     */
    @Test
    fun `トグルを切り替えると次の操作から反映される`() {
        var on = true
        val fired = mutableListOf<HapticEvent>()
        val gate = HapticGate(enabled = { on }, sink = { fired += it })
        gate.perform(HapticEvent.SEND)
        on = false
        gate.perform(HapticEvent.SEND)
        on = true
        gate.perform(HapticEvent.ABORT)
        assertEquals(listOf(HapticEvent.SEND, HapticEvent.ABORT), fired)
    }

    /** observer は**鳴らす鳴らさないに関わらず**呼ばれる(実機の logcat 証跡がこれ)。 */
    @Test
    fun `observerはOFFのときも判定結果を受け取る`() {
        val seen = mutableListOf<Pair<HapticEvent, Boolean>>()
        val fired = mutableListOf<HapticEvent>()
        var on = false
        val gate = HapticGate(enabled = { on }, sink = { fired += it }, observer = { e, v -> seen += e to v })
        gate.perform(HapticEvent.SEND)
        on = true
        gate.perform(HapticEvent.SEND)
        assertEquals(listOf(HapticEvent.SEND to false, HapticEvent.SEND to true), seen)
        assertEquals(listOf(HapticEvent.SEND), fired)
    }

    // ---------- BACK キー(Q5 E2E 所見B) ----------

    /**
     * **ドロワーが開いている間の BACK は「閉じる」だけ。**
     *
     * 直す欠陥(実機で2回再現): `ModalNavigationDrawer` は BACK を自前で拾わないので、
     * 一覧画面でドロワーを開いたまま BACK を押すと**アプリが終了してランチャーへ落ちた**。
     *
     * ここで固定できるのは**判定の排他性**だけである。実際に `BackHandler` が
     * その判定を使って登録されているかは Compose 層で、`runTest` からは到達できない ——
     * **そこは実機測定が唯一の検出器**(報告に明記)。
     */
    @Test
    fun `ドロワーが開いている間だけドロワー側がBACKを受ける`() {
        assertTrue(drawerBackEnabled(drawerOpen = true))
        assertFalse(drawerBackEnabled(drawerOpen = false))
    }

    /**
     * **2つのハンドラが同時に有効にならないこと。**
     *
     * 同時に有効だと、どちらが先に呼ばれるかが Compose の登録順に依存し、
     * 設定画面でドロワーを開いて BACK を押すと「ドロワーが閉じずに一覧へ飛ぶ」。
     * 登録順に頼らないための不変条件なので、**4通り全部**を主張する。
     */
    @Test
    fun `ドロワー側と画面側のBACKは排他`() {
        for (canGoBack in listOf(true, false)) {
            for (drawerOpen in listOf(true, false)) {
                val both = drawerBackEnabled(drawerOpen) && screenBackEnabled(canGoBack, drawerOpen)
                assertFalse("canGoBack=$canGoBack drawerOpen=$drawerOpen で両方が有効", both)
            }
        }
    }

    /** ドロワーが閉じているときの戻る導線は**従来どおり**(設定→一覧を壊さない)。 */
    @Test
    fun `ドロワーが閉じていれば画面側のBACKは従来どおり`() {
        assertTrue(screenBackEnabled(canGoBack = true, drawerOpen = false))
        // 未設定の初回起動は戻り先が無いので受けない(P1 からの挙動)。
        assertFalse(screenBackEnabled(canGoBack = false, drawerOpen = false))
        assertFalse(screenBackEnabled(canGoBack = true, drawerOpen = true))
    }

    // ---------- ドロワー ----------

    private fun session(id: String, title: String) =
        SessionDto(id = id, title = title, time = SessionTimeDto(created = 1, updated = 1))

    @Test
    fun `最近の項目は上位N件で順序を変えない`() {
        val items = (1..12).map { session("ses_$it", "t$it") }
        val recent = recentSessions(items)
        assertEquals(DRAWER_RECENT_COUNT, recent.size)
        assertEquals(items.take(DRAWER_RECENT_COUNT).map { it.id }, recent.map { it.id })
    }

    @Test
    fun `最近の項目は件数がNより少なくても落ちない`() {
        assertEquals(emptyList<SessionDto>(), recentSessions(emptyList()))
        assertEquals(2, recentSessions(listOf(session("a", "A"), session("b", "B"))).size)
    }

    @Test
    fun `ホスト名はスキームとパスを落として host_port にする`() {
        assertEquals("10.0.2.2:4098", connectionHostLabel("http://10.0.2.2:4098"))
        assertEquals("100.64.0.1:4097", connectionHostLabel("http://100.64.0.1:4097"))
        assertEquals("example.com", connectionHostLabel("https://example.com"))
        assertEquals("example.com:4097", connectionHostLabel("http://example.com:4097/api"))
        assertNull(connectionHostLabel(null))
        assertNull(connectionHostLabel(""))
        assertNull(connectionHostLabel("   "))
    }

    // 接続先変更の配布は `Q5WiringTest` へ移した(Q5 レビュー major-2)。
    // ラムダ引数を取る配布関数は**呼び出し側が `{ }` を渡す変異を見られなかった**ので、
    // 宛先を controller の実体で受け取る `wireConnectionChangesTo` に置き換えてある。

    // ---------- サーバー素性(ServerInfoController) ----------

    private class FakeServerInfo(override val isConfigured: Boolean = true) : ServerInfoGateway {
        var result: ApiResult<HealthDto> = ApiResult.Ok(HealthDto(healthy = true, version = "1.18.21"))
        var calls = 0
        override suspend fun health(): ApiResult<HealthDto> {
            calls++
            return result
        }
    }

    private fun controller(gateway: ServerInfoGateway, scope: TestScope) =
        ServerInfoController(gateway, scope, describeError = { "err:$it" })

    @Test
    fun `ensureLoadedは一度成功したら二度引かない`() = runTest {
        val gw = FakeServerInfo()
        val c = controller(gw, TestScope(testScheduler))
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, gw.calls)
        assertEquals("1.18.21", c.state.value.version)
        assertEquals(true, c.state.value.healthy)

        c.ensureLoaded()
        runCurrent()
        assertEquals(1, gw.calls)
    }

    /** **失敗は loaded にしない。** 一度失敗したきり永久に空を出すのが一番わかりにくい。 */
    @Test
    fun `失敗したら次のensureLoadedで引き直す`() = runTest {
        val gw = FakeServerInfo()
        gw.result = ApiResult.Err(ApiError.Network("boom"))
        val c = controller(gw, TestScope(testScheduler))
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, gw.calls)
        assertNull(c.state.value.version)
        assertFalse(c.state.value.loaded)

        gw.result = ApiResult.Ok(HealthDto(healthy = true, version = "1.18.21"))
        c.ensureLoaded()
        runCurrent()
        assertEquals(2, gw.calls)
        assertEquals("1.18.21", c.state.value.version)
    }

    /** 取れなかったサーバーのバージョンを**残さない**(取れているのと区別が付かなくなる)。 */
    @Test
    fun `取得に失敗したら古いバージョンを残さない`() = runTest {
        val gw = FakeServerInfo()
        val c = controller(gw, TestScope(testScheduler))
        c.ensureLoaded()
        runCurrent()
        assertEquals("1.18.21", c.state.value.version)

        gw.result = ApiResult.Err(ApiError.Http(500))
        c.refresh()
        runCurrent()
        assertNull(c.state.value.version)
        assertNull(c.state.value.healthy)
        assertEquals("err:${ApiError.Http(500)}", c.state.value.error)
    }

    /**
     * **接続先が変わったら捨てる**(Q4 レビュー major-1 と同じ形)。
     * 捨てないと、サーバーAのバージョンがサーバーBのヘッダーに出続ける。
     */
    @Test
    fun `接続先が変わるとバージョンを捨てる`() = runTest {
        val gw = FakeServerInfo()
        val c = controller(gw, TestScope(testScheduler))
        c.onConnectionChanged("http://a:4097")
        c.ensureLoaded()
        runCurrent()
        assertEquals("1.18.21", c.state.value.version)

        c.onConnectionChanged("http://b:4097")
        assertNull(c.state.value.version)

        c.ensureLoaded()
        runCurrent()
        assertEquals(2, gw.calls)
    }

    @Test
    fun `同じ接続先への再保存では捨てない`() = runTest {
        val gw = FakeServerInfo()
        val c = controller(gw, TestScope(testScheduler))
        c.onConnectionChanged("http://a:4097")
        c.ensureLoaded()
        runCurrent()
        assertEquals("1.18.21", c.state.value.version)

        c.onConnectionChanged("http://a:4097")
        assertEquals("1.18.21", c.state.value.version)
        c.ensureLoaded()
        runCurrent()
        assertEquals(1, gw.calls)
    }

    @Test
    fun `未設定なら通信しない`() = runTest {
        val gw = FakeServerInfo(isConfigured = false)
        val c = controller(gw, TestScope(testScheduler))
        c.ensureLoaded()
        runCurrent()
        assertEquals(0, gw.calls)
        assertNull(c.state.value.version)
    }
}
