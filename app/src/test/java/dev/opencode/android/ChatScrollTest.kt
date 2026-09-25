package dev.opencode.android

import dev.opencode.android.ui.FOLLOW_THRESHOLD_PX
import dev.opencode.android.ui.isFollowingBottom
import dev.opencode.android.ui.shouldShowJumpToLatest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 末尾追従と「↓ 最新へ」(QUALITY_PLAN §5 Q2 スコープ5)。
 *
 * ここを壊しても**画面は正常に見える** —— チップが出ないだけ、あるいは出っぱなしなだけ。
 * 追従の向き(`reverseLayout` なので添字0が最新)と閾値の境界を数値で固定する。
 */
class ChatScrollTest {

    @Test
    fun `添字0でオフセット0なら追従`() {
        assertTrue(isFollowingBottom(0, 0))
    }

    @Test
    fun `閾値ちょうどまでは追従 超えたら外れる`() {
        assertTrue(isFollowingBottom(0, FOLLOW_THRESHOLD_PX))
        assertFalse(isFollowingBottom(0, FOLLOW_THRESHOLD_PX + 1))
    }

    @Test
    fun `添字が1以上なら追従しない`() {
        // 向きを取り違える(reverseLayout を忘れる)と、ここが真になってしまう。
        assertFalse(isFollowingBottom(1, 0))
        assertFalse(isFollowingBottom(5, 0))
    }

    @Test
    fun `閾値は0ではない`() {
        // 0 にすると、ストリーミング中の数px のずれで追従が外れ、
        // 誰もスクロールしていないのにチップが出続ける。
        assertTrue("追従許容量は正の値であること", FOLLOW_THRESHOLD_PX > 0)
    }

    @Test
    fun `チップは追従していない かつ メッセージがあるときだけ`() {
        assertEquals(false, shouldShowJumpToLatest(following = true, hasMessages = true))
        assertEquals(true, shouldShowJumpToLatest(following = false, hasMessages = true))
        assertEquals(false, shouldShowJumpToLatest(following = false, hasMessages = false))
        assertEquals(false, shouldShowJumpToLatest(following = true, hasMessages = false))
    }
}
