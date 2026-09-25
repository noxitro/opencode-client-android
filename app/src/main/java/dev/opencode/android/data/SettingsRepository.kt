package dev.opencode.android.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * アプリのDataStoreは**1ファイル**([DataStorePreferencesStore] も同じものを使う)。
 * 分けると起動時に待ち合わせる点が増え、テーマのちらつき対策が2重になる。
 *
 * ## corruptionHandler が要る理由(Q5 レビュー minor-1、故障注入で実測)
 *
 * `.preferences_pb` が protobuf として読めない内容になると、DataStore は
 * `CorruptionException` を投げる。Q5 でテーマのちらつきを消すために
 * `MainActivity` が起動時に最初の値を待つようになった結果、**この例外が
 * `performLaunchActivity` を落とし、アプリデータの消去以外に回復手段が無くなる**。
 * レビューが43バイトの非protobufで上書きして再現している。
 *
 * 壊れていたら**空の設定として作り直す**。失うのは接続先とローカル設定だけで、
 * どちらも画面から入れ直せる —— 起動できないアプリより桁違いにましである。
 */
internal val Context.settingsDataStore by preferencesDataStore(
    name = "opencode_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 永続化の口。[ConnectionRepository] はこれにだけ依存するので、
 * ユニットテストは Android の DataStore 抜きで差し替えられる。
 */
interface SettingsStore {
    val saved: Flow<SettingsRepository.Saved>
    suspend fun save(baseUrl: String, password: String)
}

/**
 * 接続設定(サーバーURL + Basic認証パスワード)の永続化。
 * パスワードをログ・テスト・コミットに書かないこと(AGENTS.md)。
 */
class SettingsRepository(context: Context) : SettingsStore {

    data class Saved(
        val baseUrl: String,
        val password: String,
    ) {
        /** URLが保存済み(設定済み)か。空パスワードは認証不要サーバーがあり得るため許容する。 */
        val isConfigured: Boolean
            get() = baseUrl.isNotBlank()
    }

    private val appContext = context.applicationContext

    override val saved: Flow<Saved> = appContext.settingsDataStore.data.map { prefs ->
        Saved(
            baseUrl = prefs[KEY_BASE_URL].orEmpty(),
            password = prefs[KEY_PASSWORD].orEmpty(),
        )
    }

    override suspend fun save(baseUrl: String, password: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl
            prefs[KEY_PASSWORD] = password
        }
    }

    companion object {
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_PASSWORD = stringPreferencesKey("password")
    }
}
