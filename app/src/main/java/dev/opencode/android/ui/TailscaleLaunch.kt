package dev.opencode.android.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 「Tailscaleを開く」ボタンの**行き先の判定**(Q11 レビュー major-2)。
 *
 * 元の実装は3段フォールバックを Composable の `onClick` に直書きしており、
 * ユニットテストが1本も無かった —— RUN_PLAN が繰り返し記録している
 * 「UI配線層に条件を置くと誰も検出できない」形そのものである。
 * ここでは**どこへ飛ばすかの判定**だけを純関数に出し、
 * `Intent` の組み立てと `startActivity` は別に分けてある。
 *
 * 判定は `Intent` にも `Uri` にも触らない([TailscaleProbe] が boolean だけを返す)ので、
 * Robolectric 無しの素の JVM テストで3分岐すべてを固定できる。
 */

/** Tailscale の Android アプリのパッケージ名。 */
const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

/** Play ストアアプリ(`com.android.vending`)を直接指す URI。 */
const val TAILSCALE_MARKET_URI = "market://details?id=$TAILSCALE_PACKAGE"

/** ストアアプリが無い端末向けの web フォールバック。 */
const val TAILSCALE_WEB_URL = "https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE"

/** 行き先。**「どこへも飛べない」を null で表す**(「飛べた」と混同させない)。 */
enum class TailscaleDestination {
    /** Tailscale アプリ本体。 */
    APP,

    /** Play ストアアプリ(`market://`)。 */
    STORE_APP,

    /** ブラウザ(`https://play.google.com/...`)。 */
    STORE_WEB,
}

/**
 * 可視性の問い合わせだけを抽象化したもの。
 * 本番は [PackageManagerTailscaleProbe] が `PackageManager` を叩き、テストは fake を渡す。
 */
interface TailscaleProbe {
    /** `pm.getLaunchIntentForPackage(TAILSCALE_PACKAGE) != null` か。 */
    fun isAppLaunchable(): Boolean

    /** `market://` を解決できるアクティビティがあるか。 */
    fun isMarketResolvable(): Boolean

    /** `https://play.google.com/...` を解決できるアクティビティがあるか。 */
    fun isWebResolvable(): Boolean
}

/**
 * 3段フォールバックの判定。**上から順に、解決できた最初のものを採る。**
 *
 * どれも解決できなければ `null` —— 呼び出し側は `startActivity` せず、
 * ユーザーに「開けなかった」と伝える(レビュー minor-8。無ガードの
 * `startActivity(web)` は `ActivityNotFoundException` で落ちうる)。
 */
fun selectTailscaleDestination(probe: TailscaleProbe): TailscaleDestination? = when {
    probe.isAppLaunchable() -> TailscaleDestination.APP
    probe.isMarketResolvable() -> TailscaleDestination.STORE_APP
    probe.isWebResolvable() -> TailscaleDestination.STORE_WEB
    else -> null
}

/**
 * `PackageManager` 実装。
 *
 * **`market://` の解決には `com.android.vending` への可視性が要る**(レビュー major-3)。
 * `AndroidManifest.xml` の `<queries>` に `com.tailscale.ipn` しか無かったため、
 * API 30+ では `resolveActivity` が常に null を返し2段目が死んでいた。
 * manifest 側に `com.android.vending` のパッケージ宣言を足してある。
 */
class PackageManagerTailscaleProbe(private val pm: PackageManager) : TailscaleProbe {
    override fun isAppLaunchable(): Boolean = pm.getLaunchIntentForPackage(TAILSCALE_PACKAGE) != null

    override fun isMarketResolvable(): Boolean =
        viewIntent(TAILSCALE_MARKET_URI).resolveActivity(pm) != null

    override fun isWebResolvable(): Boolean =
        viewIntent(TAILSCALE_WEB_URL).resolveActivity(pm) != null
}

private fun viewIntent(uri: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

/**
 * 判定 → `Intent`。[TailscaleDestination.APP] だけは `PackageManager` から取り直す
 * (ランチャー Intent はパッケージ名だけからは組めない)。取れなければ null。
 */
fun tailscaleIntentFor(destination: TailscaleDestination, pm: PackageManager): Intent? =
    when (destination) {
        TailscaleDestination.APP -> pm.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
        TailscaleDestination.STORE_APP -> viewIntent(TAILSCALE_MARKET_URI)
        TailscaleDestination.STORE_WEB -> viewIntent(TAILSCALE_WEB_URL)
    }

/**
 * ボタン押下の実行部。**開けなかったら `false` を返す**(呼び出し側が画面に出す)。
 * ここは `Context` に触るので純関数ではない —— 判定は [selectTailscaleDestination] が
 * 全部持っており、この関数に残っているのは「投げる」だけである。
 */
fun launchTailscale(context: Context): Boolean {
    val pm = context.packageManager
    val destination = selectTailscaleDestination(PackageManagerTailscaleProbe(pm)) ?: return false
    val intent = tailscaleIntentFor(destination, pm) ?: return false
    return try {
        context.startActivity(intent)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        // resolveActivity が通っても起動時に消えていることはありうる。黙って失敗させない。
        false
    }
}
