package com.amusic.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 睡眠定时器的可选时长（分钟）。做成固定几档而不是让用户输入：
 * 睡前场景要的是"点一下就走"，不是精确到分钟。
 */
private val SleepTimerPresets = listOf(10, 20, 30, 45, 60, 90)

/**
 * 睡眠定时器。
 *
 * 到点**暂停播放**（不是停止，也不清队列）：第二天打开还在原位，接着播就行。
 *
 * 倒计时按"结束时刻"算并存进设置，所以**应用被系统回收再打开，倒计时依然准**；
 * 关掉进程不等于用户取消定时器，这一点和"进程被杀不等于暂停播放"是同一个原则。
 *
 * @param remainingMs 当前剩余毫秒；<= 0 表示没有定时器在跑
 * @param onPick 选一个时长（分钟）
 * @param onCancelTimer 关掉正在跑的定时器
 */
@Composable
fun SleepTimerDialog(
    remainingMs: Long,
    onPick: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = remainingMs > 0L
    // 悬浮卡片样式（和播放栏、播放模式上拉框同一套）
    GlassDialog(onDismiss = onDismiss) {
            Column(Modifier.padding(20.dp)) {
                Text("睡眠定时器", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (active) {
                        "还有 ${formatRemaining(remainingMs)} 暂停播放"
                    } else {
                        "到时间后暂停播放，不会清空列表，也不会停止播放器"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))

                // 两列排布，六档一眼看完
                SleepTimerPresets.chunked(2).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { minutes ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onPick(minutes) },
                            ) {
                                Text(
                                    text = "$minutes 分钟",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                        // 奇数个时补一个空位，保证两列对齐
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (active) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCancelTimer) {
                            Text("关闭定时器", color = Color(0xFFE5534B), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                }
            }
        
    }
}

/** 剩余时间：一小时以上显示 `1:05:20`，否则 `12:34` */
fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
