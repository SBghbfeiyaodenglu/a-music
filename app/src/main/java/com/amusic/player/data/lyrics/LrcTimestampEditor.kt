package com.amusic.player.data.lyrics

/**
 * LRC 时间戳的整体平移。
 *
 * 单独 .lrc 文件和内嵌歌词都用它，保证两处的规则完全一致。
 * 只动形如 [mm:ss.xx] 的标签，其它内容（[ar:]、[ti:]、纯文本行、换行符）一个字节都不碰，
 * 这样在原地改写音频标签时长度才可能保持不变。
 */
object LrcTimestampEditor {

    private val TAG_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 平移文本里所有时间标签；返回的新文本长度通常和原文一致 */
    fun shiftText(text: String, deltaMs: Long): String =
        TAG_REGEX.replace(text) { match -> format(parseTag(match) + deltaMs) }

    /** 平移一个毫秒时间戳（用于 SYLT 这种二进制时间轴） */
    fun shiftTimestamp(timeMs: Long, deltaMs: Long): Long = (timeMs + deltaMs).coerceAtLeast(0L)

    private fun parseTag(match: MatchResult): Long {
        val minutes = match.groupValues[1].toInt()
        val seconds = match.groupValues[2].toInt()
        val fraction = match.groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0
            1 -> fraction.toInt() * 100
            2 -> fraction.toInt() * 10
            else -> fraction.toInt()
        }
        return minutes * 60_000L + seconds * 1_000L + fractionMs
    }

    /** 先四舍五入到百分之一秒再拆分，避免出现 .100 这种进位问题 */
    private fun format(totalMs: Long): String {
        val rounded = ((totalMs.coerceAtLeast(0L) + 5L) / 10L) * 10L
        val mm = rounded / 60_000L
        val ss = (rounded % 60_000L) / 1_000L
        val cs = (rounded % 1_000L) / 10L
        return "[%02d:%02d.%02d]".format(mm, ss, cs)
    }
}
