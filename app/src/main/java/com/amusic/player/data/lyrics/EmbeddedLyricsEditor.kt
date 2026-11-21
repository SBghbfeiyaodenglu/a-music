package com.amusic.player.data.lyrics

import com.amusic.player.data.lyrics.EmbeddedLyricsReader.LYRICS_KEYS
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.MAX_TAG_BYTES
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.findTerminator
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.int32
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.int32Le
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.isAscii
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.readUInt32
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.readUInt64
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.skipTerminated
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.syncSafeInt
import com.amusic.player.data.lyrics.EmbeddedLyricsReader.terminatorLength
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/** 内嵌歌词在文件里的位置，用于原地改时间戳（长度必须保持不变才允许写） */
sealed interface EmbeddedLyricsTarget {
    /** 一段文本：USLT 正文 / Vorbis 的 LYRICS 值 / MP4 的 ©lyr 值 */
    data class Text(val offset: Long, val length: Int, val charset: Charset) : EmbeddedLyricsTarget

    /** SYLT：一串 4 字节大端时间戳的位置 */
    data class Timestamps(val offsets: List<Long>) : EmbeddedLyricsTarget
}

/**
 * 改写音频文件里的内嵌歌词时间戳。
 *
 * 关键约束：**只有新内容字节长度和原来完全一致才写**。
 * 时间戳平移（[00:12.30] → [00:12.50]）恰好是等长的，所以绝大多数情况都能原地改。
 * 这样文件其它部分一个字节都不动，不需要重写整个音频文件，也就不会破坏音频数据。
 * 长度对不上（比如分钟数从 99 进位到 100）就返回 false，交给上层如实说明，绝不冒险。
 *
 * 定位不到的情况也返回失败：格式不支持（OGG）、或 ID3v2 标签做了整体去同步
 * （字节位置会漂移，按偏移写会写坏文件）。
 */
object EmbeddedLyricsEditor {

    fun locate(path: String): EmbeddedLyricsTarget? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 12L) return null
                val magic = ByteArray(4)
                raf.seek(0L)
                if (raf.read(magic) < 4) return null
                when {
                    magic.isAscii("ID3") -> locateId3v2(raf)
                    magic.isAscii("fLaC") -> locateFlac(raf)
                    magic.isAscii("OggS") -> null
                    else -> locateMp4(raf)
                }
            }
        }.getOrNull()
    }

    fun rewriteInPlace(path: String, target: EmbeddedLyricsTarget, deltaMs: Long): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        return runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                when (target) {
                    is EmbeddedLyricsTarget.Text -> {
                        val bytes = ByteArray(target.length)
                        raf.seek(target.offset)
                        raf.readFully(bytes)
                        val shifted = LrcTimestampEditor.shiftText(String(bytes, target.charset), deltaMs)
                        val newBytes = shifted.toByteArray(target.charset)
                        // 长度变了就不能原地写，宁可不动
                        if (newBytes.size != bytes.size) return false
                        // 内容一模一样（比如整篇没有时间标签可改）→ 别写、更别谎报"改过了"：
                        // 调用方拿 true 会去告诉用户"内嵌歌词已改写"
                        if (newBytes.contentEquals(bytes)) return false
                        raf.seek(target.offset)
                        raf.write(newBytes)
                        true
                    }

                    is EmbeddedLyricsTarget.Timestamps -> {
                        // ⚠ 先把所有时间戳读完算完，再统一写回。
                        //   边读边写的话，万一某个偏移越界，前面几个已经被改了，
                        //   异常又被外层 runCatching 吞成 false —— 文件已经动了却报告"没动"。
                        val values = IntArray(target.offsets.size)
                        val buffer = ByteArray(4)
                        target.offsets.forEachIndexed { i, at ->
                            if (at < 0 || at + 4 > raf.length()) return false
                            raf.seek(at)
                            raf.readFully(buffer)
                            values[i] = LrcTimestampEditor.shiftTimestamp(
                                int32(buffer, 0).toLong(),
                                deltaMs,
                            ).toInt()
                        }
                        target.offsets.forEachIndexed { i, at ->
                            val value = values[i]
                            raf.seek(at)
                            raf.write(
                                byteArrayOf(
                                    (value shr 24).toByte(),
                                    (value shr 16).toByte(),
                                    (value shr 8).toByte(),
                                    value.toByte(),
                                ),
                            )
                        }
                        true
                    }
                }
            }
        }.getOrDefault(false)
    }

    // ---------------- 各容器的定位 ----------------

    private fun locateId3v2(raf: RandomAccessFile): EmbeddedLyricsTarget? {
        raf.seek(0L)
        val header = ByteArray(10)
        if (raf.read(header) < 10) return null
        val major = header[3].toInt() and 0xFF
        if (major < 3 || major > 4) return null
        val flags = header[5].toInt() and 0xFF
        if (flags and 0x80 != 0) return null
        val tagSize = syncSafeInt(header, 6)
        if (tagSize <= 0) return null
        val body = ByteArray(minOf(tagSize, MAX_TAG_BYTES))
        raf.readFully(body)

        var offset = 0
        if (flags and 0x40 != 0) {
            val extSize = if (major >= 4) syncSafeInt(body, 0) else 4 + int32(body, 0)
            if (extSize > 0 && extSize < body.size) offset = extSize
        }

        var textTarget: EmbeddedLyricsTarget? = null
        while (offset + 10 <= body.size) {
            val id = String(body, offset, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000' || id.isBlank()) break
            var frameSize = if (major >= 4) syncSafeInt(body, offset + 4) else int32(body, offset + 4)
            val frameFlags = ((body[offset + 8].toInt() and 0xFF) shl 8) or
                (body[offset + 9].toInt() and 0xFF)
            var dataStart = offset + 10
            if (major >= 4 && frameFlags and 0x0001 != 0) {
                frameSize -= 4
                dataStart += 4
            }
            if (frameSize <= 0 || dataStart + frameSize > body.size) break
            val data = body.copyOfRange(dataStart, dataStart + frameSize)
            val dataFileOffset = 10L + dataStart
            when (id) {
                "SYLT" -> locateSylt(data, dataFileOffset)?.let { return it }
                "USLT" -> if (textTarget == null) textTarget = locateUslt(data, dataFileOffset)
            }
            offset = dataStart + frameSize
        }
        return textTarget
    }

    private fun locateUslt(data: ByteArray, frameDataFileOffset: Long): EmbeddedLyricsTarget? {
        if (data.size < 4) return null
        val encoding = data[0]
        val textStart = skipTerminated(data, 4, encoding)
        if (textStart < 0 || textStart >= data.size) return null
        return EmbeddedLyricsTarget.Text(
            offset = frameDataFileOffset + textStart,
            length = data.size - textStart,
            charset = charsetOf(encoding),
        )
    }

    private fun locateSylt(data: ByteArray, frameDataFileOffset: Long): EmbeddedLyricsTarget? {
        if (data.size < 6) return null
        val encoding = data[0]
        if ((data[4].toInt() and 0xFF) != 2) return null
        var offset = skipTerminated(data, 6, encoding)
        if (offset < 0) return null
        val offsets = mutableListOf<Long>()
        while (offset < data.size) {
            val textEnd = findTerminator(data, offset, encoding)
            if (textEnd < 0) break
            val timestampAt = textEnd + terminatorLength(encoding)
            if (timestampAt + 4 > data.size) break
            offsets += frameDataFileOffset + timestampAt
            offset = timestampAt + 4
        }
        return if (offsets.isEmpty()) null else EmbeddedLyricsTarget.Timestamps(offsets)
    }

    private fun locateFlac(raf: RandomAccessFile): EmbeddedLyricsTarget? {
        raf.seek(4L)
        while (true) {
            val header = ByteArray(4)
            if (raf.read(header) < 4) return null
            val isLast = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            val blockStart = raf.filePointer
            if (type == 127) return null
            if (type == 4) {
                if (length <= 0 || length > MAX_TAG_BYTES) return null
                val data = ByteArray(length)
                raf.readFully(data)
                return locateVorbis(data, blockStart)
            }
            if (isLast) return null
            raf.seek(blockStart + length)
        }
    }

    private fun locateVorbis(data: ByteArray, blockStart: Long): EmbeddedLyricsTarget? {
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
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).trim().uppercase()
                if (key in LYRICS_KEYS) {
                    return EmbeddedLyricsTarget.Text(
                        offset = blockStart + offset + eq + 1,
                        length = length - eq - 1,
                        charset = Charsets.UTF_8,
                    )
                }
            }
            offset += length
        }
        return null
    }

    private fun locateMp4(raf: RandomAccessFile): EmbeddedLyricsTarget? {
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
                locateMp4Lyrics(raf, offset + headerSize, minOf(offset + size, fileLength))
                    ?.let { return it }
            }
            offset += size
        }
        return null
    }

    private fun locateMp4Lyrics(raf: RandomAccessFile, start: Long, end: Long): EmbeddedLyricsTarget? {
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
                "\u00A9lyr" -> return locateMp4Data(raf, bodyStart, bodyEnd)
                "meta" -> locateMp4Lyrics(raf, bodyStart + 4, bodyEnd)?.let { return it }
                "moov", "udta", "ilst", "trak", "mdia", "minf", "stbl" ->
                    locateMp4Lyrics(raf, bodyStart, bodyEnd)?.let { return it }
            }
            offset += size
        }
        return null
    }

    private fun locateMp4Data(raf: RandomAccessFile, start: Long, end: Long): EmbeddedLyricsTarget? {
        var offset = start
        while (offset + 8 <= end) {
            raf.seek(offset)
            val header = ByteArray(8)
            if (raf.read(header) < 8) return null
            val size = readUInt32(header, 0).toInt()
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size < 8) return null
            if (type == "data") {
                // ⚠ size 是文件里写的 32 位无符号数（最大 4GB），畸形文件能写出一个超大值 ——
                //   这里必须先按"这一段还剩多少"和 MAX_TAG_BYTES 双重卡住，否则后面
                //   rewriteInPlace 会 ByteArray(length) 直接申请上 GB 内存，进程被 OOM 干掉。
                //   （EmbeddedLyricsReader.readMp4Data 也是这么卡的，两处必须一致。）
                val available = (end - offset - 8).coerceAtMost(MAX_TAG_BYTES.toLong())
                val textLength = (size - 16).toLong()
                if (textLength <= 0 || textLength > available) return null
                val bodyStart = offset + 8
                raf.seek(bodyStart)
                val meta = ByteArray(minOf(8, size - 8))
                raf.readFully(meta)
                if (meta.size < 8) return null
                val dataType = readUInt32(meta, 0).toInt()
                return EmbeddedLyricsTarget.Text(
                    offset = bodyStart + 8,
                    length = textLength.toInt(),
                    charset = if (dataType == 2) Charsets.UTF_16BE else Charsets.UTF_8,
                )
            }
            offset += size
        }
        return null
    }

    private fun charsetOf(encoding: Byte): Charset = when (encoding.toInt() and 0xFF) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
}
