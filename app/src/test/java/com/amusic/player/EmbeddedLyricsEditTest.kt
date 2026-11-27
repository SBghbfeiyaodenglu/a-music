package com.amusic.player

import com.amusic.player.data.lyrics.EmbeddedLyrics
import com.amusic.player.data.lyrics.EmbeddedLyricsEditor
import com.amusic.player.data.lyrics.EmbeddedLyricsReader
import com.amusic.player.data.lyrics.LrcTimestampEditor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 内嵌歌词"原地改时间戳"的测试。
 *
 * 这是没有真机时最需要验证的一块：它会写用户的音频文件，写坏了就是大事。
 * 这里手工拼出各种容器，验证三件事：
 *   1. 能定位到歌词在文件里的位置
 *   2. 等长平移能正确写进去，且文件长度不变
 *   3. 长度会变时**拒绝写入**，文件一个字节都不动
 */
class EmbeddedLyricsEditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- 容器构造 ----------------

    private fun atom(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = body.size + 8
        out.write(byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val size = payload.size
        out.write(byteArrayOf(0, 0, (size shr 8).toByte(), (size and 0xFF).toByte()))
        out.write(byteArrayOf(0, 0))
        out.write(payload)
        return out.toByteArray()
    }

    private fun id3v2WithUslt(text: String): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(3) // UTF-8
        payload.write("chi".toByteArray(Charsets.ISO_8859_1))
        payload.write(0)
        payload.write(text.toByteArray(Charsets.UTF_8))
        val body = frame("USLT", payload.toByteArray())
        val size = body.size
        val syncSafe = byteArrayOf(
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte(),
        )
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(3, 0, 0))
        out.write(syncSafe)
        out.write(body)
        out.write(ByteArray(64)) // 假音频数据
        return out.toByteArray()
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun flacWithLyrics(text: String): ByteArray = flacWithEntry("LYRICS=$text")

    /** 造一个只带指定 Vorbis Comment 字段的 FLAC（用来构造"没有 LYRICS 字段"这种情况） */
    private fun flacWithEntry(entryText: String): ByteArray {
        val comment = ByteArrayOutputStream()
        val vendor = "test".toByteArray(Charsets.UTF_8)
        comment.write(le32(vendor.size))
        comment.write(vendor)
        comment.write(le32(1))
        val entry = entryText.toByteArray(Charsets.UTF_8)
        comment.write(le32(entry.size))
        comment.write(entry)
        val commentBytes = comment.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        out.write(0x00)
        out.write(byteArrayOf(0, 0, 34))
        out.write(ByteArray(34))
        out.write(0x80 or 0x04)
        out.write(
            byteArrayOf(
                (commentBytes.size shr 16).toByte(),
                (commentBytes.size shr 8).toByte(),
                commentBytes.size.toByte(),
            ),
        )
        out.write(commentBytes)
        return out.toByteArray()
    }

    private fun mp4WithLyrics(text: String): ByteArray {
        val dataBody = ByteArrayOutputStream()
        dataBody.write(byteArrayOf(0, 0, 0, 1)) // UTF-8
        dataBody.write(byteArrayOf(0, 0, 0, 0))
        dataBody.write(text.toByteArray(Charsets.UTF_8))
        val lyr = atom("\u00A9lyr", atom("data", dataBody.toByteArray()))
        val meta = atom("meta", ByteArray(4) + atom("ilst", lyr))
        val moov = atom("moov", atom("udta", meta))
        return atom("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8)) + moov
    }

    private fun writtenLyrics(file: File): String {
        val embedded = EmbeddedLyricsReader.read(file.absolutePath)
        assertNotNull("应当能读回歌词", embedded)
        return (embedded as EmbeddedLyrics.Plain).text
    }

    // ---------------- FLAC ----------------

    @Test
    fun `FLAC 内嵌歌词能原地平移且长度不变`() {
        val file = tmp.newFile("a.flac")
        file.writeBytes(flacWithLyrics("[00:01.00]第一句\n[00:05.00]第二句\n"))
        val before = file.readBytes()

        val target = EmbeddedLyricsEditor.locate(file.absolutePath)
        assertNotNull("应当定位到歌词位置", target)
        assertTrue(EmbeddedLyricsEditor.rewriteInPlace(file.absolutePath, target!!, 200L))

        val after = file.readBytes()
        assertEquals("文件长度必须不变", before.size, after.size)
        assertTrue("内容应当变了", before.indices.any { before[it] != after[it] })

        val lyrics = writtenLyrics(file)
        assertTrue(lyrics.contains("[00:01.20]"))
        assertTrue(lyrics.contains("[00:05.20]"))
    }

    @Test
    fun `FLAC 上改两次等于一次改 400 毫秒`() {
        val file = tmp.newFile("b.flac")
        file.writeBytes(flacWithLyrics("[00:10.00]一句\n"))

        val target = EmbeddedLyricsEditor.locate(file.absolutePath)!!
        assertTrue(EmbeddedLyricsEditor.rewriteInPlace(file.absolutePath, target, 200L))

        // 改完位置会变吗？内容等长，位置不变，可以直接复用
        assertTrue(EmbeddedLyricsEditor.rewriteInPlace(file.absolutePath, target, 200L))
        assertTrue(writtenLyrics(file).contains("[00:10.40]"))
    }

    // ---------------- MP3 ----------------

    @Test
    fun `MP3 的 USLT 能原地平移`() {
        val file = tmp.newFile("c.mp3")
        file.writeBytes(id3v2WithUslt("[00:02.00]甲\n[00:04.00]乙\n"))
        val before = file.readBytes()

        val target = EmbeddedLyricsEditor.locate(file.absolutePath)
        assertNotNull(target)
        assertTrue(EmbeddedLyricsEditor.rewriteInPlace(file.absolutePath, target!!, 500L))

        assertEquals(before.size, file.readBytes().size)
        val lyrics = writtenLyrics(file)
        assertTrue(lyrics.contains("[00:02.50]"))
        assertTrue(lyrics.contains("[00:04.50]"))
    }

    // ---------------- M4A ----------------

    @Test
    fun `M4A 的歌词原子能原地平移`() {
        val file = tmp.newFile("d.m4a")
        file.writeBytes(mp4WithLyrics("[00:03.00]丙\n"))
        val before = file.readBytes()

        val target = EmbeddedLyricsEditor.locate(file.absolutePath)
        assertNotNull(target)
        assertTrue(EmbeddedLyricsEditor.rewriteInPlace(file.absolutePath, target!!, 100L))

        assertEquals(before.size, file.readBytes().size)
        assertTrue(writtenLyrics(file).contains("[00:03.10]"))
    }

    // ---------------- 安全边界 ----------------

    @Test
    fun `长度会变时拒绝写入并且文件完全不变`() {
        val file = tmp.newFile("e.flac")
        // 99 分钟加 60 秒会进位成 100 分钟，时间标签从 9 字节变成 10 字节
        file.writeBytes(flacWithLyrics("[99:30.00]很长的一首歌\n"))
        val before = file.readBytes()

        val target = EmbeddedLyricsEditor.locate(file.absolutePath)!!
        assertFalse(
            "长度变化时必须拒绝",
            EmbeddedLyricsEditor.rewriteInPlace(file.absolutePath, target, 60_000L),
        )
        assertArrayEquals("拒绝写入时文件必须原封不动", before, file.readBytes())
    }

    @Test
    fun `没有内嵌歌词时定位返回空`() {
        // 构造的文件必须**真的没有歌词**（只有 TITLE），否则没测到"没歌词"这个前提，
        // 断言也要针对"返回空"本身。
        val file = tmp.newFile("f.flac")
        file.writeBytes(flacWithEntry("TITLE=只有标题没有歌词"))
        assertNull(
            "没有 LYRICS 字段时不该定位到任何写入位置",
            EmbeddedLyricsEditor.locate(file.absolutePath),
        )
    }

    @Test
    fun `纯文本平移不会破坏换行符和长度`() {
        val text = "[00:01.00]第一句\r\n[00:02.00]第二句\r\n"
        val shifted = LrcTimestampEditor.shiftText(text, 200L)

        assertEquals(text.length, shifted.length)
        assertTrue("回车换行要原样保留", shifted.contains("\r\n"))
        assertTrue(shifted.contains("[00:01.20]"))
    }

    @Test
    fun `提前过头会夹到 00 00 00 且长度不变`() {
        val text = "[00:00.50]开头"
        val shifted = LrcTimestampEditor.shiftText(text, -5_000L)

        assertEquals(text.length, shifted.length)
        assertTrue(shifted.startsWith("[00:00.00]"))
    }

    @Test
    fun `不是时间标签的方括号内容不会被改`() {
        val text = "[ar:陶喆]\n[ti:蝴蝶]\n[00:01.00]歌词\n"
        val shifted = LrcTimestampEditor.shiftText(text, 200L)

        assertTrue(shifted.contains("[ar:陶喆]"))
        assertTrue(shifted.contains("[ti:蝴蝶]"))
        assertTrue(shifted.contains("[00:01.20]"))
    }
}
