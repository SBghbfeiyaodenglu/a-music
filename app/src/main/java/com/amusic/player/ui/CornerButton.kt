package com.amusic.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 页面角落的圆形按钮（列表页的「置顶 / 定位」、歌词页的「在线搜索歌词」共用）。
 *
 * 半透明底色 + 图标：压在内容上但不会把下面的文字遮得太死，
 * 也不会和"正在播放"的高亮抢注意力。
 *
 * 为什么歌词页的搜索入口用这种形式，而不是底部一整条文字条：
 * 底部播放栏（歌名 / 时间 / 控件）那一块**只能放歌曲信息**，
 * 用一整条文字条贴在播放栏上方的话，看起来就像"歌名被换成了那句提示"。
 */
@Composable
fun CornerButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 外面这层 48dp 的 Box 只是为了**触摸区**：Android 的最小触摸目标是 48dp，
    // 圆形本身保持 44dp 的观感不变（两者差 2dp，肉眼看不出来，手指感觉得到）
    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            tonalElevation = 3.dp,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
