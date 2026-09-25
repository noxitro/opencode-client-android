package dev.opencode.android

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 撃たれた HTTP リクエストを**そのまま控える**テスト用サーバー(Q7 レビュー minor-2)。
 *
 * ## なぜ自前の `ServerSocket` なのか
 *
 * 1. `okhttp3:mockwebserver` はこのプロジェクトの Gradle キャッシュに無く、
 *    **追加するとビルドがネットワークに依存する**。Q6 が Robolectric の
 *    `android-all-instrumented` 取得で同じ債務を「オフライン再現性 → 次期計画」として
 *    残しており、**同じ形の債務をもう1つ作らない**
 * 2. JDK 同梱の `com.sun.net.httpserver` は **Android のユニットテスト classpath に無い**
 *    (実測: `Unresolved reference 'sun'`。`android.jar` スタブが JDK モジュールを覆う)
 *
 * 残るのは `java.net.ServerSocket` で、これは通る。HTTP/1.1 の**この用途に要る分だけ**を実装する
 * (リクエスト行 + ヘッダ + `Content-Length` ぶんのボディ、固定長応答)。
 * チャンク転送も keep-alive も扱わない —— **扱う必要が出たらそれは検出器の仕事が増えた合図**である。
 *
 * ## 何を守るのか
 *
 * レビューの変異 **RB**: `OpenCodeApi` の `?messageID=` を `?messageId=` に変えても
 * **610件が全緑で通り抜けた**。しかも**実物 serve は未知のクエリキーを黙って無視する**
 * (レビュー実測: `?messageId=msg_x` → 200)。症状は
 * 「**メッセージの差分を開いたのにセッション全体の差分が無言で出る**」で、画面は正常に見える。
 *
 * 既存の `ConnectionRepositoryTest` は `OpenCodeApi` の**同一性**しか見ておらず、
 * **撃たれた URL を見張る検出器はこのリポジトリに1本も無かった**。
 *
 * **Q8 が相続する**: `/file` `/find` で URL 組み立てはさらに増える。
 * パスごとに応答を登録するだけで使える形にしてある。
 */
class RecordingHttpServer {

    /** 1リクエスト分の記録。**クエリは生文字列のまま持つ**(キー名の取り違えを見るため)。 */
    data class Recorded(
        val method: String,
        val path: String,
        /** `?` の後ろ。クエリが無ければ null。**デコードしない。** */
        val rawQuery: String?,
        val body: String,
        val authorization: String?,
        /**
         * 受け取ったヘッダ全部(**キーは小文字**)。Q9 で足した。
         *
         * `POST /pty/{id}/connect-token` は **`x-opencode-ticket` が無いと 403** を返す
         * (実測 2026-08-30。spec には載っていない)。ヘッダを控えていないと、
         * その1行を落とす変異が**チケットが取れない = ターミナルが一度も開かない**という
         * 形で残るのに、URL だけを見る検出器からは見えない。
         */
        val headers: Map<String, String> = emptyMap(),
    ) {
        /** `path?query`。テストが1本の文字列で比較できるようにする。 */
        val target: String get() = if (rawQuery == null) path else "$path?$rawQuery"
    }

    private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val responses = mutableMapOf<String, Pair<Int, String>>()

    /** 受け取った順の記録。**別スレッドが書くのでスレッドセーフな器にする。** */
    val recorded = CopyOnWriteArrayList<Recorded>()

    val baseUrl: String get() = "http://127.0.0.1:" + socket.localPort

    @Volatile
    private var running = true

    private val acceptor = thread(isDaemon = true, name = "recording-http") {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            try {
                serve(client)
            } catch (_: Throwable) {
                // 接続が途中で切れてもテスト本体は落とさない(記録は残る)。
            } finally {
                runCatching { client.close() }
            }
        }
    }

    /** パス(クエリ抜き)に対する応答を登録する。登録が無ければ 200 + `[]`。 */
    fun respond(path: String, body: String, status: Int = 200) {
        responses[path] = status to body
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]

        var contentLength = 0
        var authorization: String? = null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) continue
            val name = header.substring(0, colon).trim().lowercase()
            val value = header.substring(colon + 1).trim()
            headers[name] = value
            when (name) {
                "content-length" -> contentLength = value.toIntOrNull() ?: 0
                "authorization" -> authorization = value
            }
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, StandardCharsets.UTF_8)
        } else {
            ""
        }

        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val rawQuery = if (q < 0) null else target.substring(q + 1)
        recorded += Recorded(method, path, rawQuery, body, authorization, headers.toMap())

        val (status, text) = responses[path] ?: (200 to "[]")
        writeResponse(client.getOutputStream(), status, text)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().removeSuffix("\r")
            out.append(c.toChar())
        }
    }

    private fun writeResponse(out: OutputStream, status: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(" X\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            // keep-alive を扱わない(この検出器に要らない)。毎回閉じる。
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    fun close() {
        running = false
        runCatching { socket.close() }
        acceptor.join(2000)
    }

    /** 記録を1件だけ取り出す。**0件や2件以上は失敗**(撃ちすぎ/撃たなすぎを見る)。 */
    fun single(): Recorded {
        check(recorded.size == 1) { "リクエストが ${recorded.size} 件: ${recorded.map { it.target }}" }
        return recorded[0]
    }
}
