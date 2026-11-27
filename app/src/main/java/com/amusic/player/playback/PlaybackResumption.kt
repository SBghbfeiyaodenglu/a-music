package com.amusic.player.playback

import com.amusic.player.data.PlayMode
import com.amusic.player.data.db.TrackEntity
import java.io.File

/** 交给系统"媒体恢复"的队列：曲目、从第几首开始、从第几毫秒开始 */
internal data class ResumptionQueue(
    val items: List<QueueItem>,
    val startIndex: Int,
    val startPositionMs: Long,
)

/**
 * 从"上次播放状态 + 列表里的曲目"还原出要交给系统的播放队列。
 *
 * 为什么抽成纯函数：这条路的真机触发条件是"系统把进程回收掉"，很难复现，
 * 而最容易写错的地方恰恰是纯逻辑 —— 起点算错（接着放的成了别的歌）、
 * 倒序模式下队列顺序不对、文件已经被删掉的曲目还塞给系统。
 * 这些放单测里全都能覆盖，不必等系统来杀我们。
 *
 * @param fileExists 文件还在不在（测试里替换成假的）
 * @return null = 这次恢复不了（一首可播的都没有）
 */
internal fun buildResumptionQueue(
    tracks: List<TrackEntity>,
    currentTrackId: Long?,
    playMode: String?,
    positionMs: Long,
    fileExists: (String) -> Boolean = { File(it).isFile },
): ResumptionQueue? {
    // 文件已经不在了的不给系统：否则一点播放就报错，还不如恢复不了
    val playable = tracks.filter { fileExists(it.path) }
    if (playable.isEmpty()) return null

    // 和界面保持一致：倒序模式下播放队列是反的（见 MainScreen.orderedSongs）
    val ordered = if (playMode == PlayMode.REVERSE.name) playable.reversed() else playable
    // 上次那首已经不在了就从第一首开始，别让系统拿一个越界的起点
    val startIndex = ordered.indexOfFirst { it.id == currentTrackId }.coerceAtLeast(0)

    return ResumptionQueue(
        items = ordered.map { QueueItem(id = it.id, path = it.path, title = it.title, artist = it.artist) },
        startIndex = startIndex,
        startPositionMs = positionMs.coerceAtLeast(0L),
    )
}
