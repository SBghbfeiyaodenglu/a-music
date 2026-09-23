package com.amusic.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File
import java.util.ArrayDeque

/** 送给播放器的一条队列项。id 会写进 mediaId，用来在播放器自己换歌时反查是哪首。 */
data class QueueItem(
    val id: Long,
    val path: String,
    val title: String,
    val artist: String,
)

/**
 * 播放控制的对外接口，内部连的是 [PlaybackService] 里的播放器。
 *
 * **队列整体交给播放器**（用 setMediaItems 而不是单条 setMediaItem）。原因：
 * 媒体会话对外声明的可用命令来自播放器自身的能力，队列里只有一首歌时
 * 播放器会认为"没有下一首"，于是通知栏和锁屏上的"下一首"按钮**根本不会出现**。
 * 把整个列表交给播放器之后：
 *   - 上一首 / 下一首 由播放器原生支持，通知栏、锁屏、蓝牙耳机都能用
 *   - 播完自动下一首也由播放器处理，界面只需要跟着它的换歌回调更新
 *
 * 界面侧仍然保留"当前列表 + 当前序号"这套模型，通过 [onItemChanged] 和播放器保持同步。
 *
 * 两个要注意的点：
 *   1. 连接是异步的。连上之前发出的操作先排队，连上后按顺序执行。
 *   2. 所有读取都做了空值兜底：万一服务没连上，界面也只是显示"没在播"，不会崩。
 */
class PlaybackController(private val context: Context) {

    /** 队列自然播完（只有"列表循环"模式需要界面介入：切到下一个列表） */
    var onEnded: (() -> Unit)? = null

    /** 播放出错，参数是给用户看的原因 */
    var onError: ((String) -> Unit)? = null

    /** 是否真的在出声。界面状态以这个为准，不要自己反推。 */
    var onIsPlayingChanged: ((Boolean) -> Unit)? = null

    /** 播放器自己换歌了（自动下一首、或通知栏/锁屏切歌），参数是歌曲 id */
    var onItemChanged: ((Long) -> Unit)? = null

    private var controller: MediaController? = null
    private val pendingActions = ArrayDeque<MediaController.() -> Unit>()

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching {
                    val connected = future.get()
                    controller = connected
                    connected.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            onIsPlayingChanged?.invoke(isPlaying)
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            mediaItem?.mediaId?.toLongOrNull()?.let { onItemChanged?.invoke(it) }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            onError?.invoke(error.errorCodeName)
                        }
                    })
                    // 把连接期间攒下的操作按顺序补上
                    while (pendingActions.isNotEmpty()) {
                        pendingActions.removeFirst().invoke(connected)
                    }
                }.onFailure { error ->
                    // 连接不上（服务被限制后台启动、系统回收等）时要说出来：
                    // 否则界面会一直"点了没反应"（播放状态还是乐观置位的 true）
                    pendingActions.clear()
                    onError?.invoke(error.javaClass.simpleName)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun withController(action: MediaController.() -> Unit) {
        val connected = controller
        if (connected != null) connected.action() else pendingActions.addLast(action)
    }

    /**
     * 用整个列表组建播放队列并从 [startIndex] 开始播放。
     * 文件不存在时返回 false，交给界面提示"文件不存在"。
     */
    fun playQueue(items: List<QueueItem>, startIndex: Int, startPositionMs: Long = 0L): Boolean {
        val target = items.getOrNull(startIndex) ?: return false
        if (!File(target.path).exists()) return false
        withController {
            setMediaItems(items.map { it.toMediaItem() }, startIndex, startPositionMs)
            prepare()
            play()
        }
        return true
    }

    /** 只装载不播放，用于启动时恢复进度 */
    fun prepareQueue(items: List<QueueItem>, startIndex: Int, startPositionMs: Long = 0L): Boolean {
        val target = items.getOrNull(startIndex) ?: return false
        if (!File(target.path).exists()) return false
        withController {
            setMediaItems(items.map { it.toMediaItem() }, startIndex, startPositionMs)
            prepare()
            playWhenReady = false
        }
        return true
    }

    /**
     * 就地替换队列但保持播放进度和播放状态（切换播放模式导致队列顺序变化时用）。
     * 直接 setMediaItems 会把这首歌从头开始，所以显式 seek 回原位置。
     */
    fun replaceQueue(items: List<QueueItem>, startIndex: Int, positionMs: Long, keepPlaying: Boolean) {
        if (items.isEmpty() || startIndex !in items.indices) return
        withController {
            setMediaItems(items.map { it.toMediaItem() }, startIndex, positionMs)
            prepare()
            playWhenReady = keepPlaying
        }
    }

    /** 用播放器原生的随机：一轮内不重复，播完一轮自动重洗 */
    fun setShuffle(enabled: Boolean) = withController { shuffleModeEnabled = enabled }

    /** 传 Player.REPEAT_MODE_ALL / ONE / OFF */
    fun setRepeatMode(mode: Int) = withController { repeatMode = mode }

    fun seekToNext() = withController { if (hasNextMediaItem()) seekToNextMediaItem() }

    fun seekToPrevious() = withController { if (hasPreviousMediaItem()) seekToPreviousMediaItem() }

    /** 队列里还有没有下一首 / 上一首（列表循环模式据此决定要不要换列表） */
    val hasNext: Boolean get() = controller?.hasNextMediaItem() ?: false

    val hasPrevious: Boolean get() = controller?.hasPreviousMediaItem() ?: false

    /** 播放器里是否已经装载了歌曲 */
    val hasMedia: Boolean get() = (controller?.mediaItemCount ?: 0) > 0

    val isPlaying: Boolean get() = controller?.isPlaying ?: false

    val positionMs: Long get() = controller?.currentPosition ?: 0L

    /** 播放器还不知道时长时返回 0，调用方用歌曲元数据里的时长兜底 */
    val durationMs: Long
        get() = controller?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L

    fun togglePlayPause() = withController {
        if (isPlaying) {
            this.pause()
        } else {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                this.prepare()
            }
            this.play()
        }
    }

    fun pause() = withController { this.pause() }

    fun seekTo(positionMs: Long) = withController { this.seekTo(positionMs) }

    /**
     * 停止并清空当前媒体。
     * 只调 stop() 的话播放位置不会归零，界面会残留上一次的进度。
     */
    fun stop() = withController {
        this.stop()
        this.clearMediaItems()
    }

}

/**
 * 队列项 → MediaItem。
 *
 * 播放控制器和"媒体恢复"（[PlaybackService] 在进程被回收后重建队列）共用这一份转换，
 * 免得两处对通知里显示的标题/歌手做出不一样的解释。
 */
internal fun QueueItem.toMediaItem(): MediaItem {
    val builder = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(Uri.fromFile(File(path)))
    if (title.isNotBlank() || artist.isNotBlank()) {
        builder.setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title.ifBlank { File(path).name })
                .setArtist(artist.ifBlank { null })
                .build(),
        )
    }
    return builder.build()
}
