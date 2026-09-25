package dev.opencode.android.ui

/**
 * 末尾追従と「↓ 最新へ」チップの判定(QUALITY_PLAN §5 Q2 スコープ5)。
 *
 * メッセージ列は `reverseLayout = true` の LazyColumn なので、
 * **添字 0 が最新**であり「一番下」は `firstVisibleItemIndex == 0`(かつオフセット 0)である。
 * この向きを間違えると「常に追従している」か「一度も追従しない」のどちらかに倒れ、
 * **どちらも画面は正常に見える**(チップが出ないだけ / 出っぱなしなだけ)。
 * だから判定をここへ出して、境界値をテストで固定する。
 */

/**
 * 追従許容量(px)。**0 にしないこと。**
 *
 * ストリーミング中は末尾のバブルが1文字ごとに伸びるので、
 * オフセットがぴったり 0 に留まる保証が無い。数px のずれで追従が外れると、
 * 誰もスクロールしていないのに「↓ 最新へ」が出続ける。
 */
const val FOLLOW_THRESHOLD_PX = 24

/**
 * 末尾に追従しているか。[firstVisibleItemIndex] / [firstVisibleItemScrollOffset] は
 * `reverseLayout=true` の `LazyListState` からそのまま渡す。
 */
fun isFollowingBottom(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    thresholdPx: Int = FOLLOW_THRESHOLD_PX,
): Boolean = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= thresholdPx

/**
 * 「↓ 最新へ」チップを出すか。
 *
 * 追従していない**かつ**表示するメッセージがあるとき。空の画面で
 * 「最新へ」だけが浮くのを防ぐ。
 */
fun shouldShowJumpToLatest(following: Boolean, hasMessages: Boolean): Boolean =
    !following && hasMessages
