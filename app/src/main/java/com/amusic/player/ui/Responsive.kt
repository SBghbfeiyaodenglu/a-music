package com.amusic.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 当前是不是宽屏（平板、横屏等）。
 *
 * 判定门槛用 Material 的 600dp：普通手机竖屏约 360~430dp，拯救者 Y700 这类平板约 640dp。
 * **只用来决定排版怎么利用横向空间**（同一行显示、加大间距），不改变任何功能。
 * 歌词页不用这个：歌词始终一行一句、纵向滚动，不做横向并排。
 */
@Composable
fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 600
