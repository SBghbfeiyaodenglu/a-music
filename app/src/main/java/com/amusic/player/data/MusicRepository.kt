package com.amusic.player.data

import com.amusic.player.data.db.LyricOffsetEntity
import com.amusic.player.data.db.MusicDao
import com.amusic.player.data.db.PlayerStateEntity
import com.amusic.player.data.db.PlaylistEntity
import com.amusic.player.data.db.TrackEntity
import com.amusic.player.data.media.AudioMetadataSource
import com.amusic.player.data.media.BasicTags
import com.amusic.player.data.media.DeviceAudio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 列表与歌曲的读写，架在 Room 之上的一层薄封装。
 * 只做"把数据库三张表拼成界面要的领域模型"这件事，不引入更多抽象。
 */
class MusicRepository(
    private val dao: MusicDao,
    private val metadata: AudioMetadataSource,
) {

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
            )
        }
    }

    suspend fun createPlaylist(name: String): Long =
        dao.insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))

    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, name)

    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)

    /** 清空当前列表的歌曲：只删引用，不删文件 */
    suspend fun clearPlaylist(id: Long) = dao.clearPlaylist(id)

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

    suspend fun removeTrack(playlistId: Long, trackId: Long) =
        dao.removeFromPlaylist(playlistId, trackId)

    /** 批量移除引用（列表内多选移除）。同样只删引用，不动音频文件和歌词文件。 */
    suspend fun removeTracks(playlistId: Long, trackIds: List<Long>) =
        dao.removeFromPlaylist(playlistId, trackIds)

    suspend fun findTrack(trackId: Long): TrackEntity? = dao.findTrack(trackId)

    suspend fun loadPlayerState(): PlayerStateEntity? = dao.loadPlayerState()

    /** 某个列表里的歌，按列表顺序。系统"媒体恢复"用它还原上次的播放队列。 */
    suspend fun tracksOfPlaylist(playlistId: Long): List<TrackEntity> =
        dao.tracksOfPlaylist(playlistId)

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
