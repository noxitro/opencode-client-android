package dev.opencode.android.ui

import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SessionStatusDto
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.sortedForDisplay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * セッション一覧の**表示ロジックだけ**を持つ純関数群(QUALITY_PLAN §5 Q1 スコープ1・2・3)。
 *
 * なぜ純関数に切り出すのか: 日付の境界は「今日の0時ちょうど」「月をまたぐ先週」「週の起点」
 * 「タイムゾーン」の4つで壊れる。Composable の中に埋めると**実機で日付が変わるまで
 * 検証できない**——つまり一度も検証されない。ここに [now] / [zone] / [firstDayOfWeek] を
 * 引数として持たせ、境界そのものをユニットテストで殴れる形にする。
 *
 * **時刻の単位はエポックミリ秒**。`Session.time.updated` が実際にミリ秒であることは
 * 実サーバーで測ってある(API_CONTRACT.md「`time.updated` の単位」)。秒と取り違えると
 * 全件が1970年になり、逆だと56000年後になる。
 */

/** 日付グループヘッダーの種別。 */
enum class SessionDateGroup {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    LAST_WEEK,
    OLDER,

    /**
     * `time.updated` が欠損/非正。spec 上 `time` は required なので実サーバーでは出ないが、
     * 出たときに「1970年1月1日」というヘッダーを作らないための逃げ場所。
     */
    UNKNOWN,
}

/** 実行状態バッジ。`GET /session/status` と `session.status` の `type` から決まる。 */
enum class SessionRunState { IDLE, BUSY, RETRY }

/**
 * `SessionStatus.type` → バッジ。
 * **マップにキーが無い(=null)ときは idle**(実測: `GET /session/status` は idle を返さない)。
 * 未知の type が増えても落とさず idle に倒す。
 */
fun runStateOf(status: SessionStatusDto?): SessionRunState = when (status?.type) {
    "busy" -> SessionRunState.BUSY
    "retry" -> SessionRunState.RETRY
    else -> SessionRunState.IDLE
}

/** 一覧の1行。ヘッダーと項目を同じリストに並べて LazyColumn へ渡す。 */
sealed interface SessionListRow {
    /** [key] は LazyColumn の key。OLDER は日付ごとにヘッダーが分かれるので日付を含む。 */
    data class Header(val group: SessionDateGroup, val label: String, val key: String) : SessionListRow

    data class Item(val session: SessionDto) : SessionListRow
}

/**
 * `time.updated`(エポックミリ秒)がどのグループに落ちるかを決める。
 *
 * 判定順が意味を持つ: **今日 → 昨日 → 今週 → 先週 → それ以前**。
 * 昨日を今週より先に見るのは、週の起点が日曜のとき「今日=日曜・昨日=土曜」が
 * 先週側に落ちてしまうため。ユーザーにとって昨日は常に昨日である。
 *
 * @param nowMs 現在時刻(エポックミリ秒)
 * @param zone 端末のローカルタイムゾーン。「今日」はローカル暦日で決まる
 * @param firstDayOfWeek 週の起点。ロケール依存なので呼び出し側から明示的に渡す
 */
fun sessionDateGroup(
    updatedMs: Long?,
    nowMs: Long,
    zone: ZoneId,
    firstDayOfWeek: DayOfWeek,
): SessionDateGroup {
    if (updatedMs == null || updatedMs <= 0L) return SessionDateGroup.UNKNOWN
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    // 未来の時刻(端末とサーバーの時計ずれ)は「今日」に倒す。「-3分前」を出さないため。
    val date = Instant.ofEpochMilli(updatedMs).atZone(zone).toLocalDate()
        .let { if (it.isAfter(today)) today else it }

    if (date == today) return SessionDateGroup.TODAY
    if (date == today.minusDays(1)) return SessionDateGroup.YESTERDAY

    val weekStart = startOfWeek(today, firstDayOfWeek)
    if (!date.isBefore(weekStart)) return SessionDateGroup.THIS_WEEK
    if (!date.isBefore(weekStart.minusWeeks(1))) return SessionDateGroup.LAST_WEEK
    return SessionDateGroup.OLDER
}

/**
 * [date] を含む週の初日。`minusWeeks` ではなく曜日の差で戻すので、
 * **月をまたいでも年をまたいでも同じ式で正しい**(LocalDate が暦を持っている)。
 */
internal fun startOfWeek(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
    val diff = (date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    return date.minusDays(diff.toLong())
}

/** 「8月17日」。ロケールの書式に任せると端末次第で "8/17" や "Aug 17" になるので自前で組む。 */
internal fun formatMonthDay(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

/**
 * グループヘッダーの表示文字列。OLDER だけは項目の日付そのものが見出しになる
 * (§2.1「先週より前は『8月17日』のような日付表記のヘッダー」)。
 */
fun sessionGroupLabel(group: SessionDateGroup, date: LocalDate?): String = when (group) {
    SessionDateGroup.TODAY -> "今日"
    SessionDateGroup.YESTERDAY -> "昨日"
    SessionDateGroup.THIS_WEEK -> "今週"
    SessionDateGroup.LAST_WEEK -> "先週"
    SessionDateGroup.OLDER -> date?.let(::formatMonthDay) ?: "以前"
    SessionDateGroup.UNKNOWN -> "日時不明"
}

/**
 * 相対時刻(カードの右端)。
 * 今日内は「たった今 / N分前 / N時間前」、昨日は「昨日」、それ以前は「M月d日」
 * (QUALITY_PLAN §5 Q1 スコープ2)。
 */
fun relativeTimeLabel(updatedMs: Long?, nowMs: Long, zone: ZoneId): String {
    if (updatedMs == null || updatedMs <= 0L) return "-"
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val date = Instant.ofEpochMilli(updatedMs).atZone(zone).toLocalDate()
    return when {
        // 未来(時計ずれ)は「たった今」。負の分数を見せない。
        updatedMs >= nowMs -> "たった今"
        date == today -> {
            val minutes = (nowMs - updatedMs) / 60_000L
            when {
                minutes < 1 -> "たった今"
                minutes < 60 -> "${minutes}分前"
                else -> "${minutes / 60}時間前"
            }
        }
        date == today.minusDays(1) -> "昨日"
        else -> formatMonthDay(date)
    }
}

/**
 * §2.4 の写像「『リモートコントロール』ラベル → セッションの `directory` 短縮表示」。
 * 実サーバーの directory は `E:\github\opencode-android` のような Windows パスでも来るので
 * 両方の区切りを見る。
 */
fun shortDirectoryLabel(directory: String?): String? {
    val trimmed = directory?.trim()?.trimEnd('/', '\\')
    if (trimmed.isNullOrEmpty()) return null
    val cut = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
    val name = if (cut >= 0) trimmed.substring(cut + 1) else trimmed
    return name.takeIf { it.isNotEmpty() }
}

/**
 * 一覧(**time.updated 降順に整列済み**であること)をヘッダー付きの行列へ畳む。
 *
 * 並び順は変えない。順序を作り直すとサーバーの並びと食い違い、ページングで
 * 「読み込んだ順」と「表示順」がずれる。ここは**区切りを挿すだけ**。
 */
fun buildSessionListRows(
    sessions: List<SessionDto>,
    nowMs: Long,
    zone: ZoneId,
    firstDayOfWeek: DayOfWeek,
): List<SessionListRow> {
    val rows = ArrayList<SessionListRow>(sessions.size + 6)
    var lastKey: String? = null
    for (session in sessions) {
        val updated = session.time?.updated
        val group = sessionDateGroup(updated, nowMs, zone, firstDayOfWeek)
        val date = if (group == SessionDateGroup.OLDER && updated != null && updated > 0L) {
            Instant.ofEpochMilli(updated).atZone(zone).toLocalDate()
        } else {
            null
        }
        // OLDER は日付ごとに見出しが変わるので、キーに日付を混ぜる。
        val key = if (date != null) "${group.name}:${date.toEpochDay()}" else group.name
        if (key != lastKey) {
            rows.add(SessionListRow.Header(group = group, label = sessionGroupLabel(group, date), key = key))
            lastKey = key
        }
        rows.add(SessionListRow.Item(session))
    }
    return rows
}

// ---- 楽観更新とイベント適用(純関数。ロールバックが効くことを測れる形にするため) ----

/**
 * 削除の楽観更新: 表示から先に消す。**サーバー応答を待たない**(§5 Q1 スコープ7)。
 * 戻ってこられるように、消した位置は呼び出し側が [indexOfSession] で控えておくこと。
 */
fun removeSessionOptimistically(items: List<SessionDto>, sessionId: String): List<SessionDto> =
    items.filterNot { it.id == sessionId }

/** 楽観削除の位置。-1 = 一覧に無い。 */
fun indexOfSession(items: List<SessionDto>, sessionId: String): Int =
    items.indexOfFirst { it.id == sessionId }

/**
 * 削除失敗時のロールバック。**元の位置へ戻す** — 末尾に足すと「戻った」ことが
 * 画面上で並び替えと区別できず、ロールバックが効いているかを測れない。
 *
 * 位置は**添字ではなく「1つ上の行のid」([anchorId])で指す**。添字で覚えると、
 * 通信中に `session.created` が届いて先頭に1件挿さっただけで1つずれ、
 * **別の場所に生き返る**(Q1レビュー minor-4)。id なら列が動いても指し先は動かない。
 *
 * - [anchorId] が null = 元は先頭だった → 先頭へ戻す
 * - [anchorId] が見つからない(その行自体が消えた)→ `time.updated` 降順の正しい位置へ入れる
 * - 既に同じidが居る(イベントで復帰済み)→ 二重に足さない
 */
fun restoreSessionAfter(
    items: List<SessionDto>,
    removed: SessionDto,
    anchorId: String?,
): List<SessionDto> {
    if (items.any { it.id == removed.id }) return items
    if (anchorId == null) return listOf(removed) + items
    val anchor = items.indexOfFirst { it.id == anchorId }
    if (anchor < 0) return (items + removed).sortedForDisplay()
    return ArrayList<SessionDto>(items.size + 1).apply {
        addAll(items)
        add(anchor + 1, removed)
    }
}

/**
 * `session.created` / `session.updated` / `session.deleted` を一覧へ適用する。
 *
 * - DELETED: id で消す。`info` が読めなくても `sessionID` だけで消せる
 * - UPDATED: 既にある項目を差し替える(**タイトル自動更新**の経路。§5 Q1 スコープ4)
 * - CREATED: [allowInsert] のときだけ先頭へ入れる。検索中は false —
 *   検索条件に合わない新規セッションを結果に混ぜると、一覧が「検索結果」でなくなる
 *
 * 差し替え後は必ず `time.updated` 降順へ整列し直す(改名では `time.updated` が
 * 動かないことは実測済みなので、実際には順序は動かない。将来 updated を伴う更新が
 * 来たときに順序が壊れないための保険)。
 */
fun applySessionInfoToItems(
    items: List<SessionDto>,
    kind: SseEvent.SessionInfoChanged.Kind,
    sessionId: String?,
    info: SessionDto?,
    allowInsert: Boolean,
): List<SessionDto> {
    val id = info?.id ?: sessionId ?: return items
    if (kind == SseEvent.SessionInfoChanged.Kind.DELETED) {
        return items.filterNot { it.id == id }
    }
    if (info == null) return items
    val existing = items.any { it.id == id }
    return when {
        existing -> items.map { if (it.id == id) info else it }.sortedForDisplay()
        allowInsert -> (listOf(info) + items).distinctBy { it.id }.sortedForDisplay()
        else -> items
    }
}

/** テスト・デバッグ用: 2つの時刻が同じローカル暦日か。 */
internal fun sameLocalDay(a: Long, b: Long, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

/** テスト用の可読ヘルパ: [days] 日前の同時刻(ローカル)。 */
internal fun daysBefore(nowMs: Long, days: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowMs).atZone(zone).minus(days, ChronoUnit.DAYS).toInstant().toEpochMilli()
