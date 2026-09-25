package dev.opencode.android

import dev.opencode.android.ui.TAILSCALE_MARKET_URI
import dev.opencode.android.ui.TAILSCALE_PACKAGE
import dev.opencode.android.ui.TAILSCALE_WEB_URL
import dev.opencode.android.ui.TailscaleDestination
import dev.opencode.android.ui.TailscaleProbe
import dev.opencode.android.ui.selectTailscaleDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Q11: 「Tailscaleを開く」の3段フォールバックの検出器(レビュー major-2)。
 *
 * 元の実装は判定が `SettingsScreen` の `onClick` に直書きで**テストが0本**だった。
 * 3分岐のどれを潰しても画面は正常に見えるので、変異は素通りする。
 * ここでは [selectTailscaleDestination] に fake の [TailscaleProbe] を渡し、
 * **4つの状態(アプリ / market / https / どこへも飛べない)すべて**を固定する。
 */
class Q11TailscaleTest {

    /** 呼ばれた問い合わせも記録する fake。**要らない問い合わせをしていないこと**も見る。 */
    private class FakeProbe(
        private val app: Boolean,
        private val market: Boolean,
        private val web: Boolean,
    ) : TailscaleProbe {
        val asked = mutableListOf<String>()

        override fun isAppLaunchable(): Boolean {
            asked += "app"
            return app
        }

        override fun isMarketResolvable(): Boolean {
            asked += "market"
            return market
        }

        override fun isWebResolvable(): Boolean {
            asked += "web"
            return web
        }
    }

    @Test
    fun `Tailscaleがインストール済みならアプリを開く`() {
        val probe = FakeProbe(app = true, market = true, web = true)
        assertEquals(TailscaleDestination.APP, selectTailscaleDestination(probe))
        // アプリが開けるなら、ストアの可視性は問い合わせない。
        assertEquals(listOf("app"), probe.asked)
    }

    @Test
    fun `未インストールで market が解決できるならストアアプリ`() {
        val probe = FakeProbe(app = false, market = true, web = true)
        assertEquals(TailscaleDestination.STORE_APP, selectTailscaleDestination(probe))
        assertEquals(listOf("app", "market"), probe.asked)
    }

    /**
     * **market が解決できない端末**(Play ストア非搭載、あるいは `<queries>` に
     * `com.android.vending` が無い状態 —— レビュー major-3 が指摘した状況)では https へ。
     */
    @Test
    fun `market が解決できなければ https へ落ちる`() {
        val probe = FakeProbe(app = false, market = false, web = true)
        assertEquals(TailscaleDestination.STORE_WEB, selectTailscaleDestination(probe))
        assertEquals(listOf("app", "market", "web"), probe.asked)
    }

    /**
     * **どこへも飛べないなら null。**「飛んだ」と混同させない
     * (レビュー minor-8。無ガードの `startActivity` は `ActivityNotFoundException` で落ちる)。
     */
    @Test
    fun `どこへも飛べなければ null`() {
        val probe = FakeProbe(app = false, market = false, web = false)
        assertNull(selectTailscaleDestination(probe))
        assertEquals(listOf("app", "market", "web"), probe.asked)
    }

    /**
     * **`<queries>` に `com.android.vending` が宣言されていること**(レビュー major-3)。
     *
     * API 30+ のパッケージ可視性制限下では、この宣言が無いと `market://` を解決する
     * Play ストアが「見えない」ので `resolveActivity` が常に null になり、
     * [selectTailscaleDestination] の2段目は**永久に選ばれない**(= デッドコード)。
     * 分岐のテストだけでは検出できない —— 判定は正しいまま入力が偽になるからである。
     *
     * manifest はビルド成果物ではなくソースとして読む(unit test の cwd は
     * `app/` モジュールか repo ルートのどちらかになりうるので両方試す)。
     */
    @Test
    fun `manifest の queries に Play ストアが宣言されている`() {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        val manifest = listOf(
            java.io.File(cwd, "src/main/AndroidManifest.xml"),
            java.io.File(cwd, "app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
        requireNotNull(manifest) { "AndroidManifest.xml が見つからない (cwd=$cwd)" }

        val text = manifest.readText()
        val queries = text.substringAfter("<queries>", "").substringBefore("</queries>", "")
        org.junit.Assert.assertTrue("<queries> ブロックが無い", queries.isNotBlank())
        org.junit.Assert.assertTrue(
            "com.tailscale.ipn の宣言が無い: $queries",
            queries.contains("""android:name="com.tailscale.ipn""""),
        )
        org.junit.Assert.assertTrue(
            "com.android.vending の宣言が無い(market:// が API30+ で死ぬ): $queries",
            queries.contains("""android:name="com.android.vending""""),
        )
    }

    /** 行き先の URI/パッケージ名を固定する(タイポ変異の検出器)。 */
    @Test
    fun `行き先の定数`() {
        assertEquals("com.tailscale.ipn", TAILSCALE_PACKAGE)
        assertEquals("market://details?id=com.tailscale.ipn", TAILSCALE_MARKET_URI)
        assertEquals("https://play.google.com/store/apps/details?id=com.tailscale.ipn", TAILSCALE_WEB_URL)
    }
}
