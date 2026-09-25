package dev.opencode.android.ui

/**
 * チャット画面の composable が **ViewModel へ配る宛先**(Q7 レビュー minor-3)。
 *
 * ## なぜラムダをやめてオブジェクトにしたか
 *
 * RUN_PLAN の設計規則1は「**宛先はオブジェクトで名指しする。ラムダで受け取らない。**」である。
 * Q7 の1周目はこれを Compose の外(`AppWiring` / `EventDelivery`)にしか適用しておらず、
 * レビューが打った変異 **RD**(`onUnrevert = viewModel::unrevert` を `{ }` にする)は
 * **610件全緑で通り抜けた**。症状は「**『元に戻す』への唯一の入口が無音で死ぬ**」で、
 * 帯は出たまま、押しても何も起きない —— 画面はどこも壊れて見えない。
 *
 * ラムダ引数のままだと、その差し替えは**受け取る composable のテストからは見えない**。
 * 実体で受け取れば、
 *
 *  - **宛先を落とす変異は composable の中にしか書けず**、Compose UI テストが本物のボタンを
 *    押して「この呼び出しが起きたこと」を assert できる
 *  - 呼び出し側で潰すには「全メソッドが空の別オブジェクト」を書く必要があり、
 *    1行の変異にはならない(= レビューが打つ形の変異では通らない)
 *
 * Q6 が触覚で採った「**(b) 振る舞いをコントロール自身の持ち物にする**」と同じ考え方で、
 * **テストが自前の composable ではなく本物のコントロールを押せる**ようにするためのものである。
 *
 * ## ここに置かないもの
 *
 * **画面遷移とシートの開閉は置かない**(`onBackToList` / `onChangeModel`)。
 * それは ViewModel の関心ではなく `AppRoot` が持つ画面状態なので、
 * ここへ入れると「ViewModel が画面遷移を知っている」構造になる。
 * 閉じられない配線として報告に列挙する。
 *
 * 実装は [AppViewModel]。
 */
interface ChatActions {
    /** `POST /session/{id}/unrevert`。**帯の「元に戻す」から呼ばれる唯一の経路。** */
    fun unrevert()

    /** 履歴の再取得(帯の「再試行」/ 空状態の「再試行」)。 */
    fun retryLoadMessages()

    /** 帯を閉じる。どの種類が閉じられるかは [ChatController.dismissBanner] が決める。 */
    fun dismissChatBanner(kind: ChatBannerKind)

    /** 「N ファイル変更」チップ → `GET /session/{id}/diff?messageID=`。 */
    fun openMessageDiff(sessionId: String, messageId: String, knownFiles: List<String>)

    /**
     * 長押し「ここまで戻す」→ **確認ダイアログを出すだけ**。
     * ここから `POST /revert` は飛ばない([ChatController.requestRevert])。
     */
    fun requestRevert(messageId: String, partId: String?, preview: String)

    /**
     * Q8 スコープ6: ツール活動カードのファイルパス → **そのファイルをビューアで開く**。
     *
     * `ChatActions` に置いたのは、押されるのがチャットの面だからである
     * ([FileActions] はファイル画面の面)。**画面遷移は起こさない** ——
     * ビューアはダイアログなので、チャットの上にそのまま開く。
     */
    fun openFileFromChat(path: String)
}
