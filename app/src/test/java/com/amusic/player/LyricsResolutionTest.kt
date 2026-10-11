package com.amusic.player

import com.amusic.player.data.LrcParser
import com.amusic.player.data.db.TrackEntity
import com.amusic.player.data.lyrics.LyricsRepository
import com.amusic.player.data.lyrics.LyricsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 歌词解析的纯逻辑测试。
 *
 * 这些用例覆盖了本阶段风险最高的两件事：同名 .lrc 的匹配规则
 * 和中文歌词文件的编码（大量 .lrc 是 GBK，处理不好会整篇乱码）。
 * 不需要设备，直接 `gradle testDebugUnitTest` 就能跑。
 */
class LyricsResolutionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun track(path: String) = TrackEntity(
        id = 1L,
        path = path,
        displayName = File(path).name,
        title = "",
        artist = "",
        album = "",
        durationMs = 1_000L,
        sizeBytes = 0L,
        lastModified = 0L,
    )

    @Test
    fun `同名 lrc 命中并解析出时间轴`() = runBlocking {
        val dir = tmp.newFolder()
        val audio = File(dir, "晴天.mp3").apply { writeBytes(ByteArray(0)) }
        File(dir, "晴天.lrc").writeText("[00:01.00]第一句\n[00:05.50]第二句\n", Charsets.UTF_8)

        val result = LyricsRepository().resolve(track(audio.absolutePath))

        assertEquals(LyricsSource.LRC_FILE, result.source)
        assertTrue(result.hasTimeline)
        assertEquals(listOf("第一句", "第二句"), result.lines.map { it.text })
        assertEquals(1_000L, result.lines[0].timeMs)
        assertEquals(5_500L, result.lines[1].timeMs)
    }

    @Test
    fun `GBK 编码的 lrc 不会乱码`() = runBlocking {
        val dir = tmp.newFolder()
        val audio = File(dir, "七里香.flac").apply { writeBytes(ByteArray(0)) }
        File(dir, "七里香.lrc").writeBytes("[00:02.00]周杰伦 - 七里香\n".toByteArray(charset("GBK")))

        val result = LyricsRepository().resolve(track(audio.absolutePath))

        assertEquals(listOf("周杰伦 - 七里香"), result.lines.map { it.text })
    }

    @Test
    fun `UTF8 BOM 的 lrc 也能读`() = runBlocking {
        val dir = tmp.newFolder()
        val audio = File(dir, "夜曲.mp3").apply { writeBytes(ByteArray(0)) }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        File(dir, "夜曲.lrc").writeBytes(bom + "[00:03.00]夜曲\n".toByteArray(Charsets.UTF_8))

        val result = LyricsRepository().resolve(track(audio.absolutePath))

        assertEquals(listOf("夜曲"), result.lines.map { it.text })
    }

    @Test
    fun `lrc 扩展名大小写不敏感`() = runBlocking {
        val dir = tmp.newFolder()
        val audio = File(dir, "稻香.MP3").apply { writeBytes(ByteArray(0)) }
        File(dir, "稻香.LRC").writeText("[00:03.00]稻香\n")

        assertEquals(LyricsSource.LRC_FILE, LyricsRepository().resolve(track(audio.absolutePath)).source)
    }

    @Test
    fun `不同名的歌词不会被误用`() = runBlocking {
        val dir = tmp.newFolder()
        val audio = File(dir, "稻香.mp3").apply { writeBytes(ByteArray(0)) }
        File(dir, "晴天.lrc").writeText("[00:01.00]不该被用到\n")

        val result = LyricsRepository().resolve(track(audio.absolutePath))

        assertEquals(LyricsSource.NONE, result.source)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `没有 lrc 时返回无歌词`() = runBlocking {
        val dir = tmp.newFolder()
        val audio = File(dir, "以父之名.mp3").apply { writeBytes(ByteArray(0)) }

        val result = LyricsRepository().resolve(track(audio.absolutePath))

        assertEquals(LyricsSource.NONE, result.source)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `一行多个时间标签会展开成多行`() {
        val lines = LrcParser.parse("[00:10.00][01:20.00]重复句\n")

        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timeMs)
        assertEquals(80_000L, lines[1].timeMs)
        assertEquals("重复句", lines[0].text)
        assertEquals("重复句", lines[1].text)
    }

    @Test
    fun `定位当前行取最后一个不超过当前位置的行`() {
        val lines = LrcParser.parse("[00:10.00]A\n[00:20.00]B\n[00:20.00]C\n[00:30.00]D\n")

        assertEquals(-1, LrcParser.currentIndex(lines, 5_000L))
        assertEquals(0, LrcParser.currentIndex(lines, 10_000L))
        // 时间戳重复时取最后一个，保证高亮不来回跳
        assertEquals(2, LrcParser.currentIndex(lines, 20_000L))
        assertEquals(3, LrcParser.currentIndex(lines, 99_000L))
    }

    @Test
    fun `非歌词行会被忽略`() {
        val lines = LrcParser.parse("[ar:周杰伦]\n[ti:晴天]\n[00:10.00]真正的歌词\n")

        assertEquals(1, lines.size)
        assertEquals("真正的歌词", lines[0].text)
    }
}
