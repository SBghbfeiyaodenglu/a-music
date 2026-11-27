package com.amusic.player.data

/** 一次文件夹扫描看到的文件（只用来比对，不读标签） */
data class FolderSyncFile(
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** 列表里已有的歌曲（只取比对需要的字段） */
data class FolderSyncExisting(
    val trackId: Long,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** 一次同步要做的三件事；路径已归一化 */
data class FolderSyncDiff(
    val addedPaths: List<String>,
    val changedPaths: List<String>,
    val removedTrackIds: List<Long>,
) {
    val isEmpty: Boolean get() = addedPaths.isEmpty() && changedPaths.isEmpty() && removedTrackIds.isEmpty()
}

/**
 * 列表「跟随文件夹」的纯计算部分，方便单元测试。
 *
 * 规则：
 *   - 只处理**可访问**的源文件夹；文件夹暂时不在/读不到时，不删任何歌。
 *   - 新增：扫描到、列表里没有的文件。
 *   - 变化：路径相同，但体积/修改时间变了（标签被改过）→ 更新元数据。
 *   - 删除：列表里有、扫描不到，且路径在某个可访问源文件夹下面 → 移除引用。
 *   - 不在任何源文件夹下的歌（手动单曲导入）永远不动。
 */
object FolderSync {

    /**
     * 统一路径写法：反斜杠转正斜杠、去末尾斜杠，并把 Android 上指向同一位置的
     * 存储别名归一（/sdcard、/storage/self/primary、/mnt/sdcard 都是
     * /storage/emulated/0）。
     *
     * 不归一的话，同一首歌在列表里可能存着 /sdcard/... 和 /storage/emulated/0/...
     * 两条路径，文件夹同步会把它当成两首歌，搜索里也会出现两个同名结果。
     */
    // 这里必须写死存储别名：本类同时跑在 JVM 单元测试里，不能用 Android 的 Environment。
    @Suppress("SdCardPath")
    fun normalize(path: String): String {
        var normalized = path.replace('\\', '/').trimEnd('/')
        val aliases = listOf(
            "/storage/self/primary",
            "/mnt/sdcard",
            "/storage/emulated/legacy",
            "/sdcard",
        )
        for (alias in aliases) {
            if (normalized == alias) {
                normalized = "/storage/emulated/0"
                break
            }
            if (normalized.startsWith("$alias/")) {
                normalized = "/storage/emulated/0/" + normalized.removePrefix("$alias/")
                break
            }
        }
        return normalized
    }

    fun isUnder(path: String, root: String): Boolean {
        val p = normalize(path)
        val r = normalize(root)
        if (r.isEmpty()) return false
        return p == r || p.startsWith("$r/")
    }

    /** 路径的父目录；取不到返回 null */
    fun parentOf(path: String): String? {
        val p = normalize(path)
        val idx = p.lastIndexOf('/')
        return if (idx > 0) p.substring(0, idx) else null
    }

    /**
     * 老列表没有记录源文件夹时，按"所有歌共同的父目录"推断：
     * 只有全部歌曲都在同一个目录里才绑定它；分布在多个目录就不猜，
     * 避免把 /sdcard 这种上层目录绑进来、同步时把别的歌也带进列表。
     */
    fun inferRoots(paths: List<String>): List<String> {
        val parents = paths.mapNotNull { parentOf(it) }.distinct()
        return if (parents.size == 1) parents else emptyList()
    }

    fun diff(
        existing: List<FolderSyncExisting>,
        files: List<FolderSyncFile>,
        accessibleRoots: Set<String>,
    ): FolderSyncDiff {
        val filesByPath = files.associateBy { normalize(it.path) }
        val existingByPath = existing.associateBy { normalize(it.path) }

        val added = filesByPath.keys.filter { it !in existingByPath }.sorted()
        val changed = existing.mapNotNull { old ->
            val now = filesByPath[normalize(old.path)] ?: return@mapNotNull null
            if (now.sizeBytes != old.sizeBytes || now.lastModified != old.lastModified) {
                normalize(old.path)
            } else {
                null
            }
        }.sorted()
        val removed = existing.filter { old ->
            val path = normalize(old.path)
            path !in filesByPath && accessibleRoots.any { isUnder(path, it) }
        }.map { it.trackId }

        return FolderSyncDiff(addedPaths = added, changedPaths = changed, removedTrackIds = removed)
    }
}
