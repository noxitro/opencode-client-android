package dev.opencode.android.ui

import dev.opencode.android.data.SseEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * SSEイベントの**配布**(接続ではなく、届いたイベントを状態へ適用する経路)の失敗記録。
 *
 * なぜ状態にするのか: `collect { apply(it) }` は apply が1回でも投げると
 * collector コルーチンごと死ぬ。SSE接続そのものは生きているので接続状態は `CONNECTED` のまま——
 * **「接続中」と出ているのに1文字も増えない**、という一番診断しにくい壊れ方になる。
 * L3 の `jsonPrimitive` 欠陥(エラーを出すための変更がエラーを消していた)と同じ形なので、
 * 握り潰さず「配布が死にかけた」ことを画面から観測できるようにする。
 */
data class EventDeliveryFailure(
    /** 累計失敗回数(1件の失敗で購読が終わらないので積み上がる)。 */
    val count: Int,
    /** 直近の例外の要約(クラス名+メッセージ先頭)。資格情報は含まない経路。 */
    val lastMessage: String,
)

/** 例外の表示用要約。長いメッセージは切る(状態帯の1行に収める)。 */
fun Throwable.summarizeForDelivery(): String {
    val name = this::class.simpleName ?: "Throwable"
    val detail = message?.takeIf { it.isNotBlank() }?.let { if (it.length > 120) it.take(120) + "…" else it }
    return if (detail == null) name else "$name: $detail"
}

/** 既存の失敗記録へ1件足す。 */
fun EventDeliveryFailure?.plus(t: Throwable): EventDeliveryFailure =
    EventDeliveryFailure(count = (this?.count ?: 0) + 1, lastMessage = t.summarizeForDelivery())

/**
 * 1件の適用が投げても collector を殺さない collect。
 *
 * [CancellationException] だけは通す — これはコルーチンの停止機構そのものであり、
 * ここで飲むと ViewModel が破棄されても購読が残る。
 */
suspend fun <T> Flow<T>.collectGuarded(
    onFailure: (Throwable) -> Unit,
    handle: (T) -> Unit,
) {
    collect { value ->
        try {
            handle(value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            onFailure(t)
        }
    }
}

/**
 * SSEイベントを**2つの宛先へ順に無条件で配る**購読。
 *
 * ここが独立した関数になっているのは、Q1 の1周目レビューが打った変異のためである。
 * 当時この配線は ViewModel の中にあり、「一覧へ配る」が
 * `val sid = activeSessionId ?: return` の**手前**に置かれていた。順序は
 * コメントでしか守られておらず、1行動かす変異でテスト102件は全緑のまま
 * **一覧のバッジもタイトル自動更新も死んだ**(= Q1 が直したはずの退行が戻る)。
 * 画面は正常に見えるので、気づく手段が無い。
 *
 * 対策は2つ重ねてある:
 *  1. **早期 return を書ける場所を無くす** — この関数には条件が1つも無い。
 *     「自分に関係あるか」の判断は各宛先の内側に閉じる
 *  2. **この配線自体をテストできる形にする** — ViewModel の中に置いたままでは
 *     Android 抜きで叩けず、検出器を1本も置けなかった
 *
 * [collectGuarded] を使うので、片方の宛先が投げてももう片方と後続イベントは死なない
 * (`onFailure` に記録が残る)。**新しい購読を足すときも同じ扱いにすること。**
 */
suspend fun Flow<SseEvent>.deliverTo(
    onFailure: (Throwable) -> Unit,
    toSessionList: (SseEvent) -> Unit,
    toChat: (SseEvent) -> Unit,
    /**
     * Q9: 3つ目の宛先。**`pty.exited` は終了コードの唯一の出所である**
     * ([dev.opencode.android.data.SseEvent.PtyLifecycle])—— REST は終了した瞬間に
     * 404 になるので、ここを落とすと**終了コードが二度と手に入らない**。
     * 症状は「シェルが終わったのに『接続が切れました』と出続ける」で、
     * クラッシュもエラーも出ない。
     *
     * **実体で受け取る**([deliverConnectionsTo] と同じ理由)。ラムダのままだと
     * 呼び出し側が `{ }` を渡す変異がこの関数のテストからは見えない。
     */
    toPty: PtyController,
) {
    collectGuarded(onFailure) { event ->
        toSessionList(event)
        toChat(event)
        toPty.onEvent(event)
    }
}

/**
 * **再接続**を2つの宛先へ順に配る購読([deliverTo] と同じ理由でここに切り出す)。
 *
 * 切り出した理由: Q2 のレビューが「`AppRoot.kt` の `chatController.onReconnected()` を
 * コメントアウトしても207件全緑で通り抜ける」(W1)を指摘した。ViewModel の中に
 * 直に書いた配線は**テストを置ける場所が無い**ので、宛先を1つ落とす変異が素通しになる。
 *
 * 再接続で何をするかは各宛先の内側に閉じている。ここが持つ条件は1つだけ:
 * **世代0は「まだ一度も繋がっていない」**なので配らない。`connectedGeneration` は
 * 接続が確立するたびに増える単調カウンタで、0 は初期値である。
 *
 * `collectGuarded` を通すので、片方の宛先が投げてももう片方と後続の再接続は死なない。
 */
suspend fun Flow<Int>.deliverConnectionsTo(
    onFailure: (Throwable) -> Unit,
    /**
     * Q8 で**ラムダから実体に変えた**(Q7 申し送り Q7-6)。
     *
     * Q7 は3つ目の宛先([diff])だけを実体で受け取り、既存の2つは
     * `toSessionList: () -> Unit` / `toChat: () -> Unit` のまま残していた ——
     * **同じ関数の中に、検出できる宛先と検出できない宛先が並んでいた**。
     * `toSessionList = { }` を渡す変異はこの関数のテストからは見えず、
     * 症状は「**再接続しても一覧の実行状態バッジが二度と更新されない**」で、
     * 画面はどこも壊れて見えない(RUN_PLAN 決定2 の違反がそのまま戻る)。
     */
    sessions: SessionListController,
    /**
     * チャット。再接続で `GET /session/status` 等を取り直す ——
     * 切れている間に届いた `session.idle` は購読者ゼロで消えており、
     * 取り直さないと**入力欄が永久に disabled のまま**になる(Q2 レビューの差し戻し)。
     */
    chat: ChatController,
    /**
     * Q7: 差分 / VCS。`vcs.*` の SSE イベントは実機 spec に**存在しない**ので、
     * 切れている間のブランチ切替はイベント列から原理的に復元できない。
     */
    diff: DiffController,
    /**
     * Q8: ファイルツリー。`file.*` の SSE イベントも**存在しない** ——
     * 切れている間にエージェントが足したファイルは、イベント列から復元できない。
     * **まだ何も読んでいなければ何もしない**判定は [FileBrowserController.onReconnected] が持つ。
     */
    files: FileBrowserController,
    /**
     * Q9: ターミナル一覧。切れている間の `pty.created` / `pty.exited` は
     * **購読者ゼロで消えている**ので、一覧を引き直さないと
     * 「終わったターミナルが一覧に残り続ける」「他のクライアントが起こしたターミナルが出ない」。
     * **まだ一度も読んでいなければ何もしない**判定は [PtyController.onReconnected] が持つ。
     */
    pty: PtyController,
) {
    collectGuarded(onFailure) { generation ->
        if (generation > 0) {
            sessions.refreshStatus()
            chat.onReconnected()
            diff.onReconnected()
            files.onReconnected()
            pty.onReconnected()
        }
    }
}
