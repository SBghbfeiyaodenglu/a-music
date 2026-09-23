package com.amusic.player.data.lyrics

import com.amusic.player.data.LrcParser
import com.amusic.player.data.LyricLine
import com.amusic.player.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

enum class LyricsSource(val label: String) {
    LRC_FILE("同名 LRC"),
    EMBEDDED("内嵌歌词"),
    NONE(""),
}

data class ResolvedLyrics(
    val lines: List<LyricLine>,
    val source: LyricsSource,
    /** false 表示没有时间轴，只能当静态歌词显示 */
    val hasTimeline: Boolean,
) {
    companion object {
        val None = ResolvedLyrics(emptyList(), LyricsSource.NONE, false)
    }
}

/** 改写歌词文件的结果 */
data class LrcEditResult(
    val success: Boolean,
    val message: String,
)

/** 有没有 UTF-8 BOM —— 改写时间戳时要原样保留，不能悄悄把它删掉 */
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private class LrcFileContent(
    val text: String,
    val charset: Charset,
    val hasBom: Boolean = false,
)

/**
 * 歌词解析，严格按下面的优先级：
 *
 *   1. 同名 LRC 文件        ← 已实现，支持原地修改时间戳（见 shiftTimestamps）
 *   2. 内嵌歌词             ← 已实现
 *
 * 解析只认这两种来源；"在线搜索歌词"（公开的在线歌词接口，§21）负责**给这两种来源填内容**，
 * 自己不是第三种来源：搜到之后仍然保存成同名 .lrc / 内嵌歌词。
 *
 * 写入规则：同名 .lrc **直接覆盖、不备份**；繁体歌词先转简体。
 */
class LyricsRepository {

    /** 目录 → 文件列表缓存。滚动时不要反复列目录。 */
    private val dirCache = ConcurrentHashMap<String, List<File>>()

    suspend fun resolve(track: TrackEntity): ResolvedLyrics = withContext(Dispatchers.IO) {
        // 1. 同名 LRC 文件
        resolveLrcFile(track.path)?.let { return@withContext it }

        // 2. 内嵌歌词
        resolveEmbedded(track.path)?.let { return@withContext it }

        ResolvedLyrics.None
    }

    /**
     * 读内嵌歌词。
     * 带时间轴（SYLT）直接用；纯文本的能解析出时间戳就按时间轴走，否则当静态歌词显示。
     */
    private fun resolveEmbedded(audioPath: String): ResolvedLyrics? {
        return when (val embedded = EmbeddedLyricsReader.read(audioPath)) {
            null -> null

            is EmbeddedLyrics.Synced ->
                if (embedded.lines.isEmpty()) {
                    null
                } else {
                    ResolvedLyrics(embedded.lines, LyricsSource.EMBEDDED, true)
                }

            is EmbeddedLyrics.Plain -> {
                val parsed = LrcParser.parse(embedded.text)
                val lines = if (parsed.isNotEmpty()) {
                    parsed
                } else {
                    // 纯文本歌词：丢掉 [ar:] 这类标签行，其余每行当一句静态歌词
                    embedded.text.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("[") }
                        .map { LyricLine(0L, it) }
                        .toList()
                }
                if (lines.isEmpty()) {
                    null
                } else {
                    ResolvedLyrics(lines, LyricsSource.EMBEDDED, lines.any { it.timeMs > 0L })
                }
            }
        }
    }

    /** 用户可能刚改了 .lrc，需要时清掉目录缓存重新查找。 */
    fun invalidate(audioPath: String) {
        File(audioPath).parentFile?.absolutePath?.let { dirCache.remove(it) }
    }

    /**
     * 把同名 .lrc 里的所有时间戳整体平移 [deltaMs]，写回原文件，然后清缓存。
     *
     * 为什么要真的改文件：歌词对不上往往是因为这份 .lrc 的时间戳本身不准。
     * 写回文件后，在任何设备、任何播放器里播这首歌都直接是对的，属于一次操作长期受益；
     * 只存在应用内存里的偏移量，换个播放器就没了。
     *
     * 写回时保持原文件编码（UTF-8 或 GBK）不变。**直接覆盖原文件，不留备份**
     * （不保留原始 LRC 的副本）。
     */
    suspend fun shiftTimestamps(audioPath: String, deltaMs: Long): LrcEditResult = withContext(Dispatchers.IO) {
        if (deltaMs == 0L) {
            return@withContext LrcEditResult(true, "偏移量为 0，无需修改")
        }
        val file = findLrcFile(audioPath)
            ?: return@withContext LrcEditResult(false, "这首歌没有本地 .lrc 文件，改不了文件")

        val original = runCatching { readLrcFile(file) }.getOrNull()
            ?: return@withContext LrcEditResult(false, "歌词文件读取失败")

        val shifted = shiftTimestampTags(original.text, deltaMs)
        if (shifted == original.text) {
            return@withContext LrcEditResult(false, "这个文件里没有可调整的时间戳")
        }

        val written = runCatching {
            // ⚠ 原文件带 UTF-8 BOM 的话要原样写回去：readLrcFile 把 BOM 剥掉记成 UTF_8，
            //   直接 toByteArray(UTF_8) 不会补回来 —— 相当于改一次偏移就悄悄删了人家的 BOM。
            val body = shifted.toByteArray(original.charset)
            val out = if (original.hasBom) UTF8_BOM + body else body
            file.writeBytes(out)
            true
        }.getOrDefault(false)
        if (!written) {
            return@withContext LrcEditResult(false, "写入失败，请检查存储权限")
        }

        // 让下次解析重新读文件，而不是拿旧缓存
        invalidate(audioPath)

        LrcEditResult(true, "已写入 ${file.name}")
    }

    /**
     * 歌词偏移：本地同名 .lrc 和内嵌歌词**都改**（有哪个改哪个，两个都有就都改）。
     *
     * 为什么两处都要改：这两份歌词会让用户听到的时间轴不一致。
     * 只改 .lrc 的话，换个只认内嵌歌词的播放器时间轴还是错的。
     *
     * 改完调用方要重新 resolve 一次，界面上才能立刻看到效果。
     */
    suspend fun shiftAllLyrics(audioPath: String, deltaMs: Long): LrcEditResult = withContext(Dispatchers.IO) {
        if (deltaMs == 0L) {
            return@withContext LrcEditResult(true, "偏移量为 0，无需修改")
        }

        val notes = mutableListOf<String>()
        var changed = false

        // 1) 本地同名 .lrc 文件
        if (findLrcFile(audioPath) != null) {
            val result = shiftTimestamps(audioPath, deltaMs)
            if (result.success) {
                changed = true
                notes += "同名 .lrc ${result.message}"
            } else {
                notes += "同名 .lrc 未改（${result.message}）"
            }
        }

        // 2) 文件里的内嵌歌词
        val target = EmbeddedLyricsEditor.locate(audioPath)
        if (target != null) {
            val ok = EmbeddedLyricsEditor.rewriteInPlace(audioPath, target, deltaMs)
            if (ok) {
                changed = true
                notes += "内嵌歌词已改写"
            } else {
                notes += "内嵌歌词未改（长度会变或格式不支持原地改写）"
            }
        }

        if (!changed) {
            val detail = notes.joinToString("；").ifEmpty { "这首歌没有可修改的歌词" }
            return@withContext LrcEditResult(false, detail)
        }

        val seconds = "%.1f".format(deltaMs / 1000.0)
        LrcEditResult(true, "偏移 ${seconds}s 已生效：" + notes.joinToString("；"))
    }

    /** 编辑器用的初稿：当前这首歌的歌词原文，以及它是不是"只有内嵌歌词" */
    data class LyricsDraft(val text: String, val fromEmbedded: Boolean)

    /**
     * 取当前歌词的**原文**给编辑器当初始内容。
     *
     * 优先取同名 .lrc 的原始文本（连时间戳一起，用户改的就是它）；
     * 没有 .lrc 就退回内嵌歌词的原文。
     */
    suspend fun draftTextFor(audioPath: String): LyricsDraft = withContext(Dispatchers.IO) {
        val lrc = findLrcFile(audioPath)
        if (lrc != null) {
            val text = runCatching { readLrcFile(lrc).text }.getOrNull()
            if (!text.isNullOrBlank()) return@withContext LyricsDraft(text, fromEmbedded = false)
        }
        // 内嵌歌词有两种形态：带时间轴的（还原成 [mm:ss.xx] 文本）和纯文本的（原样用）
        val embedded = runCatching { EmbeddedLyricsReader.read(audioPath) }.getOrNull()
        val text = when (embedded) {
            is EmbeddedLyrics.Plain -> embedded.text
            is EmbeddedLyrics.Synced -> embedded.lines.joinToString("\n") { line ->
                val totalSeconds = line.timeMs / 1000
                "[%02d:%02d.%02d]%s".format(
                    totalSeconds / 60,
                    totalSeconds % 60,
                    (line.timeMs % 1000) / 10,
                    line.text,
                )
            }
            null -> ""
        }
        LyricsDraft(text, fromEmbedded = true)
    }

    /**
     * 在线搜索/编辑歌词的保存入口。
     *
     * ```
     * 繁体 → 自动转成简体再存（网上搜到的多是台湾版歌词）
     * 歌词文件：同名 .lrc 直接覆盖（不留 .bak 备份）
     * 可选：同时写进音频的内嵌标签（FLAC / MP3 支持；原有的内嵌歌词会被清掉换成新的）
     * ```
     *
     * 内嵌写失败**不影响** .lrc 已经保存成功这件事，返回值里会分开说清楚。
     */
    suspend fun saveLyrics(audioPath: String, lyrics: String, alsoEmbedded: Boolean): LrcEditResult =
        // ⚠ 整段都得在 IO 线程：writeLrc 自己切了 IO，但**内嵌那一步没有** ——
        //   而 MP3 标签变大时 EmbeddedLyricsWriter 会把整个音频文件复制到临时文件（几十 MB），
        //   留在主线程就是 ANR。调用方 MainScreen 用的是 rememberCoroutineScope().launch（主线程）。
        withContext(Dispatchers.IO) {
            // 先转简体，再让 .lrc 和内嵌歌词用同一份文本，避免两边内容不一致
            val prepared = prepare(lyrics)
            val lrcResult = writeLrc(audioPath, prepared)
            if (!lrcResult.success || !alsoEmbedded) return@withContext lrcResult

            val embeddedError = EmbeddedLyricsWriter.write(audioPath, prepared.text)
            invalidate(audioPath)   // 内嵌变了，缓存里的歌词要重读
            val extra = if (embeddedError == null) {
                "；内嵌歌词也已写入"
            } else {
                "；内嵌歌词没写入（$embeddedError）"
            }
            LrcEditResult(true, lrcResult.message + extra)
        }

    /**
     * 把用户编辑好的歌词文本写成这首歌的同名 .lrc（[LyricsEditorDialog] 用）。
     *
     * 同名 .lrc 存在就**直接覆盖**（不留 .bak）；歌词是繁体时先转成简体。
     */
    suspend fun saveLyricsText(audioPath: String, rawText: String): LrcEditResult =
        writeLrc(audioPath, prepare(rawText))

    // ---------------- 内部实现 ----------------

    /** 待写入的歌词：正文 + 是不是刚从繁体转过来的（用来提示用户） */
    private class PreparedLyrics(val text: String, val converted: Boolean)

    private fun prepare(rawText: String): PreparedLyrics {
        val simplified = LyricsTextConverter.toSimplified(rawText)
        return PreparedLyrics(simplified, converted = simplified != rawText)
    }

    private suspend fun writeLrc(audioPath: String, prepared: PreparedLyrics): LrcEditResult =
        withContext(Dispatchers.IO) {
            val rawText = prepared.text
            if (rawText.isBlank()) {
                return@withContext LrcEditResult(false, "歌词内容为空，没有保存")
            }
            val audio = File(audioPath)
            val dir = audio.parentFile
                ?: return@withContext LrcEditResult(false, "找不到歌曲所在目录")
            val base = audio.name.substringBeforeLast('.', audio.name)
            if (base.isEmpty()) {
                return@withContext LrcEditResult(false, "文件名异常，无法生成歌词文件")
            }

            val target = File(dir, "$base.lrc")
            val written = runCatching {
                target.writeBytes(rawText.toByteArray(Charsets.UTF_8))
                true
            }.getOrDefault(false)
            if (!written) {
                return@withContext LrcEditResult(false, "写入失败，请检查存储权限")
            }

            invalidate(audioPath)
            val count = runCatching { LrcParser.parse(rawText).size }.getOrDefault(0)
            val notes = mutableListOf<String>()
            if (prepared.converted) notes += "繁体已转成简体"
            notes += if (count > 0) "含 $count 条时间戳，可同步滚动" else "没有时间戳，会作为静态歌词显示"
            LrcEditResult(true, "已保存为 ${target.name}（${notes.joinToString("；")}）")
        }

    private fun resolveLrcFile(audioPath: String): ResolvedLyrics? {
        val file = findLrcFile(audioPath) ?: return null
        val content = runCatching { readLrcFile(file) }.getOrNull() ?: return null
        if (content.text.isBlank()) return null

        val parsed = runCatching { LrcParser.parse(content.text) }.getOrDefault(emptyList())
        val lines = if (parsed.isNotEmpty()) {
            parsed
        } else {
            // .lrc 里没有时间标签时也要能用：按静态歌词显示。
            // 网上抄来的 .lrc 常常整篇没有时间戳，不能直接返回 null，
            // 否则等于"文件明明在却当作没歌词"。
            content.text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("[") }
                .map { LyricLine(0L, it) }
                .toList()
        }
        if (lines.isEmpty()) return null
        return ResolvedLyrics(
            lines = lines,
            source = LyricsSource.LRC_FILE,
            hasTimeline = lines.any { it.timeMs > 0L },
        )
    }

    /** 去掉扩展名后同名的 .lrc，扩展名大小写不敏感。 */
    private fun findLrcFile(audioPath: String): File? {
        val audio = File(audioPath)
        val dir = audio.parentFile ?: return null
        val base = audio.name.substringBeforeLast('.', "")
        if (base.isEmpty()) return null

        val files = dirCache.getOrPut(dir.absolutePath) {
            dir.listFiles()?.toList().orEmpty()
        }
        return files.firstOrNull { candidate ->
            candidate.isFile &&
                candidate.extension.equals("lrc", ignoreCase = true) &&
                candidate.name.substringBeforeLast('.', "").equals(base, ignoreCase = true)
        }
    }

    /** 只调整形如 [mm:ss.xx] 的时间标签；换行符等其它内容一个字节都不动 */
    private fun shiftTimestampTags(text: String, deltaMs: Long): String =
        LrcTimestampEditor.shiftText(text, deltaMs)

    /**
     * 读取歌词文本并记住编码。
     * 中文 LRC 有相当一部分是 GBK，UTF-8 严格解码失败时退回 GBK，否则会整篇乱码；
     * 记住编码是为了改完写回时保持原编码不变。
     */
    private fun readLrcFile(file: File): LrcFileContent {
        val bytes = file.readBytes()
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return LrcFileContent(
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8),
                Charsets.UTF_8,
                hasBom = true,
            )
        }
        return try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            LrcFileContent(text, Charsets.UTF_8)
        } catch (e: CharacterCodingException) {
            // 退回 GBK（中文 LRC 常见编码）；GBK 也解不开时用 ISO-8859-1，
            // 它逐字节映射，写回时能原样保留字节，不会进一步破坏内容
            val gbk = runCatching { charset("GBK") }.getOrNull()
            val decodedByGbk = gbk?.let { candidate ->
                runCatching {
                    candidate.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                }.getOrNull()
            }
            if (gbk != null && decodedByGbk != null) {
                LrcFileContent(decodedByGbk, gbk)
            } else {
                LrcFileContent(String(bytes, Charsets.ISO_8859_1), Charsets.ISO_8859_1)
            }
        }
    }

}
