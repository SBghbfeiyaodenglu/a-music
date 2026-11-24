package com.amusic.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amusic.player.data.PlayMode

/**
 * 播放模式选择（上拉框）。
 *
 * **设计语言和底部播放栏一致**：悬浮的圆角玻璃卡片 ——
 * 四角 20dp、1px 白描边、半透明黑底；遮罩调得很淡，好让玻璃把背后的模糊背景透出来。
 *
 * 内容上做了三处精简：
 *
 * ```
 * × 去掉顶部"播放模式"标题和那句总说明 —— 每个模式下面本来就有自己的说明，重复了
 * × 去掉每行左侧的对勾图标 —— 选中项改成**圆角胶囊**（底色 + 描边），一眼能认出来，
 *   也把左边那 32dp 的缩进还给文字
 * × 行距压紧，五项加拖动条一共约 300dp，最后一项不再被屏幕底边裁掉
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayModeSheet(
    current: PlayMode,
    onSelect: (PlayMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // ⚠ 弹窗的外壳必须**完全透明、不要描边**：
        // ModalBottomSheet 的 modifier 加在**整个弹窗容器（全屏）**上，不是加在卡片上 ——
        // 把 1px 白描边写在 modifier 里，屏幕上就会出现一圈"
        // 绕着整屏的白色圆角方框"（只在点模式按钮后出现，因为那时才构建这个弹窗）。
        // 卡片改成在**内容里**自己画（见下面的 Surface），描边就只围着卡片自己。
        shape = RectangleShape,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = null,
    ) {
        Surface(
            shape = shape,
            // 玻璃底比播放栏实得多：它正下方就是播放栏，太透会让两边文字重叠
            color = Color.Black.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                .fillMaxWidth(),
        ) {
            Column(
            Modifier
                .fillMaxWidth()
                // 屏高很矮时仍然能滚到最后一项（并且让开系统手势条）
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // 自己画拖动条：系统的 dragHandle 在透明外壳上会悬空，这里画在卡片里更整
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.35f)),
                )
            }

            PlayMode.entries.forEach { mode ->
                val selected = mode == current
                val pill = RoundedCornerShape(16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 行距压到最紧：再大的话五项加拖动条会顶到屏幕底边被裁掉
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .clip(pill)
                        .then(
                            if (selected) {
                                Modifier
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), pill)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(mode) }
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = MaterialTheme.typography.labelSmall.fontSize * 1.25f,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            }
        }
    }
}
