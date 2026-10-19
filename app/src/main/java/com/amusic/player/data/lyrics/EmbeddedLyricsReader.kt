package com.amusic.player.data.lyrics

import com.amusic.player.data.LyricLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** 从音频标签里读出来的内嵌歌词 */
sealed interface EmbeddedLyrics {
    /** 带时间轴（ID3v2 的 SYLT 帧） */
    data class Synced(val lines: List<LyricLine>) : EmbeddedLyrics

    /** 纯文本歌词，没有时间信息（USLT / Vorbis Comment / ©lyr） */
    data class Plain(val text: String) : EmbeddedLyrics
}

/**
 * 读取音频文件里的内嵌歌词。
 *
 * Media3 / ExoPlayer 和系统的 MediaMetadataRetriever 都不提供歌词字段，只能自己解析标签：
 *
 *   MP3        ID3v2 的 USLT（纯文本）和 SYLT（带时间轴）
 *   FLAC       Vorbis Comment 的 LYRICS / UNSYNCEDLYRICS
 *   M4A / MP4  ©lyr 原子
 *   OGG        Vorbis Comment 要跨页解析，暂不支持
 *
 * 只读文件头部的标签区，不会把整个音频读进内存。
 */
object EmbeddedLyricsReader {

    /** 单个标签块的最大读取量，防止异常文件把内存吃光 */
    internal const val MAX_TAG_BYTES = 8 * 1024 * 1024

    internal val LYRICS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRIC")

    fun read(path: String): EmbeddedLyrics? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 12L) return null
                val magic = ByteArray(4)
                raf.seek(0L)
                if (raf.read(magic) < 4) return null
                when {
                    magic.isAscii("ID3") -> readId3v2(raf)
                    magic.isAscii("fLaC") -> readFlac(raf)
                    magic.isAscii("OggS") -> null
                    else -> readMp4(raf)
                }
            }
        }.getOrNull()
    }

    // ---------------- MP3 / ID3v2 ----------------

    private fun readId3v2(raf: RandomAccessFile): EmbeddedLyrics? {
        raf.seek(0L)
        val header = ByteArray(10)
        if (raf.read(header) < 10) return null
        val major = header[3].toInt() and 0xFF
        if (major < 3 || major > 4) return null
        val flags = header[5].toInt() and 0xFF
        val tagSize = syncSafeInt(header, 6)
        if (tagSize <= 0) return null

        var body = ByteArray(minOf(tagSize, MAX_TAG_BYTES))
        raf.readFully(body)
        if (flags and 0x80 != 0) body = deUnsynchronise(body)

        var offset = 0
        if (flags and 0x40 != 0) {
            // 扩展头：v2.4 的长度包含自身，v2.3 的不包含
            val extSize = if (major >= 4) syncSafeInt(body, 0) else 4 + int32(body, 0)
            if (extSize > 0 && extSize < body.size) offset = extSize
        }

        var plainLyrics: String? = null
        while (offset + 10 <= body.size) {
            val id = String(body, offset, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000' || id.isBlank()) break
            var frameSize = if (major >= 4) syncSafeInt(body, offset + 4) else int32(body, offset + 4)
            val frameFlags = ((body[offset + 8].toInt() and 0xFF) shl 8) or
                (body[offset + 9].toInt() and 0xFF)
            var dataStart = offset + 10
            if (major >= 4 && frameFlags and 0x0001 != 0) {
                // v2.4 的数据长度指示器
                frameSize -= 4
                dataStart += 4
            }
            if (frameSize <= 0 || dataStart + frameSize > body.size) break
            val data = body.copyOfRange(dataStart, dataStart + frameSize)

            when (id) {
                // 有时间轴的最好，直接返回
                "SYLT" -> parseSylt(data)?.let { return it }
                "USLT" -> if (plainLyrics == null) plainLyrics = parseUslt(data)
            }
            offset = dataStart + frameSize
        }
        return plainLyrics?.let { EmbeddedLyrics.Plain(it) }
    }

    private fun parseUslt(data: ByteArray): String? {
        if (data.size < 4) return null
        val encoding = data[0]
        val textStart = skipTerminated(data, 4, encoding)
        if (textStart < 0 || textStart >= data.size) return null
        return decode(data, textStart, data.size, encoding).trim().ifEmpty { null }
    }

    private fun parseSylt(data: ByteArray): EmbeddedLyrics? {
        if (data.size < 6) return null
        val encoding = data[0]
        val timestampFormat = data[4].toInt() and 0xFF
        // 1 = MPEG 帧数（需要码率才能换算），2 = 毫秒
        if (timestampFormat != 2) return null
        var offset = skipTerminated(data, 6, encoding)
        if (offset < 0) return null

        val lines = mutableListOf<LyricLine>()
        while (offset < data.size) {
            val textEnd = findTerminator(data, offset, encoding)
            if (textEnd < 0) break
            val text = decode(data, offset, textEnd, encoding)
            val timestampAt = textEnd + terminatorLength(encoding)
            if (timestampAt + 4 > data.size) break
            val timeMs = int32(data, timestampAt).toLong()
            offset = timestampAt + 4
            if (text.isNotBlank()) lines += LyricLine(timeMs, text)
        }
        return if (lines.isEmpty()) null else EmbeddedLyrics.Synced(lines.sortedBy { it.timeMs })
    }

    // ---------------- FLAC ----------------

    private fun readFlac(raf: RandomAccessFile): EmbeddedLyrics? {
        raf.seek(4L)
        while (true) {
            val header = ByteArray(4)
            if (raf.read(header) < 4) return null
            val isLast = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            if (type == 127) return null
            if (type == 4) {
                if (length <= 0 || length > MAX_TAG_BYTES) return null
                val data = ByteArray(length)
                raf.readFully(data)
                return parseVorbisComment(data)
            }
            if (isLast) return null
            raf.seek(raf.filePointer + length)
        }
    }

    private fun parseVorbisComment(data: ByteArray): EmbeddedLyrics? {
        if (data.size < 8) return null
        var offset = 0
        val vendorLength = int32Le(data, offset)
        offset += 4
        if (vendorLength < 0 || offset + vendorLength + 4 > data.size) return null
        offset += vendorLength
        val count = int32Le(data, offset)
        offset += 4

        repeat(count.coerceIn(0, 4096)) {
            if (offset + 4 > data.size) return null
            val length = int32Le(data, offset)
            offset += 4
            if (length < 0 || offset + length > data.size) return null
            val entry = String(data, offset, length, Charsets.UTF_8)
            offset += length
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).trim().uppercase()
                if (key in LYRICS_KEYS) {
                    val value = entry.substring(eq + 1).trim()
                    if (value.isNotEmpty()) return EmbeddedLyrics.Plain(value)
                }
            }
        }
        return null
    }

    // ---------------- MP4 / M4A ----------------

    private fun readMp4(raf: RandomAccessFile): EmbeddedLyrics? {
        val fileLength = raf.length()
        var offset = 0L
        while (offset + 8 <= fileLength) {
            raf.seek(offset)
            val header = ByteArray(8)
            if (raf.read(header) < 8) return null
            var size = readUInt32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) {
                val big = ByteArray(8)
                raf.readFully(big)
                size = readUInt64(big)
                headerSize = 16L
            } else if (size == 0L) {
                size = fileLength - offset
            }
            if (size < headerSize) return null
            if (type == "moov") {
                findMp4Lyrics(raf, offset + headerSize, minOf(offset + size, fileLength))?.let { return it }
            }
            offset += size
        }
        return null
    }

    private fun findMp4Lyrics(raf: RandomAccessFile, start: Long, end: Long): EmbeddedLyrics? {
        var offset = start
        while (offset + 8 <= end) {
            raf.seek(offset)
            val header = ByteArray(8)
            if (raf.read(header) < 8) return null
            var size = readUInt32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) {
                val big = ByteArray(8)
                raf.readFully(big)
                size = readUInt64(big)
                headerSize = 16L
            } else if (size == 0L) {
                size = end - offset
            }
            if (size < headerSize) return null
            val bodyStart = offset + headerSize
            val bodyEnd = minOf(offset + size, end)

            when (type) {
                "\u00A9lyr" -> return readMp4Data(raf, bodyStart, bodyEnd)?.let { EmbeddedLyrics.Plain(it) }
                "meta" -> findMp4Lyrics(raf, bodyStart + 4, bodyEnd)?.let { return it }
                "moov", "udta", "ilst", "trak", "mdia", "minf", "stbl" ->
                    findMp4Lyrics(raf, bodyStart, bodyEnd)?.let { return it }
            }
            offset += size
        }
        return null
    }

    private fun readMp4Data(raf: RandomAccessFile, start: Long, end: Long): String? {
        var offset = start
        while (offset + 8 <= end) {
            raf.seek(offset)
            val header = ByteArray(8)
            if (raf.read(header) < 8) return null
            val size = readUInt32(header, 0).toInt()
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size < 8) return null
            if (type == "data") {
                // ⚠ size 是文件里写的 32 位无符号数（最大 4GB），不能直接拿来 new ByteArray：
                //   先按"这一段还剩多少"和 MAX_TAG_BYTES 双重卡住，畸形文件才不会让我们申请上 GB
                val available = (end - offset - 8).coerceAtMost(MAX_TAG_BYTES.toLong())
                val wanted = (size - 8).toLong()
                if (wanted <= 0 || wanted > available) return null
                val body = ByteArray(wanted.toInt())
                raf.readFully(body)
                if (body.size < 8) return null
                val dataType = readUInt32(body, 0).toInt()
                val text = if (dataType == 2) {
                    String(body, 8, body.size - 8, Charsets.UTF_16BE)
                } else {
                    String(body, 8, body.size - 8, Charsets.UTF_8)
                }
                return text.trim().ifEmpty { null }
            }
            offset += size
        }
        return null
    }

    // ---------------- 工具 ----------------

    internal fun ByteArray.isAscii(text: String): Boolean {
        if (size < text.length) return false
        return text.indices.all { this[it] == text[it].code.toByte() }
    }

    internal fun decode(data: ByteArray, from: Int, to: Int, encoding: Byte): String {
        if (to <= from) return ""
        val slice = data.copyOfRange(from, to)
        val charset = when (encoding.toInt() and 0xFF) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return runCatching { String(slice, charset) }.getOrDefault("")
    }

    internal fun terminatorLength(encoding: Byte): Int =
        if ((encoding.toInt() and 0xFF) == 1 || (encoding.toInt() and 0xFF) == 2) 2 else 1

    internal fun findTerminator(data: ByteArray, from: Int, encoding: Byte): Int {
        val term = terminatorLength(encoding)
        var i = from
        while (i + term <= data.size) {
            if (term == 1) {
                if (data[i] == 0.toByte()) return i
            } else {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
            }
            i += term
        }
        return -1
    }

    internal fun skipTerminated(data: ByteArray, from: Int, encoding: Byte): Int {
        val at = findTerminator(data, from, encoding)
        return if (at < 0) -1 else at + terminatorLength(encoding)
    }

    internal fun syncSafeInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    internal fun int32(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    internal fun int32Le(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    internal fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    internal fun readUInt64(data: ByteArray): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[i].toLong() and 0xFF)
        }
        return value
    }

    private fun deUnsynchronise(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val byte = data[i]
            out.write(byte.toInt())
            if (byte == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0x00.toByte()) {
                i++ // 跳过为避免同步而插入的 0x00
            }
            i++
        }
        return out.toByteArray()
    }
}
