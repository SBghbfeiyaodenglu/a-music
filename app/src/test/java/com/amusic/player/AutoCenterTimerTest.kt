package com.amusic.player

import com.amusic.player.data.AutoCenterTimer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表「自动居中」的计时决策测试。
 *
 * 这块逻辑**没有真机就验证不了**（要盯着列表看它几秒后才滚），
 * 所以把决策抽成纯函数在这里测。
 *
 * 核心语义：从最后一次触摸算起连续安静满设定秒数才滚一次；中途一碰就重新计时；
 * 同一轮安静期不重复滚。
 */
class AutoCenterTimerTest {

    private val second = 1_000L

    // ---------------- 安静时长 ----------------

    @Test
    fun `安静时间不够时不许滚动`() {
        val touch = 10_000L
        // 默认 5 秒：才安静 4.9 秒，还不能动
        assertFalse(AutoCenterTimer.shouldCenter(nowMs = touch + 4_900, lastTouchMs = touch, idleMs = 5 * second, lastAttemptMs = 0L))
    }

    @Test
    fun `刚好安静满设定秒数就允许滚动`() {
        val touch = 10_000L
        // 到点即滚（5 秒整），不是"必须超过"
        assertTrue(AutoCenterTimer.shouldCenter(nowMs = touch + 5 * second, lastTouchMs = touch, idleMs = 5 * second, lastAttemptMs = 0L))
    }

    @Test
    fun `设定值就是触发阈值本身`() {
        val touch = 0L
        // 同一时刻下，1 秒设置会滚、60 秒设置不会滚 —— 证明用的确实是传进来的秒数
        assertTrue(AutoCenterTimer.shouldCenter(nowMs = 3 * second, lastTouchMs = touch, idleMs = 1 * second, lastAttemptMs = 0L))
        assertFalse(AutoCenterTimer.shouldCenter(nowMs = 3 * second, lastTouchMs = touch, idleMs = 60 * second, lastAttemptMs = 0L))
    }

    @Test
    fun `改成更短的秒数后立刻按新值生效`() {
        val touch = 0L
        // 用户把 30 秒改成 1 秒：安静 2 秒时就该滚，不必等 30 秒
        assertTrue(AutoCenterTimer.shouldCenter(nowMs = 2 * second, lastTouchMs = touch, idleMs = 1 * second, lastAttemptMs = 0L))
    }

    @Test
    fun `用户又碰了一下就重新计时`() {
        val firstTouch = 10_000L
        // 已经安静了 4 秒（还差 1 秒），用户又滑了一下
        val secondTouch = firstTouch + 4 * second
        // 从新的触摸算起才过 1 秒，不能滚
        assertFalse(AutoCenterTimer.shouldCenter(nowMs = secondTouch + 1 * second, lastTouchMs = secondTouch, idleMs = 5 * second, lastAttemptMs = 0L))
        // 从新的触摸算起满 5 秒，才可以
        assertTrue(AutoCenterTimer.shouldCenter(nowMs = secondTouch + 5 * second, lastTouchMs = secondTouch, idleMs = 5 * second, lastAttemptMs = 0L))
    }

    // ---------------- 一轮安静期只滚一次 ----------------

    @Test
    fun `同一轮安静期里滚过一次就不再滚`() {
        val touch = 10_000L
        val idle = 5 * second
        // 安静满 5 秒时滚了第一次
        assertTrue(AutoCenterTimer.shouldCenter(nowMs = touch + 5 * second, lastTouchMs = touch, idleMs = idle, lastAttemptMs = 0L))
        // 记下这次滚动时刻；随后每个轮询点都不该再滚（哪怕又过了很久）
        val attempt = touch + 5 * second
        assertFalse(AutoCenterTimer.shouldCenter(nowMs = attempt + 500, lastTouchMs = touch, idleMs = idle, lastAttemptMs = attempt))
        assertFalse(AutoCenterTimer.shouldCenter(nowMs = attempt + 60 * second, lastTouchMs = touch, idleMs = idle, lastAttemptMs = attempt))
    }

    @Test
    fun `用户再碰一下之后会重新武装`() {
        val touch = 10_000L
        val idle = 5 * second
        val attempt = touch + 5 * second          // 第一轮滚过了
        val newTouch = attempt + 3 * second       // 用户又开始翻列表
        // 新一轮安静满 5 秒后，应当再滚一次
        assertTrue(AutoCenterTimer.shouldCenter(nowMs = newTouch + 5 * second, lastTouchMs = newTouch, idleMs = idle, lastAttemptMs = attempt))
    }

    // ---------------- 边界 ----------------

    @Test
    fun `触摸与滚动落在同一毫秒时不算已滚过_由已居中判定兜底`() {
        // lastAttemptMs 与 lastTouchMs 相等这一格用的是严格 ">"，所以它算"这轮还没滚过"。
        // 这是刻意的：相等只可能出现在"滚动和触摸落在同一毫秒"这种极端情况下，
        // 此时最多多算一次，而紧接着的"已经居中了就不滚"判定会把它挡掉，
        // 不会真的重复滚动。反过来改成 ">=" 就等于让同一毫秒的触摸把刚滚过的记录也抹掉，
        // 没有必要（原实现就是 ">"，这里只是把它的语义钉住）。
        val t = 50_000L
        assertTrue(AutoCenterTimer.shouldCenter(nowMs = t + 10 * second, lastTouchMs = t, idleMs = 5 * second, lastAttemptMs = t))
    }

    @Test
    fun `最短可设的一秒也按预期工作`() {
        val touch = 1_000L
        // 下限就是 1 秒（设置里不允许更小），刚好 1 秒时滚
        assertTrue(AutoCenterTimer.shouldCenter(nowMs = touch + 1 * second, lastTouchMs = touch, idleMs = 1 * second, lastAttemptMs = 0L))
        assertFalse(AutoCenterTimer.shouldCenter(nowMs = touch + 999, lastTouchMs = touch, idleMs = 1 * second, lastAttemptMs = 0L))
    }

    @Test
    fun `页面刚出现时不会立刻滚动`() {
        // 触摸时间戳的初值取"页面出现那一刻"，所以进页面时 now 和 lastTouch 基本相等，
        // 必须等满设定秒数才滚 —— 否则会一进来就抢掉"每个列表各记各的滚动位置"
        val appear = 7_000L
        assertFalse(AutoCenterTimer.shouldCenter(nowMs = appear, lastTouchMs = appear, idleMs = 5 * second, lastAttemptMs = 0L))
        assertFalse(AutoCenterTimer.shouldCenter(nowMs = appear + 500, lastTouchMs = appear, idleMs = 5 * second, lastAttemptMs = 0L))
        assertTrue(AutoCenterTimer.shouldCenter(nowMs = appear + 5 * second, lastTouchMs = appear, idleMs = 5 * second, lastAttemptMs = 0L))
    }
}
