package com.amusic.player

import com.amusic.player.data.PlaybackOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跨列表轮转逻辑测试（列表循环模式）。
 *
 * 这套跨列表语义没法在没设备时验证，所以要在这里测：
 *   顺序 / 倒序 / 随机 都在当前列表内循环 —— **由播放器自己的队列和循环模式负责**
 *   （所以这里没有"列表内下一首"的测试，那段逻辑在 Media3 里）；
 *   列表循环是在各个列表之间轮转（当前列表放完切下一个列表，列表全空才停）—— 才是本文件测的。
 */
class PlaybackOrderTest {

    // ---------------- 跨列表循环（列表循环） ----------------

    @Test
    fun `列表循环放完当前列表切到下一个列表`() {
        // 三个列表分别 3 / 2 / 4 首，当前在第 0 个列表
        val sizes = listOf(3, 2, 4)
        assertEquals(1, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 0, forward = true))
        assertEquals(2, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 1, forward = true))
        // 最后一个列表之后回到第一个列表，形成列表之间的循环
        assertEquals(0, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 2, forward = true))
    }

    @Test
    fun `列表循环反向走`() {
        val sizes = listOf(3, 2, 4)
        assertEquals(2, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 0, forward = false))
        assertEquals(0, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 1, forward = false))
    }

    @Test
    fun `列表循环跳过空列表`() {
        // 第 1、2 个列表是空的，应当跳到第 3 个
        val sizes = listOf(3, 0, 0, 5)
        assertEquals(3, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 0, forward = true))
        // 反向：从第 3 个往前找，第一个非空的还是第 3 个自己之外的前一个非空 → 第 0 个
        assertEquals(0, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 3, forward = false))
    }

    @Test
    fun `只有一个列表时永远循环这个列表`() {
        val sizes = listOf(5)
        // "下一个列表"就是它自己：等于当前列表放完一轮后从头再来
        assertEquals(0, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 0, forward = true))
        assertEquals(0, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 0, forward = false))
    }

    @Test
    fun `所有列表都空时返回负一表示停止`() {
        assertEquals(-1, PlaybackOrder.nextNonEmptyList(listOf(0, 0, 0), currentListIndex = 0, forward = true))
        assertEquals(-1, PlaybackOrder.nextNonEmptyList(emptyList(), currentListIndex = 0, forward = true))
    }

    @Test
    fun `当前列表下标越界时也能正常找下一个`() {
        val sizes = listOf(2, 3)
        // 只有当前列表被删掉时才会出现这种越界，兜底从头开始找
        assertEquals(0, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = 9, forward = true))
        assertEquals(1, PlaybackOrder.nextNonEmptyList(sizes, currentListIndex = -5, forward = true))
    }
}
