package com.amusic.player.data

import android.content.Context
import androidx.core.content.edit

/**
 * 应用级设置。用 SharedPreferences 就够了，不值得为它单独建表。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("a-music-settings", Context.MODE_PRIVATE)

    /** 歌词字号档位，0 是最小号（默认） */
    fun lyricsFontLevel(): Int =
        LyricsFontScale.clamp(prefs.getInt(KEY_LYRICS_FONT_LEVEL, 0))

    fun setLyricsFontLevel(level: Int) {
        prefs.edit { putInt(KEY_LYRICS_FONT_LEVEL, LyricsFontScale.clamp(level)) }
    }

    /**
     * 歌曲列表"自动居中"的安静时长（秒），默认 5 秒。
     *
     * 含义：用户在列表区域最后一次触摸之后，连续安静这么多秒，才把**正在播放的那一行**
     * 滚到屏幕中间。和歌词页会自动滚回当前歌词是同一个思路，但这里的宽限时间更长，
     * 因为列表页是拿来"翻找歌曲"的，滚得太勤会一直跟用户抢列表。
     * 期间任何触摸都会重新计时；用户不动它就不插手。
     *
     * 只接受 >= 1 的正整数：0 或负数等于每时每刻都在滚。
     */
    fun listAutoCenterSeconds(): Int =
        prefs.getInt(KEY_LIST_AUTO_CENTER_SECONDS, DefaultListAutoCenterSeconds)
            .coerceAtLeast(MinListAutoCenterSeconds)

    fun setListAutoCenterSeconds(seconds: Int) {
        prefs.edit {
            putInt(KEY_LIST_AUTO_CENTER_SECONDS, seconds.coerceAtLeast(MinListAutoCenterSeconds))
        }
    }

    /**
     * 歌曲名称来源。**固定用文件名**：界面上没有切换入口，这两项不接受修改，
     * 保留读取只是给将来放开留余地（默认值就是推荐值）。
     */
    fun titleFromMetadata(): Boolean = prefs.getBoolean(KEY_TITLE_FROM_METADATA, false)

    /**
     * 背景色调序号（没有封面的歌用它，换歌时递增）。
     *
     * 存下来是为了"接着上次往下轮"，不然每次打开应用都从第 1 套开始。
     * 数值只增不减，取用时由 PaletteTable 自己对 200 取模。
     */
    fun paletteIndex(): Int = prefs.getInt(KEY_PALETTE_INDEX, 0)

    fun setPaletteIndex(value: Int) {
        prefs.edit { putInt(KEY_PALETTE_INDEX, value) }
    }

    /**
     * 睡眠定时器的结束时刻（epoch 毫秒），0 表示没有定时器。
     *
     * 存"结束时刻"而不是"还剩多少分钟"：这样**关掉进程再打开倒计时还是准的**
     * （睡前设了 30 分钟，中途应用被系统回收，回来不该重新计时）。
     */
    fun sleepTimerEndAt(): Long = prefs.getLong(KEY_SLEEP_END_AT, 0L)

    fun setSleepTimerEndAt(endAtMillis: Long) {
        prefs.edit { putLong(KEY_SLEEP_END_AT, endAtMillis) }
    }

    companion object {
        /** 列表自动居中的默认延迟（秒） */
        const val DefaultListAutoCenterSeconds = 5

        /** 延迟下限：0 秒等于一直在滚、一直跟用户抢列表，所以最少 1 秒 */
        const val MinListAutoCenterSeconds = 1

        private const val KEY_LYRICS_FONT_LEVEL = "lyrics_font_level"
        private const val KEY_TITLE_FROM_METADATA = "title_from_metadata"
        private const val KEY_LIST_AUTO_CENTER_SECONDS = "list_auto_center_seconds"
        private const val KEY_SLEEP_END_AT = "sleep_timer_end_at"
        private const val KEY_PALETTE_INDEX = "palette_index"
    }
}
