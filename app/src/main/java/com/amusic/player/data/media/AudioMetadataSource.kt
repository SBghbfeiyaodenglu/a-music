package com.amusic.player.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条元数据：字段名 + 值 */
data class MetadataItem(val label: String, val value: String)

/** 导入时用得上的几个基本标签 */
data class BasicTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: String = "",
    val genre: String = "",
)

/**
 * 读一首歌的元数据。
 *
 * 用系统的 MediaMetadataRetriever：它能直接从标签里取出十几项常见字段，
 * 不用我们为每种容器再手写一遍标签解析。取到什么显示什么，取不到就不显示。
 *
 * 另外单独给出"文件信息"，那几项（路径、大小、修改时间等）和标签无关，总是有的。
 */
class AudioMetadataSource(private val context: Context) {

    /** 只列音频相关的字段，视频那几项对音乐播放器没意义 */
    /**
     * 只列音频相关的字段，视频那几项对音乐播放器没意义。
     * 全部选 API 24 就存在的常量：SAMPLERATE / BITS_PER_SAMPLE 是 API 31 才加的，
     * 引用它们在旧机器上会抛 NoSuchFieldError，所以不用。
     */
    private val tagKeys: List<Pair<Int, String>> = listOf(
        MediaMetadataRetriever.METADATA_KEY_TITLE to "标题",
        MediaMetadataRetriever.METADATA_KEY_ARTIST to "艺术家",
        MediaMetadataRetriever.METADATA_KEY_ALBUM to "专辑",
        MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to "专辑艺术家",
        MediaMetadataRetriever.METADATA_KEY_AUTHOR to "作者",
        MediaMetadataRetriever.METADATA_KEY_COMPOSER to "作曲",
        MediaMetadataRetriever.METADATA_KEY_WRITER to "作词",
        MediaMetadataRetriever.METADATA_KEY_YEAR to "年份",
        MediaMetadataRetriever.METADATA_KEY_DATE to "日期",
        MediaMetadataRetriever.METADATA_KEY_GENRE to "风格",
        MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER to "音轨号",
        MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS to "音轨总数",
        MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER to "碟号",
        MediaMetadataRetriever.METADATA_KEY_DURATION to "时长",
        MediaMetadataRetriever.METADATA_KEY_BITRATE to "码率",
        MediaMetadataRetriever.METADATA_KEY_MIMETYPE to "MIME 类型",
    )

    suspend fun read(path: String): List<MetadataItem> = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext emptyList()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            tagKeys.mapNotNull { (key, label) ->
                val raw = runCatching { retriever.extractMetadata(key) }.getOrNull()
                val value = formatValue(label, raw)
                if (value.isBlank()) null else MetadataItem(label, value)
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 只取导入时用得上的几项。
     * 目录浏览导入时文件可能没被媒体库索引，拿不到专辑年份风格，用标签补一下。
     */
    suspend fun readBasic(path: String): BasicTags = withContext(Dispatchers.IO) {
        if (!File(path).isFile) return@withContext BasicTags()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            BasicTags(
                title = text(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = text(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = text(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM),
                year = text(retriever, MediaMetadataRetriever.METADATA_KEY_YEAR),
                genre = text(retriever, MediaMetadataRetriever.METADATA_KEY_GENRE),
            )
        } catch (e: Exception) {
            BasicTags()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun text(retriever: MediaMetadataRetriever, key: Int): String {
        val raw = runCatching { retriever.extractMetadata(key) }.getOrNull()?.trim().orEmpty()
        if (raw.equals("<unknown>", ignoreCase = true) || raw == "null") return ""
        return raw
    }

    /** 文件本身的信息，和标签无关，一定拿得到 */
    fun fileInfo(path: String): List<MetadataItem> {
        val file = File(path)
        val items = mutableListOf<MetadataItem>()
        if (!file.isFile) return items
        items += MetadataItem("文件名", file.name)
        items += MetadataItem("格式", file.extension.uppercase(Locale.getDefault()))
        items += MetadataItem("所在目录", file.parentFile?.absolutePath.orEmpty())
        items += MetadataItem("大小", formatBytes(file.length()))
        items += MetadataItem(
            "修改时间",
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
        )
        return items
    }

    private fun formatValue(label: String, raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        if (trimmed.equals("<unknown>", ignoreCase = true) || trimmed == "null") return ""
        return when (label) {
            "时长" -> trimmed.toLongOrNull()?.let { ms ->
                val total = (ms / 1000).toInt()
                "%d:%02d".format(total / 60, total % 60)
            } ?: trimmed

            "码率" -> trimmed.toLongOrNull()?.let { bps -> "${bps / 1000} kbps" } ?: trimmed
            else -> trimmed
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "${bytes} B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}
