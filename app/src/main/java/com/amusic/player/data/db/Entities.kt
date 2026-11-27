package com.amusic.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 表结构。
 *
 * 核心约定：列表只保存对音频文件的"引用"，不复制文件。
 * track.path 是音频的绝对路径，列表与歌曲是多对多关系。
 */

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
    /**
     * 自动跟随的源文件夹，多个用换行分隔；空串 = 不跟随。
     * 用 TEXT 存路径列表，避免为"列表可能来自多个文件夹"再建一张表。
     */
    val sourceFolders: String = "",
    /**
     * 用户手动从列表里删掉、但文件还在源文件夹里的歌（换行分隔）。
     * 自动同步时跳过这些路径，否则删掉的歌 10 秒后又被文件夹同步加回来。
     */
    val excludedPaths: String = "",
)

@Entity(tableName = "track", indices = [Index(value = ["path"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val path: String,
    /** 文件名（含扩展名），显示时去掉扩展名 */
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    /** 标签里的年份和风格，取不到就是空串 */
    val year: String = "",
    val genre: String = "",
    val durationMs: Long,
    /** 下面两个和 path 一起用来判断"还是不是同一个文件" */
    val sizeBytes: Long,
    val lastModified: Long,
)

@Entity(
    tableName = "playlist_track",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: Long,
    /** 在列表中的顺序，即"导入顺序" */
    val position: Int,
)

@Entity(tableName = "player_state")
data class PlayerStateEntity(
    @PrimaryKey val id: Int = 0,
    val currentPlaylistId: Long?,
    val currentTrackId: Long?,
    val positionMs: Long,
    val playMode: String,
)

@Entity(tableName = "lyric_offset")
data class LyricOffsetEntity(
    @PrimaryKey val trackId: Long,
    val offsetMs: Long,
)
