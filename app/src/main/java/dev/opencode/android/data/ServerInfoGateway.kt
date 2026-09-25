package dev.opencode.android.data

/**
 * サーバーの素性(`GET /global/health`)を取る口(Q5)。
 *
 * **なぜインターフェイスを切るのか**([SessionsGateway] / [CatalogGateway] と同じ理由):
 * 「接続先が変わったら捨てて引き直す」「一度取れたら二度引かない」は状態機械であり、
 * 純関数に切り出せない。RUN_PLAN「検出器の穴という欠陥形」以降、このプロジェクトは
 * **状態遷移そのものに検出器を置く**ことを規則にしている。
 *
 * `GET /global/health` は**推論を伴わない**ので実物 serve でも測れる(402 にならない)。
 * 実装は [ConnectionRepository]。
 */
interface ServerInfoGateway {
    /** 接続先が設定済みか。未設定なら呼び出し側は通信を試みない。 */
    val isConfigured: Boolean

    suspend fun health(): ApiResult<HealthDto>
}

/**
 * 接続設定の保存+疎通テストが必要とする口(Q5 レビュー major-2 / R1)。
 *
 * **なぜインターフェイスを切るのか**: 「入力URLを正規化して保存し、その接続先を測る」は
 * `AppViewModel` に素で書かれており、レビューが `Urls.normalize` の呼び出しを外す変異を
 * 打ったところ **431件が全緑のまま通り抜けた**。関数のテストは「関数が正しい」ことしか
 * 主張できず、「**アプリがその関数を通る**」は別の主張である。
 *
 * ここを差し替え可能にして [dev.opencode.android.ui.performSaveAndTestHealth] を
 * `runTest` から叩き、**保存された文字列が正規化済みであること**を assert できるようにする。
 * 実装は [ConnectionRepository]。
 */
interface ConnectionSetupGateway {
    /** 設定保存。`baseUrl` は**正規化済み**であること(Urls.normalize を通った値)。 */
    suspend fun save(baseUrl: String, password: String)

    suspend fun health(): ApiResult<HealthDto>
}
