package com.amusic.player.data.media

/**
 * 背景色调表：200 套渐变，换歌时轮换下一套。
 *
 * 用途：**没有专辑封面的歌**用它派生一个底色，再走和真封面完全相同的流水线
 * （模糊 → 色调映射 → 页面深色蒙版），所以有没有封面的歌风格是一致的。
 * 有封面的歌仍然用封面（那才是"和你在听的专辑有联系"的部分）。
 *
 * 为什么不是手写 200 行颜色表：这 200 套是用**同一套算式**算出来的，
 * 好处是零数据、可单测、改档位只要动两个常数。想手工微调某几套时再换成表也来得及。
 *
 * ## 轮换规则（关键）
 * 每次换歌取下一套（`index + 1`）。色相按**金色角 137.508°** 走：
 *
 * ```
 * hue = (index * 137.508) % 360
 * ```
 *
 * 金色角的好处是**相邻两首歌色相就跳 137.5°**（对侧色），永远一眼看出换了颜色；
 * 同时 200 步之内不会重复，转完一圈才回到起点。
 * 如果改成"色相按 1.8°/步"那种均匀铺开，相邻两首几乎看不出区别 —— 那就白轮换了。
 *
 * 明度档位用 `index % 10`：随轮换缓慢漂移（偶尔亮一点、偶尔沉一点），
 * 但不会出现"这一首很亮、下一首很暗"的跳变。
 */
internal object PaletteTable {

    /** 一共多少套。200 套只是"多少步才循环一圈"，不影响每次换歌的差异大小 */
    const val SIZE = 200

    /** 一套色调：两个渐变端色 + 渐变方向（0..3 → 0°/45°/90°/135°） */
    class Palette(val light: Int, val dark: Int, val angle: Int)

    /** 取第 [index] 套（自动对 SIZE 取模，负数也安全） */
    fun at(index: Int): Palette {
        val i = ((index % SIZE) + SIZE) % SIZE
        val hue = (i * 137.508f) % 360f
        val tier = i % 10                                   // 明度/饱和度档位，缓慢漂移
        val sat = 0.36f + 0.048f * tier
        val value = 0.30f + 0.042f * tier
        val light = hsv(hue, sat, value + 0.13f)
        val dark = hsv((hue + 38f) % 360f, sat + 0.08f, value * 0.70f)
        return Palette(light, dark, (i / 10) % 4)
    }

    /**
     * HSV → ARGB（h: 0..360，s/v: 0..1）。
     * 手写是为了不依赖 android.graphics —— 这段数学能在纯 JVM 单元测试里验证
     * （`PaletteTableTest` 会检查 200 套的色相/亮度分布）。
     */
    fun hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val hh = (h % 360f) / 60f
        val x = c * (1 - kotlin.math.abs(hh % 2f - 1f))
        val (r1, g1, b1) = when {
            hh < 1f -> Triple(c, x, 0f)
            hh < 2f -> Triple(x, c, 0f)
            hh < 3f -> Triple(0f, c, x)
            hh < 4f -> Triple(0f, x, c)
            hh < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun ch(value: Float) = ((value + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}
