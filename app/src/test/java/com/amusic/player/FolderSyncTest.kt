package com.amusic.player

import com.amusic.player.data.FolderSync
import com.amusic.player.data.FolderSyncExisting
import com.amusic.player.data.FolderSyncFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表「跟随文件夹」的纯逻辑测试。
 *
 * 这块一旦算错，后果不是界面不好看，而是**用户的列表被改坏**：
 * 多算一条就是凭空加歌，少算一条就是歌从列表里消失。核心安全底线在这里钉死：
 *   - 只删"确实在可访问源文件夹下、且扫描不到"的歌；
 *   - 文件夹读不到（没挂载/没权限）时一条都不删；
 *   - 不在源文件夹下的歌永远不动。
 */
class FolderSyncTest {

    private val root = "/storage/emulated/0/0.伴奏"

    // ---------------- 路径判断 ----------------

    @Test
    fun `路径归一化统一斜杠并去掉末尾斜杠`() {
        assertEquals("/sdcard/a/b", FolderSync.normalize("/sdcard/a/b/"))
        assertEquals("/sdcard/a/b", FolderSync.normalize("\\sdcard\\a\\b\\"))
        assertEquals("/sdcard/a/b", FolderSync.normalize("/sdcard/a/b"))
    }

    @Test
    fun `源文件夹下的子目录和文件算在范围内`() {
        assertTrue(FolderSync.isUnder("$root/周杰伦/晴天.mp3", root))
        assertTrue(FolderSync.isUnder("$root/晴天.mp3", root))
    }

    @Test
    fun `只是前缀相同的兄弟目录不算在范围内`() {
        // 关键边界：/0.伴奏2 不能因为字符串前缀相同被当成 /0.伴奏 里的歌
        assertFalse(FolderSync.isUnder("$root-备份/晴天.mp3", root))
        assertFalse(FolderSync.isUnder("/storage/emulated/0/其它/晴天.mp3", root))
        assertFalse(FolderSync.isUnder("$root/晴天.mp3", ""))
    }

    @Test
    fun `父目录推断`() {
        assertEquals("/sdcard/a", FolderSync.parentOf("/sdcard/a/b.mp3"))
        assertEquals(null, FolderSync.parentOf("/b.mp3"))
    }

    // ---------------- 老列表推断源文件夹 ----------------

    @Test
    fun `全部歌都在同一个目录时才推断出源文件夹`() {
        val paths = listOf("$root/晴天.mp3", "$root/七里香.mp3")
        assertEquals(listOf(root), FolderSync.inferRoots(paths))
    }

    @Test
    fun `歌分散在多个目录时不猜源文件夹`() {
        // 猜错会把别的目录的歌也带进列表，宁可不同步
        val paths = listOf("$root/周杰伦/晴天.mp3", "$root/林俊杰/江南.mp3")
        assertTrue(FolderSync.inferRoots(paths).isEmpty())
    }

    @Test
    fun `空列表推断不出源文件夹`() {
        assertTrue(FolderSync.inferRoots(emptyList()).isEmpty())
    }

    // ---------------- 差异计算 ----------------

    @Test
    fun `扫描到列表里没有的文件算新增`() {
        val files = listOf(
            FolderSyncFile("$root/晴天.mp3", 100, 1_000),
            FolderSyncFile("$root/七里香.mp3", 200, 2_000),
        )
        val existing = listOf(FolderSyncExisting(1, "$root/晴天.mp3", 100, 1_000))

        val diff = FolderSync.diff(existing, files, setOf(root))

        assertEquals(listOf("$root/七里香.mp3"), diff.addedPaths)
        assertTrue(diff.changedPaths.isEmpty())
        assertTrue(diff.removedTrackIds.isEmpty())
    }

    @Test
    fun `体积或修改时间变了算文件变化`() {
        val existing = listOf(
            FolderSyncExisting(1, "$root/晴天.mp3", 100, 1_000),
            FolderSyncExisting(2, "$root/七里香.mp3", 200, 2_000),
        )
        val files = listOf(
            FolderSyncFile("$root/晴天.mp3", 101, 1_000),   // 体积变了
            FolderSyncFile("$root/七里香.mp3", 200, 2_500), // 修改时间变了
        )

        val diff = FolderSync.diff(existing, files, setOf(root))

        assertEquals(listOf("$root/七里香.mp3", "$root/晴天.mp3"), diff.changedPaths)
        assertTrue(diff.addedPaths.isEmpty())
    }

    @Test
    fun `扫描不到且路径在源文件夹下的歌要被移除`() {
        val existing = listOf(
            FolderSyncExisting(1, "$root/晴天.mp3", 100, 1_000),
            FolderSyncExisting(2, "$root/删除的歌.mp3", 200, 2_000),
        )
        val files = listOf(FolderSyncFile("$root/晴天.mp3", 100, 1_000))

        val diff = FolderSync.diff(existing, files, setOf(root))

        assertEquals(listOf(2L), diff.removedTrackIds)
    }

    @Test
    fun `源文件夹不可访问时一条都不删`() {
        // 没挂载/没权限时扫描结果是空的，这时绝不能把列表清空
        val existing = listOf(FolderSyncExisting(1, "$root/晴天.mp3", 100, 1_000))

        val diff = FolderSync.diff(existing, emptyList(), accessibleRoots = emptySet())

        assertTrue(diff.removedTrackIds.isEmpty())
    }

    @Test
    fun `不在源文件夹下的单曲导入永远不动`() {
        val existing = listOf(
            FolderSyncExisting(1, "$root/晴天.mp3", 100, 1_000),
            FolderSyncExisting(2, "/storage/emulated/0/下载/手动导入.mp3", 300, 3_000),
        )
        val files = listOf(FolderSyncFile("$root/晴天.mp3", 100, 1_000))

        val diff = FolderSync.diff(existing, files, setOf(root))

        assertTrue(diff.removedTrackIds.isEmpty())
    }

    @Test
    fun `多个源文件夹里只删属于可访问文件夹的缺失歌`() {
        val otherRoot = "/storage/1A2B-3C4D/Music"
        val existing = listOf(
            FolderSyncExisting(1, "$root/晴天.mp3", 100, 1_000),
            FolderSyncExisting(2, "$otherRoot/江南.mp3", 200, 2_000),
        )

        // 只挂载了内置存储的目录，外置卡读不到
        val diff = FolderSync.diff(
            existing = existing,
            files = listOf(FolderSyncFile("$root/晴天.mp3", 100, 1_000)),
            accessibleRoots = setOf(root),
        )

        assertTrue(diff.removedTrackIds.isEmpty())
    }

    @Test
    fun `反斜杠路径也能正确比对`() {
        val existing = listOf(FolderSyncExisting(1, "\\storage\\emulated\\0\\0.伴奏\\晴天.mp3", 100, 1_000))
        val files = listOf(FolderSyncFile("$root/晴天.mp3", 100, 1_000))

        val diff = FolderSync.diff(existing, files, setOf(root))

        assertTrue(diff.isEmpty)
    }

    @Test
    fun `新增和删除可以同时发生`() {
        val existing = listOf(FolderSyncExisting(1, "$root/旧歌.mp3", 100, 1_000))
        val files = listOf(FolderSyncFile("$root/新歌.mp3", 200, 2_000))

        val diff = FolderSync.diff(existing, files, setOf(root))

        assertEquals(listOf("$root/新歌.mp3"), diff.addedPaths)
        assertEquals(listOf(1L), diff.removedTrackIds)
        assertFalse(diff.isEmpty)
    }
}
