package dev.opencode.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 色トークン(QUALITY_PLAN §5 Q0 スコープ1)。
 *
 * 既定はダーク。参照UI(§2.1「ほぼ黒の背景 #0f0f0f 系、カードはわずかに明るい表面色」)に合わせ、
 * **Material の動的色を使わず固定色**にする。動的色は端末の壁紙で変わるため、
 * 「モバイル版Claudeアプリと同じ見え方」を保証できない。
 *
 * ライトは**トークンだけ**用意する(切替UIはQ5)。既定の [lightColorScheme] を素で使うと
 * 紫系のMaterialベースラインになり、ダークと同じアクセントを共有しないので、
 * ダーク側と対になる値をここで明示する。
 */

// アクセント(ダーク/ライト共通の色相。Claudeアプリのテラコッタ系)
private val AccentDark = Color(0xFFD97757)
// Q6 レビュー major-3: `#B4471F` は `surfaceContainerHigh`(#E8E5DD)の上で **4.32:1** だった。
// 質問カードの header(`ChatCards.kt`)がまさにその組み合わせで描かれている。
// 色相は保ったまま暗くして 4.89:1 にする。
private val AccentLight = Color(0xFFA84018)

// ダーク階調: 背景がほぼ黒、面が上がるほど明るくなる
private val Ink0 = Color(0xFF0F0F0F) // background / surface
private val Ink1 = Color(0xFF171717) // surfaceContainerLow
private val Ink2 = Color(0xFF1A1A1A) // surfaceVariant / surfaceContainer
private val Ink3 = Color(0xFF212121) // surfaceContainerHigh
private val Ink4 = Color(0xFF2A2A2A) // surfaceContainerHighest
// Q6: 輪郭は「装飾」ではなく**部品の境界**である(OutlinedTextField の枠)。
// WCAG 1.4.11 は非テキストの境界に 3:1 を求めるが、旧値 `#3A3A3A` は `#0F0F0F` の上で
// **1.65:1** しか無かった(Q6 で機械計算して発見)。面の上でも 3:1 を満たす値へ上げる。
private val InkOutline = Color(0xFF6E6E6E)
private val InkOn = Color(0xFFECECEC)
private val InkOnMuted = Color(0xFFA6A6A6)

// ライト階調: 生成りの紙色。ダークの対
private val Paper0 = Color(0xFFFAF9F5)
private val Paper1 = Color(0xFFFFFFFF)
private val Paper2 = Color(0xFFF0EEE8)
private val Paper3 = Color(0xFFE8E5DD)
private val Paper4 = Color(0xFFDFDBD1)
// 同上。旧値 `#C8C3B8` は `#FAF9F5` の上で **1.69:1**。
private val PaperOutline = Color(0xFF7E7A72)
private val PaperOn = Color(0xFF1A1A18)
private val PaperOnMuted = Color(0xFF5C5A54)

val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF2B1005),
    primaryContainer = Color(0xFF3A2117),
    onPrimaryContainer = Color(0xFFFFDCCF),
    secondary = Color(0xFF8FA3B8),
    onSecondary = Color(0xFF10202E),
    secondaryContainer = Color(0xFF1E2A36),
    onSecondaryContainer = Color(0xFFD3E1EF),
    tertiary = Color(0xFFB9A2D6),
    onTertiary = Color(0xFF23133A),
    background = Ink0,
    onBackground = InkOn,
    surface = Ink0,
    onSurface = InkOn,
    surfaceVariant = Ink2,
    onSurfaceVariant = InkOnMuted,
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Ink1,
    surfaceContainer = Ink2,
    surfaceContainerHigh = Ink3,
    surfaceContainerHighest = Ink4,
    outline = InkOutline,
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFF2846A),
    onError = Color(0xFF3A0B02),
    errorContainer = Color(0xFF4A1C12),
    onErrorContainer = Color(0xFFFFDAD2),
    scrim = Color(0xCC000000),
)

val LightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCCF),
    onPrimaryContainer = Color(0xFF3A1400),
    secondary = Color(0xFF3E5A73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E1EF),
    onSecondaryContainer = Color(0xFF10202E),
    tertiary = Color(0xFF5B3F80),
    onTertiary = Color(0xFFFFFFFF),
    background = Paper0,
    onBackground = PaperOn,
    surface = Paper0,
    onSurface = PaperOn,
    surfaceVariant = Paper2,
    onSurfaceVariant = PaperOnMuted,
    surfaceContainerLowest = Paper1,
    surfaceContainerLow = Paper1,
    surfaceContainer = Paper2,
    surfaceContainerHigh = Paper3,
    surfaceContainerHighest = Paper4,
    outline = PaperOutline,
    outlineVariant = Color(0xFFDCD8CE),
    error = Color(0xFFA33017),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD2),
    onErrorContainer = Color(0xFF410900),
    scrim = Color(0x99000000),
)

/**
 * 状態を表す色(Q6)。`ColorScheme` に枠が無いので別に持つ。
 *
 * **なぜテーマごとに分けるのか**: Q5 まで、実行中バッジ `#4CAF50` / 再試行バッジ `#FFA726` /
 * ツール完了 `#6FBF73` は**ライトとダークで同じ値**だった。Q6 で機械計算したところ、
 * `#4CAF50` はライトの面(`#F0EEE8`)の上で **2.45:1** しか無い —— ダークでは 6.1:1 で
 * 通っていたので、**ライト設定にした人にだけ読めない文字**になっていた。
 * 「1つの色で両方を満たす」値は存在しない(明るい面と暗い面の両方から3段以上離れられない)。
 */
@Immutable
data class StatusColors(
    /** 実行中バッジ / ツール完了。 */
    val running: Color,
    /** 再試行バッジ。 */
    val retry: Color,

    // ---- Q7: 差分の行(§5b Q7 スコープ1「追加=緑背景、削除=赤背景、文脈=既定色」)----

    /**
     * 追加行の**面**。本文は `onSurface` を載せるので、この色は
     * 「`onSurface` と 4.5:1 以上離れていること」が要件になる
     * ([themeContrastPairs] が機械検査する)。
     */
    val diffAddSurface: Color,

    /** 削除行の面。 */
    val diffDeleteSurface: Color,

    /**
     * 追加行の記号(`+`)と行番号の色。**面の上でも 4.5:1** を満たすこと ——
     * 記号だけが読めない差分は、色分けの意味を失う。
     */
    val diffAdd: Color,

    /** 削除行の記号(`-`)の色。 */
    val diffDelete: Color,
)

val DarkStatusColors = StatusColors(
    running = Color(0xFF6FBF73),
    retry = Color(0xFFFFB74D),
    diffAddSurface = Color(0xFF14301A),
    diffDeleteSurface = Color(0xFF3A1414),
    diffAdd = Color(0xFF7EE787),
    diffDelete = Color(0xFFFF7B72),
)

val LightStatusColors = StatusColors(
    running = Color(0xFF1B5E20),
    retry = Color(0xFF8A4B00),
    diffAddSurface = Color(0xFFE6FFEC),
    diffDeleteSurface = Color(0xFFFFEBE9),
    diffAdd = Color(0xFF1B5E20),
    diffDelete = Color(0xFFA33017),
)

/**
 * ダーク/ライトのどちらの [StatusColors] を使うか。**純関数**にしてあるのは、
 * 選択そのものにテストを置けるようにするため([isDarkTheme] と同じ理由)。
 */
fun statusColorsFor(darkTheme: Boolean): StatusColors =
    if (darkTheme) DarkStatusColors else LightStatusColors

/**
 * 画面から状態色を引く口。**既定はダーク**([OpenCodeTheme] の既定と揃える)。
 */
val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }

/**
 * 既定はダーク。[darkTheme] は Q5 の設定画面から渡す想定で、現時点では切替UIを持たない。
 * 動的色(`dynamicColorScheme`)は使わない — 上の設計注記を参照。
 */
@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
    ) {
        // 状態色も**同じ判定**で切り替える。ここに `if (darkTheme)` を書き直さないこと ——
        // 条件が2か所になると片方だけ直し忘れた状態が「画面は正常に見える」まま残る。
        CompositionLocalProvider(
            LocalStatusColors provides statusColorsFor(darkTheme),
            content = content,
        )
    }
}
