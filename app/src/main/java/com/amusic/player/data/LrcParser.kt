package com.amusic.player.data

/**
 * 标准 LRC 解析。支持一行多个时间标签（`[00:12.30][01:20.00]歌词`），
 * 以及 `.` / `:` 两种毫秒分隔符和 1~3 位毫秒。
 *
 * 还支持整体偏移标签 `[offset:±毫秒]`：这是 LRC 格式自带的字段，用来整体提前/延后歌词。
 * 网上下载的 .lrc 里很常见，必须解析出来：当成"看不懂的行"跳过的结果是
 * 这些文件整体偏一大截（偏移多少就偏多少）。
 */
object LrcParser {

    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * 整体偏移标签，例如 `[offset:+500]`、`[offset:-200]`、`[offset: 300]`。
     * 大小写不敏感，值前面允许有空格和正负号。
     */
    private val OFFSET_TAG = Regex("""\[\s*offset\s*:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /**
     * 取文件里的整体偏移（毫秒）。约定与主流播放器一致：**有效时间 = 时间戳 + offset**，
     * 即 `+500` 表示歌词整体晚 0.5 秒出现，`-500` 表示提前 0.5 秒。
     *
     * 出现多次时取最后一个：极少见，取最后一个更接近"后写的覆盖前面的"。
     */
    fun offsetOf(raw: String): Long =
        OFFSET_TAG.findAll(raw).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    fun parse(raw: String): List<LyricLine> {
        val offset = offsetOf(raw)
        val result = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { line ->
            val tags = TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            // ⚠ 正文要接在**最后一个时间标签**之后，而不是"最后一个 ] 之后"：
            //   歌词正文自己带 ] 时（例如「[00:12.00]她问“为什么]”」），
            //   按最后一个 ] 截会把「她问“为什么」整段吃掉。
            val text = line.substring(tags.last().range.last + 1).trim()
            tags.forEach { tag ->
                val minutes = tag.groupValues[1].toInt()
                val seconds = tag.groupValues[2].toInt()
                val fraction = tag.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0
                    1 -> fraction.toInt() * 100
                    2 -> fraction.toInt() * 10
                    else -> fraction.toInt()
                }
                // 偏移可能把第一句推到 0 之前，钳到 0，免得出现负时间
                val timeMs = minutes * 60_000L + seconds * 1_000L + millis + offset
                result += LyricLine(timeMs.coerceAtLeast(0L), text)
            }
        }
        return result.sortedBy { it.timeMs }
    }

    /**
     * 返回 [positionMs] 时刻应该高亮的行号；还没到第一行时返回 -1。
     * 用二分找"最后一个 timeMs <= positionMs 的行"，重复时间戳也能得到稳定结果。
     */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
