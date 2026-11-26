package com.amusic.player

import com.amusic.player.data.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LRC 的 `[offset:±毫秒]` 整体偏移标签。
 *
 * 这个标签网上下载的 .lrc 里很常见，处理不对会让整篇歌词偏一大截，
 * 而且是"看起来没问题、就是不同步"那种难查的错，所以单独测。
 *
 * 约定：**有效时间 = 时间戳 + offset**（和主流播放器一致）。
 */
class LrcOffsetTagTest {

    @Test
    fun `没有 offset 标签时时间戳原样`() {
        val lines = LrcParser.parse("[00:10.00]第一句\n[00:20.00]第二句")
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timeMs)
        assertEquals(20_000L, lines[1].timeMs)
        assertEquals(0L, LrcParser.offsetOf("[00:10.00]第一句"))
    }

    @Test
    fun `正偏移让歌词整体延后`() {
        val lines = LrcParser.parse("[offset:+500]\n[00:10.00]第一句\n[00:20.00]第二句")
        assertEquals(10_500L, lines[0].timeMs)
        assertEquals(20_500L, lines[1].timeMs)
    }

    @Test
    fun `负偏移让歌词整体提前`() {
        val lines = LrcParser.parse("[offset:-1500]\n[00:10.00]第一句\n[00:20.00]第二句")
        assertEquals(8_500L, lines[0].timeMs)
        assertEquals(18_500L, lines[1].timeMs)
    }

    @Test
    fun `负偏移把时间戳推到 0 之前时钳到 0`() {
        val lines = LrcParser.parse("[offset:-5000]\n[00:02.00]很早的一句\n[00:10.00]后面一句")
        assertEquals(0L, lines[0].timeMs)
        assertEquals(5_000L, lines[1].timeMs)
        assertTrue("时间不能为负", lines.all { it.timeMs >= 0L })
    }

    @Test
    fun `标签写法容错 空格 大小写 正号`() {
        val variants = listOf(
            "[offset:300]",
            "[OFFSET:300]",
            "[Offset: 300 ]",
            "[ offset : +300 ]",
        )
        variants.forEach { tag ->
            val lines = LrcParser.parse("$tag\n[00:10.00]一句")
            assertEquals("标签写法 $tag 应被识别", 10_300L, lines[0].timeMs)
        }
    }

    @Test
    fun `offset 行本身不会被当成歌词行`() {
        val lines = LrcParser.parse("[offset:+500]\n[00:10.00]唯一一句")
        assertEquals(1, lines.size)
        assertEquals("唯一一句", lines[0].text)
        assertTrue(lines.none { it.text.contains("offset") })
    }

    @Test
    fun `出现多次时以最后一个为准`() {
        val lines = LrcParser.parse("[offset:+100]\n[offset:+900]\n[00:10.00]一句")
        assertEquals(10_900L, lines[0].timeMs)
    }

    @Test
    fun `偏移后仍按时间排序 且二分查找正确`() {
        // 偏移不改变相对顺序，但这里确认排序与高亮索引都没被破坏
        val lines = LrcParser.parse("[offset:+2000]\n[00:30.00]第三句\n[00:10.00]第一句\n[00:20.00]第二句")
        assertEquals(listOf(12_000L, 22_000L, 32_000L), lines.map { it.timeMs })
        assertEquals(-1, LrcParser.currentIndex(lines, 11_999L))
        assertEquals(0, LrcParser.currentIndex(lines, 12_000L))
        assertEquals(1, LrcParser.currentIndex(lines, 25_000L))
        assertEquals(2, LrcParser.currentIndex(lines, 99_000L))
    }

    @Test
    fun `内嵌歌词里的 offset 标签同样生效`() {
        // 内嵌歌词也是同一套 LRC 文本，走同一个解析器，所以这里直接验证解析层面
        val embedded = "LYRICS=[offset:-300][00:10.00]一句"
        val text = embedded.removePrefix("LYRICS=")
        val lines = LrcParser.parse(text)
        assertEquals(9_700L, lines[0].timeMs)
    }

    @Test
    fun `非法或缺失的值按 0 处理 不影响正常解析`() {
        val lines = LrcParser.parse("[offset:abc]\n[00:10.00]一句")
        assertEquals(10_000L, lines[0].timeMs)
        assertEquals(0L, LrcParser.offsetOf("[offset:]"))
        assertEquals(0L, LrcParser.offsetOf(""))
    }
}
