package dev.opencode.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * GET /event (SSE) 接続を**1本だけ**保持する(QUALITY_PLAN §5 Q0 スコープ3)。
 *
 * **「1本だけ」の正確な範囲**(2026-08-27、Q1着手条件の裁定・決定1で確定):
 * **プロセスがフォアグラウンドにある間は常時1本で、アプリ内の画面の出入りでは切らない。**
 * プロセスがバックグラウンドへ回ると OS 側(Doze/ネットワーク制限)で実際に切れ、
 * 復帰時に再接続する — これは Q0 のE2Eで実測されている(所見Q0-2: バックグラウンドで
 * サーバー側 `sseClients` が 0 に落ち、復帰で「再接続中…」を経て戻る)。
 * **「アプリ起動中は常時1本」ではない。** 以前この doc はそう書いていたが、満たしていない。
 *
 * この範囲で足りると判断した理由(決定1): 一覧の実行状態バッジが要求するのは
 * 「一覧を見ている間、状態が正しいこと」であり、見ていない間の更新は要らない。
 * バックグラウンドで接続を保つのは電池と Doze の両方に逆らう。
 *
 * ただし**切れている間のイベントは失われる**ので、状態はイベント列だけでは再構成できない。
 * 購読側は再接続のたびに `GET /session/status` を取り直すこと(決定2)。
 * その合図が [connectedGeneration]。
 *
 * これ以前は ViewModel がチャット入室で購読を開始し退室で切っていた。Q1の一覧が
 * 常時の `session.status` を要求するため、接続のライフサイクルを画面から切り離し、
 * [scope](アプリ生存中のスコープ)へ移した。
 * 接続先([ConnectionRepository.connection])が変わったときだけ張り直す。
 *
 * 再接続・バックオフ・順序保持は既存の [OpenCodeEvents] をそのまま使う(P3で実測済みの経路を
 * 変えない)。この型はその上に「1本だけ」「接続先変更で張り直す」を足す。
 *
 * **なぜイベントが StateFlow ではなく SharedFlow なのか**: StateFlow は conflate するため、
 * 購読者が遅れた分の `message.part.updated` を捨てる。捨てられた分は逐次描画から
 * 欠落する(P3ゲート①の退行)。イベントは「最新値」ではなく「列」なので、
 * バッファ付き SharedFlow で全件・順序どおりに配る。
 * **接続状態は最新値だけが意味を持つ**ので、そちらは StateFlow。
 */
class OpenCodeEventStream(
    private val connection: ConnectionRepository,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<SseEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        // SUSPEND が保証するのは「**登録済みの購読者に対しては**バッファ溢れで捨てない」ことだけ。
        // 購読者がゼロのとき MutableSharedFlow の emit は待たずに**そのまま捨てる**。
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * 全イベント。**購読者がゼロの間に届いたイベントは失われる**(replay=0)。
     * 失う窓は2つ:
     *   (a) [AppContainer] 生成 〜 ViewModel が collect を始めるまで
     *   (b) ViewModel が onCleared されてから次の ViewModel が collect を始めるまで
     *
     * 現状は活性セッション外のイベントをどのみち捨てているので実害が無いが、
     * **replay を持たせて塞ぐことは選ばなかった**: replay は新しい購読者に過去イベントを
     * 配り直すので、回転で作り直された ViewModel に `session.idle` や `permission.asked` が
     * 再生され、解決済みのダイアログが蘇る/busyが巻き戻る。取りこぼしより悪い。
     *
     * Q1 の一覧はこの窓を replay ではなく **`GET /session/status` の取得**で埋めている
     * (状態はイベント列ではなくサーバーが持つ現在値で初期化する。決定2)。
     * 取り直す契機は購読開始時と [connectedGeneration] の増加時の2つ。
     */
    val events: SharedFlow<SseEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow<OpenCodeEvents.Status?>(null)

    /** 接続状態。null=未接続(接続先未設定)。 */
    val status: StateFlow<OpenCodeEvents.Status?> = _status.asStateFlow()

    private val _connectedGeneration = MutableStateFlow(0)

    /**
     * **接続が確立するたびに 1 増える世代番号**(初期値0=まだ一度も繋がっていない)。
     *
     * 「再接続したこと」を購読側へ伝えるための唯一の合図。[status] を見て
     * `CONNECTED` を待つのではこれができない — StateFlow は同値を再放出しないので、
     * `CONNECTED → (切断) → RECONNECTING → CONNECTED` の往復は拾えても、
     * 購読側が後から collect を始めた場合に「今の CONNECTED は何回目か」が分からない。
     * 世代番号なら、購読側は「前回取得した世代と違えば取り直す」だけで済む。
     *
     * 用途(決定2): 一覧はこれが増えるたびに `GET /session/status` を取り直す。
     * 切れている間の `session.status` イベントは失われており、イベント列だけでは
     * 状態が古いまま固まるため。
     */
    val connectedGeneration: StateFlow<Int> = _connectedGeneration.asStateFlow()

    private var current: OpenCodeEvents? = null
    private var started = false
    private var supervisor: Job? = null

    /**
     * 接続の維持を開始する。多重呼び出しは無視される(collectは1本)。
     * 接続先が未設定の間は接続を張らず、設定された時点で自動的に張る。
     */
    fun start() {
        if (started) return
        started = true
        supervisor = scope.launch {
            // StateFlow は同値を再放出しないので、接続先が変わったときだけこの本体が走る。
            connection.connection.collect { conn ->
                current?.stop()
                current = null
                if (conn == null) {
                    _status.value = null
                    return@collect
                }
                val stream = OpenCodeEvents(
                    baseUrl = conn.baseUrl,
                    password = conn.password,
                    scope = scope,
                    onEvent = { event -> _events.emit(event) },
                    onStatus = { status ->
                        // 接続が「確立した瞬間」だけ世代を進める。CONNECTED が連続して
                        // 報告されても(同値なので)ここは1回しか通らない。
                        if (status == OpenCodeEvents.Status.CONNECTED &&
                            _status.value != OpenCodeEvents.Status.CONNECTED
                        ) {
                            _connectedGeneration.value += 1
                        }
                        _status.value = status
                    },
                )
                current = stream
                stream.start()
            }
        }
    }

    /**
     * 接続を止める。**接続先の監視ごと止める**ので、以後は接続先を変えても張り直さない。
     * 再開するには [start] を呼び直す。
     *
     * 誰が呼ぶのか(RUN_PLAN「Q1着手条件の裁定」決定1・決定3の答え):
     * - 通常運転では**誰も呼ばない**。プロセスがフォアグラウンドにある間は1本を保つのが方針で、
     *   バックグラウンド化での切断は OS 側が行う(アプリが能動的に切る設計にはしない)。
     * - 認証失敗(401/403)での停止は [OpenCodeEvents] の再試行ループ内で完結する
     *   ([OpenCodeEvents.Status.UNAUTHORIZED])。接続先が変われば新しいインスタンスが張られる。
     * - **それでも停止経路を用意するのは、「止める手段がプロセス終了しか無い」状態を
     *   残さないため**(Q0レビュー minor-2)。テストと将来のライフサイクル制御の口。
     */
    fun stop() {
        supervisor?.cancel()
        supervisor = null
        current?.stop()
        current = null
        started = false
        _status.value = null
    }
}
