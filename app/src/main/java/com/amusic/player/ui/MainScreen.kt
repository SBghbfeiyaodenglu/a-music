package com.amusic.player.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.amusic.player.AppContainer
import com.amusic.player.data.lyrics.EmbeddedLyricsWriter
import com.amusic.player.data.media.MetadataItem
import com.amusic.player.data.PlayMode
import com.amusic.player.playback.QueueItem
import androidx.media3.common.Player
import java.io.File
import com.amusic.player.data.PlaybackOrder
import com.amusic.player.data.Playlist
import com.amusic.player.data.Song
import com.amusic.player.data.lyrics.LyricsSource
import com.amusic.player.data.lyrics.ResolvedLyrics
import com.amusic.player.data.media.DeviceAudio
import com.amusic.player.data.media.Permissions
import com.amusic.player.data.media.TagWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 主界面：上方两页水平分页容器（歌曲列表 / 歌词），底部是共用的播放栏。
 * 底部播放栏不参与滑动，所以在两个页面上都能控制播放。
 */
@Composable
fun MainScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback = container.playback

    // ---------------- 数据 ----------------
    // 歌名来源要先于数据流声明：observePlaylists 需要它，改设置时要重建数据流
    // 歌名来源与列表显示的元数据字段
    var titleFromMetadata by remember { mutableStateOf(container.settings.titleFromMetadata()) }
    /** 没有内嵌封面时用哪一套色调：换歌递增一号（200 套一轮，见 PaletteTable） */
    var paletteIndex by remember { mutableIntStateOf(container.settings.paletteIndex()) }

    val playlistsFlow = remember(titleFromMetadata) {
        container.music.observePlaylists(titleFromMetadata)
    }
    val playlists by playlistsFlow.collectAsState(initial = emptyList())

    // ---------------- 界面状态 ----------------
    var currentListId by remember { mutableStateOf<Long?>(null) }
    var queue by remember { mutableStateOf<List<Song>>(emptyList()) }
    var queueIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var playMode by remember { mutableStateOf(PlayMode.ORDER) }
    var resolvedLyrics by remember { mutableStateOf(ResolvedLyrics.None) }
    /** 当前这首歌能不能写内嵌歌词（读文件头得出的结论；在解析歌词那次 IO 里算好） */
    var embeddedSupportedState by remember { mutableStateOf(false) }
    var lyricsOffsetMs by remember { mutableLongStateOf(0L) }

    var showNewListDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteListDialog by remember { mutableStateOf(false) }
    var showClearListDialog by remember { mutableStateOf(false) }
    var showDeleteAllListsDialog by remember { mutableStateOf(false) }
    // "删除所有列表"是最高危操作，要连续两道确认
    var showDeleteAllListsFinal by remember { mutableStateOf(false) }
    var showOffsetDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    // 列表自动居中的延迟设置弹窗（左上角菜单 →「列表自动居中」）
    var showListAutoCenterDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    var showMetadata by remember { mutableStateOf(false) }
    var showPlayModeSheet by remember { mutableStateOf(false) }

    // 歌词字号档位，从设置里读，改完立即保存
    var lyricsFontLevel by remember { mutableIntStateOf(container.settings.lyricsFontLevel()) }

    // 列表自动居中的延迟秒数（左上角菜单可改，默认 5 秒），改完立即保存
    var listAutoCenterSeconds by remember {
        mutableIntStateOf(container.settings.listAutoCenterSeconds())
    }

    // 元数据弹窗内容
    var metadataItems by remember { mutableStateOf<List<MetadataItem>>(emptyList()) }
    var metadataFileInfo by remember { mutableStateOf<List<MetadataItem>>(emptyList()) }
    var metadataLoading by remember { mutableStateOf(false) }
    /** 可编辑的标签（规范名→值）；null 表示这个格式不支持改（M4A/OGG 等） */
    var metadataEditable by remember { mutableStateOf<Map<String, String>?>(null) }
    var metadataSaving by remember { mutableStateOf(false) }

    // 批量移除：非空时弹确认框，确认后一次性删掉这些引用
    // 带 listId：对话框打开时就把它记下来（确认时才读 currentListId 会删错列表）
    var pendingBatchRemove by remember { mutableStateOf<BatchRemoveRequest?>(null) }

    // 调音量时在播放栏上方弹的小提示（-1 = 不显示）。用 tick 而不是百分比做 key，
    // 这样"已经到顶了还继续点"也会重新计时（否则值没变、提示会提前消失）
    var volumeHintPercent by remember { mutableIntStateOf(-1) }
    var volumeHintTick by remember { mutableIntStateOf(0) }
    // 界面上的音量百分比：按 5% 一格走，不直接读系统档位 ——
    // 手机的系统音量只有十几档（这台 15 档，一格 6.7%），读回来就只能是 6.7% 的倍数。
    // -1 = 还没点过（第一次点按会按当前真实档位对齐）
    var volumePercent by remember { mutableIntStateOf(-1) }
    LaunchedEffect(volumeHintTick) {
        if (volumeHintTick > 0) {
            delay(1500L)
            volumeHintPercent = -1
        }
    }

    // 首次运行就提示权限：这两个权限缺一个，导入和歌词都会有功能不可用，
    // 所以不等用户点到那一步才提示，启动时就弹
    var showPermissionPrompt by remember { mutableStateOf(false) }
    // 用户是不是"为了导入"才来要权限的。
    // 只有从「导入」入口进来的才在授权完成后自动继续进导入页；
    // 启动时自动弹的那个权限说明只是说明，授权完不该把用户丢进导入页。
    var importIntent by remember { mutableStateOf(false) }
    // 首次安装：等主界面画出来 1 秒再弹权限说明。
    // 一进来就弹会被误当成"启动就拦截"，也让用户来不及看清后面的界面。
    LaunchedEffect(Unit) {
        delay(1_000L)
        if (!Permissions.hasAudio(context) || !Permissions.hasAllFilesAccess()) {
            showPermissionPrompt = true
        }
    }

    // 每个列表各记各的滚动位置：用列表 id 存 LazyListState。
    // 放在这里（而不是列表页内部）是因为列表页在切到歌词页后会被回收，
    // 状态放里面会跟着丢，回到列表页就都回到顶部了。
    val listScrollStates = remember { mutableMapOf<Long, LazyListState>() }

    // 在线搜索歌词：非空时打开
    var showOnlineLyrics by remember { mutableStateOf(false) }

    // 歌词内容编辑器：非空时打开；初值取当前歌词原文，并记住它来自 .lrc 还是内嵌
    var showLyricsEditor by remember { mutableStateOf(false) }
    var lyricsEditorText by remember { mutableStateOf("") }
    var lyricsEditorCreatesLrc by remember { mutableStateOf(false) }

    // ---------------- 定位到正在播放的歌 ----------------
    // scrollToSongId 交给列表页去滚动；locatedSongId 是定位后那几秒的临时高亮
    var locateSongId by remember { mutableStateOf<Long?>(null) }
    var locatedSongId by remember { mutableStateOf<Long?>(null) }

    // 睡眠定时器：存"结束时刻"，进程被回收再回来倒计时依然准
    var showSleepTimer by remember { mutableStateOf(false) }
    var sleepEndAt by remember { mutableLongStateOf(container.settings.sleepTimerEndAt()) }
    var sleepRemainingMs by remember { mutableLongStateOf(0L) }
    // 带 listId：弹窗打开那一刻就锁定"在哪個列表里移除"
    var missingFileSong by remember { mutableStateOf<MissingFileRequest?>(null) }

    var showImport by remember { mutableStateOf(false) }
    // ⚠ 打开导入页时把"要导进哪个列表"记下来：
    //   用户可能在目录里翻好几分钟，而这期间当前列表会变（列表循环模式放完一轮会自动切列表），
    //   确认时再读 currentListId 就会把歌导进另一个列表。
    var importTargetListId by remember { mutableStateOf<Long?>(null) }
    var importLoading by remember { mutableStateOf(false) }

    // 目录浏览时用来补歌手/时长的媒体库索引
    var mediaIndex by remember { mutableStateOf<Map<String, DeviceAudio>>(emptyMap()) }

    // 点"导入"后如果发现重复歌曲，先挂在这里等用户决定
    var duplicatePrompt by remember { mutableStateOf<DuplicatePrompt?>(null) }

    var hasAllFilesAccess by remember { mutableStateOf(Permissions.hasAllFilesAccess()) }
    // 音频权限也用状态存：从系统权限弹窗回来、或从设置页回来时要能立刻反映到界面上
    // 每次回到前台 +1，用来强制权限弹窗重新计算一次（即使权限值没变也重算，
    // 免得出现"在设置页打开了开关、回到应用弹窗还挂着"的尴尬）
    var resumeTick by remember { mutableIntStateOf(0) }

    val currentList: Playlist? = playlists.firstOrNull { it.id == currentListId }
    val currentSong: Song? = queue.getOrNull(queueIndex)
    // 当前这首歌确实有歌词内容（同名 LRC 或内嵌）才能去调偏移 / 编辑歌词：
    // 没有歌词时点开偏移，改的到底是哪首歌根本没意义
    val hasLyrics = currentSong != null && resolvedLyrics.lines.isNotEmpty()
    val pagerState = rememberPagerState(pageCount = { 2 })

    // 从"所有文件访问"设置页、或系统权限弹窗回来时重新检查授权状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = Permissions.hasAllFilesAccess()
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 按当前模式把列表内容排成播放顺序。随机交给播放器原生随机，这里只处理倒序。 */
    fun orderedSongs(songs: List<Song>): List<Song> =
        if (playMode == PlayMode.REVERSE) songs.reversed() else songs

    fun List<Song>.toQueueItems(): List<QueueItem> =
        map { QueueItem(id = it.id, path = it.path, title = it.title, artist = it.artist) }

    /** 用当前列表按当前模式重建队列，尽量保持正在播的那首歌 */
    fun rebuildQueue(keepCurrent: Boolean) {
        val anchorId = if (keepCurrent) queue.getOrNull(queueIndex)?.id else null
        queue = orderedSongs(currentList?.songs.orEmpty())
        queueIndex = if (anchorId == null) -1 else queue.indexOfFirst { it.id == anchorId }
    }

    /**
     * 把播放模式翻译成播放器的循环方式：
     *   顺序 / 倒序 / 随机 → 在当前列表内循环（REPEAT_MODE_ALL）
     *   单曲循环          → REPEAT_MODE_ONE
     *   列表循环          → REPEAT_MODE_OFF，播完队列触发 onEnded，由界面切到下一个列表
     */
    fun applyPlayModeToPlayer() {
        playback.setShuffle(playMode == PlayMode.SHUFFLE)
        playback.setRepeatMode(
            when (playMode) {
                PlayMode.LOOP_ONE -> Player.REPEAT_MODE_ONE
                PlayMode.LOOP_LIST -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_ALL
            },
        )
    }

    // ---------------- 启动状态恢复 ----------------
    var restoredTrackId by remember { mutableStateOf<Long?>(null) }
    var restoredPositionMs by remember { mutableLongStateOf(0L) }
    // 必须等播放状态真的读出来再谈恢复：否则列表先返回时会把 restored 提前置位，
    // 等 trackId 读到时已经不再重试，结果就是"什么都没恢复"
    var playerStateLoaded by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    // 恢复流程跑完之前不要落盘，否则启动瞬间会用空值把上次的进度覆盖掉
    var restoreDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val state = container.music.loadPlayerState()
        if (state != null) {
            restoredTrackId = state.currentTrackId
            restoredPositionMs = state.positionMs
            playMode = runCatching { PlayMode.valueOf(state.playMode) }.getOrDefault(PlayMode.ORDER)
            if (state.currentPlaylistId != null) currentListId = state.currentPlaylistId
        }
        playerStateLoaded = true
    }

    LaunchedEffect(playlists, restoredTrackId, playerStateLoaded) {
        if (!playerStateLoaded) return@LaunchedEffect
        // 列表数据还没从数据库回来时不能判定"恢复完成"：
        // 那会先把 restored 置位，等真实列表到达时就不再恢复，接着一次落盘就把
        // 上次的歌曲清成空，表现就是"重启后什么都没恢复"。
        if (playlists.isEmpty()) return@LaunchedEffect
        if (currentListId == null || playlists.none { it.id == currentListId }) {
            currentListId = playlists.first().id
        }
        if (!restored) {
            restored = true
            val trackId = restoredTrackId
            val owner = if (trackId == null) {
                null
            } else {
                playlists.firstOrNull { playlist -> playlist.songs.any { it.id == trackId } }
            }
            if (owner != null) {
                // ⚠ 必须走 orderedSongs：倒序模式下队列要真的倒过来，
                // 否则恢复之后"下一首"会按正序走，和当前模式对不上
                val songs = orderedSongs(owner.songs)
                val index = songs.indexOfFirst { it.id == trackId }
                queue = songs
                queueIndex = index
                durationMs = songs[index].durationMs
                currentListId = owner.id
                if (playback.hasMedia) {
                    // 播放器里已经有歌了（界面重建但进程和播放器都还活着，
                    // 例如从后台回来）。这时**绝对不能**再 prepare 一次：
                    // prepareFile 里会写 playWhenReady = false，等于把正在播的歌按停。
                    // 进度由进度轮询从播放器实时读，不做回退。
                } else {
                    // 只恢复进度，不自动播放。这里必须把队列装进播放器，
                    // 否则用户点播放时播放器是空的，会立刻 ENDED 并自动跳到下一首。
                    positionMs = restoredPositionMs
                    applyPlayModeToPlayer()
                    playback.prepareQueue(
                        items = queue.toQueueItems(),
                        startIndex = index,
                        startPositionMs = restoredPositionMs,
                    )
                }
            }
        }
        restoreDone = true
    }

    // ---------------- 播放动作 ----------------

    // 通知权限：首次播放时申请，用于通知栏与锁屏控制。
    // 即使用户拒绝，播放照常，只是没有通知栏控制。
    var notificationAsked by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            toast(context, "没有通知权限，通知栏和锁屏上看不到播放控制（播放不受影响）")
        }
    }

    fun ensureNotificationPermission() {
        if (notificationAsked) return
        notificationAsked = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Permissions.hasNotifications(context)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }


    /** 播放队列里的第 index 首（必要时先按模式重建队列） */
    fun playAt(index: Int) {
        val song = queue.getOrNull(index) ?: return
        queueIndex = index
        durationMs = song.durationMs
        positionMs = 0L
        ensureNotificationPermission()
        applyPlayModeToPlayer()
        if (playback.playQueue(queue.toQueueItems(), index)) {
            isPlaying = true
        } else {
            // 文件不存在：保留列表记录，弹窗提示用户
            isPlaying = false
            missingFileSong = MissingFileRequest(listId = currentListId, song = song)
        }
    }

    /**
     * 列表循环专用：切到相邻的**非空**列表继续播放。
     *
     * 只有一个列表时，"下一个"就是它自己，于是永远循环这一个列表；
     * 空列表会被跳过；所有列表都空就停下来。
     */
    fun switchToNeighbourList(forward: Boolean) {
        val index = PlaybackOrder.nextNonEmptyList(
            listSizes = playlists.map { it.songs.size },
            currentListIndex = playlists.indexOfFirst { it.id == currentListId },
            forward = forward,
        )
        if (index < 0) {
            // 所有列表都是空的，没什么可放
            isPlaying = false
            playback.pause()
            return
        }
        val candidate = playlists[index]
        // 自动打开这个列表，并在新列表里接着播
        currentListId = candidate.id
        queue = orderedSongs(candidate.songs)
        playAt(if (forward) 0 else queue.lastIndex)
    }

    /**
     * 上一首 / 下一首。
     *
     * 推进本身交给播放器（上一首/下一首、以及播完自动下一首都由它原生处理），
     * 界面只在"列表循环"模式下介入：队列走到头时换成相邻列表。
     */
    fun step(forward: Boolean, auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto) {
            // 自然播完。其它模式的循环由播放器的循环模式自己接上，
            // 只有列表循环要在这里切到下一个列表。
            if (playMode == PlayMode.LOOP_LIST) switchToNeighbourList(forward = true)
            return
        }
        if (playMode == PlayMode.LOOP_LIST) {
            val atEdge = if (forward) !playback.hasNext else !playback.hasPrevious
            if (atEdge) {
                switchToNeighbourList(forward)
                return
            }
        }
        if (forward) playback.seekToNext() else playback.seekToPrevious()
    }

    // 播放器回调。用 rememberUpdatedState 保证闭包读到的是最新的状态。
    val onEndedHandler by rememberUpdatedState {
        scope.launch { step(forward = true, auto = true) }
    }
    val onErrorHandler by rememberUpdatedState { message: String ->
        scope.launch { toast(context, "播放失败：$message") }
    }
    DisposableEffect(playback) {
        playback.onEnded = { onEndedHandler() }
        playback.onError = { message -> onErrorHandler(message) }
        // 播放/暂停状态以播放器回调为准，不要自己反推（见 PlaybackController 的说明）
        playback.onIsPlayingChanged = { playing -> isPlaying = playing }
        // 播放器自己换歌（播完自动下一首、或通知栏/锁屏切歌）时，界面跟着走
        playback.onItemChanged = { songId ->
            val index = queue.indexOfFirst { it.id == songId }
            if (index >= 0) {
                queueIndex = index
                durationMs = queue[index].durationMs
                positionMs = 0L
            }
        }
        // 界面重建时状态是重置的，而播放器状态可能没变化、不会触发回调，
        // 所以这里主动对齐一次，避免"音乐在响但按钮显示播放"。
        isPlaying = playback.isPlaying
        onDispose {
            playback.onEnded = null
            playback.onError = null
            playback.onIsPlayingChanged = null
            playback.onItemChanged = null
        }
    }

    // 进度同步 + 定期保存播放状态。
    // 持续轮询而不是拿 isPlaying 当循环条件：写入相同的值不会触发重组，暂停期间几乎没开销，
    // 但能避免"缓冲阶段被误判成暂停"这类问题——用 player.isPlaying 反推会导致
    // 音乐在响而界面显示暂停、进度停在 0:00。
    LaunchedEffect(isPlaying) {
        // 位置和时长写进状态；读取它们的只有进度条那个小组件和时间文字，
        // 主界面本身不读，所以这里刷新不会引起整屏重组重绘
        fun syncProgress() {
            if (playback.hasMedia) {
                if (!seeking) {
                    positionMs = playback.positionMs
                }
                val playerDuration = playback.durationMs
                if (playerDuration > 0L) durationMs = playerDuration
            } else {
                // 播放器里已经没有媒体（例如删除了所有列表）：进度、时长、播放状态一起归零。
                // 没有媒体时 isPlaying 必然为 false，在这里直接落定是安全的。
                positionMs = 0L
                durationMs = 0L
                isPlaying = false
            }
        }

        syncProgress()
        if (!isPlaying) return@LaunchedEffect

        // 只在播放中轮询：暂停和空闲时一次都不刷新，界面彻底静止。
        // 不能做成无条件 5Hz 轮询 + 界面直接读位置值 —— 那等于整屏每 200ms 重绘一次，
        // 白天看不出来，夜间低亮度下 OLED 上能看到有规律的一闪一闪。
        var ticks = 0
        while (isPlaying) {
            delay(200L)
            syncProgress()
            if (++ticks % 25 == 0) {
                container.music.savePlayerState(
                    currentListId,
                    queue.getOrNull(queueIndex)?.id,
                    playback.positionMs,
                    playMode,
                )
            }
        }
    }

    // 歌曲切换、列表切换、模式切换时立即落盘。
    // 必须等恢复流程跑完：启动瞬间 currentSong 还是空的，这时候写会把上次的进度清掉。
    LaunchedEffect(currentListId, currentSong?.id, playMode, restoreDone) {
        if (!restoreDone) return@LaunchedEffect
        container.music.savePlayerState(currentListId, currentSong?.id, positionMs, playMode)
    }

    // 换歌时解析歌词并读取该曲的偏移量；顺便把背景色调推进到下一套
    LaunchedEffect(currentSong?.id) {
        val song = currentSong
        resolvedLyrics = ResolvedLyrics.None
        lyricsOffsetMs = 0L
        // 一起清掉"支持内嵌歌词吗"的结论（它要读文件头，不能放在合成里算）
        embeddedSupportedState = false
        // 换了歌就把这三个跟"某一首歌"绑定的弹窗全收掉：
        //   - 偏移弹窗：这一首没歌词它会被隐藏，下一首有歌词又自己弹出来
        //   - 在线搜词 / 歌词编辑：它们里面保存时用的是 `currentSong.path`，
        //     弹窗开着的时候歌自己放完了（尤其在线搜词要联网等好几秒），
        //     再点保存就会把 A 歌的歌词写进 B 歌的文件
        showOffsetDialog = false
        showOnlineLyrics = false
        showLyricsEditor = false
        lyricsEditorText = ""
        if (song == null) return@LaunchedEffect
        paletteIndex = (paletteIndex + 1).also { container.settings.setPaletteIndex(it) }
        lyricsOffsetMs = container.music.loadLyricOffset(song.id)
        val track = container.music.findTrack(song.id) ?: return@LaunchedEffect
        resolvedLyrics = container.lyrics.resolve(track)
        embeddedSupportedState = withContext(Dispatchers.IO) {
            EmbeddedLyricsWriter.supported(song.path)
        }
        // 歌词来自本地 .lrc 时以文件为准：偏移是直接写进文件时间戳的，
        // 这里如果还留着一个应用内偏移量，就会和文件叠加，越调越偏。
        // 旧版本存下来的偏移值在这里自动清掉。
        if (resolvedLyrics.source == LyricsSource.LRC_FILE && lyricsOffsetMs != 0L) {
            container.music.saveLyricOffset(song.id, 0L)
            lyricsOffsetMs = 0L
        }
    }

    // ---------------- 导入流程 ----------------
    /** 媒体库索引只用来补歌手和时长，拿不到也不影响导入和搜索 */
    fun ensureMediaIndex() {
        if (mediaIndex.isEmpty() && !importLoading) {
            importLoading = true
            scope.launch {
                mediaIndex = runCatching { container.mediaStore.queryIndex() }.getOrDefault(emptyMap())
                importLoading = false
            }
        }
    }

    fun openImport() {
        showImport = true
        importTargetListId = currentListId
        ensureMediaIndex()
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            when {
                // 本来就点的是「导入」、权限也齐了 → 继续进导入页
                importIntent && Permissions.hasAllFilesAccess() -> {
                    importIntent = false
                    openImport()
                }
                // 还缺"所有文件访问" → 回主界面把话说清楚（是不是要导入由 importIntent 记着）
                !Permissions.hasAllFilesAccess() -> showPermissionPrompt = true
                // 只是启动时弹的权限说明，授权完就结束，不要跳任何界面
                else -> Unit
            }
        } else {
            toast(context, "没有音频读取权限，读不到歌手和时长（仍可导入）")
            // ⚠ 只有"用户本来就是点导入来的"才继续进导入页。
            // 启动时那个权限说明被拒，就老实留在主界面 —— 否则等于把用户"丢进"导入页。
            if (importIntent) openImport()
        }
    }

    fun requestImport() {
        if (playlists.isEmpty()) {
            toast(context, "请先点右上角 + 新建一个列表")
            return
        }
        if (currentList == null) {
            toast(context, "请先选择一个列表")
            return
        }
        // 从这里开始，用户的目的就是导入：授权走完（可能跨两次弹窗）要自动接着进导入页
        importIntent = true
        if (!Permissions.hasAudio(context)) {
            audioPermissionLauncher.launch(Permissions.audioPermission())
            return
        }
        // 浏览目录、读同名 .lrc 都需要"所有文件访问"。缺它的话先在主界面解释清楚，
        // 别让用户先进到目录里、发现什么都看不到、再回头找那个"去授权"按钮。
        if (!Permissions.hasAllFilesAccess()) {
            showPermissionPrompt = true
            return
        }
        importIntent = false
        openImport()
    }

    fun doImport(listId: Long, audios: List<DeviceAudio>) {
        scope.launch {
            val added = container.music.importAudios(listId, audios)
            toast(context, "已导入 $added 首")
        }
    }

    // ---------------- 渲染 ----------------
    if (showImport) {
        // 标题和导入目标都用打开时锚定的那个列表
        val targetList = playlists.firstOrNull { it.id == importTargetListId } ?: currentList
        ImportScreen(
            listName = targetList?.name.orEmpty(),
            mediaIndex = mediaIndex,
            loadingIndex = importLoading,
            hasAllFilesAccess = hasAllFilesAccess,
            onRequestAllFilesAccess = {
                toast(context, "请在系统设置里打开「所有文件访问」，回来后会自动刷新")
                if (!Permissions.openAllFilesSettings(context)) {
                    toast(context, "没找到系统设置页：请到 设置 → 应用管理 → A-Music → 权限 里手动打开")
                }
            },
            onCancel = { showImport = false },
            onConfirm = { selected ->
                val listId = importTargetListId
                if (listId == null || playlists.none { it.id == listId }) {
                    showImport = false
                } else {
                    showImport = false
                    // 重复检测：已在目标列表里的先挑出来，交给用户决定
                    val existingPaths = targetList?.songs?.map { it.path }?.toHashSet().orEmpty()
                    val duplicates = selected.filter { it.path in existingPaths }
                    val fresh = selected.filterNot { it.path in existingPaths }
                    if (duplicates.isEmpty()) {
                        doImport(listId, selected)
                    } else {
                        duplicatePrompt = DuplicatePrompt(fresh = fresh, duplicates = duplicates, listId = listId)
                    }
                }
            },
        )
        return
    }

    // 专辑封面背景：有内嵌封面就模糊后铺在最底层，没有就保持默认背景色（不需要开关，
    // 有没有封面自己就决定了）。
    // 放在最外层，列表页和歌词页共用同一张；顶栏和播放栏覆盖在它上面。
    val albumArt = rememberAlbumArtBackground(currentSong?.path, paletteIndex)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 歌词页（第 1 页）用"中间留亮"的蒙版，列表页用"往下渐暗"的 —— 两个界面靠背景就能区分
        AlbumArtBackground(art = albumArt, onLyricsPage = pagerState.currentPage == 1)

        Column(Modifier.fillMaxSize()) {
            TopBarSection(
                playlists = playlists,
                currentListId = currentListId,
                currentListName = currentList?.name,
                currentListSongCount = currentList?.songs?.size ?: 0,
                onSelectList = { currentListId = it.id },
                onNewList = { showNewListDialog = true },
                onRenameList = { showRenameDialog = true },
                onClearList = { showClearListDialog = true },
                onLyricsFont = { showFontDialog = true },
                listAutoCenterSeconds = listAutoCenterSeconds,
                onListAutoCenter = { showListAutoCenterDialog = true },
                hasCurrentSong = currentSong != null,
                onShowMetadata = { showMetadata = true },
                onDeleteList = { showDeleteListDialog = true },
                onDeleteAllLists = { showDeleteAllListsDialog = true },
                onImport = { requestImport() },
                onSleepTimer = { showSleepTimer = true },
                sleepRemainingProvider = { sleepRemainingMs },
                onSearch = {
                    showSearch = true
                    ensureMediaIndex()
                },
                modifier = Modifier.statusBarsPadding(),
            )

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> SongListPage(
                        songs = currentList?.songs.orEmpty(),
                        hasAnyList = playlists.isNotEmpty(),
                        currentSongId = currentSong?.id,
                        onSongClick = { index ->
                            val list = currentList ?: return@SongListPage
                            val clickedId = list.songs.getOrNull(index)?.id ?: return@SongListPage
                            // 队列按当前模式排（倒序时是倒过来的），所以索引要重新算一遍
                            val songs = orderedSongs(list.songs)
                            queue = songs
                            playAt(songs.indexOfFirst { it.id == clickedId })
                            scope.launch { pagerState.animateScrollToPage(1) }
                        },
                        listKey = currentListId,
                        onRemoveSelected = { ids ->
                            pendingBatchRemove = BatchRemoveRequest(
                                listId = currentListId,
                                songs = currentList?.songs?.filter { it.id in ids }.orEmpty(),
                            )
                        },
                        listState = listScrollStates.getOrPut(currentListId ?: -1L) { LazyListState() },
                        autoCenterIdleMs = listAutoCenterSeconds * 1000L,
                        scrollToSongId = locateSongId,
                        onScrolledToSong = {
                            // 滚动到位后才开始那 6 秒高亮
                            locatedSongId = locateSongId
                            locateSongId = null
                        },
                        locatedSongId = locatedSongId,
                        onLocateCurrent = {
                            val songId = currentSong?.id
                            if (songId == null) {
                                toast(context, "当前没有正在播放的歌曲")
                            } else {
                                // 正在播放的歌可能不在当前列表里：先找它在哪个列表，找不到就报一声
                                val owner = playlists.firstOrNull { list -> list.songs.any { it.id == songId } }
                                if (owner == null) {
                                    toast(context, "正在播放的歌曲不在任何列表里")
                                } else {
                                    if (owner.id != currentListId) currentListId = owner.id
                                    locateSongId = songId
                                }
                            }
                        },
                        onImport = { requestImport() },
                        onNewList = { showNewListDialog = true },
                    )

                    else -> LyricsPage(
                        song = currentSong,
                        resolved = resolvedLyrics,
                        positionProvider = { positionMs },
                        offsetMs = lyricsOffsetMs,
                        fontLevel = lyricsFontLevel,
                        allFilesAccessMissing = !hasAllFilesAccess,
                        onRequestAllFilesAccess = {
                            importIntent = false
                            showPermissionPrompt = true
                        },
                    )
                }
            }

            PlayerBar(
                song = currentSong,
                isPlaying = isPlaying,
                playMode = playMode,
                positionProvider = { positionMs },
                durationProvider = { durationMs },
                onPrev = { step(forward = false) },
                onNext = { step(forward = true) },
                onTogglePlay = {
                    val song = currentSong
                    if (song != null) {
                        if (!playback.hasMedia) {
                            // 播放器里还没装队列（例如启动恢复后又被清空），先装再播
                            playAt(if (queueIndex >= 0) queueIndex else 0)
                        } else {
                            // 从"恢复的上次状态"直接播放时也要有通知权限，否则通知栏/锁屏都没有控制
                            ensureNotificationPermission()
                            playback.togglePlayPause()
                        }
                    }
                },
                onVolumeDown = {
                    volumePercent = adjustVolume(context, up = false, lastPercent = volumePercent)
                    volumeHintPercent = volumePercent
                    volumeHintTick++
                },
                onVolumeUp = {
                    volumePercent = adjustVolume(context, up = true, lastPercent = volumePercent)
                    volumeHintPercent = volumePercent
                    volumeHintTick++
                },
                onSeek = {
                    seeking = true
                    positionMs = it
                },
                onSeekFinished = { target ->
                    // 用回调带回来的目标位置，别读 positionMs ——
                    // 点按时它还是旧值（同一个事件里刚 set 还没生效）
                    playback.seekTo(target)
                    positionMs = target
                    seeking = false
                },
                onSeekCancelled = {
                    // 拖动被系统打断：把"正在拖动"清掉，进度条立刻恢复跟播放器走
                    seeking = false
                },
                onOpenPlayMode = { showPlayModeSheet = true },
                onOpenOffset = { if (hasLyrics) showOffsetDialog = true },
                onOpenOnlineSearch = { showOnlineLyrics = true },
                volumeHintPercent = volumeHintPercent,
                offsetEnabled = hasLyrics,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    // 定位高亮只显示 6 秒：定位的目的是"帮你找到那首歌"，找到就够了，
    // 长期高亮反而和"正在播放"的底色混淆
    LaunchedEffect(locatedSongId) {
        if (locatedSongId != null) {
            delay(6_000L)
            locatedSongId = null
        }
    }

    // 睡眠定时器倒计时：只有真的有时钟在跑时才每秒走一格，
    // 到点暂停播放并清掉定时器（暂停而不是停止，第二天打开还在原位）
    LaunchedEffect(sleepEndAt) {
        if (sleepEndAt <= 0L) {
            sleepRemainingMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            val left = sleepEndAt - System.currentTimeMillis()
            sleepRemainingMs = left.coerceAtLeast(0L)
            if (left <= 0L) {
                playback.pause()
                container.settings.setSleepTimerEndAt(0L)
                sleepRemainingMs = 0L
                sleepEndAt = 0L
                toast(context, "睡眠定时器到点，已暂停播放")
                break
            }
            delay(1_000L)
        }
    }

    // ---------------- 弹窗 ----------------
    if (showNewListDialog) {
        ListNameDialog(
            title = "新建列表",
            initialName = "",
            confirmLabel = "创建",
            onDismiss = { showNewListDialog = false },
            onConfirm = { name ->
                showNewListDialog = false
                scope.launch {
                    val id = container.music.createPlaylist(name)
                    currentListId = id
                }
            },
        )
    }

    if (showRenameDialog) {
        val target = currentList
        ListNameDialog(
            title = "重命名列表",
            initialName = target?.name.orEmpty(),
            confirmLabel = "保存",
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                showRenameDialog = false
                target?.let { scope.launch { container.music.renamePlaylist(it.id, name) } }
            },
        )
    }

    if (showDeleteListDialog) {
        val target = currentList
        ConfirmDialog(
            title = "删除列表",
            message = "只删除列表记录和它里面的歌曲引用，不会删除手机上的任何歌曲文件。",
            confirmLabel = "删除",
            onDismiss = { showDeleteListDialog = false },
            onConfirm = {
                showDeleteListDialog = false
                target?.let { list ->
                    scope.launch {
                        container.music.deletePlaylist(list.id)
                        currentListId = playlists.firstOrNull { it.id != list.id }?.id
                    }
                }
            },
        )
    }

    if (showClearListDialog) {
        val target = currentList
        ConfirmDialog(
            title = "清空当前列表",
            message = "把「${target?.name.orEmpty()}」里的 ${target?.songs?.size ?: 0} 首歌曲全部移出列表？" +
                "只删除引用，不会删除手机上的任何歌曲文件。",
            confirmLabel = "清空",
            onDismiss = { showClearListDialog = false },
            onConfirm = {
                showClearListDialog = false
                target?.let { list -> scope.launch { container.music.clearPlaylist(list.id) } }
            },
        )
    }

    // 删除所有列表 · 第一道确认
    if (showDeleteAllListsDialog) {
        ConfirmDialog(
            title = "删除所有列表",
            message = "将删除全部 ${playlists.size} 个列表及其中的歌曲引用。\n\n" +
                "手机上的歌曲文件和 .lrc 歌词文件不会被删除。\n\n" +
                "这是高危操作，点「继续」后还有一次最终确认。",
            confirmLabel = "继续",
            onDismiss = { showDeleteAllListsDialog = false },
            onConfirm = {
                showDeleteAllListsDialog = false
                showDeleteAllListsFinal = true
            },
        )
    }

    // 删除所有列表 · 第二道确认（到这里才真正执行）
    if (showDeleteAllListsFinal) {
        ConfirmDialog(
            title = "最终确认",
            message = "确定要删除全部 ${playlists.size} 个列表吗？\n\n" +
                "此操作不可撤销，删掉后列表需要重新建立。（手机上的歌曲文件不受影响）",
            confirmLabel = "确认全部删除",
            onDismiss = { showDeleteAllListsFinal = false },
            onConfirm = {
                showDeleteAllListsFinal = false
                scope.launch {
                    container.music.deleteAllPlaylists()
                    currentListId = null
                    queue = emptyList()
                    queueIndex = -1
                    playback.stop()
                    // MediaController 的调用是异步的：stop/clearMediaItems 还没生效时，
                    // 进度轮询可能已经把旧的时长读进状态了，而轮询在暂停后就停了，
                    // 不会再有下一次同步。所以这里显式归零，别让底部残留上一首的时长。
                    positionMs = 0L
                    durationMs = 0L
                    isPlaying = false
                }
            },
        )
    }

    // 重复歌曲：先问用户怎么处理，不要静默丢弃
    duplicatePrompt?.let { prompt ->
        if (prompt.fresh.isEmpty()) {
            ConfirmDialog(
                title = "无需重复导入",
                message = "所选的 ${prompt.duplicates.size} 首歌曲都已经在「${currentList?.name.orEmpty()}」里了。",
                confirmLabel = "知道了",
                onDismiss = { duplicatePrompt = null },
                onConfirm = { duplicatePrompt = null },
            )
        } else {
            ConfirmDialog(
                title = "有重复歌曲",
                message = "所选中 ${prompt.duplicates.size} 首已经在当前列表里，会被跳过；" +
                    "其余 ${prompt.fresh.size} 首将导入。",
                confirmLabel = "跳过并导入 ${prompt.fresh.size} 首",
                onDismiss = { duplicatePrompt = null },
                onConfirm = {
                    val fresh = prompt.fresh
                    val listId = prompt.listId
                    duplicatePrompt = null
                    doImport(listId, fresh)
                },
            )
        }
    }

    if (showPermissionPrompt) {
        // 每次回到前台重算一次（resumeTick 变化会走到这里），不只是读缓存的状态
        val audioOk = remember(resumeTick) { Permissions.hasAudio(context) }
        val allFilesOk = remember(resumeTick) { Permissions.hasAllFilesAccess() }

        // 两个都授齐了就自动关掉，不用用户再点一次；
        // 如果用户本来就是点「导入」进来的，顺手继续进导入页
        LaunchedEffect(audioOk, allFilesOk) {
            if (audioOk && allFilesOk) {
                showPermissionPrompt = false
                if (importIntent) {
                    importIntent = false
                    openImport()
                }
            }
        }

        PermissionPromptDialog(
            audioGranted = audioOk,
            allFilesGranted = allFilesOk,
            onRequest = {
                when {
                    !audioOk -> {
                        // 音频是运行时权限，系统弹窗直接问
                        audioPermissionLauncher.launch(Permissions.audioPermission())
                    }
                    !allFilesOk -> {
                        // "所有文件访问"没有运行时弹窗，只能跳到系统设置页让用户自己打开
                        toast(context, "找到「所有文件访问」，打开开关后返回即可")
                        if (!Permissions.openAllFilesSettings(context)) {
                    toast(context, "没找到系统设置页：请到 设置 → 应用管理 → A-Music → 权限 里手动打开")
                }
                    }
                    else -> showPermissionPrompt = false
                }
            },
            onDismiss = {
                // 点"稍后"就是不打算现在授权，取消掉待办的导入
                showPermissionPrompt = false
                importIntent = false
            },
        )
    }

    if (showSleepTimer) {
        SleepTimerDialog(
            remainingMs = sleepRemainingMs,
            onPick = { minutes ->
                showSleepTimer = false
                val endAt = System.currentTimeMillis() + minutes * 60_000L
                container.settings.setSleepTimerEndAt(endAt)
                sleepEndAt = endAt
                sleepRemainingMs = minutes * 60_000L
                toast(context, "已设置 $minutes 分钟后暂停播放")
            },
            onCancelTimer = {
                showSleepTimer = false
                container.settings.setSleepTimerEndAt(0L)
                sleepEndAt = 0L
                sleepRemainingMs = 0L
                toast(context, "已关闭睡眠定时器")
            },
            onDismiss = { showSleepTimer = false },
        )
    }

    // 列表内批量移除：只删引用，不动文件；确认一次即可（和"清空当前列表"的强度一致）
    pendingBatchRemove?.let { pending ->
        // listId 在**打开对话框那一刻**就定下来：确认时才读 currentListId 的话，
        // 对话框开着的时候如果播放把列表切走了，这一刀就砍到别的列表上了
        val songs = pending.songs
        val count = songs.size
        ConfirmDialog(
            title = "从列表移除 $count 首歌曲",
            message = if (count == 1) {
                "把「${songs.first().title}」从当前列表移除？只删除引用，不会删除手机上的歌曲文件。"
            } else {
                "把选中的 $count 首歌曲从当前列表移除？只删除引用，不会删除手机上的歌曲文件。\n\n" +
                    "其中：「${songs.take(3).joinToString("」「") { it.title }}" +
                    if (count > 3) "」等" else "」"
            },
            confirmLabel = "移除",
            onDismiss = { pendingBatchRemove = null },
            onConfirm = {
                val listId = pending.listId
                val ids = songs.map { it.id }
                pendingBatchRemove = null
                if (listId != null) {
                    scope.launch {
                        container.music.removeTracks(listId, ids)
                        toast(context, "已从列表移除 $count 首歌曲（文件未删除）")
                    }
                }
            },
        )
    }

    missingFileSong?.let { pending ->
        val song = pending.song
        ConfirmDialog(
            title = "文件不存在",
            message = "「${song.title}」的音频文件可能已被移动或删除。要从当前列表移除这条记录吗？",
            confirmLabel = "移除",
            onDismiss = { missingFileSong = null },
            onConfirm = {
                missingFileSong = null
                val listId = pending.listId
                if (listId != null) {
                    scope.launch { container.music.removeTrack(listId, song.id) }
                }
            },
        )
    }


    // 歌曲元数据：打开时读一次，只显示这首歌实际有的字段
    // 键里带上 resolvedLyrics.source：歌词是异步解析的，解析完这一行"歌词来源"要跟着刷新
    LaunchedEffect(showMetadata, currentSong?.id, resolvedLyrics.source) {
        val song = currentSong
        if (!showMetadata || song == null) return@LaunchedEffect
        metadataLoading = true
        metadataItems = container.metadata.read(song.path)
        // fileInfo 里是 isFile()/length()/lastModified() 这些磁盘调用，挪到 IO 线程，
        // 别在 LaunchedEffect（主线程）里直接做
        metadataFileInfo = withContext(Dispatchers.IO) {
            container.metadata.fileInfo(song.path)
        } + MetadataItem(
            "歌词来源",
            resolvedLyrics.source.label.ifBlank { "无" },
        )
        // 能不能改标签、当前值是什么（只有 FLAC / MP3 能改）
        metadataEditable = withContext(Dispatchers.IO) {
            if (TagWriter.supported(song.path)) TagWriter.read(song.path) else null
        }
        metadataLoading = false
    }

    if (showMetadata) {
        MetadataDialog(
            songTitle = currentSong?.title.orEmpty(),
            loading = metadataLoading,
            metadata = metadataItems,
            fileInfo = metadataFileInfo,
            editable = metadataEditable,
            onSaveField = { key, value ->
                // 原地编辑：改完一个字段就写一次（点别处自动保存）。
                // 返回 false = 这次编辑没收下，弹窗会把输入框和内容**留着**等下次失焦再存 ——
                // 不能静默 return：MP3 标签变大时要复制整个音频文件（好几秒），
                // 这段时间里用户改的下一个字段会被无声丢掉，看起来就是"我改了它又变回去了"。
                val song = currentSong
                if (song == null || metadataSaving) {
                    false
                } else {
                    metadataSaving = true
                    scope.launch {
                        try {
                        // 正在放的就是这首歌时先暂停：写文件时播放器还开着这个文件，
                        // 就地改写可能让它读到半截（MP3 那条是临时文件+替换，但暂停更稳）
                        val resumeAfter = isPlaying
                        if (resumeAfter) playback.pause()
                        val error = withContext(Dispatchers.IO) {
                            // 一个字段一个字段地写：null＝删掉这个字段
                            TagWriter.write(song.path, mapOf(key to value))
                        }
                        if (error == null) {
                            // 数据库里那几列也同步一下：列表副标题（歌手）等处会立刻跟着变
                            val merged = metadataEditable.orEmpty() + mapOf(key to value.orEmpty())
                            container.music.updateTagFields(
                                id = song.id,
                                title = merged["title"].orEmpty(),
                                artist = merged["artist"].orEmpty(),
                                album = merged["album"].orEmpty(),
                                year = merged["year"].orEmpty(),
                                genre = merged["genre"].orEmpty(),
                            )
                            // 重新读一遍，弹窗里显示的信息立即是新值
                            metadataItems = container.metadata.read(song.path)
                            metadataEditable = withContext(Dispatchers.IO) { TagWriter.read(song.path) }
                        } else {
                            toast(context, error)
                        }
                        if (resumeAfter) playback.togglePlayPause()
                        } finally {
                            // 必须 finally：中途任何异常都不能让标志卡在 true，
                            // 否则之后所有标签保存都会被上面的判断拒绝
                            metadataSaving = false
                        }
                    }
                    true
                }
            },
            onDismiss = { showMetadata = false },
        )
    }

    // 播放模式上拉框：列出全部模式，点哪个就生效哪个
    if (showPlayModeSheet) {
        PlayModeSheet(
            current = playMode,
            onSelect = { mode ->
                playMode = mode
                // 倒序模式下队列顺序和列表相反，要重建；随机由播放器原生处理
                rebuildQueue(keepCurrent = true)
                applyPlayModeToPlayer()
                // 就地替换队列并保持播放进度与播放状态（不重新从头播）
                if (playback.hasMedia && queueIndex in queue.indices) {
                    playback.replaceQueue(
                        items = queue.toQueueItems(),
                        startIndex = queueIndex,
                        positionMs = positionMs,
                        keepPlaying = isPlaying,
                    )
                }
                showPlayModeSheet = false
            },
            onDismiss = { showPlayModeSheet = false },
        )
    }

    // 歌词文字大小：拖动即时生效并保存
    if (showFontDialog) {
        LyricsFontDialog(
            level = lyricsFontLevel,
            onLevelChange = { newLevel ->
                lyricsFontLevel = newLevel
                container.settings.setLyricsFontLevel(newLevel)
            },
            onDismiss = { showFontDialog = false },
        )
    }

    // 列表自动居中的延迟：确认后立即生效并保存，下一次自动居中就用新的秒数
    if (showListAutoCenterDialog) {
        ListAutoCenterDialog(
            seconds = listAutoCenterSeconds,
            onConfirm = { seconds ->
                listAutoCenterSeconds = seconds
                container.settings.setListAutoCenterSeconds(seconds)
                showListAutoCenterDialog = false
            },
            onDismiss = { showListAutoCenterDialog = false },
        )
    }

    // 搜索弹窗：优先已导入的歌曲，再扫手机里的音频文件
    if (showSearch) {
        SearchDialog(
            playlists = playlists,
            mediaIndex = mediaIndex,
            hasAllFilesAccess = hasAllFilesAccess,
            onRequestAllFilesAccess = {
                toast(context, "请在系统设置里打开「所有文件访问」，回来后会自动刷新")
                if (!Permissions.openAllFilesSettings(context)) {
                    toast(context, "没找到系统设置页：请到 设置 → 应用管理 → A-Music → 权限 里手动打开")
                }
            },
            onPlayImported = { song ->
                val owner = playlists.firstOrNull { list -> list.songs.any { it.id == song.id } }
                if (owner != null) {
                    val songs = orderedSongs(owner.songs)
                    queue = songs
                    currentListId = owner.id
                    playAt(songs.indexOfFirst { it.id == song.id })
                    scope.launch { pagerState.animateScrollToPage(1) }
                }
                showSearch = false
            },
            onImportFile = { audio ->
                val listId = currentListId
                if (listId == null) {
                    toast(context, "请先选择一个列表，再导入歌曲")
                } else {
                    val existing = currentList?.songs?.map { it.path }?.toHashSet().orEmpty()
                    if (audio.path in existing) {
                        // 单首手动导入，重复时直接说清楚，不再弹一层确认
                        toast(context, "《${audio.displayName}》已经在这个列表里了")
                    } else {
                        scope.launch {
                            val added = container.music.importAudios(listId, listOf(audio))
                            toast(context, "已导入 $added 首")
                        }
                    }
                }
            },
            onDismiss = { showSearch = false },
        )
    }

    if (showOnlineLyrics) {
        val song = currentSong
        OnlineLyricsDialog(
            // 播放区域有歌名就用它搜（不管在不在播）；没有就让用户自己输
            songTitle = song?.let { File(it.path).name.substringBeforeLast('.') }.orEmpty(),
            localDurationMs = song?.durationMs ?: 0L,
            canSave = song != null,
            embeddedSupported = embeddedSupportedState,
            onSave = { text, alsoEmbedded ->
                showOnlineLyrics = false
                if (song == null) {
                    toast(context, "没有正在播放的歌曲，无法保存歌词")
                } else if (!Permissions.hasAllFilesAccess()) {
                    // 写 .lrc 需要"所有文件访问"，就地弹权限说明
                    showPermissionPrompt = true
                } else {
                    scope.launch {
                        val result = container.lyrics.saveLyrics(song.path, text, alsoEmbedded)
                        toast(context, result.message)
                        if (result.success) {
                            container.music.findTrack(song.id)?.let { track ->
                                resolvedLyrics = container.lyrics.resolve(track)
                            }
                        }
                    }
                }
            },
            onDismiss = { showOnlineLyrics = false },
        )
    }

    if (showLyricsEditor) {
        val song = currentSong
        LyricsEditorDialog(
            initialText = lyricsEditorText,
            willCreateLrc = lyricsEditorCreatesLrc,
            onDismiss = { showLyricsEditor = false },
            onSave = { text ->
                showLyricsEditor = false
                if (song == null) {
                    toast(context, "没有正在播放的歌曲，无法保存歌词")
                } else if (!Permissions.hasAllFilesAccess()) {
                    // 保存 .lrc 需要"所有文件访问"，就地弹权限说明
                    showPermissionPrompt = true
                } else {
                    scope.launch {
                        val result = container.lyrics.saveLyricsText(song.path, text)
                        toast(context, result.message)
                        if (result.success) {
                            // 保存完立刻重新解析，马上能看到效果
                            container.music.findTrack(song.id)?.let { track ->
                                resolvedLyrics = container.lyrics.resolve(track)
                            }
                        }
                    }
                }
            },
        )
    }

    if (showOffsetDialog && hasLyrics) {
        // hasLyrics 已经保证有正在播放的歌，这里不用再判空
        val song = currentSong
        LyricsOffsetDialog(
            offsetMs = lyricsOffsetMs,
            onDismiss = { showOffsetDialog = false },
            onEditLyrics = {
                showOffsetDialog = false
                scope.launch {
                    // 编辑器的初值：有同名 .lrc 就用它的原文，否则用内嵌歌词的原文
                    val draft = container.lyrics.draftTextFor(song.path)
                    lyricsEditorText = draft.text
                    lyricsEditorCreatesLrc = draft.fromEmbedded
                    showLyricsEditor = true
                }
            },
            onApply = { newOffset ->
                // 写 .lrc / 改内嵌标签都要"所有文件访问"；缺了就地弹权限说明，
                // 别等写完再看"写入失败，请检查存储权限"
                if (!Permissions.hasAllFilesAccess()) {
                    showOffsetDialog = false
                    showPermissionPrompt = true
                } else {
                lyricsOffsetMs = newOffset
                scope.launch {
                    // 同名 .lrc 和文件里的内嵌歌词都改（有哪个改哪个，两个都有就都改），
                    // 这样在任何播放器里播这首歌时间轴都是对的
                    val result = container.lyrics.shiftAllLyrics(song.path, newOffset)
                    toast(context, result.message)
                    if (result.success) {
                        // 已经写进文件，应用内偏移归零，避免下次播放再叠加一遍
                        container.music.saveLyricOffset(song.id, 0L)
                        lyricsOffsetMs = 0L
                        // 重新读一遍歌词，马上就能看出改得对不对
                        container.music.findTrack(song.id)?.let { track ->
                            resolvedLyrics = container.lyrics.resolve(track)
                        }
                    }
                }
                }
            },
        )
    }
}

/** 导入时发现的重复歌曲，等用户决定怎么处理 */
/** 批量移除请求：打开对话框时就把"哪个列表"记下来 */
private data class BatchRemoveRequest(
    val listId: Long?,
    val songs: List<Song>,
)

/** 文件不存在要移除的请求：同样带 listId */
private data class MissingFileRequest(
    val listId: Long?,
    val song: Song,
)

private data class DuplicatePrompt(
    /** 新增的 */
    val fresh: List<DeviceAudio>,
    /** 已在该列表里的 */
    val duplicates: List<DeviceAudio>,
    val listId: Long,
)

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** 音量按钮一格走多少 */
private const val VolumeStepPercent = 5

/**
 * 音量 ±5%，返回新的音量百分比。
 *
 * ```
 * 用哪一层   系统媒体音量（AudioManager.STREAM_MUSIC），不用 ExoPlayer 自己的 setVolume ——
 *           这样硬件音量键、通知栏音量条、这里的 +/- 是同一层，
 *           不会出现"系统音量已满但 App 声音很小"。
 * 5% 怎么做到  这台机器 STREAM_MUSIC 的索引范围是 0..150（`dumpsys audio` 的 `Max: 150`），
 *           而音量键一格 = 10（正好 6.7%，比 5% 粗）。
 *           5% = 7.5 个索引单位，不是 10 的整数倍也能设进去（实测设 53/68/83… 系统都照收），
 *           所以界面上的百分比按 5% 的格走、系统档位跟着换算，
 *           既保住了"5% 一格"，又不需要再叠一层播放器音量。
 * 和系统同步  硬件音量键/别的应用改了音量时，真实档位会和界面上的数对不上 ——
 *           差超过一格就按真实档位重新对齐（对齐到 5% 的格），不会越点越偏。
 * ```
 *
 *
 * ## 关于 MIUI 那个蓝色音量面板（实测结论：利用不了）
 *
 * 小米/澎湃自己的音量面板（左上角那块蓝色圆形图标）**拦不住**：播放音乐时只要系统媒体音量
 * 变了它就画出来 —— `adjustStreamVolume(flags = 0)`、`setStreamVolume`、物理音量键，
 * 全都一样；`settings put system volume_music_*` 根本不生效；也没有对应的系统开关。
 *
 * 有一个看似可行的猜想："先用物理音量键调一次，之后点软件里的按钮就不再弹了"。
 * 为此做过时序实验（每次先抓干净基线帧，触发后逐帧比对左上角区域的差异像素）：
 *
 * ```
 * 间隔 6s / 1.5s / 1.5s / 8s / 20s 点应用内音量按钮   → 五次全都弹面板
 * 物理键后再隔 1.5s / 8s 点应用内音量按钮              → 两次也都弹
 * ```
 * ⇒ **没有可用的"抑制窗口"**：唯一看似成立的情况是"面板还开着（约 1 秒的显示期内），
 * 新变化只是更新它、不会重新弹"，间隔一过照样弹。所以：
 *
 * - 先用 `ADJUST_SAME + FLAG_SHOW_UI` "打底"再改音量 —— 一样弹，纯属多余；
 * - 只要动系统媒体音量，面板一定会出现，**应用层没有办法减少它**。
 *
 * 想要"彻底不弹"，只有一条路：**不碰系统音量**，改成只调播放器自身音量
 * （代价：与系统音量、硬件音量键脱钩，会出现"软件调到最大但系统音量小、声音依然小"）。
 */
private fun adjustVolume(context: Context, up: Boolean, lastPercent: Int): Int {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return lastPercent.coerceAtLeast(0)
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val real = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max
    val base = if (lastPercent < 0 || kotlin.math.abs(real - lastPercent) > VolumeStepPercent) {
        // 第一次点、或音量被别处改过：按真实档位对齐到最近的 5%
        ((real / VolumeStepPercent).roundToInt() * VolumeStepPercent).coerceIn(0, 100)
    } else {
        lastPercent
    }
    val next = (base + if (up) VolumeStepPercent else -VolumeStepPercent).coerceIn(0, 100)
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        (next * max / 100f).roundToInt(),
        0,
    )
    return next
}
