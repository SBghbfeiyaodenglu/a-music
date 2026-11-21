package com.amusic.player.data.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

object Permissions {

    /** 读取本地音频的运行时权限：13+ 是 READ_MEDIA_AUDIO，12 及以下是 READ_EXTERNAL_STORAGE */
    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

    /** 通知权限：没有它通知栏和锁屏看不到播放控制（播放本身不受影响） */
    fun hasNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * 全文件访问权限。
     *
     * 只有拿到它才能读到音频旁边的同名 .lrc —— 分区存储下 .lrc 属于非媒体文件，
     * 用路径读不到、MediaStore 也读不到内容。
     * 没有它应用仍能导入和播放，只是歌词会退化为"暂无歌词"。
     */
    // ⚠ Android 10（API 29）是个例外：那代系统还没有「所有文件访问」这个开关，
    //    而 targetSdk 34+ 下分区存储又读不到歌曲旁边的 .lrc —— 也就是这台系统上
    //    「同名 .lrc」这条路走不通（内嵌歌词不受影响）。这里按「有权限」返回，
    //    免得把用户送去一个根本没有开关的设置页。
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /**
     * 打开"所有文件访问权限"设置页。不同机型支持的 Action 不一样，逐个试。
     *
     * 这里**故意不用 `resolveActivity` 判断**：Android 11+ 的包可见性限制会让它对系统设置页
     * 也返回 null，于是明明能打开的页面被跳过、退化成更粗的列表页。
     * 直接启动、失败（ActivityNotFoundException）再退下一个更可靠。
     */
    /** @return 是否成功打开了某个系统页面；false 时调用方应给用户一句手动指引 */
    fun openAllFilesSettings(context: Context): Boolean {
        val packageUri = "package:${context.packageName}".toUri()
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
                add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            if (opened) return true
        }
        // 一个都打不开（有些 ROM 把这些页面藏了）：返回 false，让调用方告诉用户手动去哪开
        return false
    }
}
