package com.amusic.player.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 取歌曲内嵌的专辑封面，并把它处理成可以直接当界面背景用的模糊图。
 *
 * 需求：默认读歌曲元数据，**有专辑封面就把列表页/歌词页的背景换成它**（做轻微模糊处理），
 * 没有封面就保持默认背景。
 *
 * 三个取舍：
 *
 * **一、取图用系统的 `MediaMetadataRetriever.embeddedPicture`。**
 * 它把 ID3v2 的 APIC、FLAC 的 PICTURE 块、MP4 的 covr 都统一处理好了，
 * 不用我们按容器再写一遍（歌词那边手写是因为要"原地改写"，封面只是只读，没必要）。
 *
 * **二、模糊不用 RenderEffect（API 31+ 才有）也不用 RenderScript（已废弃）。**
 * 用的是"降采样 + 可分离盒式模糊"：纯 Kotlin 也只要几十毫秒，
 * 而且 minSdk 24 到最新版本行为完全一致。
 * 模糊程度刻意**很轻**（半径约为图宽的 1/400，只跑一轮）：背景只是陪衬，
 * 糊到只剩色块反而看不出是专辑封面。
 * 算完的图直接缓存，之后滚动列表、播放进度刷新都不会再碰它。
 *
 * **三、没有封面时，用 [PaletteTable] 里的轮换色调合成一张"假封面"。**
 * 换歌就取第 index+1 套（200 套一轮），**不看病名** —— 这样"换歌"和"换色调"是一一对应的：
 * 不需要根据歌名去推断该用哪套颜色，就算只在两首歌之间来回切，每次也能展示不同的色调。
 * 合成出来的图**走和真封面完全相同的流水线**（模糊 → 色调映射），所以风格是统一的；
 * 连索引都没有时返回 null（界面保持主题纯色背景）。
 *
 * **四、结果按"路径+色调序号"缓存（含"这首歌没有封面"这个结论）。**
 * 一首歌的封面不会变，缓存后切换歌曲不会反复读文件、反复算模糊；
 * LRU 只留几张，占的内存可以忽略（一张 192px 的 ARGB 大概 150KB）。
 */
class AlbumArtSource {

    private val lock = Any()

    /** accessOrder = true：按访问顺序淘汰，最近用过的留在里面 */
    private val cache = LinkedHashMap<String, Bitmap?>(0, 0.75f, true)

    /**
     * 返回可以直接当背景画的模糊封面。
     * 没有封面时用 [paletteIndex] 从 [PaletteTable] 取一套色调合成一张；
     * 两者都没有才返回 null，界面此时保持主题纯色背景。
     */
    suspend fun blurredBackground(path: String?, paletteIndex: Int? = null): Bitmap? {
        if (path.isNullOrBlank() && paletteIndex == null) return null
        val key = "$path|$paletteIndex"

        synchronized(lock) {
            if (cache.containsKey(key)) return cache[key]
        }

        val art = withContext(Dispatchers.IO) { loadAndBlur(path, paletteIndex) }

        synchronized(lock) {
            cache[key] = art
            while (cache.size > MAX_CACHE_ENTRIES) {
                val oldest = cache.entries.iterator()
                oldest.next()
                oldest.remove()
            }
        }
        return art
    }

    private fun loadAndBlur(path: String?, paletteIndex: Int?): Bitmap? {
        val decoded = path?.let { p ->
            if (!File(p).isFile) {
                null
            } else {
                readEmbeddedPicture(p)?.let { decodeDownscaled(it) }
            }
        } ?: paletteIndex?.let { synthesizeCover(it) }

        if (decoded == null) return null
        return runCatching { blur(decoded) }.getOrNull()
    }

    /**
     * 没有封面时，按轮换色调合成一张"假封面"：两个端色的渐变 + 一个偏一侧的亮点。
     *
     * 为什么要画结构而不是纯色块：后面还要重度模糊 + 加噪点，
     * 有一点明暗方向才像"磨砂色调"；纯色块模糊完就是一张死平的单色。
     * 渐变方向也按套数轮换（0°/45°/90°/135°），让相邻两首的"光从哪来"也不一样。
     */
    private fun synthesizeCover(paletteIndex: Int): Bitmap {
        val palette = PaletteTable.at(paletteIndex)
        val light = palette.light
        val dark = palette.dark
        val size = TARGET_MAX_PX
        val pixels = IntArray(size * size)
        val lr = (light shr 16) and 0xFF
        val lg = (light shr 8) and 0xFF
        val lb = light and 0xFF
        val dr = (dark shr 16) and 0xFF
        val dg = (dark shr 8) and 0xFF
        val db = dark and 0xFF
        // 亮点位置按渐变方向轮换（0/1/2/3 → 左上/右上/右下/左下）
        val lx = when (palette.angle) {
            0 -> 0.32f
            1 -> 0.68f
            2 -> 0.68f
            else -> 0.32f
        }
        val ly = if (palette.angle < 2) 0.30f else 0.70f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x - size * lx) / size
                val dy = (y - size * ly) / size
                val t = ((dx * dx + dy * dy) / 0.52f).coerceIn(0f, 1f)
                val r = (lr + (dr - lr) * t).toInt()
                val g = (lg + (dg - lg) * t).toInt()
                val b = (lb + (db - lb) * t).toInt()
                pixels[y * size + x] = (0xFF shl 24) or
                    (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        val bitmap = createBitmap(size, size)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** 元数据里的内嵌封面原图字节；没有就返回 null */
    private fun readEmbeddedPicture(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 先降采样再解码。
     * 封面动辄 1000~3000 像素，直接整张解码既慢又可能 OOM；
     * 反正后面要模糊，留 [TARGET_MAX_PX] 就够用了。
     */
    private fun decodeDownscaled(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_MAX_PX) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size, options) }.getOrNull()
    }

    /**
     * 把封面处理成"全屏高斯模糊 + 可读性兜底"的背景图。
     *
     * 这是行业最主流的做法（网易云 / 汽水 / QQ 播放页都是这个路子）：
     * **封面铺满 → 重度模糊 → 色调映射（提亮 + 压缩 + 限上限）→ 加一点噪点**。
     * 页面自己再叠深色蒙版决定"多黑"（见 `AlbumArtBackground`），
     * 这里只负责把封面变成一张"有色彩氛围、峰值可控"的图。
     *
     *   1) 只解码到 [TARGET_MAX_PX] 这么大，再放大到整屏 —— 细节在这一步就已经没了；
     *   2) 再做多轮盒式模糊，连色块边界都抹平，只剩大片的色调过渡；
     *   3) **色调映射**：提亮保证暗封面也有层次，压缩对比度让结构更柔，
     *      上限 [CAP] 把峰值钉住（页面遮罩再乘一次，所以峰值要留有余量）；
     *   4) **把饱和度拉起来（[SATURATION]）**：这一步是"好不好看"的关键 ——
     *      模糊 + 压暗很容易变成一片灰，提饱和后封面本身偏灰也能出效果；
     *   5) **掺一点点噪点（[GRAIN]）**：深色大面积渐变在 OLED 上会出现色带，
     *      一点点噪声就能打散它，成本为零（图只有 96px）。
     *
     * 边缘用"钳制到边界像素"而不是补透明，这样画面四周不会发虚变暗。
     */
    private fun blur(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 2 || height < 2) return source

        // 重度模糊：半径按图的大小等比给（很小的图上给大半径，等于彻底抹平结构）
        val radius = (minOf(width, height) / BLUR_RADIUS_DIVISOR).coerceIn(2, MAX_BLUR_RADIUS)

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val scratch = IntArray(width * height)
        repeat(BLUR_PASSES) {
            blurHorizontal(pixels, scratch, width, height, radius)
            blurVertical(scratch, pixels, width, height, radius)
        }

        // 色调映射 + 提饱和 + 掺底色 + 噪点（参数含义见 companion 里的注释）
        val baseR = (BASE_COLOR shr 16) and 0xFF
        val baseG = (BASE_COLOR shr 8) and 0xFF
        val baseB = BASE_COLOR and 0xFF
        val random = java.util.Random(GRAIN_SEED)
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = LIFT + ((c shr 16) and 0xFF) * GAIN
            var g = LIFT + ((c shr 8) and 0xFF) * GAIN
            var b = LIFT + (c and 0xFF) * GAIN

            // 提亮会顺带把颜色冲淡，这里把饱和度拉起来，色相保持不动
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r = lum + (r - lum) * SATURATION
            g = lum + (g - lum) * SATURATION
            b = lum + (b - lum) * SATURATION

            // 掺一点主题底色，让背景和界面同色系
            r = r * (1f - BASE_MIX) + baseR * BASE_MIX
            g = g * (1f - BASE_MIX) + baseG * BASE_MIX
            b = b * (1f - BASE_MIX) + baseB * BASE_MIX

            // 噪点：打散深色渐变的色带（幅度很小，肉眼只当"质感"）
            val n = (random.nextInt(2 * GRAIN + 1) - GRAIN).toFloat()
            r += n; g += n; b += n

            pixels[i] = (0xFF shl 24) or
                (r.toInt().coerceIn(0, CAP.toInt()) shl 16) or
                (g.toInt().coerceIn(0, CAP.toInt()) shl 8) or
                b.toInt().coerceIn(0, CAP.toInt())
        }

        val result = createBitmap(width, height)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun blurHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val color = src[row + (x + k).coerceIn(0, width - 1)]
                    a += (color ushr 24) and 0xFF
                    r += (color shr 16) and 0xFF
                    g += (color shr 8) and 0xFF
                    b += color and 0xFF
                }
                val n = radius * 2 + 1
                dst[row + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private fun blurVertical(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val color = src[(y + k).coerceIn(0, height - 1) * width + x]
                    a += (color ushr 24) and 0xFF
                    r += (color shr 16) and 0xFF
                    g += (color shr 8) and 0xFF
                    b += color and 0xFF
                }
                val n = radius * 2 + 1
                dst[y * width + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private companion object {
        /** 缓存几张封面。一张 96px ARGB 只有 36KB，留几张完全没有压力 */
        const val MAX_CACHE_ENTRIES = 4

        /**
         * 解码后的最长边。**故意取很小**：图越小，放大到整屏后越没有细节，
         * 天然的"重度景深"效果。96 配合下面的模糊，出来就是一片色调过渡。
         */
        const val TARGET_MAX_PX = 96

        const val BLUR_PASSES = 3
        const val BLUR_RADIUS_DIVISOR = 8
        const val MAX_BLUR_RADIUS = 16

        /**
         * 色调映射参数（决定背景"看不看得见"与"好不好看"）：
         *
         *   LIFT  —— 整体抬亮，保证再暗的封面也能看出层次
         *   GAIN  —— 压缩对比度：把 0~255 的明暗压到 0~LIFT+255*GAIN，结构更柔
         *   CAP   —— 峰值上限（注意这里留了余量：页面还会再叠 0.46~0.55 的深色蒙版，
         *            165 × 0.54 ≈ 89，浅灰正文仍有 7:1 以上，白字 10:1 以上）
         *   SATURATION —— 【好看的关键】把模糊后发灰的色彩拉回来
         *   BASE_MIX   —— 掺多少主题底色，让背景和界面同色系
         *   GRAIN      —— 噪点幅度，用来打散 OLED 上的色带
         *
         * 注意：模糊 + 压暗之后，若**没有明暗方向、没有饱和度**，封面会被压成一片
         * 均匀的浅灰 —— 光加大模糊半径救不回来。所以抬亮的 LIFT、压缩对比度的 GAIN
         * 和提饱和的 [SATURATION] 三者缺一不可。
         */
        const val LIFT = 8f
        const val GAIN = 0.95f
        const val CAP = 165f
        const val SATURATION = 1.45f
        const val BASE_MIX = 0.08f
        const val GRAIN = 3
        const val GRAIN_SEED = 20260912L

        /** 混合用的底色，与主题背景色一致 */
        const val BASE_COLOR = 0x0E0E10
    }
}
