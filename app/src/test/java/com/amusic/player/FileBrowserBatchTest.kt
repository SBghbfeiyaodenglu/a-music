package com.amusic.player

import com.amusic.player.data.media.AUDIO_EXTENSIONS
import com.amusic.player.data.media.FileBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 按文件夹批量导入时的"递归找歌"逻辑。
 *
 * 用户的用法是把歌按分类装在文件夹里（/MP3/周杰伦…、/MP3/林俊杰…），
 * 勾一个文件夹就要把里面**所有**歌（包括专辑子目录里的）一起导进来，
 * 所以这里盯住四件事：递归、只认音频、别钻进隐藏目录、顺序稳定。
 */
class FileBrowserBatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText("x")
        return file
    }

    @Test
    fun `递归收子目录里的音频`() {
        val root = tmp.newFolder("MP3")
        touch(File(root, "a.mp3"))
        touch(File(root, "专辑一/01.mp3"))
        touch(File(root, "专辑一/02.flac"))
        touch(File(root, "专辑一/碟2/01.m4a"))

        val found = FileBrowser.audioFilesRecursive(root)

        assertEquals(4, found.size)
        assertTrue(found.all { it.extension.lowercase() in AUDIO_EXTENSIONS })
    }

    @Test
    fun `只认音频扩展名`() {
        val root = tmp.newFolder("MP3")
        touch(File(root, "歌.mp3"))
        touch(File(root, "歌.lrc"))
        touch(File(root, "封面.jpg"))
        touch(File(root, "说明.txt"))
        touch(File(root, "专辑/歌.flac"))
        touch(File(root, "专辑/歌.lrc"))

        val found = FileBrowser.audioFilesRecursive(root)

        assertEquals(2, found.size)
        assertEquals(listOf("歌.flac", "歌.mp3"), found.map { it.name }.sorted())
    }

    @Test
    fun `不钻进隐藏目录`() {
        val root = tmp.newFolder("MP3")
        touch(File(root, "歌.mp3"))
        touch(File(root, ".thumbnails/缓存.mp3"))
        touch(File(root, "专辑/.隐藏/藏起来的.mp3"))

        val found = FileBrowser.audioFilesRecursive(root)

        assertEquals(1, found.size)
        assertEquals("歌.mp3", found.first().name)
    }

    @Test
    fun `按路径排序，同一个专辑里就是 01 02 的顺序`() {
        val root = tmp.newFolder("MP3")
        touch(File(root, "专辑B/02.mp3"))
        touch(File(root, "专辑B/01.mp3"))
        touch(File(root, "专辑B/10.mp3"))
        touch(File(root, "专辑A/01.mp3"))

        val found = FileBrowser.audioFilesRecursive(root)

        assertEquals(
            listOf("专辑A/01.mp3", "专辑B/01.mp3", "专辑B/02.mp3", "专辑B/10.mp3"),
            found.map { it.parentFile.name + "/" + it.name },
        )
    }

    @Test
    fun `没有音频的文件夹返回空`() {
        val root = tmp.newFolder("空分类")
        touch(File(root, "封面.jpg"))
        assertTrue(FileBrowser.audioFilesRecursive(root).isEmpty())
    }

    @Test
    fun `不存在的目录不抛异常`() {
        assertTrue(FileBrowser.audioFilesRecursive(File(tmp.root, "根本没有这个目录")).isEmpty())
    }
}
