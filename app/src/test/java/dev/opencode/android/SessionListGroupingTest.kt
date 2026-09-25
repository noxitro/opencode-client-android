package dev.opencode.android

import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionTimeDto
import dev.opencode.android.ui.SessionDateGroup
import dev.opencode.android.ui.SessionListRow
import dev.opencode.android.ui.buildSessionListRows
import dev.opencode.android.ui.formatMonthDay
import dev.opencode.android.ui.relativeTimeLabel
import dev.opencode.android.ui.sessionDateGroup
import dev.opencode.android.ui.sessionGroupLabel
import dev.opencode.android.ui.shortDirectoryLabel
import dev.opencode.android.ui.startOfWeek
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日付グループ境界の固定テスト(QUALITY_PLAN §5 Q1 スコープ1)。
 *
 * ここが壊れると**一覧全体が誤ったヘッダーの下に並ぶ**が、画面は正常に見える。
 * 検出できるのは境界を明示的に殴るテストだけなので、次の4つを個別に置く:
 *   ①今日の0時ちょうど ②週の起点 ③月をまたぐ「先週」 ④タイムゾーン
 *
 * **時刻は全てエポックミリ秒**。`time.updated` がミリ秒であることは実サーバーで実測済み
 * (docs/API_CONTRACT.md「`time.updated` の単位」)。秒で書くと全部1970年になる。
 */
class SessionListGroupingTest {

    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
    private val sunday = DayOfWeek.SUNDAY
    private val monday = DayOfWeek.MONDAY

    /** ローカル日時 → エポックミリ秒。テストの意図(暦上の時刻)をそのまま書けるようにする。 */
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, zone: ZoneId = tokyo): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun group(updated: Long, now: Long, zone: ZoneId = tokyo, weekStart: DayOfWeek = sunday) =
        sessionDateGroup(updated, now, zone, weekStart)

    // ---- ① 今日の0時ちょうど ----

    @Test
    fun `今日の0時ちょうどは今日、その1ミリ秒前は昨日`() {
        // 2026-08-27(木) 10:00 が現在
        val now = ms(2026, 8, 27, 10)
        val midnight = ms(2026, 8, 27, 0, 0)
        assertEquals(SessionDateGroup.TODAY, group(midnight, now))
        assertEquals(SessionDateGroup.YESTERDAY, group(midnight - 1, now))
    }

    @Test
    fun `未来の時刻は今日に倒す(サーバーと端末の時計ずれ)`() {
        val now = ms(2026, 8, 27, 10)
        assertEquals(SessionDateGroup.TODAY, group(now + 3_600_000, now))
        assertEquals("たった今", relativeTimeLabel(now + 3_600_000, now, tokyo))
    }

    // ---- ② 週の起点 ----

    @Test
    fun `週の起点が日曜のとき、今日が日曜なら土曜は昨日で先週へ落ちない`() {
        // 2026-08-30 は日曜(週の起点)。前日 08-29 は土曜=先週側の日。
        val now = ms(2026, 8, 30, 10)
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 8, 30).dayOfWeek)
        assertEquals(SessionDateGroup.YESTERDAY, group(ms(2026, 8, 29, 12), now))
        // その前日(金)は先週
        assertEquals(SessionDateGroup.LAST_WEEK, group(ms(2026, 8, 28, 12), now))
    }

    @Test
    fun `週の起点を月曜にすると同じ日付の分類が変わる`() {
        // 2026-08-30(日) 現在。起点=月曜なら今週は 08-24(月)〜08-30(日)。
        val now = ms(2026, 8, 30, 10)
        val wednesday = ms(2026, 8, 26, 12) // 水曜
        assertEquals(SessionDateGroup.LAST_WEEK, group(wednesday, now, weekStart = sunday))
        assertEquals(SessionDateGroup.THIS_WEEK, group(wednesday, now, weekStart = monday))
    }

    @Test
    fun `startOfWeekは週の起点ごとに正しい日を返す`() {
        val thursday = LocalDate.of(2026, 8, 27)
        assertEquals(DayOfWeek.THURSDAY, thursday.dayOfWeek)
        assertEquals(LocalDate.of(2026, 8, 23), startOfWeek(thursday, sunday))
        assertEquals(LocalDate.of(2026, 8, 24), startOfWeek(thursday, monday))
    }

    // ---- ③ 月をまたぐ「先週」 ----

    @Test
    fun `先週が前の月にまたがっても先週として分類される`() {
        // 2026-09-02(水)現在、週の起点=日曜 → 今週は 08-30(日)〜。先週は 08-23〜08-29。
        val now = ms(2026, 9, 2, 10)
        assertEquals(DayOfWeek.WEDNESDAY, LocalDate.of(2026, 9, 2).dayOfWeek)
        assertEquals(SessionDateGroup.THIS_WEEK, group(ms(2026, 8, 30, 12), now))
        assertEquals(SessionDateGroup.LAST_WEEK, group(ms(2026, 8, 29, 12), now))
        assertEquals(SessionDateGroup.LAST_WEEK, group(ms(2026, 8, 23, 12), now))
        // 先週の1日前は「それ以前」
        assertEquals(SessionDateGroup.OLDER, group(ms(2026, 8, 22, 12), now))
    }

    @Test
    fun `年をまたぐ先週も先週`() {
        // 2027-01-06(水)現在。週の起点=日曜 → 今週は 2027-01-03〜。先週は 2026-12-27〜2027-01-02。
        val now = ms(2027, 1, 6, 10)
        assertEquals(SessionDateGroup.LAST_WEEK, group(ms(2026, 12, 28, 12), now))
        assertEquals(SessionDateGroup.OLDER, group(ms(2026, 12, 26, 12), now))
    }

    // ---- ④ タイムゾーン ----

    @Test
    fun `同じ瞬間でもタイムゾーンが違えば今日と昨日が入れ替わる`() {
        val utc = ZoneId.of("UTC")
        // 東京 2026-08-27 08:00 = UTC 2026-08-26 23:00
        val instant = ms(2026, 8, 27, 8, 0, tokyo)
        val now = ms(2026, 8, 27, 10, 0, tokyo)
        assertEquals(SessionDateGroup.TODAY, group(instant, now, zone = tokyo))
        assertEquals(SessionDateGroup.YESTERDAY, group(instant, now, zone = utc))
    }

    // ---- 欠損 ----

    @Test
    fun `time-updatedが無い、または非正なら日時不明`() {
        val now = ms(2026, 8, 27, 10)
        assertEquals(SessionDateGroup.UNKNOWN, group(0, now))
        assertEquals(SessionDateGroup.UNKNOWN, sessionDateGroup(null, now, tokyo, sunday))
        assertEquals("日時不明", sessionGroupLabel(SessionDateGroup.UNKNOWN, null))
    }

    // ---- ラベル ----

    @Test
    fun `グループ見出しの文字列`() {
        assertEquals("今日", sessionGroupLabel(SessionDateGroup.TODAY, null))
        assertEquals("昨日", sessionGroupLabel(SessionDateGroup.YESTERDAY, null))
        assertEquals("今週", sessionGroupLabel(SessionDateGroup.THIS_WEEK, null))
        assertEquals("先週", sessionGroupLabel(SessionDateGroup.LAST_WEEK, null))
        assertEquals("8月17日", sessionGroupLabel(SessionDateGroup.OLDER, LocalDate.of(2026, 8, 17)))
        assertEquals("8月17日", formatMonthDay(LocalDate.of(2026, 8, 17)))
    }

    // ---- 相対時刻 ----

    @Test
    fun `相対時刻は今日内が分と時間、昨日は昨日、それ以前は日付`() {
        val now = ms(2026, 8, 27, 10, 0)
        assertEquals("たった今", relativeTimeLabel(now - 30_000, now, tokyo))
        assertEquals("1分前", relativeTimeLabel(now - 60_000, now, tokyo))
        assertEquals("59分前", relativeTimeLabel(now - 59 * 60_000, now, tokyo))
        assertEquals("1時間前", relativeTimeLabel(now - 60 * 60_000, now, tokyo))
        assertEquals("9時間前", relativeTimeLabel(ms(2026, 8, 27, 1, 0), now, tokyo))
        assertEquals("昨日", relativeTimeLabel(ms(2026, 8, 26, 23, 59), now, tokyo))
        assertEquals("8月17日", relativeTimeLabel(ms(2026, 8, 17, 9, 0), now, tokyo))
        assertEquals("-", relativeTimeLabel(0, now, tokyo))
    }

    // ---- ディレクトリ短縮 ----

    @Test
    fun `directoryは末尾のフォルダ名だけを出す(Windowsパスも)`() {
        assertEquals("opencode-android", shortDirectoryLabel("E:\\github\\opencode-android"))
        assertEquals("stub", shortDirectoryLabel("/tmp/stub"))
        assertEquals("stub", shortDirectoryLabel("/tmp/stub/"))
        assertEquals("home", shortDirectoryLabel("home"))
        assertNull(shortDirectoryLabel(null))
        assertNull(shortDirectoryLabel("   "))
        assertNull(shortDirectoryLabel("/"))
    }

    // ---- 行の組み立て ----

    private fun session(id: String, updated: Long) =
        SessionDto(id = id, title = "t-$id", time = SessionTimeDto(created = updated, updated = updated))

    @Test
    fun `見出しは連続する同一グループに1つだけ入り、順序は変えない`() {
        val now = ms(2026, 8, 27, 10)
        val items = listOf(
            session("a", now - 60_000),                 // 今日
            session("b", now - 120_000),                // 今日
            session("c", ms(2026, 8, 26, 12)),          // 昨日
            session("d", ms(2026, 8, 24, 12)),          // 今週(週起点=8/23 日)
            session("e", ms(2026, 8, 20, 12)),          // 先週
            // 先週の窓は 8/16(日)〜8/22。8/15 以前が「それ以前」になる
            session("f", ms(2026, 8, 15, 12)),          // それ以前 (8月15日)
            session("g", ms(2026, 8, 14, 12)),          // それ以前 (8月14日) — 見出しが分かれる
        )
        val rows = buildSessionListRows(items, now, tokyo, sunday)
        val headers = rows.filterIsInstance<SessionListRow.Header>().map { it.label }
        assertEquals(listOf("今日", "昨日", "今週", "先週", "8月15日", "8月14日"), headers)

        // 項目の順序は入力のまま
        assertEquals(
            listOf("a", "b", "c", "d", "e", "f", "g"),
            rows.filterIsInstance<SessionListRow.Item>().map { it.session.id },
        )
        // 見出し6 + 項目7
        assertEquals(13, rows.size)
    }

    @Test
    fun `見出しキーは同じグループなら同じ、OLDERは日付で分かれる`() {
        val now = ms(2026, 8, 27, 10)
        val rows = buildSessionListRows(
            listOf(session("f", ms(2026, 8, 15, 12)), session("g", ms(2026, 8, 15, 9))),
            now,
            tokyo,
            sunday,
        )
        assertEquals(1, rows.filterIsInstance<SessionListRow.Header>().size)
    }

    @Test
    fun `空リストは行を1つも作らない`() {
        assertEquals(0, buildSessionListRows(emptyList(), ms(2026, 8, 27, 10), tokyo, sunday).size)
    }
}
