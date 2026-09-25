package dev.opencode.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.opencode.android.ui.ChatActions
import dev.opencode.android.ui.ChatBannerArea
import dev.opencode.android.ui.ChatBannerKind
import dev.opencode.android.ui.ChatMessage
import dev.opencode.android.ui.ChatPart
import dev.opencode.android.ui.ChatUi
import dev.opencode.android.ui.MessageList
import dev.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **Compose 層の配線**に検出器を置く(Q7 レビュー minor-3)。
 *
 * ## 通り抜けた変異
 *
 * レビューの **RD**: `ChatScreen` の `onUnrevert = viewModel::unrevert` を `{ }` にすると
 * **610件全緑のまま「元に戻す」への唯一の入口が無音で死ぬ**。帯は出たまま、押しても何も起きない。
 * 同類として `onOpenDiff` / `onRequestRevert` も挙げられた。
 *
 * ## 直し方(Q6 の (b) と同じ)
 *
 * ラムダ引数をやめ、宛先を [ChatActions] の**実体**で受け取る形にした
 * (RUN_PLAN 設計規則1)。すると宛先を落とす変異は **composable の中にしか書けず**、
 * ここが**本物の [ChatBannerArea] / [MessageList] を描いて本物のボタンを押す**ことで捕まえられる。
 *
 * **自前の composable を押さない。** Q6 のレビューが blocker として挙げた
 * 「テストが自分で組んだ `Text{}.clickable{}` を押しており画面に触れていない」トートロジーを避ける。
 *
 * ## それでも閉じられないもの(報告に列挙する)
 *
 * `ChatScreen` から `actions = viewModel` を渡す**その1行**は、ここからは見えない。
 * ただし潰すには「全メソッドが空の別オブジェクト」を書く必要があり、1行の変異にはならない。
 */
@RunWith(RobolectricTestRunner::class)
class Q7ComposeWiringTest {

    @get:Rule
    val compose = createComposeRule()

    /** 呼ばれたことを控えるだけの宛先。**素通しの `Unit` を返さない**(呼ばれた事実を残す)。 */
    private class RecordingActions : ChatActions {
        val calls = mutableListOf<String>()
        override fun unrevert() {
            calls += "unrevert"
        }
        override fun retryLoadMessages() {
            calls += "retryLoadMessages"
        }
        override fun dismissChatBanner(kind: ChatBannerKind) {
            calls += "dismiss:${kind.name}"
        }
        override fun openMessageDiff(sessionId: String, messageId: String, knownFiles: List<String>) {
            calls += "diff:$sessionId:$messageId:${knownFiles.joinToString(",")}"
        }
        override fun requestRevert(messageId: String, partId: String?, preview: String) {
            calls += "revert:$messageId:$partId:$preview"
        }
        /** Q8 スコープ6。**ここも実体で受ける**(Q8ComposeTest がツールカードから押す)。 */
        override fun openFileFromChat(path: String) {
            calls += "openFile:$path"
        }
    }

    // ---------------------------------------------------------------------
    // 帯(レビュー RD が狙った場所)
    // ---------------------------------------------------------------------

    /**
     * **「元に戻す」が `unrevert()` に繋がっていること。**
     * この帯が unrevert への唯一の入口なので、切れると復帰経路が画面から消える。
     */
    @Test
    fun `巻き戻しの帯の元に戻すが unrevert を呼ぶ`() {
        val actions = RecordingActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                ChatBannerArea(
                    ui = ChatUi(revertedMessageId = "msg_9"),
                    actions = actions,
                    onBackToList = {},
                    onChangeModel = {},
                )
            }
        }
        compose.onNodeWithContentDescription("chat-banner:REVERTED").assertExists()
        compose.onNodeWithText("元に戻す").performClick()
        assertEquals(listOf("unrevert"), actions.calls)
    }

    /** `session.error` の帯の「再試行」が履歴の再取得に繋がっていること。 */
    @Test
    fun `セッションエラーの帯の再試行が履歴を引き直す`() {
        val actions = RecordingActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                ChatBannerArea(
                    ui = ChatUi(sessionError = "落ちました", sessionErrorRetryable = true),
                    actions = actions,
                    onBackToList = {},
                    onChangeModel = {},
                )
            }
        }
        compose.onNodeWithText("再試行").performClick()
        assertEquals(listOf("retryLoadMessages"), actions.calls)
    }

    /** 「閉じる」が**その帯の種類**を渡していること(別の帯を閉じない)。 */
    @Test
    fun `閉じるは出ている帯の種類を渡す`() {
        val actions = RecordingActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                ChatBannerArea(
                    ui = ChatUi(revertError = "HTTP 409"),
                    actions = actions,
                    onBackToList = {},
                    onChangeModel = {},
                )
            }
        }
        compose.onNodeWithContentDescription("chat-banner:REVERT_ERROR").assertExists()
        compose.onNodeWithText("閉じる").performClick()
        assertEquals(listOf("dismiss:REVERT_ERROR"), actions.calls)
    }

    // ---------------------------------------------------------------------
    // メッセージのチップと長押しメニュー
    // ---------------------------------------------------------------------

    private fun patchMessage() = ChatMessage(
        messageId = "msg_1",
        role = "assistant",
        parts = listOf(
            ChatPart(partId = "prt_1", type = "text", text = "変更しました", toolLabel = null),
            ChatPart(
                partId = "prt_2",
                type = "patch",
                text = "",
                toolLabel = null,
                patchFiles = listOf("a.txt", "b/c.kt"),
            ),
        ),
    )

    @Test
    fun `Nファイル変更チップが差分を開く`() {
        val actions = RecordingActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                MessageList(
                    messages = listOf(patchMessage()),
                    busy = false,
                    sessionId = "ses_7",
                    actions = actions,
                )
            }
        }
        compose.onNodeWithContentDescription("message-diff:msg_1:2").performClick()
        // **セッションIDとファイル名がそのまま渡ること**まで見る ——
        // 引数を取り違えると「別のセッションの差分が出る」形になる。
        assertEquals(listOf("diff:ses_7:msg_1:a.txt,b/c.kt"), actions.calls)
    }

    @Test
    fun `長押しのここまで戻すが確認を要求する`() {
        val actions = RecordingActions()
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                MessageList(
                    messages = listOf(patchMessage()),
                    busy = false,
                    sessionId = "ses_7",
                    actions = actions,
                )
            }
        }
        compose.onNodeWithText("変更しました").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("message-revert-item").performClick()
        // **`requestRevert` であって `confirmRevert` ではない。** ここから POST は飛ばない。
        assertEquals(listOf("revert:msg_1:null:[assistant] 変更しました"), actions.calls)
    }
}
