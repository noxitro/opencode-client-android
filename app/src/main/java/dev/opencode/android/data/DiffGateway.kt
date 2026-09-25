package dev.opencode.android.data

/**
 * 差分表示と VCS が必要とするサーバー操作の口(Q7)。
 *
 * **なぜインターフェイスを切るのか**([SessionsGateway] / [ChatGateway] / [CatalogGateway]
 * と同じ理由): 「ブランチは一度取れたら二度引かない」「接続先が変わったら捨てる」
 * 「展開したときにだけハンクを組む」は状態機械であって純関数に切り出せない。
 * RUN_PLAN「検出器の穴という欠陥形」以降、このプロジェクトは
 * **状態遷移そのものに検出器を置く**ことを規則にしている。
 *
 * ここを差し替え可能にして [dev.opencode.android.ui.DiffController] を `runTest` から
 * 直接叩く。実装は [DiffRepository]。
 *
 * **`POST /vcs/apply` はこの口に無い。** QUALITY_PLAN §5b が明示的に除外している ——
 * 端末からの任意パッチ適用は入力手段が無く、誤爆の被害がリポジトリ全体に及ぶ。
 * 「使わないが口だけ開けておく」をしないのは、開いた口は必ず誰かが繋ぐからである。
 */
interface DiffGateway {
    /** 接続先が設定済みか。未設定なら呼び出し側は通信を試みない。 */
    val isConfigured: Boolean

    /**
     * `GET /vcs`。**推論を伴わないので 402 の環境でも測れる**
     * (Q4 の「402 で測れないものと測れるものを混ぜない」の Q7 版)。
     */
    suspend fun vcsInfo(directory: String? = null): ApiResult<VcsInfoDto>

    /** `GET /vcs/status`。作業ツリーの変更ファイル一覧(patch を持たない軽い口)。 */
    suspend fun vcsStatus(directory: String? = null): ApiResult<List<VcsFileStatusDto>>

    /**
     * `GET /vcs/diff?mode=…&context=…`。**`mode` は required**(実測: 省略で 400)。
     * `context` を渡さないとサーバー既定でファイル全体が返る(実測)。
     */
    suspend fun vcsDiff(mode: String, context: Int?, directory: String? = null): ApiResult<List<VcsFileDiffDto>>

    /** `GET /session/{sessionID}/diff?messageID=`。実測では常に `[]`(402 で変更が起きないため)。 */
    suspend fun sessionDiff(
        sessionId: String,
        messageId: String?,
        directory: String? = null,
    ): ApiResult<List<SnapshotFileDiffDto>>
}
/**
 * `directory` クエリの既定値。**null = サーバーの cwd を使う**(現在の唯一の使い方)。
 *
 * QUALITY_PLAN §6 は「**Q7〜Q9 の全エンドポイントが `directory` クエリを取るため、
 * データ層は最初から `directory` を通せる形にしておくこと(UIは出さない)**」と定めている。
 * Q7 の1周目はこれを落としており、レビューが minor-5 として指摘した。
 *
 * **UI は出さない。** 出す/出さないは Q8 以降の判断で、ここが用意するのは経路だけである。
 * 経路が無いと Q8 は口の形から作り直すことになる。
 */
val DEFAULT_DIRECTORY: String? = null

/**
 * `GET /vcs/diff` に渡す文脈行数(Q7)。
 *
 * **既定に任せない。** 実測でサーバー既定は「ファイル全体を1ハンクにする」に近く
 * (57行のファイルが `@@ -1,57 +1,57 @@` になった)、1行の変更でもファイル全部が
 * 端末へ来る。3 は `git diff` の既定と同じで、実測で
 * `@@ -1,5 +1,5 @@` / `@@ -50,7 +50,7 @@ def f17():` の2ハンクに分かれることを確認している。
 */
const val VCS_DIFF_CONTEXT = 3
