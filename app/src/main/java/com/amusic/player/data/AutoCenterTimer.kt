package com.amusic.player.data

/**
 * 歌曲列表「自动居中」的计时决策。
 *
 * 抽成纯函数的理由和 [PlaybackOrder] 一样：这是"决策"，写成纯对象就能在 JVM 上单独测；
 * 真正去滚动列表是"执行"，留在 [com.amusic.player.ui.SongListPage] 里做。
 * 这块逻辑没法在没有真机的时候靠眼睛验证，所以更需要测试兜住。
 *
 * 规则（[idleMs] 由用户在左上角菜单里设置）：
 *   1. 从用户最后一次在列表区域触摸算起，必须**连续安静满 [idleMs]** 才允许滚；
 *   2. **同一轮安静期只滚一次** —— 当前歌在列表首/尾时居中物理上做不到，
 *      不记这一笔的话轮询会每 500ms 发起一次永远滚不动的动画（白耗电、还影响手感）；
 *   3. 用户一碰（触摸时间戳变新）就重新武装，下次安静满同样时长还会再滚。
 */
object AutoCenterTimer {

    /**
     * 现在这一刻该不该把正在播放的那行滚到屏幕中间。
     *
     * "已经居中了"这一条不在这里判断：它要读 LazyList 的布局信息（视口高度、行高、行偏移），
     * 属于"执行"侧的事，放在列表页里做。
     *
     * @param nowMs         当前时刻（SystemClock.elapsedRealtime）
     * @param lastTouchMs   用户最后一次在列表区域触摸的时刻
     * @param idleMs        需要连续安静的时长，由用户在左上角菜单里设置（默认 5 秒，最少 1 秒）
     * @param lastAttemptMs 这一轮安静期里上一次滚动的时刻；0 表示这轮还没滚过
     */
    fun shouldCenter(
        nowMs: Long,
        lastTouchMs: Long,
        idleMs: Long,
        lastAttemptMs: Long,
    ): Boolean {
        // 用户还在操作，或者刚操作完还没安静够 → 继续等，并重新计时
        if (nowMs - lastTouchMs < idleMs) return false
        // 这轮安静期已经滚过一次了 → 别重复（等用户下一次触摸重新武装）
        if (lastAttemptMs > lastTouchMs) return false
        return true
    }
}
