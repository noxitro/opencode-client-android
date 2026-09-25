package dev.opencode.android

import dev.opencode.android.data.HealthDto
import dev.opencode.android.data.PromptInput
import dev.opencode.android.data.PromptTextPartInput
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.data.contractJson
import dev.opencode.android.data.sortedForDisplay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/API_CONTRACT.md のshapeをそのままパースできることの固定テスト。
 * 実サーバーは契約書に無い追加フィールド(cost, tokens等)を返すため ignoreUnknownKeys を検証する。
 */
class ContractParsingTest {

    @Test
    fun `healthレスポンスをパースできる`() {
        val dto = contractJson.decodeFromString<HealthDto>("""{"healthy":true,"version":"1.18.21"}""")
        assertEquals(true, dto.healthy)
        assertEquals("1.18.21", dto.version)
    }

    @Test
    fun `session配列を未知フィールド付きでパースできる`() {
        val text = """
            [
              {
                "id": "ses_a",
                "slug": "a",
                "projectID": "proj",
                "directory": "/tmp",
                "title": "first",
                "version": "1.18.21",
                "time": {"created": 1000, "updated": 2000},
                "cost": 0.5,
                "tokens": {"input": 1},
                "share": {"url": "https://example.test/s/a"}
              },
              {
                "id": "ses_b",
                "slug": "b",
                "projectID": "proj",
                "directory": "/tmp",
                "title": "second",
                "version": "1.18.21",
                "time": {"created": 3000, "updated": 9000}
              }
            ]
        """.trimIndent()
        val list = contractJson.decodeFromString(ListSerializer(SessionDto.serializer()), text)
        assertEquals(2, list.size)
        assertEquals("first", list[0].title)

        // time.updated降順に並び替えられる
        val sorted = list.sortedForDisplay()
        assertEquals("ses_b", sorted[0].id)
        assertEquals("ses_a", sorted[1].id)
    }

    @Test
    fun `updated不明のセッションは末尾に来る`() {
        val noTime = SessionDto(id = "ses_none")
        val old = SessionDto(id = "ses_old", time = SessionTimeDto(created = 1, updated = 5))
        assertEquals(listOf(old, noTime), listOf(noTime, old).sortedForDisplay())
    }

    @Test
    fun `空タイトルはIDで代替表示される`() {
        val s = SessionDto(id = "ses_x", title = "  ")
        assertEquals("ses_x", s.displayTitle)
        assertFalse(s.displayTitle.isBlank())
    }

    @Test
    fun `prompt_asyncのボディはtype付きpartとして直列化される(契約shape)`() {
        // 回帰: encodeDefaults=false のままでは既定値付きの type が欠落し
        // {"parts":[{"text":"..."}]} が送信されていた(実測バグ)。
        val body = contractJson.encodeToString(
            PromptInput(parts = listOf(PromptTextPartInput(text = "こんにちは"))),
        )
        assertTrue("typeフィールドが欠落: $body", body.contains("\"type\":\"text\""))
        assertTrue(body.contains("\"text\":\"こんにちは\""))
        assertEquals("""{"parts":[{"type":"text","text":"こんにちは"}]}""", body)
    }
}
