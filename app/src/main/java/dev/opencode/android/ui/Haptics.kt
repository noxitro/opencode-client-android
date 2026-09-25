package dev.opencode.android.ui

/**
 * 触覚フィードバックを出す操作の種類(§5 Q5 スコープ2「送信/permission応答等の操作時」)。
 *
 * enum にしてあるのは**テストが同一性を主張するため**。「振動したか」は実機でも測りにくいが、
 * 「どの操作でゲートを通ったか」は列として突き合わせられる。
 */
enum class HapticEvent {
    /** プロンプト送信。 */
    SEND,

    /** permission ダイアログの応答(once/always/reject)。 */
    PERMISSION_REPLY,

    /** question カードの送信/拒否。 */
    QUESTION_REPLY,

    /** セッション新規作成。 */
    SESSION_CREATE,

    /** 実行中断(abort)。 */
    ABORT,
}

/**
 * 触覚を出すかどうかの**唯一の所在**(§5 Q5 のゲート「OFF のとき実際に呼ばれないこと」)。
 *
 * ## なぜクラスにするのか
 *
 * `if (hapticsEnabled) haptics.performHapticFeedback(...)` を呼び出し側5か所に書くと、
 * トグルを見ない1か所が残っても**画面はどこも壊れて見えない**。このプロジェクトが
 * 6度繰り返した「テストは全緑のまま症状が残る」形そのものである
 * (RUN_PLAN「検出器の穴という欠陥形」)。条件を1か所へ集め、
 * **Android にも Compose にも依存しない**素のクラスにして `runTest` から叩けるようにする。
 *
 * 呼び出し側は [perform] を**無条件で**呼ぶこと。呼び出し側に `if` を書かない。
 *
 * @param enabled 現在のトグル。**値ではなく関数で受ける** —— 設定を変えた直後の操作が
 *   古い値で判定されないようにするため(Compose の再合成を待たない)
 * @param sink 実際に端末を鳴らす口。Android 側は `HapticFeedback` を呼ぶ
 * @param observer 判定の結果を**鳴らす鳴らさないに関わらず**受け取る口。
 *   実機ではここが logcat に1行出すので、**トグルの効きをテキスト証跡で引用できる**
 *   (HARNESS「引用できない証拠は、検査したことにならない」)
 */
class HapticGate(
    private val enabled: () -> Boolean,
    private val sink: (HapticEvent) -> Unit,
    private val observer: (HapticEvent, Boolean) -> Unit = { _, _ -> },
) {
    fun perform(event: HapticEvent) {
        val on = enabled()
        observer(event, on)
        if (!on) return
        sink(event)
    }

    /**
     * 現在の判定を**鳴らさずに**読む(Q6)。
     *
     * `perform` は sink を呼ぶので、`rememberHapticGate` が作った本物のゲートに対して
     * 「いま ON か」を知る手段が無かった。Compose テストが
     * 「**同じインスタンスが最新のトグル値を読む**」(= `rememberUpdatedState` が効いている)
     * を主張するために要る。判定の所在は [enabled] のままで、ここは覗き窓にすぎない。
     */
    val isEnabled: Boolean get() = enabled()
}

/** 何もしないゲート(プレビュー・テストの既定)。 */
val NoopHapticGate: HapticGate = HapticGate(enabled = { false }, sink = {})
