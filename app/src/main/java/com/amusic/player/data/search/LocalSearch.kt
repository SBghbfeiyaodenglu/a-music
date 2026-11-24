package com.amusic.player.data.search

import android.os.Environment
import com.amusic.player.data.media.AUDIO_EXTENSIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/** 本地文件搜索命中的一条 */
data class LocalFileHit(val file: File)

/**
 * 搜索手机里的音频文件。
 *
 * 搜索顺序按下面两级：
 *   1. 先扫一眼就能判断是音乐目录的（Music / MP3 / Download / Documents / 音乐 …），整棵子树扫完
 *   2. 再扫根目录下其余目录
 * 结果通过 Flow 边搜边吐，界面上能立刻看到东西，不用等整盘扫完。
 *
 * 靠"所有文件访问"权限直接遍历，不走 MediaStore —— 没被媒体库扫描到的文件也能搜到。
 */
object LocalSearch {

    /** 一眼能看出是音乐目录的名字，先搜这些 */
    private val PRIORITY_DIR_NAMES = setOf(
        "music", "mp3", "download", "documents", "music1",
        "音乐", "歌曲", "歌", "音频", "audio", "songs",
    )

    /** 系统目录读不到或没必要扫 */
    private val SKIP_DIR_NAMES = setOf("android", "data", "obb", "lost.dir")

    /** 结果上限，防止一次扫出几千条把界面拖垮 */
    private const val MAX_RESULTS = 200

    /** 遍历文件数上限，避免在超大存储上耗时过久 */
    private const val MAX_VISITED = 30_000

    fun searchAudioFiles(query: String): Flow<LocalFileHit> = flow {
        val keyword = query.trim().lowercase()
        if (keyword.isEmpty()) return@flow

        val root = Environment.getExternalStorageDirectory()
        val rootChildren = runCatching { root.listFiles() }.getOrNull().orEmpty()
        val allDirs = rootChildren.filter { it.isDirectory && isSearchable(it.name) }

        // 两组按顺序处理：先优先目录，再其余目录（数组顺序就是扫描顺序）
        val groups = listOf(
            allDirs.filter { it.name.lowercase() in PRIORITY_DIR_NAMES },
            allDirs.filter { it.name.lowercase() !in PRIORITY_DIR_NAMES },
        )

        val visited = HashSet<String>()
        var found = 0
        var visitedCount = 0

        for (roots in groups) {
            val stack = ArrayDeque<File>()
            roots.forEach { if (visited.add(it.absolutePath)) stack.addLast(it) }

            while (stack.isNotEmpty() && found < MAX_RESULTS && visitedCount < MAX_VISITED) {
                val dir = stack.removeLast()
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue

                for (child in children) {
                    if (found >= MAX_RESULTS || visitedCount >= MAX_VISITED) break
                    if (!child.isFile) continue
                    visitedCount++
                    if (child.extension.lowercase() in AUDIO_EXTENSIONS &&
                        child.name.lowercase().contains(keyword)
                    ) {
                        found++
                        emit(LocalFileHit(child))
                    }
                }

                for (child in children) {
                    if (child.isDirectory && isSearchable(child.name) && visited.add(child.absolutePath)) {
                        stack.addLast(child)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun isSearchable(dirName: String): Boolean {
        val lower = dirName.lowercase()
        if (lower.startsWith(".")) return false
        return lower !in SKIP_DIR_NAMES
    }
}
