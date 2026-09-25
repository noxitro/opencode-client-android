package dev.opencode.android.ui

/**
 * ファイルブラウザの composable が **ViewModel へ配る宛先**(Q8)。
 *
 * ## なぜラムダではなくオブジェクトなのか
 *
 * RUN_PLAN の設計規則1「**宛先はオブジェクトで名指しする。ラムダで受け取らない。**」を、
 * Q7 が [ChatActions] で Compose 層へ持ち込んだ形をそのまま踏襲する。
 *
 * Q7 のレビューが打った変異 **RD**(`onUnrevert = viewModel::unrevert` を `{ }` にする)は
 * **610件全緑で通り抜けた** —— 「元に戻す」への唯一の入口が無音で死ぬのに、
 * 帯は出たまま画面はどこも壊れて見えない。ラムダ引数のままだと、その差し替えは
 * **受け取る composable のテストからは見えない**。
 *
 * 実体で受け取れば、
 *
 *  - **宛先を落とす変異は composable の中にしか書けず**、Compose UI テストが
 *    本物のノードを押して「この呼び出しが起きたこと」を assert できる
 *  - 呼び出し側で潰すには「全メソッドが空の別オブジェクト」が要り、**1行の変異にならない**
 *
 * ## ここに置かないもの
 *
 * **画面遷移は置かない**(`onBack` / `onOpenSettings`)。それは `AppRoot` が持つ画面状態で、
 * ここへ入れると「ViewModel が画面遷移を知っている」構造になる([ChatActions] と同じ判断)。
 * 閉じられない配線として報告に列挙する。
 *
 * 実装は [AppViewModel]。
 */
interface FileActions {
    /** ツリーのディレクトリを開く(行のタップ / パンくず)。 */
    fun openDirectory(path: String)

    /** `ignored` の表示を反転する。**通信しない。** */
    fun toggleIgnoredFiles()

    /** 一覧の再取得(空状態の「再試行」)。 */
    fun retryFileTree()

    /**
     * ファイルを開く。[focusLine] は検索結果 / シンボルから来たときの**1始まりの**行番号。
     * チャットのツールカードからもここへ来る(§5b Q8 スコープ6)。
     */
    fun openFile(path: String, focusLine: Int? = null)

    fun closeFileViewer()

    /** ビューアの「再試行」。**同じファイルをもう一度。** */
    fun retryFileViewer()

    /** 「変更あり」トグル。`diff` を持たないときは何も起きない(判定は Controller 側)。 */
    fun toggleFileDiff()

    fun setFileSearchTab(tab: FileSearchTab)

    /** 入力。**撃たない**(デバウンスは Controller 側)。 */
    fun updateFileSearchQuery(query: String)

    /** 検索キー。**デバウンスを待たずに撃つ。** */
    fun submitFileSearch()

    fun clearFileSearch()
}
