package com.amusic.player.data.media

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 设备上的一个音频文件。只描述"文件在哪、叫什么"，不复制文件。 */
data class DeviceAudio(
    val path: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModified: Long,
    /** 媒体库里没有风格字段，年份有；取不到的留给标签解析补 */
    val year: String = "",
    val genre: String = "",
)

/**
 * 从 MediaStore 查询设备上的音频文件，作为导入界面的候选列表。
 *
 * 这里拿到的是绝对路径。因为本项目采用全文件访问方案，
 * 拿到路径后就能直接读文件，也能读到它旁边的同名 .lrc。
 */
class MediaStoreAudioSource(private val context: Context) {

    /**
     * path → 元数据。
     * 目录浏览时用它给文件补上歌手、专辑和时长；没被媒体库索引过的文件不在这个表里，
     * 也不影响导入，只是这些字段暂时为空。
     */
    suspend fun queryIndex(): Map<String, DeviceAudio> = queryAll().associateBy { it.path }

    @Suppress("DEPRECATION")
    suspend fun queryAll(): List<DeviceAudio> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // ⚠ RELATIVE_PATH 是 API 29 才有的列：老机器上把它写进 projection 会让整条查询
        //    抛异常（被 runCatching 吞掉 → 索引永远是空的），所以按版本拼
        val projection = buildList {
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        // 过滤掉通知音、录音片段这类非音乐条目
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val result = mutableListOf<DeviceAudio>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val modifiedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relativeCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val displayName = nameCol.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                    if (displayName.isEmpty()) continue

                    // DATA 在 Android 10+ 已废弃，部分机型可能为空，用 RELATIVE_PATH 兜底
                    val dataPath = dataCol.takeIf { it >= 0 }?.let { cursor.getString(it) }
                    val relative = relativeCol.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                    val path = dataPath?.takeIf { it.isNotBlank() }
                        ?: "/storage/emulated/0/$relative$displayName"

                    result += DeviceAudio(
                        path = path,
                        displayName = displayName,
                        title = titleCol.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty(),
                        artist = artistCol.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                            .let { if (it == "<unknown>") "" else it },
                        album = albumCol.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty(),
                        durationMs = durationCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        sizeBytes = sizeCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        // MediaStore 的 DATE_MODIFIED 是**秒**，别处（File.lastModified）都是毫秒，
                        // 统一成毫秒存，免得以后拿它做"文件变了吗"的判断时永远对不上
                        lastModified = modifiedCol.takeIf { it >= 0 }
                            ?.let { cursor.getLong(it) * 1000L } ?: 0L,
                        year = yearCol.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                            .let { if (it == "0" || it == "<unknown>") "" else it },
                    )
                }
            }
        }
        result.sortedBy { it.displayName }
    }
}
