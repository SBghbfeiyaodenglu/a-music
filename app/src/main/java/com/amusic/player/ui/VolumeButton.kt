package com.amusic.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull

/** 按住多久开始连发（太短会把"手抖"误判成长按） */
private const val VolumeHoldDelayMs = 400L

/** 连发间隔（150ms 一格：够快，又不会一按就冲到顶、来不及松手） */
private const val VolumeRepeatEveryMs = 150L

/**
 * 音量 −/+ 按钮。
 *
 * 和普通 IconButton 的区别（IconButton 的手感是"调节要等两秒"，所以不能直接用）：
 *
 * ```
 * 按下就调：不等抬手 —— IconButton 是"抬起才触发"，一次快按也要等手指离开才生效；
 *           而且它一次点击只走一格，想大幅调整得反复按。
 * 按住连发：按住不放每 150ms 走一格（先等 400ms，避免手抖误判），
 *           所以从 0 拉到满只需要按一下不放。
 * ```
 *
 * 触摸区 56dp（和播放按钮一个量级）：
 * 改成自定义手势之后触摸区只剩内容大小（约 42dp 高），点在图标上下边缘会漏掉 ——
 * 表现就是"有时候点了没反应"。Android 的标准触摸目标是 48dp，这里显式兜到 56dp。
 * 按下时图标变亮给个即时反馈（音量数值提示由播放栏上方的 VolumeHintPill 负责）。
 */
@Composable
internal fun VolumeButton(increase: Boolean, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    // ⚠ 手势节点用 pointerInput(Unit) + rememberUpdatedState：
    //   直接 pointerInput(onClick) 的话，界面每次重组都会换 key → 手势被取消，
    //   正在按住连发时会断掉，甚至留下"按下"的残留状态
    val currentClick by rememberUpdatedState(onClick)
    val tint = if (pressed) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    }
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 56.dp, minHeight = 56.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    currentClick()                  // 按下立刻生效，不等抬手
                    var heldMs = 0L
                    while (true) {
                        // 有事件就来（松手/滑动），没事件说明手指一直按着不动
                        val event = withTimeoutOrNull(VolumeRepeatEveryMs) { awaitPointerEvent() }
                        if (event != null && event.changes.none { it.pressed }) break
                        if (event == null) {
                            heldMs += VolumeRepeatEveryMs
                            if (heldMs >= VolumeHoldDelayMs) currentClick()
                        }
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = if (increase) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = if (increase) "音量加" else "音量减",
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = if (increase) "+" else "−",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
        }
    }
}
