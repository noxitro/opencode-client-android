package dev.opencode.android.data

/**
 * セッション一覧が必要とするサーバー操作の口。
 *
 * **なぜインターフェイスを切るのか**: Q1 のページング状態機械とイベント振り分けは
 * 純関数に切り出せない(状態を持ち、通信の結果で分岐する)。Q1 レビューは、まさに
 * その部分にテストが1本も無いことを変異2本で示した — `canLoadMore` の比較演算子を
 * `>=` から `>` に変えても、一覧イベントの振り分けを殺しても、**102件のテストは
 * 全緑のまま通り抜けた**。どちらも画面は正常に見えるまま R2 と Q1 の中核が死ぬ。
 *
 * ここを差し替え可能にして、[dev.opencode.android.ui.SessionListController] を
 * `runTest` で直接殴れるようにする。実装は [SessionsRepository]。
 */
interface SessionsGateway {
    /** 接続先が設定済みか。未設定なら呼び出し側は通信を試みない。 */
    val isConfigured: Boolean

    suspend fun listSessions(limit: Int? = null, search: String? = null): ApiResult<List<SessionDto>>

    suspend fun sessionStatus(): ApiResult<Map<String, SessionStatusDto>>

    /**
     * `POST /session {title, agent?, model?}`。
     * Q4 で `agent` / `model` を足した(実測: 返る `Session` に両方反映される)。
     * **未指定は送らない** —— `null` を明示すると 400 BadRequest になる(API_CONTRACT.md)。
     */
    suspend fun createSession(
        title: String,
        agent: String? = null,
        model: ModelRefDto? = null,
    ): ApiResult<SessionDto>

    suspend fun renameSession(sessionId: String, title: String): ApiResult<SessionDto>

    suspend fun deleteSession(sessionId: String): ApiResult<Unit>
}
