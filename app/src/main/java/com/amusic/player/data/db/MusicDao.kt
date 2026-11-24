package com.amusic.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MusicDao {

    // ---------------- 观察用（界面订阅） ----------------

    @Query("SELECT * FROM playlist ORDER BY createdAt ASC, id ASC")
    abstract fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM track")
    abstract fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM playlist_track ORDER BY position ASC")
    abstract fun observePlaylistTracks(): Flow<List<PlaylistTrackEntity>>

    // ---------------- 列表 ----------------

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    abstract suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    abstract suspend fun deletePlaylistRecord(id: Long)

    @Query("DELETE FROM playlist_track WHERE playlistId = :playlistId")
    abstract suspend fun deleteLinksOfPlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_track")
    abstract suspend fun deleteAllLinks()

    @Query("DELETE FROM playlist")
    abstract suspend fun deleteAllPlaylistRecords()

    // ---------------- 歌曲 ----------------

    @Query("SELECT * FROM track WHERE path = :path LIMIT 1")
    abstract suspend fun findTrackByPath(path: String): TrackEntity?

    @Query("SELECT * FROM track WHERE id = :id LIMIT 1")
    abstract suspend fun findTrack(id: Long): TrackEntity?

    @Query("SELECT * FROM track")
    abstract suspend fun allTracksOnce(): List<TrackEntity>

    @Insert
    abstract suspend fun insertTrack(track: TrackEntity): Long

    @Query(
        """
        UPDATE track SET displayName = :displayName, title = :title, artist = :artist,
                         album = :album, year = :year, genre = :genre,
                         durationMs = :durationMs,
                         sizeBytes = :sizeBytes, lastModified = :lastModified
        WHERE id = :id
        """,
    )
    abstract suspend fun updateTrackMeta(
        id: Long,
        displayName: String,
        title: String,
        artist: String,
        album: String,
        year: String,
        genre: String,
        durationMs: Long,
        sizeBytes: Long,
        lastModified: Long,
    )

    /** 只想改标签字段时用它（不动时长/体积/修改时间这些"文件事实"） */
    @Query(
        """
        UPDATE track SET title = :title, artist = :artist, album = :album,
                         year = :year, genre = :genre
        WHERE id = :id
        """,
    )
    abstract suspend fun updateTagFields(
        id: Long,
        title: String,
        artist: String,
        album: String,
        year: String,
        genre: String,
    )

    // ---------------- 列表 ↔ 歌曲 ----------------

    @Query("SELECT MAX(position) FROM playlist_track WHERE playlistId = :playlistId")
    abstract suspend fun maxPosition(playlistId: Long): Int?

    @Query("SELECT * FROM playlist_track WHERE playlistId = :playlistId ORDER BY position ASC")
    abstract suspend fun linksOfPlaylist(playlistId: Long): List<PlaylistTrackEntity>

    /**
     * 一次查出某个列表里的歌（已按列表顺序排好）。
     * 给系统"媒体恢复"用：进程被系统回收后，服务要能立刻把上次的队列还原出来。
     */
    @Query(
        """
        SELECT track.* FROM track
        INNER JOIN playlist_track ON playlist_track.trackId = track.id
        WHERE playlist_track.playlistId = :playlistId
        ORDER BY playlist_track.position ASC
        """,
    )
    abstract suspend fun tracksOfPlaylist(playlistId: Long): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertLink(link: PlaylistTrackEntity): Long

    @Query("UPDATE playlist_track SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    abstract suspend fun updateLinkPosition(playlistId: Long, trackId: Long, position: Int)

    @Query("DELETE FROM playlist_track WHERE playlistId = :playlistId AND trackId = :trackId")
    abstract suspend fun deleteLink(playlistId: Long, trackId: Long)

    /** 批量移除引用（列表内多选移除用）：一次删掉，不逐条往返 */
    @Query("DELETE FROM playlist_track WHERE playlistId = :playlistId AND trackId IN (:trackIds)")
    abstract suspend fun deleteLinks(playlistId: Long, trackIds: List<Long>)

    @Query("SELECT COUNT(*) FROM playlist_track WHERE trackId = :trackId")
    abstract suspend fun linkCountOfTrack(trackId: Long): Int

    // ---------------- 播放状态 ----------------

    @Query("SELECT * FROM player_state WHERE id = 0")
    abstract suspend fun loadPlayerState(): PlayerStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun savePlayerState(state: PlayerStateEntity)

    // ---------------- 歌词偏移 ----------------

    @Query("SELECT * FROM lyric_offset WHERE trackId = :trackId")
    abstract suspend fun loadLyricOffset(trackId: Long): LyricOffsetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveLyricOffset(offset: LyricOffsetEntity)

    // ---------------- 组合操作 ----------------

    /**
     * 批量导入。顺序即导入顺序，重复歌曲（同一路径）直接跳过，不产生重复条目。
     * 返回真正新增的条数。
     */
    @Transaction
    open suspend fun importTracks(playlistId: Long, tracks: List<TrackEntity>): Int {
        var position = (maxPosition(playlistId) ?: -1) + 1
        var added = 0
        for (incoming in tracks) {
            val existing = findTrackByPath(incoming.path)
            val trackId = if (existing == null) {
                insertTrack(incoming)
            } else {
                // 同一个文件再次导入时顺便刷新元数据（文件可能换过标签）
                updateTrackMeta(
                    id = existing.id,
                    displayName = incoming.displayName,
                    title = incoming.title,
                    artist = incoming.artist,
                    album = incoming.album,
                    year = incoming.year,
                    genre = incoming.genre,
                    durationMs = incoming.durationMs,
                    sizeBytes = incoming.sizeBytes,
                    lastModified = incoming.lastModified,
                )
                existing.id
            }
            val rowId = insertLink(PlaylistTrackEntity(playlistId, trackId, position))
            if (rowId != -1L) added++
            position++
        }
        renumber(playlistId)
        return added
    }

    /** 从列表移除歌曲引用（绝不删除音频文件），然后把序号重新连续编号。 */
    @Transaction
    open suspend fun removeFromPlaylist(playlistId: Long, trackId: Long) {
        deleteLink(playlistId, trackId)
        renumber(playlistId)
    }

    /**
     * 从列表批量移除歌曲引用（列表内多选移除）。
     * 和单条移除一样只删引用，绝不删除音频文件；删完同样重新编号。
     */
    @Transaction
    open suspend fun removeFromPlaylist(playlistId: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        deleteLinks(playlistId, trackIds)
        renumber(playlistId)
    }

    /** 删除列表：只删列表记录和它的歌曲关联，不删除任何歌曲文件。 */
    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        deleteLinksOfPlaylist(id)
        deletePlaylistRecord(id)
    }

    /** 清空列表里的歌曲：只删引用，不删列表本身，也不删任何文件。 */
    @Transaction
    open suspend fun clearPlaylist(id: Long) {
        deleteLinksOfPlaylist(id)
    }

    /**
     * 删除所有列表。危险操作，界面上必须二次确认。
     * 同样只删列表和关联记录，歌曲文件一个都不动。
     */
    @Transaction
    open suspend fun deleteAllPlaylists() {
        deleteAllLinks()
        deleteAllPlaylistRecords()
    }

    /** 按 position 重新编号为 0..n-1，保证删减后序号连续。 */
    @Transaction
    open suspend fun renumber(playlistId: Long) {
        linksOfPlaylist(playlistId).forEachIndexed { index, link ->
            if (link.position != index) {
                updateLinkPosition(playlistId, link.trackId, index)
            }
        }
    }
}
