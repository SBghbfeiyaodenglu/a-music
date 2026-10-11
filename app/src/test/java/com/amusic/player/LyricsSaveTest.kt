package com.amusic.player

import com.amusic.player.data.lyrics.LyricsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 歌词保存行为的测试（同名 .lrc 的写入规则）。
 *
 * 两条硬要求：
 *   1. **不备份**原文件：同名 .lrc 直接覆盖，不许产生 .lrc.bak
 *   2. 歌词是繁体时先转成简体再存
 */
class LyricsSaveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val repo = LyricsRepository()

    private fun newSong(name: String = "晴天.mp3"): File {
        val dir = tmp.newFolder()
        return File(dir, name).apply { writeBytes(ByteArray(0)) }
    }

    @Test
    fun `繁体歌词保存时自动转成简体`() = runBlocking {
        val audio = newSong()
        val result = repo.saveLyricsText(
            audio.absolutePath,
            "[00:02.04]下雨天了怎麼辦 我好想你\n[00:09.11]我不敢打給你 我找不到原因\n",
        )

        assertTrue(result.message, result.success)
        val lrc = File(audio.parentFile, "晴天.lrc")
        val saved = lrc.readText(Charsets.UTF_8)
        assertTrue("应该已转成简体：$saved", saved.contains("怎么办"))
        assertTrue(saved.contains("我不敢打给你"))
        assertFalse("不该留着繁体字", saved.contains("怎麼辦"))
        assertTrue("提示里要说清转了简体：${result.message}", result.message.contains("繁体"))
        // 时间戳一个都不能动
        assertTrue(saved.contains("[00:02.04]"))
        assertTrue(saved.contains("[00:09.11]"))
    }

    @Test
    fun `本来就是简体时原样保存且不提示转换`() = runBlocking {
        val audio = newSong()
        val text = "[00:01.00]这是简体歌词\n"
        val result = repo.saveLyricsText(audio.absolutePath, text)

        assertTrue(result.success)
        assertEquals(text, File(audio.parentFile, "晴天.lrc").readText(Charsets.UTF_8))
        assertFalse(result.message.contains("繁体"))
    }

    @Test
    fun `覆盖已有 lrc 时不产生 bak 备份`() = runBlocking {
        val audio = newSong()
        val lrc = File(audio.parentFile, "晴天.lrc")
        lrc.writeText("[00:01.00]旧歌词\n", Charsets.UTF_8)

        val result = repo.saveLyricsText(audio.absolutePath, "[00:02.00]新歌词\n")

        assertTrue(result.message, result.success)
        assertEquals("[00:02.00]新歌词\n", lrc.readText(Charsets.UTF_8))
        val siblings = audio.parentFile!!.listFiles()!!.map { it.name }.sorted()
        assertEquals("目录里只该有音频和 .lrc：$siblings", listOf("晴天.lrc", "晴天.mp3"), siblings)
        assertFalse(result.message.contains("备份"))
    }

    @Test
    fun `连续保存两次也不留备份`() = runBlocking {
        val audio = newSong()
        repo.saveLyricsText(audio.absolutePath, "[00:01.00]第一版\n")
        repo.saveLyricsText(audio.absolutePath, "[00:02.00]第二版\n")

        assertEquals("[00:02.00]第二版\n", File(audio.parentFile, "晴天.lrc").readText(Charsets.UTF_8))
        assertFalse(File(audio.parentFile, "晴天.lrc.bak").exists())
    }

    @Test
    fun `只存 lrc 时不动音频文件`() = runBlocking {
        val audio = newSong()
        val before = audio.readBytes()
        repo.saveLyrics(audio.absolutePath, "[00:01.00]歌词\n", alsoEmbedded = false)

        assertEquals(File(audio.parentFile, "晴天.lrc").exists(), true)
        // ⚠ 要比内容不能只比长度：等长的误写（覆盖同长度字节）长度检查是看不出来的
        assertArrayEquals("音频文件不该被碰（逐字节相同）", before, audio.readBytes())
    }

    @Test
    fun `容器不支持内嵌时 lrc 照样保存并说明原因`() = runBlocking {
        // 扩展名是 .flac 但内容没有 fLaC 头 → 内嵌写入必须失败，且不能影响 .lrc
        val audio = newSong("随便.flac")
        val result = repo.saveLyrics(audio.absolutePath, "[00:01.00]歌词\n", alsoEmbedded = true)

        assertTrue(result.message, result.success)
        assertTrue(File(audio.parentFile, "随便.lrc").exists())
        assertTrue("应说明内嵌没写入：${result.message}", result.message.contains("内嵌歌词没写入"))
    }

    @Test
    fun `空歌词不保存`() = runBlocking {
        val audio = newSong()
        val result = repo.saveLyricsText(audio.absolutePath, "   \n")
        assertFalse(result.success)
        assertFalse(File(audio.parentFile, "晴天.lrc").exists())
    }
}
