package dev.opencode.android.ui

/**
 * チャット画面の**帯を1本にまとめる**(QUALITY_PLAN §5 Q2 スコープ6 /
 * RUN_PLAN「Q2 が引き受ける申し送り」)。
 *
 * Q1 終了時点で帯は3種類が**別々の行に同時に**出ていた:
 * 接続状態帯 / 配布失敗帯([EventDeliveryFailure]) / `session.error` バナー。
 * ここに retry バナーを足すと4本になる。計画書は「欠陥Gのバナーと統合し、
 * `session.error` と retry の両方を同じバナー領域で扱う」と定めているので、
 * **領域を1つにし、出す1本を優先順位で決める**。
 *
 * 優先順位の考え方(上ほど強い):
 *  1. **この画面で何をしても無駄** —— セッションが消えた / 認証が通らない
 *  2. **直前のユーザー操作への返事** —— 送信失敗 / 巻き戻しの失敗(Q7)
 *  3. **サーバー側でこの実行が失敗した** —— `session.error`
 *  4. **まだ続いている** —— retry(カウントダウン)
 *  5. **中断の後始末** —— abort したのに完了通知が来なかった
 *  6. **続いている状態の告知** —— 巻き戻し中(Q7。「元に戻す」への唯一の入口)
 *  7. **診断** —— イベント配布の失敗
 *  8. **一時的** —— 再接続中 / 接続中
 *
 * 1本しか出さないので、**低い方が消えたわけではない**。上を閉じると下が出る。
 * この性質はテストで固定してある(`ChatBannerTest`)。
 */

/** バナーの種類。UI の色とアイコン、そして**テストが同一性を主張する対象**。 */
enum class ChatBannerKind {
    SESSION_DELETED,
    UNAUTHORIZED,
    SEND_ERROR,

    /** Q7: 巻き戻し(revert/unrevert)が失敗した。**直前のユーザー操作への返事**なので上位。 */
    REVERT_ERROR,
    SESSION_ERROR,
    RETRY,
    ABORT_NOTICE,

    /**
     * Q7: このセッションは巻き戻された状態にある。
     * **「元に戻す」への唯一の入口**なので、閉じる導線を持たない。
     */
    REVERTED,
    DELIVERY_FAILURE,
    RECONNECTING,
    CONNECTING,
}

/** バナーの深刻度。色分けだけに使う(判定には使わない)。 */
enum class ChatBannerTone { ERROR, WARNING, INFO }

/**
 * 表示する1本。[text] は本文、[actionLabel]/[dismissible] は導線の有無。
 *
 * [text] を組み立てる側(=この関数)がカウントダウンの秒数も持つ。UI 側で計算させると
 * 「1秒ごとに再計算する場所」がテストの外に出る。
 */
data class ChatBanner(
    val kind: ChatBannerKind,
    val tone: ChatBannerTone,
    val text: String,
    val actionLabel: String? = null,
    val dismissible: Boolean = false,
    /**
     * Q4(§5 Q4 スコープ5): 「モデルを変更」の導線。null なら出さない。
     *
     * **帯を増やしていない。** 帯は1本のままで、その1本が持つ導線が1つ増えただけである。
     * R3 の中身はここ —— P5 で「モデルが落ちている」と「そのモデルが落ちている」を
     * 区別できなかったとき、**ユーザーができることが「再試行」しかなかった**。
     * 再試行しか無い画面は、同じモデルを何度も叩かせる。
     */
    val modelActionLabel: String? = null,
)

/**
 * このエラーは**モデルを変えるべき**か(Q4 / R3)。
 *
 * 判定材料は実測した2つ(API_CONTRACT.md「AssistantMessage のメタ」):
 *  - `error.data.isRetryable` —— 402 のとき実測で `false` だった
 *  - `error.data.statusCode` —— 認証・課金・不在は再試行で解決しない
 *
 * **`retryable == null` は「分からない」であって「再試行するな」ではない。**
 * 分からないときは再試行も残す。片方に倒すと、一時的な失敗で
 * 「モデルを変えろ」と言い続けるか、恒久的な失敗で「再試行」しか出さないかのどちらかになる。
 * 後者が P5 で実際に起きたことである。
 */
fun errorSuggestsModelChange(statusCode: Int?, retryable: Boolean?): Boolean =
    retryable == false || statusCode in setOf(401, 402, 403, 404)

/** `session.status` の retry。spec の required(`attempt` `message` `next`)をそのまま持つ。 */
data class RetryState(
    val attempt: Int?,
    val message: String?,
    /** 次の試行時刻(エポックミリ秒)。 */
    val next: Long?,
    /** `retry.action`(任意)。required は reason/provider/title/message/label。 */
    val actionTitle: String? = null,
    val actionLabel: String? = null,
    val actionMessage: String? = null,
)

/**
 * retry バナーの本文。「N秒後に再試行(M回目)」。
 *
 * [next] が無い/過去なら秒数を出さない。**負の秒数を出さない**のが要点で、
 * `next` は「その時刻に再試行する」なので過ぎていれば残りは 0 である。
 */
fun retryBannerText(retry: RetryState, nowMs: Long): String {
    val seconds = retry.next?.let { next ->
        val remaining = next - nowMs
        if (remaining <= 0L) 0L else (remaining + 999L) / 1000L
    }
    val head = when {
        seconds == null -> "再試行中"
        seconds == 0L -> "まもなく再試行"
        else -> "${seconds}秒後に再試行"
    }
    val attempt = retry.attempt?.let { "(${it}回目)" }.orEmpty()
    // action.title があればそちらが「なぜ再試行しているか」を人の言葉で持っている(spec: required)。
    val detail = retry.actionTitle?.takeIf { it.isNotBlank() }
        ?: retry.message?.takeIf { it.isNotBlank() }
    return listOfNotNull("$head$attempt", detail).joinToString(": ")
}

/**
 * 帯1本を選ぶ。**この関数だけが優先順位を知っている。**
 *
 * 引数を状態オブジェクトごと受けないのは、UI が「今の時刻」を毎秒差し替えて呼ぶため。
 */
fun selectChatBanner(state: ChatUi, nowMs: Long): ChatBanner? = when {
    state.sessionDeleted -> ChatBanner(
        kind = ChatBannerKind.SESSION_DELETED,
        tone = ChatBannerTone.ERROR,
        text = "このセッションはサーバー上で削除されました",
        actionLabel = "一覧へ戻る",
    )

    state.eventStatus == ChatEventStatus.UNAUTHORIZED -> ChatBanner(
        kind = ChatBannerKind.UNAUTHORIZED,
        tone = ChatBannerTone.ERROR,
        text = "⛔ 認証エラー(401/403)でイベントストリームを停止しました。接続設定を確認してください",
    )

    state.sendError != null -> ChatBanner(
        kind = ChatBannerKind.SEND_ERROR,
        tone = ChatBannerTone.ERROR,
        text = state.sendError,
        dismissible = true,
    )

    state.revertError != null -> ChatBanner(
        kind = ChatBannerKind.REVERT_ERROR,
        tone = ChatBannerTone.ERROR,
        text = state.revertError,
        dismissible = true,
    )

    state.sessionError != null -> {
        // **モデルを変えるべき失敗では「再試行」を出さない**(R3)。
        // 402 で「再試行」だけを出し続けたのが P5 で観測された行き止まりそのものである。
        val changeModel = errorSuggestsModelChange(state.sessionErrorStatusCode, state.sessionErrorRetryable)
        ChatBanner(
            kind = ChatBannerKind.SESSION_ERROR,
            tone = ChatBannerTone.ERROR,
            text = state.sessionError,
            actionLabel = if (changeModel) null else "再試行",
            dismissible = true,
            // モデル切替が効くことは実測済み(API_CONTRACT.md 実機検証 #2)なので、
            // セッションエラーには常に出す。**押した先で何を選べるかは別の層の関心**。
            modelActionLabel = "モデルを変更",
        )
    }

    state.retry != null -> ChatBanner(
        kind = ChatBannerKind.RETRY,
        tone = ChatBannerTone.WARNING,
        text = retryBannerText(state.retry, nowMs),
        dismissible = false,
    )

    state.abortNotice != null -> ChatBanner(
        kind = ChatBannerKind.ABORT_NOTICE,
        tone = ChatBannerTone.WARNING,
        text = state.abortNotice,
        dismissible = true,
    )

    // Q7 スコープ6: 巻き戻しは**続いている状態**であって一度きりの通知ではない。
    // したがって「一定時間」ではなく **`Session.revert` がサーバーに在る間**出す ——
    // 時間で消すと、消えた後に「元に戻す」へ辿り着く経路が画面に1つも無くなる。
    // 権威がサーバーなので、他クライアントが unrevert すれば次の取り直しで消える。
    state.revertedMessageId != null -> ChatBanner(
        kind = ChatBannerKind.REVERTED,
        tone = ChatBannerTone.WARNING,
        text = "このセッションを巻き戻しました(ファイルが書き換わっています)",
        actionLabel = "元に戻す",
        dismissible = false,
    )

    // 「接続中と出ているのに1文字も増えない」を観測可能にするための帯(Q0)。
    // 寿命(RUN_PLAN が Q2 に設計を要求した点): **閉じるまで残す + 再接続で消す**。
    // 累計回数は「一度でも壊れた」を保存するためのもので、閉じる操作は
    // 「読んだ」という意思表示、再接続は「今の接続では起きていない」という新しい事実である。
    state.eventDeliveryFailure != null -> ChatBanner(
        kind = ChatBannerKind.DELIVERY_FAILURE,
        tone = ChatBannerTone.WARNING,
        text = "⚠ イベント適用に失敗 ${state.eventDeliveryFailure.count}件: ${state.eventDeliveryFailure.lastMessage}",
        dismissible = true,
    )

    state.eventStatus == ChatEventStatus.RECONNECTING -> ChatBanner(
        kind = ChatBannerKind.RECONNECTING,
        tone = ChatBannerTone.WARNING,
        text = "⚠ イベントストリーム再接続中…",
    )

    state.eventStatus == ChatEventStatus.CONNECTING -> ChatBanner(
        kind = ChatBannerKind.CONNECTING,
        tone = ChatBannerTone.INFO,
        text = "イベントストリーム接続中…",
    )

    else -> null
}
