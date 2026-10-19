package com.amusic.player

import com.amusic.player.data.PlayMode
import com.amusic.player.data.db.TrackEntity
import com.amusic.player.playback.buildResumptionQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 系统"媒体恢复"的队列还原逻辑。
 *
 * 真机触发条件是"系统把进程回收掉、你再点系统那张媒体卡片"，
 * 用 adb 很难造（前台服务让进程一直可见），所以这里把纯逻辑钉死：
 * 起点算错就会接着放成别的歌，文件没了还塞给系统就会一点播放就报错。
 */
class PlaybackResumptionTest {

    private fun track(id: Long, name: String, path: String = "/music/$name.flac") = TrackEntity(
        id = id,
        path = path,
        displayName = "$name.flac",
        title = name,
        artist = "歌手",
        album = "专辑",
        durationMs = 200_000L,
        sizeBytes = 1L,
        lastModified = 1L,
    )

    private val tracks = listOf(
        track(1, "第一首"),
        track(2, "第二首"),
        track(3, "第三首"),
    )

    @Test
    fun `起点就是上次播的那首歌`() {
        val q = buildResumptionQueue(tracks, currentTrackId = 2, playMode = PlayMode.ORDER.name,
            positionMs = 42_000L) { true }!!

        assertEquals(3, q.items.size)
        assertEquals("第二首", q.items[q.startIndex].title)
        assertEquals(1, q.startIndex)
        assertEquals(42_000L, q.startPositionMs)
    }

    @Test
    fun `队列顺序和列表一致`() {
        val q = buildResumptionQueue(tracks, currentTrackId = 1, playMode = PlayMode.ORDER.name,
            positionMs = 0L) { true }!!
        assertEquals(listOf("第一首", "第二首", "第三首"), q.items.map { it.title })
    }

    @Test
    fun `倒序模式下队列反过来且起点跟着对`() {
        val q = buildResumptionQueue(tracks, currentTrackId = 2, playMode = PlayMode.REVERSE.name,
            positionMs = 0L) { true }!!

        assertEquals(listOf("第三首", "第二首", "第一首"), q.items.map { it.title })
        assertEquals("接着放的还得是第二首", "第二首", q.items[q.startIndex].title)
        assertEquals(1, q.startIndex)
    }

    @Test
    fun `文件没了的曲目不塞给系统并且起点仍然对得上`() {
        // 第一首的文件被删了，上次播的是第三首
        val q = buildResumptionQueue(tracks, currentTrackId = 3, playMode = PlayMode.ORDER.name,
            positionMs = 0L) { it != "/music/第一首.flac" }!!

        assertEquals(listOf("第二首", "第三首"), q.items.map { it.title })
        assertEquals("第三首", q.items[q.startIndex].title)
    }

    @Test
    fun `上次那首的文件没了就从第一首开始`() {
        val q = buildResumptionQueue(tracks, currentTrackId = 2, playMode = PlayMode.ORDER.name,
            positionMs = 5_000L) { it != "/music/第二首.flac" }!!

        assertEquals(0, q.startIndex)
        assertEquals(5_000L, q.startPositionMs)
    }

    @Test
    fun `一首可播的都没有就返回空`() {
        assertNull(
            "全都没文件时不该给系统任何东西",
            buildResumptionQueue(tracks, currentTrackId = 1, playMode = PlayMode.ORDER.name,
                positionMs = 0L) { false },
        )
    }

    @Test
    fun `曲目列表本身是空的也返回空`() {
        assertNull(buildResumptionQueue(emptyList(), currentTrackId = 1,
            playMode = PlayMode.ORDER.name, positionMs = 0L) { true })
    }

    @Test
    fun `负数进度归零`() {
        val q = buildResumptionQueue(tracks, currentTrackId = 1, playMode = PlayMode.ORDER.name,
            positionMs = -123L) { true }!!
        assertEquals(0L, q.startPositionMs)
    }

    @Test
    fun `没有记录过当前歌曲时从第一首开始`() {
        val q = buildResumptionQueue(tracks, currentTrackId = null, playMode = PlayMode.ORDER.name,
            positionMs = 0L) { true }!!
        assertEquals(0, q.startIndex)
    }
}
