package com.amusic.player

import com.amusic.player.data.media.PaletteTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景色调表（200 套，换歌轮换）的测试。
 *
 * 重点验三件事：
 *   1. **相邻两套差别要明显** —— 不然"每次换歌都换个颜色"就成了空话
 *   2. **转一圈不重复** —— 200 步之内色相不撞车
 *   3. **亮度落在看得见又不顶破文字对比度的区间** —— 派生底色不能太暗也不能太亮
 */
class PaletteTableTest {

    private fun maxChannel(color: Int) =
        maxOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

    /** 粗略的色相差（只用于断言"差得够远"，不需要很精确） */
    private fun hueOf(color: Int): Float {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        if (d == 0f) return 0f
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return (h + 360f) % 360f
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return minOf(d, 360f - d)
    }

    @Test
    fun `一共 200 套，索引越界也安全`() {
        assertEquals(200, PaletteTable.SIZE)
        assertEquals(PaletteTable.at(0).light, PaletteTable.at(200).light)
        assertEquals(PaletteTable.at(1).light, PaletteTable.at(-199).light)
    }

    @Test
    fun `相邻两套色相差得足够远（换歌一眼能看出）`() {
        for (i in 0 until PaletteTable.SIZE - 1) {
            val d = hueDistance(hueOf(PaletteTable.at(i).light), hueOf(PaletteTable.at(i + 1).light))
            assertTrue("第 $i 套和下一套色相只差 $d 度，太近了", d > 60f)
        }
    }

    @Test
    fun `转一圈之前色相不重复`() {
        // 按 0.1 度取整后应当 200 个各不相同（实测就是 200/200）
        val hues = (0 until PaletteTable.SIZE).map { (hueOf(PaletteTable.at(it).light) * 10).toInt() }
        assertEquals("有色相撞车", PaletteTable.SIZE, hues.toSet().size)
    }

    @Test
    fun `每套的浅色都比深色亮，且两色不一样`() {
        for (i in 0 until PaletteTable.SIZE) {
            val p = PaletteTable.at(i)
            assertTrue("第 $i 套浅色不比深色亮", maxChannel(p.light) > maxChannel(p.dark))
            assertNotEquals("第 $i 套两个端色一样了", p.light, p.dark)
        }
    }

    @Test
    fun `派生亮度落在"看得见又不会顶破文字对比度"的区间`() {
        for (i in 0 until PaletteTable.SIZE) {
            val p = PaletteTable.at(i)
            // 实测：浅色 110~206、深色 54~121（v 越高的档位越亮）。
            // 上界不担心——色调映射会把峰值钉在 165，再乘页面蒙版；
            // 下界才是关键：太暗就和纯黑没区别了。
            assertTrue("第 $i 套浅色超出区间: ${maxChannel(p.light)}", maxChannel(p.light) in 108..215)
            assertTrue("第 $i 套深色超出区间: ${maxChannel(p.dark)}", maxChannel(p.dark) in 50..130)
        }
    }

    @Test
    fun `渐变方向有四种，轮换着用`() {
        val angles = (0 until 40).map { PaletteTable.at(it).angle }.toSet()
        assertEquals(setOf(0, 1, 2, 3), angles)
    }

    @Test
    fun `HSV 转换符合预期`() {
        assertEquals(0xFFFF0000.toInt(), PaletteTable.hsv(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), PaletteTable.hsv(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), PaletteTable.hsv(240f, 1f, 1f))
        assertEquals(0xFF808080.toInt(), PaletteTable.hsv(0f, 0f, 0.5f))
    }
}
