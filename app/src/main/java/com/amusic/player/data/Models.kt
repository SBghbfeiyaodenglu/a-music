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

/**
 * 切换播放模式时，播放器里的**队列顺序**需不需要重建。
 *
 * 只有"倒序 ↔ 其它"会改变实际的播放顺序（倒序要把列表反过来）；
 * 顺序 / 单曲 / 列表 / 随机 的队列都是列表原顺序，只需要改播放器的
 * repeatMode / shuffleModeEnabled，**绝不能重建队列**。
 *
 * 为什么这条判断很重要：重建队列走的是 `setMediaItems + prepare()`，
 * 会把正在播放的这首歌重新装载一遍，听感上就是切换模式的那一下卡顿。
 * 用户 2026-11-21 反馈"调播放模式会让正在播的歌卡一下"，根因就是这里。
 */
fun PlayMode.requiresQueueRebuild(previous: PlayMode): Boolean =
    (this == PlayMode.REVERSE) != (previous == PlayMode.REVERSE)

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
    /**
     * 这个列表自动跟随的源文件夹（绝对路径）。
     * 空 = 不跟随（例如只手动导入过单曲的列表）。
     * 新导入时用户勾选的文件夹会记在这里；老列表在第一次同步时按"歌曲共同的父目录"推断。
     */
    val sourceFolders: List<String> = emptyList(),
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
 *
 * 第 0 档是原有最小号；前 7 档（0~6）的数值保持不变，老用户的设置看起来完全一样；
 * 后面新增 5 档更大的字号（7~11），主要是给平板/大屏用的（手机上选太大也能用，只是很大）。
 * 共 12 档。
 *
 * 歌词排版仍然是一行一句、纵向滚动，不做横向并排 —— 这里只改字号。
 */
object LyricsFontScale {

    const val MaxLevel = 11

    private val TextSp = intArrayOf(
        24, 26, 28, 30, 32, 34, 36, // 0~6：原有 7 档，数值不动
        40, 44, 48, 52, 56,          // 7~11：新增的大字号（平板用）
    )

    fun clamp(raw: Int): Int = raw.coerceIn(0, MaxLevel)

    /**
     * 歌词字号（档位 0~11 → 24~56sp）。
     *
     * **所有歌词行都用这一个字号**：高亮行不能放大（那会让它比别的行大一号，和"字号由
     * 菜单设置统一决定"冲突）。高亮只靠颜色（主色）和字重（加粗）区分，不改字号。
     */
    fun textSp(raw: Int): Int = TextSp[clamp(raw)]
}
