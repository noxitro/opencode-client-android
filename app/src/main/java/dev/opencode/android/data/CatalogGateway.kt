package dev.opencode.android.data

/**
 * モデル/エージェントのカタログを取る口(Q4)。
 *
 * **なぜインターフェイスを切るのか**([SessionsGateway] / [ChatGateway] と同じ理由):
 * カタログの取得は「一度だけ引いてキャッシュし、失敗したら次に開いたときに引き直す」という
 * 状態機械であり、純関数に切り出せない。Q1 のレビューが「切り出せなかった部分に
 * テストが1本も無い」を変異2本で示して以来、このプロジェクトは
 * **状態遷移そのものに検出器を置く**ことを規則にしている(RUN_PLAN「検出器の穴という欠陥形」)。
 *
 * ここを差し替え可能にして [dev.opencode.android.ui.ModelCatalogController] を
 * `runTest` で直接叩けるようにする。実装は [CatalogRepository]。
 */
interface CatalogGateway {
    /** 接続先が設定済みか。未設定なら呼び出し側は通信を試みない。 */
    val isConfigured: Boolean

    /**
     * `GET /provider`。**実測 5.4 MiB / 203プロバイダ / 7,338モデル**(API_CONTRACT.md)。
     * 呼ぶのは「モデル選択を開いたとき」だけにすること。
     */
    suspend fun listProviders(): ApiResult<ProvidersDto>

    /** `GET /agent`。実測 17件 / 80 KB。 */
    suspend fun listAgents(): ApiResult<List<AgentDto>>
}
