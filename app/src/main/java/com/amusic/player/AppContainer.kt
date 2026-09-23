package com.amusic.player

import android.content.Context
import com.amusic.player.data.MusicRepository
import com.amusic.player.data.SettingsRepository
import com.amusic.player.data.db.AppDatabase
import com.amusic.player.data.lyrics.LyricsRepository
import com.amusic.player.data.media.AlbumArtSource
import com.amusic.player.data.media.AudioMetadataSource
import com.amusic.player.data.media.MediaStoreAudioSource
import com.amusic.player.playback.PlaybackController

/**
 * 极简的依赖容器：没有引入 DI 框架，构造函数注入也不值得，
 * 直接在这个对象里把需要的东西建出来。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database = AppDatabase.get(appContext)

    val settings = SettingsRepository(appContext)
    val mediaStore = MediaStoreAudioSource(appContext)
    val metadata = AudioMetadataSource(appContext)
    val music = MusicRepository(database.musicDao(), metadata)
    val lyrics = LyricsRepository()
    val albumArt = AlbumArtSource()
    val playback = PlaybackController(appContext)
}

/**
 * 全局容器。
 *
 * 用进程级单例而不是放在 Activity 里，是为了让播放器在屏幕旋转、Activity 重建时不被释放。
 * 接入 MediaSessionService 之后，播放器的生命周期会交给服务管理，这里只保留数据层。
 */
object AppGraph {

    @Volatile
    private var container: AppContainer? = null

    fun init(context: Context): AppContainer =
        container ?: synchronized(this) {
            container ?: AppContainer(context).also { container = it }
        }

    fun get(): AppContainer = container ?: error("AppGraph 尚未初始化")
}
