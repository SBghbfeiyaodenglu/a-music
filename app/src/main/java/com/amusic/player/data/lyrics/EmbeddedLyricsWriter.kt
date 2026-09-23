package com.amusic.player.data.lyrics

import com.amusic.player.data.media.TagWriter

/**
 * 把一段歌词写进音频文件的**内嵌标签**（在线搜索保存、歌词偏移时用）。
 *
 * 实现已经搬到 [TagWriter]（它同时负责"改元数据"），这里只保留"歌词"这一个入口，
 * 免得两处各写一份"改标签区"的危险代码。
 *
 * ```
 * 安全底线：绝不碰音频数据（细节见 TagWriter 的类注释）
 *   FLAC  重建 metadata block 并保持标签区总长不变 → 音频帧一帧都不动；
 *         剩余空间不足 4 字节时把差额补在 vendor 串上，保证长度精确相等；
 *         写回前核对总长度，放不下就拒绝写入。
 *   MP3   保留除歌词帧外的所有帧；标签变长时写临时文件再原子替换。
 *   其它容器（M4A / MP4 / OGG）不支持 —— MP4 的 moov 里有绝对偏移，改大标签会让它失效。
 * ```
 */
object EmbeddedLyricsWriter {

    /** 读文件头判断能不能写内嵌歌词。任何 IO 异常都按"不支持"处理，绝不往外抛。 */
    fun supported(path: String): Boolean = TagWriter.supported(path)

    /**
     * 写入内嵌歌词。
     * @return null 表示成功；否则是**给用户看**的失败原因（上层据此说明"只存了 .lrc"）
     */
    fun write(path: String, lyrics: String): String? {
        if (lyrics.isBlank()) return "歌词内容为空"
        return TagWriter.writeLyrics(path, lyrics)
    }
}
