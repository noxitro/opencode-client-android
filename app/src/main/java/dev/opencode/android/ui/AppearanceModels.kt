package dev.opencode.android.ui

import dev.opencode.android.data.ColorMode
import dev.opencode.android.data.SessionDto

/**
 * 実際に使う配色を決める(§5 Q5 スコープ2「カラーモード: システム/ダーク/ライト」)。
 *
 * **条件はここにしか無い。** `MainActivity` / `OpenCodeTheme` / システムバーの色は
 * どれもこの1つの関数の戻りを使う —— 3か所で `if (mode == DARK || (mode == SYSTEM && ...))` を
 * 書くと、1か所だけ直し忘れた状態が「画面は正常に見える」ままになる
 * (RUN_PLAN「UI配線層は今も素通し」)。
 *
 * @param systemInDarkMode 端末側のダークモード設定(`Configuration.UI_MODE_NIGHT_YES`)
 */
fun isDarkTheme(mode: ColorMode, systemInDarkMode: Boolean): Boolean = when (mode) {
    ColorMode.DARK -> true
    ColorMode.LIGHT -> false
    ColorMode.SYSTEM -> systemInDarkMode
}

/** 設定画面の「現在値」表示(§2.3「アイコン+ラベル+現在値(グレーの小文字)」)。 */
fun colorModeLabel(mode: ColorMode): String = when (mode) {
    ColorMode.SYSTEM -> "システム"
    ColorMode.DARK -> "ダーク"
    ColorMode.LIGHT -> "ライト"
}

/**
 * ドロワーのヘッダーに出す接続先の表示名(§5 Q5 スコープ1「接続先ホスト名+バージョン」)。
 *
 * 正規化済み baseUrl(`http://host:port`)から `host:port` を取り出す。**素の文字列操作**にする:
 * `java.net.URI` はユニットテストでも動くが、ここで欲しいのは「人が読む短い名前」であって
 * URIの解釈ではない。未設定・解釈不能なら null(呼び出し側が「未設定」を出す)。
 */
/**
 * ドロワーの「最近の項目」に出す行(§5 Q5 スコープ1「一覧上位N件」)。
 *
 * **並べ替えない。** 一覧が既に表示順(`sortedForDisplay`)で持っているものの先頭を切るだけである。
 * ここで並べ替えると、ドロワーと一覧が違う順序を出す経路ができる。
 * **取得もしない** —— 通信を起こす口はこの関数にも [AppDrawerContent] にも無い
 * (一覧の再取得はページングを1ページ目へ戻す。Q1レビュー major-1)。
 */
fun recentSessions(items: List<SessionDto>, count: Int = DRAWER_RECENT_COUNT): List<SessionDto> =
    items.take(count)

fun connectionHostLabel(baseUrl: String?): String? {
    val raw = baseUrl?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val afterScheme = raw.substringAfter("://", raw)
    val authority = afterScheme.substringBefore('/')
    return authority.takeIf { it.isNotEmpty() }
}

/**
 * サーバーバージョンの表示文言。**ドロワーと設定画面で同じ関数を使う**(Q5 レビュー minor-2)。
 *
 * 1周目はドロワーが `version ?: "バージョン取得中…"` と書いており、
 * **取得に失敗しても「取得中…」と言い続けた** —— レビューが死んだポートへ向けて
 * 「セッション中ずっと取得中のまま」を実機で観測している。設定画面は
 * 取得中/取得できません/未取得を正しく区別していたので、**ドロワーだけが嘘をついていた**。
 *
 * 文言を2か所に書くと、片方だけ直した状態が「画面は正常に見える」まま残る。1本にする。
 */
fun serverVersionLabel(info: ServerInfoUi): String = when {
    info.version != null -> info.version
    info.loading -> "取得中…"
    info.error != null -> "取得できません"
    else -> "未取得"
}

/**
 * ドロワーヘッダーの2行目。**バージョンが取れているときだけ `serve` を冠する** ——
 * 「serve 取得できません」は日本語として壊れているし、取れた版と区別が付かない。
 */
fun drawerServerLine(info: ServerInfoUi): String =
    info.version?.let { "serve $it" } ?: "バージョン: ${serverVersionLabel(info)}"

// ---------------------------------------------------------------------------
// BACK キーの扱い(Q5 E2E 所見B)
// ---------------------------------------------------------------------------

/**
 * ドロワーが開いている間の BACK は「**ドロワーを閉じる**」だけを意味する。
 *
 * 直す欠陥(E2E 所見B、実機で2回再現): Material3 の `ModalNavigationDrawer` は BACK を
 * 自前で拾わない。一覧画面には戻る導線が無いので BACK はそのまま Activity を抜け、
 * **ドロワーを開いたまま BACK を押すとアプリが終了してランチャーへ落ちた**。
 * Android の慣習(開いているシートは BACK で閉じる)に反する。
 *
 * [screenBackEnabled] と**対**にすること。両方が同時に有効だと、
 * どちらのハンドラが先に呼ばれるかが Compose の登録順に依存し、
 * 設定画面でドロワーを開いて BACK を押すと**ドロワーが閉じずに一覧へ飛ぶ**。
 * 登録順に頼らないよう、条件をこの2関数に集めて**排他**にしてある。
 */
fun drawerBackEnabled(drawerOpen: Boolean): Boolean = drawerOpen

/**
 * 画面固有の戻る導線(設定→一覧)を BACK で受けるか。
 *
 * **ドロワーが開いている間は黙る**([drawerBackEnabled] と排他)。
 * ドロワーが閉じているときの挙動は従来どおり —— 接続設定が保存済みなら一覧へ戻り、
 * 未設定の初回起動では受けない(戻り先が無い)。
 */
fun screenBackEnabled(canGoBack: Boolean, drawerOpen: Boolean): Boolean = canGoBack && !drawerOpen
