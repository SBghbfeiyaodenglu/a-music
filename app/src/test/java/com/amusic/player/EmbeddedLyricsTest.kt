package com.amusic.player

import com.amusic.player.data.lyrics.EmbeddedLyrics
import com.amusic.player.data.lyrics.EmbeddedLyricsReader
import com.amusic.player.data.lyrics.LyricsRepository
import com.amusic.player.data.lyrics.LyricsSource
import com.amusic.player.data.db.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 内嵌歌词解析测试。
 *
 * 这里手工拼出各种音频容器的标签区（ID3v2 / FLAC Vorbis Comment / MP4 ©lyr），
 * 不依赖真实音频文件，也不需要设备 —— `gradle testDebugUnitTest` 就能跑。
 */
class EmbeddedLyricsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- ID3v2 (MP3) ----------------

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val size = payload.size
        out.write(byteArrayOf(0, 0, (size shr 8).toByte(), (size and 0xFF).toByte())) // v2.3 普通尺寸
        out.write(byteArrayOf(0, 0)) // flags
        out.write(payload)
        return out.toByteArray()
    }

    private fun id3v2(vararg frames: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { body.write(it) }
        val bodyBytes = body.toByteArray()
        val size = bodyBytes.size
        val syncSafe = byteArrayOf(
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte(),
        )
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(3, 0, 0)) // v2.3, revision 0, flags 无
        out.write(syncSafe)
        out.write(bodyBytes)
        // 后面跟点假的音频数据
        out.write(ByteArray(64))
        return out.toByteArray()
    }

    private fun usltFrame(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(3) // UTF-8
        out.write("chi".toByteArray(Charsets.ISO_8859_1)) // 语言
        out.write(0) // 空描述
        out.write(text.toByteArray(Charsets.UTF_8))
        return frame("USLT", out.toByteArray())
    }

    private fun syltFrame(entries: List<Pair<Long, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(3) // UTF-8
        out.write("chi".toByteArray(Charsets.ISO_8859_1))
        out.write(2) // 时间戳格式 = 毫秒
        out.write(1) // 内容类型 = 歌词
        out.write(0) // 空描述
        entries.forEach { (time, text) ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.write(0)
            val t = time.toInt()
            out.write(byteArrayOf((t shr 24).toByte(), (t shr 16).toByte(), (t shr 8).toByte(), t.toByte()))
        }
        return frame("SYLT", out.toByteArray())
    }

    @Test
    fun `MP3 的 USLT 纯文本歌词能读出来`() {
        val file = tmp.newFile("plain.mp3")
        file.writeBytes(id3v2(usltFrame("[00:01.00]第一句\n[00:05.00]第二句")))

        val result = EmbeddedLyricsReader.read(file.absolutePath)

        assertNotNull(result)
        assertTrue(result is EmbeddedLyrics.Plain)
        assertTrue((result as EmbeddedLyrics.Plain).text.contains("第一句"))
    }

    @Test
    fun `MP3 的 SYLT 同步歌词能读出时间轴`() {
        val file = tmp.newFile("synced.mp3")
        file.writeBytes(
            id3v2(
                syltFrame(listOf(1500L to "第一句", 4200L to "第二句", 9000L to "第三句")),
            ),
        )

        val result = EmbeddedLyricsReader.read(file.absolutePath)

        assertNotNull(result)
        assertTrue(result is EmbeddedLyrics.Synced)
        val lines = (result as EmbeddedLyrics.Synced).lines
        assertEquals(3, lines.size)
        assertEquals("第一句", lines[0].text)
        assertEquals(1500L, lines[0].timeMs)
        assertEquals(9000L, lines[2].timeMs)
    }

    @Test
    fun `同时存在 USLT 和 SYLT 时优先用带时间轴的`() {
        val file = tmp.newFile("both.mp3")
        file.writeBytes(id3v2(usltFrame("纯文本歌词"), syltFrame(listOf(2000L to "同步歌词"))))

        val result = EmbeddedLyricsReader.read(file.absolutePath)

        assertTrue(result is EmbeddedLyrics.Synced)
    }

    // ---------------- FLAC ----------------

    private fun flacWithComment(key: String, value: String): ByteArray {
        val comment = ByteArrayOutputStream()
        val vendor = "test".toByteArray(Charsets.UTF_8)
        comment.write(le32(vendor.size))
        comment.write(vendor)
        comment.write(le32(1)) // 一个字段
        val entry = "$key=$value".toByteArray(Charsets.UTF_8)
        comment.write(le32(entry.size))
        comment.write(entry)
        return comment.toByteArray()
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun flacFile(comment: ByteArray, vararg extraBlocks: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        // STREAMINFO（类型 0），内容随便给 34 字节
        val streamInfo = ByteArray(34)
        out.write(0x00)
        out.write(byteArrayOf(0, 0, streamInfo.size.toByte()))
        out.write(streamInfo)
        extraBlocks.forEach { (type, data) ->
            out.write(type)
            out.write(byteArrayOf((data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
            out.write(data)
        }
        // 最后一个块：VORBIS_COMMENT（类型 4），last 标志置位
        out.write(0x80 or 0x04)
        out.write(byteArrayOf((comment.size shr 16).toByte(), (comment.size shr 8).toByte(), comment.size.toByte()))
        out.write(comment)
        return out.toByteArray()
    }

    @Test
    fun `FLAC 的 LYRICS 字段能读出来`() {
        val file = tmp.newFile("song.flac")
        file.writeBytes(flacFile(flacWithComment("LYRICS", "[00:02.00]FLAC 歌词")))

        val result = EmbeddedLyricsReader.read(file.absolutePath)

        assertNotNull(result)
        assertTrue(result is EmbeddedLyrics.Plain)
        assertTrue((result as EmbeddedLyrics.Plain).text.contains("FLAC 歌词"))
    }

    @Test
    fun `FLAC 的 UNSYNCEDLYRICS 字段也认`() {
        val file = tmp.newFile("song2.flac")
        file.writeBytes(flacFile(flacWithComment("UNSYNCEDLYRICS", "没有时间轴的歌词")))

        val result = EmbeddedLyricsReader.read(file.absolutePath)

        assertNotNull(result)
        assertTrue((result as EmbeddedLyrics.Plain).text.contains("没有时间轴的歌词"))
    }

    @Test
    fun `FLAC 里没有歌词字段时返回空`() {
        val file = tmp.newFile("song3.flac")
        file.writeBytes(flacFile(flacWithComment("ARTIST", "某歌手")))

        assertEquals(null, EmbeddedLyricsReader.read(file.absolutePath))
    }

    // ---------------- MP4 ----------------

    @Test
    fun `M4A 的 mp4 歌词原子能读出来`() {
        val file = tmp.newFile("song.m4a")
        file.writeBytes(mp4WithLyrics("[00:03.00]MP4 歌词"))

        val result = EmbeddedLyricsReader.read(file.absolutePath)

        assertNotNull(result)
        assertTrue(result is EmbeddedLyrics.Plain)
        assertTrue((result as EmbeddedLyrics.Plain).text.contains("MP4 歌词"))
    }

    private fun atom(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = body.size + 8
        out.write(byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun mp4WithLyrics(text: String): ByteArray {
        // data 原子：4 字节类型(1=UTF-8) + 4 字节 locale + 文本
        val dataBody = ByteArrayOutputStream()
        dataBody.write(byteArrayOf(0, 0, 0, 1))
        dataBody.write(byteArrayOf(0, 0, 0, 0))
        dataBody.write(text.toByteArray(Charsets.UTF_8))
        val lyr = atom("\u00A9lyr", atom("data", dataBody.toByteArray()))
        val ilst = atom("ilst", lyr)
        val meta = atom("meta", ByteArray(4) + ilst) // meta 是 full box，前 4 字节版本+flags
        val udta = atom("udta", meta)
        val moov = atom("moov", udta)
        val ftyp = atom("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8))
        return ftyp + moov
    }

    // ---------------- 与解析链路的衔接 ----------------

    @Test
    fun `同名 lrc 优先级高于内嵌歌词`() = runBlocking {
        val dir = tmp.newFolder()
        val audio = File(dir, "晴天.mp3")
        audio.writeBytes(id3v2(usltFrame("内嵌的歌词")))
        File(dir, "晴天.lrc").writeText("[00:09.00]LRC 的歌词\n")

        val track = TrackEntity(
            id = 1L, path = audio.absolutePath, displayName = "晴天.mp3",
            title = "", artist = "", album = "",
            durationMs = 0L, sizeBytes = 0L, lastModified = 0L,
        )
        val resolved = LyricsRepository().resolve(track)

        assertEquals(LyricsSource.LRC_FILE, resolved.source)
        assertEquals(listOf("LRC 的歌词"), resolved.lines.map { it.text })
    }

    @Test
    fun `没有 lrc 时回落到内嵌歌词并按静态歌词处理`() = runBlocking {
        val dir = tmp.newFolder()
        val audio = File(dir, "七里香.mp3")
        audio.writeBytes(id3v2(usltFrame("第一句\n第二句")))

        val track = TrackEntity(
            id = 2L, path = audio.absolutePath, displayName = "七里香.mp3",
            title = "", artist = "", album = "",
            durationMs = 0L, sizeBytes = 0L, lastModified = 0L,
        )
        val resolved = LyricsRepository().resolve(track)

        assertEquals(LyricsSource.EMBEDDED, resolved.source)
        assertEquals(listOf("第一句", "第二句"), resolved.lines.map { it.text })
        // 纯文本没有时间轴，界面应作为静态歌词显示
        assertTrue(!resolved.hasTimeline)
    }
}
