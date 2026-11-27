package com.amusic.player.data.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * 音频文件的**标签（元数据）读写**：标题 / 艺术家 / 专辑 / 年份 / 风格 / 音轨号 / 碟号，
 * 外加内嵌歌词。支持 FLAC（Vorbis Comment）和 MP3（ID3v2），其它容器**不支持**。
 *
 * 这个文件里的"写"是**全项目最危险的一段代码**，所以它集中在一处、并且只干一件事：
 * 改标签区，绝不碰音频数据。两种容器各自的约束：
 *
 * ```
 * FLAC  标签区（metadata blocks）在文件最前面，后面全是音频帧。
 *       重建这些块，并**让整个标签区总长度保持不变**（不够就用 PADDING 补满，多了就吃掉 PADDING）。
 *       → 音频帧一个字节都不会移动。
 *       ⚠ 剩余空间只有 1~3 字节时**不能**硬塞 PADDING（块头本身就要 4 字节，会写越界到音频帧上），
 *         这种情况把那几字节补在 vendor 串尾巴上，保证总长**精确相等**；
 *         写回**之前**再核对一次总长度，对不上就整段放弃。
 *       放不下就**拒绝写入**（不选"整体后移音频"：FLAC 的 SEEKTABLE 记的是相对第一个音频帧的
 *       字节偏移，后移会让这些偏移全错）。
 *
 * MP3   ID3v2 标签也在最前面，但 MP3 帧自带同步字、没有绝对偏移，所以标签变大是安全的。
 *       重写标签（**保留除被改字段外的所有帧**），放得下保持原大小、放不下就变大 + 临时文件原子替换。
 *
 * 其它 M4A / MP4 / OGG：不支持。MP4 的 moov 里有指向音频数据的绝对偏移，改大标签会让偏移失效。
 * ```
 *
 * 读（[read]）只做解析，不写文件；界面拿它预填编辑框。
 */
object TagWriter {

    // 规范名 → FLAC 的 Vorbis Comment 键
    private val FLAC_KEYS = mapOf(
        "title" to "TITLE",
        "artist" to "ARTIST",
        "album" to "ALBUM",
        "year" to "DATE",
        "genre" to "GENRE",
        "track" to "TRACKNUMBER",
        "disc" to "DISCNUMBER",
        "lyrics" to "LYRICS",
    )

    // 规范名 → ID3v2 的帧 ID（v2.4 的年份帧和 v2.3 不同，写入时按版本挑）
    private val ID3_FRAMES = mapOf(
        "title" to "TIT2",
        "artist" to "TPE1",
        "album" to "TALB",
        "year" to "TYER",
        "genre" to "TCON",
        "track" to "TRCK",
        "disc" to "TPOS",
        "lyrics" to "USLT",
    )

    // 写歌词时要一并清掉的历史键（不同工具写法不一样，留着播放器可能读到旧的那份）
    private val LYRIC_FLAC_KEYS = listOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRIC")
    private val LYRIC_ID3_FRAMES = listOf("USLT", "SYLT")

    private const val TYPE_PADDING = 1
    private const val TYPE_VORBIS_COMMENT = 4
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    // ---------------- 对外接口 ----------------

    /** 能不能改这个文件的标签（任何 IO 异常都按"不支持"处理，绝不往外抛） */
    fun supported(path: String): Boolean = runCatching {
        val magic = readMagic(path) ?: return@runCatching false
        magic.matchesAscii("fLaC") || magic.matchesAscii("ID3")
    }.getOrDefault(false)

    /**
     * 读当前标签。键是 [FIELDS] 里的规范名（外加 `lyrics`）；取不到的字段不会出现在结果里。
     * 读不出来就返回空表（不抛异常）。
     */
    fun read(path: String): Map<String, String> = runCatching {
        val magic = readMagic(path) ?: return@runCatching emptyMap()
        when {
            magic.matchesAscii("fLaC") -> readFlac(path)
            magic.matchesAscii("ID3") -> readId3v2(path)
            else -> emptyMap()
        }
    }.getOrDefault(emptyMap())

    /**
     * 改标签。[edits] 的键用 [FIELDS] 的规范名（或 `lyrics`），值为 null 表示**删除**该字段。
     * @return null 表示成功；否则是给用户看的失败原因（文件一个字节都不会动）
     */
    fun write(path: String, edits: Map<String, String?>): String? {
        if (edits.isEmpty()) return null
        if (!File(path).isFile) return "找不到这个音频文件"
        return runCatching {
            // 注意：本方法返回 null 表示**成功**，所以不能用 null 兜底去判断"读不出格式"
            val magic = readMagic(path)
                ?: return@runCatching "认不出这个音频文件的格式（文件是空的或被截断了？）"
            when {
                magic.matchesAscii("fLaC") -> writeFlac(path, edits)
                magic.matchesAscii("ID3") -> writeId3v2(path, edits)
                else -> "这个格式（M4A / MP4 / OGG）不支持修改标签"
            }
        }.getOrElse { e ->
            // 标签区在文件最前面，中途 IO 失败时文件确实可能被动过 ——
            // 只能说「可能未完整写入」，不能说「未被改动」
            "写入失败（${e.javaClass.simpleName}）：标签可能未完整写入，请检查这首歌"
        }
    }

    /** 写内嵌歌词（原来的 [com.amusic.player.data.lyrics.EmbeddedLyricsWriter.write] 走这里） */
    fun writeLyrics(path: String, lyrics: String): String? {
        if (lyrics.isBlank()) return "歌词内容为空"
        // 两套键名合在一起传：FLAC 侧只认 VC 键、ID3 侧只认帧 ID，互不干扰
        return write(path, flacLyricsEdits(lyrics) + id3LyricsEdits(lyrics))
    }

    /** 清掉所有历史歌词键 + 写入新的 LYRICS（FLAC 用） */
    private fun flacLyricsEdits(lyrics: String): Map<String, String?> =
        LYRIC_FLAC_KEYS.associateWith { null } + ("lyrics" to lyrics)

    /** ID3 侧：删掉 USLT/SYLT 再写新的（键名和 FLAC 不同，用内部标记区分） */
    private fun id3LyricsEdits(lyrics: String): Map<String, String?> =
        LYRIC_ID3_FRAMES.associateWith { null } + ("lyrics" to lyrics)

    // ---------------- 工具 ----------------

    private fun readMagic(path: String): ByteArray? {
        val file = File(path)
        if (!file.isFile) return null
        return RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.seek(0)
            runCatching { raf.readFully(magic) }.getOrNull()?.let { magic }
        }
    }

    private fun synchsafeToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun int32Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun intToSynchsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    // ---------------- FLAC ----------------

    private class FlacBlock(val type: Int, val data: ByteArray)

    private fun readFlac(path: String): Map<String, String> {
        val file = File(path)
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(0)
            val magic = ByteArray(4)
            runCatching { raf.readFully(magic) }.getOrNull() ?: return emptyMap()
            if (!magic.matchesAscii("fLaC")) return emptyMap()
            var last = false
            while (!last) {
                val header = ByteArray(4)
                runCatching { raf.readFully(header) }.getOrNull() ?: return emptyMap()
                last = (header[0].toInt() and 0x80) != 0
                val type = header[0].toInt() and 0x7F
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                val data = ByteArray(length)
                runCatching { raf.readFully(data) }.getOrNull() ?: return emptyMap()
                if (type == TYPE_VORBIS_COMMENT) return fieldsToCanonical(parseVorbisComment(data))
            }
            emptyMap()
        }
    }

    /** Vorbis Comment 的键 → 规范名（键不分大小写） */
    private fun fieldsToCanonical(fields: List<Pair<String, String>>): Map<String, String> {
        val byKey = fields.associate { it.first.uppercase() to it.second }
        val out = mutableMapOf<String, String>()
        for ((canonical, key) in FLAC_KEYS) {
            // 歌词字段：LYRICS 优先，其次 UNSYNCEDLYRICS / LYRIC
            val value = if (canonical == "lyrics") {
                byKey["LYRICS"] ?: byKey["UNSYNCEDLYRICS"] ?: byKey["LYRIC"]
            } else {
                byKey[key]
            }
            if (!value.isNullOrBlank()) out[canonical] = value
        }
        return out
    }

    private fun writeFlac(path: String, edits: Map<String, String?>): String? {
        val file = File(path)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (!magic.matchesAscii("fLaC")) return "不是 FLAC 文件"

            // 1) 把所有 metadata block 读出来（PADDING 不原样保留，最后重新分配）
            val blocks = mutableListOf<FlacBlock>()
            var regionEnd = 4L
            var last = false
            while (!last) {
                val header = ByteArray(4)
                runCatching { raf.readFully(header) }.getOrNull() ?: return "FLAC 标签区不完整"
                last = (header[0].toInt() and 0x80) != 0
                val type = header[0].toInt() and 0x7F
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                val data = ByteArray(length)
                runCatching { raf.readFully(data) }.getOrNull() ?: return "FLAC 标签区不完整"
                if (type != TYPE_PADDING) blocks += FlacBlock(type, data)
                regionEnd += 4 + length
            }

            // 2) 重建 VORBIS_COMMENT：改掉要改的字段，删掉值为 null 的字段
            val vcIndex = blocks.indexOfFirst { it.type == TYPE_VORBIS_COMMENT }
            val oldVc = if (vcIndex >= 0) parseVorbisComment(blocks[vcIndex].data) else emptyList()
            val vcAt = if (vcIndex >= 0) {
                vcIndex
            } else {
                val at = if (blocks.isEmpty()) 0 else 1
                blocks.add(at, FlacBlock(TYPE_VORBIS_COMMENT, ByteArray(0)))
                at
            }
            val fields = applyFlacEdits(oldVc, edits)

            // 3) 标签区总长度必须**一个字节都不变**（音频帧才会原地不动）
            val othersSize = blocks.indices.filter { it != vcAt }.sumOf { 4L + blocks[it].data.size }
            val vcRoom = regionEnd - 4L - othersSize - 4L
            var vc = serializeVorbisComment(fields)
            val leftover = vcRoom - vc.size
            when {
                leftover < 0L -> return "这首歌的标签区里没有足够空间，已放弃修改（文件未被改动）"
                leftover == 0L -> Unit
                // ⚠ 差 1~3 字节时不能加 PADDING（块头就要 4 字节，会写越界到音频帧上），
                //    补在 vendor 串尾巴上，保证总长精确相等
                leftover < 4L -> vc = serializeVorbisComment(fields, vendorPad = leftover.toInt())
                else -> {
                    // ⚠ FLAC 块长度是 24 位：超过 0xFFFFFF 的话块头会装不下（写的时候只取低 24 位），
                    //   而下面按 ByteArray.size 核对总长又是"对得上"的 —— 等于写出一个文件自己读不回来的标签区。
                    //   现实里要触发得有好几 MB 的 PADDING，但宁可直接放弃也不能写出坏文件。
                    val pad = leftover - 4L
                    if (pad > 0xFFFFFFL) {
                        return "这首歌的标签区过大，已放弃修改（文件未被改动）"
                    }
                    blocks += FlacBlock(TYPE_PADDING, ByteArray(pad.toInt()))
                }
            }
            blocks[vcAt] = FlacBlock(TYPE_VORBIS_COMMENT, vc)

            // 4) 写回**之前**核对总长度：对不上就不写
            val total = blocks.sumOf { 4L + it.data.size }
            if (total != regionEnd - 4L) {
                return "标签区长度对不上（$total ≠ ${regionEnd - 4L}），已放弃修改（文件未被改动）"
            }
            raf.seek(4)
            blocks.forEachIndexed { index, block ->
                val isLast = index == blocks.lastIndex
                raf.write(
                    byteArrayOf(
                        ((if (isLast) 0x80 else 0x00) or block.type).toByte(),
                        ((block.data.size shr 16) and 0xFF).toByte(),
                        ((block.data.size shr 8) and 0xFF).toByte(),
                        (block.data.size and 0xFF).toByte(),
                    ),
                )
                raf.write(block.data)
            }
        }
        return null
    }

    /** 把 edits 应用到 Vorbis Comment 字段表上（保留顺序，未知键原样保留） */
    private fun applyFlacEdits(
        old: List<Pair<String, String>>,
        edits: Map<String, String?>,
    ): List<Pair<String, String>> {
        // 规范名 → 要写的新值（含 null＝删除）
        val byCanonicalKey = edits.entries.associate { (canonical, value) ->
            (FLAC_KEYS[canonical] ?: canonical.uppercase()) to value
        }
        val kept = old.filterNot { (key, _) -> key.uppercase() in byCanonicalKey.keys }
        // ⚠ null 才是"删除这个字段"；空字符串是"写成空标签"，两者不能混
        val added = byCanonicalKey.entries
            .filter { it.value != null }
            .map { it.key to it.value!! }
        return kept + added
    }

    private fun parseVorbisComment(data: ByteArray): List<Pair<String, String>> {
        var pos = 0
        fun readInt(): Int? {
            if (pos + 4 > data.size) return null
            val v = (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        if (data.size < 8) return emptyList()
        val vendorLength = readInt() ?: return emptyList()
        if (vendorLength < 0 || pos + vendorLength > data.size) return emptyList()
        pos += vendorLength
        val count = readInt() ?: return emptyList()
        val fields = mutableListOf<Pair<String, String>>()
        repeat(count.coerceAtMost(4096)) {
            val length = readInt() ?: return@repeat
            if (length < 0 || pos + length > data.size) return@repeat
            val text = String(data, pos, length, StandardCharsets.UTF_8)
            pos += length
            val eq = text.indexOf('=')
            if (eq > 0) fields += text.substring(0, eq) to text.substring(eq + 1)
        }
        return fields
    }

    private fun serializeVorbisComment(
        fields: List<Pair<String, String>>,
        vendorPad: Int = 0,
    ): ByteArray {
        val vendor = ("A-Music" + " ".repeat(vendorPad.coerceAtLeast(0)))
            .toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream()
        fun writeInt(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        writeInt(vendor.size)
        out.write(vendor)
        writeInt(fields.size)
        fields.forEach { (key, value) ->
            val bytes = "$key=$value".toByteArray(StandardCharsets.UTF_8)
            writeInt(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    // ---------------- MP3 / ID3v2 ----------------

    private fun readId3v2(path: String): Map<String, String> {
        val file = File(path)
        return RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            raf.seek(0)
            if (raf.read(header) < 10) return emptyMap()
            if (!header.matchesAscii("ID3")) return emptyMap()
            val version = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val declared = synchsafeToInt(header, 6) + 10
            if (declared < 14 || declared > MAX_TAG_BYTES || declared > file.length()) return emptyMap()
            val tag = ByteArray(declared)
            raf.seek(0)
            runCatching { raf.readFully(tag) }.getOrNull() ?: return emptyMap()

            val values = mutableMapOf<String, String>()
            var pos = 10
            if (flags and 0x40 != 0) {
                val extSize = if (version == 4) synchsafeToInt(tag, 10) else 4 + int32Be(tag, 10)
                if (extSize <= 0 || 10 + extSize > declared) return emptyMap()
                pos = 10 + extSize
            }
            while (pos + 10 <= declared) {
                val id = String(tag, pos, 4, StandardCharsets.ISO_8859_1)
                if (id[0] == '\u0000' || id.isBlank()) break
                val size = if (version == 4) synchsafeToInt(tag, pos + 4) else int32Be(tag, pos + 4)
                val total = 10 + size
                if (size <= 0 || pos + total > declared) break
                val payload = tag.copyOfRange(pos + 10, pos + 10 + size)
                values[id] = decodeId3Text(id, payload)
                pos += total
            }
            val out = mutableMapOf<String, String>()
            for ((canonical, frameId) in ID3_FRAMES) {
                val value = values[frameId]
                if (!value.isNullOrBlank()) out.putIfAbsent(canonical, value)
            }
            // 年份在 v2.4 里是 TDRC
            values["TDRC"]?.takeIf { it.isNotBlank() }?.let { out.putIfAbsent("year", it) }
            return out
        }
    }

    /** 解 ID3 文本帧/歌词帧的正文（编码字节：0=Latin1 1=UTF-16(带BOM) 2=UTF-16BE 3=UTF-8） */
    private fun decodeId3Text(id: String, payload: ByteArray): String {
        if (payload.size < 2) return ""
        val encoding = payload[0].toInt() and 0xFF
        // USLT：编码字节 + 3 字节语言 + 描述（以 0 结尾）+ 正文
        val start = if (id == "USLT" || id == "SYLT") {
            if (payload.size < 5) return ""
            var i = 4
            while (i < payload.size && payload[i] != 0.toByte()) i++
            val terminator = if (encoding == 1 || encoding == 2) 2 else 1
            (i + terminator).coerceAtMost(payload.size)
        } else {
            1
        }
        if (start >= payload.size) return ""
        val body = payload.copyOfRange(start, payload.size)
        val charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.UTF_16
        }
        return runCatching { String(body, charset) }.getOrDefault("")
            .trimEnd('\u0000')
            .substringBefore('\u0000')
            .trim()
    }

    /** 按 ID3 版本编码文本（v2.3 只认 Latin1/UTF-16，所以中文必须用 UTF-16 + BOM） */
    private fun encodeId3Text(text: String, version: Int): Pair<Byte, ByteArray> =
        if (version >= 4) {
            0x03.toByte() to text.toByteArray(StandardCharsets.UTF_8)
        } else {
            0x01.toByte() to text.toByteArray(StandardCharsets.UTF_16)   // UTF-16 带 BOM
        }

    private fun buildId3Frame(id: String, payload: ByteArray?, version: Int): ByteArray {
        assert(id.length == 4)
        val body = payload ?: ByteArray(0)
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(StandardCharsets.ISO_8859_1))
        if (version == 4) {
            out.write(intToSynchsafe(body.size))
        } else {
            out.write((body.size shr 24) and 0xFF)
            out.write((body.size shr 16) and 0xFF)
            out.write((body.size shr 8) and 0xFF)
            out.write(body.size and 0xFF)
        }
        out.write(byteArrayOf(0, 0))            // flags
        out.write(body)
        return out.toByteArray()
    }

    private fun writeId3v2(path: String, edits: Map<String, String?>): String? {
        val file = File(path)
        var pendingTemp: File? = null

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            val header = ByteArray(10)
            runCatching { raf.readFully(header) }.getOrNull() ?: return "文件太小，没有 ID3v2 标签"
            if (!header.matchesAscii("ID3")) return "不是 ID3v2 标签"
            val version = header[3].toInt() and 0xFF
            // ⚠ ID3v2 头：3 字节 "ID3" + 版本(第 4 字节) + 修订号(第 5 字节) + flags(第 6 字节)
            val flags = header[5].toInt() and 0xFF
            if (flags and 0x80 != 0) return "这个 MP3 的标签用了去同步存储，暂不支持修改"
            if (version != 3 && version != 4) return "不支持的 ID3v2 版本（$version）"

            val declared = synchsafeToInt(header, 6) + 10
            if (declared < 14 || declared > MAX_TAG_BYTES || declared > file.length()) {
                return "ID3v2 标签长度异常，已放弃修改（文件未被改动）"
            }
            val oldTagSize: Int = declared
            val oldTag = ByteArray(oldTagSize)
            raf.seek(0)
            runCatching { raf.readFully(oldTag) }.getOrNull() ?: return "标签区不完整"

            // 要删/要写的帧
            val drop = mutableSetOf<String>()
            val put = mutableMapOf<String, ByteArray?>()
            for ((canonical, value) in edits) {
                val frameId = ID3_FRAMES[canonical] ?: canonical
                drop += frameId
                // 同上：null＝删除，空字符串＝写空标签
                if (value != null) {
                    put[frameId] = if (frameId == "USLT") {
                        // USLT：编码 + 语言 "XXX" + 空描述（**按编码补终止符**）+ 正文
                        // ⚠ UTF-16 的描述终止符是 2 个 0 字节，只写 1 个的话读取端会从
                        //   正文中间开始解，歌词读回来是乱码
                        val (encoding, body) = encodeId3Text(value, version)
                        val terminator = if (encoding.toInt() == 1 || encoding.toInt() == 2) 2 else 1
                        ByteArrayOutputStream().apply {
                            write(encoding.toInt())
                            write("XXX".toByteArray(StandardCharsets.ISO_8859_1))
                            repeat(terminator) { write(0x00) }
                            write(body)
                        }.toByteArray()
                    } else {
                        val (encoding, body) = encodeId3Text(value, version)
                        ByteArray(1 + body.size).also {
                            it[0] = encoding
                            System.arraycopy(body, 0, it, 1, body.size)
                        }
                    }
                }
            }
            // 年份要按版本写对帧：v2.4 用 TDRC、v2.3 用 TYER。
            // ⚠ 不管「写」还是「删」，两个帧都先干掉 —— 否则 v2.4 上删年份删不掉（只删了 TYER）
            if (edits.containsKey("year")) {
                drop += "TYER"
                drop += "TDRC"
                put.remove("TYER")
                put.remove("TDRC")
                val year = edits["year"]
                if (year != null) {
                    val wanted = if (version >= 4) "TDRC" else "TYER"
                    val (encoding, body) = encodeId3Text(year, version)
                    put[wanted] = ByteArray(1 + body.size).also {
                        it[0] = encoding
                        System.arraycopy(body, 0, it, 1, body.size)
                    }
                }
            }

            // 1) 原样保留其它帧
            val frameBytes = ByteArrayOutputStream()
            var pos = 10
            if (flags and 0x40 != 0) {
                val extSize = if (version == 4) synchsafeToInt(oldTag, 10) else 4 + int32Be(oldTag, 10)
                if (extSize <= 0 || 10 + extSize > oldTagSize) {
                    return "ID3v2 扩展头异常，已放弃修改（文件未被改动）"
                }
                pos = 10 + extSize
            }
            while (pos + 10 <= oldTagSize) {
                val id = String(oldTag, pos, 4, StandardCharsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                val size = if (version == 4) synchsafeToInt(oldTag, pos + 4) else int32Be(oldTag, pos + 4)
                val total = 10 + size
                if (size <= 0 || pos + total > oldTagSize) break
                if (id !in drop) frameBytes.write(oldTag, pos, total)
                pos += total
            }
            put.forEach { (id, payload) -> frameBytes.write(buildId3Frame(id, payload, version)) }

            // 2) 组装新标签
            val contentSize = frameBytes.size()
            val newTagSize = maxOf(oldTagSize, 10 + contentSize)
            val newTag = ByteArray(newTagSize)
            System.arraycopy(oldTag, 0, newTag, 0, 10)
            System.arraycopy(intToSynchsafe(newTagSize - 10), 0, newTag, 6, 4)
            // ⚠ 清掉 flags 里的「有扩展头(0x40)」「有页脚(0x10)」：重建的帧区里没有扩展头，
            //   留着这两位新标签会自称有扩展头，读取端会拿首帧当扩展头解析 → 这个文件
            //   从此读不出任何标签
            newTag[5] = (newTag[5].toInt() and 0x40.inv() and 0x10.inv()).toByte()
            System.arraycopy(frameBytes.toByteArray(), 0, newTag, 10, contentSize)

            if (newTagSize <= oldTagSize) {
                raf.seek(0)
                raf.write(newTag)
            } else {
                // 标签变长：绝不能就地写（会覆盖开头音频）。写到临时文件，句柄关掉后原子替换
                val audioStartInOld = oldTagSize.toLong()
                val audioLength = file.length() - audioStartInOld
                if (audioLength < 0) return "文件不完整，已放弃修改"
                var copied = 0L
                val temp = File(file.parentFile, file.name + ".amusic-tmp")
                try {
                    java.io.FileOutputStream(temp).use { out ->
                        out.write(newTag)
                        raf.seek(audioStartInOld)
                        val buffer = ByteArray(1 shl 20)
                        while (copied < audioLength) {
                            val want = minOf(buffer.size.toLong(), audioLength - copied).toInt()
                            val read = raf.read(buffer, 0, want)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            copied += read.toLong()
                        }
                        out.flush()
                        out.fd.sync()
                    }
                } catch (e: Exception) {
                    temp.delete()
                    return "写入失败（${e.javaClass.simpleName}），音频文件未被改动"
                }
                if (copied < audioLength) {
                    temp.delete()
                    return "音频数据读取不完整，已放弃修改"
                }
                pendingTemp = temp
            }
        }

        val temp = pendingTemp ?: return null
        if (!temp.renameTo(file)) {
            val stash = File(file.parentFile, file.name + ".amusic-old")
            stash.delete()
            if (!file.renameTo(stash)) {
                // 连"把原文件挪开"都不允许时**不做**危险兜底：宁可这次不写
                temp.delete()
                return "替换文件失败，已放弃修改（音频文件未被改动）"
            }
            if (!temp.renameTo(file)) {
                stash.renameTo(file)
                temp.delete()
                return "替换文件失败，已放弃修改（音频文件未被改动）"
            }
            stash.delete()
        }
        return null
    }
}

/** 文件的魔数是不是这几个 ASCII 字符 */
private fun ByteArray.matchesAscii(text: String): Boolean {
    val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
    if (size < bytes.size) return false
    for (i in bytes.indices) if (this[i] != bytes[i]) return false
    return true
}
