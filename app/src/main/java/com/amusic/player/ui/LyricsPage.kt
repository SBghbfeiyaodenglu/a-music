package com.amusic.player.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amusic.player.data.LrcParser
import com.amusic.player.data.LyricLine
import com.amusic.player.data.LyricsFontScale
import com.amusic.player.data.Song
import com.amusic.player.data.lyrics.ResolvedLyrics

/**
 * 提前多少毫秒把下一句滚上去。
 *
 * 踩点滚动的问题：用户正在看马上要唱的那句，那句却在时间戳到达的瞬间被硬拉走，
 * 眼睛得跟着移动一次，很别扭。提前 0.5 秒滚，等时间戳真的到达时那一行已经在位置上，
 * 只需要换高亮，视觉上就是平滑上移一行。
 */
private const val ScrollLeadMs = 500L

/** 当前行在视口中的基准位置（0.40 = 距顶部 40%） */
private const val AnchorFraction = 0.40f

/**
 * 歌词页。
 *
 * 页面顶部不放歌名/歌手/来源信息——底部播放栏已经有歌名了，再显示一遍纯属占地方。
 *
 * 关于滚动位置与时间戳的准确性：**时间戳到歌词行的对应关系是纯计算（二分查找行号），
 * 和界面怎么排版完全无关**。多行显示、字号大小只影响那一行画多高、画在哪，
 * 不会影响"现在是第几句"。所以这里放开行数限制让长句换行完整显示，
 * 高亮判断依然按时间戳精确走。
 */
@Composable
fun LyricsPage(
    song: Song?,
    resolved: ResolvedLyrics,
    positionProvider: () -> Long,
    offsetMs: Long,
    fontLevel: Int,
    /** 缺"所有文件访问"权限时，空状态里要提示一句——否则用户会以为歌词文件不存在，
     *  其实是有 .lrc 但读不到 */
    allFilesAccessMissing: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
    when {
        song == null -> Centered(
            title = "无歌曲播放",
            subtitle = "无歌词内容",
        )

        resolved.lines.isEmpty() -> Centered(
            title = "无 LRC 和内嵌歌词",
            subtitle = if (allFilesAccessMissing) {
                "如果这首歌本来有同名 .lrc 文件，那就是缺少「所有文件访问」权限、读不到它。"
            } else {
                "把同名 .lrc 文件放到歌曲所在目录，或给音频文件写入内嵌歌词后即可显示"
            },
            // 缺权限时给一句：否则用户会以为这首歌本来就没歌词
            // （"在线搜索歌词"已经常驻在页面下方，这里不用再放按钮）
            action = if (allFilesAccessMissing) {
                {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "如果这首歌本来有同名 .lrc，那是缺权限读不到",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRequestAllFilesAccess) {
                            Text("去打开「所有文件访问」")
                        }
                    }
                }
            } else {
                null
            },
        )

        else -> LyricsBody(
            lines = resolved.lines,
            hasTimeline = resolved.hasTimeline,
            positionProvider = positionProvider,
            offsetMs = offsetMs,
            fontLevel = fontLevel,
            modifier = Modifier.fillMaxSize(),
        )
    }

    }
    }
}

@Composable
private fun LyricsBody(
    lines: List<LyricLine>,
    hasTimeline: Boolean,
    positionProvider: () -> Long,
    offsetMs: Long,
    fontLevel: Int,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 高亮与滚动**用同一个索引**：都按"时间戳前 0.5 秒"切换。
    //
    // 高亮和滚动必须**同步**：如果滚动用"提前 0.5 秒"的索引而高亮按真实时间戳，
    // 看起来就是滚动先动、高亮半秒后才跟上。这里两者共用同一个索引，
    // 滚到预设位置的同时那一行就点亮。
    //
    // 用 derivedStateOf 包一层：播放位置每 200ms 都在变，但行号几秒才变一次，
    // 这样只有行号真的变了才重组歌词列表——否则整片歌词会以 5Hz 反复重绘，
    // 夜间低亮度下 OLED 上能看到明显的规律闪烁。
    val activeIndex by remember(lines, hasTimeline, offsetMs) {
        derivedStateOf {
            if (!hasTimeline) {
                -1
            } else {
                LrcParser.currentIndex(lines, (positionProvider() + ScrollLeadMs) - offsetMs)
            }
        }
    }
    val highlightedIndex = activeIndex
    val scrollIndex = activeIndex

    // 视口高度同样用 derivedStateOf：直接在合成里读 layoutInfo 会在滚动时每帧触发重组
    val viewportHeight by remember {
        derivedStateOf { listState.layoutInfo.viewportSize.height }
    }
    val rowMinHeight = LyricsFontScale.rowHeightDp(fontLevel).dp
    val textSp = LyricsFontScale.textSp(fontLevel)

    // 只在目标行变化或视口尺寸变化时滚动，不做定时整页刷新。
    // scrollToItem 定位的是"该行顶部"，和这一行实际有多高无关，
    // 所以放开行数限制（长句换行）也不会让定位失准。
    LaunchedEffect(scrollIndex, viewportHeight, lines) {
        if (scrollIndex >= 0 && viewportHeight > 0) {
            val anchor = (viewportHeight * AnchorFraction).toInt()
            listState.animateScrollToItem(scrollIndex, scrollOffset = -anchor)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 200.dp),
    ) {
        // 内嵌的纯文本歌词没有时间轴，滚不动也高亮不了，说明一句免得用户以为坏了
        if (!hasTimeline) {
            item {
                Text(
                    text = "静态歌词 · 这个文件里没有时间轴",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }
        }

        itemsIndexed(lines) { index, line ->
            val isCurrent = hasTimeline && index == highlightedIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 用最小高度而不是固定高度：字号调到最大时，长句换行后这行需要变高
                    .heightIn(min = rowMinHeight)
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = line.text.ifBlank { "♪" },
                    fontSize = textSp.sp,
                    lineHeight = (textSp * 1.35f).sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    // 只有"正在唱的那句"做成圆角胶囊：
                    // 歌词本来就是一大片文字，每行都加卡片会切得零碎；只把当前行托起来，
                    // 既好看又真的有用 —— 扫一眼就知道唱到哪了。
                    // 胶囊跟着文字宽度走（不占满整行），所以长句换行时它包住整块。
                    modifier = Modifier
                        .let { base ->
                            if (isCurrent) {
                                base
                                    .background(
                                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
                                        RoundedCornerShape(22.dp),
                                    )
                                    .then(
                                        Modifier.border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                            RoundedCornerShape(22.dp),
                                        ),
                                    )
                                    .padding(horizontal = 18.dp, vertical = 6.dp)
                            } else {
                                base
                            }
                        },
                    // 未高亮的歌词也保持接近纯白，偏灰就不够显眼；
                    // 已唱过的行稍暗一点，用来区分"唱到哪了"，但仍要明显亮于普通灰白。
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        hasTimeline && index < highlightedIndex ->
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.95f)
                    },
                    textAlign = TextAlign.Center,
                    // 不限行数、不省略：长句换行完整显示，绝不截成省略号
                )
            }
        }
    }
}

@Composable
private fun Centered(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}
