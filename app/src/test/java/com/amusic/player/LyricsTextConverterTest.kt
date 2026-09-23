package com.amusic.player

import com.amusic.player.data.lyrics.LyricsTextConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 繁→简转换的测试。
 *
 * 重点不是"能转"，而是**不出错**：繁体歌词里有大量"一个字对多个简体字"的情况
 * （著/着、乾/干、裡/里…），转错会把歌词改坏。所以这里既测常见繁体行，
 * 也测那些必须保持不变的书名/成语类搭配。
 */
class LyricsTextConverterTest {

    private fun convert(text: String) = LyricsTextConverter.toSimplified(text)

    @Test
    fun `常见繁体歌词行能转成简体`() {
        assertEquals("下雨天了怎么办 我好想你", convert("下雨天了怎麼辦 我好想你"))
        assertEquals("我不敢打给你 我找不到原因", convert("我不敢打給你 我找不到原因"))
        assertEquals("为什么失眠的声音", convert("為什麼失眠的聲音"))
        assertEquals("变得好熟悉", convert("變得好熟悉"))
    }

    @Test
    fun `时间戳和数字英文原样保留`() {
        val line = "[00:12.34]Baby 我愛你 2026"
        assertEquals("[00:12.34]Baby 我爱你 2026", convert(line))
    }

    @Test
    fun `本来就是简体时原样返回同一个对象`() {
        val simplified = "[00:01.00]这是简体歌词\n[00:05.00]不用转换"
        assertSame(simplified, convert(simplified))
    }

    @Test
    fun `空字符串安全`() {
        assertEquals("", convert(""))
    }

    /** 「著」在简体里也是规范字：著名/著作/顯著 必须保留，不能转成「着」 */
    @Test
    fun `著名著作这类词不会被转错`() {
        assertEquals("著名作家", convert("著名作家"))
        assertEquals("著作权", convert("著作權"))
        assertEquals("显著提升", convert("顯著提升"))
        assertEquals("原著小说", convert("原著小說"))
    }

    /** 但歌词里"V+著"的用法在简体里必须写「着」 */
    @Test
    fun `歌词里的著按着转`() {
        assertEquals("看着你的脸", convert("看著你的臉"))
        assertEquals("我想着你的笑容", convert("我想著你的笑容"))
        assertEquals("爱着你", convert("愛著你"))
        assertEquals("睡不着", convert("睡不著"))
        assertEquals("为什么你还笑着", convert("為什麼你還笑著"))
        assertEquals("着急", convert("著急"))
        assertEquals("沿着河边走", convert("沿著河邊走"))
    }

    /** 其它高频歧义字：靠词组规则才不出错 */
    @Test
    fun `其它歧义字按词组判断`() {
        assertEquals("干净", convert("乾淨"))
        assertEquals("乾隆", convert("乾隆"))          // 不能变成「干隆」
        assertEquals("皇后", convert("皇后"))          // 不能变成「后后」
        assertEquals("后面", convert("後面"))
        assertEquals("头发", convert("頭髮"))
        assertEquals("理发", convert("理髮"))
        assertEquals("一只狗", convert("一隻狗"))
        assertEquals("只有", convert("只有"))
        assertEquals("台北", convert("臺北"))
        assertEquals("面包", convert("麵包"))
        assertEquals("面孔", convert("面孔"))
        assertEquals("里面", convert("裡面"))
        assertEquals("周末", convert("週末"))
    }

    @Test
    fun `整段歌词逐行转换`() {
        val traditional = """
            [00:02.04]下雨天了怎麼辦 我好想你
            [00:09.11]我不敢打給你 我找不到原因
            [00:16.87]為什麼失眠的聲音
        """.trimIndent()
        val expected = """
            [00:02.04]下雨天了怎么办 我好想你
            [00:09.11]我不敢打给你 我找不到原因
            [00:16.87]为什么失眠的声音
        """.trimIndent()
        assertEquals(expected, convert(traditional))
    }

    @Test
    fun `转换表规模合理（防止生成脚本出错导致表变空）`() {
        // 表太小说明生成脚本坏了，转换会大面积失效；这里留一道防线
        assertTrue("单字表太小", LyricsTextConverter.tableSize > 3000)
        assertTrue("词组表太小", LyricsTextConverter.phraseCount > 300)
    }

    @Test
    fun `台湾异体字也要转成简体（妳-牠-祂）`() {
        // OpenCC 按"标准"不转这几个字，但台湾歌词里到处都是，所以必须单独补进来
        assertEquals("你", LyricsTextConverter.toSimplified("妳"))
        assertEquals("它", LyricsTextConverter.toSimplified("牠"))
        assertEquals("他", LyricsTextConverter.toSimplified("祂"))
        assertEquals("你过得好不好", LyricsTextConverter.toSimplified("妳過得好不好"))
    }
}
