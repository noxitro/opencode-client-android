package dev.opencode.android.data

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSource.Factory
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * GET /event (SSE) の購読。okhttp-sse の EventSource を使い、**自前で再試行する**
 * (okhttp-sseは接続断後の自動再接続を持たないため。切断/異常終了ごとに指数バックオフで張り直す)。
 *
 * - イベントは受信順を保って1本のコルーチンで処理する(Channel経由。OkHttpスレッドから直接UIに触らせない)
 * - 個々のイベントのパース失敗・未知typeは破棄する(ストリーム/再試行ループは落とさない)
 * - Basic認証ヘッダをログに出さない(logging interceptorは付けない)
 */
class OpenCodeEvents(
    private val baseUrl: String,
    private val password: String,
    private val scope: CoroutineScope,
    // suspend: 配布先(SharedFlow)がバックプレッシャで待てるようにする。受信そのものは
    // UNLIMITED Channel が受け止めるので、ここで待ってもSSEの読み取りは止まらない。
    private val onEvent: suspend (SseEvent) -> Unit,
    private val onStatus: (Status) -> Unit,
) {
    enum class Status {
        CONNECTING,
        CONNECTED,
        RECONNECTING,

        /**
         * 認証に失敗した(401/403)。**再試行を止めた状態**であり、待てば直る状態ではない。
         *
         * これ以前は 401 でも他の切断と同じ扱いで指数バックオフの再試行を無限に回していた。
         * 誤ったパスワードを保存すると health は「認証エラー(401)」を出す一方で SSE だけが
         * 永久に叩き続ける、という食い違いが起きていた(RUN_PLAN「Q1着手条件の裁定」決定3)。
         * 認証失敗は再試行で解決しないので、止めて状態に出す。
         * 復帰は接続先(パスワード)の再保存 = 新しいインスタンスの生成による。
         */
        UNAUTHORIZED,
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // SSEは長時間無通信でも切らない(死活はサーバー側の心跳コメント/再試行ループ頼み)。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val eventSourceFactory: Factory = EventSources.createFactory(client)

    /** パース済みイベントの受信箱(順序保持)。 */
    private val inbox = Channel<SseEvent>(Channel.UNLIMITED)
    private var loopJob: Job? = null
    private var consumerJob: Job? = null
    private var source: EventSource? = null

    /** 連続失敗回数(onOpen成功で0に戻す)。OkHttpスレッドから触るためAtomic。 */
    private val failures = AtomicInteger(0)

    /** stop() 済み。再利用しない(OkHttpClientを落としているため)。 */
    private var disposed = false

    /**
     * 直近の接続が 401/403 で拒否された。OkHttpのコールバックスレッドから書き、
     * 再試行ループのコルーチンから読むので `@Volatile`。
     */
    @Volatile
    private var authRejected = false

    /** 購読開始。stop()されるまで接続断ごとに再試行を繰り返す。二重startは無視。 */
    fun start() {
        if (disposed) return
        if (loopJob?.isActive == true) return
        failures.set(0)
        authRejected = false
        consumerJob = scope.launch {
            for (event in inbox) onEvent(event)
        }
        loopJob = scope.launch {
            while (isActive) {
                onStatus(if (failures.get() == 0) Status.CONNECTING else Status.RECONNECTING)
                val closed = CompletableDeferred<Unit>()
                source = open(closed)
                closed.await()
                if (!isActive) break
                // 決定3(RUN_PLAN): 401/403 は「一時的な切断」ではない。再試行を止めて状態に出す。
                // ここで break しないと、誤ったパスワードのまま永久にサーバーを叩き続ける。
                if (authRejected) {
                    onStatus(Status.UNAUTHORIZED)
                    break
                }
                // 指数バックオフ: 1s,2s,4s,8s(上限)。onOpen成功でfailuresは0に戻る。
                val n = failures.incrementAndGet()
                delay(1000L shl (n - 1).coerceAtMost(3))
            }
        }
    }

    /**
     * 購読停止。再試行ループも止め、**このインスタンス専用の OkHttpClient も落とす**。
     *
     * 落とさないと、接続先を変えるたびに Dispatcher のスレッドプールと接続プールが
     * 積み上がる(アイドル60秒で回収はされるが、切替のたびに残る)。
     * 落とした後は再利用しない([start] は no-op になる)。呼び出し側
     * ([OpenCodeEventStream])は接続先ごとに新しいインスタンスを作る。
     */
    fun stop() {
        disposed = true
        loopJob?.cancel()
        loopJob = null
        consumerJob?.cancel()
        consumerJob = null
        source?.cancel()
        source = null
        // shutdown() は実行中のコールをキャンセルしない(上の cancel() が担当)。
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        /**
         * 再試行しても解決しないHTTPステータス(認証・認可の失敗)。
         * 純関数にしてあるのはユニットテストから直接叩くため(SSEの実接続を張らずに校正できる)。
         */
        fun isAuthRejection(code: Int): Boolean = code == 401 || code == 403
    }

    private fun open(closed: CompletableDeferred<Unit>): EventSource {
        val request = Request.Builder()
            .url(baseUrl + "/event")
            // 認証: HTTP Basic、ユーザー名既定 "opencode"(API_CONTRACT.md 認証節)
            .header("Authorization", Credentials.basic("opencode", password, Charsets.UTF_8))
            .build()
        return eventSourceFactory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    failures.set(0)
                    authRejected = false
                    onStatus(Status.CONNECTED)
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    // パースはここ(CPUのみ・軽量)で行い、結果だけを順序通道へ渡す。
                    // 失敗時は何も渡さずストリームを落とさない(parseSseEnvelope契約)。
                    parseSseEnvelope(data)?.let { inbox.trySend(it) }
                }

                override fun onClosed(eventSource: EventSource) {
                    closed.complete(Unit)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    // 401/403 は再試行しない(RUN_PLAN 決定3)。判定はここでしかできない —
                    // ループ側には Response が届かないため。
                    if (response != null && isAuthRejection(response.code)) {
                        authRejected = true
                    }
                    closed.complete(Unit)
                }
            },
        )
    }
}
