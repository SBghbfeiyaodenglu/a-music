package com.amusic.player

import com.amusic.player.data.PlayMode
import com.amusic.player.data.requiresQueueRebuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切换播放模式时"要不要重建队列"的判断。
 *
 * 这块直接决定正在播放的歌会不会被重新装载（听感上就是卡一下）：
 * 只有倒序参与的模式切换才需要重建，其余模式只改播放器的循环/随机属性。
 */
class PlayModeQueueTest {

    @Test
    fun `只有倒序参与的模式切换才需要重建队列`() {
        for (previous in PlayMode.values()) {
            for (next in PlayMode.values()) {
                val expected = (previous == PlayMode.REVERSE) != (next == PlayMode.REVERSE)
                assertEquals(
                    "从 $previous 切到 $next",
                    expected,
                    next.requiresQueueRebuild(previous),
                )
            }
        }
    }

    @Test
    fun `顺序与单曲循环互切不重建队列`() {
        // 用户反馈的场景：播着歌切"单曲循环/顺序循环"，不应该有任何重载
        assertFalse(PlayMode.LOOP_ONE.requiresQueueRebuild(PlayMode.ORDER))
        assertFalse(PlayMode.ORDER.requiresQueueRebuild(PlayMode.LOOP_ONE))
    }

    @Test
    fun `顺序与随机、列表循环互切不重建队列`() {
        assertFalse(PlayMode.SHUFFLE.requiresQueueRebuild(PlayMode.ORDER))
        assertFalse(PlayMode.ORDER.requiresQueueRebuild(PlayMode.SHUFFLE))
        assertFalse(PlayMode.LOOP_LIST.requiresQueueRebuild(PlayMode.ORDER))
        assertFalse(PlayMode.ORDER.requiresQueueRebuild(PlayMode.LOOP_LIST))
    }

    @Test
    fun `切入或切出倒序才重建队列`() {
        assertTrue(PlayMode.REVERSE.requiresQueueRebuild(PlayMode.ORDER))
        assertTrue(PlayMode.ORDER.requiresQueueRebuild(PlayMode.REVERSE))
        assertTrue(PlayMode.REVERSE.requiresQueueRebuild(PlayMode.SHUFFLE))
        assertTrue(PlayMode.SHUFFLE.requiresQueueRebuild(PlayMode.REVERSE))
        // 同一个模式再点一次，不重建
        assertFalse(PlayMode.REVERSE.requiresQueueRebuild(PlayMode.REVERSE))
    }
}
