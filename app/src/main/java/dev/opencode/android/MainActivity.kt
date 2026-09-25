package dev.opencode.android

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.data.AppContainer
import dev.opencode.android.ui.AppRoot
import dev.opencode.android.ui.LocalHapticGate
import dev.opencode.android.ui.isDarkTheme
import dev.opencode.android.ui.rememberHapticGate
import dev.opencode.android.ui.theme.OpenCodeTheme
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = AppContainer.get(application)

        // **最初の1フレームから正しい配色で描く**(§5 Q5 のゲート:
        // 「DataStore の読み込み前に一瞬既定テーマで描いてちらつく経路が無いか」)。
        //
        // Compose の中で `collectAsState(initial = null)` を使うと、初回合成は必ず
        // 「まだ分からない」状態で走る。そこで既定色を敷けばライト設定の端末は起動のたびに
        // 黒い画面を1フレーム見るし、敷かずに空を描けば白飛びが1フレーム入る。
        // **どちらもちらつきである。** DataStore の初回読み込みは数ミリ秒の小さなファイル読みなので、
        // ここで1回だけ待って、以降は StateFlow の同期値として使う。
        // (この待ちは**プロセスに1回**。以後 AppContainer がプロセス生存中1つなので再発しない)
        val initialPreferences = runBlocking { container.preferences.awaitLoaded() }

        // ウィンドウ背景も**先に**合わせる。`themes.xml` は `windowBackground` を黒で固定しており、
        // Compose が最初のフレームを描くまでその黒が見える —— ライトを選んだ端末では
        // それが「一瞬暗い」の正体になる。設定値はもう手元にあるので、setContent の前に差し替える。
        // (コールドスタートの**起動ウィンドウ**は onCreate より前にシステムが描くので、
        //  そこだけは黒のまま。実測値と併せて報告に書いた)
        val initialDark = isDarkTheme(
            initialPreferences.colorMode,
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES,
        )
        window.setBackgroundDrawable(
            ColorDrawable(if (initialDark) WINDOW_BACKGROUND_DARK else WINDOW_BACKGROUND_LIGHT),
        )

        setContent {
            val preferences by container.preferences.current.collectAsStateWithLifecycle()
            val current = preferences ?: initialPreferences
            // LocalConfiguration にするのは、端末のダークモード切替で再合成が走るため
            // (`resources.configuration` を直に読むと Compose は変化を知らない)。
            val systemInDarkMode =
                (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            // **判定は1か所**([isDarkTheme])。テーマとシステムバーで別々に書かない。
            val darkTheme = isDarkTheme(current.colorMode, systemInDarkMode)

            // 引数なしの enableEdgeToEdge() は SystemBarStyle.auto(...) を使い、
            // **端末の system dark mode** でシステムバーのアイコン色を決める。
            // このアプリの配色はユーザー設定で決まるので、`auto` に任せると
            // 「アプリはライトなのにバーのアイコンは白」のような食い違いが出る。
            // Q0 は「常時ダーク」を理由にダーク固定にしていたが、Q5 で切替が入ったので
            // **実際に描いている配色に合わせて動かす**。
            LaunchedEffect(darkTheme) {
                if (darkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    )
                }
            }

            // 実行中の切替でも背景を追随させる(Compose の下に見える面)。
            LaunchedEffect(darkTheme) {
                window.setBackgroundDrawable(
                    ColorDrawable(if (darkTheme) WINDOW_BACKGROUND_DARK else WINDOW_BACKGROUND_LIGHT),
                )
            }

            OpenCodeTheme(darkTheme = darkTheme) {
                // 触覚は**1つのゲート**を全画面で共有する(Haptics.kt: 条件は1か所)。
                CompositionLocalProvider(
                    LocalHapticGate provides rememberHapticGate(current.hapticsEnabled),
                ) {
                    // P1(接続設定+health)/P2(セッション一覧)。画面切替はAppRoot内のstateで行う。
                    AppRoot()
                }
            }
        }
    }
}

/** `Theme.kt` の `Ink0` / `Paper0` と同じ値。ウィンドウ背景をテーマの背景色に合わせる。 */
private const val WINDOW_BACKGROUND_DARK = 0xFF0F0F0F.toInt()
private const val WINDOW_BACKGROUND_LIGHT = 0xFFFAF9F5.toInt()
