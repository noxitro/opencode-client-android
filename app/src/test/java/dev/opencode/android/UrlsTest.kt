package dev.opencode.android

import dev.opencode.android.data.DEFAULT_OPENCODE_PORT
import dev.opencode.android.data.Urls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlsTest {

    @Test
    fun `スキーム付きURLは末尾スラッシュを除去して正規化される`() {
        assertEquals("http://10.0.2.2:4097", Urls.normalize("http://10.0.2.2:4097/"))
        assertEquals("http://10.0.2.2:4097", Urls.normalize("  http://10.0.2.2:4097  "))
    }

    @Test
    fun `スキーム省略時はhttpを補う`() {
        assertEquals("http://10.0.2.2:4097", Urls.normalize("10.0.2.2:4097"))
        assertEquals("http://localhost:4096", Urls.normalize("localhost:4096/"))
    }

    // ---- H1a: ポート省略時の補填(LOOP.md H1a / QUALITY_PLAN §5 Q5 の申し送り) ----

    /**
     * **報告された入力そのもの。**
     *
     * `http://100.64.0.1` はスキーム付き・ポート無しで、以前は補填経路
     * (`if (schemeMatch == null)` の内側)を通らずHTTP既定の80番へ落ちていた。
     * serve は 4097 でしか listen していない(LOOP.md H1 の実測)ので、これは繋がらない。
     *
     * **旧テスト `http host no port (スキーム付き、ポートなし) はポートなしで正規化される` は
     * この症状を「正しい挙動」として固定していた**ため書き直した。
     */
    @Test
    fun `報告された入力 http100_64_0_1 は80ではなく4097を補われる`() {
        assertEquals("http://100.64.0.1:$DEFAULT_OPENCODE_PORT", Urls.normalize("http://100.64.0.1"))
        // 「80に落ちない」を同一性ではなく**否定側でも**固定する。
        assertNull(Urls.normalize("http://100.64.0.1")?.takeIf { it.endsWith(":80") })
        assertNull(Urls.normalize("http://100.64.0.1")?.takeIf { it == "http://100.64.0.1" })
    }

    /**
     * **スキームの有無で挙動が分かれないこと。** 分岐軸は「ポートを書いたか」だけである。
     * 同じホストを2通りに書いた結果が一致することを、文字列の同一性で主張する。
     */
    @Test
    fun `ポート補填はスキームの有無で分岐しない`() {
        assertEquals(Urls.normalize("100.64.0.1"), Urls.normalize("http://100.64.0.1"))
        assertEquals(Urls.normalize("10.0.2.2"), Urls.normalize("http://10.0.2.2"))
        assertEquals("http://10.0.2.2:$DEFAULT_OPENCODE_PORT", Urls.normalize("10.0.2.2"))
        assertEquals("http://10.0.2.2:$DEFAULT_OPENCODE_PORT", Urls.normalize("http://10.0.2.2"))
    }

    /** 明示されたポートは**書かれたまま**。補填が上書きしない(4098 のスタブへ繋げなくなる)。 */
    @Test
    fun `明示ポートは補填で上書きされない`() {
        assertEquals("http://10.0.2.2:4098", Urls.normalize("http://10.0.2.2:4098"))
        assertEquals("http://10.0.2.2:80", Urls.normalize("http://10.0.2.2:80"))
        assertEquals("https://example.com:8443", Urls.normalize("https://example.com:8443"))
    }

    /** `https://host` の既定は 443 が正しい。**ここだけは分岐してよい**(4097 を補わない)。 */
    @Test
    fun `httpsはポート無しのままにする`() {
        assertEquals("https://example.com", Urls.normalize("https://example.com"))
        assertEquals("https://example.com", Urls.normalize("https://example.com/"))
    }

    @Test
    fun `パスは保持され ポート省略なら補填も効く`() {
        // 補填規則はパスの有無でも分岐しない(Urls.kt の「既知の代償」を参照)。
        assertEquals("http://example.com:$DEFAULT_OPENCODE_PORT/api", Urls.normalize("http://example.com/api/"))
        assertEquals("http://example.com:4097/api", Urls.normalize("example.com:4097/api"))
        assertEquals("https://example.com/api", Urls.normalize("https://example.com/api/"))
    }

    @Test
    fun `不正な入力はnull`() {
        assertNull(Urls.normalize(""))
        assertNull(Urls.normalize("   "))
        assertNull(Urls.normalize("http://"))
        assertNull(Urls.normalize("ftp://example.com"))
    }
}
