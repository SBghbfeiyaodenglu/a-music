package com.amusic.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.amusic.player.AppGraph
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * 后台播放服务。
 *
 * 播放器由服务持有，而不是放在界面里：这样退出应用、锁屏、从最近任务划掉之后音乐都还能继续放。
 * 通知栏控制、锁屏控制、蓝牙耳机按键由 MediaSessionService + MediaSession 统一处理，
 * 不需要我们自己搭通知和监听按键。
 *
 * 界面通过 MediaController 连到这个服务（见 PlaybackController）。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    // 这里要碰 Media3 标了 @UnstableApi 的"媒体恢复"回调。lint 认的是直接标 @UnstableApi
    // （Kotlin 的 @OptIn 它不认，会继续报 UnsafeOptInUsageError）
    @UnstableApi
    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 拔耳机自动暂停，避免突然外放
            .setHandleAudioBecomingNoisy(true)
            // ⚠ 必须显式开 wake lock：ExoPlayer 默认 C.WAKE_MODE_NONE，
            //   MediaSessionService 也不会替我们持有。清单里声明了 WAKE_LOCK 却不开这个，
            //   息屏/系统浅睡时后台播放（尤其缓冲那一段）会被挂起，
            //   表现就是"锁屏放着放着断了"。
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            // 系统"媒体恢复"：进程被回收后，系统会重新拉起本服务问"上次播的什么"
            .setCallback(ResumptionCallback(this))
            // ⚠ 必须给 sessionActivity：Media3 的通知用它当 contentIntent。
            //   不给的话**点通知栏/锁屏那块播控区域没有任何反应**（回不到应用）。
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, com.amusic.player.MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从最近任务里划掉应用时：
     * 还在播就继续播（音乐播放器应当如此），没在播就把服务收掉，不留后台常驻。
     *
     * ⚠ 这里**故意不调 super**：父类的默认实现是"没在播就暂停并停止服务"，
     * 但它判断的是 isAnySessionPlaying()，会把"暂停中的歌"也一起收掉 ——
     * 本应用要的是"划掉不打断"，暂停状态也要留着队列和进度。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/**
 * 读数据库的线程。**必须是守护线程**：这个回调可能在服务已经销毁之后才被系统调用
 * （系统拉起服务 → 问一句 → 我们异步查库），非守护线程会拖着整个进程不退出。
 */
private val resumptionExecutor: ListeningExecutorService = MoreExecutors.listeningDecorator(
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "a-music-resumption").apply { isDaemon = true }
    },
)

/**
 * 系统"媒体恢复"（Android 12+ 下拉通知栏/控制中心那张媒体卡片）。
 *
 * 场景：放着歌 → 暂停 → 系统把 App 进程回收了 → 你下拉通知栏，系统还留着那张卡片，
 * 点播放键时系统会**重新拉起本服务**并调用 [onPlaybackResumption] 问"上次播的是什么"。
 *
 * 这里从数据库里把"上次那个列表 + 上次那首歌 + 播到第几秒"还原成队列还给它。
 * 不实现这个回调的话，那张卡片的播放键点了没有任何反应，只能回应用里手动点歌
 * （播放中的播控不受影响 —— 那些是服务活着时 MediaSession 提供的）。
 *
 * 说明：
 * - 只覆盖这一个方法，其余回调沿用 Media3 的默认实现（播放/暂停/跳转/连接都不动）
 * - 返回的 future 解析成 **null** 就是"这次恢复不了"（Media3 的约定），系统便不恢复
 */
// onPlaybackResumption / MediaItemsWithStartPosition 在 Media3 里标了 @UnstableApi，
// 想用就得显式 opt-in（不写的话 lint 直接判 Error，构建过不去）
@UnstableApi
private class ResumptionCallback(private val context: Context) : MediaSession.Callback {

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        // ⚠ 这个回调在主线程被调用，而我们要查数据库 —— 必须立刻返回一个 future，
        //   把活丢到后台线程去做，否则就是主线程做磁盘 IO。
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        resumptionExecutor.execute {
            @Suppress("UNCHECKED_CAST")     // null = 这次恢复不了，Media3 就是这么约定的
            future.set(loadLastQueue(context) as MediaSession.MediaItemsWithStartPosition)
        }
        return future
    }

    /** 读出上次的队列；任何一步对不上（没有记录、列表空了、歌都不可播）就返回 null */
    private fun loadLastQueue(context: Context): MediaSession.MediaItemsWithStartPosition? = runCatching {
        val music = AppGraph.init(context).music
        val state = runBlocking { music.loadPlayerState() } ?: return@runCatching null
        val playlistId = state.currentPlaylistId ?: return@runCatching null
        val tracks = runBlocking { music.tracksOfPlaylist(playlistId) }

        // 排队顺序、起点、剔除不可播曲目这些都在纯函数里（有单测）
        val queue = buildResumptionQueue(
            tracks = tracks,
            currentTrackId = state.currentTrackId,
            playMode = state.playMode,
            positionMs = state.positionMs,
        ) ?: return@runCatching null

        MediaSession.MediaItemsWithStartPosition(
            queue.items.map { it.toMediaItem() },
            queue.startIndex,
            queue.startPositionMs,
        )
    }.getOrNull()
}
