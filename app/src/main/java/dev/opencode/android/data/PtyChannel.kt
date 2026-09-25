package dev.opencode.android.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * PTY の WebSocket 経路(Q9)。**このフェーズだけ通信方式が違う**(QUALITY_PLAN §5b Q9)。
 *
 * ## 実機で確定した接続シーケンス(2026-08-30、実物 serve 1.18.21)
 *
 * ```
 * POST /pty {"command":"cmd.exe","args":[],"title":"probe"}
 *   -> 200 {"id":"pty_…","title":…,"command":…,"args":[],"cwd":…,"status":"running","pid":58556}
 *
 * POST /pty/{id}/connect-token                        -> 403 PtyForbiddenError
 * POST /pty/{id}/connect-token  x-opencode-ticket: 1  -> 200 {"ticket":"…","expires_in":60}
 *
 * ws://HOST/pty/{id}/connect?ticket=<ticket>[&cursor=N]   -> 101
 * ```
 *
 * 計画書 §5b の「実機検証タスク」への答えを、測った順に:
 *
 * | 問い | 実測 |
 * |---|---|
 * | チケットの寿命・使い回し | `expires_in: 60`。**単回使用**(同じチケットの2本目は接続失敗) |
 * | 本当に WebSocket か | **101 Switching Protocols**。SSE でもロングポーリングでもない |
 * | 入力の送り方 | **同じ WebSocket に生の端末入力を書く**。別エンドポイントは無い |
 * | `cursor` の意味 | 出力ストリーム先頭からの**累積バイト数**。省略/0=全再送、-1=ライブのみ |
 * | リサイズの経路 | **`PUT /pty/{id}` の body `{size:{rows,cols}}`**。WS 経由ではない |
 *
 * ## 指示と食い違った実測(報告に併記した)
 *
 * 1. **「チケットは `directory` にバインドされる」は再現しなかった。**
 *    発行時に `?directory=` を付けて接続時に付けない/その逆/両方付ける、の3通りとも
 *    **101 で開いた**。したがってアプリは揃えるが、揃えないと失敗するとは書けない。
 * 2. **「終了状態は v2 `/api/pty/{id}` で取れる」は誤り。** v1 と v2 は**別の登録簿**である:
 *    `POST /pty` で作った PTY は走っている間も `GET /api/pty` に載らず
 *    `GET /api/pty/{id}` は 404、`POST /api/pty` で作った PTY は `GET /pty` に載らない(実測)。
 * 3. **「終了済み PTY は v2 の一覧に残り続ける」も誤り。** 終了すると両系統から消え、
 *    `DELETE` すら 404 になる。**終了コードの唯一の出所は SSE `pty.exited`**
 *    ([SseEvent.PtyLifecycle])。
 */

/** `connect-token` に必須のヘッダ名(実測。spec には載っていない)。 */
const val PTY_TICKET_HEADER = "x-opencode-ticket"

/** 同ヘッダの値。サーバーは**存在だけ**を見ている(実測では `1` で通る)。 */
const val PTY_TICKET_HEADER_VALUE = "1"

/**
 * メタフレームの先頭バイト。**これが判別規則の全部である。**
 *
 * 実測: バイナリフレームで先頭が `0x00` なら、残りは JSON(`{"cursor":223}`)。
 * それ以外(テキストフレーム、または先頭が `0x00` でないバイナリ)は生の PTY 出力。
 */
const val PTY_META_PREFIX: Byte = 0x00

/**
 * **リプレイ無し**で繋ぐときの `cursor`(実測: `-1` でライブのみ)。
 * `0` や省略は「先頭から全部再送」なので、**同じ意味に使わないこと**。
 */
const val PTY_CURSOR_LIVE_ONLY = -1L

/**
 * WebSocket の1フレームを何と読んだか。
 *
 * **種別で判別する。位置で判別しない。** 実測でフレームの順序は固定ではない:
 *
 * ```
 * 接続直後(リプレイ有り) : [text 223B の本文] → [binary 00 {"cursor":223}]
 * 接続直後(cursor=-1)    : [binary 00 {"cursor":301}] → [text 342B の本文]
 * ```
 *
 * 「先頭フレームはメタ」と決め打つ実装は、**リプレイが在る接続でだけ本文をメタとして捨てる**。
 * (2026-08-30 に測り直した3通りの順序は `e2e-artifacts/Q9/ptyprobe-output.txt` §3/§5/§6。)
 *
 * **これは「何のフレームか」の話に限る。** 再開位置の算術([advancePtyCursor])は
 * 順序に依存する —— そちらの doc に実測と前提を書いてある。
 */
sealed interface PtyFrame {
    /**
     * 生の端末出力。[byteLength] は**カーソル計算に使う UTF-8 バイト数**であり、
     * [text] の文字数ではない(日本語を含む出力で1文字3バイトになる)。
     */
    data class Output(val text: String, val byteLength: Int) : PtyFrame

    /** メタ情報。[cursor] は**このフレーム時点での**出力ストリームの累積バイト数。 */
    data class Meta(val cursor: Long) : PtyFrame

    /**
     * メタのはずが JSON として読めなかった。**捨てて本文として描かない** ——
     * `{"cursor":…}` の生バイトが端末画面に出るのは、読めなかったことを隠す描画である。
     */
    data class UnreadableMeta(val reason: String) : PtyFrame
}

/**
 * テキストフレームの判別。**テキストフレームは必ず出力である**(メタはバイナリで来る)。
 *
 * バイト数は UTF-8 で数える。WebSocket のテキストフレームは UTF-8 で運ばれるので、
 * デコード後の文字列を UTF-8 へ戻した長さが、サーバーが数えているバイト数と一致する。
 */
fun classifyPtyTextFrame(text: String): PtyFrame =
    PtyFrame.Output(text, text.toByteArray(Charsets.UTF_8).size)

/**
 * バイナリフレームの判別。**先頭バイトが [PTY_META_PREFIX] のときだけメタ。**
 *
 * 空フレームは出力(0バイト)として扱う —— メタと読むと `cursor` が読めず
 * [PtyFrame.UnreadableMeta] が積み上がる。
 */
fun classifyPtyBinaryFrame(bytes: ByteArray): PtyFrame {
    if (bytes.isEmpty() || bytes[0] != PTY_META_PREFIX) {
        return PtyFrame.Output(String(bytes, Charsets.UTF_8), bytes.size)
    }
    val body = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
    val cursor = try {
        val root = contractJson.parseToJsonElement(body) as? JsonObject
            ?: return PtyFrame.UnreadableMeta("メタフレームがオブジェクトではありません")
        (root["cursor"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: return PtyFrame.UnreadableMeta("メタフレームに cursor がありません")
    } catch (_: SerializationException) {
        return PtyFrame.UnreadableMeta("メタフレームの JSON が壊れています")
    } catch (_: IllegalArgumentException) {
        return PtyFrame.UnreadableMeta("メタフレームの JSON が壊れています")
    }
    return PtyFrame.Meta(cursor)
}

/**
 * 再接続位置の更新。**メタフレームだけを信じてはいけない。**
 *
 * ## 実測(2026-08-30)—— ここが Q9 で一番間違えやすい所である
 *
 * メタフレームは**接続時に1回だけ**流れ、その時点の位置を言う。以後のライブ出力では
 * 更新されない。実測:
 *
 * ```
 * 接続直後にメタ {"cursor":0}
 * 223 バイトのバナーが流れる
 * "echo MARKER-BRAVO" を送る → さらに出力が流れる
 * → メタは 0 のまま二度と来ない
 * 別接続を張ると、そのメタは {"cursor":315}(それまでの累計)
 * ```
 *
 * つまり**メタの値だけを保存して再接続すると、接続してから今までの出力を全部もう一度読む**。
 * 症状は「回転するたびに画面の先頭からやり直す」で、クラッシュもエラーも出ない。
 *
 * したがって: **メタは位置を置き換え、出力フレームはバイト数だけ位置を進める。**
 *
 * ## この算術は**フレームの順序に依存する**(レビュー minor-2。KDoc の訂正)
 *
 * [PtyFrame] の doc が「種別で判別する。位置で判別しない」と書いているのは
 * **フレームの読み方**であって、ここの算術ではない。**順序は1つではない。** 実物 serve 1.18.21 で測り直した3通り
 * (採取 `e2e-artifacts/Q9/ptyprobe.mjs`、生の出力 `e2e-artifacts/Q9/ptyprobe-output.txt` §3/§5/§6):
 *
 * ```
 * 新品へ接続(再送する物が無い)  [メタ cursor=0]   → [本文 338B]      ⇒ 0 → 338
 * 位置を指定して接続(再送あり)  [本文 105B]       → [メタ cursor=443] ⇒ 338+105=443 → 443
 * cursor=-1(リプレイ無し)       [メタ cursor=443] のみ                ⇒ 443
 * ```
 *
 * 3通りとも正しい位置になるが、**それは「メタの `cursor` は、そのフレームより前に
 * 送られた本文をすべて含む」という不変条件が成り立っているから**である。
 * 実測でも**再送する物があるときはメタが最後**に来ており、
 * 「メタが先に来て、その後に再送が続く」順序は一度も観測されなかった ——
 * もし来れば、この関数は**再送分を二重に数える**(位置が進みすぎ、次の再接続で出力が飛ぶ)。
 *
 * つまりここは**順序そのものではなく上の不変条件に依存している**。
 * 崩れても画面には何も出ない(症状は「再接続で出力が飛ぶ / 二重になる」だけである)。
 *
 * @param current 現在位置。**null は「まだ位置を知らない」**(0 ではない ——
 *   0 は「先頭から全部再送してよい」という確かな値である)
 * @return 新しい位置。読めなかったメタでは動かさない
 */
fun advancePtyCursor(current: Long?, frame: PtyFrame): Long? = when (frame) {
    is PtyFrame.Meta -> frame.cursor
    is PtyFrame.Output -> current?.plus(frame.byteLength)
    is PtyFrame.UnreadableMeta -> current
}

/**
 * `http://host:port` → `ws://host:port`(`https` → `wss`)。
 *
 * OkHttp は `ws://` を受け付けるが、`Urls.normalize` が作るのは `http(s)://` である。
 * **スキームだけを差し替える** —— ホストやパスに `http` が現れても壊れないよう、
 * 先頭一致でしか置換しない。想定外のスキームは**そのまま返す**(黙って壊した URL を作らない)。
 */
fun webSocketBaseUrl(baseUrl: String): String = when {
    baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
    baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
    else -> baseUrl
}
