package com.amusic.player

import com.amusic.player.data.media.TagWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 改元数据（标签）的安全性测试。
 *
 * 和歌词写入同一套底线：**绝不能动音频数据**。所以这里断言的都不是"改成功了"，
 * 而是"改完之后音频那一段字节完全没变、总长度也没变、封面等其它标签还在"。
 */
class TagWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- 合成文件 ----------------

    private fun vorbisComment(fields: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeInt(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        val vendor = "test".toByteArray(StandardCharsets.UTF_8)
        writeInt(vendor.size); out.write(vendor); writeInt(fields.size)
        fields.forEach { (k, v) ->
            val b = "$k=$v".toByteArray(StandardCharsets.UTF_8)
            writeInt(b.size); out.write(b)
        }
        return out.toByteArray()
    }

    /** fLaC + STREAMINFO + VORBIS_COMMENT + 一张假封面(PICTURE, type 6) + PADDING + 音频 */
    private fun buildFlac(
        fields: List<Pair<String, String>>,
        paddingSize: Int,
        audioSize: Int = 4096,
    ): Pair<ByteArray, Int> {
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        blocks += 0 to ByteArray(34)                                  // STREAMINFO
        blocks += 4 to vorbisComment(fields)                          // VORBIS_COMMENT
        blocks += 6 to ByteArray(200) { (it * 7 % 251).toByte() }     // PICTURE（假封面）
        if (paddingSize > 0) blocks += 1 to ByteArray(paddingSize)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.ISO_8859_1))
        blocks.forEachIndexed { i, (type, data) ->
            out.write((if (i == blocks.lastIndex) 0x80 else 0x00) or type)
            out.write((data.size shr 16) and 0xFF)
            out.write((data.size shr 8) and 0xFF)
            out.write(data.size and 0xFF)
            out.write(data)
        }
        val audio = ByteArray(audioSize) { (it * 31 % 251).toByte() }
        out.write(audio)
        return out.toByteArray() to (out.size() - audioSize)
    }

    /** ID3v2.3 + TIT2 + APIC(假封面) + padding + 音频 */
    private fun buildMp3(title: String = "老标题", tagPadding: Int = 1024, audioSize: Int = 4096): Pair<ByteArray, Int> {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, payload: ByteArray) {
            frames.write(id.toByteArray(StandardCharsets.ISO_8859_1))
            frames.write(byteArrayOf(0, 0, 0, payload.size.toByte()))
            frames.write(byteArrayOf(0, 0))
            frames.write(payload)
        }
        frame("TIT2", byteArrayOf(0x03) + title.toByteArray(StandardCharsets.UTF_8))
        frame("APIC", byteArrayOf(0x00) + "image/jpeg".toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(0x00) + byteArrayOf(0x03) + ByteArray(120) { (it * 13 % 251).toByte() })

        val content = frames.toByteArray()
        val tagSize = 10 + content.size + tagPadding
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(byteArrayOf(3, 0, 0))
        val len = tagSize - 10
        out.write(byteArrayOf(((len shr 21) and 0x7F).toByte(), ((len shr 14) and 0x7F).toByte(),
                              ((len shr 7) and 0x7F).toByte(), (len and 0x7F).toByte()))
        out.write(content)
        out.write(ByteArray(tagPadding))
        val audio = ByteArray(audioSize) { (it * 17 % 253).toByte() }
        out.write(audio)
        return out.toByteArray() to (out.size() - audioSize)
    }

    private fun write(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun audioRegion(file: File, from: Int) = file.readBytes().copyOfRange(from, file.length().toInt())

    // ---------------- FLAC ----------------

    @Test
    fun `FLAC 改标题和艺术家后音频区逐字节不变、总长度不变`() {
        val (bytes, audioStart) = buildFlac(
            fields = listOf("TITLE" to "老标题", "ARTIST" to "老歌手", "ALBUM" to "老专辑"),
            paddingSize = 2048,
        )
        val file = write("meta.flac", bytes)
        val audioBefore = audioRegion(file, audioStart)

        val result = TagWriter.write(file.path, mapOf("title" to "新标题", "artist" to "新歌手"))

        assertNull("应当改成功：$result", result)
        assertEquals("文件总长不该变", bytes.size.toLong(), file.length())
        assertTrue("音频区被改动了！", audioBefore.contentEquals(audioRegion(file, audioStart)))
    }

    @Test
    fun `FLAC 改完之后读回来是新值，其它字段和封面都还在`() {
        val (bytes, _) = buildFlac(
            fields = listOf("TITLE" to "老标题", "ARTIST" to "老歌手", "ALBUM" to "专辑A", "GENRE" to "流行"),
            paddingSize = 4096,
        )
        val file = write("meta2.flac", bytes)

        assertNull(TagWriter.write(file.path, mapOf("title" to "新标题", "artist" to "新歌手")))

        val read = TagWriter.read(file.path)
        assertEquals("新标题", read["title"])
        assertEquals("新歌手", read["artist"])
        assertEquals("专辑A", read["album"])
        assertEquals("流行", read["genre"])
        // 假封面那一块的字节要原样还在
        assertTrue("封面块没保留", file.readBytes().toList().windowed(200).any { it == ByteArray(200) { i -> (i * 7 % 251).toByte() }.toList() })
    }

    @Test
    fun `FLAC 把字段设成 null 就是删除它`() {
        val (bytes, _) = buildFlac(
            fields = listOf("TITLE" to "标题", "ARTIST" to "歌手", "ALBUM" to "专辑"),
            paddingSize = 2048,
        )
        val file = write("meta3.flac", bytes)
        assertNull(TagWriter.write(file.path, mapOf("album" to null)))
        val read = TagWriter.read(file.path)
        assertNull("专辑该被删掉", read["album"])
        assertEquals("标题要保留", "标题", read["title"])
    }

    @Test
    fun `FLAC 各种长度的新值都不会碰到音频区`() {
        for (len in 0..40) {
            val (bytes, audioStart) = buildFlac(
                fields = listOf("TITLE" to "老标题", "ARTIST" to "老歌手"),
                paddingSize = 32,
            )
            val file = write("len$len.flac", bytes)
            val audioBefore = audioRegion(file, audioStart)
            val result = TagWriter.write(file.path, mapOf("title" to "标".repeat(len)))
            assertEquals("长度 $len：文件总长不该变", bytes.size.toLong(), file.length())
            assertTrue("长度 $len：音频被改动了（$result）", audioBefore.contentEquals(audioRegion(file, audioStart)))
        }
    }

    @Test
    fun `FLAC 放不下时拒绝并要求文件不变`() {
        // 只有 16 字节 padding，塞不下很长的标题
        val (bytes, audioStart) = buildFlac(fields = listOf("TITLE" to "短"), paddingSize = 16)
        val file = write("tight.flac", bytes)
        val before = file.readBytes()
        val result = TagWriter.write(file.path, mapOf("title" to "很长的标题".repeat(200)))
        assertTrue("应当明确拒绝：$result", result != null)
        assertTrue("被拒绝时文件必须一字节不变", before.contentEquals(file.readBytes()))
        assertTrue("音频区不能动", audioRegion(file, audioStart).contentEquals(before.copyOfRange(audioStart, before.size)))
    }

    // ---------------- MP3 ----------------

    @Test
    fun `MP3 改中文标题后音频区不变、能读回来（v2_3 用 UTF-16）`() {
        val (bytes, audioStart) = buildMp3(title = "老标题", tagPadding = 2048)
        val file = write("meta.mp3", bytes)
        val audioBefore = audioRegion(file, audioStart)

        val result = TagWriter.write(file.path, mapOf("title" to "开不了口", "artist" to "周杰伦"))

        assertNull("应当改成功：$result", result)
        assertTrue("音频区被改动了！", audioBefore.contentEquals(audioRegion(file, audioStart)))
        val read = TagWriter.read(file.path)
        assertEquals("开不了口", read["title"])
        assertEquals("周杰伦", read["artist"])
        // 封面帧要保留
        assertTrue("APIC 封面帧没了", String(file.readBytes(), StandardCharsets.ISO_8859_1).contains("APIC"))
    }

    @Test
    fun `MP3 改年份时按版本挑帧（v2_3 用 TYER）`() {
        val (bytes, _) = buildMp3(tagPadding = 1024)
        val file = write("year.mp3", bytes)
        assertNull(TagWriter.write(file.path, mapOf("year" to "2024")))
        val raw = String(file.readBytes(), StandardCharsets.ISO_8859_1)
        assertTrue("v2.3 应该写 TYER", raw.contains("TYER"))
        assertFalse("v2.3 不该出现 TDRC", raw.contains("TDRC"))
        assertEquals("2024", TagWriter.read(file.path)["year"])
    }

    @Test
    fun `不支持的格式拒绝修改且不动文件`() {
        val file = write("x.m4a", ByteArray(64) { 0x11 })
        val before = file.readBytes()
        val result = TagWriter.write(file.path, mapOf("title" to "x"))
        assertTrue("应当明确拒绝：$result", result != null)
        assertTrue(before.contentEquals(file.readBytes()))
        assertFalse(TagWriter.supported(file.path))
    }

    @Test
    fun `空字符串是写成空标签，不是删掉字段`() {
        val (bytes, _) = buildFlac(
            fields = listOf("TITLE" to "老标题", "ARTIST" to "老歌手"),
            paddingSize = 2048,
        )
        val file = write("empty.flac", bytes)
        assertNull(TagWriter.write(file.path, mapOf("album" to "")))
        val read = TagWriter.read(file.path)
        // 空值不显示（read 里空串会被过滤掉），但文件里这个字段本身还在
        val raw = file.readBytes().decodeToString()
        assertTrue("ALBUM= 应该还在（空标签）", raw.contains("ALBUM="))
        assertEquals("老标题", read["title"])
    }
}
