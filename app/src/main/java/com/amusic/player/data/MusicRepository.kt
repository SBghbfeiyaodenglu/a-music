package com.amusic.player.data

import com.amusic.player.data.db.LyricOffsetEntity
import com.amusic.player.data.db.MusicDao
import com.amusic.player.data.db.PlayerStateEntity
import com.amusic.player.data.db.PlaylistEntity
import com.amusic.player.data.db.TrackEntity
import com.amusic.player.data.media.AudioMetadataSource
import com.amusic.player.data.media.BasicTags
import com.amusic.player.data.media.DeviceAudio
import com.amusic.player.data.media.FileBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 一次列表「跟随文件夹」同步的结果 */
data class FolderSyncResult(
    val playlistId: Long,
    val playlistName: String,
    val added: Int,
    val changed: Int,
    val removed: Int,
    val sourceFolders: Int,
)

/**
 * 列表与歌曲的读写，架在 Room 之上的一层薄封装。
 * 只做"把数据库三张表拼成界面要的领域模型"这件事，不引入更多抽象。
 */
class MusicRepository(
    private val dao: MusicDao,
    private val metadata: AudioMetadataSource,
) {

    /** 同一个时间只跑一个文件夹同步，避免用户连续触发时两次扫描互相打架 */
    private val folderSyncMutex = Mutex()

    /**
     * @param titleFromMetadata true = 歌名用标签标题，false = 用文件名（默认）
     */
    fun observePlaylists(titleFromMetadata: Boolean): Flow<List<Playlist>> = combine(
        dao.observePlaylists(),
        dao.observeTracks(),
        dao.observePlaylistTracks(),
    ) { playlists, tracks, links ->
        val trackById = tracks.associateBy { it.id }
        val linksByPlaylist = links.groupBy { it.playlistId }
        playlists.map { playlist ->
            Playlist(
                id = playlist.id,
                name = playlist.name,
                songs = linksByPlaylist[playlist.id].orEmpty()
                    .sortedBy { it.position }
                    .mapNotNull { link -> trackById[link.trackId] }
                    .map { it.toSong(titleFromMetadata) },
                sourceFolders = playlist.sourceFolders
                    .split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            )
        }
    }

    suspend fun createPlaylist(name: String): Long =
        dao.insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))

    /**
     * 追加这个列表要自动跟随的源文件夹（新导入时传勾选的文件夹）。
     * 在仓库里读-改-写而不是由界面传合并结果：界面手上的列表可能是旧的，
     * 和同步推断同时发生时会把刚记下的文件夹覆盖掉。
     */
    suspend fun addSourceFolders(id: Long, folders: List<String>) {
        val normalized = folders.map { FolderSync.normalize(it) }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) return
        folderSyncMutex.withLock {
            val playlist = dao.findPlaylist(id) ?: return@withLock
            val merged = (parsePaths(playlist.sourceFolders) + normalized).distinct()
            dao.updateSourceFolders(id, merged.joinToString("\n"))
        }
    }

    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, name)

    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)

    /** 清空当前列表的歌曲：只删引用，不删文件 */
    suspend fun clearPlaylist(id: Long) {
        // 清空也是一次"用户明确不想要这些歌"的决定：把它们记进排除表，
        // 否则列表跟随文件夹，下一轮同步又全部加回来了
        val paths = dao.tracksOfPlaylist(id).map { it.path }
        excludeFromAutoSync(id, paths)
        dao.clearPlaylist(id)
    }

    /** 删除所有列表：危险操作，界面负责二次确认。同样只删记录，不删文件 */
    suspend fun deleteAllPlaylists() = dao.deleteAllPlaylists()

    /**
     * 返回真正新增的条数（已在本列表里的歌曲会被跳过）。
     *
     * 导入前用标签补一遍元数据：目录浏览导入的文件可能没被媒体库索引，
     * 专辑、年份、风格这些字段 MediaStore 给不出来，只能用标签补。
     * 媒体库已有的值优先，标签只填空缺。
     */
    suspend fun importAudios(playlistId: Long, audios: List<DeviceAudio>): Int {
        val enriched = audios.map { audio ->
            val tags = runCatching { metadata.readBasic(audio.path) }.getOrDefault(BasicTags())
            audio.copy(
                title = audio.title.ifBlank { tags.title },
                artist = audio.artist.ifBlank { tags.artist },
                album = audio.album.ifBlank { tags.album },
                year = audio.year.ifBlank { tags.year },
                genre = audio.genre.ifBlank { tags.genre },
                // 媒体库没索引过的文件，DeviceAudio.durationMs 是 0；用标签里的时长补上，
                // 否则列表/进度条初始时长会显示 0:00（平板实测发现）。
                durationMs = audio.durationMs.takeIf { it > 0L } ?: tags.durationMs,
            ).toTrackEntity()
        }
        return dao.importTracks(playlistId, enriched)
    }

    /** 只更新标签字段（改元数据后同步数据库用；不动时长/体积/修改时间） */
    suspend fun updateTagFields(
        id: Long,
        title: String,
        artist: String,
        album: String,
        year: String,
        genre: String,
    ) = dao.updateTagFields(id, title, artist, album, year, genre)

    suspend fun removeTrack(playlistId: Long, trackId: Long) {
        // 手动移除的歌记进排除表：文件还在源文件夹里，但用户就是不想要它，
        // 不能被自动同步在下一轮又捡回来
        val path = dao.findTrack(trackId)?.path
        if (path != null) excludeFromAutoSync(playlistId, listOf(path))
        dao.removeFromPlaylist(playlistId, trackId)
    }

    /** 批量移除引用（列表内多选移除）。同样只删引用，不动音频文件和歌词文件。 */
    suspend fun removeTracks(playlistId: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        val idSet = trackIds.toSet()
        val paths = dao.tracksOfPlaylist(playlistId)
            .filter { it.id in idSet }
            .map { it.path }
        excludeFromAutoSync(playlistId, paths)
        dao.removeFromPlaylist(playlistId, trackIds)
    }

    suspend fun findTrack(trackId: Long): TrackEntity? = dao.findTrack(trackId)

    /** 按文件路径找曲目（搜索里导入单个文件后，用它拿到新曲目 id 立即播放） */
    suspend fun findTrackByPath(path: String): TrackEntity? = dao.findTrackByPath(path)

    suspend fun loadPlayerState(): PlayerStateEntity? = dao.loadPlayerState()

    /** 某个列表里的歌，按列表顺序。系统"媒体恢复"用它还原上次的播放队列。 */
    suspend fun tracksOfPlaylist(playlistId: Long): List<TrackEntity> =
        dao.tracksOfPlaylist(playlistId)

    /** 某个列表里的歌，按列表顺序、转成界面模型（歌名来源跟设置一致）。 */
    suspend fun songsOfPlaylist(playlistId: Long, titleFromMetadata: Boolean): List<Song> =
        dao.tracksOfPlaylist(playlistId).map { it.toSong(titleFromMetadata) }

    /**
     * 让列表跟随它的源文件夹：
     *   - 新增音频 → 追加进列表；
     *   - 文件体积/修改时间变了 → 更新元数据；
     *   - 文件在源文件夹下但已不存在 → 移除引用（只删引用，不动文件）。
     *
     * 安全规则：
     *   - 源文件夹暂时不可访问（没挂载/没权限）时什么都不删；
     *   - 不在任何源文件夹下的歌（手动单曲导入）永远不动；
     *   - 刚写入不到 1.5 秒的文件先跳过，避免读到半个文件。
     */
    suspend fun syncPlaylistFolder(playlistId: Long): FolderSyncResult = withContext(Dispatchers.IO) {
        folderSyncMutex.withLock {
            val playlist = dao.findPlaylist(playlistId)
                ?: return@withLock FolderSyncResult(playlistId, "", 0, 0, 0, 0)
            val tracks = dao.tracksOfPlaylist(playlistId)
            var roots = parsePaths(playlist.sourceFolders)
            if (roots.isEmpty()) {
                // 老列表：第一次同步时按"所有歌共同的父目录"推断
                roots = inferRoots(tracks.map { it.path })
                if (roots.isNotEmpty()) dao.updateSourceFolders(playlistId, roots.joinToString("\n"))
            }
            if (roots.isEmpty()) {
                return@withLock FolderSyncResult(playlistId, playlist.name, 0, 0, 0, 0)
            }

            // 只信"目录存在 + 能读 + 列得动"的源文件夹；不可访问时绝不按"空目录"删歌
            val accessibleRoots = roots.filter { root ->
                val dir = File(root)
                dir.isDirectory && dir.canRead() &&
                    runCatching { dir.listFiles() }.getOrNull() != null
            }
            if (accessibleRoots.isEmpty()) {
                return@withLock FolderSyncResult(playlistId, playlist.name, 0, 0, 0, roots.size)
            }

            val now = System.currentTimeMillis()
            // 每个根目录单独扫：只要有一个目录撞上了扫描上限，这一轮就**不做删除**
            // （上限意味着结果被截断，没扫到不等于文件没了），只做新增/更新。
            val scans = accessibleRoots.map { root -> FileBrowser.audioFilesRecursive(File(root)) }
            val scanTruncated = scans.any { it.size >= FileBrowser.MAX_BATCH_FILES }
            val scannedFiles = scans
                .flatten()
                .distinctBy { it.absolutePath }
                .filter { file -> now - file.lastModified() > 1500L }
            // 同一首歌可能因为 /sdcard 与 /storage/emulated/0 两种写法在列表里
            // 存成两条记录（搜索里会出现两个同名结果）。这里按归一化路径去重：
            // 保留与当前扫描路径写法一致的那条，删掉另一条链接（不动文件、不动别的列表）。
            val scannedRawPaths = scannedFiles.map { it.absolutePath }.toSet()
            val deduped = dedupeTracksByPath(tracks, scannedRawPaths)
            if (deduped.removedIds.isNotEmpty()) {
                dao.removeFromPlaylist(playlistId, deduped.removedIds)
            }
            val activeTracks = deduped.tracks
            val scanned = scannedFiles.map {
                FolderSyncFile(it.absolutePath, it.length(), it.lastModified())
            }
            val existing = activeTracks.map {
                FolderSyncExisting(it.id, it.path, it.sizeBytes, it.lastModified)
            }
            val diff = FolderSync.diff(existing, scanned, accessibleRoots.toSet())
            // 用户手动删掉的歌不自动加回来
            val excluded = parsePaths(playlist.excludedPaths).map { FolderSync.normalize(it) }.toSet()
            val addedPaths = diff.addedPaths.filterNot { it in excluded }
            val removedIds = if (scanTruncated) emptyList() else diff.removedTrackIds

            if (addedPaths.isNotEmpty() || diff.changedPaths.isNotEmpty()) {
                val byPath = scannedFiles.associateBy { FolderSync.normalize(it.absolutePath) }
                val toImport = (addedPaths + diff.changedPaths)
                    .mapNotNull { byPath[it] }
                    .map { FileBrowser.toDeviceAudio(it, meta = null) }
                if (toImport.isNotEmpty()) importAudios(playlistId, toImport)
            }
            if (removedIds.isNotEmpty()) {
                dao.removeFromPlaylist(playlistId, removedIds)
            }
            FolderSyncResult(
                playlistId = playlistId,
                playlistName = playlist.name,
                added = addedPaths.size,
                changed = diff.changedPaths.size,
                removed = deduped.removedIds.size + removedIds.size,
                sourceFolders = roots.size,
            )
        }
    }

    private data class DedupedTracks(
        val tracks: List<TrackEntity>,
        val removedIds: List<Long>,
    )

    /**
     * 按 [FolderSync.normalize] 后的路径去重。同一路径有多条记录时，
     * 优先保留与当前扫描原始路径写法一致的那条（说明它指向现在的文件），
     * 否则保留先出现的（老记录）。只返回要删的 trackId，不删文件。
     */
    private fun dedupeTracksByPath(
        tracks: List<TrackEntity>,
        scannedRawPaths: Set<String>,
    ): DedupedTracks {
        val kept = LinkedHashMap<String, TrackEntity>()
        val removed = mutableListOf<Long>()
        for (track in tracks) {
            val key = FolderSync.normalize(track.path)
            val previous = kept[key]
            if (previous == null) {
                kept[key] = track
                continue
            }
            val keepCurrent = track.path in scannedRawPaths && previous.path !in scannedRawPaths
            if (keepCurrent) {
                removed += previous.id
                kept[key] = track
            } else {
                removed += track.id
            }
        }
        return DedupedTracks(kept.values.toList(), removed)
    }

    private fun parsePaths(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 推断老列表的源文件夹。只认一种情况：列表里的歌和某个目录里的音频**完全一致**
     * （不多不少）。这样才不会把"从大文件夹里精挑出来的歌单"绑到整个文件夹上，
     * 一升级就凭空多出一堆没导过的歌；搜索自动建的「临时列表」也只有那一首，
     * 不会因此把整个目录都吸进来。
     */
    private fun inferRoots(paths: List<String>): List<String> {
        val inferred = FolderSync.inferRoots(paths).firstOrNull() ?: return emptyList()
        val storageRoot = FolderSync.normalize(FileBrowser.storageRoot().absolutePath)
        if (FolderSync.normalize(inferred) == storageRoot) return emptyList()
        val inFolder = FileBrowser.audioFilesIn(File(inferred))
            .map { FolderSync.normalize(it.absolutePath) }
            .toSet()
        val inList = paths.map { FolderSync.normalize(it) }.toSet()
        return if (inFolder == inList) listOf(inferred) else emptyList()
    }

    /**
     * 把用户手动删掉的歌记进排除表。只有路径确实在源文件夹下才记 ——
     * 单曲导入的歌本来就不参与文件夹同步，记了也没用。
     */
    private suspend fun excludeFromAutoSync(playlistId: Long, paths: List<String>) {
        if (paths.isEmpty()) return
        folderSyncMutex.withLock {
            val playlist = dao.findPlaylist(playlistId) ?: return@withLock
            var roots = parsePaths(playlist.sourceFolders)
            if (roots.isEmpty()) {
                roots = inferRoots(dao.tracksOfPlaylist(playlistId).map { it.path })
            }
            if (roots.isEmpty()) return@withLock
            val under = paths.map { FolderSync.normalize(it) }
                .filter { path -> roots.any { FolderSync.isUnder(path, it) } }
            if (under.isEmpty()) return@withLock
            val merged = (parsePaths(playlist.excludedPaths) + under).distinct().joinToString("\n")
            dao.updateExcludedPaths(playlistId, merged)
        }
    }

    suspend fun savePlayerState(
        playlistId: Long?,
        trackId: Long?,
        positionMs: Long,
        playMode: PlayMode,
    ) = dao.savePlayerState(
        PlayerStateEntity(
            id = 0,
            currentPlaylistId = playlistId,
            currentTrackId = trackId,
            positionMs = positionMs,
            playMode = playMode.name,
        ),
    )

    suspend fun loadLyricOffset(trackId: Long): Long = dao.loadLyricOffset(trackId)?.offsetMs ?: 0L

    suspend fun saveLyricOffset(trackId: Long, offsetMs: Long) =
        dao.saveLyricOffset(LyricOffsetEntity(trackId, offsetMs))
}

internal fun TrackEntity.toSong(titleFromMetadata: Boolean): Song = Song(
    id = id,
    // 默认用文件名（去掉扩展名）；设置里可以切成用标签标题
    title = if (titleFromMetadata && title.isNotBlank()) {
        title
    } else {
        displayName.substringBeforeLast('.', displayName)
    },
    artist = artist,
    path = path,
    durationMs = durationMs,
    album = album,
    year = year,
    genre = genre,
)

internal fun DeviceAudio.toTrackEntity(): TrackEntity = TrackEntity(
    path = path,
    displayName = displayName,
    title = title,
    artist = artist,
    album = album,
    year = year,
    genre = genre,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
)
