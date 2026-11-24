package com.amusic.player.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 歌名走马灯（歌曲列表的行、播放栏第一行共用）。
 *
 * ## 按"名字有多长"自动选机制（三种情况）
 *
 * ```
 * 放得下、也不要求动        → 静静显示（普通行）
 * 放不下（含间隔已占满整行）→ 无缝循环滚动
 * 放得下、但要求动          → 左右轻摆（48dp、1.8 秒一个来回、缓入缓出）
 * ```
 *
 * 为什么短名不跟着"循环滚"（这是有意的设计）：
 *
 * ```
 * 循环滚 = 同一段文字画两份、一前一后，整段位移正好是一份「文字 + 间隔」。
 *   名字长到「文字 + 间隔 ≥ 行宽」时，任何时刻屏幕上只看得见其中一份，
 *   滚动是无缝的（循环点上两份位置完全重合，看不出"重来"），也永远不会出现空行。
 * 可名字很短时（「晴天」只有 100px，行宽 800px），同一屏会同时出现两遍「晴天」，
 *   像标签贴重了；而如果只画一份，滚出去之后那一行就空了。所以短名改用"轻摆"：
 *   一直有动感、永远不空、也不重复。
 * ```
 *
 * 为什么自己写而不用 `Modifier.basicMarquee`：那个只在内容超出容器时才动，
 * 而且循环方式是"滚完**跳回起点**"（观感上太突兀）。
 */
@Composable
internal fun MarqueeTitle(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    /** true = 放得下也要动（"正在播放"的两处：列表那一行 + 播放栏） */
    alwaysScroll: Boolean = false,
    /** 第一次开始动之前先停一下，让人有时间读开头 */
    initialDelayMillis: Int = 900,
    /** 循环滚动的速度（dp/秒） */
    velocityDpPerSecond: Int = 30,
) {
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val containerPx = with(density) { maxWidth.toPx() }
        val textPx = remember(text, style) {
            measurer.measure(text = text, style = style, maxLines = 1).size.width.toFloat()
        }
        // 两份内容之间的间隔：按行宽比例给（有上下限），长名滚完喘口气，短名也不会显得原地打转
        val gapDp = (maxWidth * GapFraction).coerceIn(MinGapDp, MaxGapDp)
        val gapPx = with(density) { gapDp.toPx() }

        val overflow = textPx > containerPx
        // 「文字 + 间隔」就能占满整行 → 用无缝循环；否则屏幕上会同时出现两遍同样的名字
        val loops = textPx + gapPx > containerPx

        when {
            // ① 放得下、也不需要动
            !overflow && !alwaysScroll ->
                PlainTitle(text, style, color, fontWeight)

            // ② 放不下 → 无缝循环滚动
            loops -> LoopTitle(
                text = text,
                style = style,
                color = color,
                fontWeight = fontWeight,
                textPx = textPx,
                gapDp = gapDp,
                speedPx = with(density) { velocityDpPerSecond.dp.toPx() },
                initialDelayMillis = initialDelayMillis,
            )

            // ③ 放得下但要求动 → 左右轻摆
            else -> {
                // 摆动的幅度 = **整段空余宽度**：往右跑到"右边框"（列表里就是时长/音符标记
                // 之前的那条边）再跑回来，把这块空间用满。
                // ⚠ 方向必须往**右**：文字从最左边开始画，左边没有余地，
                //   往左摆会把名字开头裁掉（实测"起"边缘一直贴在裁切线上）。
                val marginPx = with(density) { DriftMarginDp.toPx() }
                val travelPx = (containerPx - textPx - marginPx).coerceAtLeast(0f)
                // 距离越长走的时间越久（按固定速度算），否则跑满一行时像在飞
                val speedPx = with(density) { DriftSpeedDpPerSecond.dp.toPx() }
                val driftMs = (travelPx / speedPx * 1000f).roundToInt()
                    .coerceIn(MinDriftMillis, MaxDriftMillis)
                val transition = rememberInfiniteTransition(label = "drift")
                val shift by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = travelPx,
                    animationSpec = infiniteRepeatable(
                        animation = tween(driftMs, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,      // 来回走，不是跳回起点
                        initialStartOffset = StartOffset(initialDelayMillis),
                    ),
                    label = "driftShift",
                )
                Box(Modifier.offset { IntOffset(shift.roundToInt(), 0) }) {
                    PlainTitle(text, style, color, fontWeight)
                }
            }
        }
    }
}

/**
 * 无缝循环：同一段文字画两份、一前一后，整段位移正好是一份「文字 + 间隔」。
 *
 * 循环点上第二份刚好走到第一份的位置，两帧像素完全重合 —— 所以看不出接缝，
 * 也不会出现"滚完跳回起点"。任何时刻屏幕上都至少有一份文字的一部分可见。
 */
@Composable
private fun LoopTitle(
    text: String,
    style: TextStyle,
    color: Color,
    fontWeight: FontWeight?,
    textPx: Float,
    gapDp: Dp,
    speedPx: Float,
    initialDelayMillis: Int,
) {
    val tilePx = textPx + with(LocalDensity.current) { gapDp.toPx() }
    val durationMs = (tilePx / speedPx * 1000f).roundToInt().coerceAtLeast(600)
    val transition = rememberInfiniteTransition(label = "marquee")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = -tilePx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(initialDelayMillis),
        ),
        label = "marqueeShift",
    )
    Row(
        Modifier
            .offset { IntOffset(shift.roundToInt(), 0) }
            // ⚠ 两份加起来比行宽还宽，必须放开约束量一遍，否则第二份会被裁成 0 宽
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE),
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
    ) {
        PlainTitle(text, style, color, fontWeight)
        Spacer(Modifier.width(gapDp))
        PlainTitle(text, style, color, fontWeight)
    }
}

/** 静态歌名：单行、不换行（走马灯的三种模式都用它画字，保证字号/颜色一致） */
@Composable
private fun PlainTitle(
    text: String,
    style: TextStyle,
    color: Color,
    fontWeight: FontWeight?,
) {
    Text(
        text = text,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
    )
}

/** 两份内容之间的间隔 = 行宽 × 这个比例（再按下面的上下限夹一下） */
private const val GapFraction = 0.25f
private val MinGapDp = 32.dp
private val MaxGapDp = 110.dp

/** 短名轻摆：离右边框留这么多，别让字贴着边框 */
private val DriftMarginDp = 4.dp

/** 轻摆的速度（dp/秒）—— 距离越长走的时间越久，观感才是"匀速跑" */
private const val DriftSpeedDpPerSecond = 60

/** 轻摆单程时间的上下限（避免极短/极长时太快或太慢） */
private const val MinDriftMillis = 900
private const val MaxDriftMillis = 5000
