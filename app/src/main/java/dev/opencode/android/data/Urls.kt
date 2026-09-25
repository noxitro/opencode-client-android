package dev.opencode.android.data

import java.net.URI
import java.net.URISyntaxException

/**
 * opencode serve の既定ポート。**ポートを書かなかった入力に補う値**([Urls.normalize])。
 *
 * 実測(LOOP.md H1): serve は `0.0.0.0:4097` で listen し、**ポート80では何も listen していない**。
 */
const val DEFAULT_OPENCODE_PORT = 4097

object Urls {
    /**
     * ユーザー入力のベースURLを正規化する。
     * - 前後の空白を除去
     * - スキーム省略時は http:// を補う(opencode serveは平文HTTP想定)
     * - **ポート省略時は [DEFAULT_OPENCODE_PORT] を補う**(下の注記)
     * - 末尾の "/" を除去(パス結合を "$base/global/health" の形で行うため)
     * - hostが解釈できない / スキームがhttp(s)以外の場合はnull(入力エラー扱い)
     *
     * 例: "10.0.2.2:4097/" -> "http://10.0.2.2:4097" / "http://100.64.0.1" -> "http://100.64.0.1:4097"
     *
     * ## ポート補填が**スキームの有無で分岐しない**理由(H1a)
     *
     * 報告された入力は `http://100.64.0.1` ——**スキーム付き・ポート無し**である。
     * 以前の実装は補填を `if (schemeMatch == null)` の内側にしか持たず、この入力は補填経路を
     * 通らないまま**HTTPの既定ポート80へ落ちた**(serve は 4097 でしか listen していない)。
     * ユーザーが `http://` と書いたか書かなかったかは、**どのポートで待っているかとは無関係**である。
     * したがって分岐は「スキームを書いたか」ではなく「**ポートを書いたか**」に置く。
     *
     * ## `https` だけは分岐する
     *
     * `https://host` の既定ポートは 443 であり、それが正しい。TLS 終端のリバースプロキシ越しの
     * 構成がこれに当たるので、4097 を**補わない**。
     *
     * ## 既知の代償
     *
     * `http://example.com/opencode` のように**平文HTTPの80番でリバースプロキシに載せている**構成は、
     * この規則だと `:4097` を補われて繋がらなくなる。**`:80` を明示すれば通る**(そして health の
     * 接続テストが即座に失敗を出す)ので、黙って 80 へ落ちて「serve が居ない」と見える以前の形より
     * 診断できる。パスの有無で分岐させることも考えたが、**分岐軸を増やすほど
     * 「どちらの規則が効いたか」がユーザーから見えなくなる**ので、規則は1本にした。
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // すでに "<scheme>://" で始まる場合はそのschemeを尊重し、http(s)以外は拒否する。
        // 単純なstartsWithチェックだと "ftp://x" に http:// を二重付与してしまうため正規表現で判定する。
        val schemeMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://").find(trimmed)
        val withScheme = when {
            schemeMatch == null -> "http://$trimmed"
            schemeMatch.groupValues[1].lowercase() in setOf("http", "https") -> trimmed
            else -> return null
        }
        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if ((scheme == "http" || scheme == "https") && !host.isNullOrEmpty()) {
                val port = defaultPortSuffix(scheme, uri.port)
                val path = uri.rawPath?.trimEnd('/').orEmpty()
                "$scheme://$host$port$path"
            } else {
                null
            }
        } catch (_: URISyntaxException) {
            null
        }
    }

    /**
     * 付けるポート表記。**ここが唯一の分岐点**(上の doc の規則をそのまま置いた)。
     *
     * @param port `URI.getPort()` の戻り。-1 = 入力にポートが書かれていない
     */
    internal fun defaultPortSuffix(scheme: String, port: Int): String = when {
        port != -1 -> ":$port"
        // https の既定は 443 で、それが正しい。4097 を補わない。
        scheme == "https" -> ""
        else -> ":$DEFAULT_OPENCODE_PORT"
    }
}
