package dev.opencode.android.data

/**
 * ファイルブラウザと検索が必要とするサーバー操作の口(Q8)。
 *
 * **なぜインターフェイスを切るのか**([SessionsGateway] / [ChatGateway] / [CatalogGateway] /
 * [DiffGateway] と同じ理由): 「1階層ずつ展開する」「パンくずで上へ戻る」
 * 「検索語をデバウンスして最後の1本だけ撃つ」「空だったら校正クエリをもう1本撃つ」は
 * **状態機械であって純関数に切り出せない**。RUN_PLAN「検出器の穴という欠陥形」以降、
 * このプロジェクトは**状態遷移そのものに検出器を置く**ことを規則にしている。
 *
 * ここを差し替え可能にして [dev.opencode.android.ui.FileBrowserController] を
 * `runTest` から直接叩く。実装は [FilesRepository]。
 *
 * ## `fileStatus` がこの口に**在る**のに画面が使わない理由
 *
 * `GET /file/status` は 1.18.21 では**変更があっても `[]` を返す**(実測。同時刻の
 * `GET /vcs/status` は5件返した)。200 が返るので、応答からは
 * 「変更が無い」と「この口が機能していない」が区別できない。
 *
 * **口を消さずに残し、使わないと決めた**のは、消すと次に触る人が
 * 「計画書に書いてあるのに実装が無い」を欠落と読んで**もう一度同じ調査をする**からである。
 * 変更ファイルの権威は `GET /vcs/status`([DiffGateway.vcsStatus])。
 */
interface FilesGateway {
    /** 接続先が設定済みか。未設定なら呼び出し側は通信を試みない。 */
    val isConfigured: Boolean

    /**
     * `GET /file?path=`。**`path` は required**(省略すると 400)。ルートは `"."`。
     * 返る `path` はサーバーOSの区切りで、ディレクトリは末尾に区切りが付く。
     */
    suspend fun listFiles(path: String, directory: String? = null): ApiResult<List<FileNodeDto>>

    /**
     * `GET /file/content?path=`。**受信は [FILE_CONTENT_MAX_BYTES] で打ち切られる**。
     * 打ち切りは [FileContentPayload.truncated] で伝わる(黙って切らない)。
     */
    suspend fun readFile(path: String, directory: String? = null): ApiResult<FileContentPayload>

    /** `GET /file/status`。**上の doc の通り、画面はこれを判断材料にしない。** */
    suspend fun fileStatus(directory: String? = null): ApiResult<List<FileStatusDto>>

    /** `GET /find?pattern=`。**サーバーが [FIND_SERVER_CAP] 件で打ち切る**(増やす口が無い)。 */
    suspend fun findText(pattern: String, directory: String? = null): ApiResult<List<FindMatchDto>>

    /** `GET /find/file?query=&limit=`。`limit` は 1〜200(超えると 400)。 */
    suspend fun findFiles(
        query: String,
        limit: Int = FIND_FILE_LIMIT,
        directory: String? = null,
    ): ApiResult<List<String>>

    /** `GET /find/symbol?query=`。**LSP が無い環境では常に `[]`**(区別は Controller が付ける)。 */
    suspend fun findSymbols(query: String, directory: String? = null): ApiResult<List<SymbolDto>>
}
