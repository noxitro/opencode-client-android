package dev.opencode.android.ui

import dev.opencode.android.data.PtyDto

/**
 * ターミナル画面の composable が **ViewModel へ配る宛先**(Q9)。
 *
 * ## なぜラムダではなくオブジェクトなのか
 *
 * RUN_PLAN の設計規則1「**宛先はオブジェクトで名指しする。ラムダで受け取らない。**」。
 * Q7 のレビューが打った変異 RD(`onUnrevert` を `{ }` にする)は **610件全緑で通り抜けた**。
 * ここで特に効くのは [sendKey] である —— `Ctrl+C` の宛先を `{ }` にする変異は、
 * **暴走したコマンドを止める唯一の手段が無音で死ぬ**のに、画面はどこも壊れて見えない。
 *
 * ## ここに置かないもの
 *
 * **画面遷移は置かない**(`onBack` / `onOpenSettings`)。[FileActions] / [ChatActions] と同じ判断。
 *
 * 実装は [AppViewModel]。
 */
interface PtyActions {
    /** `GET /pty` を引き直す(空状態の「再試行」・引き下げ更新)。 */
    fun refreshPtyList()

    /** シェルを起動して開く。[command] は `GET /pty/shells` の `path`。 */
    fun startPty(command: String, title: String)

    /** 一覧の行から開く。 */
    fun openPty(pty: PtyDto)

    /** `DELETE /pty/{id}`。**終了コードは取れない**(判定は Controller 側)。 */
    fun deletePty(ptyId: String)

    /** ターミナルを閉じる(ソケットも閉じる)。 */
    fun closeTerminal()

    /** 入力欄から1行。**改行の付与は Controller が持つ**(画面が `+ "\r"` を書かない)。 */
    fun sendPtyLine(text: String)

    /** 補助キー。**対応表は [ptyKeySequence] にしかない。** */
    fun sendPtyKey(key: PtyKey)

    /** 手動の再接続。 */
    fun reconnectPty()

    /** 端末サイズを伝える(`PUT /pty/{id}`)。同値なら撃たない判定は Controller 側。 */
    fun resizePty(rows: Int, cols: Int)

    fun clearPtyActionError()

    fun clearPtyCreateError()
}
