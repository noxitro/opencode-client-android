package dev.opencode.android.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * カラーモード(§5 Q5 スコープ2)。
 *
 * **保存値は enum 名の文字列**にする。序数で保存すると、将来 enum の並びを変えた瞬間に
 * 既存端末の保存値が別の意味になる(移行コードが要る)。
 */
enum class ColorMode {
    SYSTEM,
    DARK,
    LIGHT,
    ;

    companion object {
        /**
         * 保存値からの復元。**未知の値・null は [SYSTEM] ではなく [DARK] に落とす**。
         *
         * 既定がダークなのは Q0 の決定(QUALITY_PLAN §5 Q0 スコープ1「ダークテーマを既定にする」)
         * であり、Q5 はその既定を変えない —— ユーザーが一度も設定していない端末の見え方を
         * 「切替UIを足した」だけで変えてしまわないため。
         */
        fun fromStored(value: String?): ColorMode =
            entries.firstOrNull { it.name == value } ?: DARK
    }
}

/**
 * ローカル設定(接続先ではないもの)。**接続の資格情報はここに入れない**
 * ([ConnectionRepository] が唯一の持ち主。AGENTS.md)。
 */
data class AppPreferences(
    val colorMode: ColorMode = ColorMode.DARK,
    /** 触覚フィードバック。**既定は ON**(Android の標準的な既定に合わせる)。 */
    val hapticsEnabled: Boolean = true,
)

/** 永続化の口。ユニットテストは Android の DataStore 抜きで差し替えられる([SettingsStore] と同じ形)。 */
interface PreferencesStore {
    val preferences: Flow<AppPreferences>
    suspend fun setColorMode(mode: ColorMode)
    suspend fun setHapticsEnabled(enabled: Boolean)
}

/**
 * [PreferencesStore] の DataStore 実装。**接続設定と同じ DataStore ファイルを使う**
 * (`opencode_settings`)。別ファイルにすると読み込み完了の待ち合わせ点が2つになり、
 * 起動時のちらつき対策([awaitLoaded])が2重になる。
 */
class DataStorePreferencesStore(context: Context) : PreferencesStore {

    private val appContext = context.applicationContext

    override val preferences: Flow<AppPreferences> =
        appContext.settingsDataStore.data.map { prefs -> readPreferences(prefs) }

    override suspend fun setColorMode(mode: ColorMode) {
        appContext.settingsDataStore.edit { it[KEY_COLOR_MODE] = mode.name }
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[KEY_HAPTICS] = enabled }
    }

    companion object {
        internal val KEY_COLOR_MODE = stringPreferencesKey("color_mode")
        internal val KEY_HAPTICS = booleanPreferencesKey("haptics_enabled")

        /** 保存された Preferences を [AppPreferences] にする。未保存キーは既定へ。 */
        internal fun readPreferences(prefs: Preferences) = AppPreferences(
            colorMode = ColorMode.fromStored(prefs[KEY_COLOR_MODE]),
            hapticsEnabled = prefs[KEY_HAPTICS] ?: true,
        )
    }
}

/**
 * ローカル設定の保持。**プロセス生存中に1つ**([AppContainer] が持つ)。
 *
 * ## なぜ「読み込み前」を null で表すのか
 *
 * カラーモードは**最初の1フレームから正しくないといけない**。既定値で描いてから
 * DataStore の値で描き直すと、ライトを選んだ端末は起動のたびに黒い画面が一瞬見える
 * (QUALITY_PLAN §5 Q5 のゲート「ちらつく経路が無いか自分で確かめること」)。
 * [current] が null の間は「まだ分からない」であって「既定」ではない。
 * 呼び出し側([MainActivity])は [awaitLoaded] で最初の値を待ってから描き始める。
 */
class PreferencesRepository(
    private val store: PreferencesStore,
    scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<AppPreferences?>(null)

    /** null = DataStore 初回読み込み前。**既定値ではない**(上の注記)。 */
    val current: StateFlow<AppPreferences?> = _current.asStateFlow()

    init {
        scope.launch {
            store.preferences.collect { _current.value = it }
        }
    }

    /**
     * 最初の値が読めるまで待つ。**起動時に1回だけ**呼ぶ(それ以降は [current] を購読する)。
     *
     * `store.preferences.first()` を直接待つ: [current] を待つと、上の collect が
     * 走るスケジューリングに依存してデッドロックしうる。
     */
    suspend fun awaitLoaded(): AppPreferences =
        _current.value ?: store.preferences.first().also { _current.value = it }

    suspend fun setColorMode(mode: ColorMode) {
        // メモリ状態を先に更新する([ConnectionRepository.save] と同じ理由)。
        // 切替は「押した瞬間に色が変わる」ことが要件なので、DataStore の Flow が
        // 一周するのを待たない。
        _current.value = (_current.value ?: AppPreferences()).copy(colorMode = mode)
        store.setColorMode(mode)
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        _current.value = (_current.value ?: AppPreferences()).copy(hapticsEnabled = enabled)
        store.setHapticsEnabled(enabled)
    }
}
