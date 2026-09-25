package dev.opencode.android.ui

import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.ConnectionSetupGateway
import dev.opencode.android.data.HealthDto
import dev.opencode.android.data.Urls
import kotlinx.coroutines.flow.Flow

/**
 * **配線そのものを `runTest` から叩ける形に出す場所**(Q5 レビュー major-2)。
 *
 * ## なぜこのファイルが要るのか
 *
 * Q5 の1周目は、状態機械にも純関数にも検出器を置いた(変異13本すべて検出)。
 * それでもレビューが打った変異8本のうち**7本が431件全緑で通り抜けた** —— 狙われたのは
 * 関数の中身ではなく、**`AppViewModel` / `AppRoot` に素で書かれた呼び出し口**だった:
 *
 *  - `Urls.normalize` を1か所の呼び出し口で外すと、H1a の修正が丸ごと死んだコードになる(R1)
 *  - `toCatalog = { }` にすると、**Q4 の blocker(サーバーAのモデルをBへ送る)が静かに再オープン**する(R3)
 *  - `ensureLoaded()` を `refresh()` に変えると、**Q1 major-1 のページング退行がそのまま戻る**(R7)
 *
 * どれも**画面はどこも壊れて見えない**。このプロジェクトが7度繰り返した形である。
 *
 * ## 設計の規則
 *
 * 1. **宛先はオブジェクトで名指しする。ラムダで受け取らない。**
 *    `toCatalog: (String?) -> Unit` を引数にすると、呼び出し側が `{ }` を渡す変異が
 *    この関数のテストからは見えない。[ModelCatalogController] そのものを受け取れば、
 *    「宛先を落とす」変異は**この関数の中**にしか書けず、テストが捕まえる。
 * 2. **条件はこのファイルの中にしか置かない。** 呼び出し側に `if` を書かない。
 * 3. **Android にも Compose にも依存しない。** ここに import してよいのは data 層と controller だけ。
 *
 * Compose の中(`LaunchedEffect` / `onClick`)に残る配線はここでは閉じられない。
 * それは実機の dump と logcat で押さえる —— 報告に「閉じられない」と明記すること。
 */

// ---------------------------------------------------------------------------
// 接続先の変更(R3 / R8)
// ---------------------------------------------------------------------------

/**
 * 接続先が変わったことを**2つの宛先へ順に無条件で配る**購読。
 *
 * 宛先を `ModelCatalogController` / `ServerInfoController` の**実体**で受け取るのが要点
 * (上の規則1)。1周目は `toCatalog: (String?) -> Unit` というラムダ引数で、
 * 呼び出し側が `{ }` を渡す変異(R3)が素通りしていた。
 *
 * **カタログを捨てるのは Q4 レビュー major-1 の防波堤である。** 古いカタログは
 * 「`GET /provider` から得た候補以外を送らない」という唯一の防波堤をそのまま抜け、
 * サーバーB はサーバーA のモデルIDを **204 で受理して保存する**(API_CONTRACT.md 実測 #4)。
 * ここを空にすると、その blocker が静かに戻る。
 *
 * 鍵は **baseUrl だけ**。`connected` はサーバー側の環境変数で決まるので「どのサーバーか」が
 * カタログの同一性であり、パスワードは同一性ではない(AGENTS.md: 資格情報を持ち回さない)。
 *
 * 「同じ接続先なら何もしない」「初回は捨てるものが無い」の判断は各 controller の内側にある。
 * [collectGuarded] を通すので、片方が投げてももう片方と後続の変更は死なない。
 */
suspend fun Flow<String?>.wireConnectionChangesTo(
    catalog: ModelCatalogController,
    serverInfo: ServerInfoController,
    /**
     * Q7: 3つ目の宛先。**ブランチと作業ツリーはサーバーごとに違う。**
     * 捨てないと、サーバーAの `master` がサーバーBの TopAppBar に出続ける ——
     * Q4 レビュー major-1 がカタログで見つけたのと同じ形で、画面はどこも壊れて見えない。
     */
    diff: DiffController,
    /**
     * Q8: 4つ目の宛先。**ツリーと検索結果もサーバーごとに違う。**
     * 捨てないと、サーバーAのディレクトリ一覧がサーバーBの画面に出続ける ——
     * Q4 レビュー major-1 がカタログで、Q7 がブランチで見つけたのと同じ形で、
     * **画面はどこも壊れて見えない**。
     */
    files: FileBrowserController,
    /**
     * Q9: 5つ目の宛先。**ここだけは捨てるだけでは足りず、開いている WebSocket を閉じる。**
     * 閉じないと、**サーバーA のシェルへ入力を送り続ける経路が生き残る** ——
     * 画面は新しいサーバーの一覧を出しているので、どこも壊れて見えない。
     * 閉じる判断は [PtyController.onConnectionChanged] の内側にある。
     */
    pty: PtyController,
    onFailure: (Throwable) -> Unit,
) {
    collectGuarded(onFailure) { baseUrl ->
        catalog.onConnectionChanged(baseUrl)
        serverInfo.onConnectionChanged(baseUrl)
        diff.onConnectionChanged(baseUrl)
        files.onConnectionChanged(baseUrl)
        pty.onConnectionChanged(baseUrl)
    }
}

// ---------------------------------------------------------------------------
// 接続設定の保存 + 疎通テスト(R1)
// ---------------------------------------------------------------------------

/** [performSaveAndTestHealth] が返す結果。[HealthUiState] を組むのは呼び出し側の関心。 */
sealed interface SaveAndTestOutcome {
    /** URL が解釈できなかった。**保存もテストもしていない。** */
    data object BadUrl : SaveAndTestOutcome

    /** 保存して疎通を測った。[normalized] は**実際に保存した文字列**。 */
    data class Measured(val normalized: String, val result: ApiResult<HealthDto>) : SaveAndTestOutcome
}

/**
 * 接続設定を保存し、その接続先の `/global/health` を測る。
 *
 * **[Urls.normalize] を通る経路はここ1本しかない。** 1周目は `AppViewModel` に素で書かれており、
 * レビューがこの1行を `val normalized = rawUrl` に変えたところ、
 * **H1a のために足した `UrlsTest` 6本を含む431件が全緑のまま通り抜けた**(R1)。
 * `Urls.normalize` 自身のテストは「関数が正しい」ことしか主張できず、
 * 「**アプリがその関数を通る**」は別の主張である。
 *
 * ここを叩くテストは、[gateway] が受け取った文字列が**正規化済みであること**を assert する。
 *
 * [onTesting] は測っている間の画面表示のため。**資格情報はここから先へ渡さない** ——
 * [gateway] の実体が [dev.opencode.android.data.ConnectionRepository] で、保存も health も
 * そこが持つ(AGENTS.md)。
 */
suspend fun performSaveAndTestHealth(
    rawUrl: String,
    password: String,
    gateway: ConnectionSetupGateway,
    onTesting: () -> Unit,
): SaveAndTestOutcome {
    val normalized = Urls.normalize(rawUrl) ?: return SaveAndTestOutcome.BadUrl
    onTesting()
    // 保存でリポジトリ側の接続先が切り替わる。**保存が先** —— 逆順だと
    // 直後の health が古い接続先を測る(ConnectionRepository.save の doc)。
    gateway.save(normalized, password)
    return SaveAndTestOutcome.Measured(normalized, gateway.health())
}

// ---------------------------------------------------------------------------
// 画面遷移の副作用(R2 / R7)
// ---------------------------------------------------------------------------

/**
 * 一覧画面が表示された(回転・チャットからの復帰・ドロワーからの移動)。
 *
 * **取り直しではなく「まだ読んでいなければ読む」。** ここを `refresh()` にすると
 * `limit` が1ページ目へ戻り、120件を末尾まで開いていたユーザーが48件目付近へ飛ぶ
 * (Q1レビュー major-1)。レビューの変異 R7 がこの1行だった。
 *
 * 呼び出し側に条件を書かないこと —— 「まだ読んでいないか」の判定は
 * [SessionListController.ensureLoaded] が持つ。
 */
fun onSessionListShown(sessions: SessionListController) {
    sessions.ensureLoaded()
}

/**
 * 一覧へ移る導線の副作用。**設定から出るときだけ**1ページ目から取り直す。
 *
 * 取り直す理由: 設定画面では接続先が変わっているかもしれず、前のサーバーの一覧を
 * 出し続けるわけにいかない。**一覧から一覧へ(ドロワーの「セッション一覧」)では撃たない** ——
 * 撃つとページングが1ページ目へ戻る。レビューの変異 R2 がこのガードを外すものだった。
 *
 * 条件はこの関数の中にしか無い。呼び出し側は無条件に呼ぶこと。
 *
 * @return 実際に取り直したか(テストと計測のため。呼び出し側は分岐しない)
 */
fun applyEnterSessionsEffect(from: Screen?, sessions: SessionListController): Boolean {
    if (from != Screen.Settings) return false
    sessions.refresh()
    return true
}
