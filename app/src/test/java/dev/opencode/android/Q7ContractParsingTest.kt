package dev.opencode.android

import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.RevertRequest
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SnapshotFileDiffDto
import dev.opencode.android.data.VcsFileDiffDto
import dev.opencode.android.data.VcsFileStatusDto
import dev.opencode.android.data.VcsInfoDto
import dev.opencode.android.data.contractJson
import dev.opencode.android.ui.changedFilesOf
import dev.opencode.android.ui.initialChatMessages
import dev.opencode.android.ui.messageDiffChip
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q7 の契約(実機 `/doc` 2026-08-27 で採取。SHA-256 は
 * `docs/spec/opencode-1.18.21-openapi.json` と**一致**)。
 *
 * ## 計画書と実測が食い違った点を、ここで固定する
 *
 * QUALITY_PLAN §5b は
 * `SnapshotFileDiff = {file, patch, additions, deletions, status}` と書いているが、
 * **spec の required は `additions` と `deletions` の2つだけ**である。
 * `file` も `patch` も `status` も欠けうる。
 *
 * required でないものを必須宣言する形は、このプロジェクトが3回踏んでいる
 * (P4 の `metadata` / P4 の `PermissionRepliedEvent` / L3 の `error`)。
 * どれも**テストは全緑のまま画面が黙って壊れた**。
 */
class Q7ContractParsingTest {

    // ---------------------------------------------------------------------
    // GET /vcs
    // ---------------------------------------------------------------------

    @Test
    fun `実測の VcsInfo を読む`() {
        val dto = contractJson.decodeFromString<VcsInfoDto>(Q7DiffFixtures.VCS_INFO_JSON)
        assertEquals("master", dto.branch)
        assertEquals("master", dto.defaultBranch)
    }

    /**
     * **`default_branch` はスネークケースである。** ここを `defaultBranch` のまま
     * 受けると **常に null** になり、「既定ブランチと違うときだけ強調」が
     * 一度も成立しない —— 画面は普通に見える。
     */
    @Test
    fun `default_branch のキー名を取り違えると読めない`() {
        val wrongKey = contractJson.decodeFromString<VcsInfoDto>("""{"branch":"x","defaultBranch":"y"}""")
        assertEquals("x", wrongKey.branch)
        assertNull("キー名が違えば読めない(=SerialName が効いている証拠)", wrongKey.defaultBranch)
    }

    /** required が1つも無いので `{}` でも落ちない(実機 spec)。 */
    @Test
    fun `空の VcsInfo でも落ちない`() {
        val dto = contractJson.decodeFromString<VcsInfoDto>("{}")
        assertNull(dto.branch)
        assertNull(dto.defaultBranch)
    }

    // ---------------------------------------------------------------------
    // GET /vcs/status
    // ---------------------------------------------------------------------

    @Test
    fun `実測の VcsFileStatus 配列を読む`() {
        val list = contractJson.decodeFromString<List<VcsFileStatusDto>>(Q7DiffFixtures.VCS_STATUS_JSON)
        assertEquals(5, list.size)
        assertEquals("added2.txt", list[0].file)
        assertEquals(2, list[0].additions)
        assertEquals("added", list[0].status)
        // **未追跡ファイルも `added` として載る**(実測)。
        assertEquals("deleted", list[3].status)
        assertEquals(6, list[3].deletions)
        // バイナリは additions/deletions が 0(git が行を数えられない)。
        assertEquals("image.bin", list[2].file)
        assertEquals(0, list[2].additions)
        assertEquals(0, list[2].deletions)
    }

    // ---------------------------------------------------------------------
    // GET /vcs/diff / GET /session/{id}/diff
    // ---------------------------------------------------------------------

    @Test
    fun `VcsFileDiff は patch と status が欠けても読める`() {
        val dto = contractJson.decodeFromString<VcsFileDiffDto>(
            """{"file":"a.txt","additions":1,"deletions":0}""",
        )
        assertEquals("a.txt", dto.file)
        assertNull(dto.patch)
        assertNull(dto.status)
    }

    /**
     * **`SnapshotFileDiff` の required は additions/deletions だけ。**
     * `file` すら欠けうるので、欠けた要素は表示側で捨てる(見出しが作れない)。
     */
    @Test
    fun `SnapshotFileDiff は additions と deletions だけで読める`() {
        val dto = contractJson.decodeFromString<SnapshotFileDiffDto>("""{"additions":3,"deletions":1}""")
        assertNull(dto.file)
        assertNull(dto.patch)
        assertNull(dto.status)
        assertEquals(3, dto.additions)
        assertEquals(1, dto.deletions)
    }

    /**
     * **実物 serve は既存200セッションすべてで `[]` を返した**(402 でエージェントが
     * ファイルを変更しないため)。空配列で落ちないことを固定する。
     */
    @Test
    fun `実測の session diff は空配列`() {
        val list = contractJson.decodeFromString<List<SnapshotFileDiffDto>>("[]")
        assertTrue(list.isEmpty())
    }

    /** 未知の status(サーバーの enum が増えた場合)でも落ちない。 */
    @Test
    fun `未知の status でも落ちない`() {
        val dto = contractJson.decodeFromString<SnapshotFileDiffDto>(
            """{"file":"a","additions":0,"deletions":0,"status":"renamed"}""",
        )
        assertEquals("renamed", dto.status)
    }

    // ---------------------------------------------------------------------
    // POST /session/{id}/revert
    // ---------------------------------------------------------------------

    /**
     * `additionalProperties:false` のスキーマに `"partID":null` を送ると 400 になる形は
     * Q4 が `POST /session` で実測している。**`explicitNulls = false` がそれを防ぐ。**
     */
    @Test
    fun `revert のボディに null を書かない`() {
        val json = contractJson.encodeToString(RevertRequest(messageID = "msg_1"))
        assertEquals("""{"messageID":"msg_1"}""", json)
    }

    @Test
    fun `partID があれば載せる`() {
        val json = contractJson.encodeToString(RevertRequest(messageID = "msg_1", partID = "prt_2"))
        assertEquals("""{"messageID":"msg_1","partID":"prt_2"}""", json)
    }

    /** `Session.revert` は revert/unrevert の応答と `GET /session/{id}` の権威。 */
    @Test
    fun `Session revert を読む`() {
        val ses = contractJson.decodeFromString<SessionDto>(
            """{"id":"ses_1","title":"t","revert":{"messageID":"msg_9","snapshot":"abc"}}""",
        )
        assertEquals("msg_9", ses.revert?.messageID)
        assertEquals("abc", ses.revert?.snapshot)
        assertNull(ses.revert?.partID)
    }

    @Test
    fun `revert が無ければ null`() {
        val ses = contractJson.decodeFromString<SessionDto>("""{"id":"ses_1","title":"t"}""")
        assertNull(ses.revert)
    }

    // ---------------------------------------------------------------------
    // POST /vcs/apply を実装しない(§5b の明示的な除外)
    // ---------------------------------------------------------------------

    /**
     * **`DiffGateway` に任意パッチ適用の口が生えていないこと。**
     *
     * §5b は `POST /vcs/apply` を明示的に除外している —— 端末からの任意パッチ適用は
     * 入力手段が無く、誤爆の被害がリポジトリ全体に及ぶ。
     * 「使わないが口だけ開けておく」をしないのは、**開いた口は必ず誰かが繋ぐ**からである。
     *
     * ここを列挙で固定しておくと、口が1つ増えた瞬間にこのテストが落ちて
     * 「除外したはずのものが入った」と分かる。文言ではなく**形**で守る。
     */
    @Test
    fun `差分の口に任意パッチ適用は無い`() {
        // `$` を含む名前は Kotlin が生成した合成メソッド
        // (`vcsInfo$default` = 既定引数のブリッジ、`access$…`)。**口ではない。**
        val names = dev.opencode.android.data.DiffGateway::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it.contains('$') }
            .toSortedSet()
        val allowed = setOf("getConfigured", "isConfigured", "sessionDiff", "vcsDiff", "vcsInfo", "vcsStatus")
        assertEquals(
            "許可していない口が生えている",
            emptySet<String>(),
            names - allowed,
        )
        assertTrue("apply の口が生えていない", names.none { it.contains("apply", ignoreCase = true) })
    }

    // ---------------------------------------------------------------------
    // PatchPart(「N ファイル変更」チップの材料)
    // ---------------------------------------------------------------------

    /**
     * `PatchPart` の required は `id` `sessionID` `messageID` `type` `hash` `files`。
     * **チップの件数はここから出す**ので、`files` を読み落とすとチップが一度も出ない。
     */
    @Test
    fun `PatchPart の files からチップを作る`() {
        val json = """
            [{"info":{"id":"msg_1","sessionID":"ses_1","role":"assistant"},
              "parts":[
                {"id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"text","text":"やりました"},
                {"id":"prt_2","sessionID":"ses_1","messageID":"msg_1","type":"patch","hash":"abc1234",
                 "files":["a.txt","b/c.kt","a.txt"]}
              ]}]
        """.trimIndent()
        val entries = contractJson.decodeFromString<List<MessageEntryDto>>(json)
        val message = initialChatMessages(entries).single()
        // **重複は畳む。** 同じファイルを2度書いたら2ファイルではない。
        assertEquals(listOf("a.txt", "b/c.kt"), changedFilesOf(message))
        val chip = messageDiffChip(message)
        assertEquals("2 ファイル変更", chip?.label)
        assertEquals("message-diff:msg_1:2", chip?.description)
    }

    /** `patch` パートが無ければチップは出ない。 */
    @Test
    fun `patch が無ければチップは出ない`() {
        val json = """
            [{"info":{"id":"msg_1","sessionID":"ses_1","role":"assistant"},
              "parts":[{"id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"text","text":"何もしていません"}]}]
        """.trimIndent()
        val message = initialChatMessages(contractJson.decodeFromString<List<MessageEntryDto>>(json)).single()
        assertNull(messageDiffChip(message))
    }

    /**
     * **`files` は `patch` type のときだけ数える。** 他の part にも `files` が生えたときに
     * 「N ファイル変更」が別の意味の配列を数え始めないようにする。
     */
    @Test
    fun `patch 以外の files は数えない`() {
        val json = """
            [{"info":{"id":"msg_1","sessionID":"ses_1","role":"assistant"},
              "parts":[{"id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"tool",
                        "tool":"read","files":["x.txt","y.txt"]}]}]
        """.trimIndent()
        val message = initialChatMessages(contractJson.decodeFromString<List<MessageEntryDto>>(json)).single()
        assertEquals(emptyList<String>(), changedFilesOf(message))
        assertNull(messageDiffChip(message))
    }
}
