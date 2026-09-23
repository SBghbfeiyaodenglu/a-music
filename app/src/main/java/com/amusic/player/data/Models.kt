package com.amusic.player.data

/**
 * 播放模式。
 *
 * 关键区分（别搞混）：
 *   顺序 / 倒序 / 随机，这三种都是**在当前列表内部**循环，
 *   列表里的歌放完一轮就回到列表开头继续。
 *   而"列表循环"是**在各个列表之间**循环：当前列表的歌放完一轮后，
 *   自动切到下一个列表，在新列表里接着放，放完再进下一个列表，在列表之间轮转。
 *   只有一个列表时，"下一个列表"就是它自己，等于永远循环这一个列表。
 */
enum class PlayMode(
    val label: String,
    /** 上拉框里显示的一行说明，避免用户分不清"顺序循环"和"列表循环" */
    val description: String,
) {
    ORDER("顺序循环", "在当前列表内按顺序循环播放"),
    REVERSE("倒序循环", "在当前列表内按倒序循环播放"),
    SHUFFLE("随机循环", "在当前列表内随机播放，一轮内不重复"),
    LOOP_ONE("单曲循环", "始终重复播放当前这首歌"),
    LOOP_LIST("列表循环", "当前列表放完一轮后，自动切到下一个列表继续播放"),
}

/** 歌曲引用。只记录"指向哪个文件"，不复制文件本身。 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String = "",
    val path: String = "",
    val durationMs: Long = 0L,
    val album: String = "",
    val year: String = "",
    val genre: String = "",
) {
}

/** 列表 = 歌曲引用的有序集合。同一首歌可以同时属于多个列表。 */
data class Playlist(
    val id: Long,
    val name: String,
    val songs: List<Song>,
)

/** 一行歌词：时间戳 + 文本。所有歌词来源最终都归一成这个结构。 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/** 歌词偏移的调整步长：0.2 秒 */
const val LyricsOffsetStepMs = 200L

/**
 * 歌词字号档位。
 * 第 0 档是现有大小（最小号），往上 6 档逐级加大，共 7 档。
 */
object LyricsFontScale {

    const val MaxLevel = 6

    fun clamp(raw: Int): Int = raw.coerceIn(0, MaxLevel)

    /**
     * 歌词字号（档位 0~6 → 24~36sp）。
     *
     * **所有歌词行都用这一个字号**：高亮行不能放大（那会让它比别的行大一号，和"字号由
     * 菜单设置统一决定"冲突）。高亮只靠颜色（主色）和字重（加粗）区分，不改字号。
     */
    fun textSp(raw: Int): Int = 24 + clamp(raw) * 2

    /** 每行歌词的高度，要能容下两行放大后的文字 */
    fun rowHeightDp(raw: Int): Int = 56 + clamp(raw) * 6
}
