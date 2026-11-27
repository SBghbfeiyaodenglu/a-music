package com.amusic.player.data.media

import android.os.Environment
import com.amusic.player.data.lyrics.EmbeddedLyricsReader
import java.io.File

/** 导入时认这些扩展名，其余文件在浏览界面不显示 */
val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus",
    "wav", "wma", "ape", "aif", "aiff", "mka",
)

/** 目录浏览里的一项：子目录或音频文件 */
data class BrowserEntry(
    val file: File,
    val isDirectory: Boolean,
    /** 来自 MediaStore 的补充信息（歌手、时长、专辑），没被媒体库索引过就是 null */
    val mediaMeta: DeviceAudio?,
    /** 同目录下是否存在同名 .lrc */
    val hasLrc: Boolean,
) {
    val name: String get() = file.name
    val path: String get() = file.absolutePath

    /** 去掉扩展名的显示名 */
    val displayName: String
        get() = if (isDirectory) name else name.substringBeforeLast('.', name)

    val sizeBytes: Long get() = if (isDirectory) 0L else file.length()

    val artist: String get() = mediaMeta?.artist.orEmpty()

    val durationMs: Long get() = mediaMeta?.durationMs ?: 0L
}

/**
 * 按目录浏览本地音频文件。
 *
 * 用 java.io.File 直接列目录，靠的是"所有文件访问"权限。
 * 只列当前目录、不递归子目录 —— 目的是"看得见这个文件夹里有什么，再决定导哪些"。
 */
object FileBrowser {

    /** 手机内部存储根目录 */
    fun storageRoot(): File = Environment.getExternalStorageDirectory()


    /**
     * 列出目录内容：子目录在前，音频文件在后，各自按名称排序。
     *
     * [mediaIndex] 是 MediaStore 的 path → 元数据映射，用来补歌手和时长；
     * 没被媒体库索引过的文件也能导入，只是这些字段暂时为空。
     */
    fun list(dir: File, mediaIndex: Map<String, DeviceAudio>): List<BrowserEntry> {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()

        // 同目录下所有 .lrc 的文件名主干，用于判断每个音频有没有配套歌词
        val lrcStems = children
            .filter { it.isFile && it.extension.equals("lrc", ignoreCase = true) }
            .map { it.name.substringBeforeLast('.').lowercase() }
            .toHashSet()

        return children
            // 隐藏以 . 开头的系统/缓存目录，避免一堆 .xxx 混在音乐目录里
            .filterNot { it.name.startsWith(".") }
            .mapNotNull { child ->
                when {
                    child.isDirectory -> BrowserEntry(child, isDirectory = true, mediaMeta = null, hasLrc = false)

                    child.isFile && child.extension.lowercase() in AUDIO_EXTENSIONS -> BrowserEntry(
                        file = child,
                        isDirectory = false,
                        mediaMeta = mediaIndex[child.absolutePath],
                        hasLrc = child.name.substringBeforeLast('.').lowercase() in lrcStems,
                    )

                    else -> null
                }
            }
            .sortedWith(compareByDescending<BrowserEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** 当前目录下的全部音频文件，用于"全选本目录" */
    fun audioFilesIn(dir: File): List<File> =
        runCatching { dir.listFiles() }.getOrNull().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }

    /** 一次批量导入最多收这么多首（用户可能手滑勾到内部存储根目录） */
    const val MAX_BATCH_FILES = 20_000

    /** 递归最多往下钻这么多层 */
    private const val MAX_DEPTH = 8

    /**
     * 递归收集一个目录（含所有子目录）里的音频文件 —— "按文件夹批量导入"用的。
     *
     * 用户把歌按分类放在一个个文件夹里（比如 /MP3/周杰伦音乐 所有专辑和单曲/…），
     * 勾一个文件夹就该把里面**所有**的歌一次带进来，包括专辑子目录里的。
     *
     * 保护措施（选到根目录这种手滑要扛得住）：
     * - 最多收 [MAX_BATCH_FILES] 首、最多往下 [MAX_DEPTH] 层
     * - 跳过以 `.` 开头的隐藏目录（.thumbnails 之类）
     * - 记住访问过的真实路径，软链接成环不会转死
     * - 列不动（没权限/已删除）的目录直接跳过，不往外抛异常
     *
     * 返回按绝对路径排好序：同一个专辑目录里就自然成了 01、02… 的顺序。
     */
    fun audioFilesRecursive(dir: File): List<File> {
        val out = ArrayList<File>()
        val visited = HashSet<String>()

        fun walk(current: File, depth: Int) {
            if (out.size >= MAX_BATCH_FILES || depth > MAX_DEPTH) return
            val real = runCatching { current.canonicalPath }.getOrDefault(current.absolutePath)
            if (!visited.add(real)) return

            val children = runCatching { current.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (out.size >= MAX_BATCH_FILES) return
                when {
                    child.isDirectory -> if (!child.name.startsWith(".")) walk(child, depth + 1)
                    child.isFile && child.extension.lowercase() in AUDIO_EXTENSIONS -> out += child
                }
            }
        }

        walk(dir, 0)
        return out.sortedBy { it.absolutePath.lowercase() }
    }

    /** 有媒体库元数据就用，没有就用文件本身的信息凑一个，保证能导入 */
    fun toDeviceAudio(file: File, meta: DeviceAudio?): DeviceAudio = meta ?: DeviceAudio(
        path = file.absolutePath,
        displayName = file.name,
        title = "",
        artist = "",
        album = "",
        durationMs = 0L,
        sizeBytes = file.length(),
        lastModified = file.lastModified(),
        year = "",
        genre = "",
    )

    /**
     * 文件里有没有内嵌歌词。
     *
     * 只读标签头，开销不大，但目录里文件多时仍然要放到后台线程批量做。
     * 缓存键带上大小和修改时间，文件换过标签就会重新检测。
     */
    private val embeddedCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun hasEmbeddedLyrics(file: File): Boolean {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        return embeddedCache.getOrPut(key) {
            EmbeddedLyricsReader.read(file.absolutePath) != null
        }
    }
}
