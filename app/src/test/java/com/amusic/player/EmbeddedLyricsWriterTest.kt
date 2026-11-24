package com.amusic.player

import com.amusic.player.data.lyrics.EmbeddedLyrics
import com.amusic.player.data.lyrics.EmbeddedLyricsReader
import com.amusic.player.data.lyrics.EmbeddedLyricsWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 内嵌歌词写入（在线搜索保存时用）的安全性测试。
 *
 * 这个功能的底线是**绝不能动音频数据**，所以这里的断言不是"写成功了"，
 * 而是"写完之后音频那一段字节完全没变"。测试全部在合成文件上做，不碰真实音乐文件。
 */
class EmbeddedLyricsWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- 合成文件 ----------------

    private fun vorbisComment(fields: List<Pair<String, String>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun writeInt(value: Int) {
            out.write(value and 0xFF); out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF); out.write((value shr 24) and 0xFF)
        }
        val vendor = "test".toByteArray(StandardCharsets.UTF_8)
        writeInt(vendor.size); out.write(vendor); writeInt(fields.size)
        fields.forEach { (k, v) ->
            val b = "$k=$v".toByteArray(StandardCharsets.UTF_8)
            writeInt(b.size); out.write(b)
        }
        return out.toByteArray()
    }

    /**
     * 造一个 FLAC：fLaC + STREAMINFO + VORBIS_COMMENT + PADDING + 音频字节。
     * @return 文件内容 与 "音频区起始偏移"
     */
    private fun buildFlac(
        fields: List<Pair<String, String>>,
        paddingSize: Int,
        audioSize: Int = 4096,
    ): Pair<ByteArray, Int> {
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        blocks += 0 to ByteArray(34)                       // STREAMINFO
        blocks += 4 to vorbisComment(fields)               // VORBIS_COMMENT
        if (paddingSize > 0) blocks += 1 to ByteArray(paddingSize)
        val out = java.io.ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.ISO_8859_1))
        blocks.forEachIndexed { index, (type, data) ->
            val last = index == blocks.lastIndex
            out.write((if (last) 0x80 else 0x00) or type)
            out.write((data.size shr 16) and 0xFF)
            out.write((data.size shr 8) and 0xFF)
            out.write(data.size and 0xFF)
            out.write(data)
        }
        val audio = ByteArray(audioSize) { (it * 31 % 251).toByte() }
        out.write(audio)
        return out.toByteArray() to (out.size() - audioSize)
    }

    private fun buildMp3(
        title: String = "老标题",
        audioSize: Int = 4096,
        tagPadding: Int = 512,
        /** 非 null 表示这首 MP3 本来就有内嵌歌词（USLT 帧） */
        existingLyrics: String? = null,
        /** 标签头里的 flags 字节（0x80 = 去同步） */
        flags: Int = 0,
        /** true 表示标签里有 v2.3 的扩展头（10 字节：4 字节长度 + flags + padding 大小） */
        extendedHeader: Boolean = false,
    ): Pair<ByteArray, Int> {
        val frames = java.io.ByteArrayOutputStream()
        val titleBytes = title.toByteArray(StandardCharsets.UTF_8)
        val payload = java.io.ByteArrayOutputStream().apply {
            write(0x03); write(titleBytes)
        }.toByteArray()
        frames.write("TIT2".toByteArray(StandardCharsets.ISO_8859_1))
        frames.write(byteArrayOf(0, 0, 0, payload.size.toByte()))
        frames.write(byteArrayOf(0, 0))
        frames.write(payload)

        if (existingLyrics != null) {
            val uslt = java.io.ByteArrayOutputStream().apply {
                write(0x03)
                write("eng".toByteArray(StandardCharsets.ISO_8859_1))
                write(0x00)
                write(existingLyrics.toByteArray(StandardCharsets.UTF_8))
            }.toByteArray()
            frames.write("USLT".toByteArray(StandardCharsets.ISO_8859_1))
            frames.write(byteArrayOf(0, 0, 0, uslt.size.toByte()))
            frames.write(byteArrayOf(0, 0))
            frames.write(uslt)
        }

        val framesBytes = frames.toByteArray()
        // v2.3 扩展头：4 字节长度（不含自己）+ 2 字节 flags + 4 字节 padding 大小 = 10 字节
        val ext = if (extendedHeader) {
            byteArrayOf(0, 0, 0, 6, 0, 0, 0, 0, 0, 0)
        } else {
            ByteArray(0)
        }
        val content = ext + framesBytes
        val tagSize = 10 + content.size + tagPadding
        val out = java.io.ByteArrayOutputStream()
        out.write("ID3".toByteArray(StandardCharsets.ISO_8859_1))
        // 有扩展头时 flags 必须带 0x40（真文件就是这样；不然写出来的测试素材本身是畸形标签）
        val effectiveFlags = if (extendedHeader) flags or 0x40 else flags
        out.write(byteArrayOf(3, 0, effectiveFlags.toByte()))   // 版本 3、修订 0、flags（头 10 字节）
        out.write(synchsafe(tagSize - 10))
        out.write(content)
        out.write(ByteArray(tagPadding))
        val audio = ByteArray(audioSize) { (it * 17 % 253).toByte() }
        out.write(audio)
        return out.toByteArray() to (out.size() - audioSize)
    }

    private fun synchsafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(), ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(), (value and 0x7F).toByte(),
    )

    private fun write(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun audioRegion(file: File, from: Int) = file.readBytes().copyOfRange(from, file.length().toInt())

    // ---------------- FLAC ----------------

    @Test
    fun `FLAC 写入歌词后音频数据一个字节都没变`() {
        val (bytes, audioStart) = buildFlac(
            fields = listOf("TITLE" to "蝴蝶", "LYRICS" to "[00:01.00]老歌词"),
            paddingSize = 4096,
        )
        val file = write("a.flac", bytes)
        val audioBefore = audioRegion(file, audioStart)

        val result = EmbeddedLyricsWriter.write(file.path, "[00:01.00]新歌词\n[00:05.00]第二句")

        assertNull("应当写入成功：$result", result)
        assertEquals("音频区长度变了（说明标签区被撑大，危险）", bytes.size - audioStart, audioRegion(file, audioStart).size)
        assertTrue("音频数据被改动了！", audioBefore.contentEquals(audioRegion(file, audioStart)))
    }

    @Test
    fun `FLAC 写入后能读回新歌词且保留原有标签`() {
        val (bytes, _) = buildFlac(
            fields = listOf("TITLE" to "蝴蝶", "ARTIST" to "陶喆", "LYRICS" to "旧的"),
            paddingSize = 8192,
        )
        val file = write("b.flac", bytes)
        assertNull(EmbeddedLyricsWriter.write(file.path, "新歌词内容"))

        val text = String(file.readBytes(), StandardCharsets.UTF_8)
        assertTrue("应保留 TITLE", text.contains("TITLE=蝴蝶"))
        assertTrue("应保留 ARTIST", text.contains("ARTIST=陶喆"))
        assertTrue("应写入新歌词", text.contains("LYRICS=新歌词内容"))
        assertEquals("旧歌词字段应被替换掉，不能出现两份", 0, Regex("LYRICS=旧").findAll(text).count())
    }

    @Test
    fun `FLAC 原有的各种内嵌歌词字段全部清空只留新的一份`() {
        // 不同工具写歌词用的键不一样，这些都要清掉，否则播放器可能读到旧的那一份
        val (bytes, audioStart) = buildFlac(
            fields = listOf(
                "TITLE" to "牡丹江", "ARTIST" to "南拳妈妈",
                "LYRICS" to "[00:01.00]旧的LYRICS",
                "UNSYNCEDLYRICS" to "[00:02.00]旧的UNSYNCED",
                "UNSYNCED LYRICS" to "[00:03.00]旧的带空格",
                "LYRIC" to "[00:04.00]旧的LYRIC",
            ),
            paddingSize = 8192,
        )
        val file = write("clear.flac", bytes)
        val audioBefore = audioRegion(file, audioStart)

        assertNull(EmbeddedLyricsWriter.write(file.path, "[00:10.00]新歌词"))

        val text = String(file.readBytes(), StandardCharsets.UTF_8)
        listOf("旧的LYRICS", "旧的UNSYNCED", "旧的带空格", "旧的LYRIC").forEach { old ->
            assertFalse("旧内嵌歌词没清干净：$old", text.contains(old))
        }
        assertEquals("新歌词应当只写入一份", 1, Regex("LYRICS=\\[00:10\\.00\\]新歌词").findAll(text).count())
        assertTrue("TITLE 要保留", text.contains("TITLE=牡丹江"))
        assertTrue("ARTIST 要保留", text.contains("ARTIST=南拳妈妈"))
        assertTrue("音频不能动", audioBefore.contentEquals(audioRegion(file, audioStart)))
        assertEquals("文件长度不该变", bytes.size, file.length().toInt())
    }

    @Test
    fun `MP3 原有的 USLT 歌词帧被替换而不是叠加`() {
        val (bytes, audioStart) = buildMp3(
            title = "牡丹江",
            existingLyrics = "[00:01.00]这是旧的USLT歌词",
            tagPadding = 2048,
        )
        val file = write("clear.mp3", bytes)
        val audioBefore = audioRegion(file, audioStart)

        assertNull(EmbeddedLyricsWriter.write(file.path, "[00:20.00]换成了新歌词"))

        val raw = file.readBytes()
        val text = String(raw, StandardCharsets.UTF_8)
        assertFalse("旧的 USLT 歌词要清掉", text.contains("这是旧的USLT歌词"))
        // 新歌词用应用自己的读取器读回来验证（v2.3 的 ID3 只认 Latin1/UTF-16，
        // 中文必须写 UTF-16，所以不能直接拿 UTF-8 去搜原文）
        val readBack = EmbeddedLyricsReader.read(file.path)
        assertTrue(
            "新歌词要写进去",
            readBack is EmbeddedLyrics.Plain && readBack.text.contains("换成了新歌词"),
        )
        assertEquals("USLT 帧只能有一个", 1, Regex("USLT").findAll(text).count())
        assertTrue("TIT2 要保留", text.contains("牡丹江"))
        assertTrue("音频不能动", audioBefore.contentEquals(audioRegion(file, audioStart)))
    }

    @Test
    fun `FLAC 标签区放不下时拒绝写入且文件完全不变`() {
        // 只有 16 字节 padding，塞不下很长的歌词
        val (bytes, _) = buildFlac(fields = listOf("TITLE" to "x"), paddingSize = 16)
        val file = write("c.flac", bytes)
        val before = file.readBytes()

        val result = EmbeddedLyricsWriter.write(file.path, "很长的歌词".repeat(500))

        assertNotNull("空间不够时应当返回原因", result)
        assertTrue("文件不能被改动", before.contentEquals(file.readBytes()))
    }

    // ---------------- MP3 ----------------

    @Test
    fun `MP3 写入歌词后音频数据不变且原来的帧还在`() {
        val (bytes, audioStart) = buildMp3(title = "蝴蝶")
        val file = write("a.mp3", bytes)
        val audioBefore = audioRegion(file, audioStart)

        val result = EmbeddedLyricsWriter.write(file.path, "[00:01.00]这是新歌词")

        assertNull("应当写入成功：$result", result)
        val after = file.readBytes()
        assertTrue("原 TIT2 帧应保留", String(after, StandardCharsets.ISO_8859_1).contains("TIT2"))
        assertTrue("应写入 USLT 帧", String(after, StandardCharsets.ISO_8859_1).contains("USLT"))
        assertTrue("音频数据被改动了！", audioBefore.contentEquals(audioRegion(file, audioStart)))
    }

    @Test
    fun `MP3 歌词比原标签大时标签变大仍不动音频内容`() {
        val (bytes, audioStart) = buildMp3(title = "x", tagPadding = 8)
        val file = write("b.mp3", bytes)
        val audioBefore = audioRegion(file, audioStart)

        assertNull(EmbeddedLyricsWriter.write(file.path, "很长的歌词".repeat(200)))

        val after = file.readBytes()
        // 标签变大 → 音频起点后移，但音频字节本身必须一致
        val newAudioStart = after.size - audioBefore.size
        assertTrue(newAudioStart > audioStart)
        assertTrue(audioBefore.contentEquals(after.copyOfRange(newAudioStart, after.size)))
    }

    // ---------------- 不支持的格式 ----------------

    @Test
    fun `不支持的容器明确拒绝且不写文件`() {
        val mp4 = ByteArray(2048) { (it % 97).toByte() }
        mp4[4] = 'f'.code.toByte(); mp4[5] = 't'.code.toByte(); mp4[6] = 'y'.code.toByte(); mp4[7] = 'p'.code.toByte()
        val file = write("a.m4a", mp4)
        val before = file.readBytes()

        val result = EmbeddedLyricsWriter.write(file.path, "歌词")

        assertNotNull(result)
        assertTrue("应说明不支持：$result", result!!.contains("不支持"))
        assertTrue("文件不能被改动", before.contentEquals(file.readBytes()))
        assertTrue("不该报告为支持", !EmbeddedLyricsWriter.supported(file.path))
    }

    /**
     * 回归测试：新歌词让"标签区还差多少字节"落在 1~3 时，绝不能碰音频。
     *
     * 老代码的缺陷：差 1~3 字节时会硬加一个 4 字节的 PADDING 块头，多出的 1~3 字节正好
     * 写到第一个音频帧上（文件被写坏）。这里把新歌词从 0 个字符到 48 个字符全试一遍 ——
     * 只要有一条越界就会在文件长度或音频区比对里露出来。
     */
    @Test
    fun `FLAC 任意长度的新歌词都不能碰到音频区`() {
        for (len in 0..48) {
            val (bytes, audioStart) = buildFlac(
                fields = listOf("TITLE" to "测试", "LYRICS" to "旧歌词"),
                paddingSize = 24,
            )
            val file = write("len$len.flac", bytes)
            val audioBefore = audioRegion(file, audioStart)
            val lyrics = "x".repeat(len)

            val result = EmbeddedLyricsWriter.write(file.path, lyrics)

            assertEquals("长度 $len：文件总长度不该变", bytes.size.toLong(), file.length())
            assertTrue(
                "长度 $len：音频区被改动了！（返回：$result）",
                audioBefore.contentEquals(audioRegion(file, audioStart)),
            )
            if (result == null) {
                val text = String(file.readBytes(), StandardCharsets.UTF_8)
                assertTrue("长度 $len：应能读回新歌词", text.contains("LYRICS=$lyrics"))
            }
        }
    }

    /**
     * 回归测试：ID3v2 标签带扩展头时，原有帧不能被丢掉。
     *
     * 老代码固定从第 10 字节开始扫帧，遇到扩展头会把"扩展头长度"的首字节（0x00）当成帧 ID →
     * 立刻 break，于是 TITLE / 封面等**所有原有帧全部丢失**，只剩新写的歌词帧。
     */
    @Test
    fun `MP3 标签带扩展头时原有帧不能丢`() {
        val (bytes, audioStart) = buildMp3(title = "老标题", tagPadding = 2048, extendedHeader = true)
        val file = write("ext.mp3", bytes)
        val audioBefore = audioRegion(file, audioStart)

        val result = EmbeddedLyricsWriter.write(file.path, "新歌词")

        assertNull("应当写入成功：$result", result)
        assertTrue("原有 TIT2 帧应保留", (file.readBytes().decodeToString().contains("TIT2")))
        assertTrue("应写入 USLT 帧", file.readBytes().decodeToString().contains("USLT"))
        assertTrue("音频区被改动了", audioBefore.contentEquals(audioRegion(file, audioStart)))
    }

    /**
     * 回归测试：标签用了"去同步存储"时必须拒绝写入。
     *
     * 老代码把 flags 读成 header[4]（其实是修订号），去同步标志永远判断不出来，
     * 于是会按普通标签改写——标签结构直接被写坏。
     */
    @Test
    fun `MP3 去同步标签要拒绝写入且不动文件`() {
        val (bytes, _) = buildMp3(title = "老标题", tagPadding = 1024, flags = 0x80)
        val file = write("unsync.mp3", bytes)
        val before = file.readBytes()

        val result = EmbeddedLyricsWriter.write(file.path, "新歌词")

        assertNotNull("应当拒绝写入", result)
        assertTrue("提示应说明原因：$result", result!!.contains("去同步"))
        assertTrue("文件不该被改动", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `空歌词不写`() {
        val (bytes, _) = buildFlac(fields = emptyList(), paddingSize = 4096)
        val file = write("d.flac", bytes)
        val before = file.readBytes()
        assertNotNull(EmbeddedLyricsWriter.write(file.path, "   "))
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `supported 只认 FLAC 和 ID3`() {
        val (flac, _) = buildFlac(emptyList(), 64)
        val (mp3, _) = buildMp3()
        assertTrue(EmbeddedLyricsWriter.supported(write("s.flac", flac).path))
        assertTrue(EmbeddedLyricsWriter.supported(write("s.mp3", mp3).path))
        assertTrue(!EmbeddedLyricsWriter.supported(write("s.bin", ByteArray(32)).path))
    }
}
